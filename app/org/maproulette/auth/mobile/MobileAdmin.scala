package org.maproulette.auth.mobile

import anorm._
import java.sql.Connection
import java.time.Instant
import javax.inject.{Inject, Provider, Singleton}
import org.maproulette.framework.model.User
import org.maproulette.framework.service.UserService
import org.maproulette.permissions.Permission
import play.api.db.Database
import play.api.libs.json.{JsValue, Json}

/**
  * Who may hold or use `mobile:admin`: MapRoulette super-users only. Checked when the grant is
  * created, on code exchange and refresh, and on every admin request, so a demotion takes effect
  * at once.
  */
@com.google.inject.ImplementedBy(classOf[MobileAdminGate])
trait MobileAdminCheck {
  def isAdmin(user: User): Boolean
  def isAdmin(userId: Long): Boolean
}

object MobileAdminCheck {

  /** Grants admin to nobody (unit tests that don't involve admin). */
  val Nobody: MobileAdminCheck = new MobileAdminCheck {
    def isAdmin(user: User): Boolean   = false
    def isAdmin(userId: Long): Boolean = false
  }
}

// Providers: UserService reads the database when it is built, which must wait for evolutions.
@Singleton
class MobileAdminGate @Inject() (users: Provider[UserService], permission: Provider[Permission])
    extends MobileAdminCheck {
  def isAdmin(user: User): Boolean =
    user.id > 0 && !user.guest && permission.get().isSuperUser(user)
  def isAdmin(userId: Long): Boolean = users.get().retrieve(userId).exists(isAdmin)
}

case class MobileAuditEntry(
    id: Long,
    actorUserId: Long,
    action: String,
    target: String,
    before: Option[JsValue],
    after: Option[JsValue],
    createdAt: Instant
)

object MobileAuditEntry {
  implicit val writes: play.api.libs.json.OWrites[MobileAuditEntry] = Json.writes[MobileAuditEntry]
}

/** A partial client update from `PATCH /api/v2/mobile-admin/clients/:id`. */
case class MobileClientPatch(
    name: Option[String] = None,
    redirectUris: Option[Set[String]] = None,
    scopes: Option[Set[String]] = None,
    enabled: Option[Boolean] = None
) {
  def applyTo(client: MobileClient): MobileClient = client.copy(
    name = name.getOrElse(client.name),
    redirectUris = redirectUris.getOrElse(client.redirectUris),
    scopes = scopes.getOrElse(client.scopes),
    enabled = enabled.getOrElse(client.enabled)
  )
}

/** Admin writes and the audit log (evolution 131). Every write and its audit entry share one transaction. */
@Singleton
class MobileAdminRepository @Inject() (db: Database, registry: MobileClientRegistry) {
  def clientJson(client: MobileClient): JsValue = Json.obj(
    "id"           -> client.id,
    "name"         -> client.name,
    "redirectUris" -> client.redirectUris.toSeq.sorted,
    "scopes"       -> MobileScopes.format(client.scopes).split(" ").toSeq,
    "enabled"      -> client.enabled
  )

  private def record(
      actor: Long,
      action: String,
      target: String,
      before: Option[JsValue],
      after: Option[JsValue]
  )(implicit c: Connection): Unit = {
    SQL("""INSERT INTO mobile_admin_audit(actor_user_id,action,target,before,after)
      VALUES ({actor},{action},{target},CAST({before} AS jsonb),CAST({after} AS jsonb))""")
      .on(
        "actor"  -> actor,
        "action" -> action,
        "target" -> target,
        "before" -> before.map(Json.stringify),
        "after"  -> after.map(Json.stringify)
      )
      .executeUpdate()
    ()
  }

  def record(actor: Long, action: String, target: String, after: Option[JsValue]): Unit =
    db.withConnection(implicit c => record(actor, action, target, None, after))

  // Admin reads and writes see the table only after this process has seeded it from config.
  private def seeded(): Unit = { registry.all; () }

  /** Clients with their audit columns, for the admin list. */
  def listClients: Seq[JsValue] = {
    seeded()
    db.withConnection { implicit c =>
      SQL("SELECT * FROM mobile_oauth_clients ORDER BY id").as(RowParser { row =>
        Success(
          Json.obj(
            "id"           -> row[String]("id"),
            "name"         -> row[String]("name"),
            "redirectUris" -> row[Array[String]]("redirect_uris").toSeq.sorted,
            "scopes"       -> row[String]("scopes").split(" ").toSeq,
            "enabled"      -> row[Boolean]("enabled"),
            "source"       -> (if (row[Option[Long]]("created_by").isEmpty) "config" else "admin"),
            "createdBy"    -> row[Option[Long]]("created_by"),
            "updatedBy"    -> row[Option[Long]]("updated_by"),
            "createdAt"    -> row[java.util.Date]("created_at").toInstant,
            "updatedAt"    -> row[java.util.Date]("updated_at").toInstant
          )
        )
      }.*)
    }
  }

  /** False when the id is taken. */
  def createClient(client: MobileClient, actor: Long): Boolean = {
    seeded()
    val created = db.withTransaction { implicit c =>
      val inserted = MobileClientRows.insert(client, actor)
      if (inserted) record(actor, "client.create", client.id, None, Some(clientJson(client)))
      inserted
    }
    registry.invalidate()
    created
  }

  /**
    * Applies the patch; with `revokeGrants` also revokes every active grant family of the client
    * (and deletes their OSM tokens). Returns the updated client and the number of revoked families,
    * or None when the client does not exist.
    */
  def updateClient(
      id: String,
      patch: MobileClientPatch,
      actor: Long,
      revokeGrants: Boolean
  ): Option[(MobileClient, Int)] = {
    seeded()
    val updated = db.withTransaction { implicit c =>
      MobileClientRows.lock(id).map { before =>
        val after = patch.applyTo(before)
        MobileClientRows.update(after, actor)
        val revoked =
          if (!revokeGrants) Seq.empty
          else {
            val families = SQL("""UPDATE mobile_oauth_families SET revoked_at={now}
              WHERE client_id={id} AND revoked_at IS NULL RETURNING family_id""")
              .on("now" -> java.sql.Timestamp.from(Instant.now()), "id" -> id)
              .as(SqlParser.str("family_id").*)
            if (families.nonEmpty)
              SQL(
                "DELETE FROM mobile_osm_tokens WHERE grant_family_id = ANY(CAST({ids} AS text[]))"
              ).on("ids" -> families.toArray)
                .executeUpdate()
            families
          }
        val afterJson = clientJson(after).as[play.api.libs.json.JsObject] ++
          (if (revokeGrants) Json.obj("revokedGrantFamilies" -> revoked.size) else Json.obj())
        record(actor, "client.update", id, Some(clientJson(before)), Some(afterJson))
        (after, revoked.size)
      }
    }
    registry.invalidate()
    updated
  }

  /** Newest first. */
  def audit(limit: Int, offset: Int): (Seq[MobileAuditEntry], Long) =
    db.withConnection { implicit c =>
      val total = SQL("SELECT COUNT(*) FROM mobile_admin_audit").as(SqlParser.scalar[Long].single)
      val entries =
        SQL("""SELECT id,actor_user_id,action,target,before::text AS b,after::text AS a,
        created_at FROM mobile_admin_audit ORDER BY id DESC LIMIT {limit} OFFSET {offset}""")
          .on("limit" -> limit, "offset" -> offset)
          .as(RowParser { row =>
            Success(
              MobileAuditEntry(
                row[Long]("id"),
                row[Long]("actor_user_id"),
                row[String]("action"),
                row[String]("target"),
                row[Option[String]]("b").map(Json.parse),
                row[Option[String]]("a").map(Json.parse),
                row[java.util.Date]("created_at").toInstant
              )
            )
          }.*)
      (entries, total)
    }
}

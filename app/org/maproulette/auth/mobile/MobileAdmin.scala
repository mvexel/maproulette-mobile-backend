package org.maproulette.auth.mobile

import anorm._
import java.sql.Connection
import java.time.Instant
import javax.inject.{Inject, Provider, Singleton}
import org.maproulette.framework.model.{Grant, User}
import org.maproulette.framework.psql.Paging
import org.maproulette.framework.service.{ProjectService, UserService}
import org.maproulette.permissions.Permission
import play.api.db.Database
import play.api.libs.json.{JsValue, Json}

/**
  * What an admin app grant may do (D8). Super-users with `mobile:admin` may do everything; listed
  * organizers with `mobile:organize` may run campaigns in the projects where they hold Write or
  * better, and read the write switch. Checked on every request, so a demotion takes effect on the
  * next one.
  */
sealed trait MobileCapability

object MobileCapability {

  /** Clients, the audit log, setting the write switch, organizers, creating projects. */
  case object Operate extends MobileCapability

  /** The campaign and project lists; an organizer's are filtered to their projects. */
  case object ListCampaigns extends MobileCapability

  /** Reading the write switch. */
  case object SeeWritePolicy extends MobileCapability

  /** Running the campaigns of one project: turning it on or off, live page, results, queue. */
  case class Campaigns(projectId: Long) extends MobileCapability
}

/**
  * Who may hold or use the admin app's grants: `mobile:admin` is for MapRoulette super-users only,
  * `mobile:organize` for users on the organizer list (`mobile_memberships`, evolution 145). Checked
  * when the grant is created, on code exchange and refresh, and on every request.
  */
@com.google.inject.ImplementedBy(classOf[MobileAdminGate])
trait MobileAdminCheck {
  def isAdmin(user: User): Boolean
  def isAdmin(userId: Long): Boolean

  /** On the organizer list. */
  def isOrganizer(userId: Long): Boolean = false

  /** The projects whose campaigns this organizer may run. */
  def organizerProjects(user: User): Set[Long] = Set.empty

  /** The project of a challenge that is not deleted. */
  def projectOf(challengeId: Long): Option[Long] = None

  /** Whether the user may be given, or keep using, a grant of this scope. */
  final def mayHold(scope: String, userId: Long): Boolean =
    if (MobileScopes.isAdmin(scope)) isAdmin(userId)
    else if (MobileScopes.isOrganize(scope)) isOrganizer(userId)
    else true

  /** Whether this grant may do this now. Never for app grants. */
  final def can(user: User, scopes: Set[String], capability: MobileCapability): Boolean =
    if (MobileScopes.isAdmin(scopes)) isAdmin(user)
    else if (MobileScopes.isOrganize(scopes))
      user.id > 0 && !user.guest && isOrganizer(user.id) && (capability match {
        case MobileCapability.Operate            => false
        case MobileCapability.ListCampaigns      => true
        case MobileCapability.SeeWritePolicy     => true
        case MobileCapability.Campaigns(project) => organizerProjects(user).contains(project)
      })
    else false

  /** [[can]] for routes about one challenge: its project's campaigns. */
  final def canChallenge(user: User, scopes: Set[String], challengeId: Long): Boolean =
    if (MobileScopes.isAdmin(scopes)) isAdmin(user)
    else projectOf(challengeId).exists(p => can(user, scopes, MobileCapability.Campaigns(p)))
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
class MobileAdminGate @Inject() (
    users: Provider[UserService],
    permission: Provider[Permission],
    projects: Provider[ProjectService],
    db: Database
) extends MobileAdminCheck {
  def isAdmin(user: User): Boolean =
    user.id > 0 && !user.guest && permission.get().isSuperUser(user)
  def isAdmin(userId: Long): Boolean = users.get().retrieve(userId).exists(isAdmin)

  override def isOrganizer(userId: Long): Boolean = MobileOrganizers.listed(db, userId)

  /**
    * Write or better, through the user's own grants or their teams (upstream `effectiveRole`),
    * on projects upstream counts as managed. Not their Home project: campaigns run in projects a
    * super-user set up.
    */
  override def organizerProjects(user: User): Set[Long] = {
    val fresh = users.get().retrieve(user.id).getOrElse(user)
    val home  = s"Home_${fresh.osmProfile.id}"
    projects
      .get()
      .getManagedProjects(fresh, Paging(MobileOrganizers.MaxProjects))
      .filter(p => !p.deleted && p.name != home)
      .filter(p => permission.get().effectiveRole(p, fresh).exists(_ <= Grant.ROLE_WRITE_ACCESS))
      .map(_.id)
      .toSet
  }

  override def projectOf(challengeId: Long): Option[Long] = db.withConnection { implicit c =>
    SQL"SELECT parent_id FROM challenges WHERE id = $challengeId AND NOT deleted"
      .as(SqlParser.long("parent_id").singleOpt)
  }
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

  private[mobile] def record(
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

package org.maproulette.auth.mobile

import anorm.SqlParser._
import anorm._
import javax.inject.{Inject, Singleton}
import org.maproulette.data.Actions
import java.sql.Connection
import org.maproulette.framework.model.{Grant, GrantTarget, Grantee}
import play.api.db.Database
import play.api.libs.json._

/** A user the Organizers screen can add: signed in to MapRoulette at least once. */
case class MobileOrganizerCandidate(userId: Long, osmId: Long, name: String)

object MobileOrganizers {
  val Role        = "organizer"
  val MaxProjects = 500

  def listed(db: Database, userId: Long): Boolean = db.withConnection { implicit c =>
    SQL"SELECT EXISTS(SELECT 1 FROM mobile_memberships WHERE user_id = $userId AND role = $Role)"
      .as(scalar[Boolean].single)
  }

  /** "admin" and "write", the upstream project roles an organizer can be given. */
  def role(name: String): Option[Int] = name match {
    case "admin" => Some(Grant.ROLE_ADMIN)
    case "write" => Some(Grant.ROLE_WRITE_ACCESS)
    case _       => None
  }

  def roleName(role: Int): String = if (role <= Grant.ROLE_ADMIN) "admin" else "write"
}

/**
  * The organizer list (evolution 145) for the super-user Organizers screen. Every change, the
  * upstream project grant it makes and its audit entry share one transaction: the grant rows are
  * written here as upstream's GrantRepository writes them, and the caller clears upstream's user
  * and project caches after commit. The projects shown are the person's own upstream grants of
  * Write or better; their Home project is left out.
  */
@Singleton
class MobileOrganizerRepository @Inject() (db: Database, audit: MobileAdminRepository) {
  import MobileOrganizers._

  /** By OSM username, ignoring case: the name upstream shows, not a pattern. */
  def candidate(osmUsername: String): Option[MobileOrganizerCandidate] =
    db.withConnection { implicit c =>
      SQL"""SELECT id, osm_id, name FROM users WHERE lower(name) = lower($osmUsername)
            ORDER BY id LIMIT 1"""
        .as((long("id") ~ long("osm_id") ~ str("name")).map {
          case id ~ osmId ~ name => MobileOrganizerCandidate(id, osmId, name)
        }.singleOpt)
    }

  def candidate(userId: Long): Option[MobileOrganizerCandidate] =
    db.withConnection { implicit c =>
      SQL"SELECT id, osm_id, name FROM users WHERE id = $userId"
        .as((long("id") ~ long("osm_id") ~ str("name")).map {
          case id ~ osmId ~ name => MobileOrganizerCandidate(id, osmId, name)
        }.singleOpt)
    }

  private def projects(userIds: Seq[Long]): Map[Long, Seq[JsObject]] =
    if (userIds.isEmpty) Map.empty
    else
      db.withConnection { implicit c =>
        SQL"""SELECT g.grantee_id AS user_id, p.id, p.name, p.display_name, MIN(g.role) AS role
              FROM grants g
              JOIN projects p ON p.id = g.object_id
              JOIN users u ON u.id = g.grantee_id
              WHERE g.grantee_type = ${Actions.ITEM_TYPE_USER}
                AND g.object_type = ${Actions.ITEM_TYPE_PROJECT}
                AND g.grantee_id IN ($userIds) AND g.role <= ${Grant.ROLE_WRITE_ACCESS}
                AND NOT p.deleted AND p.name <> ('Home_' || u.osm_id)
              GROUP BY g.grantee_id, p.id ORDER BY lower(p.name), p.id"""
          .as(
            (long("user_id") ~ long("id") ~ str("name") ~ get[Option[String]]("display_name") ~
              int("role")).map {
              case userId ~ id ~ name ~ display ~ role =>
                userId -> Json.obj(
                  "id"          -> id,
                  "name"        -> name,
                  "displayName" -> display.filter(_.nonEmpty),
                  "role"        -> roleName(role)
                )
            }.*
          )
          .groupMap(_._1)(_._2)
      }

  private def rows(userId: Option[Long]): Seq[JsObject] = {
    val all: Boolean = userId.isEmpty
    val only: Long   = userId.getOrElse(0L)
    val listed = db.withConnection { implicit c =>
      SQL"""SELECT m.user_id, u.osm_id, u.name, m.granted_by, m.created_at
            FROM mobile_memberships m JOIN users u ON u.id = m.user_id
            WHERE m.role = $Role AND ($all OR m.user_id = $only)
            ORDER BY lower(u.name), m.user_id"""
        .as(
          (long("user_id") ~ long("osm_id") ~ str("name") ~ long("granted_by") ~
            get[java.util.Date]("created_at")).*
        )
    }
    val held = projects(listed.map { case id ~ _ ~ _ ~ _ ~ _ => id })
    listed.map {
      case id ~ osmId ~ name ~ grantedBy ~ created =>
        val own: Seq[JsObject] = held.getOrElse(id, Nil)
        Json.obj(
          "userId"    -> id,
          "osmId"     -> osmId,
          "name"      -> name,
          "grantedBy" -> grantedBy,
          "createdAt" -> created.toInstant,
          "projects"  -> own
        )
    }
  }

  def list: Seq[JsObject] = rows(None)

  def organizer(userId: Long): Option[JsObject] = rows(Some(userId)).headOption

  // The person's own project roles; the project row is locked so two changes can't both leave
  // it without an administrator.
  private def roles(userId: Long, projectId: Long)(implicit c: Connection): Seq[Int] = {
    SQL"SELECT id FROM projects WHERE id = $projectId FOR UPDATE".as(scalar[Long].singleOpt)
    SQL"""SELECT role FROM grants WHERE grantee_type = ${Actions.ITEM_TYPE_USER}
          AND grantee_id = $userId AND object_type = ${Actions.ITEM_TYPE_PROJECT}
          AND object_id = $projectId""".as(scalar[Int].*)
  }

  /** Upstream's rule: a project keeps at least one administrator who is a person. */
  private def lastAdmin(userId: Long, projectId: Long, held: Seq[Int])(implicit c: Connection) =
    held.exists(_ <= Grant.ROLE_ADMIN) &&
      !SQL"""SELECT EXISTS(SELECT 1 FROM grants WHERE grantee_type = ${Actions.ITEM_TYPE_USER}
              AND grantee_id <> $userId AND object_type = ${Actions.ITEM_TYPE_PROJECT}
              AND object_id = $projectId AND role <= ${Grant.ROLE_ADMIN})"""
        .as(scalar[Boolean].single)

  private def dropRoles(userId: Long, projectId: Long)(implicit c: Connection): Unit = {
    SQL"""DELETE FROM grants WHERE grantee_type = ${Actions.ITEM_TYPE_USER}
          AND grantee_id = $userId AND object_type = ${Actions.ITEM_TYPE_PROJECT}
          AND object_id = $projectId""".executeUpdate()
    ()
  }

  /**
    * Sets the person's role on the project (replacing any other role they held there, as
    * upstream's `addUserToProject` with `clear`), lists them (no change if listed already) and
    * audits it. Left("last_project_admin") when that would leave the project without an
    * administrator.
    */
  def add(
      person: MobileOrganizerCandidate,
      projectId: Long,
      role: Int,
      actor: Long
  ): Either[String, Unit] =
    db.withTransaction { implicit c =>
      val held = roles(person.userId, projectId)
      if (role > Grant.ROLE_ADMIN && lastAdmin(person.userId, projectId, held))
        Left("last_project_admin")
      else {
        dropRoles(person.userId, projectId)
        val grant =
          Grant(-1, "", Grantee.user(person.userId), role, GrantTarget.project(projectId))
        SQL"""INSERT INTO grants (name, grantee_id, grantee_type, role, object_id, object_type)
              VALUES (${grant.description()}, ${person.userId}, ${Actions.ITEM_TYPE_USER},
                $role, $projectId, ${Actions.ITEM_TYPE_PROJECT})""".executeUpdate()
        SQL"""INSERT INTO mobile_memberships(user_id, role, granted_by)
              VALUES (${person.userId}, $Role, $actor) ON CONFLICT DO NOTHING""".executeUpdate()
        audit.record(
          actor,
          "organizer.add",
          s"user:${person.userId}",
          None,
          Some(Json.obj("projectId" -> projectId, "role" -> roleName(role), "name" -> person.name))
        )
        Right(())
      }
    }

  /** Removes every role the person holds on the project, audited; refused for its last administrator. */
  def removeProject(userId: Long, projectId: Long, actor: Long): Either[String, Unit] =
    db.withTransaction { implicit c =>
      val held = roles(userId, projectId)
      if (lastAdmin(userId, projectId, held)) Left("last_project_admin")
      else {
        dropRoles(userId, projectId)
        audit.record(
          actor,
          "organizer.project.remove",
          s"user:$userId",
          None,
          Some(Json.obj("projectId" -> projectId))
        )
        Right(())
      }
    }

  /** False when the user wasn't listed. Their upstream project grants stay. */
  def remove(userId: Long, actor: Long): Boolean = {
    val before = organizer(userId)
    db.withTransaction { implicit c =>
      val removed =
        SQL"DELETE FROM mobile_memberships WHERE user_id = $userId AND role = $Role"
          .executeUpdate() == 1
      if (removed) audit.record(actor, "organizer.remove", s"user:$userId", before, None)
      removed
    }
  }
}

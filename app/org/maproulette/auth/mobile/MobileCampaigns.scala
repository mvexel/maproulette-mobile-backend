package org.maproulette.auth.mobile

import anorm.SqlParser._
import anorm._
import javax.inject.Inject
import org.joda.time.DateTime
import play.api.db.Database
import play.api.libs.json._

/**
  * Reads for the admin app's Campaigns list and project picker (`/api/v2/mobile-admin/projects` and
  * `/campaigns`). A campaign is a choice challenge (cooperative type 3). Progress uses the same rule
  * as the app and the live page: Checked is status 1, 2 or 5, or a live pending guest answer;
  * Needs another look is status 6 without one. Deleted (4) and disabled (9) tasks are not counted.
  */
class MobileCampaignRepository @Inject() (db: Database) {
  private val Limit = 500
  // The admin app's Publish tag, as MobileChoicePendingService.PublishedTag.
  private val Tag = "mobile-survey-v1"

  private val published =
    """EXISTS(SELECT 1 FROM tags_on_challenges tc JOIN tags tg ON tg.id = tc.tag_id
      WHERE tc.challenge_id = c.id AND tg.name = {tag})"""

  /** Projects that are not deleted, archived or virtual, by name, with their campaign counts. */
  def projects: Seq[JsObject] = db.withConnection { implicit c =>
    SQL(s"""SELECT p.id, p.name, p.display_name, p.enabled,
          COUNT(c.id) AS campaigns,
          COUNT(c.id) FILTER (WHERE c.enabled AND $published) AS published
        FROM projects p
        LEFT JOIN challenges c ON c.parent_id = p.id AND c.cooperative_type = 3 AND NOT c.deleted
        WHERE NOT p.deleted AND NOT COALESCE(p.is_archived, false) AND NOT COALESCE(p.is_virtual, false)
        GROUP BY p.id ORDER BY lower(p.name), p.id LIMIT {limit}""")
      .on("tag" -> Tag, "limit" -> Limit)
      .as(
        (long("id") ~ str("name") ~ get[Option[String]]("display_name") ~ bool("enabled") ~
          long("campaigns") ~ long("published")).map {
          case id ~ name ~ display ~ enabled ~ campaigns ~ published =>
            Json.obj(
              "id"          -> id,
              "name"        -> name,
              "displayName" -> display.filter(_.nonEmpty),
              "enabled"     -> enabled,
              "campaigns"   -> campaigns,
              "published"   -> published
            )
        }.*
      )
  }

  /** The project, as in [[projects]], or None if unknown, deleted, archived or virtual. */
  def project(id: Long): Option[JsObject] =
    projects.find(p => (p \ "id").as[Long] == id) // at most Limit rows; one admin, rare calls

  def projectNameTaken(name: String): Boolean = db.withConnection { implicit c =>
    SQL"SELECT EXISTS(SELECT 1 FROM projects WHERE lower(name) = lower($name))"
      .as(scalar[Boolean].single)
  }

  /** Choice challenges, most recently changed first, with their project and progress. */
  def campaigns: Seq[JsObject] = db.withConnection { implicit c =>
    SQL(s"""SELECT c.id, c.name, c.enabled, c.modified, p.id AS project_id, p.name AS project_name,
          p.display_name, p.enabled AS project_enabled, $published AS published,
          COALESCE(s.total, 0) AS total, COALESCE(s.checked, 0) AS checked,
          COALESCE(s.needs_look, 0) AS needs_look
        FROM challenges c
        JOIN projects p ON p.id = c.parent_id
        LEFT JOIN LATERAL (
          SELECT COUNT(*) AS total,
            COUNT(*) FILTER (WHERE t.status IN (1, 2, 5) OR h.held) AS checked,
            COUNT(*) FILTER (WHERE t.status = 6 AND NOT h.held) AS needs_look
          FROM tasks t
          CROSS JOIN LATERAL (SELECT EXISTS(SELECT 1 FROM choice_pending cp
            WHERE cp.task_id = t.id AND cp.state = 'pending' AND cp.hold_until > NOW()) AS held) h
          WHERE t.parent_id = c.id AND t.status NOT IN (4, 9)
        ) s ON true
        WHERE c.cooperative_type = 3 AND NOT c.deleted AND NOT p.deleted
        ORDER BY c.modified DESC, c.id DESC LIMIT {limit}""")
      .on("tag" -> Tag, "limit" -> Limit)
      .as(
        (long("id") ~ str("name") ~ bool("enabled") ~ get[DateTime]("modified") ~
          long("project_id") ~ str("project_name") ~ get[Option[String]]("display_name") ~
          bool("project_enabled") ~ bool("published") ~ long("total") ~ long("checked") ~
          long("needs_look")).map {
          case id ~ name ~ enabled ~ modified ~ projectId ~ projectName ~ display ~
                projectEnabled ~ published ~ total ~ checked ~ needsLook =>
            Json.obj(
              "id"      -> id,
              "name"    -> name,
              "enabled" -> enabled,
              // Tagged mobile-survey-v1 by Publish.
              "published" -> published,
              // What the app shows: enabled, published and the project on.
              "listed"   -> (enabled && published && projectEnabled),
              "modified" -> modified.toString,
              "project" -> Json.obj(
                "id"          -> projectId,
                "name"        -> projectName,
                "displayName" -> display.filter(_.nonEmpty),
                "enabled"     -> projectEnabled
              ),
              "tasks" -> Json.obj("total" -> total, "checked" -> checked, "needsLook" -> needsLook)
            )
        }.*
      )
  }
}

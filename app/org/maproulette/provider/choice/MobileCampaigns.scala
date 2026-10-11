package org.maproulette.provider.choice

import anorm._
import javax.inject.{Inject, Singleton}
import org.maproulette.framework.model.Project
import play.api.db.Database
import play.api.libs.json._

/** A project as the admin's picker shows it: how many choice campaigns, and how many are listed. */
case class AdminProject(
    id: Long,
    name: String,
    displayName: Option[String],
    enabled: Boolean,
    campaigns: Int,
    listed: Int
)

object AdminProject {
  implicit val writes: OWrites[AdminProject] = Json.writes[AdminProject]
}

/**
  * A choice campaign (challenge `cooperative_type` 3) for the admin's Campaigns list. `inApp`:
  * `listed` (enabled, tagged `mobile-survey-v1`, project on), `project_off` (would be listed but
  * the project is off), `not_published` (enabled without the tag) or `draft` (challenge disabled).
  * Progress uses the app's rule: checked is Fixed, Not an issue or Already fixed; Too hard (6) needs
  * another look; deleted and disabled tasks don't count.
  */
case class AdminCampaign(
    id: Long,
    name: String,
    projectId: Long,
    projectName: String,
    projectEnabled: Boolean,
    enabled: Boolean,
    published: Boolean,
    inApp: String,
    tasks: Int,
    checked: Int,
    needsAnotherLook: Int,
    updatedAt: String
)

object AdminCampaign {
  implicit val writes: OWrites[AdminCampaign] = Json.writes[AdminCampaign]

  def inApp(enabled: Boolean, published: Boolean, projectEnabled: Boolean): String =
    if (!enabled) "draft"
    else if (!published) "not_published"
    else if (!projectEnabled) "project_off"
    else "listed"
}

/** Reads for the admin's project picker and Campaigns list (admin design pass §2 and §3). */
@Singleton
class MobileCampaignsRepository @Inject() (db: Database) {

  /** The admin's "publish for mobile" tag, as in MobileChoicePendingService. */
  private val PublishedTag = "mobile-survey-v1"
  private val Choice       = 3

  private val campaign = for {
    id             <- SqlParser.long("id")
    name           <- SqlParser.str("name")
    projectId      <- SqlParser.long("project_id")
    projectName    <- SqlParser.str("project_name")
    projectEnabled <- SqlParser.bool("project_enabled")
    enabled        <- SqlParser.bool("enabled")
    published      <- SqlParser.bool("published")
    tasks          <- SqlParser.int("tasks")
    checked        <- SqlParser.int("checked")
    look           <- SqlParser.int("look")
    updated        <- SqlParser.str("updated_at")
  } yield AdminCampaign(
    id,
    name,
    projectId,
    projectName,
    projectEnabled,
    enabled,
    published,
    AdminCampaign.inApp(enabled, published, projectEnabled),
    tasks,
    checked,
    look,
    updated
  )

  private val select =
    s"""SELECT c.id, c.name, p.id AS project_id, COALESCE(NULLIF(p.display_name, ''), p.name)
          AS project_name, p.enabled AS project_enabled, c.enabled,
        EXISTS(SELECT 1 FROM tags_on_challenges tc JOIN tags t ON t.id = tc.tag_id
          WHERE tc.challenge_id = c.id AND t.name = '$PublishedTag') AS published,
        (SELECT count(*) FROM tasks k WHERE k.parent_id = c.id AND k.status NOT IN (4, 9))::int
          AS tasks,
        (SELECT count(*) FROM tasks k WHERE k.parent_id = c.id AND k.status IN (1, 2, 5))::int
          AS checked,
        (SELECT count(*) FROM tasks k WHERE k.parent_id = c.id AND k.status = 6)::int AS look,
        btrim(to_json(c.modified)::text, '"') AS updated_at
      FROM challenges c JOIN projects p ON p.id = c.parent_id
      WHERE c.cooperative_type = $Choice AND NOT COALESCE(c.deleted, false) AND NOT COALESCE(p.deleted, false)"""

  /** Every choice campaign, most recently changed first. */
  def campaigns(): Seq[AdminCampaign] = db.withConnection { implicit c =>
    SQL(s"$select ORDER BY c.modified DESC, c.id DESC").as(campaign.*)
  }

  def campaign(id: Long): Option[AdminCampaign] = db.withConnection { implicit c =>
    SQL(s"$select AND c.id = {id}").on("id" -> id).as(campaign.singleOpt)
  }

  /** The project's campaigns that are listed in the app, or would be with the project on. */
  def publishedIn(projectId: Long): Seq[AdminCampaign] =
    campaigns().filter(c => c.projectId == projectId && c.enabled && c.published)

  /** Projects that aren't virtual or deleted, by name, with their choice campaign counts. */
  def projects(): Seq[AdminProject] = db.withConnection { implicit c =>
    SQL(s"""SELECT p.id, p.name, NULLIF(p.display_name, '') AS display_name, p.enabled,
        (SELECT count(*) FROM challenges c WHERE c.parent_id = p.id AND NOT COALESCE(c.deleted, false)
          AND c.cooperative_type = $Choice)::int AS campaigns,
        (SELECT count(*) FROM challenges c WHERE c.parent_id = p.id AND NOT COALESCE(c.deleted, false)
          AND c.cooperative_type = $Choice AND c.enabled AND p.enabled AND EXISTS(
            SELECT 1 FROM tags_on_challenges tc JOIN tags t ON t.id = tc.tag_id
            WHERE tc.challenge_id = c.id AND t.name = '$PublishedTag'))::int AS listed
      FROM projects p WHERE NOT COALESCE(p.deleted, false) AND NOT COALESCE(p.is_virtual, false)
      ORDER BY lower(COALESCE(NULLIF(p.display_name, ''), p.name)), p.id""").as(
      (SqlParser.long("id") ~ SqlParser.str("name") ~ SqlParser.get[Option[String]](
        "display_name"
      ) ~ SqlParser.bool("enabled") ~ SqlParser.int("campaigns") ~ SqlParser.int("listed")).map {
        case id ~ name ~ display ~ enabled ~ campaigns ~ listed =>
          AdminProject(id, name, display, enabled, campaigns, listed)
      }.*
    )
  }

  def project(id: Long): Option[AdminProject] = projects().find(_.id == id)

  def projectNameTaken(name: String): Boolean = db.withConnection { implicit c =>
    SQL("SELECT EXISTS(SELECT 1 FROM projects WHERE lower(name) = lower({n}))")
      .on("n" -> name)
      .as(SqlParser.scalar[Boolean].single)
  }
}

object MobileCampaigns {

  /** `{"name", "displayName"}`: name 1–100 characters without spaces, display name 1–100. */
  def parseProject(body: JsValue): Either[Seq[String], (String, String)] = body match {
    case o: JsObject if o.keys == Set("name", "displayName") =>
      val name    = (o \ "name").asOpt[String].map(_.trim).getOrElse("")
      val display = (o \ "displayName").asOpt[String].map(_.trim).getOrElse("")
      val errors = Seq(
        Option.when(!name.matches("[A-Za-z0-9_.-]{1,100}"))(
          "name: 1–100 letters, digits, '.', '_' or '-'"
        ),
        Option.when(display.isEmpty || display.length > 100)("displayName: 1–100 characters")
      ).flatten
      if (errors.isEmpty) Right(name -> display) else Left(errors)
    case _ => Left(Seq("expected only name and displayName"))
  }

  def newProject(name: String, displayName: String, owner: Long): Project =
    Project(-1, owner, name, displayName = Some(displayName), enabled = true)
}

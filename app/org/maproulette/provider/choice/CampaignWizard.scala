package org.maproulette.provider.choice

import anorm._
import anorm.SqlParser.{bool, long}
import java.time.LocalDate
import javax.inject.{Inject, Singleton}
import org.maproulette.auth.mobile.MobileAdminRepository
import org.maproulette.framework.model._
import org.maproulette.framework.service.TagService
import org.maproulette.models.dal.DALManager
import org.maproulette.provider.ChallengeProvider
import play.api.db.Database
import play.api.libs.json._
import scala.collection.mutable.ListBuffer
import scala.concurrent.{ExecutionContext, Future}

/** What `POST /api/v2/mobile-admin/wizard/campaigns` asks for (wizard step 4). */
case class WizardCampaign(
    set: String,
    version: Int,
    area: SearchArea,
    place: String,
    leaveOut: Set[String],
    name: String,
    description: String,
    eventDate: Option[LocalDate],
    projectId: Long
)

object WizardCampaign {
  private val Element = "^(node|way)/[1-9][0-9]{0,15}$"
  private def clean(value: String, max: Int): Boolean =
    value.trim == value && value.nonEmpty && value.codePointCount(0, value.length) <= max &&
      !value.exists(c => Character.isISOControl(c) && c != '\n')

  def parse(body: JsValue): Either[Seq[String], WizardCampaign] = body match {
    case value: JsObject =>
      val allowed =
        Set("set", "version", "area", "leaveOut", "name", "description", "eventDate", "projectId")
      val unknown = value.keys.diff(allowed).toSeq.sorted.map(k => s"unknown field $k")
      val set     = (value \ "set").asOpt[String].toRight("set is required")
      val version = (value \ "version").asOpt[Int].filter(_ > 0).toRight("version is required")
      val area = (value \ "area").asOpt[JsObject].toRight(List("area is required")).flatMap { a =>
        val keys = a.keys
          .diff(Set("lat", "lon", "radius", "place"))
          .toList
          .sorted
          .map(k => s"unknown field area.$k")
        val circle = SearchArea.parse(
          (a \ "lat").asOpt[BigDecimal].map(_.toString),
          (a \ "lon").asOpt[BigDecimal].map(_.toString),
          (a \ "radius").asOpt[Int].map(_.toString)
        )
        val place =
          (a \ "place").asOpt[String].filter(clean(_, 200)).toRight("area.place is required")
        val errors = keys ++ circle.left.toSeq.flatten ++ place.left.toSeq
        if (errors.nonEmpty) Left(errors) else Right((circle.toOption.get, place.toOption.get))
      }
      val leaveOut = (value \ "leaveOut").toOption match {
        case None => Right(Set.empty[String])
        case Some(list) =>
          list
            .asOpt[List[String]]
            .filter(ids => ids.size <= 5000 && ids.forall(_.matches(Element)))
            .map(_.toSet)
            .toRight("leaveOut must list node/<id> or way/<id>")
      }
      val name =
        (value \ "name").asOpt[String].filter(clean(_, 120)).toRight("name: 1 to 120 characters")
      val description = (value \ "description")
        .asOpt[String]
        .filter(clean(_, 2000))
        .toRight("description: 1 to 2000 characters")
      val eventDate = (value \ "eventDate").toOption match {
        case None | Some(JsNull) => Right(None)
        case Some(JsString(d)) if d.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}") =>
          scala.util
            .Try(LocalDate.parse(d))
            .toOption
            .map(Some(_))
            .toRight("eventDate must be YYYY-MM-DD")
        case _ => Left("eventDate must be YYYY-MM-DD")
      }
      val project = (value \ "projectId").asOpt[Long].filter(_ > 0).toRight("projectId is required")
      val errors = unknown ++ set.left.toSeq ++ version.left.toSeq ++ area.left.toSeq.flatten ++
        leaveOut.left.toSeq ++ name.left.toSeq ++ description.left.toSeq ++ eventDate.left.toSeq ++
        project.left.toSeq
      if (errors.nonEmpty) Left(errors)
      else
        Right(
          WizardCampaign(
            set.toOption.get,
            version.toOption.get,
            area.toOption.get._1,
            area.toOption.get._2,
            leaveOut.toOption.get,
            name.toOption.get,
            description.toOption.get,
            eventDate.toOption.get,
            project.toOption.get
          )
        )
    case _ => Left(Seq("expected a JSON object"))
  }
}

/** A set's open features in an area, with the counts behind them. */
case class OpenFeatures(all: Long, open: Long, features: List[SetFeature]) {
  def complete: Long = math.max(0, all - open)
  def truncated: Boolean =
    open > CampaignWizard.MaxFeatures || features.size > CampaignWizard.MaxFeatures
  def shown: List[SetFeature] = features.take(CampaignWizard.MaxFeatures)
}

/**
  * The campaign wizard's server side (campaign-wizard.md §4, Juno's wizard-api-draft.md): counts
  * and open features from Overpass, and one-call create and publish. Create reads OSM again and
  * makes the tasks itself, so a campaign never holds a feature the set wouldn't pick.
  */
@Singleton
class CampaignWizard @Inject() (
    val library: QuestionSetLibrary,
    overpass: WizardOverpass,
    db: Database,
    dal: DALManager,
    provider: ChallengeProvider,
    tagService: TagService,
    audit: MobileAdminRepository
)(implicit ec: ExecutionContext) {
  import CampaignWizard._

  /** Every set's count, or all `unavailable` when Overpass didn't answer. */
  def counts(area: SearchArea, userId: Long): Future[Either[WizardQueryFailure, JsObject]] = {
    val sets = library.sets
    overpass.elements(OverpassQuery.counts(sets, round(area)), userId).map {
      case Left(f @ WizardQueryFailure("rate_limited")) => Left(f)
      case other =>
        val totals = other.toOption.map(_.value.flatMap(OverpassQuery.total).toList)
        Right(Json.obj("counts" -> sets.zipWithIndex.map {
          case (set, i) =>
            totals.filter(_.size == 2 * sets.size) match {
              case Some(t) =>
                Json.obj(
                  "set"      -> set.id,
                  "version"  -> set.version,
                  "open"     -> t(2 * i),
                  "complete" -> math.max(0, t(2 * i + 1) - t(2 * i))
                )
              case None =>
                Json.obj("set" -> set.id, "version" -> set.version, "unavailable" -> true)
            }
        }))
    }
  }

  def features(
      set: QuestionSet,
      area: SearchArea,
      userId: Long,
      cached: Boolean = true
  ): Future[Either[WizardQueryFailure, OpenFeatures]] =
    overpass
      .elements(OverpassQuery.features(set, round(area), MaxFeatures + 1), userId, cached)
      .map(_.flatMap { elements =>
        elements.value.flatMap(OverpassQuery.total).toList match {
          case all :: open :: _ =>
            // Re-checked here: only what the set would pick, nearest-first is the admin's job.
            val found =
              elements.value.flatMap(OverpassQuery.feature).filter(f => set.isOpen(f.kind, f.tags))
            Right(OpenFeatures(all, open, found.toList.distinctBy(_.element)))
          case _ => Left(WizardQueryFailure("osm_unavailable"))
        }
      })

  /**
    * Creates the campaign as a draft in the project: a disabled challenge with one task per open
    * feature not left out. Left(code) for 422 `nothing_to_check`.
    */
  def create(
      user: User,
      input: WizardCampaign,
      set: QuestionSet,
      open: OpenFeatures
  ): Either[String, (Long, Int)] = {
    val lines = open.shown.filterNot(f => input.leaveOut.contains(f.element)).flatMap(set.taskLine)
    if (lines.isEmpty) Left("nothing_to_check")
    else {
      val challenge = dal.challenge.insert(
        Challenge(
          -1,
          input.name,
          null,
          null,
          description = Some(input.description),
          general = ChallengeGeneral(
            user.osmProfile.id,
            input.projectId,
            set.instruction,
            enabled = false,
            checkinComment = changesetComment(input.name, input.place, input.eventDate),
            checkinSource = "survey",
            requiresLocal = true
          ),
          creation = ChallengeCreation(),
          priority = ChallengePriority(),
          extra = ChallengeExtra()
        ),
        user
      )
      val rejected = ListBuffer[String]()
      val created = lines.count { line =>
        val errors = ListBuffer[String]()
        val task   = provider.createTaskFromJson(user, challenge, line, Some(errors))
        rejected ++= errors
        task.isDefined && errors.isEmpty
      }
      val leftOut = open.shown.count(f => input.leaveOut.contains(f.element))
      db.withConnection { implicit c =>
        SQL"""INSERT INTO mobile_campaign_sources (challenge_id, set_id, set_version, place, lat, lon,
                radius, event_date, left_out, created_by)
              VALUES (${challenge.id}, ${set.id}, ${set.version}, ${input.place}, ${input.area.lat},
                ${input.area.lon}, ${input.area.radius}, ${input.eventDate}, $leftOut, ${user.id})"""
          .executeInsert(SqlParser.scalar[Long].singleOpt)
      }
      audit.record(
        user.id,
        "campaign.create",
        s"challenge:${challenge.id}",
        Some(
          Json.obj(
            "projectId" -> input.projectId,
            "set"       -> set.id,
            "version"   -> set.version,
            "place"     -> input.place,
            "radius"    -> input.area.radius,
            "tasks"     -> created,
            "leftOut"   -> leftOut,
            "rejected"  -> rejected.size
          )
        )
      )
      Right((challenge.id, created))
    }
  }

  /**
    * Lists the campaign in the app: enabled and tagged as Publish in the admin does. Right(listed),
    * false when its project is off; Left(code) for `not_found` and `nothing_to_check`.
    */
  def publish(user: User, challengeId: Long): Either[String, Boolean] = {
    val state = db.withConnection { implicit c =>
      SQL"""SELECT p.enabled AS project_enabled,
              (SELECT COUNT(*) FROM tasks t WHERE t.parent_id = c.id) AS tasks
            FROM challenges c JOIN projects p ON p.id = c.parent_id
            WHERE c.id = $challengeId AND c.cooperative_type = ${ChoiceWork.CooperativeType}
              AND NOT c.deleted AND NOT p.deleted"""
        .as((bool("project_enabled") ~ long("tasks")).map { case e ~ t => (e, t) }.singleOpt)
    }
    state match {
      case None          => Left("not_found")
      case Some((_, 0L)) => Left("nothing_to_check")
      case Some((listed, _)) =>
        dal.challenge.update(Json.obj("enabled" -> true), user)(challengeId)
        val ids = tagService
          .updateTagList(List(Tag(-1, PublishedTag)), User.superUser)
          .map(_.id)
        dal.challenge.updateItemTags(challengeId, ids, User.superUser)
        audit.record(
          user.id,
          "campaign.publish",
          s"challenge:$challengeId",
          Some(Json.obj("listed" -> listed))
        )
        Right(listed)
    }
  }
}

object CampaignWizard {
  val MaxFeatures = 2000

  /** The admin's Publish tag (MobileChoicePendingService.PublishedTag, MobileCampaigns). */
  val PublishedTag = "mobile-survey-v1"

  /** "#StreetTallyWoodbineFoodHall": as the admin's hashtag() (src/wizard/words.ts), at most 40 characters. */
  def hashtag(place: String): String = {
    val plain = java.text.Normalizer
      .normalize(place, java.text.Normalizer.Form.NFKD)
      .replaceAll("[\\u0300-\\u036f]", "")
    val camel =
      "[A-Za-z0-9]+".r.findAllIn(plain).map(w => w.take(1).toUpperCase + w.drop(1)).mkString
    s"#StreetTally$camel".take(40)
  }

  /**
    * The changeset comment Iris signed off (admin #30, words.ts changesetComment), so mappers see
    * one string in the admin, on the review page and in OSM.
    */
  def changesetComment(name: String, place: String, eventDate: Option[LocalDate]): String = {
    val day = eventDate.map(d =>
      ", " + d.format(
        java.time.format.DateTimeFormatter.ofPattern("d MMMM yyyy", java.util.Locale.UK)
      )
    )
    s"From a Street Tally survey for $name${day.getOrElse("")}. Checked by me. #StreetTally ${hashtag(place)}"
  }

  /** Centres snap to about 50 m, so nearby searches share Overpass answers and the create step. */
  def round(area: SearchArea): SearchArea =
    area.copy(
      lat = math.round(area.lat * 2000) / 2000.0,
      lon = math.round(area.lon * 2000) / 2000.0
    )
}

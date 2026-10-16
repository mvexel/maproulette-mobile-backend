package org.maproulette.provider.choice

import anorm._
import java.security.SecureRandom
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Base64
import javax.inject.{Inject, Singleton}
import org.maproulette.framework.model.Task
import play.api.db.Database
import play.api.libs.json._

/** A campaign's live progress page (evolution 141): link-only, reached by its token. */
case class LivePage(
    challengeId: Long,
    token: String,
    enabled: Boolean,
    showAnswers: Boolean,
    eventStart: Option[Instant],
    endedAt: Option[Instant],
    updatedAt: Instant
) {

  /** For the admin app, which builds the page's link from the token. */
  def adminJson: JsObject = Json.obj(
    "challengeId" -> challengeId,
    "token"       -> token,
    "enabled"     -> enabled,
    "showAnswers" -> showAnswers,
    "eventStart"  -> eventStart,
    "endedAt"     -> endedAt,
    "updatedAt"   -> updatedAt
  )

  /** For the audit log: the settings without the token, which is the link itself. */
  def auditJson: JsObject = adminJson - "token"
}

/** A settings change from `PUT /api/v2/mobile-admin/challenges/:id/live`; None keeps the value. */
case class LivePageUpdate(
    enabled: Option[Boolean] = None,
    showAnswers: Option[Boolean] = None,
    eventStart: Option[Option[Instant]] = None,
    ended: Option[Boolean] = None
)

/**
  * One task as the live page needs it. Never a user, changeset or guest: those are not read, so
  * the public response cannot carry them (design/live-progress.md §5).
  */
case class LiveStopRow(
    taskId: Long,
    lon: Double,
    lat: Double,
    status: Int,
    name: Option[String],
    ref: Option[String],
    at: Option[Instant],
    stale: Boolean,
    answers: Map[String, String],
    outcome: Option[String],
    distanceM: Option[Int],
    payload: Option[JsValue],
    pendingAt: Option[Instant],
    pendingOutcome: Option[String]
)

@Singleton
class LivePageRepository @Inject() (db: Database) {
  private val random = new SecureRandom()

  /** 32 random bytes, base64url without padding: 43 characters. */
  def newToken(): String = {
    val bytes = new Array[Byte](32)
    random.nextBytes(bytes)
    Base64.getUrlEncoder.withoutPadding.encodeToString(bytes)
  }

  private val page = for {
    challenge <- SqlParser.long("challenge_id")
    token     <- SqlParser.str("token")
    enabled   <- SqlParser.bool("enabled")
    answers   <- SqlParser.bool("show_answers")
    start     <- SqlParser.get[Option[java.util.Date]]("event_start")
    ended     <- SqlParser.get[Option[java.util.Date]]("ended_at")
    updated   <- SqlParser.get[java.util.Date]("updated_at")
  } yield LivePage(
    challenge,
    token,
    enabled,
    answers,
    start.map(_.toInstant),
    ended.map(_.toInstant),
    updated.toInstant
  )

  private val columns =
    "challenge_id, token, enabled, show_answers, event_start, ended_at, updated_at"

  def byToken(token: String): Option[LivePage] = db.withConnection { implicit c =>
    SQL(s"SELECT $columns FROM mobile_live_pages WHERE token = {token}")
      .on("token" -> token)
      .as(page.singleOpt)
  }

  def byChallenge(challengeId: Long): Option[LivePage] = db.withConnection { implicit c =>
    SQL(s"SELECT $columns FROM mobile_live_pages WHERE challenge_id = {id}")
      .on("id" -> challengeId)
      .as(page.singleOpt)
  }

  def challengeExists(challengeId: Long): Boolean = db.withConnection { implicit c =>
    SQL"SELECT EXISTS(SELECT 1 FROM challenges WHERE id = $challengeId)"
      .as(SqlParser.scalar[Boolean].single)
  }

  /** Creates the page on first use (off until enabled, answers shown, live) and applies `update`. */
  def save(challengeId: Long, update: LivePageUpdate): LivePage = db.withTransaction { implicit c =>
    SQL"""INSERT INTO mobile_live_pages (challenge_id, token) VALUES ($challengeId, ${newToken()})
            ON CONFLICT (challenge_id) DO NOTHING""".execute()
    val current =
      SQL(s"SELECT $columns FROM mobile_live_pages WHERE challenge_id = {id} FOR UPDATE")
        .on("id" -> challengeId)
        .as(page.single)
    val start = update.eventStart.getOrElse(current.eventStart).map(java.util.Date.from)
    val ended = update.ended match {
      case Some(true)  => Some(current.endedAt.getOrElse(Instant.now()))
      case Some(false) => None
      case None        => current.endedAt
    }
    val enabled: Boolean                = update.enabled.getOrElse(current.enabled)
    val answers: Boolean                = update.showAnswers.getOrElse(current.showAnswers)
    val endedAt: Option[java.util.Date] = ended.map(java.util.Date.from)
    SQL"""UPDATE mobile_live_pages SET
              enabled = $enabled, show_answers = $answers,
              event_start = $start, ended_at = $endedAt,
              updated_at = NOW()
            WHERE challenge_id = $challengeId""".executeUpdate()
    SQL(s"SELECT $columns FROM mobile_live_pages WHERE challenge_id = {id}")
      .on("id" -> challengeId)
      .as(page.single)
  }

  /** A new token: the old link stops working. None when the campaign has no page. */
  def rotate(challengeId: Long): Option[LivePage] = db.withConnection { implicit c =>
    SQL"""UPDATE mobile_live_pages SET token = ${newToken()}, updated_at = NOW()
          WHERE challenge_id = $challengeId""".executeUpdate()
    SQL(s"SELECT $columns FROM mobile_live_pages WHERE challenge_id = {id}")
      .on("id" -> challengeId)
      .as(page.singleOpt)
  }

  /** The campaign's name and changeset comment, for the header and the footer's hashtag. */
  def campaign(challengeId: Long): Option[(String, Option[String])] = db.withConnection {
    implicit c =>
      SQL"SELECT name, checkin_comment FROM challenges WHERE id = $challengeId"
        .as((SqlParser.str("name") ~ SqlParser.get[Option[String]]("checkin_comment")).map {
          case name ~ comment => (name, comment)
        }.singleOpt)
  }

  private val stop = for {
    id       <- SqlParser.long("id")
    lon      <- SqlParser.double("lon")
    lat      <- SqlParser.double("lat")
    status   <- SqlParser.int("status")
    name     <- SqlParser.get[Option[String]]("feature_name")
    ref      <- SqlParser.get[Option[String]]("feature_ref")
    at       <- SqlParser.get[Option[java.util.Date]]("at")
    stale    <- SqlParser.bool("stale")
    answers  <- SqlParser.get[Option[String]]("answers")
    result   <- SqlParser.get[Option[String]]("result")
    payload  <- SqlParser.get[Option[String]]("payload")
    pAt      <- SqlParser.get[Option[java.util.Date]]("pending_at")
    pOutcome <- SqlParser.get[Option[String]]("pending_outcome")
  } yield {
    val resultJson = result.map(Json.parse)
    LiveStopRow(
      id,
      lon,
      lat,
      status,
      name.filter(_.trim.nonEmpty),
      ref.filter(_.trim.nonEmpty),
      at.map(_.toInstant),
      stale,
      answers.flatMap(Json.parse(_).asOpt[Map[String, String]]).getOrElse(Map.empty),
      resultJson.flatMap(j => (j \ "outcome").asOpt[String]),
      resultJson.flatMap(j => (j \ "distanceM").asOpt[Int]),
      payload.map(Json.parse),
      pAt.map(_.toInstant),
      pOutcome
    )
  }

  /**
    * Every task of the campaign with a point. The latest done submission gives the answers; a live
    * pending guest answer (the same rule as excludePending) gives only its time and outcome id.
    */
  def stops(challengeId: Long): Seq[LiveStopRow] = db.withConnection { implicit c =>
    SQL"""SELECT t.id, t.status, ST_X(t.location) AS lon, ST_Y(t.location) AS lat,
                t.geojson #>> '{features,0,properties,name}' AS feature_name,
                t.geojson #>> '{features,0,properties,ref}' AS feature_ref,
                COALESCE(t.mapped_on, t.modified) AS at,
                EXISTS(SELECT 1 FROM choice_stale st WHERE st.task_id = t.id) AS stale,
                s.answers::text AS answers, s.result_json::text AS result,
                t.cooperative_work_json::text AS payload,
                p.answered_at AS pending_at, p.body->>'outcome' AS pending_outcome
          FROM tasks t
          LEFT JOIN LATERAL (
            SELECT answers, result_json FROM mobile_choice_submissions
            WHERE task_id = t.id AND state = 'done'
            ORDER BY updated_at DESC LIMIT 1
          ) s ON true
          LEFT JOIN LATERAL (
            SELECT cp.answered_at, cp.body FROM choice_pending cp
            WHERE cp.task_id = t.id AND #${ChoicePendingStore.holds("cp")}
            ORDER BY cp.answered_at DESC LIMIT 1
          ) p ON true
          WHERE t.parent_id = $challengeId AND t.location IS NOT NULL
          ORDER BY t.id""".as(stop.*)
  }

  /** Distinct people who saved a stop since `since`: signed-in mappers and guests, a count only. */
  def people(challengeId: Long, since: Instant): Int = db.withConnection { implicit c =>
    val from = java.util.Date.from(since)
    SQL"""SELECT
            (SELECT count(DISTINCT s.user_id) FROM mobile_choice_submissions s
              JOIN tasks t ON t.id = s.task_id
              WHERE t.parent_id = $challengeId AND s.state = 'done' AND s.updated_at >= $from)
          + (SELECT count(DISTINCT p.guest_id) FROM choice_pending p
              WHERE p.challenge_id = $challengeId AND p.guest_id IS NOT NULL
                AND p.answered_at >= $from AND p.state IN ('pending', 'published'))
          AS people""".as(SqlParser.int("people").single)
  }
}

/**
  * The public live page's response (design/live-progress.md, BACKLOG #12). Stop states:
  * - checked: status 1, 2 or 5, or a live pending guest answer whose outcome isn't a status-6 one;
  * - look ("Needs another look"): status 6, or a live pending answer with a status-6 outcome
  *   (too-hard, not-here, moved, or a campaign outcome declared with status 6);
  * - todo: status 0 or 3 (a skip is still open), unless stale;
  * - anything else (stale and open, deleted, disabled) is left out.
  * Answers and reported outcomes appear only with `showAnswers`, and never for a pending answer.
  * Times are cut to the minute.
  */
object LiveProgress {
  val Checked = "checked"
  val Look    = "look"
  val Todo    = "todo"

  private val Hashtag = "#[\\p{L}\\p{N}_]+".r

  /** The status a pending answer's outcome would set: built-ins are 6, campaign outcomes say. */
  def outcomeStatus(outcome: String, payload: Option[JsValue]): Int =
    if (ChoiceWork.BuiltIn.contains(outcome)) Task.STATUS_TOO_HARD
    else
      payload
        .flatMap(p => (p \ "outcomes").asOpt[Seq[JsObject]])
        .flatMap(_.find(o => (o \ "id").asOpt[String].contains(outcome)))
        .flatMap(o => (o \ "status").asOpt[Int])
        .getOrElse(Task.STATUS_FALSE_POSITIVE)

  def state(row: LiveStopRow): Option[String] = row.status match {
    case Task.STATUS_CREATED | Task.STATUS_SKIPPED if row.pendingAt.isDefined =>
      row.pendingOutcome match {
        case Some(o) if outcomeStatus(o, row.payload) == Task.STATUS_TOO_HARD => Some(Look)
        case _                                                                => Some(Checked)
      }
    case Task.STATUS_CREATED | Task.STATUS_SKIPPED if row.stale => None
    case Task.STATUS_CREATED | Task.STATUS_SKIPPED              => Some(Todo)
    case Task.STATUS_FIXED | Task.STATUS_FALSE_POSITIVE | Task.STATUS_ALREADY_FIXED =>
      Some(Checked)
    case Task.STATUS_TOO_HARD => Some(Look)
    case _                    => None
  }

  private def minute(at: Instant): Instant = at.truncatedTo(ChronoUnit.MINUTES)

  /** Question prompt and answer label, in the payload's question order; unknown ids as given. */
  def answers(row: LiveStopRow): Seq[JsObject] = {
    val questions = row.payload.flatMap(p => (p \ "questions").asOpt[Seq[JsObject]]).getOrElse(Nil)
    val known = questions.flatMap { q =>
      val id = (q \ "id").as[String]
      row.answers.get(id).map { option =>
        val label: String = (q \ "options")
          .asOpt[Seq[JsObject]]
          .getOrElse(Nil)
          .find(o => (o \ "id").asOpt[String].contains(option))
          .flatMap(o => (o \ "label").asOpt[String])
          .getOrElse(option)
        val prompt: String = (q \ "prompt").asOpt[String].getOrElse(id)
        Json.obj("question" -> prompt, "answer" -> label)
      }
    }
    val asked = questions.flatMap(q => (q \ "id").asOpt[String]).toSet
    known ++ row.answers.toSeq.filterNot(a => asked.contains(a._1)).sortBy(_._1).map {
      case (q, o) => Json.obj("question" -> q, "answer" -> o)
    }
  }

  /** "It's not here", "It's moved about 40 m", or a campaign outcome's label. Never a place. */
  def reported(row: LiveStopRow): Option[String] = row.outcome.flatMap {
    case ChoiceWork.TooHard => None
    case ChoiceWork.Moved =>
      Some(row.distanceM.fold("It's moved")(m => s"It's moved about $m m"))
    case other => Some(ChoiceResults.outcomeLabel(other, row.payload))
  }

  def json(
      page: LivePage,
      name: String,
      checkinComment: Option[String],
      rows: Seq[LiveStopRow],
      people: Option[Int],
      now: Instant
  ): JsObject = {
    val ended = page.endedAt.isDefined
    val stops = rows.flatMap { row =>
      state(row).map { st =>
        val pending = row.status == Task.STATUS_CREATED || row.status == Task.STATUS_SKIPPED
        val at      = if (pending) row.pendingAt else if (st == Todo) None else row.at
        val details =
          if (!page.showAnswers || pending || st == Todo) Json.obj()
          else {
            val list = answers(row)
            (if (list.isEmpty) Json.obj() else Json.obj("answers" -> list)) ++
              reported(row).fold(Json.obj())(r => Json.obj("reported" -> r))
          }
        Json.obj("id" -> row.taskId, "lon" -> row.lon, "lat" -> row.lat, "state" -> st) ++
          row.name.fold(Json.obj())(n => Json.obj("name" -> n)) ++
          row.ref.fold(Json.obj())(r => Json.obj("ref"   -> r)) ++
          at.fold(Json.obj())(t => Json.obj("at"         -> minute(t))) ++ details
      }
    }
    val states = stops.map(s => (s \ "state").as[String])
    val tally = Json.obj(
      "checked"   -> states.count(_ == Checked),
      "total"     -> states.size,
      "needsLook" -> states.count(_ == Look)
    ) ++ people.filter(_ => !ended).fold(Json.obj())(n => Json.obj("people" -> n))
    val matchTags: JsObject = rows.iterator
      .flatMap(_.payload.flatMap(p => (p \ "match").asOpt[JsObject]))
      .find(_.keys.nonEmpty)
      .getOrElse(Json.obj())
    Json.obj(
      "campaign" -> Json.obj(
        "name"     -> name,
        "hashtags" -> checkinComment.toSeq.flatMap(Hashtag.findAllIn(_)).distinct,
        // The first task's identity guard, so the page names its places as the apps do
        // ("stops", "entrances"). Tags only, as public as the OSM element.
        "match" -> matchTags
      ),
      "mode"         -> (if (ended) "ended" else "live"),
      "answersShown" -> page.showAnswers,
      "generatedAt"  -> now.truncatedTo(ChronoUnit.SECONDS),
      "tally"        -> tally,
      "stops"        -> stops
    ) ++ page.endedAt.fold(Json.obj())(e => Json.obj("endedAt" -> e))
  }
}

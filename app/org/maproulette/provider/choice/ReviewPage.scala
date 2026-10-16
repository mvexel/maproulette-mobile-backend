package org.maproulette.provider.choice

import anorm._
import java.sql.Connection
import java.time.{Instant, ZoneOffset}
import javax.inject.{Inject, Singleton}
import play.api.db.Database
import play.api.libs.json._

/**
  * The review page (H1a, backend/h1/h1a-api.md): mappers read a campaign's held guest answers by its
  * live page token and mark places. A place is a task of the campaign with a point that has a held
  * guest answer, or that someone has marked.
  */
object ReviewPage {
  val Open       = "open"
  val NeedsVisit = "needs_visit"
  val Done       = "done"

  val Filters      = Set("all", "agree", "visit", "done")
  val DefaultLimit = 100
  val MaxLimit     = 200

  /** "I'm not sure" is not an answer: it is never shown and never makes a place. */
  val Ignored: Set[String] = Set(ChoiceWork.TooHard)

  private val Hashtag = "#[\\p{L}\\p{N}_]+".r

  def hashtags(comment: Option[String]): Seq[String] =
    comment.toSeq.flatMap(c => Hashtag.findAllIn(c).toSeq).distinct

  /** One held guest answer: its body and when it was given. Never the guest. */
  case class Answer(body: JsValue, at: Instant)

  case class Place(
      id: Long,
      elementType: Option[String],
      elementId: Option[Long],
      lon: Double,
      lat: Double,
      name: Option[String],
      ref: Option[String],
      state: String,
      payload: Option[JsValue],
      properties: JsObject,
      answers: Seq[Answer]
  ) {
    private def day(at: Instant): String = at.atOffset(ZoneOffset.UTC).toLocalDate.toString

    private lazy val work: Option[ChoiceWork] = payload.flatMap(ChoiceWork.validate(_).toOption)

    /** One per distinct question and answer, in the campaign's question order. */
    lazy val suggestions: Seq[JsObject] = work.toSeq.flatMap { w =>
      val given = for {
        answer        <- answers
        byQuestion    <- (answer.body \ "answers").asOpt[Map[String, String]].toSeq
        (qid, option) <- byQuestion
      } yield (qid, option, answer.at)
      w.questions.flatMap { q =>
        q.options
          .flatMap { o =>
            val same = given.filter { case (qid, oid, _) => qid == q.id && oid == o.id }
            if (same.isEmpty) None
            else {
              val tags = o.setTags.map { case (k, v) => k -> JsString(v) } ++
                o.unsetTags.map(_ -> JsNull)
              Some(
                same.size -> Json.obj(
                  "question" -> q.id,
                  "prompt"   -> q.prompt,
                  "answer"   -> o.id,
                  "label"    -> o.label,
                  "tags"     -> JsObject(tags.toSeq),
                  "people"   -> same.size,
                  "day"      -> day(same.map(_._3).max),
                  "current" -> JsObject(tags.keys.toSeq.map { key =>
                    key -> (properties \ key).asOpt[String].fold[JsValue](JsNull)(JsString(_))
                  })
                )
              )
            }
          }
          .sortBy(-_._1)
          .map(_._2)
      }
    }

    /** Reports grouped by outcome id; the spot is the latest one given. */
    lazy val reports: Seq[JsObject] = answers
      .flatMap(a => (a.body \ "outcome").asOpt[String].map(_ -> a))
      .filterNot { case (id, _) => Ignored(id) }
      .groupBy(_._1)
      .toSeq
      .map {
        case (id, rows) =>
          val latest = rows.map(_._2).maxBy(_.at)
          val spot = rows
            .map(_._2)
            .sortBy(_.at)
            .reverse
            .flatMap(a => (a.body \ "location").asOpt[JsObject])
            .headOption
          val base = Json.obj(
            "outcome" -> id,
            "label"   -> ChoiceResults.outcomeLabel(id, payload),
            "people"  -> rows.size,
            "day"     -> day(latest.at)
          )
          (rows.size, id) -> spot.fold(base) { l =>
            base ++ Json.obj("lon" -> (l \ "lon").as[Double], "lat" -> (l \ "lat").as[Double])
          }
      }
      .sortBy { case ((n, id), _) => (-n, id) }
      .map(_._2)

    /** Two or more guests gave the same answer to a question. */
    def agreed: Boolean = suggestions.exists(s => (s \ "people").as[Int] >= 2)

    def json(skipped: Boolean): JsObject = Json.obj(
      "id"          -> id,
      "osm"         -> elementType.zip(elementId).map { case (t, i) => Json.obj("type" -> t, "id" -> i) },
      "lon"         -> lon,
      "lat"         -> lat,
      "name"        -> name,
      "ref"         -> ref,
      "state"       -> state,
      "skipped"     -> skipped,
      "suggestions" -> suggestions,
      "reports"     -> reports
    )
  }

  def matches(filter: String, place: Place): Boolean = filter match {
    case "agree" => place.state == Open && place.agreed
    case "visit" => place.state == NeedsVisit
    case "done"  => place.state == Done
    case _       => true
  }

  /** The page's response; `places` is every place of the campaign, in id order. */
  def json(
      name: String,
      comment: Option[String],
      places: Seq[Place],
      skipped: Set[Long],
      filter: String,
      after: Option[Long],
      limit: Int
  ): JsObject = {
    val chosen = places.filter(p => matches(filter, p) && after.forall(p.id > _))
    val page   = chosen.take(limit)
    val base = Json.obj(
      "campaign" -> Json.obj(
        "name"             -> name,
        "hashtags"         -> hashtags(comment),
        "changesetComment" -> comment
      ),
      "counts" -> Json.obj(
        "toReview"   -> places.count(_.state == Open),
        "added"      -> places.count(_.state == Done),
        "needsVisit" -> places.count(_.state == NeedsVisit)
      ),
      "places" -> page.map(p => p.json(skipped(p.id)))
    )
    if (chosen.size > page.size) base ++ Json.obj("next" -> page.last.id) else base
  }
}

/** Why a mark was refused. */
sealed trait ReviewRefusal
object ReviewRefusal {
  case object NotFound    extends ReviewRefusal
  case object AlreadyDone extends ReviewRefusal
}

@Singleton
class ReviewRepository @Inject() (db: Database) {
  import ReviewPage._

  private val ElementPattern = "(node|way|relation)/([0-9]+)".r

  private val place = for {
    id      <- SqlParser.long("id")
    lon     <- SqlParser.double("lon")
    lat     <- SqlParser.double("lat")
    name    <- SqlParser.get[Option[String]]("feature_name")
    ref     <- SqlParser.get[Option[String]]("feature_ref")
    state   <- SqlParser.str("state")
    payload <- SqlParser.get[Option[String]]("payload")
    props   <- SqlParser.get[Option[String]]("props")
  } yield {
    val work = payload.map(Json.parse)
    val element = work.flatMap(w => (w \ "element").asOpt[String]).collect {
      case ElementPattern(kind, number) => kind -> number.toLong
    }
    Place(
      id,
      element.map(_._1),
      element.map(_._2),
      lon,
      lat,
      name.filter(_.trim.nonEmpty),
      ref.filter(_.trim.nonEmpty),
      state,
      work,
      props.flatMap(p => Json.parse(p).asOpt[JsObject]).getOrElse(Json.obj()),
      Seq.empty
    )
  }

  private val ignored = Ignored.map(id => s"'$id'").mkString(", ")

  /** Every place of the campaign (or just `only`), with its held guest answers, in id order. */
  def places(challengeId: Long, only: Option[Long] = None)(
      implicit c: Connection
  ): Seq[Place] = {
    val one = only.getOrElse(-1L)
    val rows = SQL(s"""SELECT t.id, ST_X(t.location) AS lon, ST_Y(t.location) AS lat,
              t.geojson->'features'->0->'properties'->>'name' AS feature_name,
              t.geojson->'features'->0->'properties'->>'ref' AS feature_ref,
              (t.geojson->'features'->0->'properties')::text AS props,
              COALESCE(rp.state, 'open') AS state,
              t.cooperative_work_json::text AS payload
        FROM tasks t
        LEFT JOIN choice_review_places rp ON rp.task_id = t.id
        WHERE t.parent_id = {challenge} AND t.location IS NOT NULL
          AND ({one} < 0 OR t.id = {one})
          AND (rp.task_id IS NOT NULL OR EXISTS (
            SELECT 1 FROM choice_pending p WHERE p.task_id = t.id AND p.state = 'pending'
              AND COALESCE(p.body->>'outcome', '') NOT IN ($ignored)))
        ORDER BY t.id""")
      .on("challenge" -> challengeId, "one" -> one)
      .as(place.*)
    val answers =
      SQL("""SELECT task_id, body::text AS body, answered_at FROM choice_pending
        WHERE challenge_id = {challenge} AND state = 'pending' AND ({one} < 0 OR task_id = {one})
        ORDER BY task_id, answered_at""")
        .on("challenge" -> challengeId, "one" -> one)
        .as(
          (SqlParser.long("task_id") ~ SqlParser.str("body") ~ SqlParser.date("answered_at")).map {
            case task ~ body ~ at => task -> Answer(Json.parse(body), at.toInstant)
          }.*
        )
        .groupBy(_._1)
    rows.map(p => p.copy(answers = answers.getOrElse(p.id, Nil).map(_._2)))
  }

  def places(challengeId: Long): Seq[Place] =
    db.withConnection(implicit c => places(challengeId, None))

  /** The places of the campaign this mapper skipped. */
  def skipped(challengeId: Long, userId: Long): Set[Long] = db.withConnection { implicit c =>
    SQL"""SELECT m.task_id FROM choice_review_marks m JOIN tasks t ON t.id = m.task_id
          WHERE m.user_id = $userId AND m.kind = 'skip' AND t.parent_id = $challengeId"""
      .as(SqlParser.long("task_id").*)
      .toSet
  }

  private def isPlace(challengeId: Long, taskId: Long)(implicit c: Connection): Boolean =
    places(challengeId, Some(taskId)).nonEmpty

  private def result(challengeId: Long, taskId: Long, userId: Long)(
      implicit c: Connection
  ): Either[ReviewRefusal, JsObject] = {
    val skip = SQL"""SELECT EXISTS(SELECT 1 FROM choice_review_marks
                     WHERE task_id = $taskId AND user_id = $userId AND kind = 'skip')"""
      .as(SqlParser.scalar[Boolean].single)
    places(challengeId, Some(taskId)).headOption
      .map(p => p.json(skip))
      .toRight(ReviewRefusal.NotFound)
  }

  /** Hide (`on`) or unhide this place for this mapper. */
  def skip(
      challengeId: Long,
      taskId: Long,
      userId: Long,
      on: Boolean
  ): Either[ReviewRefusal, JsObject] =
    db.withTransaction { implicit c =>
      if (!isPlace(challengeId, taskId)) Left(ReviewRefusal.NotFound)
      else {
        if (on)
          SQL"""INSERT INTO choice_review_marks (task_id, user_id, kind)
                VALUES ($taskId, $userId, 'skip') ON CONFLICT DO NOTHING""".executeUpdate()
        else
          SQL"""DELETE FROM choice_review_marks
                WHERE task_id = $taskId AND user_id = $userId AND kind = 'skip'""".executeUpdate()
        result(challengeId, taskId, userId)
      }
    }

  /**
    * The shared "Needs a visit" mark, recorded in the admin audit log with the mapper's user id.
    * A place OSM already carries (done) can't be marked; clearing the mark on it changes nothing.
    */
  def needsVisit(
      challengeId: Long,
      taskId: Long,
      userId: Long,
      on: Boolean
  ): Either[ReviewRefusal, JsObject] =
    db.withTransaction { implicit c =>
      if (!isPlace(challengeId, taskId)) Left(ReviewRefusal.NotFound)
      else {
        val current = SQL"SELECT state FROM choice_review_places WHERE task_id = $taskId FOR UPDATE"
          .as(SqlParser.str("state").singleOpt)
        if (current.contains(Done) && on) Left(ReviewRefusal.AlreadyDone)
        else if (current.contains(Done) || current.getOrElse(Open) == (if (on) NeedsVisit
                                                                       else Open))
          result(challengeId, taskId, userId)
        else {
          val state = if (on) NeedsVisit else Open
          SQL"""INSERT INTO choice_review_places (task_id, challenge_id, state, marked_by, marked_at)
                VALUES ($taskId, $challengeId, $state, $userId, NOW())
                ON CONFLICT (task_id) DO UPDATE SET state = EXCLUDED.state,
                  marked_by = EXCLUDED.marked_by, marked_at = EXCLUDED.marked_at""".executeUpdate()
          val action = if (on) "review.needs_visit" else "review.needs_visit_cleared"
          SQL("""INSERT INTO mobile_admin_audit(actor_user_id, action, target, before, after)
                VALUES ({actor}, {action}, {target}, NULL, CAST({after} AS jsonb))""")
            .on(
              "actor"  -> userId,
              "action" -> action,
              "target" -> s"task:$taskId",
              "after"  -> Json.stringify(Json.obj("challengeId" -> challengeId, "state" -> state))
            )
            .executeUpdate()
          result(challengeId, taskId, userId)
        }
      }
    }
}

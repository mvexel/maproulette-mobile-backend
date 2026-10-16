package org.maproulette.provider.choice

import anorm._
import java.sql.Connection
import java.time.{Instant, ZoneOffset}
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.{Inject, Singleton}
import org.maproulette.framework.model._
import org.maproulette.models.dal.{ChallengeDAL, TaskDAL}
import org.maproulette.auth.mobile.MobileOAuthSettings
import play.api.db.Database
import play.api.inject.{ApplicationLifecycle, SimpleModule, bind}
import play.api.libs.json._
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal

/**
  * The confirm queue (H1b, backend/h1/h1b-design.md): a campaign's guest tag answers that pass the
  * stale check become tasks of a disabled tag-fix challenge, one per place and question, which
  * mappers work on maproulette.org from its line-by-line export. Questions guests answered
  * differently stay on the review page. Nothing here writes to OSM.
  */
object ChoiceQueue {

  /** One queued edit: every guest gave this answer to this question at this place. */
  case class Candidate(
      place: ReviewPage.Place,
      question: ChoiceQuestion,
      option: ChoiceOption,
      people: Int,
      at: Instant,
      first: Instant
  ) {
    def tags: Map[String, Option[String]] =
      option.setTags.map { case (k, v) => k -> Some(v) } ++ option.unsetTags.map(_ -> None)

    /** OSM already carries the answer. */
    def applied(current: Map[String, String]): Boolean = carries(option, current)

    /** `14 Oct`, or the range from the first to the latest answer: `13–14 Oct`, `30 Sep–2 Oct`. */
    def days: String = {
      def utc(i: Instant) = i.atOffset(ZoneOffset.UTC).toLocalDate
      val (from, to)      = (utc(first), utc(at))
      val month           = DateTimeFormatter.ofPattern("MMM", Locale.ENGLISH)
      if (from == to) s"${to.getDayOfMonth} ${to.format(month)}"
      else if (from.getMonth == to.getMonth && from.getYear == to.getYear)
        s"${from.getDayOfMonth}–${to.getDayOfMonth} ${to.format(month)}"
      else s"${from.getDayOfMonth} ${from.format(month)}–${to.getDayOfMonth} ${to.format(month)}"
    }

    /** The answer's tag changes: `door=sliding`, and `no key` for a removed one. */
    def tagText: String =
      (option.setTags.toSeq.sorted.map { case (k, v) => s"$k=$v" } ++
        option.unsetTags.sorted.map(k => s"no $k")).mkString(", ")

    /** Iris (2026-10-16): the task instruction a mapper reads on maproulette.org. */
    def evidence: String = {
      val who = if (people == 1) "1 person" else s"$people people"
      s"""Surveyed on $days by $who. Question: "${question.prompt}" Answer: "${option.label}" """ +
        s"($tagText). Add it if it looks right; skip if unsure."
    }

    def element: String = s"${place.elementType.getOrElse("node")}/${place.elementId.getOrElse(0L)}"

    def name: String =
      s"$element " + tags.toSeq
        .sortBy(_._1)
        .map { case (k, v) => s"$k=${v.getOrElse("")}" }
        .mkString(" ")

    /** Upstream cooperative tag fix (type 1): set and unset the answer's tags on the element. */
    def cooperativeWork: JsObject = {
      val operations =
        (if (option.setTags.isEmpty) Nil
         else List(Json.obj("operation" -> "setTags", "data" -> option.setTags))) ++
          (if (option.unsetTags.isEmpty) Nil
           else List(Json.obj("operation" -> "unsetTags", "data" -> option.unsetTags)))
      Json.obj(
        "meta" -> Json.obj("version" -> 2, "type" -> Challenge.COOPERATIVE_TAGS),
        "operations" -> Json.arr(
          Json.obj(
            "operationType" -> "modifyElement",
            "data"          -> Json.obj("id" -> element, "operations" -> operations)
          )
        )
      )
    }

    def feature: JsObject = Json.obj(
      "type" -> "Feature",
      "geometry" -> Json
        .obj("type" -> "Point", "coordinates" -> Json.arr(place.lon, place.lat)),
      "properties" -> Json.obj("@id" -> element, "evidence" -> evidence)
    )

    /** One line of the maproulette.org line-by-line upload. */
    def line: JsObject = Json.obj(
      "type"            -> "FeatureCollection",
      "features"        -> Json.arr(feature),
      "cooperativeWork" -> cooperativeWork
    )
  }

  /** The element's tags include the option's: its set tags, and none of the tags it removes. */
  def carries(option: ChoiceOption, current: Map[String, String]): Boolean =
    option.setTags.forall { case (k, v) => current.get(k).contains(v) } &&
      option.unsetTags.forall(k => !current.contains(k))

  /** One run of the "On the map" check (H1c), for the log. */
  case class OnMapReport(checked: Int, onMap: Int, gone: Int, failures: Int)

  /** What one place's answers can give, before the OSM check. */
  case class PlaceCandidates(candidates: Seq[Candidate], disagree: Int)

  /**
    * Questions every guest answered alike, which aren't queued yet. Places a mapper marked
    * (needs a visit), on the map or gone are left out.
    */
  def candidates(place: ReviewPage.Place, queued: Set[String]): PlaceCandidates = {
    val work = place.payload.flatMap(ChoiceWork.validate(_).toOption)
    if (Set(ReviewPage.NeedsVisit, ReviewPage.Done, ReviewPage.Gone)(place.state) || work.isEmpty)
      PlaceCandidates(Nil, 0)
    else {
      val found = work.get.questions.flatMap { q =>
        place.byQuestion.get(q.id).filterNot(_ => queued(q.id)).map(q -> _)
      }
      PlaceCandidates(
        found.collect {
          case (q, opts) if opts.size == 1 =>
            val (oid, (people, at)) = opts.head
            val first = place.answers
              .filter(a => (a.body \ "answers" \ q.id).asOpt[String].contains(oid))
              .map(_.at)
              .minOption
              .getOrElse(at)
            q.options.find(_.id == oid).map(o => Candidate(place, q, o, people, at, first))
        }.flatten,
        found.count(_._2.size > 1)
      )
    }
  }

  /** The stale check of one place's candidates against the element as OSM has it now. */
  sealed trait Verdict
  case object PlaceGone                                                              extends Verdict
  case class Checked(queue: Seq[Candidate], applied: Seq[Candidate], stale: Boolean) extends Verdict

  def check(work: ChoiceWork, candidates: Seq[Candidate], read: ElementRead): Verdict = read match {
    case ElementGone => PlaceGone
    case ElementFound(_, _, tags) if !work.matches(tags) =>
      Checked(Nil, Nil, stale = true)
    case ElementFound(_, _, tags) =>
      val (applied, rest)  = candidates.partition(_.applied(tags))
      val (holds, changed) = rest.partition(_.question.holds(tags))
      Checked(holds, applied, stale = changed.nonEmpty)
  }

  /** The generator's counts for the admin app. */
  case class Report(
      queueChallengeId: Long,
      queued: Int,
      alreadyQueued: Int,
      done: Int,
      stale: Int,
      gone: Int,
      disagree: Int
  ) {
    def json: JsObject = Json.obj(
      "queueChallengeId" -> queueChallengeId,
      "queued"           -> queued,
      "alreadyQueued"    -> alreadyQueued,
      "done"             -> done,
      "stale"            -> stale,
      "gone"             -> gone,
      "disagree"         -> disagree
    )
  }

  /** Far enough ahead that a queued answer's task stays out of discovery until Needs a visit. */
  val QueuedHold: Instant = Instant.parse("9999-12-31T00:00:00Z")
}

@Singleton
class ChoiceQueueRepository @Inject() (db: Database) {

  def campaign(challengeId: Long): Option[(String, String, Long)] = db.withConnection {
    implicit c =>
      SQL"""SELECT name, COALESCE(checkin_comment, '') AS comment, parent_id FROM challenges
            WHERE id = $challengeId AND cooperative_type = ${ChoiceWork.CooperativeType}
              AND NOT deleted"""
        .as((SqlParser.str("name") ~ SqlParser.str("comment") ~ SqlParser.long("parent_id")).map {
          case name ~ comment ~ parent => (name, comment, parent)
        }.singleOpt)
  }

  def queueChallenge(campaignId: Long): Option[Long] = db.withConnection { implicit c =>
    SQL"SELECT queue_challenge_id FROM choice_queues WHERE campaign_id = $campaignId"
      .as(SqlParser.long("queue_challenge_id").singleOpt)
  }

  def linkQueue(campaignId: Long, queueChallengeId: Long): Unit = db.withConnection { implicit c =>
    SQL"""INSERT INTO choice_queues (campaign_id, queue_challenge_id)
            VALUES ($campaignId, $queueChallengeId)""".executeUpdate()
    ()
  }

  /** The questions already queued, per campaign task. */
  def queued(campaignId: Long): Map[Long, Set[String]] = db.withConnection { implicit c =>
    SQL"""SELECT i.source_task_id, i.question FROM choice_queue_items i
          JOIN tasks t ON t.id = i.source_task_id WHERE t.parent_id = $campaignId"""
      .as((SqlParser.long("source_task_id") ~ SqlParser.str("question")).map {
        case task ~ q => task -> q
      }.*)
      .groupBy(_._1)
      .map { case (task, rows) => task -> rows.map(_._2).toSet }
  }

  /**
    * Records a queued edit and keeps the campaign task out of discovery (Q-4). False when another
    * run queued the same task and question first, and the new queue task is removed.
    */
  def recordQueued(queueTaskId: Long, candidate: ChoiceQueue.Candidate): Boolean =
    db.withTransaction { implicit c =>
      val inserted = SQL"""INSERT INTO choice_queue_items
              (queue_task_id, source_task_id, question, answer, people)
            VALUES ($queueTaskId, ${candidate.place.id}, ${candidate.question.id},
              ${candidate.option.id}, ${candidate.people})
            ON CONFLICT (source_task_id, question) DO NOTHING""".executeUpdate() == 1
      if (inserted) hold(candidate.place.id)
      // Another run got there first: drop the task this run made for it.
      else SQL"DELETE FROM tasks WHERE id = $queueTaskId".executeUpdate()
      inserted
    }

  private def hold(taskId: Long)(implicit c: Connection): Unit = {
    val until = java.util.Date.from(ChoiceQueue.QueuedHold)
    SQL"""UPDATE choice_pending SET hold_until = $until, updated_at = NOW()
          WHERE task_id = $taskId AND state = 'pending'""".executeUpdate()
    ()
  }

  /** The stale check's place state; a place a mapper marked meanwhile keeps its mark. */
  def setState(challengeId: Long, taskId: Long, state: String): Unit = db.withConnection {
    implicit c =>
      SQL"""INSERT INTO choice_review_places (task_id, challenge_id, state, marked_by, marked_at)
            VALUES ($taskId, $challengeId, $state, NULL, NOW())
            ON CONFLICT (task_id) DO UPDATE SET state = EXCLUDED.state, marked_at = NOW()
              WHERE choice_review_places.state <> ${ReviewPage.NeedsVisit}""".executeUpdate()
      ()
  }

  /**
    * H1c: OSM carries this answer to this question. The place's held guest answers that gave it
    * are on the map once none of their other queued answers is still waiting (Ada: the guest sees
    * "On the map" only for the whole answer). Returns how many rows changed.
    */
  def markOnMap(taskId: Long, question: String, answer: String): Int = db.withConnection {
    implicit c =>
      SQL"""UPDATE choice_pending p SET state = 'on_map', updated_at = NOW()
            WHERE p.task_id = $taskId AND p.state = 'pending'
              AND p.body->'answers'->>$question = $answer
              AND NOT EXISTS (SELECT 1 FROM choice_queue_items i
                WHERE i.source_task_id = p.task_id AND i.on_map_at IS NULL
                  AND p.body->'answers'->>i.question = i.answer)""".executeUpdate()
  }

  /**
    * Queued edits not yet seen on the map, grouped by campaign task, least recently checked first:
    * (campaign id, campaign task id, its payload, [(question, answer)]).
    */
  def unchecked(limit: Int): Seq[(Long, Long, Option[JsValue], Seq[(String, String)])] =
    db.withConnection { implicit c =>
      SQL"""SELECT t.parent_id, i.source_task_id, t.cooperative_work_json::text AS payload,
              i.question, i.answer
            FROM choice_queue_items i JOIN tasks t ON t.id = i.source_task_id
            WHERE i.source_task_id IN (
              SELECT source_task_id FROM choice_queue_items WHERE on_map_at IS NULL
              GROUP BY source_task_id ORDER BY min(checked_at) NULLS FIRST, source_task_id
              LIMIT $limit)
              AND i.on_map_at IS NULL
            ORDER BY i.source_task_id, i.question"""
        .as(
          (SqlParser.long("parent_id") ~ SqlParser.long("source_task_id") ~
            SqlParser.get[Option[String]]("payload") ~ SqlParser.str("question") ~
            SqlParser.str("answer")).*
        )
        .groupBy { case parent ~ task ~ payload ~ _ ~ _ => (parent, task, payload) }
        .toSeq
        .sortBy(_._1._2)
        .map {
          case ((parent, task, payload), rows) =>
            (parent, task, payload.map(Json.parse), rows.map { case _ ~ _ ~ _ ~ q ~ a => q -> a })
        }
    }

  /** Records a check of the campaign task's queued edits; `onMap` are the ones OSM now carries. */
  def checked(taskId: Long, onMap: Seq[String]): Unit = db.withTransaction { implicit c =>
    if (onMap.nonEmpty)
      SQL"""UPDATE choice_queue_items SET on_map_at = NOW() WHERE source_task_id = $taskId
            AND on_map_at IS NULL AND question IN ($onMap)""".executeUpdate()
    SQL"""UPDATE choice_queue_items SET checked_at = NOW() WHERE source_task_id = $taskId"""
      .executeUpdate()
    ()
  }

  /** Every queued edit of the campaign task is on the map. */
  def allOnMap(taskId: Long): Boolean = db.withConnection { implicit c =>
    SQL"""SELECT NOT EXISTS (SELECT 1 FROM choice_queue_items
            WHERE source_task_id = $taskId AND on_map_at IS NULL) AS done"""
      .as(SqlParser.bool("done").single)
  }

  /** The queue's tasks in id order, rebuilt as export lines from their stored payloads. */
  def exportLines(queueChallengeId: Long): Seq[JsObject] = db.withConnection { implicit c =>
    SQL"""SELECT t.geojson::text AS geojson, t.cooperative_work_json::text AS work FROM tasks t
          WHERE t.parent_id = $queueChallengeId ORDER BY t.id"""
      .as((SqlParser.str("geojson") ~ SqlParser.get[Option[String]]("work")).map {
        case geojson ~ work =>
          Json.obj(
            "type"     -> "FeatureCollection",
            "features" -> (Json.parse(geojson) \ "features").getOrElse(Json.arr())
          ) ++ work.fold(Json.obj())(w => Json.obj("cooperativeWork" -> Json.parse(w)))
      }.*)
  }
}

/** Runs the generator: reads OSM once per place, read-only and without a token. */
@Singleton
class ChoiceQueueService @Inject() (
    repository: ChoiceQueueRepository,
    reviews: ReviewRepository,
    osm: ChoiceOsmClient,
    challengeDAL: ChallengeDAL,
    taskDAL: TaskDAL
)(implicit ec: ExecutionContext) {
  import ChoiceQueue._

  private def queueChallenge(
      campaignId: Long,
      name: String,
      comment: String,
      project: Long,
      user: User
  ): Long =
    repository.queueChallenge(campaignId).getOrElse {
      val created = challengeDAL.insert(
        Challenge(
          -1,
          s"$name: confirm survey answers",
          null,
          null,
          Some(s"Street Tally survey answers for $name, one suggested tag change per task."),
          general = ChallengeGeneral(
            user.osmProfile.id,
            project,
            "{{evidence}}",
            enabled = false,
            cooperativeType = Challenge.COOPERATIVE_TAGS,
            checkinComment = comment,
            checkinSource = "survey"
          ),
          creation = ChallengeCreation(),
          priority = ChallengePriority(),
          extra = ChallengeExtra()
        ),
        user
      )
      repository.linkQueue(campaignId, created.id)
      created.id
    }

  /** None for an unknown campaign or one that isn't a choice campaign. */
  def generate(campaignId: Long, user: User): Future[Option[Report]] =
    Future(repository.campaign(campaignId)).flatMap {
      case None => Future.successful(None)
      case Some((name, comment, project)) =>
        val queued = repository.queued(campaignId)
        val places = reviews.places(campaignId).map { p =>
          p -> candidates(p, queued.getOrElse(p.id, Set.empty))
        }
        val alreadyQueued = places.map {
          case (p, _) => queued.getOrElse(p.id, Set.empty).count(p.byQuestion.contains)
        }.sum
        val disagree = places.map(_._2.disagree).sum
        val toCheck  = places.filter(_._2.candidates.nonEmpty)
        // One place at a time: a campaign has tens of places, and OSM asks for gentle clients.
        toCheck
          .foldLeft(Future.successful(List.empty[(ReviewPage.Place, Seq[Candidate], Verdict)])) {
            case (acc, (place, found)) =>
              acc.flatMap { done =>
                val work = place.payload.flatMap(ChoiceWork.validate(_).toOption).get
                osm
                  .element(work.elementType, work.elementId, None)
                  .map(read =>
                    (place, found.candidates, check(work, found.candidates, read)) :: done
                  )
              }
          }
          .map { verdicts =>
            val queue =
              if (verdicts.exists(_._3.isInstanceOf[Checked]))
                Some(queueChallenge(campaignId, name, comment, project, user))
              else repository.queueChallenge(campaignId)
            var counts = Report(queue.getOrElse(0L), 0, alreadyQueued, 0, 0, 0, disagree)
            verdicts.reverse.foreach {
              case (place, _, PlaceGone) =>
                repository.setState(campaignId, place.id, ReviewPage.Gone)
                counts = counts.copy(gone = counts.gone + 1)
              case (place, found, Checked(toQueue, applied, stale)) =>
                if (stale) repository.setState(campaignId, place.id, ReviewPage.Stale)
                else if (applied.size == found.size)
                  repository.setState(campaignId, place.id, ReviewPage.Done)
                else if (place.state == ReviewPage.Stale)
                  repository.setState(campaignId, place.id, ReviewPage.Open)
                val added = toQueue.count { candidate =>
                  val task = taskDAL.insert(
                    Task(
                      -1,
                      candidate.name,
                      null,
                      null,
                      queue.get,
                      instruction = Some(candidate.evidence),
                      geometries = Json.obj("features" -> Json.arr(candidate.feature)),
                      cooperativeWork = Some(candidate.cooperativeWork),
                      status = Some(Task.STATUS_CREATED)
                    ),
                    user
                  )
                  repository.recordQueued(task.id, candidate)
                }
                // H1c: answers OSM already carries are on the map, after this run's queued ones are
                // recorded, so a guest answer with a part still queued stays pending.
                applied.foreach(c => repository.markOnMap(place.id, c.question.id, c.option.id))
                counts = counts.copy(
                  queued = counts.queued + added,
                  done = counts.done + applied.size,
                  stale = counts.stale + (if (stale) 1 else 0)
                )
            }
            Some(counts)
          }
    }

  /**
    * H1c: re-reads OSM for queued edits not yet on the map, the least recently checked places
    * first. An edit OSM now carries is on the map, and so are the guest answers that gave it; a
    * place whose queued edits all are is `done`, one whose element was deleted is `gone`. A place
    * OSM can't answer for is left for the next run.
    */
  def detect(limit: Int = 200): Future[OnMapReport] =
    Future(repository.unchecked(limit)).flatMap { places =>
      places.foldLeft(Future.successful(OnMapReport(0, 0, 0, 0))) {
        case (acc, (campaignId, taskId, payload, edits)) =>
          acc.flatMap { report =>
            payload.flatMap(ChoiceWork.validate(_).toOption) match {
              case None =>
                repository.checked(taskId, Nil)
                Future.successful(report.copy(failures = report.failures + 1))
              case Some(work) =>
                osm
                  .element(work.elementType, work.elementId, None)
                  .map {
                    case ElementGone =>
                      repository.setState(campaignId, taskId, ReviewPage.Gone)
                      repository.checked(taskId, Nil)
                      report.copy(checked = report.checked + 1, gone = report.gone + 1)
                    case ElementFound(_, _, tags) =>
                      val onMap = edits.filter {
                        case (q, a) =>
                          work.questions
                            .find(_.id == q)
                            .flatMap(_.options.find(_.id == a))
                            .exists(carries(_, tags))
                      }
                      repository.checked(taskId, onMap.map(_._1))
                      onMap.foreach { case (q, a) => repository.markOnMap(taskId, q, a) }
                      if (onMap.nonEmpty && repository.allOnMap(taskId))
                        repository.setState(campaignId, taskId, ReviewPage.Done)
                      report.copy(checked = report.checked + 1, onMap = report.onMap + onMap.size)
                  }
                  .recover {
                    case _: OsmCallException =>
                      repository.checked(taskId, Nil)
                      report.copy(failures = report.failures + 1)
                  }
            }
          }
      }
    }

  /** None when the campaign has no queue yet. */
  def exportQueue(campaignId: Long): Future[Option[Seq[JsObject]]] = Future {
    repository.queueChallenge(campaignId).map(repository.exportLines)
  }
}

/** Runs the "On the map" check (H1c) hourly while guests are enabled. One run at a time. */
@Singleton
class ChoiceOnMapScheduler @Inject() (
    settings: MobileOAuthSettings,
    service: ChoiceQueueService,
    actorSystem: akka.actor.ActorSystem,
    lifecycle: ApplicationLifecycle
) {
  private val logger  = play.api.Logger(getClass)
  private val running = new AtomicBoolean(false)
  private implicit val ec: ExecutionContext =
    actorSystem.dispatchers.lookup("mobile-oauth-dispatcher")

  private def tick(): Unit =
    if (running.compareAndSet(false, true))
      service
        .detect()
        .map { report =>
          if (report.checked + report.failures > 0) logger.info(s"On the map check: $report")
        }
        .recover {
          case NonFatal(e) => logger.error(s"On the map check failed: ${e.getClass.getSimpleName}")
        }
        .onComplete(_ => running.set(false))

  if (settings.guestsEnabled) {
    val task = actorSystem.scheduler.scheduleWithFixedDelay(5.minutes, 1.hour)(() => tick())
    lifecycle.addStopHook(() => Future.successful(task.cancel()))
  }
}

class ChoiceOnMapModule extends SimpleModule(bind[ChoiceOnMapScheduler].toSelf.eagerly())

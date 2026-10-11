package org.maproulette.provider.choice

import anorm._
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.{DeserializationFeature, ObjectMapper}
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.sql.Connection
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.{Inject, Singleton}
import org.maproulette.auth.mobile.{MobileOAuthStore, MobileOsmTokenCipher, MobileScopes}
import org.maproulette.data.{ActionManager, TaskItem, TaskStatusSet, TaskType}
import org.maproulette.exception.{InvalidException, NotFoundException}
import org.maproulette.framework.model.{Task, User}
import org.maproulette.models.dal.{ChallengeDAL, TaskDAL}
import org.maproulette.provider.websockets.{WebSocketMessages, WebSocketProvider}
import play.api.Logger
import play.api.db.Database
import play.api.libs.json._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal
import scala.util.{Failure, Success, Try}

/** An HTTP status and JSON body; the controller only adds headers. */
case class ChoiceResponse(status: Int, body: JsObject)

/** What a validated submission asks for. */
sealed trait ChoicePlan { def status: Int }
case class EditTags(answers: List[(ChoiceQuestion, ChoiceOption)]) extends ChoicePlan {
  val status                   = Task.STATUS_FIXED
  val set: Map[String, String] = answers.flatMap(_._2.setTags).toMap
  val unset: List[String]      = answers.flatMap(_._2.unsetTags).distinct.sorted
}
case object DeleteNode             extends ChoicePlan { val status = Task.STATUS_FIXED }
case class StatusOnly(status: Int) extends ChoicePlan

/** An idempotency row of mobile_choice_submissions. */
case class SubmissionRow(state: String, changesetId: Option[Long], result: Option[JsObject])

/**
  * A submission body that parsed and resolved against its task's payload. `body` is the canonical
  * JSON form, `canonical` the idempotency text and `payloadDigest` the SHA-256 of the payload.
  */
case class ValidatedSubmission(
    task: Task,
    work: ChoiceWork,
    payloadDigest: String,
    body: JsObject,
    canonical: String,
    plan: ChoicePlan
)

/** Why an element no longer matches its payload; `detail` is for diagnostics only. */
case class Staleness(reason: String, detail: JsArray, version: Option[Long])

/**
  * Mobile choice tasks: the uncached eligibility check and the submission that applies answers to
  * OSM in one changeset and then writes the task status. See docs/mobile-oauth.md.
  *
  * Eligibility is all-or-nothing: the element exists and is visible, `match` holds and every
  * question's `expect` holds. Anything else is staleness, recorded in choice_stale (which hides
  * the task from mobile discovery). Staleness never writes a task status.
  */
@Singleton
class MobileChoiceService @Inject() (
    db: Database,
    taskDAL: TaskDAL,
    challengeDAL: ChallengeDAL,
    osm: ChoiceOsmClient,
    oauthStore: MobileOAuthStore,
    cipher: MobileOsmTokenCipher,
    webSocketProvider: WebSocketProvider,
    actionManager: ActionManager
)(implicit ec: ExecutionContext) {
  private val logger       = Logger(getClass)
  private val CheckMillis  = 60000L
  private val LeaseSeconds = 120
  private val checks       = new ConcurrentHashMap[Long, (Long, String, ChoiceResponse)]()
  private val strict = new ObjectMapper()
    .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
    .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)

  private def error(status: Int, code: String, extra: (String, Json.JsValueWrapper)*) =
    ChoiceResponse(
      status,
      Json.obj(Seq[(String, Json.JsValueWrapper)]("error" -> code) ++ extra: _*)
    )
  private def done(response: ChoiceResponse): Future[ChoiceResponse] = Future.successful(response)
  private val osmUnavailable                                         = error(502, "osm_unavailable")
  private val reauth                                                 = error(401, "osm_reauth_required")
  private val pending                                                = error(409, "submission_pending")

  // ---- payload ------------------------------------------------------------------------------

  /** The task, its valid choice payload and the payload's digest, or the ending response. */
  private def load(taskId: Long): Either[ChoiceResponse, (Task, ChoiceWork, String)] =
    taskDAL.retrieveById(taskId) match {
      case None => Left(error(404, "not_found"))
      case Some(task) =>
        task.cooperativeWork match {
          case Some(json) if ChoiceWork.isChoice(json) =>
            ChoiceWork.validate(json) match {
              case Right(work) if task.bundleId.isEmpty =>
                Right((task, work, digest(Json.stringify(json))))
              case _ => Left(error(422, "unsupported_task"))
            }
          case _ => Left(error(422, "unsupported_task"))
        }
    }

  private def digest(value: String): String =
    MessageDigest
      .getInstance("SHA-256")
      .digest(value.getBytes(StandardCharsets.UTF_8))
      .map(b => f"${b & 0xff}%02x")
      .mkString

  // ---- eligibility --------------------------------------------------------------------------

  private case class Observation(read: ElementRead, inUse: Option[Boolean])

  /** Fresh element read; parent ways/relations only when a delete outcome could apply. */
  private def observe(work: ChoiceWork, token: Option[String]): Future[Observation] =
    osm.element(work.elementType, work.elementId, token).flatMap {
      case found: ElementFound
          if work.deleteOutcome.isDefined && work.elementType == "node" &&
            work.matches(found.tags) =>
        osm.nodeInUse(work.elementId, token).map(inUse => Observation(found, Some(inUse)))
      case read => Future.successful(Observation(read, None))
    }

  private def value(v: Option[String]): JsValue = v.map(JsString).getOrElse(JsNull)

  /** Fixed tasks require every guard. Live-filtered tasks need at least one missing key. */
  private def staleness(work: ChoiceWork, read: ElementRead): Either[Staleness, ElementFound] =
    read match {
      case ElementGone => Left(Staleness("element_gone", Json.arr(), None))
      case found: ElementFound =>
        val matchDiff = work.matchTags.toList.sorted.collect {
          case (key, expected) if !found.tags.get(key).contains(expected) =>
            Json.obj("key" -> key, "expected" -> expected, "current" -> value(found.tags.get(key)))
        }
        val keyDiff = for {
          question        <- work.questions
          (key, expected) <- question.expect.toList.sortBy(_._1)
          if !work.liveMissingQuestions && found.tags.get(key) != expected
        } yield Json.obj(
          "question" -> question.id,
          "key"      -> key,
          "expected" -> value(expected),
          "current"  -> value(found.tags.get(key))
        )
        if (matchDiff.nonEmpty)
          Left(Staleness("match_failed", JsArray(matchDiff), Some(found.version)))
        else if (work.liveMissingQuestions && !work.questions.exists(_.holds(found.tags)))
          Left(Staleness("already_tagged", Json.arr(), Some(found.version)))
        else if (keyDiff.nonEmpty)
          Left(Staleness("key_changed", JsArray(keyDiff), Some(found.version)))
        else Right(found)
    }

  /** Insert-only system observation. False when it could not be recorded. */
  private def markStale(taskId: Long, stale: Staleness): Boolean =
    try {
      val detail = Json.stringify(stale.detail)
      val rows = db.withConnection { implicit c =>
        SQL"""INSERT INTO choice_stale(task_id, reason, detail, element_version, payload_md5)
              SELECT id, ${stale.reason}, $detail::jsonb, ${stale.version},
                md5(cooperative_work_json::text) FROM tasks
              WHERE id = $taskId AND cooperative_work_json IS NOT NULL
              ON CONFLICT (task_id) DO NOTHING""".executeUpdate()
      }
      checks.remove(taskId)
      if (rows == 0) logger.info(s"Choice task $taskId was already marked stale")
      true
    } catch {
      case NonFatal(e) =>
        logger.error(s"Could not mark choice task $taskId stale (${stale.reason})", e)
        false
    }

  private def recordedStale(taskId: Long): Option[ChoiceResponse] = db.withConnection {
    implicit c =>
      SQL"SELECT reason, detail::text AS detail FROM choice_stale WHERE task_id = $taskId"
        .as((SqlParser.str("reason") ~ SqlParser.get[Option[String]]("detail")).map {
          case reason ~ detail =>
            ChoiceResponse(
              200,
              Json.obj(
                "eligible" -> false,
                "reason"   -> reason,
                "detail"   -> detail.map(Json.parse).getOrElse[JsValue](Json.arr())
              )
            )
        }.singleOpt)
  }

  /**
    * GET /task/:id/choice/check. Anonymous OSM reads, reused for 60 seconds per task. Its only
    * side effect is the choice_stale insert when it observes a stale element.
    */
  def check(taskId: Long): Future[ChoiceResponse] = load(taskId) match {
    case Left(response) => done(response)
    case Right((_, work, payload)) =>
      val now = System.currentTimeMillis()
      Option(checks.get(taskId)).filter {
        case (at, key, _) => key == payload && now - at < CheckMillis
      } match {
        case Some((_, _, cached)) => done(cached)
        case None =>
          recordedStale(taskId) match {
            case Some(recorded) => done(recorded)
            case None =>
              observe(work, None)
                .map { observation =>
                  val (response, cacheable) = staleness(work, observation.read) match {
                    case Left(stale) =>
                      val body =
                        Json.obj(
                          "eligible" -> false,
                          "reason"   -> stale.reason,
                          "detail"   -> stale.detail
                        )
                      (ChoiceResponse(200, body), markStale(taskId, stale))
                    case Right(found) =>
                      val liveQuestions =
                        if (work.liveMissingQuestions)
                          Json.obj(
                            "questionIds" -> work.questions.filter(_.holds(found.tags)).map(_.id)
                          )
                        else Json.obj()
                      (
                        ChoiceResponse(
                          200,
                          Json.obj(
                            "eligible"       -> true,
                            "deleteAllowed"  -> observation.inUse.contains(false),
                            "elementVersion" -> found.version
                          ) ++ liveQuestions
                        ),
                        true
                      )
                  }
                  if (cacheable) {
                    if (checks.size > 10000) checks.clear()
                    checks.put(taskId, (now, payload, response))
                  }
                  response
                }
                .recover {
                  case e: OsmCallException =>
                    logger.warn(
                      s"Choice check for task $taskId: OSM ${e.status} ${e.detail}",
                      e.getCause
                    )
                    osmUnavailable
                }
          }
      }
  }

  // ---- submission parsing -------------------------------------------------------------------

  private sealed trait Submission { def canonical: String; def json: JsObject }
  private case class Answers(byQuestion: Map[String, String]) extends Submission {
    def canonical: String =
      "answers:" + byQuestion.toSeq.sortBy(_._1).map { case (q, o) => s"$q=$o" }.mkString(",")
    def json: JsObject =
      Json.obj("answers" -> JsObject(byQuestion.toSeq.sortBy(_._1).map {
        case (q, o) => q -> JsString(o)
      }))
  }
  private case class Outcome(id: String, delete: Option[Boolean]) extends Submission {
    def canonical: String = s"outcome:$id:delete=${delete.contains(true)}"
    def json: JsObject =
      Json.obj("outcome" -> id) ++ delete.fold(Json.obj())(flag => Json.obj("delete" -> flag))
  }

  /** Strict: one form only, no unknown or duplicate keys, ids re-checked, at most 8 answers. */
  private def parse(body: String): Option[Submission] = {
    def isId(value: String) = value.matches(ChoiceWork.IdPattern)
    Try { strict.readTree(body); Json.parse(body) }.toOption.flatMap {
      case o: JsObject if o.keys == Set("answers") =>
        o("answers") match {
          case a: JsObject if a.keys.nonEmpty && a.keys.size <= 8 =>
            val pairs = a.fields.collect { case (q, JsString(v)) if isId(q) && isId(v) => q -> v }
            if (pairs.size == a.fields.size) Some(Answers(pairs.toMap)) else None
          case _ => None
        }
      case o: JsObject if o.keys == Set("outcome") || o.keys == Set("outcome", "delete") =>
        (o("outcome"), o.value.get("delete")) match {
          case (JsString(id), None) if isId(id)                  => Some(Outcome(id, None))
          case (JsString(id), Some(JsBoolean(flag))) if isId(id) => Some(Outcome(id, Some(flag)))
          case _                                                 => None
        }
      case _ => None
    }
  }

  private def resolve(work: ChoiceWork, submission: Submission): Either[String, ChoicePlan] =
    submission match {
      case Answers(byQuestion) =>
        val resolved = byQuestion.toList.sortBy(_._1).map {
          case (qid, oid) =>
            work.questions.find(_.id == qid) match {
              case None => Left(s"unknown question '$qid'")
              case Some(q) =>
                q.options.find(_.id == oid).map(q -> _).toRight(s"unknown option '$oid' for '$qid'")
            }
        }
        resolved.collectFirst { case Left(problem) => problem } match {
          case Some(problem) => Left(problem)
          case None          => Right(EditTags(resolved.collect { case Right(pair) => pair }))
        }
      case Outcome(ChoiceWork.TooHard, None) => Right(StatusOnly(Task.STATUS_TOO_HARD))
      case Outcome(ChoiceWork.TooHard, _)    => Left("delete is only allowed on a delete outcome")
      case Outcome(id, delete) =>
        work.outcomes.find(_.id == id) match {
          case None                                         => Left(s"unknown outcome '$id'")
          case Some(o) if o.delete && delete.contains(true) => Right(DeleteNode)
          case Some(o) if o.delete                          => Right(StatusOnly(Task.STATUS_FALSE_POSITIVE))
          case Some(_) if delete.isDefined                  => Left("delete is only allowed on a delete outcome")
          case Some(o)                                      => Right(StatusOnly(o.status.get))
        }
    }

  // ---- idempotency rows ---------------------------------------------------------------------

  private def lockHolder(taskId: Long): Option[Long] = db.withConnection { implicit c =>
    SQL"""SELECT user_id FROM locked WHERE item_type = ${TaskType().typeId}
          AND (item_id = $taskId OR $taskId = ANY(bundled_tasks))"""
      .as(SqlParser.long("user_id").*)
      .headOption
  }

  private def row(taskId: Long, userId: Long, key: String): Option[SubmissionRow] =
    db.withConnection { implicit c =>
      SQL"""SELECT state, changeset_id, result_json::text AS result FROM mobile_choice_submissions
            WHERE task_id = $taskId AND user_id = $userId AND submission_key = $key"""
        .as(
          (SqlParser.str("state") ~ SqlParser.get[Option[Long]]("changeset_id") ~
            SqlParser.get[Option[String]]("result")).map {
            case state ~ changeset ~ result =>
              SubmissionRow(state, changeset, result.map(Json.parse(_).as[JsObject]))
          }.singleOpt
        )
    }

  private def otherPending(taskId: Long, userId: Long, key: String): Boolean =
    db.withConnection { implicit c =>
      SQL"""SELECT EXISTS(SELECT 1 FROM mobile_choice_submissions WHERE task_id = $taskId
            AND state <> 'done' AND NOT (user_id = $userId AND submission_key = $key))"""
        .as(SqlParser.scalar[Boolean].single)
    }

  /** A started row this request works on. All writes are fenced by the attempt id. */
  private class Claim(val taskId: Long, val userId: Long, val key: String) {
    val attempt: String = UUID.randomUUID().toString

    /** New row; false when any unfinished submission holds the task (partial unique index). */
    def insert(): Boolean = db.withConnection { implicit c =>
      SQL"""INSERT INTO mobile_choice_submissions
            (task_id, user_id, submission_key, state, attempt, lease_until)
            VALUES ($taskId, $userId, $key, 'started', $attempt,
              NOW() + make_interval(secs => $LeaseSeconds))
            ON CONFLICT DO NOTHING""".executeUpdate() == 1
    }

    /** Takes over an existing started row unless another request's lease is still running. */
    def take(): Boolean = db.withConnection { implicit c =>
      SQL"""UPDATE mobile_choice_submissions SET attempt = $attempt, updated_at = NOW(),
            lease_until = NOW() + make_interval(secs => $LeaseSeconds)
            WHERE task_id = $taskId AND user_id = $userId AND submission_key = $key
            AND state = 'started' AND (lease_until IS NULL OR lease_until < NOW())"""
        .executeUpdate() == 1
    }

    private def fenced(update: Connection => Int, what: String): Unit = {
      val rows = db.withConnection(update)
      if (rows != 1) {
        logger.error(s"Choice task $taskId: lost the submission row while trying to $what")
        throw new IllegalStateException("submission row lost")
      }
    }
    def changeset(cs: Option[Long]): Unit = fenced(
      implicit c =>
        SQL"""UPDATE mobile_choice_submissions SET changeset_id = $cs, updated_at = NOW()
              WHERE task_id = $taskId AND user_id = $userId AND submission_key = $key
              AND attempt = $attempt AND state = 'started'""".executeUpdate(),
      "record a changeset"
    )
    def uploaded(cs: Long): Unit = fenced(
      implicit c =>
        SQL"""UPDATE mobile_choice_submissions SET state = 'uploaded', changeset_id = $cs,
              lease_until = NULL, updated_at = NOW()
              WHERE task_id = $taskId AND user_id = $userId AND submission_key = $key
              AND attempt = $attempt""".executeUpdate(),
      "mark the upload"
    )

    /** Leaves the row for a later retry (upload outcome unknown). */
    def release(): Unit =
      Try(db.withConnection { implicit c =>
        SQL"""UPDATE mobile_choice_submissions SET lease_until = NULL, updated_at = NOW()
              WHERE task_id = $taskId AND user_id = $userId AND submission_key = $key
              AND attempt = $attempt""".executeUpdate()
      }).failed.foreach(e => logger.error(s"Choice task $taskId: could not release lease", e))

    /** Nothing reached OSM: forget the submission. */
    def drop(): Unit =
      Try(db.withConnection { implicit c =>
        SQL"""DELETE FROM mobile_choice_submissions WHERE task_id = $taskId AND user_id = $userId
              AND submission_key = $key AND attempt = $attempt AND state = 'started'"""
          .executeUpdate()
      }).failed.foreach(e => logger.error(s"Choice task $taskId: could not drop submission row", e))
  }

  /** The grant's OSM token when it can still write, or the response to give. */
  private def osmToken(familyId: String, user: User): Either[ChoiceResponse, String] =
    if (!cipher.available) Left(error(503, "osm_edits_unavailable"))
    else
      oauthStore.osmToken(familyId) match {
        case None => Left(reauth)
        case Some(stored) if stored.userId != user.id =>
          logger.error(s"OSM token of grant family $familyId belongs to another user")
          Left(reauth)
        case Some(stored) if !stored.osmScope.split(" ").contains("write_api") =>
          logger.warn(s"OSM token of grant family $familyId lacks write_api; dropping it")
          oauthStore.deleteOsmToken(familyId)
          Left(reauth)
        case Some(stored) =>
          cipher.open(user.id, stored.token) match {
            case Right(token) => Right(token)
            case Left(problem) =>
              logger.error(s"OSM token of grant family $familyId is $problem; dropping it")
              oauthStore.deleteOsmToken(familyId)
              Left(reauth)
          }
      }

  /**
    * Parses a submission body and resolves it against the task's payload, without reading OSM or
    * writing anything. The same checks, in the same order, as the start of [[submit]].
    */
  def validateSubmission(taskId: Long, body: String): Either[ChoiceResponse, ValidatedSubmission] =
    parse(body) match {
      case None => Left(error(400, "invalid_request"))
      case Some(submission) =>
        load(taskId).flatMap {
          case (task, work, payload) =>
            resolve(work, submission) match {
              case Left(detail) => Left(error(422, "invalid_submission", "detail" -> detail))
              case Right(plan) =>
                Right(
                  ValidatedSubmission(
                    task,
                    work,
                    payload,
                    submission.json,
                    submission.canonical,
                    plan
                  )
                )
            }
        }
    }

  // ---- submit -------------------------------------------------------------------------------

  /** POST /task/:id/choice. The filter has already authenticated a mobile tasks:write grant. */
  def submit(
      taskId: Long,
      user: User,
      scopes: Set[String],
      familyId: String,
      body: String
  ): Future[ChoiceResponse] =
    validateSubmission(taskId, body) match {
      case Left(response) => done(response)
      case Right(valid) =>
        val edits = !valid.plan.isInstanceOf[StatusOnly]
        if (edits && !scopes.contains(MobileScopes.TagFix))
          done(error(403, "insufficient_scope", "scope" -> MobileScopes.TagFix))
        else {
          // The payload is part of the key: a re-uploaded task is a new submission.
          val key =
            digest(s"${valid.task.id}\n${user.id}\n${valid.payloadDigest}\n${valid.canonical}")
          run(valid.task, valid.work, valid.plan, user, familyId, key)
        }
    }

  private def run(
      task: Task,
      work: ChoiceWork,
      plan: ChoicePlan,
      user: User,
      familyId: String,
      key: String
  ): Future[ChoiceResponse] = {
    val claim = new Claim(task.id, user.id, key)
    row(task.id, user.id, key) match {
      // Before the lock check: a completed submission resent gets the same answer.
      case Some(SubmissionRow("done", _, Some(result))) => done(ChoiceResponse(200, result))
      // Uploaded but the status write did not commit: finish it, never upload twice. The status
      // write itself refuses if another user holds the lock.
      case Some(SubmissionRow("uploaded", cs, _)) => done(finish(task.id, user, key, plan, cs))
      case Some(SubmissionRow("started", cs, _)) =>
        if (!claim.take()) done(pending)
        else
          cs match {
            // The outcome of an earlier upload is unknown: OSM tells.
            case Some(id) =>
              withToken(claim, familyId, user)(token =>
                resume(claim, task, work, plan, user, familyId, token, id)
              )
            case None => fresh(claim, task, work, plan, user, familyId, rowExists = true)
          }
      case _ => fresh(claim, task, work, plan, user, familyId, rowExists = false)
    }
  }

  private def withToken(claim: Claim, familyId: String, user: User)(
      next: String => Future[ChoiceResponse]
  ): Future[ChoiceResponse] =
    osmToken(familyId, user) match {
      case Left(response) => claim.release(); done(response)
      case Right(token)   => next(token)
    }

  /** Lock, transition and pending checks, then the non-editing result or the OSM edit. */
  private def fresh(
      claim: Claim,
      task: Task,
      work: ChoiceWork,
      plan: ChoicePlan,
      user: User,
      familyId: String,
      rowExists: Boolean
  ): Future[ChoiceResponse] = {
    val paused     = challengeDAL.retrieveById(task.parent).exists(_.extra.paused)
    val allowReset = task.completedBy.contains(user.id)
    val refusal =
      if (!lockHolder(task.id).contains(user.id)) Some(error(409, "lock_required"))
      else if (paused || !Task.isValidStatusProgression(
                 task.status.getOrElse(Task.STATUS_CREATED),
                 plan.status,
                 allowReset
               )) Some(error(409, "invalid_transition"))
      else if (otherPending(task.id, user.id, claim.key)) Some(pending)
      else None
    refusal match {
      case Some(response) =>
        if (rowExists) claim.release()
        done(response)
      case None =>
        plan match {
          case StatusOnly(_) => done(finish(task.id, user, claim.key, plan, None))
          case _ =>
            withToken(claim, familyId, user) { token =>
              Attempt(claim, task, work, plan, user, familyId, token).edit(rowExists)
            }
        }
    }
  }

  /** A started row with a changeset: if it holds changes, the upload landed. */
  private def resume(
      claim: Claim,
      task: Task,
      work: ChoiceWork,
      plan: ChoicePlan,
      user: User,
      familyId: String,
      token: String,
      cs: Long
  ): Future[ChoiceResponse] = {
    val attempt = Attempt(claim, task, work, plan, user, familyId, token)
    osm
      .changesCount(cs, Some(token))
      .transformWith {
        case Success(count) if count > 0 =>
          claim.uploaded(cs)
          attempt.closeQuietly(cs).map(_ => finish(task.id, user, claim.key, plan, Some(cs)))
        case Success(_) =>
          claim.changeset(None)
          attempt
            .closeQuietly(cs)
            .flatMap(_ => fresh(claim, task, work, plan, user, familyId, rowExists = true))
        case Failure(OsmCallException(401 | 403, _, _)) => done(attempt.unauthorized(None))
        case Failure(e) =>
          logger.error(
            s"Choice task ${task.id}: cannot read changeset $cs to resume (${describe(e)})"
          )
          claim.release()
          done(osmUnavailable)
      }
  }

  private def describe(e: Throwable): String = e match {
    case OsmCallException(code, detail, _) => s"OSM $code $detail"
    case other                             => other.getClass.getSimpleName
  }

  /** One OSM edit for a claimed submission. Every branch that created a changeset closes it. */
  private case class Attempt(
      claim: Claim,
      task: Task,
      work: ChoiceWork,
      plan: ChoicePlan,
      user: User,
      familyId: String,
      token: String
  ) {
    def closeQuietly(cs: Long): Future[Unit] =
      osm.close(cs, token).recover {
        case e: OsmCallException =>
          logger.warn(s"Choice task ${task.id}: closing changeset $cs failed (${describe(e)})")
      }

    /** OSM rejected the token: drop it (the MapRoulette grant stays) and the row. */
    def unauthorized(cs: Option[Long]): ChoiceResponse = {
      logger.warn(s"Choice task ${task.id}: OSM rejected the token of grant family $familyId")
      Try(oauthStore.deleteOsmToken(familyId)).failed
        .foreach(e => logger.error("Could not drop rejected OSM token", e))
      claim.drop()
      reauth
    }

    /** Staleness at submit: record it, release the lock, write no status. */
    private def stale(found: Staleness): ChoiceResponse = {
      markStale(task.id, found)
      try taskDAL.unlockItem(user, task)
      catch {
        case NonFatal(e) => logger.warn(s"Choice task ${task.id}: unlock failed: ${e.getMessage}")
      }
      error(409, "task_ineligible", "reason" -> found.reason, "detail" -> found.detail)
    }

    /** A selected question was answered in OSM meanwhile; other questions may remain. */
    private def changedAnswer(): ChoiceResponse = {
      try taskDAL.unlockItem(user, task)
      catch {
        case NonFatal(e) => logger.warn(s"Choice task ${task.id}: unlock failed: ${e.getMessage}")
      }
      error(409, "task_ineligible", "reason" -> "key_changed")
    }

    private def verify(): Future[Either[ChoiceResponse, ElementFound]] =
      observe(work, Some(token)).map { observation =>
        staleness(work, observation.read) match {
          case Left(found) => Left(stale(found))
          case Right(found) if work.liveMissingQuestions && (plan match {
                case edit: EditTags => !edit.answers.forall(_._1.holds(found.tags))
                case _              => false
              }) =>
            Left(changedAnswer())
          case Right(_) if plan == DeleteNode && observation.inUse.contains(true) =>
            Left(error(409, "element_in_use"))
          case Right(found) => Right(found)
        }
      }

    /** Failures before any changeset exists: nothing reached OSM. */
    private def beforeChangeset(e: Throwable): ChoiceResponse = e match {
      case OsmCallException(401 | 403, _, _) => unauthorized(None)
      case other =>
        logger.warn(s"Choice task ${task.id}: OSM read failed (${describe(other)})", other.getCause)
        claim.drop()
        osmUnavailable
    }

    def edit(rowExists: Boolean): Future[ChoiceResponse] =
      verify().transformWith {
        case Failure(e: OsmCallException) => done(beforeChangeset(e))
        case Failure(e)                   => claim.drop(); Future.failed(e)
        case Success(Left(response)) =>
          claim.drop(); done(response)
        case Success(Right(found)) =>
          if (!rowExists && !claim.insert()) done(pending)
          else
            osm.createChangeset(changesetTags(), token).transformWith {
              case Success(cs) =>
                Future(claim.changeset(Some(cs)))
                  .flatMap(_ => upload(found, cs, retried = false))
                  .recoverWith {
                    // Unexpected failures (e.g. the database) still close the changeset.
                    case NonFatal(e) =>
                      logger.error(s"Choice task ${task.id}: failed with changeset $cs open", e)
                      claim.release()
                      closeQuietly(cs).flatMap(_ => Future.failed(e))
                  }
              case Failure(e) => done(beforeChangeset(e))
            }
      }

    private def changesetTags(): Seq[(String, String)] = {
      val general = challengeDAL.retrieveById(task.parent).map(_.general)
      val comment = general.map(_.checkinComment.trim).filter(_.nonEmpty)
      Seq(
        "created_by"                                                         -> "MapRoulette",
        "comment"                                                            -> comment.getOrElse(s"MapRoulette task ${task.id}")
      ) ++ general.map(_.checkinSource.trim).filter(_.nonEmpty).map("source" -> _)
    }

    private def change(found: ElementFound, cs: Long) = plan match {
      case edit: EditTags => ChoiceOsmClient.modify(found, found.tags ++ edit.set -- edit.unset, cs)
      case _              => ChoiceOsmClient.delete(found, cs)
    }

    private def upload(found: ElementFound, cs: Long, retried: Boolean): Future[ChoiceResponse] = {
      def end(response: ChoiceResponse): Future[ChoiceResponse] = {
        claim.drop(); closeQuietly(cs).map(_ => response)
      }
      osm.upload(cs, change(found, cs), token).transformWith {
        case Success((200, _)) =>
          claim.uploaded(cs)
          closeQuietly(cs).map(_ => finish(task.id, user, claim.key, plan, Some(cs)))
        case Success((409, _)) if !retried =>
          // The element changed between the read and the upload: re-check once (step 5).
          verify().transformWith {
            case Success(Right(current)) => upload(current, cs, retried = true)
            case Success(Left(response)) => end(response)
            case Failure(OsmCallException(401 | 403, _, _)) =>
              closeQuietly(cs).map(_ => unauthorized(Some(cs)))
            case Failure(e) =>
              logger.warn(s"Choice task ${task.id}: re-check failed (${describe(e)})")
              end(osmUnavailable)
          }
        case Success((409, detail)) =>
          logger.warn(s"Choice task ${task.id}: second upload conflict: $detail")
          end(error(409, "osm_conflict"))
        case Success((412, _)) => end(error(409, "element_in_use"))
        case Success((401 | 403, _)) =>
          closeQuietly(cs).map(_ => unauthorized(Some(cs)))
        case Success((code, detail)) if code >= 400 && code < 500 =>
          // MapRoulette built a change OSM does not accept: a server bug, not an outage.
          logger.error(s"Choice task ${task.id}: OSM rejected the upload ($code): $detail")
          end(error(500, "server_error"))
        case other =>
          // 5xx or no answer: the upload may have landed. The row keeps the changeset, so a
          // retry asks OSM for its changes_count before uploading again.
          logger.warn(
            s"Choice task ${task.id}: upload outcome unknown for changeset $cs (${other match {
              case Success((code, detail)) => s"OSM $code $detail"
              case Failure(e)              => describe(e)
            }})"
          )
          claim.release()
          closeQuietly(cs).map(_ => osmUnavailable)
      }
    }
  }

  // ---- status -------------------------------------------------------------------------------

  private def applied(plan: ChoicePlan, cs: Option[Long]): JsObject = plan match {
    case edit: EditTags if cs.isDefined =>
      Json.obj("set" -> edit.set, "unset" -> edit.unset, "deleted" -> false)
    case DeleteNode if cs.isDefined =>
      Json.obj("set" -> Json.obj(), "unset" -> Json.arr(), "deleted" -> true)
    case _ => Json.obj("set" -> Json.obj(), "unset" -> Json.arr(), "deleted" -> false)
  }

  /**
    * Writes the status, tasks.changeset_id and the done row in one transaction. After an upload a
    * failure is `status_pending`, and a retry of the same submission finishes it here.
    */
  private def finish(
      taskId: Long,
      user: User,
      key: String,
      plan: ChoicePlan,
      cs: Option[Long]
  ): ChoiceResponse = {
    val status = if (cs.isDefined) Task.STATUS_FIXED else plan.status
    val result = Json.obj("status" -> status, "changesetId" -> cs, "applied" -> applied(plan, cs))
    val answers = plan match {
      case edit: EditTags =>
        Some(JsObject(edit.answers.map { case (q, o) => q.id -> JsString(o.id) }))
      case _ => None
    }
    Try(writeStatus(taskId, user, status, cs, key, result, answers)) match {
      case Success(_) => ChoiceResponse(200, result)
      case Failure(e) if cs.isDefined =>
        logger.error(s"Choice task $taskId: status write failed after changeset ${cs.get}", e)
        error(500, "status_pending", "changesetId" -> cs.get)
      case Failure(e: NotFoundException) =>
        logger.warn(s"Choice task $taskId: gone before its status write")
        error(404, "not_found")
      case Failure(e: IllegalAccessException) =>
        logger.warn(s"Choice task $taskId: status write refused: ${e.getMessage}")
        error(409, "lock_required")
      case Failure(e: InvalidException) =>
        logger.warn(s"Choice task $taskId: status write refused: ${e.getMessage}")
        error(409, "invalid_transition")
      case Failure(e) =>
        logger.error(s"Choice task $taskId: status write failed", e)
        error(500, "server_error")
    }
  }

  /** The same status path as PUT /task/:id/:status (cache refresh, lock release, events). */
  protected def writeStatus(
      taskId: Long,
      user: User,
      status: Int,
      cs: Option[Long],
      key: String,
      result: JsObject,
      answers: Option[JsObject]
  ): Unit = {
    val task     = taskDAL.retrieveById(taskId).getOrElse(throw new NotFoundException("Task is gone"))
    val released = taskDAL.resolveLockReleaseTasks(task)
    taskDAL.setTaskStatus(
      List(task),
      status,
      user,
      inTransaction = { implicit c: Connection =>
        cs.foreach(id => SQL"UPDATE tasks SET changeset_id = $id WHERE id = $taskId".executeUpdate()
        )
        val json   = Json.stringify(result)
        val chosen = answers.map(Json.stringify)
        SQL"""INSERT INTO mobile_choice_submissions
              (task_id, user_id, submission_key, state, changeset_id, result_json, answers)
              VALUES ($taskId, ${user.id}, $key, 'done', $cs, $json::jsonb, $chosen::jsonb)
              ON CONFLICT (task_id, user_id, submission_key) DO UPDATE SET state = 'done',
                changeset_id = EXCLUDED.changeset_id, result_json = EXCLUDED.result_json,
                answers = EXCLUDED.answers, lease_until = NULL, updated_at = NOW()"""
          .executeUpdate()
        ()
      }
    )
    // Committed. Side effects below must not turn a written status into an error.
    def quietly(what: String)(effect: => Unit): Unit =
      try effect
      catch { case NonFatal(e) => logger.warn(s"Choice task $taskId: $what failed", e) }
    quietly("cache refresh")(if (cs.isDefined) taskDAL.cacheManager.cache.remove(taskId))
    quietly("release notification")(
      webSocketProvider.sendMessage(
        if (released.length > 1)
          WebSocketMessages.tasksReleased(released, Some(WebSocketMessages.userSummary(user)))
        else WebSocketMessages.taskReleased(task, Some(WebSocketMessages.userSummary(user)))
      )
    )
    quietly("status action")(
      actionManager.setAction(Some(user), new TaskItem(task.id), TaskStatusSet(status), task.name)
    )
  }
}

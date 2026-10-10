package org.maproulette.provider.choice

import akka.actor.ActorSystem
import anorm._
import java.time.{Duration, Instant}
import javax.inject.{Inject, Singleton}
import org.maproulette.auth.mobile.{MobileOAuthSettings, MobileWritePolicy}
import org.maproulette.auth.mobile.guest.{MobileGuest, MobileGuestService}
import org.maproulette.framework.model.Task
import play.api.db.Database
import play.api.libs.json._
import scala.concurrent.{ExecutionContext, Future}

/**
  * Pending answers (deferred sign-up): a guest answers a live-filtered choice task, the answer is
  * held here, and the task is hidden from other mappers until `holdUntil`. Nothing is written to
  * OSM or to the task's status; that happens when the guest links an OSM account. See
  * docs/mobile-oauth.md.
  */
@Singleton
class MobileChoicePendingService @Inject() (
    choice: MobileChoiceService,
    store: ChoicePendingStore,
    settings: MobileOAuthSettings,
    writePolicy: MobileWritePolicy,
    db: Database,
    actorSystem: ActorSystem
) {
  // Blocking JDBC runs on the mobile OAuth dispatcher, as in MobileGuestService.
  private implicit lazy val executionContext: ExecutionContext =
    actorSystem.dispatchers.lookup("mobile-oauth-dispatcher")

  /** How long a pending answer keeps its task out of other mappers' discovery. */
  val Hold: Duration = Duration.ofDays(7)

  /** Pending answers per guest. */
  val Limit = 200

  /** The admin's "publish for mobile" tag. */
  val PublishedTag = "mobile-survey-v1"

  private val open = Set(Task.STATUS_CREATED, Task.STATUS_SKIPPED, Task.STATUS_TOO_HARD)

  private def error(status: Int, code: String, extra: (String, Json.JsValueWrapper)*) =
    ChoiceResponse(
      status,
      Json.obj(Seq[(String, Json.JsValueWrapper)]("error" -> code) ++ extra: _*)
    )
  private def done(response: ChoiceResponse): Future[ChoiceResponse] = Future.successful(response)

  /** Enabled and tagged for mobile by the admin. */
  private def published(challengeId: Long): Boolean = db.withConnection { implicit c =>
    SQL"""SELECT EXISTS(SELECT 1 FROM challenges c
          JOIN tags_on_challenges tc ON tc.challenge_id = c.id
          JOIN tags t ON t.id = tc.tag_id
          WHERE c.id = $challengeId AND c.enabled AND t.name = $PublishedTag)"""
      .as(SqlParser.scalar[Boolean].single)
  }

  private def writesOff: Boolean = settings.writeControlEnabled && !writePolicy.enabled

  private def answered(plan: ChoicePlan): List[String] = plan match {
    case EditTags(answers) => answers.map(_._1.id)
    case _                 => Nil
  }

  /** POST /task/:id/choice/pending. The filter has already authenticated a live guest. */
  def submit(guest: MobileGuest, taskId: Long, body: String): Future[ChoiceResponse] =
    Future(choice.validateSubmission(taskId, body)).flatMap {
      case Left(response) => done(response)
      case Right(valid) if !valid.work.liveMissingQuestions =>
        done(error(422, "unsupported_task"))
      case Right(valid) if valid.plan == DeleteNode =>
        done(
          error(422, "invalid_submission", "detail" -> "delete is not allowed for pending answers")
        )
      case Right(valid) if !open.contains(valid.task.status.getOrElse(Task.STATUS_CREATED)) =>
        done(error(409, "task_completed"))
      case Right(valid) =>
        Future(writesOff && !published(valid.task.parent)).flatMap {
          case true => done(error(403, "challenge_not_published"))
          case false =>
            choice.check(taskId).flatMap {
              case response if response.status != 200 => done(response)
              case response if !(response.body \ "eligible").asOpt[Boolean].contains(true) =>
                done(
                  ChoiceResponse(
                    409,
                    Json.obj(
                      "error"  -> "task_ineligible",
                      "reason" -> (response.body \ "reason").toOption,
                      "detail" -> (response.body \ "detail").toOption.getOrElse[JsValue](Json.arr())
                    )
                  )
                )
              case response
                  if answered(valid.plan)
                    .exists(id => !(response.body \ "questionIds").as[List[String]].contains(id)) =>
                // A chosen question was answered in OSM meanwhile, as in submit.
                done(
                  error(409, "task_ineligible", "reason" -> "key_changed", "detail" -> Json.arr())
                )
              case response =>
                Future(save(guest, valid, (response.body \ "elementVersion").as[Long]))
            }
        }
    }

  private def save(
      guest: MobileGuest,
      valid: ValidatedSubmission,
      version: Long
  ): ChoiceResponse = {
    val now = Instant.now()
    store.save(
      PendingWrite(
        guest.id,
        valid.task.id,
        valid.task.parent,
        valid.body,
        valid.payloadDigest,
        version,
        now.plus(Hold)
      ),
      Limit,
      now.plus(MobileGuestService.Retention),
      now
    ) match {
      case Left(PendingProblem.Limit) => error(429, "pending_limit")
      // The token was valid a moment ago; the guest was deleted, claimed or expired since.
      case Left(PendingProblem.GuestGone) => error(401, "invalid_token")
      case Right((row, expires)) =>
        ChoiceResponse(
          200,
          Json.obj(
            "taskId"     -> row.taskId,
            "state"      -> row.state,
            "answeredAt" -> row.answeredAt.toString,
            "holdUntil"  -> row.holdUntil.toString,
            "expiresAt"  -> expires.toString
          )
        )
    }
  }

  /** DELETE /task/:id/choice/pending: withdraws the guest's pending answer. */
  def withdraw(guest: MobileGuest, taskId: Long): Future[ChoiceResponse] =
    Future(store.withdraw(guest.id, taskId)).map {
      case true  => ChoiceResponse(204, Json.obj())
      case false => error(404, "not_found")
    }

  /** GET /mobile-guest/pending: every answer of the guest, newest first. */
  def list(
      guest: MobileGuest,
      limit: Option[String],
      after: Option[String]
  ): Future[ChoiceResponse] = {
    val size   = limit.fold(Option(50))(_.toIntOption.filter(n => n >= 1 && n <= 100))
    val before = after.map(_.toLongOption.filter(_ > 0))
    (size, before) match {
      case (None, _) | (_, Some(None)) => done(error(400, "invalid_request"))
      case (Some(n), cursor) =>
        Future(store.list(guest.id, n + 1, cursor.flatten)).map { rows =>
          val page = rows.take(n)
          ChoiceResponse(
            200,
            Json.obj(
              "items" -> page.map { row =>
                Json.obj(
                  "taskId"      -> row.taskId,
                  "challengeId" -> row.challengeId,
                  "state"       -> row.state,
                  "answeredAt"  -> row.answeredAt.toString,
                  "holdUntil"   -> row.holdUntil.toString,
                  "result"      -> row.result
                )
              },
              "next" -> (if (rows.size > n) page.lastOption.map(_.id.toString) else None)
            )
          )
        }
    }
  }
}

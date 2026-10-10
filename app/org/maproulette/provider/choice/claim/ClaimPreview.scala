package org.maproulette.provider.choice.claim

import akka.actor.ActorSystem
import anorm._
import java.time.Instant
import java.util.UUID
import javax.inject.{Inject, Singleton}
import org.maproulette.auth.mobile.guest.{MobileGuest, MobileGuestStore}
import org.maproulette.auth.mobile.{MobileOAuthSettings, MobileSecrets, MobileWritePolicy}
import org.maproulette.provider.choice.ChoiceWork
import play.api.db.Database
import play.api.libs.json._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

/** One pending answer with what the claim page shows about its task and campaign. */
case class PreviewRow(
    taskId: Long,
    challengeId: Long,
    body: JsValue,
    answeredAt: Instant,
    taskName: Option[String],
    lat: Option[Double],
    lon: Option[Double],
    payload: Option[String],
    challengeName: String,
    checkinComment: Option[String],
    infoLink: Option[String]
)

@com.google.inject.ImplementedBy(classOf[ClaimPreviewRepository])
trait ClaimPreviewStore {
  def rows(guest: UUID, limit: Int): Seq[PreviewRow]
}

@Singleton
class ClaimPreviewRepository @Inject() (db: Database) extends ClaimPreviewStore {
  override def rows(guest: UUID, limit: Int): Seq[PreviewRow] =
    db.withConnection { implicit c =>
      SQL("""SELECT p.task_id, p.challenge_id, p.body::text AS body, p.answered_at, t.name AS task_name,
          ST_Y(t.location) AS lat, ST_X(t.location) AS lon, t.cooperative_work_json::text AS payload,
          c.name AS challenge_name, c.checkin_comment, c.info_link
        FROM choice_pending p JOIN tasks t ON t.id = p.task_id
          JOIN challenges c ON c.id = p.challenge_id
        WHERE p.guest_id = {id}::uuid AND p.state = 'pending'
        ORDER BY p.answered_at, p.id LIMIT {limit}""")
        .on("id" -> guest.toString, "limit" -> limit)
        .as(RowParser { row =>
          Success(
            PreviewRow(
              row[Long]("task_id"),
              row[Long]("challenge_id"),
              Json.parse(row[String]("body")),
              row[java.util.Date]("answered_at").toInstant,
              row[Option[String]]("task_name"),
              row[Option[Double]]("lat"),
              row[Option[Double]]("lon"),
              row[Option[String]]("payload"),
              row[String]("challenge_name"),
              row[Option[String]]("checkin_comment"),
              row[Option[String]]("info_link")
            )
          )
        }.*)
    }
}

/**
  * `POST /api/v2/mobile-claim/preview` (deferred sign-up B5, API plan §3.6 and §3.10): what the
  * claim page shows before sign-in. Does not consume the token. Only the guest's three newest
  * claim tokens work; anything else is None (404), so token existence isn't disclosed.
  */
@Singleton
class ClaimPreviewService @Inject() (
    tokens: GuestJobStore,
    guests: MobileGuestStore,
    store: ClaimPreviewStore,
    settings: MobileOAuthSettings,
    writePolicy: MobileWritePolicy,
    actorSystem: ActorSystem
) {
  private implicit lazy val ec: ExecutionContext =
    actorSystem.dispatchers.lookup("mobile-oauth-dispatcher")

  /** Pending answers are capped at 200 per guest (MobileChoicePendingService.Limit). */
  val MaxRows = 200

  def writesEnabled: Boolean =
    settings.allowTaskWrites && (!settings.writeControlEnabled || writePolicy.enabled)

  def preview(token: String, now: Instant = Instant.now()): Future[Option[JsObject]] = Future {
    if (!token.matches("[A-Za-z0-9_-]{43}")) None
    else
      tokens
        .claimToken(MobileSecrets.hash(token))
        .flatMap(found => guests.get(found.guest))
        .map { guest =>
          ClaimPreview.state(guest, now) match {
            case "active" =>
              ClaimPreview.body(guest, store.rows(guest.id, MaxRows), writesEnabled)
            case other => Json.obj("state" -> other, "expiresAt" -> guest.expiresAt.toString)
          }
        }
  }
}

object ClaimPreview {
  def state(guest: MobileGuest, now: Instant): String =
    if (guest.deletedAt.isDefined) "deleted"
    else if (guest.claimed) "claimed"
    else if (!guest.expiresAt.isAfter(now)) "expired"
    else "active"

  private def work(row: PreviewRow): Option[ChoiceWork] =
    row.payload
      .flatMap(text => Try(Json.parse(text)).toOption)
      .flatMap(json => ChoiceWork.validate(json).toOption)

  /** The answer as the guest saw it: question prompt and option label, or the outcome's label. */
  private def answers(row: PreviewRow, choice: Option[ChoiceWork]): Seq[JsObject] =
    (row.body \ "answers").asOpt[Map[String, String]] match {
      case Some(byQuestion) =>
        byQuestion.toSeq.sortBy(_._1).map {
          case (qid, oid) =>
            val question = choice.flatMap(_.questions.find(_.id == qid))
            Json.obj(
              "questionId" -> qid,
              "prompt"     -> question.fold(qid)(_.prompt),
              "optionId"   -> oid,
              "answer"     -> question.flatMap(_.options.find(_.id == oid)).fold(oid)(_.label)
            )
        }
      case None =>
        (row.body \ "outcome").asOpt[String].toSeq.map { id =>
          val label =
            if (id == ChoiceWork.TooHard) "Not sure"
            else choice.flatMap(_.outcomes.find(_.id == id)).fold(id)(_.label)
          Json.obj("outcome" -> id, "answer" -> label)
        }
    }

  def body(guest: MobileGuest, rows: Seq[PreviewRow], writesEnabled: Boolean): JsObject = {
    val works = rows.map(row => row -> work(row))
    val challenges = rows.groupBy(_.challengeId).toSeq.sortBy(_._2.head.answeredAt).map {
      case (id, group) =>
        val first = group.head
        Json.obj(
          "id"      -> id,
          "name"    -> first.challengeName,
          "pending" -> group.size,
          // The campaign hashtag the changesets will carry, e.g. "#StreetTallySLCStops": the
          // first one that isn't MapRoulette's own.
          "hashtag" -> first.checkinComment
            .flatMap { comment =>
              val tags = "#[A-Za-z0-9_]+".r.findAllIn(comment).toSeq
              tags
                .find(_.toLowerCase(java.util.Locale.ROOT) != "#maproulette")
                .orElse(tags.headOption)
            }
            .map(JsString)
            .getOrElse[JsValue](JsNull),
          "wikiUrl" -> first.infoLink
            .filter(_.startsWith("https://"))
            .map(JsString)
            .getOrElse[JsValue](JsNull)
        )
    }
    val tasks = works.map {
      case (row, choice) =>
        Json.obj(
          "taskId"      -> row.taskId,
          "challengeId" -> row.challengeId,
          "label"       -> row.taskName,
          "lat"         -> row.lat,
          "lon"         -> row.lon,
          "answeredAt"  -> row.answeredAt.toString,
          "answers"     -> answers(row, choice)
        )
    }
    // Every option of every answered question, counted, so the page can pick what it needs.
    val summary = works
      .flatMap {
        case (row, choice) =>
          (row.body \ "answers").asOpt[Map[String, String]].getOrElse(Map.empty).toSeq.map {
            case (qid, oid) =>
              (row.challengeId, qid, oid, choice.flatMap(_.questions.find(_.id == qid)))
          }
      }
      .groupBy { case (challenge, qid, _, _) => (challenge, qid) }
      .toSeq
      .sortBy { case ((challenge, qid), _) => (challenge, qid) }
      .map {
        case ((challenge, qid), items) =>
          val question = items.flatMap(_._4).headOption
          val counts   = items.groupBy(_._3).map { case (oid, hits) => oid -> hits.size }
          val options = question.fold(counts.keys.toSeq.sorted.map(id => id -> id))(
            _.options.map(o => o.id -> o.label)
          )
          Json.obj(
            "challengeId" -> challenge,
            "questionId"  -> qid,
            "label"       -> question.fold(qid)(_.prompt),
            "options"     -> options.map { case (id, label) => Json.obj("id" -> id, "label" -> label) },
            "counts" -> JsObject(options.map {
              case (id, _) => id -> JsNumber(BigDecimal(counts.getOrElse(id, 0): Int))
            })
          )
      }
    Json.obj(
      "state"         -> "active",
      "expiresAt"     -> guest.expiresAt.toString,
      "pending"       -> rows.size,
      "challenges"    -> challenges,
      "tasks"         -> tasks,
      "summary"       -> summary,
      "writesEnabled" -> writesEnabled
    )
  }
}

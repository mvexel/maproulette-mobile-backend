package org.maproulette.provider.choice

import anorm._
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import javax.inject.{Inject, Singleton}
import play.api.db.Database
import play.api.libs.json.{JsObject, Json}

/** A guest's answer to a choice task, held until it is published or expires. */
case class PendingAnswer(
    id: Long,
    guestId: Option[UUID],
    taskId: Long,
    challengeId: Long,
    body: JsObject,
    payloadDigest: String,
    elementVersion: Long,
    answeredAt: Instant,
    holdUntil: Instant,
    state: String,
    result: Option[JsObject]
)

/** A pending answer to store; it replaces the guest's own pending answer for the task. */
case class PendingWrite(
    guestId: UUID,
    taskId: Long,
    challengeId: Long,
    body: JsObject,
    payloadDigest: String,
    elementVersion: Long,
    holdUntil: Instant
)

sealed trait PendingProblem
object PendingProblem {

  /** Deleted, expired or claimed since the token was checked. */
  case object GuestGone extends PendingProblem

  /** The guest already holds the maximum number of pending answers. */
  case object Limit extends PendingProblem
}

@com.google.inject.ImplementedBy(classOf[ChoicePendingRepository])
trait ChoicePendingStore {

  /**
    * Inserts or replaces the guest's pending answer for the task and extends the guest's expiry to
    * at least `guestExpiresAt`. Returns the row and the guest's expiry.
    */
  def save(
      write: PendingWrite,
      limit: Int,
      guestExpiresAt: Instant,
      now: Instant
  ): Either[PendingProblem, (PendingAnswer, Instant)]

  /** Deletes the guest's pending answer for the task; false when there was none. */
  def withdraw(guestId: UUID, taskId: Long): Boolean

  /** How many of the guest's answers are in each state. */
  def counts(guestId: UUID): Map[String, Int]

  /** The guest's answers in every state, newest first, starting below `before` (a row id). */
  def list(guestId: UUID, limit: Int, before: Option[Long]): List[PendingAnswer]

  /** Which of these tasks a live pending answer holds (the same rows `excludePending` hides). */
  def held(taskIds: Seq[Long]): Set[Long]
}

@Singleton
class ChoicePendingRepository @Inject() (db: Database) extends ChoicePendingStore {
  private def stamp(value: Instant): Timestamp = Timestamp.from(value)
  private val columns =
    """id, guest_id::text AS guest, task_id, challenge_id, body::text AS body, payload_digest,
       element_version, answered_at, hold_until, state, result::text AS result"""
  private val answer: RowParser[PendingAnswer] = RowParser { row =>
    anorm.Success(
      PendingAnswer(
        row[Long]("id"),
        row[Option[String]]("guest").map(UUID.fromString),
        row[Long]("task_id"),
        row[Long]("challenge_id"),
        Json.parse(row[String]("body")).as[JsObject],
        row[String]("payload_digest"),
        row[Long]("element_version"),
        row[java.util.Date]("answered_at").toInstant,
        row[java.util.Date]("hold_until").toInstant,
        row[String]("state"),
        row[Option[String]]("result").map(Json.parse(_).as[JsObject])
      )
    )
  }

  override def save(
      write: PendingWrite,
      limit: Int,
      guestExpiresAt: Instant,
      now: Instant
  ): Either[PendingProblem, (PendingAnswer, Instant)] =
    db.withTransaction { implicit c =>
      // The guest row lock serializes one guest's writes, so the limit count is exact.
      val live = SQL(
        """SELECT 1 AS live FROM mobile_guests WHERE id={guest}::uuid AND deleted_at IS NULL
           AND claimed_user_id IS NULL AND expires_at > {now} FOR UPDATE"""
      ).on("guest" -> write.guestId.toString, "now" -> stamp(now))
        .as(SqlParser.int("live").singleOpt)
        .isDefined
      if (!live) Left(PendingProblem.GuestGone)
      else {
        val params = Seq[NamedParameter](
          "guest"   -> write.guestId.toString,
          "task"    -> write.taskId,
          "parent"  -> write.challengeId,
          "body"    -> Json.stringify(write.body),
          "digest"  -> write.payloadDigest,
          "version" -> write.elementVersion,
          "hold"    -> stamp(write.holdUntil),
          "now"     -> stamp(now)
        )
        val replaced = SQL(
          s"""UPDATE choice_pending SET challenge_id={parent}, body={body}::jsonb,
              payload_digest={digest}, element_version={version}, answered_at={now},
              hold_until={hold}, updated_at={now}
              WHERE guest_id={guest}::uuid AND task_id={task} AND state='pending'
              RETURNING $columns"""
        ).on(params: _*).as(answer.singleOpt)
        val saved = replaced.orElse {
          val count = SQL(
            "SELECT count(*) FROM choice_pending WHERE guest_id={guest}::uuid AND state='pending'"
          ).on("guest" -> write.guestId.toString).as(SqlParser.scalar[Long].single)
          if (count >= limit) None
          else
            Some(
              SQL(
                s"""INSERT INTO choice_pending (guest_id, task_id, challenge_id, body,
                    payload_digest, element_version, answered_at, hold_until, updated_at)
                    VALUES ({guest}::uuid, {task}, {parent}, {body}::jsonb, {digest}, {version},
                    {now}, {hold}, {now})
                    RETURNING $columns"""
              ).on(params: _*).as(answer.single)
            )
        }
        saved match {
          case None => Left(PendingProblem.Limit)
          case Some(row) =>
            val expires = SQL(
              """UPDATE mobile_guests SET expires_at=GREATEST(expires_at, {expires}),
                 last_seen_at={now} WHERE id={guest}::uuid RETURNING expires_at"""
            ).on(
                "guest"   -> write.guestId.toString,
                "expires" -> stamp(guestExpiresAt),
                "now"     -> stamp(now)
              )
              .as(SqlParser.get[java.util.Date]("expires_at").single)
              .toInstant
            Right((row, expires))
        }
      }
    }

  override def withdraw(guestId: UUID, taskId: Long): Boolean =
    db.withConnection { implicit c =>
      SQL(
        """DELETE FROM choice_pending WHERE guest_id={guest}::uuid AND task_id={task}
           AND state='pending'"""
      ).on("guest" -> guestId.toString, "task" -> taskId).executeUpdate() == 1
    }

  override def counts(guestId: UUID): Map[String, Int] =
    db.withConnection { implicit c =>
      SQL(
        "SELECT state, count(*)::int AS n FROM choice_pending WHERE guest_id={guest}::uuid GROUP BY state"
      ).on("guest" -> guestId.toString)
        .as((SqlParser.str("state") ~ SqlParser.int("n")).map { case state ~ n => state -> n }.*)
        .toMap
    }

  override def list(guestId: UUID, limit: Int, before: Option[Long]): List[PendingAnswer] =
    db.withConnection { implicit c =>
      SQL(
        s"""SELECT $columns FROM choice_pending WHERE guest_id={guest}::uuid
            AND ({before}::bigint IS NULL OR id < {before}::bigint)
            ORDER BY id DESC LIMIT {limit}"""
      ).on("guest" -> guestId.toString, "before" -> before, "limit" -> limit).as(answer.*)
    }

  override def held(taskIds: Seq[Long]): Set[Long] =
    if (taskIds.isEmpty) Set.empty
    else
      db.withConnection { implicit c =>
        SQL(
          """SELECT DISTINCT task_id FROM choice_pending WHERE task_id IN ({ids})
             AND state = 'pending' AND hold_until > NOW()"""
        ).on("ids" -> taskIds.distinct)
          .as(SqlParser.long("task_id").*)
          .toSet
      }
}

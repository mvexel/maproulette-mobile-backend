package org.maproulette.auth.mobile.guest

import anorm._
import java.sql.{Connection, Timestamp}
import java.time.Instant
import java.util.UUID
import javax.inject.{Inject, Singleton}
import play.api.db.Database

/**
  * A deferred sign-up guest: answers choice tasks before having an OSM account. Never a `users`
  * row. The guest secret and email are not part of this view.
  */
case class MobileGuest(
    id: UUID,
    clientId: String,
    createdAt: Instant,
    expiresAt: Instant,
    emailSet: Boolean,
    emailVerified: Boolean,
    remindersStopped: Boolean,
    claimedUserId: Option[Long],
    claimedAt: Option[Instant],
    exchangedAt: Option[Instant],
    deletedAt: Option[Instant]
) {
  def live(now: Instant): Boolean = deletedAt.isEmpty && expiresAt.isAfter(now)
  def claimed: Boolean            = claimedUserId.isDefined
}

/** Why the guest secret did not mint a guest access token. */
sealed trait GuestTokenProblem
object GuestTokenProblem {

  /** Unknown guest, wrong secret or client, deleted or expired: all look alike to the caller. */
  case object InvalidGrant extends GuestTokenProblem

  /** The guest was claimed: the app should exchange the secret for a user grant instead. */
  case object Claimed extends GuestTokenProblem
}

@com.google.inject.ImplementedBy(classOf[MobileGuestRepository])
trait MobileGuestStore {
  def create(
      id: UUID,
      clientId: String,
      secretHash: String,
      expiresAt: Instant,
      now: Instant
  ): MobileGuest
  def get(id: UUID): Option[MobileGuest]

  /** Checks the secret and records a new access token, atomically. Clears the guest's expired tokens. */
  def issueToken(
      id: UUID,
      clientId: String,
      secretHash: String,
      tokenHash: String,
      tokenExpiresAt: Instant,
      now: Instant
  ): Either[GuestTokenProblem, MobileGuest]

  /** The live, unclaimed guest an unexpired access token belongs to. */
  def authenticate(tokenHash: String, now: Instant): Option[MobileGuest]

  /**
    * "Delete my data": removes the secret, email and access tokens and leaves a tombstone, so a
    * reused secret fails cleanly and old claim links can say the data was deleted. False if the
    * guest was unknown or already deleted.
    */
  def delete(id: UUID, now: Instant): Boolean
}

@Singleton
class MobileGuestRepository @Inject() (db: Database) extends MobileGuestStore {
  private def stamp(value: Instant): Timestamp = Timestamp.from(value)
  private def digest(value: String): Unit =
    require(value.matches("[0-9a-f]{64}"), "Expected SHA-256 digest")
  private def instant(row: Row, column: String): Option[Instant] =
    row[Option[java.util.Date]](column).map(_.toInstant)
  private val guest: RowParser[MobileGuest] = RowParser { row =>
    Success(
      MobileGuest(
        row[UUID]("id"),
        row[String]("client_id"),
        row[java.util.Date]("created_at").toInstant,
        row[java.util.Date]("expires_at").toInstant,
        row[Option[Array[Byte]]]("email_ciphertext").isDefined,
        instant(row, "email_verified_at").isDefined,
        instant(row, "reminders_stopped_at").isDefined,
        row[Option[Long]]("claimed_user_id"),
        instant(row, "claimed_at"),
        instant(row, "exchanged_at"),
        instant(row, "deleted_at")
      )
    )
  }

  override def create(
      id: UUID,
      clientId: String,
      secretHash: String,
      expiresAt: Instant,
      now: Instant
  ): MobileGuest = {
    digest(secretHash)
    require(expiresAt.isAfter(now))
    db.withConnection { implicit c =>
      SQL("""INSERT INTO mobile_guests (id,client_id,secret_hash,created_at,last_seen_at,expires_at)
        VALUES ({id}::uuid,{client},{secret},{now},{now},{expires}) RETURNING *""")
        .on(
          "id"      -> id.toString,
          "client"  -> clientId,
          "secret"  -> secretHash,
          "now"     -> stamp(now),
          "expires" -> stamp(expiresAt)
        )
        .as(guest.single)
    }
  }

  override def get(id: UUID): Option[MobileGuest] =
    db.withConnection { implicit c =>
      SQL("SELECT * FROM mobile_guests WHERE id={id}::uuid")
        .on("id" -> id.toString)
        .as(guest.singleOpt)
    }

  override def issueToken(
      id: UUID,
      clientId: String,
      secretHash: String,
      tokenHash: String,
      tokenExpiresAt: Instant,
      now: Instant
  ): Either[GuestTokenProblem, MobileGuest] = {
    digest(secretHash); digest(tokenHash)
    db.withTransaction { implicit c =>
      SQL("""SELECT * FROM mobile_guests WHERE id={id}::uuid AND client_id={client}
        AND secret_hash={secret} FOR UPDATE""")
        .on("id" -> id.toString, "client" -> clientId, "secret" -> secretHash)
        .as(guest.singleOpt) match {
        case Some(value) if !value.live(now) => Left(GuestTokenProblem.InvalidGrant)
        case Some(value) if value.claimed    => Left(GuestTokenProblem.Claimed)
        case Some(value) =>
          SQL("DELETE FROM mobile_guest_tokens WHERE guest_id={id}::uuid AND expires_at<={now}")
            .on("id" -> id.toString, "now" -> stamp(now))
            .executeUpdate()
          SQL("""INSERT INTO mobile_guest_tokens (token_hash,guest_id,expires_at)
            VALUES ({token},{id}::uuid,{expires})""")
            .on("token" -> tokenHash, "id" -> id.toString, "expires" -> stamp(tokenExpiresAt))
            .executeUpdate()
          touch(id, now)
          Right(value)
        case None => Left(GuestTokenProblem.InvalidGrant)
      }
    }
  }

  private def touch(id: UUID, now: Instant)(implicit c: Connection): Unit = {
    SQL("UPDATE mobile_guests SET last_seen_at={now} WHERE id={id}::uuid")
      .on("id" -> id.toString, "now" -> stamp(now))
      .executeUpdate()
    ()
  }

  override def authenticate(tokenHash: String, now: Instant): Option[MobileGuest] =
    if (!tokenHash.matches("[0-9a-f]{64}")) None
    else
      db.withConnection { implicit c =>
        SQL("""SELECT g.* FROM mobile_guest_tokens t JOIN mobile_guests g ON g.id=t.guest_id
          WHERE t.token_hash={token} AND t.expires_at>{now} AND g.deleted_at IS NULL
          AND g.expires_at>{now} AND g.claimed_user_id IS NULL""")
          .on("token" -> tokenHash, "now" -> stamp(now))
          .as(guest.singleOpt)
      }

  override def delete(id: UUID, now: Instant): Boolean =
    db.withTransaction { implicit c =>
      SQL("DELETE FROM mobile_guest_tokens WHERE guest_id={id}::uuid")
        .on("id" -> id.toString)
        .executeUpdate()
      // Answers not yet published; published ones are already in OSM.
      SQL("DELETE FROM choice_pending WHERE guest_id={id}::uuid AND state='pending'")
        .on("id" -> id.toString)
        .executeUpdate()
      SQL("""UPDATE mobile_guests SET secret_hash=NULL, email_ciphertext=NULL, email_nonce=NULL,
        deleted_at={now} WHERE id={id}::uuid AND deleted_at IS NULL""")
        .on("id" -> id.toString, "now" -> stamp(now))
        .executeUpdate() == 1
    }
}

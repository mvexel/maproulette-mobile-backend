package org.maproulette.provider.choice.claim

import anorm._
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.sql.Timestamp
import java.time.format.DateTimeFormatter
import java.time.{Duration, Instant, ZoneId}
import java.util.{Locale, UUID}
import javax.crypto.Cipher
import javax.crypto.spec.{GCMParameterSpec, SecretKeySpec}
import javax.inject.{Inject, Singleton}
import org.maproulette.auth.mobile.guest.MobileGuest
import org.maproulette.auth.mobile.{MobileOAuthSettings, MobileSecrets}
import play.api.db.Database
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

case class SealedEmail(ciphertext: Array[Byte], nonce: Array[Byte])

/**
  * AES-256-GCM for guest email addresses under `mobileOAuth.osmTokenKey`, with the guest id as
  * associated data, so a row copied to another guest does not decrypt. No key, no email.
  */
@Singleton
class GuestEmailCipher @Inject() (settings: MobileOAuthSettings) {
  private val random = new SecureRandom()
  private def cipher(mode: Int, key: Array[Byte], nonce: Array[Byte], guest: UUID): Cipher = {
    val value = Cipher.getInstance("AES/GCM/NoPadding")
    value.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce))
    value.updateAAD(s"maproulette-mobile-guest-email:v1:$guest".getBytes(StandardCharsets.UTF_8))
    value
  }

  def available: Boolean = settings.osmTokenKey.isDefined

  def seal(guest: UUID, email: String): Option[SealedEmail] = settings.osmTokenKey.map { key =>
    val nonce = new Array[Byte](12)
    random.nextBytes(nonce)
    SealedEmail(
      cipher(Cipher.ENCRYPT_MODE, key, nonce, guest)
        .doFinal(email.getBytes(StandardCharsets.UTF_8)),
      nonce
    )
  }

  def open(guest: UUID, value: SealedEmail): Option[String] =
    settings.osmTokenKey.flatMap { key =>
      Try(
        new String(
          cipher(Cipher.DECRYPT_MODE, key, value.nonce, guest).doFinal(value.ciphertext),
          StandardCharsets.UTF_8
        )
      ).toOption
    }
}

@com.google.inject.ImplementedBy(classOf[GuestEmailRepository])
trait GuestEmailStore {

  /** Stores the sealed address on a live, unclaimed guest. False if the guest no longer qualifies. */
  def setEmail(guest: UUID, email: SealedEmail, now: Instant): Boolean

  /** Claim tokens created for the guest after `since`: each email send creates one. */
  def sendsSince(guest: UUID, since: Instant): Int

  def addClaimToken(guest: UUID, tokenHash: String, now: Instant): Unit
}

@Singleton
class GuestEmailRepository @Inject() (db: Database) extends GuestEmailStore {
  private def stamp(value: Instant) = Timestamp.from(value)

  override def setEmail(guest: UUID, email: SealedEmail, now: Instant): Boolean =
    db.withConnection { implicit c =>
      SQL(
        """UPDATE mobile_guests SET email_ciphertext={ct}, email_nonce={nonce},
          email_set_at={now}, email_verified_at=NULL
        WHERE id={id}::uuid AND deleted_at IS NULL AND claimed_user_id IS NULL AND expires_at>{now}"""
      ).on(
          "ct"    -> email.ciphertext,
          "nonce" -> email.nonce,
          "now"   -> stamp(now),
          "id"    -> guest.toString
        )
        .executeUpdate() == 1
    }

  override def sendsSince(guest: UUID, since: Instant): Int =
    db.withConnection { implicit c =>
      SQL("""SELECT count(*) FROM mobile_guest_claim_tokens
        WHERE guest_id={id}::uuid AND created_at>{since}""")
        .on("id" -> guest.toString, "since" -> stamp(since))
        .as(SqlParser.scalar[Long].single)
        .toInt
    }

  override def addClaimToken(guest: UUID, tokenHash: String, now: Instant): Unit =
    db.withConnection { implicit c =>
      SQL("""INSERT INTO mobile_guest_claim_tokens (token_hash,guest_id,created_at)
        VALUES ({token},{id}::uuid,{now})""")
        .on("token" -> tokenHash, "id" -> guest.toString, "now" -> stamp(now))
        .executeUpdate()
      ()
    }
}

/** What the emails say about a guest's saved answers: the campaign of their first answer. */
case class GuestSummary(
    campaignName: String,
    organizerName: Option[String],
    savedCount: Int,
    firstAnswerAt: Instant
)

/**
  * Reads the guest's pending answers (B3's `choice_pending`). Until B3 lands this returns None,
  * so the email route answers 409 `nothing_saved`.
  */
@com.google.inject.ImplementedBy(classOf[GuestSummaryRepository])
trait GuestSummaries {
  def summary(guest: UUID): Option[GuestSummary]
}

@Singleton
class GuestSummaryRepository @Inject() (db: Database) extends GuestSummaries {
  override def summary(guest: UUID): Option[GuestSummary] =
    db.withConnection { implicit c =>
      val exists = SQL("SELECT to_regclass('choice_pending') IS NOT NULL")
        .as(SqlParser.scalar[Boolean].single)
      if (!exists) None
      else
        SQL("""SELECT c.name, count(*) OVER () AS saved, min(p.answered_at) OVER () AS first_at
          FROM choice_pending p JOIN challenges c ON c.id = p.challenge_id
          WHERE p.guest_id={id}::uuid AND p.state='pending'
          ORDER BY p.answered_at LIMIT 1""")
          .on("id" -> guest.toString)
          .as(
            (SqlParser.str("name") ~ SqlParser.long("saved") ~ SqlParser.date("first_at")).map {
              case name ~ saved ~ first => GuestSummary(name, None, saved.toInt, first.toInstant)
            }.singleOpt
          )
    }
}

sealed abstract class GuestEmailError(val status: Int, val code: String)
object GuestEmailError {
  case object InvalidRequest  extends GuestEmailError(400, "invalid_request")
  case object GuestClaimed    extends GuestEmailError(409, "guest_claimed")
  case object NothingSaved    extends GuestEmailError(409, "nothing_saved")
  case object RateLimited     extends GuestEmailError(429, "email_rate_limited")
  case object MailUnavailable extends GuestEmailError(503, "mail_unavailable")
}

/**
  * `PUT /api/v2/mobile-guest/email` (deferred sign-up B4): stores the address sealed, creates a
  * claim token and sends the link email. The token is never stored or logged in clear.
  */
@Singleton
class GuestEmailService @Inject() (
    store: GuestEmailStore,
    summaries: GuestSummaries,
    cipher: GuestEmailCipher,
    mailer: ClaimMailer,
    settings: ClaimMailSettings,
    actorSystem: akka.actor.ActorSystem
) {
  private val logger = play.api.Logger(getClass)
  // Blocking JDBC runs on the mobile OAuth dispatcher, as in MobileGuestService.
  private implicit lazy val ec: ExecutionContext =
    actorSystem.dispatchers.lookup("mobile-oauth-dispatcher")
  val sendsPerDay = 3

  /** Campaign time zone for dates in emails; the pilot is in Salt Lake City. */
  val zone: ZoneId = ZoneId.of("America/Denver")

  def valid(email: String): Boolean =
    email.length <= 254 && email.count(_ == '@') == 1 && !email.startsWith("@") &&
      !email.endsWith("@") && !email.exists(ch => ch.isWhitespace || ch.isControl)

  private def links(token: String): Map[String, String] = Map(
    "claimUrl"         -> s"${settings.claimOrigin}/claim#t=$token",
    "deleteUrl"        -> s"${settings.claimOrigin}/claim/delete#t=$token",
    "stopRemindersUrl" -> s"${settings.claimOrigin}/claim/stop-reminders#t=$token",
    "privacyUrl"       -> s"${settings.claimOrigin}/privacy"
  )

  private val long  = DateTimeFormatter.ofPattern("EEEE, MMM d", Locale.US)
  private val short = DateTimeFormatter.ofPattern("MMM d", Locale.US)

  def claimValues(guest: MobileGuest, summary: GuestSummary, token: String): Map[String, String] =
    links(token) ++ Map(
      "campaignName"     -> summary.campaignName,
      "organizerName"    -> summary.organizerName.getOrElse("Street Tally"),
      "eventDate"        -> long.format(summary.firstAnswerAt.atZone(zone)),
      "nounMany"         -> "bus stops",
      "savedStopsText"   -> ClaimEmails.count(summary.savedCount, "bus stop", "bus stops"),
      "savedAnswersText" -> ClaimEmails.count(summary.savedCount, "answer", "answers"),
      "deadline"         -> short.format(guest.expiresAt.atZone(zone))
    )

  def setEmail(guest: MobileGuest, email: String): Future[Either[GuestEmailError, MobileGuest]] = {
    val now     = Instant.now()
    val address = email.trim
    if (!valid(address)) Future.successful(Left(GuestEmailError.InvalidRequest))
    else if (guest.claimed) Future.successful(Left(GuestEmailError.GuestClaimed))
    else if (!mailer.available || !cipher.available)
      Future.successful(Left(GuestEmailError.MailUnavailable))
    else
      Future {
        if (store.sendsSince(guest.id, now.minus(Duration.ofDays(1))) >= sendsPerDay)
          Left(GuestEmailError.RateLimited)
        else
          summaries.summary(guest.id) match {
            case None => Left(GuestEmailError.NothingSaved)
            case Some(summary) =>
              val sealedEmail = cipher.seal(guest.id, address).get
              if (!store.setEmail(guest.id, sealedEmail, now)) Left(GuestEmailError.GuestClaimed)
              else {
                val token = MobileSecrets.generate()
                store.addClaimToken(guest.id, MobileSecrets.hash(token), now)
                Right(
                  ClaimEmails.render(ClaimEmails.Claim, address, claimValues(guest, summary, token))
                )
              }
          }
      }.flatMap {
        case Left(error) => Future.successful(Left(error))
        case Right(message) =>
          mailer.send(message).map {
            case Right(_) => Right(guest.copy(emailSet = true, emailVerified = false))
            case Left(reason) =>
              logger.error(s"Guest link email not sent: $reason")
              Left(GuestEmailError.MailUnavailable)
          }
      }
  }
}

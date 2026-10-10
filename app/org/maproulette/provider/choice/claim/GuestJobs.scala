package org.maproulette.provider.choice.claim

import akka.actor.ActorSystem
import anorm._
import java.sql.Timestamp
import java.time.{Duration, Instant}
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.{Inject, Singleton}
import org.maproulette.auth.mobile.guest.MobileGuestStore
import org.maproulette.auth.mobile.{MobileOAuthSettings, MobileSecrets}
import play.api.db.Database
import play.api.inject.{ApplicationLifecycle, SimpleModule, bind}
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal

/** A reminder that is due: `which` is 1 (a day after the first email) or 2 (five days before expiry). */
case class ReminderDue(guest: UUID, expiresAt: Instant, email: SealedEmail, which: Int)

/** An unclaimed guest past `expires_at` that still has an address or pending answers. */
case class ExpiryDue(guest: UUID, expiresAt: Instant, email: Option[SealedEmail])

/** What a claim token in an emailed link (`/claim/delete#t=`, `/claim/stop-reminders#t=`) names. */
case class ClaimTokenGuest(guest: UUID, claimed: Boolean)

@com.google.inject.ImplementedBy(classOf[GuestJobRepository])
trait GuestJobStore {
  def remindersDue(now: Instant, limit: Int): Seq[ReminderDue]

  /** Records the reminder before it is sent, so it goes out at most once. False if no longer due. */
  def markReminded(guest: UUID, which: Int, now: Instant): Boolean

  /** Undoes [[markReminded]] when the provider refused the mail, so the next run tries again. */
  def unmarkReminded(guest: UUID, which: Int): Unit

  def expiriesDue(now: Instant, limit: Int): Seq[ExpiryDue]

  /** Pending answers become `expired` and the guest's access tokens go. Returns the rows expired. */
  def expire(guest: UUID, now: Instant): Int

  def clearEmail(guest: UUID): Unit

  /** Deletes unclaimed guests that expired before `before`. Their answers stay, without a guest. */
  def purge(before: Instant): Int

  /** The guest behind one of its three newest claim tokens; older and unknown tokens are None. */
  def claimToken(tokenHash: String): Option[ClaimTokenGuest]

  /** Stops (or resumes) reminder mails. Deletes nothing. */
  def setReminders(guest: UUID, enabled: Boolean, now: Instant): Unit
}

@Singleton
class GuestJobRepository @Inject() (db: Database) extends GuestJobStore {
  private def stamp(value: Instant) = Timestamp.from(value)
  private def column(which: Int)    = if (which == 1) "reminded_1_at" else "reminded_2_at"
  private val sealedEmail = SqlParser.byteArray("email_ciphertext") ~ SqlParser.byteArray(
    "email_nonce"
  ) map { case ct ~ nonce => SealedEmail(ct, nonce) }

  override def remindersDue(now: Instant, limit: Int): Seq[ReminderDue] =
    db.withConnection { implicit c =>
      // The first email is the guest's oldest claim token. A guest that gives its address late
      // gets only the second reminder, and none in its first day.
      SQL(
        """SELECT g.id, g.expires_at, g.email_ciphertext, g.email_nonce,
          CASE WHEN g.expires_at > {secondFrom} THEN 1 ELSE 2 END AS which
        FROM mobile_guests g
        WHERE g.deleted_at IS NULL AND g.claimed_user_id IS NULL
          AND g.reminders_stopped_at IS NULL AND g.email_ciphertext IS NOT NULL
          AND g.expires_at > {now}
          AND ((g.reminded_1_at IS NULL AND g.expires_at > {secondFrom})
            OR (g.reminded_2_at IS NULL AND g.expires_at <= {secondFrom}))
          AND (SELECT min(t.created_at) FROM mobile_guest_claim_tokens t WHERE t.guest_id = g.id)
            <= {dayAgo}
          AND EXISTS (SELECT 1 FROM choice_pending p WHERE p.guest_id = g.id AND p.state = 'pending')
        ORDER BY g.expires_at LIMIT {limit}"""
      ).on(
          "now"        -> stamp(now),
          "secondFrom" -> stamp(now.plus(GuestJobs.SecondReminderBefore)),
          "dayAgo"     -> stamp(now.minus(GuestJobs.FirstReminderAfter)),
          "limit"      -> limit
        )
        .as(
          (SqlParser.get[UUID]("id") ~ SqlParser.date("expires_at") ~ sealedEmail ~
            SqlParser.int("which")).map {
            case id ~ expires ~ email ~ which => ReminderDue(id, expires.toInstant, email, which)
          }.*
        )
    }

  override def markReminded(guest: UUID, which: Int, now: Instant): Boolean =
    db.withConnection { implicit c =>
      SQL(s"""UPDATE mobile_guests SET ${column(which)}={now}
        WHERE id={id}::uuid AND ${column(which)} IS NULL AND reminders_stopped_at IS NULL
          AND deleted_at IS NULL AND claimed_user_id IS NULL AND email_ciphertext IS NOT NULL""")
        .on("id" -> guest.toString, "now" -> stamp(now))
        .executeUpdate() == 1
    }

  override def unmarkReminded(guest: UUID, which: Int): Unit =
    db.withConnection { implicit c =>
      SQL(s"UPDATE mobile_guests SET ${column(which)}=NULL WHERE id={id}::uuid")
        .on("id" -> guest.toString)
        .executeUpdate()
      ()
    }

  override def expiriesDue(now: Instant, limit: Int): Seq[ExpiryDue] =
    db.withConnection { implicit c =>
      SQL("""SELECT g.id, g.expires_at, g.email_ciphertext, g.email_nonce FROM mobile_guests g
        WHERE g.deleted_at IS NULL AND g.claimed_user_id IS NULL AND g.expires_at <= {now}
          AND (g.email_ciphertext IS NOT NULL OR EXISTS (
            SELECT 1 FROM choice_pending p WHERE p.guest_id = g.id AND p.state = 'pending'))
        ORDER BY g.expires_at LIMIT {limit}""")
        .on("now" -> stamp(now), "limit" -> limit)
        .as(
          (SqlParser.get[UUID]("id") ~ SqlParser.date("expires_at") ~ sealedEmail.?).map {
            case id ~ expires ~ email => ExpiryDue(id, expires.toInstant, email)
          }.*
        )
    }

  override def expire(guest: UUID, now: Instant): Int =
    db.withTransaction { implicit c =>
      SQL("DELETE FROM mobile_guest_tokens WHERE guest_id={id}::uuid")
        .on("id" -> guest.toString)
        .executeUpdate()
      SQL("""UPDATE choice_pending SET state='expired', updated_at={now}
        WHERE guest_id={id}::uuid AND state='pending'""")
        .on("id" -> guest.toString, "now" -> stamp(now))
        .executeUpdate()
    }

  override def clearEmail(guest: UUID): Unit =
    db.withConnection { implicit c =>
      SQL("UPDATE mobile_guests SET email_ciphertext=NULL, email_nonce=NULL WHERE id={id}::uuid")
        .on("id" -> guest.toString)
        .executeUpdate()
      ()
    }

  override def purge(before: Instant): Int =
    db.withTransaction { implicit c =>
      // Anything still pending is expired first, so no answer outlives its guest as pending.
      SQL("""UPDATE choice_pending p SET state='expired', updated_at=now()
        FROM mobile_guests g WHERE p.guest_id = g.id AND p.state = 'pending'
          AND g.claimed_user_id IS NULL AND g.expires_at < {before}""")
        .on("before" -> stamp(before))
        .executeUpdate()
      // Tokens cascade; choice_pending.guest_id is set to NULL.
      SQL("DELETE FROM mobile_guests WHERE claimed_user_id IS NULL AND expires_at < {before}")
        .on("before" -> stamp(before))
        .executeUpdate()
    }

  override def claimToken(tokenHash: String): Option[ClaimTokenGuest] =
    if (!tokenHash.matches("[0-9a-f]{64}")) None
    else
      db.withConnection { implicit c =>
        SQL(
          """SELECT g.id, g.claimed_user_id IS NOT NULL AS claimed FROM (
            SELECT token_hash, guest_id, row_number() OVER (
              PARTITION BY guest_id ORDER BY created_at DESC, token_hash) AS rank
            FROM mobile_guest_claim_tokens
            WHERE guest_id = (SELECT guest_id FROM mobile_guest_claim_tokens WHERE token_hash={token})
          ) t JOIN mobile_guests g ON g.id = t.guest_id
          WHERE t.token_hash={token} AND t.rank <= 3"""
        ).on("token" -> tokenHash)
          .as((SqlParser.get[UUID]("id") ~ SqlParser.bool("claimed")).map {
            case id ~ claimed => ClaimTokenGuest(id, claimed)
          }.singleOpt)
      }

  override def setReminders(guest: UUID, enabled: Boolean, now: Instant): Unit =
    db.withConnection { implicit c =>
      SQL(s"""UPDATE mobile_guests SET reminders_stopped_at=${if (enabled) "NULL"
      else "coalesce(reminders_stopped_at, {now})"} WHERE id={id}::uuid""")
        .on("id" -> guest.toString, "now" -> stamp(now))
        .executeUpdate()
      ()
    }
}

/** One run of the hourly job, for the log. Counts only; nothing personal. */
case class GuestJobReport(reminded: Int, expired: Int, purged: Int, failures: Int)

object GuestJobs {
  val FirstReminderAfter: Duration   = Duration.ofDays(1)
  val SecondReminderBefore: Duration = Duration.ofDays(5)

  /** How long a refused expiry notice is retried before the address is deleted anyway. */
  val NoticeRetry: Duration = Duration.ofDays(1)

  /** Expired guests are deleted this long after `expires_at`, keeping their answers for statistics. */
  val PurgeAfter: Duration = Duration.ofDays(30)
  val BatchSize            = 500

  /** "Your 14 bus stops are waiting"; "5 days left to put your bus stops on the map". */
  def reminderSubject(which: Int, savedStopsText: String, savedCount: Int, daysLeft: Long): String =
    if (which == 1) s"Your $savedStopsText ${if (savedCount == 1) "is" else "are"} waiting"
    else
      s"${ClaimEmails.count(daysLeft.toInt, "day", "days")} left to put your bus stops on the map"
}

/**
  * The deferred sign-up mail and retention job (API plan §6 "Expiry job"), hourly:
  * reminders a day after the first email and five days before expiry, at most once each and never
  * after "stop reminders"; on expiry, pending answers become `expired`, the expiry notice goes out
  * and the address is deleted; thirty days after expiry the guest row goes, its answers stay
  * without a guest id. Claim tokens stay until then, so an old link can say "expired".
  */
@Singleton
class GuestJobService @Inject() (
    store: GuestJobStore,
    emailStore: GuestEmailStore,
    summaries: GuestSummaries,
    cipher: GuestEmailCipher,
    mailer: ClaimMailer,
    emails: GuestEmailService,
    actorSystem: ActorSystem
) {
  private val logger = play.api.Logger(getClass)
  private implicit lazy val ec: ExecutionContext =
    actorSystem.dispatchers.lookup("mobile-oauth-dispatcher")

  private def sequentially[A](items: Seq[A])(step: A => Future[Boolean]): Future[(Int, Int)] =
    items.foldLeft(Future.successful((0, 0))) { (previous, item) =>
      previous.flatMap {
        case (done, failed) =>
          step(item)
            .recover {
              case NonFatal(e) =>
                // Class only: messages could echo an address.
                logger.error(s"Guest job step failed: ${e.getClass.getSimpleName}")
                false
            }
            .map(ok => if (ok) (done + 1, failed) else (done, failed + 1))
      }
    }

  private def remind(due: ReminderDue, now: Instant): Future[Boolean] =
    (cipher.open(due.guest, due.email), summaries.summary(due.guest)) match {
      case (Some(address), Some(summary)) if store.markReminded(due.guest, due.which, now) =>
        // Each email carries a fresh claim token; the stored hashes can't be turned back into links.
        val token = MobileSecrets.generate()
        emailStore.addClaimToken(due.guest, MobileSecrets.hash(token), now)
        val values   = emails.linkValues(due.expiresAt, summary, token)
        val daysLeft = math.max(1L, Duration.between(now, due.expiresAt).plusHours(23).toDays)
        val subject = GuestJobs.reminderSubject(
          due.which,
          values("savedStopsText"),
          summary.savedCount,
          daysLeft
        )
        mailer
          .send(
            ClaimEmails
              .render(ClaimEmails.Reminder, address, values + ("reminderSubject" -> subject))
          )
          .map {
            case Right(_) => true
            case Left(reason) =>
              logger.warn(s"Guest reminder ${due.which} not sent: $reason")
              store.unmarkReminded(due.guest, due.which)
              false
          }
      case (None, _) =>
        logger.error("Guest reminder skipped: the address does not decrypt with the current key")
        Future.successful(false)
      case _ => Future.successful(false)
    }

  private def expire(due: ExpiryDue, now: Instant): Future[Boolean] = {
    // Read the summary while the answers are still pending, or on a retry, already expired.
    val summary = summaries.summary(due.guest, Set("pending", "expired"))
    store.expire(due.guest, now)
    val address = due.email.flatMap(cipher.open(due.guest, _))
    (address, summary) match {
      case (Some(to), Some(value)) if mailer.available =>
        mailer
          .send(ClaimEmails.render(ClaimEmails.Expiry, to, emails.values(due.expiresAt, value)))
          .map {
            case Right(_) =>
              store.clearEmail(due.guest)
              true
            case Left(reason) =>
              logger.warn(s"Guest expiry notice not sent: $reason")
              // Keep the address for a retry next hour, but never past the retry window.
              if (!now.isBefore(due.expiresAt.plus(GuestJobs.NoticeRetry)))
                store.clearEmail(due.guest)
              false
          }
      case _ =>
        // Nothing to say, no provider, or an address the key no longer opens: delete it anyway.
        if (due.email.isDefined) store.clearEmail(due.guest)
        Future.successful(true)
    }
  }

  def run(now: Instant = Instant.now()): Future[GuestJobReport] =
    Future(store.remindersDue(now, GuestJobs.BatchSize)).flatMap { reminders =>
      val remind =
        if (mailer.available && cipher.available) sequentially(reminders)(this.remind(_, now))
        else Future.successful((0, 0))
      remind.flatMap {
        case (reminded, remindFailures) =>
          Future(store.expiriesDue(now, GuestJobs.BatchSize))
            .flatMap(sequentially(_)(expire(_, now)))
            .map {
              case (expired, expireFailures) =>
                val purged = store.purge(now.minus(GuestJobs.PurgeAfter))
                GuestJobReport(
                  reminded,
                  expired + expireFailures,
                  purged,
                  remindFailures + expireFailures
                )
            }
      }
    }
}

/** Runs [[GuestJobService]] hourly while guests are enabled. One run at a time. */
@Singleton
class GuestJobScheduler @Inject() (
    settings: MobileOAuthSettings,
    service: GuestJobService,
    actorSystem: ActorSystem,
    lifecycle: ApplicationLifecycle
) {
  private val logger  = play.api.Logger(getClass)
  private val running = new AtomicBoolean(false)
  private implicit val ec: ExecutionContext =
    actorSystem.dispatchers.lookup("mobile-oauth-dispatcher")

  private def tick(): Unit =
    if (running.compareAndSet(false, true))
      service
        .run()
        .map { report =>
          if (report != GuestJobReport(0, 0, 0, 0)) logger.info(s"Guest job: $report")
        }
        .recover {
          case NonFatal(e) => logger.error(s"Guest job failed: ${e.getClass.getSimpleName}")
        }
        .onComplete(_ => running.set(false))

  if (settings.guestsEnabled) {
    val task = actorSystem.scheduler.scheduleWithFixedDelay(2.minutes, 1.hour)(() => tick())
    lifecycle.addStopHook(() => Future.successful(task.cancel()))
  }
}

class GuestJobModule extends SimpleModule(bind[GuestJobScheduler].toSelf.eagerly())

/**
  * The claim-token routes behind the emailed links (API plan §3.10). No credential: the token is
  * 32 random bytes, and only the guest's three newest tokens work.
  */
@Singleton
class GuestTokenService @Inject() (
    store: GuestJobStore,
    guests: MobileGuestStore,
    actorSystem: ActorSystem
) {
  private implicit lazy val ec: ExecutionContext =
    actorSystem.dispatchers.lookup("mobile-oauth-dispatcher")

  private def lookup(token: String): Option[ClaimTokenGuest] =
    if (!token.matches("[A-Za-z0-9_-]{43}")) None else store.claimToken(MobileSecrets.hash(token))

  /** None: unknown or superseded token. Left: claimed, use MapRoulette's account deletion. */
  def delete(token: String): Future[Option[Either[String, Unit]]] = Future {
    lookup(token).map { found =>
      if (found.claimed) Left("guest_claimed")
      else {
        guests.delete(found.guest, Instant.now())
        Right(())
      }
    }
  }

  def stopReminders(token: String): Future[Boolean] = Future {
    lookup(token) match {
      case Some(found) => store.setReminders(found.guest, enabled = false, Instant.now()); true
      case None        => false
    }
  }

  def setReminders(guest: UUID, enabled: Boolean): Future[Unit] =
    Future(store.setReminders(guest, enabled, Instant.now()))
}

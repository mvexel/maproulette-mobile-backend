package org.maproulette.provider.choice.claim

import akka.actor.ActorSystem
import anorm._
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.{Inject, Singleton}
import org.maproulette.auth.mobile.{
  MobileClaimRoutes,
  MobileClientRegistry,
  MobileOAuthSettings,
  MobileSecrets
}
import org.maproulette.framework.model.User
import org.maproulette.framework.service.UserService
import org.maproulette.provider.choice.{MobileChoiceService, PendingOutcome, PendingPublish}
import play.api.db.Database
import play.api.inject.{ApplicationLifecycle, SimpleModule, bind}
import play.api.libs.json._
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal

/** A claim the publish job still has work on: `queued`, `waiting` or `running`. */
case class OpenClaim(
    id: Long,
    guest: Option[UUID],
    userId: Long,
    familyId: String,
    state: String,
    waited: Boolean
)

/** A pending row of a claim with its failed attempts so far. */
case class ClaimRow(row: PendingPublish, attempts: Int)

/** The claimed guest's sealed address and deadline, for the publish job's emails. */
case class ClaimGuestMail(expiresAt: Instant, email: SealedEmail)

@com.google.inject.ImplementedBy(classOf[ClaimPublishRepository])
trait ClaimPublishStore {
  def open(limit: Int): Seq[OpenClaim]

  /** `waiting` also stamps waited_at once; `done` and `failed` stamp finished_at. */
  def setState(claim: Long, state: String, now: Instant): Unit

  /** The claim's rows still in state `pending`, oldest answer first. */
  def rows(claim: Long): Seq[ClaimRow]

  def settle(row: Long, state: String, result: JsObject, now: Instant): Unit

  /** Counts one failed attempt and returns the new total. */
  def attempt(row: Long, now: Instant): Int

  /** How many of the claim's rows are in each state. */
  def counts(claim: Long): Map[String, Int]

  def guestMail(guest: UUID): Option[ClaimGuestMail]
}

@Singleton
class ClaimPublishRepository @Inject() (db: Database) extends ClaimPublishStore {
  private def stamp(value: Instant) = Timestamp.from(value)

  override def open(limit: Int): Seq[OpenClaim] =
    db.withConnection { implicit c =>
      SQL(
        """SELECT id, guest_id, user_id, family_id, state, waited_at IS NOT NULL AS waited
        FROM mobile_claims WHERE state IN ('queued', 'waiting', 'running') ORDER BY id LIMIT {limit}"""
      ).on("limit" -> limit)
        .as(
          (SqlParser.long("id") ~ SqlParser.get[Option[UUID]]("guest_id") ~
            SqlParser.long("user_id") ~ SqlParser.str("family_id") ~ SqlParser.str("state") ~
            SqlParser.bool("waited")).map {
            case id ~ guest ~ user ~ family ~ state ~ waited =>
              OpenClaim(id, guest, user, family, state, waited)
          }.*
        )
    }

  override def setState(claim: Long, state: String, now: Instant): Unit =
    db.withConnection { implicit c =>
      SQL(
        """UPDATE mobile_claims SET state={state}, updated_at={now},
        waited_at = CASE WHEN {state}='waiting' THEN COALESCE(waited_at, {now}) ELSE waited_at END,
        finished_at = CASE WHEN {state} IN ('done', 'failed') THEN {now} ELSE NULL END
        WHERE id={id}"""
      ).on("state" -> state, "now" -> stamp(now), "id" -> claim)
        .executeUpdate()
      ()
    }

  override def rows(claim: Long): Seq[ClaimRow] =
    db.withConnection { implicit c =>
      SQL("""SELECT id, task_id, body::text AS body, payload_digest, attempts FROM choice_pending
        WHERE claim_id={claim} AND state='pending' ORDER BY answered_at, id""")
        .on("claim" -> claim)
        .as(
          (SqlParser.long("id") ~ SqlParser.long("task_id") ~ SqlParser.str("body") ~
            SqlParser.str("payload_digest") ~ SqlParser.int("attempts")).map {
            case id ~ task ~ body ~ digest ~ attempts =>
              ClaimRow(PendingPublish(id, task, Json.parse(body).as[JsObject], digest), attempts)
          }.*
        )
    }

  override def settle(row: Long, state: String, result: JsObject, now: Instant): Unit =
    db.withConnection { implicit c =>
      SQL("""UPDATE choice_pending SET state={state}, result=CAST({result} AS jsonb),
        updated_at={now} WHERE id={id} AND state='pending'""")
        .on("state" -> state, "result" -> Json.stringify(result), "now" -> stamp(now), "id" -> row)
        .executeUpdate()
      ()
    }

  override def attempt(row: Long, now: Instant): Int =
    db.withConnection { implicit c =>
      SQL("""UPDATE choice_pending SET attempts = attempts + 1, updated_at={now}
        WHERE id={id} RETURNING attempts""")
        .on("now" -> stamp(now), "id" -> row)
        .as(SqlParser.int("attempts").singleOpt)
        .getOrElse(0)
    }

  override def counts(claim: Long): Map[String, Int] =
    db.withConnection { implicit c =>
      SQL("SELECT state, count(*) AS n FROM choice_pending WHERE claim_id={claim} GROUP BY state")
        .on("claim" -> claim)
        .as((SqlParser.str("state") ~ SqlParser.long("n")).map {
          case state ~ n => state -> n.toInt
        }.*)
        .toMap
    }

  override def guestMail(guest: UUID): Option[ClaimGuestMail] =
    db.withConnection { implicit c =>
      SQL("""SELECT expires_at, email_ciphertext, email_nonce FROM mobile_guests
        WHERE id={g}::uuid AND deleted_at IS NULL AND email_ciphertext IS NOT NULL""")
        .on("g" -> guest.toString)
        .as(
          (SqlParser.date("expires_at") ~ SqlParser.byteArray("email_ciphertext") ~
            SqlParser.byteArray("email_nonce")).map {
            case expires ~ ct ~ nonce => ClaimGuestMail(expires.toInstant, SealedEmail(ct, nonce))
          }.singleOpt
        )
    }
}

case class ClaimPublishReport(claims: Int, published: Int, settled: Int, waiting: Int)

object ClaimPublish {

  /** OSM outages are retried on this many runs before the answer is `failed`. */
  val MaxAttempts = 5
  val BatchSize   = 50

  /** "2 stops weren't added because …"; empty when every stop was added. */
  def notAddedLine(n: Int): String =
    if (n == 0) ""
    else
      s"${ClaimEmails.count(n, "stop", "stops")} ${if (n == 1) "wasn't" else "weren't"} added " +
        s"because someone updated ${if (n == 1) "it" else "them"} after you checked. Nothing to " +
        s"do; ${if (n == 1) "it's" else "they're"} up to date."
}

/**
  * The deferred sign-up publish job (API plan §6 "Publish job", §3.10 "Waiting"): applies each
  * open claim's pending answers with the claim's own grant family, one task at a time. While task
  * writes are off a claim is `waiting`, checked again on every run and before every answer. OSM
  * outages leave the answer pending for a later run, up to [[ClaimPublish.MaxAttempts]]; another
  * mapper's lock only defers it. A refused OSM token stops the claim (`failed`) and emails "sign
  * in again to finish". A claim with nothing pending left is `done`: one that waited gets the
  * "now on the map" email, and the address is deleted then (§6 "Email retention").
  */
@Singleton
class ClaimPublishService @Inject() (
    store: ClaimPublishStore,
    choice: MobileChoiceService,
    users: UserService,
    preview: ClaimPreviewService,
    clients: MobileClientRegistry,
    summaries: GuestSummaries,
    cipher: GuestEmailCipher,
    mailer: ClaimMailer,
    emails: GuestEmailService,
    emailStore: GuestEmailStore,
    jobStore: GuestJobStore,
    actorSystem: ActorSystem
) {
  private val logger = play.api.Logger(getClass)
  private implicit lazy val ec: ExecutionContext =
    actorSystem.dispatchers.lookup("mobile-oauth-dispatcher")

  /**
    * Writes are on and the claim page's client is enabled (D13 (4)); otherwise claims wait. Read on
    * every run and before every answer (D13 (2)).
    */
  def ready: Boolean =
    preview.writesEnabled && clients.get(MobileClaimRoutes.ClientId).isDefined

  private sealed trait Step
  private case object Next      extends Step
  private case object Published extends Step
  private case object Stop      extends Step

  private def result(outcome: PendingOutcome): Option[(String, JsObject)] = outcome match {
    case PendingOutcome.Published(body) => Some("published" -> body)
    case PendingOutcome.Superseded(agrees) =>
      Some("superseded" -> Json.obj("agrees" -> agrees))
    case PendingOutcome.SkippedStale(reason) =>
      Some("skipped_stale" -> Json.obj("reason" -> reason))
    case PendingOutcome.Failed(code) => Some("failed" -> Json.obj("error" -> code))
    case _                           => None
  }

  private def publishRow(claim: OpenClaim, user: User, item: ClaimRow, now: Instant): Future[Step] =
    if (!ready) {
      store.setState(claim.id, "waiting", now)
      Future.successful(Stop)
    } else
      choice.publishPending(item.row, user, claim.familyId).map {
        case PendingOutcome.Reauth                 => Stop
        case PendingOutcome.Retry("lock_required") => Next
        case PendingOutcome.Retry(code) =>
          if (store.attempt(item.row.id, now) >= ClaimPublish.MaxAttempts)
            store.settle(item.row.id, "failed", Json.obj("error" -> code), now)
          Next
        case outcome =>
          result(outcome).foreach {
            case (state, json) => store.settle(item.row.id, state, json, now)
          }
          if (outcome.isInstanceOf[PendingOutcome.Published]) Published else Next
      }

  private def rows(claim: OpenClaim, user: User, now: Instant): Future[(Int, Int, Boolean)] =
    store.rows(claim.id).foldLeft(Future.successful((0, 0, false))) {
      case (previous, item) =>
        previous.flatMap {
          case stopped @ (_, _, true) => Future.successful(stopped)
          case (published, settled, _) =>
            publishRow(claim, user, item, now).map {
              case Published => (published + 1, settled + 1, false)
              case Next      => (published, settled, false)
              case Stop      => (published, settled, true)
            }
        }
    }

  private def send(
      guest: UUID,
      kind: String,
      values: ClaimGuestMail => Map[String, String]
  ): Future[Boolean] =
    store.guestMail(guest).flatMap(m => cipher.open(guest, m.email).map(m -> _)) match {
      case Some((mail, to)) if mailer.available =>
        mailer.send(ClaimEmails.render(kind, to, values(mail))).map {
          case Right(_) => true
          case Left(reason) =>
            logger.warn(s"Claim email $kind not sent: $reason")
            false
        }
      case _ => Future.successful(false)
    }

  private def reauth(claim: OpenClaim, now: Instant): Future[Unit] =
    claim.guest.flatMap(g => summaries.summary(g, Set("pending")).map(g -> _)) match {
      case Some((guest, summary)) =>
        // A new link: the stored token hashes can't be turned back into links.
        val token = MobileSecrets.generate()
        emailStore.addClaimToken(guest, MobileSecrets.hash(token), now)
        send(guest, ClaimEmails.Reauth, mail => emails.linkValues(mail.expiresAt, summary, token))
          .map(_ => ())
      case None => Future.successful(())
    }

  private def finished(claim: OpenClaim, user: User): Future[Unit] =
    claim.guest match {
      case None => Future.successful(())
      case Some(guest) =>
        val counts = store.counts(claim.id)
        val notice = summaries.summary(guest, Set("published")) match {
          case Some(summary) if claim.waited =>
            val name = user.osmProfile.displayName
            send(
              guest,
              ClaimEmails.Published,
              mail =>
                emails.values(mail.expiresAt, summary) ++ Map(
                  "osmUsername" -> name,
                  "editsUrl" -> s"https://www.openstreetmap.org/user/${URLEncoder
                    .encode(name, StandardCharsets.UTF_8)
                    .replace("+", "%20")}/history",
                  "publishedStopsText" -> ClaimEmails
                    .count(counts.getOrElse("published", 0), "bus stop", "bus stops"),
                  "notAddedLine" -> ClaimPublish.notAddedLine(
                    counts.getOrElse("superseded", 0) + counts.getOrElse("skipped_stale", 0)
                  )
                )
            )
          case _ => Future.successful(true)
        }
        // Nothing more will be said about these answers.
        notice.map(_ => jobStore.clearEmail(guest))
    }

  private def publish(claim: OpenClaim, now: Instant): Future[(Int, Int, Boolean)] =
    if (!ready) {
      if (claim.state != "waiting") store.setState(claim.id, "waiting", now)
      Future.successful((0, 0, true))
    } else
      users.retrieve(claim.userId) match {
        case None =>
          logger.warn(s"Claim ${claim.id}: user ${claim.userId} is gone")
          store.setState(claim.id, "failed", now)
          Future.successful((0, 0, false))
        case Some(user) =>
          if (claim.state != "running") store.setState(claim.id, "running", now)
          rows(claim, user, now).flatMap {
            case (published, settled, true) if ready =>
              // Stopped while ready: the OSM token was refused.
              store.setState(claim.id, "failed", now)
              reauth(claim, now).map(_ => (published, settled, false))
            case (published, settled, true) => Future.successful((published, settled, true))
            case (published, settled, false) =>
              if (store.counts(claim.id).getOrElse("pending", 0) > 0)
                Future.successful((published, settled, false))
              else {
                store.setState(claim.id, "done", now)
                finished(claim, user).map(_ => (published, settled, false))
              }
          }
      }

  def run(now: Instant = Instant.now()): Future[ClaimPublishReport] =
    Future(store.open(ClaimPublish.BatchSize)).flatMap { claims =>
      claims.foldLeft(Future.successful(ClaimPublishReport(claims.size, 0, 0, 0))) {
        (previous, claim) =>
          previous.flatMap { report =>
            publish(claim, now)
              .map {
                case (published, settled, waiting) =>
                  report.copy(
                    published = report.published + published,
                    settled = report.settled + settled,
                    waiting = report.waiting + (if (waiting) 1 else 0)
                  )
              }
              .recover {
                case NonFatal(e) =>
                  logger.error(s"Claim ${claim.id}: publish run failed", e)
                  report
              }
          }
      }
    }
}

/**
  * Runs [[ClaimPublishService]] every 10 seconds while guests are enabled, and on a nudge after a
  * claim. One run at a time; a nudge during a run triggers one more.
  */
@Singleton
class ClaimPublishScheduler @Inject() (
    settings: MobileOAuthSettings,
    service: ClaimPublishService,
    actorSystem: ActorSystem,
    lifecycle: ApplicationLifecycle
) {
  private val logger  = play.api.Logger(getClass)
  private val running = new AtomicBoolean(false)
  private val again   = new AtomicBoolean(false)
  private implicit val ec: ExecutionContext =
    actorSystem.dispatchers.lookup("mobile-oauth-dispatcher")

  def nudge(): Unit = if (settings.guestsEnabled) tick()

  private def tick(): Unit =
    if (running.compareAndSet(false, true))
      service
        .run()
        .map { report =>
          if (report.published + report.settled > 0) logger.info(s"Claim publish job: $report")
        }
        .recover {
          case NonFatal(e) => logger.error(s"Claim publish job failed: ${e.getClass.getSimpleName}")
        }
        .onComplete { _ =>
          running.set(false)
          if (again.getAndSet(false)) tick()
        } else again.set(true)

  if (settings.guestsEnabled) {
    val task = actorSystem.scheduler.scheduleWithFixedDelay(30.seconds, 10.seconds)(() => tick())
    lifecycle.addStopHook(() => Future.successful(task.cancel()))
  }
}

class ClaimPublishModule extends SimpleModule(bind[ClaimPublishScheduler].toSelf.eagerly())

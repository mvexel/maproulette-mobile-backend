package org.maproulette.provider.choice.claim

import akka.actor.ActorSystem
import com.typesafe.config.ConfigFactory
import java.time.{Duration, Instant}
import java.util.UUID
import org.maproulette.auth.mobile.{MobileGuestRoutes, MobileOAuthSettings}
import org.scalatest.BeforeAndAfterAll
import org.scalatestplus.play.PlaySpec
import play.api.Configuration
import play.api.test.FakeRequest
import play.api.test.Helpers._
import scala.collection.mutable

/** The job's decisions; the SQL that picks due guests is in GuestJobRepositorySpec. */
class MemoryGuestJobStore extends GuestJobStore {
  var reminders                                                = Seq.empty[ReminderDue]
  var expiries                                                 = Seq.empty[ExpiryDue]
  val marked                                                   = mutable.Set[(UUID, Int)]()
  val expired                                                  = mutable.ListBuffer[UUID]()
  val cleared                                                  = mutable.ListBuffer[UUID]()
  val stopped                                                  = mutable.Map[UUID, Boolean]()
  var purgedFrom                                               = Option.empty[Instant]
  def remindersDue(now: Instant, limit: Int): Seq[ReminderDue] = reminders
  def markReminded(guest: UUID, which: Int, now: Instant): Boolean =
    synchronized(marked.add((guest, which)))
  def unmarkReminded(guest: UUID, which: Int): Unit          = synchronized(marked.remove((guest, which)))
  def expiriesDue(now: Instant, limit: Int): Seq[ExpiryDue]  = expiries
  def expire(guest: UUID, now: Instant): Int                 = synchronized { expired += guest; 1 }
  def clearEmail(guest: UUID): Unit                          = synchronized(cleared += guest)
  def purge(before: Instant): Int                            = { purgedFrom = Some(before); 0 }
  def claimToken(tokenHash: String): Option[ClaimTokenGuest] = None
  def setReminders(guest: UUID, enabled: Boolean, now: Instant): Unit =
    synchronized(stopped(guest) = !enabled)
}

class GuestJobsSpec extends PlaySpec with BeforeAndAfterAll {
  private val system = ActorSystem(
    "guest-jobs-test",
    ConfigFactory.parseString("""
    mobile-oauth-dispatcher {
      type = Dispatcher
      executor = "thread-pool-executor"
      thread-pool-executor.fixed-pool-size = 2
    }
  """)
  )
  override def afterAll(): Unit = { await(system.terminate()); super.afterAll() }

  private val tokenKey = java.util.Base64.getEncoder.encodeToString(Array.fill[Byte](32)(7))
  private val cipher = new GuestEmailCipher(
    new MobileOAuthSettings(
      Configuration.from(
        Map(
          "mobileOAuth.enabled"     -> true,
          "mobileOAuth.callbackUri" -> "https://mr.example/oauth/mobile/callback",
          "mobileOAuth.osmTokenKey" -> tokenKey
        )
      )
    )
  )
  private val mailSettings = new ClaimMailSettings(
    Configuration.from(Map("mobileOAuth.guests.mail.claimOrigin" -> "https://claim.example"))
  )
  private val now     = Instant.parse("2026-11-25T18:00:00Z")
  private val expires = Instant.parse("2026-11-30T17:00:00Z")
  private val summary =
    GuestSummary("SLC Bus Stops", None, 14, Instant.parse("2026-11-06T17:00:00Z"))

  private case class Fixture(
      service: GuestJobService,
      store: MemoryGuestJobStore,
      emailStore: MemoryGuestEmailStore,
      mailer: RecordingMailer,
      states: mutable.ListBuffer[Set[String]]
  )
  private def fixture(
      mailer: RecordingMailer = new RecordingMailer(),
      saved: Option[GuestSummary] = Some(summary)
  ) = {
    val store      = new MemoryGuestJobStore
    val emailStore = new MemoryGuestEmailStore
    val states     = mutable.ListBuffer[Set[String]]()
    val summaries = new GuestSummaries {
      def summary(guest: UUID, wanted: Set[String]): Option[GuestSummary] = {
        states += wanted
        saved
      }
    }
    val emails =
      new GuestEmailService(emailStore, summaries, cipher, mailer, mailSettings, system)
    Fixture(
      new GuestJobService(store, emailStore, summaries, cipher, mailer, emails, system),
      store,
      emailStore,
      mailer,
      states
    )
  }

  private def sealedFor(id: UUID) = cipher.seal(id, "rosa@example.org").get

  "GuestJobs.reminderSubject" should {
    "follow the brand rules, singular included" in {
      GuestJobs.reminderSubject(1, "14 bus stops", 14, 0) mustBe "Your 14 bus stops are waiting"
      GuestJobs.reminderSubject(1, "1 bus stop", 1, 0) mustBe "Your 1 bus stop is waiting"
      GuestJobs.reminderSubject(2, "", 14, 5) mustBe "5 days left to put your bus stops on the map"
      GuestJobs.reminderSubject(2, "", 14, 1) mustBe "1 day left to put your bus stops on the map"
    }
  }

  "GuestJobService" should {
    "send a due reminder once, with a fresh claim token and the days left" in {
      val f  = fixture()
      val id = UUID.randomUUID()
      f.store.reminders = Seq(ReminderDue(id, expires, sealedFor(id), 2))
      await(f.service.run(now)).reminded mustBe 1
      f.mailer.sent.size mustBe 1
      val message = f.mailer.sent.head
      message.to mustBe "rosa@example.org"
      message.tag mustBe "guest-reminder"
      message.subject mustBe "5 days left to put your bus stops on the map"
      f.emailStore.tokens.size mustBe 1
      message.text must include("https://claim.example/claim/stop-reminders#t=")
      f.store.marked mustBe Set((id, 2))
      // Already marked: a second run finds it no longer due.
      await(f.service.run(now)).reminded mustBe 0
      f.mailer.sent.size mustBe 1
    }

    "release a reminder the provider refused, so the next run retries" in {
      val f  = fixture(mailer = new RecordingMailer(fail = true))
      val id = UUID.randomUUID()
      f.store.reminders = Seq(ReminderDue(id, expires, sealedFor(id), 1))
      val report = await(f.service.run(now))
      report.reminded mustBe 0
      report.failures mustBe 1
      f.store.marked mustBe empty
    }

    "send no reminders without a mail provider" in {
      val f  = fixture(mailer = new RecordingMailer(available = false))
      val id = UUID.randomUUID()
      f.store.reminders = Seq(ReminderDue(id, expires, sealedFor(id), 1))
      await(f.service.run(now)).reminded mustBe 0
      f.store.marked mustBe empty
    }

    "expire answers, send the expiry notice, then delete the address" in {
      val f  = fixture()
      val id = UUID.randomUUID()
      f.store.expiries = Seq(ExpiryDue(id, expires, Some(sealedFor(id))))
      await(f.service.run(expires.plusSeconds(60))).expired mustBe 1
      f.store.expired mustBe Seq(id)
      f.states mustBe Seq(Set("pending", "expired"))
      f.mailer.sent.map(_.subject) mustBe Seq("Your SLC Bus Stops answers were removed")
      f.mailer.sent.head.text must include("from Nov 6")
      f.mailer.sent.head.text must not include "#t="
      f.store.cleared mustBe Seq(id)
    }

    "keep the address for a retry when the notice fails, but not past a day" in {
      val f  = fixture(mailer = new RecordingMailer(fail = true))
      val id = UUID.randomUUID()
      f.store.expiries = Seq(ExpiryDue(id, expires, Some(sealedFor(id))))
      await(f.service.run(expires.plusSeconds(60))).failures mustBe 1
      f.store.cleared mustBe empty
      await(f.service.run(expires.plus(Duration.ofDays(1))))
      f.store.cleared mustBe Seq(id)
    }

    "expire guests without an address, and purge thirty days after expiry" in {
      val f  = fixture()
      val id = UUID.randomUUID()
      f.store.expiries = Seq(ExpiryDue(id, expires, None))
      await(f.service.run(now)).expired mustBe 1
      f.mailer.sent mustBe empty
      f.store.cleared mustBe empty
      f.store.purgedFrom mustBe Some(now.minus(Duration.ofDays(30)))
    }
  }

  "MobileGuestRoutes" should {
    "let a guest token switch reminders, and the link routes through the field gate" in {
      MobileGuestRoutes.permits("PUT", "/api/v2/mobile-guest/reminders") mustBe true
      MobileGuestRoutes.acceptable(FakeRequest("PUT", "/api/v2/mobile-guest/reminders")) mustBe true
      MobileGuestRoutes.acceptable(FakeRequest("PUT", "/api/v2/mobile-guest/reminders?a=1")) mustBe false
      MobileGuestRoutes.claimToken("POST", "/api/v2/mobile-claim/delete") mustBe true
      MobileGuestRoutes.claimToken("POST", "/api/v2/mobile-claim/stop-reminders") mustBe true
      MobileGuestRoutes.claimToken("POST", "/api/v2/mobile-claim") mustBe false
      // Guest tokens don't reach the link routes; those take no credential.
      MobileGuestRoutes.permits("POST", "/api/v2/mobile-claim/delete") mustBe false
      org.maproulette.auth.mobile.MobileFieldRoutes.permitsWhenDisabled(
        FakeRequest("POST", "/api/v2/mobile-claim/stop-reminders")
      ) mustBe true
      org.maproulette.auth.mobile.MobileFieldRoutes.permitsWhenDisabled(
        FakeRequest("POST", "/api/v2/mobile-claim")
      ) mustBe false
    }
  }
}

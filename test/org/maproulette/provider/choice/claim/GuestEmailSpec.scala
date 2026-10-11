package org.maproulette.provider.choice.claim

import akka.actor.ActorSystem
import com.typesafe.config.ConfigFactory
import java.time.Instant
import java.util.UUID
import org.maproulette.auth.mobile.guest.MobileGuest
import org.maproulette.auth.mobile.{MobileGuestRoutes, MobileOAuthSettings, MobileSecrets}
import org.scalatest.BeforeAndAfterAll
import org.scalatestplus.play.PlaySpec
import play.api.Configuration
import play.api.test.FakeRequest
import play.api.test.Helpers._
import scala.collection.mutable
import scala.concurrent.Future

class MemoryGuestEmailStore extends GuestEmailStore {
  val emails = mutable.Map[UUID, SealedEmail]()
  val tokens = mutable.ListBuffer[(UUID, String, Instant)]()
  var refuse = false
  def setEmail(guest: UUID, email: SealedEmail, now: Instant): Boolean = synchronized {
    if (!refuse) emails(guest) = email
    !refuse
  }
  def sendsSince(guest: UUID, since: Instant): Int = synchronized {
    tokens.count { case (g, _, at) => g == guest && at.isAfter(since) }
  }
  def addClaimToken(guest: UUID, tokenHash: String, now: Instant): Unit = synchronized {
    tokens += ((guest, tokenHash, now))
  }
}

class RecordingMailer(var available: Boolean = true, var fail: Boolean = false)
    extends ClaimMailer {
  val sent = mutable.ListBuffer[ClaimMessage]()
  def send(message: ClaimMessage): Future[Either[String, Unit]] = synchronized {
    if (fail) Future.successful(Left("postmark_422_300"))
    else {
      sent += message
      Future.successful(Right(()))
    }
  }
}

class GuestEmailSpec extends PlaySpec with BeforeAndAfterAll {
  private val system = ActorSystem(
    "guest-email-test",
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
  private def oauthSettings(withKey: Boolean = true) = new MobileOAuthSettings(
    Configuration.from(
      Map(
        "mobileOAuth.enabled"     -> true,
        "mobileOAuth.callbackUri" -> "https://mr.example/oauth/mobile/callback"
      ) ++ (if (withKey) Map("mobileOAuth.osmTokenKey" -> tokenKey) else Map.empty)
    )
  )
  private val mailSettings = new ClaimMailSettings(
    Configuration.from(Map("mobileOAuth.guests.mail.claimOrigin" -> "https://claim.example/"))
  )
  private val firstAnswer = Instant.parse("2026-11-06T17:00:00Z")
  private def guest(claimed: Boolean = false) =
    MobileGuest(
      UUID.randomUUID(),
      "app",
      firstAnswer,
      Instant.parse("2026-12-01T06:00:00Z"),
      false,
      false,
      false,
      if (claimed) Some(1L) else None,
      None,
      None,
      None
    )
  private val fourteen = GuestSummary("SLC <Bus> Stops", None, 14, firstAnswer)

  private case class Fixture(
      service: GuestEmailService,
      store: MemoryGuestEmailStore,
      mailer: RecordingMailer,
      cipher: GuestEmailCipher
  )
  private def fixture(
      saved: Option[GuestSummary] = Some(fourteen),
      withKey: Boolean = true,
      mailer: RecordingMailer = new RecordingMailer()
  ) = {
    val store  = new MemoryGuestEmailStore
    val cipher = new GuestEmailCipher(oauthSettings(withKey))
    val summaries = new GuestSummaries {
      def summary(guest: UUID): Option[GuestSummary] = saved
    }
    Fixture(
      new GuestEmailService(store, summaries, cipher, mailer, mailSettings, system),
      store,
      mailer,
      cipher
    )
  }

  "ClaimEmails" should {
    "fill every placeholder of every template and escape values in HTML only" in {
      val values = Map(
        "campaignName"       -> "SLC <Bus> Stops",
        "organizerName"      -> "Riders & Co",
        "eventDate"          -> "Thursday, Nov 6",
        "firstAnswerDate"    -> "Nov 6",
        "nounMany"           -> "bus stops",
        "savedStopsText"     -> "14 bus stops",
        "savedAnswersText"   -> "14 answers",
        "deadline"           -> "Nov 30",
        "reminderSubject"    -> "Your 14 bus stops are waiting",
        "claimUrl"           -> "https://claim.example/claim#t=abc",
        "deleteUrl"          -> "https://claim.example/claim/delete#t=abc",
        "stopRemindersUrl"   -> "https://claim.example/claim/stop-reminders#t=abc",
        "publishedStopsText" -> "12 bus stops",
        "osmUsername"        -> "rosa",
        "editsUrl"           -> "https://www.openstreetmap.org/user/rosa/history",
        "notAddedLine"       -> "",
        "privacyUrl"         -> "https://claim.example/privacy"
      )
      Seq(
        ClaimEmails.Claim,
        ClaimEmails.Reminder,
        ClaimEmails.Expiry,
        ClaimEmails.Reauth,
        ClaimEmails.Published
      ).foreach { name =>
        val message = ClaimEmails.render(name, "rosa@example.org", values)
        Seq(message.subject, message.text, message.html).foreach(_ must not include "{{")
        message.html must not include "<Bus>"
        message.tag mustBe s"guest-$name"
      }
      val claim = ClaimEmails.render(ClaimEmails.Claim, "rosa@example.org", values)
      claim.subject mustBe "Put your 14 answers from SLC <Bus> Stops on the map"
      claim.text must include("https://claim.example/claim#t=abc")
      claim.html must include("SLC &lt;Bus&gt; Stops")
    }

    "refuse a missing value" in {
      an[IllegalArgumentException] must be thrownBy ClaimEmails.fill(
        "{{x}}",
        Map.empty,
        html = false
      )
    }
  }

  "PostmarkClaimMailer" should {
    "send text and HTML with tracking off" in {
      val body = PostmarkClaimMailer.body(
        ClaimMessage("rosa@example.org", "S", "T", "<p>H</p>", "guest-claim"),
        "Street Tally <hello@example.org>",
        "outbound"
      )
      (body \ "To").as[String] mustBe "rosa@example.org"
      (body \ "TrackOpens").as[Boolean] mustBe false
      (body \ "TrackLinks").as[String] mustBe "None"
      (body \ "MessageStream").as[String] mustBe "outbound"
    }
  }

  "GuestEmailCipher" should {
    "open only for the same guest" in {
      val cipher      = new GuestEmailCipher(oauthSettings())
      val id          = UUID.randomUUID()
      val sealedEmail = cipher.seal(id, "rosa@example.org").get
      cipher.open(id, sealedEmail) mustBe Some("rosa@example.org")
      cipher.open(UUID.randomUUID(), sealedEmail) mustBe None
      new GuestEmailCipher(oauthSettings(withKey = false)).seal(id, "x@y") mustBe None
    }
  }

  "GuestEmailService" should {
    "store the address sealed, keep only the token's hash, and mail the claim link" in {
      val f = fixture()
      val g = guest()
      await(f.service.setEmail(g, " rosa@example.org ")).map(_.emailSet) mustBe Right(true)
      f.cipher.open(g.id, f.store.emails(g.id)) mustBe Some("rosa@example.org")
      f.mailer.sent.size mustBe 1
      val message = f.mailer.sent.head
      message.to mustBe "rosa@example.org"
      message.subject mustBe "Put your 14 answers from SLC <Bus> Stops on the map"
      val token = "claim#t=([A-Za-z0-9_-]{43})".r.findFirstMatchIn(message.text).get.group(1)
      f.store.tokens.map(_._2) mustBe Seq(MobileSecrets.hash(token))
      message.text must include(s"https://claim.example/claim#t=$token")
      message.text must include(s"https://claim.example/claim/delete#t=$token")
      message.text must include("Nov 30")
      message.text must include("Friday, Nov 6")
    }

    "refuse bad addresses, claimed guests and guests with nothing saved" in {
      val f = fixture()
      Seq("", "rosa", "@example.org", "rosa@", "a@b@c", "ro sa@example.org", "x" * 250 + "@ex.org")
        .foreach(email =>
          await(f.service.setEmail(guest(), email)) mustBe Left(GuestEmailError.InvalidRequest)
        )
      await(f.service.setEmail(guest(claimed = true), "rosa@example.org")) mustBe
        Left(GuestEmailError.GuestClaimed)
      await(fixture(saved = None).service.setEmail(guest(), "rosa@example.org")) mustBe
        Left(GuestEmailError.NothingSaved)
      f.mailer.sent mustBe empty
    }

    "answer 503 without a provider, a key, or when the provider fails" in {
      await(
        fixture(mailer = new RecordingMailer(available = false)).service
          .setEmail(guest(), "rosa@example.org")
      ) mustBe Left(GuestEmailError.MailUnavailable)
      await(fixture(withKey = false).service.setEmail(guest(), "rosa@example.org")) mustBe
        Left(GuestEmailError.MailUnavailable)
      await(
        fixture(mailer = new RecordingMailer(fail = true)).service
          .setEmail(guest(), "rosa@example.org")
      ) mustBe Left(GuestEmailError.MailUnavailable)
    }

    "allow three sends per guest per day" in {
      val f = fixture()
      val g = guest()
      (1 to 3).foreach(_ => await(f.service.setEmail(g, "rosa@example.org")).isRight mustBe true)
      await(f.service.setEmail(g, "rosa@example.org")) mustBe Left(GuestEmailError.RateLimited)
      await(f.service.setEmail(guest(), "rosa@example.org")).isRight mustBe true
    }

    "treat a guest that stopped qualifying while storing as claimed" in {
      val f = fixture()
      f.store.refuse = true
      await(f.service.setEmail(guest(), "rosa@example.org")) mustBe Left(
        GuestEmailError.GuestClaimed
      )
      f.mailer.sent mustBe empty
    }
  }

  "MobileGuestRoutes" should {
    "let a guest token reach the email route with a body" in {
      MobileGuestRoutes.permits("PUT", "/api/v2/mobile-guest/email") mustBe true
      MobileGuestRoutes.acceptable(FakeRequest("PUT", "/api/v2/mobile-guest/email")) mustBe true
      MobileGuestRoutes.acceptable(FakeRequest("PUT", "/api/v2/mobile-guest/email?x=1")) mustBe false
      MobileGuestRoutes.permits("POST", "/api/v2/mobile-guest/email") mustBe false
    }
  }
}

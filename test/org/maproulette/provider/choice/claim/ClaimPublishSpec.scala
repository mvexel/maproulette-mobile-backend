package org.maproulette.provider.choice.claim

import akka.actor.ActorSystem
import com.typesafe.config.ConfigFactory
import java.time.Instant
import java.util.UUID
import org.maproulette.auth.mobile.MobileOAuthSettings
import org.maproulette.framework.model.User
import org.maproulette.framework.service.UserService
import org.maproulette.provider.choice.{MobileChoiceService, PendingOutcome, PendingPublish}
import org.mockito.ArgumentMatchers.{any, eq => same}
import org.mockito.Mockito.when
import org.scalatest.BeforeAndAfterAll
import org.scalatestplus.mockito.MockitoSugar
import org.scalatestplus.play.PlaySpec
import play.api.Configuration
import play.api.libs.json._
import play.api.test.Helpers._
import scala.collection.mutable
import scala.concurrent.Future

/** The claim and row states, as the repository keeps them. */
class MemoryClaimPublishStore extends ClaimPublishStore {
  val claims   = mutable.Map[Long, OpenClaim]()
  val finished = mutable.Set[Long]()
  // row id -> (claim id, row, state, attempts, result)
  val pending = mutable.LinkedHashMap[Long, (Long, PendingPublish, String, Int, JsObject)]()
  var mail    = Option.empty[ClaimGuestMail]

  def add(claim: OpenClaim, rows: PendingPublish*): Unit = synchronized {
    claims(claim.id) = claim
    rows.foreach(r => pending(r.id) = (claim.id, r, "pending", 0, Json.obj()))
  }
  def state(row: Long): String    = pending(row)._3
  def result(row: Long): JsObject = pending(row)._5

  def open(limit: Int): Seq[OpenClaim] = synchronized {
    claims.values.filter(c => Set("queued", "waiting", "running")(c.state)).toSeq.sortBy(_.id)
  }
  def setState(claim: Long, state: String, now: Instant): Unit = synchronized {
    val c = claims(claim)
    claims(claim) = c.copy(state = state, waited = c.waited || state == "waiting")
    if (Set("done", "failed")(state)) finished += claim else finished -= claim
  }
  def rows(claim: Long): Seq[ClaimRow] = synchronized {
    pending.values.collect {
      case (c, row, "pending", attempts, _) if c == claim => ClaimRow(row, attempts)
    }.toSeq
  }
  def settle(row: Long, state: String, result: JsObject, now: Instant): Unit = synchronized {
    val (c, r, _, a, _) = pending(row)
    pending(row) = (c, r, state, a, result)
  }
  def attempt(row: Long, now: Instant): Int = synchronized {
    val (c, r, s, a, res) = pending(row)
    pending(row) = (c, r, s, a + 1, res)
    a + 1
  }
  def counts(claim: Long): Map[String, Int] = synchronized {
    pending.values.filter(_._1 == claim).groupBy(_._3).map { case (s, v) => s -> v.size }
  }
  def guestMail(guest: UUID): Option[ClaimGuestMail] = mail
}

class ClaimPublishSpec extends PlaySpec with MockitoSugar with BeforeAndAfterAll {
  private val system = ActorSystem(
    "claim-publish-test",
    ConfigFactory.parseString("""
    mobile-oauth-dispatcher {
      type = Dispatcher
      executor = "thread-pool-executor"
      thread-pool-executor.fixed-pool-size = 2
    }
  """)
  )
  override def afterAll(): Unit = { await(system.terminate()); super.afterAll() }

  private val cipher = new GuestEmailCipher(
    new MobileOAuthSettings(
      Configuration.from(
        Map(
          "mobileOAuth.enabled"     -> true,
          "mobileOAuth.callbackUri" -> "https://mr.example/oauth/mobile/callback",
          "mobileOAuth.osmTokenKey" -> java.util.Base64.getEncoder
            .encodeToString(Array.fill[Byte](32)(7))
        )
      )
    )
  )
  private val user = User.superUser.copy(
    id = 42L,
    osmProfile = User.superUser.osmProfile.copy(displayName = "rosa slc")
  )
  private val guest = UUID.randomUUID()
  private def row(id: Long) =
    PendingPublish(id, 1000L + id, Json.obj("answers" -> Json.obj()), "0" * 64)
  private val published = PendingOutcome.Published(Json.obj("status" -> 1, "dropped" -> Json.arr()))

  private class Fixture(outcomes: Map[Long, Seq[PendingOutcome]], var writes: Boolean = true) {
    val store   = new MemoryClaimPublishStore
    val jobs    = new MemoryGuestJobStore
    val emails  = new MemoryGuestEmailStore
    val mailer  = new RecordingMailer()
    val choice  = mock[MobileChoiceService]
    val users   = mock[UserService]
    val preview = mock[ClaimPreviewService]
    val calls   = mutable.Map[Long, Int]().withDefaultValue(0)
    when(users.retrieve(42L)).thenReturn(Some(user))
    when(preview.writesEnabled).thenAnswer(_ => writes)
    when(choice.publishPending(any[PendingPublish], same(user), same("family"))).thenAnswer {
      invocation =>
        val r    = invocation.getArgument[PendingPublish](0)
        val n    = calls(r.id); calls(r.id) = n + 1
        val list = outcomes(r.id)
        Future.successful(list(math.min(n, list.size - 1)))
    }
    val summaries = new GuestSummaries {
      def summary(g: UUID, states: Set[String]): Option[GuestSummary] =
        Some(GuestSummary("SLC Bus Stops", None, 2, Instant.parse("2026-11-06T17:00:00Z")))
    }
    val mailSettings = new ClaimMailSettings(
      Configuration.from(Map("mobileOAuth.guests.mail.claimOrigin" -> "https://claim.example"))
    )
    val service = new ClaimPublishService(
      store,
      choice,
      users,
      preview,
      summaries,
      cipher,
      mailer,
      new GuestEmailService(emails, summaries, cipher, mailer, mailSettings, system),
      emails,
      jobs,
      system
    )
    def claim(state: String = "queued", rows: Seq[PendingPublish] = Seq(row(1), row(2))): Unit = {
      store.add(OpenClaim(9, Some(guest), 42L, "family", state, waited = false), rows: _*)
      store.mail = Some(
        ClaimGuestMail(
          Instant.parse("2026-12-01T06:00:00Z"),
          cipher.seal(guest, "r@example.org").get
        )
      )
    }
    def run() = await(service.run())
  }

  "The claim publish job" should {
    "publish every answer, finish the claim and delete the address without an email" in new Fixture(
      Map(
        1L -> Seq(published),
        2L -> Seq(PendingOutcome.SkippedStale("key_changed"))
      )
    ) {
      claim()
      run() mustBe ClaimPublishReport(1, 1, 1, 0)
      store.state(1) mustBe "published"
      store.state(2) mustBe "skipped_stale"
      store.result(2) mustBe Json.obj("reason" -> "key_changed")
      store.claims(9).state mustBe "done"
      mailer.sent mustBe empty
      jobs.cleared mustBe List(guest)
    }

    "wait while writes are off, then publish and send the now-on-the-map email" in new Fixture(
      Map(1L -> Seq(published), 2L -> Seq(PendingOutcome.Superseded(Some(true)))),
      writes = false
    ) {
      claim()
      run().waiting mustBe 1
      store.claims(9).state mustBe "waiting"
      calls mustBe empty
      writes = true
      run()
      store.claims(9).state mustBe "done"
      mailer.sent.map(_.tag) mustBe List(s"guest-${ClaimEmails.Published}")
      mailer.sent.head.subject must startWith("Your 1 bus stop ")
      mailer.sent.head.text must include("https://www.openstreetmap.org/user/rosa%20slc/history")
      mailer.sent.head.text must include("1 stop wasn't added")
      jobs.cleared mustBe List(guest)
    }

    "retry OSM outages up to five runs, then fail the answer" in new Fixture(
      Map(1L -> Seq(PendingOutcome.Retry("osm_unavailable")), 2L -> Seq(published))
    ) {
      claim()
      run()
      store.state(1) mustBe "pending"
      store.claims(9).state mustBe "running"
      (2 to ClaimPublish.MaxAttempts).foreach(_ => run())
      store.state(1) mustBe "failed"
      store.result(1) mustBe Json.obj("error" -> "osm_unavailable")
      store.claims(9).state mustBe "done"
    }

    "defer on another mapper's lock without counting an attempt" in new Fixture(
      Map(1L -> Seq(PendingOutcome.Retry("lock_required"))),
      writes = true
    ) {
      claim(rows = Seq(row(1)))
      (1 to 7).foreach(_ => run())
      store.state(1) mustBe "pending"
      store.pending(1L)._4 mustBe 0
    }

    "stop on a refused OSM token and email a fresh link to sign in again" in new Fixture(
      Map(1L -> Seq(PendingOutcome.Reauth), 2L -> Seq(published))
    ) {
      claim()
      run()
      store.claims(9).state mustBe "failed"
      store.state(1) mustBe "pending"
      store.state(2) mustBe "pending"
      calls(2L) mustBe 0
      mailer.sent.map(_.tag) mustBe List(s"guest-${ClaimEmails.Reauth}")
      mailer.sent.head.text must include("https://claim.example/claim#t=")
      emails.tokens.size mustBe 1
      jobs.cleared mustBe empty
      run()
      calls(1L) mustBe 1
    }

    "go back to waiting when writes are switched off mid-claim" in new Fixture(
      Map(1L -> Seq(published), 2L -> Seq(published))
    ) {
      claim()
      when(choice.publishPending(any[PendingPublish], same(user), same("family"))).thenAnswer { _ =>
        writes = false
        Future.successful(published)
      }
      run()
      store.state(1) mustBe "published"
      store.state(2) mustBe "pending"
      store.claims(9).state mustBe "waiting"
    }
  }

  "notAddedLine" should {
    "use the singular for one and be empty for none" in {
      ClaimPublish.notAddedLine(0) mustBe ""
      ClaimPublish.notAddedLine(1) must startWith("1 stop wasn't added because someone updated it")
      ClaimPublish.notAddedLine(2) must endWith("they're up to date.")
    }
  }
}

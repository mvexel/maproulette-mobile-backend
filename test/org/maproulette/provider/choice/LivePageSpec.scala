package org.maproulette.provider.choice

import anorm._
import java.time.{Duration, Instant}
import java.util.UUID
import org.maproulette.auth.mobile.MobileSecrets
import org.maproulette.auth.mobile.guest.MobileGuestRepository
import org.maproulette.framework.model._
import org.maproulette.framework.util.FrameworkHelper
import play.api.Application
import play.api.db.Database
import play.api.libs.json._

/**
  * The live progress page's settings and reads against a real database (evolution 141). Runs in
  * FrameworkMasterSuite (tag "choice").
  */
class LivePageSpec(implicit val application: Application) extends FrameworkHelper {
  override implicit val projectTestName: String = "LivePageSpecProject"
  private val db                                = application.injector.instanceOf[Database]
  private val pages                             = new LivePageRepository(db)
  private val guests                            = new MobileGuestRepository(db)
  private var challenge: Challenge              = _

  private def task(node: Long, name: Option[String] = None): Task =
    taskDAL.insert(
      Task(
        -1,
        s"node/$node",
        null,
        null,
        challenge.id,
        geometries = Json.obj(
          "features" -> Json.arr(
            Json.obj(
              "type" -> "Feature",
              "geometry" -> Json
                .obj("type" -> "Point", "coordinates" -> Json.arr(-111.9068, 40.7537)),
              "properties" -> (Json.obj("@id" -> s"node/$node") ++
                name.fold(Json.obj())(n => Json.obj("name" -> n)))
            )
          )
        ),
        cooperativeWork = Some(ChoiceFixtures.withNode(node)),
        status = Some(Task.STATUS_CREATED)
      ),
      User.superUser
    )

  private def setStatus(t: Task, status: Int): Unit = db.withConnection { implicit c =>
    SQL"UPDATE tasks SET status = $status, mapped_on = NOW() WHERE id = ${t.id}".executeUpdate()
    ()
  }

  private def submission(t: Task, user: Long, answers: JsObject, at: Instant): Unit =
    db.withConnection { implicit c =>
      SQL"""INSERT INTO mobile_choice_submissions
              (task_id, user_id, submission_key, state, result_json, answers, updated_at)
            VALUES (${t.id}, $user, ${"%064x".format(BigInt(t.id))}, 'done',
              '{}'::jsonb, ${Json.stringify(answers)}::jsonb, ${java.util.Date.from(at)})"""
        .executeUpdate()
      ()
    }

  private def pending(t: Task, body: JsObject, hold: Duration, at: Instant): Unit = {
    val guest = guests.create(
      UUID.randomUUID(),
      "live-test",
      MobileSecrets.hash(UUID.randomUUID().toString),
      Instant.now().plus(Duration.ofDays(1)),
      Instant.now()
    )
    db.withConnection { implicit c =>
      SQL"""INSERT INTO choice_pending (guest_id, task_id, challenge_id, body, payload_digest,
              element_version, answered_at, hold_until)
            VALUES (${guest.id.toString}::uuid, ${t.id}, ${challenge.id}, ${Json.stringify(body)}::jsonb,
              ${"0" * 64}, 1, ${java.util.Date.from(at)},
              ${java.util.Date.from(Instant.now().plus(hold))})""".executeUpdate()
      ()
    }
  }

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    challenge = challengeDAL.insert(
      Challenge(
        -1,
        "liveChallenge",
        null,
        null,
        general = ChallengeGeneral(
          User.superUser.osmProfile.id,
          defaultProject.id,
          "Add bench details",
          enabled = true,
          checkinComment = "Add bench details #StreetTallyLive",
          checkinSource = "survey"
        ),
        creation = ChallengeCreation(),
        priority = ChallengePriority(),
        extra = ChallengeExtra()
      ),
      User.superUser
    )
    db.withConnection { implicit c =>
      SQL"""INSERT INTO mobile_oauth_clients (id, name, redirect_uris, scopes)
            VALUES ('live-test', 'Test', ARRAY['org.example:/cb'], 'tasks:read guest')
            ON CONFLICT DO NOTHING""".execute()
    }
  }

  "A live page" should {
    "be created off on first save, with answers shown, and keep its first end time" taggedAs ChoiceTag in {
      pages.byChallenge(challenge.id) mustBe None
      val created = pages.save(challenge.id, LivePageUpdate())
      created.token must fullyMatch regex "[A-Za-z0-9_-]{43}"
      (created.enabled, created.showAnswers, created.eventStart, created.endedAt) mustBe
        (false, true, None, None)
      pages.byToken(created.token).map(_.challengeId) mustBe Some(challenge.id)

      val start = Instant.parse("2026-10-18T23:00:00Z")
      val ended = pages.save(
        challenge.id,
        LivePageUpdate(
          showAnswers = Some(false),
          eventStart = Some(Some(start)),
          ended = Some(true)
        )
      )
      ended.token mustBe created.token
      ended.showAnswers mustBe false
      ended.eventStart mustBe Some(start)
      val first = ended.endedAt.get
      pages.save(challenge.id, LivePageUpdate(ended = Some(true))).endedAt mustBe Some(first)
      pages.save(challenge.id, LivePageUpdate(ended = Some(false))).endedAt mustBe None
      pages.save(challenge.id, LivePageUpdate(eventStart = Some(None))).eventStart mustBe None
      pages.save(challenge.id, LivePageUpdate(enabled = Some(false))).enabled mustBe false
      pages.save(challenge.id, LivePageUpdate(enabled = Some(true))).enabled mustBe true
    }

    "break the old link when its token is rotated" taggedAs ChoiceTag in {
      val before  = pages.save(challenge.id, LivePageUpdate())
      val rotated = pages.rotate(challenge.id).get
      rotated.token must not be before.token
      pages.byToken(before.token) mustBe None
      pages.byToken(rotated.token).map(_.challengeId) mustBe Some(challenge.id)
      pages.rotate(Long.MaxValue) mustBe None
    }

    "read stops with their answers, live pending answers and the people since the start" taggedAs ChoiceTag in {
      val todo    = task(9801, Some("Granary Live"))
      val done    = task(9802)
      val held    = task(9803)
      val expired = task(9804)
      val look    = task(9805)
      val user    = defaultUser.id
      setStatus(done, Task.STATUS_FIXED)
      submission(done, user, Json.obj("backrest" -> "yes"), Instant.now())
      pending(
        held,
        Json.obj("answers" -> Json.obj("backrest" -> "no")),
        Duration.ofDays(1),
        Instant.now()
      )
      pending(
        expired,
        Json.obj("answers" -> Json.obj("backrest" -> "no")),
        Duration.ofDays(-1),
        Instant.now()
      )
      setStatus(look, Task.STATUS_TOO_HARD)

      val rows = pages.stops(challenge.id).map(r => r.taskId -> r).toMap
      rows(todo.id).name mustBe Some("Granary Live")
      math.abs(rows(todo.id).lon + 111.9068) must be < 0.0001
      rows(done.id).answers mustBe Map("backrest" -> "yes")
      rows(held.id).pendingAt mustBe defined
      rows(held.id).answers mustBe empty
      rows(expired.id).pendingAt mustBe None
      LiveProgress.state(rows(todo.id)) mustBe Some(LiveProgress.Todo)
      LiveProgress.state(rows(done.id)) mustBe Some(LiveProgress.Checked)
      LiveProgress.state(rows(held.id)) mustBe Some(LiveProgress.Checked)
      LiveProgress.state(rows(expired.id)) mustBe Some(LiveProgress.Todo)
      LiveProgress.state(rows(look.id)) mustBe Some(LiveProgress.Look)

      // One signed-in mapper and two guests (one of them now past its hold) since an hour ago.
      pages.people(challenge.id, Instant.now().minus(Duration.ofHours(1))) mustBe 3
      pages.people(challenge.id, Instant.now().plus(Duration.ofHours(1))) mustBe 0
      pages.campaign(challenge.id) mustBe Some(
        ("liveChallenge", Some("Add bench details #StreetTallyLive"))
      )
    }
  }
}

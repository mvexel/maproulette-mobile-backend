package org.maproulette.provider.choice

import anorm._
import java.time.{Duration, Instant}
import java.util.UUID
import org.maproulette.Config
import org.maproulette.auth.mobile.MobileSecrets
import org.maproulette.auth.mobile.guest.MobileGuestRepository
import org.maproulette.framework.model._
import org.maproulette.framework.util.FrameworkHelper
import play.api.Application
import play.api.db.Database
import play.api.libs.json._
import play.api.libs.ws.WSClient
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext}

/**
  * The confirm queue generator and export against a real database and a loopback fake OSM API
  * (evolution 143, H1b). Runs in FrameworkMasterSuite (tag "choice").
  */
class ChoiceQueueServiceSpec(implicit val application: Application) extends FrameworkHelper {
  override implicit val projectTestName: String = "ChoiceQueueServiceSpecProject"
  private implicit val ec: ExecutionContext     = application.injector.instanceOf[ExecutionContext]
  private val db                                = application.injector.instanceOf[Database]
  private val guests                            = new MobileGuestRepository(db)
  private val osm                               = new FakeOsmServer
  private val client = new ChoiceOsmClient(
    application.injector.instanceOf[WSClient],
    application.injector.instanceOf[Config]
  ) { override protected def baseUrl: String = osm.url }
  private val reviews = new ReviewRepository(db)
  private val service =
    new ChoiceQueueService(new ChoiceQueueRepository(db), reviews, client, challengeDAL, taskDAL)

  private def newChallenge(name: String): Challenge =
    challengeDAL.insert(
      Challenge(
        -1,
        name,
        null,
        null,
        general = ChallengeGeneral(
          User.superUser.osmProfile.id,
          defaultProject.id,
          "Add bench details",
          enabled = true,
          checkinComment = "Add bench details #StreetTally",
          checkinSource = "survey"
        ),
        creation = ChallengeCreation(),
        priority = ChallengePriority(),
        extra = ChallengeExtra()
      ),
      User.superUser
    )

  private def task(in: Challenge, node: Long): Task =
    taskDAL.insert(
      Task(
        -1,
        s"node/$node",
        null,
        null,
        in.id,
        geometries = Json.obj(
          "features" -> Json.arr(
            Json.obj(
              "type" -> "Feature",
              "geometry" -> Json
                .obj("type" -> "Point", "coordinates" -> Json.arr(-111.9068, 40.7537)),
              "properties" -> Json.obj("@id" -> s"node/$node", "amenity" -> "bench")
            )
          )
        ),
        cooperativeWork = Some(ChoiceFixtures.withNode(node)),
        status = Some(Task.STATUS_CREATED)
      ),
      User.superUser
    )

  private def answer(t: Task, values: (String, String)*): Unit = {
    val guest = guests.create(
      UUID.randomUUID(),
      "queue-test",
      MobileSecrets.hash(UUID.randomUUID().toString),
      Instant.now().plus(Duration.ofDays(1)),
      Instant.now()
    )
    val body = Json.obj("answers" -> JsObject(values.map { case (q, o) => q -> JsString(o) }))
    db.withConnection { implicit c =>
      SQL"""INSERT INTO choice_pending (guest_id, task_id, challenge_id, body, payload_digest,
              element_version, answered_at, hold_until, state)
            VALUES (${guest.id.toString}::uuid, ${t.id}, ${t.parent}, ${Json.stringify(body)}::jsonb,
              ${"0" * 64}, 1, NOW(), ${java.util.Date.from(Instant.now().plus(Duration.ofDays(1)))},
              'pending')""".executeUpdate()
      ()
    }
  }

  private def state(t: Task): Option[String] = db.withConnection { implicit c =>
    SQL"SELECT state FROM choice_review_places WHERE task_id = ${t.id}"
      .as(SqlParser.str("state").singleOpt)
  }

  private def heldForever(t: Task): Seq[Boolean] = db.withConnection { implicit c =>
    SQL"""SELECT hold_until > NOW() + INTERVAL '1000 years' AS held FROM choice_pending
          WHERE task_id = ${t.id} ORDER BY id""".as(SqlParser.bool("held").*)
  }

  private def rows(t: Task): Seq[String] = db.withConnection { implicit c =>
    SQL"SELECT state FROM choice_pending WHERE task_id = ${t.id} ORDER BY id"
      .as(SqlParser.str("state").*)
  }

  private def detect(): ChoiceQueue.OnMapReport = Await.result(service.detect(1000), 30.seconds)

  private def generate(id: Long): Option[ChoiceQueue.Report] =
    Await.result(service.generate(id, User.superUser), 30.seconds)

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    db.withConnection { implicit c =>
      SQL"""INSERT INTO mobile_oauth_clients (id, name, redirect_uris, scopes)
            VALUES ('queue-test', 'Test', ARRAY['org.example:/cb'], 'tasks:read guest')
            ON CONFLICT DO NOTHING""".execute()
    }
  }

  override protected def afterAll(): Unit = {
    osm.stop()
    super.afterAll()
  }

  "The confirm queue" should {
    "queue clear answers that pass the stale check, once, and export them" taggedAs ChoiceTag in {
      val campaign = newChallenge("Benches")
      val split    = task(campaign, 8801)
      val onMap    = task(campaign, 8802)
      val changed  = task(campaign, 8803)
      val gone     = task(campaign, 8804)
      answer(split, "backrest"   -> "yes", "material" -> "wood")
      answer(split, "backrest"   -> "yes", "material" -> "metal")
      answer(onMap, "backrest"   -> "yes")
      answer(changed, "material" -> "wood")
      answer(gone, "backrest"    -> "no")
      osm.put("node", 8801, Map("amenity" -> "bench"))
      osm.put("node", 8802, Map("amenity" -> "bench", "backrest" -> "yes"))
      osm.put("node", 8803, Map("amenity" -> "bench", "material" -> "stone"))

      val first = generate(campaign.id).get
      first.copy(queueChallengeId = 0) mustBe ChoiceQueue.Report(0, 1, 0, 1, 1, 1, 1)
      Seq(split, onMap, changed, gone).map(state) mustBe
        Seq(None, Some("done"), Some("stale"), Some("gone"))
      heldForever(split) mustBe Seq(true, true)
      heldForever(changed) mustBe Seq(false)
      // H1c: the answer OSM already carried is on the map.
      rows(onMap) mustBe Seq("on_map")
      rows(gone) mustBe Seq("pending")

      val queue = challengeDAL.retrieveById(first.queueChallengeId).get
      queue.name mustBe "Benches: confirm survey answers"
      queue.general.enabled mustBe false
      // The type comes from the first task, past the challenge cache.
      db.withConnection { implicit c =>
        SQL"SELECT cooperative_type FROM challenges WHERE id = ${queue.id}"
          .as(SqlParser.int("cooperative_type").single)
      } mustBe Challenge.COOPERATIVE_TAGS
      queue.general.parent mustBe campaign.general.parent
      queue.general.checkinComment mustBe "Add bench details #StreetTally"
      queue.general.instruction mustBe "{{evidence}}"

      val lines = Await.result(service.exportQueue(campaign.id), 10.seconds).get
      lines.size mustBe 1
      ((lines.head \ "features")(0) \ "properties" \ "@id").as[String] mustBe "node/8801"
      ((lines.head \ "features")(0) \ "properties" \ "evidence").as[String] must
        startWith("Surveyed on ")
      (lines.head \ "cooperativeWork" \ "operations")(0) \ "data" \ "operations" mustBe
        JsDefined(
          Json.arr(Json.obj("operation" -> "setTags", "data" -> Json.obj("backrest" -> "yes")))
        )
      osm.uploadCount mustBe 0

      // A mapper fixed the changed bench: a rerun queues it and adds nothing twice.
      osm.edit("node", 8803)(_ - "material")
      val second = generate(campaign.id).get
      second mustBe ChoiceQueue.Report(first.queueChallengeId, 1, 1, 0, 0, 0, 1)
      state(changed) mustBe Some("open")
      Await.result(service.exportQueue(campaign.id), 10.seconds).get.size mustBe 2
      generate(campaign.id).get.copy(queueChallengeId = 0) mustBe
        ChoiceQueue.Report(0, 0, 2, 0, 0, 0, 1)

      // A run that lost the race for a place and question drops the task it made.
      val late = task(campaign, 8899)
      val already =
        ChoiceQueue.candidates(reviews.places(campaign.id).find(_.id == split.id).get, Set.empty)
      new ChoiceQueueRepository(db).recordQueued(late.id, already.candidates.head) mustBe false
      taskDAL.retrieveById(late.id) mustBe None

      // Needs a visit lets the campaign task back into discovery.
      reviews.needsVisit(campaign.id, split.id, defaultUser.id, on = true).isRight mustBe true
      heldForever(split) mustBe Seq(false, false)
    }

    "answer nothing for a campaign without a queue or a challenge that isn't one" taggedAs ChoiceTag in {
      val plain = newChallenge("Plain")
      generate(plain.id) mustBe None
      generate(9999999L) mustBe None
      // A choice campaign is one whose tasks are choice tasks.
      val quiet = newChallenge("Quiet")
      task(quiet, 8811)
      Await.result(service.exportQueue(quiet.id), 10.seconds) mustBe None
      generate(quiet.id) mustBe Some(ChoiceQueue.Report(0, 0, 0, 0, 0, 0, 0))
      Await.result(service.exportQueue(quiet.id), 10.seconds) mustBe None
    }
    "see queued answers land on the map, or their element go (H1c)" taggedAs ChoiceTag in {
      val campaign = newChallenge("Doors")
      val door     = task(campaign, 8821)
      val deleted  = task(campaign, 8822)
      answer(door, "backrest"    -> "no", "material" -> "metal")
      answer(door, "backrest"    -> "no")
      answer(deleted, "material" -> "wood")
      osm.put("node", 8821, Map("amenity" -> "bench"))
      osm.put("node", 8822, Map("amenity" -> "bench"))
      val partial = task(campaign, 8823)
      answer(partial, "backrest" -> "yes", "material" -> "wood")
      osm.put("node", 8823, Map("amenity" -> "bench", "backrest" -> "yes"))
      generate(campaign.id).get.queued mustBe 4
      // OSM already carries the backrest, but the material is still queued.
      rows(partial) mustBe Seq("pending")

      detect().failures mustBe 0
      rows(door) mustBe Seq("pending", "pending")
      state(door) mustBe None

      // A mapper adds one of the two queued edits on maproulette.org.
      osm.edit("node", 8821)(_ + ("backrest" -> "no"))
      osm.elements(("node", 8822L)).visible = false
      detect().failures mustBe 0
      // The first guest's material answer is still queued, so only the second guest's is on the map.
      rows(door) mustBe Seq("pending", "on_map")
      state(door) mustBe None
      state(deleted) mustBe Some("gone")

      osm.edit("node", 8821)(_ + ("material" -> "metal"))
      detect()
      rows(door) mustBe Seq("on_map", "on_map")
      state(door) mustBe Some("done")
      db.withConnection { implicit c =>
        SQL"""SELECT count(*) FROM choice_queue_items
              WHERE source_task_id = ${door.id} AND on_map_at IS NOT NULL"""
          .as(SqlParser.scalar[Long].single)
      } mustBe 2
    }
  }
}

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
  * The review page's reads and marks against a real database (evolution 142, H1a). Runs in
  * FrameworkMasterSuite (tag "choice").
  */
class ReviewRepositorySpec(implicit val application: Application) extends FrameworkHelper {
  override implicit val projectTestName: String = "ReviewRepositorySpecProject"
  private val db                                = application.injector.instanceOf[Database]
  private val reviews                           = new ReviewRepository(db)
  private val guests                            = new MobileGuestRepository(db)
  private val queue                             = new ChoiceQueueRepository(db)
  private var challenge: Challenge              = _
  private var other: Challenge                  = _

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
              "properties" -> Json.obj("@id" -> s"node/$node", "material" -> "stone")
            )
          )
        ),
        cooperativeWork = Some(ChoiceFixtures.withNode(node)),
        status = Some(Task.STATUS_CREATED)
      ),
      User.superUser
    )

  private def pending(t: Task, body: JsObject, state: String = "pending"): Unit = {
    val guest = guests.create(
      UUID.randomUUID(),
      "review-test",
      MobileSecrets.hash(UUID.randomUUID().toString),
      Instant.now().plus(Duration.ofDays(1)),
      Instant.now()
    )
    db.withConnection { implicit c =>
      SQL"""INSERT INTO choice_pending (guest_id, task_id, challenge_id, body, payload_digest,
              element_version, answered_at, hold_until, state)
            VALUES (${guest.id.toString}::uuid, ${t.id}, ${t.parent}, ${Json.stringify(body)}::jsonb,
              ${"0" * 64}, 1, NOW(), ${java.util.Date.from(Instant.now().plus(Duration.ofDays(1)))},
              $state)""".executeUpdate()
      ()
    }
  }

  private def audits(taskId: Long): Seq[String] = db.withConnection { implicit c =>
    SQL"SELECT action FROM mobile_admin_audit WHERE target = ${s"task:$taskId"} ORDER BY id"
      .as(SqlParser.str("action").*)
  }

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    challenge = newChallenge("reviewChallenge")
    other = newChallenge("reviewOther")
    db.withConnection { implicit c =>
      SQL"""INSERT INTO mobile_oauth_clients (id, name, redirect_uris, scopes)
            VALUES ('review-test', 'Test', ARRAY['org.example:/cb'], 'tasks:read guest')
            ON CONFLICT DO NOTHING""".execute()
    }
  }

  "The review repository" should {
    "list places with held answers or marks, and their answers, and nothing else" taggedAs ChoiceTag in {
      val answered  = task(challenge, 9901)
      val unsure    = task(challenge, 9902)
      val quiet     = task(challenge, 9903)
      val gone      = task(challenge, 9904)
      val elsewhere = task(other, 9905)
      pending(answered, Json.obj("answers"  -> Json.obj("material" -> "wood")))
      pending(answered, Json.obj("answers"  -> Json.obj("material" -> "wood")))
      pending(unsure, Json.obj("outcome"    -> "too-hard"))
      pending(gone, Json.obj("answers"      -> Json.obj("material" -> "metal")), "published")
      pending(elsewhere, Json.obj("answers" -> Json.obj("material" -> "wood")))

      val places = reviews.places(challenge.id)
      places.map(_.id) mustBe Seq(answered.id)
      val json = places.head.json(skipped = false)
      (json \ "osm").as[JsObject] mustBe Json.obj("type" -> "node", "id" -> 9901)
      ((json \ "suggestions")(0) \ "people").as[Int] mustBe 2
      ((json \ "suggestions")(0) \ "current").as[JsObject] mustBe Json.obj("material" -> "stone")
      places.head.state mustBe ReviewPage.Open
      reviews.skip(challenge.id, quiet.id, defaultUser.id, on = true) mustBe
        Left(ReviewRefusal.NotFound)
      reviews.skip(challenge.id, elsewhere.id, defaultUser.id, on = true) mustBe
        Left(ReviewRefusal.NotFound)
    }

    "list a gone place's answers that its deletion skipped, but not key-changed ones (R1)" taggedAs ChoiceTag in {
      val t = task(challenge, 9921)
      pending(t, Json.obj("answers" -> Json.obj("material" -> "wood")))
      pending(t, Json.obj("answers" -> Json.obj("material" -> "metal")))
      db.withConnection { implicit c =>
        SQL"""UPDATE choice_pending SET state = 'skipped_stale',
                result = '{"reason": "key_changed"}'::jsonb
              WHERE task_id = ${t.id} AND body->'answers'->>'material' = 'metal'""".executeUpdate()
      }
      queue.skipGone(t.id) mustBe 1
      db.withConnection(implicit c => reviews.places(challenge.id, Some(t.id))) mustBe empty
      queue.setState(challenge.id, t.id, ReviewPage.Gone)
      val place = db.withConnection(implicit c => reviews.places(challenge.id, Some(t.id))).head
      place.state mustBe ReviewPage.Gone
      place.answers.map(a => (a.body \ "answers" \ "material").as[String]) mustBe Seq("wood")
      val json = place.json(skipped = false)
      ((json \ "suggestions")(0) \ "people").as[Int] mustBe 1
    }

    "keep skips per mapper and needs-visit shared and audited" taggedAs ChoiceTag in {
      val t = task(challenge, 9911)
      pending(t, Json.obj("outcome" -> "not-here"))
      val me = defaultUser.id

      val skipped = reviews.skip(challenge.id, t.id, me, on = true).toOption.get
      (skipped \ "skipped").as[Boolean] mustBe true
      reviews.skip(challenge.id, t.id, me, on = true).isRight mustBe true
      reviews.skipped(challenge.id, me) mustBe Set(t.id)
      reviews.skipped(challenge.id, User.superUser.id) mustBe Set.empty
      (reviews.skip(challenge.id, t.id, me, on = false).toOption.get \ "skipped").as[Boolean] mustBe
        false
      reviews.skipped(challenge.id, me) mustBe Set.empty
      audits(t.id) mustBe empty

      val marked = reviews.needsVisit(challenge.id, t.id, me, on = true).toOption.get
      (marked \ "state").as[String] mustBe ReviewPage.NeedsVisit
      reviews.needsVisit(challenge.id, t.id, me, on = true).isRight mustBe true
      audits(t.id) mustBe Seq("review.needs_visit")
      val cleared = reviews.needsVisit(challenge.id, t.id, me, on = false).toOption.get
      (cleared \ "state").as[String] mustBe ReviewPage.Open
      audits(t.id) mustBe Seq("review.needs_visit", "review.needs_visit_cleared")

      db.withConnection { implicit c =>
        SQL"UPDATE choice_review_places SET state = 'done' WHERE task_id = ${t.id}".executeUpdate()
      }
      reviews.needsVisit(challenge.id, t.id, me, on = true) mustBe Left(ReviewRefusal.AlreadyDone)
      (reviews.needsVisit(challenge.id, t.id, me, on = false).toOption.get \ "state")
        .as[String] mustBe
        ReviewPage.Done
      // A marked place stays a place once its answers are gone.
      db.withConnection { implicit c =>
        SQL"UPDATE choice_pending SET state = 'published' WHERE task_id = ${t.id}".executeUpdate()
      }
      reviews.places(challenge.id).map(_.id) must contain(t.id)
    }
  }
}

package org.maproulette.provider.choice

import akka.actor.ActorSystem
import anorm._
import java.time.{Duration, Instant}
import java.util.UUID
import org.maproulette.Config
import org.maproulette.auth.mobile._
import org.maproulette.auth.mobile.guest.{MobileGuest, MobileGuestRepository}
import org.maproulette.data.ActionManager
import org.maproulette.framework.model._
import org.maproulette.framework.service.TaskClusterService
import org.maproulette.framework.util.FrameworkHelper
import org.maproulette.provider.websockets.WebSocketProvider
import org.maproulette.session.{
  SearchChallengeParameters,
  SearchLocation,
  SearchParameters,
  SearchTaskParameters
}
import play.api.{Application, Configuration}
import play.api.db.Database
import play.api.libs.json._
import play.api.libs.ws.WSClient
import scala.concurrent.Await
import scala.concurrent.duration._

/**
  * Pending answers of deferred sign-up guests against a real database and the loopback fake OSM
  * API. Runs in FrameworkMasterSuite (tag "choice").
  */
class MobileChoicePendingSpec(implicit val application: Application) extends FrameworkHelper {
  override implicit val projectTestName: String = "MobileChoicePendingSpecProject"
  private implicit val ec: scala.concurrent.ExecutionContext =
    application.injector.instanceOf[scala.concurrent.ExecutionContext]
  private val db  = application.injector.instanceOf[Database]
  private val osm = new FakeOsmServer
  private val client = new ChoiceOsmClient(
    application.injector.instanceOf[WSClient],
    application.injector.instanceOf[Config]
  ) { override protected def baseUrl: String = osm.url }
  private val choice = new MobileChoiceService(
    db,
    taskDAL,
    challengeDAL,
    client,
    new OsmTokenOnlyStore,
    new MobileOsmTokenCipher(new MobileOAuthSettings(Configuration())),
    application.injector.instanceOf[WebSocketProvider],
    application.injector.instanceOf[ActionManager]
  )
  @volatile private var writesOn = true
  private val service = new MobileChoicePendingService(
    choice,
    new ChoicePendingRepository(db),
    new MobileOAuthSettings(Configuration("mobileOAuth.writeControlEnabled" -> true)),
    new MobileWritePolicy(db) { override def enabled: Boolean = writesOn },
    db,
    application.injector.instanceOf[ActorSystem]
  )
  private val guests               = new MobileGuestRepository(db)
  private var challenge: Challenge = _
  private var nextNode             = 9500L
  private val live                 = (_: JsObject) ++ Json.obj("liveMissingQuestions" -> true)

  private def await[A](future: scala.concurrent.Future[A]): A = Await.result(future, 30.seconds)

  private def guest(): MobileGuest =
    guests.create(
      UUID.randomUUID(),
      "pending-test",
      MobileSecrets.hash(UUID.randomUUID().toString),
      Instant.now().plus(Duration.ofDays(1)),
      Instant.now()
    )

  private def benchTask(
      payload: JsObject => JsObject = live,
      tags: Map[String, String] = Map("amenity" -> "bench")
  ): (Task, Long) = {
    nextNode += 1
    val node = nextNode
    osm.put("node", node, tags)
    val task = taskDAL.insert(
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
                .obj("type" -> "Point", "coordinates" -> Json.arr(-111.891, 40.7608)),
              "properties" -> Json.obj("@id" -> s"node/$node")
            )
          )
        ),
        cooperativeWork = Some(payload(ChoiceFixtures.withNode(node))),
        status = Some(Task.STATUS_CREATED)
      ),
      User.superUser
    )
    (task, node)
  }

  private def submit(g: MobileGuest, task: Task, body: String) =
    await(service.submit(g, task.id, body))
  private def status(task: Task): Int = db.withConnection { implicit c =>
    SQL"SELECT status FROM tasks WHERE id = ${task.id}".as(SqlParser.int("status").single)
  }
  private def code(response: ChoiceResponse) = (response.body \ "error").asOpt[String]

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    serviceManager.project.update(defaultProject.id, Json.obj("enabled" -> true), User.superUser)
    challenge = challengeDAL.insert(
      Challenge(
        -1,
        "pendingChallenge",
        null,
        null,
        general = ChallengeGeneral(
          User.superUser.osmProfile.id,
          defaultProject.id,
          "Add bench details",
          enabled = true,
          checkinComment = "Add bench details #maproulette",
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
            VALUES ('pending-test', 'Test', ARRAY['org.example:/cb'], 'tasks:read guest')
            ON CONFLICT DO NOTHING""".execute()
    }
  }
  override protected def afterAll(): Unit = {
    osm.stop()
    super.afterAll()
  }

  "Pending answers" should {
    "hold a guest's answer without writing OSM or the task status" taggedAs ChoiceTag in {
      val g         = guest()
      val (task, _) = benchTask()
      val response  = submit(g, task, """{"answers":{"backrest":"yes"}}""")
      response.status mustBe 200
      (response.body \ "state").as[String] mustBe "pending"
      val hold = Instant.parse((response.body \ "holdUntil").as[String])
      hold.isAfter(Instant.now().plus(Duration.ofDays(6))) mustBe true
      Instant
        .parse((response.body \ "expiresAt").as[String])
        .isAfter(
          Instant.now().plus(Duration.ofDays(29))
        ) mustBe true
      status(task) mustBe Task.STATUS_CREATED
      osm.writeAuth mustBe empty
      val listed = await(service.list(g, None, None)).body
      (listed \ "items" \ 0 \ "taskId").as[Long] mustBe task.id
      (listed \ "items" \ 0 \ "challengeId").as[Long] mustBe challenge.id
      (listed \ "next").toOption mustBe Some(JsNull)
    }

    "replace a resubmitted answer and withdraw it once" taggedAs ChoiceTag in {
      val g         = guest()
      val (task, _) = benchTask()
      submit(g, task, """{"answers":{"backrest":"yes"}}""").status mustBe 200
      submit(g, task, """{"outcome":"gone"}""").status mustBe 200
      (await(service.list(g, None, None)).body \ "items").as[JsArray].value.size mustBe 1
      db.withConnection { implicit c =>
        SQL"SELECT body::text FROM choice_pending WHERE task_id = ${task.id}"
          .as(SqlParser.str(1).single)
      } mustBe """{"outcome": "gone"}"""
      await(service.withdraw(g, task.id)).status mustBe 204
      await(service.withdraw(g, task.id)).status mustBe 404
    }

    "refuse fixed-question tasks, deletes and completed tasks" taggedAs ChoiceTag in {
      val g = guest()
      code(submit(g, benchTask(identity)._1, """{"answers":{"backrest":"yes"}}""")) mustBe
        Some("unsupported_task")
      code(submit(g, benchTask()._1, """{"outcome":"gone","delete":true}""")) mustBe
        Some("invalid_submission")
      code(submit(g, benchTask()._1, """{"answers":{"nope":"yes"}}""")) mustBe
        Some("invalid_submission")
      code(submit(g, benchTask()._1, """{"answers":""")) mustBe Some("invalid_request")
      val (done, _) = benchTask()
      db.withConnection { implicit c =>
        SQL"UPDATE tasks SET status = ${Task.STATUS_FIXED} WHERE id = ${done.id}".executeUpdate()
      }
      code(submit(g, done, """{"answers":{"backrest":"yes"}}""")) mustBe Some("task_completed")
    }

    "refuse an answer whose question was answered in OSM, or a gone element" taggedAs ChoiceTag in {
      val g             = guest()
      val (answered, _) = benchTask(tags = Map("amenity" -> "bench", "backrest" -> "no"))
      val response      = submit(g, answered, """{"answers":{"backrest":"yes"}}""")
      response.status mustBe 409
      (response.body \ "reason").as[String] mustBe "key_changed"
      submit(g, answered, """{"answers":{"material":"wood"}}""").status mustBe 200
      val (gone, node) = benchTask()
      osm.synchronized(osm.elements.remove(("node", node)))
      val stale = submit(g, gone, """{"answers":{"backrest":"yes"}}""")
      (stale.status, (stale.body \ "reason").as[String]) mustBe ((409, "element_gone"))
    }

    "accept answers while writes are off only for published challenges" taggedAs ChoiceTag in {
      val g = guest()
      writesOn = false
      try {
        val (task, _) = benchTask()
        code(submit(g, task, """{"answers":{"backrest":"yes"}}""")) mustBe
          Some("challenge_not_published")
        db.withConnection { implicit c =>
          val tag =
            SQL"""INSERT INTO tags (name, tag_type) VALUES ('mobile-survey-v1', 'challenges')
                          RETURNING id""".as(SqlParser.int("id").single)
          SQL"INSERT INTO tags_on_challenges (challenge_id, tag_id) VALUES (${challenge.id}, $tag)"
            .executeUpdate()
        }
        submit(g, task, """{"answers":{"backrest":"yes"}}""").status mustBe 200
      } finally writesOn = true
    }

    "refuse answers to a draft challenge as not found" taggedAs ChoiceTag in {
      val g         = guest()
      val (task, _) = benchTask()
      challengeDAL.update(Json.obj("enabled" -> false), User.superUser)(challenge.id)
      try code(submit(g, task, """{"answers":{"backrest":"yes"}}""")) mustBe Some("not_found")
      finally challengeDAL.update(Json.obj("enabled" -> true), User.superUser)(challenge.id)
      submit(g, task, """{"answers":{"backrest":"yes"}}""").status mustBe 200
    }

    "refuse answers once the guest is deleted" taggedAs ChoiceTag in {
      val g = guest()
      guests.delete(g.id, Instant.now())
      code(submit(g, benchTask()._1, """{"answers":{"backrest":"yes"}}""")) mustBe
        Some("invalid_token")
    }

    "page the list and reject bad paging parameters" taggedAs ChoiceTag in {
      val g = guest()
      Seq(benchTask()._1, benchTask()._1, benchTask()._1).foreach { task =>
        submit(g, task, """{"answers":{"backrest":"yes"}}""").status mustBe 200
      }
      val first = await(service.list(g, Some("2"), None)).body
      (first \ "items").as[JsArray].value.size mustBe 2
      val next = (first \ "next").as[String]
      (await(service.list(g, Some("2"), Some(next))).body \ "items").as[JsArray].value.size mustBe 1
      Seq((Some("0"), None), (Some("101"), None), (Some("x"), None), (None, Some("-1")))
        .foreach {
          case (limit, after) => await(service.list(g, limit, after)).status mustBe 400
        }
    }

    "hide held tasks from discovery with excludePending until the hold ends" taggedAs ChoiceTag in {
      val clusters = application.injector.instanceOf[TaskClusterService]
      def found(excludePending: Boolean): Set[Long] =
        clusters
          .getTasksInBoundingBox(
            User.superUser,
            SearchParameters(
              location = Some(SearchLocation(-180, -85, 180, 85)),
              challengeParams = SearchChallengeParameters(challengeIds = Some(List(challenge.id))),
              taskParams = SearchTaskParameters(excludePending = Some(excludePending))
            ),
            paging = org.maproulette.framework.psql.Paging(5000, 0),
            ignoreLocked = true
          )
          ._2
          .map(_.id)
          .toSet
      val g         = guest()
      val (task, _) = benchTask()
      found(true) must contain(task.id)
      submit(g, task, """{"answers":{"backrest":"yes"}}""").status mustBe 200
      found(true) must not contain task.id
      found(false) must contain(task.id)
      db.withConnection { implicit c =>
        SQL"UPDATE choice_pending SET hold_until = NOW() - INTERVAL '1 second' WHERE task_id = ${task.id}"
          .executeUpdate()
      }
      found(true) must contain(task.id)
    }

    "flag held tasks on tasks/box with includePending, and change nothing without it" taggedAs ChoiceTag in {
      import play.api.test.FakeRequest
      import play.api.test.Helpers.{contentAsJson, defaultAwaitTimeout, GET, OK}
      val controller =
        application.injector.instanceOf[org.maproulette.framework.controller.TaskController]
      def box(query: String): JsArray = {
        val result = controller
          .getTasksInBoundingBox(-180, -85, 180, 85, 5000, 0, false)
          .apply(FakeRequest(GET, s"/api/v2/tasks/box/-180/-85/180/85?cid=${challenge.id}$query"))
        play.api.test.Helpers.status(result) mustBe OK
        contentAsJson(result).as[JsArray]
      }
      def flags(query: String): Map[Long, Option[Boolean]] =
        box(query).value.map { t =>
          (t \ "id").as[Long] -> (t \ "pending").asOpt[Boolean]
        }.toMap
      val g         = guest()
      val (held, _) = benchTask()
      val (free, _) = benchTask()
      submit(g, held, """{"answers":{"backrest":"yes"}}""").status mustBe 200
      val flagged = flags("&includePending=true")
      flagged(held.id) mustBe Some(true)
      flagged(free.id) mustBe Some(false)
      flags("").values.flatten mustBe empty
      flags("&excludePending=true").keySet must not contain held.id
      new ChoicePendingRepository(db).held(Seq(held.id, free.id, held.id)) mustBe Set(held.id)
    }
  }
}

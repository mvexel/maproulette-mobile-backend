package org.maproulette.auth.mobile

import akka.actor.ActorSystem
import anorm._
import com.typesafe.config.ConfigFactory
import controllers.MobileCampaignController
import org.maproulette.framework.model._
import org.maproulette.framework.util.FrameworkHelper
import org.maproulette.provider.choice.ChoiceTag
import org.mockito.ArgumentMatchers.{any, anyLong, anyString, eq => is}
import org.mockito.Mockito.{times, verify}
import play.api.{Application, Configuration}
import play.api.db.Database
import play.api.libs.json.{JsArray, JsObject, JsValue, Json}
import play.api.test.FakeRequest
import play.api.test.Helpers.{
  contentAsJson,
  defaultAwaitTimeout,
  stubControllerComponents,
  BAD_REQUEST,
  CONFLICT,
  CREATED,
  FORBIDDEN,
  NOT_FOUND,
  OK
}
import scala.concurrent.ExecutionContext

/** The admin app's campaign and project routes against the real schema. Runs in FrameworkMasterSuite. */
class MobileCampaignSpec(implicit val application: Application) extends FrameworkHelper {
  override implicit val projectTestName: String = "MobileCampaignSpecProject"
  private implicit val ec: ExecutionContext =
    application.injector.instanceOf[ExecutionContext]
  private val db                                              = application.injector.instanceOf[Database]
  private implicit val materializer: akka.stream.Materializer = application.materializer

  private val settings = new MobileOAuthSettings(
    Configuration(ConfigFactory.parseString("""
    mobileOAuth {
      enabled = true
      callbackUri = "https://mr.example/oauth/mobile/callback"
      clients = []
    }"""))
  )
  private val admins = new MobileAdminCheck {
    def isAdmin(value: User): Boolean  = value.id == User.superUser.id
    def isAdmin(userId: Long): Boolean = userId == User.superUser.id
  }
  private val service = new MobileOAuthService(
    mock[MobileOAuthStore],
    settings,
    new StaticMobileClientRegistry(settings),
    admins,
    application.injector.instanceOf[ActorSystem]
  )
  private val audit = mock[MobileAdminRepository]
  private val controller = new MobileCampaignController(
    stubControllerComponents(),
    settings,
    service,
    new MobileCampaignRepository(db),
    audit,
    serviceManager.project
  )

  private def as(user: User = User.superUser, body: JsValue = Json.obj()) =
    FakeRequest()
      .withBody(body)
      .addAttr(MobileBearerIdentity.UserKey, user)
      .addAttr(MobileBearerIdentity.ScopesKey, Set(MobileScopes.Admin))

  private def campaign(id: Long): JsObject = {
    val all = contentAsJson(controller.listCampaigns(as()))
    (all \ "campaigns")
      .as[JsArray]
      .value
      .collectFirst { case c: JsObject if (c \ "id").as[Long] == id => c }
      .getOrElse(fail(s"campaign $id not in $all"))
  }

  private def choiceChallenge(projectId: Long): Challenge =
    challengeDAL.insert(
      Challenge(
        -1,
        "campaignSpecChallenge",
        null,
        null,
        general = ChallengeGeneral(
          User.superUser.osmProfile.id,
          projectId,
          "Instruction",
          enabled = true,
          cooperativeType = 3
        ),
        creation = ChallengeCreation(),
        priority = ChallengePriority(),
        extra = ChallengeExtra()
      ),
      User.superUser
    )

  private def task(parent: Challenge, n: Int, status: Int): Long = {
    val id = taskDAL
      .insert(
        Task(
          -1,
          s"campaignSpecTask$n",
          null,
          null,
          parent.id,
          geometries = Json.obj(
            "features" -> Json.arr(
              Json.obj(
                "type" -> "Feature",
                "geometry" -> Json
                  .obj("type" -> "Point", "coordinates" -> Json.arr(-111.89, 40.76)),
                "properties" -> Json.obj()
              )
            )
          )
        ),
        User.superUser
      )
      .id
    db.withConnection(implicit c =>
      SQL"UPDATE tasks SET status = $status WHERE id = $id".executeUpdate()
    )
    id
  }

  "The admin campaign routes" should {
    "create a project, list its campaign with progress, and turn the project off and on" taggedAs ChoiceTag in {
      val created =
        controller.createProject(as(body = Json.obj("name" -> "Campaign spec project")))
      play.api.test.Helpers.status(created) mustBe CREATED
      val project = contentAsJson(created)
      val id      = (project \ "id").as[Long]
      (project \ "enabled").as[Boolean] mustBe true
      verify(audit).record(anyLong(), is("project.create"), is(s"project:$id"), any())
      play.api.test.Helpers.status(
        controller.createProject(as(body = Json.obj("name" -> "campaign SPEC project")))
      ) mustBe CONFLICT

      val challenge = choiceChallenge(id)
      db.withConnection { implicit c =>
        SQL"INSERT INTO tags(name, tag_type) VALUES ('mobile-survey-v1', 'challenges') ON CONFLICT DO NOTHING"
          .executeUpdate()
        SQL"""INSERT INTO tags_on_challenges(challenge_id, tag_id)
              SELECT ${challenge.id}, id FROM tags WHERE name = 'mobile-survey-v1' LIMIT 1"""
          .executeUpdate()
      }
      task(challenge, 1, Task.STATUS_FIXED)
      task(challenge, 2, Task.STATUS_TOO_HARD)
      task(challenge, 3, Task.STATUS_CREATED)
      val held = task(challenge, 4, Task.STATUS_CREATED)
      task(challenge, 5, Task.STATUS_DELETED)
      val onMap   = task(challenge, 6, Task.STATUS_CREATED)
      val skipped = task(challenge, 7, Task.STATUS_CREATED)
      db.withConnection { implicit c =>
        // Set by importing choice tasks in practice; inserting plain tasks resets it.
        SQL"UPDATE challenges SET cooperative_type = 3 WHERE id = ${challenge.id}".executeUpdate()
        SQL"""INSERT INTO choice_pending(task_id, challenge_id, body, payload_digest, element_version, hold_until)
              VALUES ($held, ${challenge.id}, '{}'::jsonb, ${"0" * 64}, 1, NOW() + interval '1 day')"""
          .executeUpdate()
        // An answer OSM now carries keeps its place checked; a skipped one doesn't (H1c, H1d).
        SQL"""INSERT INTO choice_pending(task_id, challenge_id, body, payload_digest, element_version,
                hold_until, state)
              VALUES ($onMap, ${challenge.id}, '{}'::jsonb, ${"0" * 64}, 1, '9999-12-31', 'on_map'),
                ($skipped, ${challenge.id}, '{}'::jsonb, ${"0" * 64}, 1, '9999-12-31', 'skipped_stale')"""
          .executeUpdate()
      }

      val listed = campaign(challenge.id)
      (listed \ "listed").as[Boolean] mustBe true
      (listed \ "published").as[Boolean] mustBe true
      (listed \ "project" \ "enabled").as[Boolean] mustBe true
      (listed \ "tasks")
        .as[JsObject] mustBe Json.obj("total" -> 6, "checked" -> 3, "needsLook" -> 1)
      val listedProject = (contentAsJson(controller.listProjects(as())) \ "projects")
        .as[JsArray]
        .value
        .find(p => (p \ "id").as[Long] == id)
        .get
      (listedProject \ "campaigns").as[Long] mustBe 1
      (listedProject \ "published").as[Long] mustBe 1

      val off = controller.setProjectEnabled(id)(as(body = Json.obj("enabled" -> false)))
      play.api.test.Helpers.status(off) mustBe OK
      (contentAsJson(off) \ "enabled").as[Boolean] mustBe false
      verify(audit).record(anyLong(), is("project.disable"), is(s"project:$id"), any())
      (campaign(challenge.id) \ "listed").as[Boolean] mustBe false

      play.api.test.Helpers.status(
        controller.setProjectEnabled(id)(as(body = Json.obj("enabled" -> false)))
      ) mustBe OK
      verify(audit, times(1)).record(anyLong(), is("project.disable"), anyString(), any())

      play.api.test.Helpers.status(
        controller.setProjectEnabled(id)(as(body = Json.obj("enabled" -> true)))
      ) mustBe OK
      (campaign(challenge.id) \ "listed").as[Boolean] mustBe true
    }

    "refuse bad input, unknown projects and non-admins" taggedAs ChoiceTag in {
      Seq(
        Json.obj(),
        Json.obj("name" -> " padded"),
        Json.obj("name" -> "ok", "owner" -> 1),
        Json.obj("name" -> "ok", "displayName" -> "")
      ).foreach { body =>
        withClue(body)(
          play.api.test.Helpers.status(controller.createProject(as(body = body))) mustBe BAD_REQUEST
        )
      }
      play.api.test.Helpers.status(
        controller.setProjectEnabled(1)(as(body = Json.obj("enabled" -> "yes")))
      ) mustBe BAD_REQUEST
      play.api.test.Helpers.status(
        controller.setProjectEnabled(Long.MaxValue)(as(body = Json.obj("enabled" -> true)))
      ) mustBe NOT_FOUND
      val mapper = User.superUser.copy(id = User.superUser.id + 1000)
      play.api.test.Helpers.status(controller.listCampaigns(as(mapper))) mustBe FORBIDDEN
      play.api.test.Helpers.status(
        controller.createProject(as(mapper, Json.obj("name" -> "Not allowed")))
      ) mustBe FORBIDDEN
    }
  }
}

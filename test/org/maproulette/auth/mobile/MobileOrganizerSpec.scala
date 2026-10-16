package org.maproulette.auth.mobile

import akka.actor.ActorSystem
import anorm._
import com.typesafe.config.ConfigFactory
import controllers.{MobileCampaignController, MobileOrganizerController}
import org.maproulette.framework.model._
import org.maproulette.framework.util.FrameworkHelper
import org.maproulette.provider.choice.ChoiceTag
import play.api.{Application, Configuration}
import play.api.db.Database
import play.api.libs.json.{JsArray, JsValue, Json}
import play.api.test.FakeRequest
import play.api.test.Helpers.{
  contentAsJson,
  defaultAwaitTimeout,
  status,
  stubControllerComponents,
  BAD_REQUEST,
  FORBIDDEN,
  NOT_FOUND,
  NO_CONTENT,
  OK
}
import scala.concurrent.ExecutionContext

/**
  * The organizer list (evolution 145), the Organizers routes and what an organizer then sees, with
  * the real gate and upstream project grants. Runs in FrameworkMasterSuite (tag "choice").
  */
class MobileOrganizerSpec(implicit val application: Application) extends FrameworkHelper {
  override implicit val projectTestName: String = "MobileOrganizerSpecProject"
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
  private val gate = application.injector.instanceOf[MobileAdminGate]
  // The real gate, except that the system super-user (id -999) counts as a super-user here.
  private val admins = new MobileAdminCheck {
    def isAdmin(value: User): Boolean                       = value.id == User.superUser.id
    def isAdmin(userId: Long): Boolean                      = userId == User.superUser.id
    override def isOrganizer(userId: Long): Boolean         = gate.isOrganizer(userId)
    override def organizerProjects(user: User): Set[Long]   = gate.organizerProjects(user)
    override def projectOf(challengeId: Long): Option[Long] = gate.projectOf(challengeId)
  }
  private val service = new MobileOAuthService(
    mock[MobileOAuthStore],
    settings,
    new StaticMobileClientRegistry(settings),
    admins,
    application.injector.instanceOf[ActorSystem]
  )
  private val organizers =
    new MobileOrganizerRepository(db, application.injector.instanceOf[MobileAdminRepository])
  private val controller = new MobileOrganizerController(
    stubControllerComponents(),
    settings,
    service,
    organizers,
    serviceManager.user,
    serviceManager.project
  )
  private val campaigns = new MobileCampaignController(
    stubControllerComponents(),
    settings,
    service,
    new MobileCampaignRepository(db),
    application.injector.instanceOf[MobileAdminRepository],
    serviceManager.project
  )

  private def as(user: User, scope: String = MobileScopes.Admin, body: JsValue = Json.obj()) =
    FakeRequest()
      .withBody(body)
      .addAttr(MobileBearerIdentity.UserKey, user)
      .addAttr(MobileBearerIdentity.ScopesKey, Set(scope))

  private def project(name: String): Project =
    serviceManager.project.create(Project(-1, User.superUser.osmProfile.id, name), User.superUser)

  private def campaign(in: Project, name: String): Challenge = {
    val created = challengeDAL.insert(
      Challenge(
        -1,
        name,
        null,
        null,
        general = ChallengeGeneral(User.superUser.osmProfile.id, in.id, "Instruction"),
        creation = ChallengeCreation(),
        priority = ChallengePriority(),
        extra = ChallengeExtra()
      ),
      User.superUser
    )
    db.withConnection(implicit c =>
      SQL"UPDATE challenges SET cooperative_type = 3 WHERE id = ${created.id}".executeUpdate()
    )
    created
  }

  private def ids(result: JsValue, key: String, id: JsValue => Long): Set[Long] =
    (result \ key).as[JsArray].value.map(id).toSet

  "The Organizers routes" should {
    "add an organizer to a project, scope what they see, and take it away again" taggedAs ChoiceTag in {
      val dana = serviceManager.user
        .create(getTestUser(77001, "Dana_Organizer"), User.superUser)
      val mine   = project("Organizer spec: Dana's")
      val theirs = project("Organizer spec: someone else's")
      val c1     = campaign(mine, "Organizer spec benches")
      val c2     = campaign(theirs, "Organizer spec doors")
      def add(name: String, projectId: Long, role: String) =
        controller.add(
          as(
            User.superUser,
            body = Json.obj("osmUsername" -> name, "projectId" -> projectId, "role" -> role)
          )
        )

      status(add("nobody_signed_in", mine.id, "write")) mustBe NOT_FOUND
      contentAsJson(add("Dana_Organizer", Long.MaxValue, "write")) mustBe
        Json.obj("error" -> "project_not_found")
      status(add("Dana_Organizer", mine.id, "owner")) mustBe BAD_REQUEST
      gate.isOrganizer(dana.id) mustBe false

      // Case does not matter, and an underscore is not a pattern.
      val added = add("dana_organizer", mine.id, "write")
      status(added) mustBe OK
      (contentAsJson(added) \ "userId").as[Long] mustBe dana.id
      (contentAsJson(added) \ "projects").as[JsArray].value.map(p => (p \ "role").as[String]) mustBe
        Seq("write")
      status(add("Dana%Organizer", mine.id, "write")) mustBe NOT_FOUND
      gate.isOrganizer(dana.id) mustBe true
      gate.organizerProjects(dana) mustBe Set(mine.id)
      gate.projectOf(c2.id) mustBe Some(theirs.id)

      val danaCampaigns = contentAsJson(campaigns.listCampaigns(as(dana, MobileScopes.Organize)))
      ids(danaCampaigns, "campaigns", c => (c \ "id").as[Long]) must contain(c1.id)
      ids(danaCampaigns, "campaigns", c => (c \ "id").as[Long]) must not contain c2.id
      ids(
        contentAsJson(campaigns.listProjects(as(dana, MobileScopes.Organize))),
        "projects",
        p => (p \ "id").as[Long]
      ) mustBe Set(mine.id)
      ids(
        contentAsJson(campaigns.listProjects(as(User.superUser))),
        "projects",
        p => (p \ "id").as[Long]
      ) must
        contain(theirs.id)

      def enable(p: Project) =
        campaigns.setProjectEnabled(p.id)(
          as(dana, MobileScopes.Organize, Json.obj("enabled" -> true))
        )
      status(enable(mine)) mustBe OK
      contentAsJson(enable(theirs)) mustBe Json.obj("error" -> "organizer_forbidden")
      status(
        campaigns.createProject(as(dana, MobileScopes.Organize, Json.obj("name" -> "Nope")))
      ) mustBe FORBIDDEN
      admins.canChallenge(dana, Set(MobileScopes.Organize), c1.id) mustBe true
      admins.canChallenge(dana, Set(MobileScopes.Organize), c2.id) mustBe false
      status(controller.list(as(dana, MobileScopes.Organize))) mustBe FORBIDDEN

      // A second project, then off it again.
      status(add("Dana_Organizer", theirs.id, "admin")) mustBe OK
      gate.organizerProjects(dana) mustBe Set(mine.id, theirs.id)
      val listed = (contentAsJson(controller.list(as(User.superUser))) \ "organizers")
        .as[JsArray]
        .value
        .find(o => (o \ "userId").as[Long] == dana.id)
        .get
      (listed \ "projects").as[JsArray].value.map(p => (p \ "id").as[Long]).toSet mustBe
        Set(mine.id, theirs.id)
      status(controller.removeProject(dana.id, theirs.id)(as(User.superUser))) mustBe OK
      gate.organizerProjects(dana) mustBe Set(mine.id)
      status(controller.removeProject(dana.id + 1000, mine.id)(as(User.superUser))) mustBe
        NOT_FOUND

      // Off the list: the next request is refused, the project role stays.
      status(controller.remove(dana.id)(as(User.superUser))) mustBe NO_CONTENT
      gate.isOrganizer(dana.id) mustBe false
      contentAsJson(campaigns.listCampaigns(as(dana, MobileScopes.Organize))) mustBe
        Json.obj("error" -> "organizer_forbidden")
      status(controller.remove(dana.id)(as(User.superUser))) mustBe NOT_FOUND
      gate.organizerProjects(dana) mustBe Set(mine.id)

      db.withConnection { implicit c =>
        SQL"""SELECT action FROM mobile_admin_audit WHERE target = ${s"user:${dana.id}"}
              ORDER BY id""".as(SqlParser.str("action").*)
      } mustBe Seq("organizer.add", "organizer.add", "organizer.project.remove", "organizer.remove")
    }
  }
}

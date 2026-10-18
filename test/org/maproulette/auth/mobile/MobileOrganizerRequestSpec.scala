package org.maproulette.auth.mobile

import akka.actor.ActorSystem
import anorm._
import com.typesafe.config.ConfigFactory
import controllers.{MobileOrganizerController, MobileOrganizerRequestController}
import java.time.Instant
import java.time.temporal.ChronoUnit
import org.maproulette.framework.model._
import org.maproulette.framework.util.FrameworkHelper
import org.maproulette.provider.choice.ChoiceTag
import play.api.{Application, Configuration}
import play.api.db.Database
import play.api.libs.json.{JsArray, JsNull, JsValue, Json}
import play.api.test.FakeRequest
import play.api.test.Helpers.{
  contentAsJson,
  defaultAwaitTimeout,
  status,
  stubControllerComponents,
  BAD_REQUEST,
  CONFLICT,
  CREATED,
  FORBIDDEN,
  NO_CONTENT,
  NOT_FOUND,
  OK
}
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}

/**
  * Becoming an organizer (evolution 151, ORG-ONB): asking, the super-user decisions, the 30-day
  * rule and what removal does to an old approval, with the real gate and upstream project grants.
  * Runs in FrameworkMasterSuite (tag "choice").
  */
class MobileOrganizerRequestSpec(implicit val application: Application) extends FrameworkHelper {
  override implicit val projectTestName: String = "MobileOrganizerRequestSpecProject"
  private implicit val ec: ExecutionContext =
    application.injector.instanceOf[ExecutionContext]
  private val db                                              = application.injector.instanceOf[Database]
  private implicit val materializer: akka.stream.Materializer = application.materializer

  private def settings(requests: String) = new MobileOAuthSettings(
    Configuration(ConfigFactory.parseString(s"""
    mobileOAuth {
      enabled = true
      callbackUri = "https://mr.example/oauth/mobile/callback"
      clients = []
      organizers.requests = "$requests"
    }"""))
  )
  private val gate = application.injector.instanceOf[MobileAdminGate]
  private val admins = new MobileAdminCheck {
    def isAdmin(value: User): Boolean                     = value.id == User.superUser.id
    def isAdmin(userId: Long): Boolean                    = userId == User.superUser.id
    override def isOrganizer(userId: Long): Boolean       = gate.isOrganizer(userId)
    override def organizerProjects(user: User): Set[Long] = gate.organizerProjects(user)
  }
  private val audit    = application.injector.instanceOf[MobileAdminRepository]
  private val requests = new MobileOrganizerRequestRepository(db, audit)
  private val created  = Instant.parse("2019-05-01T10:00:00Z")
  private val lookups  = new java.util.concurrent.atomic.AtomicInteger()
  private val osm = new OsmAccountStats {
    def lookup(osmId: Long): Future[OsmAccount] = {
      lookups.incrementAndGet()
      Future.successful(OsmAccount(Some(created), Some(42)))
    }
  }
  private def controller(requestsSetting: String = MobileOrganizerPolicy.Review) = {
    val values = settings(requestsSetting)
    new MobileOrganizerRequestController(
      stubControllerComponents(),
      values,
      new MobileOAuthService(
        mock[MobileOAuthStore],
        values,
        new StaticMobileClientRegistry(values),
        admins,
        application.injector.instanceOf[ActorSystem]
      ),
      requests,
      osm,
      serviceManager.user,
      serviceManager.project
    )
  }
  private val open = controller()
  private val organizers = {
    val values = settings(MobileOrganizerPolicy.Review)
    new MobileOrganizerController(
      stubControllerComponents(),
      values,
      new MobileOAuthService(
        mock[MobileOAuthStore],
        values,
        new StaticMobileClientRegistry(values),
        admins,
        application.injector.instanceOf[ActorSystem]
      ),
      new MobileOrganizerRepository(db, audit),
      serviceManager.user,
      serviceManager.project
    )
  }

  private def as(
      user: User,
      scope: String = MobileScopes.OrganizerRequest,
      body: JsValue = Json.obj()
  ) =
    FakeRequest()
      .withBody(body)
      .addAttr(MobileBearerIdentity.UserKey, user)
      .addAttr(MobileBearerIdentity.ScopesKey, Set(scope))

  private val ask = Json.obj(
    "place"          -> "Woodbine, Toronto",
    "subject"        -> "Entrances and crossings",
    "group"          -> "Woodbine Walkers",
    "policyAccepted" -> true
  )

  private def mine(user: User): JsValue = contentAsJson(open.status(as(user)))

  private def actions(target: String): Seq[String] = db.withConnection { implicit c =>
    SQL"SELECT action FROM mobile_admin_audit WHERE target = $target ORDER BY id"
      .as(SqlParser.str("action").*)
  }

  private def project(name: String): Project =
    serviceManager.project.create(Project(-1, User.superUser.osmProfile.id, name), User.superUser)

  private def role(projectId: Long, userId: Long): Option[Int] =
    permission.effectiveRole(
      serviceManager.project.retrieve(projectId).get,
      serviceManager.user.retrieve(userId).get
    )

  "Organizer requests" should {
    "go from asking to approval, and an old approval ends with removal" taggedAs ChoiceTag in {
      val gil = serviceManager.user.create(getTestUser(78001, "Gil_Requester"), User.superUser)
      gate.mayRequestOrganizer(gil.id) mustBe true
      gate.mayHold(MobileScopes.OrganizerRequest, gil.id) mustBe true
      gate.mayHold(MobileScopes.Organize, gil.id) mustBe false
      mine(gil) mustBe Json.obj(
        "name"       -> "Gil_Requester",
        "osmId"      -> gil.osmProfile.id,
        "organizer"  -> false,
        "canRequest" -> true,
        "askAgainAt" -> JsNull,
        "request"    -> JsNull
      )

      status(open.create(as(gil, body = ask - "policyAccepted"))) mustBe BAD_REQUEST
      status(open.create(as(gil, body = ask + ("place" -> Json.toJson(" "))))) mustBe BAD_REQUEST
      status(open.create(as(gil, body = ask + ("extra" -> Json.toJson(1))))) mustBe BAD_REQUEST
      status(open.create(as(gil, MobileScopes.Admin, ask))) mustBe FORBIDDEN
      status(open.create(as(gil, MobileScopes.Organize, ask))) mustBe FORBIDDEN

      val asked = open.create(as(gil, body = ask))
      status(asked) mustBe CREATED
      val id = (contentAsJson(asked) \ "id").as[Long]
      (contentAsJson(asked) \ "state").as[String] mustBe "open"
      (contentAsJson(asked) \ "group").as[String] mustBe "Woodbine Walkers"
      contentAsJson(open.create(as(gil, body = ask))) mustBe Json.obj("error" -> "request_open")
      (mine(gil) \ "canRequest").as[Boolean] mustBe false
      (mine(gil) \ "request" \ "id").as[Long] mustBe id

      // Super-users see it with the OSM snapshot; the requester can't list or decide.
      val listed = (contentAsJson(open.list(as(User.superUser, MobileScopes.Admin))) \ "requests")
        .as[JsArray]
        .value
        .find(r => (r \ "id").as[Long] == id)
        .get
      (listed \ "name").as[String] mustBe "Gil_Requester"
      (listed \ "osmAccount" \ "changesets").as[Int] mustBe 42
      (listed \ "osmAccount" \ "createdAt").as[Instant] mustBe created
      status(open.list(as(gil))) mustBe FORBIDDEN
      status(open.approve(id)(as(gil, body = Json.obj("projectName" -> "Gil's")))) mustBe FORBIDDEN

      // A taken name changes nothing.
      val taken = project("Request spec: taken")
      val clash = open.approve(id)(
        as(User.superUser, MobileScopes.Admin, Json.obj("projectName" -> "REQUEST SPEC: TAKEN"))
      )
      status(clash) mustBe CONFLICT
      contentAsJson(clash) mustBe Json.obj("error" -> "project_exists")
      (mine(gil) \ "request" \ "state").as[String] mustBe "open"
      gate.isOrganizer(gil.id) mustBe false

      val approved = open.approve(id)(
        as(User.superUser, MobileScopes.Admin, Json.obj("projectName" -> "Woodbine, Toronto"))
      )
      status(approved) mustBe OK
      val projectId = (contentAsJson(approved) \ "projectId").as[Long]
      (contentAsJson(approved) \ "state").as[String] mustBe "approved"
      serviceManager.project.retrieve(projectId).get.name mustBe "Woodbine, Toronto"
      gate.isOrganizer(gil.id) mustBe true
      gate.organizerProjects(serviceManager.user.retrieve(gil.id).get) mustBe Set(projectId)
      // Upstream's own checks agree: Write for the requester, Admin for the approver.
      role(projectId, gil.id) mustBe Some(Grant.ROLE_WRITE_ACCESS)
      // The approver's own Admin grant, as upstream gives a project's creator (a super-user's
      // effective role is the super-user role, so the row is checked).
      db.withConnection { implicit c =>
        SQL"""SELECT role FROM grants WHERE object_type = 0 AND object_id = $projectId
              AND grantee_type = 5 AND grantee_id = ${User.superUser.id}"""
          .as(SqlParser.int("role").*)
      } mustBe Seq(Grant.ROLE_ADMIN)
      (mine(gil) \ "organizer").as[Boolean] mustBe true
      (mine(gil) \ "canRequest").as[Boolean] mustBe false
      contentAsJson(open.create(as(gil, body = ask))) mustBe Json.obj(
        "error" -> "already_organizer"
      )
      val again = open.approve(id)(
        as(User.superUser, MobileScopes.Admin, Json.obj("projectName" -> "Woodbine 2"))
      )
      contentAsJson(again) mustBe Json.obj("error" -> "not_open", "state" -> "approved")
      status(open.decline(id + 100000)(as(User.superUser, MobileScopes.Admin))) mustBe NOT_FOUND

      // A project they had before stays theirs; the one their request created doesn't.
      val earlier = project("Request spec: Gil's earlier")
      status(
        organizers.add(
          as(
            User.superUser,
            MobileScopes.Admin,
            Json.obj("osmUsername" -> "Gil_Requester", "projectId" -> earlier.id, "role" -> "write")
          )
        )
      ) mustBe OK
      status(organizers.remove(gil.id)(as(User.superUser, MobileScopes.Admin))) mustBe NO_CONTENT
      gate.isOrganizer(gil.id) mustBe false
      role(projectId, gil.id) mustBe None
      role(earlier.id, gil.id) mustBe Some(Grant.ROLE_WRITE_ACCESS)
      // The old approval is void: no request to show, and the next one is new.
      mine(gil) mustBe Json.obj(
        "name"       -> "Gil_Requester",
        "osmId"      -> gil.osmProfile.id,
        "organizer"  -> false,
        "canRequest" -> true,
        "askAgainAt" -> JsNull,
        "request"    -> JsNull
      )
      val next = open.create(as(gil, body = ask))
      status(next) mustBe CREATED
      (contentAsJson(next) \ "id").as[Long] must not be id

      actions(s"organizer_request:$id") mustBe Seq("organizer.request", "organizer.request.approve")
      actions(s"user:${gil.id}") mustBe Seq("organizer.add", "organizer.remove")
      taken.id must not be projectId
    }

    "let a declined person ask again only 30 days after the decline" taggedAs ChoiceTag in {
      val hal = serviceManager.user.create(getTestUser(78002, "Hal_Requester"), User.superUser)
      val id  = (contentAsJson(open.create(as(hal, body = ask - "group"))) \ "id").as[Long]
      status(
        open.decline(id)(as(User.superUser, MobileScopes.Admin, Json.obj("note" -> " padded ")))
      ) mustBe BAD_REQUEST
      val declined = open.decline(id)(
        as(User.superUser, MobileScopes.Admin, Json.obj("note" -> "Try a smaller area first."))
      )
      status(declined) mustBe OK
      (contentAsJson(declined) \ "state").as[String] mustBe "declined"
      val view = mine(hal)
      (view \ "request" \ "note").as[String] mustBe "Try a smaller area first."
      (view \ "canRequest").as[Boolean] mustBe false
      val at = (view \ "askAgainAt").as[Instant]
      at.isAfter(Instant.now().plus(29, ChronoUnit.DAYS)) mustBe true
      val before = lookups.get()
      val soon   = open.create(as(hal, body = ask))
      status(soon) mustBe CONFLICT
      // A refused ask costs no call to OSM.
      lookups.get() mustBe before
      (contentAsJson(soon) \ "error").as[String] mustBe "too_soon"
      (contentAsJson(soon) \ "askAgainAt").as[Instant] mustBe at
      contentAsJson(open.decline(id)(as(User.superUser, MobileScopes.Admin))) mustBe
        Json.obj("error" -> "not_open", "state" -> "declined")

      val later = Instant.now().plus(31, ChronoUnit.DAYS)
      requests.create(
        hal,
        OrganizerRequestInput("Woodbine", "Doors", None),
        OsmAccount.Unknown,
        later
      ) must matchPattern { case Right(_) => }
      // Without an OSM answer the account date comes from sign-in.
      db.withConnection { implicit c =>
        SQL"""SELECT osm_account_created IS NOT NULL AND osm_changesets IS NULL
              FROM mobile_organizer_requests WHERE user_id = ${hal.id} AND state = 'open'"""
          .as(SqlParser.scalar[Boolean].single)
      } mustBe true
      actions(s"organizer_request:$id") mustBe Seq("organizer.request", "organizer.request.decline")
    }

    "keep one open request when the same person asks twice at once" taggedAs ChoiceTag in {
      val ivy = serviceManager.user.create(getTestUser(78003, "Ivy_Requester"), User.superUser)
      val tries = (1 to 8).map(_ =>
        Future(
          requests.create(ivy, OrganizerRequestInput("Here", "Trees", None), OsmAccount.Unknown)
        )
      )
      val results = Await.result(Future.sequence(tries), 30.seconds)
      results.count(_.isRight) mustBe 1
      results.collect { case Left(refusal) => refusal.code }.toSet mustBe Set("request_open")
      db.withConnection { implicit c =>
        SQL"SELECT COUNT(*) FROM mobile_organizer_requests WHERE user_id = ${ivy.id}"
          .as(SqlParser.scalar[Long].single)
      } mustBe 1
    }

    "take no new requests while they are closed" taggedAs ChoiceTag in {
      val jo     = serviceManager.user.create(getTestUser(78004, "Jo_Requester"), User.superUser)
      val closed = controller(MobileOrganizerPolicy.Closed)
      (contentAsJson(closed.status(as(jo))) \ "canRequest").as[Boolean] mustBe false
      contentAsJson(closed.create(as(jo, body = ask))) mustBe Json.obj("error" -> "requests_closed")
      settings("anything else").organizerRequestsOpen mustBe false
      settings(MobileOrganizerPolicy.Review).organizerRequestsOpen mustBe true
    }
  }
}

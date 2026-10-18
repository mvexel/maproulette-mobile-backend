package org.maproulette.auth.mobile

import akka.actor.ActorSystem
import akka.stream.{Materializer, SystemMaterializer}
import com.typesafe.config.ConfigFactory
import controllers.{
  MobileAdminController,
  MobileLiveController,
  MobileOAuthController,
  MobileOrganizerController,
  MobileQueueController,
  MobileResultsController
}
import java.time.Instant
import org.joda.time.DateTime
import org.maproulette.Config
import org.maproulette.framework.model.{Location, OSMProfile, User}
import org.maproulette.framework.service.UserService
import org.maproulette.provider.choice.{
  ChoiceQueueService,
  ChoiceResultsRepository,
  LivePageRepository
}
import org.mockito.ArgumentMatchers.{any, anyLong, anyString}
import org.mockito.Mockito.{never, verify, when}
import org.scalatest.BeforeAndAfterAll
import org.scalatestplus.mockito.MockitoSugar
import org.scalatestplus.play.PlaySpec
import play.api.Configuration
import play.api.libs.json.Json
import play.api.libs.ws.{WSClient, WSRequest, WSResponse}
import play.api.mvc.{Cookie, RequestHeader, Results}
import play.api.test.FakeRequest
import play.api.test.Helpers._
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}
import scalaoauth2.provider.{InvalidGrant, InvalidScope}

/** The organizer role (D8): `mobile:organize`, its routes and the capability check. No database. */
class MobileOrganizeSpec extends PlaySpec with MockitoSugar with BeforeAndAfterAll {
  private implicit val system: ActorSystem = ActorSystem(
    "mobile-organize-test",
    ConfigFactory.parseString("""
    mobile-oauth-dispatcher {
      type=Dispatcher
      executor="thread-pool-executor"
      thread-pool-executor.fixed-pool-size=2
    }
  """)
  )
  private implicit val materializer: Materializer = SystemMaterializer(system).materializer
  private implicit val ec: ExecutionContext       = system.dispatcher
  override def afterAll(): Unit                   = { Await.result(system.terminate(), 5.seconds); super.afterAll() }

  private val adminOrigin = "https://admin.example"
  private val config      = ConfigFactory.parseString(s"""
    mobileOAuth {
      enabled = true
      callbackUri = "https://mr.example/oauth/mobile/callback"
      adminOrigin = "$adminOrigin"
      clients = [
        { id = "app", name = "App", redirectUris = ["org.example.app:/cb"], scopes = ["tasks:read", "tasks:write"] },
        { id = "admin", name = "Admin", redirectUris = ["$adminOrigin/callback"], scopes = ["mobile:admin"] }
      ]
    }""")
  private val settings    = new MobileOAuthSettings(Configuration(config))
  private val token       = "A" * 43
  private def user(id: Long) = User(
    id,
    DateTime.now(),
    DateTime.now(),
    OSMProfile(456, "Mapper", "", "", Location(1, 2), DateTime.now(), "legacy-token"),
    List.empty
  )
  private val superUser = user(7)
  private val mapper    = user(8)
  private val organizer = user(9)
  // Not listed, but may ask to be (ORG-ONB).
  private val requester = user(10)
  // The organizer runs project 3; challenge 70 is in project 3, challenge 71 in project 4.
  private val admins = new MobileAdminCheck {
    def isAdmin(value: User): Boolean                       = value.id == superUser.id
    def isAdmin(userId: Long): Boolean                      = userId == superUser.id
    override def isOrganizer(userId: Long): Boolean         = userId == organizer.id
    override def mayRequestOrganizer(userId: Long): Boolean = userId == requester.id
    override def organizerProjects(u: User): Set[Long]      = if (u.id == organizer.id) Set(3) else Set()
    override def projectOf(challengeId: Long): Option[Long] =
      Map(70L -> 3L, 71L -> 4L).get(challengeId)
  }
  private val organizeGrant =
    MobileGrant("fam-org", 9, "admin", "mobile:organize", s"$adminOrigin/callback", "a" * 43)
  private val adminGrant = organizeGrant.copy(userId = 7, scope = "mobile:admin")

  private def service(store: MobileOAuthStore = mock[MobileOAuthStore]) =
    new MobileOAuthService(
      store,
      settings,
      new StaticMobileClientRegistry(settings),
      admins,
      system
    )

  private val Organize = Set(MobileScopes.Organize)
  private val Admin    = Set(MobileScopes.Admin)

  "The mobile:organize scope" should {
    "stand alone, like mobile:admin" in {
      MobileScopes.parse("mobile:organize") mustBe Some(Organize)
      Seq(
        "tasks:read mobile:organize",
        "mobile:admin mobile:organize",
        "mobile:organize mobile:admin"
      ).foreach(value => MobileScopes.parse(value) mustBe None)
      MobileScopes.isOrganize("mobile:organize") mustBe true
      MobileScopes.isAdmin("mobile:organize") mustBe false
      MobileScopes.osmScope("mobile:organize") mustBe "read_prefs"
      MobileScopes.parseClient("mobile:organize") mustBe Some(Organize)
      MobileScopes.parseClient("mobile:organize guest") mustBe None
      MobileScopes.clientScopeSets must contain(Seq("mobile:organize"))
    }

    "keep mobile:organizer-request alone, granted with mobile:admin and never configured" in {
      MobileScopes.parse("mobile:organizer-request") mustBe Some(Set(MobileScopes.OrganizerRequest))
      Seq(
        "tasks:read mobile:organizer-request",
        "mobile:organize mobile:organizer-request",
        "mobile:admin mobile:organizer-request"
      ).foreach(value => MobileScopes.parse(value) mustBe None)
      MobileScopes.isOrganize("mobile:organizer-request") mustBe false
      MobileScopes.isStaff(Set(MobileScopes.OrganizerRequest)) mustBe false
      MobileScopes.parseClient("mobile:organizer-request") mustBe None
      MobileScopes.clientScopeSets.flatten must not contain MobileScopes.OrganizerRequest
      MobileScopes.granted(Set(MobileScopes.Admin)) must contain(MobileScopes.OrganizerRequest)
      MobileScopes.granted(Set(MobileScopes.Read)) must not contain MobileScopes.OrganizerRequest
      MobileScopes.osmScope("mobile:organizer-request") mustBe "read_prefs"
    }

    "be granted to a client that may hold mobile:admin, and to no app client" in {
      def query(client: String, redirect: String) = Map(
        "response_type"         -> Seq("code"),
        "client_id"             -> Seq(client),
        "redirect_uri"          -> Seq(redirect),
        "scope"                 -> Seq("mobile:organize"),
        "state"                 -> Seq("s"),
        "code_challenge"        -> Seq("a" * 43),
        "code_challenge_method" -> Seq("S256")
      )
      service().authorization(query("admin", s"$adminOrigin/callback")).isRight mustBe true
      service()
        .authorization(query("app", "org.example.app:/cb"))
        .left
        .map(_.getClass) mustBe Left(classOf[InvalidScope])
      MobileScopes.granted(Set(MobileScopes.Read)) mustBe Set(MobileScopes.Read)
    }

    "refresh only while the user is on the organizer list" in {
      val store = mock[MobileOAuthStore]
      val refresh = Map(
        "grant_type"    -> Seq("refresh_token"),
        "client_id"     -> Seq("admin"),
        "refresh_token" -> Seq("r")
      )
      when(store.findRefresh(anyString(), any[Instant]))
        .thenReturn(Some(organizeGrant.copy(userId = mapper.id)))
      Await.result(service(store).exchange(refresh, false), 5.seconds).left.map(_.getClass) mustBe
        Left(classOf[InvalidGrant])
      when(store.findRefresh(anyString(), any[Instant])).thenReturn(Some(organizeGrant))
      when(store.rotate(anyString(), anyString(), any[TokenHashes], any[Instant]))
        .thenReturn(Some(organizeGrant))
      Await.result(service(store).exchange(refresh, false), 5.seconds).map(_.scope) mustBe
        Right(Some("mobile:organize"))
    }
  }

  "The capability check" should {
    "give super-users everything, organizers their projects' campaigns, app grants nothing" in {
      import MobileCapability._
      val all = Seq(Operate, ListCampaigns, SeeWritePolicy, Campaigns(3), Campaigns(4))
      all.foreach(c => admins.can(superUser, Admin, c) mustBe true)
      all.foreach(c => admins.can(mapper, Admin, c) mustBe false)
      all.map(admins.can(organizer, Organize, _)) mustBe Seq(false, true, true, true, false)
      // On the list but holding an admin grant, or a super-user holding an organizer grant.
      all.foreach(c => admins.can(organizer, Admin, c) mustBe false)
      all.foreach(c => admins.can(superUser, Organize, c) mustBe false)
      all.foreach(c =>
        admins.can(organizer, Set(MobileScopes.Read, MobileScopes.Write), c) mustBe false
      )
      admins.canChallenge(organizer, Organize, 70) mustBe true
      admins.canChallenge(organizer, Organize, 71) mustBe false
      admins.canChallenge(organizer, Organize, 72) mustBe false
      admins.canChallenge(superUser, Admin, 72) mustBe true
      admins.mayHold("mobile:organize", organizer.id) mustBe true
      admins.mayHold("mobile:organize", superUser.id) mustBe false
      admins.mayHold("mobile:admin", organizer.id) mustBe false
      admins.mayHold("tasks:read", mapper.id) mustBe true
    }
  }

  "The organizer sign-in" should {
    implicit val configuration: Configuration = Configuration.from(
      Map(
        Config.KEY_OSM_SERVER            -> "https://osm.example",
        Config.KEY_OSM_USER_DETAILS_URL  -> "/api/0.6/user/details",
        Config.KEY_OSM_REQUEST_TOKEN_URL -> "/oauth/request_token",
        Config.KEY_OSM_ACCESS_TOKEN_URL  -> "/oauth/access_token",
        Config.KEY_OSM_AUTHORIZATION_URL -> "/oauth/authorize",
        Config.KEY_OSM_CONSUMER_KEY      -> "test-client",
        Config.KEY_OSM_CONSUMER_SECRET   -> "test-secret",
        Config.KEY_OSM_OAUTH2_SCOPE      -> "read_prefs"
      )
    )
    val state   = MobileSecrets.generate()
    val browser = MobileSecrets.generate()
    val interaction = MobileInteraction(
      MobileSecrets.hash(state),
      MobileSecrets.hash(browser),
      "admin",
      s"$adminOrigin/callback",
      "mobile:organize",
      "web-state",
      "a" * 43,
      Instant.now().plusSeconds(600)
    )
    def callback(store: MobileOAuthStore, signedIn: User) = {
      val identity = mock[MobileOSMIdentity]
      val ws       = mock[WSClient]
      val upstream = mock[WSRequest]
      val response = mock[WSResponse]
      when(ws.url(anyString())).thenReturn(upstream)
      when(upstream.withFollowRedirects(false)).thenReturn(upstream)
      when(upstream.withRequestTimeout(any[Duration])).thenReturn(upstream)
      when(upstream.withHttpHeaders(any[(String, String)])).thenReturn(upstream)
      when(upstream.post(any[Map[String, String]])(any())).thenReturn(Future.successful(response))
      when(response.status).thenReturn(200)
      when(response.json)
        .thenReturn(Json.obj("access_token" -> "upstream", "scope" -> "read_prefs"))
      when(identity.resolve(anyString())).thenReturn(Future.successful(signedIn))
      when(store.claimLogin(anyString(), anyString(), any[Instant])).thenReturn(Some(interaction))
      when(store.completeLogin(anyString(), anyString(), anyLong(), anyString(), any[Instant]))
        .thenReturn(true)
      new MobileOAuthController(
        stubControllerComponents(),
        service(store),
        settings,
        identity,
        new MobileOsmTokenCipher(settings),
        mock[UserService],
        new Config(),
        ws
      ).callback.apply(
        FakeRequest(GET, s"/oauth/mobile/callback?state=$state&code=example")
          .withCookies(Cookie("mr_mobile_oauth", browser))
      )
    }

    "show an organizer the organizer consent text" in {
      val result = callback(mock[MobileOAuthStore], organizer)
      status(result) mustBe OK
      contentAsString(result) must include("projects you organize")
    }

    "send anyone else back to the admin app before consent" in {
      val store  = mock[MobileOAuthStore]
      val result = callback(store, mapper)
      redirectLocation(result).get must
        (startWith(s"$adminOrigin/callback?error=access_denied") and include("organizer+required"))
      verify(store, never()).completeLogin(
        anyString(),
        anyString(),
        anyLong(),
        anyString(),
        any[Instant]
      )
    }
  }

  "The bearer gate for organizer grants" should {
    def filter(grant: MobileGrant) = {
      val oauth = mock[MobileOAuthService]
      val users = mock[UserService]
      val audit = mock[MobileAdminRepository]
      when(oauth.authenticate(token)).thenReturn(Future.successful(Some(grant)))
      when(users.retrieve(grant.userId)).thenReturn(Some(user(grant.userId)))
      (new MobileBearerFilter(settings, oauth, users, admins, audit), audit)
    }
    def next(request: RequestHeader) =
      Future.successful(
        Results.Ok(request.attrs.get(MobileBearerIdentity.ClientKey).getOrElse("legacy"))
      )
    def bearer(method: String, path: String) =
      FakeRequest(method, path).withHeaders("Authorization" -> s"Bearer $token")
    val organizerRoutes = Seq(
      GET  -> "/api/v2/mobile-admin/campaigns",
      GET  -> "/api/v2/mobile-admin/projects",
      GET  -> "/api/v2/mobile-admin/write-policy",
      PUT  -> "/api/v2/mobile-admin/projects/3/enabled",
      GET  -> "/api/v2/mobile-admin/challenges/70/live",
      PUT  -> "/api/v2/mobile-admin/challenges/70/live",
      POST -> "/api/v2/mobile-admin/challenges/70/live/rotate",
      GET  -> "/api/v2/mobile-admin/challenges/70/results?format=csv",
      POST -> "/api/v2/mobile-admin/challenges/70/queue",
      GET  -> "/api/v2/mobile-admin/challenges/70/queue/export",
      GET  -> "/oauth/mobile/me",
      GET  -> "/api/v2/mobile-admin/wizard/question-sets",
      GET  -> "/api/v2/mobile-admin/wizard/counts?lat=40.75&lon=-111.9&radius=800",
      GET  -> "/api/v2/mobile-admin/wizard/coverage",
      GET  -> "/api/v2/mobile-admin/wizard/question-sets/entrance-doors/features?lat=40.75&lon=-111.9&radius=800",
      POST -> "/api/v2/mobile-admin/wizard/campaigns",
      POST -> "/api/v2/mobile-admin/wizard/campaigns/70/publish",
      GET  -> "/api/v2/challenge/70/tasks?limit=5",
      GET  -> "/api/v2/task/9"
    )
    val staffOnly = Seq(
      GET    -> "/api/v2/mobile-admin/clients",
      POST   -> "/api/v2/mobile-admin/clients",
      PATCH  -> "/api/v2/mobile-admin/clients/app",
      GET    -> "/api/v2/mobile-admin/scope-sets",
      GET    -> "/api/v2/mobile-admin/audit",
      PUT    -> "/api/v2/mobile-admin/write-policy",
      POST   -> "/api/v2/mobile-admin/projects",
      GET    -> "/api/v2/mobile-admin/organizers",
      POST   -> "/api/v2/mobile-admin/organizers",
      DELETE -> "/api/v2/mobile-admin/organizers/9",
      DELETE -> "/api/v2/mobile-admin/organizers/9/projects/3",
      DELETE -> "/api/v2/mobile-admin/challenges/70/live",
      PUT    -> "/api/v2/mobile-admin/challenges/70/results",
      GET    -> "/api/v2/mobile-admin/organizer-requests",
      POST   -> "/api/v2/mobile-admin/organizer-requests/4/approve",
      POST   -> "/api/v2/mobile-admin/organizer-requests/4/decline",
      GET    -> "/api/v2/mobile-admin/organizer-request",
      POST   -> "/api/v2/mobile-admin/organizer-request"
    )
    // Organizers write only through organize endpoints that check the project (Ada, #47 review).
    val stockWrites = Seq(
      POST -> "/api/v2/challenge",
      PUT  -> "/api/v2/challenge/70",
      PUT  -> "/api/v2/challenge/71",
      PUT  -> "/api/v2/challenge/70/addFileTasks?lineByLine=true&report=true"
    )

    "let an organizer's grant reach the campaign routes and nothing else of the admin API" in {
      val (gate, _) = filter(organizeGrant)
      organizerRoutes.foreach {
        case (method, path) =>
          withClue(s"$method $path")(
            contentAsString(gate.apply(next)(bearer(method, path))) mustBe "admin"
          )
      }
      (staffOnly ++ stockWrites ++ Seq(
        GET -> "/api/v2/tasks/box/-112/40/-111/41",
        GET -> "/api/v2/task/9/start",
        PUT -> "/api/v2/task/9/1"
      )).foreach {
        case (method, path) =>
          withClue(s"$method $path") {
            val result = gate.apply(next)(bearer(method, path))
            status(result) mustBe FORBIDDEN
            contentAsJson(result) mustBe Json.obj("error" -> "insufficient_scope")
          }
      }
    }

    "leave the super-user's admin grant every route it had, and the organizer list" in {
      val (gate, _) = filter(adminGrant)
      (organizerRoutes ++ stockWrites ++ staffOnly.take(11)).foreach {
        case (method, path) =>
          withClue(s"$method $path")(
            contentAsString(gate.apply(next)(bearer(method, path))) mustBe "admin"
          )
      }
    }

    "read an OSM account's age and changeset count, and nothing it can't parse" in {
      OsmAccount.parse(
        Json.parse(
          """{"user": {"id": 1, "account_created": "2019-05-01T10:00:00Z", "changesets": {"count": 42}}}"""
        )
      ) mustBe OsmAccount(Some(Instant.parse("2019-05-01T10:00:00Z")), Some(42))
      OsmAccount.parse(Json.obj("user" -> Json.obj("account_created" -> "yesterday"))) mustBe
        OsmAccount.Unknown
    }

    "let a mobile:organizer-request grant reach only its own request, while it may be held" in {
      admins.mayHold(MobileScopes.OrganizerRequest, requester.id) mustBe true
      admins.mayHold(MobileScopes.OrganizerRequest, mapper.id) mustBe false
      admins.mayHold(MobileScopes.Organize, requester.id) mustBe false
      val asking        = organizeGrant.copy(userId = requester.id, scope = "mobile:organizer-request")
      val (gate, audit) = filter(asking)
      Seq(
        GET  -> "/api/v2/mobile-admin/organizer-request",
        POST -> "/api/v2/mobile-admin/organizer-request"
      ).foreach {
        case (method, path) =>
          withClue(s"$method $path")(
            contentAsString(gate.apply(next)(bearer(method, path))) mustBe "admin"
          )
      }
      (organizerRoutes ++ staffOnly.filterNot(_._2.endsWith("organizer-request")) ++ stockWrites ++
        Seq(
          PUT -> "/api/v2/mobile-admin/organizer-request",
          GET -> "/api/v2/tasks/box/-112/40/-111/41"
        ))
        .foreach {
          case (method, path) =>
            withClue(s"$method $path") {
              val result = gate.apply(next)(bearer(method, path))
              status(result) mustBe FORBIDDEN
              contentAsJson(result) mustBe Json.obj("error" -> "insufficient_scope")
            }
        }
      // Requests closed since, or the person can't ask any more: refused on the next request.
      val (closed, _) = filter(asking.copy(userId = mapper.id))
      val refused     = closed.apply(next)(bearer(GET, "/api/v2/mobile-admin/organizer-request"))
      status(refused) mustBe FORBIDDEN
      contentAsJson(refused) mustBe Json.obj("error" -> "organizer_request_not_allowed")
      verify(audit, never()).record(anyLong(), anyString(), anyString(), any())
    }

    "refuse an organizer's grant once they are off the list" in {
      val (demoted, audit) = filter(organizeGrant.copy(userId = mapper.id))
      val refused          = demoted.apply(next)(bearer(GET, "/api/v2/mobile-admin/campaigns"))
      status(refused) mustBe FORBIDDEN
      contentAsJson(refused) mustBe Json.obj("error" -> "organizer_required")
      verify(audit, never()).record(anyLong(), anyString(), anyString(), any())
    }

    "refuse an organizer's writes while answer saving is off, and let the super-user write" +
      " everything but a wizard publish" in {
      val switched = new MobileOAuthSettings(
        Configuration(
          ConfigFactory
            .parseString("mobileOAuth.writeControlEnabled = true")
            .withFallback(config)
        )
      )
      val policy = mock[MobileWritePolicy]
      when(policy.enabled).thenReturn(false)
      def gate(grant: MobileGrant) = {
        val oauth = mock[MobileOAuthService]
        val users = mock[UserService]
        when(oauth.authenticate(token)).thenReturn(Future.successful(Some(grant)))
        when(users.retrieve(grant.userId)).thenReturn(Some(user(grant.userId)))
        new MobileBearerFilter(switched, oauth, users, admins, mock[MobileAdminRepository], policy)
      }
      val writes = Seq(
        PUT  -> "/api/v2/mobile-admin/projects/3/enabled",
        PUT  -> "/api/v2/mobile-admin/challenges/70/live",
        POST -> "/api/v2/mobile-admin/challenges/70/live/rotate",
        POST -> "/api/v2/mobile-admin/challenges/70/queue"
      )
      writes.foreach {
        case (method, path) =>
          withClue(s"$method $path") {
            val result = gate(organizeGrant).apply(next)(bearer(method, path))
            status(result) mustBe FORBIDDEN
            contentAsJson(result) mustBe Json.obj("error" -> "mobile_writes_disabled")
            contentAsString(gate(adminGrant).apply(next)(bearer(method, path))) mustBe "admin"
          }
      }
      // W-DRAFT: a wizard draft is created while the switch is off; publishing it waits, for
      // everyone.
      Seq(organizeGrant, adminGrant).foreach { grant =>
        withClue(s"create ${grant.scope}") {
          val created =
            gate(grant).apply(next)(bearer(POST, "/api/v2/mobile-admin/wizard/campaigns"))
          status(created) mustBe OK
          contentAsString(created) mustBe "admin"
        }
        withClue(s"publish ${grant.scope}") {
          val result = gate(grant).apply(next)(
            bearer(POST, "/api/v2/mobile-admin/wizard/campaigns/70/publish")
          )
          status(result) mustBe FORBIDDEN
          contentAsJson(result) mustBe Json.obj("error" -> "mobile_writes_disabled")
        }
      }
      contentAsString(
        gate(organizeGrant).apply(next)(bearer(GET, "/api/v2/mobile-admin/campaigns"))
      ) mustBe "admin"
      // ORG-ONB: asking to be an organizer writes only our table, so it isn't held back.
      Seq(organizeGrant.copy(userId = requester.id, scope = "mobile:organizer-request")).foreach {
        grant =>
          withClue(s"request by ${grant.userId}") {
            contentAsString(
              gate(grant).apply(next)(bearer(POST, "/api/v2/mobile-admin/organizer-request"))
            ) mustBe "admin"
          }
      }
    }
  }

  "The admin routes' own gate" should {
    def as(u: User, scopes: Set[String]) =
      FakeRequest()
        .addAttr(MobileBearerIdentity.UserKey, u)
        .addAttr(MobileBearerIdentity.ScopesKey, scopes)
    val controller = new MobileAdminController(
      stubControllerComponents(),
      settings,
      service(),
      mock[MobileAdminRepository]
    )

    "let organizers read the write switch and nothing else there" in {
      val policy = controller.getWritePolicy(as(organizer, Organize))
      status(policy) mustBe OK
      (contentAsJson(policy) \ "managed").as[Boolean] mustBe false
      val clients = controller.listClients(as(organizer, Organize))
      status(clients) mustBe FORBIDDEN
      contentAsJson(clients) mustBe Json.obj("error" -> "organizer_forbidden")
      status(controller.scopeSets(as(superUser, Admin))) mustBe OK
      status(controller.scopeSets(as(organizer, Organize))) mustBe FORBIDDEN
      contentAsJson(controller.listClients(as(mapper, Admin))) mustBe
        Json.obj("error" -> "mobile_admin_only")
      contentAsJson(controller.getWritePolicy(as(mapper, Set(MobileScopes.Read)))) mustBe
        Json.obj("error" -> "mobile_admin_only")
    }
  }

  "The campaign routes" should {
    def as(u: User, scopes: Set[String]) =
      FakeRequest()
        .addAttr(MobileBearerIdentity.UserKey, u)
        .addAttr(MobileBearerIdentity.ScopesKey, scopes)
    val pages   = mock[LivePageRepository]
    val queue   = mock[ChoiceQueueService]
    val results = mock[ChoiceResultsRepository]
    val audit   = mock[MobileAdminRepository]
    val cc      = stubControllerComponents()
    val live    = new MobileLiveController(cc, settings, service(), pages, audit)
    val queues  = new MobileQueueController(cc, settings, service(), queue, audit)
    val exports = new MobileResultsController(cc, settings, service(), results)

    "refuse an organizer the live page, queue and results of another project's challenge" in {
      // Challenge 71 is in project 4, which the organizer doesn't run; 72 is in no known project.
      for (challenge <- Seq(71L, 72L)) {
        Seq(
          live.get(challenge)(as(organizer, Organize)),
          live.rotate(challenge)(as(organizer, Organize)),
          live.update(challenge)(as(organizer, Organize).withBody(Json.obj("enabled" -> true))),
          queues.generate(challenge)(as(organizer, Organize)),
          queues.exportQueue(challenge)(as(organizer, Organize)),
          exports.results(challenge, "csv")(as(organizer, Organize))
        ).foreach { result =>
          withClue(s"challenge $challenge") {
            status(result) mustBe FORBIDDEN
            contentAsJson(result) mustBe Json.obj("error" -> "organizer_forbidden")
          }
        }
      }
      verify(pages, never()).byChallenge(anyLong())
      verify(pages, never()).rotate(anyLong())
      verify(queue, never()).generate(anyLong(), any[User])
      verify(results, never()).results(anyLong())
      verify(audit, never()).record(anyLong(), anyString(), anyString(), any())
    }
  }

  "Adding an organizer" should {
    "take an OSM username, a project and a role" in {
      MobileOrganizerController.parseAdd(
        Json.obj("osmUsername" -> "Dana B", "projectId" -> 3, "role" -> "write")
      ) mustBe Right(("Dana B", 3L, 2))
      MobileOrganizerController.parseAdd(
        Json.obj("osmUsername" -> "dana", "projectId" -> 3, "role" -> "admin")
      ) mustBe Right(("dana", 3L, 1))
      Seq(
        Json.obj(),
        Json.obj("osmUsername" -> " dana", "projectId" -> 3, "role" -> "write"),
        Json.obj("osmUsername" -> "dana", "projectId"  -> 0, "role" -> "write"),
        Json.obj("osmUsername" -> "dana", "projectId"  -> 3, "role" -> "read"),
        Json.obj("osmUsername" -> "dana", "projectId"  -> 3, "role" -> "write", "extra" -> 1),
        Json.arr()
      ).foreach(body => MobileOrganizerController.parseAdd(body).isLeft mustBe true)
    }
  }
}

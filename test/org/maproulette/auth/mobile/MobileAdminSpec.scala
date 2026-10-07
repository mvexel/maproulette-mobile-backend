package org.maproulette.auth.mobile

import akka.actor.ActorSystem
import akka.stream.{Materializer, SystemMaterializer}
import com.typesafe.config.ConfigFactory
import controllers.{MobileAdminController, MobileOAuthController}
import java.time.Instant
import org.joda.time.DateTime
import org.maproulette.Config
import org.maproulette.framework.model.{Location, OSMProfile, User}
import org.maproulette.framework.service.UserService
import org.mockito.ArgumentMatchers.{any, anyBoolean, anyLong, anyString}
import org.mockito.Mockito.{never, verify, when}
import org.scalatest.BeforeAndAfterAll
import org.scalatestplus.mockito.MockitoSugar
import org.scalatestplus.play.PlaySpec
import play.api.Configuration
import play.api.http.DefaultHttpErrorHandler
import play.api.libs.json.{JsValue, Json}
import play.api.libs.ws.{WSClient, WSRequest, WSResponse}
import play.api.mvc.{Cookie, RequestHeader, Results}
import play.api.test.FakeRequest
import play.api.test.Helpers._
import play.filters.cors.{CORSConfig, CORSFilter}
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}
import scalaoauth2.provider.{InvalidClient, InvalidGrant, InvalidScope}

/** Admin scope gate, disabled clients, admin client routes and admin CORS. */
class MobileAdminSpec extends PlaySpec with MockitoSugar with BeforeAndAfterAll {
  private implicit val system: ActorSystem = ActorSystem(
    "mobile-admin-test",
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
  private val settings = new MobileOAuthSettings(
    Configuration(ConfigFactory.parseString(s"""
    mobileOAuth {
      enabled = true
      callbackUri = "https://mr.example/oauth/mobile/callback"
      adminOrigin = "$adminOrigin"
      clients = [
        { id = "app", name = "App", redirectUris = ["org.example.app:/cb"], scopes = ["tasks:read", "tasks:write"] },
        { id = "admin", name = "Admin", redirectUris = ["$adminOrigin/callback"], scopes = ["mobile:admin"] },
        { id = "off", name = "Off", redirectUris = ["org.example.off:/cb"], enabled = false }
      ]
    }"""))
  )
  private val registry = new StaticMobileClientRegistry(settings)
  private val token    = "A" * 43
  private def user(id: Long) = User(
    id,
    DateTime.now(),
    DateTime.now(),
    OSMProfile(456, "Mapper", "", "", Location(1, 2), DateTime.now(), "legacy-token"),
    List.empty
  )
  private val superUser = user(7)
  private val mapper    = user(8)
  private val admins = new MobileAdminCheck {
    def isAdmin(value: User): Boolean  = value.id == superUser.id
    def isAdmin(userId: Long): Boolean = userId == superUser.id
  }
  private val adminGrant =
    MobileGrant("fam-admin", 7, "admin", "mobile:admin", s"$adminOrigin/callback", "a" * 43)
  private val appGrant =
    MobileGrant("fam-app", 7, "app", "tasks:read tasks:write", "org.example.app:/cb", "a" * 43)

  private def service(store: MobileOAuthStore = mock[MobileOAuthStore]) =
    new MobileOAuthService(store, settings, registry, admins, system)

  "Mobile scope parsing" should {
    "accept mobile:admin only on its own" in {
      MobileScopes.parse("mobile:admin") mustBe Some(Set("mobile:admin"))
      Seq(
        "tasks:read mobile:admin",
        "mobile:admin tasks:read",
        "tasks:read tasks:write mobile:admin"
      ).foreach(value => MobileScopes.parse(value) mustBe None)
      MobileScopes.isAdmin("mobile:admin") mustBe true
      MobileScopes.isAdmin("tasks:read") mustBe false
      MobileScopes.osmScope("mobile:admin") mustBe "read_prefs"
    }

    "validate the admin origin" in {
      settings.adminOrigin mustBe Some(adminOrigin)
      Seq(
        "http://admin.example",
        "https://admin.example/",
        "https://Admin.example",
        "admin.example"
      ).foreach { origin =>
        an[Exception] must be thrownBy new MobileOAuthSettings(
          Configuration.from(
            Map(
              "mobileOAuth.enabled"     -> true,
              "mobileOAuth.callbackUri" -> "https://mr.example/oauth/mobile/callback",
              "mobileOAuth.adminOrigin" -> origin
            )
          )
        )
      }
    }
  }

  "Disabled clients and the admin scope in the OAuth flow" should {
    def query(client: String, scope: String, redirect: String) = Map(
      "response_type"         -> Seq("code"),
      "client_id"             -> Seq(client),
      "redirect_uri"          -> Seq(redirect),
      "scope"                 -> Seq(scope),
      "state"                 -> Seq("s"),
      "code_challenge"        -> Seq("a" * 43),
      "code_challenge_method" -> Seq("S256")
    )

    "refuse new authorizations, refreshes and tokens of a disabled client" in {
      val store = mock[MobileOAuthStore]
      service(store)
        .authorization(query("off", "tasks:read", "org.example.off:/cb"))
        .left
        .map(_.getClass) mustBe
        Left(classOf[InvalidClient])
      val refresh = Map(
        "grant_type"    -> Seq("refresh_token"),
        "client_id"     -> Seq("off"),
        "refresh_token" -> Seq("r")
      )
      Await.result(service(store).exchange(refresh, false), 5.seconds).left.map(_.getClass) mustBe
        Left(classOf[InvalidClient])
      when(store.authenticate(anyString(), any[Instant]))
        .thenReturn(Some(appGrant.copy(clientId = "off", scope = "tasks:read")))
      Await.result(service(store).authenticate(token), 5.seconds) mustBe None
      verify(store, never()).rotate(anyString(), anyString(), any[TokenHashes], any[Instant])
    }

    "grant mobile:admin only to clients configured for it" in {
      service()
        .authorization(query("admin", "mobile:admin", s"$adminOrigin/callback"))
        .isRight mustBe true
      service()
        .authorization(query("app", "mobile:admin", "org.example.app:/cb"))
        .left
        .map(_.getClass) mustBe
        Left(classOf[InvalidScope])
      service()
        .authorization(query("admin", "tasks:read", s"$adminOrigin/callback"))
        .left
        .map(_.getClass) mustBe
        Left(classOf[InvalidScope])
    }

    "issue and refresh admin tokens only while the user is a super-user" in {
      val store   = mock[MobileOAuthStore]
      val demoted = adminGrant.copy(userId = mapper.id)
      when(store.findRefresh(anyString(), any[Instant])).thenReturn(Some(demoted))
      val refresh = Map(
        "grant_type"    -> Seq("refresh_token"),
        "client_id"     -> Seq("admin"),
        "refresh_token" -> Seq("r")
      )
      Await.result(service(store).exchange(refresh, false), 5.seconds).left.map(_.getClass) mustBe
        Left(classOf[InvalidGrant])
      verify(store, never()).rotate(anyString(), anyString(), any[TokenHashes], any[Instant])
      when(store.findRefresh(anyString(), any[Instant])).thenReturn(Some(adminGrant))
      when(store.rotate(anyString(), anyString(), any[TokenHashes], any[Instant]))
        .thenReturn(Some(adminGrant))
      Await.result(service(store).exchange(refresh, false), 5.seconds).map(_.scope) mustBe
        Right(Some("mobile:admin"))
    }
  }

  "The admin consent flow" should {
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
    val csrf    = MobileSecrets.generate()
    val interaction = MobileInteraction(
      MobileSecrets.hash(state),
      MobileSecrets.hash(browser),
      "admin",
      s"$adminOrigin/callback",
      "mobile:admin",
      "web-state",
      "a" * 43,
      Instant.now().plusSeconds(600)
    )
    def controller(store: MobileOAuthStore, signedIn: User) = {
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
      new MobileOAuthController(
        stubControllerComponents(),
        service(store),
        settings,
        identity,
        new MobileOsmTokenCipher(settings),
        mock[UserService],
        new Config(),
        ws
      )
    }
    def callback =
      FakeRequest(GET, s"/oauth/mobile/callback?state=$state&code=example")
        .withCookies(Cookie("mr_mobile_oauth", browser))

    "send a non-super-user back to the app with access_denied, before consent" in {
      val store = mock[MobileOAuthStore]
      when(store.claimLogin(anyString(), anyString(), any[Instant])).thenReturn(Some(interaction))
      val result = controller(store, mapper).callback.apply(callback)
      status(result) mustBe SEE_OTHER
      val location = redirectLocation(result).get
      location must startWith(s"$adminOrigin/callback?error=access_denied")
      location must include("state=web-state")
      verify(store, never()).completeLogin(
        anyString(),
        anyString(),
        anyLong(),
        anyString(),
        any[Instant]
      )
    }

    "show a super-user the admin consent text" in {
      val store = mock[MobileOAuthStore]
      when(store.claimLogin(anyString(), anyString(), any[Instant])).thenReturn(Some(interaction))
      when(store.completeLogin(anyString(), anyString(), anyLong(), anyString(), any[Instant]))
        .thenReturn(true)
      val result = controller(store, superUser).callback.apply(callback)
      status(result) mustBe OK
      contentAsString(result) must include("super-user")
      header("Content-Security-Policy", result).get must include(adminOrigin)
    }

    "refuse approval when the user is no longer a super-user" in {
      val store = mock[MobileOAuthStore]
      when(store.getInteraction(anyString(), anyString(), any[Instant]))
        .thenReturn(
          Some(
            interaction.copy(userId = Some(mapper.id), csrfHash = Some(MobileSecrets.hash(csrf)))
          )
        )
      val request = FakeRequest(POST, "/oauth/mobile/consent")
        .withCookies(Cookie("mr_mobile_oauth", browser))
        .withFormUrlEncodedBody("interaction" -> state, "csrf" -> csrf, "decision" -> "allow")
      status(call(controller(store, mapper).consent, request)) mustBe BAD_REQUEST
      verify(store, never()).approveInteraction(
        anyString(),
        anyString(),
        anyString(),
        anyString(),
        anyString(),
        any[Instant],
        any[Instant]
      )
    }
  }

  "The bearer gate for admin grants" should {
    def filter(grant: MobileGrant, signedIn: User = superUser) = {
      val oauth = mock[MobileOAuthService]
      val users = mock[UserService]
      val audit = mock[MobileAdminRepository]
      when(oauth.authenticate(token)).thenReturn(Future.successful(Some(grant)))
      when(users.retrieve(grant.userId)).thenReturn(Some(signedIn))
      (new MobileBearerFilter(settings, oauth, users, admins, audit), audit)
    }
    def next(request: RequestHeader) =
      Future.successful(
        Results.Ok(request.attrs.get(MobileBearerIdentity.ClientKey).getOrElse("legacy"))
      )
    def bearer(method: String, path: String) =
      FakeRequest(method, path).withHeaders("Authorization" -> s"Bearer $token")
    val adminRoutes = Seq(
      GET   -> "/api/v2/mobile-admin/clients",
      POST  -> "/api/v2/mobile-admin/clients",
      PATCH -> "/api/v2/mobile-admin/clients/app",
      GET   -> "/api/v2/mobile-admin/audit?limit=5",
      GET   -> "/oauth/mobile/me",
      POST  -> "/api/v2/challenge",
      PUT   -> "/api/v2/challenge/7",
      PUT   -> "/api/v2/challenge/7/addFileTasks?lineByLine=true&report=true",
      PUT   -> "/api/v2/challenge/7/addFileTasks?report=true&lineByLine=true",
      GET   -> "/api/v2/challenge/7",
      GET   -> "/api/v2/challenge/7/tasks?limit=5",
      GET   -> "/api/v2/task/9"
    )

    "let a super-user's admin grant reach exactly the admin allowlist" in {
      val (gate, _) = filter(adminGrant)
      adminRoutes.foreach {
        case (method, path) =>
          contentAsString(gate.apply(next)(bearer(method, path))) mustBe "admin"
      }
    }

    "keep admin grants off every other route, including app reads and writes" in {
      val (gate, _) = filter(adminGrant)
      Seq(
        GET    -> "/api/v2/tasks/box/-112/40/-111/41",
        PUT    -> "/api/v2/markers/box/-112/40/-111/41",
        GET    -> "/api/v2/challenges/extendedFind",
        GET    -> "/api/v2/challenge/7/tags",
        GET    -> "/api/v2/task/9/choice/check",
        GET    -> "/api/v2/task/9/start",
        PUT    -> "/api/v2/task/9/1",
        POST   -> "/api/v2/task/9/choice",
        GET    -> "/api/v2/user/whoami",
        DELETE -> "/api/v2/challenge/7",
        DELETE -> "/api/v2/challenge/7/tasks",
        POST   -> "/api/v2/challenge/saveOrUpdate",
        PUT    -> "/api/v2/challenge/7/addFileTasks",
        PUT    -> "/api/v2/challenge/7/addFileTasks?lineByLine=true",
        PUT    -> "/api/v2/challenge/7/addFileTasks?lineByLine=true&report=true&removeUnmatched=true",
        PUT    -> "/api/v2/challenge/7/addFileTasks?lineByLine=false&report=true",
        GET    -> "/api/v2/mobile-admin",
        GET    -> "/api/v2/mobile-admin/clients/",
        GET    -> "/api/v2/mobile-admin/../user/whoami",
        PATCH  -> "/api/v2/challenge/7",
        GET    -> "/auth/generateAPIKey"
      ).foreach {
        case (method, path) =>
          withClue(s"$method $path") {
            // 400 when the app route's shape check fails first (choice without its JSON body).
            status(gate.apply(next)(bearer(method, path))) must (be(FORBIDDEN) or be(BAD_REQUEST))
          }
      }
    }

    "refuse an admin grant once its user is no longer a super-user" in {
      val (gate, _) = filter(adminGrant, mapper)
      val result    = gate.apply(next)(bearer(GET, "/api/v2/mobile-admin/clients"))
      status(result) mustBe FORBIDDEN
      contentAsJson(result) mustBe Json.obj("error" -> "admin_required")
    }

    "keep app grants off the admin API and the admin-only stock routes" in {
      val (gate, _) = filter(appGrant)
      Seq(
        GET   -> "/api/v2/mobile-admin/clients",
        POST  -> "/api/v2/mobile-admin/clients",
        PATCH -> "/api/v2/mobile-admin/clients/app",
        POST  -> "/api/v2/challenge",
        PUT   -> "/api/v2/challenge/7",
        PUT   -> "/api/v2/challenge/7/addFileTasks?lineByLine=true&report=true"
      ).foreach {
        case (method, path) =>
          status(gate.apply(next)(bearer(method, path))) mustBe FORBIDDEN
      }
      // Shared reads keep working for app grants.
      contentAsString(gate.apply(next)(bearer(GET, "/api/v2/challenge/7"))) mustBe "app"
    }

    "record admin writes through stock routes in the audit log" in {
      val (gate, audit) = filter(adminGrant)
      status(gate.apply(next)(bearer(PUT, "/api/v2/challenge/7"))) mustBe OK
      verify(audit).record(7L, "stock.PUT", "/api/v2/challenge/7", Some(Json.obj("status" -> 200)))
      status(gate.apply(next)(bearer(GET, "/api/v2/challenge/7"))) mustBe OK
      status(gate.apply(next)(bearer(POST, "/api/v2/mobile-admin/clients"))) mustBe OK
      // A failed action is audited too, and still fails.
      val failing = gate.apply(_ => Future.failed(new IllegalStateException("boom")))(
        bearer(PUT, "/api/v2/challenge/8/addFileTasks?lineByLine=true&report=true")
      )
      an[IllegalStateException] must be thrownBy Await.result(failing, 5.seconds)
      verify(audit).record(
        7L,
        "stock.PUT",
        "/api/v2/challenge/8/addFileTasks",
        Some(Json.obj("status" -> 500, "error" -> "IllegalStateException"))
      )
      verify(audit, never()).record(
        anyLong(),
        org.mockito.ArgumentMatchers.eq("stock.GET"),
        anyString(),
        any()
      )
      verify(audit, never()).record(
        anyLong(),
        org.mockito.ArgumentMatchers.eq("stock.POST"),
        anyString(),
        any()
      )
    }
  }

  "The admin client routes" should {
    def fixture() = {
      val repository = mock[MobileAdminRepository]
      when(repository.clientJson(any[MobileClient])).thenAnswer { invocation =>
        Json.obj("id" -> invocation.getArgument[MobileClient](0).id)
      }
      (
        new MobileAdminController(stubControllerComponents(), settings, service(), repository),
        repository
      )
    }
    def as(signedIn: User, body: JsValue = Json.obj(), scopes: Set[String] = Set("mobile:admin")) =
      FakeRequest()
        .withBody(body)
        .addAttr(MobileBearerIdentity.UserKey, signedIn)
        .addAttr(MobileBearerIdentity.ScopesKey, scopes)
        .addAttr(MobileBearerIdentity.ClientKey, "admin")
    val valid = Json.obj(
      "id"           -> "new-app",
      "name"         -> "New app",
      "redirectUris" -> Seq("org.example.new:/cb", "https://new.example/cb"),
      "scopes"       -> Seq("tasks:read", "tasks:write")
    )

    "refuse anything but a current super-user's admin grant" in {
      val (controller, repository) = fixture()
      Seq(
        as(mapper),
        as(superUser, scopes = Set("tasks:read")),
        FakeRequest().withBody(Json.obj(): JsValue)
      ).foreach { request =>
        val result = controller.listClients.apply(request.map(_ => play.api.mvc.AnyContentAsEmpty))
        status(result) mustBe FORBIDDEN
        contentAsJson(result) mustBe Json.obj("error" -> "mobile_admin_only")
      }
      verify(repository, never()).listClients
    }

    "create a valid client and refuse a duplicate id" in {
      val (controller, repository) = fixture()
      when(repository.createClient(any[MobileClient], anyLong())).thenReturn(true)
      status(controller.createClient.apply(as(superUser, valid))) mustBe CREATED
      verify(repository).createClient(
        MobileClient(
          "new-app",
          "New app",
          Set("org.example.new:/cb", "https://new.example/cb"),
          Set("tasks:read", "tasks:write")
        ),
        7L
      )
      when(repository.createClient(any[MobileClient], anyLong())).thenReturn(false)
      status(controller.createClient.apply(as(superUser, valid))) mustBe CONFLICT
    }

    "validate ids, names, redirects and scopes" in {
      val (controller, repository) = fixture()
      Seq(
        valid + ("id"           -> Json.toJson("bad id")),
        valid + ("name"         -> Json.toJson(" ")),
        valid + ("redirectUris" -> Json.toJson(Seq.empty[String])),
        valid + ("redirectUris" -> Json.toJson(Seq("https://x.example/cb#frag"))),
        valid + ("redirectUris" -> Json.toJson(Seq("http://x.example/cb"))),
        valid + ("redirectUris" -> Json.toJson(Seq("myapp:/cb"))),
        valid + ("redirectUris" -> Json.toJson(Seq("https://x.example/cb?a=1"))),
        valid + ("scopes"       -> Json.toJson(Seq("tasks:read", "mobile:admin"))),
        valid + ("scopes"       -> Json.toJson(Seq("tasks:write"))),
        valid + ("extra"        -> Json.toJson(1)),
        valid - "redirectUris",
        Json.arr()
      ).foreach { body =>
        withClue(body) {
          val result = controller.createClient.apply(as(superUser, body))
          status(result) mustBe BAD_REQUEST
          (contentAsJson(result) \ "error").as[String] mustBe "invalid_request"
        }
      }
      verify(repository, never()).createClient(any[MobileClient], anyLong())
    }

    "patch a client, revoking grants only together with disabling it" in {
      val (controller, repository) = fixture()
      val client                   = MobileClient("app", "App", Set("org.example.app:/cb"), enabled = false)
      when(repository.updateClient(anyString(), any[MobileClientPatch], anyLong(), anyBoolean()))
        .thenReturn(Some((client, 3)))
      val disable = controller
        .updateClient("app", revokeGrants = true)
        .apply(as(superUser, Json.obj("enabled" -> false)))
      status(disable) mustBe OK
      (contentAsJson(disable) \ "revokedGrantFamilies").as[Int] mustBe 3
      verify(repository).updateClient("app", MobileClientPatch(enabled = Some(false)), 7L, true)
      status(
        controller
          .updateClient("app", revokeGrants = true)
          .apply(as(superUser, Json.obj("name" -> "X")))
      ) mustBe BAD_REQUEST
      status(controller.updateClient("app", revokeGrants = false).apply(as(superUser, Json.obj()))) mustBe
        BAD_REQUEST
      status(
        controller
          .updateClient("app", revokeGrants = false)
          .apply(as(superUser, Json.obj("id" -> "y")))
      ) mustBe
        BAD_REQUEST
      when(repository.updateClient(anyString(), any[MobileClientPatch], anyLong(), anyBoolean()))
        .thenReturn(None)
      status(
        controller
          .updateClient("nope", revokeGrants = false)
          .apply(as(superUser, Json.obj("enabled" -> true)))
      ) mustBe NOT_FOUND
    }

    "not let the admin app lock itself out" in {
      val (controller, repository) = fixture()
      Seq(Json.obj("enabled" -> false), Json.obj("scopes" -> Seq("tasks:read"))).foreach { body =>
        status(controller.updateClient("admin", revokeGrants = false).apply(as(superUser, body))) mustBe CONFLICT
      }
      verify(repository, never()).updateClient(
        anyString(),
        any[MobileClientPatch],
        anyLong(),
        anyBoolean()
      )
    }

    "page the audit log within bounds" in {
      val (controller, repository) = fixture()
      when(repository.audit(20, 40)).thenReturn((Seq.empty, 41L))
      val request = as(superUser).map(_ => play.api.mvc.AnyContentAsEmpty)
      val result  = controller.audit(20, 2).apply(request)
      status(result) mustBe OK
      contentAsJson(result) mustBe Json.obj(
        "items" -> Json.arr(),
        "page"  -> 2,
        "limit" -> 20,
        "total" -> 41
      )
      Seq((0, 0), (201, 0), (10, -1)).foreach {
        case (limit, page) =>
          status(controller.audit(limit, page).apply(request)) mustBe BAD_REQUEST
      }
    }
  }

  "The field write policy route" should {
    "require a super-user and an OSM token key before enabling" in {
      val fieldSettings = new MobileOAuthSettings(
        Configuration(
          ConfigFactory.parseString("""
          mobileOAuth {
            enabled = true
            callbackUri = "https://mr-stage.osm.lol/oauth/mobile/callback"
            writeControlEnabled = true
          }
        """)
        )
      )
      val policy = mock[MobileWritePolicy]
      when(policy.enabled).thenReturn(false)
      val controller = new MobileAdminController(
        stubControllerComponents(),
        fieldSettings,
        service(),
        mock[MobileAdminRepository],
        policy
      )
      val signed = FakeRequest()
        .withBody(Json.obj("enabled" -> true): JsValue)
        .addAttr(MobileBearerIdentity.UserKey, superUser)
        .addAttr(MobileBearerIdentity.ScopesKey, Set("mobile:admin"))
      status(controller.setWritePolicy.apply(signed)) mustBe CONFLICT
      (contentAsJson(controller.setWritePolicy.apply(signed)) \ "error").as[String] mustBe
        "write_prerequisites_missing"
      verify(policy, never()).set(anyBoolean(), anyLong())
    }
  }

  "Admin CORS" should {
    // The upstream settings from conf/application.conf.
    val playCors = new CORSFilter(
      CORSConfig.fromConfiguration(
        Configuration(
          ConfigFactory
            .parseString(
              "play.filters.cors { pathPrefixes = [\"/\"], allowedOrigins = null, allowedHttpMethods = null, allowedHttpHeaders = null }"
            )
            .withFallback(ConfigFactory.load())
        )
      ),
      DefaultHttpErrorHandler,
      Seq("/")
    )
    val wrapper  = new MobileCorsFilter(playCors, settings)
    val disabled = new MobileCorsFilter(playCors, new MobileOAuthSettings(Configuration.empty))
    val action   = stubControllerComponents().actionBuilder(Results.Ok("body"))
    def run(filter: MobileCorsFilter, request: FakeRequest[_]) =
      call(filter(action), request.map(_ => play.api.mvc.AnyContentAsEmpty))
    def preflight(path: String, origin: String, method: String, headers: String = "authorization") =
      FakeRequest(OPTIONS, path).withHeaders(
        "Origin"                         -> origin,
        "Access-Control-Request-Method"  -> method,
        "Access-Control-Request-Headers" -> headers
      )

    "answer the admin origin, without credentials, on the admin API, token, revoke and admin stock routes" in {
      Seq(
        "/api/v2/mobile-admin/clients"     -> "PATCH",
        "/oauth/mobile/token"              -> "POST",
        "/oauth/mobile/revoke"             -> "POST",
        "/api/v2/challenge"                -> "POST",
        "/api/v2/challenge/7/addFileTasks" -> "PUT",
        "/api/v2/task/9"                   -> "GET"
      ).foreach {
        case (path, method) =>
          withClue(path) {
            val result =
              run(wrapper, preflight(path, adminOrigin, method, "Authorization, Content-Type"))
            status(result) mustBe NO_CONTENT
            header("Access-Control-Allow-Origin", result) mustBe Some(adminOrigin)
            header("Access-Control-Allow-Methods", result) mustBe Some(method)
            header("Access-Control-Allow-Headers", result) mustBe Some(
              "authorization, content-type"
            )
            header("Access-Control-Allow-Credentials", result) mustBe None
            val actual =
              run(wrapper, FakeRequest(method, path).withHeaders("Origin" -> adminOrigin))
            header("Access-Control-Allow-Origin", actual) mustBe Some(adminOrigin)
            header("Vary", actual).get must include("Origin")
            header("Access-Control-Allow-Credentials", actual) mustBe None
          }
      }
    }

    "refuse the admin origin's preflight for other methods and headers on admin paths" in {
      status(run(wrapper, preflight("/oauth/mobile/token", adminOrigin, "GET"))) mustBe FORBIDDEN
      status(
        run(wrapper, preflight("/api/v2/mobile-admin/clients", adminOrigin, "GET", "x-api-key"))
      ) mustBe
        FORBIDDEN
      status(run(wrapper, preflight("/api/v2/mobile-admin/clients", adminOrigin, "OPTIONS"))) mustBe FORBIDDEN
    }

    "give other origins nothing on the admin API, token and revoke" in {
      Seq("/api/v2/mobile-admin/clients", "/oauth/mobile/token", "/oauth/mobile/revoke").foreach {
        path =>
          status(run(wrapper, preflight(path, "https://evil.example", "POST"))) mustBe FORBIDDEN
          val actual =
            run(wrapper, FakeRequest(POST, path).withHeaders("Origin" -> "https://evil.example"))
          status(actual) mustBe OK
          header("Access-Control-Allow-Origin", actual) mustBe None
      }
    }

    "leave every other origin and path to Play's filter, as upstream" in {
      Seq("/api/v2/challenge/7", "/api/v2/task/9", "/api/v2/tasks/box/1/2/3/4", "/oauth/mobile/me")
        .foreach { path =>
          val result =
            run(wrapper, FakeRequest(GET, path).withHeaders("Origin" -> "https://other.example"))
          header("Access-Control-Allow-Origin", result) mustBe Some("https://other.example")
          header("Access-Control-Allow-Credentials", result) mustBe Some("true")
        }
      // The admin origin on a non-admin route also gets Play's (upstream) answer.
      header(
        "Access-Control-Allow-Credentials",
        run(
          wrapper,
          FakeRequest(GET, "/api/v2/tasks/box/1/2/3/4").withHeaders("Origin" -> adminOrigin)
        )
      ) mustBe Some("true")
    }

    "change nothing while mobile OAuth is disabled" in {
      val result =
        run(
          disabled,
          FakeRequest(POST, "/oauth/mobile/token").withHeaders("Origin" -> "https://other.example")
        )
      header("Access-Control-Allow-Origin", result) mustBe Some("https://other.example")
    }
  }
}

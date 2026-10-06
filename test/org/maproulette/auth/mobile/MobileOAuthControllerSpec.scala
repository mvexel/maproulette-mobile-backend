package org.maproulette.auth.mobile

import akka.actor.ActorSystem
import akka.stream.{Materializer, SystemMaterializer}
import com.typesafe.config.ConfigFactory
import controllers.MobileOAuthController
import java.time.Instant
import org.maproulette.Config
import org.maproulette.framework.model.User
import org.maproulette.framework.service.UserService
import org.mockito.ArgumentMatchers.{any, anyLong, anyString}
import org.mockito.Mockito.{never, verify, when}
import org.scalatest.BeforeAndAfterAll
import org.scalatestplus.mockito.MockitoSugar
import org.scalatestplus.play.PlaySpec
import play.api.Configuration
import play.api.libs.json.Json
import play.api.libs.ws.{WSClient, WSRequest, WSResponse}
import play.api.mvc.Cookie
import play.api.test.FakeRequest
import play.api.test.Helpers._
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration._

class MobileOAuthControllerSpec extends PlaySpec with MockitoSugar with BeforeAndAfterAll {
  private val system = ActorSystem(
    "mobile-oauth-controller-test",
    ConfigFactory.parseString("""
    mobile-oauth-dispatcher {
      type=Dispatcher
      executor="thread-pool-executor"
      thread-pool-executor.fixed-pool-size=2
    }
  """)
  )
  private implicit val ec: ExecutionContext       = system.dispatcher
  private implicit val materializer: Materializer = SystemMaterializer(system).materializer
  private val mobileConfig = Configuration(
    ConfigFactory.parseString(
      """
    mobileOAuth {
      enabled=true
      callbackUri="https://mr.example/oauth/mobile/callback"
      clients=[{
        id="app",name="Example <App>",redirectUris=["org.example.app:/callback"],
        scopes=["tasks:read","tasks:write"]
      }]
    }
  """
    )
  )
  private implicit val configuration: Configuration = Configuration.from(
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
  private val state   = MobileSecrets.generate()
  private val browser = MobileSecrets.generate()
  private val csrf    = MobileSecrets.generate()
  private val interaction = MobileInteraction(
    MobileSecrets.hash(state),
    MobileSecrets.hash(browser),
    "app",
    "org.example.app:/callback",
    "tasks:read",
    "native-state",
    "a" * 43,
    Instant.now().plusSeconds(600),
    Some(42L),
    Some(MobileSecrets.hash(csrf))
  )
  private val grant = MobileGrant(
    "family",
    42L,
    "app",
    "tasks:read",
    interaction.redirectUri,
    interaction.codeChallenge
  )
  private val user = User.superUser.copy(
    id = 42L,
    guest = false,
    apiKey = Some("private-global-key"),
    osmProfile = User.superUser.osmProfile
      .copy(id = 99L, displayName = "Example User", requestToken = "private-osm-token")
  )

  private val tokenKey = java.util.Base64.getEncoder.encodeToString(Array.fill[Byte](32)(7))
  private val tagFixConfig = Configuration(
    ConfigFactory.parseString(
      s"""
    mobileOAuth {
      enabled=true
      callbackUri="https://mr.example/oauth/mobile/callback"
      osmTokenKey="$tokenKey"
      clients=[{
        id="app",name="Example <App>",redirectUris=["org.example.app:/callback"],
        scopes=["tasks:read","tasks:write","osm:tagfix"]
      }]
    }
  """
    )
  )

  private class Fixture(enabled: Boolean = true, config: Option[Configuration] = None) {
    val store = mock[MobileOAuthStore]
    val settings = new MobileOAuthSettings(
      config.getOrElse(if (enabled) mobileConfig else Configuration.empty)
    )
    val cipher   = new MobileOsmTokenCipher(settings)
    val service  = new MobileOAuthService(store, settings, system)
    val identity = mock[MobileOSMIdentity]
    val users    = mock[UserService]
    val ws       = mock[WSClient]
    val upstream = mock[WSRequest]
    val response = mock[WSResponse]
    when(ws.url(anyString())).thenReturn(upstream)
    when(upstream.withFollowRedirects(false)).thenReturn(upstream)
    when(upstream.withRequestTimeout(any[Duration])).thenReturn(upstream)
    when(upstream.withHttpHeaders(any[(String, String)])).thenReturn(upstream)
    when(upstream.post(any[Map[String, String]])(any())).thenReturn(Future.successful(response))
    when(response.status).thenReturn(200)
    when(response.json).thenReturn(
      Json.obj("access_token" -> "upstream-only-secret", "scope" -> "read_prefs write_api")
    )
    when(identity.resolve(anyString())).thenReturn(Future.successful(user))
    val controller = new MobileOAuthController(
      stubControllerComponents(),
      service,
      settings,
      identity,
      cipher,
      users,
      new Config(),
      ws
    )
  }
  override protected def afterAll(): Unit = {
    Await.result(system.terminate(), 5.seconds)
    super.afterAll()
  }

  "Mobile authorization HTTP flow" should {
    "be inert by default" in {
      val f      = new Fixture(false)
      val result = f.controller.authorize.apply(FakeRequest(GET, "/oauth/mobile/authorize"))
      status(result) mustBe NOT_FOUND
      verify(f.store, never()).createInteraction(any[MobileInteraction])
    }

    "bind upstream callback to persisted state and browser before contacting OSM" in {
      val f = new Fixture()
      when(f.store.claimLogin(anyString(), anyString(), any[Instant])).thenReturn(None)
      val result = f.controller.callback.apply(
        FakeRequest(GET, s"/oauth/mobile/callback?state=$state&code=example")
          .withCookies(Cookie("mr_mobile_oauth", browser))
      )
      status(result) mustBe BAD_REQUEST
      verify(f.ws, never()).url(anyString())
      val noCookie = f.controller.callback
        .apply(FakeRequest(GET, s"/oauth/mobile/callback?state=$state&code=example"))
      status(noCookie) mustBe BAD_REQUEST
    }

    "exchange upstream identity server-side and show escaped consent without leaking credentials" in {
      val f = new Fixture()
      when(f.store.claimLogin(anyString(), anyString(), any[Instant])).thenReturn(Some(interaction))
      when(f.store.completeLogin(anyString(), anyString(), anyLong(), anyString(), any[Instant]))
        .thenReturn(true)
      val result = f.controller.callback.apply(
        FakeRequest(GET, s"/oauth/mobile/callback?state=$state&code=example")
          .withCookies(Cookie("mr_mobile_oauth", browser))
      )
      status(result) mustBe OK
      val body = contentAsString(result)
      body must include("Example &lt;App&gt;")
      body must include("name=\"csrf\"")
      body must not include "upstream-only-secret"
      body must not include "private-global-key"
      body must not include "private-osm-token"
      header("Cache-Control", result) mustBe Some("no-store")
      header("Content-Security-Policy", result).get must include("org.example.app:")
      verify(f.upstream).withFollowRedirects(false)
      verify(f.identity).resolve("upstream-only-secret")
    }

    "name the write permission on consent only for a tasks:write request" in {
      Seq(
        "tasks:read"             -> false,
        "tasks:read tasks:write" -> true
      ).foreach {
        case (scope, write) =>
          val f = new Fixture()
          when(f.store.claimLogin(anyString(), anyString(), any[Instant]))
            .thenReturn(Some(interaction.copy(scope = scope)))
          when(
            f.store.completeLogin(anyString(), anyString(), anyLong(), anyString(), any[Instant])
          ).thenReturn(true)
          val body = contentAsString(
            f.controller.callback.apply(
              FakeRequest(GET, s"/oauth/mobile/callback?state=$state&code=example")
                .withCookies(Cookie("mr_mobile_oauth", browser))
            )
          )
          body.contains("mark them fixed, not an issue, already fixed or too hard") mustBe write
          body.contains("It cannot edit tasks") mustBe !write
      }
    }

    "keep an osm:tagfix login's OSM token only sealed, and name OSM edits on consent" in {
      val f      = new Fixture(config = Some(tagFixConfig))
      val tagFix = interaction.copy(scope = "tasks:read tasks:write osm:tagfix")
      when(f.store.claimLogin(anyString(), anyString(), any[Instant])).thenReturn(Some(tagFix))
      when(f.store.completeLogin(anyString(), anyString(), anyLong(), anyString(), any[Instant]))
        .thenReturn(true)
      val sealedToken = org.mockito.ArgumentCaptor.forClass(classOf[SealedOsmToken])
      when(
        f.store.attachOsmToken(
          anyString(),
          anyString(),
          anyLong(),
          sealedToken.capture(),
          anyString(),
          any[Instant]
        )
      ).thenReturn(true)
      val result = f.controller.callback.apply(
        FakeRequest(GET, s"/oauth/mobile/callback?state=$state&code=example")
          .withCookies(Cookie("mr_mobile_oauth", browser))
      )
      status(result) mustBe OK
      val body = contentAsString(result)
      body must include("edit OpenStreetMap as you")
      body must not include "upstream-only-secret"
      verify(f.store).attachOsmToken(
        org.mockito.ArgumentMatchers.eq(tagFix.idHash),
        org.mockito.ArgumentMatchers.eq(tagFix.browserHash),
        org.mockito.ArgumentMatchers.eq(42L),
        any[SealedOsmToken],
        org.mockito.ArgumentMatchers.eq("read_prefs write_api"),
        any[Instant]
      )
      new String(sealedToken.getValue.ciphertext, "UTF-8") must not include "upstream-only-secret"
      f.cipher.open(42L, sealedToken.getValue) mustBe Right("upstream-only-secret")
      f.cipher.open(43L, sealedToken.getValue) mustBe Left("unreadable")
    }

    "fail an osm:tagfix login closed when its token cannot be kept" in {
      val f      = new Fixture(config = Some(tagFixConfig))
      val tagFix = interaction.copy(scope = "tasks:read tasks:write osm:tagfix")
      when(f.store.claimLogin(anyString(), anyString(), any[Instant])).thenReturn(Some(tagFix))
      when(f.store.completeLogin(anyString(), anyString(), anyLong(), anyString(), any[Instant]))
        .thenReturn(true)
      when(
        f.store.attachOsmToken(
          anyString(),
          anyString(),
          anyLong(),
          any[SealedOsmToken],
          anyString(),
          any[Instant]
        )
      ).thenReturn(false)
      val result = f.controller.callback.apply(
        FakeRequest(GET, s"/oauth/mobile/callback?state=$state&code=example")
          .withCookies(Cookie("mr_mobile_oauth", browser))
      )
      status(result) mustBe BAD_REQUEST
    }

    "never keep the OSM token of a login without osm:tagfix" in {
      val f = new Fixture(config = Some(tagFixConfig))
      when(f.store.claimLogin(anyString(), anyString(), any[Instant]))
        .thenReturn(Some(interaction.copy(scope = "tasks:read tasks:write")))
      when(f.store.completeLogin(anyString(), anyString(), anyLong(), anyString(), any[Instant]))
        .thenReturn(true)
      val result = f.controller.callback.apply(
        FakeRequest(GET, s"/oauth/mobile/callback?state=$state&code=example")
          .withCookies(Cookie("mr_mobile_oauth", browser))
      )
      status(result) mustBe OK
      contentAsString(result) must not include "edit OpenStreetMap as you"
      verify(f.store, never()).attachOsmToken(
        anyString(),
        anyString(),
        anyLong(),
        any[SealedOsmToken],
        anyString(),
        any[Instant]
      )
    }

    "treat an unset, empty or malformed token key alike: no new osm:tagfix, nothing else changes" in {
      Seq(
        "",
        """osmTokenKey="" """,
        """osmTokenKey="   " """,
        """osmTokenKey="not base64!" """,
        s"""osmTokenKey="${java.util.Base64.getEncoder.encodeToString(Array.fill[Byte](16)(1))}" """
      ).foreach { keyLine =>
        val config = Configuration(
          ConfigFactory.parseString(
            s"""
          mobileOAuth {
            enabled=true
            callbackUri="https://mr.example/oauth/mobile/callback"
            $keyLine
            clients=[{
              id="app",name="App",redirectUris=["org.example.app:/callback"],
              scopes=["tasks:read","tasks:write","osm:tagfix"]
            }]
          }
        """
          )
        )
        val f = new Fixture(config = Some(config))
        f.settings.tagFixAvailable mustBe false
        f.cipher.available mustBe false
        // Configured scopes stay, so grants issued earlier keep authenticating.
        f.settings.allowedScopes("app", "tasks:read tasks:write osm:tagfix").isDefined mustBe true
        def authorize(scope: String) = f.controller.authorize.apply(
          FakeRequest(
            GET,
            "/oauth/mobile/authorize?response_type=code&client_id=app" +
              s"&redirect_uri=org.example.app%3A%2Fcallback&scope=$scope" +
              s"&state=native&code_challenge=${"a" * 43}&code_challenge_method=S256"
          )
        )
        val refused = authorize("tasks%3Aread+tasks%3Awrite+osm%3Atagfix")
        status(refused) mustBe BAD_REQUEST
        (contentAsJson(refused) \ "error").as[String] mustBe "invalid_scope"
        verify(f.store, never()).createInteraction(any[MobileInteraction])
        // Other sign-ins go on as before.
        Await.ready(authorize("tasks%3Aread+tasks%3Awrite"), 5.seconds)
        verify(f.store).createInteraction(any[MobileInteraction])
      }
    }

    "refuse an osm:tagfix login whose OSM grant lacks write_api" in {
      val f      = new Fixture(config = Some(tagFixConfig))
      val tagFix = interaction.copy(scope = "tasks:read tasks:write osm:tagfix")
      when(f.store.claimLogin(anyString(), anyString(), any[Instant])).thenReturn(Some(tagFix))
      when(f.store.completeLogin(anyString(), anyString(), anyLong(), anyString(), any[Instant]))
        .thenReturn(true)
      when(f.response.json).thenReturn(
        Json.obj("access_token" -> "upstream-only-secret", "scope" -> "read_prefs")
      )
      val result = f.controller.callback.apply(
        FakeRequest(GET, s"/oauth/mobile/callback?state=$state&code=example")
          .withCookies(Cookie("mr_mobile_oauth", browser))
      )
      status(result) mustBe BAD_REQUEST
      verify(f.store, never()).attachOsmToken(
        anyString(),
        anyString(),
        anyLong(),
        any[SealedOsmToken],
        anyString(),
        any[Instant]
      )
    }

    "persist the requested canonical scope at authorization" in {
      val f      = new Fixture()
      val stored = org.mockito.ArgumentCaptor.forClass(classOf[MobileInteraction])
      val result = f.controller.authorize.apply(
        FakeRequest(
          GET,
          "/oauth/mobile/authorize?response_type=code&client_id=app" +
            "&redirect_uri=org.example.app%3A%2Fcallback&scope=tasks%3Awrite+tasks%3Aread" +
            s"&state=native&code_challenge=${"a" * 43}&code_challenge_method=S256"
        )
      )
      // The upstream redirect builder is not stubbed; only the stored interaction matters here.
      Await.ready(result, 5.seconds)
      verify(f.store).createInteraction(stored.capture())
      stored.getValue.scope mustBe "tasks:read tasks:write"
    }

    "reject invalid consent CSRF and return only code and client state after approval" in {
      val f = new Fixture()
      when(f.store.getInteraction(anyString(), anyString(), any[Instant]))
        .thenReturn(Some(interaction))
      when(
        f.store.approveInteraction(
          anyString(),
          anyString(),
          anyString(),
          anyString(),
          anyString(),
          any[Instant],
          any[Instant]
        )
      ).thenReturn(None)
      def request =
        FakeRequest(POST, "/oauth/mobile/consent")
          .withCookies(Cookie("mr_mobile_oauth", browser))
          .withFormUrlEncodedBody("interaction" -> state, "csrf" -> csrf, "decision" -> "allow")
      status(call(f.controller.consent, request)) mustBe BAD_REQUEST
      when(
        f.store.approveInteraction(
          anyString(),
          anyString(),
          anyString(),
          anyString(),
          anyString(),
          any[Instant],
          any[Instant]
        )
      ).thenReturn(Some(grant))
      val result = call(f.controller.consent, request)
      status(result) mustBe SEE_OTHER
      val location = redirectLocation(result).get
      location must startWith("org.example.app:/callback?code=")
      location must include("state=native-state")
      location must not include "access_token"
      location must not include "private-global-key"
    }

    "return a credential-free identity projection" in {
      val f = new Fixture()
      when(f.store.authenticate(anyString(), any[Instant])).thenReturn(Some(grant))
      when(f.users.retrieve(42L)).thenReturn(Some(user))
      val result = f.controller.me.apply(
        FakeRequest(GET, "/oauth/mobile/me")
          .withHeaders(AUTHORIZATION -> s"Bearer ${MobileSecrets.generate()}")
      )
      status(result) mustBe OK
      contentAsJson(result) mustBe Json.obj(
        "id"          -> 42,
        "osmId"       -> 99,
        "displayName" -> "Example User",
        "scope"       -> "tasks:read"
      )
    }
  }
}

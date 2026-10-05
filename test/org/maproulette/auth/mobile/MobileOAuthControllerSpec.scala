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
      clients=[{id="app",name="Example <App>",redirectUris=["org.example.app:/callback"]}]
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

  private class Fixture(enabled: Boolean = true) {
    val store    = mock[MobileOAuthStore]
    val settings = new MobileOAuthSettings(if (enabled) mobileConfig else Configuration.empty)
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
    when(response.json).thenReturn(Json.obj("access_token" -> "upstream-only-secret"))
    when(identity.resolve(anyString())).thenReturn(Future.successful(user))
    val controller = new MobileOAuthController(
      stubControllerComponents(),
      service,
      settings,
      identity,
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

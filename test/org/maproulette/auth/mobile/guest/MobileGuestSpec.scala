package org.maproulette.auth.mobile.guest

import akka.actor.ActorSystem
import akka.stream.{Materializer, SystemMaterializer}
import com.typesafe.config.ConfigFactory
import controllers.{MobileGuestController, MobileOAuthController}
import java.time.Instant
import java.util.UUID
import org.maproulette.Config
import org.maproulette.auth.mobile._
import org.maproulette.framework.service.UserService
import org.scalatest.BeforeAndAfterAll
import org.scalatestplus.mockito.MockitoSugar
import org.scalatestplus.play.PlaySpec
import play.api.Configuration
import play.api.libs.json.Json
import play.api.libs.ws.WSClient
import play.api.mvc.{RequestHeader, Results}
import play.api.mvc.request.RemoteConnection
import play.api.test.FakeRequest
import play.api.test.Helpers._
import scala.collection.mutable
import scala.concurrent.{ExecutionContext, Future}

/** In-memory guest storage with the repository's rules (MobileGuestRepositorySpec covers SQL). */
class MemoryGuestStore extends MobileGuestStore {
  private case class Row(guest: MobileGuest, secretHash: Option[String])
  private val rows   = mutable.Map[UUID, Row]()
  private val tokens = mutable.Map[String, (UUID, Instant)]()

  def claim(id: UUID): Unit = synchronized {
    val row = rows(id)
    rows(id) =
      row.copy(guest = row.guest.copy(claimedUserId = Some(1L), claimedAt = Some(Instant.now())))
  }

  def create(id: UUID, clientId: String, secretHash: String, expiresAt: Instant, now: Instant) =
    synchronized {
      val guest =
        MobileGuest(id, clientId, now, expiresAt, false, false, false, None, None, None, None)
      rows(id) = Row(guest, Some(secretHash))
      guest
    }
  def get(id: UUID): Option[MobileGuest] = synchronized(rows.get(id).map(_.guest))
  def issueToken(
      id: UUID,
      clientId: String,
      secretHash: String,
      tokenHash: String,
      tokenExpiresAt: Instant,
      now: Instant
  ): Either[GuestTokenProblem, MobileGuest] = synchronized {
    rows
      .get(id)
      .filter(r => r.guest.clientId == clientId && r.secretHash.contains(secretHash)) match {
      case Some(row) if !row.guest.live(now) => Left(GuestTokenProblem.InvalidGrant)
      case Some(row) if row.guest.claimed    => Left(GuestTokenProblem.Claimed)
      case Some(row) =>
        tokens(tokenHash) = (id, tokenExpiresAt)
        Right(row.guest)
      case None => Left(GuestTokenProblem.InvalidGrant)
    }
  }
  def authenticate(tokenHash: String, now: Instant): Option[MobileGuest] = synchronized {
    tokens
      .get(tokenHash)
      .collect {
        case (id, expires) if expires.isAfter(now) => rows(id).guest
      }
      .filter(g => g.live(now) && !g.claimed)
  }
  def delete(id: UUID, now: Instant): Boolean = synchronized {
    rows.get(id).filter(_.guest.deletedAt.isEmpty) match {
      case Some(row) =>
        rows(id) = Row(row.guest.copy(deletedAt = Some(now)), None)
        tokens.filterInPlace { case (_, (guest, _)) => guest != id }
        true
      case None => false
    }
  }
}

class MobileGuestSpec extends PlaySpec with MockitoSugar with BeforeAndAfterAll {
  private implicit val system: ActorSystem = ActorSystem(
    "mobile-guest-test",
    ConfigFactory.parseString("""
    mobile-oauth-dispatcher {
      type = Dispatcher
      executor = "thread-pool-executor"
      thread-pool-executor.fixed-pool-size = 2
    }
  """)
  )
  private implicit val materializer: Materializer = SystemMaterializer(system).materializer
  private implicit val ec: ExecutionContext       = system.dispatcher
  override def afterAll(): Unit                   = { await(system.terminate()); super.afterAll() }

  private def settings(guests: Boolean = true, writeControl: Boolean = false) =
    new MobileOAuthSettings(
      Configuration(
        ConfigFactory.parseString(s"""
      mobileOAuth {
        enabled = true
        writeControlEnabled = $writeControl
        callbackUri = "https://mr.example/oauth/mobile/callback"
        guests.enabled = $guests
        clients = [
          { id = "app", name = "App", redirectUris = ["org.example.app:/cb"], scopes = ["tasks:read", "guest"] },
          { id = "plain", name = "Plain", redirectUris = ["org.example.plain:/cb"], scopes = ["tasks:read"] }
        ]
      }
    """)
      )
    )

  private class Fixture(guests: Boolean = true) {
    val store  = new MemoryGuestStore
    val config = settings(guests)
    val service =
      new MobileGuestService(store, config, new StaticMobileClientRegistry(config), system)
    val pendingCounts = new org.maproulette.provider.choice.ChoicePendingStore {
      def save(
          write: org.maproulette.provider.choice.PendingWrite,
          limit: Int,
          guestExpiresAt: Instant,
          now: Instant
      )                                                         = ???
      def withdraw(guestId: UUID, taskId: Long)                 = ???
      def counts(guestId: UUID)                                 = Map("pending" -> 2, "published" -> 1, "expired" -> 4)
      def list(guestId: UUID, limit: Int, before: Option[Long]) = ???
      def held(taskIds: Seq[Long])                              = ???
    }
    val controller = new MobileGuestController(stubControllerComponents(), service, pendingCounts)
    val oauth = new MobileOAuthController(
      stubControllerComponents(),
      new MobileOAuthService(mock[MobileOAuthStore], config, system),
      config,
      mock[MobileOSMIdentity],
      new MobileOsmTokenCipher(config),
      mock[UserService],
      mock[Config],
      mock[WSClient],
      Some(service)
    )
    def register(client: String = "app", ip: String = "192.0.2.1") =
      controller.register(
        FakeRequest(POST, "/oauth/mobile/guest")
          .withBody(Map("client_id" -> Seq(client)))
          .withConnection(RemoteConnection(ip, secure = false, clientCertificateChain = None))
      )
    def tokenRequest(fields: (String, String)*) =
      oauth.token(
        FakeRequest(POST, "/oauth/mobile/token").withBody(
          fields.map { case (k, v) => k -> Seq(v) }.toMap
        )
      )
    def registered(): (String, String) = {
      val body = contentAsJson(register())
      ((body \ "guestId").as[String], (body \ "guestSecret").as[String])
    }
    def accessToken(): String = {
      val (id, secret) = registered()
      val result = tokenRequest(
        "grant_type"   -> service.GrantType,
        "client_id"    -> "app",
        "guest_id"     -> id,
        "guest_secret" -> secret
      )
      status(result) mustBe OK
      (contentAsJson(result) \ "access_token").as[String]
    }
  }

  "Guest registration" should {
    "return a guest id, a one-time secret and the deadline" in new Fixture {
      val result = register()
      status(result) mustBe CREATED
      header(CACHE_CONTROL, result) mustBe Some("no-store")
      val body = contentAsJson(result)
      UUID.fromString((body \ "guestId").as[String])
      (body \ "guestSecret").as[String] must fullyMatch regex "[A-Za-z0-9_-]{43}"
      Instant
        .parse((body \ "expiresAt").as[String])
        .isAfter(Instant.now().plusSeconds(29L * 86400)) mustBe
        true
    }
    "refuse clients without the guest scope, and unknown clients" in new Fixture {
      status(register("plain")) mustBe UNAUTHORIZED
      contentAsJson(register("nobody")) mustBe Json.obj("error" -> "invalid_client")
    }
    "limit registrations per IP and hour" in new Fixture {
      (1 to service.registrationsPerHour).foreach(_ =>
        status(register(ip = "198.51.100.7")) mustBe CREATED
      )
      val limited = register(ip = "198.51.100.7")
      status(limited) mustBe TOO_MANY_REQUESTS
      header(RETRY_AFTER, limited) mustBe Some("3600")
      status(register(ip = "198.51.100.8")) mustBe CREATED
    }
    "start a new window each hour" in new Fixture {
      val hour = Instant.parse("2026-01-01T10:00:00Z")
      (1 to service.registrationsPerHour).foreach(_ => service.admit("ip", hour) mustBe true)
      service.admit("ip", hour.plusSeconds(60)) mustBe false
      service.admit("ip", hour.plusSeconds(3600)) mustBe true
    }
    "answer 404 while guests are off" in new Fixture(guests = false) {
      status(register()) mustBe NOT_FOUND
    }
  }

  "The guest token grant" should {
    "issue a guest access token without a refresh token" in new Fixture {
      val (id, secret) = registered()
      val result = tokenRequest(
        "grant_type"   -> service.GrantType,
        "client_id"    -> "app",
        "guest_id"     -> id,
        "guest_secret" -> secret
      )
      status(result) mustBe OK
      val body = contentAsJson(result)
      (body \ "scope").as[String] mustBe "guest"
      (body \ "token_type").as[String] mustBe "Bearer"
      (body \ "expires_in").as[Long] mustBe 900L
      (body \ "refresh_token").toOption mustBe None
      await(service.authenticate((body \ "access_token").as[String]))
        .map(_.id.toString) mustBe Some(id)
    }
    "refuse a wrong secret, a malformed id, another client or a client secret" in new Fixture {
      val (id, secret) = registered()
      def grant(fields: (String, String)*) = {
        val base = Map(
          "grant_type"   -> service.GrantType,
          "client_id"    -> "app",
          "guest_id"     -> id,
          "guest_secret" -> secret
        )
        tokenRequest((base ++ fields).toSeq: _*)
      }
      contentAsJson(grant("guest_secret"  -> "B" * 43)) mustBe Json.obj("error"     -> "invalid_grant")
      contentAsJson(grant("guest_id"      -> "not-a-uuid")) mustBe Json.obj("error" -> "invalid_grant")
      contentAsJson(grant("client_id"     -> "plain")) mustBe Json.obj("error"      -> "invalid_client")
      contentAsJson(grant("client_secret" -> "x")) mustBe Json.obj("error"          -> "invalid_client")
    }
    "say guest_claimed once the guest has been claimed" in new Fixture {
      val (id, secret) = registered()
      store.claim(UUID.fromString(id))
      val result = tokenRequest(
        "grant_type"   -> service.GrantType,
        "client_id"    -> "app",
        "guest_id"     -> id,
        "guest_secret" -> secret
      )
      status(result) mustBe BAD_REQUEST
      contentAsJson(result) mustBe Json.obj("error" -> "guest_claimed")
    }
    "be an unsupported grant type while guests are off" in new Fixture(guests = false) {
      contentAsJson(tokenRequest("grant_type" -> service.GrantType, "client_id" -> "app")) mustBe
        Json.obj("error" -> "unsupported_grant_type")
    }
  }

  "Guest routes" should {
    def guestRequest(method: String, path: String, guest: MobileGuest) =
      FakeRequest(method, path).addAttr(MobileBearerIdentity.GuestKey, guest)

    "show the guest's status" in new Fixture {
      val token  = accessToken()
      val guest  = await(service.authenticate(token)).get
      val result = controller.me(guestRequest(GET, "/api/v2/mobile-guest/me", guest))
      status(result) mustBe OK
      val body = contentAsJson(result)
      (body \ "guestId").as[String] mustBe guest.id.toString
      (body \ "state").as[String] mustBe "active"
      (body \ "email").as[String] mustBe "none"
      (body \ "pending").as[Int] mustBe 2
      (body \ "published").as[Int] mustBe 1
      (body \ "claimedAs").toOption mustBe Some(play.api.libs.json.JsNull)
    }
    "delete the guest's data and end its tokens" in new Fixture {
      val token = accessToken()
      val guest = await(service.authenticate(token)).get
      status(controller.delete(guestRequest(DELETE, "/api/v2/mobile-guest", guest))) mustBe NO_CONTENT
      await(service.authenticate(token)) mustBe None
      store.get(guest.id).flatMap(_.deletedAt) must not be None
    }
    "require a guest identity" in new Fixture {
      status(controller.me(FakeRequest(GET, "/api/v2/mobile-guest/me"))) mustBe UNAUTHORIZED
    }
  }

  "The bearer filter with guests" should {
    val token = "G" * 43
    val guest =
      MobileGuest(
        UUID.randomUUID(),
        "app",
        Instant.now(),
        Instant.now().plusSeconds(60),
        false,
        false,
        false,
        None,
        None,
        None,
        None
      )
    val guests = new MobileGuestAuth {
      def enabled                     = true
      def authenticate(value: String) = Future.successful(Some(guest).filter(_ => value == token))
    }
    def filter(auth: MobileGuestAuth = guests) = {
      val oauth = mock[MobileOAuthService]
      org.mockito.Mockito
        .when(oauth.authenticate(org.mockito.ArgumentMatchers.anyString()))
        .thenReturn(Future.successful(None))
      new MobileBearerFilter(
        settings(),
        oauth,
        mock[UserService],
        MobileAdminCheck.Nobody,
        null,
        null,
        auth
      )
    }
    def next(request: RequestHeader) =
      Future.successful(
        Results.Ok(
          request.attrs.get(MobileBearerIdentity.GuestKey).map(_.id.toString).getOrElse("none")
        )
      )
    def bearer(method: String, path: String, value: String = token) =
      FakeRequest(method, path).withHeaders("Authorization" -> s"Bearer $value")

    "let a guest token through to discovery reads and its own routes" in {
      Seq(
        GET    -> "/api/v2/challenges/extendedFind",
        GET    -> "/api/v2/task/1",
        GET    -> "/api/v2/tasks/box/1/2/3/4",
        PUT    -> "/api/v2/markers/box/1/2/3/4",
        GET    -> "/api/v2/task/1/choice/check",
        GET    -> "/api/v2/mobile-guest/me",
        GET    -> "/api/v2/mobile-guest/pending",
        DELETE -> "/api/v2/task/1/choice/pending",
        DELETE -> "/api/v2/mobile-guest"
      ).foreach {
        case (method, path) =>
          contentAsString(filter().apply(next)(bearer(method, path))) mustBe guest.id.toString
      }
    }
    "keep a guest token off writes, choice submissions and the identity endpoint" in {
      Seq(
        GET  -> "/api/v2/task/1/start",
        PUT  -> "/api/v2/task/1/1",
        POST -> "/api/v2/task/1/skip",
        GET  -> "/oauth/mobile/me",
        GET  -> "/api/v2/mobile-admin/clients"
      ).foreach {
        case (method, path) =>
          val result = filter().apply(next)(bearer(method, path))
          status(result) mustBe FORBIDDEN
          contentAsJson(result) mustBe Json.obj("error" -> "insufficient_scope")
      }
    }
    "refuse an unknown token on guest routes" in {
      status(filter().apply(next)(bearer(GET, "/api/v2/mobile-guest/me", "H" * 43))) mustBe UNAUTHORIZED
    }
    "take a pending answer shaped like a choice submission" in {
      def pending(body: String, contentType: String = "application/json") =
        bearer(POST, "/api/v2/task/1/choice/pending")
          .withHeaders("Content-Type" -> contentType, "Content-Length" -> body.length.toString)
          .withBody(body)
      contentAsString(filter().apply(next)(pending("""{"answers":{"a":"b"}}"""))) mustBe
        guest.id.toString
      status(filter().apply(next)(pending("x", "text/plain"))) mustBe BAD_REQUEST
      status(filter().apply(next)(pending("x" * 2049))) mustBe BAD_REQUEST
      status(filter().apply(next)(bearer(POST, "/api/v2/task/1/choice/pending"))) mustBe BAD_REQUEST
    }
    "allow only limit and after on the pending list" in {
      contentAsString(
        filter().apply(next)(bearer(GET, "/api/v2/mobile-guest/pending?limit=5&after=9"))
      ) mustBe guest.id.toString
      status(filter().apply(next)(bearer(GET, "/api/v2/mobile-guest/pending?x=1"))) mustBe BAD_REQUEST
      status(
        filter().apply(next)(bearer(GET, "/api/v2/mobile-guest/pending?limit=1&limit=2"))
      ) mustBe BAD_REQUEST
      status(filter().apply(next)(bearer(GET, "/api/v2/task/1/choice/check?x=1"))) mustBe BAD_REQUEST
      status(filter().apply(next)(bearer(DELETE, "/api/v2/task/1/choice/pending?x=1"))) mustBe
        BAD_REQUEST
    }
    "refuse a query or body on the guest's own routes" in {
      status(filter().apply(next)(bearer(GET, "/api/v2/mobile-guest/me?x=1"))) mustBe BAD_REQUEST
      status(filter().apply(next)(bearer(DELETE, "/api/v2/mobile-guest").withBody("x"))) mustBe BAD_REQUEST
    }
    "not know the guest routes while guests are off" in {
      val result =
        filter(MobileGuestAuth.Disabled).apply(next)(bearer(GET, "/api/v2/mobile-guest/me"))
      status(result) mustBe FORBIDDEN
      contentAsJson(result) mustBe Json.obj("error" -> "insufficient_scope")
    }
    "keep app grants off the guest's own routes" in {
      val oauth = mock[MobileOAuthService]
      org.mockito.Mockito
        .when(oauth.authenticate(token))
        .thenReturn(
          Future.successful(
            Some(MobileGrant("family", 1, "app", "tasks:read", "org.example.app:/cb", "c"))
          )
        )
      val result = new MobileBearerFilter(
        settings(),
        oauth,
        mock[UserService],
        MobileAdminCheck.Nobody,
        null,
        null,
        guests
      ).apply(next)(bearer(GET, "/api/v2/mobile-guest/me"))
      status(result) mustBe FORBIDDEN
    }
    "allow guest registration and status while the field write switch is off" in {
      MobileFieldRoutes.permitsWhenDisabled(FakeRequest(POST, "/oauth/mobile/guest")) mustBe true
      MobileFieldRoutes.permitsWhenDisabled(FakeRequest(GET, "/api/v2/mobile-guest/me")) mustBe true
      MobileFieldRoutes.permitsWhenDisabled(FakeRequest(DELETE, "/api/v2/mobile-guest")) mustBe true
      MobileFieldRoutes.permitsWhenDisabled(FakeRequest(DELETE, "/api/v2/task/1")) mustBe false
    }
    "allow pending answers and the choice check while the field write switch is off" in {
      Seq(
        GET    -> "/api/v2/task/1/choice/check",
        POST   -> "/api/v2/task/1/choice/pending",
        DELETE -> "/api/v2/task/1/choice/pending",
        GET    -> "/api/v2/mobile-guest/pending"
      ).foreach {
        case (method, path) =>
          MobileFieldRoutes.permitsWhenDisabled(
            FakeRequest(method, path).withHeaders("Authorization" -> s"Bearer $token")
          ) mustBe true
      }
      // Without a bearer token nothing could authenticate a guest.
      MobileFieldRoutes.permitsWhenDisabled(FakeRequest(GET, "/api/v2/task/1/choice/check")) mustBe
        false
      MobileFieldRoutes.permitsWhenDisabled(FakeRequest(POST, "/api/v2/task/1/choice")) mustBe false
    }
    "give the choice check to guests only while the field write switch is off" in {
      val policy = mock[MobileWritePolicy]
      org.mockito.Mockito.when(policy.enabled).thenReturn(false)
      val oauth = mock[MobileOAuthService]
      org.mockito.Mockito
        .when(oauth.authenticate("A" * 43))
        .thenReturn(
          Future.successful(
            Some(MobileGrant("family", 1, "app", "tasks:read", "org.example.app:/cb", "c"))
          )
        )
      org.mockito.Mockito
        .when(oauth.authenticate(token))
        .thenReturn(Future.successful(None))
      val gated = new MobileBearerFilter(
        settings(writeControl = true),
        oauth,
        mock[UserService],
        MobileAdminCheck.Nobody,
        null,
        policy,
        guests
      )
      contentAsString(gated.apply(next)(bearer(GET, "/api/v2/task/1/choice/check"))) mustBe
        guest.id.toString
      val app = gated.apply(next)(bearer(GET, "/api/v2/task/1/choice/check", "A" * 43))
      status(app) mustBe FORBIDDEN
      contentAsJson(app) mustBe Json.obj("error" -> "mobile_writes_disabled")
    }
  }

  "MobileOAuthController" should {
    "have exactly one injectable constructor" in {
      classOf[MobileOAuthController].getConstructors
        .count(_.isAnnotationPresent(classOf[javax.inject.Inject])) mustBe 1
    }
  }
}

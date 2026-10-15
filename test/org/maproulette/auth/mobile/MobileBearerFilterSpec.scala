package org.maproulette.auth.mobile

import akka.actor.ActorSystem
import akka.stream.{Materializer, SystemMaterializer}
import org.joda.time.DateTime
import org.maproulette.framework.model.{Location, OSMProfile, User}
import org.maproulette.framework.service.UserService
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.{never, verify, when}
import org.scalatest.BeforeAndAfterAll
import org.scalatestplus.mockito.MockitoSugar
import org.scalatestplus.play.PlaySpec
import play.api.Configuration
import play.api.mvc.{RequestHeader, Results}
import play.api.test.FakeRequest
import play.api.test.Helpers._
import scala.concurrent.{ExecutionContext, Future}

class MobileBearerFilterSpec extends PlaySpec with MockitoSugar with BeforeAndAfterAll {
  private implicit val system: ActorSystem        = ActorSystem("mobile-bearer-tests")
  private implicit val materializer: Materializer = SystemMaterializer(system).materializer
  private implicit val ec: ExecutionContext       = system.dispatcher
  private val token                               = "A" * 43
  private val user = User(
    123,
    DateTime.now(),
    DateTime.now(),
    OSMProfile(456, "Mapper", "", "", Location(1, 2), DateTime.now(), "legacy-token"),
    List.empty
  )
  private val grant = MobileGrant(
    "family",
    123,
    "test-client",
    "tasks:read",
    "org.example.app:/callback",
    "challenge"
  )
  override def afterAll(): Unit = { await(system.terminate()); super.afterAll() }

  private def settings(
      enabled: Boolean,
      allowTaskWrites: Boolean = true,
      writeControlEnabled: Boolean = false
  ) =
    new MobileOAuthSettings(
      Configuration.from(
        Map(
          "mobileOAuth.enabled"             -> enabled,
          "mobileOAuth.callbackUri"         -> "https://example.org/oauth/mobile/callback",
          "mobileOAuth.allowTaskWrites"     -> allowTaskWrites,
          "mobileOAuth.writeControlEnabled" -> writeControlEnabled
        )
      )
    )
  private def next(request: RequestHeader) =
    Future.successful(
      Results
        .Ok(request.attrs.get(MobileBearerIdentity.UserKey).map(_.id.toString).getOrElse("legacy"))
    )

  private val writeGrant = grant.copy(scope = "tasks:read tasks:write")
  private val allowedWrites = Seq(
    GET  -> "/api/v2/task/123/start",
    GET  -> "/api/v2/task/123/release",
    POST -> "/api/v2/task/123/skip",
    PUT  -> "/api/v2/task/123/1",
    PUT  -> "/api/v2/task/123/2",
    PUT  -> "/api/v2/task/123/5",
    PUT  -> "/api/v2/task/123/6"
  )
  private def bearer(method: String, path: String) =
    FakeRequest(method, path).withHeaders("Authorization" -> s"Bearer $token")
  private def json(request: FakeRequest[_], body: String, extra: (String, String)*) =
    request
      .withHeaders(
        Seq("Content-Type" -> "application/json", "Content-Length" -> body.length.toString) ++ extra: _*
      )
      .withBody(body)
  private val editGrant = grant.copy(scope = "tasks:read tasks:write tasks:edit")
  private def editorRequests = Seq(
    bearer(PUT, "/api/v2/task/123/1?requestReview=true"),
    bearer(PUT, "/api/v2/task/123/2?requestReview=true&tags=a,b"),
    json(bearer(PUT, "/api/v2/task/123/5"), """{"answer":"yes"}"""),
    json(bearer(PUT, "/api/v2/task/123/6?tags=x"), "x" * 16384),
    json(bearer(POST, "/api/v2/task/123/comment"), """{"comment":"done"}"""),
    json(bearer(POST, "/api/v2/task/123/comment?actionId=7"), """{"comment":"done"}"""),
    bearer(GET, "/api/v2/task/123/refreshLock"),
    bearer(PUT, "/api/v2/task/123/changeset"),
    bearer(PUT, "/api/v2/task/123/changeset?changesetId=987654")
  )
  private def writeFilter(scopedGrant: MobileGrant) = {
    val oauth = mock[MobileOAuthService]
    val users = mock[UserService]
    when(oauth.authenticate(token)).thenReturn(Future.successful(Some(scopedGrant)))
    when(users.retrieve(123)).thenReturn(Some(user))
    (new MobileBearerFilter(settings(true), oauth, users), oauth)
  }

  "Mobile scope parsing" should {
    "accept read and read+write sets in either order and format them canonically" in {
      MobileScopes.parse("tasks:read") mustBe Some(Set("tasks:read"))
      MobileScopes.parse("tasks:write tasks:read") mustBe Some(Set("tasks:read", "tasks:write"))
      MobileScopes.format(Set("tasks:write", "tasks:read")) mustBe "tasks:read tasks:write"
    }
    "accept osm:tagfix only together with tasks:write, in canonical order" in {
      MobileScopes.parse("osm:tagfix tasks:write tasks:read") mustBe
        Some(Set("tasks:read", "tasks:write", "osm:tagfix"))
      MobileScopes.format(Set("osm:tagfix", "tasks:read", "tasks:write")) mustBe
        "tasks:read tasks:write osm:tagfix"
      MobileScopes.parse("tasks:read osm:tagfix") mustBe None
      MobileScopes.parse("osm:tagfix") mustBe None
      MobileScopes.osmScope("tasks:read tasks:write osm:tagfix") mustBe "read_prefs write_api"
      MobileScopes.osmScope("tasks:read tasks:write") mustBe "read_prefs"
    }
    "reject empty, unknown, duplicate, write-only and irregularly spaced scopes" in {
      Seq(
        "",
        "tasks:write",
        "tasks:read tasks:read",
        "tasks:read tasks:delete",
        "tasks:read  tasks:write",
        " tasks:read",
        "tasks:read\ttasks:write",
        "TASKS:READ"
      ).foreach(value => MobileScopes.parse(value) mustBe None)
    }
  }

  "Mobile task write gate" should {
    "close legacy and bearer write paths when the field policy is off" in {
      val policy = mock[MobileWritePolicy]
      when(policy.enabled).thenReturn(false)
      val filter = new MobileBearerFilter(
        settings(true, writeControlEnabled = true),
        mock[MobileOAuthService],
        mock[UserService],
        MobileAdminCheck.Nobody,
        mock[MobileAdminRepository],
        policy
      )
      val deniedRequests = Seq(
        bearer(GET, "/api/v2/task/123/start"),
        FakeRequest(GET, "/api/v2/task/123/choice/check"),
        FakeRequest(POST, "/api/v2/challenge"),
        FakeRequest(PUT, "/api/v2/task/123/1"),
        FakeRequest(GET, "/auth/generateAPIKey")
      )
      deniedRequests.foreach { request =>
        val result = filter.apply(next)(request)
        status(result) mustBe FORBIDDEN
        contentAsJson(result) mustBe play.api.libs.json.Json
          .obj("error" -> "mobile_writes_disabled")
      }
      contentAsString(filter.apply(next)(FakeRequest(GET, "/ping"))) mustBe "legacy"
      contentAsString(
        filter.apply(next)(FakeRequest(GET, "/api/v2/challenges/search?search=field&limit=1"))
      ) mustBe "legacy"
    }

    "allow only bearer admin challenge setup through the disabled policy gate" in {
      Seq(
        POST -> "/api/v2/challenge",
        PUT  -> "/api/v2/challenge/123",
        PUT  -> "/api/v2/challenge/123/addFileTasks?lineByLine=true&report=true"
      ).foreach {
        case (method, path) =>
          MobileFieldRoutes.permitsWhenDisabled(bearer(method, path)) mustBe true
          MobileFieldRoutes.permitsWhenDisabled(FakeRequest(method, path)) mustBe false
      }
      MobileFieldRoutes.permitsWhenDisabled(
        bearer(
          PUT,
          "/api/v2/challenge/123/addFileTasks?lineByLine=true&report=true&removeUnmatched=true"
        )
      ) mustBe false
      MobileFieldRoutes.permitsWhenDisabled(bearer(POST, "/api/v2/task/123/choice")) mustBe false
    }

    "D13 (1): give the claim page's grant only the claim routes among writes" in {
      val claimGrant = writeGrant.copy(
        clientId = MobileClaimRoutes.ClientId,
        scope = "tasks:read tasks:write osm:tagfix"
      )
      val (filter, _) = writeFilter(claimGrant)
      def json(method: String, path: String) =
        bearer(method, path)
          .withHeaders("Content-Type" -> "application/json", "Content-Length" -> "2")
          .withBody("{}")
      (allowedWrites.map { case (method, path) => bearer(method, path) } :+
        json(POST, "/api/v2/task/123/choice")).foreach { request =>
        val result = filter.apply(next)(request)
        status(result) mustBe FORBIDDEN
        contentAsJson(result) mustBe play.api.libs.json.Json.obj("error" -> "insufficient_scope")
      }
      status(filter.apply(next)(bearer(GET, "/api/v2/task/123/choice/check"))) mustBe FORBIDDEN
      contentAsString(filter.apply(next)(json(POST, "/api/v2/mobile-claim"))) mustBe "123"
    }

    "D13 (3): let only the claim page's grant claim while the switch is off, and audit it" in {
      val policy = mock[MobileWritePolicy]
      when(policy.enabled).thenReturn(false)
      val audit = mock[MobileAdminRepository]
      def gated(clientId: String) = {
        val oauth = mock[MobileOAuthService]
        val users = mock[UserService]
        when(oauth.authenticate(token)).thenReturn(
          Future.successful(
            Some(writeGrant.copy(clientId = clientId, scope = "tasks:read tasks:write osm:tagfix"))
          )
        )
        when(users.retrieve(123)).thenReturn(Some(user))
        new MobileBearerFilter(
          settings(true, writeControlEnabled = true),
          oauth,
          users,
          MobileAdminCheck.Nobody,
          audit,
          policy
        )
      }
      def post(path: String) =
        bearer(POST, path)
          .withHeaders("Content-Type" -> "application/json", "Content-Length" -> "2")
          .withBody("{}")
      val claimPage = gated(MobileClaimRoutes.ClientId)
      contentAsString(claimPage.apply(next)(post("/api/v2/mobile-claim"))) mustBe "123"
      contentAsString(claimPage.apply(next)(post("/api/v2/mobile-claim/9/retry"))) mustBe "123"
      verify(audit).record(
        123L,
        "claim.writes_off",
        "/api/v2/mobile-claim",
        Some(play.api.libs.json.Json.obj("status" -> 200))
      )
      verify(audit).record(
        123L,
        "claim.writes_off",
        "/api/v2/mobile-claim/9/retry",
        Some(play.api.libs.json.Json.obj("status" -> 200))
      )
      Seq(
        claimPage.apply(next)(bearer(POST, "/api/v2/task/123/skip")),
        gated("test-client").apply(next)(post("/api/v2/mobile-claim"))
      ).foreach { result =>
        status(result) mustBe FORBIDDEN
        contentAsJson(result) mustBe play.api.libs.json.Json
          .obj("error" -> "mobile_writes_disabled")
      }
      // Unauthenticated, the claim routes stay closed.
      MobileFieldRoutes.permitsWhenDisabled(FakeRequest(POST, "/api/v2/mobile-claim")) mustBe false
    }

    "refuse task writes when the deployment gate is closed, including existing write grants" in {
      val oauth = mock[MobileOAuthService]
      val filter =
        new MobileBearerFilter(settings(true, allowTaskWrites = false), oauth, mock[UserService])
      (allowedWrites :+ (POST -> "/api/v2/task/123/choice")).foreach {
        case (method, path) =>
          val result = filter.apply(next)(bearer(method, path))
          status(result) mustBe FORBIDDEN
          contentAsJson(result) mustBe play.api.libs.json.Json
            .obj("error" -> "mobile_writes_disabled")
      }
      verify(oauth, never()).authenticate(anyString())
    }

    "allow each audited lifecycle route for a tasks:write grant" in {
      val (filter, _) = writeFilter(writeGrant)
      allowedWrites.foreach {
        case (method, path) =>
          contentAsString(filter.apply(next)(bearer(method, path))) mustBe "123"
      }
    }

    "deny every lifecycle route to a legacy tasks:read grant with insufficient_scope" in {
      val (filter, _) = writeFilter(grant)
      allowedWrites.foreach {
        case (method, path) =>
          val result = filter.apply(next)(bearer(method, path))
          status(result) mustBe FORBIDDEN
          contentAsJson(result) mustBe play.api.libs.json.Json.obj("error" -> "insufficient_scope")
      }
      contentAsString(filter.apply(next)(bearer(GET, "/api/v2/task/123"))) mustBe "123"
    }

    "deny unaudited writes and status codes even with tasks:write, before authentication" in {
      val (filter, oauth) = writeFilter(writeGrant)
      Seq(
        PUT    -> "/api/v2/task/123/0",
        PUT    -> "/api/v2/task/123/3",
        PUT    -> "/api/v2/task/123/4",
        PUT    -> "/api/v2/task/123/7",
        PUT    -> "/api/v2/task/123/8",
        PUT    -> "/api/v2/task/123/9",
        PUT    -> "/api/v2/task/123/01",
        PUT    -> "/api/v2/task/123/12",
        PUT    -> "/api/v2/task/123/-1",
        PUT    -> "/api/v2/task/123/1/",
        PUT    -> "/api/v2/task/123/%31",
        POST   -> "/api/v2/task/123/1",
        GET    -> "/api/v2/task/123/1",
        GET    -> "/api/v2/task/123/skip",
        PUT    -> "/api/v2/task/123/skip",
        POST   -> "/api/v2/task/123/start",
        PUT    -> "/api/v2/task/123/start",
        GET    -> "/api/v2/task/123/start/",
        GET    -> "/api/v2/task/abc/start",
        PUT    -> "/api/v2/task/123/unlock/request",
        GET    -> "/api/v2/task/123/tags/update",
        GET    -> "/api/v2/task/123/review/start",
        POST   -> "/api/v2/task/123/lockBundle",
        PUT    -> "/api/v2/task/123",
        DELETE -> "/api/v2/task/123",
        PUT    -> "/api/v2/task/123/review/1",
        PUT    -> "/api/v2/tasks/changeStatus",
        POST   -> "/api/v2/taskBundle",
        GET    -> "/api/v2/challenge/1/start",
        POST   -> "/api/v2/task/123/changeset"
      ).foreach {
        case (method, path) =>
          status(filter.apply(next)(bearer(method, path))) mustBe FORBIDDEN
      }
      verify(oauth, never()).authenticate(anyString())
    }

    "reject a query string or body on write routes, including review and tag overrides" in {
      val (filter, oauth) = writeFilter(writeGrant)
      Seq(
        bearer(PUT, "/api/v2/task/123/1?requestReview=maybe"),
        bearer(PUT, "/api/v2/task/123/1?comment=hi"),
        bearer(GET, "/api/v2/task/123/start?x=1"),
        bearer(POST, "/api/v2/task/123/skip?x=1"),
        bearer(PUT, "/api/v2/task/123/1").withHeaders("Content-Length"     -> "8"),
        bearer(PUT, "/api/v2/task/123/2").withHeaders("Transfer-Encoding"  -> "chunked"),
        bearer(POST, "/api/v2/task/123/skip").withHeaders("Content-Length" -> "2")
      ).foreach { request =>
        val result = filter.apply(next)(request)
        status(result) mustBe BAD_REQUEST
        contentAsJson(result) mustBe play.api.libs.json.Json.obj("error" -> "invalid_request")
      }
      contentAsString(
        filter.apply(next)(bearer(PUT, "/api/v2/task/123/1").withHeaders("Content-Length" -> "0"))
      ) mustBe "123"
      verify(oauth).authenticate(token)
    }

    "deny editor requests to a tasks:write grant with insufficient_scope" in {
      val (filter, _) = writeFilter(writeGrant)
      editorRequests.foreach { request =>
        val result = filter.apply(next)(request)
        status(result) mustBe FORBIDDEN
        contentAsJson(result) mustBe play.api.libs.json.Json.obj("error" -> "insufficient_scope")
      }
    }

    "keep query strings on read routes, reject invalid tokens and leave legacy writes unchanged" in {
      val (filter, _) = writeFilter(writeGrant)
      contentAsString(
        filter.apply(next)(bearer(GET, "/api/v2/challenge/1/tasks?limit=5"))
      ) mustBe "123"
      val oauth = mock[MobileOAuthService]
      when(oauth.authenticate(token)).thenReturn(Future.successful(None))
      val invalid = new MobileBearerFilter(settings(true), oauth, mock[UserService])
      status(invalid.apply(next)(bearer(PUT, "/api/v2/task/123/1"))) mustBe UNAUTHORIZED
      val legacy = mock[MobileOAuthService]
      val open   = new MobileBearerFilter(settings(true), legacy, mock[UserService])
      Seq(
        FakeRequest(PUT, "/api/v2/task/123/4?requestReview=false")
          .withHeaders("apiKey"                                         -> "legacy-key", "Content-Length" -> "2"),
        FakeRequest(PUT, "/api/v2/task/123/9").withSession("token"      -> "legacy-cookie"),
        FakeRequest(GET, "/api/v2/task/123/start").withHeaders("apiKey" -> "legacy-key")
      ).foreach(request => contentAsString(open.apply(next)(request)) mustBe "legacy")
      verify(legacy, never()).authenticate(anyString())
    }
  }

  "Mobile editor routes" should {
    "allow each editor request and the lifecycle routes for a tasks:edit grant" in {
      val (filter, _) = writeFilter(editGrant)
      (editorRequests ++ allowedWrites.map { case (method, path) => bearer(method, path) })
        .foreach(request => contentAsString(filter.apply(next)(request)) mustBe "123")
    }

    "reject editor requests outside the allowed shapes before authentication" in {
      val (filter, oauth) = writeFilter(editGrant)
      Seq(
        bearer(PUT, "/api/v2/task/123/1?requestReview=yes"),
        bearer(PUT, "/api/v2/task/123/1?requestReview=false"),
        bearer(PUT, "/api/v2/task/123/1?requestReview"),
        bearer(PUT, "/api/v2/task/123/1?requestReview=true&requestReview=false"),
        bearer(PUT, "/api/v2/task/123/1?tags=" + "a" * 1025),
        bearer(PUT, "/api/v2/task/123/1?comment=dropped"),
        bearer(PUT, "/api/v2/task/123/1").withHeaders("Content-Length" -> "2").withBody("{}"),
        json(bearer(PUT, "/api/v2/task/123/1"), "x" * 16385),
        json(bearer(PUT, "/api/v2/task/123/1"), "{}", "Transfer-Encoding" -> "chunked"),
        bearer(POST, "/api/v2/task/123/comment"),
        bearer(POST, "/api/v2/task/123/comment?actionId=abc"),
        json(bearer(POST, "/api/v2/task/123/comment?actionId=1&tags=x"), "{}"),
        bearer(GET, "/api/v2/task/123/refreshLock?x=1"),
        bearer(GET, "/api/v2/task/123/refreshLock").withHeaders("Content-Length" -> "2"),
        bearer(PUT, "/api/v2/task/123/changeset?changesetId=x"),
        bearer(PUT, "/api/v2/task/123/changeset?id=1"),
        json(bearer(PUT, "/api/v2/task/123/changeset"), "{}")
      ).foreach { request =>
        val result = filter.apply(next)(request)
        status(result) mustBe BAD_REQUEST
        contentAsJson(result) mustBe play.api.libs.json.Json.obj("error" -> "invalid_request")
      }
      Seq(
        PUT    -> "/api/v2/task/123/3?requestReview=true",
        POST   -> "/api/v2/task/123/changeset",
        PUT    -> "/api/v2/task/123/comment",
        GET    -> "/api/v2/task/123/comments/x",
        POST   -> "/api/v2/taskBundle/1/comment",
        DELETE -> "/api/v2/task/123/comment/1"
      ).foreach {
        case (method, path) => status(filter.apply(next)(bearer(method, path))) mustBe FORBIDDEN
      }
      verify(oauth, never()).authenticate(anyString())
    }

    "refuse editor writes when task writes are off" in {
      val oauth = mock[MobileOAuthService]
      val filter =
        new MobileBearerFilter(settings(true, allowTaskWrites = false), oauth, mock[UserService])
      editorRequests.foreach { request =>
        val result = filter.apply(next)(request)
        status(result) mustBe FORBIDDEN
        contentAsJson(result) mustBe play.api.libs.json.Json
          .obj("error" -> "mobile_writes_disabled")
      }
      verify(oauth, never()).authenticate(anyString())
    }
  }

  "Mobile choice routes" should {
    val choice = "/api/v2/task/123/choice"
    def json(body: String, extra: (String, String)*) =
      bearer(POST, choice).withHeaders(
        Seq("Content-Type" -> "application/json", "Content-Length" -> body.length.toString) ++ extra: _*
      )
    def attrs(request: RequestHeader) =
      Future.successful(
        Results.Ok(
          Seq(
            request.attrs.get(MobileBearerIdentity.UserKey).map(_.id.toString),
            request.attrs.get(MobileBearerIdentity.ScopesKey).map(_.toSeq.sorted.mkString("+")),
            request.attrs.get(MobileBearerIdentity.FamilyKey)
          ).flatten.mkString("|")
        )
      )

    "accept a small JSON body on POST choice only, and pass the grant's scopes and family on" in {
      val (filter, _) = writeFilter(grant.copy(scope = "tasks:read tasks:write osm:tagfix"))
      contentAsString(filter.apply(attrs)(json("""{"answers":{"a":"b"}}"""))) mustBe
        "123|osm:tagfix+tasks:read+tasks:write|family"
      contentAsString(
        filter.apply(next)(
          bearer(POST, choice).withHeaders(
            "Content-Type"   -> "application/json; charset=UTF-8",
            "Content-Length" -> "2048"
          )
        )
      ) mustBe "123"
    }

    "reject a choice body that is too large, chunked, untyped, empty or has a query" in {
      val (filter, _) = writeFilter(writeGrant)
      Seq(
        bearer(POST, choice)
          .withHeaders("Content-Type"  -> "application/json", "Content-Length" -> "2049"),
        json("{}", "Transfer-Encoding" -> "chunked"),
        bearer(POST, choice)
          .withHeaders("Content-Type"                     -> "application/json", "Transfer-Encoding" -> "chunked"),
        bearer(POST, choice).withHeaders("Content-Type"   -> "text/plain", "Content-Length" -> "2"),
        bearer(POST, choice).withHeaders("Content-Length" -> "2"),
        bearer(POST, choice).withHeaders("Content-Type"   -> "application/json"),
        bearer(POST, choice)
          .withHeaders("Content-Type" -> "application/json", "Content-Length" -> "0"),
        bearer(POST, choice + "?x=1")
          .withHeaders("Content-Type" -> "application/json", "Content-Length" -> "2"),
        bearer(POST, choice).withHeaders(
          "Content-Type"   -> "application/json",
          "Content-Length" -> "2",
          "Content-Length" -> "2"
        )
      ).foreach { request =>
        val result = filter.apply(next)(request)
        status(result) mustBe BAD_REQUEST
        contentAsJson(result) mustBe play.api.libs.json.Json.obj("error" -> "invalid_request")
      }
      // Every other lifecycle write keeps the no-body rule, JSON or not.
      status(
        filter.apply(next)(
          bearer(POST, "/api/v2/task/123/skip")
            .withHeaders("Content-Type" -> "application/json", "Content-Length" -> "2")
        )
      ) mustBe BAD_REQUEST
      // A status write with completion responses is an editor request, which tasks:write can't make.
      status(
        filter.apply(next)(
          bearer(PUT, "/api/v2/task/123/1")
            .withHeaders("Content-Type" -> "application/json", "Content-Length" -> "2")
        )
      ) mustBe FORBIDDEN
    }

    "require tasks:write for the submit and allow the check to tasks:read without query or body" in {
      val (readOnly, _) = writeFilter(grant)
      status(readOnly.apply(next)(json("{}"))) mustBe FORBIDDEN
      contentAsString(readOnly.apply(next)(bearer(GET, s"$choice/check"))) mustBe "123"
      Seq(
        bearer(GET, s"$choice/check?x=1"),
        bearer(GET, s"$choice/check").withHeaders("Content-Length" -> "2")
      ).foreach(request => status(readOnly.apply(next)(request)) mustBe BAD_REQUEST)
      Seq(
        bearer(PUT, choice),
        bearer(GET, choice),
        bearer(POST, s"$choice/check"),
        bearer(GET, s"$choice/check/"),
        bearer(POST, "/api/v2/task/abc/choice")
      ).foreach(request => status(readOnly.apply(next)(request)) mustBe FORBIDDEN)
    }
  }

  "Mobile bearer boundary" should {
    "leave disabled and non-bearer legacy requests unchanged without consulting the provider" in {
      val oauth    = mock[MobileOAuthService]
      val users    = mock[UserService]
      val disabled = new MobileBearerFilter(settings(false), oauth, users)
      contentAsString(
        disabled.apply(next)(
          FakeRequest(GET, "/auth/generateAPIKey")
            .withHeaders("Authorization" -> s"Bearer $token", "apiKey" -> "legacy-key")
        )
      ) mustBe "legacy"
      val enabled = new MobileBearerFilter(settings(true), oauth, users)
      contentAsString(
        enabled.apply(next)(
          FakeRequest(GET, "/api/v2/user/whoami")
            .withHeaders("apiKey" -> "legacy-key")
            .withSession("token" -> "legacy-cookie")
        )
      ) mustBe "legacy"
      verify(oauth, never()).authenticate(anyString())
    }

    "accept a valid scoped token only on explicitly audited reads" in {
      val oauth = mock[MobileOAuthService]
      val users = mock[UserService]
      when(oauth.authenticate(token)).thenReturn(Future.successful(Some(grant)))
      when(users.retrieve(123)).thenReturn(Some(user))
      val filter = new MobileBearerFilter(settings(true), oauth, users)
      contentAsString(
        filter.apply(next)(
          FakeRequest(GET, "/api/v2/task/123")
            .withHeaders("Authorization" -> s"bearer $token")
        )
      ) mustBe "123"
      contentAsString(
        filter.apply(next)(
          FakeRequest(PUT, "/api/v2/markers/box/-112/40/-111/41")
            .withHeaders("Authorization" -> s"Bearer $token")
        )
      ) mustBe "123"
    }

    "reject mutating GETs, credential-returning identity, other methods and ambiguous paths" in {
      val oauth  = mock[MobileOAuthService]
      val filter = new MobileBearerFilter(settings(true), oauth, mock[UserService])
      Seq(
        GET  -> "/auth/generateAPIKey",
        GET  -> "/api/v2/user/whoami",
        GET  -> "/api/v2/user/123",
        POST -> "/api/v2/task/123",
        GET  -> "/api/v2/task/123/",
        GET  -> "/api/v2/task/123%2fstart"
      ).foreach {
        case (method, path) =>
          status(
            filter.apply(next)(
              FakeRequest(method, path)
                .withHeaders("Authorization" -> s"Bearer $token")
            )
          ) mustBe FORBIDDEN
      }
      verify(oauth, never()).authenticate(anyString())
    }

    "reject malformed, duplicate and mixed credentials without falling back" in {
      val oauth  = mock[MobileOAuthService]
      val filter = new MobileBearerFilter(settings(true), oauth, mock[UserService])
      Seq("Bearer", "Bearer\t" + token, "Bearer  " + token, "BearerX " + token).foreach { value =>
        status(
          filter.apply(next)(
            FakeRequest(GET, "/api/v2/task/123")
              .withHeaders("Authorization" -> value, "apiKey" -> "legacy-key")
          )
        ) mustBe UNAUTHORIZED
      }
      status(
        filter.apply(next)(
          FakeRequest(GET, "/api/v2/task/123")
            .withHeaders("Authorization" -> s"Bearer $token", "Authorization" -> s"Bearer $token")
        )
      ) mustBe UNAUTHORIZED
      status(
        filter.apply(next)(
          FakeRequest(GET, "/api/v2/task/123")
            .withHeaders("Authorization" -> s"Bearer $token")
            .withSession("userId" -> "7", "tokenHash" -> "web-session")
        )
      ) mustBe UNAUTHORIZED
      verify(oauth, never()).authenticate(anyString())
    }

    "reject expired/revoked tokens, wrong scope, and deleted accounts" in {
      val oauth  = mock[MobileOAuthService]
      val users  = mock[UserService]
      val filter = new MobileBearerFilter(settings(true), oauth, users)
      val request =
        FakeRequest(GET, "/api/v2/task/123").withHeaders("Authorization" -> s"Bearer $token")
      when(oauth.authenticate(token)).thenReturn(Future.successful(None))
      status(filter.apply(next)(request)) mustBe UNAUTHORIZED
      when(oauth.authenticate(token))
        .thenReturn(Future.successful(Some(grant.copy(scope = "unknown"))))
      status(filter.apply(next)(request)) mustBe UNAUTHORIZED
      when(oauth.authenticate(token)).thenReturn(Future.successful(Some(grant)))
      when(users.retrieve(123)).thenReturn(None)
      status(filter.apply(next)(request)) mustBe UNAUTHORIZED
    }
  }

  "Mobile preview mode" should {
    // Challenge 7 and task 70 are a draft; everything else is published.
    val drafts = new MobileDrafts {
      def draft(target: MobileDraftRoutes.Target): Boolean = target match {
        case MobileDraftRoutes.Challenge(id) => id == 7
        case MobileDraftRoutes.Task(id)      => id == 70
      }
    }
    def draftFilter(superUser: Boolean) = {
      val oauth = mock[MobileOAuthService]
      val users = mock[UserService]
      when(oauth.authenticate(token)).thenReturn(Future.successful(Some(writeGrant)))
      when(users.retrieve(123)).thenReturn(Some(user))
      val admins = new MobileAdminCheck {
        def isAdmin(u: User): Boolean  = superUser
        def isAdmin(id: Long): Boolean = superUser
      }
      new MobileBearerFilter(settings(true), oauth, users, admins, null, null, drafts)
    }
    val draftReads = Seq(
      "/api/v2/challenge/7",
      "/api/v2/challenge/7/tags",
      "/api/v2/challenge/7/tasks",
      "/api/v2/task/70",
      "/api/v2/task/70/choice/check"
    )
    val draftWrites = Seq(
      GET  -> "/api/v2/task/70/start",
      POST -> "/api/v2/task/70/skip",
      PUT  -> "/api/v2/task/70/1",
      PUT  -> "/api/v2/task/70/6"
    )

    "answer 404 for a draft's challenge and tasks to everyone but super-users" in {
      val filter = draftFilter(superUser = false)
      (draftReads.map(GET -> _) ++ draftWrites).foreach {
        case (method, path) =>
          val result = filter.apply(next)(bearer(method, path))
          status(result) mustBe NOT_FOUND
          contentAsString(result) mustBe ""
      }
      contentAsString(filter.apply(next)(bearer(GET, "/api/v2/challenge/8"))) mustBe "123"
      contentAsString(filter.apply(next)(bearer(GET, "/api/v2/task/71"))) mustBe "123"
      contentAsString(filter.apply(next)(bearer(GET, "/api/v2/task/71/start"))) mustBe "123"
    }

    "let super-users read a draft but refuse their writes to it" in {
      val filter = draftFilter(superUser = true)
      draftReads.foreach { path =>
        contentAsString(filter.apply(next)(bearer(GET, path))) mustBe "123"
      }
      draftWrites.foreach {
        case (method, path) =>
          val result = filter.apply(next)(bearer(method, path))
          status(result) mustBe FORBIDDEN
          contentAsJson(result) mustBe play.api.libs.json.Json.obj("error" -> "challenge_draft")
      }
      val submit = bearer(POST, "/api/v2/task/70/choice")
        .withHeaders("Content-Type" -> "application/json", "Content-Length" -> "2")
      status(filter.apply(next)(submit)) mustBe FORBIDDEN
    }

    "leave release open so a lock taken before the challenge was disabled can be dropped" in {
      contentAsString(
        draftFilter(superUser = false).apply(next)(bearer(GET, "/api/v2/task/70/release"))
      ) mustBe "123"
    }
  }

  "Mobile choice tasks" should {
    // Task 80 is a multiple-choice task; task 81 is not.
    val choices = new MobileDrafts {
      def draft(target: MobileDraftRoutes.Target): Boolean = false
      override def choiceTask(taskId: Long): Boolean       = taskId == 80
    }
    def choiceFilter(scopedGrant: MobileGrant) = {
      val oauth = mock[MobileOAuthService]
      val users = mock[UserService]
      when(oauth.authenticate(token)).thenReturn(Future.successful(Some(scopedGrant)))
      when(users.retrieve(123)).thenReturn(Some(user))
      val admins = new MobileAdminCheck {
        def isAdmin(u: User): Boolean  = false
        def isAdmin(id: Long): Boolean = false
      }
      new MobileBearerFilter(settings(true), oauth, users, admins, null, null, choices)
    }

    "refuse direct status writes for tasks:write and tasks:edit grants with choice_task_status" in {
      val bare = Seq("1", "2", "5", "6").map(code => bearer(PUT, s"/api/v2/task/80/$code"))
      val extras = Seq(
        bearer(PUT, "/api/v2/task/80/1?requestReview=true"),
        json(bearer(PUT, "/api/v2/task/80/2"), """{"answer":"yes"}""")
      )
      Seq(writeGrant -> bare, editGrant -> (bare ++ extras)).foreach {
        case (scopedGrant, requests) =>
          val filter = choiceFilter(scopedGrant)
          requests.foreach { request =>
            val result = filter.apply(next)(request)
            status(result) mustBe FORBIDDEN
            contentAsJson(result) mustBe play.api.libs.json.Json
              .obj("error" -> "choice_task_status")
          }
      }
    }

    "leave the choice flow, other task routes and other tasks' status writes open" in {
      val filter = choiceFilter(editGrant)
      Seq(
        bearer(GET, "/api/v2/task/80/start"),
        bearer(GET, "/api/v2/task/80/release"),
        bearer(POST, "/api/v2/task/80/skip"),
        json(bearer(POST, "/api/v2/task/80/choice"), "{}"),
        json(bearer(POST, "/api/v2/task/80/comment"), """{"comment":"done"}"""),
        bearer(GET, "/api/v2/task/80/refreshLock"),
        bearer(PUT, "/api/v2/task/81/1"),
        bearer(PUT, "/api/v2/task/81/1?requestReview=true")
      ).foreach(request => contentAsString(filter.apply(next)(request)) mustBe "123")
    }
  }
}

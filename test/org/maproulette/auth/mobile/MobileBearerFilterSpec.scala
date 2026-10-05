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

  private def settings(enabled: Boolean) =
    new MobileOAuthSettings(
      Configuration.from(
        Map(
          "mobileOAuth.enabled"     -> enabled,
          "mobileOAuth.callbackUri" -> "https://example.org/oauth/mobile/callback"
        )
      )
    )
  private def next(request: RequestHeader) =
    Future.successful(
      Results
        .Ok(request.attrs.get(MobileBearerIdentity.UserKey).map(_.id.toString).getOrElse("legacy"))
    )

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
        GET  -> "/api/v2/task/123/start",
        GET  -> "/api/v2/task/123/release",
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
            .withSession("token" -> "legacy-cookie")
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
}

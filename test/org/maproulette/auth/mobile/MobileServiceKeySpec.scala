package org.maproulette.auth.mobile

import akka.actor.ActorSystem
import akka.stream.{Materializer, SystemMaterializer}
import javax.inject.Provider
import org.joda.time.DateTime
import org.maproulette.auth.mobile.guest.MobileGuestAuth
import org.maproulette.framework.model.{Location, OSMProfile, User}
import org.maproulette.framework.service.UserService
import org.mockito.ArgumentMatchers.{any, anyLong, anyString}
import org.mockito.Mockito.{never, verify, when}
import org.scalatest.BeforeAndAfterAll
import org.scalatestplus.mockito.MockitoSugar
import org.scalatestplus.play.PlaySpec
import play.api.Configuration
import play.api.libs.json.Json
import play.api.mvc.{RequestHeader, Results}
import play.api.test.FakeRequest
import play.api.test.Helpers._
import scala.concurrent.{ExecutionContext, Future}

/** The stage service key (SK): stage only, the dedicated super-user, the narrow allowlist. */
class MobileServiceKeySpec extends PlaySpec with MockitoSugar with BeforeAndAfterAll {
  private implicit val system: ActorSystem        = ActorSystem("mobile-service-key-tests")
  private implicit val materializer: Materializer = SystemMaterializer(system).materializer
  private implicit val ec: ExecutionContext       = system.dispatcher
  override def afterAll(): Unit                   = { await(system.terminate()); super.afterAll() }

  private val secret = "0123456789abcdef" * 4
  private val hash   = MobileServiceKey.digest(secret).map("%02x".format(_)).mkString
  private val serviceUser = User(
    31,
    DateTime.now(),
    DateTime.now(),
    OSMProfile(
      MobileServiceKey.ServiceOsmId,
      MobileServiceKey.ServiceName,
      "",
      "",
      Location(0, 0),
      DateTime.now(),
      ""
    ),
    List.empty
  )
  private val admins = new MobileAdminCheck {
    def isAdmin(value: User): Boolean  = value.id == serviceUser.id
    def isAdmin(userId: Long): Boolean = userId == serviceUser.id
    // Challenge 5 is the service user's, 6 someone else's; others don't exist.
    override def ownerOf(challengeId: Long): Option[Long] = challengeId match {
      case 5 => Some(MobileServiceKey.ServiceOsmId)
      case 6 => Some(8909L)
      case _ => None
    }
  }
  private def settings(writeControl: Boolean) =
    new MobileOAuthSettings(
      Configuration.from(
        Map(
          "mobileOAuth.enabled"             -> true,
          "mobileOAuth.callbackUri"         -> "https://mr-stage.osm.lol/oauth/mobile/callback",
          "mobileOAuth.writeControlEnabled" -> writeControl
        )
      )
    )
  private def config(deployment: String, origin: String, sha: String = hash) =
    Configuration.from(
      Map(
        "mobileOAuth.serviceKey.sha256" -> sha,
        "mobileOAuth.deployment"        -> deployment,
        "maproulette.publicOrigin"      -> origin
      )
    )
  private def unused[T]: Provider[T] = () => throw new IllegalStateException("not provisioned")
  private def stageKey(as: User = serviceUser) = new MobileServiceKey {
    private val real = new MobileStageServiceKey(
      config("stage", MobileServiceKey.StageOrigin),
      unused[UserService],
      unused[MobileUserProvisioner]
    )
    def matches(token: String): Boolean = real.matches(token)
    def user(): Option[User]            = Some(as)
  }
  private def filter(serviceKey: MobileServiceKey, writesOn: Boolean = true) = {
    val oauth  = mock[MobileOAuthService]
    val audit  = mock[MobileAdminRepository]
    val policy = mock[MobileWritePolicy]
    when(policy.enabled).thenReturn(writesOn)
    when(oauth.authenticate(anyString())).thenReturn(Future.successful(None))
    val gate = new MobileBearerFilter(
      settings(writeControl = !writesOn),
      oauth,
      mock[UserService],
      admins,
      audit,
      policy,
      MobileGuestAuth.Disabled,
      MobileDrafts.NoDrafts,
      serviceKey
    )
    (gate, audit)
  }
  private def next(request: RequestHeader) =
    Future.successful(
      Results.Ok(
        request.attrs.get(MobileBearerIdentity.UserKey).map(_.id.toString).getOrElse("none") + " " +
          request.attrs.get(MobileBearerIdentity.ScopesKey).map(_.mkString).getOrElse("")
      )
    )
  private def bearer(method: String, path: String, token: String = secret) =
    FakeRequest(method, path).withHeaders("Authorization" -> s"Bearer $token")

  "The service key's environment check" should {
    "accept the secret only on stage at the stage origin" in {
      MobileServiceKey
        .active(Some(hash), Some("stage"), MobileServiceKey.StageOrigin)
        .exists(_.isRight) mustBe true
      MobileServiceKey
        .active(Some(hash), Some("prod"), MobileServiceKey.StageOrigin)
        .exists(_.isLeft) mustBe true
      MobileServiceKey
        .active(Some(hash), Some("stage"), "https://mr-prod.osm.lol")
        .exists(_.isLeft) mustBe true
      MobileServiceKey
        .active(Some(hash), None, MobileServiceKey.StageOrigin)
        .exists(_.isLeft) mustBe true
      MobileServiceKey
        .active(Some("abc"), Some("stage"), MobileServiceKey.StageOrigin)
        .exists(_.isLeft) mustBe true
      MobileServiceKey.active(None, Some("stage"), MobileServiceKey.StageOrigin) mustBe None
    }

    "match the secret and nothing else" in {
      val stage = new MobileStageServiceKey(
        config("stage", MobileServiceKey.StageOrigin),
        unused[UserService],
        unused[MobileUserProvisioner]
      )
      stage.matches(secret) mustBe true
      stage.matches(secret.reverse) mustBe false
      stage.matches(secret + "0") mustBe false
    }

    "ignore a configured secret in prod, so a prod call with it is refused" in {
      val prod = new MobileStageServiceKey(
        config("prod", "https://mr-prod.osm.lol"),
        unused[UserService],
        unused[MobileUserProvisioner]
      )
      prod.matches(secret) mustBe false
      prod.user() mustBe None
      // A prod stack that claims to be stage is still refused by its origin.
      new MobileStageServiceKey(
        config("stage", "https://mr-prod.osm.lol"),
        unused[UserService],
        unused[MobileUserProvisioner]
      ).matches(secret) mustBe false
      val (gate, _) = filter(prod)
      status(gate.apply(next)(bearer(POST, "/api/v2/challenge"))) mustBe UNAUTHORIZED
      status(gate.apply(next)(bearer(GET, "/api/v2/challenge/5"))) mustBe UNAUTHORIZED
    }
  }

  "The service key at the bearer filter" should {
    "reach the import, publish and their reads as the service user's admin grant" in {
      val (gate, _) = filter(stageKey())
      Seq(
        bearer(POST, "/api/v2/challenge"),
        bearer(PUT, "/api/v2/challenge/5"),
        bearer(PUT, "/api/v2/challenge/5/addFileTasks?lineByLine=true&report=true"),
        bearer(GET, "/api/v2/challenge/5"),
        bearer(GET, "/api/v2/challenge/5/tasks"),
        bearer(GET, "/api/v2/mobile-admin/campaigns"),
        bearer(GET, "/api/v2/mobile-admin/projects")
      ).foreach { request =>
        val result = gate.apply(next)(request)
        status(result) mustBe OK
        contentAsString(result) mustBe s"31 ${MobileScopes.Admin}"
      }
    }

    "refuse everything outside the narrow allowlist" in {
      val (gate, _) = filter(stageKey())
      Seq(
        bearer(GET, "/api/v2/mobile-admin/clients"),
        bearer(POST, "/api/v2/mobile-admin/clients"),
        bearer(PUT, "/api/v2/mobile-admin/write-policy"),
        bearer(GET, "/api/v2/mobile-admin/audit"),
        bearer(POST, "/api/v2/mobile-admin/organizers"),
        bearer(POST, "/api/v2/mobile-admin/projects"),
        bearer(PUT, "/api/v2/mobile-admin/projects/1/enabled"),
        bearer(POST, "/api/v2/mobile-admin/wizard/campaigns"),
        bearer(GET, "/oauth/mobile/me"),
        bearer(GET, "/api/v2/task/9"),
        bearer(GET, "/api/v2/task/9/start"),
        bearer(
          PUT,
          "/api/v2/challenge/5/addFileTasks?lineByLine=true&report=true&removeUnmatched=true"
        ),
        bearer(DELETE, "/api/v2/challenge/5")
      ).foreach(request => status(gate.apply(next)(request)) mustBe FORBIDDEN)
    }

    "write only challenges the service user owns, and still read the others" in {
      val (gate, _) = filter(stageKey())
      Seq(
        bearer(PUT, "/api/v2/challenge/6"),
        bearer(PUT, "/api/v2/challenge/6/addFileTasks?lineByLine=true&report=true"),
        bearer(PUT, "/api/v2/challenge/7")
      ).foreach { request =>
        val result = gate.apply(next)(request)
        status(result) mustBe FORBIDDEN
        contentAsJson(result) mustBe Json.obj("error" -> "not_owner")
      }
      Seq(bearer(GET, "/api/v2/challenge/6"), bearer(GET, "/api/v2/challenge/6/tasks"))
        .foreach(request => status(gate.apply(next)(request)) mustBe OK)
    }

    "refuse the secret once the service user is no longer a super-user" in {
      val (gate, _) = filter(stageKey(serviceUser.copy(id = 32)))
      val result    = gate.apply(next)(bearer(POST, "/api/v2/challenge"))
      status(result) mustBe FORBIDDEN
      contentAsJson(result) mustBe Json.obj("error" -> "admin_required")
    }

    "audit its writes tagged as the service key, and work while field writes are off" in {
      val (gate, audit) = filter(stageKey(), writesOn = false)
      status(gate.apply(next)(bearer(POST, "/api/v2/challenge"))) mustBe OK
      verify(audit).record(
        31L,
        "service_key.stock.POST",
        "/api/v2/challenge",
        Some(Json.obj("status" -> 200))
      )
      status(gate.apply(next)(bearer(GET, "/api/v2/challenge/5"))) mustBe OK
      verify(audit, never()).record(
        anyLong(),
        org.mockito.ArgumentMatchers.eq("service_key.stock.GET"),
        anyString(),
        any()
      )
    }

    "leave other bearer tokens to the OAuth grants" in {
      val (gate, _) = filter(stageKey())
      status(gate.apply(next)(bearer(POST, "/api/v2/challenge", "B" * 43))) mustBe UNAUTHORIZED
    }
  }
}

package org.maproulette.provider.choice.claim

import java.time.Instant
import java.util.UUID
import org.maproulette.auth.mobile.guest.MobileGuest
import org.maproulette.auth.mobile.{MobileCorsFilter, MobileFieldRoutes, MobileOAuthSettings}
import org.scalatestplus.play.PlaySpec
import play.api.Configuration
import play.api.libs.json._
import play.api.test.FakeRequest

class ClaimPreviewSpec extends PlaySpec {
  private val now = Instant.parse("2026-11-10T18:00:00Z")
  private def guest(
      claimed: Boolean = false,
      deleted: Boolean = false,
      expires: Instant = Instant.parse("2026-12-01T06:00:00Z")
  ) =
    MobileGuest(
      UUID.randomUUID(),
      "app",
      now,
      expires,
      true,
      false,
      false,
      if (claimed) Some(1L) else None,
      None,
      None,
      if (deleted) Some(now) else None
    )

  private val payload = Json.stringify(
    Json.obj(
      "meta"    -> Json.obj("version" -> 2, "type" -> 3, "choiceVersion" -> 1),
      "element" -> "node/42",
      "questions" -> Json.arr(
        Json.obj(
          "id"     -> "shelter",
          "prompt" -> "Is there a shelter?",
          "expect" -> Json.obj("shelter" -> JsNull),
          "options" -> Json.arr(
            Json.obj("id" -> "yes", "label" -> "Yes", "setTags" -> Json.obj("shelter" -> "yes")),
            Json.obj("id" -> "no", "label"  -> "No", "setTags"  -> Json.obj("shelter" -> "no"))
          )
        )
      )
    )
  )
  private def row(task: Long, body: JsValue, at: String) =
    PreviewRow(
      task,
      7,
      body,
      Instant.parse(at),
      Some(s"Stop $task"),
      Some(40.76),
      Some(-111.89),
      Some(payload),
      "SLC Bus Stops",
      Some("#maproulette #StreetTallySLCStops"),
      Some("https://wiki.openstreetmap.org/wiki/SLC")
    )
  private val rows = Seq(
    row(1, Json.obj("answers" -> Json.obj("shelter" -> "yes")), "2026-11-06T17:00:00Z"),
    row(2, Json.obj("answers" -> Json.obj("shelter" -> "yes")), "2026-11-06T17:05:00Z"),
    row(3, Json.obj("answers" -> Json.obj("shelter" -> "no")), "2026-11-06T17:10:00Z"),
    row(4, Json.obj("outcome" -> "too-hard"), "2026-11-06T17:15:00Z")
  )

  "ClaimPreview" should {
    "tell the token states apart" in {
      ClaimPreview.state(guest(), now) mustBe "active"
      ClaimPreview.state(guest(claimed = true), now) mustBe "claimed"
      ClaimPreview.state(guest(deleted = true, claimed = true), now) mustBe "deleted"
      ClaimPreview.state(guest(expires = now), now) mustBe "expired"
    }

    "summarize answers with prompts, labels and every option counted" in {
      val body = ClaimPreview.body(guest(), rows, writesEnabled = true)
      (body \ "state").as[String] mustBe "active"
      (body \ "pending").as[Int] mustBe 4
      (body \ "writesEnabled").as[Boolean] mustBe true
      val challenge = (body \ "challenges")(0)
      (challenge \ "pending").as[Int] mustBe 4
      (challenge \ "hashtag").as[String] mustBe "#StreetTallySLCStops"
      (challenge \ "wikiUrl").as[String] mustBe "https://wiki.openstreetmap.org/wiki/SLC"
      val first = (body \ "tasks")(0)
      (first \ "label").as[String] mustBe "Stop 1"
      (first \ "lat").as[Double] mustBe 40.76
      ((first \ "answers")(0) \ "prompt").as[String] mustBe "Is there a shelter?"
      ((first \ "answers")(0) \ "answer").as[String] mustBe "Yes"
      (((body \ "tasks")(3) \ "answers")(0) \ "answer").as[String] mustBe "Not sure"
      val summary = (body \ "summary")(0)
      (summary \ "label").as[String] mustBe "Is there a shelter?"
      (summary \ "counts").as[Map[String, Int]] mustBe Map("yes" -> 2, "no" -> 1)
      (summary \ "options").as[Seq[JsObject]].map(o => (o \ "label").as[String]) mustBe
        Seq("Yes", "No")
    }

    "fall back to ids when the task's payload no longer parses" in {
      val broken =
        row(5, Json.obj("answers" -> Json.obj("shelter" -> "no")), "2026-11-06T17:00:00Z")
          .copy(payload = Some("{"), infoLink = Some("http://insecure.example"))
      val body = ClaimPreview.body(guest(), Seq(broken), writesEnabled = false)
      ((body \ "tasks")(0) \ "answers")(0) \ "answer" mustBe JsDefined(JsString("no"))
      (body \ "challenges")(0) \ "wikiUrl" mustBe JsDefined(JsNull)
      ((body \ "summary")(0) \ "counts").as[Map[String, Int]] mustBe Map("no" -> 1)
    }
  }

  "The claim origin" should {
    def settings(extra: Map[String, Any]) = new MobileOAuthSettings(
      Configuration.from(
        Map(
          "mobileOAuth.enabled"     -> true,
          "mobileOAuth.callbackUri" -> "https://mr.example/oauth/mobile/callback"
        ) ++ extra
      )
    )

    "default to the Street Tally host, only while guests are enabled" in {
      settings(Map.empty).claimOrigin mustBe None
      settings(Map("mobileOAuth.guests.enabled" -> true)).claimOrigin mustBe
        Some("https://streettally.osm.lol")
      settings(
        Map(
          "mobileOAuth.guests.enabled"          -> true,
          "mobileOAuth.guests.mail.claimOrigin" -> "https://claim.example/"
        )
      ).claimOrigin mustBe Some("https://claim.example")
      an[IllegalArgumentException] must be thrownBy settings(
        Map(
          "mobileOAuth.guests.enabled"          -> true,
          "mobileOAuth.guests.mail.claimOrigin" -> "https://claim.example/path"
        )
      )
    }

    "get CORS on the claim routes only, and nobody else on the claim API" in {
      import MobileCorsFilter._
      val admin = Some("https://admin.example")
      val claim = Some("https://claim.example")
      def from(origin: String, method: String, path: String, preflight: Option[String] = None) =
        decide(
          enabled = true,
          admin,
          claim,
          preflight
            .fold(FakeRequest(method, path))(m =>
              FakeRequest("OPTIONS", path).withHeaders("Access-Control-Request-Method" -> m)
            )
            .withHeaders("Origin" -> origin)
        )
      from("https://claim.example", "POST", "/api/v2/mobile-claim/preview") mustBe
        AllowOrigin("https://claim.example")
      from("https://claim.example", "POST", "/oauth/mobile/token") mustBe
        AllowOrigin("https://claim.example")
      from("https://claim.example", "GET", "/api/v2/mobile-claim/9") mustBe
        AllowOrigin("https://claim.example")
      from("https://claim.example", "OPTIONS", "/api/v2/mobile-claim", Some("POST")) match {
        case Preflight(result) => result.header.status mustBe 204
        case other             => fail(other.toString)
      }
      from("https://claim.example", "OPTIONS", "/api/v2/mobile-claim", Some("DELETE")) match {
        case Preflight(result) => result.header.status mustBe 403
        case other             => fail(other.toString)
      }
      from("https://claim.example", "GET", "/api/v2/mobile-admin/clients") mustBe Plain
      from("https://evil.example", "POST", "/api/v2/mobile-claim/preview") mustBe Plain
      from("https://evil.example", "OPTIONS", "/api/v2/mobile-claim/preview", Some("POST")) match {
        case Preflight(result) => result.header.status mustBe 403
        case other             => fail(other.toString)
      }
      from("https://admin.example", "POST", "/oauth/mobile/token") mustBe
        AllowOrigin("https://admin.example")
      from("https://claim.example", "GET", "/api/v2/challenges/extendedFind") mustBe Delegate
    }
  }

  "The claim routes" should {
    "need a write grant for the claim and keep guests out of both" in {
      import org.maproulette.auth.mobile.{MobileGuestRoutes, MobileReadRoutes, MobileWriteRoutes}
      MobileWriteRoutes.permits("POST", "/api/v2/mobile-claim") mustBe true
      MobileWriteRoutes.takesBody("POST", "/api/v2/mobile-claim") mustBe true
      MobileReadRoutes.permits("GET", "/api/v2/mobile-claim/9") mustBe true
      MobileReadRoutes.bare("GET", "/api/v2/mobile-claim/9") mustBe true
      MobileGuestRoutes.permits("GET", "/api/v2/mobile-claim/9") mustBe false
      MobileGuestRoutes.permits("POST", "/api/v2/mobile-claim") mustBe false
      MobileGuestRoutes.guestOnlyWhenDisabled("GET", "/api/v2/mobile-claim/9") mustBe false
      MobileFieldRoutes.permitsWhenDisabled(FakeRequest("POST", "/api/v2/mobile-claim")) mustBe false
    }

    "take a claim token or a guest id and secret, nothing else" in {
      val service = new ClaimService(null, null)
      val token   = "a" * 43
      service.credential(Json.obj("claimToken" -> token), "app") mustBe
        Some(ByToken(org.maproulette.auth.mobile.MobileSecrets.hash(token)))
      val id = UUID.randomUUID()
      service.credential(Json.obj("guestId" -> id.toString, "guestSecret" -> token), "app") mustBe
        Some(BySecret(id, org.maproulette.auth.mobile.MobileSecrets.hash(token), "app"))
      service.credential(Json.obj("claimToken" -> token, "x" -> 1), "app") mustBe None
      service.credential(Json.obj("claimToken" -> "short"), "app") mustBe None
      service.credential(Json.obj("guestId"    -> "nope", "guestSecret" -> token), "app") mustBe None
    }
  }
}

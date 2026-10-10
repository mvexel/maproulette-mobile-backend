package org.maproulette.auth.mobile

import org.scalatestplus.play.PlaySpec
import play.api.Configuration

class MobileScopesSpec extends PlaySpec {
  "MobileScopes.parse" should {
    "never accept guest in a grant scope" in {
      MobileScopes.parse("guest") mustBe None
      MobileScopes.parse("tasks:read guest") mustBe None
      MobileScopes.parse("tasks:read tasks:write") mustBe Some(Set("tasks:read", "tasks:write"))
    }
  }

  "MobileScopes.parseClient" should {
    "accept guest next to app scopes" in {
      MobileScopes.parseClient("tasks:read guest") mustBe Some(Set("tasks:read", "guest"))
      MobileScopes.parseClient("tasks:read tasks:write osm:tagfix guest") mustBe
        Some(Set("tasks:read", "tasks:write", "osm:tagfix", "guest"))
    }
    "reject guest alone, on admin clients, or twice" in {
      MobileScopes.parseClient("guest") mustBe None
      MobileScopes.parseClient("mobile:admin guest") mustBe None
      MobileScopes.parseClient("tasks:read guest guest") mustBe None
    }
    "parse clients without guest like grants" in {
      MobileScopes.parseClient("mobile:admin") mustBe Some(Set("mobile:admin"))
      MobileScopes.parseClient("tasks:write") mustBe None
    }
    "format guest last" in {
      MobileScopes.format(Set("guest", "tasks:write", "tasks:read")) mustBe
        "tasks:read tasks:write guest"
    }
  }

  "MobileOAuthSettings.guestsEnabled" should {
    val base = Map(
      "mobileOAuth.enabled"     -> true,
      "mobileOAuth.callbackUri" -> "https://backend.example/oauth/mobile/callback"
    )
    "default to off" in {
      new MobileOAuthSettings(Configuration.from(base)).guestsEnabled mustBe false
    }
    "follow mobileOAuth.guests.enabled" in {
      new MobileOAuthSettings(Configuration.from(base + ("mobileOAuth.guests.enabled" -> true))).guestsEnabled mustBe
        true
    }
    "stay off while the provider is off" in {
      new MobileOAuthSettings(
        Configuration.from(
          Map("mobileOAuth.enabled" -> false, "mobileOAuth.guests.enabled" -> true)
        )
      ).guestsEnabled mustBe false
    }
    "accept a guest-capable config client" in {
      val settings = new MobileOAuthSettings(
        Configuration.from(
          base + ("mobileOAuth.clients" -> Seq(
            Map(
              "id"           -> "app",
              "name"         -> "App",
              "redirectUris" -> Seq("org.example.app:/cb"),
              "scopes"       -> Seq("tasks:read", "guest")
            )
          ))
        )
      )
      settings.clients("app").scopes mustBe Set("tasks:read", "guest")
    }
  }
}

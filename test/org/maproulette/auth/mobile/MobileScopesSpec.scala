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

  "MobileScopes tasks:edit" should {
    "require tasks:write, refuse osm:tagfix and format in canonical order" in {
      MobileScopes.parse("tasks:edit tasks:write tasks:read") mustBe
        Some(Set("tasks:read", "tasks:write", "tasks:edit"))
      MobileScopes.format(Set("tasks:edit", "tasks:read", "tasks:write")) mustBe
        "tasks:read tasks:write tasks:edit"
      MobileScopes.parse("tasks:read tasks:edit") mustBe None
      MobileScopes.parse("tasks:edit") mustBe None
      MobileScopes.parse("tasks:read tasks:write tasks:edit osm:tagfix") mustBe None
      MobileScopes.parse("mobile:admin tasks:edit") mustBe None
      MobileScopes.osmScope("tasks:read tasks:write tasks:edit") mustBe "read_prefs"
    }
  }

  "MobileOAuthSettings.validRedirect" should {
    "accept an http loopback IP literal with a port and path" in {
      Seq(
        "http://127.0.0.1:8111/oauth_authorization",
        "http://[::1]:8111/oauth_authorization",
        "https://app.example/callback",
        "org.example.app:/callback"
      ).foreach(uri => MobileOAuthSettings.validRedirect(uri) mustBe true)
    }
    "refuse other http callbacks and loopbacks without a port, path or clean URI" in {
      Seq(
        "http://localhost:8111/oauth_authorization",
        "http://127.0.0.2:8111/oauth_authorization",
        "http://example.org:8111/oauth_authorization",
        "http://127.0.0.1/oauth_authorization",
        "http://127.0.0.1:8111",
        "http://127.0.0.1:8111/cb?x=1",
        "http://127.0.0.1:8111/cb#x",
        "http://user@127.0.0.1:8111/cb",
        "http://127.0.0.1.example.org:8111/cb",
        "ftp://127.0.0.1:8111/cb"
      ).foreach(uri => MobileOAuthSettings.validRedirect(uri) mustBe false)
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
    "list every valid client scope set in canonical order" in {
      MobileScopes.clientScopeSets mustBe Seq(
        Seq("tasks:read"),
        Seq("mobile:admin"),
        Seq("mobile:organize"),
        Seq("tasks:read", "tasks:write"),
        Seq("tasks:read", "guest"),
        Seq("tasks:read", "tasks:write", "tasks:edit"),
        Seq("tasks:read", "tasks:write", "osm:tagfix"),
        Seq("tasks:read", "tasks:write", "guest"),
        Seq("tasks:read", "tasks:write", "tasks:edit", "guest"),
        Seq("tasks:read", "tasks:write", "osm:tagfix", "guest")
      )
      MobileScopes.clientScopeSets.map(_.mkString(" ")).foreach { scope =>
        MobileScopes.parseClient(scope).map(MobileScopes.format) mustBe Some(scope)
      }
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

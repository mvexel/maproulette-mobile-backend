package org.maproulette.auth.mobile

import com.typesafe.config.ConfigFactory
import java.io.File
import org.scalatestplus.play.PlaySpec
import play.api.Configuration

class MobileFieldConfigSpec extends PlaySpec {
  "The production-OSM field config" should {
    "bind each callback and admin client to its own host with writes off" in {
      val values = ConfigFactory.parseMap(
        java.util.Map.of(
          "MR_PUBLIC_ORIGIN",
          "https://mr-stage.osm.lol",
          "MOBILE_OAUTH_CALLBACK_URI",
          "https://mr-stage.osm.lol/oauth/mobile/callback",
          "MR_MOBILE_ADMIN_ORIGIN",
          "https://admin.mr-stage.osm.lol",
          "MR_MOBILE_ADMIN_CALLBACK_URI",
          "https://admin.mr-stage.osm.lol/callback",
          "MR_OSM_OAUTH2SCOPE",
          "read_prefs"
        )
      )
      val config = ConfigFactory
        .parseFile(new File("conf/mobile-field.conf"))
        .withFallback(values)
        .resolve()
      val settings = new MobileOAuthSettings(Configuration(config))
      config.getString("maproulette.publicOrigin") mustBe "https://mr-stage.osm.lol"
      config.getInt("maproulette.task.max_tasks_per_challenge") mustBe 5000
      config.getString("osm.server") mustBe "https://www.openstreetmap.org"
      settings.writeControlEnabled mustBe true
      settings.adminOrigin mustBe Some("https://admin.mr-stage.osm.lol")
      settings.clients.keySet mustBe Set(
        "maproulette-android-example",
        "maproulette-ios-example",
        "maproulette-mobile-admin"
      )
      settings.clients("maproulette-android-example").scopes mustBe Set("tasks:read")
      settings.clients("maproulette-mobile-admin").redirectUris mustBe
        Set("https://admin.mr-stage.osm.lol/callback")
    }

    "trust only loopback and private ranges as proxies, so X-Forwarded-For is the client" in {
      Seq("conf/mobile-field.conf", "conf/mobile-staging.conf").foreach { file =>
        ConfigFactory
          .parseFile(new File(file))
          .getStringList("play.http.forwarded.trustedProxies") mustBe java.util.List.of(
          "127.0.0.1",
          "::1",
          "10.0.0.0/8",
          "172.16.0.0/12",
          "192.168.0.0/16",
          "fc00::/7"
        )
      }
    }
  }

  "The selected stage pending-guest config" should {
    def stageConfig(guests: Option[Boolean]): com.typesafe.config.Config = {
      val values = ConfigFactory.parseMap(
        java.util.Map.of(
          "MR_PUBLIC_ORIGIN",
          "https://mr-stage.osm.lol",
          "MOBILE_OAUTH_CALLBACK_URI",
          "https://mr-stage.osm.lol/oauth/mobile/callback",
          "MR_MOBILE_ADMIN_ORIGIN",
          "https://admin.mr-stage.osm.lol",
          "MR_MOBILE_ADMIN_CALLBACK_URI",
          "https://admin.mr-stage.osm.lol/callback"
        )
      )
      val optIn = guests.fold(ConfigFactory.empty())(enabled =>
        ConfigFactory.parseString(s"MR_MOBILE_GUESTS_ENABLED=$enabled")
      )
      ConfigFactory
        .parseFile(new File("conf/mobile-field-stage-pending.conf"))
        .withFallback(optIn)
        .withFallback(values)
        .resolve()
    }

    "keep guests off unless explicitly opted in, retaining audited write control" in {
      Seq(None, Some(false), Some(true)).foreach { optIn =>
        val settings = new MobileOAuthSettings(Configuration(stageConfig(optIn)))
        settings.guestsEnabled mustBe optIn.getOrElse(false)
        settings.allowTaskWrites mustBe true
        settings.writeControlEnabled mustBe true
      }
    }

    "seed only read and guest capability for the exact native callback" in {
      val settings = new MobileOAuthSettings(Configuration(stageConfig(Some(true))))
      val native   = settings.clients("maproulette-ios-choice")
      native.redirectUris mustBe Set("lol.osm.maproulette.choice:/oauth2redirect")
      native.scopes mustBe Set(MobileScopes.Read, MobileScopes.Guest)
      settings.allowedScopes(native.id, "tasks:read") mustBe Some(Set(MobileScopes.Read))
      settings.allowedScopes(native.id, "tasks:read guest") mustBe None
      settings.allowedScopes(native.id, "tasks:read tasks:write") mustBe None
      settings.allowedScopes(native.id, "tasks:read tasks:write osm:tagfix") mustBe None
      stageConfig(None).getString("mobileOAuth.guests.mail.provider") mustBe "none"
    }
  }
}

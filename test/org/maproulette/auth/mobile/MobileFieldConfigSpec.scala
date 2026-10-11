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
}

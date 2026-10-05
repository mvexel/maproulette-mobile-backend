package org.maproulette.auth.mobile

import javax.inject.{Inject, Singleton}
import java.net.URI
import play.api.Configuration

case class MobileClient(id: String, name: String, redirectUris: Set[String])

/** The new provider is inert unless explicitly configured and enabled. */
@Singleton
class MobileOAuthSettings @Inject() (configuration: Configuration) {
  val enabled: Boolean = configuration.getOptional[Boolean]("mobileOAuth.enabled").getOrElse(false)
  val scope: String    = "tasks:read"
  val callbackUri: String =
    configuration.getOptional[String]("mobileOAuth.callbackUri").getOrElse("")
  val accessSeconds: Long =
    configuration.getOptional[Long]("mobileOAuth.accessSeconds").getOrElse(900L)
  val refreshSeconds: Long =
    configuration.getOptional[Long]("mobileOAuth.refreshSeconds").getOrElse(2592000L)
  val interactionSeconds: Long = 600L
  val codeSeconds: Long        = 120L
  val clients: Map[String, MobileClient] =
    if (!enabled) Map.empty
    else {
      val entries =
        configuration.getOptional[Seq[Configuration]]("mobileOAuth.clients").getOrElse(Seq.empty)
      val parsed = entries.map { entry =>
        val id        = entry.get[String]("id")
        val redirects = entry.get[Seq[String]]("redirectUris").toSet
        require(id.matches("[A-Za-z0-9._-]{1,100}"), "Invalid mobile client ID")
        require(
          redirects.nonEmpty && redirects.forall(validRedirect),
          "Invalid mobile client redirect URI"
        )
        MobileClient(id, entry.get[String]("name"), redirects)
      }
      require(parsed.map(_.id).distinct.size == parsed.size, "Duplicate mobile client IDs")
      parsed.map(client => client.id -> client).toMap
    }
  if (enabled) {
    val uri      = new URI(callbackUri)
    val loopback = Set("localhost", "127.0.0.1", "[::1]").contains(uri.getHost)
    val allowHttp =
      configuration.getOptional[Boolean]("mobileOAuth.allowInsecureLoopback").getOrElse(false)
    require(
      uri.getScheme == "https" || (allowHttp && loopback && uri.getScheme == "http"),
      "Invalid mobile callback URI"
    )
    require(
      uri.getHost != null && uri.getUserInfo == null && uri.getFragment == null && uri.getQuery == null,
      "Invalid mobile callback URI"
    )
    require(
      accessSeconds > 0 && accessSeconds <= 3600 && refreshSeconds >= accessSeconds && refreshSeconds <= 7776000,
      "Invalid mobile credential lifetime"
    )
  }
  def secureCookie: Boolean = callbackUri.startsWith("https://")

  private def validRedirect(value: String): Boolean =
    scala.util
      .Try {
        val uri    = new URI(value)
        val scheme = Option(uri.getScheme).getOrElse("")
        val web    = scheme == "https" && uri.getHost != null
        val native = scheme.contains(".") && scheme.matches("[a-z][a-z0-9+.-]*")
        (web || native) && uri.getFragment == null && uri.getUserInfo == null && uri.getQuery == null
      }
      .getOrElse(false)
}

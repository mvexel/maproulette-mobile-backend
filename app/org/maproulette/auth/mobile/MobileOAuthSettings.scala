package org.maproulette.auth.mobile

import javax.inject.{Inject, Singleton}
import java.net.URI
import play.api.Configuration

case class MobileClient(
    id: String,
    name: String,
    redirectUris: Set[String],
    scopes: Set[String] = Set(MobileScopes.Read)
)

/**
  * OAuth scope sets. Every grant includes `tasks:read`; `tasks:write` is an optional addition, and
  * `osm:tagfix` (apply choice answers to OSM) is only valid together with `tasks:write`.
  */
object MobileScopes {
  val Read   = "tasks:read"
  val Write  = "tasks:write"
  val TagFix = "osm:tagfix"
  // Canonical order for stored and returned scope strings.
  private val supported = Seq(Read, Write, TagFix)

  /** Strict RFC 6749 scope parsing: single-space separated, known, unique, including read. */
  def parse(value: String): Option[Set[String]] = {
    val items = value.split(" ", -1).toSeq
    if (items.forall(supported.contains) && items.distinct.size == items.size && items.contains(
          Read
        ) && (!items.contains(TagFix) || items.contains(Write))) Some(items.toSet)
    else None
  }

  def format(scopes: Set[String]): String = supported.filter(scopes.contains).mkString(" ")

  /** The OSM scope a login asks for: write access only for grants that include osm:tagfix. */
  def osmScope(scope: String): String =
    if (parse(scope).exists(_.contains(TagFix))) "read_prefs write_api" else "read_prefs"
}

/** The new provider is inert unless explicitly configured and enabled. */
@Singleton
class MobileOAuthSettings @Inject() (configuration: Configuration) {
  private val logger   = play.api.Logger(getClass)
  val enabled: Boolean = configuration.getOptional[Boolean]("mobileOAuth.enabled").getOrElse(false)

  /**
    * AES-256 key for stored OSM tokens (`MR_MOBILE_OSM_TOKEN_KEY`: standard base64 of exactly 32
    * bytes, e.g. `openssl rand -base64 32`). Unset, empty or malformed means no key: new
    * `osm:tagfix` authorizations are refused and choice edits answer 503, while existing grants,
    * reads, lifecycle writes and startup are unaffected (fail closed).
    */
  val osmTokenKey: Option[Array[Byte]] = {
    val raw =
      configuration.getOptional[String]("mobileOAuth.osmTokenKey").map(_.trim).filter(_.nonEmpty)
    val key = raw
      .flatMap(value => scala.util.Try(java.util.Base64.getDecoder.decode(value)).toOption)
      .filter(_.length == 32)
    if (enabled && raw.isDefined && key.isEmpty)
      logger.error(
        "mobileOAuth.osmTokenKey (MR_MOBILE_OSM_TOKEN_KEY) is malformed: expected base64 of 32 bytes; osm:tagfix is disabled"
      )
    key
  }
  def tagFixAvailable: Boolean = osmTokenKey.isDefined
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
        val scopes = MobileScopes
          .parse(
            entry.getOptional[Seq[String]]("scopes").getOrElse(Seq(MobileScopes.Read)).mkString(" ")
          )
          .getOrElse(throw new IllegalArgumentException("Invalid mobile client scopes"))
        if (scopes.contains(MobileScopes.TagFix) && osmTokenKey.isEmpty)
          logger.warn(
            s"Mobile client $id lists osm:tagfix but no valid mobileOAuth.osmTokenKey " +
              "(MR_MOBILE_OSM_TOKEN_KEY) is set; new osm:tagfix sign-ins are refused"
          )
        MobileClient(id, entry.get[String]("name"), redirects, scopes)
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

  /** The grant's scopes while its client remains configured to allow all of them. */
  def allowedScopes(clientId: String, scope: String): Option[Set[String]] =
    for {
      client <- clients.get(clientId)
      scopes <- MobileScopes.parse(scope) if scopes.subsetOf(client.scopes)
    } yield scopes

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

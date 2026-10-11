package org.maproulette.auth.mobile

import javax.inject.{Inject, Singleton}
import java.net.URI
import play.api.Configuration

case class MobileClient(
    id: String,
    name: String,
    redirectUris: Set[String],
    scopes: Set[String] = Set(MobileScopes.Read),
    enabled: Boolean = true
)

/**
  * OAuth scope sets. An app grant includes `tasks:read`; `tasks:write` is an optional addition, and
  * `osm:tagfix` (apply choice answers to OSM) is only valid together with `tasks:write`.
  * `mobile:admin` stands alone: an admin grant has no app scopes, so it reaches only the admin
  * allowlist, and app grants never reach it. `guest` is never part of a grant: it is a client
  * capability (the client may register guests, see [[org.maproulette.auth.mobile.guest]]) and the
  * scope of guest access tokens, which are not OAuth grants.
  */
object MobileScopes {
  val Read   = "tasks:read"
  val Write  = "tasks:write"
  val TagFix = "osm:tagfix"
  val Admin  = "mobile:admin"
  val Guest  = "guest"
  // Canonical order for stored and returned scope strings.
  private val supported = Seq(Read, Write, TagFix, Admin, Guest)

  /** Strict RFC 6749 scope parsing: single-space separated, known, unique; read or admin alone. */
  def parse(value: String): Option[Set[String]] = {
    val items = value.split(" ", -1).toSeq
    val known = items.forall(item => item != Guest && supported.contains(item)) &&
      items.distinct.size == items.size
    val app = items.contains(Read) && !items.contains(Admin) &&
      (!items.contains(TagFix) || items.contains(Write))
    if (known && (app || items == Seq(Admin))) Some(items.toSet) else None
  }

  /** A client's scopes: a valid grant scope set, plus `guest` on app clients only. */
  def parseClient(value: String): Option[Set[String]] = {
    val items = value.split(" ", -1).toSeq
    if (items.count(_ == Guest) != 1) parse(value)
    else parse(items.filterNot(_ == Guest).mkString(" ")).filterNot(isAdmin).map(_ + Guest)
  }

  def isAdmin(scopes: Set[String]): Boolean = scopes.contains(Admin)
  def isAdmin(scope: String): Boolean       = parse(scope).exists(isAdmin)

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
  val allowTaskWrites: Boolean =
    configuration.getOptional[Boolean]("mobileOAuth.allowTaskWrites").getOrElse(true)
  val writeControlEnabled: Boolean =
    configuration.getOptional[Boolean]("mobileOAuth.writeControlEnabled").getOrElse(false)

  /**
    * Deferred sign-up: clients with the `guest` scope may register guests that answer choice tasks
    * before signing in. Off by default; off, the guest routes answer 404.
    */
  val guestsEnabled: Boolean =
    enabled && configuration.getOptional[Boolean]("mobileOAuth.guests.enabled").getOrElse(false)

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

  /**
    * Clients listed in `mobileOAuth.clients`. They seed the `mobile_oauth_clients` table; at
    * runtime clients are read through [[MobileClientRegistry]], not from here.
    */
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
          redirects.nonEmpty && redirects.size <= 10 &&
            redirects.forall(MobileOAuthSettings.validRedirect),
          "Invalid mobile client redirect URI"
        )
        val name = entry.get[String]("name")
        // The limits of mobile_oauth_clients (evolution 131), so a bad entry fails at startup.
        require(name.nonEmpty && name.length <= 200, "Invalid mobile client name")
        val scopes = MobileScopes
          .parseClient(
            entry.getOptional[Seq[String]]("scopes").getOrElse(Seq(MobileScopes.Read)).mkString(" ")
          )
          .getOrElse(throw new IllegalArgumentException("Invalid mobile client scopes"))
        if (scopes.contains(MobileScopes.TagFix) && osmTokenKey.isEmpty)
          logger.warn(
            s"Mobile client $id lists osm:tagfix but no valid mobileOAuth.osmTokenKey " +
              "(MR_MOBILE_OSM_TOKEN_KEY) is set; new osm:tagfix sign-ins are refused"
          )
        MobileClient(
          id,
          name,
          redirects,
          scopes,
          entry.getOptional[Boolean]("enabled").getOrElse(true)
        )
      }
      require(parsed.map(_.id).distinct.size == parsed.size, "Duplicate mobile client IDs")
      parsed.map(client => client.id -> client).toMap
    }
  private val allowHttp =
    configuration.getOptional[Boolean]("mobileOAuth.allowInsecureLoopback").getOrElse(false)
  private def loopback(uri: URI) = Set("localhost", "127.0.0.1", "[::1]").contains(uri.getHost)

  /**
    * The one browser origin of the admin web app (`MR_MOBILE_ADMIN_ORIGIN`), e.g.
    * `https://admin.mr-dev.osm.lol`. Unset means no admin CORS; see [[MobileCorsFilter]].
    */
  private def origin(path: String, value: String): String = {
    val valid = scala.util
      .Try {
        val uri = new URI(value)
        (uri.getScheme == "https" || (allowHttp && loopback(uri) && uri.getScheme == "http")) &&
        uri.getHost != null && value == s"${uri.getScheme}://${uri.getRawAuthority}" &&
        uri.getUserInfo == null && value == value.toLowerCase(java.util.Locale.ROOT)
      }
      .getOrElse(false)
    require(valid, s"Invalid $path: expected https://host[:port]")
    value
  }

  val adminOrigin: Option[String] =
    if (!enabled) None
    else
      configuration
        .getOptional[String]("mobileOAuth.adminOrigin")
        .map(_.trim)
        .filter(_.nonEmpty)
        .map(origin("mobileOAuth.adminOrigin", _))

  /**
    * Browser origin of the claim page (deferred sign-up), which gets credential-free CORS on the
    * claim and token routes only. The same setting builds the emailed links. Guests off: none.
    */
  val claimOrigin: Option[String] =
    if (!guestsEnabled) None
    else
      Some(
        origin(
          "mobileOAuth.guests.mail.claimOrigin",
          configuration
            .getOptional[String]("mobileOAuth.guests.mail.claimOrigin")
            .map(_.trim.stripSuffix("/"))
            .filter(_.nonEmpty)
            .getOrElse(MobileOAuthSettings.DefaultClaimOrigin)
        )
      )

  if (enabled) {
    val uri = new URI(callbackUri)
    require(
      uri.getScheme == "https" || (allowHttp && loopback(uri) && uri.getScheme == "http"),
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

  /** The grant's scopes while its configured client allows all of them (config clients only). */
  def allowedScopes(clientId: String, scope: String): Option[Set[String]] =
    MobileClientRegistry.allowedScopes(clients.get(clientId), scope)
}

object MobileOAuthSettings {
  val DefaultClaimOrigin = "https://streettally.osm.lol"

  /**
    * A registered app callback: https with a host, or a reverse-domain custom scheme. No fragment,
    * query or user info, because the code is appended as the query.
    */
  def validRedirect(value: String): Boolean =
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

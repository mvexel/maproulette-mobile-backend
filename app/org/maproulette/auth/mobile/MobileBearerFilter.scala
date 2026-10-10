package org.maproulette.auth.mobile

import akka.stream.Materializer
import javax.inject.Inject
import org.maproulette.auth.mobile.guest.{MobileGuest, MobileGuestAuth}
import org.maproulette.framework.model.User
import org.maproulette.framework.service.UserService
import org.maproulette.session.SessionManager
import play.api.libs.json.Json
import play.api.libs.typedmap.TypedKey
import play.api.mvc.{Filter, RequestHeader, Result, Results}
import scala.concurrent.{ExecutionContext, Future}

object MobileBearerIdentity {
  val UserKey: TypedKey[User] = TypedKey[User]("mobile-oauth-user")
  // The grant's scopes and family, for controllers that need more than tasks:write.
  val ScopesKey: TypedKey[Set[String]] = TypedKey[Set[String]]("mobile-oauth-scopes")
  val FamilyKey: TypedKey[String]      = TypedKey[String]("mobile-oauth-family")
  val ClientKey: TypedKey[String]      = TypedKey[String]("mobile-oauth-client")
  // Set instead of UserKey for a deferred sign-up guest; a guest is never a MapRoulette user.
  val GuestKey: TypedKey[MobileGuest] = TypedKey[MobileGuest]("mobile-guest")
}

/**
  * Routes for `mobile:admin` grants, and only for them: the fork's admin API, plus the exact stock
  * routes the admin app's challenge creator needs. Nothing else, not even the app read routes.
  */
object MobileAdminRoutes {
  private val challenge = "/api/v2/challenge/[0-9]+"
  val Methods           = Set("GET", "POST", "PATCH", "PUT", "DELETE")

  def adminApi(path: String): Boolean =
    path.matches("/api/v2/mobile-admin(/[A-Za-z0-9_-][A-Za-z0-9._-]*)+")

  /** Method and path only; [[permits]] also checks the `addFileTasks` query. */
  def matches(method: String, path: String): Boolean =
    if (adminApi(path)) Methods.contains(method)
    else
      method match {
        case "GET" =>
          path == "/oauth/mobile/me" || path.matches(challenge) ||
            path.matches(s"$challenge/tasks") || path.matches("/api/v2/task/[0-9]+")
        case "POST" => path == "/api/v2/challenge"
        case "PUT"  => path.matches(challenge) || path.matches(s"$challenge/addFileTasks")
        case _      => false
      }

  /** `addFileTasks` only line by line with a per-line report: never removeUnmatched. */
  def permits(method: String, path: String, query: Map[String, Seq[String]]): Boolean =
    matches(method, path) && (!path.endsWith("/addFileTasks") ||
      query == Map("lineByLine" -> Seq("true"), "report" -> Seq("true")))

  /** Stock writes, which the bearer filter records in the admin audit log. */
  def stockWrite(method: String, path: String): Boolean = method != "GET" && !adminApi(path)
}

/** An explicit route allowlist: several legacy GET routes mutate data or disclose API keys. */
object MobileReadRoutes {
  private val number = "[+-]?[0-9]+(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?"
  private val box    = s"$number/$number/$number/$number"
  private val reads = Seq(
    "/api/v2/challenges/extendedFind",
    "/api/v2/challenge/[0-9]+",
    "/api/v2/challenge/[0-9]+/tags",
    "/api/v2/challenge/[0-9]+/tasks",
    "/api/v2/task/[0-9]+",
    "/api/v2/task/[0-9]+/choice/check",
    s"/api/v2/tasks/box/$box",
    "/oauth/mobile/me"
  ).map(_.r)

  def permits(method: String, path: String): Boolean =
    (method == "GET" && reads.exists(_.pattern.matcher(path).matches())) ||
      (method == "PUT" && path.matches(s"/api/v2/markers/box/$box"))

  /** Reads that take no query string or body. */
  def bare(method: String, path: String): Boolean =
    method == "GET" && path.matches("/api/v2/task/[0-9]+/choice/check")
}

/**
  * Routes for guest access tokens (deferred sign-up), and only for them: the app discovery reads,
  * the choice check, pending answers and the guest's own routes. No task writes, no identity
  * endpoint, no admin routes.
  */
object MobileGuestRoutes {
  private val pending = "/api/v2/task/[0-9]+/choice/pending"
  private val list    = "/api/v2/mobile-guest/pending"

  /** Routes that only guests reach. None of them writes OSM or a task status. */
  def own(method: String, path: String): Boolean = method match {
    case "GET"    => path == "/api/v2/mobile-guest/me" || path == list
    case "POST"   => path.matches(pending)
    case "DELETE" => path == "/api/v2/mobile-guest" || path.matches(pending)
    case "PUT"    => email(method, path)
    case _        => false
  }

  /** Setting the claim email or the reminder switch (B4): a JSON body, no query string. */
  def email(method: String, path: String): Boolean =
    method == "PUT" && (path == "/api/v2/mobile-guest/email" || path == "/api/v2/mobile-guest/reminders")

  /** The emailed links' routes (B4): a claim token in a JSON body, no credential. */
  def claimToken(method: String, path: String): Boolean =
    method == "POST" && (path == "/api/v2/mobile-claim/delete" ||
      path == "/api/v2/mobile-claim/stop-reminders")

  def permits(method: String, path: String): Boolean =
    own(method, path) || (MobileReadRoutes.permits(method, path) && path != "/oauth/mobile/me")

  /** The one guest route with a body: a pending answer, shaped like a choice submission. */
  def takesBody(method: String, path: String): Boolean = method == "POST" && path.matches(pending)

  /** Whether the request has the shape its guest route allows. */
  def acceptable(request: RequestHeader): Boolean =
    if (takesBody(request.method, request.path)) MobileWriteRoutes.acceptableBody(request)
    else if (email(request.method, request.path)) request.rawQueryString.isEmpty
    else if (request.method == "GET" && request.path == list)
      !request.hasBody && request.queryString.forall {
        case (key, values) => Set("limit", "after").contains(key) && values.size == 1
      }
    else if (own(request.method, request.path) || MobileReadRoutes.bare(
               request.method,
               request.path
             ))
      request.rawQueryString.isEmpty && !request.hasBody
    else true

  /** Allowed while field writes are off, for guest tokens only (checked after authentication). */
  def guestOnlyWhenDisabled(method: String, path: String): Boolean =
    MobileReadRoutes.bare(method, path)
}

/**
  * Task lifecycle writes for grants with `tasks:write`. Only the bare route is allowed: no query
  * string (which would carry requestReview or tags) and no body (completion responses). Status
  * codes are limited to Fixed, False positive, Already fixed and Too hard. Mobile clients lock late
  * (start immediately before the status write), so refreshLock is deliberately not allowed.
  */
object MobileWriteRoutes {
  private val task       = "/api/v2/task/[0-9]+"
  val MaxChoiceBodyBytes = 2048

  def permits(method: String, path: String): Boolean = method match {
    case "GET"  => path.matches(s"$task/(start|release)")
    case "POST" => path.matches(s"$task/(skip|choice)")
    case "PUT"  => path.matches(s"$task/[1256]")
    case _      => false
  }

  /** The only write that carries a body: a small JSON choice submission. */
  def takesBody(method: String, path: String): Boolean =
    method == "POST" && path.matches(s"$task/choice")

  def acceptableBody(request: RequestHeader): Boolean = {
    val contentType = request.headers
      .get("Content-Type")
      .map(_.toLowerCase(java.util.Locale.ROOT).replace(" ", ""))
    val length = request.headers.getAll("Content-Length") match {
      case Seq(value) if value.matches("[0-9]{1,5}") => Some(value.toInt)
      case _                                         => None
    }
    request.rawQueryString.isEmpty &&
    request.headers.get("Transfer-Encoding").isEmpty &&
    contentType.exists(Set("application/json", "application/json;charset=utf-8").contains) &&
    length.exists(value => value > 0 && value <= MaxChoiceBodyBytes)
  }
}

/** During a field deployment's disabled phase, expose only the mobile login,
  * admin control, and known read routes. This also closes legacy session and
  * API-key write paths while the policy is off.
  */
object MobileFieldRoutes {
  private val discovery = Set(
    "/api/v2/challenges/tags",
    "/api/v2/challenges/find",
    "/api/v2/challenges/search",
    "/api/v2/challenges/extendedFind"
  )

  def permitsWhenDisabled(request: RequestHeader): Boolean = {
    // Challenge preparation is an admin action, not a mapper task or OSM edit.
    // The bearer filter below validates the grant and super-user before forwarding.
    val bearer = request.headers
      .getAll("Authorization")
      .exists(_.toLowerCase(java.util.Locale.ROOT).startsWith("bearer"))
    val adminSetup = bearer &&
      MobileAdminRoutes.permits(request.method, request.path, request.queryString) &&
      MobileAdminRoutes.stockWrite(request.method, request.path)
    adminSetup || (request.method match {
      case "OPTIONS" => true
      case "GET" =>
        request.path == "/ping" ||
          Set("/oauth/mobile/authorize", "/oauth/mobile/callback", "/oauth/mobile/me")
            .contains(request.path) ||
          // Guests neither write OSM nor change task status. The choice check is for guest
          // tokens only while writes are off; the bearer filter refuses it to app grants.
          MobileGuestRoutes.own(request.method, request.path) ||
          (bearer && MobileGuestRoutes.guestOnlyWhenDisabled(request.method, request.path)) ||
          discovery.contains(request.path) ||
          request.path.matches("/api/v2/task/[0-9]+/tags") ||
          (!request.path.matches("/api/v2/task/[0-9]+/choice/check") &&
            MobileReadRoutes.permits(request.method, request.path)) ||
          MobileAdminRoutes.permits(request.method, request.path, request.queryString)
      case "POST" =>
        Set(
          "/oauth/mobile/consent",
          "/oauth/mobile/token",
          "/oauth/mobile/revoke",
          "/oauth/mobile/guest",
          "/api/v2/mobile-admin/clients"
        ).contains(request.path) || MobileGuestRoutes.own(request.method, request.path) ||
          MobileGuestRoutes.claimToken(request.method, request.path)
      case "PATCH" => request.path.matches("/api/v2/mobile-admin/clients/[A-Za-z0-9._-]+")
      case "PUT" =>
        request.path == "/api/v2/mobile-admin/write-policy" ||
          MobileGuestRoutes.email(request.method, request.path)
      case "DELETE" => MobileGuestRoutes.own(request.method, request.path)
      case _        => false
    })
  }
}

/** Disabled/legacy requests pass through unchanged; mobile credentials can never fall back. */
class MobileBearerFilter @Inject() (
    settings: MobileOAuthSettings,
    oauth: MobileOAuthService,
    users: UserService,
    admins: MobileAdminCheck,
    adminRepository: MobileAdminRepository,
    writePolicy: MobileWritePolicy,
    guests: MobileGuestAuth
)(implicit val mat: Materializer, ec: ExecutionContext)
    extends Filter {
  private val logger = play.api.Logger(getClass)

  // Without guests: for tests of app and admin grants.
  def this(
      settings: MobileOAuthSettings,
      oauth: MobileOAuthService,
      users: UserService,
      admins: MobileAdminCheck,
      adminRepository: MobileAdminRepository,
      writePolicy: MobileWritePolicy
  )(
      implicit mat: Materializer,
      ec: ExecutionContext
  ) = this(settings, oauth, users, admins, adminRepository, writePolicy, MobileGuestAuth.Disabled)

  // No admins and so no audit log: for tests of app grants only.
  def this(settings: MobileOAuthSettings, oauth: MobileOAuthService, users: UserService)(
      implicit mat: Materializer,
      ec: ExecutionContext
  ) = this(settings, oauth, users, MobileAdminCheck.Nobody, null, null, MobileGuestAuth.Disabled)

  def this(
      settings: MobileOAuthSettings,
      oauth: MobileOAuthService,
      users: UserService,
      admins: MobileAdminCheck,
      adminRepository: MobileAdminRepository
  )(
      implicit mat: Materializer,
      ec: ExecutionContext
  ) = this(settings, oauth, users, admins, adminRepository, null, MobileGuestAuth.Disabled)

  private def denied(status: Int, code: String): Future[Result] = Future.successful(
    Results
      .Status(status)(Json.obj("error" -> code))
      .withHeaders("Cache-Control" -> "no-store", "WWW-Authenticate" -> "Bearer")
  )

  /**
    * Admin writes through stock routes: who, which route, and the outcome. Also recorded when the
    * action fails with an exception (status 500 and the exception class), since part of the write
    * may have been committed.
    */
  private def auditStockWrite(
      user: User,
      request: RequestHeader,
      outcome: scala.util.Try[Result]
  ): Future[Result] = {
    val after = outcome match {
      case scala.util.Success(response) => Json.obj("status" -> response.header.status)
      case scala.util.Failure(e)        => Json.obj("status" -> 500, "error" -> e.getClass.getSimpleName)
    }
    Future(adminRepository.record(user.id, s"stock.${request.method}", request.path, Some(after)))
      .recover {
        case e: Exception =>
          // The write already happened; losing its audit entry must be visible in the logs.
          logger.error(
            s"Mobile admin audit entry not recorded for ${request.method} ${request.path}: ${e.getClass.getSimpleName}"
          )
      }
      .flatMap(_ => Future.fromTry(outcome))
  }

  def apply(next: RequestHeader => Future[Result])(request: RequestHeader): Future[Result] = {
    if (settings.writeControlEnabled && !MobileFieldRoutes.permitsWhenDisabled(request) &&
        !writePolicy.enabled) return denied(403, "mobile_writes_disabled")
    val authorizations = request.headers.getAll("Authorization")
    val mobile         = authorizations.exists(_.toLowerCase(java.util.Locale.ROOT).startsWith("bearer"))
    if (!settings.enabled || !mobile) return next(request)
    if (authorizations.size != 1 || request.headers.get(SessionManager.KEY_API).isDefined ||
        request.session.get(SessionManager.KEY_TOKEN).isDefined) return denied(401, "invalid_token")
    val parts = authorizations.head.split(" ", -1)
    if (parts.length != 2 || !parts(0).equalsIgnoreCase("Bearer") ||
        !parts(1).matches("[A-Za-z0-9_-]{32,256}")) return denied(401, "invalid_token")
    val write = MobileWriteRoutes.permits(request.method, request.path)
    val read  = MobileReadRoutes.permits(request.method, request.path)
    val admin = MobileAdminRoutes.permits(request.method, request.path, request.queryString)
    val guest = guests.enabled && MobileGuestRoutes.permits(request.method, request.path)
    if (write && (!settings.allowTaskWrites ||
        (settings.writeControlEnabled && !writePolicy.enabled)))
      return denied(403, "mobile_writes_disabled")
    if (!write && !read && !admin && !guest) return denied(403, "insufficient_scope")
    val badShape =
      if (write && MobileWriteRoutes.takesBody(request.method, request.path))
        !MobileWriteRoutes.acceptableBody(request)
      else if (guest && !write && !read) !MobileGuestRoutes.acceptable(request)
      else
        (write || MobileReadRoutes.bare(request.method, request.path)) &&
        (request.rawQueryString.nonEmpty || request.hasBody)
    if (badShape) return denied(400, "invalid_request")
    oauth.authenticate(parts(1)).flatMap { grant =>
      // MobileOAuthService.authenticate has already checked the scopes against the client.
      grant.flatMap(value => MobileScopes.parse(value.scope).map(value -> _)) match {
        case Some((value, scopes)) =>
          // Admin grants reach only admin routes; app grants never do.
          val adminGrant = MobileScopes.isAdmin(scopes)
          if (if (adminGrant) !admin else !write && !read) denied(403, "insufficient_scope")
          else if (!adminGrant && settings.writeControlEnabled &&
                   MobileGuestRoutes.guestOnlyWhenDisabled(request.method, request.path) &&
                   !writePolicy.enabled) denied(403, "mobile_writes_disabled")
          else if (write && !scopes.contains(MobileScopes.Write)) denied(403, "insufficient_scope")
          else
            users.retrieve(value.userId).filter(_.id > 0) match {
              // Re-checked on every request, so a demotion takes effect at once.
              case Some(user) if adminGrant && !admins.isAdmin(user) =>
                denied(403, "admin_required")
              case Some(user) =>
                val result = next(
                  request
                    .addAttr(MobileBearerIdentity.UserKey, user)
                    .addAttr(MobileBearerIdentity.ScopesKey, scopes)
                    .addAttr(MobileBearerIdentity.FamilyKey, value.familyId)
                    .addAttr(MobileBearerIdentity.ClientKey, value.clientId)
                )
                if (adminGrant && MobileAdminRoutes.stockWrite(request.method, request.path))
                  result.transformWith(outcome => auditStockWrite(user, request, outcome))
                else result
              case None => denied(401, "invalid_token")
            }
        // Not an app grant: maybe a guest access token. Guests reach only MobileGuestRoutes.
        case None if guests.enabled =>
          guests.authenticate(parts(1)).flatMap {
            case Some(value) if guest => next(request.addAttr(MobileBearerIdentity.GuestKey, value))
            case Some(_)              => denied(403, "insufficient_scope")
            case None                 => denied(401, "invalid_token")
          }
        case None => denied(401, "invalid_token")
      }
    }
  }
}

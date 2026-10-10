package org.maproulette.auth.mobile

import akka.util.ByteString
import javax.inject.Inject
import play.api.libs.streams.Accumulator
import play.api.mvc._
import play.filters.cors.CORSFilter
import scala.concurrent.ExecutionContext

/**
  * CORS for the admin web app, wrapped around Play's [[CORSFilter]].
  *
  * Upstream configures Play's filter with `allowedOrigins = null`, which in Play means every
  * origin, with credentials, on every path. This wrapper never widens that. With mobile OAuth
  * enabled:
  *   - Admin API (`/api/v2/mobile-admin/...`), `/oauth/mobile/token` and `/oauth/mobile/revoke`
  *     answer CORS for the configured admin origin only; Play's filter is skipped there, so other
  *     origins get no CORS headers and their preflights get 403.
  *   - The stock routes in [[MobileAdminRoutes]] answer the admin origin here, without
  *     credentials; every other origin still goes to Play's filter unchanged.
  *   - The claim page's routes (deferred sign-up: `/api/v2/mobile-claim...`, plus the token,
  *     revoke and identity routes) answer the claim origin, without credentials. The claim
  *     routes answer no other origin.
  *   - Everything else goes to Play's filter unchanged.
  * With mobile OAuth disabled, every request goes to Play's filter.
  */
class MobileCorsFilter @Inject() (cors: CORSFilter, settings: MobileOAuthSettings)(
    implicit ec: ExecutionContext
) extends EssentialFilter {
  import MobileCorsFilter._

  def apply(next: EssentialAction): EssentialAction = EssentialAction { request =>
    decide(settings.enabled, settings.adminOrigin, settings.claimOrigin, request) match {
      case Delegate            => cors(next)(request)
      case Plain               => next(request)
      case Preflight(result)   => Accumulator.done(result)
      case AllowOrigin(origin) => next(request).map(allow(_, origin))
    }
  }
}

object MobileCorsFilter {
  sealed trait Decision
  case object Delegate                   extends Decision
  case object Plain                      extends Decision
  case class Preflight(result: Result)   extends Decision
  case class AllowOrigin(origin: String) extends Decision

  private val OAuthPaths     = Set("/oauth/mobile/token", "/oauth/mobile/revoke")
  private val AllowedHeaders = Set("authorization", "content-type", "accept")
  val MaxAgeSeconds          = 600

  /** Paths where only the admin origin (and for the claim routes, the claim origin) gets CORS. */
  def exclusive(path: String): Boolean =
    MobileAdminRoutes.adminApi(path) || OAuthPaths.contains(path) || claimApi(path)

  private def claimApi(path: String): Boolean =
    path == "/api/v2/mobile-claim" || path.matches("/api/v2/mobile-claim/[A-Za-z0-9-]+")

  /** What the claim page calls (API plan §5). */
  def claimEligible(method: String, path: String): Boolean = method match {
    case "POST" =>
      OAuthPaths.contains(path) || Set(
        "/api/v2/mobile-claim",
        "/api/v2/mobile-claim/preview",
        "/api/v2/mobile-claim/delete",
        "/api/v2/mobile-claim/stop-reminders"
      ).contains(path)
    case "GET" => path == "/oauth/mobile/me" || path.matches("/api/v2/mobile-claim/[0-9]+")
    case _     => false
  }
  private def claimPath(path: String): Boolean =
    claimEligible("POST", path) || claimEligible("GET", path)

  private def eligible(method: String, path: String): Boolean =
    if (OAuthPaths.contains(path)) method == "POST" else MobileAdminRoutes.matches(method, path)

  private def vary(result: Result): Result = {
    val current = result.header.headers.get("Vary").filter(_.nonEmpty)
    result.withHeaders("Vary" -> current.fold("Origin")(_ + ", Origin"))
  }

  def allow(result: Result, origin: String): Result =
    vary(result.withHeaders("Access-Control-Allow-Origin" -> origin))

  def decide(enabled: Boolean, adminOrigin: Option[String], request: RequestHeader): Decision =
    decide(enabled, adminOrigin, None, request)

  def decide(
      enabled: Boolean,
      adminOrigin: Option[String],
      claimOrigin: Option[String],
      request: RequestHeader
  ): Decision = {
    val path      = request.path
    val origin    = request.headers.get("Origin")
    val requested = request.headers.get("Access-Control-Request-Method")
    val preflight = request.method == "OPTIONS" && requested.isDefined
    val isAdmin   = adminOrigin.isDefined && origin == adminOrigin
    val isClaim   = claimOrigin.isDefined && origin == claimOrigin && claimPath(path)
    val adminPath = exclusive(path) ||
      eligible(if (preflight) requested.get else request.method, path)
    def allowed(method: String) =
      if (isClaim) claimEligible(method, path) else eligible(method, path)
    if (!enabled) Delegate
    else if ((isAdmin && adminPath) || isClaim) {
      if (!preflight) AllowOrigin(origin.get)
      else {
        val headers = request.headers
          .get("Access-Control-Request-Headers")
          .toSeq
          .flatMap(_.split(","))
          .map(_.trim.toLowerCase(java.util.Locale.ROOT))
          .filter(_.nonEmpty)
        if (!allowed(requested.get) || !headers.forall(AllowedHeaders.contains))
          Preflight(Results.Forbidden)
        else
          Preflight(
            allow(
              Results.NoContent.withHeaders(
                Seq(
                  "Access-Control-Allow-Methods" -> requested.get,
                  "Access-Control-Max-Age"       -> MaxAgeSeconds.toString
                ) ++ (if (headers.isEmpty) Seq.empty
                      else Seq("Access-Control-Allow-Headers" -> headers.mkString(", "))): _*
              ),
              origin.get
            )
          )
      }
    } else if (exclusive(path)) {
      if (preflight) Preflight(Results.Forbidden) else Plain
    } else Delegate
  }
}

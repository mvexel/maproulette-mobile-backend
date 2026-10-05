package org.maproulette.auth.mobile

import akka.stream.Materializer
import javax.inject.Inject
import org.maproulette.framework.model.User
import org.maproulette.framework.service.UserService
import org.maproulette.session.SessionManager
import play.api.libs.json.Json
import play.api.libs.typedmap.TypedKey
import play.api.mvc.{Filter, RequestHeader, Result, Results}
import scala.concurrent.{ExecutionContext, Future}

object MobileBearerIdentity {
  val UserKey: TypedKey[User] = TypedKey[User]("mobile-oauth-user")
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
    s"/api/v2/tasks/box/$box",
    "/oauth/mobile/me"
  ).map(_.r)

  def permits(method: String, path: String): Boolean =
    (method == "GET" && reads.exists(_.pattern.matcher(path).matches())) ||
      (method == "PUT" && path.matches(s"/api/v2/markers/box/$box"))
}

/** Disabled/legacy requests pass through unchanged; mobile credentials can never fall back. */
class MobileBearerFilter @Inject() (
    settings: MobileOAuthSettings,
    oauth: MobileOAuthService,
    users: UserService
)(implicit val mat: Materializer, ec: ExecutionContext)
    extends Filter {
  private def denied(status: Int, code: String): Future[Result] = Future.successful(
    Results
      .Status(status)(Json.obj("error" -> code))
      .withHeaders("Cache-Control" -> "no-store", "WWW-Authenticate" -> "Bearer")
  )

  def apply(next: RequestHeader => Future[Result])(request: RequestHeader): Future[Result] = {
    val authorizations = request.headers.getAll("Authorization")
    val mobile         = authorizations.exists(_.toLowerCase(java.util.Locale.ROOT).startsWith("bearer"))
    if (!settings.enabled || !mobile) return next(request)
    if (authorizations.size != 1 || request.headers.get(SessionManager.KEY_API).isDefined ||
        request.session.get(SessionManager.KEY_TOKEN).isDefined) return denied(401, "invalid_token")
    val parts = authorizations.head.split(" ", -1)
    if (parts.length != 2 || !parts(0).equalsIgnoreCase("Bearer") ||
        !parts(1).matches("[A-Za-z0-9_-]{32,256}")) return denied(401, "invalid_token")
    if (!MobileReadRoutes.permits(request.method, request.path))
      return denied(403, "insufficient_scope")
    oauth.authenticate(parts(1)).flatMap {
      case Some(grant) if grant.scope == settings.scope =>
        users.retrieve(grant.userId).filter(_.id > 0) match {
          case Some(user) => next(request.addAttr(MobileBearerIdentity.UserKey, user))
          case None       => denied(401, "invalid_token")
        }
      case _ => denied(401, "invalid_token")
    }
  }
}

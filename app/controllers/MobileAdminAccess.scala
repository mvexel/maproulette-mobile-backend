package controllers

import org.maproulette.auth.mobile._
import org.maproulette.framework.model.User
import play.api.http.HeaderNames.CACHE_CONTROL
import play.api.libs.json.Json
import play.api.mvc.{Request, Result, Results}
import scala.concurrent.{ExecutionContext, Future}

/**
  * The admin API's gate, shared by its controllers: a `mobile:admin` grant of a current
  * super-user, or a `mobile:organize` grant of a listed organizer for what organizers may do (D8,
  * [[MobileAdminCheck.can]]). The bearer filter checked the grant and the route already; checked
  * again here so no route can skip it. Web sessions and API keys get 403 `mobile_admin_only`.
  */
object MobileAdminAccess {

  /** The grant's scopes, for a request that passed [[guard]]. */
  def scopes[A](request: Request[A]): Set[String] =
    request.attrs.get(MobileBearerIdentity.ScopesKey).getOrElse(Set.empty)

  def guard[A](
      request: Request[A],
      settings: MobileOAuthSettings,
      service: MobileOAuthService
  )(allowed: (User, Set[String]) => Boolean)(
      work: User => Future[Result]
  )(implicit ec: ExecutionContext): Future[Result] = {
    def refuse(code: String) = Future.successful(Results.Forbidden(Json.obj("error" -> code)))
    val result =
      if (!settings.enabled) Future.successful(Results.NotFound)
      else
        (
          request.attrs.get(MobileBearerIdentity.UserKey),
          request.attrs.get(MobileBearerIdentity.ScopesKey)
        ) match {
          case (Some(user), Some(scopes)) if MobileScopes.isStaff(scopes) =>
            service.storage(allowed(user, scopes)).flatMap {
              case true                                  => work(user)
              case false if MobileScopes.isAdmin(scopes) => refuse("mobile_admin_only")
              case false                                 => refuse("organizer_forbidden")
            }
          case _ => refuse("mobile_admin_only")
        }
    result.map(_.withHeaders(CACHE_CONTROL -> "no-store"))
  }

  def capability[A](
      request: Request[A],
      settings: MobileOAuthSettings,
      service: MobileOAuthService,
      capability: MobileCapability
  )(work: User => Future[Result])(implicit ec: ExecutionContext): Future[Result] =
    guard(request, settings, service)(service.admins.can(_, _, capability))(work)

  /** Routes about one challenge: the campaigns of its project. */
  def challenge[A](
      request: Request[A],
      settings: MobileOAuthSettings,
      service: MobileOAuthService,
      challengeId: Long
  )(work: User => Future[Result])(implicit ec: ExecutionContext): Future[Result] =
    guard(request, settings, service)(service.admins.canChallenge(_, _, challengeId))(work)

  /**
    * The projects a grant may see in the campaign and project lists: None for all (super-users),
    * else the organizer's. Blocking.
    */
  def visibleProjects[A](
      request: Request[A],
      service: MobileOAuthService,
      user: User
  ): Option[Set[Long]] =
    if (MobileScopes.isAdmin(scopes(request))) None
    else Some(service.admins.organizerProjects(user))
}

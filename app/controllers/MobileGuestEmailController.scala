package controllers

import javax.inject.Inject
import org.maproulette.auth.mobile.MobileBearerIdentity
import org.maproulette.auth.mobile.guest.MobileGuestAuth
import org.maproulette.provider.choice.claim.GuestEmailService
import play.api.libs.json.{JsValue, Json}
import play.api.mvc._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal

/**
  * `PUT /api/v2/mobile-guest/email` (deferred sign-up B4), guest bearer. 404 while guests are off.
  * The response is the guest status body of `GET /api/v2/mobile-guest/me`.
  */
class MobileGuestEmailController @Inject() (
    components: ControllerComponents,
    guests: MobileGuestAuth,
    emails: GuestEmailService
)(implicit ec: ExecutionContext)
    extends AbstractController(components) {
  private val logger = play.api.Logger(getClass)

  private def noStore(result: Result): Result =
    result.withHeaders(CACHE_CONTROL -> "no-store", PRAGMA -> "no-cache")

  def setEmail: Action[JsValue] = Action.async(parse.tolerantJson(maxLength = 2048)) { request =>
    if (!guests.enabled) Future.successful(NotFound)
    else
      request.attrs.get(MobileBearerIdentity.GuestKey) match {
        case None => Future.successful(noStore(Unauthorized(Json.obj("error" -> "invalid_token"))))
        case Some(guest) =>
          (request.body \ "email").asOpt[String] match {
            case None =>
              Future.successful(noStore(BadRequest(Json.obj("error" -> "invalid_request"))))
            case Some(email) =>
              emails
                .setEmail(guest, email)
                .map {
                  case Left(error) => Status(error.status)(Json.obj("error" -> error.code))
                  case Right(updated) =>
                    Ok(
                      Json.obj(
                        "guestId"   -> updated.id.toString,
                        "state"     -> "active",
                        "email"     -> "pending",
                        "expiresAt" -> updated.expiresAt.toString
                      )
                    )
                }
                .recover {
                  case NonFatal(e) =>
                    // Class only: messages could echo the address.
                    logger.error(s"Guest email request failed: ${e.getClass.getSimpleName}")
                    InternalServerError(Json.obj("error" -> "server_error"))
                }
                .map(noStore)
          }
      }
  }
}

/**
  * The routes behind the emailed links (deferred sign-up B4, API plan §3.10), and the app's
  * reminder switch. The token routes take no credential and answer 404 for an unknown or
  * superseded token, so token existence isn't disclosed.
  */
class MobileGuestTokenController @Inject() (
    components: ControllerComponents,
    guests: MobileGuestAuth,
    tokens: org.maproulette.provider.choice.claim.GuestTokenService
)(implicit ec: ExecutionContext)
    extends AbstractController(components) {
  private val logger = play.api.Logger(getClass)

  private def handled(operation: => Future[Result]): Future[Result] =
    if (!guests.enabled) Future.successful(NotFound)
    else
      (try operation
      catch { case NonFatal(e) => Future.failed(e) })
        .recover {
          case NonFatal(e) =>
            logger.error(s"Guest token request failed: ${e.getClass.getSimpleName}")
            InternalServerError(Json.obj("error" -> "server_error"))
        }
        .map(_.withHeaders(CACHE_CONTROL -> "no-store", PRAGMA -> "no-cache"))

  private def withToken(request: Request[JsValue])(operation: String => Future[Result]) =
    handled {
      (request.body \ "claimToken").asOpt[String] match {
        case Some(token) if !request.headers.hasHeader(AUTHORIZATION) => operation(token)
        case _                                                        => Future.successful(BadRequest(Json.obj("error" -> "invalid_request")))
      }
    }

  private val notFound = NotFound(Json.obj("error" -> "not_found"))

  /** `POST /api/v2/mobile-claim/delete`, `{"claimToken"}`: same effect as `DELETE /api/v2/mobile-guest`. */
  def delete: Action[JsValue] = Action.async(parse.tolerantJson(maxLength = 1024)) { request =>
    withToken(request) { token =>
      tokens.delete(token).map {
        case None             => notFound
        case Some(Left(code)) => Conflict(Json.obj("error" -> code))
        case Some(Right(_))   => NoContent
      }
    }
  }

  /** `POST /api/v2/mobile-claim/stop-reminders`, `{"claimToken"}`. Deletes nothing. */
  def stopReminders: Action[JsValue] = Action.async(parse.tolerantJson(maxLength = 1024)) {
    request =>
      withToken(request) { token =>
        tokens.stopReminders(token).map(if (_) NoContent else notFound)
      }
  }

  /** `PUT /api/v2/mobile-guest/reminders`, guest bearer, `{"enabled": false}`. */
  def reminders: Action[JsValue] = Action.async(parse.tolerantJson(maxLength = 512)) { request =>
    handled {
      (request.attrs.get(MobileBearerIdentity.GuestKey), (request.body \ "enabled").asOpt[Boolean]) match {
        case (None, _) => Future.successful(Unauthorized(Json.obj("error" -> "invalid_token")))
        case (Some(guest), Some(enabled)) =>
          tokens.setReminders(guest.id, enabled).map(_ => NoContent)
        case _ => Future.successful(BadRequest(Json.obj("error" -> "invalid_request")))
      }
    }
  }
}

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

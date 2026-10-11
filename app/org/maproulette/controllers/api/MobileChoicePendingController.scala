package org.maproulette.controllers.api

import java.nio.ByteBuffer
import java.nio.charset.{CodingErrorAction, StandardCharsets}
import javax.inject.Inject
import org.maproulette.auth.mobile.{MobileBearerIdentity, MobileOAuthSettings, MobileWriteRoutes}
import org.maproulette.auth.mobile.guest.MobileGuest
import org.maproulette.provider.choice.{ChoiceResponse, MobileChoicePendingService}
import play.api.Logger
import play.api.libs.json.Json
import play.api.mvc._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try
import scala.util.control.NonFatal

/**
  * Pending answers of deferred sign-up guests (fork only). Guest access tokens only; the bearer
  * filter has already checked the token and the request shape. Every route answers 404 while
  * `mobileOAuth.guests.enabled` is off.
  */
class MobileChoicePendingController @Inject() (
    components: ControllerComponents,
    settings: MobileOAuthSettings,
    service: MobileChoicePendingService
)(implicit ec: ExecutionContext)
    extends AbstractController(components) {
  private val logger = Logger(getClass)

  private def respond(response: ChoiceResponse): Result =
    (if (response.status == NO_CONTENT) NoContent else Status(response.status)(response.body))
      .withHeaders(CACHE_CONTROL -> "no-store")

  private def guarded(request: RequestHeader)(call: MobileGuest => Future[ChoiceResponse]) =
    if (!settings.guestsEnabled) Future.successful(NotFound)
    else
      request.attrs.get(MobileBearerIdentity.GuestKey) match {
        case None =>
          Future.successful(respond(ChoiceResponse(403, Json.obj("error" -> "mobile_only"))))
        case Some(guest) =>
          Try(call(guest)).fold(Future.failed, identity).map(respond).recover {
            case NonFatal(e) =>
              logger.error(s"Pending answer request failed: ${e.getClass.getSimpleName}", e)
              respond(ChoiceResponse(500, Json.obj("error" -> "server_error")))
          }
      }

  /** `POST /api/v2/task/:id/choice/pending`, body as for `POST /task/:id/choice`. */
  def submit(taskId: Long): Action[akka.util.ByteString] =
    Action.async(parse.byteString(maxLength = MobileWriteRoutes.MaxChoiceBodyBytes.toLong)) {
      request =>
        guarded(request) { guest =>
          val decoder = StandardCharsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
          Try(decoder.decode(ByteBuffer.wrap(request.body.toArray)).toString).toOption match {
            case None =>
              Future.successful(ChoiceResponse(400, Json.obj("error" -> "invalid_request")))
            case Some(body) => service.submit(guest, taskId, body)
          }
        }
    }

  /** `DELETE /api/v2/task/:id/choice/pending`. */
  def withdraw(taskId: Long): Action[AnyContent] = Action.async { request =>
    guarded(request)(guest => service.withdraw(guest, taskId))
  }

  /** `GET /api/v2/mobile-guest/pending?limit=&after=`. */
  def list: Action[AnyContent] = Action.async { request =>
    guarded(request) { guest =>
      service.list(
        guest,
        request.getQueryString("limit"),
        request.getQueryString("after")
      )
    }
  }
}

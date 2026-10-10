package org.maproulette.controllers.api

import java.nio.ByteBuffer
import java.nio.charset.{CodingErrorAction, StandardCharsets}
import javax.inject.Inject
import org.maproulette.auth.mobile.{MobileBearerIdentity, MobileWriteRoutes}
import org.maproulette.provider.choice.{ChoiceResponse, MobileChoiceService}
import play.api.Logger
import play.api.libs.json.Json
import play.api.mvc._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try
import scala.util.control.NonFatal

/**
  * Mobile choice tasks (fork only). Both routes accept mobile bearer grants only; the bearer filter
  * has already checked the scope (tasks:read for check, tasks:write for submit) and the request
  * shape. Web sessions and API keys get 403 mobile_only.
  */
class MobileChoiceController @Inject() (
    components: ControllerComponents,
    service: MobileChoiceService
)(implicit ec: ExecutionContext)
    extends AbstractController(components) {
  private val logger = Logger(getClass)

  private def respond(response: ChoiceResponse): Result =
    Status(response.status)(response.body).withHeaders(CACHE_CONTROL -> "no-store")
  private def guarded(taskId: Long)(call: => Future[ChoiceResponse]): Future[Result] =
    Try(call).fold(Future.failed, identity).map(respond).recover {
      case NonFatal(e) =>
        logger.error(s"Choice request for task $taskId failed", e)
        respond(ChoiceResponse(500, Json.obj("error" -> "server_error")))
    }
  private val mobileOnly =
    Future.successful(respond(ChoiceResponse(403, Json.obj("error" -> "mobile_only"))))

  def check(taskId: Long): Action[AnyContent] = Action.async { request =>
    // App grants and, for pending answers, guest tokens.
    if (request.attrs.get(MobileBearerIdentity.UserKey).isEmpty &&
        request.attrs.get(MobileBearerIdentity.GuestKey).isEmpty) mobileOnly
    else guarded(taskId)(service.check(taskId))
  }

  def submit(taskId: Long): Action[akka.util.ByteString] =
    Action.async(parse.byteString(maxLength = MobileWriteRoutes.MaxChoiceBodyBytes.toLong)) {
      request =>
        val identity = for {
          scopes <- request.attrs.get(MobileBearerIdentity.ScopesKey)
          family <- request.attrs.get(MobileBearerIdentity.FamilyKey)
        } yield (scopes, family)
        (request.attrs.get(MobileBearerIdentity.UserKey), identity) match {
          case (None, _) => mobileOnly
          case (Some(_), None) =>
            logger.error("Mobile bearer request without grant scopes or family")
            Future.successful(respond(ChoiceResponse(500, Json.obj("error" -> "server_error"))))
          case (Some(user), Some((scopes, family))) =>
            val decoder = StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
            Try(decoder.decode(ByteBuffer.wrap(request.body.toArray)).toString).toOption match {
              case None =>
                Future.successful(
                  respond(ChoiceResponse(400, Json.obj("error" -> "invalid_request")))
                )
              case Some(body) => guarded(taskId)(service.submit(taskId, user, scopes, family, body))
            }
        }
    }
}

package controllers

import javax.inject.Inject
import org.maproulette.auth.mobile.MobileBearerIdentity
import org.maproulette.auth.mobile.guest.{
  GuestError,
  MobileGuest,
  MobileGuestService,
  MobileGuestStore
}
import org.maproulette.provider.choice.ChoicePendingStore
import play.api.libs.json.{JsNull, JsObject, Json}
import play.api.mvc._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal

/**
  * Deferred sign-up guests: registration and the guest's own routes. The guest grant on
  * `/oauth/mobile/token` is in [[MobileOAuthController.token]]. Every route answers 404 while
  * `mobileOAuth.guests.enabled` is off.
  */
class MobileGuestController @Inject() (
    components: ControllerComponents,
    guests: MobileGuestService,
    pending: ChoicePendingStore
)(implicit ec: ExecutionContext)
    extends AbstractController(components) {
  private val logger = play.api.Logger(getClass)

  private def noStore(result: Result): Result =
    result.withHeaders(CACHE_CONTROL -> "no-store", PRAGMA -> "no-cache")

  private def handled(operation: => Future[Result]): Future[Result] =
    if (!guests.enabled) Future.successful(NotFound)
    else
      (try operation
      catch { case NonFatal(e) => Future.failed(e) })
        .map(noStore)
        .recover {
          case NonFatal(e) =>
            // Class only: messages could echo request parameters.
            logger.error(s"Mobile guest request failed: ${e.getClass.getSimpleName}")
            noStore(InternalServerError(Json.obj("error" -> "server_error")))
        }

  private def guestRequest(request: RequestHeader)(operation: MobileGuest => Future[Result]) =
    handled {
      // Set only by MobileBearerFilter for a valid guest token on a guest route.
      request.attrs.get(MobileBearerIdentity.GuestKey) match {
        case Some(guest) => operation(guest)
        case None        => Future.successful(Unauthorized(Json.obj("error" -> "invalid_token")))
      }
    }

  private def failure(error: GuestError): Result = {
    val result = Status(error.status)(Json.obj("error" -> error.code))
    if (error == GuestError.RateLimited) result.withHeaders(RETRY_AFTER -> "3600") else result
  }

  /**
    * `POST /oauth/mobile/guest`, form-encoded `client_id` and optional `install_id` (G-IDEM: a
    * retry with the same install id returns the same unused guest, with a new secret). No
    * credential.
    */
  def register: Action[Map[String, Seq[String]]] =
    Action.async(parse.formUrlEncoded(maxLength = 1024)) { request =>
      handled {
        val installId = request.body.get("install_id") match {
          case None                                                 => Right(None)
          case Some(Seq(id)) if MobileGuestStore.validInstallId(id) => Right(Some(id))
          case _                                                    => Left(())
        }
        (request.body.get("client_id"), installId) match {
          case (Some(Seq(clientId)), Right(install)) if !request.headers.hasHeader(AUTHORIZATION) =>
            guests.register(clientId, request.remoteAddress, install).map {
              case Left(error) => failure(error)
              case Right(registration) =>
                Created(
                  Json.obj(
                    "guestId"     -> registration.guest.id.toString,
                    "guestSecret" -> registration.secret,
                    "expiresAt"   -> registration.guest.expiresAt.toString
                  )
                )
            }
          case _ => Future.successful(failure(GuestError.InvalidRequest))
        }
      }
    }

  /** `GET /api/v2/mobile-guest/me`, guest bearer. */
  def me: Action[AnyContent] = Action.async { request =>
    guestRequest(request)(guest =>
      Future((pending.counts(guest.id), pending.byChallenge(guest.id))).map {
        case (counts, byChallenge) => Ok(MobileGuestController.status(guest, counts, byChallenge))
      }
    )
  }

  /** `DELETE /api/v2/mobile-guest`, guest bearer: "delete my data". */
  def delete: Action[AnyContent] = Action.async { request =>
    guestRequest(request)(guest => guests.delete(guest).map(_ => NoContent))
  }
}

object MobileGuestController {

  /** The guest status body of `GET /api/v2/mobile-guest/me`; `PUT /email` answers with it too. */
  def status(
      guest: MobileGuest,
      counts: Map[String, Int],
      byChallenge: Map[Long, Map[String, Int]]
  ): JsObject = {
    // H1c: an answer is on the map once published, or once OSM carries it from the confirm queue.
    def onMap(states: Map[String, Int]) =
      states.getOrElse[Int]("published", 0) + states.getOrElse[Int]("on_map", 0)
    Json.obj(
      "guestId" -> guest.id.toString,
      // Only live, unclaimed guests authenticate; claimed and expired states come with claiming.
      "state" -> "active",
      "email" -> (if (guest.emailVerified) "verified"
                  else if (guest.emailSet) "pending"
                  else "none"),
      "pending"   -> counts.getOrElse[Int]("pending", 0),
      "published" -> onMap(counts),
      "expiresAt" -> guest.expiresAt.toString,
      // Set once claiming exists; a claimed guest's token no longer authenticates.
      "claimedAs" -> JsNull,
      "byChallenge" -> JsObject(
        byChallenge.toSeq
          .map { case (id, states) => (id, states.getOrElse[Int]("pending", 0), onMap(states)) }
          .filter { case (_, pending, done) => pending + done > 0 }
          .sortBy(_._1)
          .map {
            case (id, pending, done) =>
              id.toString -> Json.obj("pending" -> pending, "onMap" -> done)
          }
      )
    )
  }
}

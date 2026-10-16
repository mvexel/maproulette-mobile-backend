package controllers

import javax.inject.Inject
import org.maproulette.auth.mobile._
import org.maproulette.framework.model.User
import org.maproulette.provider.choice.{ChoiceQueueService, OsmCallException}
import play.api.libs.json._
import play.api.mvc._
import scala.concurrent.{ExecutionContext, Future}

/**
  * The confirm queue (H1b, backend/h1/h1b-design.md, docs/mobile-admin-api.md "Confirm queue"),
  * for super-users and the campaign's organizers ([[MobileAdminAccess]]).
  *   - `POST /api/v2/mobile-admin/challenges/:id/queue` runs the generator: it reads OSM, never
  *     writes it, and is audited as `queue.generate` with its counts.
  *   - `GET .../queue/export` is the queue as line-by-line GeoJSON for maproulette.org.
  */
class MobileQueueController @Inject() (
    components: ControllerComponents,
    settings: MobileOAuthSettings,
    service: MobileOAuthService,
    queue: ChoiceQueueService,
    audit: MobileAdminRepository
)(implicit ec: ExecutionContext)
    extends AbstractController(components) {

  private def problem(status: Status, code: String): Result = status(Json.obj("error" -> code))

  private def admin[A](request: Request[A], challengeId: Long)(
      work: User => Future[Result]
  ): Future[Result] =
    MobileAdminAccess.challenge(request, settings, service, challengeId)(work)

  def generate(challengeId: Long): Action[AnyContent] = Action.async { request =>
    admin(request, challengeId) { user =>
      queue
        .generate(challengeId, user)
        .map {
          case None => problem(NotFound, "not_found")
          case Some(report) =>
            audit.record(user.id, "queue.generate", s"challenge:$challengeId", Some(report.json))
            Ok(report.json)
        }
        .recover { case _: OsmCallException => problem(BadGateway, "osm_unavailable") }
    }
  }

  def exportQueue(challengeId: Long): Action[AnyContent] = Action.async { request =>
    admin(request, challengeId) { _ =>
      queue.exportQueue(challengeId).map {
        case None => problem(NotFound, "no_queue")
        case Some(lines) =>
          Ok(lines.map(Json.stringify).mkString("", "\n", "\n"))
            .as("application/geo+json-seq")
            .withHeaders(
              CONTENT_DISPOSITION -> s"""attachment; filename="confirm-queue-$challengeId.geojson""""
            )
      }
    }
  }
}

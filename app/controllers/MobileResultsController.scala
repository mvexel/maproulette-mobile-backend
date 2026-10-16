package controllers

import javax.inject.Inject
import org.maproulette.auth.mobile._
import org.maproulette.provider.choice.{ChoiceResults, ChoiceResultsRepository}
import play.api.libs.json.Json
import play.api.mvc._
import scala.concurrent.{ExecutionContext, Future}

/**
  * `GET /api/v2/mobile-admin/challenges/:id/results?format=csv|geojson`: a campaign's results for
  * the admin app's Export button. Gate: [[MobileAdminAccess]], so organizers too, for their
  * projects' campaigns. Read-only.
  */
class MobileResultsController @Inject() (
    components: ControllerComponents,
    settings: MobileOAuthSettings,
    service: MobileOAuthService,
    repository: ChoiceResultsRepository
)(implicit ec: ExecutionContext)
    extends AbstractController(components) {

  def results(challengeId: Long, format: String): Action[AnyContent] = Action.async { request =>
    MobileAdminAccess.challenge(request, settings, service, challengeId) { _ =>
      format match {
        case "csv" | "geojson" =>
          service.storage(repository.results(challengeId)).map {
            case None => NotFound(Json.obj("error" -> "not_found"))
            case Some(rows) =>
              val name = s"challenge-$challengeId-results.$format"
              val body =
                if (format == "csv") Ok(ChoiceResults.csv(rows)).as("text/csv; charset=utf-8")
                else Ok(ChoiceResults.geojson(rows)).as("application/geo+json")
              body.withHeaders(CONTENT_DISPOSITION -> s"""attachment; filename="$name"""")
          }
        case _ =>
          Future.successful(
            BadRequest(
              Json.obj(
                "error"  -> "invalid_request",
                "detail" -> Seq("format must be csv or geojson")
              )
            )
          )
      }
    }
  }
}

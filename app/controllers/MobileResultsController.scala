package controllers

import javax.inject.Inject
import org.maproulette.auth.mobile._
import org.maproulette.provider.choice.{ChoiceResults, ChoiceResultsRepository}
import play.api.libs.json.Json
import play.api.mvc._
import scala.concurrent.{ExecutionContext, Future}

/**
  * `GET /api/v2/mobile-admin/challenges/:id/results?format=csv|geojson`: a campaign's results for
  * the admin app's Export button. Same gate as [[MobileAdminController]]: `mobile:admin` bearer
  * grants of current super-users only. Read-only.
  */
class MobileResultsController @Inject() (
    components: ControllerComponents,
    settings: MobileOAuthSettings,
    service: MobileOAuthService,
    repository: ChoiceResultsRepository
)(implicit ec: ExecutionContext)
    extends AbstractController(components) {

  def results(challengeId: Long, format: String): Action[AnyContent] = Action.async { request =>
    val result =
      if (!settings.enabled) Future.successful(NotFound)
      else
        (
          request.attrs.get(MobileBearerIdentity.UserKey),
          request.attrs.get(MobileBearerIdentity.ScopesKey)
        ) match {
          // The bearer filter checked this already; checked again so no route can skip it.
          case (Some(user), Some(scopes))
              if MobileScopes.isAdmin(scopes) && service.admins.isAdmin(user) =>
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
          case _ => Future.successful(Forbidden(Json.obj("error" -> "mobile_admin_only")))
        }
    result.map(_.withHeaders(CACHE_CONTROL -> "no-store"))
  }
}

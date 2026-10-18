package controllers

import javax.inject.Inject
import org.maproulette.auth.mobile._
import org.maproulette.framework.model.User
import org.maproulette.framework.service.ProjectService
import org.maproulette.provider.choice._
import play.api.libs.json._
import play.api.mvc._
import scala.concurrent.{ExecutionContext, Future}

/**
  * The campaign wizard (campaign-wizard.md, Juno's wizard-api-draft.md), for super-users and for
  * organizers in their own projects. docs/mobile-admin-api.md "Campaign wizard".
  */
class MobileWizardController @Inject() (
    components: ControllerComponents,
    settings: MobileOAuthSettings,
    service: MobileOAuthService,
    wizard: CampaignWizard,
    projects: ProjectService
)(implicit ec: ExecutionContext)
    extends AbstractController(components) {
  private val MaxBody = 256 * 1024

  private def problem(status: Status, code: String, detail: Seq[String] = Seq.empty): Result =
    status(
      if (detail.isEmpty) Json.obj("error" -> code)
      else Json.obj("error"                -> code, "detail" -> detail)
    )

  /** Our limit is 429; Overpass timing out is 504 and any other Overpass failure 502. */
  private def failed(f: WizardQueryFailure): Result = f.code match {
    case "rate_limited" => problem(TooManyRequests, f.code)
    case "osm_timeout"  => problem(GatewayTimeout, f.code)
    case _              => problem(BadGateway, "osm_unavailable")
  }

  private def organizer[A](request: Request[A])(work: User => Future[Result]): Future[Result] =
    MobileAdminAccess.capability(request, settings, service, MobileCapability.ListCampaigns)(work)

  private def area[A](request: Request[A]): Either[Seq[String], SearchArea] =
    SearchArea.parse(
      request.getQueryString("lat"),
      request.getQueryString("lon"),
      request.getQueryString("radius")
    )

  def questionSets: Action[AnyContent] = Action.async { request =>
    organizer(request)(_ =>
      Future.successful(Ok(Json.obj("sets" -> wizard.library.sets.map(_.publicJson))))
    )
  }

  /** `GET /api/v2/mobile-admin/wizard/coverage`: the nightly extract's area, or `extract: null`. */
  def coverage: Action[AnyContent] = Action.async { request =>
    organizer(request)(_ => wizard.coverage().map(found => Ok(Json.obj("extract" -> found))))
  }

  def counts: Action[AnyContent] = Action.async { request =>
    organizer(request) { user =>
      area(request) match {
        case Left(errors) => Future.successful(problem(BadRequest, "invalid_request", errors))
        case Right(circle) =>
          wizard.counts(circle, user.id).map(_.fold(failed, Ok(_)))
      }
    }
  }

  def features(setId: String): Action[AnyContent] = Action.async { request =>
    organizer(request) { user =>
      (wizard.library.find(setId), area(request)) match {
        case (None, _)         => Future.successful(problem(NotFound, "not_found"))
        case (_, Left(errors)) => Future.successful(problem(BadRequest, "invalid_request", errors))
        case (Some(set), Right(circle)) =>
          wizard.features(set, circle, user.id).map {
            case Left(f) => failed(f)
            case Right(open) =>
              Ok(
                Json.obj(
                  "set"       -> set.id,
                  "version"   -> set.version,
                  "complete"  -> open.complete,
                  "truncated" -> open.truncated,
                  "open" -> open.shown.map { f =>
                    Json.obj(
                      "id"   -> f.element,
                      "lon"  -> f.lon,
                      "lat"  -> f.lat,
                      "name" -> f.tags.get("name")
                    )
                  }
                )
              )
          }
      }
    }
  }

  /** A draft campaign from a set, an area and the left-out places: 201 `{"id", "tasks"}`. */
  def create: Action[JsValue] = Action.async(parse.tolerantJson(MaxBody)) { request =>
    WizardCampaign.parse(request.body) match {
      case Left(errors) =>
        organizer(request)(_ => Future.successful(problem(BadRequest, "invalid_request", errors)))
      case Right(input) =>
        MobileAdminAccess.capability(
          request,
          settings,
          service,
          MobileCapability.Campaigns(input.projectId)
        ) { user =>
          wizard.library.find(input.set) match {
            case None =>
              Future.successful(problem(BadRequest, "invalid_request", Seq("unknown set")))
            case Some(set) if set.version != input.version =>
              Future.successful(problem(Conflict, "set_changed"))
            case Some(set) =>
              service.storage(projects.retrieve(input.projectId).filterNot(_.deleted)).flatMap {
                case None => Future.successful(problem(NotFound, "project_not_found"))
                case Some(_) =>
                  wizard.features(set, input.area, user.id, cached = false).flatMap {
                    case Left(f) => Future.successful(failed(f))
                    case Right(open) if open.truncated =>
                      Future.successful(problem(BadRequest, "area_too_big"))
                    case Right(open) =>
                      service.storage(wizard.create(user, input, set, open)).map {
                        case Left(code)         => problem(UnprocessableEntity, code)
                        case Right((id, tasks)) => Created(Json.obj("id" -> id, "tasks" -> tasks))
                      }
                  }
              }
          }
        }
    }
  }

  /** Lists the campaign in the app: `{"id", "published": true, "listed"}`. */
  def publish(challengeId: Long): Action[AnyContent] = Action.async { request =>
    MobileAdminAccess.challenge(request, settings, service, challengeId) { user =>
      service.storage(wizard.publish(user, challengeId)).map {
        case Left("not_found") => problem(NotFound, "not_found")
        case Left(code)        => problem(UnprocessableEntity, code)
        case Right(listed) =>
          Ok(Json.obj("id" -> challengeId, "published" -> true, "listed" -> listed))
      }
    }
  }
}

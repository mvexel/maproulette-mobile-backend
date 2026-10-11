package controllers

import javax.inject.Inject
import org.maproulette.auth.mobile._
import org.maproulette.framework.model.User
import org.maproulette.framework.service.ProjectService
import org.maproulette.provider.choice.{MobileCampaigns, MobileCampaignsRepository}
import play.api.libs.json._
import play.api.mvc._
import scala.concurrent.{ExecutionContext, Future}

/**
  * `/api/v2/mobile-admin/projects` and `/api/v2/mobile-admin/campaigns` (admin design pass §2, §3):
  * the project picker, turning a project on or off, and the Campaigns list. Same gate as
  * [[MobileAdminController]]. Project writes are recorded in the admin audit log.
  */
class MobileCampaignsController @Inject() (
    components: ControllerComponents,
    settings: MobileOAuthSettings,
    service: MobileOAuthService,
    campaigns: MobileCampaignsRepository,
    projects: ProjectService,
    audit: MobileAdminRepository
)(implicit ec: ExecutionContext)
    extends AbstractController(components) {
  private val MaxBody = 4 * 1024

  private def problem(status: Status, code: String, detail: Seq[String] = Seq.empty): Result =
    status(
      if (detail.isEmpty) Json.obj("error" -> code)
      else Json.obj("error"                -> code, "detail" -> detail)
    )

  private def admin[A](request: Request[A])(work: User => Future[Result]): Future[Result] = {
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
            work(user)
          case _ => Future.successful(problem(Forbidden, "mobile_admin_only"))
        }
    result.map(_.withHeaders(CACHE_CONTROL -> "no-store"))
  }

  private def db[A](operation: => A): Future[A] = service.storage(operation)

  def listProjects: Action[AnyContent] = Action.async { request =>
    admin(request)(_ => db(Ok(Json.obj("projects" -> campaigns.projects()))))
  }

  /** Created on, owned by the admin. */
  def createProject: Action[JsValue] = Action.async(parse.tolerantJson(MaxBody)) { request =>
    admin(request) { user =>
      MobileCampaigns.parseProject(request.body) match {
        case Left(errors) => Future.successful(problem(BadRequest, "invalid_request", errors))
        case Right((name, displayName)) =>
          db {
            if (campaigns.projectNameTaken(name)) problem(Conflict, "project_exists")
            else {
              val created =
                projects.create(MobileCampaigns.newProject(name, displayName, user.id), user)
              audit.record(
                user.id,
                "project.create",
                s"project/${created.id}",
                Some(Json.obj("name" -> name, "displayName" -> displayName, "enabled" -> true))
              )
              campaigns.project(created.id) match {
                case Some(project) => Created(Json.toJson(project))
                case None          => problem(InternalServerError, "server_error")
              }
            }
          }
      }
    }
  }

  /**
    * `{"enabled": bool}`. Answers the project and `campaigns`: its published campaigns, which
    * appear in or disappear from the app with it.
    */
  def setProjectEnabled(id: Long): Action[JsValue] = Action.async(parse.tolerantJson(MaxBody)) {
    request =>
      admin(request) { user =>
        request.body match {
          case value: JsObject if value.keys == Set("enabled") =>
            (value \ "enabled").asOpt[Boolean] match {
              case None =>
                Future.successful(
                  problem(BadRequest, "invalid_request", Seq("enabled must be a boolean"))
                )
              case Some(enabled) =>
                db {
                  campaigns.project(id) match {
                    case None => problem(NotFound, "not_found")
                    case Some(before) =>
                      if (before.enabled != enabled) {
                        projects.update(id, Json.obj("enabled" -> enabled), user)
                        audit.record(
                          user.id,
                          if (enabled) "project.enable" else "project.disable",
                          s"project/$id",
                          Some(Json.obj("enabled" -> enabled))
                        )
                      }
                      campaigns.project(id) match {
                        case None => problem(NotFound, "not_found")
                        case Some(after) =>
                          Ok(
                            Json.obj(
                              "project" -> after,
                              "campaigns" -> campaigns
                                .publishedIn(id)
                                .map(c => Json.obj("id" -> c.id, "name" -> c.name))
                            )
                          )
                      }
                  }
                }
            }
          case _ =>
            Future.successful(problem(BadRequest, "invalid_request", Seq("expected only enabled")))
        }
      }
  }

  def listCampaigns: Action[AnyContent] = Action.async { request =>
    admin(request)(_ => db(Ok(Json.obj("campaigns" -> campaigns.campaigns()))))
  }

  def getCampaign(id: Long): Action[AnyContent] = Action.async { request =>
    admin(request) { _ =>
      db(campaigns.campaign(id) match {
        case Some(campaign) => Ok(Json.toJson(campaign))
        case None           => problem(NotFound, "not_found")
      })
    }
  }
}

package controllers

import javax.inject.Inject
import org.maproulette.auth.mobile._
import org.maproulette.framework.model.{Project, User}
import org.maproulette.framework.service.ProjectService
import play.api.libs.json._
import play.api.mvc._
import scala.concurrent.{ExecutionContext, Future}

/**
  * The admin app's Campaigns list and project picker (docs/mobile-admin-api.md "Campaigns and
  * projects"). Same gate as [[MobileAdminController]]: `mobile:admin` bearer grants of current
  * super-users only. Creating a project and turning one on or off are recorded in the audit log.
  */
class MobileCampaignController @Inject() (
    components: ControllerComponents,
    settings: MobileOAuthSettings,
    service: MobileOAuthService,
    campaigns: MobileCampaignRepository,
    audit: MobileAdminRepository,
    projects: ProjectService
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
    admin(request)(_ => db(Ok(Json.obj("projects" -> campaigns.projects))))
  }

  def listCampaigns: Action[AnyContent] = Action.async { request =>
    admin(request)(_ => db(Ok(Json.obj("campaigns" -> campaigns.campaigns))))
  }

  /** `{"name", "displayName"?}`: a new project, created on, owned by the signed-in user. */
  def createProject: Action[JsValue] = Action.async(parse.tolerantJson(MaxBody)) { request =>
    admin(request) { user =>
      MobileCampaignController.parseProject(request.body) match {
        case Left(errors) => Future.successful(problem(BadRequest, "invalid_request", errors))
        case Right((name, displayName)) =>
          db {
            if (campaigns.projectNameTaken(name)) problem(Conflict, "project_exists")
            else {
              val created = projects.create(
                Project(-1, user.osmProfile.id, name, enabled = true, displayName = displayName),
                user
              )
              val body = campaigns.project(created.id).getOrElse(Json.obj("id" -> created.id))
              audit.record(user.id, "project.create", s"project:${created.id}", Some(body))
              Created(body)
            }
          }
      }
    }
  }

  /** `{"enabled": bool}`: turns a project on or off, which lists or hides all its campaigns. */
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
                    case None                                                        => problem(NotFound, "not_found")
                    case Some(before) if (before \ "enabled").as[Boolean] == enabled => Ok(before)
                    case Some(_) =>
                      projects.update(id, Json.obj("enabled" -> enabled), user)
                      val after = campaigns.project(id).get
                      audit.record(
                        user.id,
                        if (enabled) "project.enable" else "project.disable",
                        s"project:$id",
                        Some(after)
                      )
                      Ok(after)
                  }
                }
            }
          case _ =>
            Future.successful(problem(BadRequest, "invalid_request", Seq("expected only enabled")))
        }
      }
  }
}

object MobileCampaignController {
  private def text(value: JsValue, field: String, max: Int): Either[String, String] = value match {
    case JsString(s)
        if s.trim.nonEmpty && s.trim == s && s.length <= max && !s.exists(Character.isISOControl) =>
      Right(s)
    case _ =>
      Left(s"$field must be 1-$max characters, without control characters or outer spaces")
  }

  def parseProject(body: JsValue): Either[Seq[String], (String, Option[String])] = body match {
    case value: JsObject =>
      val unknown = value.keys.diff(Set("name", "displayName")).toSeq.sorted
      val name =
        value.value.get("name").map(text(_, "name", 200)).getOrElse(Left("name is required"))
      val display = value.value.get("displayName") match {
        case None | Some(JsNull) => Right(None)
        case Some(v)             => text(v, "displayName", 200).map(Some(_))
      }
      val errors = unknown
        .map(key => s"unknown field $key") ++ name.left.toSeq ++ display.left.toSeq
      if (errors.nonEmpty) Left(errors) else Right((name.toOption.get, display.toOption.get))
    case _ => Left(Seq("expected a JSON object"))
  }
}

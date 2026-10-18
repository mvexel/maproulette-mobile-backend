package controllers

import javax.inject.Inject
import org.maproulette.auth.mobile._
import org.maproulette.framework.model.User
import org.maproulette.framework.service.{ProjectService, UserService}
import play.api.libs.json._
import play.api.mvc._
import scala.concurrent.{ExecutionContext, Future}

/**
  * The super-user Organizers screen (D8, docs/mobile-admin-api.md "Organizers"). Adding someone
  * gives them an upstream project role and puts them on the organizer list in one audited
  * transaction; a change that would leave a project without an administrator is 409;
  * removing them from the list ends their `mobile:organize` access on their next request.
  */
class MobileOrganizerController @Inject() (
    components: ControllerComponents,
    settings: MobileOAuthSettings,
    service: MobileOAuthService,
    organizers: MobileOrganizerRepository,
    users: UserService,
    projects: ProjectService
)(implicit ec: ExecutionContext)
    extends AbstractController(components) {
  private val MaxBody = 4 * 1024

  private def problem(status: Status, code: String, detail: Seq[String] = Seq.empty): Result =
    status(
      if (detail.isEmpty) Json.obj("error" -> code)
      else Json.obj("error"                -> code, "detail" -> detail)
    )

  private def admin[A](request: Request[A])(work: User => Future[Result]): Future[Result] =
    MobileAdminAccess.capability(request, settings, service, MobileCapability.Operate)(work)

  private def db[A](operation: => A): Future[A] = service.storage(operation)

  // Grants are written in our transaction, not through upstream's services: drop what upstream
  // cached about the person (by id and by OSM id) and the project, so its checks see the change.
  private def changed(person: MobileOrganizerCandidate, projectId: Long): Unit = {
    users.clearCache(person.userId)
    users.clearCache(person.osmId)
    projects.clearCache(projectId)
  }

  def list: Action[AnyContent] = Action.async { request =>
    admin(request)(_ => db(Ok(Json.obj("organizers" -> organizers.list))))
  }

  /**
    * `{"osmUsername", "projectId", "role": "write"|"admin"}`. The person must have signed in to
    * MapRoulette once. Sets their role on the project (replacing any other role they held there)
    * and lists them; adding them to a second project is the same call.
    */
  def add: Action[JsValue] = Action.async(parse.tolerantJson(MaxBody)) { request =>
    admin(request) { actor =>
      MobileOrganizerController.parseAdd(request.body) match {
        case Left(errors) => Future.successful(problem(BadRequest, "invalid_request", errors))
        case Right((username, projectId, role)) =>
          db {
            (organizers.candidate(username), projects.retrieve(projectId).filterNot(_.deleted)) match {
              case (None, _) => problem(NotFound, "user_not_found")
              case (_, None) => problem(NotFound, "project_not_found")
              case (Some(person), Some(project)) =>
                organizers.add(person, project.id, role, actor.id) match {
                  case Left(code) => problem(Conflict, code)
                  case Right(_) =>
                    changed(person, project.id)
                    Ok(organizers.organizer(person.userId).get)
                }
            }
          }
      }
    }
  }

  /**
    * Off the list: their next `mobile:organize` request is refused. Their roles on projects their
    * approved requests created are dropped, other project roles stay.
    */
  def remove(userId: Long): Action[AnyContent] = Action.async { request =>
    admin(request) { actor =>
      db {
        val person = organizers.candidate(userId)
        organizers.remove(userId, actor.id) match {
          case None => problem(NotFound, "not_found")
          case Some(dropped) =>
            person.foreach(p => dropped.foreach(changed(p, _)))
            NoContent
        }
      }
    }
  }

  /** Removes their upstream role on the project; refused for its last administrator. */
  def removeProject(userId: Long, projectId: Long): Action[AnyContent] = Action.async { request =>
    admin(request) { actor =>
      db {
        organizers.organizer(userId).flatMap(_ => organizers.candidate(userId)) match {
          case None => problem(NotFound, "not_found")
          case Some(_) if projects.retrieve(projectId).forall(_.deleted) =>
            problem(NotFound, "project_not_found")
          case Some(person) =>
            organizers.removeProject(userId, projectId, actor.id) match {
              case Left(code) => problem(Conflict, code)
              case Right(_) =>
                changed(person, projectId)
                Ok(organizers.organizer(userId).get)
            }
        }
      }
    }
  }
}

object MobileOrganizerController {
  def parseAdd(body: JsValue): Either[Seq[String], (String, Long, Int)] = body match {
    case value: JsObject =>
      val unknown = value.keys.diff(Set("osmUsername", "projectId", "role")).toSeq.sorted
      val name = (value \ "osmUsername").toOption match {
        case Some(JsString(s))
            if s.trim == s && s.nonEmpty && s.length <= 255 && !s.exists(Character.isISOControl) =>
          Right(s)
        case _ => Left("osmUsername must be an OSM username")
      }
      val project = (value \ "projectId").asOpt[Long].filter(_ > 0).toRight("projectId is required")
      val role = (value \ "role")
        .asOpt[String]
        .flatMap(MobileOrganizers.role)
        .toRight("role must be write or admin")
      val errors = unknown.map(key => s"unknown field $key") ++ name.left.toSeq ++
        project.left.toSeq ++ role.left.toSeq
      if (errors.nonEmpty) Left(errors)
      else Right((name.toOption.get, project.toOption.get, role.toOption.get))
    case _ => Left(Seq("expected a JSON object"))
  }
}

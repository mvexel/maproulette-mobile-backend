package controllers

import javax.inject.Inject
import org.maproulette.auth.mobile._
import org.maproulette.framework.model.User
import org.maproulette.framework.service.{ProjectService, UserService}
import play.api.http.HeaderNames.CACHE_CONTROL
import play.api.libs.json._
import play.api.mvc._
import scala.concurrent.{ExecutionContext, Future}

/**
  * Becoming an organizer (ORG-ONB, design/organizer-onboarding.md, docs/mobile-admin-api.md
  * "Organizer requests"). The requester's routes take their own `mobile:organizer-request` grant
  * ([[MobileAdminCheck.mayRequestOrganizer]]); the decision routes are for super-users'
  * `mobile:admin` grants.
  */
class MobileOrganizerRequestController @Inject() (
    components: ControllerComponents,
    settings: MobileOAuthSettings,
    service: MobileOAuthService,
    requests: MobileOrganizerRequestRepository,
    osm: OsmAccountStats,
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

  private def refused(refusal: OrganizerRequestRefusal): Result = refusal.code match {
    case "not_found" => NotFound(Json.obj("error" -> refusal.code) ++ refusal.detail)
    case _           => Conflict(Json.obj("error" -> refusal.code) ++ refusal.detail)
  }

  private def db[A](operation: => A): Future[A] = service.storage(operation)

  private def admin[A](request: Request[A])(work: User => Future[Result]): Future[Result] =
    MobileAdminAccess.capability(request, settings, service, MobileCapability.Operate)(work)

  /**
    * The requester's own grant: `mobile:organizer-request`, held by a person signed in with OSM.
    * The bearer filter checked they may still ask.
    */
  private def requester[A](request: Request[A])(work: User => Future[Result]): Future[Result] = {
    val result =
      if (!settings.enabled) Future.successful(NotFound)
      else
        (
          request.attrs.get(MobileBearerIdentity.UserKey),
          request.attrs.get(MobileBearerIdentity.ScopesKey)
        ) match {
          case (Some(user), Some(scopes))
              if MobileScopes.isOrganizerRequest(scopes) && MobileOrganizerPolicy.eligible(user) =>
            work(user)
          case _ =>
            Future.successful(Forbidden(Json.obj("error" -> "organizer_request_grant_required")))
        }
    result.map(_.withHeaders(CACHE_CONTROL -> "no-store"))
  }

  /** `GET /api/v2/mobile-admin/organizer-request`: the signed-in person's own state. */
  def status: Action[AnyContent] = Action.async { request =>
    requester(request)(user => db(Ok(requests.status(user, settings.organizerRequestsOpen))))
  }

  /**
    * `POST /api/v2/mobile-admin/organizer-request`, `{"place", "subject", "group"?,
    * "policyAccepted": true}`. 201 with the request; 409 `already_organizer`, `request_open`,
    * `too_soon` (with `askAgainAt`) or `requests_closed`.
    */
  def create: Action[JsValue] = Action.async(parse.tolerantJson(MaxBody)) { request =>
    requester(request) { user =>
      MobileOrganizerRequestController.parseRequest(request.body) match {
        case Left(errors) => Future.successful(problem(BadRequest, "invalid_request", errors))
        case Right(_) if !settings.organizerRequestsOpen =>
          Future.successful(problem(Conflict, "requests_closed"))
        case Right(input) =>
          osm.lookup(user.osmProfile.id).flatMap { account =>
            db(requests.create(user, input, account) match {
              case Left(refusal) => refused(refusal)
              case Right(body)   => Created(body)
            })
          }
      }
    }
  }

  /** `GET /api/v2/mobile-admin/organizer-requests`: open requests, oldest first. */
  def list: Action[AnyContent] = Action.async { request =>
    admin(request)(_ => db(Ok(Json.obj("requests" -> requests.open))))
  }

  /**
    * `POST /api/v2/mobile-admin/organizer-requests/:id/approve`, `{"projectName"}`: the new
    * project, the requester's Write on it and their place on the organizer list. 409
    * `project_exists` or `not_open`.
    */
  def approve(id: Long): Action[JsValue] = Action.async(parse.tolerantJson(MaxBody)) { request =>
    admin(request) { actor =>
      MobileOrganizerRequestController.parseApprove(request.body) match {
        case Left(errors) => Future.successful(problem(BadRequest, "invalid_request", errors))
        case Right(name) =>
          db(requests.approve(id, name, actor) match {
            case Left(refusal) => refused(refusal)
            case Right(done)   =>
              // Grants were written in our transaction: drop what upstream cached.
              users.clearCache(done.userId)
              users.clearCache(done.osmId)
              users.clearCache(actor.id)
              users.clearCache(actor.osmProfile.id)
              projects.clearCache(done.projectId)
              Ok(done.request)
          })
      }
    }
  }

  /** `POST /api/v2/mobile-admin/organizer-requests/:id/decline`, `{"note"?}`. 409 `not_open`. */
  def decline(id: Long): Action[JsValue] = Action.async(parse.tolerantJson(MaxBody)) { request =>
    admin(request) { actor =>
      MobileOrganizerRequestController.parseDecline(request.body) match {
        case Left(errors) => Future.successful(problem(BadRequest, "invalid_request", errors))
        case Right(note) =>
          db(requests.decline(id, note, actor) match {
            case Left(refusal) => refused(refusal)
            case Right(body)   => Ok(body)
          })
      }
    }
  }
}

object MobileOrganizerRequestController {
  private def text(value: Option[JsValue], field: String, max: Int): Either[String, String] =
    value match {
      case Some(JsString(s))
          if s.trim.nonEmpty && s.trim == s && s.length <= max && !s.exists(
            Character.isISOControl
          ) =>
        Right(s)
      case _ =>
        Left(s"$field must be 1-$max characters, without control characters or outer spaces")
    }

  private def optionalText(value: Option[JsValue], field: String, max: Int) = value match {
    case None | Some(JsNull) => Right(None)
    case some                => text(some, field, max).map(Some(_))
  }

  private def only(value: JsObject, allowed: Set[String]): Seq[String] =
    value.keys.diff(allowed).toSeq.sorted.map(key => s"unknown field $key")

  def parseRequest(body: JsValue): Either[Seq[String], OrganizerRequestInput] = body match {
    case value: JsObject =>
      val place   = text(value.value.get("place"), "place", 200)
      val subject = text(value.value.get("subject"), "subject", 200)
      val group   = optionalText(value.value.get("group"), "group", 200)
      val policy =
        if (value.value.get("policyAccepted").contains(JsTrue)) Right(())
        else Left("policyAccepted must be true")
      val errors = only(value, Set("place", "subject", "group", "policyAccepted")) ++
        place.left.toSeq ++ subject.left.toSeq ++ group.left.toSeq ++ policy.left.toSeq
      if (errors.nonEmpty) Left(errors)
      else
        Right(OrganizerRequestInput(place.toOption.get, subject.toOption.get, group.toOption.get))
    case _ => Left(Seq("expected a JSON object"))
  }

  def parseApprove(body: JsValue): Either[Seq[String], String] = body match {
    case value: JsObject =>
      val name   = text(value.value.get("projectName"), "projectName", 200)
      val errors = only(value, Set("projectName")) ++ name.left.toSeq
      if (errors.nonEmpty) Left(errors) else Right(name.toOption.get)
    case _ => Left(Seq("expected a JSON object"))
  }

  def parseDecline(body: JsValue): Either[Seq[String], Option[String]] = body match {
    case value: JsObject =>
      val note   = optionalText(value.value.get("note"), "note", 1000)
      val errors = only(value, Set("note")) ++ note.left.toSeq
      if (errors.nonEmpty) Left(errors) else Right(note.toOption.get)
    case _ => Left(Seq("expected a JSON object"))
  }
}

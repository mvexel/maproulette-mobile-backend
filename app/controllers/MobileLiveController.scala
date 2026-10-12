package controllers

import java.time.Instant
import javax.inject.Inject
import org.maproulette.auth.mobile._
import org.maproulette.framework.model.User
import org.maproulette.provider.choice.{LivePageRepository, LivePageUpdate, LiveProgress}
import play.api.libs.json._
import play.api.mvc._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

/**
  * The live progress page (BACKLOG #12, design/live-progress.md, docs/mobile-admin-api.md "Live
  * progress page").
  *   - `GET /api/v2/live/:token`: public, no credential, link-only. Only what the page shows: no
  *     user, guest or changeset, and answers only when the page's settings show them.
  *   - `GET|PUT /api/v2/mobile-admin/challenges/:id/live` and `POST .../live/rotate`: the page's
  *     settings and token, for `mobile:admin` grants of super-users only. Changes are audited
  *     without the token.
  */
class MobileLiveController @Inject() (
    components: ControllerComponents,
    settings: MobileOAuthSettings,
    service: MobileOAuthService,
    pages: LivePageRepository,
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

  /** The public page data. An unknown or rotated token and a turned-off page are both 404. */
  def show(token: String): Action[AnyContent] = Action.async {
    val result =
      if (!settings.enabled || !MobileLiveController.TokenPattern.matches(token))
        Future.successful(problem(NotFound, "not_found"))
      else
        db {
          pages.byToken(token) match {
            case None                        => problem(NotFound, "not_found")
            case Some(page) if !page.enabled => problem(NotFound, "turned_off")
            case Some(page) =>
              pages.campaign(page.challengeId) match {
                case None => problem(NotFound, "not_found")
                case Some((name, comment)) =>
                  val people = page.eventStart
                    .filter(_ => page.endedAt.isEmpty)
                    .map(pages.people(page.challengeId, _))
                  Ok(
                    LiveProgress.json(
                      page,
                      name,
                      comment,
                      pages.stops(page.challengeId),
                      people,
                      Instant.now()
                    )
                  )
              }
          }
        }
    result.map(
      _.withHeaders(
        CACHE_CONTROL     -> "no-store",
        "X-Robots-Tag"    -> "noindex",
        "Referrer-Policy" -> "no-referrer"
      )
    )
  }

  def get(challengeId: Long): Action[AnyContent] = Action.async { request =>
    admin(request) { _ =>
      db {
        pages.byChallenge(challengeId) match {
          case Some(page)                                 => Ok(page.adminJson)
          case None if pages.challengeExists(challengeId) => problem(NotFound, "no_live_page")
          case None                                       => problem(NotFound, "not_found")
        }
      }
    }
  }

  /** Creates the page on first use; see [[MobileLiveController.parseUpdate]] for the body. */
  def update(challengeId: Long): Action[JsValue] = Action.async(parse.tolerantJson(MaxBody)) {
    request =>
      admin(request) { user =>
        MobileLiveController.parseUpdate(request.body) match {
          case Left(errors) => Future.successful(problem(BadRequest, "invalid_request", errors))
          case Right(update) =>
            db {
              if (!pages.challengeExists(challengeId)) problem(NotFound, "not_found")
              else {
                val before = pages.byChallenge(challengeId)
                val after  = pages.save(challengeId, update)
                audit.record(
                  user.id,
                  if (before.isEmpty) "live.create" else "live.update",
                  s"challenge:$challengeId",
                  Some(after.auditJson)
                )
                Ok(after.adminJson)
              }
            }
        }
      }
  }

  /** A new token: the old link answers 404 from now on. */
  def rotate(challengeId: Long): Action[AnyContent] = Action.async { request =>
    admin(request) { user =>
      db {
        pages.rotate(challengeId) match {
          case None => problem(NotFound, "no_live_page")
          case Some(page) =>
            audit.record(user.id, "live.rotate", s"challenge:$challengeId", Some(page.auditJson))
            Ok(page.adminJson)
        }
      }
    }
  }
}

object MobileLiveController {
  val TokenPattern = "[A-Za-z0-9_-]{43}".r

  private val Fields = Set("enabled", "showAnswers", "eventStart", "ended")

  private def bool(value: JsObject, field: String): Either[String, Option[Boolean]] =
    value.value.get(field) match {
      case None               => Right(None)
      case Some(JsBoolean(b)) => Right(Some(b))
      case Some(_)            => Left(s"$field must be a boolean")
    }

  /**
    * `{"enabled"?, "showAnswers"?, "eventStart"?, "ended"?}`: booleans, and `eventStart` an ISO
    * 8601 instant or null (no people count). `ended: true` keeps the first end time; false makes
    * the page live again.
    */
  def parseUpdate(body: JsValue): Either[Seq[String], LivePageUpdate] = body match {
    case value: JsObject =>
      val unknown = value.keys.diff(Fields).toSeq.sorted.map(key => s"unknown field $key")
      val start: Either[String, Option[Option[Instant]]] = value.value.get("eventStart") match {
        case None         => Right(None)
        case Some(JsNull) => Right(Some(None))
        case Some(JsString(s)) =>
          Try(Instant.parse(s)).toOption
            .map(i => Right(Some(Some(i))))
            .getOrElse(Left("eventStart must be an ISO 8601 instant, like 2026-10-18T23:00:00Z"))
        case Some(_) => Left("eventStart must be an ISO 8601 instant or null")
      }
      val enabled = bool(value, "enabled")
      val answers = bool(value, "showAnswers")
      val ended   = bool(value, "ended")
      val errors = unknown ++ Seq(enabled, answers, ended).flatMap(_.left.toOption) ++
        start.left.toOption
      if (errors.nonEmpty) Left(errors)
      else
        Right(
          LivePageUpdate(
            enabled.toOption.get,
            answers.toOption.get,
            start.toOption.get,
            ended.toOption.get
          )
        )
    case _ => Left(Seq("expected a JSON object"))
  }
}

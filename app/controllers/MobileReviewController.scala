package controllers

import javax.inject.Inject
import org.maproulette.auth.mobile._
import org.maproulette.framework.model.User
import org.maproulette.provider.choice._
import play.api.libs.json._
import play.api.mvc._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

/**
  * The review page (H1a, backend/h1/h1a-api.md, docs/mobile-admin-api.md "Review page"), reached by
  * the campaign's live page token.
  *   - `GET /api/v2/review/:token`: public and link-only. With a bearer, places also say whether
  *     this mapper skipped them. No guest ids, no times finer than a day.
  *   - `PUT|DELETE /api/v2/review/:token/places/:id/(skip|needs-visit)`: an app grant with
  *     `tasks:read`. They write only the review tables, never a task or OSM.
  */
class MobileReviewController @Inject() (
    components: ControllerComponents,
    settings: MobileOAuthSettings,
    service: MobileOAuthService,
    pages: LivePageRepository,
    reviews: ReviewRepository
)(implicit ec: ExecutionContext)
    extends AbstractController(components) {

  private def problem(status: Status, code: String): Result = status(Json.obj("error" -> code))

  private def headers(result: Future[Result]): Future[Result] =
    result.map(
      _.withHeaders(
        CACHE_CONTROL     -> "no-store",
        "X-Robots-Tag"    -> "noindex",
        "Referrer-Policy" -> "no-referrer"
      )
    )

  /** The page's campaign: its id, name and changeset comment; 404 like the live page otherwise. */
  private def campaign(token: String)(
      work: (Long, String, Option[String]) => Result
  ): Future[Result] =
    if (!settings.enabled || !MobileLiveController.TokenPattern.matches(token))
      Future.successful(problem(NotFound, "not_found"))
    else
      service.storage {
        pages.byToken(token) match {
          case None                        => problem(NotFound, "not_found")
          case Some(page) if !page.enabled => problem(NotFound, "turned_off")
          case Some(page) =>
            pages.campaign(page.challengeId) match {
              case None => problem(NotFound, "not_found")
              case Some((name, comment)) =>
                work(page.challengeId, name, comment.map(_.trim).filter(_.nonEmpty))
            }
        }
      }

  private def user[A](request: Request[A]): Option[User] =
    request.attrs.get(MobileBearerIdentity.UserKey)

  def show(token: String): Action[AnyContent] = Action.async { request =>
    headers(MobileReviewController.query(request.queryString) match {
      case None => Future.successful(problem(BadRequest, "invalid_request"))
      case Some((filter, after, limit)) =>
        campaign(token) { (challengeId, name, comment) =>
          val skipped = user(request).fold(Set.empty[Long])(u => reviews.skipped(challengeId, u.id))
          Ok(
            ReviewPage
              .json(name, comment, reviews.places(challengeId), skipped, filter, after, limit)
          )
        }
    })
  }

  private def mark(token: String, placeId: Long, request: Request[AnyContent])(
      write: (Long, Long) => Either[ReviewRefusal, JsObject]
  ): Future[Result] =
    headers(user(request) match {
      case None => Future.successful(problem(Unauthorized, "sign_in_required"))
      case Some(u) =>
        campaign(token) { (challengeId, _, _) =>
          write(challengeId, u.id) match {
            case Right(place)                    => Ok(place)
            case Left(ReviewRefusal.NotFound)    => problem(NotFound, "not_found")
            case Left(ReviewRefusal.AlreadyDone) => problem(Conflict, "done")
          }
        }
    })

  def skip(token: String, placeId: Long): Action[AnyContent] = Action.async { request =>
    mark(token, placeId, request) { (challengeId, userId) =>
      reviews.skip(challengeId, placeId, userId, on = request.method == "PUT")
    }
  }

  def needsVisit(token: String, placeId: Long): Action[AnyContent] = Action.async { request =>
    mark(token, placeId, request) { (challengeId, userId) =>
      reviews.needsVisit(challengeId, placeId, userId, on = request.method == "PUT")
    }
  }
}

object MobileReviewController {

  /** `filter`, `after` and `limit`, each at most once; None when anything else is given. */
  def query(params: Map[String, Seq[String]]): Option[(String, Option[Long], Int)] = {
    def one(key: String): Either[Unit, Option[String]] = params.get(key) match {
      case None             => Right(None)
      case Some(Seq(value)) => Right(Some(value))
      case _                => Left(())
    }
    val known = Set("filter", "after", "limit")
    if (!params.keySet.subsetOf(known)) None
    else
      (for {
        filter <- one("filter").toOption.map(_.getOrElse("all")).filter(ReviewPage.Filters)
        after <- one("after").toOption.flatMap {
          case None    => Some(None)
          case Some(v) => Try(v.toLong).toOption.filter(_ >= 0).map(Some(_))
        }
        limit <- one("limit").toOption.flatMap {
          case None    => Some(ReviewPage.DefaultLimit)
          case Some(v) => Try(v.toInt).toOption.filter(n => n >= 1 && n <= ReviewPage.MaxLimit)
        }
      } yield (filter, after, limit))
  }
}

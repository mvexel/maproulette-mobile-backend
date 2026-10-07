package controllers

import javax.inject.Inject
import org.maproulette.auth.mobile._
import org.maproulette.framework.model.User
import play.api.libs.json._
import play.api.mvc._
import scala.concurrent.{ExecutionContext, Future}

/**
  * `/api/v2/mobile-admin/...`: mobile client management and the audit log, for `mobile:admin`
  * bearer grants of current super-users only (see docs/mobile-admin-api.md). Web sessions and
  * API keys get 403 `mobile_admin_only`.
  */
class MobileAdminController @Inject() (
    components: ControllerComponents,
    settings: MobileOAuthSettings,
    service: MobileOAuthService,
    repository: MobileAdminRepository
)(implicit ec: ExecutionContext)
    extends AbstractController(components) {
  private val MaxBody = 16 * 1024

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

  def listClients: Action[AnyContent] = Action.async { request =>
    admin(request)(_ => db(Ok(Json.obj("clients" -> repository.listClients))))
  }

  def createClient: Action[JsValue] = Action.async(parse.tolerantJson(MaxBody)) { request =>
    admin(request) { user =>
      MobileAdminController.parseClient(request.body) match {
        case Left(errors) => Future.successful(problem(BadRequest, "invalid_request", errors))
        case Right(client) =>
          db {
            if (repository.createClient(client, user.id))
              Created(repository.clientJson(client))
            else problem(Conflict, "client_exists")
          }
      }
    }
  }

  def updateClient(id: String, revokeGrants: Boolean): Action[JsValue] =
    Action.async(parse.tolerantJson(MaxBody)) { request =>
      admin(request) { user =>
        MobileAdminController.parsePatch(request.body) match {
          case Left(errors) => Future.successful(problem(BadRequest, "invalid_request", errors))
          case Right(patch) if revokeGrants && !patch.enabled.contains(false) =>
            Future.successful(
              problem(BadRequest, "invalid_request", Seq("revokeGrants needs \"enabled\": false"))
            )
          case Right(patch)
              if request.attrs.get(MobileBearerIdentity.ClientKey).contains(id) &&
                (patch.enabled.contains(false) ||
                  patch.scopes.exists(!_.contains(MobileScopes.Admin))) =>
            // The admin app would lock itself out; recovering needs SQL.
            Future.successful(problem(Conflict, "self_lockout"))
          case Right(patch) =>
            db {
              repository.updateClient(id, patch, user.id, revokeGrants) match {
                case None => problem(NotFound, "not_found")
                case Some((client, revoked)) =>
                  val body = repository.clientJson(client).as[JsObject]
                  Ok(
                    if (revokeGrants) body + ("revokedGrantFamilies" -> JsNumber(revoked)) else body
                  )
              }
            }
        }
      }
    }

  def audit(limit: Int, page: Int): Action[AnyContent] = Action.async { request =>
    admin(request) { _ =>
      if (limit < 1 || limit > 200 || page < 0 || page > 100000)
        Future.successful(problem(BadRequest, "invalid_request", Seq("limit 1-200, page >= 0")))
      else
        db {
          val (entries, total) = repository.audit(limit, page * limit)
          Ok(Json.obj("items" -> entries, "page" -> page, "limit" -> limit, "total" -> total))
        }
    }
  }
}

object MobileAdminController {
  private val ClientFields = Set("id", "name", "redirectUris", "scopes", "enabled")
  private val PatchFields  = ClientFields - "id"

  private def fields(body: JsValue, allowed: Set[String]): Either[Seq[String], JsObject] =
    body match {
      case value: JsObject =>
        val unknown = value.keys.diff(allowed)
        if (unknown.isEmpty) Right(value)
        else Left(unknown.toSeq.sorted.map(key => s"unknown field $key"))
      case _ => Left(Seq("expected a JSON object"))
    }

  private def name(value: JsValue): Either[String, String] = value match {
    case JsString(text)
        if text.trim.nonEmpty && text.trim == text && text.length <= 200 &&
          !text.exists(Character.isISOControl) =>
      Right(text)
    case _ => Left("name: 1 to 200 characters, no surrounding spaces or control characters")
  }

  private def strings(value: JsValue): Option[Seq[String]] =
    value.asOpt[Seq[JsValue]].flatMap { items =>
      val texts = items.collect { case JsString(text) => text }
      if (texts.size == items.size) Some(texts) else None
    }

  private def redirects(value: JsValue): Either[String, Set[String]] = strings(value) match {
    case Some(uris)
        if uris.nonEmpty && uris.size <= 10 && uris.distinct.size == uris.size &&
          uris.forall(uri => uri.length <= 2048 && MobileOAuthSettings.validRedirect(uri)) =>
      Right(uris.toSet)
    case _ =>
      Left(
        "redirectUris: 1 to 10 distinct URIs, each https://host/... or a reverse-domain custom scheme (com.example.app:/path), without query or fragment"
      )
  }

  private def scopes(value: JsValue): Either[String, Set[String]] =
    strings(value).flatMap(items => MobileScopes.parse(items.mkString(" "))) match {
      case Some(set) => Right(set)
      case None =>
        Left(
          "scopes: [\"tasks:read\"], [\"tasks:read\",\"tasks:write\"], [\"tasks:read\",\"tasks:write\",\"osm:tagfix\"] or [\"mobile:admin\"]"
        )
    }

  private def enabled(value: JsValue): Either[String, Boolean] =
    value.asOpt[Boolean].toRight("enabled: true or false")

  private def optional[A](
      body: JsObject,
      key: String,
      read: JsValue => Either[String, A]
  ): Either[String, Option[A]] =
    (body \ key).toOption match {
      case None        => Right(None)
      case Some(value) => read(value).map(Some(_))
    }

  def parseClient(body: JsValue): Either[Seq[String], MobileClient] =
    fields(body, ClientFields).flatMap { value =>
      val id = (value \ "id").asOpt[String] match {
        case Some(text) if text.matches("[A-Za-z0-9._-]{1,100}") => Right(text)
        case _                                                   => Left("id: 1 to 100 of A-Z a-z 0-9 . _ -")
      }
      val parsed = (
        id,
        (value \ "name").toOption.toRight("name is required").flatMap(name),
        (value \ "redirectUris").toOption.toRight("redirectUris is required").flatMap(redirects),
        optional(value, "scopes", scopes),
        optional(value, "enabled", enabled)
      )
      parsed match {
        case (Right(i), Right(n), Right(r), Right(s), Right(e)) =>
          Right(
            MobileClient(i, n, r, s.getOrElse(Set(MobileScopes.Read)), e.getOrElse(true))
          )
        case (i, n, r, s, e) =>
          Left(Seq(i, n, r, s, e).collect { case Left(error) => error })
      }
    }

  def parsePatch(body: JsValue): Either[Seq[String], MobileClientPatch] =
    fields(body, PatchFields).flatMap { value =>
      if (value.keys.isEmpty) Left(Seq("nothing to change"))
      else
        (
          optional(value, "name", name),
          optional(value, "redirectUris", redirects),
          optional(value, "scopes", scopes),
          optional(value, "enabled", enabled)
        ) match {
          case (Right(n), Right(r), Right(s), Right(e)) => Right(MobileClientPatch(n, r, s, e))
          case (n, r, s, e)                             => Left(Seq(n, r, s, e).collect { case Left(error) => error })
        }
    }
}

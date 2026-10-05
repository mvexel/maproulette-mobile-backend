package controllers

import javax.inject.Inject
import java.net.{URI, URLEncoder}
import java.nio.charset.StandardCharsets
import java.time.Instant
import org.maproulette.Config
import org.maproulette.auth.mobile._
import org.maproulette.framework.service.UserService
import play.api.libs.json.Json
import play.api.libs.ws.WSClient
import play.api.mvc._
import play.twirl.api.HtmlFormat
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._
import scala.util.control.NonFatal
import scalaoauth2.provider.{InvalidClient, InvalidGrant, InvalidRequest, OAuthError}

/** A separate browser authorization flow. No existing web login routes or cookies are changed. */
class MobileOAuthController @Inject() (
    components: ControllerComponents,
    service: MobileOAuthService,
    settings: MobileOAuthSettings,
    identity: MobileOSMIdentity,
    users: UserService,
    config: Config,
    ws: WSClient
)(implicit ec: ExecutionContext)
    extends AbstractController(components) {
  private val cookieName = "mr_mobile_oauth"
  private val cookiePath = "/oauth/mobile"
  private val defaultPolicy =
    "default-src 'none'; form-action 'self'; frame-ancestors 'none'; base-uri 'none'"

  private def secure(result: Result): Result = result.withHeaders(
    CACHE_CONTROL            -> "no-store",
    PRAGMA                   -> "no-cache",
    "Referrer-Policy"        -> "no-referrer",
    "X-Content-Type-Options" -> "nosniff",
    "Content-Security-Policy" -> result.header.headers
      .getOrElse("Content-Security-Policy", defaultPolicy)
  )
  private def error(value: OAuthError): Result =
    Status(value.statusCode)(Json.obj("error" -> value.errorType))
  private def handled(operation: => Future[Result]): Future[Result] = {
    if (!settings.enabled) Future.successful(secure(NotFound))
    else {
      val result =
        try operation
        catch {
          case NonFatal(_) => Future.successful(BadRequest(Json.obj("error" -> "invalid_request")))
        }
      result.map(secure).recover {
        case NonFatal(_) => secure(InternalServerError(Json.obj("error" -> "server_error")))
      }
    }
  }
  private def encoded(value: String): String =
    URLEncoder.encode(value, StandardCharsets.UTF_8.name())
  private def appRedirect(uri: String, values: (String, String)*): Result =
    Redirect(
      uri + "?" + values
        .map { case (key, value) => encoded(key) + "=" + encoded(value) }
        .mkString("&")
    ).discardingCookies(DiscardingCookie(cookieName, path = cookiePath))
  private def browser[A](request: Request[A]): Option[String] =
    request.cookies.get(cookieName).map(_.value).filter(_.matches("[A-Za-z0-9_-]{43}"))
  private def validInteraction(value: MobileInteraction): Boolean =
    settings.clients
      .get(value.clientId)
      .exists(_.redirectUris.contains(value.redirectUri)) && value.scope == settings.scope

  def authorize: Action[AnyContent] = Action.async { request =>
    handled {
      service.authorization(request.queryString) match {
        case Left(problem) => Future.successful(error(problem))
        case Right(authorization) =>
          val state   = MobileSecrets.generate()
          val binding = MobileSecrets.generate()
          val interaction = MobileInteraction(
            MobileSecrets.hash(state),
            MobileSecrets.hash(binding),
            authorization.client.id,
            authorization.redirectUri,
            settings.scope,
            authorization.state,
            authorization.challenge,
            Instant.now().plusSeconds(settings.interactionSeconds)
          )
          service.storage(service.store.createInteraction(interaction)).map { _ =>
            val params = Seq(
              "client_id"     -> config.getOSMOauth.consumerKey.key,
              "response_type" -> "code",
              "redirect_uri"  -> settings.callbackUri,
              "scope"         -> "read_prefs",
              "state"         -> state
            )
            val destination = ws
              .url(s"${config.getOSMServer}/oauth2/authorize")
              .withQueryStringParameters(params: _*)
              .uri
              .toString
            Redirect(destination).withCookies(
              Cookie(
                cookieName,
                binding,
                maxAge = Some(settings.interactionSeconds.toInt),
                path = cookiePath,
                secure = settings.secureCookie,
                httpOnly = true,
                sameSite = Some(Cookie.SameSite.Lax)
              )
            )
          }
      }
    }
  }

  def callback: Action[AnyContent] = Action.async { request =>
    handled {
      (service.parameters(request.queryString), browser(request)) match {
        case (Right(params), Some(binding))
            if params.getOrElse("state", "").matches("[A-Za-z0-9_-]{43}") =>
          val state       = params("state")
          val stateHash   = MobileSecrets.hash(state)
          val browserHash = MobileSecrets.hash(binding)
          service.storage(service.store.claimLogin(stateHash, browserHash, Instant.now())).flatMap {
            case Some(interaction) if validInteraction(interaction) =>
              if (params.contains("error") && !params.contains("code")) {
                Future.successful(
                  appRedirect(
                    interaction.redirectUri,
                    "error" -> "access_denied",
                    "state" -> interaction.clientState
                  )
                )
              } else if (!params.contains("code") || params.contains("error")) {
                Future.successful(error(new InvalidRequest()))
              } else {
                ws.url(s"${config.getOSMServer}/oauth2/token")
                  .withFollowRedirects(false)
                  .withRequestTimeout(20.seconds)
                  .withHttpHeaders(ACCEPT -> JSON)
                  .post(
                    Map(
                      "grant_type"    -> "authorization_code",
                      "code"          -> params("code"),
                      "client_id"     -> config.getOSMOauth.consumerKey.key,
                      "client_secret" -> config.getOSMOauth.consumerKey.secret,
                      "redirect_uri"  -> settings.callbackUri
                    )
                  )
                  .flatMap { response =>
                    if (response.status != OK) Future.successful(error(new InvalidGrant()))
                    else {
                      val accessToken = (response.json \ "access_token").as[String]
                      identity.resolve(accessToken).flatMap { user =>
                        val csrf = MobileSecrets.generate()
                        service
                          .storage(
                            service.store.completeLogin(
                              stateHash,
                              browserHash,
                              user.id,
                              MobileSecrets.hash(csrf),
                              Instant.now()
                            )
                          )
                          .map {
                            case true  => consentPage(interaction, state, csrf, user.name)
                            case false => error(new InvalidGrant())
                          }
                      }
                    }
                  }
              }
            case _ => Future.successful(error(new InvalidGrant()))
          }
        case _ => Future.successful(error(new InvalidRequest()))
      }
    }
  }

  private def consentPage(
      interaction: MobileInteraction,
      state: String,
      csrf: String,
      userName: String
  ): Result = {
    def html(value: String): String = HtmlFormat.escape(value).body
    val clientName                  = settings.clients(interaction.clientId).name
    val callback                    = new URI(interaction.redirectUri)
    // Chromium applies form-action to the consent response's redirect chain too.
    val callbackSource =
      if (callback.getScheme == "https") s"https://${callback.getRawAuthority}"
      else callback.getScheme + ":"
    Ok(s"""<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Authorize MapRoulette access</title></head>
      <body><h1>Connect ${html(clientName)}?</h1><p>Signed in as ${html(userName)}.</p>
      <p>This app can read MapRoulette tasks and your basic identity. It cannot edit tasks or obtain your personal API key.</p>
      <form method="post" action="/oauth/mobile/consent">
      <input type="hidden" name="interaction" value="${html(state)}"><input type="hidden" name="csrf" value="${html(
      csrf
    )}">
      <button name="decision" value="allow" type="submit">Allow</button>
      <button name="decision" value="deny" type="submit">Cancel</button></form></body></html>""")
      .as(HTML)
      .withHeaders(
        "Content-Security-Policy" -> s"default-src 'none'; form-action 'self' $callbackSource; frame-ancestors 'none'; base-uri 'none'"
      )
  }

  def consent: Action[Map[String, Seq[String]]] =
    Action.async(parse.formUrlEncoded(maxLength = 8192)) { request =>
      handled {
        (service.parameters(request.body), browser(request)) match {
          case (Right(params), Some(binding))
              if params.getOrElse("interaction", "").matches("[A-Za-z0-9_-]{43}") &&
                params.getOrElse("csrf", "").matches("[A-Za-z0-9_-]{43}") && Set("allow", "deny")
                .contains(params.getOrElse("decision", "")) =>
            val idHash      = MobileSecrets.hash(params("interaction"))
            val browserHash = MobileSecrets.hash(binding)
            val csrfHash    = MobileSecrets.hash(params("csrf"))
            service.storage {
              service.store.getInteraction(idHash, browserHash, Instant.now()) match {
                case Some(interaction) if validInteraction(interaction) =>
                  if (params("decision") == "deny") {
                    service.store.declineInteraction(idHash, browserHash, csrfHash, Instant.now()) match {
                      case Some(value) =>
                        appRedirect(
                          value.redirectUri,
                          "error" -> "access_denied",
                          "state" -> value.clientState
                        )
                      case None => error(new InvalidGrant())
                    }
                  } else {
                    val code = MobileSecrets.generate()
                    val now  = Instant.now()
                    service.store.approveInteraction(
                      idHash,
                      browserHash,
                      csrfHash,
                      MobileSecrets.hash(code),
                      MobileSecrets.generate(),
                      now.plusSeconds(settings.codeSeconds),
                      now
                    ) match {
                      case Some(_) =>
                        appRedirect(
                          interaction.redirectUri,
                          "code"  -> code,
                          "state" -> interaction.clientState
                        )
                      case None => error(new InvalidGrant())
                    }
                  }
                case _ => error(new InvalidGrant())
              }
            }
          case _ => Future.successful(error(new InvalidRequest()))
        }
      }
    }

  def token: Action[Map[String, Seq[String]]] =
    Action.async(parse.formUrlEncoded(maxLength = 8192)) { request =>
      handled {
        service.exchange(request.body, request.headers.get(AUTHORIZATION).isDefined).map {
          case Left(problem) => error(problem)
          case Right(grant) =>
            Ok(
              Json.obj(
                "access_token"  -> grant.accessToken,
                "token_type"    -> grant.tokenType,
                "expires_in"    -> grant.expiresIn,
                "refresh_token" -> grant.refreshToken,
                "scope"         -> grant.scope
              )
            )
        }
      }
    }

  def revoke: Action[Map[String, Seq[String]]] =
    Action.async(parse.formUrlEncoded(maxLength = 8192)) { request =>
      handled {
        service.parameters(request.body) match {
          case Right(params)
              if !request.headers.hasHeader(AUTHORIZATION) && !params.contains("client_secret") &&
                settings.clients.contains(params.getOrElse("client_id", "")) && params
                .getOrElse("token", "")
                .nonEmpty =>
            service.storage {
              service.store.revoke(
                MobileSecrets.hash(params("token")),
                params("client_id"),
                Instant.now()
              )
              Ok
            }
          case _ => Future.successful(error(new InvalidClient()))
        }
      }
    }

  def me: Action[AnyContent] = Action.async { request =>
    handled {
      val token = request.headers.get(AUTHORIZATION).collect {
        case value if value.matches("(?i)Bearer [A-Za-z0-9_-]{43}") => value.substring(7)
      }
      token match {
        case None => Future.successful(Unauthorized(Json.obj("error" -> "invalid_token")))
        case Some(value) =>
          service.authenticate(value).flatMap {
            case Some(grant) =>
              service.storage {
                users.retrieve(grant.userId) match {
                  case Some(user) if !user.guest =>
                    Ok(
                      Json.obj(
                        "id"          -> user.id,
                        "osmId"       -> user.osmProfile.id,
                        "displayName" -> user.name,
                        "scope"       -> grant.scope
                      )
                    )
                  case _ => Unauthorized(Json.obj("error" -> "invalid_token"))
                }
              }
            case None => Future.successful(Unauthorized(Json.obj("error" -> "invalid_token")))
          }
      }
    }
  }
}

package org.maproulette.auth.mobile

import akka.actor.ActorSystem
import javax.inject.{Inject, Singleton}
import java.nio.charset.StandardCharsets
import java.security.{MessageDigest, SecureRandom}
import java.time.Instant
import java.util.{Base64, Date}
import scala.concurrent.{ExecutionContext, Future}
import scalaoauth2.provider._

object MobileSecrets {
  private val random = new SecureRandom()
  def generate(): String = {
    val bytes = new Array[Byte](32)
    random.nextBytes(bytes)
    Base64.getUrlEncoder.withoutPadding().encodeToString(bytes)
  }
  def hash(value: String): String =
    MessageDigest
      .getInstance("SHA-256")
      .digest(value.getBytes(StandardCharsets.UTF_8))
      .map(byte => f"${byte & 0xff}%02x")
      .mkString
}

case class MobileAuthorization(
    client: MobileClient,
    redirectUri: String,
    state: String,
    challenge: String,
    scope: String
)

@Singleton
class MobileOAuthService @Inject() (
    val store: MobileOAuthStore,
    val settings: MobileOAuthSettings,
    actorSystem: ActorSystem
) {
  // Older production config files need no mobile dispatcher while the feature is disabled.
  private implicit lazy val executionContext: ExecutionContext =
    actorSystem.dispatchers.lookup("mobile-oauth-dispatcher")
  private val endpoint = new TokenEndpoint {
    override val handlers: Map[String, GrantHandler] = Map(
      "authorization_code" -> new AuthorizationCode(),
      "refresh_token"      -> new RefreshToken()
    )
  }

  def storage[A](operation: => A): Future[A] = Future(operation)

  def authenticate(accessToken: String): Future[Option[MobileGrant]] =
    if (!settings.enabled || !accessToken.matches("[A-Za-z0-9_-]{43}")) Future.successful(None)
    else
      storage {
        store
          .authenticate(MobileSecrets.hash(accessToken), Instant.now())
          .filter(grant => settings.allowedScopes(grant.clientId, grant.scope).isDefined)
      }

  def parameters(values: Map[String, Seq[String]]): Either[OAuthError, Map[String, String]] = {
    if (values.exists { case (_, items) => items.size != 1 || items.head.length > 2048 })
      Left(new InvalidRequest("Invalid or duplicate parameters"))
    else Right(values.map { case (key, items) => key -> items.head })
  }

  def authorization(values: Map[String, Seq[String]]): Either[OAuthError, MobileAuthorization] = {
    parameters(values).flatMap { params =>
      settings.clients.get(params.getOrElse("client_id", "")) match {
        case None => Left(new InvalidClient())
        case Some(client) =>
          val redirect  = params.getOrElse("redirect_uri", "")
          val state     = params.getOrElse("state", "")
          val challenge = params.getOrElse("code_challenge", "")
          val scopes    = MobileScopes.parse(params.getOrElse("scope", ""))
          if (!client.redirectUris.contains(redirect))
            Left(new InvalidRequest("Invalid redirect URI"))
          else if (params.get("response_type") != Some("code"))
            Left(new InvalidRequest("Only code response type is supported"))
          else if (!scopes.exists(_.subsetOf(client.scopes))) Left(new InvalidScope())
          else if (state.isEmpty || params.get("code_challenge_method") != Some("S256") || !challenge
                     .matches("[A-Za-z0-9_-]{43}"))
            Left(new InvalidRequest("State and S256 PKCE are required"))
          else
            Right(
              MobileAuthorization(
                client,
                redirect,
                state,
                challenge,
                MobileScopes.format(scopes.get)
              )
            )
      }
    }
  }

  def exchange(
      values: Map[String, Seq[String]],
      hasAuthorizationHeader: Boolean
  ): Future[Either[OAuthError, GrantHandlerResult[MobileGrant]]] = {
    val validated = parameters(values).flatMap { params =>
      val client = settings.clients.get(params.getOrElse("client_id", ""))
      if (hasAuthorizationHeader || params.contains("client_secret") || client.isEmpty)
        Left(new InvalidClient())
      else
        params.get("grant_type") match {
          case Some("authorization_code") =>
            if (!params.getOrElse("code_verifier", "").matches("[A-Za-z0-9._~-]{43,128}"))
              Left(new InvalidGrant("Invalid PKCE verifier"))
            else if (!client.get.redirectUris.contains(params.getOrElse("redirect_uri", "")))
              Left(new InvalidGrant("Invalid redirect URI"))
            else Right(params)
          case Some("refresh_token") =>
            if (params.get("scope").exists(MobileScopes.parse(_).isEmpty)) Left(new InvalidScope())
            else Right(params)
          case _ => Left(new UnsupportedGrantType())
        }
    }
    validated match {
      case Left(error) => Future.successful(Left(error))
      case Right(params) =>
        endpoint.handleRequest(
          new AuthorizationRequest(
            Map.empty,
            params.map { case (key, value) => key -> Seq(value) }
          ),
          new Handler(params)
        )
    }
  }

  private class Handler(params: Map[String, String]) extends AuthorizationHandler[MobileGrant] {
    override def validateClient(
        credential: Option[ClientCredential],
        request: AuthorizationRequest
    ): Future[Boolean] =
      Future.successful(
        credential.exists(c => settings.clients.contains(c.clientId) && c.clientSecret.isEmpty)
      )

    override def findUser(
        credential: Option[ClientCredential],
        request: AuthorizationRequest
    ): Future[Option[MobileGrant]] =
      Future.successful(None)

    private def authInfo(grant: MobileGrant): AuthInfo[MobileGrant] = AuthInfo(
      grant,
      Some(grant.clientId),
      Some(grant.scope),
      Some(grant.redirectUri),
      Some(grant.codeChallenge),
      Some(S256)
    )

    override def findAuthInfoByCode(code: String): Future[Option[AuthInfo[MobileGrant]]] = storage {
      store.findCode(MobileSecrets.hash(code), Instant.now()).map(authInfo)
    }
    override def findAuthInfoByRefreshToken(token: String): Future[Option[AuthInfo[MobileGrant]]] =
      storage {
        store.findRefresh(MobileSecrets.hash(token), Instant.now()).map(authInfo)
      }
    override def getStoredAccessToken(info: AuthInfo[MobileGrant]): Future[Option[AccessToken]] =
      Future.successful(None)

    // Single use is enforced together with issuance in the repository transaction.
    // The library's separate post-issuance deletion callback is intentionally inert.
    override def deleteAuthCode(code: String): Future[Unit] = Future.successful(())

    private def issue(
        operation: (TokenHashes, Instant) => Option[MobileGrant]
    ): Future[AccessToken] = storage {
      val now     = Instant.now()
      val access  = MobileSecrets.generate()
      val refresh = MobileSecrets.generate()
      val hashes = TokenHashes(
        MobileSecrets.hash(access),
        MobileSecrets.hash(refresh),
        now.plusSeconds(settings.accessSeconds),
        now.plusSeconds(settings.refreshSeconds)
      )
      // Repository returns after committing replay revocation; throwing here cannot roll it back.
      val grant = operation(hashes, now).getOrElse(throw new InvalidGrant())
      AccessToken(
        access,
        Some(refresh),
        Some(grant.scope),
        Some(settings.accessSeconds),
        Date.from(now)
      )
    }

    override def createAccessToken(info: AuthInfo[MobileGrant]): Future[AccessToken] = issue {
      (pair, now) =>
        val grant = info.user
        store.redeemCode(
          MobileSecrets.hash(params("code")),
          grant.clientId,
          grant.redirectUri,
          grant.codeChallenge,
          pair,
          now
        )
    }
    override def refreshAccessToken(
        info: AuthInfo[MobileGrant],
        refreshToken: String
    ): Future[AccessToken] = {
      // A refresh may restate the grant's scope but never widen or narrow it.
      val requested = params.get("scope").flatMap(MobileScopes.parse)
      if (requested.exists(scopes => !MobileScopes.parse(info.user.scope).contains(scopes)))
        Future.failed(new InvalidScope())
      else
        issue { (pair, now) =>
          store.rotate(MobileSecrets.hash(refreshToken), info.user.clientId, pair, now)
        }
    }
  }
}

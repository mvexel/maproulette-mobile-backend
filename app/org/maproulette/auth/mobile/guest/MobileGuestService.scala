package org.maproulette.auth.mobile.guest

import akka.actor.ActorSystem
import java.time.{Duration, Instant}
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.{Inject, Singleton}
import org.maproulette.auth.mobile.{
  MobileClientRegistry,
  MobileOAuthSettings,
  MobileScopes,
  MobileSecrets
}
import scala.concurrent.{ExecutionContext, Future}

/** A newly registered guest. The secret is returned to the app once and never stored. */
case class GuestRegistration(guest: MobileGuest, secret: String)

/** A guest access token, scope `guest`, without a refresh token. */
case class GuestAccessToken(token: String, expiresIn: Long)

/** OAuth-style refusals for the guest routes on the token endpoint. */
sealed abstract class GuestError(val status: Int, val code: String)
object GuestError {
  case object InvalidRequest extends GuestError(400, "invalid_request")
  case object InvalidClient  extends GuestError(401, "invalid_client")
  case object InvalidGrant   extends GuestError(400, "invalid_grant")
  case object GuestClaimed   extends GuestError(400, "guest_claimed")
  case object RateLimited    extends GuestError(429, "rate_limited")
}

/** What the bearer filter needs from guests; [[MobileGuestAuth.Disabled]] where guests are off. */
@com.google.inject.ImplementedBy(classOf[MobileGuestService])
trait MobileGuestAuth {
  def enabled: Boolean
  def authenticate(accessToken: String): Future[Option[MobileGuest]]
}

object MobileGuestAuth {
  val Disabled: MobileGuestAuth = new MobileGuestAuth {
    def enabled: Boolean                                               = false
    def authenticate(accessToken: String): Future[Option[MobileGuest]] = Future.successful(None)
  }
}

/**
  * Deferred sign-up guests: registration, guest access tokens and their authentication. The guest
  * secret only mints short-lived access tokens; requests carry the access token.
  */
@Singleton
class MobileGuestService @Inject() (
    store: MobileGuestStore,
    settings: MobileOAuthSettings,
    clients: MobileClientRegistry,
    actorSystem: ActorSystem
) extends MobileGuestAuth {
  // Blocking JDBC runs on the mobile OAuth dispatcher, as in MobileOAuthService.
  private implicit lazy val executionContext: ExecutionContext =
    actorSystem.dispatchers.lookup("mobile-oauth-dispatcher")
  val GrantType = "urn:maproulette:grant-type:guest"

  /** Pending answers are kept this long after the last one; a new guest gets the same window. */
  val retention: Duration = Duration.ofDays(30)

  /** Registrations per client IP per hour. Sized for one shared venue Wi-Fi with a few dozen phones. */
  val registrationsPerHour = 100

  private val registrations = new ConcurrentHashMap[String, (Long, Int)]()

  def enabled: Boolean = settings.guestsEnabled

  private def storage[A](operation: => A): Future[A] = Future(operation)

  /** Counts this registration; false once the IP has used up the current hour. */
  private[guest] def admit(ip: String, now: Instant): Boolean = {
    val hour = now.getEpochSecond / 3600
    // Forget earlier hours so the map stays bounded by the number of IPs seen in one hour.
    registrations.entrySet().removeIf(_.getValue._1 != hour)
    registrations
      .compute(
        ip,
        (_, value) =>
          if (value == null || value._1 != hour) (hour, 1)
          else (hour, value._2 + 1)
      )
      ._2 <= registrationsPerHour
  }

  private def guestClient(clientId: String): Boolean =
    clients
      .get(clientId)
      .exists(client => client.enabled && client.scopes.contains(MobileScopes.Guest))

  def register(clientId: String, ip: String): Future[Either[GuestError, GuestRegistration]] = {
    val now = Instant.now()
    if (!guestClient(clientId)) Future.successful(Left(GuestError.InvalidClient))
    else if (!admit(ip, now)) Future.successful(Left(GuestError.RateLimited))
    else {
      val secret = MobileSecrets.generate()
      storage {
        Right(
          GuestRegistration(
            store.create(
              UUID.randomUUID(),
              clientId,
              MobileSecrets.hash(secret),
              now.plus(retention),
              now
            ),
            secret
          )
        )
      }
    }
  }

  /** The `urn:maproulette:grant-type:guest` token request: guest id and secret for an access token. */
  def token(params: Map[String, String]): Future[Either[GuestError, GuestAccessToken]] = {
    val clientId = params.getOrElse("client_id", "")
    val id       = scala.util.Try(UUID.fromString(params.getOrElse("guest_id", ""))).toOption
    val secret   = params.getOrElse("guest_secret", "")
    if (!guestClient(clientId)) Future.successful(Left(GuestError.InvalidClient))
    else if (id.isEmpty || !secret.matches("[A-Za-z0-9_-]{43}"))
      Future.successful(Left(GuestError.InvalidGrant))
    else {
      val now    = Instant.now()
      val access = MobileSecrets.generate()
      storage {
        store.issueToken(
          id.get,
          clientId,
          MobileSecrets.hash(secret),
          MobileSecrets.hash(access),
          now.plusSeconds(settings.accessSeconds),
          now
        ) match {
          case Right(_)                             => Right(GuestAccessToken(access, settings.accessSeconds))
          case Left(GuestTokenProblem.Claimed)      => Left(GuestError.GuestClaimed)
          case Left(GuestTokenProblem.InvalidGrant) => Left(GuestError.InvalidGrant)
        }
      }
    }
  }

  /** The guest behind a bearer token, while guests are enabled and its client still allows them. */
  def authenticate(accessToken: String): Future[Option[MobileGuest]] =
    if (!enabled || !accessToken.matches("[A-Za-z0-9_-]{43}")) Future.successful(None)
    else
      storage {
        store
          .authenticate(MobileSecrets.hash(accessToken), Instant.now())
          .filter(g => guestClient(g.clientId))
      }

  def delete(guest: MobileGuest): Future[Boolean] = storage(store.delete(guest.id, Instant.now()))
}

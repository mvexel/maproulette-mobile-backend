package org.maproulette.auth.mobile

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import javax.inject.{Inject, Provider, Singleton}
import org.joda.time.DateTime
import org.maproulette.framework.model.{Location, OSMProfile, User}
import org.maproulette.framework.service.UserService
import play.api.Configuration

/**
  * The stage service key (SK): one static bearer key, so agents can import and publish test
  * challenges on stage without an admin sign-in (upstream disabled API keys, and admin refresh
  * tokens rotate). It acts as one dedicated super-user, reaches only [[MobileServiceRoutes]], and
  * works only on the stage deployment: anywhere else a configured key is ignored with a warning.
  * Config holds only the key's SHA-256 (`MR_STAGE_SERVICE_KEY_SHA256`), never the key.
  */
@com.google.inject.ImplementedBy(classOf[MobileStageServiceKey])
trait MobileServiceKey {

  /** Whether this bearer value is the service key; constant time, false when inactive. */
  def matches(token: String): Boolean

  /** The dedicated service user, created and made a super-user on first use. */
  def user(): Option[User]
}

object MobileServiceKey {
  val StageDeployment = "stage"
  val StageOrigin     = "https://mr-stage.osm.lol"
  // Not a real OSM account: negative ids never collide with osm.org users (upstream's own system
  // super-user is -999).
  val ServiceOsmId = -1001L
  val ServiceName  = "Street Tally stage service"

  object Off extends MobileServiceKey {
    def matches(token: String): Boolean = false
    def user(): Option[User]            = None
  }

  def digest(token: String): Array[Byte] =
    MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8))

  /**
    * The configured hash, when the key may be used here: only on the stage deployment at the stage
    * origin, whatever else the environment says. Left is why a configured key is ignored.
    */
  def active(
      hash: Option[String],
      deployment: Option[String],
      origin: String
  ): Option[Either[String, Array[Byte]]] =
    hash.map(_.trim.toLowerCase(java.util.Locale.ROOT)).filter(_.nonEmpty).map { value =>
      if (!value.matches("[0-9a-f]{64}")) Left("the hash is not 64 hex characters")
      else if (!deployment.contains(StageDeployment) || origin != StageOrigin)
        Left(
          s"this is not the stage deployment (deployment ${deployment.getOrElse("unset")}, origin $origin)"
        )
      else Right(value.grouped(2).map(Integer.parseInt(_, 16).toByte).toArray)
    }
}

@Singleton
class MobileStageServiceKey @Inject() (
    configuration: Configuration,
    users: Provider[UserService],
    provisioner: Provider[MobileUserProvisioner]
) extends MobileServiceKey {
  private val logger = play.api.Logger(getClass)

  private val hash: Option[Array[Byte]] =
    MobileServiceKey.active(
      configuration.getOptional[String]("mobileOAuth.serviceKey.sha256"),
      configuration.getOptional[String]("mobileOAuth.deployment"),
      configuration.getOptional[String]("maproulette.publicOrigin").getOrElse("")
    ) match {
      case Some(Right(value)) =>
        logger.info("Stage service key is configured")
        Some(value)
      case Some(Left(reason)) =>
        logger.warn(s"Stage service key ignored: $reason")
        None
      case None => None
    }

  def matches(token: String): Boolean =
    hash.exists(MessageDigest.isEqual(_, MobileServiceKey.digest(token)))

  private lazy val provisioned: Option[User] = hash.map { _ =>
    val user = provisioner
      .get()
      .resolve(
        User(
          -1,
          DateTime.now(),
          DateTime.now(),
          OSMProfile(
            MobileServiceKey.ServiceOsmId,
            MobileServiceKey.ServiceName,
            "Imports and publishes test challenges on stage (service key).",
            "",
            Location(0, 0),
            DateTime.now(),
            ""
          ),
          List.empty
        )
      )
    if (!users.get().promoteUserToSuperUser(user, User.superUser))
      logger.error(s"Stage service user uid=${user.id} could not be made a super-user")
    users.get().retrieve(user.id).getOrElse(user)
  }

  def user(): Option[User] = provisioned
}

/**
  * What the service key reaches: creating a challenge, its line-by-line import and publishing it,
  * plus the reads those need. A subset of [[MobileAdminRoutes]].
  */
object MobileServiceRoutes {
  private val challenge = "/api/v2/challenge/[0-9]+"

  def permits(method: String, path: String, query: Map[String, Seq[String]]): Boolean =
    MobileAdminRoutes.permits(method, path, query) && (method match {
      case "GET" =>
        path.matches(challenge) || path.matches(s"$challenge/tasks") ||
          Set("/api/v2/mobile-admin/campaigns", "/api/v2/mobile-admin/projects").contains(path)
      case "POST" => path == "/api/v2/challenge"
      case "PUT"  => path.matches(challenge) || path.matches(s"$challenge/addFileTasks")
      case _      => false
    })
}

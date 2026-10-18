package org.maproulette.auth.mobile

import anorm.SqlParser._
import anorm._
import java.sql.Connection
import java.time.Instant
import java.time.temporal.ChronoUnit
import javax.inject.{Inject, Singleton}
import org.maproulette.Config
import org.maproulette.data.Actions
import org.maproulette.framework.model.{Grant, GrantTarget, Grantee, User}
import play.api.Logger
import play.api.db.Database
import play.api.libs.json._
import play.api.libs.ws.WSClient
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

/**
  * Who may ask to become an organizer (design/organizer-onboarding.md §1). Martijn's call, so it
  * is one setting, `mobileOAuth.organizers.requests`: `review` (option A, the default: anyone signed
  * in with OSM may ask, a super-user approves each request) or `closed` (no new requests). Anything
  * else is `closed`.
  */
object MobileOrganizerPolicy {
  val Review = "review"
  val Closed = "closed"

  /** The people who may ask at all: signed in with OSM, not a guest. */
  def eligible(user: User): Boolean = user.id > 0 && !user.guest && user.osmProfile.id > 0

  /** A declined user may ask again this long after the decline (§2.5). */
  val AskAgainAfterDays = 30L
}

/** The OSM account as OSM reports it when the request is made; unknown parts are None. */
case class OsmAccount(created: Option[Instant], changesets: Option[Int])

object OsmAccount {
  val Unknown: OsmAccount = OsmAccount(None, None)

  /** OSM's public `GET /api/0.6/user/{id}.json`. */
  def parse(body: JsValue): OsmAccount =
    OsmAccount(
      (body \ "user" \ "account_created")
        .asOpt[String]
        .flatMap(value => scala.util.Try(Instant.parse(value)).toOption),
      (body \ "user" \ "changesets" \ "count").asOpt[Int].filter(_ >= 0)
    )
}

/** Looks up an OSM account's age and changeset count; never fails, Unknown when OSM can't say. */
@com.google.inject.ImplementedBy(classOf[OsmAccountLookup])
trait OsmAccountStats {
  def lookup(osmId: Long): Future[OsmAccount]
}

@Singleton
class OsmAccountLookup @Inject() (ws: WSClient, config: Config)(implicit ec: ExecutionContext)
    extends OsmAccountStats {
  private val logger = Logger(this.getClass)

  def lookup(osmId: Long): Future[OsmAccount] =
    ws.url(s"${config.getOSMServer}/api/0.6/user/$osmId.json")
      .withRequestTimeout(5.seconds)
      .get()
      .map(response =>
        if (response.status == 200) OsmAccount.parse(response.json)
        else {
          logger
            .warn(s"OSM user $osmId: HTTP ${response.status}, request kept without account stats")
          OsmAccount.Unknown
        }
      )
      .recover {
        case e: Exception =>
          logger.warn(
            s"OSM user $osmId: ${e.getClass.getSimpleName}, request kept without account stats"
          )
          OsmAccount.Unknown
      }
}

/** A request's answers (§2.1). */
case class OrganizerRequestInput(place: String, subject: String, group: Option[String])

/** Why a request or a decision was refused: the error code and anything the client should show. */
case class OrganizerRequestRefusal(code: String, detail: JsObject = Json.obj())

/** What approving changed, for the caller's cache clearing. */
case class OrganizerApproval(
    request: JsObject,
    userId: Long,
    osmId: Long,
    projectId: Long
)

/**
  * Organizer requests (evolution 151, ORG-ONB). Approving creates the project, gives its approver
  * Admin (as upstream does for a project's creator) and the requester Write, lists the requester
  * as an organizer, closes the request and audits it, all in one transaction. Grants are written
  * as upstream's GrantRepository writes them, and the caller clears upstream's user and project
  * caches after commit.
  */
@Singleton
class MobileOrganizerRequestRepository @Inject() (db: Database, audit: MobileAdminRepository) {
  import MobileOrganizerPolicy._

  private val columns =
    "r.id, r.user_id, r.place, r.subject, r.group_name, r.state, r.created_at, r.decided_at, r.note, r.project_id"

  private val own: RowParser[JsObject] =
    (long("id") ~ str("place") ~ str("subject") ~ get[Option[String]]("group_name") ~
      str("state") ~ get[java.util.Date]("created_at") ~ get[Option[java.util.Date]]("decided_at") ~
      get[Option[String]]("note") ~ get[Option[Long]]("project_id")).map {
      case id ~ place ~ subject ~ group ~ state ~ created ~ decided ~ note ~ project =>
        Json.obj(
          "id"        -> id,
          "state"     -> state,
          "place"     -> place,
          "subject"   -> subject,
          "group"     -> group,
          "createdAt" -> created.toInstant,
          "decidedAt" -> decided.map(_.toInstant),
          "note"      -> note,
          "projectId" -> project
        )
    }

  private def latest(userId: Long)(implicit c: Connection): Option[JsObject] =
    SQL(s"""SELECT $columns FROM mobile_organizer_requests r
            WHERE r.user_id = {user} AND r.state <> 'void'
            ORDER BY r.created_at DESC, r.id DESC LIMIT 1""")
      .on("user" -> userId)
      .as(own.singleOpt)

  /** When a declined user may ask again, if that is still in the future. */
  private def askAgainAt(userId: Long, now: Instant)(implicit c: Connection): Option[Instant] =
    SQL"""SELECT MAX(decided_at) FROM mobile_organizer_requests
          WHERE user_id = $userId AND state = 'declined'"""
      .as(scalar[java.util.Date].singleOpt)
      .flatMap(Option(_))
      .map(_.toInstant.plus(AskAgainAfterDays, ChronoUnit.DAYS))
      .filter(_.isAfter(now))

  private def organizer(userId: Long)(implicit c: Connection): Boolean =
    SQL"""SELECT EXISTS(SELECT 1 FROM mobile_memberships
          WHERE user_id = $userId AND role = ${MobileOrganizers.Role})"""
      .as(scalar[Boolean].single)

  /**
    * The requester's own view (§2.2 to §2.5): whether they are an organizer, their latest request
    * that is not void, and whether and from when they may ask.
    */
  def status(user: User, requestsOpen: Boolean, now: Instant = Instant.now()): JsObject =
    db.withConnection { implicit c =>
      val listed  = organizer(user.id)
      val request = latest(user.id)
      val again   = askAgainAt(user.id, now)
      val open    = request.exists(r => (r \ "state").as[String] == "open")
      Json.obj(
        "name"       -> user.osmProfile.displayName,
        "osmId"      -> user.osmProfile.id,
        "organizer"  -> listed,
        "canRequest" -> (requestsOpen && eligible(user) && !listed && !open && again.isEmpty),
        "askAgainAt" -> again,
        "request"    -> request
      )
    }

  /**
    * A new open request. Refused when they are listed already (`already_organizer`), have an
    * open one (`request_open`) or were declined less than 30 days ago (`too_soon`, with
    * `askAgainAt`).
    */
  def create(
      user: User,
      input: OrganizerRequestInput,
      account: OsmAccount,
      now: Instant = Instant.now()
  ): Either[OrganizerRequestRefusal, JsObject] =
    db.withTransaction { implicit c =>
      // One request at a time per person: serialises the checks below with the insert.
      SQL("SELECT pg_advisory_xact_lock(hashtext({key}))")
        .on("key" -> s"mobile_organizer_request:${user.id}")
        .execute()
      val open = SQL"""SELECT EXISTS(SELECT 1 FROM mobile_organizer_requests
                       WHERE user_id = ${user.id} AND state = 'open')""".as(scalar[Boolean].single)
      if (organizer(user.id)) Left(OrganizerRequestRefusal("already_organizer"))
      else if (open) Left(OrganizerRequestRefusal("request_open"))
      else
        askAgainAt(user.id, now) match {
          case Some(at) => Left(OrganizerRequestRefusal("too_soon", Json.obj("askAgainAt" -> at)))
          case None =>
            val created =
              account.created.orElse(Some(Instant.ofEpochMilli(user.osmProfile.created.getMillis)))
            val stamp   = java.sql.Timestamp.from(now)
            val id      = SQL"""INSERT INTO mobile_organizer_requests
                  (user_id, place, subject, group_name, policy_accepted_at, osm_account_created,
                   osm_changesets, created_at)
                  VALUES (${user.id}, ${input.place}, ${input.subject}, ${input.group}, $stamp,
                    ${created.map(java.sql.Timestamp.from)}, ${account.changesets}, $stamp)
                  RETURNING id""".as(scalar[Long].single)
            val request = latest(user.id).get
            audit.record(
              user.id,
              "organizer.request",
              s"organizer_request:$id",
              None,
              Some(request)
            )
            Right(request)
        }
    }

  /** Open requests for the super-user Organizers screen (§2.3), oldest first. */
  def open: Seq[JsObject] = db.withConnection { implicit c =>
    SQL"""SELECT r.id, r.user_id, u.osm_id, u.name, r.place, r.subject, r.group_name,
            r.osm_account_created, r.osm_changesets, r.policy_accepted_at, r.created_at
          FROM mobile_organizer_requests r JOIN users u ON u.id = r.user_id
          WHERE r.state = 'open' ORDER BY r.created_at, r.id"""
      .as(
        (long("id") ~ long("user_id") ~ long("osm_id") ~ str("name") ~ str("place") ~
          str("subject") ~ get[Option[String]]("group_name") ~
          get[Option[java.util.Date]]("osm_account_created") ~ get[Option[Int]]("osm_changesets") ~
          get[java.util.Date]("policy_accepted_at") ~ get[java.util.Date]("created_at")).map {
          case id ~ userId ~ osmId ~ name ~ place ~ subject ~ group ~ accountCreated ~ changesets ~
                policy ~ created =>
            Json.obj(
              "id"     -> id,
              "userId" -> userId,
              "osmId"  -> osmId,
              "name"   -> name,
              "osmAccount" -> Json.obj(
                "createdAt"  -> accountCreated.map(_.toInstant),
                "changesets" -> changesets
              ),
              "place"            -> place,
              "subject"          -> subject,
              "group"            -> group,
              "policyAcceptedAt" -> policy.toInstant,
              "createdAt"        -> created.toInstant
            )
        }.*
      )
  }

  private def grant(userId: Long, role: Int, projectId: Long)(implicit c: Connection): Unit = {
    val description =
      Grant(-1, "", Grantee.user(userId), role, GrantTarget.project(projectId)).description()
    SQL"""INSERT INTO grants (name, grantee_id, grantee_type, role, object_id, object_type)
          VALUES ($description, $userId, ${Actions.ITEM_TYPE_USER}, $role, $projectId,
            ${Actions.ITEM_TYPE_PROJECT})""".executeUpdate()
    ()
  }

  private def lockOpen(id: Long)(implicit c: Connection): Either[OrganizerRequestRefusal, Long] =
    SQL"SELECT user_id, state FROM mobile_organizer_requests WHERE id = $id FOR UPDATE"
      .as((long("user_id") ~ str("state")).singleOpt) match {
      case None => Left(OrganizerRequestRefusal("not_found"))
      case Some(_ ~ s) if s != "open" =>
        Left(OrganizerRequestRefusal("not_open", Json.obj("state" -> s)))
      case Some(userId ~ _) => Right(userId)
    }

  /**
    * Approves an open request (§2.3): a project with this name, owned by the approver, Admin for
    * the approver, Write for the requester, the organizer list, the request closed and audited.
    * `project_exists` when the name is taken, `not_open` when it was decided already.
    */
  def approve(
      id: Long,
      projectName: String,
      actor: User,
      now: Instant = Instant.now()
  ): Either[OrganizerRequestRefusal, OrganizerApproval] =
    db.withTransaction { implicit c =>
      lockOpen(id).flatMap { userId =>
        // The first write: on a taken name nothing has been written yet.
        SQL"""INSERT INTO projects (name, owner_id, display_name, description, enabled, is_virtual,
                featured, require_confirmation)
              VALUES ($projectName, ${actor.osmProfile.id}, $projectName, '', true, false, false, false)
              ON CONFLICT DO NOTHING RETURNING id""".as(scalar[Long].singleOpt) match {
          case None => Left(OrganizerRequestRefusal("project_exists"))
          case Some(projectId) =>
            val osmId = SQL"SELECT osm_id FROM users WHERE id = $userId".as(scalar[Long].single)
            grant(actor.id, Grant.ROLE_ADMIN, projectId)
            grant(userId, Grant.ROLE_WRITE_ACCESS, projectId)
            SQL"""INSERT INTO mobile_memberships(user_id, role, granted_by)
                  VALUES ($userId, ${MobileOrganizers.Role}, ${actor.id})
                  ON CONFLICT DO NOTHING""".executeUpdate()
            SQL"""UPDATE mobile_organizer_requests SET state = 'approved', decided_by = ${actor.id},
                    decided_at = ${java.sql.Timestamp.from(now)}, project_id = $projectId
                  WHERE id = $id""".executeUpdate()
            val request = SQL(s"SELECT $columns FROM mobile_organizer_requests r WHERE r.id = {id}")
              .on("id" -> id)
              .as(own.single)
            audit.record(
              actor.id,
              "organizer.request.approve",
              s"organizer_request:$id",
              None,
              Some(request + ("userId" -> JsNumber(userId)))
            )
            Right(OrganizerApproval(request, userId, osmId, projectId))
        }
      }
    }

  /** Declines an open request with an optional note shown to the requester (§2.5). */
  def decline(
      id: Long,
      note: Option[String],
      actor: User,
      now: Instant = Instant.now()
  ): Either[OrganizerRequestRefusal, JsObject] =
    db.withTransaction { implicit c =>
      lockOpen(id).map { userId =>
        SQL"""UPDATE mobile_organizer_requests SET state = 'declined', decided_by = ${actor.id},
                decided_at = ${java.sql.Timestamp.from(now)}, note = $note
              WHERE id = $id""".executeUpdate()
        val request = SQL(s"SELECT $columns FROM mobile_organizer_requests r WHERE r.id = {id}")
          .on("id" -> id)
          .as(own.single)
        audit.record(
          actor.id,
          "organizer.request.decline",
          s"organizer_request:$id",
          None,
          Some(request + ("userId" -> JsNumber(userId)))
        )
        request
      }
    }
}

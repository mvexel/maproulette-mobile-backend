package org.maproulette.provider.choice.claim

import akka.actor.ActorSystem
import anorm._
import java.sql.{Connection, Timestamp}
import java.time.Instant
import java.util.UUID
import javax.inject.{Inject, Singleton}
import org.maproulette.auth.mobile.{MobileScopes, MobileSecrets}
import org.maproulette.framework.model.User
import play.api.db.Database
import play.api.libs.json._
import scala.concurrent.{ExecutionContext, Future}

/** A claim: a guest's pending answers taken over by an OSM account, published by the job. */
case class ClaimRecord(id: Long, guest: Option[UUID], userId: Long, familyId: String, state: String)

/** What a claim request gives: the claim, its pending count, and whether it already existed. */
case class ClaimResult(claim: ClaimRecord, pending: Int, existing: Boolean)

/** One answer of a claim, for the status route. */
case class ClaimItem(taskId: Long, state: String, result: Option[JsObject])

sealed trait ClaimProblem
object ClaimProblem {

  /** Unknown, superseded or expired token, or a deleted guest: all look alike. */
  case object NotFound extends ClaimProblem

  /** Claimed by someone else; `claimedAs` is their OSM display name. */
  case class Claimed(claimedAs: String) extends ClaimProblem

  /** The request's grant family holds no OSM token to copy for the publish job. */
  case object Reauth extends ClaimProblem
}

/** How the guest is named: the emailed claim token, or the guest's own id and secret (phone). */
sealed trait ClaimCredential
case class ByToken(tokenHash: String)                                extends ClaimCredential
case class BySecret(guest: UUID, secretHash: String, client: String) extends ClaimCredential

@com.google.inject.ImplementedBy(classOf[ClaimRepository])
trait ClaimStore {

  /**
    * In one transaction: checks the credential, marks the guest claimed by `userId`, gives the
    * phone a grant family holding a copy of the request family's OSM token (the request family
    * itself for [[BySecret]], where the phone is the requester), records the claim and points the
    * guest's pending answers at it. A repeat by the same user returns the existing claim.
    */
  def claim(
      credential: ClaimCredential,
      userId: Long,
      requestFamily: String,
      now: Instant
  ): Either[ClaimProblem, ClaimResult]

  def get(id: Long): Option[ClaimRecord]
  def items(claimId: Long): Seq[ClaimItem]
}

object ClaimRepository {

  /** What the phone's family may do once the app exchanges the guest secret (B6). */
  val PhoneScope: String =
    MobileScopes.format(Set(MobileScopes.Read, MobileScopes.Write, MobileScopes.TagFix))
}

@Singleton
class ClaimRepository @Inject() (db: Database) extends ClaimStore {
  private def stamp(value: Instant) = Timestamp.from(value)

  private val record: RowParser[ClaimRecord] =
    (SqlParser.long("id") ~ SqlParser.get[Option[UUID]]("guest_id") ~ SqlParser.long("user_id") ~
      SqlParser.str("family_id") ~ SqlParser.str("state")).map {
      case id ~ guest ~ user ~ family ~ state => ClaimRecord(id, guest, user, family, state)
    }

  private case class GuestRow(
      id: UUID,
      clientId: String,
      claimedUserId: Option[Long],
      expiresAt: Instant,
      deleted: Boolean
  )
  private val guestRow: RowParser[GuestRow] =
    (SqlParser.get[UUID]("id") ~ SqlParser.str("client_id") ~
      SqlParser.get[Option[Long]]("claimed_user_id") ~ SqlParser.date("expires_at") ~
      SqlParser.bool("deleted")).map {
      case id ~ client ~ claimed ~ expires ~ deleted =>
        GuestRow(id, client, claimed, expires.toInstant, deleted)
    }
  private val guestColumns =
    "id, client_id, claimed_user_id, expires_at, deleted_at IS NOT NULL AS deleted"

  private def pendingCount(claimId: Long)(implicit c: Connection): Int =
    SQL("SELECT count(*) FROM choice_pending WHERE claim_id={id} AND state='pending'")
      .on("id" -> claimId)
      .as(SqlParser.scalar[Long].single)
      .toInt

  /** The guest a credential names, locked, or why not. */
  private def locate(credential: ClaimCredential)(
      implicit c: Connection
  ): Option[GuestRow] = credential match {
    case ByToken(hash) =>
      SQL("SELECT guest_id FROM mobile_guest_claim_tokens WHERE token_hash={t} FOR UPDATE")
        .on("t" -> hash)
        .as(SqlParser.get[UUID]("guest_id").singleOpt)
        .filter { guest =>
          // Only the guest's three newest links work, as for the preview.
          SQL("""SELECT token_hash FROM mobile_guest_claim_tokens WHERE guest_id={g}::uuid
            ORDER BY created_at DESC, token_hash LIMIT 3""")
            .on("g" -> guest.toString)
            .as(SqlParser.str("token_hash").*)
            .contains(hash)
        }
        .flatMap { guest =>
          SQL(s"SELECT $guestColumns FROM mobile_guests WHERE id={g}::uuid FOR UPDATE")
            .on("g" -> guest.toString)
            .as(guestRow.singleOpt)
        }
    case BySecret(guest, secretHash, client) =>
      // A spent secret is NULL, so a repeat finds the guest only through its claim (below).
      SQL(s"""SELECT $guestColumns FROM mobile_guests WHERE id={g}::uuid AND client_id={client}
        AND (secret_hash={secret} OR claimed_user_id IS NOT NULL) FOR UPDATE""")
        .on("g" -> guest.toString, "client" -> client, "secret" -> secretHash)
        .as(guestRow.singleOpt)
  }

  override def claim(
      credential: ClaimCredential,
      userId: Long,
      requestFamily: String,
      now: Instant
  ): Either[ClaimProblem, ClaimResult] =
    db.withTransaction { implicit c =>
      locate(credential) match {
        case None => Left(ClaimProblem.NotFound)
        case Some(guest) if guest.claimedUserId.contains(userId) =>
          SQL("SELECT * FROM mobile_claims WHERE guest_id={g}::uuid")
            .on("g" -> guest.id.toString)
            .as(record.singleOpt)
            .map(existing => ClaimResult(existing, pendingCount(existing.id), existing = true))
            .toRight(ClaimProblem.NotFound)
        // Without the secret (spent by the claim) a guest id alone discloses nothing.
        case Some(guest) if guest.claimedUserId.isDefined && credential.isInstanceOf[BySecret] =>
          Left(ClaimProblem.NotFound)
        case Some(guest) if guest.claimedUserId.isDefined =>
          val name = SQL("SELECT name FROM users WHERE id={u}")
            .on("u" -> guest.claimedUserId.get)
            .as(SqlParser.str("name").singleOpt)
          Left(ClaimProblem.Claimed(name.getOrElse("")))
        case Some(guest) if guest.deleted || !guest.expiresAt.isAfter(now) =>
          Left(ClaimProblem.NotFound)
        case Some(guest) =>
          val hasToken =
            SQL("""SELECT EXISTS(SELECT 1 FROM mobile_osm_tokens t
              JOIN mobile_oauth_families f ON f.family_id = t.grant_family_id
              WHERE t.grant_family_id={f} AND t.user_id={u} AND f.revoked_at IS NULL)""")
              .on("f" -> requestFamily, "u" -> userId)
              .as(SqlParser.scalar[Boolean].single)
          if (!hasToken) Left(ClaimProblem.Reauth)
          else {
            val family = credential match {
              case BySecret(_, _, _) => requestFamily
              case ByToken(hash) =>
                val created = MobileSecrets.generate()
                // No tokens yet: the phone gets them by exchanging its guest secret (B6).
                SQL("""INSERT INTO mobile_oauth_families
                  (family_id,user_id,client_id,scope,redirect_uri,code_challenge,created_at)
                  SELECT {family},{user},id,{scope},redirect_uris[1],'',{now}
                  FROM mobile_oauth_clients WHERE id={client}""")
                  .on(
                    "family" -> created,
                    "user"   -> userId,
                    "scope"  -> ClaimRepository.PhoneScope,
                    "now"    -> stamp(now),
                    "client" -> guest.clientId
                  )
                  .executeUpdate()
                // Sealed for the user, not the family, so the ciphertext carries over as is.
                SQL("""INSERT INTO mobile_osm_tokens
                  (grant_family_id,user_id,ciphertext,nonce,osm_scope,created_at)
                  SELECT {family},user_id,ciphertext,nonce,osm_scope,{now}
                  FROM mobile_osm_tokens WHERE grant_family_id={from}""")
                  .on("family" -> created, "from" -> requestFamily, "now" -> stamp(now))
                  .executeUpdate()
                SQL("UPDATE mobile_guest_claim_tokens SET consumed_at={now} WHERE token_hash={t}")
                  .on("now" -> stamp(now), "t" -> hash)
                  .executeUpdate()
                created
            }
            SQL(s"""UPDATE mobile_guests SET claimed_user_id={u}, claimed_at={now},
              phone_family_id={family}${credential match {
              case BySecret(_, _, _) => ", secret_hash=NULL, exchanged_at={now}"
              case ByToken(_)        => ""
            }} WHERE id={g}::uuid""")
              .on(
                "u"      -> userId,
                "now"    -> stamp(now),
                "family" -> family,
                "g"      -> guest.id.toString
              )
              .executeUpdate()
            SQL("DELETE FROM mobile_guest_tokens WHERE guest_id={g}::uuid")
              .on("g" -> guest.id.toString)
              .executeUpdate()
            val claim =
              SQL("""INSERT INTO mobile_claims (guest_id,user_id,family_id,created_at,updated_at)
              VALUES ({g}::uuid,{u},{family},{now},{now}) RETURNING *""")
                .on(
                  "g"      -> guest.id.toString,
                  "u"      -> userId,
                  "family" -> family,
                  "now"    -> stamp(now)
                )
                .as(record.single)
            val pending = SQL("""UPDATE choice_pending SET claim_id={claim}, updated_at={now}
              WHERE guest_id={g}::uuid AND state='pending'""")
              .on("claim" -> claim.id, "now" -> stamp(now), "g" -> guest.id.toString)
              .executeUpdate()
            Right(ClaimResult(claim, pending, existing = false))
          }
      }
    }

  override def get(id: Long): Option[ClaimRecord] =
    db.withConnection { implicit c =>
      SQL("SELECT * FROM mobile_claims WHERE id={id}").on("id" -> id).as(record.singleOpt)
    }

  override def items(claimId: Long): Seq[ClaimItem] =
    db.withConnection { implicit c =>
      SQL("""SELECT task_id, state, result::text AS result FROM choice_pending
        WHERE claim_id={id} ORDER BY answered_at, id""")
        .on("id" -> claimId)
        .as(
          (SqlParser.long("task_id") ~ SqlParser.str("state") ~
            SqlParser.get[Option[String]]("result")).map {
            case task ~ state ~ result =>
              ClaimItem(task, state, result.flatMap(r => Json.parse(r).asOpt[JsObject]))
          }.*
        )
    }
}

sealed abstract class ClaimError(
    val status: Int,
    val code: String,
    val extra: JsObject = Json.obj()
)
object ClaimError {
  case object InvalidRequest extends ClaimError(400, "invalid_request")
  case object InsufficientScope
      extends ClaimError(403, "insufficient_scope", Json.obj("scope" -> MobileScopes.TagFix))
  case object NotFound extends ClaimError(404, "not_found")
  case object Reauth   extends ClaimError(401, "osm_reauth_required")
  case class Claimed(claimedAs: String)
      extends ClaimError(409, "guest_claimed", Json.obj("claimedAs" -> claimedAs))
}

/**
  * `POST /api/v2/mobile-claim` and `GET /api/v2/mobile-claim/:id` (deferred sign-up B5, API plan
  * §3.7 and §3.10). The claim only records; the publish job writes OSM.
  */
@Singleton
class ClaimService @Inject() (store: ClaimStore, actorSystem: ActorSystem) {
  private implicit lazy val ec: ExecutionContext =
    actorSystem.dispatchers.lookup("mobile-oauth-dispatcher")

  /** The body's credential: `{"claimToken"}` from the claim page, `{"guestId", "guestSecret"}` from the phone. */
  def credential(body: JsValue, client: String): Option[ClaimCredential] =
    body match {
      case o: JsObject if o.keys == Set("claimToken") =>
        (o \ "claimToken").asOpt[String].filter(_.matches("[A-Za-z0-9_-]{43}")).map { token =>
          ByToken(MobileSecrets.hash(token))
        }
      case o: JsObject if o.keys == Set("guestId", "guestSecret") =>
        for {
          id <- (o \ "guestId")
            .asOpt[String]
            .flatMap(v => scala.util.Try(UUID.fromString(v)).toOption)
          secret <- (o \ "guestSecret").asOpt[String].filter(_.matches("[A-Za-z0-9_-]{43}"))
        } yield BySecret(id, MobileSecrets.hash(secret), client)
      case _ => None
    }

  def claim(
      body: JsValue,
      user: User,
      scopes: Set[String],
      family: String,
      client: String
  ): Future[Either[ClaimError, (Int, JsObject)]] =
    if (!scopes.contains(MobileScopes.TagFix))
      Future.successful(Left(ClaimError.InsufficientScope))
    else
      credential(body, client) match {
        case None => Future.successful(Left(ClaimError.InvalidRequest))
        case Some(value) =>
          Future(store.claim(value, user.id, family, Instant.now())).map {
            case Left(ClaimProblem.NotFound)       => Left(ClaimError.NotFound)
            case Left(ClaimProblem.Reauth)         => Left(ClaimError.Reauth)
            case Left(ClaimProblem.Claimed(other)) => Left(ClaimError.Claimed(other))
            case Right(result) =>
              Right(
                (if (result.existing) 200 else 202) -> Json.obj(
                  "claimId" -> result.claim.id,
                  "state"   -> result.claim.state,
                  "pending" -> result.pending,
                  "user" -> Json.obj(
                    "displayName" -> user.osmProfile.displayName,
                    "osmId"       -> user.osmProfile.id
                  )
                )
              )
          }
      }

  /** The claim's progress, for its owner only; anyone else gets None (404). */
  def status(id: Long, user: User): Future[Option[JsObject]] = Future {
    store.get(id).filter(_.userId == user.id).map { claim =>
      val items                = store.items(claim.id)
      def count(state: String) = items.count(_.state == state)
      Json.obj(
        "claimId"    -> claim.id,
        "state"      -> claim.state,
        "total"      -> items.size,
        "published"  -> count("published"),
        "skipped"    -> count("skipped_stale"),
        "superseded" -> count("superseded"),
        "failed"     -> count("failed"),
        "items" -> items.map { item =>
          Json.obj(
            "taskId"      -> item.taskId,
            "state"       -> item.state,
            "changesetId" -> item.result.flatMap(r => (r \ "changesetId").asOpt[Long]),
            "dropped" -> item.result
              .flatMap(r => (r \ "dropped").asOpt[JsArray])
              .getOrElse[JsArray](Json.arr())
          )
        }
      )
    }
  }
}

package org.maproulette.provider.choice.claim

import anorm._
import java.nio.file.{Files, Paths}
import java.sql.Timestamp
import java.time.{Duration, Instant}
import java.util.UUID
import org.maproulette.auth.mobile.MobileSecrets
import org.maproulette.auth.mobile.guest.MobileGuestRepository
import org.scalatestplus.play.PlaySpec
import play.api.db.{Database, Databases}

/** Real PostgreSQL tests, opted in like MobileGuestRepositorySpec: a fresh schema per case. */
class GuestJobRepositorySpec extends PlaySpec {
  private val now    = Instant.parse("2026-11-25T18:00:00Z")
  private val client = "mobile-test"
  private val email  = SealedEmail(Array[Byte](1, 2, 3), Array.fill[Byte](12)(0))

  private def withStore(test: (GuestJobRepository, Database) => Unit): Unit = {
    val url = sys.env.getOrElse(
      "MOBILE_OAUTH_TEST_DATABASE_URL",
      cancel("Set MOBILE_OAUTH_TEST_DATABASE_URL to a disposable PostgreSQL database")
    )
    val username = sys.env.getOrElse("MOBILE_OAUTH_TEST_DATABASE_USER", "mobile_oauth_test")
    val password = sys.env.getOrElse("MOBILE_OAUTH_TEST_DATABASE_PASSWORD", "")
    val schema   = "guest_job_test_" + UUID.randomUUID().toString.replace("-", "")
    val config   = Map("username" -> username, "password" -> password)
    val admin    = Databases("org.postgresql.Driver", url, config = config)
    admin.withConnection { implicit c =>
      SQL(s"CREATE SCHEMA $schema").execute()
    }
    val separator = if (url.contains("?")) "&" else "?"
    val db = Databases(
      "org.postgresql.Driver",
      s"$url${separator}currentSchema=$schema",
      config = config
    )
    try {
      db.withConnection { implicit c =>
        SQL("CREATE TABLE users(id bigint PRIMARY KEY)").execute()
        SQL("INSERT INTO users VALUES(1)").executeUpdate()
        SQL("CREATE TABLE tasks(id bigint PRIMARY KEY)").execute()
        SQL("INSERT INTO tasks VALUES(1),(2)").executeUpdate()
        Seq("129", "130", "131", "135", "137").foreach { version =>
          val evolution = new String(
            Files.readAllBytes(Paths.get(s"conf/evolutions/default/$version.sql")),
            "UTF-8"
          )
          val ups = evolution.split("# --- !Ups")(1).split("# --- !Downs")(0)
          scala.util.Using.resource(c.createStatement()) { statement =>
            ups
              .split(";;")
              .map(_.linesIterator.filterNot(_.trim.startsWith("--")).mkString("\n").trim)
              .filter(_.nonEmpty)
              .foreach(statement.execute)
          }
        }
        SQL(s"""INSERT INTO mobile_oauth_clients (id,name,redirect_uris,scopes)
          VALUES ('$client','Test',ARRAY['org.example:/cb'],'tasks:read guest')""").execute()
      }
      test(new GuestJobRepository(db), db)
    } finally {
      db.shutdown()
      // This generated schema was created above solely for this case, never an application schema.
      admin.withConnection { implicit c =>
        SQL(s"DROP SCHEMA $schema CASCADE").execute()
      }
      admin.shutdown()
    }
  }

  private def stamp(value: Instant) = Timestamp.from(value)

  /** A guest with an address first mailed at `firstEmail`, one pending answer, expiring at `expires`. */
  private def guest(
      db: Database,
      expires: Instant,
      firstEmail: Option[Instant] = Some(now.minus(Duration.ofDays(2))),
      pending: Boolean = true
  ): UUID = {
    val id = UUID.randomUUID()
    db.withConnection { implicit c =>
      SQL("""INSERT INTO mobile_guests (id,client_id,secret_hash,created_at,expires_at)
        VALUES ({id}::uuid,{client},{secret},{created},{expires})""")
        .on(
          "id"      -> id.toString,
          "client"  -> client,
          "secret"  -> MobileSecrets.hash(id.toString),
          "created" -> stamp(expires.minus(Duration.ofDays(30))),
          "expires" -> stamp(expires)
        )
        .executeUpdate()
      firstEmail.foreach { at =>
        SQL(
          """UPDATE mobile_guests SET email_ciphertext={ct}, email_nonce={nonce}, email_set_at={at}
          WHERE id={id}::uuid"""
        ).on(
            "ct"    -> email.ciphertext,
            "nonce" -> email.nonce,
            "at"    -> stamp(at),
            "id"    -> id.toString
          )
          .executeUpdate()
        token(db, id, s"first-$id", at)
      }
      if (pending)
        SQL("""INSERT INTO choice_pending (guest_id,task_id,challenge_id,body,payload_digest,
            element_version,hold_until) VALUES ({id}::uuid,1,7,{body}::jsonb,{digest},1,{hold})""")
          .on(
            "id"     -> id.toString,
            "body"   -> "{}",
            "digest" -> MobileSecrets.hash("p"),
            "hold"   -> stamp(now)
          )
          .executeUpdate()
    }
    id
  }

  private def token(db: Database, id: UUID, value: String, at: Instant): Unit =
    db.withConnection { implicit c =>
      SQL("""INSERT INTO mobile_guest_claim_tokens (token_hash,guest_id,created_at)
        VALUES ({token},{id}::uuid,{at})""")
        .on("token" -> MobileSecrets.hash(value), "id" -> id.toString, "at" -> stamp(at))
        .executeUpdate()
      ()
    }

  private def pendingStates(db: Database): Seq[(Option[UUID], String)] =
    db.withConnection { implicit c =>
      SQL("SELECT guest_id, state FROM choice_pending ORDER BY id")
        .as((SqlParser.get[Option[UUID]]("guest_id") ~ SqlParser.str("state")).map {
          case g ~ s => (g, s)
        }.*)
    }

  "GuestJobRepository" should {
    "pick reminder 1 a day after the first email and reminder 2 in the last five days" in withStore {
      (store, db) =>
        val early   = guest(db, now.plus(Duration.ofDays(20)))
        val late    = guest(db, now.plus(Duration.ofDays(3)))
        val fresh   = guest(db, now.plus(Duration.ofDays(20)), Some(now.minus(Duration.ofHours(2))))
        val noEmail = guest(db, now.plus(Duration.ofDays(20)), None)
        val nothing = guest(db, now.plus(Duration.ofDays(20)), pending = false)
        val due     = store.remindersDue(now, 10).map(r => r.guest -> r.which).toMap
        due mustBe Map(early -> 1, late -> 2)
        due.keySet must not contain fresh
        due.keySet must not contain noEmail
        due.keySet must not contain nothing
        store.remindersDue(now, 10).find(_.guest == early).get.email.ciphertext mustBe
          email.ciphertext

        store.markReminded(early, 1, now) mustBe true
        store.markReminded(early, 1, now) mustBe false
        store.remindersDue(now, 10).map(_.guest) mustBe Seq(late)
        store.unmarkReminded(early, 1)
        store.setReminders(early, enabled = false, now)
        store.markReminded(early, 1, now) mustBe false
        store.remindersDue(now, 10).map(_.guest) mustBe Seq(late)
    }

    "expire pending answers and access tokens, then purge thirty days later" in withStore {
      (store, db) =>
        val gone  = guest(db, now.minusSeconds(60))
        val live  = guest(db, now.plus(Duration.ofDays(3)))
        val quiet = guest(db, now.minusSeconds(60), None, pending = false)
        store.expiriesDue(now, 10).map(e => e.guest -> e.email.isDefined) mustBe Seq(gone -> true)
        store.expire(gone, now) mustBe 1
        store.clearEmail(gone)
        store.expiriesDue(now, 10) mustBe empty
        pendingStates(db) mustBe Seq(Some(gone) -> "expired", Some(live) -> "pending")
        // An expired link can still be told apart from an unknown one.
        store.claimToken(MobileSecrets.hash(s"first-$gone")).map(_.guest) mustBe Some(gone)

        store.purge(now.minus(Duration.ofDays(30))) mustBe 0
        store.purge(now) mustBe 2 // gone and quiet
        pendingStates(db) mustBe Seq(None -> "expired", Some(live) -> "pending")
        store.claimToken(MobileSecrets.hash(s"first-$gone")) mustBe None
        store.claimToken(MobileSecrets.hash(s"first-$live")).map(_.guest) mustBe Some(live)
    }

    "accept only a guest's three newest claim tokens" in withStore { (store, db) =>
      val id = guest(db, now.plus(Duration.ofDays(20)))
      (1 to 3).foreach(n => token(db, id, s"later-$n", now.plusSeconds(n.toLong)))
      store.claimToken(MobileSecrets.hash(s"first-$id")) mustBe None
      store.claimToken(MobileSecrets.hash("later-1")) mustBe Some(ClaimTokenGuest(id, false))
      store.claimToken(MobileSecrets.hash("unknown")) mustBe None
      store.claimToken("not-a-digest") mustBe None
    }

    "make the token routes' delete leave a tombstone" in withStore { (store, db) =>
      val id = guest(db, now.plus(Duration.ofDays(20)))
      new MobileGuestRepository(db).delete(id, now) mustBe true
      store.claimToken(MobileSecrets.hash(s"first-$id")).map(_.guest) mustBe Some(id)
      store.remindersDue(now, 10) mustBe empty
      pendingStates(db) mustBe empty
    }
  }
}

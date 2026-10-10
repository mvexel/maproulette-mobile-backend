package org.maproulette.auth.mobile.guest

import anorm._
import java.nio.file.{Files, Paths}
import java.time.Instant
import java.util.UUID
import org.maproulette.auth.mobile.MobileSecrets
import org.scalatestplus.play.PlaySpec
import play.api.db.{Database, Databases}

/** Real PostgreSQL tests, opted in like MobileOAuthRepositorySpec: a fresh schema per case. */
class MobileGuestRepositorySpec extends PlaySpec {
  private val now                         = Instant.parse("2026-01-01T00:00:00Z")
  private val client                      = "mobile-test"
  private def hash(value: String): String = MobileSecrets.hash(value)

  private def withStore(test: (MobileGuestRepository, Database) => Unit): Unit = {
    val url = sys.env.getOrElse(
      "MOBILE_OAUTH_TEST_DATABASE_URL",
      cancel("Set MOBILE_OAUTH_TEST_DATABASE_URL to a disposable PostgreSQL database")
    )
    val username = sys.env.getOrElse("MOBILE_OAUTH_TEST_DATABASE_USER", "mobile_oauth_test")
    val password = sys.env.getOrElse("MOBILE_OAUTH_TEST_DATABASE_PASSWORD", "")
    val schema   = "mobile_guest_test_" + UUID.randomUUID().toString.replace("-", "")
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
        Seq("129", "130", "131", "135").foreach { version =>
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
      test(new MobileGuestRepository(db), db)
    } finally {
      db.shutdown()
      // This generated schema was created above solely for this case, never an application schema.
      admin.withConnection { implicit c =>
        SQL(s"DROP SCHEMA $schema CASCADE").execute()
      }
      admin.shutdown()
    }
  }

  private def register(store: MobileGuestRepository, label: String): MobileGuest =
    store.create(UUID.randomUUID(), client, hash(s"secret-$label"), now.plusSeconds(86400), now)

  "MobileGuestRepository" should {
    "create a guest without email or claim" in withStore { (store, _) =>
      val guest = register(store, "a")
      guest.clientId mustBe client
      guest.emailSet mustBe false
      guest.claimed mustBe false
      guest.live(now) mustBe true
      store.get(guest.id) mustBe Some(guest)
    }

    "issue an access token only for the right secret and client" in withStore { (store, _) =>
      val guest = register(store, "a")
      val later = now.plusSeconds(60)
      def issue(secret: String, clientId: String, token: String) =
        store.issueToken(
          guest.id,
          clientId,
          hash(secret),
          hash(token),
          later.plusSeconds(900),
          later
        )
      issue("secret-a", client, "t1").map(_.id) mustBe Right(guest.id)
      issue("secret-b", client, "t2") mustBe Left(GuestTokenProblem.InvalidGrant)
      issue("secret-a", "other", "t3") mustBe Left(GuestTokenProblem.InvalidGrant)
      store.authenticate(hash("t1"), later).map(_.id) mustBe Some(guest.id)
      store.authenticate(hash("t2"), later) mustBe None
      store.authenticate(hash("t1"), later.plusSeconds(900)) mustBe None
      store.authenticate("not-a-digest", later) mustBe None
    }

    "drop the guest's expired tokens when issuing a new one" in withStore { (store, db) =>
      val guest = register(store, "a")
      store.issueToken(guest.id, client, hash("secret-a"), hash("old"), now.plusSeconds(10), now)
      val later = now.plusSeconds(60)
      store.issueToken(
        guest.id,
        client,
        hash("secret-a"),
        hash("new"),
        later.plusSeconds(900),
        later
      )
      db.withConnection { implicit c =>
        SQL("SELECT count(*) FROM mobile_guest_tokens").as(SqlParser.scalar[Long].single) mustBe 1L
      }
    }

    "refuse tokens for an expired guest" in withStore { (store, _) =>
      val guest = register(store, "a")
      val after = now.plusSeconds(86400)
      store.issueToken(guest.id, client, hash("secret-a"), hash("t"), after.plusSeconds(900), after) mustBe
        Left(GuestTokenProblem.InvalidGrant)
      // A token outliving its guest still stops working when the guest expires.
      store.issueToken(guest.id, client, hash("secret-a"), hash("t"), after.plusSeconds(900), now)
      store.authenticate(hash("t"), after.minusSeconds(1)).map(_.id) mustBe Some(guest.id)
      store.authenticate(hash("t"), after) mustBe None
    }

    "report a claimed guest instead of issuing a guest token" in withStore { (store, db) =>
      val guest = register(store, "a")
      store.issueToken(guest.id, client, hash("secret-a"), hash("t"), now.plusSeconds(900), now)
      db.withConnection { implicit c =>
        SQL("UPDATE mobile_guests SET claimed_user_id=1, claimed_at=now()").executeUpdate()
      }
      store.issueToken(guest.id, client, hash("secret-a"), hash("t2"), now.plusSeconds(900), now) mustBe
        Left(GuestTokenProblem.Claimed)
      store.authenticate(hash("t"), now) mustBe None
    }

    "delete a guest's credentials and keep a tombstone" in withStore { (store, db) =>
      val guest = register(store, "a")
      store.issueToken(guest.id, client, hash("secret-a"), hash("t"), now.plusSeconds(900), now)
      db.withConnection { implicit c =>
        SQL("""INSERT INTO mobile_guest_claim_tokens (token_hash,guest_id)
          VALUES ({token},{id}::uuid)""")
          .on("token" -> hash("claim"), "id" -> guest.id.toString)
          .executeUpdate()
        SQL("UPDATE mobile_guests SET email_ciphertext='\\x01', email_nonce='\\x02'")
          .executeUpdate()
      }
      store.delete(guest.id, now) mustBe true
      store.delete(guest.id, now) mustBe false
      store.authenticate(hash("t"), now) mustBe None
      store.issueToken(guest.id, client, hash("secret-a"), hash("t2"), now.plusSeconds(900), now) mustBe
        Left(GuestTokenProblem.InvalidGrant)
      val deleted = store.get(guest.id).get
      deleted.deletedAt mustBe Some(now)
      deleted.emailSet mustBe false
      db.withConnection { implicit c =>
        SQL("SELECT count(*) FROM mobile_guest_claim_tokens").as(SqlParser.scalar[Long].single) mustBe 1L
        SQL("SELECT secret_hash IS NULL FROM mobile_guests").as(SqlParser.scalar[Boolean].single) mustBe true
      }
    }

    "reject a guest for an unknown client" in withStore { (store, _) =>
      intercept[java.sql.SQLException] {
        store.create(UUID.randomUUID(), "unknown", hash("s"), now.plusSeconds(60), now)
      }
    }
  }
}

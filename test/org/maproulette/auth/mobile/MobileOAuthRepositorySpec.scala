package org.maproulette.auth.mobile

import anorm._
import java.nio.file.{Files, Paths}
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import java.util.concurrent.{Callable, CountDownLatch, Executors, TimeUnit}
import org.scalatestplus.play.PlaySpec
import play.api.db.Databases

/** Real PostgreSQL tests: opted in explicitly, each case uses a fresh schema on a disposable test DB.
  * No application bootstrap, legacy migrations or existing application tables are touched.
  */
class MobileOAuthRepositorySpec extends PlaySpec {
  private val now = Instant.parse("2026-01-01T00:00:00Z")
  private def hash(value: String): String =
    MessageDigest
      .getInstance("SHA-256")
      .digest(value.getBytes("UTF-8"))
      .map(b => f"${b & 0xff}%02x")
      .mkString
  private def pair(label: String): TokenHashes =
    TokenHashes(
      hash(s"access-$label"),
      hash(s"refresh-$label"),
      now.plusSeconds(300),
      now.plusSeconds(3600)
    )
  private def pending(label: String): MobileInteraction =
    MobileInteraction(
      hash(s"interaction-$label"),
      hash(s"browser-$label"),
      "mobile-test",
      "org.example:/callback",
      "tasks:read",
      s"client-state-$label",
      "s256-challenge",
      now.plusSeconds(600)
    )

  private def withStore(test: (MobileOAuthRepository, play.api.db.Database) => Unit): Unit = {
    val url = sys.env.getOrElse(
      "MOBILE_OAUTH_TEST_DATABASE_URL",
      cancel("Set MOBILE_OAUTH_TEST_DATABASE_URL to a disposable PostgreSQL database")
    )
    val username = sys.env.getOrElse("MOBILE_OAUTH_TEST_DATABASE_USER", "mobile_oauth_test")
    val password = sys.env.getOrElse("MOBILE_OAUTH_TEST_DATABASE_PASSWORD", "")
    val schema   = "mobile_oauth_test_" + UUID.randomUUID().toString.replace("-", "")
    val admin = Databases(
      "org.postgresql.Driver",
      url,
      config = Map("username" -> username, "password" -> password)
    )
    admin.withConnection { implicit c =>
      SQL(s"CREATE SCHEMA $schema").execute()
    }
    val separator = if (url.contains("?")) "&" else "?"
    val db = Databases(
      "org.postgresql.Driver",
      s"$url${separator}currentSchema=$schema",
      config = Map("username" -> username, "password" -> password)
    )
    try {
      db.withConnection { implicit c =>
        SQL("CREATE TABLE users(id bigint PRIMARY KEY)").execute()
        SQL("INSERT INTO users VALUES(1),(2)").executeUpdate()
        // Evolution 130 references tasks; only its id is needed here.
        SQL("CREATE TABLE tasks(id bigint PRIMARY KEY)").execute()
        Seq("129", "130").foreach { version =>
          val evolution =
            new String(
              Files.readAllBytes(Paths.get(s"conf/evolutions/default/$version.sql")),
              "UTF-8"
            )
          val ups = evolution.split("# --- !Ups")(1).split("# --- !Downs")(0)
          // Execute migration text as SQL, not Anorm named-parameter syntax (regex uses braces).
          scala.util.Using.resource(c.createStatement()) { statement =>
            ups
              .split(";;")
              .map(_.linesIterator.filterNot(_.trim.startsWith("--")).mkString("\n").trim)
              .filter(_.nonEmpty)
              .foreach(statement.execute)
          }
        }
      }
      test(new MobileOAuthRepository(db), db)
    } finally {
      db.shutdown()
      // This generated schema was created above solely for this case, never an application schema.
      admin.withConnection { implicit c =>
        SQL(s"DROP SCHEMA $schema CASCADE").execute()
      }
      admin.shutdown()
    }
  }

  private def approved(
      store: MobileOAuthRepository,
      label: String,
      user: Long = 1L
  ): (MobileInteraction, String, MobileGrant) = {
    val interaction = pending(label)
    val code        = hash(s"code-$label")
    store.createInteraction(interaction)
    store.claimLogin(interaction.idHash, interaction.browserHash, now).isDefined mustBe true
    store.completeLogin(interaction.idHash, interaction.browserHash, user, hash("csrf"), now) mustBe true
    val grant = store
      .approveInteraction(
        interaction.idHash,
        interaction.browserHash,
        hash("csrf"),
        code,
        s"family-$label",
        now.plusSeconds(60),
        now
      )
      .get
    (interaction, code, grant)
  }
  private def redeem(
      store: MobileOAuthRepository,
      code: String,
      tokens: TokenHashes
  ): Option[MobileGrant] =
    store.redeemCode(code, "mobile-test", "org.example:/callback", "s256-challenge", tokens, now)

  private def race[A](left: => A, right: => A): List[A] = {
    val executor = Executors.newFixedThreadPool(2)
    val start    = new CountDownLatch(1)
    try {
      val a = executor.submit(new Callable[A] { def call(): A = { start.await(); left }  })
      val b = executor.submit(new Callable[A] { def call(): A = { start.await(); right } })
      start.countDown()
      List(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS))
    } finally executor.shutdownNow()
  }

  "Mobile OSM token persistence" should {
    val sealedToken = SealedOsmToken(Array[Byte](1, 2, 3), Array.fill[Byte](12)(9))
    def tokenLogin(store: MobileOAuthRepository, label: String): MobileInteraction = {
      val interaction = pending(label).copy(scope = "tasks:read tasks:write osm:tagfix")
      store.createInteraction(interaction)
      store.claimLogin(interaction.idHash, interaction.browserHash, now).isDefined mustBe true
      store.completeLogin(interaction.idHash, interaction.browserHash, 1, hash("csrf"), now) mustBe true
      interaction
    }
    def approve(store: MobileOAuthRepository, interaction: MobileInteraction, family: String) =
      store.approveInteraction(
        interaction.idHash,
        interaction.browserHash,
        hash("csrf"),
        hash(s"code-$family"),
        family,
        now.plusSeconds(60),
        now
      )
    def interactionToken(db: play.api.db.Database, interaction: MobileInteraction) =
      db.withConnection { implicit c =>
        SQL(
          "SELECT osm_token_ciphertext IS NOT NULL FROM mobile_oauth_interactions WHERE interaction_hash={id}"
        ).on("id" -> interaction.idHash)
          .as(SqlParser.scalar[Boolean].single)
      }

    "attach a sealed token only to the logged-in user's interaction and move it to the family" in withStore {
      (store, db) =>
        val interaction = tokenLogin(store, "osm-move")
        store.attachOsmToken(
          interaction.idHash,
          hash("wrong"),
          1,
          sealedToken,
          "read_prefs write_api",
          now
        ) mustBe false
        store.attachOsmToken(
          interaction.idHash,
          interaction.browserHash,
          2,
          sealedToken,
          "read_prefs write_api",
          now
        ) mustBe false
        store.attachOsmToken(
          interaction.idHash,
          interaction.browserHash,
          1,
          sealedToken,
          "read_prefs write_api",
          now
        ) mustBe true
        approve(store, interaction, "family-osm").isDefined mustBe true
        val stored = store.osmToken("family-osm").get
        stored.userId mustBe 1
        stored.osmScope mustBe "read_prefs write_api"
        stored.token.ciphertext.toSeq mustBe sealedToken.ciphertext.toSeq
        stored.token.nonce.toSeq mustBe sealedToken.nonce.toSeq
        interactionToken(db, interaction) mustBe false
        store.deleteOsmToken("family-osm")
        store.osmToken("family-osm") mustBe None
    }

    "clear a declined login's token and give a family without osm:tagfix none" in withStore {
      (store, db) =>
        val declined = tokenLogin(store, "osm-decline")
        store.attachOsmToken(
          declined.idHash,
          declined.browserHash,
          1,
          sealedToken,
          "read_prefs write_api",
          now
        ) mustBe true
        store
          .declineInteraction(declined.idHash, declined.browserHash, hash("csrf"), now)
          .isDefined mustBe true
        interactionToken(db, declined) mustBe false
        val (_, _, plain) = approved(store, "osm-none")
        store.osmToken(plain.familyId) mustBe None
    }

    "issue no osm:tagfix grant without its token, and clear tokens of expired logins" in withStore {
      (store, db) =>
        val tokenless = tokenLogin(store, "osm-tokenless")
        approve(store, tokenless, "family-tokenless") mustBe None
        store.findCode(hash("code-family-tokenless"), now) mustBe None
        // The rollback leaves the interaction unconsumed; a retry still finds no token.
        approve(store, tokenless, "family-tokenless-2") mustBe None

        val abandoned = tokenLogin(store, "osm-abandoned")
        store.attachOsmToken(
          abandoned.idHash,
          abandoned.browserHash,
          1,
          sealedToken,
          "read_prefs write_api",
          now
        ) mustBe true
        interactionToken(db, abandoned) mustBe true
        val later  = tokenLogin(store, "osm-later")
        val expiry = abandoned.expiresAt.plusSeconds(1)
        store.attachOsmToken(
          later.idHash,
          later.browserHash,
          1,
          sealedToken,
          "read_prefs write_api",
          expiry
        ) mustBe false
        interactionToken(db, abandoned) mustBe false
    }

    "delete the token when the family is revoked or a refresh token is replayed" in withStore {
      (store, _) =>
        Seq("revoked", "replayed").foreach { label =>
          val interaction = tokenLogin(store, s"osm-$label")
          store.attachOsmToken(
            interaction.idHash,
            interaction.browserHash,
            1,
            sealedToken,
            "read_prefs write_api",
            now
          ) mustBe true
          approve(store, interaction, s"family-$label").isDefined mustBe true
          val first = pair(s"$label-1")
          store
            .redeemCode(
              hash(s"code-family-$label"),
              "mobile-test",
              "org.example:/callback",
              "s256-challenge",
              first,
              now
            )
            .isDefined mustBe true
          store.osmToken(s"family-$label").isDefined mustBe true
          if (label == "revoked") store.revoke(first.accessHash, "mobile-test", now)
          else {
            store
              .rotate(first.refreshHash, "mobile-test", pair(s"$label-2"), now)
              .isDefined mustBe true
            // Rotation keeps the token; the replay below revokes the family.
            store.osmToken(s"family-$label").isDefined mustBe true
            store.rotate(first.refreshHash, "mobile-test", pair(s"$label-3"), now) mustBe None
          }
          store.osmToken(s"family-$label") mustBe None
        }
    }
  }

  "Mobile OAuth PostgreSQL persistence" should {
    "bind browser callbacks, consent CSRF and verified user, and consume each interaction once" in withStore {
      (store, _) =>
        val value = pending("browser")
        store.createInteraction(value)
        store.getInteraction(value.idHash, hash("wrong-browser"), now) mustBe None
        store.claimLogin(value.idHash, hash("wrong-browser"), now) mustBe None
        store.completeLogin(value.idHash, value.browserHash, 1, hash("csrf"), now) mustBe false
        store.claimLogin(value.idHash, value.browserHash, now).isDefined mustBe true
        store.claimLogin(value.idHash, value.browserHash, now) mustBe None
        store.completeLogin(value.idHash, value.browserHash, 1, hash("csrf"), now) mustBe true
        store.completeLogin(value.idHash, value.browserHash, 2, hash("csrf"), now) mustBe false
        store.approveInteraction(
          value.idHash,
          value.browserHash,
          hash("wrong-csrf"),
          hash("code"),
          "family",
          now.plusSeconds(60),
          now
        ) mustBe None
        store.getInteraction(value.idHash, value.browserHash, now).get.userId mustBe Some(1L)
        store
          .declineInteraction(value.idHash, value.browserHash, hash("csrf"), now)
          .isDefined mustBe true
        store.getInteraction(value.idHash, value.browserHash, now) mustBe None
        store.approveInteraction(
          value.idHash,
          value.browserHash,
          hash("csrf"),
          hash("code"),
          "family",
          now.plusSeconds(60),
          now
        ) mustBe None
        val expired = pending("expired-browser")
        store.createInteraction(expired)
        store.claimLogin(expired.idHash, expired.browserHash, expired.expiresAt) mustBe None
    }

    "atomically redeem one code once and commit replay revocation under concurrency" in withStore {
      (store, db) =>
        val (_, code, _) = approved(store, "concurrent-code")
        val a            = pair("a"); val b = pair("b")
        val outcomes     = race(redeem(store, code, a), redeem(store, code, b))
        outcomes.count(_.isDefined) mustBe 1
        store.authenticate(a.accessHash, now) mustBe None
        store.authenticate(b.accessHash, now) mustBe None
        db.withConnection { implicit c =>
          SQL("SELECT COUNT(*) FROM mobile_oauth_access_tokens").as(SqlParser.scalar[Long].single) mustBe 1L
        }
    }

    "validate client redirect PKCE and expiry without burning a valid unconsumed code" in withStore {
      (store, _) =>
        val (_, code, _) = approved(store, "bindings")
        val tokens       = pair("bindings")
        store.redeemCode(
          code,
          "wrong-client",
          "org.example:/callback",
          "s256-challenge",
          tokens,
          now
        ) mustBe None
        store.redeemCode(code, "mobile-test", "org.evil:/callback", "s256-challenge", tokens, now) mustBe None
        store.redeemCode(
          code,
          "mobile-test",
          "org.example:/callback",
          "wrong-challenge",
          tokens,
          now
        ) mustBe None
        redeem(store, code, tokens).isDefined mustBe true
        store.authenticate(tokens.accessHash, now).get.userId mustBe 1L
        val (_, expiredCode, _) = approved(store, "expired-code")
        store.redeemCode(
          expiredCode,
          "mobile-test",
          "org.example:/callback",
          "s256-challenge",
          pair("expired-code"),
          now.plusSeconds(60)
        ) mustBe None
    }

    "rotate refresh tokens once and commit family revocation on concurrent replay" in withStore {
      (store, db) =>
        val (_, code, _) = approved(store, "refresh")
        val first        = pair("first")
        redeem(store, code, first).isDefined mustBe true
        val second = pair("second"); val third = pair("third")
        store.rotate(first.refreshHash, "wrong-client", second, now) mustBe None
        val outcomes = race(
          store.rotate(first.refreshHash, "mobile-test", second, now),
          store.rotate(first.refreshHash, "mobile-test", third, now)
        )
        outcomes.count(_.isDefined) mustBe 1
        List(first, second, third).foreach { value =>
          store.authenticate(value.accessHash, now) mustBe None
        }
        new MobileOAuthRepository(db).findRefresh(second.refreshHash, now) mustBe None
        db.withConnection { implicit c =>
          SQL("SELECT COUNT(*) FROM mobile_oauth_refresh_tokens").as(SqlParser.scalar[Long].single) mustBe 2L
        }
    }

    "expire access and refresh tokens and revoke only the bound user and application family" in withStore {
      (store, _) =>
        val (_, code, _)      = approved(store, "revoke", 1L)
        val (_, otherCode, _) = approved(store, "other", 2L)
        val first             = pair("revoke"); val other = pair("other")
        redeem(store, code, first).isDefined mustBe true
        redeem(store, otherCode, other).isDefined mustBe true
        store.authenticate(first.accessHash, first.accessExpiresAt) mustBe None
        val laterPair = TokenHashes(
          hash("later-a"),
          hash("later-r"),
          now.plusSeconds(5000),
          now.plusSeconds(6000)
        )
        store.rotate(first.refreshHash, "mobile-test", laterPair, first.refreshExpiresAt) mustBe None
        store.revoke(first.refreshHash, "wrong-client", now)
        store.authenticate(first.accessHash, now).isDefined mustBe true
        store.revoke(first.refreshHash, "mobile-test", now)
        store.authenticate(first.accessHash, now) mustBe None
        store.rotate(first.refreshHash, "mobile-test", pair("after-revoke"), now) mustBe None
        store.authenticate(other.accessHash, now).get.userId mustBe 2L
    }

    "persist only credential hashes, reject unhashed issuance and survive repository recreation" in withStore {
      (store, db) =>
        val (_, code, expected) = approved(store, "hashed")
        val tokens              = pair("hashed")
        intercept[IllegalArgumentException] {
          redeem(store, code, tokens.copy(accessHash = "raw-secret"))
        }
        redeem(store, code, tokens) mustBe Some(expected)
        new MobileOAuthRepository(db).authenticate(tokens.accessHash, now) mustBe Some(expected)
        db.withConnection { implicit c =>
          SQL("SELECT token_hash FROM mobile_oauth_access_tokens").as(
            SqlParser.str("token_hash").single
          ) mustBe hash("access-hashed")
          SQL("SELECT token_hash FROM mobile_oauth_refresh_tokens").as(
            SqlParser.str("token_hash").single
          ) mustBe hash("refresh-hashed")
          SQL("SELECT code_hash FROM mobile_oauth_codes").as(SqlParser.str("code_hash").single) mustBe hash(
            "code-hashed"
          )
        }
    }

    "retain consumed expired refresh tokens for replay detection against their active family" in withStore {
      (store, _) =>
        val (_, code, _) = approved(store, "expired-replay")
        val old          = pair("old")
        redeem(store, code, old).isDefined mustBe true
        val later = now.plusSeconds(3500)
        val current = TokenHashes(
          hash("current-a"),
          hash("current-r"),
          now.plusSeconds(5000),
          now.plusSeconds(6000)
        )
        store.rotate(old.refreshHash, "mobile-test", current, later).isDefined mustBe true
        val afterExpiry = now.plusSeconds(3601)
        store.findRefresh(old.refreshHash, afterExpiry).isDefined mustBe true
        val attempted = TokenHashes(
          hash("attempt-a"),
          hash("attempt-r"),
          now.plusSeconds(5001),
          now.plusSeconds(6001)
        )
        store.rotate(old.refreshHash, "mobile-test", attempted, afterExpiry) mustBe None
        store.authenticate(current.accessHash, afterExpiry) mustBe None
    }

    "roll back consumption if token issuance fails partway through the transaction" in withStore {
      (store, _) =>
        val (_, firstCode, _) = approved(store, "rollback-first")
        val initial           = pair("rollback-first")
        redeem(store, firstCode, initial).isDefined mustBe true
        val (_, secondCode, _) = approved(store, "rollback-second")
        val collision          = pair("rollback-second").copy(refreshHash = initial.refreshHash)
        intercept[java.sql.SQLException] { redeem(store, secondCode, collision) }
        store.authenticate(collision.accessHash, now) mustBe None
        redeem(store, secondCode, pair("rollback-fixed")).isDefined mustBe true
        val rotationCollision = pair("rotation-collision").copy(refreshHash = initial.refreshHash)
        intercept[java.sql.SQLException] {
          store.rotate(initial.refreshHash, "mobile-test", rotationCollision, now)
        }
        store.authenticate(rotationCollision.accessHash, now) mustBe None
        store
          .rotate(initial.refreshHash, "mobile-test", pair("rotation-fixed"), now)
          .isDefined mustBe true
    }
  }
}

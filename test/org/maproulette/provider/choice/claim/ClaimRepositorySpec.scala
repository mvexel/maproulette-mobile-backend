package org.maproulette.provider.choice.claim

import anorm._
import java.nio.file.{Files, Paths}
import java.sql.Timestamp
import java.time.{Duration, Instant}
import java.util.UUID
import org.maproulette.auth.mobile.MobileSecrets
import org.scalatestplus.play.PlaySpec
import play.api.db.{Database, Databases}

/** Real PostgreSQL tests, opted in like MobileGuestRepositorySpec: a fresh schema per case. */
class ClaimRepositorySpec extends PlaySpec {
  private val now    = Instant.parse("2026-11-10T18:00:00Z")
  private val client = "mobile-test"
  private val rosa   = 1L
  private val sam    = 2L

  private def withStore(test: (ClaimRepository, Database) => Unit): Unit = {
    val url = sys.env.getOrElse(
      "MOBILE_OAUTH_TEST_DATABASE_URL",
      cancel("Set MOBILE_OAUTH_TEST_DATABASE_URL to a disposable PostgreSQL database")
    )
    val username = sys.env.getOrElse("MOBILE_OAUTH_TEST_DATABASE_USER", "mobile_oauth_test")
    val password = sys.env.getOrElse("MOBILE_OAUTH_TEST_DATABASE_PASSWORD", "")
    val schema   = "claim_test_" + UUID.randomUUID().toString.replace("-", "")
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
        SQL("CREATE TABLE users(id bigint PRIMARY KEY, name text)").execute()
        SQL("INSERT INTO users VALUES(1,'rosa_slc'),(2,'sam')").executeUpdate()
        SQL("CREATE TABLE tasks(id bigint PRIMARY KEY)").execute()
        SQL("INSERT INTO tasks VALUES(1),(2)").executeUpdate()
        Seq("129", "130", "131", "135", "137", "138", "139").foreach { version =>
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
        SQL(
          s"""INSERT INTO mobile_oauth_clients (id,name,redirect_uris,scopes)
          VALUES ('$client','Test',ARRAY['org.example:/cb'],'tasks:read tasks:write osm:tagfix guest'),
          ('maproulette-claim','Claim',ARRAY['https://claim.example/claim/callback'],'tasks:read tasks:write osm:tagfix')"""
        ).execute()
      }
      test(new ClaimRepository(db), db)
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

  /** A grant family for `user`, with a sealed OSM token unless `withToken` is false. */
  private def family(db: Database, user: Long, clientId: String, withToken: Boolean = true) = {
    val id = MobileSecrets.generate()
    db.withConnection { implicit c =>
      SQL("""INSERT INTO mobile_oauth_families
        (family_id,user_id,client_id,scope,redirect_uri,code_challenge,created_at)
        VALUES ({f},{u},{c},'tasks:read tasks:write osm:tagfix','x','y',{now})""")
        .on("f" -> id, "u" -> user, "c" -> clientId, "now" -> stamp(now))
        .executeUpdate()
      if (withToken)
        SQL("""INSERT INTO mobile_osm_tokens (grant_family_id,user_id,ciphertext,nonce,osm_scope)
          VALUES ({f},{u},'\x0102'::bytea,'\x03'::bytea,'read_prefs write_api')""")
          .on("f" -> id, "u" -> user)
          .executeUpdate()
    }
    id
  }

  /** A live guest with two pending answers and one claim link. */
  private def guest(
      db: Database,
      label: String,
      expires: Instant = now.plus(Duration.ofDays(20))
  ) = {
    val id = UUID.randomUUID()
    db.withConnection { implicit c =>
      SQL("""INSERT INTO mobile_guests (id,client_id,secret_hash,created_at,expires_at)
        VALUES ({id}::uuid,{client},{secret},{now},{expires})""")
        .on(
          "id"      -> id.toString,
          "client"  -> client,
          "secret"  -> MobileSecrets.hash(s"secret-$label"),
          "now"     -> stamp(now.minus(Duration.ofDays(1))),
          "expires" -> stamp(expires)
        )
        .executeUpdate()
      SQL("""INSERT INTO mobile_guest_claim_tokens (token_hash,guest_id,created_at)
        VALUES ({t},{id}::uuid,{now})""")
        .on("t" -> MobileSecrets.hash(s"link-$label"), "id" -> id.toString, "now" -> stamp(now))
        .executeUpdate()
      SQL("""INSERT INTO mobile_guest_tokens (token_hash,guest_id,expires_at)
        VALUES ({t},{id}::uuid,{exp})""")
        .on(
          "t"   -> MobileSecrets.hash(s"access-$label"),
          "id"  -> id.toString,
          "exp" -> stamp(now.plusSeconds(600))
        )
        .executeUpdate()
      Seq(1L, 2L).foreach { task =>
        SQL(
          """INSERT INTO choice_pending (guest_id,task_id,challenge_id,body,payload_digest,
            element_version,hold_until) VALUES ({id}::uuid,{task},7,{body}::jsonb,{digest},1,{hold})"""
        ).on(
            "id"     -> id.toString,
            "task"   -> task,
            "body"   -> """{"answers":{"shelter":"yes"}}""",
            "digest" -> MobileSecrets.hash("p"),
            "hold"   -> stamp(now)
          )
          .executeUpdate()
      }
    }
    id
  }

  private def link(label: String) = ByToken(MobileSecrets.hash(s"link-$label"))
  private def secret(id: UUID, label: String) =
    BySecret(id, MobileSecrets.hash(s"secret-$label"), client)

  "ClaimRepository" should {
    "claim by link: a phone family with the OSM token, the answers linked, the link spent" in withStore {
      (store, db) =>
        val g      = guest(db, "a")
        val page   = family(db, rosa, "maproulette-claim")
        val result = store.claim(link("a"), rosa, page, now).toOption.get
        result.existing mustBe false
        result.pending mustBe 2
        result.claim.state mustBe "queued"
        result.claim.familyId must not be page
        db.withConnection { implicit c =>
          SQL("SELECT client_id, scope, user_id FROM mobile_oauth_families WHERE family_id={f}")
            .on("f" -> result.claim.familyId)
            .as((SqlParser.str(1) ~ SqlParser.str(2) ~ SqlParser.long(3)).map {
              case a ~ b ~ u => (a, b, u)
            }.single) mustBe ((client, "tasks:read tasks:write osm:tagfix", rosa))
          SQL("SELECT count(*) FROM mobile_osm_tokens WHERE grant_family_id={f}")
            .on("f" -> result.claim.familyId)
            .as(SqlParser.scalar[Long].single) mustBe 1
          SQL("SELECT phone_family_id FROM mobile_guests WHERE id={g}::uuid")
            .on("g" -> g.toString)
            .as(SqlParser.str(1).single) mustBe result.claim.familyId
          SQL("SELECT count(*) FROM mobile_guest_tokens").as(SqlParser.scalar[Long].single) mustBe 0
          SQL("SELECT count(*) FROM mobile_guest_claim_tokens WHERE consumed_at IS NOT NULL")
            .as(SqlParser.scalar[Long].single) mustBe 1
        }
        store.items(result.claim.id).map(_.taskId) mustBe Seq(1L, 2L)
        store.get(result.claim.id) mustBe Some(result.claim)

        // Same user again: the same claim. Someone else: told who claimed it.
        val again = store.claim(link("a"), rosa, page, now).toOption.get
        again.existing mustBe true
        again.claim.id mustBe result.claim.id
        store.claim(link("a"), sam, family(db, sam, "maproulette-claim"), now) mustBe
          Left(ClaimProblem.Claimed("rosa_slc"))
    }

    "refuse unknown, superseded and expired links, and a grant without an OSM token" in withStore {
      (store, db) =>
        val page = family(db, rosa, "maproulette-claim")
        store.claim(ByToken(MobileSecrets.hash("nope")), rosa, page, now) mustBe
          Left(ClaimProblem.NotFound)
        guest(db, "old", expires = now.minusSeconds(1))
        store.claim(link("old"), rosa, page, now) mustBe Left(ClaimProblem.NotFound)
        val g = guest(db, "b")
        db.withConnection { implicit c =>
          (1 to 3).foreach { n =>
            SQL("""INSERT INTO mobile_guest_claim_tokens (token_hash,guest_id,created_at)
              VALUES ({t},{g}::uuid,{at})""")
              .on(
                "t"  -> MobileSecrets.hash(s"newer-$n"),
                "g"  -> g.toString,
                "at" -> stamp(now.plusSeconds(n.toLong))
              )
              .executeUpdate()
          }
        }
        store.claim(link("b"), rosa, page, now) mustBe Left(ClaimProblem.NotFound)
        store.claim(
          ByToken(MobileSecrets.hash("newer-3")),
          rosa,
          family(db, rosa, "maproulette-claim", withToken = false),
          now
        ) mustBe Left(ClaimProblem.Reauth)
        store.claim(ByToken(MobileSecrets.hash("newer-3")), rosa, page, now).isRight mustBe true
    }

    "D13: a claim accepted while publishing can't run starts as waiting" in withStore {
      (store, db) =>
        val g = guest(db, "w")
        val result =
          store.claim(secret(g, "w"), rosa, family(db, rosa, client), now, waiting = true)
        result.map(_.claim.state) mustBe Right("waiting")
        db.withConnection { implicit c =>
          SQL("SELECT waited_at IS NOT NULL FROM mobile_claims WHERE guest_id={g}::uuid")
            .on("g" -> g.toString)
            .as(SqlParser.scalar[Boolean].single) mustBe true
        }
    }

    "claim on the phone with the guest secret, which is spent" in withStore { (store, db) =>
      val g     = guest(db, "c")
      val phone = family(db, rosa, client)
      store.claim(BySecret(g, MobileSecrets.hash("wrong"), client), rosa, phone, now) mustBe
        Left(ClaimProblem.NotFound)
      store.claim(BySecret(g, MobileSecrets.hash("secret-c"), "other"), rosa, phone, now) mustBe
        Left(ClaimProblem.NotFound)
      val result = store.claim(secret(g, "c"), rosa, phone, now).toOption.get
      result.claim.familyId mustBe phone
      result.claim.state mustBe "queued"
      result.pending mustBe 2
      db.withConnection { implicit c =>
        SQL(
          "SELECT secret_hash IS NULL AND exchanged_at IS NOT NULL FROM mobile_guests WHERE id={g}::uuid"
        ).on("g" -> g.toString)
          .as(SqlParser.scalar[Boolean].single) mustBe true
      }
      store.claim(secret(g, "c"), rosa, phone, now).map(_.existing) mustBe Right(true)
      // Someone else with the (now spent) secret learns nothing.
      store.claim(secret(g, "c"), sam, family(db, sam, client), now) mustBe
        Left(ClaimProblem.NotFound)
    }

    "retry failed rows, reopen the claim and re-seal a new grant's OSM token" in withStore {
      (store, db) =>
        val g      = guest(db, "r")
        val page   = family(db, rosa, "maproulette-claim")
        val result = store.claim(link("r"), rosa, page, now).toOption.get
        val claim  = result.claim
        db.withConnection { implicit c =>
          SQL("""UPDATE choice_pending SET state='failed', attempts=5,
            result=CAST({result} AS jsonb) WHERE claim_id={id}""")
            .on("id" -> claim.id, "result" -> """{"error":"osm_unavailable"}""")
            .executeUpdate()
          SQL("UPDATE mobile_claims SET state='done' WHERE id={id}")
            .on("id" -> claim.id)
            .executeUpdate()
        }
        def rows() = db.withConnection { implicit c =>
          SQL(
            "SELECT task_id, state, attempts FROM choice_pending WHERE claim_id={id} ORDER BY task_id"
          ).on("id" -> claim.id)
            .as((SqlParser.long(1) ~ SqlParser.str(2) ~ SqlParser.int(3)).map {
              case t ~ st ~ a => (t, st, a)
            }.*)
        }
        store.retry(claim.id, sam, page, None, waiting = false, now) mustBe Left(
          ClaimProblem.NotFound
        )
        store.retry(
          claim.id,
          rosa,
          family(db, rosa, "maproulette-claim", withToken = false),
          None,
          waiting = false,
          now
        ) mustBe Left(ClaimProblem.Reauth)
        store.retry(claim.id, rosa, page, Some(Set(2L)), waiting = true, now).map {
          case (c, n) => (c.state, n)
        } mustBe Right(("waiting", 1))
        rows() mustBe List((1L, "failed", 5), (2L, "pending", 0))
        // A fresh sign-in: its token replaces the claim family's.
        val fresh = family(db, rosa, "maproulette-claim")
        db.withConnection { implicit c =>
          SQL(
            "UPDATE mobile_osm_tokens SET ciphertext=decode('0909','hex') WHERE grant_family_id={f}"
          ).on("f" -> fresh)
            .executeUpdate()
        }
        store.retry(claim.id, rosa, fresh, None, waiting = false, now).map {
          case (c, n) => (c.state, n)
        } mustBe Right(("queued", 1))
        rows() mustBe List((1L, "pending", 0), (2L, "pending", 0))
        db.withConnection { implicit c =>
          SQL("SELECT encode(ciphertext,'hex') FROM mobile_osm_tokens WHERE grant_family_id={f}")
            .on("f" -> claim.familyId)
            .as(SqlParser.scalar[String].single) mustBe "0909"
        }
        // Nothing failed and the claim isn't failed: left alone.
        store.retry(claim.id, rosa, page, None, waiting = false, now).map {
          case (c, n) => (c.state, n)
        } mustBe Right(("queued", 0))
    }
  }
}

package org.maproulette.auth.mobile

import anorm._
import com.typesafe.config.ConfigFactory
import java.nio.file.{Files, Paths}
import java.util.UUID
import org.scalatestplus.play.PlaySpec
import play.api.Configuration
import play.api.db.{Database, Databases}
import play.api.libs.json.Json

/**
  * Real PostgreSQL tests for evolution 131: the clients table, seeding precedence, disabled
  * clients, client writes with revocation, and the audit log. Opt-in like
  * [[MobileOAuthRepositorySpec]]: each case uses a fresh schema on a disposable test database.
  */
class MobileAdminRepositorySpec extends PlaySpec {
  private def settings(clients: String) = new MobileOAuthSettings(
    Configuration(ConfigFactory.parseString(s"""
      mobileOAuth {
        enabled = true
        callbackUri = "https://mr.example/oauth/mobile/callback"
        clients = [$clients]
      }"""))
  )
  private val app =
    """{ id = "app", name = "App", redirectUris = ["org.example.app:/cb"], scopes = ["tasks:read", "tasks:write"] }"""
  private val admin =
    """{ id = "admin", name = "Admin", redirectUris = ["https://admin.example/callback"], scopes = ["mobile:admin"] }"""

  private def withDb(test: Database => Unit): Unit = {
    val url = sys.env.getOrElse(
      "MOBILE_OAUTH_TEST_DATABASE_URL",
      cancel("Set MOBILE_OAUTH_TEST_DATABASE_URL to a disposable PostgreSQL database")
    )
    val username = sys.env.getOrElse("MOBILE_OAUTH_TEST_DATABASE_USER", "mobile_oauth_test")
    val password = sys.env.getOrElse("MOBILE_OAUTH_TEST_DATABASE_PASSWORD", "")
    val schema   = "mobile_admin_test_" + UUID.randomUUID().toString.replace("-", "")
    val root =
      Databases(
        "org.postgresql.Driver",
        url,
        config = Map("username" -> username, "password" -> password)
      )
    root.withConnection(implicit c => SQL(s"CREATE SCHEMA $schema").execute())
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
        SQL("CREATE TABLE tasks(id bigint PRIMARY KEY)").execute()
        Seq("129", "130", "131").foreach { version =>
          val text = new String(
            Files.readAllBytes(Paths.get(s"conf/evolutions/default/$version.sql")),
            "UTF-8"
          )
          val ups = text.split("# --- !Ups")(1).split("# --- !Downs")(0)
          scala.util.Using.resource(c.createStatement()) { statement =>
            ups
              .split(";;")
              .map(_.linesIterator.filterNot(_.trim.startsWith("--")).mkString("\n").trim)
              .filter(_.nonEmpty)
              .foreach(statement.execute)
          }
        }
      }
      test(db)
    } finally {
      db.shutdown()
      // The generated schema was created above for this case only.
      root.withConnection(implicit c => SQL(s"DROP SCHEMA $schema CASCADE").execute())
      root.shutdown()
    }
  }

  private def family(db: Database, id: String, client: String): Unit =
    db.withConnection { implicit c =>
      SQL("""INSERT INTO mobile_oauth_families
        (family_id,user_id,client_id,scope,redirect_uri,code_challenge,created_at)
        VALUES ({id},1,{client},'tasks:read','x','y',NOW())""")
        .on("id" -> id, "client" -> client)
        .executeUpdate()
      SQL("""INSERT INTO mobile_osm_tokens(grant_family_id,user_id,ciphertext,nonce,osm_scope)
        VALUES ({id},1,'\x00','\x00','read_prefs write_api')""").on("id" -> id).executeUpdate()
    }

  "Evolution 131" should {
    "use ';' only as Play's ';;' statement separator, also in comments" in {
      // Play splits evolutions on a single ';', even inside SQL comments.
      val text =
        new String(Files.readAllBytes(Paths.get("conf/evolutions/default/131.sql")), "UTF-8")
      text.replace(";;", "").contains(";") mustBe false
    }
  }

  "The mobile client registry" should {
    "seed config clients into the table on first use and read them back" in withDb { db =>
      val registry = new DbMobileClientRegistry(db, settings(s"$app, $admin"))
      registry.all.map(_.id) mustBe Seq("admin", "app")
      registry.get("app").map(_.scopes) mustBe Some(Set("tasks:read", "tasks:write"))
      registry.get("admin").map(_.redirectUris) mustBe Some(Set("https://admin.example/callback"))
      db.withConnection { implicit c =>
        SQL(
          "SELECT COUNT(*) FROM mobile_oauth_clients WHERE created_by IS NULL AND updated_by IS NULL"
        ).as(SqlParser.scalar[Long].single) mustBe 2
      }
    }

    "follow config for unedited rows, keep admin edits, and disable unedited rows dropped from config" in withDb {
      db =>
        new DbMobileClientRegistry(db, settings(s"$app, $admin")).all.size mustBe 2
        val repository =
          new MobileAdminRepository(db, new DbMobileClientRegistry(db, settings(s"$app, $admin")))
        repository
          .updateClient("admin", MobileClientPatch(name = Some("Edited")), 1L, revokeGrants = false)
          .map(_._1.name) mustBe Some("Edited")
        repository.createClient(
          MobileClient("extra", "Extra", Set("org.example.extra:/cb")),
          1L
        ) mustBe true
        // Next process: config renames both, and drops "app" and "admin" is still listed.
        val renamedApp   = app.replace("name = \"App\"", "name = \"App 2\"")
        val renamedAdmin = admin.replace("name = \"Admin\"", "name = \"Admin 2\"")
        val next         = new DbMobileClientRegistry(db, settings(s"$renamedApp, $renamedAdmin"))
        next.get("app").map(_.name) mustBe Some("App 2")
        next.get("admin").map(_.name) mustBe Some("Edited")
        val dropped = new DbMobileClientRegistry(db, settings(renamedAdmin))
        dropped.known("app").map(_.enabled) mustBe Some(false)
        dropped.get("app") mustBe None
        dropped.get("admin").map(_.name) mustBe Some("Edited")
        dropped.get("extra").map(_.name) mustBe Some("Extra")
        // A config client that reappears is enabled again: the row was never admin-edited.
        new DbMobileClientRegistry(db, settings(s"$app, $admin")).get("app").isDefined mustBe true
    }

    "treat a disabled client as unknown for OAuth but known for revocation" in withDb { db =>
      val registry   = new DbMobileClientRegistry(db, settings(app))
      val repository = new MobileAdminRepository(db, registry)
      registry.allowedScopes("app", "tasks:read").isDefined mustBe true
      repository.updateClient(
        "app",
        MobileClientPatch(enabled = Some(false)),
        1L,
        revokeGrants = false
      )
      registry.get("app") mustBe None
      registry.known("app").isDefined mustBe true
      registry.allowedScopes("app", "tasks:read") mustBe None
    }

    "be empty and untouched while mobile OAuth is disabled" in withDb { db =>
      val registry = new DbMobileClientRegistry(db, new MobileOAuthSettings(Configuration.empty))
      registry.all mustBe empty
      db.withConnection { implicit c =>
        SQL("SELECT COUNT(*) FROM mobile_oauth_clients").as(SqlParser.scalar[Long].single) mustBe 0
      }
    }
  }

  "Mobile admin writes" should {
    "refuse a duplicate id and record creates and updates in the audit log" in withDb { db =>
      val repository = new MobileAdminRepository(db, new DbMobileClientRegistry(db, settings(app)))
      repository.createClient(MobileClient("app", "Dup", Set("org.example.dup:/cb")), 2L) mustBe false
      repository.createClient(MobileClient("new", "New", Set("org.example.new:/cb")), 2L) mustBe true
      repository.updateClient("missing", MobileClientPatch(enabled = Some(false)), 2L, false) mustBe None
      repository.updateClient(
        "new",
        MobileClientPatch(scopes = Some(Set("tasks:read", "tasks:write"))),
        2L,
        false
      )
      val (entries, total) = repository.audit(10, 0)
      total mustBe 2
      entries.map(_.action) mustBe Seq("client.update", "client.create")
      entries.map(_.actorUserId).distinct mustBe Seq(2L)
      entries.head.before.map(_ \ "scopes").flatMap(_.asOpt[Seq[String]]) mustBe Some(
        Seq("tasks:read")
      )
      entries.head.after.map(_ \ "scopes").flatMap(_.asOpt[Seq[String]]) mustBe
        Some(Seq("tasks:read", "tasks:write"))
      entries(1).before mustBe None
      val listed =
        repository.listClients.map(json => (json \ "id").as[String] -> (json \ "source").as[String])
      listed mustBe Seq("app" -> "config", "new" -> "admin")
    }

    "revoke only the disabled client's active grant families and their OSM tokens" in withDb { db =>
      val registry   = new DbMobileClientRegistry(db, settings(s"$app, $admin"))
      val repository = new MobileAdminRepository(db, registry)
      registry.all.size mustBe 2
      family(db, "f1", "app")
      family(db, "f2", "app")
      family(db, "f3", "admin")
      val (client, revoked) = repository
        .updateClient("app", MobileClientPatch(enabled = Some(false)), 1L, revokeGrants = true)
        .get
      client.enabled mustBe false
      revoked mustBe 2
      db.withConnection { implicit c =>
        SQL("SELECT family_id FROM mobile_oauth_families WHERE revoked_at IS NULL")
          .as(SqlParser.str(1).*) mustBe Seq("f3")
        SQL("SELECT grant_family_id FROM mobile_osm_tokens").as(SqlParser.str(1).*) mustBe Seq("f3")
      }
      (repository.audit(1, 0)._1.head.after.get \ "revokedGrantFamilies").as[Int] mustBe 2
    }

    "page the audit log newest first" in withDb { db =>
      val repository = new MobileAdminRepository(db, new DbMobileClientRegistry(db, settings(app)))
      (1 to 5).foreach(i =>
        repository.record(1L, "stock.PUT", s"/api/v2/challenge/$i", Some(Json.obj("status" -> 200)))
      )
      val (first, total) = repository.audit(2, 0)
      total mustBe 5
      first.map(_.target) mustBe Seq("/api/v2/challenge/5", "/api/v2/challenge/4")
      repository.audit(2, 4)._1.map(_.target) mustBe Seq("/api/v2/challenge/1")
    }
  }
}

package org.maproulette.provider.choice

import akka.actor.ActorSystem
import anorm._
import com.typesafe.config.ConfigFactory
import controllers.MobileWizardController
import org.maproulette.Config
import org.maproulette.auth.mobile._
import org.maproulette.framework.model._
import org.maproulette.framework.util.FrameworkHelper
import org.maproulette.models.dal.DALManager
import org.maproulette.provider.ChallengeProvider
import play.api.{Application, Configuration}
import play.api.db.Database
import play.api.libs.json.{JsValue, Json}
import play.api.libs.ws.WSClient
import play.api.test.FakeRequest
import play.api.test.Helpers._
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

/**
  * The campaign wizard's create and publish against the database, with a fake Overpass and the
  * real organizer gate. Runs in FrameworkMasterSuite (tag "choice").
  */
class MobileWizardSpec(implicit val application: Application) extends FrameworkHelper {
  override implicit val projectTestName: String               = "MobileWizardSpecProject"
  private implicit val ec: ExecutionContext                   = application.injector.instanceOf[ExecutionContext]
  private implicit val materializer: akka.stream.Materializer = application.materializer
  private val db                                              = application.injector.instanceOf[Database]
  private val audit                                           = application.injector.instanceOf[MobileAdminRepository]
  private val fake                                            = new FakeOverpass
  override def afterAll(): Unit                               = { fake.stop(); super.afterAll() }

  private val settings = new MobileOAuthSettings(
    Configuration(
      ConfigFactory.parseString(
        """
    mobileOAuth { enabled = true, callbackUri = "https://mr.example/cb", clients = [] }"""
      )
    )
  )
  private val gate = application.injector.instanceOf[MobileAdminGate]
  private val admins = new MobileAdminCheck {
    def isAdmin(value: User): Boolean                       = value.id == User.superUser.id
    def isAdmin(userId: Long): Boolean                      = userId == User.superUser.id
    override def isOrganizer(userId: Long): Boolean         = gate.isOrganizer(userId)
    override def organizerProjects(user: User): Set[Long]   = gate.organizerProjects(user)
    override def projectOf(challengeId: Long): Option[Long] = gate.projectOf(challengeId)
  }
  private val service = new MobileOAuthService(
    mock[MobileOAuthStore],
    settings,
    new StaticMobileClientRegistry(settings),
    admins,
    application.injector.instanceOf[ActorSystem]
  )
  private val wizard = new CampaignWizard(
    new QuestionSetLibrary(),
    new WizardOverpass(application.injector.instanceOf[WSClient], mock[Config]) {
      override protected def urls: Seq[String]              = Seq(fake.url)
      override protected def requestTimeout: FiniteDuration = 1.second
    },
    db,
    application.injector.instanceOf[DALManager],
    application.injector.instanceOf[ChallengeProvider],
    serviceManager.tag,
    audit
  )
  private val controller = new MobileWizardController(
    stubControllerComponents(),
    settings,
    service,
    wizard,
    serviceManager.project
  )
  private val organizers = new MobileOrganizerRepository(db, audit)

  private def as(user: User, scope: String = MobileScopes.Organize) =
    FakeRequest()
      .addAttr(MobileBearerIdentity.UserKey, user)
      .addAttr(MobileBearerIdentity.ScopesKey, Set(scope))

  private def project(name: String): Project =
    serviceManager.project.create(Project(-1, User.superUser.osmProfile.id, name), User.superUser)

  private def campaign(projectId: Long, leaveOut: Seq[String] = Seq("node/2")): JsValue = Json.obj(
    "set"     -> "entrance-doors",
    "version" -> 1,
    "area" -> Json
      .obj("lat" -> 40.7538, "lon" -> -111.9068, "radius" -> 800, "place" -> "Woodbine Food Hall"),
    "leaveOut"    -> leaveOut,
    "name"        -> "Entrance doors around Woodbine Food Hall",
    "description" -> "Help map the entrances around Woodbine Food Hall.",
    "eventDate"   -> "2026-11-10",
    "projectId"   -> projectId
  )

  private def column[A](sql: SimpleSql[Row], parser: RowParser[A]): A =
    db.withConnection(implicit c => sql.as(parser.single))

  "The campaign wizard" should {
    "create a draft campaign from a set and an area, and publish it" taggedAs ChoiceTag in {
      val created =
        serviceManager.user.create(getTestUser(77101, "Wizard_Organizer"), User.superUser)
      val mine   = project("Wizard spec: mine")
      val theirs = project("Wizard spec: theirs")
      organizers.add(
        organizers.candidate(created.id).get,
        mine.id,
        Grant.ROLE_WRITE_ACCESS,
        User.superUser.id
      ) mustBe
        Right(())
      serviceManager.user.clearCache(created.id)
      val organizer = serviceManager.user.retrieve(created.id).get

      fake.answer = _ =>
        (
          200,
          FakeOverpass.elements(
            FakeOverpass.countElement(4),
            FakeOverpass.countElement(3),
            FakeOverpass.node(1, "entrance" -> "main", "name" -> "Granary Live"),
            FakeOverpass.node(2, "entrance" -> "service"),
            FakeOverpass.node(3, "entrance" -> "yes", "door" -> "hinged")
          )
        )
      val made = controller.create(as(organizer).withBody(campaign(mine.id)))
      status(made) mustBe CREATED
      val id = (contentAsJson(made) \ "id").as[Long]
      (contentAsJson(made) \ "tasks").as[Int] mustBe 2
      // Overpass was asked again, not served from a cache.
      fake.queries.last must include("node[\"entrance\"]")

      column(
        SQL"""SELECT enabled, requires_local, cooperative_type, parent_id, checkin_comment
              FROM challenges WHERE id = $id""",
        (SqlParser.bool("enabled") ~ SqlParser.bool("requires_local") ~ SqlParser.int(
          "cooperative_type"
        ) ~
          SqlParser.long("parent_id") ~ SqlParser.str("checkin_comment")).map {
          case a ~ b ~ c ~ d ~ e => (a, b, c, d, e)
        }
      ) mustBe (false, true, 3, mine.id, "From a Street Tally survey for Entrance doors around Woodbine Food Hall, 10 November 2026. " +
        "Checked by me. #StreetTally #StreetTallyWoodbineFoodHall")
      column(
        SQL"SELECT COUNT(*) FROM tasks WHERE parent_id = $id",
        SqlParser.scalar[Long]
      ) mustBe 2
      column(
        SQL"""SELECT set_id || ' ' || set_version || ' ' || place || ' ' || event_date || ' ' || left_out
              FROM mobile_campaign_sources WHERE challenge_id = $id""",
        SqlParser.scalar[String]
      ) mustBe "entrance-doors 1 Woodbine Food Hall 2026-11-10 1"
      column(
        SQL"""SELECT actor_user_id FROM mobile_admin_audit
              WHERE action = 'campaign.create' AND target = ${s"challenge:$id"}""",
        SqlParser.scalar[Long]
      ) mustBe organizer.id

      // Nothing left once every open place is left out; Overpass failing is 502, timing out 504.
      val empty = controller.create(
        as(organizer).withBody(campaign(mine.id, Seq("node/1", "node/2", "node/3")))
      )
      status(empty) mustBe UNPROCESSABLE_ENTITY
      contentAsJson(empty) mustBe Json.obj("error" -> "nothing_to_check")
      fake.answer = _ => (504, "")
      val down = controller.create(as(organizer).withBody(campaign(mine.id)))
      status(down) mustBe BAD_GATEWAY
      contentAsJson(down) mustBe Json.obj("error" -> "osm_unavailable")
      contentAsString(down) must not include "Exception"
      fake.answer = _ => { Thread.sleep(1500); (200, FakeOverpass.elements()) }
      val slow = controller.create(as(organizer).withBody(campaign(mine.id)))
      status(slow) mustBe GATEWAY_TIMEOUT
      contentAsJson(slow) mustBe Json.obj("error" -> "osm_timeout")
      val queried = fake.count
      // Past 2000 open places the area is too big; Overpass is asked once, never retried.
      fake.answer = _ =>
        (
          200,
          FakeOverpass.elements(FakeOverpass.countElement(2001), FakeOverpass.countElement(2001))
        )
      val big = controller.create(as(organizer).withBody(campaign(mine.id)))
      status(big) mustBe BAD_REQUEST
      contentAsJson(big) mustBe Json.obj("error" -> "area_too_big")
      fake.count mustBe queried + 1

      // Publish: listed when the project is on.
      val published = controller.publish(id)(as(organizer))
      status(published) mustBe OK
      contentAsJson(published) mustBe Json.obj(
        "id"        -> id,
        "published" -> true,
        "listed"    -> mine.enabled
      )
      column(
        SQL"""SELECT c.enabled AND EXISTS(SELECT 1 FROM tags_on_challenges tc JOIN tags t ON t.id = tc.tag_id
                WHERE tc.challenge_id = c.id AND t.name = 'mobile-survey-v1') FROM challenges c WHERE c.id = $id""",
        SqlParser.scalar[Boolean]
      ) mustBe true
      db.withConnection(implicit c =>
        SQL"UPDATE projects SET enabled = false WHERE id = ${mine.id}".executeUpdate()
      )
      serviceManager.project.clearCache(mine.id)
      (contentAsJson(controller.publish(id)(as(organizer))) \ "listed").as[Boolean] mustBe false

      // A super-user creates in any project; the organizer can't publish that one.
      fake.answer = _ =>
        (
          200,
          FakeOverpass.elements(
            FakeOverpass.countElement(1),
            FakeOverpass.countElement(1),
            FakeOverpass.node(5, "entrance" -> "main")
          )
        )
      val other =
        controller.create(as(User.superUser, MobileScopes.Admin).withBody(campaign(theirs.id)))
      status(other) mustBe CREATED
      val otherId = (contentAsJson(other) \ "id").as[Long]
      contentAsJson(controller.publish(otherId)(as(organizer))) mustBe Json.obj(
        "error" -> "organizer_forbidden"
      )
      val notTheirs = controller.create(as(organizer).withBody(campaign(theirs.id)))
      status(notTheirs) mustBe FORBIDDEN
      contentAsJson(notTheirs) mustBe Json.obj("error" -> "organizer_forbidden")
      // Their Home project is never theirs to run campaigns in, though they administer it.
      val homeName = s"Home_${organizer.osmProfile.id}"
      val home = serviceManager.project
        .retrieveByName(homeName)
        .getOrElse(
          serviceManager.project.create(Project(-1, organizer.osmProfile.id, homeName), organizer)
        )
      serviceManager.user.clearCache(organizer.id)
      val inHome = controller.create(as(organizer).withBody(campaign(home.id)))
      status(inHome) mustBe FORBIDDEN
      contentAsJson(inHome) mustBe Json.obj("error" -> "organizer_forbidden")
      status(controller.publish(Long.MaxValue)(as(User.superUser, MobileScopes.Admin))) mustBe NOT_FOUND
    }

    "read the nightly extract where it covers the area, and Overpass elsewhere" taggedAs ChoiceTag in {
      val overpass = new WizardOverpass(application.injector.instanceOf[WSClient], mock[Config]) {
        override protected def urls: Seq[String] = Seq(fake.url)
      }
      val sources                                  = new WizardSources(new WizardExtract(db, mock[Config]), overpass)
      val library                                  = new QuestionSetLibrary()
      val doors                                    = library.find("entrance-doors").get
      val area                                     = SearchArea(40.7538, -111.9068, 800)
      def run[A](f: scala.concurrent.Future[A]): A = scala.concurrent.Await.result(f, 10.seconds)
      def node(id: Long, lon: Double, lat: Double, tags: (String, String)*) =
        db.withConnection { implicit c =>
          val json = Json.stringify(Json.toJson(tags.toMap))
          SQL"""INSERT INTO wizard_osm_features (kind, osm_id, tags, geom)
                VALUES ('node', $id, $json::jsonb, ST_SetSRID(ST_MakePoint($lon, $lat), 4326))"""
            .executeUpdate()
        }
      def extract(cutHoursAgo: Int) = db.withConnection { implicit c =>
        SQL"""INSERT INTO wizard_osm_extract (id, source, extract_at, features, coverage)
              VALUES (1, 'test', NOW() - ($cutHoursAgo * INTERVAL '1 hour'), 4,
                ST_MakeEnvelope(-112.2, 40.6, -111.6, 40.9, 4326))
              ON CONFLICT (id) DO UPDATE SET extract_at = EXCLUDED.extract_at"""
          .executeUpdate()
      }
      try {
        node(11, -111.9068, 40.7540, "entrance" -> "main", "name" -> "Granary \"Live\"")
        node(12, -111.9070, 40.7536, "entrance" -> "emergency")
        node(13, -111.8500, 40.7538, "entrance" -> "yes")
        node(14, -111.9066, 40.7539, "natural"  -> "tree")
        extract(3)
        fake.answer = _ => (504, "")
        val asked = fake.count

        val open = run(sources.features(doors, area, 2001, 1, cached = true)).toOption.get
        (open.all, open.open) mustBe (1L, 1L)
        open.features.map(f => (f.element, f.tags.get("name"))) mustBe
          List(("node/11", Some("Granary \"Live\"")))
        val counts = run(sources.counts(library.sets, area, 1)).toOption.get
        counts.head mustBe SetCount(1, 1)
        counts.map(_.all).sum mustBe 2
        fake.count mustBe asked

        // Outside the extract, or once it is two days old, Overpass answers (here: it can't).
        run(sources.features(doors, area.copy(lon = -110.0), 2001, 1, cached = true)) mustBe
          Left(WizardQueryFailure("osm_unavailable"))
        extract(49)
        run(sources.features(doors, area, 2001, 1, cached = true)) mustBe
          Left(WizardQueryFailure("osm_unavailable"))
        fake.count mustBe asked + 2
      } finally db.withConnection { implicit c =>
        SQL"DELETE FROM wizard_osm_features".executeUpdate()
        SQL"DELETE FROM wizard_osm_extract".executeUpdate()
      }
    }
  }
}

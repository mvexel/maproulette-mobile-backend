package org.maproulette.provider.choice

import akka.actor.ActorSystem
import akka.stream.{Materializer, SystemMaterializer}
import com.typesafe.config.ConfigFactory
import controllers.MobileWizardController
import org.joda.time.DateTime
import org.maproulette.Config
import org.maproulette.auth.mobile._
import org.maproulette.framework.model.{Location, OSMProfile, User}
import org.maproulette.framework.service.{ProjectService, TagService}
import org.maproulette.models.dal.DALManager
import org.maproulette.provider.ChallengeProvider
import org.scalatest.BeforeAndAfterAll
import org.scalatestplus.mockito.MockitoSugar
import org.scalatestplus.play.PlaySpec
import play.api.Configuration
import play.api.db.Database
import play.api.libs.json._
import play.api.libs.ws.ahc.AhcWSClient
import play.api.test.FakeRequest
import play.api.test.Helpers._
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext}
import scala.io.Source

/** The campaign wizard without a database: the library, Overpass QL, the client and the gate. */
class CampaignWizardSpec extends PlaySpec with MockitoSugar with BeforeAndAfterAll {
  private implicit val system: ActorSystem = ActorSystem(
    "campaign-wizard-test",
    ConfigFactory.parseString("""
    mobile-oauth-dispatcher {
      type=Dispatcher
      executor="thread-pool-executor"
      thread-pool-executor.fixed-pool-size=2
    }
  """)
  )
  private implicit val materializer: Materializer = SystemMaterializer(system).materializer
  private implicit val ec: ExecutionContext       = system.dispatcher
  private val ws                                  = AhcWSClient()
  private val fake                                = new FakeOverpass
  override def afterAll(): Unit = {
    fake.stop()
    ws.close()
    Await.result(system.terminate(), 5.seconds)
    super.afterAll()
  }

  private val library = new QuestionSetLibrary()
  private var clock   = 1000000L
  private def overpass() = new WizardOverpass(ws, mock[Config]) {
    override protected def urls: Seq[String] = Seq(fake.url)
    override protected def now(): Long       = clock
  }
  private def wizard(client: WizardOverpass = overpass()) =
    new CampaignWizard(
      library,
      client,
      mock[Database],
      mock[DALManager],
      mock[ChallengeProvider],
      mock[TagService],
      mock[MobileAdminRepository]
    )
  private def result[A](f: scala.concurrent.Future[A]): A = Await.result(f, 10.seconds)
  private val area                                        = SearchArea(40.7538, -111.9068, 800)
  private val doors                                       = library.find("entrance-doors").get

  "The question-set library" should {
    "hold the five starting sets, with only Iris's words for the client" in {
      library.sets.map(_.id) mustBe List(
        "entrance-doors",
        "crossings",
        "bus-stops",
        "benches",
        "trees"
      )
      library.sets.foreach { set =>
        set.publicJson.keys mustBe
          Set("id", "version", "name", "why", "audience", "noun", "example", "pace", "description")
      }
      (doors.publicJson \ "why").as[String] mustBe
        "Wheelchair, stroller and walker users know what to expect"
      (library.find("benches").get.publicJson \ "example").as[String] mustBe
        "Does the bench have armrests?"
    }

    "refuse a set the backend couldn't use" in {
      val raw = (Json.parse(
        Source.fromResource("mobile/question-sets.json").mkString
      ) \ "sets" \ 0).as[JsObject]
      def problems(change: JsObject => JsObject) =
        QuestionSet.parse(change(raw)).left.getOrElse(Nil)
      problems(identity) mustBe Nil
      problems(_ + ("example" -> JsString("Something else?"))) must contain(
        "entrance-doors: example must be one of the questions' prompts"
      )
      problems(_ + ("match" -> Json.obj("shop" -> "yes"))) must contain(
        "entrance-doors: match shop=yes is not in filter.tags"
      )
      problems(_ + ("filter" -> Json.obj("elements" -> Json.arr("relation"), "tags" -> Json.obj()))) must
        contain allOf (
        "entrance-doors: filter.elements must list node and/or way",
        "entrance-doors: filter.tags must not be empty"
      )
      // A question the backend's choice check refuses.
      val badQuestion = (raw \ "questions").as[JsArray].value.head.as[JsObject] + ("id" -> JsString(
        "Door Type"
      ))
      problems(_ + ("questions" -> Json.arr(badQuestion)))
        .exists(_.contains("task payload")) mustBe true
      QuestionSetLibrary.parse(Json.obj("sets" -> Json.arr(raw, raw))).left.toOption.get mustBe
        List("entrance-doors: listed twice")
    }

    "make the same task lines as the admin's survey builder" in {
      // test/resources/wizard/golden-tasks.json: the admin's generateTasks (src/survey.ts) on the
      // library's sets. Regenerate both sides together when either changes.
      val golden = Json.parse(Source.fromResource("wizard/golden-tasks.json").mkString).as[JsObject]
      golden.keys mustBe library.sets.map(_.id).toSet
      library.sets.foreach { set =>
        val features = (golden \ set.id \ "features").as[List[JsObject]].map { f =>
          val props           = (f \ "properties").as[Map[String, String]]
          val Array(kind, id) = props("@id").split("/")
          val Seq(lon, lat)   = (f \ "geometry" \ "coordinates").as[Seq[Double]]
          SetFeature(kind, id.toLong, lon, lat, props - "@id")
        }
        withClue(set.id) {
          features.flatMap(set.taskLine).map(Json.parse) mustBe
            (golden \ set.id \ "lines").as[List[JsValue]]
        }
      }
    }

    "pick what each set's filter picks, and nothing already answered" in {
      doors.isOpen("node", Map("entrance" -> "main")) mustBe true
      doors.isOpen("node", Map("entrance" -> "emergency")) mustBe false
      doors.isOpen("node", Map("entrance" -> "main", "access" -> "private")) mustBe false
      doors.isOpen("way", Map("entrance"  -> "main")) mustBe false
      doors.isOpen("node", Map("entrance" -> "main", "door" -> "hinged")) mustBe true
      doors.isOpen("node", Map("entrance" -> "main", "door" -> "hinged", "automatic_door" -> "no")) mustBe
        false
      library
        .find("crossings")
        .get
        .isOpen("node", Map("highway" -> "crossing", "crossing" -> "no")) mustBe
        false
    }
  }

  "The Overpass queries" should {
    "count every set in one query and list one set's open features" in {
      val counts = OverpassQuery.counts(library.sets, area)
      counts must startWith("[out:json][timeout:10];")
      counts must include(
        """(node["entrance"]["access"!~"^(private|no)$"]["entrance"!~"^(emergency)$"](around:800,40.7538000,-111.9068000);)->.a0;"""
      )
      counts must include(
        """(node.a0[!"door"];node.a0[!"automatic_door"];)->.o0;.o0 out count;.a0 out count;"""
      )
      counts must include(
        """node["highway"="crossing"]["access"!~"^(private|no)$"]["crossing"!~"^(no)$"]"""
      )
      counts must include(""".o4 out count;.a4 out count;""")
      OverpassQuery.features(doors, area, 2001) must endWith(
        ".a0 out count;.o0 out count;.o0 out tags center 2001;"
      )
    }

    "quote what it puts in strings" in {
      val odd = doors.copy(filter =
        SetFilter(List("node"), Map("na\"me" -> Some("a\\b")), Map("x" -> List("a.b", "c|d")))
      )
      OverpassQuery.counts(Seq(odd), area) must include(
        """node["na\"me"="a\\b"]["x"!~"^(a\\.b|c\\|d)$"]"""
      )
    }
  }

  "The wizard's words" should {
    "make the admin's hashtag and changeset comment" in {
      // Expected values from the admin's words.ts hashtag() and changesetComment().
      CampaignWizard.hashtag("Woodbine Food Hall") mustBe "#StreetTallyWoodbineFoodHall"
      CampaignWizard.hashtag("Café São-Paulo’s 9th ave") mustBe "#StreetTallyCafeSaoPauloS9thAve"
      CampaignWizard.hashtag("Ninth & Ninth, Salt Lake City, Utah, United States") mustBe
        "#StreetTallyNinthNinthSaltLakeCityUtahUn"
      CampaignWizard.changesetComment(
        "Entrance doors around Woodbine Food Hall",
        "Woodbine Food Hall",
        Some(java.time.LocalDate.of(2026, 11, 10))
      ) mustBe "From a Street Tally survey for Entrance doors around Woodbine Food Hall, 10 November 2026. " +
        "Checked by me. #StreetTally #StreetTallyWoodbineFoodHall"
      CampaignWizard.changesetComment("Trees", "Liberty Park", None) mustBe
        "From a Street Tally survey for Trees. Checked by me. #StreetTally #StreetTallyLibertyPark"
    }
  }

  "The wizard's Overpass client" should {
    "answer from Overpass, then from its cache" in {
      val client = overpass()
      val before = fake.count
      fake.answer = _ => (200, FakeOverpass.elements(FakeOverpass.countElement(3)))
      result(client.elements("query-a", 1)).map(_.value.size) mustBe Right(1)
      result(client.elements("query-a", 1)).map(_.value.size) mustBe Right(1)
      fake.count mustBe before + 1
      fake.queries.last mustBe "query-a"
      fake.userAgents.last mustBe WizardOverpass.UserAgent
      // The create step reads OSM as it is now.
      result(client.elements("query-a", 1, cached = false)).isRight mustBe true
      fake.count mustBe before + 2
    }

    "say in one line per endpoint whether Overpass answers, and where its host resolves" in {
      val down = "http://127.0.0.1:9/api/interpreter"
      val both = new WizardOverpass(ws, mock[Config]) {
        override protected def urls: Seq[String] = Seq(down, fake.url)
      }
      val lines = result(both.probe())
      lines.size mustBe 2
      lines.head must startWith("Overpass not reachable: ")
      lines.head must endWith("(http://127.0.0.1:9/api/interpreter, resolves to 127.0.0.1 (IPv4))")
      lines(1) must fullyMatch regex
        s"Overpass answered: HTTP 200 in [0-9]+ ms \\(${java.util.regex.Pattern
          .quote(fake.url)}, resolves to 127\\.0\\.0\\.1 \\(IPv4\\)\\)"
      // A real query to the interpreter, not a status page some instances don't serve.
      fake.queries.last mustBe WizardOverpass.ProbeQuery
      fake.contentTypes.last must startWith("application/x-www-form-urlencoded")
      fake.userAgents.last mustBe WizardOverpass.UserAgent
      WizardOverpass.UserAgent must fullyMatch regex
        """StreetTally/\S+ \(\+https://streettally\.osm\.lol; martijn@osm\.lol\)"""
      // Anything but 200 carries the start of the server's own answer.
      val answer = fake.answer
      fake.answer = _ => (500, "<p>runtime error:\n  " + "x" * 400 + "</p>")
      try {
        val failed = result(both.probe())(1)
        failed must startWith("Overpass answered: HTTP 500 in ")
        failed must endWith(": " + ("<p>runtime error: " + "x" * 400).take(300))
      } finally fake.answer = answer
      WizardOverpass.resolved("https://[::1]/api/status") mustBe "resolves to 0:0:0:0:0:0:0:1 (IPv6)"
    }

    "say in one line whether the GeoParquet and extract hosts answer a ranged read (spike)" in {
      val server = com.sun.net.httpserver.HttpServer
        .create(new java.net.InetSocketAddress("127.0.0.1", 0), 0)
      val ranges = new java.util.concurrent.ConcurrentHashMap[String, String]()
      def serve(path: String, body: String, contentRange: String): Unit =
        server.createContext(
          path,
          exchange => {
            ranges.put(path, Option(exchange.getRequestHeaders.getFirst("Range")).getOrElse(""))
            val bytes = body.getBytes("UTF-8")
            exchange.getResponseHeaders.set("Content-Range", contentRange)
            exchange.sendResponseHeaders(206, bytes.length.toLong)
            exchange.getResponseBody.write(bytes)
            exchange.close()
          }
        )
      serve("/layercake/pois.parquet", "\u0000\u0001\u0002\u0003PAR1", "bytes 92-99/100")
      serve(
        "/utah-latest.osm.pbf",
        "\u0000\u0000\u0000\u000e\n\tOSMHeader\u0018",
        "bytes 0-15/2000"
      )
      server.start()
      try {
        val base    = s"http://127.0.0.1:${server.getAddress.getPort}"
        val file    = s"$base/layercake/pois.parquet"
        val extract = s"$base/utah-latest.osm.pbf"
        val probe = new WizardParquetProbe(ws, mock[Config]) {
          override protected def url: String        = file
          override protected def extractUrl: String = extract
        }
        def quoted(u: String) = java.util.regex.Pattern.quote(u)
        result(probe.probe()) must fullyMatch regex
          s"Parquet host answered: HTTP 206 in [0-9]+ ms, Parquet footer, bytes 92-99/100 \\(${quoted(file)}, resolves to 127\\.0\\.0\\.1 \\(IPv4\\)\\)"
        result(probe.extractProbe()) must fullyMatch regex
          s"Extract host answered: HTTP 206 in [0-9]+ ms, PBF header, bytes 0-15/2000 \\(${quoted(extract)}, resolves to 127\\.0\\.0\\.1 \\(IPv4\\)\\)"
        ranges.get("/layercake/pois.parquet") mustBe "bytes=-8"
        ranges.get("/utah-latest.osm.pbf") mustBe "bytes=0-15"
        val down = new WizardParquetProbe(ws, mock[Config]) {
          override protected def url: String        = "http://127.0.0.1:9/layercake/pois.parquet"
          override protected def extractUrl: String = "http://127.0.0.1:9/utah-latest.osm.pbf"
        }
        result(down.probe()) must startWith("Parquet host not reachable: ")
        result(down.extractProbe()) must startWith("Extract host not reachable: ")
      } finally server.stop(0)
    }

    "read its endpoints from OVERPASS_URLS, and try the next only when connecting fails" in {
      WizardOverpass.parseUrls(None) mustBe WizardOverpass.DefaultUrls
      WizardOverpass.parseUrls(Some(" ")) mustBe WizardOverpass.DefaultUrls
      WizardOverpass.parseUrls(Some("https://a/api/interpreter, https://b/api/interpreter")) mustBe
        Seq("https://a/api/interpreter", "https://b/api/interpreter")
      fake.answer = _ => (200, FakeOverpass.elements(FakeOverpass.countElement(3)))
      val fallback = new WizardOverpass(ws, mock[Config]) {
        override protected def urls: Seq[String] =
          Seq("http://127.0.0.1:9/api/interpreter", fake.url)
      }
      val before = fake.count
      result(fallback.elements("query-fallback", 1)).isRight mustBe true
      fake.count mustBe before + 1
      // A failure after connecting is the answer: the next endpoint isn't asked.
      fake.answer = _ => (504, "")
      val second = new WizardOverpass(ws, mock[Config]) {
        override protected def urls: Seq[String] = Seq(fake.url, fake.url)
      }
      result(second.elements("query-504", 1)) mustBe Left(WizardQueryFailure("osm_unavailable"))
      fake.count mustBe before + 2
    }

    "say when Overpass fails, runs out of time or is asked too often" in {
      val client = overpass()
      fake.answer = _ => (504, "")
      result(client.elements("query-b", 2)) mustBe Left(WizardQueryFailure("osm_unavailable"))
      fake.answer = _ =>
        (
          200,
          Json.stringify(
            Json.obj("elements" -> Json.arr(), "remark" -> "runtime error: Query timed out")
          )
        )
      result(client.elements("query-c", 2)) mustBe Left(WizardQueryFailure("osm_unavailable"))
      fake.answer = _ => (200, FakeOverpass.elements())
      (3 to 20).foreach(i => result(client.elements(s"query-$i", 2)).isRight mustBe true)
      result(client.elements("query-21", 2)) mustBe Left(WizardQueryFailure("rate_limited"))
      result(client.elements("query-21", 3)).isRight mustBe true
      clock += 61000
      result(client.elements("query-21", 2)).isRight mustBe true
    }
  }

  "The wizard" should {
    "count every set, or mark them all unavailable" in {
      val sets = library.sets.size
      fake.answer = _ =>
        (
          200,
          FakeOverpass.elements(
            (0 until sets).flatMap(i =>
              Seq(FakeOverpass.countElement(i), FakeOverpass.countElement(10))
            ): _*
          )
        )
      val counts = (result(wizard().counts(area, 5)).toOption.get \ "counts").as[List[JsObject]]
      counts.head mustBe Json.obj(
        "set"      -> "entrance-doors",
        "version"  -> 1,
        "open"     -> 0,
        "complete" -> 10
      )
      counts(4) mustBe Json.obj("set" -> "trees", "version" -> 1, "open" -> 4, "complete" -> 6)
      fake.answer = _ => (500, "")
      (result(wizard().counts(area.copy(radius = 900), 5)).toOption.get \ "counts")
        .as[List[JsObject]] mustBe
        library.sets.map(s => Json.obj("set" -> s.id, "version" -> s.version, "unavailable" -> true)
        )
    }

    "list open features, re-checked, and say when there are too many" in {
      fake.answer = _ =>
        (
          200,
          FakeOverpass.elements(
            FakeOverpass.countElement(5),
            FakeOverpass.countElement(2),
            FakeOverpass.node(1, "entrance" -> "main", "name" -> "Granary Live"),
            FakeOverpass
              .node(2, "entrance"           -> "main", "door" -> "hinged", "automatic_door" -> "no"),
            FakeOverpass.node(3, "entrance" -> "yes")
          )
        )
      val open = result(wizard().features(doors, area.copy(radius = 700), 6)).toOption.get
      open.shown.map(_.element) mustBe List("node/1", "node/3")
      open.complete mustBe 3
      open.truncated mustBe false
      fake.answer = _ =>
        (
          200,
          FakeOverpass.elements(FakeOverpass.countElement(2500), FakeOverpass.countElement(2400))
        )
      result(wizard().features(doors, area.copy(radius = 650), 6)).toOption.get.truncated mustBe true
    }
  }

  "The wizard routes" should {
    val settings = new MobileOAuthSettings(
      Configuration(
        ConfigFactory.parseString(
          """
      mobileOAuth { enabled = true, callbackUri = "https://mr.example/cb", clients = [] }"""
        )
      )
    )
    def user(id: Long) = User(
      id,
      DateTime.now(),
      DateTime.now(),
      OSMProfile(456, "Mapper", "", "", Location(1, 2), DateTime.now(), "legacy-token"),
      List.empty
    )
    val admins = new MobileAdminCheck {
      def isAdmin(value: User): Boolean                  = value.id == 7
      def isAdmin(userId: Long): Boolean                 = userId == 7
      override def isOrganizer(userId: Long): Boolean    = userId == 9
      override def organizerProjects(u: User): Set[Long] = if (u.id == 9) Set(3) else Set()
      override def projectOf(challengeId: Long): Option[Long] =
        Map(70L -> 3L, 71L -> 4L).get(challengeId)
    }
    val service = new MobileOAuthService(
      mock[MobileOAuthStore],
      settings,
      new StaticMobileClientRegistry(settings),
      admins,
      system
    )
    val controller = new MobileWizardController(
      stubControllerComponents(),
      settings,
      service,
      wizard(),
      mock[ProjectService]
    )
    def as(id: Long, scope: String = MobileScopes.Organize, uri: String = "/") =
      FakeRequest("GET", uri)
        .addAttr(MobileBearerIdentity.UserKey, user(id))
        .addAttr(MobileBearerIdentity.ScopesKey, Set(scope))
    val body = Json.obj(
      "set"     -> "entrance-doors",
      "version" -> 1,
      "area" -> Json.obj(
        "lat"    -> 40.7538,
        "lon"    -> -111.9068,
        "radius" -> 800,
        "place"  -> "Woodbine Food Hall"
      ),
      "leaveOut"    -> Json.arr("node/123"),
      "name"        -> "Entrance doors around Woodbine Food Hall",
      "description" -> "Help map the entrances around Woodbine Food Hall.",
      "eventDate"   -> "2026-11-10",
      "projectId"   -> 3
    )

    "give organizers and super-users the sets, and no one else" in {
      val sets = controller.questionSets(as(9))
      status(sets) mustBe OK
      (contentAsJson(sets) \ "sets").as[JsArray].value.size mustBe 5
      status(controller.questionSets(as(7, MobileScopes.Admin))) mustBe OK
      contentAsJson(controller.questionSets(as(8, MobileScopes.Admin))) mustBe
        Json.obj("error" -> "mobile_admin_only")
      contentAsJson(controller.questionSets(FakeRequest())) mustBe Json.obj(
        "error" -> "mobile_admin_only"
      )
    }

    "check the area and the set" in {
      val bad = controller.counts(as(9, uri = "/?lat=40&lon=-111&radius=5000"))
      status(bad) mustBe BAD_REQUEST
      (contentAsJson(bad) \ "detail").as[List[String]] mustBe List(
        "radius must be 200 to 3000 metres"
      )
      val unknown = controller.features("nope")(as(9, uri = "/?lat=40&lon=-111&radius=500"))
      status(unknown) mustBe NOT_FOUND
    }

    "refuse a create for another project, an old set version or a bad body" in {
      contentAsJson(controller.create(as(9).withBody(body + ("projectId" -> JsNumber(4))))) mustBe
        Json.obj("error" -> "organizer_forbidden")
      val changed = controller.create(as(9).withBody(body + ("version" -> JsNumber(2))))
      status(changed) mustBe CONFLICT
      contentAsJson(changed) mustBe Json.obj("error" -> "set_changed")
      val invalid = controller.create(
        as(9).withBody(
          body + ("name" -> JsString(" ")) + ("eventDate" -> JsString("2026-13-01")) + ("x" -> JsNumber(
            1
          ))
        )
      )
      status(invalid) mustBe BAD_REQUEST
      (contentAsJson(invalid) \ "detail").as[List[String]] mustBe List(
        "unknown field x",
        "name: 1 to 120 characters",
        "eventDate must be YYYY-MM-DD"
      )
      contentAsJson(controller.create(FakeRequest().withBody(body))) mustBe
        Json.obj("error" -> "mobile_admin_only")
    }

    "refuse to publish another project's campaign" in {
      contentAsJson(controller.publish(71)(as(9))) mustBe Json.obj("error" -> "organizer_forbidden")
    }
  }
}

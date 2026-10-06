package org.maproulette.provider.choice

import anorm._
import java.time.Instant
import java.util.UUID
import org.maproulette.Config
import org.maproulette.auth.mobile._
import org.maproulette.data.ActionManager
import org.maproulette.framework.model._
import org.maproulette.framework.service.TaskClusterService
import org.maproulette.framework.util.FrameworkHelper
import org.maproulette.provider.ChallengeProvider
import org.maproulette.provider.websockets.WebSocketProvider
import org.maproulette.session.{
  SearchChallengeParameters,
  SearchLocation,
  SearchParameters,
  SearchTaskParameters
}
import org.scalatest.Tag
import play.api.{Application, Configuration}
import play.api.db.Database
import play.api.libs.json._
import play.api.libs.ws.WSClient
import scala.collection.mutable
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext}

object ChoiceTag extends Tag("choice")

/** A store that only holds OSM tokens; the choice flow never calls the OAuth methods. */
class OsmTokenOnlyStore extends MobileOAuthStore {
  val tokens                                             = mutable.Map[String, StoredOsmToken]()
  val deleted                                            = mutable.ListBuffer[String]()
  def osmToken(familyId: String): Option[StoredOsmToken] = synchronized(tokens.get(familyId))
  def deleteOsmToken(familyId: String): Unit = synchronized {
    deleted += familyId; tokens.remove(familyId); ()
  }
  def attachOsmToken(a: String, b: String, c: Long, d: SealedOsmToken, e: String, f: Instant) = ???
  def createInteraction(interaction: MobileInteraction): Unit                                 = ???
  def getInteraction(a: String, b: String, now: Instant)                                      = ???
  def claimLogin(a: String, b: String, now: Instant)                                          = ???
  def completeLogin(a: String, b: String, c: Long, d: String, now: Instant)                   = ???
  def approveInteraction(
      a: String,
      b: String,
      c: String,
      d: String,
      e: String,
      f: Instant,
      g: Instant
  ) =
    ???
  def declineInteraction(a: String, b: String, c: String, now: Instant)                    = ???
  def findCode(codeHash: String, now: Instant)                                             = ???
  def findRefresh(refreshHash: String, now: Instant)                                       = ???
  def redeemCode(a: String, b: String, c: String, d: String, e: TokenHashes, now: Instant) = ???
  def rotate(a: String, b: String, pair: TokenHashes, now: Instant)                        = ???
  def authenticate(accessHash: String, now: Instant)                                       = ???
  def revoke(tokenHash: String, clientId: String, now: Instant)                            = ???
}

/**
  * Choice check and submit against a real database and a loopback fake OSM API. Runs in
  * FrameworkMasterSuite (tag "choice").
  */
class MobileChoiceServiceSpec(implicit val application: Application) extends FrameworkHelper {
  override implicit val projectTestName: String = "MobileChoiceServiceSpecProject"
  private implicit val ec: ExecutionContext     = application.injector.instanceOf[ExecutionContext]
  private val db                                = application.injector.instanceOf[Database]
  private val osm                               = new FakeOsmServer
  private val store                             = new OsmTokenOnlyStore
  private val keyConfig = Configuration(
    "mobileOAuth.osmTokenKey" -> java.util.Base64.getEncoder.encodeToString(Array.fill[Byte](32)(3))
  )
  private val cipher = new MobileOsmTokenCipher(new MobileOAuthSettings(keyConfig))
  private val client = new ChoiceOsmClient(
    application.injector.instanceOf[WSClient],
    application.injector.instanceOf[Config]
  ) { override protected def baseUrl: String = osm.url }

  /** Fails the next status write, as a database error after the upload would. */
  class FlakyChoiceService
      extends MobileChoiceService(
        db,
        taskDAL,
        challengeDAL,
        client,
        store,
        cipher,
        application.injector.instanceOf[WebSocketProvider],
        application.injector.instanceOf[ActionManager]
      ) {
    @volatile var failStatus = false
    override protected def writeStatus(
        taskId: Long,
        user: User,
        status: Int,
        cs: Option[Long],
        key: String,
        result: JsObject
    ): Unit = {
      if (failStatus) {
        failStatus = false; throw new RuntimeException("simulated DB failure")
      }
      super.writeStatus(taskId, user, status, cs, key, result)
    }
  }
  private val service = new FlakyChoiceService

  private val tagFix               = Set(MobileScopes.Read, MobileScopes.Write, MobileScopes.TagFix)
  private val write                = Set(MobileScopes.Read, MobileScopes.Write)
  private var mapper: User         = _
  private var other: User          = _
  private var challenge: Challenge = _
  private var nextNode             = 9000L

  private def family(user: User) = s"family-${user.id}"
  private def grantToken(user: User, scope: String = "read_prefs write_api"): Unit =
    store.synchronized {
      store.tokens(family(user)) = StoredOsmToken(
        family(user),
        user.id,
        cipher.seal(user.id, s"osm-token-${user.id}").get,
        scope
      )
    }

  private def benchTask(
      payload: JsObject => JsObject = identity,
      tags: Map[String, String] = Map("amenity" -> "bench"),
      target: Challenge = challenge
  ): (Task, Long) = {
    nextNode += 1
    val node = nextNode
    osm.put("node", node, tags)
    val task = taskDAL.insert(
      Task(
        -1,
        s"node/$node",
        null,
        null,
        target.id,
        geometries = Json.obj(
          "features" -> Json.arr(
            Json.obj(
              "type" -> "Feature",
              "geometry" -> Json
                .obj("type" -> "Point", "coordinates" -> Json.arr(-111.891, 40.7608)),
              "properties" -> Json.obj("@id" -> s"node/$node")
            )
          )
        ),
        cooperativeWork = Some(payload(ChoiceFixtures.withNode(node))),
        status = Some(Task.STATUS_CREATED)
      ),
      User.superUser
    )
    (task, node)
  }

  /** Users hold one lock at a time; earlier cases may have left one behind. */
  private def lock(task: Task, user: User = mapper): Unit = {
    db.withConnection { implicit c =>
      SQL"DELETE FROM locked WHERE user_id = ${user.id} AND item_id <> ${task.id}".executeUpdate()
    }
    taskDAL.lockItem(user, taskDAL.retrieveById(task.id).get)
    ()
  }
  private def await[A](future: scala.concurrent.Future[A]): A = Await.result(future, 30.seconds)
  private def submit(task: Task, body: String, user: User = mapper, scopes: Set[String] = tagFix) =
    await(service.submit(task.id, user, scopes, family(user), body))
  private def check(task: Task) = await(service.check(task.id))
  private def fresh(task: Task): (Int, Option[Long]) = db.withConnection { implicit c =>
    SQL"SELECT status, changeset_id FROM tasks WHERE id = ${task.id}"
      .as((SqlParser.int("status") ~ SqlParser.get[Option[Long]]("changeset_id")).map {
        case s ~ cs => (s, cs.filter(_ > 0))
      }.single)
  }
  private def lockedBy(task: Task): Option[Long] = db.withConnection { implicit c =>
    SQL"SELECT user_id FROM locked WHERE item_id = ${task.id}"
      .as(SqlParser.long("user_id").singleOpt)
  }
  private def stale(task: Task): Option[String] = db.withConnection { implicit c =>
    SQL"SELECT reason FROM choice_stale WHERE task_id = ${task.id}"
      .as(SqlParser.str("reason").singleOpt)
  }
  private def rows(task: Task): List[String] = db.withConnection { implicit c =>
    SQL"SELECT state FROM mobile_choice_submissions WHERE task_id = ${task.id}"
      .as(SqlParser.str("state").*)
  }
  private def lastChangeset = osm.synchronized(osm.changesets.last)

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    mapper =
      this.serviceManager.user.create(this.getTestUser(7700001, "ChoiceMapper"), User.superUser)
    other =
      this.serviceManager.user.create(this.getTestUser(7700002, "ChoiceOther"), User.superUser)
    serviceManager.project.update(defaultProject.id, Json.obj("enabled" -> true), User.superUser)
    challenge = challengeDAL.insert(
      Challenge(
        -1,
        "choiceChallenge",
        null,
        null,
        general = ChallengeGeneral(
          User.superUser.osmProfile.id,
          defaultProject.id,
          "Add bench details",
          enabled = true,
          checkinComment = "Add bench details #maproulette",
          checkinSource = "survey"
        ),
        creation = ChallengeCreation(),
        priority = ChallengePriority(),
        extra = ChallengeExtra()
      ),
      User.superUser
    )
    grantToken(mapper)
    grantToken(other)
  }
  override protected def afterAll(): Unit = {
    osm.stop()
    super.afterAll()
  }

  private def reason(response: ChoiceResponse): Option[String] =
    (response.body \ "reason").asOpt[String]
  private def otherService(withKey: Boolean, base: String = osm.url) = new MobileChoiceService(
    db,
    taskDAL,
    challengeDAL,
    new ChoiceOsmClient(
      application.injector.instanceOf[WSClient],
      application.injector.instanceOf[Config]
    ) { override protected def baseUrl: String = base },
    store,
    if (withKey) cipher else new MobileOsmTokenCipher(new MobileOAuthSettings(Configuration())),
    application.injector.instanceOf[WebSocketProvider],
    application.injector.instanceOf[ActionManager]
  )
  private val changes: Seq[(String, Long => Unit)] = Seq(
    "element_gone" -> ((node: Long) => osm.elements.remove(("node", node))),
    "match_failed" -> ((node: Long) => osm.edit("node", node)(_ + ("amenity" -> "picnic_table"))),
    // An unanswered question's key: eligibility is all-or-nothing.
    "key_changed" -> ((node: Long) => osm.edit("node", node)(_ + ("material" -> "wood")))
  )

  "Choice check" should {
    "report an unchanged element as eligible and allow a free node's delete" taggedAs ChoiceTag in {
      val (task, _) = benchTask()
      check(task) mustBe ChoiceResponse(
        200,
        Json.obj("eligible" -> true, "deleteAllowed" -> true, "elementVersion" -> 3)
      )
      stale(task) mustBe None
      osm.writeAuth mustBe empty
    }

    "record each kind of change as stale, with detail, without writing a status" taggedAs ChoiceTag in {
      changes.foreach {
        case (expected, change) =>
          val (task, node) = benchTask()
          change(node)
          val response = check(task)
          response.status mustBe 200
          (response.body \ "eligible").as[Boolean] mustBe false
          reason(response) mustBe Some(expected)
          stale(task) mustBe Some(expected)
          fresh(task) mustBe ((Task.STATUS_CREATED, None))
          if (expected == "key_changed")
            (response.body \ "detail").as[JsArray] mustBe Json.arr(
              Json.obj(
                "question" -> "material",
                "key"      -> "material",
                "expected" -> JsNull,
                "current"  -> "wood"
              )
            )
          if (expected == "match_failed")
            (response.body \ "detail").as[JsArray] mustBe Json.arr(
              Json.obj("key" -> "amenity", "expected" -> "bench", "current" -> "picnic_table")
            )
      }
    }

    "answer a recorded stale task from the table without reading OSM" taggedAs ChoiceTag in {
      val (task, node) = benchTask()
      osm.edit("node", node)(_ + ("backrest" -> "yes"))
      val first = await(otherService(withKey = true).check(task.id))
      reason(first) mustBe Some("key_changed")
      // OSM unreachable now; the row still answers, and it is insert-only.
      osm.edit("node", node)(_ - "backrest")
      val offline = otherService(withKey = true, base = "http://127.0.0.1:9")
      val again   = await(offline.check(task.id))
      again mustBe first
    }

    "report a node used by a way as not deletable" taggedAs ChoiceTag in {
      val (task, node) = benchTask()
      osm.inWays += node
      (check(task).body \ "deleteAllowed").as[Boolean] mustBe false
    }

    "answer 502 osm_unavailable and record nothing when OSM fails or answers an HTML 404" taggedAs ChoiceTag in {
      val (task, _) = benchTask()
      await(otherService(withKey = true, base = "http://127.0.0.1:9").check(task.id)) mustBe
        ChoiceResponse(502, Json.obj("error" -> "osm_unavailable"))
      stale(task) mustBe None
      val (proxied, node) = benchTask()
      osm.htmlMissing += node
      check(proxied) mustBe ChoiceResponse(502, Json.obj("error" -> "osm_unavailable"))
      stale(proxied) mustBe None
    }
  }

  "Choice submit" should {
    "apply several answers in one changeset, then mark the task fixed with its changeset" taggedAs ChoiceTag in {
      val (task, node) = benchTask()
      lock(task)
      val before   = osm.uploadCount
      val body     = """{"answers": {"capacity": "c3", "backrest": "yes"}}"""
      val response = submit(task, body)
      response.status mustBe 200
      val (cs, changeset) = lastChangeset
      response.body mustBe Json.obj(
        "status"      -> 1,
        "changesetId" -> cs,
        "applied" -> Json.obj(
          "set"     -> Json.obj("backrest" -> "yes", "capacity" -> "3"),
          "unset"   -> Json.arr(),
          "deleted" -> false
        )
      )
      changeset.tags mustBe Map(
        "created_by" -> "MapRoulette",
        "comment"    -> "Add bench details #maproulette",
        "source"     -> "survey"
      )
      changeset.open mustBe false
      changeset.changes mustBe 1
      osm.uploadCount mustBe before + 1
      osm.elements(("node", node)).tags mustBe
        Map("amenity" -> "bench", "backrest" -> "yes", "capacity" -> "3")
      osm.writeAuth.last mustBe s"Bearer osm-token-${mapper.id}"
      fresh(task) mustBe ((Task.STATUS_FIXED, Some(cs)))
      lockedBy(task) mustBe None
      rows(task) mustBe List("done")
      taskDAL.retrieveById(task.id).get.changesetId mustBe Some(cs)
      // Resent after completion (no lock any more): the stored result, nothing touches OSM.
      submit(task, body) mustBe response
      osm.uploadCount mustBe before + 1
    }

    "apply a partial answer and keep tags outside the payload's guards" taggedAs ChoiceTag in {
      val (task, node) = benchTask()
      osm.edit("node", node)(_ + ("name" -> "Memorial bench"))
      lock(task)
      submit(task, """{"answers": {"backrest": "no"}}""").status mustBe 200
      osm.elements(("node", node)).tags mustBe
        Map("amenity" -> "bench", "name" -> "Memorial bench", "backrest" -> "no")
    }

    "refuse any change with 409 task_ineligible: no upload, no status, lock released, task hidden" taggedAs ChoiceTag in {
      changes.foreach {
        case (expected, change) =>
          val (task, node) = benchTask()
          lock(task)
          change(node)
          val changesets = osm.changesets.size
          val response   = submit(task, """{"answers": {"backrest": "no"}}""")
          response.status mustBe 409
          (response.body \ "error").as[String] mustBe "task_ineligible"
          reason(response) mustBe Some(expected)
          (response.body \ "detail").asOpt[JsArray].isDefined mustBe true
          osm.changesets.size mustBe changesets
          fresh(task) mustBe ((Task.STATUS_CREATED, None))
          lockedBy(task) mustBe None
          stale(task) mustBe Some(expected)
          rows(task) mustBe empty
      }
    }

    "re-check once after an OSM 409: stale → task_ineligible, other edit → retried upload" taggedAs ChoiceTag in {
      val (staleTask, staleNode) = benchTask()
      lock(staleTask)
      osm.beforeUpload = Some(() => osm.edit("node", staleNode)(_ + ("backrest" -> "yes")))
      reason(submit(staleTask, """{"answers": {"backrest": "no"}}""")) mustBe Some("key_changed")
      lastChangeset._2.open mustBe false
      lastChangeset._2.changes mustBe 0
      fresh(staleTask) mustBe ((Task.STATUS_CREATED, None))
      rows(staleTask) mustBe empty

      val (task, node) = benchTask()
      lock(task)
      osm.beforeUpload = Some(() => osm.edit("node", node)(_ + ("name" -> "Memorial bench")))
      submit(task, """{"answers": {"backrest": "no"}}""").status mustBe 200
      osm.elements(("node", node)).tags mustBe
        Map("amenity" -> "bench", "name" -> "Memorial bench", "backrest" -> "no")
      lastChangeset._2.open mustBe false
    }

    "answer 401 osm_reauth_required and drop the token when OSM rejects it, closing the changeset" taggedAs ChoiceTag in {
      val (task, _) = benchTask()
      lock(task)
      osm.uploadPlan.enqueue("401")
      submit(task, """{"answers": {"backrest": "no"}}""") mustBe
        ChoiceResponse(401, Json.obj("error" -> "osm_reauth_required"))
      lastChangeset._2.open mustBe false
      store.osmToken(family(mapper)) mustBe None
      fresh(task) mustBe ((Task.STATUS_CREATED, None))
      rows(task) mustBe empty
      submit(task, """{"answers": {"backrest": "no"}}""").status mustBe 401
      // A token whose OSM grant lacks write_api, or that no longer decrypts, is dropped.
      grantToken(mapper, "read_prefs")
      submit(task, """{"answers": {"backrest": "no"}}""").status mustBe 401
      store.osmToken(family(mapper)) mustBe None
      store.synchronized {
        store.tokens(family(mapper)) = StoredOsmToken(
          family(mapper),
          mapper.id,
          cipher.seal(other.id, "sealed-for-someone-else").get,
          "read_prefs write_api"
        )
      }
      submit(task, """{"answers": {"backrest": "no"}}""").status mustBe 401
      store.osmToken(family(mapper)) mustBe None
      grantToken(mapper)
    }

    "answer 503 osm_edits_unavailable without a token key, keeping the stored token" taggedAs ChoiceTag in {
      val (task, _) = benchTask()
      lock(task)
      val keyless = otherService(withKey = false)
      await(
        keyless
          .submit(task.id, mapper, tagFix, family(mapper), """{"answers": {"backrest": "no"}}""")
      ) mustBe
        ChoiceResponse(503, Json.obj("error" -> "osm_edits_unavailable"))
      store.osmToken(family(mapper)).isDefined mustBe true
      // Non-editing outcomes still work.
      await(keyless.submit(task.id, mapper, tagFix, family(mapper), """{"outcome": "too-hard"}""")).status mustBe 200
    }

    "close the changeset on OSM 5xx and, when the upload had landed, finish on retry without uploading again" taggedAs ChoiceTag in {
      val (task, node) = benchTask()
      lock(task)
      osm.uploadPlan.enqueue("500-applied")
      val body = """{"answers": {"backrest": "yes"}}"""
      submit(task, body) mustBe ChoiceResponse(502, Json.obj("error" -> "osm_unavailable"))
      val (cs, changeset) = lastChangeset
      changeset.open mustBe false
      rows(task) mustBe List("started")
      val uploads = osm.uploadCount
      val retried = submit(task, body)
      retried.status mustBe 200
      (retried.body \ "changesetId").as[Long] mustBe cs
      osm.uploadCount mustBe uploads
      osm.elements(("node", node)).tags("backrest") mustBe "yes"
      fresh(task) mustBe ((Task.STATUS_FIXED, Some(cs)))
    }

    "retry a 5xx that did not land in a new changeset, and keep other submissions out meanwhile" taggedAs ChoiceTag in {
      val (task, _) = benchTask()
      lock(task)
      osm.uploadPlan.enqueue("500")
      val body = """{"answers": {"material": "metal"}}"""
      submit(task, body).status mustBe 502
      val first = lastChangeset._1
      submit(task, """{"outcome": "too-hard"}""") mustBe
        ChoiceResponse(409, Json.obj("error" -> "submission_pending"))
      val retried = submit(task, body)
      retried.status mustBe 200
      (retried.body \ "changesetId").as[Long] must not be first
      osm.changesets(first).open mustBe false
    }

    "not resume a row another request is still working on (lease)" taggedAs ChoiceTag in {
      val (task, _) = benchTask()
      lock(task)
      osm.uploadPlan.enqueue("500")
      val body = """{"answers": {"capacity": "c2"}}"""
      submit(task, body).status mustBe 502
      // Simulate the first request still in flight.
      db.withConnection { implicit c =>
        SQL"""UPDATE mobile_choice_submissions SET lease_until = NOW() + interval '1 minute'
              WHERE task_id = ${task.id}""".executeUpdate()
      }
      val uploads = osm.uploadCount
      submit(task, body) mustBe ChoiceResponse(409, Json.obj("error" -> "submission_pending"))
      osm.uploadCount mustBe uploads
      osm.changesets.size mustBe osm.changesets.size
      // Once the lease has run out the retry resumes.
      db.withConnection { implicit c =>
        SQL"""UPDATE mobile_choice_submissions SET lease_until = NOW() - interval '1 second'
              WHERE task_id = ${task.id}""".executeUpdate()
      }
      submit(task, body).status mustBe 200
    }

    "after a failed status write answer status_pending, then finish on retry without a second upload" taggedAs ChoiceTag in {
      val (task, _) = benchTask()
      lock(task)
      service.failStatus = true
      val body     = """{"answers": {"backrest": "no", "material": "wood"}}"""
      val response = submit(task, body)
      val cs       = lastChangeset._1
      response mustBe ChoiceResponse(
        500,
        Json.obj("error" -> "status_pending", "changesetId" -> cs)
      )
      fresh(task) mustBe ((Task.STATUS_CREATED, None))
      rows(task) mustBe List("uploaded")
      val uploads = osm.uploadCount
      val retried = submit(task, body)
      retried.status mustBe 200
      (retried.body \ "changesetId").as[Long] mustBe cs
      osm.uploadCount mustBe uploads
      fresh(task) mustBe ((Task.STATUS_FIXED, Some(cs)))
      rows(task) mustBe List("done")
    }

    "delete a free node only when asked, refuse one used by a way, and otherwise resolve as Not an issue" taggedAs ChoiceTag in {
      val (task, node) = benchTask()
      lock(task)
      val response = submit(task, """{"outcome": "gone", "delete": true}""")
      response.status mustBe 200
      (response.body \ "applied" \ "deleted").as[Boolean] mustBe true
      (response.body \ "status").as[Int] mustBe 1
      osm.elements(("node", node)).visible mustBe false
      val upload = scala.xml.XML.loadString(osm.uploads.last._2)
      ((upload \ "delete" \ "node") \@ "version") mustBe "3"
      ((upload \ "delete" \ "node") \@ "lat") mustBe "40.7608"
      (upload \ "delete" \ "node" \ "tag") mustBe empty

      val (used, usedNode) = benchTask()
      osm.inWays += usedNode
      lock(used)
      val changesets = osm.changesets.size
      submit(used, """{"outcome": "gone", "delete": true}""") mustBe
        ChoiceResponse(409, Json.obj("error" -> "element_in_use"))
      osm.changesets.size mustBe changesets
      stale(used) mustBe None
      val plain = submit(used, """{"outcome": "gone"}""")
      plain.body mustBe Json.obj(
        "status"      -> 2,
        "changesetId" -> JsNull,
        "applied"     -> Json.obj("set" -> Json.obj(), "unset" -> Json.arr(), "deleted" -> false)
      )
      osm.changesets.size mustBe changesets
      fresh(used) mustBe ((Task.STATUS_FALSE_POSITIVE, None))

      val (changed, changedNode) = benchTask()
      osm.edit("node", changedNode)(_ - "amenity")
      lock(changed)
      reason(submit(changed, """{"outcome": "gone", "delete": true}""")) mustBe Some("match_failed")
    }

    "refuse delete where the payload declares no delete outcome" taggedAs ChoiceTag in {
      val (task, _) = benchTask(p =>
        p ++ Json.obj(
          "outcomes" -> Json.arr(Json.obj("id" -> "not-a-bench", "label" -> "No", "status" -> 2))
        )
      )
      lock(task)
      submit(task, """{"outcome": "gone", "delete": true}""").body mustBe
        Json.obj("error" -> "invalid_submission", "detail" -> "unknown outcome 'gone'")
      submit(task, """{"outcome": "not-a-bench", "delete": true}""").status mustBe 422
      submit(task, """{"outcome": "too-hard", "delete": false}""").status mustBe 422
    }

    "require the caller's lock and a valid transition" taggedAs ChoiceTag in {
      val (task, _) = benchTask()
      submit(task, """{"answers": {"backrest": "no"}}""") mustBe
        ChoiceResponse(409, Json.obj("error" -> "lock_required"))
      lock(task, other)
      submit(task, """{"outcome": "too-hard"}""") mustBe
        ChoiceResponse(409, Json.obj("error" -> "lock_required"))
      val (done, _) = benchTask()
      lock(done)
      submit(done, """{"outcome": "not-a-bench"}""").status mustBe 200
      // Not an issue -> Too hard is only allowed to the mapper who completed the task.
      lock(done, other)
      submit(done, """{"outcome": "too-hard"}""", user = other) mustBe
        ChoiceResponse(409, Json.obj("error" -> "invalid_transition"))
    }

    "need osm:tagfix only for submissions that edit OSM" taggedAs ChoiceTag in {
      val (task, _) = benchTask()
      lock(task)
      val changesets = osm.changesets.size
      Seq("""{"answers": {"backrest": "no"}}""", """{"outcome": "gone", "delete": true}""")
        .foreach { body =>
          submit(task, body, scopes = write) mustBe
            ChoiceResponse(403, Json.obj("error" -> "insufficient_scope", "scope" -> "osm:tagfix"))
        }
      osm.changesets.size mustBe changesets
      val response = submit(task, """{"outcome": "too-hard"}""", scopes = write)
      response.body mustBe Json.obj(
        "status"      -> 6,
        "changesetId" -> JsNull,
        "applied"     -> Json.obj("set" -> Json.obj(), "unset" -> Json.arr(), "deleted" -> false)
      )
      fresh(task) mustBe ((Task.STATUS_TOO_HARD, None))
      lockedBy(task) mustBe None
    }

    "parse the body strictly" taggedAs ChoiceTag in {
      val (task, _) = benchTask()
      lock(task)
      Seq(
        "",
        "[]",
        "{}",
        """{"answers": {}}""",
        """{"answers": {"backrest": "no"}, "outcome": "too-hard"}""",
        """{"answers": {"backrest": "no"}, "comment": "x"}""",
        """{"answers": {"backrest": "no", "backrest": "yes"}}""",
        """{"answers": {"backrest": "no"}} {}""",
        """{"answers": {"Backrest": "no"}}""",
        """{"answers": {"backrest": 1}}""",
        """{"outcome": "too-hard", "delete": "yes"}""",
        """{"outcome": ["too-hard"]}""",
        Json.stringify(Json.obj("answers" -> JsObject((1 to 9).map(i => s"q$i" -> JsString("a")))))
      ).foreach(body =>
        submit(task, body) mustBe ChoiceResponse(400, Json.obj("error" -> "invalid_request"))
      )
      submit(task, """{"answers": {"backrest": "maybe"}}""") mustBe ChoiceResponse(
        422,
        Json
          .obj("error" -> "invalid_submission", "detail" -> "unknown option 'maybe' for 'backrest'")
      )
      submit(task, """{"answers": {"colour": "red"}}""").status mustBe 422
      fresh(task) mustBe ((Task.STATUS_CREATED, None))
    }

    "reject tasks that are not valid choice tasks" taggedAs ChoiceTag in {
      submit(defaultTask, """{"outcome": "too-hard"}""") mustBe
        ChoiceResponse(422, Json.obj("error" -> "unsupported_task"))
      await(service.check(defaultTask.id)) mustBe
        ChoiceResponse(422, Json.obj("error"                                       -> "unsupported_task"))
      await(service.check(987654321L)) mustBe ChoiceResponse(404, Json.obj("error" -> "not_found"))
    }
  }

  "Choice ingest" should {
    val provider = application.injector.instanceOf[ChallengeProvider]
    def line(node: Long, payload: JsObject) =
      Json.stringify(
        Json.obj(
          "type" -> "FeatureCollection",
          "features" -> Json.arr(
            Json.obj(
              "type"       -> "Feature",
              "geometry"   -> Json.obj("type" -> "Point", "coordinates" -> Json.arr(-111.9, 40.7)),
              "properties" -> Json.obj("@id" -> s"node/$node", "amenity" -> "bench")
            )
          ),
          "cooperativeWork" -> payload
        )
      )
    def newChallenge(name: String) =
      challengeDAL.insert(getTestChallenge(name, defaultProject.id), User.superUser)

    "validate type 3 payloads and report every broken rule per line without failing the challenge" taggedAs ChoiceTag in {
      val target = newChallenge(s"ingest-${UUID.randomUUID()}")
      val ok = provider.createTaskFromJson(
        User.superUser,
        target,
        line(501, ChoiceFixtures.withNode(501)),
        Some(mutable.ListBuffer())
      )
      ok.isDefined mustBe true
      val errors = mutable.ListBuffer[String]()
      val bad = ChoiceFixtures.withNode(502) ++ Json.obj(
        "meta"  -> Json.obj("version" -> 2, "type" -> 3, "choiceVersion" -> 2),
        "extra" -> 1
      )
      provider.createTaskFromJson(User.superUser, target, line(502, bad), Some(errors)) mustBe None
      errors.toList mustBe List(
        "cooperativeWork: unknown field 'extra'",
        "meta.choiceVersion: must be 1"
      )
      challengeDAL.retrieveById(target.id).get.status must not be Some(Challenge.STATUS_FAILED)
      challengeDAL.getTaskCount(target.id) mustBe 1
      challengeDAL.retrieveById(target.id).get.general.cooperativeType mustBe 3
      // Re-uploading the same @id updates the task instead of adding one.
      provider
        .createTaskFromJson(
          User.superUser,
          target,
          line(501, ChoiceFixtures.withNode(501)),
          Some(mutable.ListBuffer())
        )
        .isDefined mustBe true
      challengeDAL.getTaskCount(target.id) mustBe 1
    }

    "refuse mixed challenges in both directions" taggedAs ChoiceTag in {
      val choice = newChallenge(s"mixed-choice-${UUID.randomUUID()}")
      provider
        .createTaskFromJson(
          User.superUser,
          choice,
          line(601, ChoiceFixtures.withNode(601)),
          Some(mutable.ListBuffer())
        )
        .isDefined mustBe true
      val plain = Json.stringify(
        Json.parse(line(602, ChoiceFixtures.withNode(602))).as[JsObject] - "cooperativeWork"
      )
      val errors = mutable.ListBuffer[String]()
      provider.createTaskFromJson(User.superUser, choice, plain, Some(errors)) mustBe None
      errors.toList mustBe List(
        "Only choice tasks can be added to a challenge that has choice tasks"
      )

      val stock   = defaultChallenge // holds plain tasks
      val errors2 = mutable.ListBuffer[String]()
      provider.createTaskFromJson(
        User.superUser,
        stock,
        line(603, ChoiceFixtures.withNode(603)),
        Some(errors2)
      ) mustBe None
      errors2.toList mustBe List(
        "Choice tasks cannot be added to a challenge that has other kinds of tasks"
      )
    }
  }

  "Choice HTTP routes" should {
    import play.api.test.FakeRequest
    import play.api.test.Helpers._
    val crypto = application.injector.instanceOf[org.maproulette.utils.Crypto]
    def apiKey(user: User): String = {
      val raw = UUID.randomUUID().toString
      db.withConnection { implicit c =>
        SQL"UPDATE users SET api_key = ${crypto.encrypt(raw)} WHERE id = ${user.id}".executeUpdate()
      }
      serviceManager.user.cacheManager.cache.remove(user.id)
      s"${user.id}|$raw"
    }

    "answer API keys and web sessions with 403 mobile_only" taggedAs ChoiceTag in {
      val (task, _) = benchTask()
      val key       = apiKey(defaultUser)
      val submitted = route(
        application,
        FakeRequest(POST, s"/api/v2/task/${task.id}/choice")
          .withHeaders("apiKey" -> key)
          .withJsonBody(Json.obj("outcome" -> "too-hard"))
      ).get
      status(submitted) mustBe FORBIDDEN
      contentAsJson(submitted) mustBe Json.obj("error" -> "mobile_only")
      val checked =
        route(application, FakeRequest(GET, s"/api/v2/task/${task.id}/choice/check")).get
      status(checked) mustBe FORBIDDEN
      contentAsJson(checked) mustBe Json.obj("error" -> "mobile_only")
      fresh(task) mustBe ((Task.STATUS_CREATED, None))
    }

    "reject a non-integer cct with 400 and accept a list" taggedAs ChoiceTag in {
      val bad = route(application, FakeRequest(GET, "/api/v2/tasks/box/-180/-85/180/85?cct=x")).get
      status(bad) mustBe BAD_REQUEST
      val good =
        route(
          application,
          FakeRequest(GET, s"/api/v2/tasks/box/-180/-85/180/85?cct=3&cid=${challenge.id}")
        ).get
      status(good) mustBe OK
    }

    "keep addFileTasks at 204 by default and report per line with report=true" taggedAs ChoiceTag in {
      import play.api.libs.Files.SingletonTemporaryFileCreator
      import play.api.mvc.MultipartFormData
      val controller =
        application.injector.instanceOf[org.maproulette.controllers.api.ChallengeController]
      val target = challengeDAL.insert(
        getTestChallenge(s"report-${UUID.randomUUID()}", defaultProject.id),
        User.superUser
      )
      def upload(lines: Seq[String], report: Boolean, lineByLine: Boolean = true) = {
        val file = SingletonTemporaryFileCreator.create("choice", ".geojson")
        java.nio.file.Files.write(file.path, lines.mkString("\n").getBytes("UTF-8"))
        val body = MultipartFormData(
          Map.empty[String, Seq[String]],
          Seq(MultipartFormData.FilePart("json", "tasks.geojson", Some("application/json"), file)),
          Nil
        )
        controller
          .addTasksToChallengeFromFile(target.id, lineByLine, false, None, true, report)
          .apply(FakeRequest(PUT, "/").withHeaders("apiKey" -> apiKey(defaultUser)).withBody(body))
      }
      def featureLine(node: Long, work: Option[JsObject]) =
        Json.stringify(
          Json.obj(
            "type" -> "FeatureCollection",
            "features" -> Json.arr(
              Json.obj(
                "type"       -> "Feature",
                "geometry"   -> Json.obj("type" -> "Point", "coordinates" -> Json.arr(-111.9, 40.7)),
                "properties" -> Json.obj("@id" -> s"node/$node")
              )
            )
          ) ++ work.map(w => Json.obj("cooperativeWork" -> w)).getOrElse(Json.obj())
        )
      val invalid = ChoiceFixtures.withNode(702) ++ Json.obj("questions" -> Json.arr())
      status(upload(Seq(featureLine(701, Some(ChoiceFixtures.withNode(701)))), report = false)) mustBe NO_CONTENT
      val reported = upload(
        Seq(
          featureLine(701, Some(ChoiceFixtures.withNode(701))),
          featureLine(702, Some(invalid)),
          "",
          featureLine(703, None),
          featureLine(704, Some(ChoiceFixtures.withNode(704)))
        ),
        report = true
      )
      status(reported) mustBe OK
      contentAsJson(reported) mustBe Json.obj(
        "created" -> 1,
        "updated" -> 1,
        "rejected" -> Json.arr(
          Json.obj("line" -> 2, "errors" -> Json.arr("questions: must have 1 to 8 entries")),
          Json.obj(
            "line" -> 4,
            "errors" -> Json.arr(
              "Only choice tasks can be added to a challenge that has choice tasks"
            )
          )
        )
      )
      // A report needs line-by-line input.
      status(
        upload(
          Seq(featureLine(705, Some(ChoiceFixtures.withNode(705)))),
          report = true,
          lineByLine = false
        )
      ) mustBe BAD_REQUEST
      challengeDAL.getTaskCount(target.id) mustBe 2
      challengeDAL.retrieveById(target.id).get.status must not be Some(Challenge.STATUS_FAILED)
    }
  }

  "Choice discovery" should {
    val clusters = application.injector.instanceOf[TaskClusterService]
    def found(types: Option[List[Int]], excludeStale: Boolean = false): Set[Long] =
      clusters
        .getTasksInBoundingBox(
          User.superUser,
          SearchParameters(
            location = Some(SearchLocation(-180, -85, 180, 85)),
            challengeParams = SearchChallengeParameters(
              challengeIds = Some(List(challenge.id, defaultChallenge.id)),
              challengeCooperativeTypes = types
            ),
            taskParams = SearchTaskParameters(excludeStale = Some(excludeStale))
          ),
          paging = org.maproulette.framework.psql.Paging(5000, 0),
          ignoreLocked = true
        )
        ._2
        .map(_.id)
        .toSet

    "filter by cooperative type, and leave stale tasks out with excludeStale until the payload changes" taggedAs ChoiceTag in {
      challengeDAL.update(Json.obj("enabled" -> true), User.superUser)(defaultChallenge.id)
      val (task, node) = benchTask()
      found(Some(List(3))) must contain(task.id)
      found(Some(List(3))) must not contain defaultTask.id
      found(Some(List(0))) must contain(defaultTask.id)
      found(Some(List(0))) must not contain task.id
      found(None) must contain allOf (task.id, defaultTask.id)

      osm.edit("node", node)(_ + ("backrest" -> "yes"))
      reason(check(task)) mustBe Some("key_changed")
      found(Some(List(3)), excludeStale = true) must not contain task.id
      // excludeStale is separate from cct, and off by default.
      found(Some(List(3))) must contain(task.id)
      found(None, excludeStale = true) must contain(defaultTask.id)

      def reupload(payload: JsObject): Unit = {
        taskDAL.mergeUpdate(
          taskDAL.retrieveById(task.id).get.copy(cooperativeWork = Some(payload)),
          User.superUser
        )(task.id)
        ()
      }
      // The same payload again: still stale.
      reupload(ChoiceFixtures.withNode(node))
      stale(task) mustBe Some("key_changed")
      // A new payload replaces the observation.
      reupload(
        ChoiceFixtures.withNode(node) ++ Json.obj(
          "match" -> Json.obj("amenity" -> "bench", "leisure" -> "x")
        )
      )
      stale(task) mustBe None
      found(Some(List(3)), excludeStale = true) must contain(task.id)
    }
  }
}

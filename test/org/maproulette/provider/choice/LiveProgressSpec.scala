package org.maproulette.provider.choice

import controllers.MobileLiveController
import java.time.Instant
import org.maproulette.auth.mobile.{MobileCorsFilter, MobileFieldRoutes, MobileLiveRoutes}
import org.maproulette.framework.model.Task
import org.scalatestplus.play.PlaySpec
import play.api.libs.json._
import play.api.test.FakeRequest

/** The live progress page's states, response and routes (BACKLOG #12). No database. */
class LiveProgressSpec extends PlaySpec {
  private val now   = Instant.parse("2026-10-18T23:30:00Z")
  private val token = "a" * 43

  private val payload = Json.obj(
    "meta"    -> Json.obj("version" -> 2, "type" -> 3, "choiceVersion" -> 1),
    "element" -> "node/1",
    "match"   -> Json.obj("entrance" -> "main"),
    "questions" -> Json.arr(
      Json.obj(
        "id"     -> "door-type",
        "prompt" -> "How does this door open?",
        "options" -> Json.arr(
          Json.obj("id" -> "hinged", "label"  -> "Swings open"),
          Json.obj("id" -> "sliding", "label" -> "Slides sideways")
        )
      ),
      Json.obj(
        "id"      -> "automatic-opening",
        "prompt"  -> "Does it open by itself?",
        "options" -> Json.arr(Json.obj("id" -> "no", "label" -> "No, you push or pull it"))
      )
    ),
    "outcomes" -> Json.arr(
      Json.obj("id" -> "no-door", "label"    -> "There's no door", "status" -> 6),
      Json.obj("id" -> "not-a-door", "label" -> "Not an entrance", "status" -> 2)
    )
  )

  private def row(
      id: Long,
      status: Int,
      at: Option[Instant] = Some(Instant.parse("2026-10-18T23:12:41Z")),
      stale: Boolean = false,
      answers: Map[String, String] = Map.empty,
      outcome: Option[String] = None,
      distance: Option[Int] = None,
      pendingAt: Option[Instant] = None,
      pendingOutcome: Option[String] = None,
      name: Option[String] = None
  ) =
    LiveStopRow(
      id,
      -111.9,
      40.75,
      status,
      name,
      None,
      at,
      stale,
      answers,
      outcome,
      distance,
      Some(payload),
      pendingAt,
      pendingOutcome
    )

  private def page(answers: Boolean = false, ended: Option[Instant] = None) =
    LivePage(7, token, enabled = true, answers, Some(now.minusSeconds(3600)), ended, now)

  private def render(rows: Seq[LiveStopRow], p: LivePage = page(), people: Option[Int] = Some(3)) =
    LiveProgress.json(
      p,
      "Woodbine entrances",
      Some("Add door details #StreetTallyWoodbine #osm"),
      rows,
      people,
      now
    )

  private def stop(json: JsObject, id: Long): JsObject =
    (json \ "stops").as[Seq[JsObject]].find(s => (s \ "id").as[Long] == id).get

  "A live page stop" should {
    "be checked, needing a look, to check or left out by its status and pending answer" in {
      import LiveProgress._
      val held = Some(Instant.parse("2026-10-18T23:20:00Z"))
      Seq(
        row(1, Task.STATUS_CREATED)                   -> Some(Todo),
        row(2, Task.STATUS_SKIPPED)                   -> Some(Todo),
        row(3, Task.STATUS_FIXED)                     -> Some(Checked),
        row(4, Task.STATUS_FALSE_POSITIVE)            -> Some(Checked),
        row(5, Task.STATUS_ALREADY_FIXED)             -> Some(Checked),
        row(6, Task.STATUS_TOO_HARD)                  -> Some(Look),
        row(7, Task.STATUS_CREATED, pendingAt = held) -> Some(Checked),
        row(8, Task.STATUS_SKIPPED, pendingAt = held) -> Some(Checked),
        row(9, Task.STATUS_CREATED, pendingAt = held, pendingOutcome = Some("no-door")) -> Some(
          Look
        ),
        row(10, Task.STATUS_CREATED, pendingAt = held, pendingOutcome = Some("not-here")) -> Some(
          Look
        ),
        row(11, Task.STATUS_CREATED, pendingAt = held, pendingOutcome = Some("not-a-door")) -> Some(
          Checked
        ),
        row(12, Task.STATUS_CREATED, stale = true) -> None,
        row(13, Task.STATUS_FIXED, stale = true)   -> Some(Checked),
        row(14, Task.STATUS_DELETED)               -> None,
        row(15, Task.STATUS_DISABLED)              -> None
      ).foreach {
        case (r, expected) => withClue(s"task ${r.taskId}")(state(r) mustBe expected)
      }
    }
  }

  "The live page response" should {
    "tally the shown stops and carry no user, changeset, guest or element" in {
      val json = render(
        Seq(
          row(1, Task.STATUS_CREATED),
          row(2, Task.STATUS_FIXED, answers = Map("door-type" -> "hinged")),
          row(3, Task.STATUS_TOO_HARD, outcome = Some("no-door")),
          row(4, Task.STATUS_CREATED, stale = true)
        )
      )
      (json \ "tally").as[JsObject] mustBe Json.obj(
        "checked"   -> 1,
        "total"     -> 3,
        "needsLook" -> 1,
        "people"    -> 3
      )
      (json \ "mode").as[String] mustBe "live"
      (json \ "campaign").as[JsObject] mustBe Json.obj(
        "name"     -> "Woodbine entrances",
        "hashtags" -> Json.arr("#StreetTallyWoodbine", "#osm"),
        "match"    -> Json.obj("entrance" -> "main")
      )
      val text = Json.stringify(json)
      Seq("user", "changeset", "guest", "node/", "token", "osm_id").foreach(word =>
        withClue(word)(text must not include word)
      )
    }

    "leave answers and reported outcomes out unless the page shows them" in {
      val rows = Seq(
        row(
          2,
          Task.STATUS_FIXED,
          answers = Map("door-type" -> "sliding", "automatic-opening" -> "no")
        ),
        row(3, Task.STATUS_TOO_HARD, outcome = Some("moved"), distance = Some(40)),
        row(4, Task.STATUS_FALSE_POSITIVE, outcome = Some("not-a-door"))
      )
      val hidden = render(rows)
      (hidden \ "answersShown").as[Boolean] mustBe false
      rows.foreach { r =>
        (stop(hidden, r.taskId) \ "answers").toOption mustBe None
        (stop(hidden, r.taskId) \ "reported").toOption mustBe None
      }
      val shown = render(rows, page(answers = true))
      (stop(shown, 2) \ "answers").as[JsArray] mustBe Json.arr(
        Json.obj("question" -> "How does this door open?", "answer" -> "Slides sideways"),
        Json.obj("question" -> "Does it open by itself?", "answer"  -> "No, you push or pull it")
      )
      (stop(shown, 3) \ "reported").as[String] mustBe "It's moved about 40 m"
      (stop(shown, 4) \ "reported").as[String] mustBe "Not an entrance"
      Json.stringify(stop(shown, 3)) must not include "location"
    }

    "show a pending guest answer as checked with its time, and never its answers" in {
      val json = render(
        Seq(
          row(
            7,
            Task.STATUS_CREATED,
            at = Some(Instant.parse("2026-10-17T10:00:00Z")),
            answers = Map("door-type" -> "hinged"),
            pendingAt = Some(Instant.parse("2026-10-18T23:20:59Z"))
          )
        ),
        page(answers = true)
      )
      stop(json, 7) mustBe Json.obj(
        "id"    -> 7,
        "lon"   -> -111.9,
        "lat"   -> 40.75,
        "state" -> "checked",
        "at"    -> "2026-10-18T23:20:00Z"
      )
    }

    "cut times to the minute, give no time to stops to check, and name stops when they have one" in {
      val json = render(
        Seq(row(1, Task.STATUS_CREATED, name = Some("Granary Live")), row(2, Task.STATUS_FIXED))
      )
      (stop(json, 1) \ "at").toOption mustBe None
      (stop(json, 1) \ "name").as[String] mustBe "Granary Live"
      (stop(json, 2) \ "at").as[String] mustBe "2026-10-18T23:12:00Z"
    }

    "drop the people count once ended and say when it ended" in {
      val end  = Instant.parse("2026-10-18T23:45:00Z")
      val json = render(Seq(row(1, Task.STATUS_FIXED)), page(ended = Some(end)), Some(5))
      (json \ "mode").as[String] mustBe "ended"
      (json \ "endedAt").as[String] mustBe "2026-10-18T23:45:00Z"
      (json \ "tally" \ "people").toOption mustBe None
      (render(Seq(row(1, Task.STATUS_FIXED)), people = None) \ "tally" \ "people").toOption mustBe None
    }
  }

  "The live page settings" should {
    "take only the four fields, with eventStart an instant or null" in {
      MobileLiveController.parseUpdate(Json.obj()) mustBe Right(LivePageUpdate())
      MobileLiveController.parseUpdate(
        Json.obj(
          "enabled"     -> false,
          "showAnswers" -> true,
          "eventStart"  -> "2026-10-18T23:00:00Z",
          "ended"       -> true
        )
      ) mustBe Right(
        LivePageUpdate(
          Some(false),
          Some(true),
          Some(Some(Instant.parse("2026-10-18T23:00:00Z"))),
          Some(true)
        )
      )
      MobileLiveController.parseUpdate(Json.obj("eventStart" -> JsNull)) mustBe
        Right(LivePageUpdate(eventStart = Some(None)))
      MobileLiveController.parseUpdate(
        Json.obj("enabled" -> "yes", "eventStart" -> "tonight", "token" -> "x")
      ) mustBe Left(
        Seq(
          "unknown field token",
          "enabled must be a boolean",
          "eventStart must be an ISO 8601 instant, like 2026-10-18T23:00:00Z"
        )
      )
      MobileLiveController.parseUpdate(JsArray()) mustBe Left(Seq("expected a JSON object"))
    }
  }

  "The live page routes" should {
    "be open while field writes are off: the read to anyone, the settings to admin grants" in {
      def bearer(method: String, path: String) =
        FakeRequest(method, path).withHeaders("Authorization" -> "Bearer t")
      MobileFieldRoutes.permitsWhenDisabled(FakeRequest("GET", s"/api/v2/live/$token")) mustBe true
      MobileFieldRoutes.permitsWhenDisabled(FakeRequest("GET", "/api/v2/live/short")) mustBe false
      MobileFieldRoutes.permitsWhenDisabled(FakeRequest("POST", s"/api/v2/live/$token")) mustBe false
      MobileFieldRoutes.permitsWhenDisabled(
        bearer("GET", "/api/v2/mobile-admin/challenges/7/live")
      ) mustBe true
      MobileFieldRoutes.permitsWhenDisabled(
        bearer("PUT", "/api/v2/mobile-admin/challenges/7/live")
      ) mustBe true
      MobileFieldRoutes.permitsWhenDisabled(
        bearer("POST", "/api/v2/mobile-admin/challenges/7/live/rotate")
      ) mustBe true
      MobileFieldRoutes.permitsWhenDisabled(
        bearer("DELETE", "/api/v2/mobile-admin/challenges/7/live")
      ) mustBe false
      MobileLiveRoutes.settings("/api/v2/mobile-admin/challenges/x/live") mustBe false
    }

    "answer CORS for the participant site only" in {
      import MobileCorsFilter._
      def from(origin: String, method: String = "GET") =
        decide(
          enabled = true,
          Some("https://admin.example"),
          Some("https://site.example"),
          FakeRequest(method, s"/api/v2/live/$token").withHeaders("Origin" -> origin)
        )
      from("https://site.example") mustBe AllowOrigin("https://site.example")
      from("https://admin.example") mustBe Plain
      from("https://evil.example") mustBe Plain
    }
  }
}

package org.maproulette.provider.choice

import java.time.Instant
import org.maproulette.auth.mobile.{MobileAdminRoutes, MobileFieldRoutes}
import org.scalatestplus.play.PlaySpec
import play.api.libs.json._
import play.api.test.FakeRequest

/** The confirm queue's candidates, stale check and export lines (H1b). No database. */
class ChoiceQueueSpec extends PlaySpec {
  import ChoiceQueue._
  import ReviewPage._

  private val day1 = Instant.parse("2026-10-14T18:00:00Z")
  private val day2 = Instant.parse("2026-10-15T01:00:00Z")
  private val work = ChoiceWork.validate(ChoiceFixtures.withNode(1001)).toOption.get

  private def answers(values: (String, String)*) =
    Json.obj("answers" -> JsObject(values.map { case (q, o) => q -> JsString(o) }))

  private def place(answers: Seq[Answer], state: String = Open) =
    Place(
      1,
      Some("node"),
      Some(1001),
      -111.9,
      40.75,
      Some("Bench"),
      None,
      state,
      Some(ChoiceFixtures.withNode(1001)),
      Json.obj("amenity" -> "bench"),
      answers
    )

  private val split = place(
    Seq(
      Answer(answers("backrest" -> "yes", "material" -> "wood"), day1),
      Answer(answers("backrest" -> "yes", "material" -> "metal"), day2),
      Answer(Json.obj("outcome" -> "too-hard"), day2)
    )
  )

  "Confirm queue candidates" should {
    "take questions every guest answered alike and count the split ones" in {
      val found = candidates(split, Set.empty)
      found.candidates.map(c => (c.question.id, c.option.id, c.people)) mustBe
        Seq(("backrest", "yes", 2))
      found.candidates.head.at mustBe day2
      found.candidates.head.first mustBe day1
      found.disagree mustBe 1
      split.shown mustBe Disagree
    }

    "leave out questions already queued and places a mapper settled" in {
      candidates(split, Set("backrest")).candidates mustBe empty
      Seq(NeedsVisit, Done, Gone).foreach { state =>
        candidates(place(split.answers, state), Set.empty) mustBe PlaceCandidates(Nil, 0)
      }
      candidates(place(split.answers, Stale), Set.empty).candidates.size mustBe 1
    }

    "write the evidence line, name and cooperative tag fix" in {
      val c = candidates(split, Set.empty).candidates.head
      c.evidence mustBe "Surveyed on 14–15 Oct by 2 people. Question: \"Does the bench have a " +
        "backrest?\" Answer: \"Yes\" (backrest=yes). Add it if it looks right; skip if unsure."
      c.copy(people = 1, first = day2).evidence must startWith("Surveyed on 15 Oct by 1 person.")
      c.copy(first = Instant.parse("2026-09-30T12:00:00Z")).days mustBe "30 Sep–15 Oct"
      c.name mustBe "node/1001 backrest=yes"
      c.cooperativeWork mustBe Json.obj(
        "meta" -> Json.obj("version" -> 2, "type" -> 1),
        "operations" -> Json.arr(
          Json.obj(
            "operationType" -> "modifyElement",
            "data" -> Json.obj(
              "id" -> "node/1001",
              "operations" -> Json.arr(
                Json.obj("operation" -> "setTags", "data" -> Json.obj("backrest" -> "yes"))
              )
            )
          )
        )
      )
      (c.line \ "features")(0) mustBe Json.obj(
        "type"       -> "Feature",
        "geometry"   -> Json.obj("type" -> "Point", "coordinates" -> Json.arr(-111.9, 40.75)),
        "properties" -> Json.obj("@id" -> "node/1001", "evidence" -> c.evidence)
      )
      (c.line \ "cooperativeWork").as[JsObject] mustBe c.cooperativeWork
    }

    "unset tags an answer removes" in {
      val q = work.questions.head
      val o = ChoiceOption(
        "none",
        "No backrest",
        None,
        Map("backrest" -> "no"),
        List("backrest:material")
      )
      val c = Candidate(split, q, o, 1, day1, day1)
      c.name mustBe "node/1001 backrest=no backrest:material="
      c.tagText mustBe "backrest=no, no backrest:material"
      val ops = (c.cooperativeWork \ "operations")(0) \ "data" \ "operations"
      ops.as[Seq[JsObject]].map(o => (o \ "operation").as[String]) mustBe
        Seq("setTags", "unsetTags")
      c.applied(Map("backrest" -> "no")) mustBe true
      c.applied(Map("backrest" -> "no", "backrest:material" -> "wood")) mustBe false
    }
  }

  "The stale check" should {
    val found = candidates(
      place(Seq(Answer(answers("backrest" -> "yes", "material" -> "wood"), day1))),
      Set.empty
    ).candidates
    def read(tags: (String, String)*) =
      ElementFound(<node/>, 4, (Map("amenity" -> "bench") ++ tags).toMap)

    "queue answers whose question still holds" in {
      check(work, found, read()) mustBe Checked(found, Nil, stale = false)
    }
    "count answers already on the map as done" in {
      check(work, found, read("backrest" -> "yes")) mustBe
        Checked(found.drop(1), found.take(1), stale = false)
    }
    "flag a place whose question changed since the survey" in {
      check(work, found, read("material" -> "stone")) mustBe
        Checked(found.take(1), Nil, stale = true)
    }
    "flag a place that no longer matches, and spot one that is gone" in {
      check(work, found, ElementFound(<node/>, 4, Map("leisure" -> "picnic_table"))) mustBe
        Checked(Nil, Nil, stale = true)
      check(work, found, ElementGone) mustBe PlaceGone
    }
  }

  "The report" should {
    "carry every count" in {
      Report(77, 12, 3, 2, 1, 1, 0).json mustBe Json.obj(
        "queueChallengeId" -> 77,
        "queued"           -> 12,
        "alreadyQueued"    -> 3,
        "done"             -> 2,
        "stale"            -> 1,
        "gone"             -> 1,
        "disagree"         -> 0
      )
    }
  }

  "The queue routes" should {
    "be admin routes that work while field writes are off" in {
      def bearer(method: String, path: String) =
        FakeRequest(method, path).withHeaders("Authorization" -> "Bearer t")
      val queue = "/api/v2/mobile-admin/challenges/7/queue"
      MobileAdminRoutes.matches("POST", queue) mustBe true
      MobileAdminRoutes.matches("GET", s"$queue/export") mustBe true
      MobileFieldRoutes.permitsWhenDisabled(bearer("POST", queue)) mustBe true
      MobileFieldRoutes.permitsWhenDisabled(bearer("GET", s"$queue/export")) mustBe true
      MobileFieldRoutes.permitsWhenDisabled(bearer("DELETE", queue)) mustBe false
      MobileFieldRoutes.permitsWhenDisabled(
        bearer("POST", "/api/v2/mobile-admin/challenges/x/queue")
      ) mustBe false
    }
  }
}

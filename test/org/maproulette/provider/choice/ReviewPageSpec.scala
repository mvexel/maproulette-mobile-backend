package org.maproulette.provider.choice

import controllers.MobileReviewController
import java.time.Instant
import org.maproulette.auth.mobile.{MobileCorsFilter, MobileFieldRoutes, MobileReviewRoutes}
import org.scalatestplus.play.PlaySpec
import play.api.libs.json._
import play.api.test.FakeRequest

/** The review page's response, query and routes (H1a). No database. */
class ReviewPageSpec extends PlaySpec {
  import ReviewPage._

  private val token = "r" * 43
  private val day1  = Instant.parse("2026-10-14T23:30:00Z")
  private val day2  = Instant.parse("2026-10-15T01:00:00Z")

  private def place(
      id: Long,
      answers: Seq[Answer],
      state: String = Open,
      properties: JsObject = Json.obj("amenity" -> "bench", "material" -> "stone")
  ) =
    Place(
      id,
      Some("node"),
      Some(1000 + id),
      -111.9,
      40.75,
      Some("Bench"),
      None,
      state,
      Some(ChoiceFixtures.withNode(1000 + id)),
      properties,
      answers
    )

  private def answers(values: (String, String)*) =
    Json.obj("answers" -> JsObject(values.map { case (q, o) => q -> JsString(o) }))

  "A review place" should {
    "merge the same answer across guests, in question order, with the payload's labels" in {
      val p = place(
        1,
        Seq(
          Answer(answers("backrest" -> "yes", "material" -> "wood"), day1),
          Answer(answers("backrest" -> "yes"), day2),
          Answer(answers("backrest" -> "no"), day1)
        )
      )
      val json = p.json(skipped = false)
      (json \ "osm").as[JsObject] mustBe Json.obj("type" -> "node", "id" -> 1001)
      val suggestions = (json \ "suggestions").as[Seq[JsObject]]
      suggestions.map(s => ((s \ "question").as[String], (s \ "answer").as[String])) mustBe
        Seq("backrest" -> "yes", "backrest" -> "no", "material" -> "wood")
      suggestions.head mustBe Json.obj(
        "question" -> "backrest",
        "prompt"   -> "Does the bench have a backrest?",
        "answer"   -> "yes",
        "label"    -> "Yes",
        "tags"     -> Json.obj("backrest" -> "yes"),
        "people"   -> 2,
        "day"      -> "2026-10-15",
        "current"  -> Json.obj("backrest" -> JsNull)
      )
      (suggestions(2) \ "current").as[JsObject] mustBe Json.obj("material" -> "stone")
      (suggestions(1) \ "day").as[String] mustBe "2026-10-14"
      p.agreed mustBe true
      (json \ "reports").as[Seq[JsObject]] mustBe empty
    }

    "group reports by outcome, with the latest spot, and leave out \"not sure\"" in {
      val spot = Json.obj("lat" -> 40.7538, "lon" -> -111.9067, "source" -> "tap")
      val p = place(
        2,
        Seq(
          Answer(Json.obj("outcome" -> "moved", "location" -> spot), day2),
          Answer(Json.obj("outcome" -> "gone", "delete" -> true), day1),
          Answer(Json.obj("outcome" -> "too-hard"), day2)
        )
      )
      val json = p.json(skipped = true)
      (json \ "skipped").as[Boolean] mustBe true
      (json \ "suggestions").as[Seq[JsObject]] mustBe empty
      (json \ "reports").as[Seq[JsObject]] mustBe Seq(
        Json.obj(
          "outcome" -> "gone",
          "label"   -> "Bench is gone",
          "people"  -> 1,
          "day"     -> "2026-10-14"
        ),
        Json.obj(
          "outcome" -> "moved",
          "label"   -> "It's moved",
          "people"  -> 1,
          "day"     -> "2026-10-15",
          "lon"     -> -111.9067,
          "lat"     -> 40.7538
        )
      )
      p.agreed mustBe false
    }
  }

  "The review response" should {
    val places = Seq(
      place(
        1,
        Seq(Answer(answers("backrest" -> "yes"), day1), Answer(answers("backrest" -> "yes"), day2))
      ),
      place(2, Seq(Answer(answers("backrest" -> "no"), day1))),
      place(3, Seq(Answer(answers("backrest" -> "no"), day1)), NeedsVisit),
      place(4, Nil, Done)
    )
    def ids(json: JsObject) = (json \ "places").as[Seq[JsObject]].map(p => (p \ "id").as[Long])

    "count every place, filter, and page by id" in {
      val all = ReviewPage.json(
        "Benches",
        Some("Add bench details #StreetTally #Woodbine"),
        places,
        Set(2L),
        "all",
        None,
        2
      )
      (all \ "campaign").as[JsObject] mustBe Json.obj(
        "name"             -> "Benches",
        "hashtags"         -> Json.arr("#StreetTally", "#Woodbine"),
        "changesetComment" -> "Add bench details #StreetTally #Woodbine"
      )
      (all \ "counts")
        .as[JsObject] mustBe Json.obj(
        "toReview"   -> 2,
        "added"      -> 1,
        "needsVisit" -> 1,
        "stale"      -> 0,
        "gone"       -> 0,
        "disagree"   -> 0
      )
      ids(all) mustBe Seq(1, 2)
      (all \ "next").as[Long] mustBe 2
      ((all \ "places")(1) \ "skipped").as[Boolean] mustBe true
      val rest = ReviewPage.json("Benches", None, places, Set.empty, "all", Some(2), 2)
      ids(rest) mustBe Seq(3, 4)
      (rest \ "next").toOption mustBe None
      (rest \ "campaign" \ "changesetComment").get mustBe JsNull
      (rest \ "campaign" \ "hashtags").as[Seq[String]] mustBe empty

      ids(ReviewPage.json("B", None, places, Set.empty, "agree", None, 100)) mustBe Seq(1)
      ids(ReviewPage.json("B", None, places, Set.empty, "visit", None, 100)) mustBe Seq(3)
      ids(ReviewPage.json("B", None, places, Set.empty, "done", None, 100)) mustBe Seq(4)
    }

    "show stale, gone and split places in their own counts (H1b)" in {
      val split = place(
        5,
        Seq(
          Answer(answers("backrest" -> "yes"), day1),
          Answer(answers("backrest" -> "yes"), day1),
          Answer(answers("backrest" -> "no"), day2)
        )
      )
      val more = places ++ Seq(
        split,
        place(6, Seq(Answer(answers("backrest" -> "no"), day1)), Stale),
        place(7, Nil, Gone)
      )
      val json = ReviewPage.json("B", None, more, Set.empty, "all", None, 100)
      (json \ "counts").as[JsObject] mustBe Json.obj(
        "toReview"   -> 2,
        "added"      -> 1,
        "needsVisit" -> 1,
        "stale"      -> 1,
        "gone"       -> 1,
        "disagree"   -> 1
      )
      ((json \ "places")(4) \ "state").as[String] mustBe Disagree
      split.disagreeing mustBe Set("backrest")
      ids(ReviewPage.json("B", None, more, Set.empty, "agree", None, 100)) mustBe Seq(1)
    }

    "take only filter, after and limit, each once and in range" in {
      def q(pairs: (String, String)*) =
        MobileReviewController.query(pairs.groupBy(_._1).map { case (k, v) => k -> v.map(_._2) })
      q() mustBe Some(("all", None, 100))
      q("filter" -> "agree", "after" -> "12", "limit" -> "200") mustBe Some(
        ("agree", Some(12L), 200)
      )
      q("filter" -> "mine") mustBe None
      q("limit"  -> "0") mustBe None
      q("limit"  -> "201") mustBe None
      q("after"  -> "x") mustBe None
      q("after"  -> "1", "after" -> "2") mustBe None
      q("page"   -> "1") mustBe None
    }
  }

  "The review routes" should {
    val page = s"/api/v2/review/$token"

    "match only the page and its two marks" in {
      MobileReviewRoutes.read("GET", page) mustBe true
      MobileReviewRoutes.read("GET", "/api/v2/review/short") mustBe false
      MobileReviewRoutes.mark("PUT", s"$page/places/12/skip") mustBe true
      MobileReviewRoutes.mark("DELETE", s"$page/places/12/needs-visit") mustBe true
      MobileReviewRoutes.mark("POST", s"$page/places/12/skip") mustBe false
      MobileReviewRoutes.mark("PUT", s"$page/places/x/skip") mustBe false
      MobileReviewRoutes.mark("PUT", s"$page/places/12/looks-right") mustBe false
    }

    "stay open while field writes are off" in {
      MobileFieldRoutes.permitsWhenDisabled(FakeRequest("GET", page)) mustBe true
      MobileFieldRoutes.permitsWhenDisabled(FakeRequest("PUT", s"$page/places/1/skip")) mustBe true
      MobileFieldRoutes.permitsWhenDisabled(FakeRequest("DELETE", s"$page/places/1/needs-visit")) mustBe
        true
      MobileFieldRoutes.permitsWhenDisabled(FakeRequest("POST", s"$page/places/1/skip")) mustBe false
    }

    "answer CORS for the participant site and the admin app only" in {
      import MobileCorsFilter._
      def from(origin: String, method: String, path: String, preflight: Boolean = false) = {
        val base = FakeRequest(if (preflight) "OPTIONS" else method, path)
          .withHeaders("Origin" -> origin)
        decide(
          enabled = true,
          Some("https://admin.example"),
          Some("https://site.example"),
          if (preflight)
            base.withHeaders(
              "Access-Control-Request-Method"  -> method,
              "Access-Control-Request-Headers" -> "authorization"
            )
          else base
        )
      }
      val mark = s"$page/places/1/needs-visit"
      from("https://site.example", "GET", page) mustBe AllowOrigin("https://site.example")
      from("https://admin.example", "GET", page) mustBe AllowOrigin("https://admin.example")
      from("https://evil.example", "GET", page) mustBe Plain
      Seq("https://site.example", "https://admin.example").foreach { origin =>
        Seq("PUT", "DELETE").foreach { method =>
          from(origin, method, mark, preflight = true) match {
            case Preflight(result) =>
              result.header.status mustBe 204
              result.header.headers.get("Access-Control-Allow-Origin") mustBe Some(origin)
            case other => fail(s"$origin $method: $other")
          }
        }
        from(origin, "POST", mark, preflight = true) mustBe Preflight(
          play.api.mvc.Results.Forbidden
        )
      }
      from("https://evil.example", "PUT", mark, preflight = true) mustBe
        Preflight(play.api.mvc.Results.Forbidden)
    }
  }
}

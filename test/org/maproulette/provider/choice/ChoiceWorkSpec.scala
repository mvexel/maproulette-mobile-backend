package org.maproulette.provider.choice

import org.scalatestplus.play.PlaySpec
import play.api.libs.json._

object ChoiceFixtures {

  /** The SLC bench example from the spec (§3). */
  val bench: JsObject = Json
    .parse("""{
    "meta": {"version": 2, "type": 3, "choiceVersion": 1},
    "element": "node/123",
    "match": {"amenity": "bench"},
    "questions": [
     {"id": "backrest", "prompt": "Does the bench have a backrest?",
      "expect": {"backrest": null},
      "options": [
       {"id": "yes", "label": "Yes", "setTags": {"backrest": "yes"}},
       {"id": "no", "label": "No", "setTags": {"backrest": "no"}}]},
     {"id": "material", "prompt": "What is the seat mainly made of?",
      "expect": {"material": null},
      "options": [
       {"id": "wood", "label": "Wood", "setTags": {"material": "wood"}},
       {"id": "metal", "label": "Metal", "setTags": {"material": "metal"}}]},
     {"id": "capacity", "prompt": "How many adults can sit on it?",
      "expect": {"capacity": null},
      "options": [
       {"id": "c2", "label": "2", "setTags": {"capacity": "2"}},
       {"id": "c3", "label": "3", "setTags": {"capacity": "3"}}]}],
    "outcomes": [
     {"id": "not-a-bench", "label": "Not a bench", "status": 2},
     {"id": "gone", "label": "Bench is gone", "delete": true}]}""")
    .as[JsObject]

  def withNode(id: Long): JsObject = bench ++ Json.obj("element" -> s"node/$id")
}

class ChoiceWorkSpec extends PlaySpec {
  import ChoiceFixtures.bench

  private def errors(json: JsValue): List[String] = ChoiceWork.validate(json).left.getOrElse(Nil)
  private def question(index: Int)                = (bench \ "questions")(index).as[JsObject]
  private def withQuestion(index: Int, value: JsObject): JsObject = {
    val questions = (bench \ "questions").as[JsArray].value.toList.updated(index, value)
    bench ++ Json.obj("questions" -> questions)
  }

  "ChoiceWork.validate" should {
    "accept the SLC bench payload" in {
      val work = ChoiceWork.validate(bench).toOption.get
      work.element mustBe "node/123"
      work.questions.map(_.id) mustBe List("backrest", "material", "capacity")
      work.questions.head.expect mustBe Map("backrest" -> None)
      work.deleteOutcome.map(_.id) mustBe Some("gone")
      work.outcomes.head.status mustBe Some(2)
    }

    "accept an opt-in live question template and require absent-key guards" in {
      val live = bench ++ Json.obj("liveMissingQuestions" -> true)
      ChoiceWork.validate(live).toOption.get.liveMissingQuestions mustBe true
      val present = question(0) ++ Json.obj("expect" -> Json.obj("backrest" -> "yes"))
      errors(withQuestion(0, present) ++ Json.obj("liveMissingQuestions" -> true)) must
        contain("liveMissingQuestions: every question must guard only absent tags")
    }

    "recognise choice payloads by meta only" in {
      ChoiceWork.isChoice(bench) mustBe true
      ChoiceWork.isChoice(Json.obj("meta" -> Json.obj("version" -> 2, "type" -> 1))) mustBe false
    }

    "reject any other choiceVersion, version or type" in {
      errors(
        bench ++ Json.obj("meta" -> Json.obj("version" -> 2, "type" -> 3, "choiceVersion" -> 2))
      ) mustBe
        List("meta.choiceVersion: must be 1")
      errors(bench ++ Json.obj("meta" -> Json.obj("version" -> 2, "type" -> 3))) mustBe
        List("meta.choiceVersion: must be 1")
    }

    "report every broken rule, not just the first" in {
      val broken = bench ++ Json.obj(
        "element" -> "node/0",
        "extra"   -> true,
        "match"   -> Json.obj("backrest" -> "x")
      )
      val found = errors(broken)
      found must contain("cooperativeWork: unknown field 'extra'")
      found must contain("element: must match (node|way|relation)/<positive id>")
      found must contain("match: key 'backrest' overlaps a question's expect")
      found.size mustBe 4 // plus: the delete outcome now has no node element
    }

    "check element, match and tag limits" in {
      Seq("way/12", "relation/1", "node/1234567890123456").foreach { element =>
        ChoiceWork
          .validate(bench ++ Json.obj("element" -> element, "outcomes" -> Json.arr()))
          .isRight mustBe true
      }
      Seq("node/-1", "node/01", "nodes/1", "node/12345678901234567", "node/1 ").foreach { element =>
        errors(bench ++ Json.obj("element" -> element)) must not be empty
      }
      errors(bench ++ Json.obj("match" -> Json.obj())) must contain("match: must have 1 to 4 keys")
      errors(bench ++ Json.obj("match" -> Json.obj("amenity" -> JsNull))) must contain(
        "match.amenity: must be a string"
      )
      errors(bench ++ Json.obj("match" -> Json.obj("amenity" -> "x" * 256))) must not be empty
      errors(bench ++ Json.obj("match" -> Json.obj("amenity" -> "a\nb"))) must not be empty
      // Without match there may be no delete outcome.
      errors(bench - "match") mustBe List(
        "outcomes: a delete outcome needs a node element and a match guard"
      )
    }

    "check questions: count, ids, prompt, expect overlap" in {
      errors(bench ++ Json.obj("questions" -> Json.arr())) must contain(
        "questions: must have 1 to 8 entries"
      )
      errors(withQuestion(1, question(1) ++ Json.obj("id" -> "backrest"))) must contain(
        "questions: duplicate id 'backrest'"
      )
      errors(withQuestion(0, question(0) ++ Json.obj("id"          -> "Back rest"))) must not be empty
      errors(withQuestion(0, question(0) ++ Json.obj("prompt"      -> ""))) must not be empty
      errors(withQuestion(0, question(0) ++ Json.obj("prompt"      -> "x" * 201))) must not be empty
      errors(withQuestion(0, question(0) ++ Json.obj("description" -> "x" * 501))) must not be empty
      val overlap = question(1) ++ Json.obj(
        "expect"  -> Json.obj("material" -> JsNull, "backrest" -> JsNull),
        "options" -> (question(1) \ "options").get
      )
      errors(withQuestion(1, overlap)) must contain(
        "questions: expect key 'backrest' is guarded by more than one question"
      )
      errors(withQuestion(0, question(0) ++ Json.obj("expect" -> Json.obj()))) must contain(
        "questions[0].expect: must have 1 to 4 keys"
      )
    }

    "check options: count, ids, labels, guarded keys and no-ops" in {
      def options(values: JsValue*) = withQuestion(0, question(0) ++ Json.obj("options" -> values))
      val yes =
        Json.obj("id" -> "yes", "label" -> "Yes", "setTags" -> Json.obj("backrest" -> "yes"))
      errors(options(yes)) must contain("questions[0].options: must have 2 to 12 entries")
      errors(options(yes, yes)) must contain("questions[0].options: duplicate id 'yes'")
      errors(options(yes, Json.obj("id" -> "no", "label" -> "No"))) must contain(
        "questions[0].options[1]: needs setTags and/or unsetTags"
      )
      errors(
        options(
          yes,
          Json.obj("id" -> "x", "label" -> "X", "setTags" -> Json.obj("material" -> "wood"))
        )
      ) must contain(
        "questions[0].options[1]: key 'material' is not guarded by the question's expect"
      )
      // Removing an already absent key is a no-op.
      errors(
        options(yes, Json.obj("id" -> "x", "label" -> "X", "unsetTags" -> Json.arr("backrest")))
      ) must contain(
        "questions[0].options[1]: equals the expected state (no-op)"
      )
      errors(
        options(
          yes,
          Json.obj("id" -> "x", "label" -> "x" * 61, "setTags" -> Json.obj("backrest" -> "no"))
        )
      ) must not be empty
      errors(options(yes, yes ++ Json.obj("id" -> "y", "icon" -> "x"))) must contain(
        "questions[0].options[1]: unknown field 'icon'"
      )
      // A string expect may be changed or removed.
      val retag = question(0) ++ Json.obj(
        "expect" -> Json.obj("backrest" -> "unknown"),
        "options" -> Json.arr(
          Json.obj("id" -> "yes", "label" -> "Yes", "setTags"      -> Json.obj("backrest" -> "yes")),
          Json.obj("id" -> "rm", "label"  -> "Remove", "unsetTags" -> Json.arr("backrest"))
        )
      )
      ChoiceWork.validate(withQuestion(0, retag)).isRight mustBe true
    }

    "check outcomes" in {
      def outcomes(values: JsValue*) = bench ++ Json.obj("outcomes" -> values)
      ChoiceWork.validate(outcomes()).isRight mustBe true
      errors(outcomes(Json.obj("id" -> "too-hard", "label" -> "X", "status" -> 6))) must contain(
        "outcomes[0].id: 'too-hard' is built in"
      )
      Seq(
        Json.obj("id" -> "a", "label" -> "A", "status" -> 5),
        Json.obj("id" -> "a", "label" -> "A", "status" -> 1),
        Json.obj("id" -> "a", "label" -> "A"),
        Json.obj("id" -> "a", "label" -> "A", "delete" -> false),
        Json.obj("id" -> "a", "label" -> "A", "status" -> 2, "delete" -> true)
      ).foreach(outcome => errors(outcomes(outcome)) must not be empty)
      errors(
        outcomes(
          Json.obj("id" -> "a", "label" -> "A", "delete" -> true),
          Json.obj("id" -> "b", "label" -> "B", "delete" -> true)
        )
      ) must contain("outcomes: at most one delete outcome")
      errors(bench ++ Json.obj("element" -> "way/5")) must contain(
        "outcomes: a delete outcome needs a node element and a match guard"
      )
      errors(
        outcomes((1 to 5).map(i => Json.obj("id" -> s"o$i", "label" -> "O", "status" -> 2)): _*)
      ) must contain(
        "outcomes: must have 0 to 4 entries"
      )
    }

    "limit the serialized payload to 16 KiB" in {
      val big = withQuestion(0, question(0) ++ Json.obj("description" -> "x" * 500)) ++
        Json.obj("padding" -> "y" * 16384)
      errors(big) must contain("payload: larger than 16384 bytes")
    }

    "treat JSON null as absent for optional fields" in {
      val nulls = bench ++ Json.obj("outcomes" -> JsNull) - "match"
      ChoiceWork.validate(nulls).isRight mustBe true
      ChoiceWork
        .validate(bench ++ Json.obj("match" -> JsNull, "outcomes" -> JsNull))
        .isRight mustBe true
      val option = Json.obj(
        "id"          -> "yes",
        "label"       -> "Yes",
        "description" -> JsNull,
        "setTags"     -> Json.obj("backrest" -> "yes"),
        "unsetTags"   -> JsNull
      )
      val second = Json.obj(
        "id"        -> "rm",
        "label"     -> "Remove",
        "setTags"   -> JsNull,
        "unsetTags" -> Json.arr("backrest")
      )
      val retag = question(0) ++ Json.obj(
        "description" -> JsNull,
        "expect"      -> Json.obj("backrest" -> "unknown"),
        "options"     -> Json.arr(option, second)
      )
      ChoiceWork.validate(withQuestion(0, retag)).isRight mustBe true
      // Required fields stay required.
      errors(withQuestion(0, question(0) ++ Json.obj("prompt" -> JsNull))) must not be empty
    }

    "count lengths in code points and limit outcome labels and descriptions" in {
      val emoji = "\uD83E\uDE91" // one code point, two UTF-16 units
      errors(withQuestion(0, question(0) ++ Json.obj("prompt" -> emoji * 200))) mustBe empty
      errors(withQuestion(0, question(0) ++ Json.obj("prompt" -> emoji * 201))) must not be empty
      def outcome(extra: JsObject) =
        bench ++ Json.obj(
          "outcomes" -> Json.arr(Json.obj("id" -> "a", "label" -> "A", "status" -> 2) ++ extra)
        )
      errors(outcome(Json.obj("label"       -> emoji * 60))) mustBe empty
      errors(outcome(Json.obj("label"       -> "x" * 61))) must not be empty
      errors(outcome(Json.obj("label"       -> ""))) must not be empty
      errors(outcome(Json.obj("description" -> "x" * 300))) mustBe empty
      errors(outcome(Json.obj("description" -> "x" * 301))) must not be empty
    }

    "reject a key both set and unset, and a repeated unset key" in {
      def options(extra: JsObject) = withQuestion(
        0,
        question(0) ++ Json.obj(
          "expect" -> Json.obj("backrest" -> "unknown"),
          "options" -> Json.arr(
            Json.obj("id" -> "yes", "label" -> "Yes", "setTags" -> Json.obj("backrest" -> "yes")),
            Json.obj("id" -> "x", "label"   -> "X") ++ extra
          )
        )
      )
      errors(
        options(
          Json.obj("setTags" -> Json.obj("backrest" -> "no"), "unsetTags" -> Json.arr("backrest"))
        )
      ) must contain(
        "questions[0].options[1]: key 'backrest' is both set and unset"
      )
      errors(options(Json.obj("unsetTags" -> Json.arr("backrest", "backrest")))) must contain(
        "questions[0].options[1].unsetTags: duplicate key"
      )
    }
  }
}

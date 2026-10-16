package org.maproulette.provider.choice

import java.nio.charset.StandardCharsets
import javax.inject.Singleton
import play.api.libs.json._
import scala.util.Try

/**
  * Which OSM elements a set covers: every key in `tags` present (with that value, if given) and no
  * key in `exclude` holding one of its values. Compiled to Overpass QL and re-checked here.
  */
case class SetFilter(
    elements: List[String],
    tags: Map[String, Option[String]],
    exclude: Map[String, List[String]]
) {
  def selects(kind: String, tags: Map[String, String]): Boolean =
    elements.contains(kind) &&
      this.tags.forall { case (k, v)    => tags.get(k).exists(found => v.forall(_ == found)) } &&
      exclude.forall { case (k, values) => !tags.get(k).exists(values.contains) }
}

/** An open feature: in the area, selected by the filter, with at least one question to ask. */
case class SetFeature(
    kind: String,
    id: Long,
    lon: Double,
    lat: Double,
    tags: Map[String, String]
) {
  def element: String = s"$kind/$id"
}

/**
  * One question set of the campaign wizard (campaign-wizard.md §1): its words for the admin, the
  * filter for the server, and the questions and outcomes every task gets. The client sees only
  * [[publicJson]].
  */
case class QuestionSet(
    id: String,
    version: Int,
    name: String,
    noun: (String, String),
    instruction: String,
    filter: SetFilter,
    matchTags: Map[String, String],
    matchKeys: List[String],
    questions: List[(JsObject, Map[String, Option[String]])],
    outcomes: JsArray,
    publicJson: JsObject
) {

  /** The questions still to ask of an element with these tags (all fill-missing). */
  def applicable(tags: Map[String, String]): List[JsObject] =
    questions.collect {
      case (question, expect) if expect.forall { case (k, v) => tags.get(k) == v } => question
    }

  def isOpen(kind: String, tags: Map[String, String]): Boolean =
    filter.selects(kind, tags) && applicable(tags).nonEmpty

  /**
    * One task line for the line-by-line import, as the admin's survey builder writes it
    * (src/survey.ts generateTasks), or None when nothing is left to ask.
    */
  def taskLine(feature: SetFeature): Option[String] = {
    val asked = applicable(feature.tags)
    val keys  = matchKeys.flatMap(k => feature.tags.get(k).map(k -> _))
    if (asked.isEmpty || keys.size != matchKeys.size) None
    else {
      val matching = matchTags ++ keys
      val allMissing =
        questions.filter(q => asked.contains(q._1)).forall(_._2.values.forall(_.isEmpty))
      val work = Json.obj(
        "meta" -> Json
          .obj("version" -> 2, "type" -> ChoiceWork.CooperativeType, "choiceVersion" -> 1),
        "element"   -> feature.element,
        "questions" -> asked
      ) ++
        (if (matching.nonEmpty) Json.obj("match"          -> matching) else Json.obj()) ++
        (if (outcomes.value.nonEmpty) Json.obj("outcomes" -> outcomes) else Json.obj()) ++
        (if (allMissing) Json.obj("liveMissingQuestions"  -> true) else Json.obj())
      val properties = JsObject(
        (feature.tags.filter { case (k, v) => ChoiceWork.validTag(k) && ChoiceWork.validTag(v) } +
          ("@id" -> feature.element)).view.mapValues(JsString(_)).toSeq
      )
      val line = Json.obj(
        "type" -> "FeatureCollection",
        "features" -> Json.arr(
          Json.obj(
            "type" -> "Feature",
            "geometry" -> Json
              .obj("type" -> "Point", "coordinates" -> Json.arr(feature.lon, feature.lat)),
            "properties" -> properties
          )
        ),
        "cooperativeWork" -> work
      )
      Some(Json.stringify(line))
    }
  }
}

object QuestionSet {
  private val Identifier = "^[a-z0-9][a-z0-9-]{0,31}$"

  /** `{"key": "value" | null}`; null is "any value" in a filter and "absent" in an expect. */
  private def optionalMap(json: JsLookupResult): Map[String, Option[String]] =
    json
      .asOpt[JsObject]
      .map(_.value.toMap.collect {
        case (k, JsString(v)) => k -> Some(v)
        case (k, JsNull)      => k -> None
      })
      .getOrElse(Map.empty)

  /** Reads one set, or every problem with it. Every task payload it can make is a valid choice. */
  def parse(json: JsValue): Either[List[String], QuestionSet] = {
    val errors                = List.newBuilder[String]
    val id                    = (json \ "id").asOpt[String].filter(_.matches(Identifier))
    def label                 = id.getOrElse("?")
    def fail(m: String): Unit = errors += s"$label: $m"
    if (id.isEmpty) fail("id must be a short lowercase identifier")
    def text(key: String): String =
      (json \ key).asOpt[String].filter(_.nonEmpty).getOrElse { fail(s"$key is required"); "" }
    val version =
      (json \ "version").asOpt[Int].filter(_ > 0).getOrElse { fail("version must be positive"); 0 }
    val name = text("name")
    val one  = (json \ "noun" \ "one").asOpt[String].getOrElse { fail("noun.one is required"); "" }
    val many =
      (json \ "noun" \ "many").asOpt[String].getOrElse { fail("noun.many is required"); "" }
    Seq("why", "audience", "example", "description").foreach(text)
    val pace =
      (json \ "pace").asOpt[Int].filter(_ > 0).getOrElse { fail("pace must be positive"); 0 }
    val instruction = text("instruction")
    val elements    = (json \ "filter" \ "elements").asOpt[List[String]].getOrElse(Nil)
    if (elements.isEmpty || !elements.forall(Set("node", "way").contains))
      fail("filter.elements must list node and/or way")
    val tags = optionalMap(json \ "filter" \ "tags")
    if (tags.isEmpty) fail("filter.tags must not be empty")
    val exclude =
      (json \ "filter" \ "exclude").asOpt[Map[String, List[String]]].getOrElse(Map.empty)
    val matchTags = (json \ "match").asOpt[Map[String, String]].getOrElse(Map.empty)
    val matchKeys = (json \ "matchKeys").asOpt[List[String]].getOrElse(Nil)
    (tags.keys ++ tags.values.flatten ++ exclude.keys ++ exclude.values.flatten).foreach { value =>
      if (!ChoiceWork.validTag(value)) fail(s"invalid tag text in the filter")
    }
    val questions = (json \ "questions").asOpt[List[JsObject]].getOrElse(Nil).map { q =>
      q -> optionalMap(q \ "expect")
    }
    if (questions.isEmpty) fail("questions must not be empty")
    val outcomes = (json \ "outcomes").asOpt[JsArray].getOrElse(Json.arr())
    if (!questions
          .map(q => (q._1 \ "prompt").asOpt[String])
          .contains((json \ "example").asOpt[String]))
      fail("example must be one of the questions' prompts")
    // Every key the filter or match fixes must also hold for every feature the filter selects.
    matchTags.foreach {
      case (k, v) => if (!tags.get(k).contains(Some(v))) fail(s"match $k=$v is not in filter.tags")
    }
    matchKeys.foreach(k => if (!tags.contains(k)) fail(s"matchKeys $k is not in filter.tags"))
    val set = QuestionSet(
      label,
      version,
      name,
      (one, many),
      instruction,
      SetFilter(elements, tags, exclude),
      matchTags,
      matchKeys,
      questions,
      outcomes,
      JsObject(
        Seq("id", "version", "name", "why", "audience", "noun", "example", "pace", "description")
          .flatMap(k => (json \ k).toOption.map(k -> _))
      )
    )
    // A sample feature with nothing set: its payload must pass the backend's own check.
    val sample = SetFeature(
      elements.headOption.getOrElse("node"),
      1,
      0,
      0,
      tags.map { case (k, v) => k -> v.getOrElse("yes") }
    )
    set.taskLine(sample) match {
      case None => fail("a feature with none of the answers set gets no questions")
      case Some(line) =>
        ChoiceWork.validate((Json.parse(line) \ "cooperativeWork").get, onWrite = true) match {
          case Left(problems) => problems.foreach(p => fail(s"task payload: $p"))
          case Right(_)       =>
        }
        if (line.getBytes(StandardCharsets.UTF_8).length > ChoiceWork.MaxBytes)
          fail("task payload is too large")
    }
    val all = errors.result()
    if (all.nonEmpty) Left(all) else Right(set)
  }
}

/**
  * The question-set library (campaign-wizard.md §1), from `conf/mobile/question-sets.json`. Changes
  * go through review; a set's `version` goes up whenever its filter, questions or outcomes change,
  * so a wizard that read an older version is told (409 `set_changed`).
  */
@Singleton
class QuestionSetLibrary(val sets: List[QuestionSet]) {
  def this() = this(QuestionSetLibrary.load("mobile/question-sets.json"))
  def find(id: String): Option[QuestionSet] = sets.find(_.id == id)
}

object QuestionSetLibrary {
  def load(resource: String): List[QuestionSet] = {
    val stream = Option(getClass.getClassLoader.getResourceAsStream(resource))
      .getOrElse(throw new IllegalStateException(s"$resource is missing"))
    val json =
      Try(Json.parse(stream)).getOrElse(throw new IllegalStateException(s"$resource is not JSON"))
    stream.close()
    parse(json).fold(e => throw new IllegalStateException(e.mkString("; ")), identity)
  }

  def parse(json: JsValue): Either[List[String], List[QuestionSet]] = {
    val parsed = (json \ "sets").asOpt[List[JsValue]].getOrElse(Nil).map(QuestionSet.parse)
    val errors = parsed.flatMap(_.left.toSeq.flatten) ++
      (if (parsed.isEmpty) List("no sets") else Nil)
    val sets = parsed.flatMap(_.toOption)
    val duplicate =
      sets.groupBy(_.id).collect { case (id, s) if s.size > 1 => s"$id: listed twice" }
    if (errors.nonEmpty || duplicate.nonEmpty) Left(errors ++ duplicate) else Right(sets)
  }
}

/** The circle a wizard search covers. */
case class SearchArea(lat: Double, lon: Double, radius: Int)

object SearchArea {
  val MinRadius = 200
  val MaxRadius = 3000

  def parse(
      lat: Option[String],
      lon: Option[String],
      radius: Option[String]
  ): Either[List[String], SearchArea] = {
    val la = lat.flatMap(v => Try(v.toDouble).toOption).filter(v => !v.isNaN && math.abs(v) <= 90)
    val lo = lon.flatMap(v => Try(v.toDouble).toOption).filter(v => !v.isNaN && math.abs(v) <= 180)
    val r  = radius.flatMap(v => Try(v.toInt).toOption).filter(v => v >= MinRadius && v <= MaxRadius)
    val errors = List(
      la.fold(List("lat must be a latitude"))(_ => Nil),
      lo.fold(List("lon must be a longitude"))(_ => Nil),
      r.fold(List(s"radius must be $MinRadius to $MaxRadius metres"))(_ => Nil)
    ).flatten
    if (errors.nonEmpty) Left(errors) else Right(SearchArea(la.get, lo.get, r.get))
  }
}

/**
  * Overpass QL for the wizard. Every string comes from the library, never from a request, and is
  * quoted anyway. One query counts every set; another lists one set's open features.
  */
object OverpassQuery {
  val Timeout = 10

  private def quote(s: String): String =
    "\"" + s.flatMap {
      case '"'  => "\\\""
      case '\\' => "\\\\"
      case '\n' => "\\n"
      case c    => c.toString
    } + "\""
  private def regexQuote(s: String): String = s.flatMap { c =>
    if ("\\^$.|?*+()[]{}".contains(c)) s"\\$c" else c.toString
  }

  private def around(a: SearchArea): String = f"(around:${a.radius},${a.lat}%.7f,${a.lon}%.7f)"

  private def selector(f: SetFilter): String =
    f.tags.toList
      .sortBy(_._1)
      .map {
        case (k, None)    => s"[${quote(k)}]"
        case (k, Some(v)) => s"[${quote(k)}=${quote(v)}]"
      }
      .mkString +
      f.exclude.toList
        .sortBy(_._1)
        .collect {
          case (k, values) if values.nonEmpty =>
            s"[${quote(k)}!~${quote(values.map(regexQuote).mkString("^(", "|", ")$"))}]"
        }
        .mkString

  private def missing(expect: Map[String, Option[String]]): String =
    expect.toList
      .sortBy(_._1)
      .map {
        case (k, None)    => s"[!${quote(k)}]"
        case (k, Some(v)) => s"[${quote(k)}=${quote(v)}]"
      }
      .mkString

  /** Sets `.a<i>` (all selected) and `.o<i>` (open). */
  private def sets(set: QuestionSet, area: SearchArea, i: Int): String = {
    val all = set.filter.elements.map(e => s"$e${selector(set.filter)}${around(area)};").mkString
    val open = for {
      e        <- set.filter.elements
      (_, exp) <- set.questions
    } yield s"$e.a$i${missing(exp)};"
    s"($all)->.a$i;(${open.mkString})->.o$i;"
  }

  private def header = s"[out:json][timeout:$Timeout];"

  /** Two count elements per set, in order: open, then all. */
  def counts(library: Seq[QuestionSet], area: SearchArea): String =
    header + library.zipWithIndex.map {
      case (set, i) => sets(set, area, i) + s".o$i out count;.a$i out count;"
    }.mkString

  /** The counts of all selected and of open elements, then up to `limit` open elements. */
  def features(set: QuestionSet, area: SearchArea, limit: Int): String =
    header + sets(set, area, 0) + s".a0 out count;.o0 out count;.o0 out tags center $limit;"

  /** Overpass's `out count` element: its total. */
  def total(element: JsValue): Option[Long] =
    (element \ "type").asOpt[String].filter(_ == "count").flatMap { _ =>
      (element \ "tags" \ "total").asOpt[String].flatMap(v => Try(v.toLong).toOption)
    }

  def feature(element: JsValue): Option[SetFeature] =
    for {
      kind <- (element \ "type").asOpt[String].filter(Set("node", "way").contains)
      id   <- (element \ "id").asOpt[Long].filter(_ > 0)
      lat  <- (element \ "lat").asOpt[Double].orElse((element \ "center" \ "lat").asOpt[Double])
      lon  <- (element \ "lon").asOpt[Double].orElse((element \ "center" \ "lon").asOpt[Double])
    } yield SetFeature(
      kind,
      id,
      lon,
      lat,
      (element \ "tags").asOpt[Map[String, String]].getOrElse(Map.empty)
    )
}

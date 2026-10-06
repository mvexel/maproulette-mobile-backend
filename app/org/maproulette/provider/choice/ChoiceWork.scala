package org.maproulette.provider.choice

import java.nio.charset.StandardCharsets
import play.api.libs.json._

/** One answer to a question: a tag change limited to the question's guarded keys. */
case class ChoiceOption(
    id: String,
    label: String,
    description: Option[String],
    setTags: Map[String, String],
    unsetTags: List[String]
)

/** A question guarding `expect` (key -> required current value, None = absent). */
case class ChoiceQuestion(
    id: String,
    prompt: String,
    description: Option[String],
    expect: Map[String, Option[String]],
    options: List[ChoiceOption]
) {
  def holds(tags: Map[String, String]): Boolean =
    expect.forall { case (key, value) => tags.get(key) == value }
}

/** A task-level result that is not an edit: a status (2 or 6) or "the element is gone". */
case class ChoiceOutcome(
    id: String,
    label: String,
    description: Option[String],
    status: Option[Int],
    delete: Boolean
)

/**
  * `cooperativeWork` with `meta.type == 3`: several questions about one OSM element, applied as one
  * changeset. See the SDK's docs/mobile-choice-challenges.md.
  */
case class ChoiceWork(
    elementType: String,
    elementId: Long,
    matchTags: Map[String, String],
    questions: List[ChoiceQuestion],
    outcomes: List[ChoiceOutcome]
) {
  def element: String                      = s"$elementType/$elementId"
  def deleteOutcome: Option[ChoiceOutcome] = outcomes.find(_.delete)
  def matches(tags: Map[String, String]): Boolean =
    matchTags.forall { case (key, value) => tags.get(key).contains(value) }
}

object ChoiceWork {
  val CooperativeType        = 3
  val MaxBytes               = 16384
  val TooHard                = "too-hard"
  val IdPattern              = "^[a-z0-9][a-z0-9-]{0,31}$"
  private val ElementPattern = "^(node|way|relation)/([1-9][0-9]{0,15})$".r

  /** True when the JSON declares itself a choice payload, valid or not. */
  def isChoice(json: JsValue): Boolean =
    (json \ "meta" \ "version").asOpt[Int].contains(2) &&
      (json \ "meta" \ "type").asOpt[Int].contains(CooperativeType)

  private def length(value: String): Int = value.codePointCount(0, value.length)

  def validTag(value: String): Boolean =
    length(value) >= 1 && length(value) <= 255 && !value.exists(Character.isISOControl)

  /** Applies every rule in spec §2 and reports all failures, not just the first. */
  def validate(json: JsValue): Either[List[String], ChoiceWork] = {
    val errors                      = List.newBuilder[String]
    def fail(message: String): Unit = errors += message

    def obj(value: JsValue, path: String, allowed: Set[String]): Option[JsObject] = value match {
      case o: JsObject =>
        o.keys.filterNot(allowed).toList.sorted.foreach(key => fail(s"$path: unknown field '$key'"))
        Some(o)
      case _ => fail(s"$path: must be an object"); None
    }
    def text(o: JsObject, key: String, path: String, max: Int, required: Boolean): Option[String] =
      present(o, key) match {
        case None if !required                                                   => None
        case Some(JsString(value)) if length(value) >= 1 && length(value) <= max => Some(value)
        case _ =>
          fail(s"$path.$key: must be a string of 1 to $max characters"); None
      }
    // JSON null counts as absent for optional fields (match, descriptions, outcomes, tag lists).
    def present(o: JsObject, key: String): Option[JsValue] = (o \ key).toOption.filter(_ != JsNull)
    def id(o: JsObject, path: String): Option[String] =
      (o \ "id").asOpt[String] match {
        case Some(value) if value.matches(IdPattern) => Some(value)
        case _                                       => fail(s"$path.id: must match $IdPattern"); None
      }
    def tagKey(key: String, path: String): Unit =
      if (!validTag(key))
        fail(s"$path: tag key '$key' must be 1 to 255 characters without control characters")
    def tagValue(value: String, path: String): Unit =
      if (!validTag(value))
        fail(s"$path: tag value must be 1 to 255 characters without control characters")
    def unique(ids: Seq[String], path: String): Unit =
      ids.diff(ids.distinct).distinct.foreach(dup => fail(s"$path: duplicate id '$dup'"))

    if (Json.stringify(json).getBytes(StandardCharsets.UTF_8).length > MaxBytes)
      fail(s"payload: larger than $MaxBytes bytes")

    val root =
      obj(json, "cooperativeWork", Set("meta", "element", "match", "questions", "outcomes"))
    val meta = root.flatMap(r =>
      (r \ "meta").toOption match {
        case Some(m) => obj(m, "meta", Set("version", "type", "choiceVersion"))
        case None    => fail("meta: required"); None
      }
    )
    meta.foreach { m =>
      if (!(m \ "version").asOpt[Int].contains(2)) fail("meta.version: must be 2")
      if (!(m \ "type").asOpt[Int].contains(CooperativeType)) fail("meta.type: must be 3")
      if (!(m \ "choiceVersion").asOpt[Int].contains(1)) fail("meta.choiceVersion: must be 1")
    }

    val element = root.flatMap(r =>
      (r \ "element").asOpt[String] match {
        case Some(ElementPattern(kind, number)) => Some(kind -> number.toLong)
        case _ =>
          fail("element: must match (node|way|relation)/<positive id>"); None
      }
    )

    val matchTags: Map[String, String] = root.flatMap(r => present(r, "match")) match {
      case None => Map.empty
      case Some(m: JsObject) =>
        if (m.keys.isEmpty || m.keys.size > 4) fail("match: must have 1 to 4 keys")
        m.fields.flatMap {
          case (key, JsString(value)) =>
            tagKey(key, "match"); tagValue(value, s"match.$key"); Some(key -> value)
          case (key, _) => fail(s"match.$key: must be a string"); None
        }.toMap
      case Some(_) => fail("match: must be an object"); Map.empty
    }

    val questions: List[ChoiceQuestion] = root.flatMap(r => (r \ "questions").toOption) match {
      case Some(JsArray(items)) =>
        if (items.isEmpty || items.size > 8) fail("questions: must have 1 to 8 entries")
        items.zipWithIndex.toList.flatMap {
          case (item, qi) =>
            val path = s"questions[$qi]"
            obj(item, path, Set("id", "prompt", "description", "expect", "options")).flatMap { q =>
              val qid         = id(q, path)
              val prompt      = text(q, "prompt", path, 200, required = true)
              val description = text(q, "description", path, 500, required = false)
              val expect: Map[String, Option[String]] = (q \ "expect").toOption match {
                case Some(e: JsObject) =>
                  if (e.keys.isEmpty || e.keys.size > 4)
                    fail(s"$path.expect: must have 1 to 4 keys")
                  e.fields.flatMap {
                    case (key, JsString(value)) =>
                      tagKey(key, s"$path.expect"); tagValue(value, s"$path.expect.$key")
                      Some(key -> Some(value))
                    case (key, JsNull) => tagKey(key, s"$path.expect"); Some(key -> None)
                    case (key, _) =>
                      fail(s"$path.expect.$key: must be a string or null"); None
                  }.toMap
                case _ => fail(s"$path.expect: must be an object"); Map.empty
              }
              val options: List[ChoiceOption] = (q \ "options").toOption match {
                case Some(JsArray(opts)) =>
                  if (opts.size < 2 || opts.size > 12)
                    fail(s"$path.options: must have 2 to 12 entries")
                  opts.zipWithIndex.toList.flatMap {
                    case (opt, oi) =>
                      val opath = s"$path.options[$oi]"
                      obj(opt, opath, Set("id", "label", "description", "setTags", "unsetTags"))
                        .flatMap { o =>
                          val oid   = id(o, opath)
                          val label = text(o, "label", opath, 60, required = true)
                          val odesc = text(o, "description", opath, 300, required = false)
                          val set: Map[String, String] = present(o, "setTags") match {
                            case None => Map.empty
                            case Some(s: JsObject) =>
                              if (s.keys.isEmpty) fail(s"$opath.setTags: must not be empty")
                              s.fields.flatMap {
                                case (key, JsString(value)) =>
                                  tagKey(key, s"$opath.setTags");
                                  tagValue(value, s"$opath.setTags.$key")
                                  Some(key -> value)
                                case (key, _) =>
                                  fail(s"$opath.setTags.$key: must be a string"); None
                              }.toMap
                            case Some(_) => fail(s"$opath.setTags: must be an object"); Map.empty
                          }
                          val unset: List[String] = present(o, "unsetTags") match {
                            case None => Nil
                            case Some(JsArray(keys)) =>
                              if (keys.isEmpty) fail(s"$opath.unsetTags: must not be empty")
                              keys.toList.flatMap {
                                case JsString(key) => tagKey(key, s"$opath.unsetTags"); Some(key)
                                case _             => fail(s"$opath.unsetTags: must hold strings"); None
                              }
                            case Some(_) => fail(s"$opath.unsetTags: must be an array"); Nil
                          }
                          if (set.isEmpty && unset.isEmpty)
                            fail(s"$opath: needs setTags and/or unsetTags")
                          if (unset.distinct.size != unset.size)
                            fail(s"$opath.unsetTags: duplicate key")
                          (set.keySet & unset.toSet).foreach(key =>
                            fail(s"$opath: key '$key' is both set and unset")
                          )
                          (set.keySet ++ unset)
                            .filterNot(expect.contains)
                            .foreach(key =>
                              fail(s"$opath: key '$key' is not guarded by the question's expect")
                            )
                          val applied = expect ++ set.map { case (k, v) => k -> Some(v) } ++
                            unset.map(_ -> None)
                          if (expect.nonEmpty && applied == expect)
                            fail(s"$opath: equals the expected state (no-op)")
                          for (i <- oid; l <- label) yield ChoiceOption(i, l, odesc, set, unset)
                        }
                  }
                case _ => fail(s"$path.options: must be an array"); Nil
              }
              unique(options.map(_.id), s"$path.options")
              for (i <- qid; p <- prompt) yield ChoiceQuestion(i, p, description, expect, options)
            }
        }
      case _ => fail("questions: must be an array"); Nil
    }
    unique(questions.map(_.id), "questions")
    val expectKeys = questions.flatMap(_.expect.keys)
    expectKeys
      .diff(expectKeys.distinct)
      .distinct
      .foreach(key => fail(s"questions: expect key '$key' is guarded by more than one question"))
    (matchTags.keySet & expectKeys.toSet).foreach(key =>
      fail(s"match: key '$key' overlaps a question's expect")
    )

    val outcomes: List[ChoiceOutcome] = root.flatMap(r => present(r, "outcomes")) match {
      case None => Nil
      case Some(JsArray(items)) =>
        if (items.size > 4) fail("outcomes: must have 0 to 4 entries")
        items.zipWithIndex.toList.flatMap {
          case (item, i) =>
            val path = s"outcomes[$i]"
            obj(item, path, Set("id", "label", "description", "status", "delete")).flatMap { o =>
              val oid = id(o, path)
              if (oid.contains(TooHard)) fail(s"$path.id: '$TooHard' is built in")
              val label       = text(o, "label", path, 60, required = true)
              val description = text(o, "description", path, 300, required = false)
              val status      = (o \ "status").toOption
              val delete      = (o \ "delete").toOption
              val parsed: Option[(Option[Int], Boolean)] = (status, delete) match {
                case (Some(JsNumber(s)), None) if s == 2 || s == 6 => Some(Some(s.toInt) -> false)
                case (None, Some(JsTrue))                          => Some(None          -> true)
                case _ =>
                  fail(s"$path: needs exactly one of status (2 or 6) or delete: true"); None
              }
              for (i <- oid; l <- label; (s, d) <- parsed)
                yield ChoiceOutcome(i, l, description, s, d)
            }
        }
      case Some(_) => fail("outcomes: must be an array"); Nil
    }
    unique(outcomes.map(_.id), "outcomes")
    if (outcomes.count(_.delete) > 1) fail("outcomes: at most one delete outcome")
    if (outcomes.exists(_.delete) && (!element.exists(_._1 == "node") || matchTags.isEmpty))
      fail("outcomes: a delete outcome needs a node element and a match guard")

    errors.result() match {
      case Nil =>
        val (kind, number) = element.get
        Right(ChoiceWork(kind, number, matchTags, questions, outcomes))
      case list => Left(list)
    }
  }
}

/** A choice payload that broke one or more rules; `errors` lists every broken rule. */
class ChoiceValidationException(val errors: List[String])
    extends org.maproulette.exception.InvalidException(
      s"Invalid choice payload: ${errors.mkString("; ")}"
    )

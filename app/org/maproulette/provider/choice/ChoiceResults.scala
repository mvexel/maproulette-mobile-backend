package org.maproulette.provider.choice

import anorm._
import javax.inject.{Inject, Singleton}
import org.maproulette.framework.model.Task
import play.api.db.Database
import play.api.libs.json._

/** One task of a campaign with what was answered and written for it. */
case class ChoiceResultRow(
    taskId: Long,
    name: String,
    element: Option[String],
    lon: Option[Double],
    lat: Option[Double],
    status: Int,
    completedBy: Option[String],
    completedByOsmId: Option[Long],
    completedAt: Option[String],
    changesetId: Option[Long],
    answers: Map[String, String],
    set: Map[String, String],
    unset: Seq[String],
    deleted: Boolean,
    stale: Option[String],
    outcome: Option[String] = None,
    outcomeLabel: Option[String] = None,
    reported: Option[ReportLocation] = None,
    reportedDistanceM: Option[Int] = None,
    reportCount: Int = 0,
    featureName: Option[String] = None,
    featureRef: Option[String] = None,
    pending: Seq[PendingResult] = Nil
)

/** A guest's answer that waits to be published (choice_pending, state pending). No guest id. */
case class PendingResult(
    answers: Map[String, String],
    outcome: Option[String],
    outcomeLabel: Option[String]
)

/**
  * Campaign results for `GET /api/v2/mobile-admin/challenges/:id/results`: every task of a
  * challenge with its status, who completed it and when, its changeset, and from the latest done
  * mobile choice submission the answers (evolution 134) and the applied tag changes.
  */
@Singleton
class ChoiceResultsRepository @Inject() (db: Database) {
  private val parser = for {
    id        <- SqlParser.long("id")
    name      <- SqlParser.str("name")
    element   <- SqlParser.get[Option[String]]("element")
    lon       <- SqlParser.get[Option[Double]]("lon")
    lat       <- SqlParser.get[Option[Double]]("lat")
    status    <- SqlParser.int("status")
    user      <- SqlParser.get[Option[String]]("user_name")
    osmId     <- SqlParser.get[Option[Long]]("osm_id")
    mappedOn  <- SqlParser.get[Option[String]]("mapped_on")
    changeset <- SqlParser.get[Option[Long]]("changeset_id")
    answers   <- SqlParser.get[Option[String]]("answers")
    applied   <- SqlParser.get[Option[String]]("applied")
    stale     <- SqlParser.get[Option[String]]("stale")
    result    <- SqlParser.get[Option[String]]("result")
    payload   <- SqlParser.get[Option[String]]("payload")
    reports   <- SqlParser.int("report_count")
    fName     <- SqlParser.get[Option[String]]("feature_name")
    fRef      <- SqlParser.get[Option[String]]("feature_ref")
    pending   <- SqlParser.get[Option[String]]("pending")
  } yield {
    val appliedJson = applied.map(Json.parse)
    val resultJson  = result.map(Json.parse)
    val outcome     = resultJson.flatMap(j => (j \ "outcome").asOpt[String])
    ChoiceResultRow(
      id,
      name,
      element,
      lon,
      lat,
      status,
      user,
      osmId,
      mappedOn,
      changeset.filter(_ > 0),
      answers.map(Json.parse(_).as[Map[String, String]]).getOrElse(Map.empty),
      appliedJson.flatMap(j => (j \ "set").asOpt[Map[String, String]]).getOrElse(Map.empty),
      appliedJson.flatMap(j => (j \ "unset").asOpt[Seq[String]]).getOrElse(Seq.empty),
      appliedJson.flatMap(j => (j \ "deleted").asOpt[Boolean]).getOrElse(false),
      stale,
      outcome,
      outcome.map(id => ChoiceResults.outcomeLabel(id, payload.map(Json.parse))),
      resultJson.flatMap(j => (j \ "location").toOption).flatMap { l =>
        for {
          lat    <- (l \ "lat").asOpt[Double]
          lon    <- (l \ "lon").asOpt[Double]
          source <- (l \ "source").asOpt[String]
        } yield ReportLocation(lat, lon, source)
      },
      resultJson.flatMap(j => (j \ "distanceM").asOpt[Int]),
      reports,
      fName.filter(_.trim.nonEmpty),
      fRef.filter(_.trim.nonEmpty),
      pending.toSeq.flatMap(Json.parse(_).as[Seq[JsObject]]).map { p =>
        val outcome = (p \ "outcome").asOpt[String]
        PendingResult(
          (p \ "answers").asOpt[Map[String, String]].getOrElse(Map.empty),
          outcome,
          outcome.map(id => ChoiceResults.outcomeLabel(id, payload.map(Json.parse)))
        )
      }
    )
  }

  /** None when the challenge does not exist. */
  def results(challengeId: Long): Option[Seq[ChoiceResultRow]] = db.withConnection { implicit c =>
    val exists = SQL"SELECT EXISTS(SELECT 1 FROM challenges WHERE id = $challengeId)"
      .as(SqlParser.scalar[Boolean].single)
    if (!exists) None
    else
      Some(
        SQL"""SELECT t.id, t.name, t.status, t.changeset_id,
                  t.cooperative_work_json->>'element' AS element,
                  ST_X(t.location) AS lon, ST_Y(t.location) AS lat,
                  to_json(t.mapped_on)#>>'{}' AS mapped_on,
                  u.name AS user_name, u.osm_id::bigint AS osm_id,
                  s.answers::text AS answers, (s.result_json->'applied')::text AS applied,
                  s.result_json::text AS result, t.cooperative_work_json::text AS payload,
                  (SELECT count(*) FROM mobile_choice_submissions r WHERE r.task_id = t.id
                    AND r.state = 'done' AND r.result_json->>'outcome' IN ('not-here', 'moved'))
                  + (SELECT count(*) FROM choice_pending p WHERE p.task_id = t.id
                    AND p.state = 'pending' AND p.body->>'outcome' IN ('not-here', 'moved'))
                    AS report_count,
                  st.reason AS stale,
                  t.geojson #>> '{features,0,properties,name}' AS feature_name,
                  t.geojson #>> '{features,0,properties,ref}' AS feature_ref,
                  (SELECT json_agg(json_build_object('answers', p.body->'answers',
                      'outcome', p.body->>'outcome') ORDER BY p.id)::text
                    FROM choice_pending p WHERE p.task_id = t.id AND p.state = 'pending')
                    AS pending
                FROM tasks t
                LEFT JOIN users u ON u.id = t.completed_by
                LEFT JOIN LATERAL (
                  SELECT answers, result_json FROM mobile_choice_submissions
                  WHERE task_id = t.id AND state = 'done'
                  ORDER BY updated_at DESC LIMIT 1
                ) s ON true
                LEFT JOIN choice_stale st ON st.task_id = t.id
                WHERE t.parent_id = $challengeId
                ORDER BY t.id""".as(parser.*)
      )
  }
}

object ChoiceResults {

  /** The built-in reports' labels, or the label a campaign outcome declares in its payload. */
  def outcomeLabel(id: String, payload: Option[JsValue]): String = id match {
    case ChoiceWork.NotHere => "It's not here"
    case ChoiceWork.Moved   => "It's moved"
    case ChoiceWork.TooHard => "I can't check this"
    case other =>
      payload
        .flatMap(p => (p \ "outcomes").asOpt[Seq[JsObject]])
        .flatMap(_.find(o => (o \ "id").asOpt[String].contains(other)))
        .flatMap(o => (o \ "label").asOpt[String])
        .getOrElse(other)
  }

  private def statusName(status: Int): String = Task.getStatusName(status).getOrElse("")
  private def pairs(tags: Map[String, String]): String =
    tags.toSeq.sorted.map { case (k, v) => s"$k=$v" }.mkString(";")

  /** Question ids answered anywhere in the campaign, in a stable order. */
  private def questionIds(rows: Seq[ChoiceResultRow]): Seq[String] =
    rows.flatMap(_.answers.keys).distinct.sorted

  /** Question ids guests answered in still-waiting answers, in a stable order. */
  private def pendingQuestionIds(rows: Seq[ChoiceResultRow]): Seq[String] =
    rows.flatMap(_.pending.flatMap(_.answers.keys)).distinct.sorted

  /** "hinged 2;sliding 1": each value with how many waiting answers gave it, most first. */
  def tally(values: Seq[String]): String =
    values
      .groupBy(identity)
      .toSeq
      .sortBy { case (value, all) => (-all.size, value) }
      .map { case (value, all) => s"$value ${all.size}" }
      .mkString(";")

  /** RFC 4180 quoting; text a spreadsheet would run as a formula gets a leading apostrophe. */
  private def text(value: String): String = {
    val safe = if (value.headOption.exists("=+-@\t\r".contains(_))) s"'$value" else value
    if (safe.exists(",\"\r\n".contains(_))) "\"" + safe.replace("\"", "\"\"") + "\"" else safe
  }

  /** The note at the top of every export (i16): it names people. */
  val Notice = "Contains OSM usernames; don't publish this file."

  /** How many of the export's stops each person completed, keyed by OSM id (i16). */
  def completedCounts(rows: Seq[ChoiceResultRow]): Map[Long, Int] =
    rows.flatMap(_.completedByOsmId).groupBy(identity).map { case (id, ids) => id -> ids.size }

  def csv(rows: Seq[ChoiceResultRow]): String = {
    val questions = questionIds(rows)
    val waiting   = pendingQuestionIds(rows)
    val counts    = completedCounts(rows)
    val header = Seq(
      "task_id",
      "task_name",
      "element",
      "lon",
      "lat",
      "status",
      "status_name",
      "completed_by",
      "completed_by_osm_id",
      "completed_by_count",
      "completed_at",
      "changeset_id",
      "stale_reason",
      "deleted",
      "tags_set",
      "tags_unset",
      "outcome",
      "outcome_label",
      "reported_lat",
      "reported_lon",
      "reported_source",
      "reported_distance_m",
      "report_count"
    ) ++ questions.map(q => text(s"answer:$q")) ++ Seq("pending_count", "pending_outcomes") ++
      waiting.map(q => text(s"pending:$q"))
    val lines = rows.map { row =>
      Seq(
        row.taskId.toString,
        text(row.name),
        text(row.element.getOrElse("")),
        row.lon.map(_.toString).getOrElse(""),
        row.lat.map(_.toString).getOrElse(""),
        row.status.toString,
        statusName(row.status),
        text(row.completedBy.getOrElse("")),
        row.completedByOsmId.map(_.toString).getOrElse(""),
        row.completedByOsmId.flatMap(counts.get).map(_.toString).getOrElse(""),
        row.completedAt.getOrElse(""),
        row.changesetId.map(_.toString).getOrElse(""),
        row.stale.getOrElse(""),
        row.deleted.toString,
        text(pairs(row.set)),
        text(row.unset.mkString(";")),
        text(row.outcome.getOrElse("")),
        text(row.outcomeLabel.getOrElse("")),
        row.reported.map(_.lat.toString).getOrElse(""),
        row.reported.map(_.lon.toString).getOrElse(""),
        row.reported.map(_.source).getOrElse(""),
        row.reportedDistanceM.map(_.toString).getOrElse(""),
        row.reportCount.toString
      ) ++ questions.map(q => text(row.answers.getOrElse(q, ""))) ++ Seq(
        row.pending.size.toString,
        text(tally(row.pending.flatMap(_.outcomeLabel)))
      ) ++ waiting.map(q => text(tally(row.pending.flatMap(_.answers.get(q)))))
    }
    (Seq(s"# $Notice") +: header +: lines).map(_.mkString(",")).mkString("", "\r\n", "\r\n")
  }

  /** For a `moved` report, a second point at the reported spot, so both load into an editor. */
  private def reportedFeature(row: ChoiceResultRow): Option[JsObject] = row.reported.map { at =>
    Json.obj(
      "type"     -> "Feature",
      "id"       -> s"${row.taskId}-reported",
      "geometry" -> Json.obj("type" -> "Point", "coordinates" -> Json.arr(at.lon, at.lat)),
      "properties" -> Json.obj(
        "kind"      -> "reported_position",
        "taskId"    -> row.taskId,
        "element"   -> row.element,
        "source"    -> at.source,
        "distanceM" -> row.reportedDistanceM
      )
    )
  }

  def geojson(rows: Seq[ChoiceResultRow]): JsObject = {
    val counts = completedCounts(rows)
    Json.obj(
      "type"   -> "FeatureCollection",
      "notice" -> Notice,
      "features" -> rows.flatMap { row =>
        val geometry = (row.lon, row.lat) match {
          case (Some(lon), Some(lat)) =>
            Json.obj("type" -> "Point", "coordinates" -> Json.arr(lon, lat))
          case _ => JsNull
        }
        Json.obj(
          "type"     -> "Feature",
          "id"       -> row.taskId,
          "geometry" -> geometry,
          "properties" -> Json.obj(
            "taskId"           -> row.taskId,
            "taskName"         -> row.name,
            "featureName"      -> row.featureName,
            "featureRef"       -> row.featureRef,
            "element"          -> row.element,
            "status"           -> row.status,
            "statusName"       -> statusName(row.status),
            "completedBy"      -> row.completedBy,
            "completedByOsmId" -> row.completedByOsmId,
            "completedByCount" -> row.completedByOsmId.flatMap(counts.get),
            "completedAt"      -> row.completedAt,
            "changesetId"      -> row.changesetId,
            "staleReason"      -> row.stale,
            "answers"          -> row.answers,
            "tagsSet"          -> row.set,
            "tagsUnset"        -> row.unset,
            "deleted"          -> row.deleted,
            "outcome"          -> row.outcome,
            "outcomeLabel"     -> row.outcomeLabel,
            "reportCount"      -> row.reportCount,
            "pending" -> row.pending.map(p =>
              Json.obj(
                "answers"      -> p.answers,
                "outcome"      -> p.outcome,
                "outcomeLabel" -> p.outcomeLabel
              )
            )
          )
        ) +: reportedFeature(row).toSeq
      }
    )
  }
}

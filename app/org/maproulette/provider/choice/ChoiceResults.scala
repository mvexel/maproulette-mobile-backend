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
    stale: Option[String]
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
  } yield {
    val appliedJson = applied.map(Json.parse)
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
      stale
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
                  st.reason AS stale
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
  private def statusName(status: Int): String = Task.getStatusName(status).getOrElse("")
  private def pairs(tags: Map[String, String]): String =
    tags.toSeq.sorted.map { case (k, v) => s"$k=$v" }.mkString(";")

  /** Question ids answered anywhere in the campaign, in a stable order. */
  private def questionIds(rows: Seq[ChoiceResultRow]): Seq[String] =
    rows.flatMap(_.answers.keys).distinct.sorted

  /** RFC 4180 quoting; text a spreadsheet would run as a formula gets a leading apostrophe. */
  private def text(value: String): String = {
    val safe = if (value.headOption.exists("=+-@\t\r".contains(_))) s"'$value" else value
    if (safe.exists(",\"\r\n".contains(_))) "\"" + safe.replace("\"", "\"\"") + "\"" else safe
  }

  def csv(rows: Seq[ChoiceResultRow]): String = {
    val questions = questionIds(rows)
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
      "completed_at",
      "changeset_id",
      "stale_reason",
      "deleted",
      "tags_set",
      "tags_unset"
    ) ++ questions.map(q => text(s"answer:$q"))
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
        row.completedAt.getOrElse(""),
        row.changesetId.map(_.toString).getOrElse(""),
        row.stale.getOrElse(""),
        row.deleted.toString,
        text(pairs(row.set)),
        text(row.unset.mkString(";"))
      ) ++ questions.map(q => text(row.answers.getOrElse(q, "")))
    }
    (header +: lines).map(_.mkString(",")).mkString("", "\r\n", "\r\n")
  }

  def geojson(rows: Seq[ChoiceResultRow]): JsObject = Json.obj(
    "type" -> "FeatureCollection",
    "features" -> rows.map { row =>
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
          "element"          -> row.element,
          "status"           -> row.status,
          "statusName"       -> statusName(row.status),
          "completedBy"      -> row.completedBy,
          "completedByOsmId" -> row.completedByOsmId,
          "completedAt"      -> row.completedAt,
          "changesetId"      -> row.changesetId,
          "staleReason"      -> row.stale,
          "answers"          -> row.answers,
          "tagsSet"          -> row.set,
          "tagsUnset"        -> row.unset,
          "deleted"          -> row.deleted
        )
      )
    }
  )
}

package org.maproulette.provider.choice

import anorm._
import anorm.SqlParser.{double, long, str}
import javax.inject.{Inject, Singleton}
import org.maproulette.Config
import play.api.db.Database
import play.api.libs.json.Json
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future, blocking}

/** One set's counts in an area: the elements it selects, and those with a question left to ask. */
case class SetCount(open: Long, all: Long)

/**
  * Where the campaign wizard reads OSM. [[WizardOverpass]] asks Overpass; [[WizardExtract]] reads
  * the nightly extract in PostGIS. [[WizardSources]] picks the extract when it covers the area.
  */
trait WizardSource {

  /** Every set's counts, in the order given. */
  def counts(
      sets: Seq[QuestionSet],
      area: SearchArea,
      userId: Long
  ): Future[Either[WizardQueryFailure, List[SetCount]]]

  /** The set's counts and up to `limit` of its open features. */
  def features(
      set: QuestionSet,
      area: SearchArea,
      limit: Int,
      userId: Long,
      cached: Boolean
  ): Future[Either[WizardQueryFailure, OpenFeatures]]
}

/**
  * The wizard's OSM elements from the nightly extract (architecture/wizard-parquet.md, Sprint 4):
  * `wizard_osm_features` holds every element the sets' filters could pick, with full tags, and
  * `wizard_osm_extract` says when the extract was cut and what it covers. Both come from
  * scripts/wizard-extract/load.sh, which swaps the table in one transaction. The sets' filters
  * and questions run here in Scala, as they re-check Overpass answers, so both sources agree.
  */
@Singleton
class WizardExtract @Inject() (db: Database, config: Config)(implicit ec: ExecutionContext) {

  /** An extract older than this is not used (`mobileWizard.extractMaxAge`, default 48 hours). */
  protected def maxAge: FiniteDuration =
    Option(config.config)
      .flatMap(_.getOptional[FiniteDuration]("mobileWizard.extractMaxAge"))
      .getOrElse(48.hours)

  /**
    * The elements in the area that any of the sets could select, nearest first, or None when no
    * fresh extract covers the whole area.
    */
  def within(sets: Seq[QuestionSet], area: SearchArea): Future[Option[List[SetFeature]]] =
    Future(blocking(db.withConnection { implicit c =>
      val covered =
        SQL"""SELECT 1 FROM wizard_osm_extract
              WHERE extract_at > NOW() - (${maxAge.toSeconds} * INTERVAL '1 second')
                AND ST_Covers(coverage, ST_Buffer(
                  ST_SetSRID(ST_MakePoint(${area.lon}, ${area.lat}), 4326)::geography,
                  ${area.radius})::geometry)"""
          .as(SqlParser.scalar[Int].singleOpt)
          .isDefined
      if (!covered) None
      else {
        val kinds = sets.flatMap(_.filter.elements).distinct.toList
        val keys  = sets.flatMap(_.filter.tags.keys).distinct.toList
        val parser = (str("kind") ~ long("osm_id") ~ double("lon") ~ double("lat") ~ str("tags"))
          .map {
            case kind ~ id ~ lon ~ lat ~ tags =>
              SetFeature(kind, id, lon, lat, Json.parse(tags).as[Map[String, String]])
          }
        Some(
          SQL"""WITH centre AS (
                     SELECT ST_SetSRID(ST_MakePoint(${area.lon}, ${area.lat}), 4326)::geography AS g)
                   SELECT f.kind, f.osm_id, ST_X(f.geom) AS lon, ST_Y(f.geom) AS lat,
                     f.tags::text AS tags
                   FROM wizard_osm_features f, centre
                   WHERE ST_DWithin(f.geom::geography, centre.g, ${area.radius})
                     AND f.kind IN ($kinds)
                     AND jsonb_exists_any(f.tags, ARRAY[$keys]::text[])
                   ORDER BY ST_Distance(f.geom::geography, centre.g), f.kind, f.osm_id"""
            .as(parser.*)
        )
      }
    }))

  def counts(sets: Seq[QuestionSet], area: SearchArea): Future[Option[List[SetCount]]] =
    within(sets, area).map(_.map { found =>
      sets.toList.map { set =>
        SetCount(
          found.count(f => set.isOpen(f.kind, f.tags)).toLong,
          found.count(f => set.filter.selects(f.kind, f.tags)).toLong
        )
      }
    })

  def features(set: QuestionSet, area: SearchArea, limit: Int): Future[Option[OpenFeatures]] =
    within(Seq(set), area).map(_.map { found =>
      val open = found.filter(f => set.isOpen(f.kind, f.tags))
      OpenFeatures(
        found.count(f => set.filter.selects(f.kind, f.tags)).toLong,
        open.size.toLong,
        open.take(limit)
      )
    })
}

/**
  * The wizard's source: the extract where a fresh one covers the area, Overpass everywhere else
  * and whenever the extract can't be read.
  */
@Singleton
class WizardSources @Inject() (extract: WizardExtract, overpass: WizardOverpass)(
    implicit ec: ExecutionContext
) extends WizardSource {
  private val logger = play.api.Logger(getClass)

  private def orOverpass[A](read: => Future[Option[A]])(
      fallback: => Future[Either[WizardQueryFailure, A]]
  ): Future[Either[WizardQueryFailure, A]] =
    read
      .recover {
        case e: Exception =>
          logger.warn(s"Wizard extract not readable, asking Overpass: ${e.getMessage}")
          None
      }
      .flatMap {
        case Some(answer) => Future.successful(Right(answer))
        case None         => fallback
      }

  def counts(
      sets: Seq[QuestionSet],
      area: SearchArea,
      userId: Long
  ): Future[Either[WizardQueryFailure, List[SetCount]]] =
    orOverpass(extract.counts(sets, area))(overpass.counts(sets, area, userId))

  def features(
      set: QuestionSet,
      area: SearchArea,
      limit: Int,
      userId: Long,
      cached: Boolean
  ): Future[Either[WizardQueryFailure, OpenFeatures]] =
    orOverpass(extract.features(set, area, limit))(
      overpass.features(set, area, limit, userId, cached)
    )
}

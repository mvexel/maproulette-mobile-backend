package org.maproulette.provider.choice

import com.github.blemale.scaffeine.{Cache, Scaffeine}
import java.util.concurrent.{ConcurrentHashMap, Semaphore}
import javax.inject.{Inject, Singleton}
import org.maproulette.Config
import play.api.libs.json.{JsArray, Json}
import play.api.libs.ws.WSClient
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

/** Why a wizard query has no answer: `rate_limited` (429) or `osm_unavailable` (503). */
case class WizardQueryFailure(code: String)

/**
  * Overpass for the campaign wizard (organizers-feasibility-scala.md §c): at most two queries in
  * flight server-wide, 20 per minute per user, a 10 s timeout, and answers cached for an hour by
  * query text. The create step skips the cache, so it reads OSM as it is now.
  */
@Singleton
class WizardOverpass @Inject() (ws: WSClient, config: Config)(implicit ec: ExecutionContext) {
  protected def url: String                    = config.getOSMQLProvider.providerURL
  protected def requestTimeout: FiniteDuration = (OverpassQuery.Timeout + 2).seconds
  private val userAgent =
    "Street Tally campaign wizard (+https://github.com/mvexel/maproulette-mobile-backend)"
  private val PerMinute = 20
  private val inFlight  = new Semaphore(2)
  private val calls     = new ConcurrentHashMap[Long, List[Long]]()
  private val cache: Cache[String, JsArray] =
    Scaffeine().expireAfterWrite(1.hour).maximumSize(2000).build[String, JsArray]()

  /** The current time in milliseconds; tests move it. */
  protected def now(): Long = System.currentTimeMillis()

  private def allowed(userId: Long): Boolean = {
    var ok = false
    calls.compute(
      userId,
      (_, previous) => {
        val recent = Option(previous).getOrElse(Nil).filter(_ > now() - 60000)
        ok = recent.size < PerMinute
        if (ok) now() :: recent else recent
      }
    )
    ok
  }

  /**
    * One line saying whether Overpass answers from this server, for the deploy log (Rex reads it
    * after each deploy; no container shell needed). Its status endpoint, 5 s, no retry.
    */
  def probe(): Future[String] = {
    val status  = url.replaceFirst("/interpreter$", "/status")
    val started = System.nanoTime()
    def ms      = (System.nanoTime() - started) / 1000000
    ws.url(status)
      .withRequestTimeout(5.seconds)
      .withHttpHeaders("User-Agent" -> userAgent)
      .get()
      .map(response => s"Overpass answered: HTTP ${response.status} in $ms ms ($status)")
      .recover {
        case e: Exception =>
          s"Overpass not reachable: ${e.getClass.getSimpleName} after $ms ms ($status)"
      }
  }

  /** The query's `elements`, from the cache when `cached` and present. */
  def elements(
      query: String,
      userId: Long,
      cached: Boolean = true
  ): Future[Either[WizardQueryFailure, JsArray]] =
    cache.getIfPresent(query).filter(_ => cached) match {
      case Some(hit) => Future.successful(Right(hit))
      case None if !allowed(userId) || !inFlight.tryAcquire() =>
        Future.successful(Left(WizardQueryFailure("rate_limited")))
      case None =>
        ws.url(url)
          .withRequestTimeout(requestTimeout)
          .withHttpHeaders("User-Agent" -> userAgent)
          .post(Map("data" -> Seq(query)))
          .map { response =>
            val parsed =
              if (response.status != 200) None
              else Try(Json.parse(response.body)).toOption
            // Overpass answers 200 with a `remark` when the query ran out of time or memory.
            parsed
              .filter(json => (json \ "remark").asOpt[String].forall(r => !r.contains("rror")))
              .flatMap(json => (json \ "elements").asOpt[JsArray]) match {
              case Some(found) =>
                cache.put(query, found)
                Right(found)
              case None => Left(WizardQueryFailure("osm_unavailable"))
            }
          }
          .recover {
            case _: java.util.concurrent.TimeoutException => Left(WizardQueryFailure("osm_timeout"))
            case _: Exception                             => Left(WizardQueryFailure("osm_unavailable"))
          }
          .andThen { case _ => inFlight.release() }
    }
}

/** Logs [[WizardOverpass.probe]] once at startup. */
@Singleton
class WizardOverpassProbe @Inject() (overpass: WizardOverpass)(implicit ec: ExecutionContext) {
  private val logger = play.api.Logger(getClass)
  overpass.probe().foreach(line => logger.info(line))
}

class WizardOverpassModule
    extends play.api.inject.SimpleModule(play.api.inject.bind[WizardOverpassProbe].toSelf.eagerly())

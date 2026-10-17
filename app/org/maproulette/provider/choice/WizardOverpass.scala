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

/** Why a wizard query has no answer: `rate_limited` (429), `osm_timeout` (504) or `osm_unavailable` (502). */
case class WizardQueryFailure(code: String)

/**
  * Overpass for the campaign wizard (organizers-feasibility-scala.md §c): at most two queries in
  * flight server-wide, 20 per minute per user, a 10 s timeout, and answers cached for an hour by
  * query text. The create step skips the cache, so it reads OSM as it is now. Endpoints come from
  * `mobileWizard.overpassUrls` (`OVERPASS_URLS`, comma-separated) and are tried in order, the next
  * one only when connecting fails; a timeout is the answer (504), never retried elsewhere.
  */
@Singleton
class WizardOverpass @Inject() (ws: WSClient, config: Config)(implicit ec: ExecutionContext)
    extends WizardSource {
  protected def urls: Seq[String] =
    WizardOverpass.parseUrls(config.config.getOptional[String]("mobileWizard.overpassUrls"))
  protected def requestTimeout: FiniteDuration = (OverpassQuery.Timeout + 2).seconds
  private val userAgent                        = WizardOverpass.UserAgent
  private val PerMinute                        = 20
  private val inFlight                         = new Semaphore(2)
  private val calls                            = new ConcurrentHashMap[Long, List[Long]]()
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
    * One line per endpoint saying whether Overpass answers from this server, with the addresses its
    * host resolves to, for the deploy log (Rex reads it after each deploy; no container shell
    * needed). A tiny real query to the endpoint itself, as the wizard asks (not every instance
    * serves `/status`), 5 s, no retry.
    */
  def probe(): Future[Seq[String]] = Future.sequence(urls.map(probe))

  private def probe(url: String): Future[String] =
    Future(WizardOverpass.resolved(url)).flatMap { addresses =>
      val started = System.nanoTime()
      def ms      = (System.nanoTime() - started) / 1000000
      ws.url(url)
        .withRequestTimeout(5.seconds)
        .withHttpHeaders("User-Agent" -> userAgent)
        .post(Map("data" -> Seq(WizardOverpass.ProbeQuery)))
        .map { response =>
          // The server's own error, for anything but 200: Overpass explains itself in the body.
          val said =
            if (response.status == 200) ""
            else s": ${response.body.replaceAll("\\s+", " ").trim.take(300)}"
          s"Overpass answered: HTTP ${response.status} in $ms ms ($url, $addresses)$said"
        }
        .recover {
          case e: Exception =>
            s"Overpass not reachable: ${e.getClass.getSimpleName} after $ms ms ($url, $addresses)"
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
        post(urls, query)
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

  /** Two count elements per set, open then all; anything else is `osm_unavailable`. */
  def counts(
      sets: Seq[QuestionSet],
      area: SearchArea,
      userId: Long
  ): Future[Either[WizardQueryFailure, List[SetCount]]] =
    elements(OverpassQuery.counts(sets, area), userId).map(_.flatMap { found =>
      found.value.flatMap(OverpassQuery.total).toList match {
        case totals if totals.size == 2 * sets.size =>
          Right(totals.grouped(2).map(p => SetCount(p(0), p(1))).toList)
        case _ => Left(WizardQueryFailure("osm_unavailable"))
      }
    })

  def features(
      set: QuestionSet,
      area: SearchArea,
      limit: Int,
      userId: Long,
      cached: Boolean
  ): Future[Either[WizardQueryFailure, OpenFeatures]] =
    elements(OverpassQuery.features(set, area, limit), userId, cached)
      .map(_.flatMap { found =>
        found.value.flatMap(OverpassQuery.total).toList match {
          case all :: open :: _ =>
            // Re-checked here: only what the set would pick, nearest-first is the admin's job.
            val picked =
              found.value.flatMap(OverpassQuery.feature).filter(f => set.isOpen(f.kind, f.tags))
            Right(OpenFeatures(all, open, picked.toList.distinctBy(_.element)))
          case _ => Left(WizardQueryFailure("osm_unavailable"))
        }
      })

  /** Posts to the first endpoint that accepts the connection. */
  private def post(endpoints: Seq[String], query: String): Future[play.api.libs.ws.WSResponse] =
    ws.url(endpoints.head)
      .withRequestTimeout(requestTimeout)
      .withHttpHeaders("User-Agent" -> userAgent)
      .post(Map("data" -> Seq(query)))
      .recoverWith {
        case e if endpoints.size > 1 && WizardOverpass.connectFailure(e) =>
          post(endpoints.tail, query)
      }
}

object WizardOverpass {

  /** Named with a contact on every request: public instances refuse generic clients. */
  val UserAgent: String = {
    val build = org.maproulette.models.service.info
      .BuildInfo()
      .gitHeadCommit
      .map(_.take(7))
      .getOrElse("dev")
    s"StreetTally/$build (+https://streettally.osm.lol; martijn@osm.lol)"
  }

  /** The startup probe's query: one node's id, a few bytes back. */
  val ProbeQuery = "[out:json][timeout:5];node(1);out ids;"

  val DefaultUrls: Seq[String] = Seq(
    "https://overpass-api.de/api/interpreter",
    "https://maps.mail.ru/osm/tools/overpass/api/interpreter",
    "https://overpass.private.coffee/api/interpreter"
  )

  def parseUrls(value: Option[String]): Seq[String] =
    value.toSeq.flatMap(_.split(",")).map(_.trim).filter(_.nonEmpty) match {
      case Nil  => DefaultUrls
      case some => some
    }

  /** Connecting failed (refused, unreachable, unknown host): nothing was asked, so try the next. */
  def connectFailure(e: Throwable): Boolean = e match {
    case _: java.net.ConnectException                    => true
    case _: java.net.UnknownHostException                => true
    case _: java.nio.channels.UnresolvedAddressException => true
    case _                                               => false
  }

  /** `resolves to 1.2.3.4 (IPv4), 2a01:4f8::1 (IPv6)`, or why not. */
  def resolved(url: String): String =
    Try(new java.net.URI(url).getHost)
      .flatMap(host => Try(java.net.InetAddress.getAllByName(host).toSeq))
      .map(_.map {
        case a: java.net.Inet6Address => s"${a.getHostAddress} (IPv6)"
        case a                        => s"${a.getHostAddress} (IPv4)"
      })
      .fold(
        e => s"unresolved: ${e.getClass.getSimpleName}",
        a => s"resolves to ${a.mkString(", ")}"
      )
}

/** Logs [[WizardOverpass.probe]] once at startup. */
@Singleton
class WizardOverpassProbe @Inject() (overpass: WizardOverpass)(implicit ec: ExecutionContext) {
  private val logger = play.api.Logger(getClass)
  overpass.probe().foreach(_.foreach(line => logger.info(line)))
}

class WizardOverpassModule
    extends play.api.inject.SimpleModule(
      play.api.inject.bind[WizardSource].to[WizardSources],
      play.api.inject.bind[WizardOverpassProbe].toSelf.eagerly(),
      play.api.inject.bind[WizardParquetProbe].toSelf.eagerly()
    )

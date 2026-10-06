package org.maproulette.provider.choice

import javax.inject.{Inject, Singleton}
import org.maproulette.Config
import play.api.libs.ws.{WSClient, WSRequest, WSResponse}
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try
import scala.xml.{Elem, Node, Null, Text, XML}

/** A fresh (uncached) element read: gone, or the full element with its version and tags. */
sealed trait ElementRead
case object ElementGone                                                          extends ElementRead
case class ElementFound(element: Elem, version: Long, tags: Map[String, String]) extends ElementRead

/**
  * An OSM response this flow cannot use. `status` is 0 for transport failures and timeouts, with
  * the original exception as the cause. `detail` is an excerpt of OSM's answer, never a token.
  */
case class OsmCallException(status: Int, detail: String, cause: Throwable = null)
    extends Exception(s"OSM call failed ($status): $detail", cause)

/**
  * Direct OSM API 0.6 calls for choice tasks. Element reads bypass the object cache that the
  * legacy tag-fix flow uses. Tokens are sent only in the Authorization header and never logged.
  */
@Singleton
class ChoiceOsmClient @Inject() (ws: WSClient, config: Config)(implicit ec: ExecutionContext) {
  protected def baseUrl: String = config.getOSMServer
  private val userAgent =
    "MapRoulette mobile choice tasks (+https://github.com/mvexel/maproulette-mobile-backend)"

  private def request(path: String, token: Option[String]): WSRequest = {
    val base = ws
      .url(s"$baseUrl/api/0.6/$path")
      .withFollowRedirects(false)
      .withRequestTimeout(20.seconds)
      .withHttpHeaders("User-Agent" -> userAgent)
    token.fold(base)(value => base.addHttpHeaders("Authorization" -> s"Bearer $value"))
  }
  private def failed(response: WSResponse): OsmCallException =
    OsmCallException(response.status, response.body.take(200))
  private def transport[A](call: Future[A]): Future[A] = call.recoverWith {
    case e: OsmCallException => Future.failed(e)
    case e: Exception        => Future.failed(OsmCallException(0, e.getClass.getSimpleName, e))
  }
  private def tags(element: Node): Map[String, String] =
    (element \ "tag").map(tag => (tag \@ "k") -> (tag \@ "v")).toMap

  def element(kind: String, id: Long, token: Option[String]): Future[ElementRead] = transport {
    request(s"$kind/$id", token).get().map { response =>
      response.status match {
        case 200 =>
          val parsed = Try(XML.loadString(response.body)).getOrElse(throw failed(response))
          (parsed \ kind).headOption match {
            case Some(found: Elem) if (found \@ "visible") != "false" =>
              val version = Try((found \@ "version").toLong).getOrElse(throw failed(response))
              ElementFound(found, version, tags(found))
            case Some(_) => ElementGone
            case None    => throw failed(response)
          }
        case 410 => ElementGone
        // OSM answers 404 for an id that never existed. An HTML 404 is a proxy or a wrong
        // MR_OSM_SERVER, which must not mark tasks stale.
        case 404 if !response.contentType.toLowerCase.contains("html") => ElementGone
        case _                                                         => throw failed(response)
      }
    }
  }

  /** True when a way or relation uses the node, which then may not be deleted. */
  def nodeInUse(id: Long, token: Option[String]): Future[Boolean] = transport {
    def parents(kind: String): Future[Boolean] =
      request(s"node/$id/$kind", token).get().map { response =>
        if (response.status != 200) throw failed(response)
        val parsed = Try(XML.loadString(response.body)).getOrElse(throw failed(response))
        (parsed \ "way").nonEmpty || (parsed \ "relation").nonEmpty
      }
    for { ways <- parents("ways"); relations <- parents("relations") } yield ways || relations
  }

  def createChangeset(tags: Seq[(String, String)], token: String): Future[Long] = transport {
    val body =
      <osm><changeset>{tags.map { case (k, v) => <tag k={k} v={v}/> }}</changeset></osm>
    request("changeset/create", Some(token))
      .addHttpHeaders("Content-Type" -> "text/xml; charset=utf-8")
      .put(body.toString)
      .map { response =>
        if (response.status != 200) throw failed(response)
        Try(response.body.trim.toLong).getOrElse(throw failed(response))
      }
  }

  /** Returns the HTTP status and an excerpt of OSM's answer; the caller maps the status. */
  def upload(changesetId: Long, change: Elem, token: String): Future[(Int, String)] = transport {
    request(s"changeset/$changesetId/upload", Some(token))
      .addHttpHeaders("Content-Type" -> "text/xml; charset=utf-8")
      .post(change.toString)
      .map(response => response.status -> response.body.take(300))
  }

  def close(changesetId: Long, token: String): Future[Unit] = transport {
    request(s"changeset/$changesetId/close", Some(token)).put("").map { response =>
      // 409: already closed.
      if (response.status != 200 && response.status != 409) throw failed(response)
    }
  }

  def changesCount(changesetId: Long, token: Option[String]): Future[Int] = transport {
    request(s"changeset/$changesetId", token).get().map { response =>
      if (response.status != 200) throw failed(response)
      val parsed = Try(XML.loadString(response.body)).getOrElse(throw failed(response))
      Try(((parsed \ "changeset").head \@ "changes_count").toInt).getOrElse(throw failed(response))
    }
  }
}

object ChoiceOsmClient {

  /** `<modify>` with the fetched element, its version and geometry, and the merged tags. */
  def modify(found: ElementFound, newTags: Map[String, String], changesetId: Long): Elem = {
    val element  = found.element
    val kept     = element.child.filter(node => node.label == "nd" || node.label == "member")
    val tagNodes = newTags.toSeq.sortBy(_._1).map { case (k, v) => <tag k={k} v={v}/> }
    <osmChange version="0.6" generator="MapRoulette">
      <modify>{withCore(element, changesetId).copy(child = kept ++ tagNodes)}</modify>
    </osmChange>
  }

  /** `<delete>` of a node at the fetched version (no if-unused, so problems surface as errors). */
  def delete(found: ElementFound, changesetId: Long): Elem =
    <osmChange version="0.6" generator="MapRoulette">
      <delete>{withCore(found.element, changesetId).copy(child = Nil)}</delete>
    </osmChange>

  /** Keeps only id, version and (for nodes) lat/lon, and sets the changeset. */
  private def withCore(element: Elem, changesetId: Long): Elem = {
    val keep = Seq("id", "version", "lat", "lon").flatMap(key =>
      Option(element \@ key).filter(_.nonEmpty).map(key -> _)
    ) :+ ("changeset" -> changesetId.toString)
    val attributes = keep.reverse.foldLeft(Null: scala.xml.MetaData) {
      case (next, (key, value)) => new scala.xml.UnprefixedAttribute(key, Text(value), next)
    }
    element.copy(attributes = attributes, scope = scala.xml.TopScope)
  }
}

package org.maproulette.provider.choice

import com.sun.net.httpserver.{HttpExchange, HttpServer}
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import scala.collection.mutable
import scala.xml.XML

/**
  * Loopback stand-in for the OSM API 0.6 calls the choice flow makes. It never contacts
  * OpenStreetMap. Uploads apply to the in-memory elements with OSM's version check, so stale
  * versions get 409 and deleting a node used by a way gets 412.
  */
class FakeOsmServer {
  case class Element(
      kind: String,
      id: Long,
      var version: Long,
      var tags: Map[String, String],
      var visible: Boolean = true
  )
  case class Changeset(tags: Map[String, String], var open: Boolean = true, var changes: Int = 0)

  val elements   = mutable.Map[(String, Long), Element]()
  val inWays     = mutable.Set[Long]()
  val changesets = mutable.LinkedHashMap[Long, Changeset]()
  val uploads    = mutable.ListBuffer[(Long, String)]()
  val writeAuth  = mutable.ListBuffer[String]()
  // Planned upload answers: "ok" (default), "409", "401", "412", "500" or "500-applied".
  val uploadPlan = mutable.Queue[String]()
  // Ids answered with an HTML 404, as a proxy or a wrong server would.
  val htmlMissing                      = mutable.Set[Long]()
  var createStatus: Option[Int]        = None
  var beforeUpload: Option[() => Unit] = None
  private var nextChangeset            = 1000L

  def put(kind: String, id: Long, tags: Map[String, String], version: Long = 3): Element =
    synchronized {
      val element = Element(kind, id, version, tags)
      elements((kind, id)) = element
      element
    }
  def edit(kind: String, id: Long)(change: Map[String, String] => Map[String, String]): Unit =
    synchronized {
      val element = elements((kind, id))
      element.tags = change(element.tags)
      element.version += 1
    }
  def uploadCount: Int = synchronized(uploads.size)

  private val server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
  val url: String    = s"http://127.0.0.1:${server.getAddress.getPort}"

  private def xml(element: Element): String = {
    val tags = element.tags.toSeq.sorted.map {
      case (k, v) => s"""<tag k="${escape(k)}" v="${escape(v)}"/>"""
    }.mkString
    val geometry = if (element.kind == "node") """ lat="40.7608" lon="-111.891"""" else ""
    s"""<osm version="0.6"><${element.kind} id="${element.id}" version="${element.version}" visible="${element.visible}"$geometry>$tags</${element.kind}></osm>"""
  }
  private def escape(value: String) =
    value.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;")

  private def respond(
      exchange: HttpExchange,
      status: Int,
      body: String = "",
      contentType: String = "text/xml; charset=utf-8"
  ): Unit = {
    val bytes = body.getBytes(StandardCharsets.UTF_8)
    exchange.getResponseHeaders.set("Content-Type", contentType)
    exchange.sendResponseHeaders(status, if (bytes.isEmpty) -1 else bytes.length.toLong)
    if (bytes.nonEmpty) exchange.getResponseBody.write(bytes)
    exchange.close()
  }

  private def apply(changeset: Long, body: String): Int = {
    val change  = XML.loadString(body)
    val modify  = (change \ "modify").flatMap(_.child).filter(_.label != "#PCDATA")
    val delete  = (change \ "delete").flatMap(_.child).filter(_.label != "#PCDATA")
    val target  = (modify ++ delete).head
    val key     = (target.label, (target \@ "id").toLong)
    val current = elements.get(key)
    if (current.isEmpty || !current.get.visible) return 410
    if ((target \@ "version").toLong != current.get.version) return 409
    if ((target \@ "changeset").toLong != changeset) return 409
    if (delete.nonEmpty) {
      if (inWays.contains(key._2)) return 412
      current.get.visible = false
    } else {
      current.get.tags = (target \ "tag").map(tag => (tag \@ "k") -> (tag \@ "v")).toMap
    }
    current.get.version += 1
    changesets(changeset).changes += 1
    200
  }

  server.createContext(
    "/api/0.6/",
    (exchange: HttpExchange) =>
      synchronized {
        val method = exchange.getRequestMethod
        val path   = exchange.getRequestURI.getPath.stripPrefix("/api/0.6/").split("/").toList
        val body =
          new String(exchange.getRequestBody.readAllBytes(), StandardCharsets.UTF_8)
        if (method != "GET")
          writeAuth += Option(exchange.getRequestHeaders.getFirst("Authorization")).getOrElse("")
        (method, path) match {
          case ("GET", List("node", id, "ways")) =>
            respond(
              exchange,
              200,
              if (inWays.contains(id.toLong)) """<osm><way id="1" version="1"/></osm>"""
              else "<osm/>"
            )
          case ("GET", List("node", _, "relations")) => respond(exchange, 200, "<osm/>")
          case ("GET", List("changeset", id)) =>
            changesets.get(id.toLong) match {
              case Some(cs) =>
                respond(
                  exchange,
                  200,
                  s"""<osm><changeset id="$id" changes_count="${cs.changes}"/></osm>"""
                )
              case None => respond(exchange, 404)
            }
          case ("GET", List(_, id)) if htmlMissing.contains(id.toLong) =>
            respond(exchange, 404, "<html><body>Not Found</body></html>", "text/html")
          case ("GET", List(kind, id)) =>
            elements.get((kind, id.toLong)) match {
              case Some(element) if element.visible => respond(exchange, 200, xml(element))
              case Some(_)                          => respond(exchange, 410)
              case None                             => respond(exchange, 404)
            }
          case ("PUT", List("changeset", "create")) =>
            createStatus match {
              case Some(status) => respond(exchange, status)
              case None =>
                nextChangeset += 1
                val tags = (XML.loadString(body) \\ "tag").map(t => (t \@ "k") -> (t \@ "v")).toMap
                changesets(nextChangeset) = Changeset(tags)
                respond(exchange, 200, nextChangeset.toString)
            }
          case ("POST", List("changeset", id, "upload")) =>
            uploads += id.toLong -> body
            beforeUpload.foreach { hook =>
              beforeUpload = None; hook()
            }
            val plan = if (uploadPlan.isEmpty) "ok" else uploadPlan.dequeue()
            plan match {
              case "ok"          => respond(exchange, apply(id.toLong, body))
              case "500-applied" => apply(id.toLong, body); respond(exchange, 500)
              case status        => respond(exchange, status.toInt)
            }
          case ("PUT", List("changeset", id, "close")) =>
            changesets.get(id.toLong).foreach(_.open = false)
            respond(exchange, 200)
          case _ => respond(exchange, 404)
        }
      }
  )
  server.start()

  def stop(): Unit = server.stop(0)
}

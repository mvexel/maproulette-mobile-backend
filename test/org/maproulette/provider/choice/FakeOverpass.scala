package org.maproulette.provider.choice

import com.sun.net.httpserver.HttpServer
import java.net.{InetSocketAddress, URLDecoder}
import java.nio.charset.StandardCharsets
import play.api.libs.json.{JsObject, JsValue, Json}
import scala.collection.mutable

/**
  * Loopback stand-in for an Overpass interpreter. It never contacts Overpass. Answers come from
  * `answer` (status and body) for every query; `queries` records the decoded `data` of each POST.
  */
class FakeOverpass {
  val queries                                   = mutable.ListBuffer[String]()
  @volatile var answer: String => (Int, String) = _ => (200, FakeOverpass.elements())

  private val server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
  val url: String    = s"http://127.0.0.1:${server.getAddress.getPort}/api/interpreter"

  server.createContext(
    "/api/interpreter",
    exchange => {
      val body = new String(exchange.getRequestBody.readAllBytes(), StandardCharsets.UTF_8)
      val query = body
        .split("&")
        .collectFirst { case p if p.startsWith("data=") => URLDecoder.decode(p.drop(5), "UTF-8") }
        .getOrElse("")
      synchronized(queries += query)
      val (status, text) = answer(query)
      val bytes          = text.getBytes(StandardCharsets.UTF_8)
      exchange.getResponseHeaders.set("Content-Type", "application/json")
      exchange.sendResponseHeaders(status, if (bytes.isEmpty) -1 else bytes.length.toLong)
      if (bytes.nonEmpty) exchange.getResponseBody.write(bytes)
      exchange.close()
    }
  )
  server.start()

  def count: Int   = synchronized(queries.size)
  def stop(): Unit = server.stop(0)
}

object FakeOverpass {
  def countElement(total: Long): JsObject =
    Json.obj("type" -> "count", "id" -> 0, "tags" -> Json.obj("total" -> total.toString))

  def node(id: Long, tags: (String, String)*): JsObject =
    Json.obj(
      "type" -> "node",
      "id"   -> id,
      "lat"  -> 40.7538,
      "lon"  -> -111.9068,
      "tags" -> Json.toJson(tags.toMap)
    )

  def elements(values: JsValue*): String =
    Json.stringify(Json.obj("version" -> 0.6, "elements" -> values))
}

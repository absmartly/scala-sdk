package com.absmartly.sdk

import com.sun.net.httpserver.{HttpExchange, HttpServer}
import io.circe.Json
import io.circe.parser._
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.BeforeAndAfterEach

import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.Collections
import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.jdk.CollectionConverters._

/**
 * Hermetic integration test: starts a real local HTTP server (JDK built-in
 * com.sun.net.httpserver.HttpServer) on an ephemeral port, points the SDK's
 * endpoint at it, and drives the PUBLIC SDK API so the real sttp
 * HttpURLConnectionBackend performs a GET /context (createContext) and a
 * PUT /context (treatment + track -> publish). Asserts the wire contract.
 */
class LocalServerIntegrationTest extends AnyFunSuite with BeforeAndAfterEach {

  case class RecordedRequest(
    method: String,
    path: String,
    rawQuery: String,
    headers: Map[String, String],
    body: String
  )

  private var server: HttpServer = _
  private var port: Int = _
  private val requests = Collections.synchronizedList(new java.util.ArrayList[RecordedRequest]())

  override def beforeEach(): Unit = {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext("/context", (exchange: HttpExchange) => {
      val body = new String(exchange.getRequestBody.readAllBytes(), StandardCharsets.UTF_8)
      val headers = exchange.getRequestHeaders.asScala.map { case (k, v) =>
        k.toLowerCase -> v.asScala.mkString(",")
      }.toMap
      val uri = exchange.getRequestURI
      requests.add(RecordedRequest(
        method = exchange.getRequestMethod,
        path = uri.getPath,
        rawQuery = Option(uri.getRawQuery).getOrElse(""),
        headers = headers,
        body = body
      ))

      val response =
        if (exchange.getRequestMethod == "GET") """{"experiments":[]}"""
        else "{}"
      val bytes = response.getBytes(StandardCharsets.UTF_8)
      exchange.getResponseHeaders.add("Content-Type", "application/json")
      exchange.sendResponseHeaders(200, bytes.length.toLong)
      val os = exchange.getResponseBody
      os.write(bytes)
      os.close()
    })
    server.start()
    port = server.getAddress.getPort
  }

  override def afterEach(): Unit = {
    if (server != null) server.stop(0)
    requests.clear()
  }

  private def parseQuery(raw: String): Map[String, String] = {
    if (raw.isEmpty) Map.empty
    else raw.split("&").map { kv =>
      val idx = kv.indexOf('=')
      if (idx < 0) kv -> "" else kv.substring(0, idx) -> kv.substring(idx + 1)
    }.toMap
  }

  test("performs a real GET /context and PUT /context against a local server") {
    val sdk = SDK.create(
      endpoint = s"http://127.0.0.1:$port",
      apiKey = "test-api-key",
      application = "website",
      environment = "dev"
    )

    val context = Await.result(
      sdk.createContext(Map("user_id" -> "123456789")),
      5.seconds
    )

    // --- assert the real GET /context ---
    val recorded = requests.asScala.toList
    val get = recorded.find(_.method == "GET")
    assert(get.isDefined, "expected a GET /context")
    assert(get.get.path == "/context")
    val query = parseQuery(get.get.rawQuery)
    assert(query.get("application").contains("website"))
    assert(query.get("environment").contains("dev"))

    // --- drive an exposure + a goal, then publish ---
    context.treatment("not_found_experiment")
    context.track("payment", Some(Map("value" -> Json.fromInt(99))))
    Await.result(context.publish(), 5.seconds)

    val put = requests.asScala.toList.find(_.method == "PUT")
    assert(put.isDefined, "expected a PUT /context")
    assert(put.get.path == "/context")
    assert(put.get.rawQuery.isEmpty, "PUT should carry no query params")

    // --- headers (Scala publish sends X-API-Key, X-Application, X-Environment, Content-Type) ---
    val h = put.get.headers
    assert(h.get("x-api-key").contains("test-api-key"))
    assert(h.get("x-application").contains("website"))
    assert(h.get("x-environment").contains("dev"))
    assert(h.get("content-type").exists(_.contains("application/json")))

    // --- body ---
    val json = parse(put.get.body).getOrElse(Json.Null)
    val cursor = json.hcursor
    assert(cursor.get[Boolean]("hashed").contains(true))
    assert(json.hcursor.downField("units").succeeded, "units must be present")
    assert(cursor.get[Long]("publishedAt").isRight, "publishedAt must be a number")
    val goals = json.hcursor.downField("goals").as[List[Json]].getOrElse(Nil)
    assert(goals.nonEmpty, "goals must be present and non-empty")
    assert(goals.head.hcursor.get[String]("name").contains("payment"))

    sdk.close()
  }
}

package com.absmartly.sdk

import scala.concurrent.{ExecutionContext, Future}
import sttp.client3._
import io.circe.parser._
import io.circe.syntax._

/**
 * ABsmartly SDK - Entry point for creating contexts
 *
 * CRITICAL: Must implement BOTH createContext (async) and createContextWith (sync)
 */
class SDK(config: SDKConfig)(implicit ec: ExecutionContext) {

  private val backend = HttpURLConnectionBackend()

  /**
   * Create context by fetching data from API (ASYNC)
   *
   * @param units Initial units (e.g., Map("session_id" -> "abc123"))
   * @param options Optional configuration
   * @return Future containing the Context
   */
  def createContext(
    units: Map[String, String],
    options: ContextOptions = ContextOptions()
  ): Future[Context] = {
    Future {
      // Fetch context data from endpoint
      val request = basicRequest
        .post(uri"${config.endpoint}/context")
        .header("X-API-Key", config.apiKey)
        .header("X-Application", config.application)
        .header("X-Environment", config.environment)
        .header("Content-Type", "application/json")
        .body(Map(
          "units" -> units.asJson
        ).asJson.noSpaces)
        .readTimeout(scala.concurrent.duration.Duration(config.timeout, "ms"))

      val response = request.send(backend)

      response.body match {
        case Right(body) =>
          parse(body).flatMap(_.as[ContextData]) match {
            case Right(data) =>
              new Context(this, data, units, options)
            case Left(error) =>
              throw new RuntimeException(s"Failed to parse context data: $error")
          }
        case Left(error) =>
          throw new RuntimeException(s"Failed to fetch context data: $error")
      }
    }
  }

  /**
   * Create context with pre-fetched data (SYNC)
   *
   * Use this for SSR, pre-fetching, or when you already have the data.
   *
   * @param units Initial units
   * @param data Pre-fetched context data
   * @param options Optional configuration
   * @return Context (ready immediately)
   */
  def createContextWith(
    units: Map[String, String],
    data: ContextData,
    options: ContextOptions = ContextOptions()
  ): Context = {
    new Context(this, data, units, options)
  }

  /**
   * Publish events to the collector
   */
  def publish(
    units: Map[String, String],
    hashed: Boolean,
    exposures: List[Exposure],
    goals: List[Goal]
  ): Future[Unit] = {
    Future {
      val request = basicRequest
        .put(uri"${config.endpoint}/context")
        .header("X-API-Key", config.apiKey)
        .header("X-Application", config.application)
        .header("X-Environment", config.environment)
        .header("Content-Type", "application/json")
        .body(Map(
          "units" -> units.asJson,
          "hashed" -> hashed.asJson,
          "exposures" -> exposures.asJson,
          "goals" -> goals.asJson,
          "publishedAt" -> System.currentTimeMillis().asJson
        ).asJson.noSpaces)
        .readTimeout(scala.concurrent.duration.Duration(config.timeout, "ms"))

      val response = request.send(backend)

      response.body match {
        case Right(_) => ()
        case Left(error) =>
          throw new RuntimeException(s"Failed to publish events: $error")
      }
    }
  }

  def getConfig: SDKConfig = config
}

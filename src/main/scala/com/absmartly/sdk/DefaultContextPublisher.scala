package com.absmartly.sdk

import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal
import sttp.client3._
import sttp.model.StatusCode
import io.circe.syntax._

class DefaultContextPublisher(config: SDKConfig)(implicit ec: ExecutionContext) extends ContextPublisher {

  private val backend = HttpURLConnectionBackend()
  private val logger = Logger.get

  def publish(
    units: Map[String, String],
    hashed: Boolean,
    exposures: List[Exposure],
    goals: List[Goal],
    attributes: Option[List[Attribute]]
  ): Future[Unit] = {
    Future {
      try {
        logger.debug(s"Publishing ${exposures.length} exposures, ${goals.length} goals")

        import PublishEvent.attributeEncoder

        val baseFields = Map(
          "units" -> units.map { case (unitType, uid) => PublishUnit(unitType, uid) }.toList.asJson,
          "hashed" -> hashed.asJson,
          "exposures" -> exposures.asJson,
          "goals" -> goals.asJson,
          "publishedAt" -> System.currentTimeMillis().asJson
        )
        val fields = attributes match {
          case Some(attrs) if attrs.nonEmpty => baseFields + ("attributes" -> attrs.asJson)
          case _ => baseFields
        }

        val request = basicRequest
          .put(uri"${config.endpoint}/context")
          .header("X-API-Key", config.apiKey)
          .header("X-Application", config.application)
          .header("X-Environment", config.environment)
          .header("X-Application-Version", "0")
          .header("X-Agent", "absmartly-scala-sdk")
          .header("Content-Type", "application/json")
          .body(fields.asJson.noSpaces)
          .readTimeout(scala.concurrent.duration.Duration(config.timeout, "ms"))

        val response = request.send(backend)

        response.code match {
          case StatusCode.Ok =>
            logger.debug("Publish successful")
            ()
          case StatusCode.Unauthorized =>
            logger.error("Publish failed: Invalid API key")
            throw AuthenticationException("Invalid API key for publish")
          case code if code.isSuccess =>
            logger.debug(s"Publish successful with status ${code.code}")
            ()
          case code =>
            val body = response.body.fold(identity, identity)
            logger.error(s"Publish failed HTTP ${code.code}: $body")
            throw ServerException(code.code, s"Publish failed: $body")
        }
      } catch {
        case e: SDKException => throw e
        case e: java.net.SocketTimeoutException =>
          logger.error(s"Publish timeout after ${config.timeout}ms")
          throw NetworkException(s"Publish timeout after ${config.timeout}ms", Some(e))
        case NonFatal(e) =>
          logger.error(s"Publish error: ${e.getMessage}", e)
          throw NetworkException(s"Publish failed: ${e.getMessage}", Some(e))
      }
    }
  }
}

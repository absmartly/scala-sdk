package com.absmartly.sdk

import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal
import sttp.client3._
import sttp.model.StatusCode
import io.circe.parser._
import io.circe.syntax._

object SDK {
  def create(
    endpoint: String,
    apiKey: String,
    application: String,
    environment: String,
    retries: Int = 5,
    timeout: Int = 3000,
    eventLogger: EventLogger = NoOpEventLogger
  )(implicit ec: ExecutionContext): SDK = {
    val config = SDKConfig(
      endpoint = endpoint,
      apiKey = apiKey,
      application = application,
      environment = environment,
      retries = retries,
      timeout = timeout,
      eventLogger = eventLogger
    )
    new SDK(config)
  }
}

class SDK(config: SDKConfig)(implicit ec: ExecutionContext) {

  private val backend = HttpURLConnectionBackend()
  private val logger = Logger.get

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
      try {
        val request = basicRequest
          .get(uri"${config.endpoint}/context?application=${config.application}&environment=${config.environment}")
          .header("X-API-Key", config.apiKey)
          .header("X-Application", config.application)
          .header("X-Environment", config.environment)
          .header("X-Agent", "absmartly-scala-sdk")
          .header("Content-Type", "application/json")
          .readTimeout(scala.concurrent.duration.Duration(config.timeout, "ms"))

        val response = request.send(backend)

        response.code match {
          case StatusCode.Ok =>
            response.body match {
              case Right(body) =>
                parse(body).flatMap(_.as[ContextData]) match {
                  case Right(data) =>
                    logger.debug(s"Context created with ${data.experiments.length} experiments")
                    new Context(this, data, units, options, config.eventLogger)
                  case Left(parseError) =>
                    logger.error(s"Parse error: $parseError\nBody: $body")
                    throw ParseException(s"Failed to parse context data: ${parseError.getMessage}", Some(parseError))
                }
              case Left(error) =>
                logger.error(s"Empty response body: $error")
                throw NetworkException(s"Empty response: $error")
            }
          case StatusCode.Unauthorized =>
            val maskedKey = if (config.apiKey.length > 4) s"***${config.apiKey.takeRight(4)}" else "***"
            logger.error(s"Auth failed. API key: $maskedKey")
            throw AuthenticationException("Invalid API key")
          case StatusCode.NotFound =>
            logger.error(s"Endpoint not found: ${config.endpoint}/context")
            throw ServerException(404, "Endpoint not found")
          case StatusCode.TooManyRequests =>
            logger.warn("Rate limit exceeded")
            throw ServerException(429, "Rate limit exceeded")
          case code =>
            val body = response.body.fold(identity, identity)
            logger.error(s"HTTP ${code.code}: $body")
            throw ServerException(code.code, body)
        }
      } catch {
        case e: SDKException => throw e
        case e: java.net.SocketTimeoutException =>
          logger.error(s"Timeout after ${config.timeout}ms")
          throw NetworkException(s"Timeout after ${config.timeout}ms", Some(e))
        case NonFatal(e) =>
          logger.error(s"Unexpected error: ${e.getMessage}", e)
          throw NetworkException(s"Context creation failed: ${e.getMessage}", Some(e))
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
    logger.debug(s"Creating context with pre-fetched data (${data.experiments.length} experiments)")
    new Context(this, data, units, options, config.eventLogger)
  }

  /**
   * Publish events to the collector
   */
  def publish(
    units: Map[String, String],
    hashed: Boolean,
    exposures: List[Exposure],
    goals: List[Goal],
    attributes: Option[List[Attribute]] = None
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

  /**
   * Close the SDK and release resources
   */
  def close(): Unit = {
    try {
      backend.close()
      logger.info("SDK closed successfully")
    } catch {
      case NonFatal(e) =>
        logger.error(s"Error closing SDK: ${e.getMessage}", e)
    }
  }

  @deprecated("fetchContextData() blocks the calling thread. Use fetchContextDataAsync() instead, which returns a Future[ContextData].", since = "0.1.0")
  def fetchContextData(): ContextData = {
    val request = basicRequest
      .get(uri"${config.endpoint}/context?application=${config.application}&environment=${config.environment}")
      .header("X-API-Key", config.apiKey)
      .header("X-Application", config.application)
      .header("X-Environment", config.environment)
      .header("X-Agent", "absmartly-scala-sdk")
      .header("Content-Type", "application/json")
      .readTimeout(scala.concurrent.duration.Duration(config.timeout, "ms"))

    val response = request.send(backend)

    response.code match {
      case StatusCode.Ok =>
        response.body match {
          case Right(body) =>
            parse(body).flatMap(_.as[ContextData]) match {
              case Right(data) =>
                logger.debug(s"Fetched context data with ${data.experiments.length} experiments")
                data
              case Left(parseError) =>
                throw ParseException(s"Failed to parse context data: ${parseError.getMessage}", Some(parseError))
            }
          case Left(error) =>
            throw NetworkException(s"Empty response: $error")
        }
      case code =>
        val body = response.body.fold(identity, identity)
        throw ServerException(code.code, body)
    }
  }

  def fetchContextDataAsync(): Future[ContextData] = {
    Future {
      val request = basicRequest
        .get(uri"${config.endpoint}/context?application=${config.application}&environment=${config.environment}")
        .header("X-API-Key", config.apiKey)
        .header("X-Application", config.application)
        .header("X-Environment", config.environment)
        .header("X-Agent", "absmartly-scala-sdk")
        .header("Content-Type", "application/json")
        .readTimeout(scala.concurrent.duration.Duration(config.timeout, "ms"))

      val response = request.send(backend)

      response.code match {
        case StatusCode.Ok =>
          response.body match {
            case Right(body) =>
              parse(body).flatMap(_.as[ContextData]) match {
                case Right(data) =>
                  logger.debug(s"Fetched context data with ${data.experiments.length} experiments")
                  data
                case Left(parseError) =>
                  throw ParseException(s"Failed to parse context data: ${parseError.getMessage}", Some(parseError))
              }
            case Left(error) =>
              throw NetworkException(s"Empty response: $error")
          }
        case StatusCode.Unauthorized =>
          val maskedKey = if (config.apiKey.length > 4) s"***${config.apiKey.takeRight(4)}" else "***"
          logger.error(s"Auth failed. API key: $maskedKey")
          throw AuthenticationException("Invalid API key")
        case StatusCode.NotFound =>
          logger.error(s"Endpoint not found: ${config.endpoint}/context")
          throw ServerException(404, "Endpoint not found")
        case StatusCode.TooManyRequests =>
          logger.warn("Rate limit exceeded")
          throw ServerException(429, "Rate limit exceeded")
        case code =>
          val body = response.body.fold(identity, identity)
          logger.error(s"HTTP ${code.code}: $body")
          throw ServerException(code.code, body)
      }
    }
  }

  def getConfig: SDKConfig = config
}

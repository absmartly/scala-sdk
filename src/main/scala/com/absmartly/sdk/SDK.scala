package com.absmartly.sdk

import scala.concurrent.{ExecutionContext, Future, blocking}
import scala.util.control.NonFatal
import sttp.client3._
import sttp.model.StatusCode
import io.circe.parser._

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
  private val logger = Logger.safe

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
    fetchContextDataAsync().map { data =>
      logger.debug(s"Context created with ${data.experiments.length} experiments")
      new Context(this, data, units, options, config.eventLogger)
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
      blocking {
        logger.debug(s"Publishing ${exposures.length} exposures, ${goals.length} goals")
        Http.publish(backend, config, units, hashed, exposures, goals, attributes)
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

  @deprecated("fetchContextData() blocks the calling thread. Use fetchContextDataAsync() instead, which returns a Future[ContextData].", since = "0.2.0")
  def fetchContextData(): ContextData = fetchContextDataBlocking()

  private[sdk] def fetchContextDataBlocking(): ContextData =
    Http.withRetries(config.retries, config.timeout)(() => fetchContextDataOnce())

  def fetchContextDataAsync(): Future[ContextData] = Future(blocking(fetchContextDataBlocking()))

  private def fetchContextDataOnce(): ContextData = {
    try {
      val request = basicRequest
        .get(uri"${config.endpoint}/context?application=${config.application}&environment=${config.environment}")
        .headers(Http.headers(config))
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
                  logger.error(s"Parse error: ${Logger.sanitize(parseError.getMessage)} Body: ${Logger.sanitize(body, 500)}")
                  throw ParseException(s"Failed to parse context data: ${Logger.sanitize(String.valueOf(parseError.getMessage))}", Some(parseError))
              }
            case Left(error) =>
              logger.error(s"Empty response body: ${Logger.sanitize(error)}")
              throw NetworkException(s"Empty response: ${Logger.sanitize(error)}")
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
          val body = Logger.sanitize(response.body.fold(identity, identity), 500)
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
        throw NetworkException(s"Context fetch failed: ${e.getMessage}", Some(e))
    }
  }

  def getConfig: SDKConfig = config
}

/**
 * Transport shared by SDK and DefaultContextPublisher, matching the JS client
 * (client.ts): the same headers on every request, and retries with exponential
 * backoff for context fetches and publishes alike.
 */
private[sdk] object Http {
  private val RetryDelayMs = 50L

  def headers(config: SDKConfig): Map[String, String] = Map(
    "Content-Type" -> "application/json",
    "X-API-Key" -> config.apiKey,
    "X-Agent" -> "absmartly-scala-sdk",
    "X-Environment" -> config.environment,
    "X-Application" -> config.application,
    "X-Application-Version" -> "0"
  )

  /** 4xx responses (bad key, bad request) are final; anything else may be transient. */
  private def isRetryable(e: Throwable): Boolean = e match {
    case _: AuthenticationException | _: ValidationException => false
    case s: ServerException => s.statusCode < 400 || s.statusCode >= 500
    case _ => true
  }

  /**
   * Runs `attempt` up to `retries` more times after a retryable failure. The
   * delay doubles from 50ms (plus jitter), and the total time spent waiting
   * stays within `timeoutMs`.
   */
  def withRetries[T](retries: Int, timeoutMs: Int)(attempt: () => T): T = {
    @scala.annotation.tailrec
    def loop(tries: Int, waitedMs: Long): T = {
      scala.util.Try(attempt()) match {
        case scala.util.Success(value) => value
        case scala.util.Failure(e) if !isRetryable(e) || tries >= retries || waitedMs >= timeoutMs => throw e
        case scala.util.Failure(e) =>
          val backoff = (1L << tries) * RetryDelayMs + (0.5 * scala.util.Random.nextDouble() * RetryDelayMs).toLong
          val delay = math.min(backoff, timeoutMs - waitedMs)
          Logger.safe.warn(s"Request failed, retrying in ${delay}ms: ${Logger.sanitize(String.valueOf(e.getMessage))}")
          Thread.sleep(delay)
          loop(tries + 1, waitedMs + delay)
      }
    }
    loop(0, 0L)
  }

  /** PUT /context. Only a 2xx is a delivery; redirects are not followed. */
  def publish(
    backend: SttpBackend[Identity, Any],
    config: SDKConfig,
    units: Map[String, String],
    hashed: Boolean,
    exposures: List[Exposure],
    goals: List[Goal],
    attributes: Option[List[Attribute]]
  ): Unit = {
    import io.circe.syntax._
    import PublishEvent.attributeEncoder
    val logger = Logger.safe

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
    val body = fields.asJson.noSpaces

    withRetries(config.retries, config.timeout) { () =>
      try {
        val request = basicRequest
          .put(uri"${config.endpoint}/context")
          .headers(headers(config))
          .body(body)
          .followRedirects(false)
          .readTimeout(scala.concurrent.duration.Duration(config.timeout, "ms"))

        val response = request.send(backend)

        response.code match {
          case StatusCode.Unauthorized =>
            logger.error("Publish failed: Invalid API key")
            throw AuthenticationException("Invalid API key for publish")
          case code if code.isSuccess =>
            logger.debug(s"Publish successful with status ${code.code}")
          case code =>
            val text = Logger.sanitize(response.body.fold(identity, identity), 500)
            logger.error(s"Publish failed HTTP ${code.code}: $text")
            throw ServerException(code.code, text)
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

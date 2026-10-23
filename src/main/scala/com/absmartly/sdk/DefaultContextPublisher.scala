package com.absmartly.sdk

import scala.concurrent.{ExecutionContext, Future, blocking}
import sttp.client3._

class DefaultContextPublisher(config: SDKConfig)(implicit ec: ExecutionContext) extends ContextPublisher {

  private val backend = HttpURLConnectionBackend()
  private val logger = Logger.safe

  def publish(
    units: Map[String, String],
    hashed: Boolean,
    exposures: List[Exposure],
    goals: List[Goal],
    attributes: Option[List[Attribute]]
  ): Future[Unit] = {
    Future {
      blocking {
        logger.debug(s"Publishing ${exposures.length} exposures, ${goals.length} goals")
        Http.publish(backend, config, units, hashed, exposures, goals, attributes)
      }
    }
  }
}

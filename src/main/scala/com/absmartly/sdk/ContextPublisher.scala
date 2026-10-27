package com.absmartly.sdk

import scala.concurrent.Future

trait ContextPublisher {
  def publish(
    units: Map[String, String],
    hashed: Boolean,
    exposures: List[Exposure],
    goals: List[Goal],
    attributes: Option[List[Attribute]]
  ): Future[Unit]
}

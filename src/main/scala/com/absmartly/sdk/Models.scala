package com.absmartly.sdk

import io.circe.{Decoder, Encoder, Json}
import io.circe.generic.semiauto._

/**
 * SDK Configuration
 */
case class SDKConfig private(
  endpoint: String,
  apiKey: String,
  application: String,
  environment: String,
  retries: Int,
  timeout: Int,
  eventLogger: EventLogger,
  publishHandler: Option[(Map[String, String], Boolean, List[Exposure], List[Goal], Option[List[Attribute]]) => scala.concurrent.Future[Unit]] = None
) {
  override def toString: String = {
    val maskedKey = if (apiKey.length > 4) s"***${apiKey.takeRight(4)}" else "***"
    s"SDKConfig(endpoint=$endpoint, apiKey=$maskedKey, " +
    s"application=$application, environment=$environment, retries=$retries, timeout=$timeout)"
  }
}

object SDKConfig {
  def apply(
    endpoint: String,
    apiKey: String,
    application: String,
    environment: String,
    retries: Int = 5,
    timeout: Int = 3000,
    eventLogger: EventLogger = NoOpEventLogger,
    publishHandler: Option[(Map[String, String], Boolean, List[Exposure], List[Goal], Option[List[Attribute]]) => scala.concurrent.Future[Unit]] = None
  ): SDKConfig = {
    require(endpoint.nonEmpty, "endpoint must not be empty")
    require(apiKey.nonEmpty, "apiKey must not be empty")
    require(application.nonEmpty, "application must not be empty")
    require(environment.nonEmpty, "environment must not be empty")
    require(retries >= 0, "retries must be >= 0")
    require(timeout > 0, "timeout must be > 0")

    new SDKConfig(endpoint, apiKey, application, environment, retries, timeout, eventLogger, publishHandler)
  }
}

/**
 * Experiment data from API
 */
case class ExperimentData(
  id: Int,
  name: String,
  unitType: String,
  iteration: Int,
  seedHi: Int,
  seedLo: Int,
  split: List[Double],
  trafficSeedHi: Int,
  trafficSeedLo: Int,
  trafficSplit: List[Double],
  fullOnVariant: Int,
  applications: List[ExperimentApplication],
  variants: List[ExperimentVariant],
  audienceStrict: Boolean = false,
  audience: Option[String] = None,
  customFieldValues: Option[List[CustomFieldValue]] = None
)

case class CustomFieldValue(
  name: String,
  value: String,
  `type`: String
)

case class ExperimentApplication(
  name: String
)

case class ExperimentVariant(
  name: String,
  config: Option[Json]
)

object ExperimentData {
  implicit val applicationDecoder: Decoder[ExperimentApplication] = deriveDecoder
  implicit val applicationEncoder: Encoder[ExperimentApplication] = deriveEncoder
  implicit val variantDecoder: Decoder[ExperimentVariant] = deriveDecoder
  implicit val variantEncoder: Encoder[ExperimentVariant] = deriveEncoder
  implicit val customFieldValueDecoder: Decoder[CustomFieldValue] = deriveDecoder
  implicit val customFieldValueEncoder: Encoder[CustomFieldValue] = deriveEncoder
  implicit val decoder: Decoder[ExperimentData] = Decoder.instance { c =>
    for {
      id <- c.downField("id").as[Int]
      name <- c.downField("name").as[String]
      unitType <- c.downField("unitType").as[String]
      iteration <- c.downField("iteration").as[Int]
      seedHi <- c.downField("seedHi").as[Int]
      seedLo <- c.downField("seedLo").as[Int]
      split <- c.downField("split").as[List[Double]]
      trafficSeedHi <- c.downField("trafficSeedHi").as[Int]
      trafficSeedLo <- c.downField("trafficSeedLo").as[Int]
      trafficSplit <- c.downField("trafficSplit").as[List[Double]]
      fullOnVariant <- c.downField("fullOnVariant").as[Int]
      applications <- c.downField("applications").as[List[ExperimentApplication]]
      variants <- c.downField("variants").as[List[ExperimentVariant]]
      audienceStrict <- c.downField("audienceStrict").as[Option[Boolean]].map(_.getOrElse(false))
      audience <- c.downField("audience").as[Option[String]]
      customFieldValues <- c.downField("customFieldValues").as[Option[List[CustomFieldValue]]]
    } yield ExperimentData(id, name, unitType, iteration, seedHi, seedLo, split,
      trafficSeedHi, trafficSeedLo, trafficSplit, fullOnVariant, applications, variants,
      audienceStrict, audience, customFieldValues)
  }
  implicit val encoder: Encoder[ExperimentData] = deriveEncoder
}

/**
 * Context data containing experiments
 */
case class ContextData(
  experiments: List[ExperimentData]
)

object ContextData {
  implicit val decoder: Decoder[ContextData] = deriveDecoder
  implicit val encoder: Encoder[ContextData] = deriveEncoder
}

/**
 * Cached variant assignment
 */
case class Assignment(
  id: Int,
  name: String,
  unitType: String,
  iteration: Int,
  trafficSplit: List[Double],
  fullOnVariant: Int,
  variant: Int,
  assigned: Boolean,
  exposed: Boolean,
  eligible: Boolean,
  overridden: Boolean,
  audienceMismatch: Boolean,
  fullOn: Boolean,
  custom: Boolean,
  attrsSeq: Int = 0
)

/**
 * Exposure event for publishing
 */
case class Exposure(
  id: Int,
  name: String,
  unit: String,
  variant: Int,
  exposedAt: Long,
  assigned: Boolean,
  eligible: Boolean,
  overridden: Boolean,
  fullOn: Boolean,
  custom: Boolean,
  audienceMismatch: Boolean
)

object Exposure {
  implicit val encoder: Encoder[Exposure] = deriveEncoder
}

/**
 * Goal achievement event
 */
case class Goal(
  name: String,
  achievedAt: Long,
  properties: Option[Map[String, Json]]
)

object Goal {
  implicit val encoder: Encoder[Goal] = deriveEncoder
}

/**
 * Context creation options
 */
case class ContextOptions(
  units: Map[String, String] = Map.empty,
  attributes: Map[String, Json] = Map.empty,
  overrides: Map[String, Int] = Map.empty,
  cassignments: Map[String, Int] = Map.empty
)

/**
 * Publish event for event logging
 */
case class PublishUnit(
  `type`: String,
  uid: String
)

object PublishUnit {
  implicit val encoder: Encoder[PublishUnit] = deriveEncoder
}

case class PublishEvent(
  hashed: Boolean,
  publishedAt: Long,
  units: List[PublishUnit],
  exposures: List[Exposure],
  goals: List[Goal],
  attributes: Option[List[Attribute]] = None
)

object PublishEvent {
  implicit val attributeEncoder: Encoder[Attribute] = deriveEncoder
  implicit val encoder: Encoder[PublishEvent] = deriveEncoder
}

/**
 * Attribute with type information
 */
case class Attribute(
  name: String,
  value: Json,
  setAt: Long
)

package com.absmartly.sdk

import io.circe.{Decoder, Encoder, Json}
import io.circe.generic.semiauto._

/**
 * SDK Configuration
 */
case class SDKConfig(
  endpoint: String,
  apiKey: String,
  application: String,
  environment: String,
  retries: Int = 5,
  timeout: Int = 3000
) {
  require(endpoint.nonEmpty, "endpoint must not be empty")
  require(apiKey.nonEmpty, "apiKey must not be empty")
  require(application.nonEmpty, "application must not be empty")
  require(environment.nonEmpty, "environment must not be empty")
  require(retries >= 0, "retries must be >= 0")
  require(timeout > 0, "timeout must be > 0")
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
  audienceStrict: Boolean,
  audience: Option[String]
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
  implicit val decoder: Decoder[ExperimentData] = deriveDecoder
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
  custom: Boolean
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
  properties: Option[Map[String, Double]]
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
 * Attribute with type information
 */
case class Attribute(
  name: String,
  value: Json,
  setAt: Long
)

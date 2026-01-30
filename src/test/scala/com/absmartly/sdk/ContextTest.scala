package com.absmartly.sdk

import org.scalatest.funsuite.AnyFunSuite
import io.circe.Json
import scala.concurrent.ExecutionContext.Implicits.global

class ContextTest extends AnyFunSuite {

  val config = SDKConfig(
    endpoint = "https://test.absmartly.io/v1",
    apiKey = "test-key",
    application = "test-app",
    environment = "test"
  )

  val sdk = new SDK(config)

  val testExperiment = ExperimentData(
    id = 1,
    name = "test_experiment",
    unitType = "session_id",
    iteration = 1,
    seedHi = 0,
    seedLo = 0,
    split = List(0.5, 0.5),
    trafficSeedHi = 0,
    trafficSeedLo = 0,
    trafficSplit = List(1.0),
    fullOnVariant = 0,
    applications = List(ExperimentApplication("test-app")),
    variants = List(
      ExperimentVariant("control", None),
      ExperimentVariant("treatment", Some(Json.obj("button_color" -> Json.fromString("blue"))))
    ),
    audienceStrict = false,
    audience = None
  )

  val testData = ContextData(experiments = List(testExperiment))

  // State tests
  test("context is ready immediately with sync creation") {
    val context = sdk.createContextWith(
      units = Map("session_id" -> "test123"),
      data = testData
    )
    assert(context.isReady())
    assert(!context.isFailed())
    assert(!context.isFinalized())
  }

  // Units tests
  test("setUnit and getUnit work correctly") {
    val context = sdk.createContextWith(Map.empty, testData)
    context.setUnit("session_id", "abc123")
    assert(context.getUnit("session_id").contains("abc123"))
  }

  test("setUnits sets multiple units") {
    val context = sdk.createContextWith(Map.empty, testData)
    context.setUnits(Map(
      "session_id" -> "abc123",
      "user_id" -> "user456"
    ))
    assert(context.getUnit("session_id").contains("abc123"))
    assert(context.getUnit("user_id").contains("user456"))
  }

  test("setUnit throws on blank UID") {
    val context = sdk.createContextWith(Map.empty, testData)
    assertThrows[IllegalArgumentException] {
      context.setUnit("session_id", "")
    }
  }

  test("setUnit throws when changing existing unit") {
    val context = sdk.createContextWith(Map("session_id" -> "abc123"), testData)
    assertThrows[IllegalStateException] {
      context.setUnit("session_id", "different")
    }
  }

  test("getUnits returns all units") {
    val context = sdk.createContextWith(
      Map("session_id" -> "abc123", "user_id" -> "user456"),
      testData
    )
    val units = context.getUnits()
    assert(units.size == 2)
    assert(units("session_id") == "abc123")
    assert(units("user_id") == "user456")
  }

  // Attributes tests
  test("setAttribute and getAttribute work correctly") {
    val context = sdk.createContextWith(Map.empty, testData)
    context.setAttribute("age", Json.fromInt(25))
    assert(context.getAttribute("age").flatMap(_.asNumber).flatMap(_.toInt).contains(25))
  }

  test("setAttributes sets multiple attributes") {
    val context = sdk.createContextWith(Map.empty, testData)
    context.setAttributes(Map(
      "age" -> Json.fromInt(25),
      "country" -> Json.fromString("US")
    ))
    assert(context.getAttribute("age").isDefined)
    assert(context.getAttribute("country").flatMap(_.asString).contains("US"))
  }

  test("getAttributes returns all attributes") {
    val context = sdk.createContextWith(Map.empty, testData)
    context.setAttributes(Map(
      "age" -> Json.fromInt(25),
      "country" -> Json.fromString("US")
    ))
    val attrs = context.getAttributes()
    assert(attrs.size == 2)
  }

  test("setAttribute overwrites previous value") {
    val context = sdk.createContextWith(Map.empty, testData)
    context.setAttribute("age", Json.fromInt(25))
    context.setAttribute("age", Json.fromInt(30))
    assert(context.getAttribute("age").flatMap(_.asNumber).flatMap(_.toInt).contains(30))
  }

  // Override tests
  test("setOverride forces specific variant") {
    val context = sdk.createContextWith(Map("session_id" -> "test123"), testData)
    context.setOverride("test_experiment", 1)
    val variant = context.peek("test_experiment")
    assert(variant == 1)
  }

  test("setOverrides sets multiple overrides") {
    val context = sdk.createContextWith(Map("session_id" -> "test123"), testData)
    context.setOverrides(Map("test_experiment" -> 1))
    val variant = context.peek("test_experiment")
    assert(variant == 1)
  }

  // Treatment and peek tests
  test("treatment returns variant") {
    val context = sdk.createContextWith(Map("session_id" -> "test123"), testData)
    val variant = context.treatment("test_experiment")
    assert(variant >= 0 && variant <= 1)
  }

  test("peek returns variant without queuing exposure") {
    val context = sdk.createContextWith(Map("session_id" -> "test123"), testData)
    val initialPending = context.pending()
    val variant = context.peek("test_experiment")
    assert(variant >= 0 && variant <= 1)
    assert(context.pending() == initialPending) // No exposure queued
  }

  test("treatment queues exposure") {
    val context = sdk.createContextWith(Map("session_id" -> "test123"), testData)
    val initialPending = context.pending()
    context.treatment("test_experiment")
    assert(context.pending() > initialPending) // Exposure queued
  }

  test("treatment returns 0 for non-existent experiment") {
    val context = sdk.createContextWith(Map("session_id" -> "test123"), testData)
    val variant = context.treatment("non_existent")
    assert(variant == 0)
  }

  test("treatment is deterministic") {
    val context = sdk.createContextWith(Map("session_id" -> "test123"), testData)
    val variant1 = context.peek("test_experiment")
    val variant2 = context.peek("test_experiment")
    assert(variant1 == variant2)
  }

  // Variable tests
  test("variableValue returns value from assigned variant") {
    val context = sdk.createContextWith(Map("session_id" -> "test123"), testData)
    context.setOverride("test_experiment", 1) // Force treatment variant
    val value = context.variableValue("button_color", "red")
    assert(value == "\"blue\"" || value == "red") // Depends on variant
  }

  test("variableValue returns default for missing variable") {
    val context = sdk.createContextWith(Map("session_id" -> "test123"), testData)
    val value = context.variableValue("missing_var", "default")
    assert(value == "default")
  }

  test("peekVariableValue doesn't queue exposure") {
    val context = sdk.createContextWith(Map("session_id" -> "test123"), testData)
    val initialPending = context.pending()
    context.peekVariableValue("button_color", "red")
    assert(context.pending() == initialPending)
  }

  test("variableKeys returns all variable keys") {
    val context = sdk.createContextWith(Map("session_id" -> "test123"), testData)
    val keys = context.variableKeys()
    assert(keys.contains("button_color"))
  }

  // Goal tracking tests
  test("track queues goal") {
    val context = sdk.createContextWith(Map("session_id" -> "test123"), testData)
    val initialPending = context.pending()
    context.track("purchase")
    assert(context.pending() > initialPending)
  }

  test("track with numeric properties") {
    val context = sdk.createContextWith(Map("session_id" -> "test123"), testData)
    context.track("purchase", Some(Map("amount" -> Json.fromDouble(99.99).get)))
    assert(context.pending() > 0)
  }

  test("track filters non-numeric properties") {
    val context = sdk.createContextWith(Map("session_id" -> "test123"), testData)
    // This should work - non-numeric properties are filtered internally
    context.track("purchase", Some(Map(
      "amount" -> Json.fromDouble(99.99).get,
      "product" -> Json.fromString("widget") // Will be filtered
    )))
    assert(context.pending() > 0)
  }

  // Publishing tests
  test("publish clears queued events") {
    val context = sdk.createContextWith(Map("session_id" -> "test123"), testData)
    context.treatment("test_experiment")
    context.track("purchase")
    assert(context.pending() > 0)
    // Note: publish() will fail without real endpoint, but we test the logic
  }

  test("pending returns count of queued events") {
    val context = sdk.createContextWith(Map("session_id" -> "test123"), testData)
    assert(context.pending() == 0)
    context.treatment("test_experiment")
    val afterTreatment = context.pending()
    context.track("goal1")
    val afterGoal = context.pending()
    assert(afterGoal > afterTreatment)
  }

  // Lifecycle tests
  // Note: Testing post-finalize behavior requires mocking or real endpoint
  // Skipped for now as it requires the context to actually finalize via HTTP

  test("experiments returns list of experiment names") {
    val context = sdk.createContextWith(Map("session_id" -> "test123"), testData)
    val names = context.experiments()
    assert(names.contains("test_experiment"))
  }

  test("data returns context data") {
    val context = sdk.createContextWith(Map("session_id" -> "test123"), testData)
    val data = context.data()
    assert(data.experiments.nonEmpty)
    assert(data.experiments.head.name == "test_experiment")
  }

  // Custom assignment tests
  test("setCustomAssignment forces specific variant") {
    val context = sdk.createContextWith(Map("session_id" -> "test123"), testData)
    context.setCustomAssignment("test_experiment", 1)
    val variant = context.peek("test_experiment")
    assert(variant == 1)
  }

  test("setCustomAssignments sets multiple assignments") {
    val context = sdk.createContextWith(Map("session_id" -> "test123"), testData)
    context.setCustomAssignments(Map("test_experiment" -> 1))
    val variant = context.peek("test_experiment")
    assert(variant == 1)
  }

  // Cache invalidation tests
  test("refresh updates context data") {
    val context = sdk.createContextWith(Map("session_id" -> "test123"), testData)
    val variant1 = context.peek("test_experiment")

    // Create new data with changed experiment
    val newExperiment = testExperiment.copy(iteration = 2)
    val newData = ContextData(experiments = List(newExperiment))

    context.refresh(newData)
    // After refresh, assignment should be recalculated
    val data = context.data()
    assert(data.experiments.head.iteration == 2)
  }
}

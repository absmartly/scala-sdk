package com.absmartly.sdk

import org.scalatest.funsuite.AnyFunSuite
import io.circe.Json
import io.circe.parser._
import scala.concurrent.ExecutionContext.Implicits.global

class ContextTest extends AnyFunSuite {

  val config: SDKConfig = SDKConfig(
    endpoint = "https://test.absmartly.io/v1",
    apiKey = "test-key",
    application = "test-app",
    environment = "test"
  )

  val sdk = new SDK(config)

  val expTestAb: ExperimentData = ExperimentData(
    id = 1,
    name = "exp_test_ab",
    unitType = "session_id",
    iteration = 1,
    seedHi = 3603515,
    seedLo = 233373850,
    split = List(0.5, 0.5),
    trafficSeedHi = 449867249,
    trafficSeedLo = 455443629,
    trafficSplit = List(0.0, 1.0),
    fullOnVariant = 0,
    applications = List(ExperimentApplication("website")),
    variants = List(
      ExperimentVariant("A", None),
      ExperimentVariant("B", parse("""{"banner.border":1,"banner.size":"large"}""").toOption)
    ),
    audienceStrict = false,
    audience = None
  )

  val expTestAbc: ExperimentData = ExperimentData(
    id = 2,
    name = "exp_test_abc",
    unitType = "session_id",
    iteration = 1,
    seedHi = 55006150,
    seedLo = 47189152,
    split = List(0.34, 0.33, 0.33),
    trafficSeedHi = 705671872,
    trafficSeedLo = 212903484,
    trafficSplit = List(0.0, 1.0),
    fullOnVariant = 0,
    applications = List(ExperimentApplication("website")),
    variants = List(
      ExperimentVariant("A", None),
      ExperimentVariant("B", parse("""{"button.color":"blue"}""").toOption),
      ExperimentVariant("C", parse("""{"button.color":"red"}""").toOption)
    ),
    audienceStrict = false,
    audience = Some("")
  )

  val expTestNotEligible: ExperimentData = ExperimentData(
    id = 3,
    name = "exp_test_not_eligible",
    unitType = "user_id",
    iteration = 1,
    seedHi = 503266407,
    seedLo = 144942754,
    split = List(0.34, 0.33, 0.33),
    trafficSeedHi = 87768905,
    trafficSeedLo = 511357582,
    trafficSplit = List(0.99, 0.01),
    fullOnVariant = 0,
    applications = List(ExperimentApplication("website")),
    variants = List(
      ExperimentVariant("A", None),
      ExperimentVariant("B", parse("""{"card.width":"80%"}""").toOption),
      ExperimentVariant("C", parse("""{"card.width":"75%"}""").toOption)
    ),
    audienceStrict = false,
    audience = Some("{}")
  )

  val expTestFullon: ExperimentData = ExperimentData(
    id = 4,
    name = "exp_test_fullon",
    unitType = "session_id",
    iteration = 1,
    seedHi = 856061641,
    seedLo = 990838475,
    split = List(0.25, 0.25, 0.25, 0.25),
    trafficSeedHi = 360868579,
    trafficSeedLo = 330937933,
    trafficSplit = List(0.0, 1.0),
    fullOnVariant = 2,
    applications = List(ExperimentApplication("website")),
    variants = List(
      ExperimentVariant("A", None),
      ExperimentVariant("B", parse("""{"submit.color":"red","submit.shape":"circle"}""").toOption),
      ExperimentVariant("C", parse("""{"submit.color":"blue","submit.shape":"rect"}""").toOption),
      ExperimentVariant("D", parse("""{"submit.color":"green","submit.shape":"square"}""").toOption)
    ),
    audienceStrict = false,
    audience = Some("null")
  )

  val testUnits: Map[String, String] = Map(
    "session_id" -> "e791e240fcd3df7d238cfc285f475e8152fcc0ec",
    "user_id" -> "123456789"
  )

  val testData: ContextData = ContextData(experiments = List(expTestAb, expTestAbc, expTestNotEligible, expTestFullon))

  def createContext(
    units: Map[String, String] = testUnits,
    data: ContextData = testData,
    options: ContextOptions = ContextOptions()
  ): Context = {
    sdk.createContextWith(units, data, options)
  }

  test("context is ready immediately with sync creation") {
    val context = createContext()
    assert(context.isReady())
    assert(!context.isFailed())
    assert(!context.isFinalized())
  }

  test("data returns context data") {
    val context = createContext()
    val data = context.data()
    assert(data.experiments.nonEmpty)
    assert(data.experiments.length == 4)
  }

  test("experiments returns list of experiment names") {
    val context = createContext()
    val names = context.experiments()
    assert(names.contains("exp_test_ab"))
    assert(names.contains("exp_test_abc"))
    assert(names.contains("exp_test_not_eligible"))
    assert(names.contains("exp_test_fullon"))
  }

  test("setUnit and getUnit work correctly") {
    val context = createContext(units = Map.empty)
    context.setUnit("session_id", "abc123")
    assert(context.getUnit("session_id").contains("abc123"))
  }

  test("setUnit allows setting same value again") {
    val context = createContext(units = Map("session_id" -> "abc123"))
    context.setUnit("session_id", "abc123")
    assert(context.getUnit("session_id").contains("abc123"))
  }

  test("setUnit throws on duplicate unit type with different value") {
    val context = createContext(units = Map("session_id" -> "abc123"))
    assertThrows[IllegalStateException] {
      context.setUnit("session_id", "different")
    }
  }

  test("setUnit throws on blank UID") {
    val context = createContext(units = Map.empty)
    assertThrows[IllegalArgumentException] {
      context.setUnit("session_id", "")
    }
  }

  test("setUnit throws on whitespace-only UID") {
    val context = createContext(units = Map.empty)
    assertThrows[IllegalArgumentException] {
      context.setUnit("session_id", "   ")
    }
  }

  test("setUnits sets multiple units") {
    val context = createContext(units = Map.empty)
    context.setUnits(Map(
      "session_id" -> "abc123",
      "user_id" -> "user456"
    ))
    assert(context.getUnit("session_id").contains("abc123"))
    assert(context.getUnit("user_id").contains("user456"))
  }

  test("getUnits returns all units") {
    val context = createContext(units = Map("session_id" -> "abc123", "user_id" -> "user456"))
    val units = context.getUnits()
    assert(units.size == 2)
    assert(units("session_id") == "abc123")
    assert(units("user_id") == "user456")
  }

  test("getUnit returns None for missing unit") {
    val context = createContext(units = Map.empty)
    assert(context.getUnit("missing").isEmpty)
  }

  test("setAttribute and getAttribute work correctly") {
    val context = createContext()
    context.setAttribute("age", Json.fromInt(25))
    assert(context.getAttribute("age").flatMap(_.asNumber).flatMap(_.toInt).contains(25))
  }

  test("setAttributes sets multiple attributes") {
    val context = createContext()
    context.setAttributes(Map(
      "age" -> Json.fromInt(25),
      "country" -> Json.fromString("US")
    ))
    assert(context.getAttribute("age").isDefined)
    assert(context.getAttribute("country").flatMap(_.asString).contains("US"))
  }

  test("getAttributes returns all attributes") {
    val context = createContext()
    context.setAttributes(Map(
      "age" -> Json.fromInt(25),
      "country" -> Json.fromString("US")
    ))
    val attrs = context.getAttributes()
    assert(attrs.size == 2)
  }

  test("setAttribute overwrites previous value") {
    val context = createContext()
    context.setAttribute("age", Json.fromInt(25))
    context.setAttribute("age", Json.fromInt(30))
    assert(context.getAttribute("age").flatMap(_.asNumber).flatMap(_.toInt).contains(30))
  }

  test("getAttribute returns None for missing attribute") {
    val context = createContext()
    assert(context.getAttribute("missing").isEmpty)
  }

  test("treatment returns expected variant for exp_test_ab") {
    val context = createContext()
    val variant = context.treatment("exp_test_ab")
    assert(variant == 1)
  }

  test("treatment returns expected variant for exp_test_abc") {
    val context = createContext()
    val variant = context.treatment("exp_test_abc")
    assert(variant == 2)
  }

  test("treatment returns expected variant for exp_test_fullon") {
    val context = createContext()
    val variant = context.treatment("exp_test_fullon")
    assert(variant == 2)
  }

  test("treatment returns 0 for non-existent experiment") {
    val context = createContext()
    val variant = context.treatment("non_existent")
    assert(variant == 0)
  }

  test("treatment is deterministic") {
    val context = createContext()
    val variant1 = context.peek("exp_test_ab")
    val variant2 = context.peek("exp_test_ab")
    assert(variant1 == variant2)
  }

  test("treatment queues exposure") {
    val context = createContext()
    assert(context.pending() == 0)
    context.treatment("exp_test_ab")
    assert(context.pending() == 1)
  }

  test("treatment queues exposure after peek") {
    val context = createContext()
    context.peek("exp_test_ab")
    assert(context.pending() == 0)
    context.treatment("exp_test_ab")
    assert(context.pending() == 1)
  }

  test("treatment queues exposure only once") {
    val context = createContext()
    context.treatment("exp_test_ab")
    assert(context.pending() == 1)
    context.treatment("exp_test_ab")
    assert(context.pending() == 1)
  }

  test("treatment returns 0 for not eligible experiment without user_id unit") {
    val context = createContext(units = Map("session_id" -> "e791e240fcd3df7d238cfc285f475e8152fcc0ec"))
    val variant = context.treatment("exp_test_not_eligible")
    assert(variant == 0)
  }

  test("peek returns variant without queuing exposure") {
    val context = createContext()
    val initialPending = context.pending()
    context.peek("exp_test_ab")
    assert(context.pending() == initialPending)
  }

  test("peek returns expected variant for exp_test_ab") {
    val context = createContext()
    val variant = context.peek("exp_test_ab")
    assert(variant == 1)
  }

  test("peek returns expected variant for exp_test_abc") {
    val context = createContext()
    val variant = context.peek("exp_test_abc")
    assert(variant == 2)
  }

  test("peek returns 0 for non-existent experiment") {
    val context = createContext()
    val variant = context.peek("non_existent")
    assert(variant == 0)
  }

  test("setOverride forces specific variant") {
    val context = createContext()
    context.setOverride("exp_test_ab", 0)
    val variant = context.peek("exp_test_ab")
    assert(variant == 0)
  }

  test("setOverride forces variant 1") {
    val context = createContext()
    context.setOverride("exp_test_ab", 1)
    val variant = context.peek("exp_test_ab")
    assert(variant == 1)
  }

  test("setOverrides sets multiple overrides") {
    val context = createContext()
    context.setOverrides(Map("exp_test_ab" -> 0, "exp_test_abc" -> 1))
    assert(context.peek("exp_test_ab") == 0)
    assert(context.peek("exp_test_abc") == 1)
  }

  test("treatment with override queues exposure") {
    val context = createContext()
    context.setOverride("exp_test_ab", 0)
    context.treatment("exp_test_ab")
    assert(context.pending() == 1)
  }

  test("setCustomAssignment forces specific variant") {
    val context = createContext()
    context.setCustomAssignment("exp_test_ab", 0)
    val variant = context.peek("exp_test_ab")
    assert(variant == 0)
  }

  test("setCustomAssignments sets multiple assignments") {
    val context = createContext()
    context.setCustomAssignments(Map("exp_test_ab" -> 0, "exp_test_abc" -> 1))
    assert(context.peek("exp_test_ab") == 0)
    assert(context.peek("exp_test_abc") == 1)
  }

  test("treatment with custom assignment queues exposure") {
    val context = createContext()
    context.setCustomAssignment("exp_test_ab", 0)
    context.treatment("exp_test_ab")
    assert(context.pending() == 1)
  }

  test("variableValue returns value from assigned variant") {
    val context = createContext()
    context.setOverride("exp_test_ab", 1)
    val value = context.variableValue("banner.border", "default")
    assert(value == "1")
  }

  test("variableValue returns default for missing variable") {
    val context = createContext()
    val value = context.variableValue("missing_var", "default")
    assert(value == "default")
  }

  test("variableValue returns default when unassigned (variant 0)") {
    val context = createContext()
    context.setOverride("exp_test_ab", 0)
    val value = context.variableValue("banner.border", "default")
    assert(value == "default")
  }

  test("variableValue returns value when overridden") {
    val context = createContext()
    context.setOverride("exp_test_ab", 1)
    val bannerSize = context.variableValue("banner.size", "default")
    assert(bannerSize == "\"large\"")
  }

  test("variableValue queues exposure") {
    val context = createContext()
    assert(context.pending() == 0)
    context.variableValue("banner.border", "default")
    assert(context.pending() >= 1)
  }

  test("peekVariableValue doesn't queue exposure") {
    val context = createContext()
    val initialPending = context.pending()
    context.peekVariableValue("banner.border", "default")
    assert(context.pending() == initialPending)
  }

  test("peekVariableValue returns default for missing variable") {
    val context = createContext()
    val value = context.peekVariableValue("missing_var", "default")
    assert(value == "default")
  }

  test("peekVariableValue returns default when unassigned") {
    val context = createContext()
    context.setOverride("exp_test_ab", 0)
    val value = context.peekVariableValue("banner.border", "default")
    assert(value == "default")
  }

  test("variableKeys returns all variable keys") {
    val context = createContext()
    val keys = context.variableKeys()
    assert(keys.contains("banner.border"))
    assert(keys.contains("banner.size"))
    assert(keys.contains("button.color"))
    assert(keys.contains("submit.color"))
    assert(keys.contains("submit.shape"))
    assert(keys.contains("card.width"))
  }

  test("variableKeys maps to correct experiments") {
    val context = createContext()
    val keys = context.variableKeys()
    assert(keys("banner.border").contains("exp_test_ab"))
    assert(keys("button.color").contains("exp_test_abc"))
    assert(keys("submit.color").contains("exp_test_fullon"))
  }

  test("track queues goal") {
    val context = createContext()
    assert(context.pending() == 0)
    context.track("purchase")
    assert(context.pending() == 1)
  }

  test("track with numeric properties") {
    val context = createContext()
    context.track("purchase", Some(Map("amount" -> Json.fromDouble(99.99).get)))
    assert(context.pending() == 1)
  }

  test("track filters non-numeric properties") {
    val context = createContext()
    context.track("purchase", Some(Map(
      "amount" -> Json.fromDouble(99.99).get,
      "product" -> Json.fromString("widget")
    )))
    assert(context.pending() == 1)
  }

  test("track with null properties") {
    val context = createContext()
    context.track("purchase", None)
    assert(context.pending() == 1)
  }

  test("track increments pending count") {
    val context = createContext()
    context.track("goal1")
    context.track("goal2")
    assert(context.pending() == 2)
  }

  test("pending returns combined count of exposures and goals") {
    val context = createContext()
    assert(context.pending() == 0)
    context.treatment("exp_test_ab")
    assert(context.pending() == 1)
    context.track("goal1")
    assert(context.pending() == 2)
  }

  test("refresh updates context data") {
    val context = createContext()
    context.peek("exp_test_ab")

    val newExperiment = expTestAb.copy(iteration = 2)
    val newData = ContextData(experiments = List(newExperiment, expTestAbc, expTestNotEligible, expTestFullon))

    context.refresh(newData)
    val data = context.data()
    assert(data.experiments.head.iteration == 2)
  }

  test("refresh keeps overrides") {
    val context = createContext()
    context.setOverride("exp_test_ab", 0)
    assert(context.peek("exp_test_ab") == 0)

    val newData = ContextData(experiments = List(
      expTestAb.copy(iteration = 2),
      expTestAbc,
      expTestNotEligible,
      expTestFullon
    ))
    context.refresh(newData)

    assert(context.peek("exp_test_ab") == 0)
  }

  test("refresh keeps custom assignments") {
    val context = createContext()
    context.setCustomAssignment("exp_test_ab", 0)
    assert(context.peek("exp_test_ab") == 0)

    val newData = ContextData(experiments = List(
      expTestAb.copy(iteration = 2),
      expTestAbc,
      expTestNotEligible,
      expTestFullon
    ))
    context.refresh(newData)

    assert(context.peek("exp_test_ab") == 0)
  }

  test("refresh clears cache when experiment iteration changes") {
    val context = createContext()
    val initial = context.peek("exp_test_ab")

    val newData = ContextData(experiments = List(
      expTestAb.copy(iteration = 2),
      expTestAbc,
      expTestNotEligible,
      expTestFullon
    ))
    context.refresh(newData)

    val afterRefresh = context.peek("exp_test_ab")
    assert(context.data().experiments.head.iteration == 2)
  }

  test("refresh clears cache when experiment id changes") {
    val context = createContext()
    context.peek("exp_test_ab")

    val newData = ContextData(experiments = List(
      expTestAb.copy(id = 99),
      expTestAbc,
      expTestNotEligible,
      expTestFullon
    ))
    context.refresh(newData)

    assert(context.data().experiments.head.id == 99)
  }

  test("refresh clears cache when fullOnVariant changes") {
    val context = createContext()
    context.peek("exp_test_ab")

    val newData = ContextData(experiments = List(
      expTestAb.copy(fullOnVariant = 1),
      expTestAbc,
      expTestNotEligible,
      expTestFullon
    ))
    context.refresh(newData)

    assert(context.data().experiments.head.fullOnVariant == 1)
  }

  test("refresh clears cache when trafficSplit changes") {
    val context = createContext()
    context.peek("exp_test_ab")

    val newData = ContextData(experiments = List(
      expTestAb.copy(trafficSplit = List(0.7, 0.3)),
      expTestAbc,
      expTestNotEligible,
      expTestFullon
    ))
    context.refresh(newData)

    assert(context.data().experiments.head.trafficSplit == List(0.7, 0.3))
  }

  test("refresh clears cache when experiment stopped (removed from list)") {
    val context = createContext()
    context.peek("exp_test_ab")
    assert(context.pending() == 0)

    val newData = ContextData(experiments = List(expTestAbc, expTestNotEligible, expTestFullon))
    context.refresh(newData)

    val variant = context.peek("exp_test_ab")
    assert(variant == 0)
  }

  test("refresh picks up new experiment") {
    val newExp = ExperimentData(
      id = 6,
      name = "exp_test_new",
      unitType = "session_id",
      iteration = 1,
      seedHi = 934590467,
      seedLo = 714771373,
      split = List(0.5, 0.5),
      trafficSeedHi = 940553836,
      trafficSeedLo = 270705624,
      trafficSplit = List(0.0, 1.0),
      fullOnVariant = 0,
      applications = List(ExperimentApplication("website")),
      variants = List(
        ExperimentVariant("A", None),
        ExperimentVariant("B", parse("""{"show-modal":true}""").toOption)
      ),
      audienceStrict = false,
      audience = None
    )

    val context = createContext()
    val newData = ContextData(experiments = List(newExp) ++ testData.experiments)
    context.refresh(newData)

    val variant = context.peek("exp_test_new")
    assert(variant >= 0)
  }

  test("refresh re-queues exposures even when experiment unchanged") {
    val context = createContext()
    context.treatment("exp_test_ab")
    assert(context.pending() == 1)

    context.refresh(testData)

    context.treatment("exp_test_ab")
    assert(context.pending() == 2)
  }

  test("setOverride does not check finalized state") {
    val context = createContext()
    context.setOverride("exp_test_ab", 1)
    assert(context.peek("exp_test_ab") == 1)
  }

  test("setOverride on unknown experiment") {
    val context = createContext()
    context.setOverride("unknown_exp", 1)
    assert(context.peek("unknown_exp") == 1)
  }

  test("setCustomAssignment throws after finalized") {
    val context = createContext()
    val field = context.getClass.getDeclaredField("_finalized")
    field.setAccessible(true)
    field.setBoolean(context, true)
    assertThrows[IllegalStateException] {
      context.setCustomAssignment("exp_test_ab", 1)
    }
  }

  test("setUnit throws after finalized") {
    val context = createContext()
    val field = context.getClass.getDeclaredField("_finalized")
    field.setAccessible(true)
    field.setBoolean(context, true)
    assertThrows[IllegalStateException] {
      context.setUnit("new_unit", "value")
    }
  }

  test("setAttribute throws after finalized") {
    val context = createContext()
    val field = context.getClass.getDeclaredField("_finalized")
    field.setAccessible(true)
    field.setBoolean(context, true)
    assertThrows[IllegalStateException] {
      context.setAttribute("attr", Json.fromInt(1))
    }
  }

  test("treatment throws after finalized") {
    val context = createContext()
    val field = context.getClass.getDeclaredField("_finalized")
    field.setAccessible(true)
    field.setBoolean(context, true)
    assertThrows[IllegalStateException] {
      context.treatment("exp_test_ab")
    }
  }

  test("peek throws after finalized") {
    val context = createContext()
    val field = context.getClass.getDeclaredField("_finalized")
    field.setAccessible(true)
    field.setBoolean(context, true)
    assertThrows[IllegalStateException] {
      context.peek("exp_test_ab")
    }
  }

  test("variableValue throws after finalized") {
    val context = createContext()
    val field = context.getClass.getDeclaredField("_finalized")
    field.setAccessible(true)
    field.setBoolean(context, true)
    assertThrows[IllegalStateException] {
      context.variableValue("key", "default")
    }
  }

  test("peekVariableValue throws after finalized") {
    val context = createContext()
    val field = context.getClass.getDeclaredField("_finalized")
    field.setAccessible(true)
    field.setBoolean(context, true)
    assertThrows[IllegalStateException] {
      context.peekVariableValue("key", "default")
    }
  }

  test("track throws after finalized") {
    val context = createContext()
    val field = context.getClass.getDeclaredField("_finalized")
    field.setAccessible(true)
    field.setBoolean(context, true)
    assertThrows[IllegalStateException] {
      context.track("goal")
    }
  }

  test("publish throws after finalized") {
    val context = createContext()
    val field = context.getClass.getDeclaredField("_finalized")
    field.setAccessible(true)
    field.setBoolean(context, true)
    assertThrows[IllegalStateException] {
      context.publish()
    }
  }

  test("refresh throws after finalized") {
    val context = createContext()
    val field = context.getClass.getDeclaredField("_finalized")
    field.setAccessible(true)
    field.setBoolean(context, true)
    assertThrows[IllegalStateException] {
      context.refresh(testData)
    }
  }

  test("treatment with audience match returns assigned variant") {
    val audienceExp = expTestAb.copy(
      audience = Some("""{"filter":[{"gte":[{"var":"age"},{"value":20}]}]}""")
    )
    val audienceData = ContextData(experiments = List(audienceExp))
    val context = createContext(data = audienceData)
    context.setAttribute("age", Json.fromInt(25))
    val variant = context.treatment("exp_test_ab")
    assert(variant == 1)
  }

  test("treatment with audience mismatch in non-strict mode returns assigned variant") {
    val audienceExp = expTestAb.copy(
      audience = Some("""{"filter":[{"gte":[{"var":"age"},{"value":20}]}]}"""),
      audienceStrict = false
    )
    val audienceData = ContextData(experiments = List(audienceExp))
    val context = createContext(data = audienceData)
    context.setAttribute("age", Json.fromInt(15))
    val variant = context.treatment("exp_test_ab")
    assert(variant == 1)
  }

  test("treatment with audience mismatch in strict mode returns control variant") {
    val audienceExp = expTestAb.copy(
      audience = Some("""{"filter":[{"gte":[{"var":"age"},{"value":20}]}]}"""),
      audienceStrict = true
    )
    val audienceData = ContextData(experiments = List(audienceExp))
    val context = createContext(data = audienceData)
    context.setAttribute("age", Json.fromInt(15))
    val variant = context.treatment("exp_test_ab")
    assert(variant == 0)
  }

  test("peek with audience match returns assigned variant") {
    val audienceExp = expTestAb.copy(
      audience = Some("""{"filter":[{"gte":[{"var":"age"},{"value":20}]}]}""")
    )
    val audienceData = ContextData(experiments = List(audienceExp))
    val context = createContext(data = audienceData)
    context.setAttribute("age", Json.fromInt(25))
    val variant = context.peek("exp_test_ab")
    assert(variant == 1)
  }

  test("peek with audience mismatch in non-strict mode returns assigned variant") {
    val audienceExp = expTestAb.copy(
      audience = Some("""{"filter":[{"gte":[{"var":"age"},{"value":20}]}]}"""),
      audienceStrict = false
    )
    val audienceData = ContextData(experiments = List(audienceExp))
    val context = createContext(data = audienceData)
    context.setAttribute("age", Json.fromInt(15))
    val variant = context.peek("exp_test_ab")
    assert(variant == 1)
  }

  test("context with initial overrides from options") {
    val context = createContext(
      options = ContextOptions(overrides = Map("exp_test_ab" -> 0))
    )
    assert(context.peek("exp_test_ab") == 0)
  }

  test("context with initial custom assignments from options") {
    val context = createContext(
      options = ContextOptions(cassignments = Map("exp_test_ab" -> 0))
    )
    assert(context.peek("exp_test_ab") == 0)
  }

  test("treatment queues multiple exposures for different experiments") {
    val context = createContext()
    context.treatment("exp_test_ab")
    context.treatment("exp_test_abc")
    assert(context.pending() == 2)
  }

  test("multiple treatments followed by track gives correct pending") {
    val context = createContext()
    context.treatment("exp_test_ab")
    context.treatment("exp_test_abc")
    context.track("purchase")
    context.track("click")
    assert(context.pending() == 4)
  }

  test("variableValue for submit.color returns fullon variant value") {
    val context = createContext()
    val value = context.variableValue("submit.color", "default")
    assert(value == "\"blue\"")
  }

  test("variableValue for submit.shape returns fullon variant value") {
    val context = createContext()
    val value = context.variableValue("submit.shape", "default")
    assert(value == "\"rect\"")
  }

  test("variableValue for button.color returns assigned variant value") {
    val context = createContext()
    val value = context.variableValue("button.color", "default")
    assert(value == "\"red\"")
  }

  test("peekVariableValue for button.color returns assigned variant value") {
    val context = createContext()
    val value = context.peekVariableValue("button.color", "default")
    assert(value == "\"red\"")
  }

  test("variableValue queues exposure after peekVariableValue") {
    val context = createContext()
    context.peekVariableValue("banner.border", "default")
    assert(context.pending() == 0)
    context.variableValue("banner.border", "default")
    assert(context.pending() >= 1)
  }

  test("variableValue queues exposure only once") {
    val context = createContext()
    context.variableValue("banner.border", "default")
    val pending1 = context.pending()
    context.variableValue("banner.border", "default")
    assert(context.pending() == pending1)
  }

  test("peekVariableValue for card.width returns default when not eligible") {
    val context = createContext(units = Map("session_id" -> "e791e240fcd3df7d238cfc285f475e8152fcc0ec"))
    val value = context.peekVariableValue("card.width", "100%")
    assert(value == "100%")
  }

  test("isFinalized is false initially") {
    val context = createContext()
    assert(!context.isFinalized())
  }

  test("isFinalizing is false initially") {
    val context = createContext()
    assert(!context.isFinalizing())
  }

  test("track with empty properties map") {
    val context = createContext()
    context.track("event", Some(Map.empty))
    assert(context.pending() == 1)
  }

  test("track with only non-numeric properties filters all") {
    val context = createContext()
    context.track("event", Some(Map("name" -> Json.fromString("test"))))
    assert(context.pending() == 1)
  }

  test("treatment for fullon experiment returns fullon variant") {
    val context = createContext()
    val variant = context.treatment("exp_test_fullon")
    assert(variant == 2)
  }

  test("context with empty experiment data") {
    val emptyData = ContextData(experiments = List.empty)
    val context = createContext(data = emptyData)
    assert(context.isReady())
    assert(context.experiments().isEmpty)
    assert(context.treatment("any") == 0)
  }

  test("context with no units still works for non-unit experiments") {
    val context = createContext(units = Map.empty)
    val variant = context.treatment("exp_test_ab")
    assert(variant == 0)
  }

  test("setOverride takes precedence over custom assignment") {
    val context = createContext()
    context.setCustomAssignment("exp_test_ab", 0)
    context.setOverride("exp_test_ab", 1)
    assert(context.peek("exp_test_ab") == 1)
  }

  test("override takes precedence over natural assignment") {
    val context = createContext()
    val natural = context.peek("exp_test_ab")
    context.setOverride("exp_test_ab", if (natural == 0) 1 else 0)
    val overridden = context.peek("exp_test_ab")
    assert(overridden != natural)
  }

  test("refresh with same data retains assignments") {
    val context = createContext()
    val variant1 = context.peek("exp_test_ab")
    context.refresh(testData)
    val variant2 = context.peek("exp_test_ab")
    assert(variant1 == variant2)
  }

  test("refresh does not clear override cache") {
    val context = createContext()
    context.setOverride("exp_test_ab", 0)
    context.peek("exp_test_ab")

    context.refresh(ContextData(experiments = List(
      expTestAb.copy(iteration = 99, id = 99),
      expTestAbc,
      expTestNotEligible,
      expTestFullon
    )))

    assert(context.peek("exp_test_ab") == 0)
  }

  test("multiple experiments can have different unit types") {
    val context = createContext()
    val abVariant = context.peek("exp_test_ab")
    val notEligibleVariant = context.peek("exp_test_not_eligible")
    assert(abVariant >= 0)
    assert(notEligibleVariant >= 0)
  }

  test("variableKeys returns empty map for empty data") {
    val emptyData = ContextData(experiments = List.empty)
    val context = createContext(data = emptyData)
    assert(context.variableKeys().isEmpty)
  }
}

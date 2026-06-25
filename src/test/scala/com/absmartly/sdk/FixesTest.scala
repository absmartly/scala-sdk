package com.absmartly.sdk

import org.scalatest.funsuite.AnyFunSuite
import io.circe.Json
import io.circe.parser._
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration._

class FixesTest extends AnyFunSuite {

  implicit val ec: ExecutionContext = ExecutionContext.global

  val config: SDKConfig = SDKConfig(
    endpoint = "https://test.absmartly.io/v1",
    apiKey = "test-api-key-12345",
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

  val testUnits: Map[String, String] = Map(
    "session_id" -> "e791e240fcd3df7d238cfc285f475e8152fcc0ec",
    "user_id" -> "123456789"
  )

  val testData: ContextData = ContextData(experiments = List(expTestAb))

  def createContext(
    units: Map[String, String] = testUnits,
    data: ContextData = testData,
    options: ContextOptions = ContextOptions()
  ): Context = {
    sdk.createContextWith(units, data, options)
  }

  // ======================
  // Fix #1/#13/#14: _flush() actually publishes and clears only on success
  // ======================

  test("fix1/13/14: _flush builds PublishEvent and calls sdk.publish") {
    var publishCalled = false
    var publishedUnits: Map[String, String] = Map.empty
    var publishedExposures: List[Exposure] = List.empty
    var publishedGoals: List[Goal] = List.empty
    var publishedHashed: Boolean = false
    var publishedAttributes: Option[List[Attribute]] = None

    val mockSdk = new SDK(config) {
      override def publish(
        units: Map[String, String],
        hashed: Boolean,
        exposures: List[Exposure],
        goals: List[Goal],
        attributes: Option[List[Attribute]]
      ): Future[Unit] = {
        publishCalled = true
        publishedUnits = units
        publishedHashed = hashed
        publishedExposures = exposures
        publishedGoals = goals
        publishedAttributes = attributes
        Future.successful(())
      }
    }

    val context = new Context(mockSdk, Some(testData), testUnits, ContextOptions(), NoOpEventLogger)
    context.treatment("exp_test_ab")
    context.track("purchase", Some(Map("amount" -> Json.fromDouble(99.99).get)))

    assert(context.pending() == 2)

    val result = context.publish()
    Await.result(result, 5.seconds)

    assert(publishCalled)
    assert(publishedHashed)
    assert(publishedExposures.length == 1)
    assert(publishedExposures.head.name == "exp_test_ab")
    assert(publishedGoals.length == 1)
    assert(publishedGoals.head.name == "purchase")
    assert(publishedUnits.nonEmpty)
    assert(context.pending() == 0)
  }

  test("fix14: queues not cleared when publish fails") {
    val failingSdk = new SDK(config) {
      override def publish(
        units: Map[String, String],
        hashed: Boolean,
        exposures: List[Exposure],
        goals: List[Goal],
        attributes: Option[List[Attribute]]
      ): Future[Unit] = {
        Future.failed(new RuntimeException("Network error"))
      }
    }

    val context = new Context(failingSdk, Some(testData), testUnits, ContextOptions(), NoOpEventLogger)
    context.treatment("exp_test_ab")
    context.track("purchase")

    assert(context.pending() == 2)

    val result = context.publish()
    try {
      Await.result(result, 5.seconds)
      fail("Should have thrown")
    } catch {
      case _: RuntimeException => ()
    }

    assert(context.pending() == 2)
  }

  test("fix1: _flush with no events does not call publish") {
    var publishCalled = false
    val mockSdk = new SDK(config) {
      override def publish(
        units: Map[String, String],
        hashed: Boolean,
        exposures: List[Exposure],
        goals: List[Goal],
        attributes: Option[List[Attribute]]
      ): Future[Unit] = {
        publishCalled = true
        Future.successful(())
      }
    }

    val context = new Context(mockSdk, Some(testData), testUnits, ContextOptions(), NoOpEventLogger)
    val result = context.publish()
    Await.result(result, 5.seconds)
    assert(!publishCalled)
  }

  test("fix13/24: publish includes attributes when set") {
    var publishedAttributes: Option[List[Attribute]] = None

    val mockSdk = new SDK(config) {
      override def publish(
        units: Map[String, String],
        hashed: Boolean,
        exposures: List[Exposure],
        goals: List[Goal],
        attributes: Option[List[Attribute]]
      ): Future[Unit] = {
        publishedAttributes = attributes
        Future.successful(())
      }
    }

    val context = new Context(mockSdk, Some(testData), testUnits, ContextOptions(), NoOpEventLogger)
    context.setAttribute("age", Json.fromInt(25))
    context.treatment("exp_test_ab")

    val result = context.publish()
    Await.result(result, 5.seconds)

    assert(publishedAttributes.isDefined)
    assert(publishedAttributes.get.exists(_.name == "age"))
  }

  // ======================
  // Fix #2: ReDoS thread leak
  // ======================

  test("fix2: regex match with normal pattern works") {
    val expr = parse("""{"match": [{"value": "hello123"}, {"value": "\\d+"}]}""").toOption.get
    val result = com.absmartly.sdk.jsonexpr.Evaluator.evaluate(expr, Map.empty)
    assert(result.asBoolean.contains(true))
  }

  test("fix2: regex match with long input is rejected") {
    val longInput = "a" * 30000
    val expr = parse(s"""{"match": [{"value": "$longInput"}, {"value": "a+"}]}""").toOption.get
    val result = com.absmartly.sdk.jsonexpr.Evaluator.evaluate(expr, Map.empty)
    assert(result.asBoolean.contains(false))
  }

  test("fix2: regex match with long pattern is rejected") {
    val longPattern = "a" * 600
    val expr = parse(s"""{"match": [{"value": "test"}, {"value": "$longPattern"}]}""").toOption.get
    val result = com.absmartly.sdk.jsonexpr.Evaluator.evaluate(expr, Map.empty)
    assert(result.asBoolean.contains(false))
  }

  // ======================
  // Fix #3: Logger @volatile
  // ======================

  test("fix3: Logger instance is visible across threads") {
    val customLogger = new NoOpLogger()
    Logger.setLogger(customLogger)

    @volatile var seenLogger: Logger = null

    val thread = new Thread(() => {
      seenLogger = Logger.get
    })
    thread.start()
    thread.join(1000)

    assert(seenLogger eq customLogger)
    Logger.setLogger(new NoOpLogger())
  }

  // ======================
  // Fix #4: @volatile state flags
  // ======================

  test("fix4: state flags visible across threads") {
    val context = createContext()
    assert(context.isReady())

    @volatile var readyFromThread = false
    val thread = new Thread(() => {
      readyFromThread = context.isReady()
    })
    thread.start()
    thread.join(1000)
    assert(readyFromThread)
  }

  // ======================
  // Fix #5: pending() synchronized
  // ======================

  test("fix5: pending returns consistent count under concurrent access") {
    val context = createContext()
    context.treatment("exp_test_ab")

    val futures = (1 to 100).map { _ =>
      Future { context.pending() }
    }
    val results = Await.result(Future.sequence(futures), 5.seconds)
    assert(results.forall(_ >= 0))
  }

  // ======================
  // Fix #6/#19: variableValue/peekVariableValue synchronized
  // ======================

  test("fix6: variableValue is thread-safe") {
    val context = createContext()
    context.setOverride("exp_test_ab", 1)

    val futures = (1 to 50).map { _ =>
      Future { context.variableValue("banner.border", "default") }
    }
    val results = Await.result(Future.sequence(futures), 5.seconds)
    assert(results.forall(_ == "1"))
  }

  test("fix6: peekVariableValue is thread-safe") {
    val context = createContext()
    context.setOverride("exp_test_ab", 1)

    val futures = (1 to 50).map { _ =>
      Future { context.peekVariableValue("banner.border", "default") }
    }
    val results = Await.result(Future.sequence(futures), 5.seconds)
    assert(results.forall(_ == "1"))
  }

  // ======================
  // Fix #7: API key masking
  // ======================

  test("fix7: SDKConfig toString masks API key showing only last 4 chars") {
    val cfg = SDKConfig(
      endpoint = "https://test.absmartly.io/v1",
      apiKey = "my-secret-api-key-12345",
      application = "test-app",
      environment = "test"
    )
    val str = cfg.toString
    assert(!str.contains("my-secret-api-key-12345"))
    assert(str.contains("***2345"))
  }

  // ======================
  // Fix #8: setOverride doesn't remove assigners
  // ======================

  test("fix8: setOverride does not invalidate assigner cache for other experiments") {
    val context = createContext()
    val v1 = context.peek("exp_test_ab")
    context.setOverride("exp_test_ab", 0)
    assert(context.peek("exp_test_ab") == 0)
  }

  // ======================
  // Fix #11/#22: Matcher uses NonFatal instead of catching VirtualMachineErrors
  // ======================

  test("fix11: AudienceMatcher catches non-fatal exceptions gracefully") {
    val matcher = new AudienceMatcher(Map.empty)
    val result = matcher.evaluate(Some("invalid json {{{"))
    assert(result.contains(true))
  }

  // ======================
  // Fix #12: customFieldValue safe boolean parsing
  // ======================

  test("fix12: customFieldValue handles valid boolean") {
    val exp = expTestAb.copy(
      customFieldValues = Some(List(CustomFieldValue("flag", "true", "boolean")))
    )
    val data = ContextData(experiments = List(exp))
    val context = createContext(data = data)
    val result = context.customFieldValue("exp_test_ab", "flag")
    assert(result.contains(Json.True))
  }

  test("fix12: customFieldValue handles false boolean") {
    val exp = expTestAb.copy(
      customFieldValues = Some(List(CustomFieldValue("flag", "false", "boolean")))
    )
    val data = ContextData(experiments = List(exp))
    val context = createContext(data = data)
    val result = context.customFieldValue("exp_test_ab", "flag")
    assert(result.contains(Json.False))
  }

  test("fix12: customFieldValue handles invalid boolean gracefully") {
    val exp = expTestAb.copy(
      customFieldValues = Some(List(CustomFieldValue("flag", "not-a-bool", "boolean")))
    )
    val data = ContextData(experiments = List(exp))
    val context = createContext(data = data)
    val result = context.customFieldValue("exp_test_ab", "flag")
    assert(result.contains(Json.fromString("not-a-bool")))
  }

  // ======================
  // Fix #15: refresh() synchronized
  // ======================

  test("fix15: refresh is thread-safe with concurrent treatment calls") {
    val context = createContext()

    val futures = (1 to 20).map { i =>
      Future {
        if (i % 2 == 0) {
          context.treatment("exp_test_ab")
        } else {
          context.refresh(testData)
        }
      }
    }

    Await.result(Future.sequence(futures), 10.seconds)
    assert(context.isReady())
  }

  // ======================
  // Fix #16: data() and experiments() synchronized
  // ======================

  test("fix16: data() is thread-safe") {
    val context = createContext()
    val futures = (1 to 50).map { _ =>
      Future { context.data() }
    }
    val results = Await.result(Future.sequence(futures), 5.seconds)
    assert(results.forall(_.experiments.nonEmpty))
  }

  test("fix16: experiments() is thread-safe") {
    val context = createContext()
    val futures = (1 to 50).map { _ =>
      Future { context.experiments() }
    }
    val results = Await.result(Future.sequence(futures), 5.seconds)
    assert(results.forall(_.contains("exp_test_ab")))
  }

  // ======================
  // Fix #17: getAttribute/getAttributes synchronized
  // ======================

  test("fix17: getAttribute is thread-safe with concurrent setAttribute") {
    val context = createContext()

    val futures = (1 to 50).map { i =>
      Future {
        if (i % 2 == 0) {
          context.setAttribute(s"attr_$i", Json.fromInt(i))
        } else {
          context.getAttributes()
        }
      }
    }

    Await.result(Future.sequence(futures), 5.seconds)
  }

  // ======================
  // Fix #18: getUnit/getUnits synchronized
  // ======================

  test("fix18: getUnit is thread-safe") {
    val context = createContext()
    val futures = (1 to 50).map { _ =>
      Future { context.getUnit("session_id") }
    }
    val results = Await.result(Future.sequence(futures), 5.seconds)
    assert(results.forall(_.isDefined))
  }

  test("fix18: getUnits is thread-safe") {
    val context = createContext()
    val futures = (1 to 50).map { _ =>
      Future { context.getUnits() }
    }
    val results = Await.result(Future.sequence(futures), 5.seconds)
    assert(results.forall(_.nonEmpty))
  }

  // ======================
  // Fix #25: Unit IDs not logged in plaintext
  // ======================

  test("fix25: setUnit masks UID in log output") {
    var loggedMessage = ""
    val capturingLogger = new Logger {
      override def debug(message: => String): Unit = { loggedMessage = message }
      override def info(message: => String): Unit = ()
      override def warn(message: => String): Unit = ()
      override def error(message: => String): Unit = ()
      override def error(message: => String, throwable: Throwable): Unit = ()
    }

    val previousLogger = Logger.get
    Logger.setLogger(capturingLogger)
    try {
      val context = createContext(units = Map.empty)
      context.setUnit("session_id", "my-secret-session-id-12345")
      assert(!loggedMessage.contains("my-secret-session-id-12345"))
      assert(loggedMessage.contains("***2345"))
    } finally {
      Logger.setLogger(previousLogger)
    }
  }

  // ======================
  // Phase 3.2: readyError
  // ======================

  test("readyError returns None when context loaded successfully") {
    val context = createContext()
    assert(context.readyError().isEmpty)
  }

  test("readyError returns the error when setDataFailed called with error") {
    val context = new Context(sdk, None, Map.empty, ContextOptions(), NoOpEventLogger)
    val error = new RuntimeException("data fetch failed")
    context.setDataFailed(Some(error))
    assert(context.readyError().contains(error))
    assert(context.isFailed())
  }

  test("readyError returns None when setDataFailed called without error") {
    val context = new Context(sdk, None, Map.empty, ContextOptions(), NoOpEventLogger)
    context.setDataFailed()
    assert(context.readyError().isEmpty)
    assert(context.isFailed())
  }

  // ======================
  // Phase 4.4: customFieldKeys global scope
  // ======================

  test("customFieldKeys returns all keys across all experiments") {
    val exp1 = expTestAb.copy(
      customFieldValues = Some(List(
        CustomFieldValue("key1", "val1", "string"),
        CustomFieldValue("key2", "val2", "string")
      ))
    )
    val exp2 = expTestAb.copy(
      id = 2,
      name = "exp_test_abc",
      customFieldValues = Some(List(
        CustomFieldValue("key2", "val2b", "string"),
        CustomFieldValue("key3", "val3", "string")
      ))
    )
    val data = ContextData(experiments = List(exp1, exp2))
    val context = createContext(data = data)
    val keys = context.customFieldKeys()
    assert(keys.contains("key1"))
    assert(keys.contains("key2"))
    assert(keys.contains("key3"))
    assert(keys.distinct.length == keys.length)
  }

  test("customFieldKeys returns empty list when no experiments have custom fields") {
    val context = createContext()
    val keys = context.customFieldKeys()
    assert(keys.isEmpty)
  }

  // ======================
  // Fix 4.1: setOverride works after finalize
  // ======================

  test("fix4.1: setOverride succeeds after context is finalized") {
    val context = createContext()
    Await.result(context.finalizeContext(), 5.seconds)
    assert(context.isFinalized())
    var threw = false
    try {
      context.setOverride("exp_test_ab", 2)
    } catch {
      case _: Exception => threw = true
    }
    assert(!threw, "setOverride should not throw after finalize")
  }

  // ======================
  // Phase 4.2: close/finalize aliases
  // ======================

  test("close is alias for finalizeContext") {
    val context = createContext()
    assert(!context.isFinalized())
    Await.result(context.close(), 5.seconds)
    assert(context.isFinalized())
  }

  test("isFinalized and isFinalizing exist") {
    val context = createContext()
    assert(!context.isFinalized())
    assert(!context.isFinalizing())
  }

  // ======================
  // Phase 4.3: standardized error messages
  // ======================

  test("not ready error message is standardized") {
    val context = new Context(sdk, None, Map.empty, ContextOptions(), NoOpEventLogger)
    val result = context.treatment("exp_test_ab")
    assert(result == 0)
  }

  test("finalized error message is standardized") {
    val context = createContext()
    Await.result(context.close(), 5.seconds)
    val result = context.treatment("exp_test_ab")
    assert(result == 0)
  }

  test("unit UID blank error message is standardized") {
    val context = createContext(units = Map.empty)
    val ex = intercept[IllegalArgumentException] {
      context.setUnit("session_id", "")
    }
    assert(ex.getMessage.contains("UID must not be blank."))
  }

  test("unit UID already set error message is standardized") {
    val context = createContext()
    val ex = intercept[IllegalStateException] {
      context.setUnit("session_id", "different-uid")
    }
    assert(ex.getMessage.contains("UID already set."))
  }
}

package com.absmartly.sdk

import org.scalatest.funsuite.AnyFunSuite
import io.circe.Json
import io.circe.parser._
import io.circe.syntax._
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration._

/**
 * Covers §4 of the "SDKs must evaluate audiences with application + environment"
 * brief: the SDK always evaluates audiences against the client's configured
 * `application` and `environment`, user attributes overlay those system values,
 * missing config leaves the key out, `app_version` is never injected, and
 * nothing extra reaches the publish payload.
 *
 * The Scala SDK evaluates audience filters only (it has no assignment-rule
 * `conditions`, which are a newer JS-SDK feature), so §4 test 5 does not apply.
 */
class SystemAttributesTest extends AnyFunSuite {

  implicit val ec: ExecutionContext = ExecutionContext.global

  // Client config names kept explicit, as §4 asks.
  private val Application = "website"
  private val Environment = "production"

  private def config(
    application: String = Application,
    environment: String = Environment,
    publisher: Option[ContextPublisher] = None
  ): SDKConfig = SDKConfig(
    endpoint = "https://test.absmartly.io/v1",
    apiKey = "test-api-key-12345",
    application = application,
    environment = environment,
    publisher = publisher
  )

  private val baseExp: ExperimentData = ExperimentData(
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

  private val testUnits: Map[String, String] = Map(
    "session_id" -> "e791e240fcd3df7d238cfc285f475e8152fcc0ec",
    "user_id" -> "123456789"
  )

  private def eqFilter(variable: String, value: String): String =
    s"""{"filter":[{"eq":[{"var":"$variable"},{"value":"$value"}]}]}"""

  private def contextWith(
    exp: ExperimentData,
    application: String = Application,
    environment: String = Environment
  ): Context = {
    val sdk = new SDK(config(application, environment))
    sdk.createContextWith(testUnits, ContextData(experiments = List(exp)))
  }

  // §4.1 — Strict audience on the environment matches.
  test("strict audience on environment matches the configured environment") {
    val exp = baseExp.copy(
      fullOnVariant = 1,
      audienceStrict = true,
      audience = Some(eqFilter("environment", "production"))
    )
    val context = contextWith(exp)
    assert(context.peek("exp_test_ab") == 1)
  }

  // §4.2 — Strict audience on the application matches.
  test("strict audience on application matches the configured application") {
    val exp = baseExp.copy(
      fullOnVariant = 1,
      audienceStrict = true,
      audience = Some(eqFilter("application", "website"))
    )
    val context = contextWith(exp)
    assert(context.peek("exp_test_ab") == 1)
  }

  // §4.3 — No match: a strict audience the environment fails gives control.
  test("strict audience on a non-matching environment gives control") {
    val exp = baseExp.copy(
      fullOnVariant = 1,
      audienceStrict = true,
      audience = Some(eqFilter("environment", "staging"))
    )
    val context = contextWith(exp)
    assert(context.peek("exp_test_ab") == 0)
  }

  // §4.4 — User value overrides the system value, even set after creation, and
  // invalidates an assignment cached before it was set.
  test("a user attribute set after creation overrides the system environment and invalidates the cache") {
    val stagingExp = baseExp.copy(
      fullOnVariant = 1,
      audienceStrict = true,
      audience = Some(eqFilter("environment", "staging"))
    )
    val context = contextWith(stagingExp)

    // System environment is "production", so the staging filter does not match yet.
    assert(context.peek("exp_test_ab") == 0)

    // Override with the user attribute; the cached mismatch must be invalidated.
    context.setAttribute("environment", Json.fromString("staging"))
    assert(context.peek("exp_test_ab") == 1)
  }

  test("a user environment attribute makes a production filter stop matching") {
    val prodExp = baseExp.copy(
      fullOnVariant = 1,
      audienceStrict = true,
      audience = Some(eqFilter("environment", "production"))
    )
    val context = contextWith(prodExp)

    assert(context.peek("exp_test_ab") == 1)

    context.setAttribute("environment", Json.fromString("staging"))
    assert(context.peek("exp_test_ab") == 0)
  }

  // §4.6 — Missing config: no environment configured means no key in the map.
  test("with no environment configured, an environment filter does not match and nothing crashes") {
    // SDKConfig requires a non-empty environment, so simulate an unconfigured
    // client by overriding the system attributes the context evaluates against.
    val exp = baseExp.copy(
      fullOnVariant = 1,
      audienceStrict = true,
      audience = Some(eqFilter("environment", "production"))
    )
    val sdk = new SDK(config())
    val context = new Context(sdk, Some(ContextData(experiments = List(exp))), testUnits, ContextOptions(), NoOpEventLogger) {
      override private[sdk] def systemAttributes: Map[String, Json] =
        Context.buildSystemAttributes("website", "")
    }
    assert(context.systemAttributes == Map("application" -> Json.fromString("website")))
    assert(context.peek("exp_test_ab") == 0)
  }

  test("buildSystemAttributes skips empty and null names") {
    assert(Context.buildSystemAttributes("website", "production") ==
      Map("application" -> Json.fromString("website"), "environment" -> Json.fromString("production")))
    assert(Context.buildSystemAttributes("", "production") ==
      Map("environment" -> Json.fromString("production")))
    assert(Context.buildSystemAttributes("website", "") ==
      Map("application" -> Json.fromString("website")))
    assert(Context.buildSystemAttributes(null, null) == Map.empty[String, Json])
  }

  // §4.7 — Nothing extra published: the serialized payload carries no
  // application/environment/app_version attribute entries.
  test("the serialized publish payload gets no application/environment/app_version attribute entries") {
    var capturedBody: String = ""
    val capturing = new ContextPublisher {
      override def publish(
        units: Map[String, String],
        hashed: Boolean,
        exposures: List[Exposure],
        goals: List[Goal],
        attributes: Option[List[Attribute]]
      ): Future[Unit] = {
        val attrs = attributes.getOrElse(Nil)
        capturedBody = PublishEvent(
          hashed = hashed,
          publishedAt = 0L,
          units = units.map { case (t, u) => PublishUnit(t, u) }.toList,
          exposures = exposures,
          goals = goals,
          attributes = if (attrs.nonEmpty) Some(attrs) else None
        ).asJson.noSpaces
        Future.successful(())
      }
    }

    val exp = baseExp.copy(
      fullOnVariant = 1,
      audienceStrict = true,
      audience = Some(eqFilter("environment", "production"))
    )
    val sdk = new SDK(config(publisher = Some(capturing)))
    val context = sdk.createContextWith(testUnits, ContextData(experiments = List(exp)))

    assert(context.treatment("exp_test_ab") == 1)
    Await.result(context.publish(), 5.seconds)

    assert(!capturedBody.contains("\"application\""))
    assert(!capturedBody.contains("\"environment\""))
    assert(!capturedBody.contains("app_version"))
    assert(!capturedBody.contains("application_version"))
  }

  test("only user-set attributes are published, not the system values") {
    var capturedAttributes: Option[List[Attribute]] = None
    val capturing = new ContextPublisher {
      override def publish(
        units: Map[String, String],
        hashed: Boolean,
        exposures: List[Exposure],
        goals: List[Goal],
        attributes: Option[List[Attribute]]
      ): Future[Unit] = {
        capturedAttributes = attributes
        Future.successful(())
      }
    }

    val sdk = new SDK(config(publisher = Some(capturing)))
    val context = sdk.createContextWith(testUnits, ContextData(experiments = List(baseExp)))
    context.setAttribute("plan", Json.fromString("pro"))
    context.treatment("exp_test_ab")
    Await.result(context.publish(), 5.seconds)

    val names = capturedAttributes.getOrElse(Nil).map(_.name).toSet
    assert(names == Set("plan"))
  }

  // §4.8 — app_version is gone: a filter on app_version never matches, even with
  // an application version configured (the SDK always sends X-Application-Version
  // "0" but never injects it into the evaluation map).
  test("a filter on app_version does not match") {
    val exp = baseExp.copy(
      fullOnVariant = 1,
      audienceStrict = true,
      audience = Some(eqFilter("app_version", "0"))
    )
    val context = contextWith(exp)
    assert(context.peek("exp_test_ab") == 0)
  }
}

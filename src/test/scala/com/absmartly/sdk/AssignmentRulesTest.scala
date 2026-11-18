package com.absmartly.sdk

import org.scalatest.funsuite.AnyFunSuite
import io.circe.Json
import io.circe.parser._
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration._

/**
 * Assignment rules (`assign` and `split`), ported from the JS SDK's
 * "rules evaluation" tests (javascript-sdk #52, #66, #67) and java-sdk #17.
 * The `exp_test_abc` fixture has the JS seeds: the session unit hashes to
 * ~0.748 with the experiment seed and normal assignment gives variant 2.
 */
class AssignmentRulesTest extends AnyFunSuite {

  implicit val ec: ExecutionContext = ExecutionContext.global

  private var published: List[Exposure] = Nil

  private val capturing = new ContextPublisher {
    def publish(units: Map[String, String], hashed: Boolean, exposures: List[Exposure], goals: List[Goal], attributes: Option[List[Attribute]])
      : Future[Unit] = {
      published = exposures
      Future.successful(())
    }
  }

  private def sdkFor(environment: String = "production"): SDK = new SDK(SDKConfig(
    endpoint = "https://test.absmartly.io/v1",
    apiKey = "test-api-key-12345",
    application = "website",
    environment = environment,
    publisher = Some(capturing)
  ))

  private val sessionId = "e791e240fcd3df7d238cfc285f475e8152fcc0ec"
  private val units = Map("session_id" -> sessionId, "user_id" -> "123456789")

  private val expAbc: ExperimentData = ExperimentData(
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
    audience = Some("""{"filter":[{"value":true}]}""")
  )

  private val usCondition = """{"and":[{"eq":[{"var":"country"},{"value":"US"}]}]}"""

  private def assignRule(variant: Int, conditions: String = usCondition, environments: String = "[]"): String =
    s"""{"name":"US Users","type":"assign","conditions":$conditions,"environments":$environments,"variant":$variant}"""

  private def splitRule(percentages: String, conditions: String = usCondition): String =
    s"""{"name":"US Split","type":"split","conditions":$conditions,"environments":[],"percentages":"$percentages"}"""

  private def rules(rs: String*): Option[String] = Some(s"""{"rules":[${rs.mkString(",")}]}""")

  private def withRules(rs: String*): ExperimentData = expAbc.copy(assignmentRules = rules(rs: _*))

  private def contextWith(
    exp: ExperimentData,
    us: Map[String, String] = units,
    environment: String = "production",
    options: ContextOptions = ContextOptions()
  ): Context =
    sdkFor(environment).createContextWith(us, ContextData(List(exp)), options)

  private def publishedExposures(context: Context): List[Exposure] = {
    published = Nil
    Await.result(context.publish(), 5.seconds)
    published.filter(_.name == "exp_test_abc")
  }

  test("normal assignment gives variant 2 without rules") {
    assert(contextWith(expAbc).treatment("exp_test_abc") == 2)
  }

  test("an assign rule that matches gives its variant") {
    val context = contextWith(withRules(assignRule(1)))
    context.setAttribute("country", Json.fromString("US"))
    assert(context.treatment("exp_test_abc") == 1)
  }

  test("normal assignment when no rule matches") {
    val context = contextWith(withRules(assignRule(1)))
    context.setAttribute("country", Json.fromString("GB"))
    assert(context.treatment("exp_test_abc") == 2)
  }

  test("exposure flags when a rule matches") {
    val context = contextWith(withRules(assignRule(1)))
    context.setAttribute("country", Json.fromString("US"))
    context.treatment("exp_test_abc")
    val exposure = publishedExposures(context).head
    assert(exposure.variant == 1)
    assert(exposure.ruleOverride)
    assert(!exposure.assigned)
    assert(exposure.eligible)
    assert(!exposure.overridden && !exposure.fullOn && !exposure.custom && !exposure.audienceMismatch)
  }

  test("exposure flags when no rule matches") {
    val context = contextWith(withRules(assignRule(1)))
    context.treatment("exp_test_abc")
    val exposure = publishedExposures(context).head
    assert(exposure.variant == 2 && exposure.assigned && !exposure.ruleOverride)
  }

  test("the exposure JSON sent to the collector carries ruleOverride") {
    import io.circe.syntax._
    val context = contextWith(withRules(assignRule(1)))
    context.setAttribute("country", Json.fromString("US"))
    context.treatment("exp_test_abc")
    val exposure = publishedExposures(context).head
    assert(exposure.asJson.hcursor.get[Boolean]("ruleOverride").contains(true))
  }

  test("an override beats a matching rule and is flagged overridden, not ruleOverride") {
    val context = contextWith(withRules(assignRule(1)))
    context.setAttribute("country", Json.fromString("US"))
    context.setOverride("exp_test_abc", 1)
    assert(context.treatment("exp_test_abc") == 1)
    val exposure = publishedExposures(context).head
    assert(exposure.overridden && !exposure.ruleOverride)
  }

  test("a rule wins over a custom assignment and is exposed only once") {
    val context = contextWith(withRules(assignRule(1)))
    context.setAttribute("country", Json.fromString("US"))
    context.setCustomAssignment("exp_test_abc", 2)
    assert(context.treatment("exp_test_abc") == 1)
    assert(context.treatment("exp_test_abc") == 1)
    assert(context.pending() == 1)

    context.setAttribute("country", Json.fromString("GB"))
    assert(context.treatment("exp_test_abc") == 2)
  }

  test("setting a custom assignment after a rule-won exposure does not expose again") {
    val context = contextWith(withRules(assignRule(1)))
    context.setAttribute("country", Json.fromString("US"))
    assert(context.treatment("exp_test_abc") == 1)
    context.setCustomAssignment("exp_test_abc", 2)
    assert(context.treatment("exp_test_abc") == 1)
    assert(context.pending() == 1)
  }

  test("a rule wins over a strict audience mismatch and leaves audienceMismatch false") {
    val exp = withRules(assignRule(1)).copy(
      audienceStrict = true,
      audience = Some("""{"filter":[{"gte":[{"var":"age"},{"value":20}]}]}""")
    )
    val context = contextWith(exp)
    context.setAttribute("country", Json.fromString("US"))
    context.setAttribute("age", Json.fromInt(10))
    assert(context.treatment("exp_test_abc") == 1)
    assert(!publishedExposures(context).head.audienceMismatch)
  }

  test("a rule-won assignment over a strict audience mismatch survives an unrelated attribute change") {
    val exp = withRules(assignRule(1)).copy(
      audienceStrict = true,
      audience = Some("""{"filter":[{"gte":[{"var":"age"},{"value":20}]}]}""")
    )
    val context = new CountingContext(ContextData(List(exp)))
    context.setAttribute("country", Json.fromString("US"))
    context.setAttribute("age", Json.fromInt(10))
    assert(context.treatment("exp_test_abc") == 1)
    assert(context.evaluations == 1)
    context.setAttribute("unrelated", Json.fromString("x"))
    assert(context.treatment("exp_test_abc") == 1)
    assert(context.evaluations == 2)
    assert(context.pending() == 1)
  }

  test("a strict audience mismatch applies when no rule matches") {
    val exp = withRules(assignRule(1)).copy(
      audienceStrict = true,
      audience = Some("""{"filter":[{"gte":[{"var":"age"},{"value":20}]}]}""")
    )
    val context = contextWith(exp)
    context.setAttribute("age", Json.fromInt(10))
    assert(context.treatment("exp_test_abc") == 0)
    assert(publishedExposures(context).head.audienceMismatch)
  }

  test("a rule wins over the full-on variant") {
    val context = contextWith(withRules(assignRule(1)).copy(fullOnVariant = 2))
    context.setAttribute("country", Json.fromString("US"))
    assert(context.treatment("exp_test_abc") == 1)
  }

  test("a rule forcing variant 0 still sets ruleOverride") {
    val context = contextWith(withRules(assignRule(0)))
    context.setAttribute("country", Json.fromString("US"))
    assert(context.treatment("exp_test_abc") == 0)
    assert(publishedExposures(context).head.ruleOverride)
  }

  test("the cached assignment is re-evaluated when an attribute changes the rule result") {
    val context = contextWith(withRules(assignRule(1)))
    assert(context.peek("exp_test_abc") == 2)
    context.setAttribute("country", Json.fromString("US"))
    assert(context.peek("exp_test_abc") == 1)
    context.setAttribute("country", Json.fromString("GB"))
    assert(context.peek("exp_test_abc") == 2)
  }

  test("like the JS SDK, an exposed rule assignment is re-evaluated after an attribute change") {
    // Attribute changes re-evaluate rules (and audiences) even after exposure, as in the JS
    // SDK; the new variant is exposed once more and then stays cached.
    val context = contextWith(withRules(assignRule(1)))
    context.setAttribute("country", Json.fromString("US"))
    assert(context.treatment("exp_test_abc") == 1)
    assert(context.treatment("exp_test_abc") == 1)
    context.setAttribute("country", Json.fromString("GB"))
    assert(context.treatment("exp_test_abc") == 2)
    assert(context.treatment("exp_test_abc") == 2)
    val exposures = publishedExposures(context)
    assert(exposures.map(e => (e.variant, e.ruleOverride)) == List((1, true), (2, false)))
  }

  test("the cache switches when a different rule starts matching") {
    val context = contextWith(withRules(
      assignRule(1),
      assignRule(0, """{"eq":[{"var":"country"},{"value":"GB"}]}""")
    ))
    context.setAttribute("country", Json.fromString("US"))
    assert(context.peek("exp_test_abc") == 1)
    context.setAttribute("country", Json.fromString("GB"))
    assert(context.peek("exp_test_abc") == 0)
  }

  test("variableValue reads the rule's variant") {
    val context = contextWith(withRules(assignRule(1)))
    context.setAttribute("country", Json.fromString("US"))
    assert(context.variableValue("button.color", "none") == "\"blue\"")
  }

  test("the first of several matching rules wins") {
    val context = contextWith(withRules(
      assignRule(1, """{"or":[{"eq":[{"var":"country"},{"value":"US"}]},{"eq":[{"var":"country"},{"value":"CA"}]}]}"""),
      assignRule(0, "null")
    ))
    context.setAttribute("country", Json.fromString("CA"))
    assert(context.peek("exp_test_abc") == 1)
  }

  test("a rule with no conditions always matches") {
    assert(contextWith(withRules(assignRule(1, "null"))).peek("exp_test_abc") == 1)
  }

  test("a rule with multiple and conditions matches only when all hold") {
    val cond = """{"and":[{"eq":[{"var":"country"},{"value":"US"}]},{"gte":[{"var":"age"},{"value":18}]}]}"""
    val context = contextWith(withRules(assignRule(1, cond)))
    context.setAttribute("country", Json.fromString("US"))
    context.setAttribute("age", Json.fromInt(10))
    assert(context.peek("exp_test_abc") == 2)
    context.setAttribute("age", Json.fromInt(30))
    assert(context.peek("exp_test_abc") == 1)
  }

  test("an environment-scoped rule matches only its environments") {
    val exp = withRules(assignRule(1, environments = """["production","qa"]"""))
    val prod = contextWith(exp, environment = "production")
    prod.setAttribute("country", Json.fromString("US"))
    assert(prod.peek("exp_test_abc") == 1)

    val qa = contextWith(exp, environment = "qa")
    qa.setAttribute("country", Json.fromString("US"))
    assert(qa.peek("exp_test_abc") == 1)

    val staging = contextWith(exp, environment = "staging")
    staging.setAttribute("country", Json.fromString("US"))
    assert(staging.peek("exp_test_abc") == 2)
  }

  test("an environment-scoped rule never matches without an environment") {
    val sdk = sdkFor()
    val context = new Context(sdk, ContextData(List(withRules(assignRule(1, environments = """["production"]""")))), units, ContextOptions(), NoOpEventLogger) {
      override private[sdk] def environmentName: Option[String] = None
    }
    context.setAttribute("country", Json.fromString("US"))
    assert(context.peek("exp_test_abc") == 2)
  }

  test("rule conditions see the configured application and environment") {
    val byEnv = contextWith(withRules(assignRule(1, """{"eq":[{"var":"environment"},{"value":"production"}]}""")))
    assert(byEnv.peek("exp_test_abc") == 1)
    val byApp = contextWith(withRules(assignRule(1, """{"eq":[{"var":"application"},{"value":"website"}]}""")))
    assert(byApp.peek("exp_test_abc") == 1)
    val other = contextWith(withRules(assignRule(1, """{"eq":[{"var":"environment"},{"value":"staging"}]}""")))
    assert(other.peek("exp_test_abc") == 2)
    other.setAttribute("environment", Json.fromString("staging"))
    assert(other.peek("exp_test_abc") == 1)
  }

  test("the rule scope uses the configured environment while conditions see the user environment") {
    // Configured production, user environment=staging: the staging-scoped rule must not match
    // (scope comes from the client config), while the production-scoped rule's condition sees
    // the user value.
    val context = contextWith(withRules(
      assignRule(1, "null", environments = """["staging"]"""),
      assignRule(0, """{"eq":[{"var":"environment"},{"value":"staging"}]}""", environments = """["production"]""")
    ))
    context.setAttribute("environment", Json.fromString("staging"))
    assert(context.treatment("exp_test_abc") == 0)
    assert(publishedExposures(context).head.ruleOverride)
  }

  test("a rule wins over a zero-traffic split") {
    val context = contextWith(withRules(assignRule(1)).copy(trafficSplit = List(1.0, 0.0)))
    context.setAttribute("country", Json.fromString("US"))
    assert(context.treatment("exp_test_abc") == 1)
    val e = publishedExposures(context).head
    assert(e.ruleOverride && e.eligible && !e.assigned)
  }

  test("cross-sdk scenario 306: a rule wins over traffic split and full-on") {
    def exp(id: Int, name: String, split: List[Double], trafficSplit: List[Double], fullOn: Int, withRule: Boolean) =
      ExperimentData(
        id = id,
        name = name,
        unitType = "session_id",
        iteration = 1,
        seedHi = 0,
        seedLo = 0,
        split = split,
        trafficSeedHi = 0,
        trafficSeedLo = 0,
        trafficSplit = trafficSplit,
        fullOnVariant = fullOn,
        applications = List(ExperimentApplication("website")),
        variants = split.indices.map(i => ExperimentVariant(('A' + i).toChar.toString, None)).toList,
        assignmentRules = if (withRule) Some("""{"rules":[{"name":"a","type":"assign","variant":1}]}""") else None
      )
    val data = ContextData(List(
      exp(430, "exp_rules_traffic", List(0.0, 1.0), List(1.0, 0.0), 0, withRule = true),
      exp(431, "exp_rules_traffic_plain", List(0.0, 1.0), List(1.0, 0.0), 0, withRule = false),
      exp(432, "exp_rules_fullon", List(0.0, 0.0, 1.0), List(0.0, 1.0), 2, withRule = true)
    ))
    val context = sdkFor().createContextWith(Map("session_id" -> "svk-unit-1"), data)
    assert(context.treatment("exp_rules_traffic") == 1)
    assert(context.treatment("exp_rules_traffic_plain") == 0)
    assert(context.treatment("exp_rules_fullon") == 1)
    assert(context.pending() == 3)
    published = Nil
    Await.result(context.publish(), 5.seconds)
    val flags = published.map(e => (e.name, e.variant, e.assigned, e.eligible, e.fullOn, e.ruleOverride))
    assert(flags == List(
      ("exp_rules_traffic", 1, false, true, false, true),
      ("exp_rules_traffic_plain", 0, true, false, false, false),
      ("exp_rules_fullon", 1, false, true, false, true)
    ))
  }

  test("an integral assign variant beyond Int range is out of bounds and stops rule evaluation") {
    val exp = withRules(
      """{"type":"assign","variant":2147483648,"conditions":null}""",
      assignRule(0, "null")
    )
    assert(contextWith(exp).peek("exp_test_abc") == 2)
    val negative = withRules(
      """{"type":"assign","variant":-2147483649,"conditions":null}""",
      assignRule(0, "null")
    )
    assert(contextWith(negative).peek("exp_test_abc") == 2)
  }

  test("an integral assign variant far beyond Int range saturates, never wraps, and stops rule evaluation") {
    Seq("4294967297", "1e20", "-1e20", "9007199254740993").foreach { variant =>
      val exp = withRules(s"""{"type":"assign","variant":$variant,"conditions":null}""", assignRule(1, "null"))
      assert(contextWith(exp).peek("exp_test_abc") == 2, variant)
    }
  }

  test("a non-finite assign variant is skipped like JS Infinity, so the next rule wins") {
    Seq("1e400", "-1e400", "1e999999999").foreach { variant =>
      val exp = withRules(s"""{"type":"assign","variant":$variant,"conditions":null}""", assignRule(1, "null"))
      assert(contextWith(exp).peek("exp_test_abc") == 1, variant)
    }
  }

  test("an assign variant is read as the JS double it parses to") {
    val tiny = withRules("""{"type":"assign","variant":1e-400,"conditions":null}""", assignRule(1, "null"))
    assert(contextWith(tiny).peek("exp_test_abc") == 0)
    val nearOne = withRules("""{"type":"assign","variant":1.0000000000000001,"conditions":null}""", assignRule(0, "null"))
    assert(contextWith(nearOne).peek("exp_test_abc") == 1)
  }

  test("an out-of-bounds or negative assign variant falls back to normal assignment") {
    assert(contextWith(withRules(assignRule(3, "null"))).peek("exp_test_abc") == 2)
    assert(contextWith(withRules(assignRule(-1, "null"))).peek("exp_test_abc") == 2)
  }

  test("an out-of-bounds assign variant stops rule evaluation") {
    assert(contextWith(withRules(assignRule(5, "null"), assignRule(1, "null"))).peek("exp_test_abc") == 2)
  }

  test("invalid assignmentRules JSON falls back to normal assignment") {
    val context = contextWith(expAbc.copy(assignmentRules = Some("not json {{")))
    context.setAttribute("country", Json.fromString("US"))
    assert(context.peek("exp_test_abc") == 2)
  }

  test("rules with an invalid action, non-array environments or non-object conditions are skipped") {
    val exp = withRules(
      """{"type":"unknown","variant":1}""",
      """{"type":"assign","variant":1.5}""",
      """{"type":"assign","variant":1,"environments":"production"}""",
      """{"type":"assign","variant":1,"conditions":[{"value":true}]}""",
      assignRule(0, "null")
    )
    assert(contextWith(exp).peek("exp_test_abc") == 0)
  }

  test("a rule whose conditions throw is skipped in favour of the next rule") {
    // Reading any variable throws, so the first rule's conditions fail to evaluate.
    val throwingVars = new scala.collection.immutable.AbstractMap[String, Json] {
      def get(key: String): Option[Json] = throw new RuntimeException("boom")
      def iterator: Iterator[(String, Json)] = throw new RuntimeException("boom")
      def removed(key: String): Map[String, Json] = this
      def updated[V1 >: Json](key: String, value: V1): Map[String, V1] = this
    }
    val rs = AudienceMatcher.parseRules(rules(assignRule(1), assignRule(2, "null")).get).get
    assert(new AudienceMatcher(throwingVars).evaluateRules(rs, None).contains(RuleAction.Assign(2)))
  }

  test("an audience whose evaluation throws is a mismatch") {
    val throwingVars = new scala.collection.immutable.AbstractMap[String, Json] {
      def get(key: String): Option[Json] = throw new RuntimeException("boom")
      def iterator: Iterator[(String, Json)] = throw new RuntimeException("boom")
      def removed(key: String): Map[String, Json] = this
      def updated[V1 >: Json](key: String, value: V1): Map[String, V1] = this
    }
    val audience = """{"filter":[{"eq":[{"var":"country"},{"value":"US"}]}]}"""
    assert(new AudienceMatcher(throwingVars).evaluate(Some(audience)).contains(false))
  }

  test("a refresh that drops the experiment does not keep the rule variant") {
    val context = contextWith(withRules(assignRule(1)))
    context.setAttribute("country", Json.fromString("US"))
    assert(context.treatment("exp_test_abc") == 1)
    context.refresh(ContextData(List.empty))
    assert(context.treatment("exp_test_abc") == 0)
  }

  test("a refresh that removes the rules re-assigns normally") {
    val context = contextWith(withRules(assignRule(1)))
    context.setAttribute("country", Json.fromString("US"))
    assert(context.peek("exp_test_abc") == 1)
    context.refresh(ContextData(List(expAbc)))
    assert(context.peek("exp_test_abc") == 2)
  }

  test("a refresh that changes the rules re-evaluates them") {
    val context = contextWith(withRules(assignRule(1)))
    context.setAttribute("country", Json.fromString("US"))
    assert(context.peek("exp_test_abc") == 1)
    context.refresh(ContextData(List(withRules(assignRule(0)))))
    assert(context.peek("exp_test_abc") == 0)
  }

  test("assignmentRules sent as an object only drops that experiment's rules") {
    val json = """{"experiments":[{"id":2,"name":"exp_test_abc","unitType":"session_id","iteration":1,
      "seedHi":55006150,"seedLo":47189152,"split":[0.34,0.33,0.33],"trafficSeedHi":705671872,
      "trafficSeedLo":212903484,"trafficSplit":[0,1],"fullOnVariant":0,"applications":[],
      "variants":[{"name":"A","config":null},{"name":"B","config":null},{"name":"C","config":null}],
      "assignmentRules":{"rules":[]}}]}"""
    val data = decode[ContextData](json)
    assert(data.isRight)
    assert(data.toOption.get.experiments.head.assignmentRules.isEmpty)

    val asString = decode[ContextData](json.replace("""{"rules":[]}""", "\"{\\\"rules\\\":[]}\""))
    assert(asString.toOption.get.experiments.head.assignmentRules.contains("""{"rules":[]}"""))
  }

  test("unusable assignmentRules values only drop that experiment's rules") {
    def experiment(id: Int, name: String, rulesField: String) =
      s"""{"id":$id,"name":"$name","unitType":"session_id","iteration":1,
       "seedHi":55006150,"seedLo":47189152,"split":[0.34,0.33,0.33],"trafficSeedHi":705671872,
       "trafficSeedLo":212903484,"trafficSplit":[0,1],"fullOnVariant":0,"applications":[],
       "variants":[{"name":"A","config":null},{"name":"B","config":null},{"name":"C","config":null}]$rulesField}"""
    val ruleOk = Json.fromString(s"""{"rules":[${assignRule(1, "null")}]}""").noSpaces
    List(
      "",
      ""","assignmentRules":null""",
      ",\"assignmentRules\":\"\"",
      ""","assignmentRules":"{}"""",
      ""","assignmentRules":"not json"""",
      ""","assignmentRules":7""",
      ""","assignmentRules":true""",
      ""","assignmentRules":{"rules":[]}""",
      ""","assignmentRules":[1]"""
    ).foreach { field =>
      val json = s"""{"experiments":[${experiment(2, "exp_test_abc", field)},${experiment(3, "exp_rules", s""","assignmentRules":$ruleOk""")}]}"""
      val data = decode[ContextData](json)
      assert(data.isRight, field)
      val context = sdkFor().createContextWith(units, data.toOption.get)
      assert(context.isReady() && !context.isFailed(), field)
      assert(context.treatment("exp_test_abc") == 2, field)
      assert(context.treatment("exp_rules") == 1, field)
    }
  }

  test("unknown fields at every level of the context payload are ignored") {
    val unknown = """"futureScalar":1,"futureObject":{"a":[1,{"b":null}]},"futureArray":[true,"x",{}]"""
    val rule =
      s"""{"name":"US","type":"assign","variant":1,"conditions":$usCondition,"environments":[],$unknown}"""
    val rulesJson = s"""{"rules":[$rule],$unknown}"""
    val json = s"""{"experiments":[
      {"id":2,"name":"exp_test_abc","unitType":"session_id","iteration":1,
       "seedHi":55006150,"seedLo":47189152,"split":[0.34,0.33,0.33],"trafficSeedHi":705671872,
       "trafficSeedLo":212903484,"trafficSplit":[0,1],"fullOnVariant":0,
       "applications":[{"name":"website",$unknown}],
       "variants":[{"name":"A","config":null,$unknown},{"name":"B","config":null},{"name":"C","config":null}],
       "customFieldValues":[{"name":"f","value":"v","type":"string",$unknown}],
       "assignmentRules":${Json.fromString(rulesJson).noSpaces},$unknown},
      {"id":3,"name":"exp_plain","unitType":"session_id","iteration":1,
       "seedHi":55006150,"seedLo":47189152,"split":[0.34,0.33,0.33],"trafficSeedHi":705671872,
       "trafficSeedLo":212903484,"trafficSplit":[0,1],"fullOnVariant":0,"applications":[],
       "variants":[{"name":"A","config":null},{"name":"B","config":null},{"name":"C","config":null,$unknown}],
       $unknown}
    ],$unknown}"""
    val data = decode[ContextData](json)
    assert(data.isRight, data)
    val context = sdkFor().createContextWith(units, data.toOption.get)
    context.setAttribute("country", Json.fromString("US"))
    assert(context.treatment("exp_test_abc") == 1)
    assert(context.treatment("exp_plain") == 2)
    assert(context.customFieldValue("exp_test_abc", "f").contains(Json.fromString("v")))
  }

  // ---- split rules ----

  test("a split rule hashes the unit with the experiment seed") {
    val context = contextWith(withRules(splitRule("70/10/20")))
    context.setAttribute("country", Json.fromString("US"))
    assert(context.treatment("exp_test_abc") == 1)
  }

  test("a split rule assigns the only variant with a non-zero share") {
    val context = contextWith(withRules(splitRule("0/100/0")))
    context.setAttribute("country", Json.fromString("US"))
    assert(context.treatment("exp_test_abc") == 1)
  }

  test("a split rule equal to the experiment split keeps the natural variant") {
    val context = contextWith(withRules(splitRule("34/33/33")))
    context.setAttribute("country", Json.fromString("US"))
    assert(context.treatment("exp_test_abc") == 2)
    assert(publishedExposures(context).head.ruleOverride)
  }

  test("a split rule sets the same exposure flags as an assign rule") {
    val context = contextWith(withRules(splitRule("0/100/0")))
    context.setAttribute("country", Json.fromString("US"))
    context.treatment("exp_test_abc")
    val e = publishedExposures(context).head
    assert(e.variant == 1 && e.ruleOverride && !e.assigned && e.eligible && !e.custom && !e.audienceMismatch)
  }

  test("a split rule wins over a custom assignment") {
    val context = contextWith(withRules(splitRule("0/100/0")))
    context.setAttribute("country", Json.fromString("US"))
    context.setCustomAssignment("exp_test_abc", 2)
    assert(context.treatment("exp_test_abc") == 1)
  }

  test("a malformed split is skipped in favour of the next rule") {
    List("50/abc/50", "50/-10/60", "30/30/30", "").foreach { p =>
      val context = contextWith(withRules(splitRule(p), assignRule(0, "null")))
      context.setAttribute("country", Json.fromString("US"))
      assert(context.peek("exp_test_abc") == 0, p)
    }
    val numeric = contextWith(withRules(
      """{"type":"split","percentages":100,"conditions":null}""",
      assignRule(0, "null")
    ))
    assert(numeric.peek("exp_test_abc") == 0)
  }

  test("a split with the wrong number of shares stops rule evaluation") {
    val context = contextWith(withRules(splitRule("0/100"), assignRule(1, "null")))
    context.setAttribute("country", Json.fromString("US"))
    assert(context.treatment("exp_test_abc") == 2)
  }

  test("the split sum tolerance matches the backend") {
    assert(RuleAction.parseSplitPercentages("50/50.014").isDefined)
    assert(RuleAction.parseSplitPercentages("50/50.015").isEmpty)
    assert(RuleAction.parseSplitPercentages("49.99/50").isDefined)
    assert(RuleAction.parseSplitPercentages("20/30/50").contains(List(0.2, 0.3, 0.5)))
    assert(RuleAction.parseSplitPercentages(" 20abc/80").contains(List(0.2, 0.8)))
  }

  test("non-finite split percentages are malformed, not an error") {
    assert(RuleAction.parseSplitPercentages("1e309/0").isEmpty)
    assert(RuleAction.parseSplitPercentages("Infinity/0").isEmpty)
    assert(RuleAction.parseSplitPercentages("1e308/1e308").isEmpty)
    val context = contextWith(withRules(splitRule("1e309/0/0"), assignRule(0, "null")))
    context.setAttribute("country", Json.fromString("US"))
    assert(context.treatment("exp_test_abc") == 0)
  }

  test("a split rule without its unit gives variant 0 and stops, then re-resolves once the unit is set") {
    val context = contextWith(withRules(splitRule("0/100/0"), assignRule(2, "null")), us = Map("user_id" -> "123456789"))
    context.setAttribute("country", Json.fromString("US"))
    assert(context.peek("exp_test_abc") == 0)
    context.setUnit("session_id", sessionId)
    assert(context.peek("exp_test_abc") == 1)
  }

  test("a split rule without its unit gives the full-on variant on a full-on experiment") {
    val context = contextWith(withRules(splitRule("0/100/0")).copy(fullOnVariant = 2), us = Map("user_id" -> "123456789"))
    context.setAttribute("country", Json.fromString("US"))
    assert(context.peek("exp_test_abc") == 2)
  }

  test("an assign rule's cached assignment is kept when the unit is set later") {
    val context = contextWith(withRules(assignRule(1)), us = Map("user_id" -> "123456789"))
    context.setAttribute("country", Json.fromString("US"))
    assert(context.treatment("exp_test_abc") == 1)
    context.setUnit("session_id", sessionId)
    assert(context.treatment("exp_test_abc") == 1)
    assert(context.pending() == 1)
  }

  test("a split rule matching on the previous rule's variant re-resolves once the unit is set") {
    val context = contextWith(withRules(splitRule("0/100/0"), assignRule(0, "null")), us = Map("user_id" -> "123456789"))
    assert(context.peek("exp_test_abc") == 0)
    context.setAttribute("country", Json.fromString("US"))
    assert(context.peek("exp_test_abc") == 0)
    context.setUnit("session_id", sessionId)
    assert(context.peek("exp_test_abc") == 1)
  }

  test("an exposed split keeps variant 0 after its unit is set and is exposed only once") {
    val context = contextWith(withRules(splitRule("0/100/0")), us = Map("user_id" -> "123456789"))
    context.setAttribute("country", Json.fromString("US"))
    assert(context.treatment("exp_test_abc") == 0)
    context.setUnit("session_id", sessionId)
    context.setAttribute("unrelated", Json.True)
    assert(context.treatment("exp_test_abc") == 0)
    assert(publishedExposures(context).map(_.variant) == List(0))
  }

  test("an exposed split keeps variant 0 after its unit is set when a custom assignment lost to it") {
    val context = contextWith(withRules(splitRule("0/100/0")), us = Map("user_id" -> "123456789"))
    context.setAttribute("country", Json.fromString("US"))
    context.setCustomAssignment("exp_test_abc", 2)
    assert(context.treatment("exp_test_abc") == 0)
    context.setUnit("session_id", sessionId)
    assert(context.treatment("exp_test_abc") == 0)
    assert(publishedExposures(context).map(_.variant) == List(0))
  }

  test("rules are parsed once per context data, not on every lookup") {
    var parses = 0
    val context = new Context(sdkFor(), ContextData(List(withRules(assignRule(1)))), units, ContextOptions(), NoOpEventLogger) {
      override private[sdk] def parseRules(assignmentRules: String): Option[List[Json]] = {
        parses += 1
        super.parseRules(assignmentRules)
      }
    }
    context.setAttribute("country", Json.fromString("US"))
    assert(context.peek("exp_test_abc") == 1)
    context.setAttribute("country", Json.fromString("GB"))
    assert(context.peek("exp_test_abc") == 2)
    context.setAttribute("country", Json.fromString("US"))
    assert(context.peek("exp_test_abc") == 1)
    assert(parses == 1)

    context.refresh(ContextData(List(withRules(assignRule(0)))))
    assert(context.peek("exp_test_abc") == 0)
    assert(parses == 2)
  }

  test("a same-outcome revalidation is remembered, so later reads do not evaluate the rules again") {
    val context = new CountingContext(ContextData(List(withRules(assignRule(1)))))
    context.setAttribute("country", Json.fromString("US"))
    assert(context.treatment("exp_test_abc") == 1)
    assert(context.evaluations == 1)

    context.setAttribute("unrelated", Json.True)
    (1 to 3).foreach(_ => assert(context.treatment("exp_test_abc") == 1))
    assert(context.evaluations == 2)

    context.refresh(ContextData(List(withRules(assignRule(1)))))
    (1 to 3).foreach(_ => assert(context.treatment("exp_test_abc") == 1))
    assert(context.evaluations == 2)

    context.refresh(ContextData(List(withRules(assignRule(1), assignRule(0, "null")))))
    (1 to 3).foreach(_ => assert(context.treatment("exp_test_abc") == 1))
    assert(context.evaluations == 3)
    assert(context.pending() == 1)
  }

  private class CountingContext(data: ContextData)
      extends Context(sdkFor(), data, units, ContextOptions(), NoOpEventLogger) {
    var evaluations = 0
    var writes = 0
    override private[sdk] def resolveRule(exp: ExperimentData, isUnitPinnedMissing: Boolean): Option[(Int, Boolean)] = {
      evaluations += 1
      super.resolveRule(exp, isUnitPinnedMissing)
    }
    override private[sdk] def storeAssignment(experimentName: String, cached: Context.Cached): Unit = {
      writes += 1
      super.storeAssignment(experimentName, cached)
    }
  }

  test("an unchanged cached assignment is read without writing the cache again") {
    val plain = expAbc.copy(id = 3, name = "exp_plain", variants = expAbc.variants.map(_.copy(config = None)))
    val context = new CountingContext(ContextData(List(withRules(assignRule(1)), plain)))
    context.setAttribute("country", Json.fromString("US"))
    Seq("exp_test_abc", "exp_plain").foreach { name =>
      context.peek(name)
      context.treatment(name)
    }
    val writes = context.writes
    (1 to 3).foreach { _ =>
      assert(context.treatment("exp_test_abc") == 1)
      assert(context.peek("exp_plain") == 2)
      assert(context.variableValue("button.color", "none") == "\"blue\"")
    }
    assert(context.writes == writes)
  }

  test("an experiment without rules never evaluates rules") {
    val context = new CountingContext(ContextData(List(expAbc.copy(assignmentRules = Some("")))))
    context.setAttribute("country", Json.fromString("US"))
    assert(context.treatment("exp_test_abc") == 2)
    context.setAttribute("unrelated", Json.True)
    context.refresh(ContextData(List(expAbc)))
    assert(context.treatment("exp_test_abc") == 2)
    assert(context.evaluations == 0)
  }
}

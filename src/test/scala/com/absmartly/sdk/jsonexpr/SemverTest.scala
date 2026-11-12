package com.absmartly.sdk.jsonexpr

import org.scalatest.funsuite.AnyFunSuite
import io.circe.Json
import io.circe.parser._

/** Semver operators and version comparison, mirroring the JS SDK's evaluator and semver_* operator tests. */
class SemverTest extends AnyFunSuite {

  private def s(v: String): Json = Json.fromString(v)
  private def cmp(l: String, r: String): Option[Int] = Evaluator.versionCompare(s(l), s(r))

  test("equal versions compare as 0") {
    assert(cmp("1.0.0", "1.0.0").contains(0))
    assert(cmp("0.0.0", "0.0.0").contains(0))
    assert(cmp("999.999.999", "999.999.999").contains(0))
  }

  test("major, minor and patch compare in order") {
    assert(cmp("2.0.0", "1.0.0").contains(1) && cmp("1.0.0", "2.0.0").contains(-1))
    assert(cmp("1.2.0", "1.1.0").contains(1) && cmp("1.1.0", "1.2.0").contains(-1))
    assert(cmp("1.0.2", "1.0.1").contains(1) && cmp("1.0.1", "1.0.2").contains(-1))
  }

  test("parts compare numerically, not lexicographically") {
    assert(cmp("1.10.0", "1.9.0").contains(1))
    assert(cmp("1.9.0", "1.10.0").contains(-1))
    assert(cmp("10.0.0", "9.0.0").contains(1))
    assert(cmp("12345678901234567890.0", "9.0").contains(1))
  }

  test("missing parts count as 0") {
    assert(cmp("1.2", "1.2.0").contains(0))
    assert(cmp("1", "1.0.0").contains(0))
    assert(cmp("1.2.0", "1.2").contains(0))
    assert(cmp("1.0.0", "1").contains(0))
  }

  test("leading zeros are ignored") {
    assert(cmp("1.02.0", "1.2.0").contains(0))
    assert(cmp("1.002.030", "1.2.30").contains(0))
    assert(cmp("01.0.0", "1.0.0").contains(0))
    assert(cmp("1.02.0", "1.3.0").contains(-1))
  }

  test("pre-releases sort before their release and by identifier") {
    assert(cmp("1.0.0", "1.0.0-alpha").contains(1))
    assert(cmp("1.0.0-alpha", "1.0.0").contains(-1))
    assert(cmp("1.0.0-alpha", "1.0.0-alpha").contains(0))
    assert(cmp("1.0.0-alpha", "1.0.0-beta").contains(-1))
    assert(cmp("1.0.0-beta", "1.0.0-alpha").contains(1))
    assert(cmp("1.0.0-alpha.1", "1.0.0-alpha.2").contains(-1))
    assert(cmp("1.0.0-alpha.2", "1.0.0-alpha.1").contains(1))
    assert(cmp("1.0.0-1", "1.0.0-2").contains(-1))
    assert(cmp("1.0.0-2", "1.0.0-1").contains(1))
    assert(cmp("1.0.0-rc-1", "1.0.0-rc-2").contains(-1))
  }

  test("numeric pre-release identifiers sort before alphanumeric ones") {
    assert(cmp("1.0.0-1", "1.0.0-alpha").contains(-1))
    assert(cmp("1.0.0-alpha", "1.0.0-1").contains(1))
  }

  test("a pre-release with fewer identifiers sorts first") {
    assert(cmp("1.0.0-alpha", "1.0.0-alpha.1").contains(-1))
    assert(cmp("1.0.0-alpha.1", "1.0.0-alpha").contains(1))
  }

  test("a v prefix and build metadata are ignored") {
    assert(cmp("v1.0.0", "1.0.0").contains(0))
    assert(cmp("V1.0.0", "1.0.0").contains(0))
    assert(cmp("v1.0.0", "V1.0.0").contains(0))
    assert(cmp("1.0.0+build1", "1.0.0+build2").contains(0))
    assert(cmp("1.0.0+build1", "1.0.0").contains(0))
    assert(cmp("1.0.0-alpha+build1", "1.0.0-alpha+build2").contains(0))
    assert(cmp("1.0.0-alpha+build1", "1.0.0-beta").contains(-1))
    assert(cmp("1.0.0-alpha+build1", "1.0.0").contains(-1))
  }

  test("non-string inputs are converted like stringConvert") {
    assert(Evaluator.versionCompare(Json.fromInt(1), s("1.0.0")).contains(0))
    assert(Evaluator.versionCompare(s("1.0.0"), Json.fromInt(1)).contains(0))
    assert(Evaluator.versionCompare(Json.True, s("true")).contains(0))
    assert(Evaluator.versionCompare(Json.fromDoubleOrNull(1.5), s("1.5.0")).contains(0))
  }

  test("null, empty, unconvertible and empty-core inputs are not comparable") {
    assert(Evaluator.versionCompare(Json.Null, s("1.0.0")).isEmpty)
    assert(Evaluator.versionCompare(s("1.0.0"), Json.Null).isEmpty)
    assert(Evaluator.versionCompare(Json.obj(), s("1.0.0")).isEmpty)
    assert(Evaluator.versionCompare(s("1.0.0"), Json.arr()).isEmpty)
    assert(cmp("", "1.0.0").isEmpty && cmp("1.0.0", "").isEmpty && cmp("", "").isEmpty)
    List("v", "V", "+build", "v+build", "-alpha").foreach(v => assert(cmp(v, "1.0.0").isEmpty, v))
    assert(cmp("1.0.0", "v").isEmpty)
  }

  test("parsing is as permissive as the JS and Java SDKs") {
    assert(cmp("foo", "bar").contains(1))
    assert(cmp("1.x", "1.0").contains(1))
    assert(cmp("1..2", "1.0.2").contains(1))
    assert(cmp("1.0.0-", "1.0.0").contains(0))
  }

  private def eval(expr: String, vars: Map[String, Json] = Map.empty): Json =
    Evaluator.evaluate(parse(expr).toOption.get, vars)

  test("each semver operator evaluates against its comparison") {
    val cases = List(
      ("semver_eq", "1.0.0", "1.0.0", true),
      ("semver_eq", "1.0.0", "1.0.1", false),
      ("semver_gt", "2.0.0", "1.0.0", true),
      ("semver_gt", "1.0.0", "1.0.0", false),
      ("semver_gt", "1.0.0", "2.0.0", false),
      ("semver_gte", "2.0.0", "1.0.0", true),
      ("semver_gte", "1.0.0", "1.0.0", true),
      ("semver_gte", "1.0.0", "2.0.0", false),
      ("semver_lt", "1.0.0", "2.0.0", true),
      ("semver_lt", "1.0.0", "1.0.0", false),
      ("semver_lt", "2.0.0", "1.0.0", false),
      ("semver_lte", "1.0.0", "2.0.0", true),
      ("semver_lte", "1.0.0", "1.0.0", true),
      ("semver_lte", "2.0.0", "1.0.0", false)
    )
    cases.foreach { case (op, l, r, expected) =>
      assert(eval(s"""{"$op":[{"value":"$l"},{"value":"$r"}]}""") == Json.fromBoolean(expected), s"$op $l $r")
    }
  }

  test("semver operators return null for a null or incomparable operand") {
    List("semver_eq", "semver_gt", "semver_gte", "semver_lt", "semver_lte").foreach { op =>
      assert(eval(s"""{"$op":[{"value":null},{"value":"1.0.0"}]}""").isNull, op)
      assert(eval(s"""{"$op":[{"value":"1.0.0"},{"value":null}]}""").isNull, op)
      assert(eval(s"""{"$op":[{"value":""},{"value":"1.0.0"}]}""").isNull, op)
      assert(eval(s"""{"$op":[{"var":"missing"},{"value":"1.0.0"}]}""").isNull, op)
    }
  }

  test("semver operators read variables, as in an app_version audience") {
    val vars = Map("app_version" -> s("2.4.1"))
    assert(eval("""{"semver_gte":[{"var":"app_version"},{"value":"2.4.0"}]}""", vars) == Json.True)
    assert(eval("""{"semver_lt":[{"var":"app_version"},{"value":"2.4.0"}]}""", vars) == Json.False)
  }
}

package com.absmartly.sdk.jsonexpr

import org.scalatest.funsuite.AnyFunSuite
import io.circe.Json
import io.circe.parser._

class EvaluatorTest extends AnyFunSuite {

  val vars: Map[String, Json] = Map(
    "age" -> Json.fromInt(25),
    "country" -> Json.fromString("US"),
    "returning" -> Json.True,
    "score" -> Json.fromDouble(98.5).get,
    "nested" -> Json.obj(
      "level1" -> Json.obj(
        "level2" -> Json.fromString("deep")
      )
    )
  )

  // AND operator tests
  test("and with empty array returns true") {
    val expr = parse("""{"and": []}""").toOption.get
    val result = Evaluator.evaluate(expr, vars)
    assert(result.asBoolean.contains(true))
  }

  test("and with all true returns true") {
    val expr = parse("""{"and": [{"value": true}, {"value": true}]}""").toOption.get
    val result = Evaluator.evaluate(expr, vars)
    assert(result.asBoolean.contains(true))
  }

  test("and with one false returns false") {
    val expr = parse("""{"and": [{"value": true}, {"value": false}]}""").toOption.get
    val result = Evaluator.evaluate(expr, vars)
    assert(result.asBoolean.contains(false))
  }

  test("and with all false returns false") {
    val expr = parse("""{"and": [{"value": false}, {"value": false}]}""").toOption.get
    val result = Evaluator.evaluate(expr, vars)
    assert(result.asBoolean.contains(false))
  }

  // OR operator tests
  test("or with empty array returns false") {
    val expr = parse("""{"or": []}""").toOption.get
    val result = Evaluator.evaluate(expr, vars)
    assert(result.asBoolean.contains(false))
  }

  test("or with at least one true returns true") {
    val expr = parse("""{"or": [{"value": false}, {"value": true}]}""").toOption.get
    val result = Evaluator.evaluate(expr, vars)
    assert(result.asBoolean.contains(true))
  }

  test("or with all false returns false") {
    val expr = parse("""{"or": [{"value": false}, {"value": false}]}""").toOption.get
    val result = Evaluator.evaluate(expr, vars)
    assert(result.asBoolean.contains(false))
  }

  // NOT operator tests
  test("not inverts true to false") {
    val expr = parse("""{"not": {"value": true}}""").toOption.get
    val result = Evaluator.evaluate(expr, vars)
    assert(result.asBoolean.contains(false))
  }

  test("not inverts false to true") {
    val expr = parse("""{"not": {"value": false}}""").toOption.get
    val result = Evaluator.evaluate(expr, vars)
    assert(result.asBoolean.contains(true))
  }

  // NULL operator tests
  test("null returns true for null") {
    val expr = parse("""{"null": {"value": null}}""").toOption.get
    val result = Evaluator.evaluate(expr, vars)
    assert(result.asBoolean.contains(true))
  }

  test("null returns false for non-null") {
    val expr = parse("""{"null": {"value": 123}}""").toOption.get
    val result = Evaluator.evaluate(expr, vars)
    assert(result.asBoolean.contains(false))
  }

  // VAR operator tests
  test("var extracts existing variable") {
    val expr = parse("""{"var": "age"}""").toOption.get
    val result = Evaluator.evaluate(expr, vars)
    assert(result.asNumber.flatMap(_.toInt).contains(25))
  }

  test("var returns null for missing variable") {
    val expr = parse("""{"var": "missing"}""").toOption.get
    val result = Evaluator.evaluate(expr, vars)
    assert(result.isNull)
  }

  test("var extracts nested variable") {
    val expr = parse("""{"var": "nested/level1/level2"}""").toOption.get
    val result = Evaluator.evaluate(expr, vars)
    assert(result.asString.contains("deep"))
  }

  // VALUE operator tests
  test("value returns literal") {
    val expr = parse("""{"value": 42}""").toOption.get
    val result = Evaluator.evaluate(expr, vars)
    assert(result.asNumber.flatMap(_.toInt).contains(42))
  }

  test("value returns literal string") {
    val expr = parse("""{"value": "hello"}""").toOption.get
    val result = Evaluator.evaluate(expr, vars)
    assert(result.asString.contains("hello"))
  }

  // EQ operator tests
  test("eq returns true for equal numbers") {
    val expr = parse("""{"eq": [{"value": 25}, {"var": "age"}]}""").toOption.get
    val result = Evaluator.evaluate(expr, vars)
    assert(result.asBoolean.contains(true))
  }

  test("eq returns false for different numbers") {
    val expr = parse("""{"eq": [{"value": 30}, {"var": "age"}]}""").toOption.get
    val result = Evaluator.evaluate(expr, vars)
    assert(result.asBoolean.contains(false))
  }

  test("eq returns true for equal strings") {
    val expr = parse("""{"eq": [{"value": "US"}, {"var": "country"}]}""").toOption.get
    val result = Evaluator.evaluate(expr, vars)
    assert(result.asBoolean.contains(true))
  }

  test("eq returns null for null == null") {
    val expr = parse("""{"eq": [{"value": null}, {"value": null}]}""").toOption.get
    val result = Evaluator.evaluate(expr, vars)
    assert(result.isNull)
  }

  test("eq returns null for null == 0") {
    val expr = parse("""{"eq": [{"value": null}, {"value": 0}]}""").toOption.get
    val result = Evaluator.evaluate(expr, vars)
    assert(result.isNull)
  }

  // GT operator tests
  test("gt returns true when lhs > rhs") {
    val expr = parse("""{"gt": [{"var": "age"}, {"value": 20}]}""").toOption.get
    val result = Evaluator.evaluate(expr, vars)
    assert(result.asBoolean.contains(true))
  }

  test("gt returns false when lhs <= rhs") {
    val expr = parse("""{"gt": [{"var": "age"}, {"value": 30}]}""").toOption.get
    val result = Evaluator.evaluate(expr, vars)
    assert(result.asBoolean.contains(false))
  }

  test("gt returns false when lhs == rhs") {
    val expr = parse("""{"gt": [{"var": "age"}, {"value": 25}]}""").toOption.get
    val result = Evaluator.evaluate(expr, vars)
    assert(result.asBoolean.contains(false))
  }

  test("gt with null returns false") {
    val expr = parse("""{"gt": [{"value": null}, {"value": 0}]}""").toOption.get
    val result = Evaluator.evaluate(expr, vars)
    assert(result.asBoolean.contains(false))
  }

  // GTE operator tests
  test("gte returns true when lhs >= rhs") {
    val expr = parse("""{"gte": [{"var": "age"}, {"value": 25}]}""").toOption.get
    val result = Evaluator.evaluate(expr, vars)
    assert(result.asBoolean.contains(true))
  }

  test("gte returns false when lhs < rhs") {
    val expr = parse("""{"gte": [{"var": "age"}, {"value": 30}]}""").toOption.get
    val result = Evaluator.evaluate(expr, vars)
    assert(result.asBoolean.contains(false))
  }

  // LT operator tests
  test("lt returns true when lhs < rhs") {
    val expr = parse("""{"lt": [{"var": "age"}, {"value": 30}]}""").toOption.get
    val result = Evaluator.evaluate(expr, vars)
    assert(result.asBoolean.contains(true))
  }

  test("lt returns false when lhs >= rhs") {
    val expr = parse("""{"lt": [{"var": "age"}, {"value": 20}]}""").toOption.get
    val result = Evaluator.evaluate(expr, vars)
    assert(result.asBoolean.contains(false))
  }

  // LTE operator tests
  test("lte returns true when lhs <= rhs") {
    val expr = parse("""{"lte": [{"var": "age"}, {"value": 25}]}""").toOption.get
    val result = Evaluator.evaluate(expr, vars)
    assert(result.asBoolean.contains(true))
  }

  test("lte returns false when lhs > rhs") {
    val expr = parse("""{"lte": [{"var": "age"}, {"value": 20}]}""").toOption.get
    val result = Evaluator.evaluate(expr, vars)
    assert(result.asBoolean.contains(false))
  }

  // IN operator tests
  test("in returns true when string contains substring") {
    val expr = parse("""{"in": [{"value": "US"}, {"var": "country"}]}""").toOption.get
    val result = Evaluator.evaluate(expr, vars)
    assert(result.asBoolean.contains(true))
  }

  test("in returns false when string doesn't contain substring") {
    val expr = parse("""{"in": [{"value": "UK"}, {"var": "country"}]}""").toOption.get
    val result = Evaluator.evaluate(expr, vars)
    assert(result.asBoolean.contains(false))
  }

  test("in returns true when array contains value") {
    val expr = parse("""{"in": [{"value": [1, 2, 3]}, {"value": 2}]}""").toOption.get
    val result = Evaluator.evaluate(expr, vars)
    assert(result.asBoolean.contains(true))
  }

  test("in returns false when array doesn't contain value") {
    val expr = parse("""{"in": [{"value": [1, 2, 3]}, {"value": 5}]}""").toOption.get
    val result = Evaluator.evaluate(expr, vars)
    assert(result.asBoolean.contains(false))
  }

  test("in returns false when either operand is null") {
    val expr = parse("""{"in": [{"value": null}, {"value": "test"}]}""").toOption.get
    val result = Evaluator.evaluate(expr, vars)
    assert(result.asBoolean.contains(false))
  }

  // MATCH operator tests
  test("match returns true when pattern matches") {
    val expr = parse("""{"match": [{"value": "hello world"}, {"value": "world"}]}""").toOption.get
    val result = Evaluator.evaluate(expr, vars)
    assert(result.asBoolean.contains(true))
  }

  test("match returns false when pattern doesn't match") {
    val expr = parse("""{"match": [{"value": "hello world"}, {"value": "foo"}]}""").toOption.get
    val result = Evaluator.evaluate(expr, vars)
    assert(result.asBoolean.contains(false))
  }

  test("match with regex pattern") {
    val expr = parse("""{"match": [{"value": "test123"}, {"value": "\\d+"}]}""").toOption.get
    val result = Evaluator.evaluate(expr, vars)
    assert(result.asBoolean.contains(true))
  }

  test("match returns false when text is null") {
    val expr = parse("""{"match": [{"value": null}, {"value": "test"}]}""").toOption.get
    val result = Evaluator.evaluate(expr, vars)
    assert(result.asBoolean.contains(false))
  }

  test("match returns false when pattern is null") {
    val expr = parse("""{"match": [{"value": "test"}, {"value": null}]}""").toOption.get
    val result = Evaluator.evaluate(expr, vars)
    assert(result.asBoolean.contains(false))
  }

  // Complex nested expressions
  test("complex nested expression with and/or/eq") {
    val expr = parse("""{"and": [
      {"eq": [{"var": "country"}, {"value": "US"}]},
      {"or": [
        {"gt": [{"var": "age"}, {"value": 18}]},
        {"eq": [{"var": "returning"}, {"value": true}]}
      ]}
    ]}""").toOption.get
    val result = Evaluator.evaluate(expr, vars)
    assert(result.asBoolean.contains(true))
  }

  test("empty object evaluates to true") {
    val expr = parse("""{}""").toOption.get
    val result = Evaluator.evaluate(expr, vars)
    assert(result.asBoolean.contains(true))
  }

  test("null expression returns null") {
    val result = Evaluator.evaluate(Json.Null, vars)
    assert(result.isNull)
  }

  test("literal value returns itself") {
    val expr = Json.fromInt(42)
    val result = Evaluator.evaluate(expr, vars)
    assert(result.asNumber.flatMap(_.toInt).contains(42))
  }

  test("unknown operator returns null") {
    val expr = parse("""{"unknown": []}""").toOption.get
    val result = Evaluator.evaluate(expr, vars)
    assert(result.isNull)
  }
}

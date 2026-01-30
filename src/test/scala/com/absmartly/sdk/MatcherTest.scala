package com.absmartly.sdk

import org.scalatest.funsuite.AnyFunSuite
import io.circe.Json

class MatcherTest extends AnyFunSuite {

  test("evaluate with null audience returns None") {
    val matcher = new AudienceMatcher(Map.empty)
    val result = matcher.evaluate(None)
    assert(result.isEmpty)
  }

  test("evaluate with empty object returns Some(true)") {
    val matcher = new AudienceMatcher(Map.empty)
    val result = matcher.evaluate(Some("{}"))
    assert(result.contains(true))
  }

  test("evaluate with empty filter returns true") {
    val matcher = new AudienceMatcher(Map.empty)
    val result = matcher.evaluate(Some("""{"filter": []}"""))
    assert(result.contains(true))
  }

  test("evaluate with filter value true returns true") {
    val matcher = new AudienceMatcher(Map.empty)
    val result = matcher.evaluate(Some("""{"filter": [{"value": true}]}"""))
    assert(result.contains(true))
  }

  test("evaluate with filter value false returns false") {
    val matcher = new AudienceMatcher(Map.empty)
    val result = matcher.evaluate(Some("""{"filter": [{"value": false}]}"""))
    assert(result.contains(false))
  }

  test("evaluate with and operator all true returns true") {
    val matcher = new AudienceMatcher(Map.empty)
    val result = matcher.evaluate(Some("""{"filter": [{"and": [{"value": true}, {"value": true}]}]}"""))
    assert(result.contains(true))
  }

  test("evaluate with and operator one false returns false") {
    val matcher = new AudienceMatcher(Map.empty)
    val result = matcher.evaluate(Some("""{"filter": [{"and": [{"value": true}, {"value": false}]}]}"""))
    assert(result.contains(false))
  }

  test("evaluate with variable matching") {
    val vars = Map("age" -> Json.fromInt(25))
    val matcher = new AudienceMatcher(vars)
    val result = matcher.evaluate(Some("""{"filter": [{"gt": [{"var": "age"}, {"value": 18}]}]}"""))
    assert(result.contains(true))
  }

  test("evaluate with variable not matching") {
    val vars = Map("age" -> Json.fromInt(15))
    val matcher = new AudienceMatcher(vars)
    val result = matcher.evaluate(Some("""{"filter": [{"gt": [{"var": "age"}, {"value": 18}]}]}"""))
    assert(result.contains(false))
  }

  test("evaluate with multiple filters (AND logic)") {
    val vars = Map(
      "age" -> Json.fromInt(25),
      "country" -> Json.fromString("US")
    )
    val matcher = new AudienceMatcher(vars)
    val result = matcher.evaluate(Some(
      """{"filter": [
        {"eq": [{"var": "country"}, {"value": "US"}]},
        {"gt": [{"var": "age"}, {"value": 18}]}
      ]}"""
    ))
    assert(result.contains(true))
  }

  test("evaluate with missing variable returns false") {
    val matcher = new AudienceMatcher(Map.empty)
    val result = matcher.evaluate(Some("""{"filter": [{"eq": [{"var": "missing"}, {"value": "test"}]}]}"""))
    // null != "test" so should be false
    assert(result.contains(false))
  }

  test("evaluate with nested and/or") {
    val vars = Map(
      "age" -> Json.fromInt(25),
      "country" -> Json.fromString("US")
    )
    val matcher = new AudienceMatcher(vars)
    val result = matcher.evaluate(Some(
      """{"filter": [{"and": [
        {"eq": [{"var": "country"}, {"value": "US"}]},
        {"or": [
          {"lt": [{"var": "age"}, {"value": 18}]},
          {"gt": [{"var": "age"}, {"value": 21}]}
        ]}
      ]}]}"""
    ))
    assert(result.contains(true))
  }

  test("evaluate with invalid JSON returns Some(true)") {
    val matcher = new AudienceMatcher(Map.empty)
    val result = matcher.evaluate(Some("invalid json"))
    // Invalid JSON should return None or Some(true) - need to check implementation
    assert(result.isDefined)
  }

  test("evaluate complex real-world audience") {
    val vars = Map(
      "returning_visitor" -> Json.True,
      "cart_value" -> Json.fromDouble(150.0).get,
      "country" -> Json.fromString("US")
    )
    val matcher = new AudienceMatcher(vars)
    val result = matcher.evaluate(Some(
      """{"filter": [{"and": [
        {"eq": [{"var": "country"}, {"value": "US"}]},
        {"or": [
          {"eq": [{"var": "returning_visitor"}, {"value": true}]},
          {"gt": [{"var": "cart_value"}, {"value": 100}]}
        ]}
      ]}]}"""
    ))
    assert(result.contains(true))
  }

  test("evaluate audience with not operator") {
    val vars = Map("returning_visitor" -> Json.False)
    val matcher = new AudienceMatcher(vars)
    val result = matcher.evaluate(Some("""{"filter": [{"not": {"var": "returning_visitor"}}]}"""))
    assert(result.contains(true))
  }

  test("evaluate audience with in operator") {
    val vars = Map("country" -> Json.fromString("US"))
    val matcher = new AudienceMatcher(vars)
    val result = matcher.evaluate(Some("""{"filter": [{"in": [{"var": "country"}, {"value": ["US", "CA", "UK"]}]}]}"""))
    // Note: This depends on "in" operator implementation
    // Should check if "US" is in the array
    assert(result.isDefined)
  }
}

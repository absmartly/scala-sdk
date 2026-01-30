package com.absmartly.sdk

import org.scalatest.funsuite.AnyFunSuite
import io.circe.Json

class UtilsTest extends AnyFunSuite {

  // hashUnit tests
  test("hashUnit produces base64url encoded MD5") {
    val result = Utils.hashUnit("bleh@absmartly.com")
    assert(result.nonEmpty)
    assert(!result.contains("+"))  // base64url shouldn't have +
    assert(!result.contains("/"))  // base64url shouldn't have /
    assert(!result.contains("="))  // no padding
  }

  // chooseVariant tests (matching JavaScript SDK expectations)
  test("chooseVariant with [0.0, 1.0] split") {
    assert(Utils.chooseVariant(List(0.0, 1.0), 0.0) == 1)
    assert(Utils.chooseVariant(List(0.0, 1.0), 0.5) == 1)
    assert(Utils.chooseVariant(List(0.0, 1.0), 1.0) == 1)
  }

  test("chooseVariant with [1.0, 0.0] split") {
    assert(Utils.chooseVariant(List(1.0, 0.0), 0.0) == 0)
    assert(Utils.chooseVariant(List(1.0, 0.0), 0.5) == 0)
    assert(Utils.chooseVariant(List(1.0, 0.0), 1.0) == 1)
  }

  test("chooseVariant 50/50 split at boundary") {
    assert(Utils.chooseVariant(List(0.5, 0.5), 0.0) == 0)
    assert(Utils.chooseVariant(List(0.5, 0.5), 0.25) == 0)
    assert(Utils.chooseVariant(List(0.5, 0.5), 0.49999999) == 0)
    assert(Utils.chooseVariant(List(0.5, 0.5), 0.5) == 1)
    assert(Utils.chooseVariant(List(0.5, 0.5), 0.50000001) == 1)
    assert(Utils.chooseVariant(List(0.5, 0.5), 0.75) == 1)
    assert(Utils.chooseVariant(List(0.5, 0.5), 1.0) == 1)
  }

  test("chooseVariant 33/33/34 split") {
    assert(Utils.chooseVariant(List(0.333, 0.333, 0.334), 0.0) == 0)
    assert(Utils.chooseVariant(List(0.333, 0.333, 0.334), 0.333) == 1)
    assert(Utils.chooseVariant(List(0.333, 0.333, 0.334), 0.666) == 2)
    assert(Utils.chooseVariant(List(0.333, 0.333, 0.334), 1.0) == 2)
  }

  // booleanConvert tests
  test("booleanConvert null returns None") {
    assert(Utils.booleanConvert(Json.Null).isEmpty)
  }

  test("booleanConvert true returns Some(true)") {
    assert(Utils.booleanConvert(Json.True) == Some(true))
  }

  test("booleanConvert false returns Some(false)") {
    assert(Utils.booleanConvert(Json.False) == Some(false))
  }

  test("booleanConvert 0 returns Some(false)") {
    assert(Utils.booleanConvert(Json.fromInt(0)) == Some(false))
  }

  test("booleanConvert 1 returns Some(true)") {
    assert(Utils.booleanConvert(Json.fromInt(1)) == Some(true))
  }

  test("booleanConvert empty string returns Some(false)") {
    assert(Utils.booleanConvert(Json.fromString("")) == Some(false))
  }

  test("booleanConvert non-empty string returns Some(true)") {
    assert(Utils.booleanConvert(Json.fromString("abc")) == Some(true))
  }

  test("booleanConvert array returns Some(true)") {
    assert(Utils.booleanConvert(Json.arr()) == Some(true))
  }

  test("booleanConvert object returns Some(true)") {
    assert(Utils.booleanConvert(Json.obj()) == Some(true))
  }

  // numberConvert tests
  test("numberConvert null returns None") {
    assert(Utils.numberConvert(Json.Null).isEmpty)
  }

  test("numberConvert true returns Some(1.0)") {
    assert(Utils.numberConvert(Json.True) == Some(1.0))
  }

  test("numberConvert false returns Some(0.0)") {
    assert(Utils.numberConvert(Json.False) == Some(0.0))
  }

  test("numberConvert 0 returns Some(0.0)") {
    assert(Utils.numberConvert(Json.fromInt(0)) == Some(0.0))
  }

  test("numberConvert 1.5 returns Some(1.5)") {
    assert(Utils.numberConvert(Json.fromDouble(1.5).get) == Some(1.5))
  }

  test("numberConvert empty string returns Some(0.0)") {
    assert(Utils.numberConvert(Json.fromString("")) == Some(0.0))
  }

  test("numberConvert '123' returns Some(123.0)") {
    assert(Utils.numberConvert(Json.fromString("123")) == Some(123.0))
  }

  test("numberConvert 'abc' returns None") {
    assert(Utils.numberConvert(Json.fromString("abc")).isEmpty)
  }

  // stringConvert tests
  test("stringConvert null returns None") {
    assert(Utils.stringConvert(Json.Null).isEmpty)
  }

  test("stringConvert true returns Some('true')") {
    assert(Utils.stringConvert(Json.True) == Some("true"))
  }

  test("stringConvert false returns Some('false')") {
    assert(Utils.stringConvert(Json.False) == Some("false"))
  }

  test("stringConvert 0 returns Some('0')") {
    assert(Utils.stringConvert(Json.fromInt(0)) == Some("0"))
  }

  test("stringConvert 1.5 returns Some('1.5')") {
    assert(Utils.stringConvert(Json.fromDouble(1.5).get) == Some("1.5"))
  }

  test("stringConvert 'abc' returns Some('abc')") {
    assert(Utils.stringConvert(Json.fromString("abc")) == Some("abc"))
  }

  // compare tests
  test("compare null with null returns Some(0)") {
    assert(Utils.compare(Json.Null, Json.Null) == Some(0))
  }

  test("compare null with 0 returns None") {
    assert(Utils.compare(Json.Null, Json.fromInt(0)).isEmpty)
  }

  test("compare 0 with null returns None") {
    assert(Utils.compare(Json.fromInt(0), Json.Null).isEmpty)
  }

  test("compare 0 with 0 returns Some(0)") {
    assert(Utils.compare(Json.fromInt(0), Json.fromInt(0)) == Some(0))
  }

  test("compare 1 with 0 returns Some(1)") {
    assert(Utils.compare(Json.fromInt(1), Json.fromInt(0)) == Some(1))
  }

  test("compare 0 with 1 returns Some(-1)") {
    assert(Utils.compare(Json.fromInt(0), Json.fromInt(1)) == Some(-1))
  }

  test("compare 'a' with 'a' returns Some(0)") {
    assert(Utils.compare(Json.fromString("a"), Json.fromString("a")) == Some(0))
  }

  test("compare 'a' with 'b' returns Some(-1)") {
    assert(Utils.compare(Json.fromString("a"), Json.fromString("b")) == Some(-1))
  }

  test("compare 'b' with 'a' returns Some(1)") {
    assert(Utils.compare(Json.fromString("b"), Json.fromString("a")) == Some(1))
  }

  test("compare true with true returns Some(0)") {
    assert(Utils.compare(Json.True, Json.True) == Some(0))
  }

  test("compare true with false returns Some(1)") {
    assert(Utils.compare(Json.True, Json.False) == Some(1))
  }

  test("compare false with true returns Some(-1)") {
    assert(Utils.compare(Json.False, Json.True) == Some(-1))
  }

  test("compare arrays returns Some(0) for equal arrays") {
    assert(Utils.compare(Json.arr(), Json.arr()) == Some(0))
  }
}

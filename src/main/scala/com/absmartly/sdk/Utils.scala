package com.absmartly.sdk

import org.apache.commons.codec.digest.DigestUtils
import java.util.Base64

object Utils {

  /**
   * Hash a unit identifier using MD5 and encode as base64url
   *
   * This is used before publishing events to anonymize unit IDs.
   *
   * @param unit The unit identifier to hash
   * @return Base64url encoded MD5 hash (no padding)
   */
  def hashUnit(unit: String): String = {
    val md5Bytes = DigestUtils.md5(utf8Bytes(unit))
    // Convert to base64url (URL-safe, no padding)
    base64UrlNoPadding(md5Bytes)
  }

  /**
   * Hash a numeric unit the way the JS SDK does: the number is first rounded
   * to an integer (`toFixed(0)`, halves away from zero), so 125.75 hashes as "126".
   */
  def hashUnit(unit: Double): String = hashUnit(toFixed(unit, 0))

  /**
   * JS `Number.prototype.toFixed`: the sign is taken first (so -0.1 gives "-0"
   * at 0 digits), halves round away from zero, and NaN, infinities and
   * magnitudes from 1e21 up use the Number string form (e.g. "1e+21").
   */
  private def toFixed(d: Double, digits: Int): String = {
    if (d.isNaN) "NaN"
    else if (d.isInfinite) (if (d > 0) "Infinity" else "-Infinity")
    else {
      val abs = math.abs(d)
      val body =
        if (abs >= 1e21) jsExponential(abs)
        else new java.math.BigDecimal(abs).setScale(digits, java.math.RoundingMode.HALF_UP).toPlainString
      (if (d < 0) "-" else "") + body
    }
  }

  // Shortest round-trip digits, as JS prints them; JDK 17 Double.toString is not always shortest.
  private def jsExponential(abs: Double): String = {
    val exact = new java.math.BigDecimal(abs)
    val shortest = (1 to 17).iterator
      .map(p => exact.round(new java.math.MathContext(p, java.math.RoundingMode.HALF_EVEN)))
      .find(_.doubleValue == abs)
      .getOrElse(exact)
      .stripTrailingZeros
    val digits = shortest.unscaledValue.toString
    val exponent = digits.length - 1 - shortest.scale
    val mantissa = if (digits.length > 1) s"${digits.head}.${digits.tail}" else digits
    s"${mantissa}e+$exponent"
  }

  /** Base64url encoding without padding, as used for unit hashes. */
  def base64UrlNoPadding(bytes: Array[Byte]): String =
    Base64.getUrlEncoder.withoutPadding().encodeToString(bytes)

  /**
   * UTF-8 bytes of the Unicode string, as the JS SDK's TextEncoder produces
   * them: a surrogate pair becomes one 4-byte sequence and a lone surrogate
   * becomes U+FFFD (EF BF BD). `String.getBytes(UTF_8)` would emit '?' for a
   * lone surrogate instead.
   */
  def utf8Bytes(value: String): Array[Byte] = {
    val encoder = java.nio.charset.StandardCharsets.UTF_8.newEncoder()
      .onMalformedInput(java.nio.charset.CodingErrorAction.REPLACE)
      .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPLACE)
      .replaceWith(Array(0xEF.toByte, 0xBF.toByte, 0xBD.toByte))
    val encoded = encoder.encode(java.nio.CharBuffer.wrap(value))
    java.util.Arrays.copyOfRange(encoded.array, encoded.arrayOffset + encoded.position(), encoded.arrayOffset + encoded.limit())
  }

  /** JS `isNumeric`: true only for a JSON number. */
  def isNumeric(value: io.circe.Json): Boolean = value.isNumber

  /** JS `isObject`: true only for a plain JSON object (not an array). */
  def isObject(value: io.circe.Json): Boolean = value.isObject

  /** JS `isPromise`: true for an asynchronous value (a Future). Decoded JSON is never one. */
  def isPromise(value: Any): Boolean = value.isInstanceOf[scala.concurrent.Future[_]]

  /**
   * Choose a variant index based on split probabilities
   *
   * @param split Individual probability weights [p0, p1, p2, ...]
   * @param probability Value between 0.0 and 1.0
   * @return Variant index (0-based)
   */
  def chooseVariant(split: List[Double], probability: Double): Int = {
    var cumSum = 0.0
    var i = 0
    while (i < split.length) {
      cumSum += split(i)
      if (probability < cumSum) {
        return i
      }
      i += 1
    }
    split.length - 1
  }

  /**
   * Filter properties to only include numeric values
   *
   * Goal events should only contain numeric properties.
   *
   * @param properties Map of property names to JSON values
   * @return Map containing only numeric properties
   */
  def filterNumericProperties(properties: Map[String, io.circe.Json]): Map[String, Double] = {
    properties.flatMap {
      case (key, value) =>
        value.asNumber.map(n => key -> n.toDouble)
    }
  }

  /**
   * Convert value to boolean for expression evaluation (JS `booleanConvert`).
   * None only for null; strings "false", "0" and "" are false; arrays and
   * objects are truthy.
   */
  def booleanConvert(value: io.circe.Json): Option[Boolean] = {
    if (value.isNull) {
      None
    } else if (value.isBoolean) {
      value.asBoolean
    } else if (value.isNumber) {
      value.asNumber.map(_.toDouble != 0.0)
    } else if (value.isString) {
      value.asString.map(s => s != "false" && s != "0" && s.nonEmpty)
    } else {
      Some(true)
    }
  }

  // JS parseFloat: the longest leading decimal literal, surrounding text ignored.
  private val FloatPrefix = """^\s*([+-]?(?:\d+\.?\d*|\.\d+)(?:[eE][+-]?\d+)?)""".r.unanchored

  /**
   * Convert value to number for expression evaluation (JS `numberConvert`).
   */
  def numberConvert(value: io.circe.Json): Option[Double] = {
    if (value.isBoolean) {
      value.asBoolean.map(if (_) 1.0 else 0.0)
    } else if (value.isNumber) {
      value.asNumber.map(_.toDouble)
    } else if (value.isString) {
      value.asString.flatMap {
        case FloatPrefix(num) => Some(num.toDouble).filter(d => !d.isInfinite && !d.isNaN)
        case _ => None
      }
    } else {
      None
    }
  }

  /**
   * Convert value to string for expression evaluation (JS `stringConvert`).
   * Numbers are rendered with up to 15 fraction digits, trailing zeros
   * removed; arrays, objects and null have no string form.
   */
  def stringConvert(value: io.circe.Json): Option[String] = {
    if (value.isBoolean) {
      value.asBoolean.map(_.toString)
    } else if (value.isNumber) {
      value.asNumber.map(n => formatNumber(n.toDouble))
    } else if (value.isString) {
      value.asString
    } else {
      None
    }
  }

  // JS: x.toFixed(15).replace(/\.?0{0,15}$/, "")
  private def formatNumber(d: Double): String = toFixed(d, 15).replaceFirst("\\.?0{0,15}$", "")

  /**
   * Compare two JSON values (JS `Evaluator.compare`).
   *
   * The left operand's type drives the conversion of the right one. Arrays
   * and objects only compare equal (Some(0)) to a deeply equal value.
   * Returns None when the values are not comparable.
   */
  def compare(lhs: io.circe.Json, rhs: io.circe.Json): Option[Int] = {
    if (lhs.isNull || rhs.isNull) {
      return if (lhs.isNull && rhs.isNull) Some(0) else None
    }

    if (lhs.isNumber) {
      for (l <- numberConvert(lhs); r <- numberConvert(rhs)) yield (if (l == r) 0 else if (l > r) 1 else -1)
    } else if (lhs.isString) {
      for (l <- lhs.asString; r <- stringConvert(rhs)) yield Integer.signum(l.compareTo(r))
    } else if (lhs.isBoolean) {
      for (l <- lhs.asBoolean; r <- booleanConvert(rhs)) yield Integer.signum(java.lang.Boolean.compare(l, r))
    } else if (lhs == rhs) {
      Some(0)
    } else {
      None
    }
  }
}

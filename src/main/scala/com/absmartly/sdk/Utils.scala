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
    val md5Bytes = DigestUtils.md5(unit)
    // Convert to base64url (URL-safe, no padding)
    Base64.getUrlEncoder.withoutPadding().encodeToString(md5Bytes)
  }

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
   * Convert value to boolean for expression evaluation
   */
  def booleanConvert(value: io.circe.Json): Option[Boolean] = {
    if (value.isNull) {
      None
    } else if (value.isBoolean) {
      value.asBoolean
    } else if (value.isNumber) {
      value.asNumber.flatMap(_.toInt).map(_ != 0)
    } else if (value.isString) {
      value.asString.map(_.nonEmpty)
    } else {
      // Arrays and objects are truthy
      Some(true)
    }
  }

  /**
   * Convert value to number for expression evaluation
   */
  def numberConvert(value: io.circe.Json): Option[Double] = {
    if (value.isNull) {
      None
    } else if (value.isBoolean) {
      value.asBoolean.map(if (_) 1.0 else 0.0)
    } else if (value.isNumber) {
      value.asNumber.map(_.toDouble)
    } else if (value.isString) {
      value.asString.flatMap { s =>
        if (s.isEmpty) Some(0.0)
        else s.toDoubleOption
      }
    } else {
      None
    }
  }

  /**
   * Convert value to string for expression evaluation
   */
  def stringConvert(value: io.circe.Json): Option[String] = {
    if (value.isNull) {
      None
    } else if (value.isBoolean) {
      value.asBoolean.map(_.toString)
    } else if (value.isNumber) {
      value.asNumber.map { n =>
        n.toInt.map(_.toString).getOrElse(n.toDouble.toString)
      }
    } else if (value.isString) {
      value.asString
    } else {
      Some(value.noSpaces)
    }
  }

  /**
   * Compare two JSON values
   *
   * Used by eq, gt, gte, lt, lte operators.
   * Returns:
   *   Some(0) if equal
   *   Some(1) if lhs > rhs
   *   Some(-1) if lhs < rhs
   *   None if incomparable (null involved)
   */
  def compare(lhs: io.circe.Json, rhs: io.circe.Json): Option[Int] = {
    // If either is null, return None
    if (lhs.isNull || rhs.isNull) {
      return if (lhs.isNull && rhs.isNull) Some(0) else None
    }

    // Try numeric comparison first
    (numberConvert(lhs), numberConvert(rhs)) match {
      case (Some(l), Some(r)) =>
        if (l == r) Some(0)
        else if (l > r) Some(1)
        else Some(-1)
      case _ =>
        // Fall back to string comparison
        (stringConvert(lhs), stringConvert(rhs)) match {
          case (Some(l), Some(r)) =>
            val cmp = l.compareTo(r)
            if (cmp == 0) Some(0)
            else if (cmp > 0) Some(1)
            else Some(-1)
          case _ =>
            // Compare JSON representations
            val ls = lhs.noSpaces
            val rs = rhs.noSpaces
            val cmp = ls.compareTo(rs)
            if (cmp == 0) Some(0)
            else if (cmp > 0) Some(1)
            else Some(-1)
        }
    }
  }
}

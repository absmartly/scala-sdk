package com.absmartly.sdk

import io.circe.Json
import com.absmartly.sdk.jsonexpr.Evaluator
import scala.util.control.NonFatal

/**
 * Audience matcher using JSON expression evaluator
 */
class AudienceMatcher(vars: Map[String, Json]) {

  private val logger = Logger.safe

  /**
   * Evaluate audience filter (JS `AudienceMatcher.evaluate`).
   *
   * @param audience Optional audience filter JSON string
   * @return Some(true) if matches, Some(false) if it doesn't or the evaluation
   *         throws (so a strict audience fails closed), None when there is no
   *         usable filter (absent, unparseable, or not an array/object).
   *         Callers treat None as "no audience decision".
   */
  def evaluate(audience: Option[String]): Option[Boolean] = {
    audience.flatMap { audienceStr =>
      io.circe.parser.parse(audienceStr) match {
        case Right(json) =>
          json.asObject.flatMap(_("filter")).filter(f => f.isArray || f.isObject) match {
            case Some(filter) =>
              try Some(Utils.booleanConvert(Evaluator.evaluate(filter, vars)).getOrElse(false))
              catch {
                case NonFatal(e) =>
                  logger.error(s"Audience evaluation error: ${Logger.sanitize(String.valueOf(e.getMessage))} Audience: ${Logger.sanitize(audienceStr)}", e)
                  Some(false)
              }
            case None =>
              logger.debug(s"Audience has no array/object 'filter', ignoring: ${Logger.sanitize(audienceStr)}")
              None
          }
        case Left(parseError) =>
          logger.error(s"Audience parse error, ignoring audience: ${Logger.sanitize(parseError.getMessage)} Audience: ${Logger.sanitize(audienceStr)}")
          None
      }
    }
  }

  /**
   * Evaluate parsed assignment rules (JS `AudienceMatcher.evaluateParsedRules`).
   * The first rule with a valid action whose environments and conditions match
   * wins. A rule with an invalid action, a non-array `environments` or a
   * non-object `conditions` is skipped; a rule whose conditions throw is
   * skipped with a warning.
   *
   * @param environmentName The SDK's environment; a rule scoped to a non-empty
   *                        environments list never matches without one.
   */
  def evaluateRules(rules: List[Json], environmentName: Option[String]): Option[RuleAction] = {
    rules.iterator.flatMap { rule =>
      rule.asObject.flatMap { obj =>
        RuleAction.parse(obj).filter { _ =>
          val environmentsMatch = obj("environments").filterNot(_.isNull) match {
            case None => true
            case Some(envs) =>
              envs.asArray match {
                case None => false
                case Some(list) if list.isEmpty => true
                case Some(list) => environmentName.exists(env => list.contains(Json.fromString(env)))
              }
          }
          environmentsMatch && (obj("conditions").filterNot(_.isNull) match {
            case None => true
            case Some(conditions) if !conditions.isObject => false
            case Some(conditions) =>
              try Utils.booleanConvert(Evaluator.evaluate(conditions, vars)).contains(true)
              catch {
                case NonFatal(e) =>
                  val name = obj("name").flatMap(_.asString).getOrElse("")
                  logger.warn(
                    s"Failed to evaluate assignment rule conditions for rule ${Logger.sanitize(name, 100)}: ${Logger.sanitize(String.valueOf(e.getMessage))}"
                  )
                  false
              }
          })
        }
      }
    }.nextOption()
  }
}

object AudienceMatcher {
  private val logger = Logger.safe

  /**
   * Parse an experiment's `assignmentRules` JSON string into its `rules` list
   * (JS `AudienceMatcher.parseRules`). None when it is not valid JSON or has no
   * `rules` array.
   */
  def parseRules(assignmentRules: String): Option[List[Json]] =
    io.circe.parser.parse(assignmentRules) match {
      case Right(json) => json.asObject.flatMap(_("rules")).flatMap(_.asArray).map(_.toList)
      case Left(e) =>
        logger.error(s"Assignment rules parse error, ignoring rules: ${Logger.sanitize(e.getMessage)}")
        None
    }
}

/** What a matching assignment rule does: assign a fixed variant, or split by percentages. */
sealed trait RuleAction

object RuleAction {
  final case class Assign(variant: Int) extends RuleAction
  final case class Split(split: List[Double]) extends RuleAction

  private val SplitPercentagesSumTolerance = 0.01

  // JS parseFloat: the longest leading decimal literal, surrounding text ignored.
  private val FloatPrefix = """^\s*([+-]?(?:\d+\.?\d*|\.\d+)(?:[eE][+-]?\d+)?)""".r.unanchored

  private[sdk] def parse(rule: io.circe.JsonObject): Option[RuleAction] =
    rule("type").flatMap(_.asString) match {
      case Some("assign") =>
        rule("variant").flatMap(_.asNumber).flatMap(_.toInt).map(Assign(_))
      case Some("split") =>
        rule("percentages").flatMap(_.asString).flatMap(parseSplitPercentages).map(Split(_))
      case _ => None
    }

  /**
   * Parse "20/30/50" into [0.2, 0.3, 0.5] like the JS SDK: each value is
   * `parseFloat(value) / 100` with no normalisation. Non-numeric or negative
   * values, or a sum more than 0.01 (after rounding to two decimals) from 100,
   * make the split invalid.
   */
  private[sdk] def parseSplitPercentages(percentages: String): Option[List[Double]] = {
    val values = percentages.split("/", -1).toList.map {
      case FloatPrefix(num) => num.toDouble
      case _ => Double.NaN
    }
    if (values.exists(v => v.isNaN || v < 0)) None
    else {
      val gap = new java.math.BigDecimal(math.abs(100 - values.sum)).setScale(2, java.math.RoundingMode.HALF_UP).doubleValue
      if (gap > SplitPercentagesSumTolerance) None else Some(values.map(_ / 100))
    }
  }
}

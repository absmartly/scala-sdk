package com.absmartly.sdk.jsonexpr

import io.circe.Json
import com.absmartly.sdk.{Utils, Logger}
import scala.util.{Try, Success, Failure}

/**
 * JSON Expression Evaluator for audience targeting.
 *
 * Mirrors the JavaScript SDK's jsonexpr: an array is an implicit `and`, an
 * object is `{operator: args}`, anything else (and an unknown operator)
 * evaluates to null. Binary operators return null when either operand
 * evaluates to null.
 *
 * Operators: and, or, not, null, var, value, eq, gt, gte, lt, lte,
 * in (haystack-first; `contains` is an alias), match, and semver_eq,
 * semver_gt, semver_gte, semver_lt, semver_lte.
 */
object Evaluator {

  private val REGEX_TIMEOUT_MS = 100L
  private val MAX_PATTERN_LENGTH = 500
  private val MAX_INPUT_LENGTH = 25000
  private val MAX_ARRAY_SIZE_WARNING = 1000

  private val logger = Logger.safe

  /**
   * Evaluate a JSON expression against a context
   *
   * @param expr The expression to evaluate (an array, or a JSON object with one operator)
   * @param vars The context variables (for 'var' operator)
   * @return The result of evaluation, Json.Null when indeterminate
   */
  def evaluate(expr: Json, vars: Map[String, Json]): Json = {
    if (expr.isArray) {
      evaluateAnd(expr, vars)
    } else {
      expr.asObject.flatMap(_.toIterable.headOption) match {
        case Some((op, args)) => evaluateOperator(op, args, vars)
        case None => Json.Null
      }
    }
  }

  private def evaluateOperator(op: String, args: Json, vars: Map[String, Json]): Json = {
    op match {
      case "and" => evaluateAnd(args, vars)
      case "or" => evaluateOr(args, vars)
      case "not" => evaluateNot(args, vars)
      case "null" => evaluateNull(args, vars)
      case "var" => evaluateVar(args, vars)
      case "value" => args
      case "eq" => binary(args, vars)((l, r) => Utils.compare(l, r).map(_ == 0))
      case "gt" => binary(args, vars)((l, r) => Utils.compare(l, r).map(_ > 0))
      case "gte" => binary(args, vars)((l, r) => Utils.compare(l, r).map(_ >= 0))
      case "lt" => binary(args, vars)((l, r) => Utils.compare(l, r).map(_ < 0))
      case "lte" => binary(args, vars)((l, r) => Utils.compare(l, r).map(_ <= 0))
      case "in" | "contains" => binary(args, vars)(evaluateIn)
      case "match" => binary(args, vars)(evaluateMatch)
      case "semver_eq" => binary(args, vars)((l, r) => versionCompare(l, r).map(_ == 0))
      case "semver_gt" => binary(args, vars)((l, r) => versionCompare(l, r).map(_ > 0))
      case "semver_gte" => binary(args, vars)((l, r) => versionCompare(l, r).map(_ >= 0))
      case "semver_lt" => binary(args, vars)((l, r) => versionCompare(l, r).map(_ < 0))
      case "semver_lte" => binary(args, vars)((l, r) => versionCompare(l, r).map(_ <= 0))
      case _ =>
        logger.error(s"Unknown operator '${Logger.sanitize(op, 50)}' with args: ${Logger.sanitize(args.noSpaces)}")
        Json.Null
    }
  }

  private def truthy(value: Json): Boolean = Utils.booleanConvert(value).getOrElse(false)

  private def evaluateAnd(args: Json, vars: Map[String, Json]): Json = {
    args.asArray match {
      case Some(arr) => Json.fromBoolean(arr.forall(arg => truthy(evaluate(arg, vars))))
      case None => Json.Null
    }
  }

  private def evaluateOr(args: Json, vars: Map[String, Json]): Json = {
    args.asArray match {
      case Some(arr) => Json.fromBoolean(arr.isEmpty || arr.exists(arg => truthy(evaluate(arg, vars))))
      case None => Json.Null
    }
  }

  private def evaluateNot(args: Json, vars: Map[String, Json]): Json = {
    Json.fromBoolean(!truthy(evaluate(args, vars)))
  }

  private def evaluateNull(args: Json, vars: Map[String, Json]): Json = {
    Json.fromBoolean(evaluate(args, vars).isNull)
  }

  private def evaluateVar(args: Json, vars: Map[String, Json]): Json = {
    val pathOpt = args.asString.orElse(args.asObject.flatMap(_("path")).flatMap(_.asString))
    pathOpt match {
      case Some(path) =>
        path.split("/", -1).foldLeft(Option(Json.fromFields(vars))) { (target, frag) =>
          target.flatMap { current =>
            current.asObject.flatMap(_(frag))
              .orElse(current.asArray.flatMap(arr => frag.toIntOption.flatMap(arr.lift)))
          }
        }.getOrElse(Json.Null)
      case None => Json.Null
    }
  }

  private def binary(args: Json, vars: Map[String, Json])(op: (Json, Json) => Option[Boolean]): Json = {
    args.asArray match {
      case Some(arr) =>
        val lhs = arr.headOption.map(evaluate(_, vars)).getOrElse(Json.Null)
        if (lhs.isNull) {
          Json.Null
        } else {
          val rhs = arr.lift(1).map(evaluate(_, vars)).getOrElse(Json.Null)
          if (rhs.isNull) Json.Null
          else op(lhs, rhs).map(Json.fromBoolean).getOrElse(Json.Null)
        }
      case None => Json.Null
    }
  }

  // Haystack-first: [haystack, needle], as in the JavaScript SDK.
  private def evaluateIn(haystack: Json, needle: Json): Option[Boolean] = {
    haystack.asArray match {
      case Some(items) =>
        if (items.size > MAX_ARRAY_SIZE_WARNING) {
          logger.warn(s"Large array in 'in' operator: ${items.size} elements (performance warning)")
        }
        Some(items.exists(item => Utils.compare(item, needle).contains(0)))
      case None =>
        haystack.asString match {
          case Some(str) => Some(Utils.stringConvert(needle).exists(str.contains(_)))
          case None =>
            haystack.asObject.map(obj => Utils.stringConvert(needle).exists(obj.contains))
        }
    }
  }

  /**
   * Compare two versions like the JS SDK's `Evaluator.versionCompare`. Both
   * sides are converted to strings; a leading "v"/"V" and build metadata
   * ("+...") are ignored, missing core parts count as "0", numeric identifiers
   * compare numerically (and sort before alphanumeric ones), and a release
   * sorts after any of its pre-releases. None when either side is empty or
   * has no string form.
   */
  private[sdk] def versionCompare(lhs: Json, rhs: Json): Option[Int] = {
    for {
      l <- Utils.stringConvert(lhs).filter(_.nonEmpty).flatMap(parseSemver)
      r <- Utils.stringConvert(rhs).filter(_.nonEmpty).flatMap(parseSemver)
    } yield {
      val (lParts, lPre) = l
      val (rParts, rPre) = r
      val core = (0 until math.max(lParts.length, rParts.length)).iterator
        .map(i => compareIdentifiers(lParts.lift(i).getOrElse("0"), rParts.lift(i).getOrElse("0")))
        .find(_ != 0)
      core.getOrElse {
        if (lPre.isEmpty && rPre.isEmpty) 0
        else if (lPre.isEmpty) 1
        else if (rPre.isEmpty) -1
        else {
          val lp = lPre.split("\\.", -1)
          val rp = rPre.split("\\.", -1)
          (0 until math.max(lp.length, rp.length)).iterator.map { i =>
            if (i >= lp.length) -1
            else if (i >= rp.length) 1
            else compareIdentifiers(lp(i), rp(i))
          }.find(_ != 0).getOrElse(0)
        }
      }
    }
  }

  // (core parts, pre-release); None for an empty version or core.
  private def parseSemver(version: String): Option[(Array[String], String)] = {
    val noPrefix = if (version.startsWith("v") || version.startsWith("V")) version.substring(1) else version
    val v = noPrefix.indexOf('+') match {
      case -1 => noPrefix
      case i => noPrefix.substring(0, i)
    }
    if (v.isEmpty) None
    else {
      val dash = v.indexOf('-')
      val core = if (dash < 0) v else v.substring(0, dash)
      val preRelease = if (dash < 0) "" else v.substring(dash + 1)
      if (core.isEmpty) None else Some((core.split("\\.", -1), preRelease))
    }
  }

  private val NumericIdentifier = "^[0-9]+$".r

  private def compareIdentifiers(a: String, b: String): Int = {
    val aIsNum = NumericIdentifier.matches(a)
    val bIsNum = NumericIdentifier.matches(b)
    if (aIsNum && bIsNum) {
      val aNorm = stripLeadingZeros(a)
      val bNorm = stripLeadingZeros(b)
      if (aNorm.length != bNorm.length) (if (aNorm.length > bNorm.length) 1 else -1)
      else Integer.signum(aNorm.compareTo(bNorm))
    } else if (aIsNum) -1
    else if (bIsNum) 1
    else Integer.signum(a.compareTo(b))
  }

  private def stripLeadingZeros(s: String): String = {
    val stripped = s.dropWhile(_ == '0')
    if (stripped.isEmpty) "0" else stripped
  }

  // Invalid patterns yield null; resource-limit rejections and deadlines yield false.
  private def evaluateMatch(text: Json, pattern: Json): Option[Boolean] = {
    for {
      textStr <- Utils.stringConvert(text)
      patternStr <- Utils.stringConvert(pattern)
      result <- {
        if (patternStr.length > MAX_PATTERN_LENGTH) {
          logger.warn(s"Regex pattern too long: ${patternStr.length} chars (max $MAX_PATTERN_LENGTH)")
          Some(false)
        } else if (textStr.length > MAX_INPUT_LENGTH) {
          logger.warn(s"Regex input too long: ${textStr.length} chars (max $MAX_INPUT_LENGTH)")
          Some(false)
        } else {
          Try(patternStr.r) match {
            case Success(regex) =>
              try {
                Some(regex.findFirstIn(new DeadlineCharSequence(textStr, System.nanoTime() + REGEX_TIMEOUT_MS * 1000000L)).isDefined)
              } catch {
                case _: RegexTimeoutException =>
                  logger.error(s"Regex timeout after ${REGEX_TIMEOUT_MS}ms: pattern='${Logger.sanitize(patternStr)}'")
                  Some(false)
              }
            case Failure(e) =>
              logger.warn(s"Invalid regex pattern: '${Logger.sanitize(patternStr)}' - ${Logger.sanitize(String.valueOf(e.getMessage))}")
              None
          }
        }
      }
    } yield result
  }
}

private[jsonexpr] class RegexTimeoutException extends RuntimeException("regex deadline exceeded", null, false, false)

/** CharSequence that aborts regex matching on the calling thread once the deadline passes. */
private[jsonexpr] class DeadlineCharSequence(inner: CharSequence, deadlineNanos: Long) extends CharSequence {
  private var counter = 0
  override def length(): Int = inner.length()
  override def charAt(index: Int): Char = {
    counter += 1
    if ((counter & 0xff) == 0 && System.nanoTime() - deadlineNanos > 0) throw new RegexTimeoutException
    inner.charAt(index)
  }
  override def subSequence(start: Int, end: Int): CharSequence = new DeadlineCharSequence(inner.subSequence(start, end), deadlineNanos)
  override def toString: String = inner.toString
}

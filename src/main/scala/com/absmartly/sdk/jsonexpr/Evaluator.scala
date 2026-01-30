package com.absmartly.sdk.jsonexpr

import io.circe.Json
import com.absmartly.sdk.Utils

/**
 * JSON Expression Evaluator for audience targeting
 *
 * Implements 13 operators:
 * - and, or, not
 * - null
 * - var, value
 * - eq, gt, gte, lt, lte
 * - in, match
 */
object Evaluator {

  /**
   * Evaluate a JSON expression against a context
   *
   * @param expr The expression to evaluate (JSON object with operator)
   * @param vars The context variables (for 'var' operator)
   * @return The result of evaluation
   */
  def evaluate(expr: Json, vars: Map[String, Json]): Json = {
    if (expr.isNull) {
      return Json.Null
    }

    expr.asObject match {
      case None => expr // Literal value
      case Some(obj) =>
        if (obj.isEmpty) {
          // Empty object evaluates to true
          return Json.fromBoolean(true)
        }

        // Get the operator and its arguments
        val (op, args) = obj.toList.head
        evaluateOperator(op, args, vars)
    }
  }

  private def evaluateOperator(op: String, args: Json, vars: Map[String, Json]): Json = {
    op match {
      case "and" => evaluateAnd(args, vars)
      case "or" => evaluateOr(args, vars)
      case "not" => evaluateNot(args, vars)
      case "null" => evaluateNull(args, vars)
      case "var" => evaluateVar(args, vars)
      case "value" => args // Literal value
      case "eq" => evaluateEq(args, vars)
      case "gt" => evaluateGt(args, vars)
      case "gte" => evaluateGte(args, vars)
      case "lt" => evaluateLt(args, vars)
      case "lte" => evaluateLte(args, vars)
      case "in" => evaluateIn(args, vars)
      case "match" => evaluateMatch(args, vars)
      case _ => Json.Null // Unknown operator
    }
  }

  // AND operator: all arguments must be truthy
  private def evaluateAnd(args: Json, vars: Map[String, Json]): Json = {
    args.asArray match {
      case Some(arr) =>
        if (arr.isEmpty) {
          Json.fromBoolean(true) // Empty AND is true
        } else {
          val result = arr.forall { arg =>
            val value = evaluate(arg, vars)
            Utils.booleanConvert(value).getOrElse(false)
          }
          Json.fromBoolean(result)
        }
      case None => Json.fromBoolean(false)
    }
  }

  // OR operator: any argument must be truthy
  private def evaluateOr(args: Json, vars: Map[String, Json]): Json = {
    args.asArray match {
      case Some(arr) =>
        if (arr.isEmpty) {
          Json.fromBoolean(false) // Empty OR is false
        } else {
          val result = arr.exists { arg =>
            val value = evaluate(arg, vars)
            Utils.booleanConvert(value).getOrElse(false)
          }
          Json.fromBoolean(result)
        }
      case None => Json.fromBoolean(false)
    }
  }

  // NOT operator: negate boolean value
  private def evaluateNot(args: Json, vars: Map[String, Json]): Json = {
    val value = evaluate(args, vars)
    val boolValue = Utils.booleanConvert(value).getOrElse(false)
    Json.fromBoolean(!boolValue)
  }

  // NULL operator: check if value is null
  private def evaluateNull(args: Json, vars: Map[String, Json]): Json = {
    val value = evaluate(args, vars)
    Json.fromBoolean(value.isNull)
  }

  // VAR operator: extract variable from context
  private def evaluateVar(args: Json, vars: Map[String, Json]): Json = {
    args.asString match {
      case Some(path) =>
        // Split path by '/' and traverse
        val parts = path.split('/').filter(_.nonEmpty)
        var current = Json.fromFields(vars)

        for (part <- parts) {
          current = current.asObject match {
            case Some(obj) => obj(part).getOrElse(Json.Null)
            case None => Json.Null
          }
        }
        current
      case None => Json.Null
    }
  }

  // EQ operator: equality comparison
  private def evaluateEq(args: Json, vars: Map[String, Json]): Json = {
    args.asArray match {
      case Some(arr) if arr.length >= 2 =>
        val lhs = evaluate(arr(0), vars)
        val rhs = evaluate(arr(1), vars)

        Utils.compare(lhs, rhs) match {
          case Some(0) => Json.fromBoolean(true)
          case Some(_) => Json.fromBoolean(false)
          case None => Json.fromBoolean(lhs.isNull && rhs.isNull)
        }
      case _ => Json.fromBoolean(false)
    }
  }

  // GT operator: greater than
  private def evaluateGt(args: Json, vars: Map[String, Json]): Json = {
    args.asArray match {
      case Some(arr) if arr.length >= 2 =>
        val lhs = evaluate(arr(0), vars)
        val rhs = evaluate(arr(1), vars)

        Utils.compare(lhs, rhs) match {
          case Some(cmp) => Json.fromBoolean(cmp > 0)
          case None => Json.fromBoolean(false)
        }
      case _ => Json.fromBoolean(false)
    }
  }

  // GTE operator: greater than or equal
  private def evaluateGte(args: Json, vars: Map[String, Json]): Json = {
    args.asArray match {
      case Some(arr) if arr.length >= 2 =>
        val lhs = evaluate(arr(0), vars)
        val rhs = evaluate(arr(1), vars)

        Utils.compare(lhs, rhs) match {
          case Some(cmp) => Json.fromBoolean(cmp >= 0)
          case None => Json.fromBoolean(false)
        }
      case _ => Json.fromBoolean(false)
    }
  }

  // LT operator: less than
  private def evaluateLt(args: Json, vars: Map[String, Json]): Json = {
    args.asArray match {
      case Some(arr) if arr.length >= 2 =>
        val lhs = evaluate(arr(0), vars)
        val rhs = evaluate(arr(1), vars)

        Utils.compare(lhs, rhs) match {
          case Some(cmp) => Json.fromBoolean(cmp < 0)
          case None => Json.fromBoolean(false)
        }
      case _ => Json.fromBoolean(false)
    }
  }

  // LTE operator: less than or equal
  private def evaluateLte(args: Json, vars: Map[String, Json]): Json = {
    args.asArray match {
      case Some(arr) if arr.length >= 2 =>
        val lhs = evaluate(arr(0), vars)
        val rhs = evaluate(arr(1), vars)

        Utils.compare(lhs, rhs) match {
          case Some(cmp) => Json.fromBoolean(cmp <= 0)
          case None => Json.fromBoolean(false)
        }
      case _ => Json.fromBoolean(false)
    }
  }

  // IN operator: contains check
  private def evaluateIn(args: Json, vars: Map[String, Json]): Json = {
    args.asArray match {
      case Some(arr) if arr.length >= 2 =>
        val needle = evaluate(arr(0), vars)
        val haystack = evaluate(arr(1), vars)

        // Check if needle is in haystack
        val result: Boolean = (needle, haystack) match {
          case (n, h) if n.isNull || h.isNull => false
          case (n, h) if h.isArray =>
            h.asArray.exists(_.contains(n))
          case (n, h) if h.isString && n.isString =>
            (for {
              haystackStr <- h.asString
              needleStr <- n.asString
            } yield haystackStr.contains(needleStr)).getOrElse(false)
          case _ => false
        }

        Json.fromBoolean(result)
      case _ => Json.fromBoolean(false)
    }
  }

  // MATCH operator: regex matching
  private def evaluateMatch(args: Json, vars: Map[String, Json]): Json = {
    args.asArray match {
      case Some(arr) if arr.length >= 2 =>
        val text = evaluate(arr(0), vars)
        val pattern = evaluate(arr(1), vars)

        val result = for {
          textStr <- text.asString
          patternStr <- pattern.asString
          regex <- scala.util.Try(patternStr.r).toOption
        } yield regex.findFirstIn(textStr).isDefined

        Json.fromBoolean(result.getOrElse(false))
      case _ => Json.fromBoolean(false)
    }
  }
}

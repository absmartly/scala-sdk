package com.absmartly.sdk

import io.circe.Json
import com.absmartly.sdk.jsonexpr.Evaluator

/**
 * Audience matcher using JSON expression evaluator
 */
class AudienceMatcher(vars: Map[String, Json]) {

  /**
   * Evaluate audience filter
   *
   * @param audience Optional audience filter JSON string
   * @return Some(true) if matches, Some(false) if doesn't match, None if no filter or error
   */
  def evaluate(audience: Option[String]): Option[Boolean] = {
    audience match {
      case None => None
      case Some(audienceStr) =>
        try {
          io.circe.parser.parse(audienceStr).toOption.flatMap { json =>
            json.asObject.flatMap { obj =>
              obj("filter").map { filter =>
                // If filter is an array, treat as AND operation
                val result = filter.asArray match {
                  case Some(arr) if arr.isEmpty =>
                    Json.True // Empty filter matches all
                  case Some(arr) =>
                    // Evaluate each filter and AND them together
                    val allTrue = arr.forall { f =>
                      val res = Evaluator.evaluate(f, vars)
                      Utils.booleanConvert(res).getOrElse(false)
                    }
                    Json.fromBoolean(allTrue)
                  case None =>
                    // Single filter expression
                    Evaluator.evaluate(filter, vars)
                }
                Utils.booleanConvert(result).getOrElse(false)
              }
            }
          }.orElse(Some(true))
        } catch {
          case _: Exception => None
        }
    }
  }
}

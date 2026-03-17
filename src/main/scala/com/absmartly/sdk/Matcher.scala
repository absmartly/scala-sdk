package com.absmartly.sdk

import io.circe.Json
import com.absmartly.sdk.jsonexpr.Evaluator
import scala.util.control.NonFatal

/**
 * Audience matcher using JSON expression evaluator
 */
class AudienceMatcher(vars: Map[String, Json]) {

  private val logger = Logger.get

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
          io.circe.parser.parse(audienceStr) match {
            case Right(json) =>
              json.asObject match {
                case Some(obj) =>
                  obj("filter") match {
                    case Some(filter) =>
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
                      Some(Utils.booleanConvert(result).getOrElse(false))
                    case None =>
                      logger.warn(s"Audience missing 'filter' key, defaulting to match all: $audienceStr")
                      Some(true)
                  }
                case None =>
                  logger.warn(s"Audience not an object, defaulting to match all: $audienceStr")
                  Some(true)
              }
            case Left(parseError) =>
              logger.error(s"Audience parse error, defaulting to match all: ${parseError.getMessage}\nAudience: $audienceStr")
              Some(true)
          }
        } catch {
          case NonFatal(e) =>
            logger.error(s"Audience evaluation error: ${e.getMessage}\nAudience: $audienceStr", e)
            None
        }
    }
  }
}

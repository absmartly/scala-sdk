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
   * @return Some(true) if matches, Some(false) if it doesn't, None when there is
   *         no usable filter (absent, unparseable, or not an array/object) or the
   *         evaluation fails. Callers treat None as "no audience decision".
   */
  def evaluate(audience: Option[String]): Option[Boolean] = {
    audience.flatMap { audienceStr =>
      try {
        io.circe.parser.parse(audienceStr) match {
          case Right(json) =>
            json.asObject.flatMap(_("filter")).filter(f => f.isArray || f.isObject) match {
              case Some(filter) =>
                Some(Utils.booleanConvert(Evaluator.evaluate(filter, vars)).getOrElse(false))
              case None =>
                logger.debug(s"Audience has no array/object 'filter', ignoring: ${Logger.sanitize(audienceStr)}")
                None
            }
          case Left(parseError) =>
            logger.error(s"Audience parse error, ignoring audience: ${Logger.sanitize(parseError.getMessage)} Audience: ${Logger.sanitize(audienceStr)}")
            None
        }
      } catch {
        case NonFatal(e) =>
          logger.error(s"Audience evaluation error: ${Logger.sanitize(String.valueOf(e.getMessage))} Audience: ${Logger.sanitize(audienceStr)}", e)
          None
      }
    }
  }
}

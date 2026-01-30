package com.absmartly.sdk

import io.circe.Json

/**
 * Event logger interface for capturing SDK events
 */
trait EventLogger {
  def logEvent(eventType: String, data: Json): Unit
}

/**
 * No-op event logger (default)
 */
object NoOpEventLogger extends EventLogger {
  override def logEvent(eventType: String, data: Json): Unit = ()
}

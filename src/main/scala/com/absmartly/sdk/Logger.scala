package com.absmartly.sdk

import scala.util.control.NonFatal

trait Logger {
  def debug(message: => String): Unit
  def info(message: => String): Unit
  def warn(message: => String): Unit
  def error(message: => String): Unit
  def error(message: => String, throwable: Throwable): Unit
}

class ConsoleLogger extends Logger {
  private def timestamp(): String = {
    val now = java.time.Instant.now()
    java.time.format.DateTimeFormatter.ISO_INSTANT.format(now)
  }

  override def debug(message: => String): Unit = {
    println(s"[${timestamp()}] DEBUG - $message")
  }

  override def info(message: => String): Unit = {
    println(s"[${timestamp()}] INFO - $message")
  }

  override def warn(message: => String): Unit = {
    System.err.println(s"[${timestamp()}] WARN - $message")
  }

  override def error(message: => String): Unit = {
    System.err.println(s"[${timestamp()}] ERROR - $message")
  }

  override def error(message: => String, throwable: Throwable): Unit = {
    System.err.println(s"[${timestamp()}] ERROR - $message")
    throwable.printStackTrace(System.err)
  }
}

class NoOpLogger extends Logger {
  override def debug(message: => String): Unit = ()
  override def info(message: => String): Unit = ()
  override def warn(message: => String): Unit = ()
  override def error(message: => String): Unit = ()
  override def error(message: => String, throwable: Throwable): Unit = ()
}

private[sdk] object SafeLogger extends Logger {
  private def guarded(f: Logger => Unit): Unit =
    try f(Logger.get)
    catch { case NonFatal(_) => () }

  override def debug(message: => String): Unit = guarded(_.debug(message))
  override def info(message: => String): Unit = guarded(_.info(message))
  override def warn(message: => String): Unit = guarded(_.warn(message))
  override def error(message: => String): Unit = guarded(_.error(message))
  override def error(message: => String, throwable: Throwable): Unit = guarded(_.error(message, throwable))
}

object Logger {
  @volatile private var instance: Logger = new NoOpLogger()

  private[sdk] def safe: Logger = SafeLogger

  /** Bounds untrusted text and replaces control characters before it is logged or surfaced. */
  private[sdk] def sanitize(text: String, maxLength: Int = 200): String = {
    val cleaned = text.map(c => if (Character.isISOControl(c)) ' ' else c)
    if (cleaned.length > maxLength) cleaned.take(maxLength) + s"...[truncated ${cleaned.length - maxLength} chars]"
    else cleaned
  }

  def setLogger(logger: Logger): Unit = {
    require(logger != null, "logger must not be null")
    instance = logger
  }

  def get: Logger = instance
}

sealed trait SDKException extends Exception {
  def message: String
  def cause: Option[Throwable]

  override def getMessage: String = message
  override def getCause: Throwable = cause.orNull
}

case class NetworkException(
  message: String,
  cause: Option[Throwable] = None
) extends Exception(message, cause.orNull) with SDKException

case class AuthenticationException(
  message: String,
  cause: Option[Throwable] = None
) extends Exception(message, cause.orNull) with SDKException

case class ParseException(
  message: String,
  cause: Option[Throwable] = None
) extends Exception(message, cause.orNull) with SDKException

case class ServerException(
  statusCode: Int,
  body: String,
  cause: Option[Throwable] = None
) extends Exception(s"Server error $statusCode: $body", cause.orNull) with SDKException {
  override def message: String = s"Server error $statusCode: $body"
}

case class ValidationException(
  message: String,
  cause: Option[Throwable] = None
) extends Exception(message, cause.orNull) with SDKException

case class StateException(
  message: String,
  cause: Option[Throwable] = None
) extends Exception(message, cause.orNull) with SDKException

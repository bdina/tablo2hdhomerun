package app.sys

import app.config.LoggingConfig

object LogConfig {
  private[sys] def pekkoLogLevel(level: String): String =
    level.trim.toLowerCase match {
      case "off"              => "OFF"
      case "error"            => "ERROR"
      case "warn" | "warning" => "WARNING"
      case "info"             => "INFO"
      case "debug"            => "DEBUG"
      case other              => other.trim.toUpperCase
    }

  def configure(logging: LoggingConfig): Unit = {
    logging.logLevel.foreach { level =>
      System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", level.toLowerCase)
    }
    logging.pekkoLogLevel.foreach { level =>
      System.setProperty("pekko.loglevel", pekkoLogLevel(level))
    }
  }

  def truncate(value: String, maxLen: Int = 256): String =
    if (value.length <= maxLen) value else s"${value.take(maxLen)}... (${value.length} chars)"
}

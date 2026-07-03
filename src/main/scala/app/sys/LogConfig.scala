package app.sys

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

  def configure(): Unit = {
    Option(System.getenv("LOG_LEVEL")).foreach { level =>
      System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", level.toLowerCase)
    }
    Option(System.getenv("PEKKO_LOG_LEVEL")).orElse(Option(System.getenv("LOG_LEVEL"))).foreach { level =>
      System.setProperty("pekko.loglevel", pekkoLogLevel(level))
    }
  }

  def truncate(value: String, maxLen: Int = 256): String =
    if (value.length <= maxLen) value else s"${value.take(maxLen)}... (${value.length} chars)"
}

package app.sys

object LogConfig {
  def configure(): Unit = {
    Option(System.getenv("LOG_LEVEL")).foreach { level =>
      System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", level.toLowerCase)
    }
    Option(System.getenv("PEKKO_LOG_LEVEL")).orElse(Option(System.getenv("LOG_LEVEL"))).foreach { level =>
      System.setProperty("pekko.loglevel", level.toUpperCase)
    }
  }

  def truncate(value: String, maxLen: Int = 256): String =
    if (value.length <= maxLen) value else s"${value.take(maxLen)}... (${value.length} chars)"
}

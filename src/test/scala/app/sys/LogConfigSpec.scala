package app.sys

import app.config.LoggingConfig

import org.junit.runner.RunWith
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.junit.JUnitRunner

@RunWith(classOf[JUnitRunner])
class LogConfigSpec extends AnyFunSuite with Matchers {
  test("pekkoLogLevel maps warn to WARNING") {
    val _ = LogConfig.pekkoLogLevel("warn") shouldBe "WARNING"
    val _ = LogConfig.pekkoLogLevel("WARN") shouldBe "WARNING"
    LogConfig.pekkoLogLevel("warning") shouldBe "WARNING"
  }

  test("pekkoLogLevel maps standard levels") {
    val _ = LogConfig.pekkoLogLevel("off") shouldBe "OFF"
    val _ = LogConfig.pekkoLogLevel("error") shouldBe "ERROR"
    val _ = LogConfig.pekkoLogLevel("info") shouldBe "INFO"
    LogConfig.pekkoLogLevel("debug") shouldBe "DEBUG"
  }

  test("configure sets logging system properties") {
    val slf4jKey = "org.slf4j.simpleLogger.defaultLogLevel"
    val pekkoKey = "pekko.loglevel"
    val priorSlf4j = Option(System.getProperty(slf4jKey))
    val priorPekko = Option(System.getProperty(pekkoKey))
    try {
      LogConfig.configure(LoggingConfig(logLevel = Some("debug"), pekkoLogLevel = Some("warn")))
      val _ = System.getProperty(slf4jKey) shouldBe "debug"
      System.getProperty(pekkoKey) shouldBe "WARNING"
    } finally {
      priorSlf4j.foreach(v => System.setProperty(slf4jKey, v))
      priorPekko.foreach(v => System.setProperty(pekkoKey, v))
      if (priorSlf4j.isEmpty) { System.clearProperty(slf4jKey); () }
      if (priorPekko.isEmpty) { System.clearProperty(pekkoKey); () }
    }
  }
}

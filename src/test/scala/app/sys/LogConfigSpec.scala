package app.sys

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class LogConfigSpec extends AnyFunSuite with Matchers {
  test("pekkoLogLevel maps warn to WARNING") {
    LogConfig.pekkoLogLevel("warn") shouldBe "WARNING"
    LogConfig.pekkoLogLevel("WARN") shouldBe "WARNING"
    LogConfig.pekkoLogLevel("warning") shouldBe "WARNING"
  }

  test("pekkoLogLevel maps standard levels") {
    LogConfig.pekkoLogLevel("off") shouldBe "OFF"
    LogConfig.pekkoLogLevel("error") shouldBe "ERROR"
    LogConfig.pekkoLogLevel("info") shouldBe "INFO"
    LogConfig.pekkoLogLevel("debug") shouldBe "DEBUG"
  }
}

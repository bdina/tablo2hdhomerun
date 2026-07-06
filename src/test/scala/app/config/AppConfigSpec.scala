package app.config

import org.junit.runner.RunWith
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.junit.JUnitRunner

import ConfigTypes.{HttpProtocol, Port}

@RunWith(classOf[JUnitRunner])
class AppConfigSpec extends AnyFlatSpec with Matchers {

  "AppConfig.load" should "apply defaults when env is empty" in {
    val loaded = AppConfig.load(Map.empty)
    val config = loaded.config
    val _ = config.tablo.ipHost shouldBe "127.0.0.1"
    val _ = config.tablo.gen shouldBe TabloGen.FourthGen
    val _ = config.tablo.protocol shouldBe HttpProtocol.Http
    val _ = config.tablo.port shouldBe Port.DefaultTablo
    val _ = config.tablo.deviceName shouldBe None
    val _ = config.proxy.bindHost shouldBe "127.0.0.1"
    val _ = config.proxy.port shouldBe Port.DefaultProxy
    val _ = config.stream.backend shouldBe StreamBackendKind.Hls
    val _ = config.stream.resilient.maxGapSec shouldBe 60
    val _ = config.stream.resilient.retryMinBackoffSec shouldBe 2
    val _ = config.stream.resilient.retryMaxBackoffSec shouldBe 30
    val _ = config.stream.resilient.recoveryTimeoutSec shouldBe 60
    val _ = config.stream.hls.stallPolls shouldBe 3
    val _ = config.stream.hls.heartbeatSec shouldBe 60
    val _ = config.stream.hls.health.windowSec shouldBe 10
    val _ = config.stream.hls.health.ccMax shouldBe 30
    val _ = config.stream.hls.health.syncMax shouldBe 10
    val _ = config.stream.hls.health.nullRatioMax shouldBe 0.6
    val _ = config.stream.hls.health.enforce shouldBe false
    val _ = config.stream.hls.pollFailuresMax shouldBe 60
    config.mediaRoot shouldBe None
    loaded.tabloAuth.email shouldBe None
    loaded.tabloAuth.password shouldBe None
    loaded.tabloAuth.credentials shouldBe None
    val _ = loaded.logging.logLevel shouldBe None
    loaded.logging.pekkoLogLevel shouldBe None
  }

  it should "parse integer env vars and fall back on invalid values" in {
    val config = AppConfig.load(Map(
      "STREAM_MAX_GAP_SEC" -> "120",
      "STREAM_RETRY_MIN_BACKOFF_SEC" -> "bad"
    )).config
    val _ = config.stream.resilient.maxGapSec shouldBe 120
    config.stream.resilient.retryMinBackoffSec shouldBe 2
  }

  it should "parse double and bool env vars" in {
    val config = AppConfig.load(Map(
      "STREAM_HLS_NULL_RATIO_MAX" -> "0.8",
      "STREAM_HLS_HEALTH_ENFORCE" -> "true"
    )).config
    val _ = config.stream.hls.health.nullRatioMax shouldBe 0.8
    val _ = config.stream.hls.health.enforce shouldBe true

    val notTrue = AppConfig.load(Map("STREAM_HLS_HEALTH_ENFORCE" -> "yes")).config
    notTrue.stream.hls.health.enforce shouldBe false
  }

  it should "resolve 4th-gen key aliases" in {
    val config = AppConfig.load(Map(
      "HashKey" -> "hash-alias",
      "DeviceKey" -> "device-alias"
    )).config
    val _ = config.tablo.hashKey shouldBe "hash-alias"
    config.tablo.deviceKey shouldBe "device-alias"
  }

  it should "support legacy tablo gen" in {
    AppConfig.load(Map("TABLO_GEN" -> "legacy")).config.tablo.gen shouldBe TabloGen.Legacy
  }

  it should "parse stream backend from env" in {
    AppConfig.load(Map("STREAM_BACKEND" -> "ffmpeg")).config.stream.backend shouldBe StreamBackendKind.Ffmpeg
  }

  it should "read logging, media, and auth env vars without retaining auth on config" in {
    val loaded = AppConfig.load(Map(
      "LOG_LEVEL" -> "debug",
      "PEKKO_LOG_LEVEL" -> "warn",
      "MEDIA_ROOT" -> "/media",
      "TABLO_EMAIL" -> "user@example.com",
      "TABLO_PASSWORD" -> "secret"
    ))
    val _ = loaded.logging.logLevel shouldBe Some("debug")
    val _ = loaded.logging.pekkoLogLevel shouldBe Some("warn")
    loaded.config.mediaRoot shouldBe Some("/media")
    loaded.tabloAuth.credentials shouldBe Some(TabloCredentials("user@example.com", "secret"))
  }

  "AppConfig.loadFrom" should "read only requested keys from a getter" in {
    val env = Map("TABLO_GEN" -> "legacy", "UNUSED" -> "ignored")
    val loaded = AppConfig.loadFrom(env.get)
    loaded.config.tablo.gen shouldBe TabloGen.Legacy
  }
}

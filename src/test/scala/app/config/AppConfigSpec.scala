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
    val _ = config.tablo.port shouldBe Port.DefaultTabloFourthGen
    val _ = config.tablo.deviceName shouldBe None
    val _ = config.proxy.bindHost shouldBe "127.0.0.1"
    val _ = config.proxy.port shouldBe Port.DefaultProxy
    val _ = config.proxy.deviceId shouldBe "12345678"
    val _ = config.proxy.tunerCount shouldBe None
    val _ = config.proxy.effectiveTunerCount shouldBe 2
    val _ = config.proxy.enableUdpDiscovery shouldBe true
    val _ = config.proxy.enableHlsEndpoint shouldBe true
    val _ = config.proxy.idleGraceSec shouldBe 75
    val _ = config.proxy.enablePreRollKeepAlive shouldBe true
    val _ = config.proxy.preRollIntervalMs shouldBe 100
    val _ = config.proxy.preRollPackets shouldBe 7
    val _ = config.stream.backend shouldBe StreamBackendKind.Hls
    val _ = config.stream.resilient.stallTimeoutSec shouldBe 8
    val _ = config.stream.resilient.tuneTimeoutSec shouldBe 20
    val _ = config.stream.resilient.retryDelaySec shouldBe 1
    val _ = config.stream.resilient.recoveryTimeoutSec shouldBe 60
    val _ = config.stream.hls.heartbeatSec shouldBe 60
    val _ = config.stream.hls.pollFailuresMax shouldBe 4
    val _ = config.mediaRoot shouldBe None
    val _ = loaded.tabloAuth.email shouldBe None
    val _ = loaded.tabloAuth.password shouldBe None
    val _ = loaded.tabloAuth.credentials shouldBe None
    val _ = loaded.logging.logLevel shouldBe None
    loaded.logging.pekkoLogLevel shouldBe None
  }

  it should "parse integer env vars and fall back on invalid values" in {
    val config = AppConfig.load(Map(
      "STREAM_STALL_TIMEOUT_SEC" -> "12",
      "STREAM_TUNE_TIMEOUT_SEC" -> "30",
      "STREAM_RETRY_DELAY_SEC" -> "2",
      "STREAM_RECOVERY_TIMEOUT_SEC" -> "90",
      "SESSION_IDLE_GRACE_SEC" -> "60",
      "STREAM_PRE_ROLL_INTERVAL_MS" -> "50",
      "STREAM_PRE_ROLL_PACKETS" -> "14"
    )).config
    val _ = config.stream.resilient.stallTimeoutSec shouldBe 12
    val _ = config.stream.resilient.tuneTimeoutSec shouldBe 30
    val _ = config.stream.resilient.retryDelaySec shouldBe 2
    val _ = config.stream.resilient.recoveryTimeoutSec shouldBe 90
    val _ = config.proxy.idleGraceSec shouldBe 60
    val _ = config.proxy.preRollIntervalMs shouldBe 50
    val _ = config.proxy.preRollPackets shouldBe 14

    val fallbackConfig = AppConfig.load(Map(
      "STREAM_STALL_TIMEOUT_SEC" -> "bad"
    )).config
    val _ = fallbackConfig.stream.resilient.stallTimeoutSec shouldBe 8
  }

  it should "parse bool env vars" in {
    val config = AppConfig.load(Map(
      "STREAM_PRE_ROLL_KEEP_ALIVE" -> "false"
    )).config
    config.proxy.enablePreRollKeepAlive shouldBe false
  }

  it should "resolve 4th-gen key aliases" in {
    val config = AppConfig.load(Map(
      "HashKey" -> "hash-alias",
      "DeviceKey" -> "device-alias"
    )).config
    val _ = config.tablo.hashKey shouldBe "hash-alias"
    config.tablo.deviceKey shouldBe "device-alias"
  }

  it should "use legacy tablo port by default" in {
    AppConfig.load(Map("TABLO_GEN" -> "legacy")).config.tablo.port shouldBe Port.DefaultTablo
  }

  it should "use fourth-gen tablo port by default" in {
    AppConfig.load(Map.empty).config.tablo.port shouldBe Port.DefaultTabloFourthGen
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
    val _ = loaded.config.mediaRoot shouldBe Some("/media")
    val _ = loaded.tabloAuth.credentials shouldBe Some(TabloCredentials("user@example.com", "secret"))
  }

  "AppConfig.loadFrom" should "read only requested keys from a getter" in {
    val env = Map("TABLO_GEN" -> "legacy", "UNUSED" -> "ignored")
    val loaded = AppConfig.loadFrom(env.get)
    loaded.config.tablo.gen shouldBe TabloGen.Legacy
  }

  it should "parse custom proxy configuration from env" in {
    val config = AppConfig.load(Map(
      "PROXY_IP" -> "192.168.1.50",
      "PROXY_PORT" -> "9090",
      "DEVICE_ID" -> "AABBCCDD",
      "TUNER_COUNT" -> "4",
      "ENABLE_UDP_DISCOVERY" -> "false",
      "ENABLE_HLS_ENDPOINT" -> "false"
    )).config
    val _ = config.proxy.bindHost shouldBe "192.168.1.50"
    val _ = config.proxy.port.value shouldBe 9090
    val _ = config.proxy.deviceId shouldBe "AABBCCDD"
    val _ = config.proxy.tunerCount shouldBe Some(4)
    val _ = config.proxy.effectiveTunerCount shouldBe 4
    val _ = config.proxy.enableUdpDiscovery shouldBe false
    config.proxy.enableHlsEndpoint shouldBe false
  }
}

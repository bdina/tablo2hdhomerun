package app.config

import app.stream.MpegTsHealth

import ConfigTypes.{HostAddress, HttpProtocol, Port}

import EnvReaders._

final case class TabloCredentials(
  email: String
, password: String
)

final case class TabloAuthEnv(
  email: Option[String]
, password: Option[String]
) {
  def credentials: Option[TabloCredentials] =
    for {
      email <- email
      password <- password
    } yield TabloCredentials(email, password)
}

final case class TabloConfig(
  ip: HostAddress
, protocol: HttpProtocol = HttpProtocol.Http
, port: Port = Port.DefaultTablo
, gen: TabloGen
, deviceName: Option[String]
, hashKey: String
, deviceKey: String
) {
  def ipHost: String = ip.hostString

  def deviceBaseUri: String = s"${protocol.value}://${ip.hostString}:${port.value}"
}

final case class ProxyConfig(
  ip: HostAddress
, port: Port = Port.DefaultProxy
) {
  def bindHost: String = ip.hostString
  def bindPort: Int = port.value
}

final case class ResilientHlsConfig(
  maxGapSec: Int
, retryMinBackoffSec: Int
, retryMaxBackoffSec: Int
, recoveryTimeoutSec: Int
)

final case class HlsStreamConfig(
  stallPolls: Int
, heartbeatSec: Int
, health: MpegTsHealth.Settings
, pollFailuresMax: Int
)

final case class StreamConfig(
  backend: StreamBackendKind
, resilient: ResilientHlsConfig
, hls: HlsStreamConfig
)

final case class LoggingConfig(
  logLevel: Option[String]
, pekkoLogLevel: Option[String]
)

final case class AppConfig(
  tablo: TabloConfig
, proxy: ProxyConfig
, stream: StreamConfig
, mediaRoot: Option[String]
)

final case class Loaded(
  config: AppConfig
, tabloAuth: TabloAuthEnv
, logging: LoggingConfig
)

object AppConfig {
  private val DefaultHashKey = "6l8jU5N43cEilqItmT3U2M2PFM3qPziilXqau9ys"
  private val DefaultDeviceKey = "ljpg6ZkwShVv8aI12E2LP55Ep8vq1uYDPvX0DdTB"

  def load(): Loaded = loadFrom(sys.env.get)

  def load(env: Map[String, String]): Loaded = loadFrom(key => env.get(key))

  def loadFrom(get: EnvGet): Loaded = {
    val gen = getTabloGen(get)
    val defaultTabloPort = if (gen.isFourthGen) Port.DefaultTabloFourthGen.value else Port.DefaultTablo.value
    val tablo = TabloConfig(
      ip = getHostAddress(get, "TABLO_IP", "127.0.0.1")
    , port = Port(getInt(get, "TABLO_PORT", defaultTabloPort))
    , gen = gen
    , deviceName = get("TABLO_DEVICE_NAME")
    , hashKey = get("TABLO_HASH_KEY").orElse(get("HashKey")).getOrElse(DefaultHashKey)
    , deviceKey = get("TABLO_DEVICE_KEY").orElse(get("DeviceKey")).getOrElse(DefaultDeviceKey)
    )
    val proxy = ProxyConfig(
      ip = getHostAddress(get, "PROXY_IP", "127.0.0.1")
    )
    val resilient = ResilientHlsConfig(
      maxGapSec = getInt(get, "STREAM_MAX_GAP_SEC", 60)
    , retryMinBackoffSec = getInt(get, "STREAM_RETRY_MIN_BACKOFF_SEC", 2)
    , retryMaxBackoffSec = getInt(get, "STREAM_RETRY_MAX_BACKOFF_SEC", 30)
    , recoveryTimeoutSec = getInt(get, "STREAM_RECOVERY_TIMEOUT_SEC", 60)
    )
    val hls = HlsStreamConfig(
      stallPolls = getInt(get, "STREAM_HLS_STALL_POLLS", 3)
    , heartbeatSec = getInt(get, "STREAM_HLS_HEARTBEAT_SEC", 60)
    , health = MpegTsHealth.Settings(
        windowSec = getInt(get, "STREAM_HLS_HEALTH_WINDOW_SEC", 10)
      , ccMax = getInt(get, "STREAM_HLS_CC_ERROR_MAX", 30)
      , syncMax = getInt(get, "STREAM_HLS_SYNC_LOSS_MAX", 10)
      , nullRatioMax = getDouble(get, "STREAM_HLS_NULL_RATIO_MAX", 0.6)
      , enforce = getBoolTrue(get, "STREAM_HLS_HEALTH_ENFORCE", false)
      )
    , pollFailuresMax = getInt(get, "STREAM_HLS_POLL_FAILURES_MAX", 60)
    )
    val stream = StreamConfig(
      backend = getStreamBackend(get)
    , resilient = resilient
    , hls = hls
    )
    val logging = LoggingConfig(
      logLevel = get("LOG_LEVEL")
    , pekkoLogLevel = get("PEKKO_LOG_LEVEL").orElse(get("LOG_LEVEL"))
    )
    val tabloAuth = TabloAuthEnv(
      email = get("TABLO_EMAIL")
    , password = get("TABLO_PASSWORD")
    )
    Loaded(
      config = AppConfig(
        tablo = tablo
      , proxy = proxy
      , stream = stream
      , mediaRoot = get("MEDIA_ROOT")
      )
    , tabloAuth = tabloAuth
    , logging = logging
    )
  }
}
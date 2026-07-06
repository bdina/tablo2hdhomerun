package app.config

import ConfigTypes.HostAddress

import scala.util.Try

object EnvReaders {
  type EnvGet = String => Option[String]

  def getOrElse(get: EnvGet, key: String, default: String): String =
    get(key).getOrElse(default)

  def getInt(get: EnvGet, key: String, default: Int): Int =
    get(key).flatMap(s => Try(s.toInt).toOption).getOrElse(default)

  def getDouble(get: EnvGet, key: String, default: Double): Double =
    get(key).flatMap(s => Try(s.toDouble).toOption).getOrElse(default)

  def getBoolTrue(get: EnvGet, key: String, default: Boolean): Boolean =
    get(key).map(_ == "true").getOrElse(default)

  def getHostAddress(get: EnvGet, key: String, default: String): HostAddress =
    HostAddress.fromName(getOrElse(get, key, default))

  def getTabloGen(get: EnvGet): TabloGen =
    TabloGen.fromEnv(getOrElse(get, "TABLO_GEN", TabloGen.FourthGen.envValue))

  def getStreamBackend(get: EnvGet): StreamBackendKind =
    StreamBackendKind.fromEnv(getOrElse(get, "STREAM_BACKEND", StreamBackendKind.Hls.envValue))
}
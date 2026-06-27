package app.stream

import org.apache.pekko.stream.scaladsl.{RestartSource, Source}
import org.apache.pekko.stream.RestartSettings
import org.apache.pekko.util.ByteString

import org.slf4j.LoggerFactory

import scala.concurrent.duration._

object ResilientHlsSource {
  val log = LoggerFactory.getLogger(this.getClass)

  // Configuration (from environment variables)
  def maxGapSec: Int = app.Tablo2HDHomeRun.STREAM_MAX_GAP_SEC
  def retryDelaySec: Int = app.Tablo2HDHomeRun.STREAM_RETRY_DELAY_SEC
  val nullPacketIntervalMs: Int = 80

  // MPEG-TS null packet constant (188 bytes, sync byte 0x47, PID 0x1FFF)
  val MPEGTS_NULL_PACKET: ByteString = {
    val arr = Array.fill[Byte](188)(0xFF.toByte)
    arr(0) = 0x47.toByte
    arr(1) = 0x1F.toByte
    arr(2) = 0xFF.toByte
    arr(3) = 0x10.toByte // adaptation field control = payload only, continuity counter = 0
    ByteString(arr)
  }

  def apply(
    streamFactory: () => Source[ByteString, ?],
    streamName: String
  ): Source[ByteString, ?] = {

    val restartSettings = RestartSettings(
      minBackoff = retryDelaySec.seconds,
      maxBackoff = retryDelaySec.seconds,
      randomFactor = 0.0
    )

    RestartSource.withBackoff(restartSettings) { () =>
      log.info(s"[$streamName] attempting stream connection")
      streamFactory().idleTimeout(maxGapSec.seconds)
    }
    .keepAlive(nullPacketIntervalMs.millis, () => MPEGTS_NULL_PACKET)
  }
}

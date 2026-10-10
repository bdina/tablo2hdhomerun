package app.stream

import org.apache.pekko.NotUsed
import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.util.ByteString
import org.slf4j.LoggerFactory

import scala.concurrent.duration._

object SlateVideoSource {
  private val log = LoggerFactory.getLogger(getClass)
  private val PacketSize = 188
  private val ResourcePath = "/slate.ts"

  val slateBytes: ByteString = {
    val stream = getClass.getResourceAsStream(ResourcePath)
    if (stream != null) {
      try {
        val bytes = stream.readAllBytes()
        log.info(s"Loaded slate video asset ($ResourcePath): ${bytes.length} bytes")
        ByteString(bytes)
      } finally {
        stream.close()
      }
    } else {
      log.warn(s"Slate video asset not found on classpath: $ResourcePath; falling back to empty")
      ByteString.empty
    }
  }

  val packetCount: Int = slateBytes.length / PacketSize

  def source(chunkPackets: Int = 24, interval: FiniteDuration = 40.millis): Source[ByteString, NotUsed] =
    if (slateBytes.isEmpty) {
      Source.repeat(MpegTsSync.MPEGTS_NULL_PACKET).throttle(1, interval)
    } else {
      val chunks = createChunks(math.max(1, chunkPackets))
      Source.cycle(() => chunks.iterator).throttle(1, interval)
    }

  def createChunks(chunkPackets: Int): IndexedSeq[ByteString] = {
    val chunkSize = chunkPackets * PacketSize
    val totalValid = (slateBytes.length / PacketSize) * PacketSize
    if (totalValid <= 0) {
      IndexedSeq(MpegTsSync.MPEGTS_NULL_PACKET)
    } else {
      val validBytes = slateBytes.take(totalValid)
      val b = IndexedSeq.newBuilder[ByteString]
      var pos = 0
      while (pos < totalValid) {
        val next = math.min(pos + chunkSize, totalValid)
        b += validBytes.slice(pos, next)
        pos = next
      }
      b.result()
    }
  }

  final class Loop(chunkPackets: Int = 24) {
    private val chunks = createChunks(chunkPackets)
    private var idx = 0

    def nextChunk(): ByteString =
      if (chunks.isEmpty) MpegTsSync.MPEGTS_NULL_PACKET
      else {
        val wrapped = idx == 0
        val chunk = chunks(idx)
        idx = (idx + 1) % chunks.size
        if (wrapped) MpegTsSync.MPEGTS_DISCONTINUITY_PACKET ++ chunk else chunk
      }
  }
}

package app.stream

import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.stream.scaladsl.{Sink, Source}
import org.apache.pekko.util.ByteString
import org.junit.runner.RunWith
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatestplus.junit.JUnitRunner

import java.util.concurrent.atomic.AtomicReference

@RunWith(classOf[JUnitRunner])
class MpegTsSyncSpec extends ScalaTestWithActorTestKit with AnyWordSpecLike with Matchers {

  private def buildPatPacket(pmtPid: Int): ByteString = {
    val arr = Array.fill[Byte](188)(0xFF.toByte)
    arr(0) = 0x47.toByte
    arr(1) = 0x40.toByte // PUSI = 1, PID = 0
    arr(2) = 0x00.toByte
    arr(3) = 0x10.toByte // payload only, CC = 0
    arr(4) = 0x00.toByte // pointer field = 0
    // Table section starts at offset 5
    arr(5) = 0x00.toByte // table_id = 0x00 (PAT)
    arr(6) = 0xB0.toByte // section syntax indicator
    arr(7) = 0x0D.toByte // section_length = 13 (5 bytes header + 4 bytes entry + 4 bytes CRC)
    arr(8) = 0x00.toByte // transport_stream_id
    arr(9) = 0x01.toByte
    arr(10) = 0xC1.toByte // version / current_next
    arr(11) = 0x00.toByte // section_number
    arr(12) = 0x00.toByte // last_section_number
    // Program entry (4 bytes)
    arr(13) = 0x00.toByte // program_number = 1
    arr(14) = 0x01.toByte
    arr(15) = (0xE0 | ((pmtPid >> 8) & 0x1F)).toByte
    arr(16) = (pmtPid & 0xFF).toByte
    // CRC (4 bytes)
    arr(17) = 0x00.toByte
    arr(18) = 0x00.toByte
    arr(19) = 0x00.toByte
    arr(20) = 0x00.toByte
    ByteString(arr)
  }

  private def buildPmtPacket(pmtPid: Int): ByteString = {
    val arr = Array.fill[Byte](188)(0xFF.toByte)
    arr(0) = 0x47.toByte
    arr(1) = (0x40 | ((pmtPid >> 8) & 0x1F)).toByte // PUSI = 1
    arr(2) = (pmtPid & 0xFF).toByte
    arr(3) = 0x10.toByte // payload only, CC = 0
    arr(4) = 0x00.toByte // pointer field
    arr(5) = 0x02.toByte // table_id = 0x02 (PMT)
    ByteString(arr)
  }

  private def buildMediaPacket(pid: Int): ByteString = {
    val arr = Array.fill[Byte](188)(0xAA.toByte)
    arr(0) = 0x47.toByte
    arr(1) = ((pid >> 8) & 0x1F).toByte
    arr(2) = (pid & 0xFF).toByte
    arr(3) = 0x10.toByte
    ByteString(arr)
  }

  "MpegTsSync" should {

    "construct a valid 188-byte MPEG-TS discontinuity packet" in {
      val packet = MpegTsSync.MPEGTS_DISCONTINUITY_PACKET
      val _ = packet.length shouldBe 188
      val _ = packet(0) shouldBe 0x47.toByte
      val _ = packet(1) shouldBe 0x1F.toByte
      val _ = packet(2) shouldBe 0xFF.toByte
      val _ = packet(3) shouldBe 0x20.toByte // Adaptation field only (0x20)
      val _ = packet(4) shouldBe 183.toByte  // Adaptation field length
      val _ = (packet(5) & 0x80) should not be 0 // discontinuity_indicator = 1
      val _ = packet(6) shouldBe 0xFF.toByte
    }

    "extract PMT PID from a valid PAT packet" in {
      val pat = buildPatPacket(0x0150).toArray
      val pmtPid = MpegTsSync.extractPmtPid(pat, 0)
      val _ = pmtPid shouldBe Some(0x0150)
    }

    "cache PAT and PMT packets from stream" in {
      val cachedHeadersRef = new AtomicReference[MpegTsSync.CachedHeaders](MpegTsSync.CachedHeaders())
      val pat = buildPatPacket(0x0100)
      val pmt = buildPmtPacket(0x0100)
      val media = buildMediaPacket(0x0101)

      val stream = Source(List(pat, pmt, media))
        .via(MpegTsSync.cacheFlow(cachedHeadersRef))
        .runWith(Sink.seq)
        .futureValue

      val _ = stream.length shouldBe 3
      val cached = cachedHeadersRef.get()
      val _ = cached.pat shouldBe Some(pat)
      val _ = cached.pmt shouldBe Some(pmt)
    }

    "reassemble split packets and update cache" in {
      val cachedHeadersRef = new AtomicReference[MpegTsSync.CachedHeaders](MpegTsSync.CachedHeaders())
      val pat = buildPatPacket(0x0100)
      val chunk1 = pat.take(90)
      val chunk2 = pat.drop(90)

      val stream = Source(List(chunk1, chunk2))
        .via(MpegTsSync.cacheFlow(cachedHeadersRef))
        .runWith(Sink.fold(ByteString.empty)(_ ++ _))
        .futureValue

      val _ = stream.length shouldBe 188
      val cached = cachedHeadersRef.get()
      val _ = cached.pat shouldBe Some(pat)
    }

    "prime client source with cached PAT, PMT, and discontinuity packet" in {
      val pat = buildPatPacket(0x0100)
      val pmt = buildPmtPacket(0x0100)
      val cached = MpegTsSync.CachedHeaders(Some(pat), Some(pmt))
      val cachedRef = new AtomicReference(cached)

      val media = buildMediaPacket(0x0101)
      val hubSource = Source.single(media)

      val primed = MpegTsSync.primeClientSource(hubSource, cachedRef)
        .runWith(Sink.fold(ByteString.empty)(_ ++ _))
        .futureValue

      // Primed source should contain: PAT (188) + PMT (188) + Discontinuity (188) + Media (188) = 752 bytes
      val _ = primed.length shouldBe (188 * 4)
      val _ = primed.take(188) shouldBe pat
      val _ = primed.slice(188, 376) shouldBe pmt
      val _ = primed.slice(376, 564) shouldBe MpegTsSync.MPEGTS_DISCONTINUITY_PACKET
      val _ = primed.drop(564) shouldBe media
    }

    "not prepend headers if cache is empty" in {
      val emptyRef = new AtomicReference(MpegTsSync.CachedHeaders())
      val media = buildMediaPacket(0x0101)
      val hubSource = Source.single(media)

      val primed = MpegTsSync.primeClientSource(hubSource, emptyRef)
        .runWith(Sink.fold(ByteString.empty)(_ ++ _))
        .futureValue

      val _ = primed shouldBe media
    }

    "emit only packet-aligned chunks starting with 0x47 when receiving arbitrary chunk sizes" in {
      val cachedHeadersRef = new AtomicReference[MpegTsSync.CachedHeaders](MpegTsSync.CachedHeaders())
      val pat = buildPatPacket(0x0100)
      val pmt = buildPmtPacket(0x0100)
      val media1 = buildMediaPacket(0x0101)
      val media2 = buildMediaPacket(0x0101)
      val allBytes = pat ++ pmt ++ media1 ++ media2

      val chunk1 = allBytes.slice(0, 100)
      val chunk2 = allBytes.slice(100, 300)
      val chunk3 = allBytes.slice(300, 550)
      val chunk4 = allBytes.slice(550, 752)

      val emittedChunks = Source(List(chunk1, chunk2, chunk3, chunk4))
        .via(MpegTsSync.cacheFlow(cachedHeadersRef))
        .runWith(Sink.seq)
        .futureValue

      emittedChunks.foreach { chunk =>
        val _ = (chunk.length % 188) shouldBe 0
        val _ = chunk(0) shouldBe 0x47.toByte
      }
      val totalBytes = emittedChunks.foldLeft(ByteString.empty)(_ ++ _)
      val _ = totalBytes shouldBe allBytes
    }

    "strip leading non-sync bytes before emitting" in {
      val cachedHeadersRef = new AtomicReference[MpegTsSync.CachedHeaders](MpegTsSync.CachedHeaders())
      val pat = buildPatPacket(0x0100)
      val junk = ByteString(Array[Byte](0x01, 0x02, 0x03, 0x04, 0x05))

      val stream = Source(List(junk ++ pat))
        .via(MpegTsSync.cacheFlow(cachedHeadersRef))
        .runWith(Sink.fold(ByteString.empty)(_ ++ _))
        .futureValue

      val _ = stream shouldBe pat
      val _ = stream.length shouldBe 188
      val _ = stream(0) shouldBe 0x47.toByte
    }
  }
}

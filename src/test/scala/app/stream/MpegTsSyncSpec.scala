package app.stream

import org.apache.pekko.NotUsed
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.stream.scaladsl.{Sink, Source}
import org.apache.pekko.util.ByteString
import org.junit.runner.RunWith
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatestplus.junit.JUnitRunner

import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.{Future, Promise}
import scala.concurrent.duration._

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

    "construct a valid 188-byte MPEG-TS null packet" in {
      val packet = MpegTsSync.MPEGTS_NULL_PACKET
      val _ = packet.length shouldBe 188
      val _ = packet(0) shouldBe 0x47.toByte
      val _ = packet(1) shouldBe 0x1F.toByte // PID high 5 bits (0x1F)
      val _ = packet(2) shouldBe 0xFF.toByte // PID low 8 bits (0xFF) -> PID = 0x1FFF
      val _ = packet(3) shouldBe 0x10.toByte // payload only (0x10), CC = 0
      val _ = packet.drop(4).forall(_ == 0xFF.toByte) shouldBe true
    }

    "generate preRollNullChunk with correct packet count and size" in {
      val chunk = MpegTsSync.preRollNullChunk(7)
      val _ = chunk.length shouldBe (188 * 7)
      (0 until 7).foreach { i =>
        val _ = chunk(i * 188) shouldBe 0x47.toByte
        val _ = chunk(i * 188 + 1) shouldBe 0x1F.toByte
        val _ = chunk(i * 188 + 2) shouldBe 0xFF.toByte
      }
    }

    "not throw ArrayIndexOutOfBoundsException when non-sync bytes occur near end of buffer" in {
      val cachedHeadersRef = new AtomicReference[MpegTsSync.CachedHeaders](MpegTsSync.CachedHeaders())
      val pat = buildPatPacket(0x0100)
      // Build a chunk of 34,780 bytes (matching the exact crash size from production logs: 185 packets = 34,780 bytes)
      val numPackets = 185
      val fullLen = numPackets * 188
      val buf = Array.fill[Byte](fullLen)(0xAA.toByte)
      // Put valid PAT at the start
      System.arraycopy(pat.toArray, 0, buf, 0, 188)
      // Corrupt the packet boundary near the end and place a lone 0x47 at the very last byte (fullLen - 1)
      buf(fullLen - 1) = 0x47.toByte

      val emitted = Source.single(ByteString(buf))
        .via(MpegTsSync.cacheFlow(cachedHeadersRef))
        .runWith(Sink.seq)
        .futureValue

      val _ = emitted.nonEmpty shouldBe true
      cachedHeadersRef.get().pat shouldBe Some(pat)
    }

    "withPreRollKeepAlive should emit zero null packets when realSourceFuture is already completed" in {
      val media = buildMediaPacket(0x0101)
      val realSourceFut = Future.successful(Source.single(media))

      val stream = MpegTsSync.withPreRollKeepAlive(realSourceFut, interval = 50.millis, chunkPackets = 1)
        .runWith(Sink.seq)
        .futureValue

      // Should contain only the media packet, with 0 null packets
      val _ = stream.length shouldBe 1
      stream.head shouldBe media
    }

    "withPreRollKeepAlive should emit null packets while realSourceFuture is pending then switch to realSource" in {
      implicit val ec: scala.concurrent.ExecutionContext = system.executionContext
      val media = buildMediaPacket(0x0101)
      val promise = Promise[Source[ByteString, NotUsed]]()

      val compositeSource = MpegTsSync.withPreRollKeepAlive(promise.future, interval = 50.millis, chunkPackets = 1)
      val streamFut = compositeSource.runWith(Sink.seq)

      // Allow 2-3 ticks of null packets to emit, then complete the promise with real media
      system.classicSystem.scheduler.scheduleOnce(140.millis, new Runnable {
        override def run(): Unit = promise.success(Source.single(media))
      })

      val emitted = streamFut.futureValue
      // Emitted chunks should have at least 1 null packet chunk, and the last chunk should be media
      val _ = emitted.length should be >= 2
      val _ = emitted.last shouldBe media
      // All earlier chunks should be null packets
      emitted.init.foreach { chunk =>
        val _ = chunk.length shouldBe 188
        val _ = chunk(0) shouldBe 0x47.toByte
        val _ = chunk(1) shouldBe 0x1F.toByte
        val _ = chunk(2) shouldBe 0xFF.toByte
      }
    }

    "withPreRollKeepAlive should fail stream when realSourceFuture fails" in {
      implicit val ec: scala.concurrent.ExecutionContext = system.executionContext
      val expectedError = new RuntimeException("Tuner failed to lock")
      val promise = Promise[Source[ByteString, NotUsed]]()

      val compositeSource = MpegTsSync.withPreRollKeepAlive(promise.future, interval = 50.millis, chunkPackets = 1)
      val streamFut = compositeSource.runWith(Sink.seq)

      system.classicSystem.scheduler.scheduleOnce(80.millis, new Runnable {
        override def run(): Unit = promise.failure(expectedError)
      })

      val failure = streamFut.failed.futureValue
      failure.getMessage shouldBe "Tuner failed to lock"
    }
  }
}

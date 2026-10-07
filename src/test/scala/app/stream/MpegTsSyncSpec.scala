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

  private def buildFullPmtPacket(pmtPid: Int, pcrPid: Int, videoPid: Int): ByteString = {
    val arr = Array.fill[Byte](188)(0xFF.toByte)
    arr(0) = 0x47.toByte
    arr(1) = (0x40 | ((pmtPid >> 8) & 0x1F)).toByte // PUSI = 1
    arr(2) = (pmtPid & 0xFF).toByte
    arr(3) = 0x10.toByte // payload only, CC = 0
    arr(4) = 0x00.toByte // pointer field = 0
    // Table section starts at offset 5
    arr(5) = 0x02.toByte // table_id = 0x02 (PMT)
    // section_length = 9 (header) + 5 (video stream entry) + 4 (CRC) = 18 = 0x0012
    arr(6) = 0xB0.toByte
    arr(7) = 0x12.toByte
    arr(8) = 0x00.toByte // program number
    arr(9) = 0x01.toByte
    arr(10) = 0xC1.toByte // version / current_next
    arr(11) = 0x00.toByte // section_number
    arr(12) = 0x00.toByte // last_section_number
    // PCR PID at offset 5 + 8 = 13, 14
    arr(13) = (0xE0 | ((pcrPid >> 8) & 0x1F)).toByte
    arr(14) = (pcrPid & 0xFF).toByte
    // Program info length at offset 5 + 10 = 15, 16 -> 0
    arr(15) = 0xF0.toByte
    arr(16) = 0x00.toByte
    // Stream entry starts at offset 5 + 12 = 17
    arr(17) = 0x02.toByte // stream_type = 0x02 (MPEG-2 Video)
    arr(18) = (0xE0 | ((videoPid >> 8) & 0x1F)).toByte
    arr(19) = (videoPid & 0xFF).toByte
    arr(20) = 0xF0.toByte // ES info length = 0
    arr(21) = 0x00.toByte
    // CRC (4 bytes at 22, 23, 24, 25)
    arr(22) = 0x00.toByte
    arr(23) = 0x00.toByte
    arr(24) = 0x00.toByte
    arr(25) = 0x00.toByte
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

      // Primed source should contain: Discontinuity (188) + PAT (188) + PMT (188) + Media (188) = 752 bytes
      val _ = primed.length shouldBe (188 * 4)
      val _ = primed.take(188) shouldBe MpegTsSync.MPEGTS_DISCONTINUITY_PACKET
      val _ = primed.slice(188, 376) shouldBe pat
      val _ = primed.slice(376, 564) shouldBe pmt
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

    "withPreRollKeepAlive should switch from pre-roll null chunks directly to the real source" in {
      implicit val ec: scala.concurrent.ExecutionContext = system.executionContext
      val media = buildMediaPacket(0x0101)
      val promise = Promise[Source[ByteString, NotUsed]]()

      val compositeSource = MpegTsSync.withPreRollKeepAlive(promise.future, interval = 20.millis, chunkPackets = 1)
      val streamFut = compositeSource.runWith(Sink.seq)

      val _ = system.classicSystem.scheduler.scheduleOnce(50.millis, new Runnable {
        override def run(): Unit = promise.success(Source.single(media))
      })

      val emitted = streamFut.futureValue
      val _ = emitted.length should be >= 2
      val _ = emitted.last shouldBe media
      val earlier = emitted.init
      earlier.foreach { chunk =>
        val _ = chunk shouldBe MpegTsSync.MPEGTS_NULL_PACKET
      }
    }

    "alignPackets should emit only whole 188-byte packets and maintain alignment across chunk splits" in {
      val packet1 = buildMediaPacket(0x0101)
      val packet2 = buildMediaPacket(0x0102)
      val packet3 = buildMediaPacket(0x0103)
      val allThree = packet1 ++ packet2 ++ packet3

      val c1 = allThree.take(100)
      val c2 = allThree.slice(100, 400)
      val c3 = allThree.drop(400)

      val result = Source(List(c1, c2, c3))
        .via(MpegTsSync.alignPackets)
        .runWith(Sink.seq)
        .futureValue

      val combined = result.foldLeft(ByteString.empty)(_ ++ _)
      val _ = combined.length shouldBe (188 * 3)
      val _ = combined shouldBe allThree
      result.foreach { chunk =>
        val _ = (chunk.length % 188) shouldBe 0
      }
    }

    "alignPackets should drop trailing partial packets on completion" in {
      val packet = buildMediaPacket(0x0101)
      val trailing = ByteString(Array.fill[Byte](50)(0xAA.toByte))

      val result = Source(List(packet ++ trailing))
        .via(MpegTsSync.alignPackets)
        .runWith(Sink.seq)
        .futureValue

      val combined = result.foldLeft(ByteString.empty)(_ ++ _)
      combined shouldBe packet
    }

    "alignPackets should produce no elements for empty chunks" in {
      val result = Source(List(ByteString.empty, ByteString.empty))
        .via(MpegTsSync.alignPackets)
        .runWith(Sink.seq)
        .futureValue

      result shouldBe empty
    }

    "cacheFlow should invoke onHeadersUpdated callback when headers are detected" in {
      val cachedHeadersRef = new AtomicReference[MpegTsSync.CachedHeaders](MpegTsSync.CachedHeaders())
      val callbackHeadersRef = new AtomicReference[MpegTsSync.CachedHeaders](MpegTsSync.CachedHeaders())
      val pat = buildPatPacket(0x0100)
      val pmt = buildPmtPacket(0x0100)
      val media = buildMediaPacket(0x0101)

      val stream = Source(List(pat, pmt, media))
        .via(MpegTsSync.cacheFlow(cachedHeadersRef, updated => callbackHeadersRef.set(updated)))
        .runWith(Sink.seq)
        .futureValue

      val _ = stream.length shouldBe 3
      val cached = cachedHeadersRef.get()
      val fromCallback = callbackHeadersRef.get()
      val _ = cached.pat shouldBe Some(pat)
      val _ = cached.pmt shouldBe Some(pmt)
      val _ = fromCallback.pat shouldBe Some(pat)
      fromCallback.pmt shouldBe Some(pmt)
    }

    "withPreRollKeepAlive should fail stream when realSourceFuture fails" in {
      implicit val ec: scala.concurrent.ExecutionContext = system.executionContext
      val expectedError = new RuntimeException("Tuner failed to lock")
      val promise = Promise[Source[ByteString, NotUsed]]()

      val compositeSource = MpegTsSync.withPreRollKeepAlive(promise.future, interval = 50.millis, chunkPackets = 1)
      val streamFut = compositeSource.runWith(Sink.seq)

      val _ = system.classicSystem.scheduler.scheduleOnce(80.millis, new Runnable {
        override def run(): Unit = promise.failure(expectedError)
      })

      val failure = streamFut.failed.futureValue
      failure.getMessage shouldBe "Tuner failed to lock"
    }

    "drop non-sync bytes in the middle of stream and maintain strict 188-byte packet alignment" in {
      val cachedHeadersRef = new AtomicReference[MpegTsSync.CachedHeaders](MpegTsSync.CachedHeaders())
      val pat = buildPatPacket(0x0100)
      val pmt = buildPmtPacket(0x0100)
      val media = buildMediaPacket(0x0101)
      val junk1 = ByteString(Array[Byte](0x11, 0x22, 0x33, 0x44, 0x55))
      val junk2 = ByteString(Array[Byte](0x66.toByte, 0x77.toByte, 0x88.toByte))

      val combined = pat ++ junk1 ++ pmt ++ junk2 ++ media
      val stream = Source.single(combined)
        .via(MpegTsSync.cacheFlow(cachedHeadersRef))
        .runWith(Sink.seq)
        .futureValue

      val total = stream.foldLeft(ByteString.empty)(_ ++ _)
      val _ = total.length shouldBe (188 * 3)
      (0 until 3).foreach { i =>
        val _ = total(i * 188) shouldBe 0x47.toByte
      }
      val _ = total.slice(0, 188) shouldBe pat
      val _ = total.slice(188, 376) shouldBe pmt
      total.slice(376, 564) shouldBe media
    }

    "replace TEI-corrupted packets with MPEG-TS null packets in cacheFlow" in {
      val cachedHeadersRef = new AtomicReference[MpegTsSync.CachedHeaders](MpegTsSync.CachedHeaders())
      val tei = {
        val arr = Array.fill[Byte](188)(0x55.toByte)
        arr(0) = 0x47.toByte
        arr(1) = 0x81.toByte // TEI = 1
        arr(2) = 0x00.toByte
        arr(3) = 0x10.toByte
        ByteString(arr)
      }
      val media = buildMediaPacket(0x0101)

      val stream = Source.single(tei ++ media)
        .via(MpegTsSync.cacheFlow(cachedHeadersRef))
        .runWith(Sink.fold(ByteString.empty)(_ ++ _))
        .futureValue

      val _ = stream.length shouldBe (188 * 2)
      val _ = stream.slice(0, 188) shouldBe MpegTsSync.MPEGTS_NULL_PACKET
      stream.slice(188, 376) shouldBe media
    }


    "validate packet headers correctly in isValidPacketHeader" in {
      val validPacket = buildMediaPacket(0x0101).toArray
      val _ = MpegTsSync.isValidPacketHeader(validPacket, 0, validPacket.length) shouldBe true

      // Invalid sync byte
      val badSync = validPacket.clone()
      badSync(0) = 0x00.toByte
      val _ = MpegTsSync.isValidPacketHeader(badSync, 0, badSync.length) shouldBe false

      // Invalid afc = 0
      val badAfc = validPacket.clone()
      badAfc(3) = (badAfc(3) & 0xCF).toByte // clears bits 4 and 5
      val _ = MpegTsSync.isValidPacketHeader(badAfc, 0, badAfc.length) shouldBe false

      // Out of bounds / short buffer
      val _ = MpegTsSync.isValidPacketHeader(validPacket, 0, 100) shouldBe false
      val _ = MpegTsSync.isValidPacketHeader(validPacket, -1, validPacket.length) shouldBe false

      // afc = 2 (adaptation field only) with valid length
      val afc2Valid = validPacket.clone()
      afc2Valid(3) = ((afc2Valid(3) & 0xCF) | 0x20).toByte
      afc2Valid(4) = 183.toByte
      val _ = MpegTsSync.isValidPacketHeader(afc2Valid, 0, afc2Valid.length) shouldBe true

      // afc = 2 with invalid length (> 183)
      val afc2Invalid = validPacket.clone()
      afc2Invalid(3) = ((afc2Invalid(3) & 0xCF) | 0x20).toByte
      afc2Invalid(4) = 184.toByte
      val _ = MpegTsSync.isValidPacketHeader(afc2Invalid, 0, afc2Invalid.length) shouldBe false

      // afc = 3 (adaptation field + payload) with valid length
      val afc3Valid = validPacket.clone()
      afc3Valid(3) = ((afc3Valid(3) & 0xCF) | 0x30).toByte
      afc3Valid(4) = 182.toByte
      val _ = MpegTsSync.isValidPacketHeader(afc3Valid, 0, afc3Valid.length) shouldBe true

      // afc = 3 with invalid length (> 182)
      val afc3Invalid = validPacket.clone()
      afc3Invalid(3) = ((afc3Invalid(3) & 0xCF) | 0x30).toByte
      afc3Invalid(4) = 183.toByte
      val _ = MpegTsSync.isValidPacketHeader(afc3Invalid, 0, afc3Invalid.length) shouldBe false

      // Scrambled packets (TSC != 0) should be rejected
      val scrambled = validPacket.clone()
      scrambled(3) = (scrambled(3) | 0x80).toByte
      val _ = MpegTsSync.isValidPacketHeader(scrambled, 0, scrambled.length) shouldBe false

      // Reserved PIDs (0x0002 to 0x000F) should be rejected
      val reservedPid = validPacket.clone()
      reservedPid(1) = 0x00.toByte
      reservedPid(2) = 0x05.toByte
      MpegTsSync.isValidPacketHeader(reservedPid, 0, reservedPid.length) shouldBe false
    }

    "find next sync position accurately in findNextSync" in {
      val packet = buildMediaPacket(0x0101).toArray
      val junk = Array[Byte](0x01, 0x02, 0x03, 0x04)
      val combined = junk ++ packet

      val _ = MpegTsSync.findNextSync(combined, 0, combined.length) shouldBe 4
      MpegTsSync.findNextSync(junk, 0, junk.length) shouldBe -1
    }

    "reject false sync in payload when multi-packet consecutive headers are present in findNextSync" in {
      val p1 = buildMediaPacket(0x0101).toArray
      val p2 = buildMediaPacket(0x0101).toArray
      val junkPrefix = Array.fill[Byte](50)(0xAA.toByte)
      // Inject a false sync byte 0x47 with valid afc (payload only) inside junkPrefix at offset 20
      junkPrefix(20) = 0x47.toByte
      junkPrefix(21) = 0x01.toByte // PID 0x0100
      junkPrefix(22) = 0x00.toByte
      junkPrefix(23) = 0x10.toByte // afc = 1 (payload only), TSC = 0, CC = 0

      val combined = junkPrefix ++ p1 ++ p2
      // junkPrefix is 50 bytes, so real packet p1 starts at offset 50, followed by p2 at 50 + 188 = 238
      // Offset 20 has a valid single header, but offset 20 + 188 = 208 is inside p1 and NOT a valid header
      // findNextSync should reject offset 20 and lock onto offset 50
      val found = MpegTsSync.findNextSync(combined, 0, combined.length)
      val _ = found shouldBe 50

      // If we scan starting at offset 21, it should also find offset 50
      MpegTsSync.findNextSync(combined, 21, combined.length) shouldBe 50
    }

    "construct a valid 188-byte discontinuity packet for a custom PID" in {
      val packet = MpegTsSync.discontinuityPacket(0x0105)
      val _ = packet.length shouldBe 188
      val _ = packet(0) shouldBe 0x47.toByte
      val pid = ((packet(1) & 0x1F) << 8) | (packet(2) & 0xFF)
      val _ = pid shouldBe 0x0105
      val _ = packet(3) shouldBe 0x20.toByte
      val _ = packet(4) shouldBe 183.toByte
      val _ = (packet(5) & 0x80) should not be 0
    }

    "construct a valid 188-byte discontinuity packet for a custom PID with custom CC" in {
      val packet = MpegTsSync.discontinuityPacket(0x0105, 11)
      val _ = packet.length shouldBe 188
      val _ = packet(0) shouldBe 0x47.toByte
      val pid = ((packet(1) & 0x1F) << 8) | (packet(2) & 0xFF)
      val _ = pid shouldBe 0x0105
      val _ = packet(3) shouldBe 0x2B.toByte
      val _ = packet(4) shouldBe 183.toByte
      val _ = (packet(5) & 0x80) should not be 0
    }

    "extract PCR PID and Video PID from PMT packet in extractPmtStreamPids" in {
      val pmt = buildFullPmtPacket(pmtPid = 0x0100, pcrPid = 0x0100, videoPid = 0x0101).toArray
      val pids = MpegTsSync.extractPmtStreamPids(pmt, 0)
      val _ = pids should not be None
      val _ = pids.get.pcrPid shouldBe Some(0x0100)
      pids.get.videoPid shouldBe Some(0x0101)
    }

    "include video and PCR discontinuity packets in CachedHeaders.syncPrefix when available" in {
      val pat = buildPatPacket(0x0100)
      val pmt = buildFullPmtPacket(pmtPid = 0x0100, pcrPid = 0x0100, videoPid = 0x0101)
      val cached = MpegTsSync.CachedHeaders(
        pat = Some(pat)
      , pmt = Some(pmt)
      , pcrPid = Some(0x0100)
      , videoPid = Some(0x0101)
      )

      val prefix = cached.syncPrefix
      // Should have: Video Disc (188) + Null Disc (188) + PAT (188) + PMT (188) = 752 bytes (since PCR PID == Video PID is false, Video + PCR + Null = 5 * 188 = 940 bytes)
      val _ = prefix.length shouldBe (188 * 5)
      val videoDisc = prefix.take(188)
      val _ = (((videoDisc(1) & 0x1F) << 8) | (videoDisc(2) & 0xFF)) shouldBe 0x0101
      val _ = (videoDisc(5) & 0x80) should not be 0

      val pcrDisc = prefix.slice(188, 376)
      val _ = (((pcrDisc(1) & 0x1F) << 8) | (pcrDisc(2) & 0xFF)) shouldBe 0x0100
      val _ = (pcrDisc(5) & 0x80) should not be 0

      val nullDisc = prefix.slice(376, 564)
      val _ = nullDisc shouldBe MpegTsSync.MPEGTS_DISCONTINUITY_PACKET

      val _ = prefix.slice(564, 752) shouldBe pat
      prefix.slice(752, 940) shouldBe pmt
    }


    "cacheFlow should populate pcrPid and videoPid from full PMT packet" in {
      val cachedHeadersRef = new AtomicReference[MpegTsSync.CachedHeaders](MpegTsSync.CachedHeaders())
      val pat = buildPatPacket(0x0100)
      val pmt = buildFullPmtPacket(pmtPid = 0x0100, pcrPid = 0x0100, videoPid = 0x0101)
      val media = buildMediaPacket(0x0101)

      val _ = Source(List(pat, pmt, media))
        .via(MpegTsSync.cacheFlow(cachedHeadersRef))
        .runWith(Sink.seq)
        .futureValue

      val cached = cachedHeadersRef.get()
      val _ = cached.pat shouldBe Some(pat)
      val _ = cached.pmt shouldBe Some(pmt)
      val _ = cached.pcrPid shouldBe Some(0x0100)
      cached.videoPid shouldBe Some(0x0101)
    }
  }
}

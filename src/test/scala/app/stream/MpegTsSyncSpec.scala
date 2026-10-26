package app.stream

import org.apache.pekko.NotUsed
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.stream.OverflowStrategy
import org.apache.pekko.stream.scaladsl.{Sink, Source}
import org.apache.pekko.util.ByteString
import org.junit.runner.RunWith
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatestplus.junit.JUnitRunner

import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.{Future, Promise}
import scala.concurrent.duration._

object MpegTsSyncSpec {
  def buildPatPacket(pmtPid: Int): ByteString = {
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

  def buildPmtPacket(pmtPid: Int): ByteString = {
    val arr = Array.fill[Byte](188)(0xFF.toByte)
    arr(0) = 0x47.toByte
    arr(1) = (0x40 | ((pmtPid >> 8) & 0x1F)).toByte // PUSI = 1
    arr(2) = (pmtPid & 0xFF).toByte
    arr(3) = 0x10.toByte // payload only, CC = 0
    arr(4) = 0x00.toByte // pointer field
    arr(5) = 0x02.toByte // table_id = 0x02 (PMT)
    ByteString(arr)
  }

  def buildFullPmtPacket(pmtPid: Int, pcrPid: Int, videoPid: Int): ByteString = {
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

  def buildFullPmtPacketWithAudio(
    pmtPid: Int
  , pcrPid: Int
  , videoPid: Int
  , videoStreamType: Int = 0x02
  , audioPid: Int = 0x0102
  , audioStreamType: Int = 0x81
  ): ByteString = {
    val arr = Array.fill[Byte](188)(0xFF.toByte)
    arr(0) = 0x47.toByte
    arr(1) = (0x40 | ((pmtPid >> 8) & 0x1F)).toByte // PUSI = 1
    arr(2) = (pmtPid & 0xFF).toByte
    arr(3) = 0x10.toByte // payload only, CC = 0
    arr(4) = 0x00.toByte // pointer field = 0
    // Table section starts at offset 5
    arr(5) = 0x02.toByte // table_id = 0x02 (PMT)
    // section_length = 9 (header) + 5 (video entry) + 5 (audio entry) + 4 (CRC) = 23 = 0x0017
    arr(6) = 0xB0.toByte
    arr(7) = 0x17.toByte
    arr(8) = 0x00.toByte // program number
    arr(9) = 0x01.toByte
    arr(10) = 0xC1.toByte // version / current_next
    arr(11) = 0x00.toByte // section_number
    arr(12) = 0x00.toByte // last_section_number
    // PCR PID at offset 13, 14
    arr(13) = (0xE0 | ((pcrPid >> 8) & 0x1F)).toByte
    arr(14) = (pcrPid & 0xFF).toByte
    // Program info length -> 0
    arr(15) = 0xF0.toByte
    arr(16) = 0x00.toByte
    // Video stream entry at offset 17
    arr(17) = (videoStreamType & 0xFF).toByte
    arr(18) = (0xE0 | ((videoPid >> 8) & 0x1F)).toByte
    arr(19) = (videoPid & 0xFF).toByte
    arr(20) = 0xF0.toByte // ES info length = 0
    arr(21) = 0x00.toByte
    // Audio stream entry at offset 22
    arr(22) = (audioStreamType & 0xFF).toByte
    arr(23) = (0xE0 | ((audioPid >> 8) & 0x1F)).toByte
    arr(24) = (audioPid & 0xFF).toByte
    arr(25) = 0xF0.toByte // ES info length = 0
    arr(26) = 0x00.toByte
    // CRC (4 bytes at 27, 28, 29, 30)
    arr(27) = 0x00.toByte
    arr(28) = 0x00.toByte
    arr(29) = 0x00.toByte
    arr(30) = 0x00.toByte
    ByteString(arr)
  }

  def buildMediaPacket(pid: Int, cc: Int = 0): ByteString = {
    val arr = Array.fill[Byte](188)(0xAA.toByte)
    arr(0) = 0x47.toByte
    arr(1) = ((pid >> 8) & 0x1F).toByte
    arr(2) = (pid & 0xFF).toByte
    arr(3) = (0x10 | (cc & 0x0F)).toByte
    ByteString(arr)
  }

  def buildKeyframePacket(pid: Int, isH264: Boolean = false, isGop: Boolean = false): ByteString = {
    val arr = Array.fill[Byte](188)(0xAA.toByte)
    arr(0) = 0x47.toByte
    arr(1) = ((pid >> 8) & 0x1F).toByte
    arr(2) = (pid & 0xFF).toByte
    arr(3) = 0x10.toByte // payload only
    arr(4) = 0x00.toByte
    arr(5) = 0x00.toByte
    arr(6) = 0x01.toByte
    if (isH264) {
      arr(7) = 0x05.toByte // NAL unit type 5 (IDR slice)
    } else if (isGop) {
      arr(7) = 0xB8.toByte // MPEG-2 GOP Header (0xB8)
    } else {
      arr(7) = 0xB3.toByte // MPEG-2 Sequence Header (0xB3)
      arr(8) = 0x78.toByte // 1920 horizontal size
      arr(9) = 0x04.toByte // 1080 vertical size
      arr(10) = 0x38.toByte
      arr(11) = 0x12.toByte
      arr(12) = 0x34.toByte
      arr(13) = 0x56.toByte
      arr(14) = 0x78.toByte
      arr(15) = 0x9A.toByte
    }
    ByteString(arr)
  }

  def buildVideoPesPacket(
    pid: Int
  , pts: Long
  , dtsOpt: Option[Long] = None
  , pusi: Boolean = true
  , cc: Int = 0
  , isKeyframe: Boolean = false
  , sliceRow: Option[Int] = None
  ): ByteString = {
    val arr = Array.fill[Byte](188)(0xFF.toByte)
    arr(0) = 0x47.toByte
    val pusiBit = if (pusi) 0x40 else 0x00
    arr(1) = (pusiBit | ((pid >> 8) & 0x1F)).toByte
    arr(2) = (pid & 0xFF).toByte
    arr(3) = (0x10 | (cc & 0x0F)).toByte
    if (pusi) {
      arr(4) = 0x00.toByte
      arr(5) = 0x00.toByte
      arr(6) = 0x01.toByte
      arr(7) = 0xE0.toByte // video stream ID
      arr(8) = 0x00.toByte
      arr(9) = 0x00.toByte
      arr(10) = 0x80.toByte
      val ptsDtsFlags = if (dtsOpt.isDefined) 3 else 2
      arr(11) = ((ptsDtsFlags & 0x03) << 6).toByte
      val headerDataLen = if (dtsOpt.isDefined) 10 else 5
      arr(12) = headerDataLen.toByte
      FreezeFramePacer.writePtsDts(arr, 4, 188, pts, dtsOpt)
      val esOffset = 4 + 9 + headerDataLen
      if (isKeyframe) {
        arr(esOffset) = 0x00.toByte
        arr(esOffset + 1) = 0x00.toByte
        arr(esOffset + 2) = 0x01.toByte
        arr(esOffset + 3) = 0xB3.toByte
        arr(esOffset + 4) = 0x78.toByte
        arr(esOffset + 5) = 0x04.toByte
        arr(esOffset + 6) = 0x38.toByte
        arr(esOffset + 7) = 0x12.toByte
        arr(esOffset + 8) = 0x34.toByte
        arr(esOffset + 9) = 0x56.toByte
        arr(esOffset + 10) = 0x78.toByte
        arr(esOffset + 11) = 0x9A.toByte
      } else if (sliceRow.isDefined) {
        val row = sliceRow.get
        arr(esOffset) = 0x00.toByte
        arr(esOffset + 1) = 0x00.toByte
        arr(esOffset + 2) = 0x01.toByte
        arr(esOffset + 3) = (row & 0xFF).toByte
        arr(esOffset + 4) = 0x38.toByte
        arr(esOffset + 5) = 0x00.toByte
      }
    } else {
      if (sliceRow.isDefined) {
        val row = sliceRow.get
        arr(4) = 0x00.toByte
        arr(5) = 0x00.toByte
        arr(6) = 0x01.toByte
        arr(7) = (row & 0xFF).toByte
        arr(8) = 0x38.toByte
        arr(9) = 0x00.toByte
      }
    }
    ByteString(arr)
  }

  def buildAudioPesPacket(
    pid: Int
  , pts: Long
  , cc: Int = 0
  , withValidSyncword: Boolean = true
  ): ByteString = {
    val arr = Array.fill[Byte](188)(0xFF.toByte)
    arr(0) = 0x47.toByte
    arr(1) = (0x40 | ((pid >> 8) & 0x1F)).toByte // PUSI = 1
    arr(2) = (pid & 0xFF).toByte
    arr(3) = (0x10 | (cc & 0x0F)).toByte
    // PES header at offset 4
    arr(4) = 0x00.toByte
    arr(5) = 0x00.toByte
    arr(6) = 0x01.toByte
    arr(7) = 0xBD.toByte // AC-3 audio stream ID
    arr(8) = 0x00.toByte
    arr(9) = 0xE0.toByte // PES length
    arr(10) = 0x80.toByte
    arr(11) = 0x80.toByte // PTS only
    arr(12) = 5.toByte   // header data length = 5
    FreezeFramePacer.encodePtsBytes(arr, 13, 2, pts)
    val es = 4 + 9 + 5 // offset 18
    if (withValidSyncword) {
      arr(es) = 0x0B.toByte
      arr(es + 1) = 0x77.toByte
      arr(es + 2) = 0x00.toByte
      arr(es + 3) = 0x00.toByte
      arr(es + 4) = 0x04.toByte // fscod=0 (48kHz), frmsizecod=4
      arr(es + 5) = 0x40.toByte // bsid=8, bsmod=0
      arr(es + 6) = 0x40.toByte // acmod=2 (stereo)
    } else {
      arr(es) = 0xAA.toByte
      arr(es + 1) = 0xAA.toByte
    }
    ByteString(arr)
  }

  def buildVideoPacketWithPcr(
    pid: Int
  , pcr: Long
  , cc: Int = 0
  , tei: Boolean = false
  ): ByteString = {
    val arr = Array.fill[Byte](188)(0xAA.toByte)
    arr(0) = 0x47.toByte
    val teiBit = if (tei) 0x80 else 0x00
    arr(1) = (teiBit | ((pid >> 8) & 0x1F)).toByte
    arr(2) = (pid & 0xFF).toByte
    arr(3) = (0x30 | (cc & 0x0F)).toByte // adaptation + payload
    arr(4) = 7.toByte // afLen = 7
    arr(5) = 0x10.toByte // PCR flag
    FreezeFramePacer.writePcrBase(arr, 0, pcr)
    ByteString(arr)
  }

  def withCc(packet: ByteString, cc: Int, pusi: Boolean = false): ByteString = {
    val arr = packet.toArray
    if (pusi) arr(1) = (arr(1) | 0x40).toByte
    arr(3) = ((arr(3) & 0xF0) | (cc & 0x0F)).toByte
    ByteString(arr)
  }
}

@RunWith(classOf[JUnitRunner])
class MpegTsSyncSpec extends ScalaTestWithActorTestKit with AnyWordSpecLike with Matchers {
  import MpegTsSyncSpec._

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
      packet(6) shouldBe 0xFF.toByte
    }

    "extract PMT PID from a valid PAT packet" in {
      val pat = buildPatPacket(0x0150).toArray
      val pmtPid = MpegTsSync.extractPmtPid(pat, 0)
      pmtPid shouldBe Some(0x0150)
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
      cached.pmt shouldBe Some(pmt)
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
      cached.pat shouldBe Some(pat)
    }

    "prime client source with cached PAT, PMT, and discontinuity packet" in {
      val pat = buildPatPacket(0x0100)
      val pmt = buildPmtPacket(0x0100)
      val cached = MpegTsSync.CachedHeaders(Some(pat), Some(pmt))
      val cachedRef = new AtomicReference(cached)

      val media = buildKeyframePacket(0x0101, isH264 = false)
      val hubSource = Source.single(media)

      val primed = MpegTsSync.primeClientSource(hubSource, cachedRef)
        .runWith(Sink.fold(ByteString.empty)(_ ++ _))
        .futureValue

      // Primed source should contain: Discontinuity (188) + PAT (188) + PMT (188) + Media (188) = 752 bytes
      val _ = primed.length shouldBe (188 * 4)
      val _ = primed.take(188) shouldBe MpegTsSync.MPEGTS_DISCONTINUITY_PACKET
      val _ = primed.slice(188, 376) shouldBe pat
      val _ = primed.slice(376, 564) shouldBe pmt
      primed.drop(564) shouldBe media
    }

    "not prepend headers if cache is empty" in {
      val emptyRef = new AtomicReference(MpegTsSync.CachedHeaders())
      val media = buildKeyframePacket(0x0101, isH264 = false)
      val hubSource = Source.single(media)

      val primed = MpegTsSync.primeClientSource(hubSource, emptyRef)
        .runWith(Sink.fold(ByteString.empty)(_ ++ _))
        .futureValue

      primed shouldBe media
    }

    "gate client attach on an I-frame when cache is empty and drop mid-GOP video packets" in {
      val videoPid = 0x0101
      val emptyRef = new AtomicReference(MpegTsSync.CachedHeaders())

      val midGopArr = buildMediaPacket(videoPid).toArray
      midGopArr(4) = 0x11.toByte
      val midGop = ByteString(midGopArr)

      val keyframe = buildKeyframePacket(videoPid, isH264 = false)

      val postKeyframeArr = buildMediaPacket(videoPid).toArray
      postKeyframeArr(4) = 0x22.toByte
      val postKeyframe = ByteString(postKeyframeArr)

      val hubSource = Source(List(midGop, keyframe, postKeyframe))

      val primed = MpegTsSync.primeClientSource(hubSource, emptyRef)
        .runWith(Sink.seq)
        .futureValue

      val allBytes = primed.foldLeft(ByteString.empty)(_ ++ _)
      val _ = allBytes.length shouldBe (188 * 2)
      val _ = allBytes.containsSlice(keyframe) shouldBe true
      val _ = allBytes.containsSlice(postKeyframe) shouldBe true
      allBytes.containsSlice(midGop) shouldBe false
    }

    "gate client attach on an I-frame, dropping mid-GOP video packets until sequence header arrives" in {
      val videoPid = 0x0101
      val pat = buildPatPacket(0x0100)
      val pmt = buildFullPmtPacket(pmtPid = 0x0100, pcrPid = 0x0100, videoPid = videoPid)
      val cached = MpegTsSync.CachedHeaders(Some(pat), Some(pmt), Some(0x0100), Some(videoPid))
      val cachedRef = new AtomicReference(cached)

      val midGop1Arr = buildMediaPacket(videoPid).toArray
      midGop1Arr(4) = 0x11.toByte
      val midGop1 = ByteString(midGop1Arr)

      val midGop2Arr = buildMediaPacket(videoPid).toArray
      midGop2Arr(4) = 0x22.toByte
      val midGop2 = ByteString(midGop2Arr)

      val keyframe = buildKeyframePacket(videoPid, isH264 = false)

      val postKeyframeArr = buildMediaPacket(videoPid).toArray
      postKeyframeArr(4) = 0x33.toByte
      val postKeyframe = ByteString(postKeyframeArr)
      val hubSource = Source(List(midGop1, midGop2, keyframe, postKeyframe))

      val primed = MpegTsSync.primeClientSource(hubSource, cachedRef)
        .runWith(Sink.seq)
        .futureValue

      val allBytes = primed.foldLeft(ByteString.empty)(_ ++ _)
      val syncPrefixLen = cached.syncPrefix.length
      val _ = allBytes.length shouldBe (syncPrefixLen + 188 * 2)
      val _ = allBytes.containsSlice(keyframe) shouldBe true
      val _ = allBytes.containsSlice(postKeyframe) shouldBe true
      val _ = allBytes.containsSlice(midGop1) shouldBe false
      val _ = allBytes.containsSlice(midGop2) shouldBe false

      // First video packet immediately following prefix must begin with sequence header
      val firstVideoPacket = allBytes.slice(syncPrefixLen, syncPrefixLen + 188)
      val _ = firstVideoPacket shouldBe keyframe
      firstVideoPacket.slice(4, 8) shouldBe ByteString(0x00, 0x00, 0x01, 0xB3.toByte)
    }

    "emit only packet-aligned chunks starting with 0x47 when receiving arbitrary chunk sizes" in {
      val cachedHeadersRef = new AtomicReference[MpegTsSync.CachedHeaders](MpegTsSync.CachedHeaders())
      val pat = buildPatPacket(0x0100)
      val pmt = buildPmtPacket(0x0100)
      val media1 = buildMediaPacket(0x0101, cc = 0)
      val media2 = buildMediaPacket(0x0101, cc = 1)
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
      totalBytes shouldBe allBytes
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
      stream(0) shouldBe 0x47.toByte
    }

    "construct a valid 188-byte MPEG-TS null packet" in {
      val packet = MpegTsSync.MPEGTS_NULL_PACKET
      val _ = packet.length shouldBe 188
      val _ = packet(0) shouldBe 0x47.toByte
      val _ = packet(1) shouldBe 0x1F.toByte // PID high 5 bits (0x1F)
      val _ = packet(2) shouldBe 0xFF.toByte // PID low 8 bits (0xFF) -> PID = 0x1FFF
      val _ = packet(3) shouldBe 0x10.toByte // payload only (0x10), CC = 0
      packet.drop(4).forall(_ == 0xFF.toByte) shouldBe true
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
      packet(5) shouldBe 0x80.toByte
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
      packet(5) shouldBe 0x80.toByte
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

    "hasKeyframeHeader should detect MPEG-2 sequence and GOP headers as well as H.264 IDR and SPS" in {
      val mpeg2Seq = Array[Byte](0x00, 0x00, 0x01, 0xB3.toByte, 0x10, 0x20)
      val _ = MpegTsSync.hasKeyframeHeader(mpeg2Seq, 0, mpeg2Seq.length) shouldBe true

      val mpeg2Gop = Array[Byte](0x00, 0x00, 0x01, 0xB8.toByte, 0x00, 0x08)
      val _ = MpegTsSync.hasKeyframeHeader(mpeg2Gop, 0, mpeg2Gop.length) shouldBe true

      val h264Idr = Array[Byte](0x00, 0x00, 0x01, 0x05, 0x88.toByte)
      val _ = MpegTsSync.hasKeyframeHeader(h264Idr, 0, h264Idr.length) shouldBe true

      val h264Sps = Array[Byte](0x00, 0x00, 0x01, 0x07, 0x4D.toByte)
      val _ = MpegTsSync.hasKeyframeHeader(h264Sps, 0, h264Sps.length) shouldBe true

      val nonKey = Array[Byte](0x00, 0x00, 0x01, 0x01, 0x99.toByte)
      MpegTsSync.hasKeyframeHeader(nonKey, 0, nonKey.length) shouldBe false
    }

    "alignToIFrame should drop video packets until MPEG-2 sequence header arrives" in {
      val videoPid = 0x0101
      val nonKey1 = buildMediaPacket(videoPid)
      val nonKey2 = buildMediaPacket(videoPid)
      val key = buildKeyframePacket(videoPid, isH264 = false, isGop = false)
      val postKey = buildMediaPacket(videoPid)

      val result = Source(List(nonKey1, nonKey2, key, postKey))
        .via(MpegTsSync.alignToIFrame(Some(videoPid)))
        .runWith(Sink.seq)
        .futureValue

      result shouldBe Seq(key, postKey)
    }

    "alignToIFrame should drop video packets until MPEG-2 GOP header arrives" in {
      val videoPid = 0x0101
      val nonKey = buildMediaPacket(videoPid)
      val key = buildKeyframePacket(videoPid, isH264 = false, isGop = true)
      val postKey = buildMediaPacket(videoPid)

      val result = Source(List(nonKey, key, postKey))
        .via(MpegTsSync.alignToIFrame(Some(videoPid)))
        .runWith(Sink.seq)
        .futureValue

      result shouldBe Seq(key, postKey)
    }

    "alignToIFrame should drop video packets until H.264 IDR slice arrives" in {
      val videoPid = 0x0102
      val nonKey = buildMediaPacket(videoPid)
      val key = buildKeyframePacket(videoPid, isH264 = true)
      val postKey = buildMediaPacket(videoPid)

      val result = Source(List(nonKey, key, postKey))
        .via(MpegTsSync.alignToIFrame(Some(videoPid)))
        .runWith(Sink.seq)
        .futureValue

      result shouldBe Seq(key, postKey)
    }

    "alignToIFrame should preserve PAT and PMT packets and detect videoPid dynamically" in {
      val pat = buildPatPacket(0x0100)
      val pmt = buildFullPmtPacket(pmtPid = 0x0100, pcrPid = 0x0100, videoPid = 0x0101)
      val nonKey = buildMediaPacket(0x0101)
      val key = buildKeyframePacket(0x0101, isH264 = false)

      val result = Source(List(pat, pmt, nonKey, key))
        .via(MpegTsSync.alignToIFrame(None))
        .runWith(Sink.seq)
        .futureValue

      result shouldBe Seq(pat, pmt, key)
    }

    "alignToIFrame should fail open when maxDroppedPackets is exceeded" in {
      val videoPid = 0x0101
      val nonKeyPackets = (1 to 4).map { i =>
        val arr = buildMediaPacket(videoPid).toArray
        arr(4) = i.toByte
        ByteString(arr)
      }.toList

      val result = Source(nonKeyPackets)
        .via(MpegTsSync.alignToIFrame(Some(videoPid), maxDroppedPackets = 2))
        .runWith(Sink.seq)
        .futureValue

      result shouldBe Seq(nonKeyPackets(2), nonKeyPackets(3))
    }

    "hasKeyframeHeader should reject MPEG-2 vertical slice codes when videoStreamType is MPEG-2" in {
      // Slice codes 0x05, 0x07, 0x25, 0x27 have (code & 0x1F) == 5 or 7, which collide with H.264 IDR/SPS
      val mpeg2Slice05 = Array[Byte](0x00, 0x00, 0x01, 0x05, 0x88.toByte)
      val mpeg2Slice25 = Array[Byte](0x00, 0x00, 0x01, 0x25, 0x88.toByte)
      val mpeg2Slice27 = Array[Byte](0x00, 0x00, 0x01, 0x27, 0x88.toByte)
      val mpeg2Slice85 = Array[Byte](0x00, 0x00, 0x01, 0x85.toByte, 0x88.toByte)

      val _ = MpegTsSync.hasKeyframeHeader(mpeg2Slice05, 0, mpeg2Slice05.length, Some(0x02)) shouldBe false
      val _ = MpegTsSync.hasKeyframeHeader(mpeg2Slice25, 0, mpeg2Slice25.length, Some(0x02)) shouldBe false
      val _ = MpegTsSync.hasKeyframeHeader(mpeg2Slice27, 0, mpeg2Slice27.length, Some(0x02)) shouldBe false
      // 0x85 has forbidden_zero_bit == 1, so it is rejected even when videoStreamType is None
      val _ = MpegTsSync.hasKeyframeHeader(mpeg2Slice85, 0, mpeg2Slice85.length, None) shouldBe false

      val mpeg2Seq = Array[Byte](0x00, 0x00, 0x01, 0xB3.toByte, 0x10, 0x20)
      MpegTsSync.hasKeyframeHeader(mpeg2Seq, 0, mpeg2Seq.length, Some(0x02)) shouldBe true
    }

    "extractPmtStreamPids and CachedHeaders.syncPrefix should include audio PIDs" in {
      val arr = buildFullPmtPacket(pmtPid = 0x0100, pcrPid = 0x0101, videoPid = 0x0101).toArray
      // Extend section_length by 5 bytes (from 18 = 0x12 to 23 = 0x17) to add an AC-3 (0x81) audio stream on PID 0x0102
      arr(7) = 0x17.toByte
      arr(22) = 0x81.toByte // stream_type = 0x81 (ATSC AC-3)
      arr(23) = 0xE1.toByte // PID 0x0102
      arr(24) = 0x02.toByte
      arr(25) = 0xF0.toByte // ES info length = 0
      arr(26) = 0x00.toByte
      val pmtWithAudio = ByteString(arr)

      val pids = MpegTsSync.extractPmtStreamPids(pmtWithAudio.toArray, 0)
      val _ = pids shouldBe defined
      val _ = pids.get.videoPid shouldBe Some(0x0101)
      val _ = pids.get.videoStreamType shouldBe Some(0x02)
      val _ = pids.get.audioPids shouldBe Seq(0x0102)
      val _ = pids.get.audioStreamTypes shouldBe Map(0x0102 -> 0x81)

      val pat = buildPatPacket(0x0100)
      val cached = MpegTsSync.CachedHeaders(
        pat = Some(pat)
      , pmt = Some(pmtWithAudio)
      , pcrPid = Some(0x0101)
      , videoPid = Some(0x0101)
      , videoStreamType = Some(0x02)
      , audioPids = Seq(0x0102)
      , audioStreamTypes = Map(0x0102 -> 0x81)
      )
      val prefix = cached.syncPrefix
      // Video Disc (0x0101) + Audio Disc (0x0102) + Null Disc (0x1FFF) + PAT + PMT = 5 * 188
      val _ = prefix.length shouldBe (188 * 5)
      val audioDisc = prefix.slice(188, 376)
      val _ = (((audioDisc(1) & 0x1F) << 8) | (audioDisc(2) & 0xFF)) shouldBe 0x0102
      (audioDisc(5) & 0x80) should not be 0
    }

    "alignToIFrame should gate post-alignment audio packets on PUSI=1 and ignore MPEG-2 slice codes" in {
      val videoPid = 0x0101
      val audioPid = 0x0102
      // MPEG-2 P-frame with slice code 0x25 (which would falsely match H.264 IDR if not codec-aware)
      val falseMpeg2Slice = {
        val a = buildMediaPacket(videoPid).toArray
        a(4) = 0x00.toByte
        a(5) = 0x00.toByte
        a(6) = 0x01.toByte
        a(7) = 0x25.toByte
        ByteString(a)
      }
      val realMpeg2Key = buildKeyframePacket(videoPid, isH264 = false, isGop = false)
      val midPesAudio = buildMediaPacket(audioPid) // PUSI = 0
      val pusiAudio = {
        val a = buildMediaPacket(audioPid).toArray
        a(1) = (a(1) | 0x40).toByte // PUSI = 1
        ByteString(a)
      }
      val nextMidPesAudio = buildMediaPacket(audioPid) // PUSI = 0 after audio is aligned

      val result = Source(List(falseMpeg2Slice, realMpeg2Key, midPesAudio, pusiAudio, nextMidPesAudio))
        .via(
          MpegTsSync.alignToIFrame(
            videoPid = Some(videoPid)
          , videoStreamType = Some(0x02)
          , audioPids = Seq(audioPid)
          )
        )
        .runWith(Sink.seq)
        .futureValue

      result shouldBe Seq(realMpeg2Key, pusiAudio, nextMidPesAudio)
    }

    "cacheFlow should replace TEI-corrupted packet and subsequent PUSI=0 packets on that PID with null packets until PUSI=1" in {
      val cachedHeadersRef = new AtomicReference[MpegTsSync.CachedHeaders](MpegTsSync.CachedHeaders())
      val pid = 0x0101
      val teiOnPid = {
        val arr = buildMediaPacket(pid).toArray
        arr(1) = (arr(1) | 0x80).toByte // TEI = 1
        ByteString(arr)
      }
      val midPesContinuation = buildMediaPacket(pid) // PUSI = 0 on same PID
      val otherPidPacket = buildMediaPacket(0x0102)  // unaffected PID
      val nextPesStart = {
        val arr = buildMediaPacket(pid).toArray
        arr(1) = (arr(1) | 0x40).toByte // PUSI = 1 on same PID
        ByteString(arr)
      }

      val out = Source.single(teiOnPid ++ midPesContinuation ++ otherPidPacket ++ nextPesStart)
        .via(MpegTsSync.cacheFlow(cachedHeadersRef))
        .runWith(Sink.fold(ByteString.empty)(_ ++ _))
        .futureValue

      val _ = out.length shouldBe (188 * 4)
      val _ = out.slice(0, 188) shouldBe MpegTsSync.MPEGTS_NULL_PACKET
      val _ = out.slice(188, 376) shouldBe MpegTsSync.MPEGTS_NULL_PACKET
      val _ = out.slice(376, 564) shouldBe otherPidPacket
      out.slice(564, 752) shouldBe nextPesStart
    }

    "cacheFlow should recover video on CC jump at next PES start without dropping the whole GOP, rewriting CC continuously" in {
      val videoPid = 0x0101
      val audioPid = 0x0102
      val cachedHeadersRef = new AtomicReference[MpegTsSync.CachedHeaders](
        MpegTsSync.CachedHeaders(
          videoPid = Some(videoPid)
        , videoStreamType = Some(0x02)
        , audioPids = Seq(audioPid)
        , audioStreamTypes = Map(audioPid -> 0x81)
        )
      )

      val cleanVideo1 = buildKeyframePacket(videoPid, isH264 = false, isGop = false)
      // Sudden CC jump inside the video GOP with PUSI = 0 and no slice start code
      val ccJumpVideo = withCc(buildMediaPacket(videoPid), cc = 5, pusi = false)
      // Subsequent P-frame (PUSI = 1 with valid PES start code 00 00 01 E0) within the same GOP
      val pFrameVideo = buildVideoPesPacket(videoPid, pts = 93003L, pusi = true, cc = 6, isKeyframe = false)
      // Clean audio packet with valid AC-3 syncword
      val cleanAudio = buildAudioPesPacket(audioPid, pts = 93003L, cc = 1, withValidSyncword = true)
      // Next video packet continuing after recovery
      val postRecoveryVideo = buildVideoPesPacket(videoPid, pts = 96006L, pusi = true, cc = 7, isKeyframe = false)

      val input = cleanVideo1 ++ ccJumpVideo ++ pFrameVideo ++ cleanAudio ++ postRecoveryVideo

      val out = Source.single(input)
        .via(MpegTsSync.cacheFlow(cachedHeadersRef))
        .runWith(Sink.fold(ByteString.empty)(_ ++ _))
        .futureValue

      // Outbound stream:
      // 0: cleanVideo1 (videoPid, outbound CC = 0)
      // 1: MPEGTS_NULL_PACKET (ccJumpVideo replaced by null packet)
      // 2: pFrameVideo (recovered immediately on PES start code 00 00 01 E0 without dropping whole GOP! Outbound CC = 1, no discontinuity packet!)
      // 3: cleanAudio (audioPid, outbound CC = 0)
      // 4: postRecoveryVideo (videoPid, outbound CC = 2)
      val _ = out.length shouldBe (188 * 5)
      val _ = (out(3) & 0x0F) shouldBe 0 // cleanVideo1 CC = 0
      val _ = out.slice(188, 376) shouldBe MpegTsSync.MPEGTS_NULL_PACKET
      val _ = (((out(376 + 1) & 0x1F) << 8) | (out(376 + 2) & 0xFF)) shouldBe videoPid
      val _ = (out(376 + 3) & 0x0F) shouldBe 1 // pFrameVideo CC = 1
      val _ = (((out(564 + 1) & 0x1F) << 8) | (out(564 + 2) & 0xFF)) shouldBe audioPid
      val _ = (out(564 + 3) & 0x0F) shouldBe 0 // cleanAudio CC = 0
      val _ = (((out(752 + 1) & 0x1F) << 8) | (out(752 + 2) & 0xFF)) shouldBe videoPid
      val _ = (out(752 + 3) & 0x0F) shouldBe 2 // postRecoveryVideo CC = 2
      out.containsSlice(MpegTsSync.discontinuityPacket(videoPid, 6)) shouldBe false
    }

    "cacheFlow should scrub PUSI=0 audio packets after a CC jump and gate until PUSI=1 with valid syncword" in {
      val audioPid = 0x0102
      val cachedHeadersRef = new AtomicReference[MpegTsSync.CachedHeaders](
        MpegTsSync.CachedHeaders(
          videoPid = Some(0x0101)
        , audioPids = Seq(audioPid)
        , audioStreamTypes = Map(audioPid -> 0x81)
        )
      )

      val audio1 = buildAudioPesPacket(audioPid, pts = 90000L, cc = 2, withValidSyncword = true)
      // CC jump mid-PES
      val audioCcJumpMidPes = withCc(buildMediaPacket(audioPid), cc = 6, pusi = false)
      // Trailing mid-PES
      val audioTrailingMidPes = withCc(buildMediaPacket(audioPid), cc = 7, pusi = false)
      // Next PES packet with valid AC-3 syncword
      val audioNextPes = buildAudioPesPacket(audioPid, pts = 92880L, cc = 8, withValidSyncword = true)

      val out = Source.single(audio1 ++ audioCcJumpMidPes ++ audioTrailingMidPes ++ audioNextPes)
        .via(MpegTsSync.cacheFlow(cachedHeadersRef))
        .runWith(Sink.fold(ByteString.empty)(_ ++ _))
        .futureValue

      val _ = out.length shouldBe (188 * 4)
      val _ = (out(3) & 0x0F) shouldBe 0 // audio1 rewritten CC = 0
      val _ = out.slice(188, 376) shouldBe MpegTsSync.MPEGTS_NULL_PACKET
      val _ = out.slice(376, 564) shouldBe MpegTsSync.MPEGTS_NULL_PACKET
      val _ = (((out(564 + 1) & 0x1F) << 8) | (out(564 + 2) & 0xFF)) shouldBe audioPid
      (out(564 + 3) & 0x0F) shouldBe 1 // audioNextPes rewritten CC = 1
    }

    "compute signed 33-bit timestamp differences correctly in signedPtsDiff" in {
      val _ = MpegTsSync.signedPtsDiff(100L, 50L) shouldBe 50L
      val _ = MpegTsSync.signedPtsDiff(50L, 100L) shouldBe -50L
      val _ = MpegTsSync.signedPtsDiff(10L, 0x1FFFFFFF0L) shouldBe 26L
      val _ = MpegTsSync.signedPtsDiff(0x1FFFFFFF0L, 10L) shouldBe -26L
      MpegTsSync.signedPtsDiff(90000L, 90000L) shouldBe 0L
    }

    "cacheFlow should recover video immediately on valid MPEG-2 slice code after TEI, stuffing prefix into adaptation field" in {
      val videoPid = 0x0101
      val cachedHeadersRef = new AtomicReference[MpegTsSync.CachedHeaders](
        MpegTsSync.CachedHeaders(
          videoPid = Some(videoPid)
        , videoStreamType = Some(0x02)
        )
      )

      val cleanVideo1 = buildKeyframePacket(videoPid, isH264 = false, isGop = false)
      val teiVideo = {
        val arr = buildMediaPacket(videoPid).toArray
        arr(1) = (arr(1) | 0x80).toByte // TEI = 1
        ByteString(arr)
      }
      // PUSI = 0 packet with mid-payload slice start code (00 00 01 05) at byte offset 10 (prefix bytes 4..9 orphaned)
      val midPayloadSliceVideo = {
        val arr = Array.fill[Byte](188)(0xAA.toByte)
        arr(0) = 0x47.toByte
        arr(1) = ((videoPid >> 8) & 0x1F).toByte // PUSI = 0
        arr(2) = (videoPid & 0xFF).toByte
        arr(3) = 0x10.toByte // payload only
        // Orphaned bytes at 4..9
        arr(10) = 0x00.toByte
        arr(11) = 0x00.toByte
        arr(12) = 0x01.toByte
        arr(13) = 0x05.toByte // slice code row 5 <= maxSliceRow (68)
        arr(14) = 0x38.toByte
        arr(15) = 0x00.toByte
        ByteString(arr)
      }
      val nextVideo = buildMediaPacket(videoPid)

      val out = Source.single(cleanVideo1 ++ teiVideo ++ midPayloadSliceVideo ++ nextVideo)
        .via(MpegTsSync.cacheFlow(cachedHeadersRef))
        .runWith(Sink.fold(ByteString.empty)(_ ++ _))
        .futureValue

      val _ = out.length shouldBe (188 * 4)
      val _ = (out(3) & 0x0F) shouldBe 0 // cleanVideo1 CC = 0
      val _ = out.slice(188, 376) shouldBe MpegTsSync.MPEGTS_NULL_PACKET
      val recoveredPkt = out.slice(376, 564)
      val _ = (((recoveredPkt(1) & 0x1F) << 8) | (recoveredPkt(2) & 0xFF)) shouldBe videoPid
      val _ = (recoveredPkt(3) & 0x0F) shouldBe 1 // continuous outbound CC = 1
      val _ = ((recoveredPkt(3) & 0x30) >> 4) shouldBe 3 // adaptation field + payload (stuffed prefix)
      val _ = recoveredPkt.slice(10, 14) shouldBe ByteString(0x00, 0x00, 0x01, 0x05) // slice start code preserved
      val _ = (((out(564 + 1) & 0x1F) << 8) | (out(564 + 2) & 0xFF)) shouldBe videoPid
      (out(564 + 3) & 0x0F) shouldBe 2 // nextVideo continuous outbound CC = 2
    }

    "cacheFlow should preserve PCR in PCR-only adaptation-field packet on video CC error" in {
      val videoPid = 0x0101
      val cachedHeadersRef = new AtomicReference[MpegTsSync.CachedHeaders](
        MpegTsSync.CachedHeaders(
          videoPid = Some(videoPid)
        , pcrPid = Some(videoPid)
        , videoStreamType = Some(0x02)
        )
      )

      val cleanVideo1 = buildKeyframePacket(videoPid, isH264 = false, isGop = false)
      // Packet with CC jump (1 -> 5), TEI = 0, but valid adaptation-field PCR
      val ccErrorWithPcr = buildVideoPacketWithPcr(videoPid, pcr = 543210L, cc = 5, tei = false)
      // Mid-PES continuation packet without slice or PES header (dropped as null packet)
      val trailingMidPes = buildMediaPacket(videoPid)
      // PES header recovery packet
      val recoveryPes = buildVideoPesPacket(videoPid, pts = 93003L, pusi = true, cc = 7, isKeyframe = false)

      val out = Source.single(cleanVideo1 ++ ccErrorWithPcr ++ trailingMidPes ++ recoveryPes)
        .via(MpegTsSync.cacheFlow(cachedHeadersRef))
        .runWith(Sink.fold(ByteString.empty)(_ ++ _))
        .futureValue

      val _ = out.length shouldBe (188 * 4)
      val _ = (out(3) & 0x0F) shouldBe 0 // cleanVideo1 CC = 0
      val pcrPkt = out.slice(188, 376)
      val _ = (((pcrPkt(1) & 0x1F) << 8) | (pcrPkt(2) & 0xFF)) shouldBe videoPid
      val _ = ((pcrPkt(3) & 0x30) >> 4) shouldBe 2 // adaptation field only
      val _ = (pcrPkt(3) & 0x0F) shouldBe 0 // adaptation-only packet preserves previous CC = 0
      val _ = (pcrPkt(4) & 0xFF) shouldBe 183 // full 183-byte adaptation field
      val _ = (pcrPkt(5) & 0x10) should not be 0 // PCR flag set
      val _ = FreezeFramePacer.readPcrBase(pcrPkt.toArray, 0) shouldBe Some(543210L)
      val _ = out.slice(376, 564) shouldBe MpegTsSync.MPEGTS_NULL_PACKET
      val _ = (((out(564 + 1) & 0x1F) << 8) | (out(564 + 2) & 0xFF)) shouldBe videoPid
      (out(564 + 3) & 0x0F) shouldBe 1 // recoveryPes continuous payload CC = 1
    }

    "cacheFlow should neutralize out-of-bounds MPEG-2 slice codes and restore mutated sequence headers once locked" in {
      val videoPid = 0x0101
      val pat = buildPatPacket(0x0100)
      val pmt = buildFullPmtPacket(pmtPid = 0x0100, pcrPid = 0x0101, videoPid = videoPid)
      val cachedHeadersRef = new AtomicReference[MpegTsSync.CachedHeaders](MpegTsSync.CachedHeaders())

      // 12-byte Sequence Header for 1920x1080 (maxSliceRow = 68)
      val lockedSeqHeaderBytes = Array[Byte](
        0x00.toByte, 0x00.toByte, 0x01.toByte, 0xB3.toByte,
        0x78.toByte, 0x04.toByte, 0x38.toByte, 0x12.toByte,
        0x34.toByte, 0x56.toByte, 0x78.toByte, 0x9A.toByte
      )

      def makeSeqPacket(seqBytes: Array[Byte]): ByteString = {
        val arr = Array.fill[Byte](188)(0xFF.toByte)
        arr(0) = 0x47.toByte
        arr(1) = (0x40 | ((videoPid >> 8) & 0x1F)).toByte
        arr(2) = (videoPid & 0xFF).toByte
        arr(3) = 0x10.toByte
        System.arraycopy(seqBytes, 0, arr, 4, 12)
        ByteString(arr)
      }

      val seqPkt1 = makeSeqPacket(lockedSeqHeaderBytes)
      val seqPkt2 = makeSeqPacket(lockedSeqHeaderBytes)

      // Packet with out-of-bounds slice code 0x99 (row 153 > 68) and valid slice code 0x02
      val poisonSlicePkt = {
        val arr = Array.fill[Byte](188)(0xAA.toByte)
        arr(0) = 0x47.toByte
        arr(1) = ((videoPid >> 8) & 0x1F).toByte
        arr(2) = (videoPid & 0xFF).toByte
        arr(3) = 0x10.toByte
        arr(4) = 0x00.toByte
        arr(5) = 0x00.toByte
        arr(6) = 0x01.toByte
        arr(7) = 0x99.toByte // out-of-bounds slice code
        arr(8) = 0x00.toByte
        arr(9) = 0x00.toByte
        arr(10) = 0x01.toByte
        arr(11) = 0x02.toByte // valid slice code
        ByteString(arr)
      }

      // Packet with mutated sequence header (byte 8 tampered to 0xFF)
      val mutatedSeqHeaderBytes = lockedSeqHeaderBytes.clone()
      mutatedSeqHeaderBytes(8) = 0xFF.toByte
      val mutatedSeqPkt = makeSeqPacket(mutatedSeqHeaderBytes)

      val out = Source(List(pat, pmt, seqPkt1, seqPkt2, poisonSlicePkt, mutatedSeqPkt))
        .via(MpegTsSync.cacheFlow(cachedHeadersRef))
        .runWith(Sink.seq)
        .futureValue

      val _ = out.length shouldBe 6
      // Packet index 4 is poisonSlicePkt
      val sanitizedSlice = out(4)
      val _ = sanitizedSlice.slice(4, 8) shouldBe ByteString(0x00, 0x00, 0x00, 0x99.toByte) // neutralized 00 00 01 99 -> 00 00 00 99
      val _ = sanitizedSlice.slice(8, 12) shouldBe ByteString(0x00, 0x00, 0x01, 0x02) // preserved valid slice

      // Packet index 5 is mutatedSeqPkt: should have been restored to lockedSeqHeaderBytes
      val restoredSeq = out(5)
      restoredSeq.slice(4, 16) shouldBe ByteString.fromArray(lockedSeqHeaderBytes)
    }

    "cacheFlow should gate audio on TEI/CC error and reject PUSI=1 packet with garbage elementary bytes until valid syncword arrives" in {
      val audioPid = 0x0102
      val cachedHeadersRef = new AtomicReference[MpegTsSync.CachedHeaders](
        MpegTsSync.CachedHeaders(
          videoPid = Some(0x0101)
        , audioPids = Seq(audioPid)
        , audioStreamTypes = Map(audioPid -> 0x81)
        )
      )

      val audio1 = buildAudioPesPacket(audioPid, pts = 90000L, cc = 1, withValidSyncword = true)
      // TEI error
      val audioTei = {
        val arr = buildAudioPesPacket(audioPid, pts = 92880L, cc = 2, withValidSyncword = true).toArray
        arr(1) = (arr(1) | 0x80).toByte // TEI = 1
        ByteString(arr)
      }
      // PUSI = 1 packet, but with garbage elementary bytes (no 0x0B77 syncword)
      val audioGarbagePusi = buildAudioPesPacket(audioPid, pts = 95760L, cc = 3, withValidSyncword = false)
      // PUSI = 0 packet (continuation)
      val audioMidPes = withCc(buildMediaPacket(audioPid), cc = 4, pusi = false)
      // PUSI = 1 packet with valid AC-3 syncword
      val audioValidPusi = buildAudioPesPacket(audioPid, pts = 98640L, cc = 5, withValidSyncword = true)

      val out = Source.single(audio1 ++ audioTei ++ audioGarbagePusi ++ audioMidPes ++ audioValidPusi)
        .via(MpegTsSync.cacheFlow(cachedHeadersRef))
        .runWith(Sink.fold(ByteString.empty)(_ ++ _))
        .futureValue

      val _ = out.length shouldBe (188 * 5)
      val _ = (out(3) & 0x0F) shouldBe 0 // audio1 rewritten CC = 0
      val _ = out.slice(188, 376) shouldBe MpegTsSync.MPEGTS_NULL_PACKET // TEI packet
      val _ = out.slice(376, 564) shouldBe MpegTsSync.MPEGTS_NULL_PACKET // garbage PUSI packet remained gated!
      val _ = out.slice(564, 752) shouldBe MpegTsSync.MPEGTS_NULL_PACKET // mid-PES packet remained gated!
      val recoveredAudio = out.slice(752, 940)
      val _ = (((recoveredAudio(1) & 0x1F) << 8) | (recoveredAudio(2) & 0xFF)) shouldBe audioPid
      (recoveredAudio(3) & 0x0F) shouldBe 1 // audioValidPusi continuous CC = 1
    }

    "cacheFlow should scrub single-packet PTS outliers jumping > 2s to lastOutputPts + 3003" in {
      val videoPid = 0x0101
      val cachedHeadersRef = new AtomicReference[MpegTsSync.CachedHeaders](
        MpegTsSync.CachedHeaders(videoPid = Some(videoPid), videoStreamType = Some(0x02))
      )

      val v1 = buildVideoPesPacket(videoPid, pts = 90000L, cc = 1)
      val v2 = buildVideoPesPacket(videoPid, pts = 93003L, cc = 2)
      // Outlier jump of +406,997 ticks (> 180,000 ticks = 2s)
      val vOutlier = buildVideoPesPacket(videoPid, pts = 500000L, cc = 3)
      val v4 = buildVideoPesPacket(videoPid, pts = 96006L, cc = 4)

      val out = Source.single(v1 ++ v2 ++ vOutlier ++ v4)
        .via(MpegTsSync.cacheFlow(cachedHeadersRef))
        .runWith(Sink.fold(ByteString.empty)(_ ++ _))
        .futureValue

      val _ = out.length shouldBe (188 * 4)
      val p1Pts = FreezeFramePacer.readPtsDts(out.slice(0, 188).toArray, 4, 188).map(_._1)
      val p2Pts = FreezeFramePacer.readPtsDts(out.slice(188, 376).toArray, 4, 188).map(_._1)
      val p3Pts = FreezeFramePacer.readPtsDts(out.slice(376, 564).toArray, 4, 188).map(_._1)
      val p4Pts = FreezeFramePacer.readPtsDts(out.slice(564, 752).toArray, 4, 188).map(_._1)

      val _ = p1Pts shouldBe Some(90000L)
      val _ = p2Pts shouldBe Some(93003L)
      // Outlier scrubbed to 93003L + 3003L = 96006L
      val _ = p3Pts shouldBe Some(96006L)
      p4Pts shouldBe Some(96006L)
    }

    "cacheFlow should rebase timestamps across confirmed PTS reset to maintain strict monotonic PTS" in {
      val videoPid = 0x0101
      val cachedHeadersRef = new AtomicReference[MpegTsSync.CachedHeaders](
        MpegTsSync.CachedHeaders(videoPid = Some(videoPid), videoStreamType = Some(0x02))
      )

      val v1 = buildVideoPesPacket(videoPid, pts = 900000L, cc = 1)
      val v2 = buildVideoPesPacket(videoPid, pts = 903003L, cc = 2)
      // Upstream encoder PTS resets back near 0 across 2 consecutive packets
      val vReset1 = buildVideoPesPacket(videoPid, pts = 0L, cc = 3)
      val vReset2 = buildVideoPesPacket(videoPid, pts = 3003L, cc = 4)

      val out = Source.single(v1 ++ v2 ++ vReset1 ++ vReset2)
        .via(MpegTsSync.cacheFlow(cachedHeadersRef))
        .runWith(Sink.fold(ByteString.empty)(_ ++ _))
        .futureValue

      val _ = out.length shouldBe (188 * 4)
      val p1Pts = FreezeFramePacer.readPtsDts(out.slice(0, 188).toArray, 4, 188).map(_._1).get
      val p2Pts = FreezeFramePacer.readPtsDts(out.slice(188, 376).toArray, 4, 188).map(_._1).get
      val p3Pts = FreezeFramePacer.readPtsDts(out.slice(376, 564).toArray, 4, 188).map(_._1).get
      val p4Pts = FreezeFramePacer.readPtsDts(out.slice(564, 752).toArray, 4, 188).map(_._1).get

      val _ = p1Pts shouldBe 900000L
      val _ = p2Pts shouldBe 903003L
      val _ = p3Pts should be >= 906006L
      p4Pts should be > p3Pts
    }

    "cacheFlow should synthesize freeze-frame video and audio during upstream stalls and resume on live keyframe" in {
      val videoPid = 0x0101
      val audioPid = 0x0102
      val pat = buildPatPacket(0x0100)
      val pmt = buildFullPmtPacketWithAudio(
        pmtPid = 0x0100
      , pcrPid = videoPid
      , videoPid = videoPid
      , videoStreamType = 0x02
      , audioPid = audioPid
      , audioStreamType = 0x81
      )
      val cachedHeadersRef = new AtomicReference[MpegTsSync.CachedHeaders](MpegTsSync.CachedHeaders())

      val initialKeyframe = buildVideoPesPacket(videoPid, pts = 90000L, cc = 1, isKeyframe = true)
      val initialAudio = buildAudioPesPacket(audioPid, pts = 90000L, cc = 1, withValidSyncword = true)

      val (queue, probe) = Source.queue[ByteString](bufferSize = 64, OverflowStrategy.backpressure)
        .via(
          MpegTsSync.cacheFlow(
            cachedHeadersRef
          , freezeThreshold = 80.millis
          , freezeTickInterval = 30.millis
          )
        )
        .toMat(Sink.seq)(org.apache.pekko.stream.scaladsl.Keep.both)
        .run()

      val _ = queue.offer(pat ++ pmt ++ initialKeyframe ++ initialAudio)

      // Stall upstream for 160ms (exceeding freezeThreshold of 80ms)
      Thread.sleep(160L)

      // Live non-keyframe video packet (should be gated while awaiting first live keyframe after stall)
      val liveNonKeyframe = buildVideoPesPacket(videoPid, pts = 120000L, cc = 2, isKeyframe = false, sliceRow = Some(1))
      // Live keyframe packet (resumes stream)
      val liveKeyframe = buildVideoPesPacket(videoPid, pts = 123003L, cc = 3, isKeyframe = true)

      val _ = queue.offer(liveNonKeyframe ++ liveKeyframe)
      queue.complete()

      val emitted = probe.futureValue.foldLeft(ByteString.empty)(_ ++ _)
      val packetCount = emitted.length / 188
      val _ = packetCount should be > 6 // Initial packets + at least one synthetic freeze frame + live packets

      // Verify that emitted packets on videoPid and audioPid maintain strictly continuous CC
      var lastVideoCc = -1
      var lastAudioCc = -1
      var syntheticVideoFound = false
      var i = 0
      while (i < packetCount) {
        val pkt = emitted.slice(i * 188, (i + 1) * 188)
        val pid = (((pkt(1) & 0x1F) << 8) | (pkt(2) & 0xFF))
        val cc = pkt(3) & 0x0F
        if (pid == videoPid) {
          if (lastVideoCc >= 0) {
            val _ = cc shouldBe ((lastVideoCc + 1) & 0x0F)
          }
          lastVideoCc = cc
          if (i > 3 && i < packetCount - 2) {
            syntheticVideoFound = true
          }
        } else if (pid == audioPid) {
          if (lastAudioCc >= 0) {
            val _ = cc shouldBe ((lastAudioCc + 1) & 0x0F)
          }
          lastAudioCc = cc
        }
        i += 1
      }
      syntheticVideoFound shouldBe true
    }
  }
}

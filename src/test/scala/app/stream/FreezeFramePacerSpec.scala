package app.stream

import org.apache.pekko.util.ByteString
import org.junit.runner.RunWith
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.junit.JUnitRunner

@RunWith(classOf[JUnitRunner])
class FreezeFramePacerSpec extends AnyWordSpec with Matchers {

  "FreezeFramePacer PTS/DTS encoding & decoding" should {
    "encode and decode 0L correctly" in {
      val buf = new Array[Byte](5)
      FreezeFramePacer.encodePtsBytes(buf, 0, 2, 0L)
      val _ = (buf(0) & 0xF0) shouldBe 0x20
      val _ = (buf(0) & 0x01) shouldBe 0x01
      val _ = (buf(2) & 0x01) shouldBe 0x01
      val _ = (buf(4) & 0x01) shouldBe 0x01
      FreezeFramePacer.decodePtsBytes(buf, 0) shouldBe 0L
    }

    "encode and decode 33-bit boundary value 0x1FFFFFFFFL correctly" in {
      val buf = new Array[Byte](5)
      FreezeFramePacer.encodePtsBytes(buf, 0, 3, 0x1FFFFFFFFL)
      val _ = (buf(0) & 0xF0) shouldBe 0x30
      FreezeFramePacer.decodePtsBytes(buf, 0) shouldBe 0x1FFFFFFFFL
    }

    "encode and decode 1-hour timestamp 90000L * 3600 correctly" in {
      val buf = new Array[Byte](5)
      val ts = 90000L * 3600L
      FreezeFramePacer.encodePtsBytes(buf, 0, 1, ts)
      val _ = (buf(0) & 0xF0) shouldBe 0x10
      FreezeFramePacer.decodePtsBytes(buf, 0) shouldBe ts
    }

    "read and write PTS-only PES headers" in {
      val pkt = new Array[Byte](188)
      pkt(0) = 0x00.toByte
      pkt(1) = 0x00.toByte
      pkt(2) = 0x01.toByte
      pkt(3) = 0xE0.toByte
      pkt(7) = 0x80.toByte
      pkt(8) = 5.toByte
      FreezeFramePacer.writePtsDts(pkt, 0, 188, 543210L, None)
      val res = FreezeFramePacer.readPtsDts(pkt, 0, 188)
      res shouldBe Some((543210L, None))
    }

    "read and write PTS+DTS PES headers" in {
      val pkt = new Array[Byte](188)
      pkt(0) = 0x00.toByte
      pkt(1) = 0x00.toByte
      pkt(2) = 0x01.toByte
      pkt(3) = 0xE0.toByte
      pkt(7) = 0xC0.toByte
      pkt(8) = 10.toByte
      FreezeFramePacer.writePtsDts(pkt, 0, 188, 0x1FFFFFFFFL, Some(0x1FFFF0000L))
      val res = FreezeFramePacer.readPtsDts(pkt, 0, 188)
      res shouldBe Some((0x1FFFFFFFFL, Some(0x1FFFF0000L)))
    }

    "return None when PES header has no PTS/DTS flags" in {
      val pkt = new Array[Byte](188)
      pkt(0) = 0x00.toByte
      pkt(1) = 0x00.toByte
      pkt(2) = 0x01.toByte
      pkt(3) = 0xE0.toByte
      pkt(7) = 0x00.toByte
      pkt(8) = 0.toByte
      FreezeFramePacer.readPtsDts(pkt, 0, 188) shouldBe None
    }
  }

  "FreezeFramePacer PCR helpers" should {
    "read and write PCR base preserving reserved bits" in {
      val pkt = new Array[Byte](188)
      pkt(3) = 0x20.toByte
      pkt(4) = 7.toByte
      pkt(5) = 0x10.toByte
      val pcr = 0x1A2B3C4D5L & FreezeFramePacer.PtsMask
      FreezeFramePacer.writePcrBase(pkt, 0, pcr)
      val _ = (pkt(10) & 0x7E) shouldBe 0x7E
      val _ = pkt(11) shouldBe 0.toByte
      FreezeFramePacer.readPcrBase(pkt, 0) shouldBe Some(pcr)
    }

    "create valid pcrOnlyPacket" in {
      val pcr = 90000L * 120
      val pkt = FreezeFramePacer.pcrOnlyPacket(0x0100, 7, pcr)
      val _ = pkt.length shouldBe 188
      val _ = pkt(0) shouldBe 0x47.toByte
      val _ = (((pkt(1) & 0x1F) << 8) | (pkt(2) & 0xFF)) shouldBe 0x0100
      val _ = ((pkt(3) & 0x30) >> 4) shouldBe 2
      val _ = (pkt(3) & 0x0F) shouldBe 7
      val _ = (pkt(4) & 0xFF) shouldBe 183
      val _ = (pkt(5) & 0x10) shouldBe 0x10
      val _ = FreezeFramePacer.readPcrBase(pkt, 0) shouldBe Some(pcr)
      pkt.slice(12, 188).forall(_ == 0xFF.toByte) shouldBe true
    }

    "provide zero-allocation raw PTS/DTS and PCR helpers matching Option-based APIs" in {
      val ptsOnly = new Array[Byte](188)
      ptsOnly(0) = 0x00.toByte
      ptsOnly(1) = 0x00.toByte
      ptsOnly(2) = 0x01.toByte
      ptsOnly(3) = 0xE0.toByte
      ptsOnly(7) = 0x80.toByte
      ptsOnly(8) = 5.toByte
      val pts = 0x123456789L
      FreezeFramePacer.writePtsDtsRaw(ptsOnly, 0, 188, pts, -1L)
      val _ = FreezeFramePacer.readPesPts(ptsOnly, 0, 188) shouldBe pts
      val _ = FreezeFramePacer.readPesDts(ptsOnly, 0, 188) shouldBe -1L
      val _ = FreezeFramePacer.readPtsDts(ptsOnly, 0, 188) shouldBe Some((pts, None))

      val ptsDts = new Array[Byte](188)
      ptsDts(0) = 0x00.toByte
      ptsDts(1) = 0x00.toByte
      ptsDts(2) = 0x01.toByte
      ptsDts(3) = 0xE0.toByte
      ptsDts(7) = 0xC0.toByte
      ptsDts(8) = 10.toByte
      val dts = 0x123450000L
      FreezeFramePacer.writePtsDtsRaw(ptsDts, 0, 188, pts, dts)
      val _ = FreezeFramePacer.readPesPts(ptsDts, 0, 188) shouldBe pts
      val _ = FreezeFramePacer.readPesDts(ptsDts, 0, 188) shouldBe dts
      val _ = FreezeFramePacer.readPtsDts(ptsDts, 0, 188) shouldBe Some((pts, Some(dts)))

      val noFlags = new Array[Byte](188)
      noFlags(2) = 0x01.toByte
      noFlags(3) = 0xE0.toByte
      val _ = FreezeFramePacer.readPesPts(noFlags, 0, 188) shouldBe -1L
      val _ = FreezeFramePacer.readPesDts(noFlags, 0, 188) shouldBe -1L

      val pcrVal = 0x1A2B3C4D5L
      val expectedPcrPkt = FreezeFramePacer.pcrOnlyPacket(0x100, 7, pcrVal)
      val scratchPkt = new Array[Byte](188)
      FreezeFramePacer.fillPcrOnlyPacket(scratchPkt, 0x100, 7, pcrVal)
      val _ = java.util.Arrays.equals(scratchPkt, expectedPcrPkt) shouldBe true
      val _ = FreezeFramePacer.readPcrBaseRaw(scratchPkt, 0) shouldBe pcrVal
      FreezeFramePacer.readPcrBaseRaw(new Array[Byte](188), 0) shouldBe -1L
    }
  }

  "FreezeFramePacer MPEG-2 sequence header & payload sanitization" should {
    "extract maxSliceRow = 68 for 1920x1080" in {
      val arr = Array.fill[Byte](32)(0x00.toByte)
      arr(4) = 0x00.toByte
      arr(5) = 0x00.toByte
      arr(6) = 0x01.toByte
      arr(7) = 0xB3.toByte
      arr(8) = 0x78.toByte
      arr(9) = 0x04.toByte
      arr(10) = 0x38.toByte
      val opt = FreezeFramePacer.parseMpeg2SequenceHeader(arr, 0, 32)
      val _ = opt.isDefined shouldBe true
      val (hdr, maxRow) = opt.get
      val _ = hdr.length shouldBe 12
      maxRow shouldBe 68
    }

    "extract maxSliceRow = 45 for 1280x720" in {
      val arr = Array.fill[Byte](32)(0x00.toByte)
      arr(4) = 0x00.toByte
      arr(5) = 0x00.toByte
      arr(6) = 0x01.toByte
      arr(7) = 0xB3.toByte
      arr(8) = 0x50.toByte
      arr(9) = 0x02.toByte
      arr(10) = 0xD0.toByte
      val opt = FreezeFramePacer.parseMpeg2SequenceHeader(arr, 0, 32)
      val _ = opt.isDefined shouldBe true
      opt.get._2 shouldBe 45
    }

    "clamp out-of-bounds slice start codes in-place" in {
      val payload = Array[Byte](
        0x00.toByte, 0x00.toByte, 0x01.toByte, 0x99.toByte,
        0x00.toByte, 0x00.toByte, 0x01.toByte, 0x10.toByte
      )
      val clamped = FreezeFramePacer.sanitizeMpeg2Payload(payload, 0, payload.length, 68, None)
      val _ = clamped shouldBe 1
      val _ = payload(2) shouldBe 0x00.toByte
      payload(6) shouldBe 0x01.toByte
    }

    "restore mutated sequence header bytes when locked header is provided" in {
      val lockedArr = Array[Byte](
        0x00.toByte, 0x00.toByte, 0x01.toByte, 0xB3.toByte,
        0x78.toByte, 0x04.toByte, 0x38.toByte, 0x12.toByte,
        0x34.toByte, 0x56.toByte, 0x78.toByte, 0x9A.toByte
      )
      val lockedHdr = ByteString.fromArray(lockedArr)
      val payload = lockedHdr.toArray
      payload(8) = 0xFF.toByte
      val clamped = FreezeFramePacer.sanitizeMpeg2Payload(payload, 0, payload.length, 68, Some(lockedHdr))
      val _ = clamped shouldBe 0
      ByteString.fromArray(payload, 0, 12) shouldBe lockedHdr
    }

    "detect sequence header with hasMpeg2SequenceHeader within bounds" in {
      val arr = Array[Byte](0x00.toByte, 0x00.toByte, 0x01.toByte, 0xB3.toByte, 0x78.toByte, 0x04.toByte)
      val _ = FreezeFramePacer.hasMpeg2SequenceHeader(arr, 0, arr.length) shouldBe true
      val _ = FreezeFramePacer.hasMpeg2SequenceHeader(arr, 1, arr.length - 1) shouldBe false
      FreezeFramePacer.hasMpeg2SequenceHeader(arr, 0, 3) shouldBe false
    }

    "return false for hasMpeg2SequenceHeader when sequence header is absent or out of bounds" in {
      val gopArr = Array[Byte](0x00.toByte, 0x00.toByte, 0x01.toByte, 0xB8.toByte, 0x00.toByte, 0x00.toByte)
      val _ = FreezeFramePacer.hasMpeg2SequenceHeader(gopArr, 0, gopArr.length) shouldBe false
      val _ = FreezeFramePacer.hasMpeg2SequenceHeader(gopArr, -1, gopArr.length) shouldBe false
      val _ = FreezeFramePacer.hasMpeg2SequenceHeader(gopArr, 0, 10) shouldBe false
      FreezeFramePacer.hasMpeg2SequenceHeader(gopArr, 0, 0) shouldBe false
    }

    "synthesize a valid sequence header packet" in {
      val lockedArr = Array[Byte](
        0x00.toByte, 0x00.toByte, 0x01.toByte, 0xB3.toByte,
        0x78.toByte, 0x04.toByte, 0x38.toByte, 0x12.toByte,
        0x34.toByte, 0x56.toByte, 0x78.toByte, 0x9A.toByte
      )
      val seqHeader = ByteString.fromArray(lockedArr)
      val videoPid = 0x0100
      val cc = 7
      val bs = FreezeFramePacer.synthesizeSequenceHeaderPacket(videoPid, seqHeader, cc)
      val _ = bs.length shouldBe 188
      val arr = bs.toArray
      val _ = arr(0) shouldBe 0x47.toByte
      val _ = (arr(1) & 0x40) should not be 0
      val _ = (((arr(1) & 0x1F) << 8) | (arr(2) & 0xFF)) shouldBe videoPid
      val _ = ((arr(3) & 0x30) >> 4) shouldBe 3
      val _ = (arr(3) & 0x0F) shouldBe cc
      val pesOffset = FreezeFramePacer.findPesHeaderOffset(arr, 0)
      val _ = pesOffset shouldBe 167
      val payloadLen = 188 - pesOffset
      val _ = FreezeFramePacer.hasMpeg2SequenceHeader(arr, pesOffset, payloadLen) shouldBe true
      val parsed = FreezeFramePacer.parseMpeg2SequenceHeader(arr, pesOffset, payloadLen)
      val _ = parsed.isDefined shouldBe true
      val (extractedHdr, maxRow) = parsed.get
      val _ = extractedHdr shouldBe seqHeader
      maxRow shouldBe 68
    }

    "detect out-of-bounds MPEG-2 slice start codes with hasOutOfBoundsMpeg2Slice" in {
      val inBoundsPayload = Array[Byte](
        0x00.toByte, 0x00.toByte, 0x01.toByte, 0x01.toByte,
        0x00.toByte, 0x00.toByte, 0x01.toByte, 0x44.toByte
      )
      val _ = FreezeFramePacer.hasOutOfBoundsMpeg2Slice(inBoundsPayload, 0, inBoundsPayload.length, 68) shouldBe false

      val outOfBoundsPayload = Array[Byte](
        0x00.toByte, 0x00.toByte, 0x01.toByte, 0x01.toByte,
        0x00.toByte, 0x00.toByte, 0x01.toByte, 0x45.toByte
      )
      val _ = FreezeFramePacer.hasOutOfBoundsMpeg2Slice(outOfBoundsPayload, 0, outOfBoundsPayload.length, 68) shouldBe true

      val nonSliceCodesPayload = Array[Byte](
        0x00.toByte, 0x00.toByte, 0x01.toByte, 0xB3.toByte,
        0x00.toByte, 0x00.toByte, 0x01.toByte, 0x00.toByte
      )
      val _ = FreezeFramePacer.hasOutOfBoundsMpeg2Slice(nonSliceCodesPayload, 0, nonSliceCodesPayload.length, 68) shouldBe false

      val userDataWithHighCode = Array[Byte](
        0x00.toByte, 0x00.toByte, 0x01.toByte, 0xB2.toByte,
        0x00.toByte, 0x00.toByte, 0x01.toByte, 0x65.toByte
      )
      FreezeFramePacer.hasOutOfBoundsMpeg2Slice(userDataWithHighCode, 0, userDataWithHighCode.length, 68) shouldBe false
    }

    "capture 22-byte sequence header + sequence_extension and strip quantizer matrix flags when separated by matrix" in {
      val arr = Array.fill[Byte](128)(0xAA.toByte)
      // 12-byte sequence header at offset 0 with load_intra_quantizer_matrix = 1 (bit 1 of byte 11 = 0x02)
      arr(0) = 0x00.toByte
      arr(1) = 0x00.toByte
      arr(2) = 0x01.toByte
      arr(3) = 0xB3.toByte
      arr(4) = 0x78.toByte
      arr(5) = 0x04.toByte
      arr(6) = 0x38.toByte
      arr(7) = 0x34.toByte
      arr(8) = 0x12.toByte
      arr(9) = 0x34.toByte
      arr(10) = 0x56.toByte
      arr(11) = 0x02.toByte
      // 64-byte intra quantizer matrix at offsets 12..75, followed by 10-byte sequence_extension at offset 76
      arr(76) = 0x00.toByte
      arr(77) = 0x00.toByte
      arr(78) = 0x01.toByte
      arr(79) = 0xB5.toByte
      arr(80) = 0x14.toByte // extension_start_code_identifier = 1 (sequence_extension), MP@HL
      arr(81) = 0x82.toByte // progressive_sequence = 0 (interlaced)
      arr(82) = 0x00.toByte
      arr(83) = 0x01.toByte
      arr(84) = 0x00.toByte
      arr(85) = 0x00.toByte

      val parsed = FreezeFramePacer.parseMpeg2SequenceHeader(arr, 0, arr.length)
      val _ = parsed.isDefined shouldBe true
      val (hdr, maxRow) = parsed.get
      val _ = maxRow shouldBe 68
      val _ = hdr.length shouldBe 22
      val _ = (hdr(11) & 0x03) shouldBe 0
      val _ = hdr.slice(12, 16) shouldBe ByteString(0x00, 0x00, 0x01, 0xB5.toByte)

      val synthPkt = FreezeFramePacer.synthesizeSequenceHeaderPacket(0x0100, hdr, cc = 3)
      val _ = synthPkt.length shouldBe 188
      val reParsed = FreezeFramePacer.parseMpeg2SequenceHeader(synthPkt.toArray, 0, 188)
      reParsed shouldBe Some((hdr, 68))
    }

    "reject non-16-aligned dimensions in parseMpeg2SequenceHeader and skip user_data in sanitizeMpeg2Payload" in {
      val invalidDim = Array[Byte](
        0x00.toByte, 0x00.toByte, 0x01.toByte, 0xB3.toByte,
        0x3F.toByte, 0x53.toByte, 0xFB.toByte, 0x00.toByte,
        0x00.toByte, 0x00.toByte, 0x00.toByte, 0x00.toByte
      )
      val _ = FreezeFramePacer.parseMpeg2SequenceHeader(invalidDim, 0, invalidDim.length) shouldBe None

      val userDataPayload = Array[Byte](
        0x00.toByte, 0x00.toByte, 0x01.toByte, 0xB2.toByte,
        0x00.toByte, 0x00.toByte, 0x01.toByte, 0x65.toByte
      )
      val clamped = FreezeFramePacer.sanitizeMpeg2Payload(userDataPayload, 0, userDataPayload.length, 68, None)
      val _ = clamped shouldBe 0
      userDataPayload(6) shouldBe 0x01.toByte
    }
  }

  "FreezeFramePacer slice offset & packet prefix stuffing" should {
    "find first valid video slice offset for MPEG-2" in {
      val arr = Array[Byte](0xFF.toByte, 0xFE.toByte, 0x00.toByte, 0x00.toByte, 0x01.toByte, 0x14.toByte, 0x00.toByte)
      val _ = FreezeFramePacer.findFirstValidVideoSliceOffset(arr, 0, arr.length, Some(0x02), 68) shouldBe 2
      val extArr = Array[Byte](0xFF.toByte, 0x00.toByte, 0x00.toByte, 0x01.toByte, 0xB5.toByte, 0x14.toByte)
      FreezeFramePacer.findFirstValidVideoSliceOffset(extArr, 0, extArr.length, Some(0x02), 68) shouldBe 1
    }

    "find first valid video slice offset for H.264 IDR" in {
      val arr = Array[Byte](0xFF.toByte, 0x00.toByte, 0x00.toByte, 0x01.toByte, 0x05.toByte)
      FreezeFramePacer.findFirstValidVideoSliceOffset(arr, 0, arr.length, Some(0x1B), 68) shouldBe 1
    }

    "stuff packet prefix in 1-byte stuffing case" in {
      val pkt = Array.fill[Byte](188)(0xAA.toByte)
      pkt(0) = 0x47.toByte
      pkt(1) = 0x00.toByte
      pkt(2) = 0x20.toByte
      pkt(3) = 0x10.toByte
      val adjusted = FreezeFramePacer.stuffPacketPrefix(pkt, 0, 5)
      val _ = adjusted shouldBe true
      val _ = ((pkt(3) & 0x30) >> 4) shouldBe 3
      pkt(4) shouldBe 0.toByte
    }

    "stuff packet prefix in multi-byte stuffing case" in {
      val pkt = Array.fill[Byte](188)(0xAA.toByte)
      pkt(0) = 0x47.toByte
      pkt(1) = 0x00.toByte
      pkt(2) = 0x20.toByte
      pkt(3) = 0x10.toByte
      val adjusted = FreezeFramePacer.stuffPacketPrefix(pkt, 0, 10)
      val _ = adjusted shouldBe true
      val _ = ((pkt(3) & 0x30) >> 4) shouldBe 3
      val _ = (pkt(4) & 0xFF) shouldBe 5
      val _ = pkt(5) shouldBe 0x00.toByte
      pkt.slice(6, 10).forall(_ == 0xFF.toByte) shouldBe true
    }

    "expand existing adaptation field" in {
      val pkt = Array.fill[Byte](188)(0xAA.toByte)
      pkt(0) = 0x47.toByte
      pkt(1) = 0x00.toByte
      pkt(2) = 0x20.toByte
      pkt(3) = 0x30.toByte
      pkt(4) = 7.toByte
      val adjusted = FreezeFramePacer.stuffPacketPrefix(pkt, 0, 20)
      val _ = adjusted shouldBe true
      val _ = (pkt(4) & 0xFF) shouldBe 15
      pkt.slice(12, 20).forall(_ == 0xFF.toByte) shouldBe true
    }

    "expand existing adaptation field with oldAfLen = 0 without corrupting flags" in {
      val pkt = Array.fill[Byte](188)(0xAA.toByte)
      pkt(0) = 0x47.toByte
      pkt(1) = 0x00.toByte
      pkt(2) = 0x20.toByte
      pkt(3) = 0x30.toByte
      pkt(4) = 0.toByte
      val adjusted = FreezeFramePacer.stuffPacketPrefix(pkt, 0, 10)
      val _ = adjusted shouldBe true
      val _ = (pkt(4) & 0xFF) shouldBe 5
      val _ = pkt(5) shouldBe 0x00.toByte
      pkt.slice(6, 10).forall(_ == 0xFF.toByte) shouldBe true
    }
  }

  "FreezeFramePacer audio syncword & format detection" should {
    "detect AC-3 syncword and format" in {
      val pkt = Array.fill[Byte](188)(0x00.toByte)
      pkt(4) = 0x00.toByte
      pkt(5) = 0x00.toByte
      pkt(6) = 0x01.toByte
      pkt(7) = 0xBD.toByte
      pkt(10) = 0x80.toByte
      pkt(11) = 0x80.toByte
      pkt(12) = 5.toByte
      pkt(18) = 0x0B.toByte
      pkt(19) = 0x77.toByte
      pkt(22) = 0x04.toByte
      pkt(23) = 0x40.toByte
      pkt(24) = 0x40.toByte
      val _ = FreezeFramePacer.hasValidAudioSyncword(pkt, 4, 184, Some(0x81)) shouldBe true
      val fmt = FreezeFramePacer.extractAudioFormat(pkt, 4, 184, Some(0x81))
      fmt shouldBe Some(FreezeFramePacer.Ac3Format(0, 4, 8, 0, 2))
    }

    "detect ADTS AAC syncword and format" in {
      val pkt = Array.fill[Byte](188)(0x00.toByte)
      pkt(4) = 0x00.toByte
      pkt(5) = 0x00.toByte
      pkt(6) = 0x01.toByte
      pkt(7) = 0xC0.toByte
      pkt(10) = 0x80.toByte
      pkt(11) = 0x80.toByte
      pkt(12) = 5.toByte
      pkt(18) = 0xFF.toByte
      pkt(19) = 0xF1.toByte
      pkt(20) = 0x50.toByte
      pkt(21) = 0x80.toByte
      pkt(22) = 0x01.toByte
      pkt(23) = 0xBF.toByte
      pkt(24) = 0xFC.toByte
      val _ = FreezeFramePacer.hasValidAudioSyncword(pkt, 4, 184, Some(0x0F)) shouldBe true
      val fmt = FreezeFramePacer.extractAudioFormat(pkt, 4, 184, Some(0x0F))
      val _ = fmt.isDefined shouldBe true
      fmt.get.isInstanceOf[FreezeFramePacer.AdtsFormat] shouldBe true
    }
  }

  "FreezeFramePacer synthetic frame generators" should {
    def extractElementaryPayload(tsBytes: ByteString): Array[Byte] = {
      val b = ByteString.newBuilder
      val count = tsBytes.length / 188
      var i = 0
      while (i < count) {
        val pkt = tsBytes.slice(i * 188, (i + 1) * 188).toArray
        val pusi = (pkt(1) & 0x40) != 0
        val afc = (pkt(3) & 0x30) >> 4
        val payloadOff =
          if (afc == 1) 4
          else if (afc == 3) 5 + (pkt(4) & 0xFF)
          else 188
        if (pusi) {
          val pesOff = FreezeFramePacer.findPesHeaderOffset(pkt, 0)
          val esOff = pesOff + 9 + (pkt(pesOff + 8) & 0xFF)
          val _ = b.putBytes(pkt, esOff, 188 - esOff)
        } else if (payloadOff < 188) {
          val _ = b.putBytes(pkt, payloadOff, 188 - payloadOff)
        }
        i += 1
      }
      b.result().toArray
    }

    def collectStartCodeOffsets(es: Array[Byte]): Seq[(Int, Int)] = {
      val out = Vector.newBuilder[(Int, Int)]
      var i = 0
      while (i <= es.length - 4) {
        if (es(i) == 0x00.toByte && es(i + 1) == 0x00.toByte && es(i + 2) == 0x01.toByte) {
          out += ((i, es(i + 3) & 0xFF))
          i += 4
        } else {
          i += 1
        }
      }
      out.result()
    }

    "synthesize valid MPEG-2 video freeze frame packets" in {
      var cc = 0
      val nextCc = () => { val c = cc; cc = (cc + 1) & 0x0F; c }
      val bs = FreezeFramePacer.synthesizeVideoFreezeFrame(0x0100, None, 90000L, includePcr = true, 68, nextCc)
      val _ = (bs.length % 188) shouldBe 0
      val _ = (bs.length / 188) shouldBe 5
      val arr = bs.toArray
      val _ = arr(0) shouldBe 0x47.toByte
      (0 until 5).foreach { idx =>
        val _ = (arr(idx * 188 + 3) & 0x0F) shouldBe idx
      }
      val _ = FreezeFramePacer.readPcrBase(arr, 0) shouldBe Some(90000L)
      val pesOffset = FreezeFramePacer.findPesHeaderOffset(arr, 0)
      val _ = pesOffset should be >= 0
      val _ = FreezeFramePacer.readPtsDts(arr, pesOffset, 188) shouldBe Some((90000L, Some(90000L)))

      val es = extractElementaryPayload(bs)
      val codes = collectStartCodeOffsets(es)
      val _ = codes.take(2).map(_._2) shouldBe Seq(0x00, 0xB5)
      val picOff = codes.head._1
      val pictureCodingType = (es(picOff + 5) >> 3) & 0x07
      val _ = pictureCodingType shouldBe 2 // P-frame
      val extOff = codes(1)._1
      val extId = (es(extOff + 4) & 0xF0) >> 4
      val _ = extId shouldBe 8 // Picture_Coding_Extension (never 1 = sequence_extension)
      val pictureStructure = es(extOff + 6) & 0x03
      val framePredFrameDct = (es(extOff + 7) >> 6) & 0x01
      val _ = pictureStructure shouldBe 3
      val _ = framePredFrameDct shouldBe 1
      val sliceCodes = codes.drop(2)
      val _ = sliceCodes.map(_._2) shouldBe (1 to 68)
      FreezeFramePacer.hasOutOfBoundsMpeg2Slice(es, 0, es.length, 68) shouldBe false
    }

    "synthesize valid MPEG-2 freeze frames for 720p, 480i, and custom macroblock widths from lockedSeqHeader" in {
      var cc = 0
      val nextCc = () => { val c = cc; cc = (cc + 1) & 0x0F; c }
      val bs720p = FreezeFramePacer.synthesizeVideoFreezeFrame(0x0100, Some(0x02), 90000L, includePcr = false, 45, nextCc)
      val es720p = extractElementaryPayload(bs720p)
      val sliceCodes720p = collectStartCodeOffsets(es720p).drop(2)
      val _ = sliceCodes720p.map(_._2) shouldBe (1 to 45)
      val _ = FreezeFramePacer.hasOutOfBoundsMpeg2Slice(es720p, 0, es720p.length, 45) shouldBe false

      val bs480i = FreezeFramePacer.synthesizeVideoFreezeFrame(0x0100, Some(0x02), 90000L, includePcr = false, 30, nextCc)
      val es480i = extractElementaryPayload(bs480i)
      val sliceCodes480i = collectStartCodeOffsets(es480i).drop(2)
      val _ = sliceCodes480i.map(_._2) shouldBe (1 to 30)
      val _ = FreezeFramePacer.hasOutOfBoundsMpeg2Slice(es480i, 0, es480i.length, 30) shouldBe false

      // 1440x1080 subchannel sequence header (0x5A0 = 1440 -> 90 MBs wide)
      val seqHdr1440 = ByteString(0x00, 0x00, 0x01, 0xB3.toByte, 0x5A, 0x04, 0x38, 0x12, 0x34, 0x56, 0x78, 0x9A.toByte)
      val _ = FreezeFramePacer.extractMpeg2MacroblockWidth(seqHdr1440) shouldBe Some(90)
      val bs1440First = FreezeFramePacer.synthesizeVideoFreezeFrame(0x0100, Some(0x02), 90000L, includePcr = false, 68, nextCc, Some(seqHdr1440))
      val bs1440Second = FreezeFramePacer.synthesizeVideoFreezeFrame(0x0100, Some(0x02), 93003L, includePcr = false, 68, nextCc, Some(seqHdr1440))
      val es1440First = extractElementaryPayload(bs1440First)
      val es1440Second = extractElementaryPayload(bs1440Second)
      val _ = java.util.Arrays.equals(es1440First, es1440Second) shouldBe true
      collectStartCodeOffsets(es1440First).drop(2).map(_._2) shouldBe (1 to 68)
    }

    "synthesize valid H.264 video freeze frame packet" in {
      var cc = 3
      val nextCc = () => { val c = cc; cc = (cc + 1) & 0x0F; c }
      val bs = FreezeFramePacer.synthesizeVideoFreezeFrame(0x0100, Some(0x1B), 90000L, includePcr = false, 68, nextCc)
      val _ = bs.length shouldBe 188
      val arr = bs.toArray
      val _ = arr(0) shouldBe 0x47.toByte
      val _ = (arr(3) & 0x0F) shouldBe 3
      val pesOffset = FreezeFramePacer.findPesHeaderOffset(arr, 0)
      val _ = pesOffset should be >= 0
      val ptsDts = FreezeFramePacer.readPtsDts(arr, pesOffset, 188)
      ptsDts shouldBe Some((90000L, Some(90000L)))
    }

    "synthesize valid silent AC-3 audio frame with zero CRC-16 checks" in {
      var cc = 4
      val nextCc = () => { val c = cc; cc = (cc + 1) & 0x0F; c }
      val bs = FreezeFramePacer.synthesizeSilentAudioFrame(0x0101, Some(0x81), None, 180000L, nextCc)
      val _ = (bs.length % 188) shouldBe 0
      val _ = (bs.length / 188) shouldBe 2
      val arr = bs.toArray
      val _ = arr(0) shouldBe 0x47.toByte
      val _ = (arr(3) & 0x0F) shouldBe 4
      val _ = (arr(188 + 3) & 0x0F) shouldBe 5
      val pesOffset = FreezeFramePacer.findPesHeaderOffset(arr, 0)
      val _ = pesOffset should be >= 0
      val _ = FreezeFramePacer.readPtsDts(arr, pesOffset, 188) shouldBe Some((180000L, None))
      val es = pesOffset + 9 + 5
      val p1Payload = arr.slice(es, 188)
      val p2AfLen = if ((arr(188 + 3) & 0x20) != 0) (arr(188 + 4) & 0xFF) + 1 else 0
      val p2Payload = arr.slice(188 + 4 + p2AfLen, 188 + 4 + p2AfLen + (224 - p1Payload.length))
      val frameBytes = p1Payload ++ p2Payload
      val _ = FreezeFramePacer.ac3Crc16(frameBytes, 2, 138) shouldBe 0
      FreezeFramePacer.ac3Crc16(frameBytes, 2, 222) shouldBe 0
    }

    "synthesize valid silent ADTS audio frame" in {
      var cc = 0
      val nextCc = () => { val c = cc; cc = (cc + 1) & 0x0F; c }
      val bs = FreezeFramePacer.synthesizeSilentAudioFrame(0x0101, Some(0x0F), None, 90000L, nextCc)
      val _ = bs.length shouldBe 188
      val arr = bs.toArray
      val pesOffset = FreezeFramePacer.findPesHeaderOffset(arr, 0)
      val _ = pesOffset should be >= 0
      val _ = FreezeFramePacer.readPtsDts(arr, pesOffset, 188) shouldBe Some((90000L, None))
      val es = pesOffset + 9 + 5
      (arr(es) == 0xFF.toByte && (arr(es + 1) & 0xF6) == 0xF0) shouldBe true
    }
  }

  "MpegTsTypes" should {
    "provide zero-allocation Pid, ContinuityCounter, PtsTicks, and OptPts operations" in {
      import MpegTsTypes.{ContinuityCounter, OptPts, Pid, PtsTicks}
      val pkt = new Array[Byte](188)
      pkt(0) = 0x47.toByte
      pkt(1) = 0x41.toByte
      pkt(2) = 0x00.toByte
      pkt(3) = 0x1F.toByte

      val pid = Pid.fromPacket(pkt, 0)
      val _ = pid.value shouldBe 0x0100
      val _ = pid.isNull shouldBe false
      val _ = pid.isPat shouldBe false
      val _ = pid.isElementary(0x0030) shouldBe true
      val _ = Pid(Pid.Null).isNull shouldBe true
      val _ = Pid(Pid.Pat).isPat shouldBe true

      val cc = ContinuityCounter.fromPacket(pkt, 0)
      val _ = cc.value shouldBe 15
      val _ = cc.next.value shouldBe 0
      val _ = cc.isDiscontinuousAfter(14) shouldBe false
      val _ = cc.isDiscontinuousAfter(15) shouldBe false
      val _ = cc.isDiscontinuousAfter(12) shouldBe true

      val wrapped = PtsTicks(0x1FFFFFFFFL).advance(10L)
      val _ = wrapped.value shouldBe 9L
      val _ = wrapped.signedDiff(0x1FFFFFFFFL) shouldBe 10L
      val _ = PtsTicks(0x1FFFFFFFFL).signedDiff(9L) shouldBe -10L

      val empty = OptPts.Empty
      val _ = empty.isEmpty shouldBe true
      val _ = empty.isDefined shouldBe false
      val _ = empty.getOrElse(42L) shouldBe 42L
      val _ = empty.toOption shouldBe None

      val present = OptPts(12345L)
      val _ = present.isDefined shouldBe true
      val _ = present.get.value shouldBe 12345L
      present.toOption shouldBe Some(12345L)
    }
  }
}

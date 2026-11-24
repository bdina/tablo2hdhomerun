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

    "restore mutated 0xB3 sequence header when locked header is 22 bytes and live packet carries an intra quantiser matrix" in {
      val clean = Array.fill[Byte](96)(0x10.toByte)
      clean(0) = 0x00.toByte
      clean(1) = 0x00.toByte
      clean(2) = 0x01.toByte
      clean(3) = 0xB3.toByte
      clean(4) = 0x78.toByte
      clean(5) = 0x04.toByte
      clean(6) = 0x38.toByte
      clean(7) = 0x34.toByte
      clean(8) = 0x12.toByte
      clean(9) = 0x34.toByte
      clean(10) = 0x56.toByte
      clean(11) = 0x82.toByte
      clean(76) = 0x00.toByte
      clean(77) = 0x00.toByte
      clean(78) = 0x01.toByte
      clean(79) = 0xB5.toByte
      clean(80) = 0x14.toByte
      clean(81) = 0x82.toByte
      clean(82) = 0x00.toByte
      clean(83) = 0x01.toByte
      clean(84) = 0x00.toByte
      clean(85) = 0x00.toByte

      val lockedHdr = FreezeFramePacer.parseMpeg2SequenceHeader(clean, 0, clean.length).get._1
      val _ = lockedHdr.length shouldBe 22
      val _ = (lockedHdr(11) & 0x03) shouldBe 0

      val corrupted = clean.clone()
      corrupted(5) = 0x00.toByte
      corrupted(6) = 0x00.toByte
      corrupted(11) = 0x02.toByte // VBV upper bits corrupted, intra quantiser flag intact

      val res = FreezeFramePacer.sanitizeMpeg2PayloadDetailed(corrupted, 0, corrupted.length, 68, Some(lockedHdr))
      val _ = res.clampedSlices shouldBe 0
      val _ = res.restoredHeaders shouldBe 1
      val _ = ByteString.fromArray(corrupted, 0, 12) shouldBe ByteString.fromArray(clean, 0, 12)
      ByteString.fromArray(corrupted, 12, 64) shouldBe ByteString.fromArray(clean, 12, 64)
    }

    "skip non-intra-only and dual quantiser matrices accurately before clamping subsequent slices" in {
      // Non-intra quantiser matrix only (76 bytes total): stray 00 00 01 99 inside matrix must be skipped,
      // while 00 00 01 99 at offset 76 must be clamped.
      val nonIntra = Array.fill[Byte](84)(0x10.toByte)
      nonIntra(0) = 0x00.toByte
      nonIntra(1) = 0x00.toByte
      nonIntra(2) = 0x01.toByte
      nonIntra(3) = 0xB3.toByte
      nonIntra(4) = 0x78.toByte
      nonIntra(5) = 0x04.toByte
      nonIntra(6) = 0x38.toByte
      nonIntra(11) = 0x01.toByte // load_non_intra_quantiser_matrix = 1
      nonIntra(20) = 0x00.toByte
      nonIntra(21) = 0x00.toByte
      nonIntra(22) = 0x01.toByte
      nonIntra(23) = 0x99.toByte
      nonIntra(76) = 0x00.toByte
      nonIntra(77) = 0x00.toByte
      nonIntra(78) = 0x01.toByte
      nonIntra(79) = 0x99.toByte

      val nonIntraRes = FreezeFramePacer.sanitizeMpeg2PayloadDetailed(nonIntra, 0, nonIntra.length, 68, None)
      val _ = nonIntraRes.clampedSlices shouldBe 1
      val _ = nonIntra(22) shouldBe 0x01.toByte
      val _ = nonIntra(78) shouldBe 0x00.toByte

      // Dual intra + non-intra quantiser matrices (140 bytes total): stray 00 00 01 99 at offset 90 must be skipped,
      // while 00 00 01 99 at offset 140 must be clamped.
      val dual = Array.fill[Byte](148)(0x10.toByte)
      dual(0) = 0x00.toByte
      dual(1) = 0x00.toByte
      dual(2) = 0x01.toByte
      dual(3) = 0xB3.toByte
      dual(4) = 0x78.toByte
      dual(5) = 0x04.toByte
      dual(6) = 0x38.toByte
      dual(11) = 0x02.toByte // load_intra_quantiser_matrix = 1
      dual(75) = 0x11.toByte // bit 0 = load_non_intra_quantiser_matrix = 1
      dual(90) = 0x00.toByte
      dual(91) = 0x00.toByte
      dual(92) = 0x01.toByte
      dual(93) = 0x99.toByte
      dual(140) = 0x00.toByte
      dual(141) = 0x00.toByte
      dual(142) = 0x01.toByte
      dual(143) = 0x99.toByte

      val dualRes = FreezeFramePacer.sanitizeMpeg2PayloadDetailed(dual, 0, dual.length, 68, None)
      val _ = dualRes.clampedSlices shouldBe 1
      val _ = dual(92) shouldBe 0x01.toByte
      dual(142) shouldBe 0x00.toByte
    }

    "clear corrupted byte-11 quantiser flags when 0xB5 sequence_extension immediately follows 0xB3" in {
      val clean = Array[Byte](
        0x00.toByte, 0x00.toByte, 0x01.toByte, 0xB3.toByte,
        0x78.toByte, 0x04.toByte, 0x38.toByte, 0x34.toByte,
        0x12.toByte, 0x34.toByte, 0x56.toByte, 0x80.toByte,
        0x00.toByte, 0x00.toByte, 0x01.toByte, 0xB5.toByte,
        0x14.toByte, 0x82.toByte, 0x00.toByte, 0x01.toByte,
        0x00.toByte, 0x00.toByte,
        0x00.toByte, 0x00.toByte, 0x01.toByte, 0x99.toByte
      )
      val lockedHdr = FreezeFramePacer.parseMpeg2SequenceHeader(clean, 0, clean.length).get._1
      val corrupted = clean.clone()
      corrupted(11) = 0x82.toByte // bit-flip sets load_intra_quantiser_matrix despite immediate 0xB5 at offset 12

      val res = FreezeFramePacer.sanitizeMpeg2PayloadDetailed(corrupted, 0, corrupted.length, 68, Some(lockedHdr))
      val _ = res.restoredHeaders shouldBe 1
      val _ = res.clampedSlices shouldBe 1
      val _ = corrupted(11) shouldBe 0x80.toByte
      corrupted(24) shouldBe 0x00.toByte
    }

    "reject invalid 0xB5 sequence_extension fields in parseMpeg2SequenceHeader and fall back to 12-byte 0xB3" in {
      def buildHeaderWithSeqExt(b5: Byte, b6: Byte, b7: Byte): Array[Byte] =
        Array[Byte](
          0x00.toByte, 0x00.toByte, 0x01.toByte, 0xB3.toByte,
          0x78.toByte, 0x04.toByte, 0x38.toByte, 0x34.toByte,
          0x12.toByte, 0x34.toByte, 0x56.toByte, 0x80.toByte,
          0x00.toByte, 0x00.toByte, 0x01.toByte, 0xB5.toByte,
          0x14.toByte, b5, b6, b7,
          0x00.toByte, 0x00.toByte
        )

      val valid = buildHeaderWithSeqExt(0x82.toByte, 0x00.toByte, 0x01.toByte)
      val _ = FreezeFramePacer.parseMpeg2SequenceHeader(valid, 0, valid.length).get._1.length shouldBe 22

      // chroma_format = 4:2:2 (0x84 instead of 0x82)
      val badChroma = buildHeaderWithSeqExt(0x84.toByte, 0x00.toByte, 0x01.toByte)
      val _ = FreezeFramePacer.parseMpeg2SequenceHeader(badChroma, 0, badChroma.length).get._1.length shouldBe 12

      // vertical_size_extension = 1 (byte 6 bits 6..5 = 0x20)
      val badVertExt = buildHeaderWithSeqExt(0x82.toByte, 0x20.toByte, 0x01.toByte)
      val _ = FreezeFramePacer.parseMpeg2SequenceHeader(badVertExt, 0, badVertExt.length).get._1.length shouldBe 12

      // horizontal_size_extension = 1 (byte 6 bit 7 = 0x80)
      val badHorizExt = buildHeaderWithSeqExt(0x82.toByte, 0x80.toByte, 0x01.toByte)
      val _ = FreezeFramePacer.parseMpeg2SequenceHeader(badHorizExt, 0, badHorizExt.length).get._1.length shouldBe 12

      // marker_bit = 0 (byte 7 bit 0 = 0x00)
      val badMarker = buildHeaderWithSeqExt(0x82.toByte, 0x00.toByte, 0x00.toByte)
      FreezeFramePacer.parseMpeg2SequenceHeader(badMarker, 0, badMarker.length).get._1.length shouldBe 12
    }

    "restore corrupted 0xB5 1x sequence_extension after intra quantiser matrix while leaving 0xB5 8x/2x extensions untouched" in {
      // Simulate CBS 4.1: 12-byte 0xB3 + 64-byte intra matrix + 10-byte 0xB5 1x + 0xB5 2x + 0x00 + 0xB5 8x
      val clean = Array.fill[Byte](120)(0x10.toByte)
      clean(0) = 0x00.toByte
      clean(1) = 0x00.toByte
      clean(2) = 0x01.toByte
      clean(3) = 0xB3.toByte
      clean(4) = 0x78.toByte
      clean(5) = 0x04.toByte
      clean(6) = 0x38.toByte
      clean(7) = 0x34.toByte
      clean(8) = 0x12.toByte
      clean(9) = 0x34.toByte
      clean(10) = 0x56.toByte
      clean(11) = 0x82.toByte // load_intra_quantiser_matrix = 1
      // Sequence Extension (0xB5 1x) at offset 76: MP@HL, interlaced (progressive_sequence=0), 4:2:0, size_ext=0, marker=1
      clean(76) = 0x00.toByte
      clean(77) = 0x00.toByte
      clean(78) = 0x01.toByte
      clean(79) = 0xB5.toByte
      clean(80) = 0x14.toByte
      clean(81) = 0x82.toByte
      clean(82) = 0x00.toByte
      clean(83) = 0x01.toByte
      clean(84) = 0x00.toByte
      clean(85) = 0x00.toByte
      // Sequence Display Extension (0xB5 2x) at offset 86
      clean(86) = 0x00.toByte
      clean(87) = 0x00.toByte
      clean(88) = 0x01.toByte
      clean(89) = 0xB5.toByte
      clean(90) = 0x23.toByte
      clean(91) = 0x05.toByte
      // Picture Coding Extension (0xB5 8x) at offset 98
      clean(98) = 0x00.toByte
      clean(99) = 0x00.toByte
      clean(100) = 0x01.toByte
      clean(101) = 0xB5.toByte
      clean(102) = 0x8F.toByte
      clean(103) = 0xFF.toByte
      clean(104) = 0xF3.toByte
      clean(105) = 0x41.toByte
      clean(106) = 0x80.toByte

      val lockedHdr = FreezeFramePacer.parseMpeg2SequenceHeader(clean, 0, clean.length).get._1
      val _ = lockedHdr.length shouldBe 22

      // Case A: 08:14:09 bit-flip (progressive_sequence=1, chroma_format=4:2:2, vertical_size_extension=1, marker=0)
      val corrupted = clean.clone()
      corrupted(81) = 0x8C.toByte // progressive_sequence=1 (0x08) | chroma_format=4:2:2 (0x04)
      corrupted(82) = 0xA0.toByte // horizontal_size_ext LSB=1 | vertical_size_ext=1
      corrupted(83) = 0x00.toByte // marker_bit cleared

      val resA = FreezeFramePacer.sanitizeMpeg2PayloadDetailed(corrupted, 0, corrupted.length, 68, Some(lockedHdr))
      val _ = resA.clampedSlices shouldBe 0
      val _ = resA.restoredHeaders shouldBe 1
      val _ = ByteString.fromArray(corrupted, 76, 10) shouldBe ByteString.fromArray(clean, 76, 10)
      val _ = ByteString.fromArray(corrupted, 86, 6) shouldBe ByteString.fromArray(clean, 86, 6)
      val _ = ByteString.fromArray(corrupted, 98, 9) shouldBe ByteString.fromArray(clean, 98, 9)

      // Case B: bit-flip in extension_start_code_identifier nibble (0x14 -> 0x94) right after 0xB3
      val corruptedId = clean.clone()
      corruptedId(80) = 0x94.toByte
      corruptedId(81) = 0x8A.toByte
      val resB = FreezeFramePacer.sanitizeMpeg2PayloadDetailed(corruptedId, 0, corruptedId.length, 68, Some(lockedHdr))
      val _ = resB.restoredHeaders shouldBe 1
      val _ = ByteString.fromArray(corruptedId, 76, 10) shouldBe ByteString.fromArray(clean, 76, 10)

      // Case C: standalone 0xB5 1x in a PUSI=0 continuation payload (no 0xB3 in same packet)
      val continuation = Array[Byte](
        0xAA.toByte, 0xBB.toByte,
        0x00.toByte, 0x00.toByte, 0x01.toByte, 0xB5.toByte,
        0x14.toByte, 0x8C.toByte, 0x20.toByte, 0x00.toByte,
        0x00.toByte, 0x00.toByte
      )
      val resC = FreezeFramePacer.sanitizeMpeg2PayloadDetailed(continuation, 0, continuation.length, 68, Some(lockedHdr))
      val _ = resC.restoredHeaders shouldBe 1
      ByteString.fromArray(continuation, 2, 10) shouldBe lockedHdr.slice(12, 22)
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

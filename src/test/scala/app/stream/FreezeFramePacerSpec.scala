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
  }

  "FreezeFramePacer slice offset & packet prefix stuffing" should {
    "find first valid video slice offset for MPEG-2" in {
      val arr = Array[Byte](0xFF.toByte, 0xFE.toByte, 0x00.toByte, 0x00.toByte, 0x01.toByte, 0x14.toByte, 0x00.toByte)
      FreezeFramePacer.findFirstValidVideoSliceOffset(arr, 0, arr.length, Some(0x02), 68) shouldBe 2
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
  }

  "FreezeFramePacer audio syncword & format detection" should {
    "detect AC-3 syncword and format" in {
      val pkt = Array.fill[Byte](188)(0x00.toByte)
      pkt(4) = 0x00.toByte
      pkt(5) = 0x00.toByte
      pkt(6) = 0x01.toByte
      pkt(7) = 0xBD.toByte
      pkt(12) = 0x80.toByte
      pkt(13) = 0x80.toByte
      pkt(14) = 5.toByte
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
      pkt(12) = 0x80.toByte
      pkt(13) = 0x80.toByte
      pkt(14) = 5.toByte
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
    "synthesize valid MPEG-2 video freeze frame packets" in {
      var cc = 0
      val nextCc = () => { val c = cc; cc = (cc + 1) & 0x0F; c }
      val bs = FreezeFramePacer.synthesizeVideoFreezeFrame(0x0100, None, 90000L, includePcr = true, 68, nextCc)
      val _ = (bs.length % 188) shouldBe 0
      val _ = (bs.length / 188) shouldBe 3
      val arr = bs.toArray
      val _ = arr(0) shouldBe 0x47.toByte
      val _ = (arr(3) & 0x0F) shouldBe 0
      val _ = (arr(188 + 3) & 0x0F) shouldBe 1
      val _ = (arr(376 + 3) & 0x0F) shouldBe 2
      val _ = FreezeFramePacer.readPcrBase(arr, 0) shouldBe Some(90000L)
      val pesOffset = FreezeFramePacer.findPesHeaderOffset(arr, 0)
      val _ = pesOffset should be >= 0
      val ptsDts = FreezeFramePacer.readPtsDts(arr, pesOffset, 188)
      ptsDts shouldBe Some((90000L, Some(90000L)))
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
      val frameBytes = arr.slice(es, es + 224)
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
}

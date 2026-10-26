package app.stream

import org.apache.pekko.util.ByteString

import java.util.Arrays

object FreezeFramePacer {
  val PacketSize: Int = 188
  val PtsMask: Long = 0x1FFFFFFFFL
  val DefaultVideoStepTicks: Long = 3003L
  val Ac3StepTicks: Long = 2880L
  val AdtsStepTicks: Long = 1920L
  val MaxSinglePacketPtsJumpTicks: Long = 180000L
  val RebaseThresholdTicks: Long = 90000L
  val DefaultMaxSliceRow: Int = 68

  private val Crc16Poly: Int = 0x18005

  sealed trait AudioFormat
  final case class Ac3Format(fscod: Int, frmsizecod: Int, bsid: Int, bsmod: Int, acmod: Int) extends AudioFormat
  final case class AdtsFormat(headerBytes: ByteString) extends AudioFormat

  def findPesHeaderOffset(arr: Array[Byte], pktOffset: Int): Int = {
    if (pktOffset < 0 || pktOffset + PacketSize > arr.length) return -1
    if (arr(pktOffset) != 0x47.toByte) return -1
    val pusi = (arr(pktOffset + 1) & 0x40) != 0
    if (!pusi) return -1
    val afc = (arr(pktOffset + 3) & 0x30) >> 4
    val payloadOffset = if (afc == 1) {
      pktOffset + 4
    } else if (afc == 3) {
      val afLen = arr(pktOffset + 4) & 0xFF
      pktOffset + 5 + afLen
    } else {
      -1
    }
    if (payloadOffset < 0 || payloadOffset + 9 > pktOffset + PacketSize) return -1
    if (arr(payloadOffset) == 0x00.toByte &&
        arr(payloadOffset + 1) == 0x00.toByte &&
        arr(payloadOffset + 2) == 0x01.toByte) {
      val streamId = arr(payloadOffset + 3) & 0xFF
      val isVideo = streamId >= 0xE0 && streamId <= 0xEF
      val isAudio = streamId == 0xBD || (streamId >= 0xC0 && streamId <= 0xDF)
      if (isVideo || isAudio) payloadOffset else -1
    } else {
      -1
    }
  }

  def readPtsDts(arr: Array[Byte], pesOffset: Int, pktEnd: Int): Option[(Long, Option[Long])] = {
    if (pesOffset < 0 || pesOffset + 9 > pktEnd) return None
    if (arr(pesOffset) != 0x00.toByte ||
        arr(pesOffset + 1) != 0x00.toByte ||
        arr(pesOffset + 2) != 0x01.toByte) return None

    val flags2 = arr(pesOffset + 7) & 0xFF
    val headerDataLen = arr(pesOffset + 8) & 0xFF
    val ptsDtsFlags = (flags2 >> 6) & 0x03

    if (ptsDtsFlags == 2 && headerDataLen >= 5 && pesOffset + 14 <= pktEnd) {
      val pts = decodePtsBytes(arr, pesOffset + 9)
      Some((pts, None))
    } else if (ptsDtsFlags == 3 && headerDataLen >= 10 && pesOffset + 19 <= pktEnd) {
      val pts = decodePtsBytes(arr, pesOffset + 9)
      val dts = decodePtsBytes(arr, pesOffset + 14)
      Some((pts, Some(dts)))
    } else {
      None
    }
  }

  def writePtsDts(arr: Array[Byte], pesOffset: Int, pktEnd: Int, pts: Long, dtsOpt: Option[Long]): Unit = {
    if (pesOffset < 0 || pesOffset + 9 > pktEnd) return
    val flags2 = arr(pesOffset + 7) & 0xFF
    val headerDataLen = arr(pesOffset + 8) & 0xFF
    val ptsDtsFlags = (flags2 >> 6) & 0x03

    if (ptsDtsFlags == 2 && headerDataLen >= 5 && pesOffset + 14 <= pktEnd) {
      encodePtsBytes(arr, pesOffset + 9, 2, pts & PtsMask)
    } else if (ptsDtsFlags == 3 && headerDataLen >= 10 && pesOffset + 19 <= pktEnd) {
      encodePtsBytes(arr, pesOffset + 9, 3, pts & PtsMask)
      encodePtsBytes(arr, pesOffset + 14, 1, dtsOpt.getOrElse(pts) & PtsMask)
    }
  }

  def encodePtsBytes(arr: Array[Byte], offset: Int, prefixNibble: Int, ts: Long): Unit = {
    val b0 = ((prefixNibble & 0x0F) << 4) | (((ts >> 29) & 0x0E).toInt) | 0x01
    val b1 = ((ts >> 22) & 0xFF).toInt
    val b2 = (((ts >> 14) & 0xFE).toInt) | 0x01
    val b3 = ((ts >> 7) & 0xFF).toInt
    val b4 = (((ts << 1) & 0xFE).toInt) | 0x01
    arr(offset) = b0.toByte
    arr(offset + 1) = b1.toByte
    arr(offset + 2) = b2.toByte
    arr(offset + 3) = b3.toByte
    arr(offset + 4) = b4.toByte
  }

  def decodePtsBytes(arr: Array[Byte], offset: Int): Long = {
    val b0 = arr(offset) & 0xFFL
    val b1 = arr(offset + 1) & 0xFFL
    val b2 = arr(offset + 2) & 0xFFL
    val b3 = arr(offset + 3) & 0xFFL
    val b4 = arr(offset + 4) & 0xFFL
    val pts = ((b0 & 0x0EL) << 29) | (b1 << 22) | ((b2 & 0xFEL) << 14) | (b3 << 7) | ((b4 & 0xFEL) >>> 1)
    pts & PtsMask
  }

  def readPcrBase(arr: Array[Byte], pktOffset: Int): Option[Long] = {
    if (pktOffset < 0 || pktOffset + 12 > arr.length) return None
    val afc = (arr(pktOffset + 3) & 0x30) >> 4
    if (afc == 2 || afc == 3) {
      val afLen = arr(pktOffset + 4) & 0xFF
      if (afLen >= 7 && (arr(pktOffset + 5) & 0x10) != 0) {
        val b6 = arr(pktOffset + 6) & 0xFFL
        val b7 = arr(pktOffset + 7) & 0xFFL
        val b8 = arr(pktOffset + 8) & 0xFFL
        val b9 = arr(pktOffset + 9) & 0xFFL
        val b10 = arr(pktOffset + 10) & 0x80L
        val pcrBase = (b6 << 25) | (b7 << 17) | (b8 << 9) | (b9 << 1) | (b10 >>> 7)
        Some(pcrBase & PtsMask)
      } else {
        None
      }
    } else {
      None
    }
  }

  def writePcrBase(arr: Array[Byte], pktOffset: Int, pcrBase: Long): Unit = {
    val masked = pcrBase & PtsMask
    arr(pktOffset + 6) = ((masked >> 25) & 0xFF).toByte
    arr(pktOffset + 7) = ((masked >> 17) & 0xFF).toByte
    arr(pktOffset + 8) = ((masked >> 9) & 0xFF).toByte
    arr(pktOffset + 9) = ((masked >> 1) & 0xFF).toByte
    arr(pktOffset + 10) = (((masked & 0x01L) << 7) | 0x7E).toByte
    arr(pktOffset + 11) = 0.toByte
  }

  def pcrOnlyPacket(pid: Int, cc: Int, pcrBase: Long): Array[Byte] = {
    val arr = Array.fill[Byte](PacketSize)(0xFF.toByte)
    arr(0) = 0x47.toByte
    arr(1) = ((pid >> 8) & 0x1F).toByte
    arr(2) = (pid & 0xFF).toByte
    arr(3) = (0x20 | (cc & 0x0F)).toByte
    arr(4) = 183.toByte
    arr(5) = 0x10.toByte
    writePcrBase(arr, 0, pcrBase)
    arr
  }

  def parseMpeg2SequenceHeader(arr: Array[Byte], offset: Int, len: Int): Option[(ByteString, Int)] = {
    if (offset < 0 || len < 12 || offset + len > arr.length) return None
    val end = offset + len - 12
    var i = offset
    var result: Option[(ByteString, Int)] = None
    while (i <= end && result.isEmpty) {
      if (arr(i) == 0x00.toByte &&
          arr(i + 1) == 0x00.toByte &&
          arr(i + 2) == 0x01.toByte &&
          (arr(i + 3) & 0xFF) == 0xB3) {
        val horizontalSize = ((arr(i + 4) & 0xFF) << 4) | ((arr(i + 5) & 0xF0) >> 4)
        val verticalSize = ((arr(i + 5) & 0x0F) << 8) | (arr(i + 6) & 0xFF)
        if (horizontalSize >= 160 && horizontalSize <= 1920 &&
            verticalSize >= 120 && verticalSize <= 1088) {
          val maxSliceRow = math.max(1, math.min(175, (verticalSize + 15) / 16))
          result = Some((ByteString.fromArray(arr, i, 12), maxSliceRow))
        }
      }
      i += 1
    }
    result
  }

  def sanitizeMpeg2Payload(
      arr: Array[Byte],
      payloadOffset: Int,
      payloadLen: Int,
      maxSliceRow: Int,
      lockedSeqHeader: Option[ByteString]
  ): Int = {
    if (payloadOffset < 0 || payloadLen < 4 || payloadOffset + payloadLen > arr.length) return 0
    val end = payloadOffset + payloadLen
    var i = payloadOffset
    var clamped = 0
    while (i <= end - 4) {
      if (arr(i) == 0x00.toByte &&
          arr(i + 1) == 0x00.toByte &&
          arr(i + 2) == 0x01.toByte) {
        val code = arr(i + 3) & 0xFF
        if (code == 0xB3) {
          if (i + 12 <= end && lockedSeqHeader.isDefined) {
            val locked = lockedSeqHeader.get
            if (locked.length == 12) {
              var differs = false
              var k = 0
              while (k < 12) {
                if (arr(i + k) != locked(k)) differs = true
                k += 1
              }
              if (differs) {
                locked.copyToArray(arr, i)
              }
            }
          }
          i += 4
        } else if (code >= 0x01 && code <= 0xAF && code > maxSliceRow) {
          arr(i + 2) = 0x00.toByte
          clamped += 1
          i += 3
        } else {
          i += 1
        }
      } else {
        i += 1
      }
    }
    clamped
  }

  def findFirstValidVideoSliceOffset(
      arr: Array[Byte],
      payloadOffset: Int,
      payloadLen: Int,
      videoStreamType: Option[Int],
      maxSliceRow: Int
  ): Int = {
    if (payloadOffset < 0 || payloadLen < 4 || payloadOffset + payloadLen > arr.length) return -1
    val isH264 = videoStreamType.contains(0x1B)
    val isMpeg1Or2 = videoStreamType.isEmpty || videoStreamType.contains(0x01) || videoStreamType.contains(0x02)
    if (!isH264 && !isMpeg1Or2) return -1

    val end = payloadOffset + payloadLen - 4
    var i = payloadOffset
    var found = -1
    while (i <= end && found < 0) {
      if (arr(i) == 0x00.toByte &&
          arr(i + 1) == 0x00.toByte &&
          arr(i + 2) == 0x01.toByte) {
        val code = arr(i + 3) & 0xFF
        if (isMpeg1Or2) {
          if ((code >= 0x01 && code <= maxSliceRow) || code == 0xB3 || code == 0xB8 || code == 0x00) {
            found = i
          } else {
            i += 1
          }
        } else {
          val nalType = code & 0x1F
          val forbiddenBit = (code & 0x80) == 0
          if (forbiddenBit && (nalType == 1 || nalType == 5 || nalType == 7 || nalType == 8 || nalType == 9)) {
            found = i
          } else {
            i += 1
          }
        }
      } else {
        i += 1
      }
    }
    found
  }

  def stuffPacketPrefix(arr: Array[Byte], pktOffset: Int, keepFromOffset: Int): Boolean = {
    if (pktOffset < 0 || pktOffset + PacketSize > arr.length) return false
    if (arr(pktOffset) != 0x47.toByte) return false
    val afc = (arr(pktOffset + 3) & 0x30) >> 4
    if (afc != 1 && afc != 3) return false

    val oldPayloadOffset = if (afc == 1) {
      pktOffset + 4
    } else {
      val oldAfLen = arr(pktOffset + 4) & 0xFF
      pktOffset + 5 + oldAfLen
    }

    if (keepFromOffset <= oldPayloadOffset || keepFromOffset >= pktOffset + PacketSize) return false

    if (afc == 1) {
      val stuffBytes = keepFromOffset - (pktOffset + 4)
      if (stuffBytes == 1) {
        arr(pktOffset + 3) = ((arr(pktOffset + 3) & 0xCF) | 0x30).toByte
        arr(pktOffset + 4) = 0.toByte
        true
      } else {
        val afLen = stuffBytes - 1
        arr(pktOffset + 3) = ((arr(pktOffset + 3) & 0xCF) | 0x30).toByte
        arr(pktOffset + 4) = afLen.toByte
        arr(pktOffset + 5) = 0x00.toByte
        if (afLen > 1) {
          Arrays.fill(arr, pktOffset + 6, keepFromOffset, 0xFF.toByte)
        }
        true
      }
    } else {
      val oldAfLen = arr(pktOffset + 4) & 0xFF
      val delta = keepFromOffset - oldPayloadOffset
      val newAfLen = oldAfLen + delta
      if (newAfLen > 182) return false
      arr(pktOffset + 4) = newAfLen.toByte
      Arrays.fill(arr, oldPayloadOffset, keepFromOffset, 0xFF.toByte)
      true
    }
  }

  def hasValidAudioSyncword(
      arr: Array[Byte],
      payloadOffset: Int,
      payloadLen: Int,
      streamType: Option[Int]
  ): Boolean = {
    if (payloadOffset < 0 || payloadLen < 9 || payloadOffset + payloadLen > arr.length) return false
    if (arr(payloadOffset) != 0x00.toByte ||
        arr(payloadOffset + 1) != 0x00.toByte ||
        arr(payloadOffset + 2) != 0x01.toByte) return false

    val streamId = arr(payloadOffset + 3) & 0xFF
    val isAudioStreamId = streamId == 0xBD || (streamId >= 0xC0 && streamId <= 0xDF)
    if (!isAudioStreamId) return false

    val pesHeaderDataLen = arr(payloadOffset + 8) & 0xFF
    val esOffset = payloadOffset + 9 + pesHeaderDataLen
    val end = payloadOffset + payloadLen - 2
    if (esOffset > end) return false

    val isAc3 = streamType.contains(0x81) || streamType.contains(0x87) || streamType.contains(0x06)
    val isAdts = streamType.contains(0x0F) || streamType.contains(0x11)

    var i = esOffset
    var matched = false
    while (i <= end && !matched) {
      val b0 = arr(i)
      val b1 = arr(i + 1)
      val matchesAc3 = b0 == 0x0B.toByte && b1 == 0x77.toByte
      val matchesAdts = b0 == 0xFF.toByte && ((b1 & 0xF6) == 0xF0)
      if (isAc3) {
        if (matchesAc3) matched = true else i += 1
      } else if (isAdts) {
        if (matchesAdts) matched = true else i += 1
      } else {
        if (matchesAc3 || matchesAdts) matched = true else i += 1
      }
    }
    matched
  }

  def extractAudioFormat(
      arr: Array[Byte],
      payloadOffset: Int,
      payloadLen: Int,
      streamType: Option[Int]
  ): Option[AudioFormat] = {
    if (payloadOffset < 0 || payloadLen < 9 || payloadOffset + payloadLen > arr.length) return None
    if (arr(payloadOffset) != 0x00.toByte ||
        arr(payloadOffset + 1) != 0x00.toByte ||
        arr(payloadOffset + 2) != 0x01.toByte) return None

    val streamId = arr(payloadOffset + 3) & 0xFF
    val isAudioStreamId = streamId == 0xBD || (streamId >= 0xC0 && streamId <= 0xDF)
    if (!isAudioStreamId) return None

    val pesHeaderDataLen = arr(payloadOffset + 8) & 0xFF
    val es = payloadOffset + 9 + pesHeaderDataLen
    if (es + 7 > payloadOffset + payloadLen) return None

    val isNotAdts = !streamType.exists(st => st == 0x0F || st == 0x11)
    val isNotAc3 = !streamType.exists(st => st == 0x81 || st == 0x87 || st == 0x06)

    if (isNotAdts && arr(es) == 0x0B.toByte && arr(es + 1) == 0x77.toByte) {
      val b4 = arr(es + 4) & 0xFF
      val b5 = arr(es + 5) & 0xFF
      val b6 = arr(es + 6) & 0xFF
      val fscod = (b4 >> 6) & 0x03
      val frmsizecod = b4 & 0x3F
      val bsid = (b5 >> 3) & 0x1F
      val bsmod = b5 & 0x07
      val acmod = (b6 >> 5) & 0x07
      if (fscod < 3 && frmsizecod < 38) {
        Some(Ac3Format(fscod, frmsizecod, bsid, bsmod, acmod))
      } else {
        None
      }
    } else if (isNotAc3 && arr(es) == 0xFF.toByte && ((arr(es + 1) & 0xF6) == 0xF0)) {
      Some(AdtsFormat(ByteString.fromArray(arr, es, 7)))
    } else {
      None
    }
  }

  def ac3Crc16(data: Array[Byte], offset: Int, length: Int): Int = {
    var crc = 0
    val end = offset + length
    var i = offset
    while (i < end) {
      val b = data(i) & 0xFF
      var bit = 7
      while (bit >= 0) {
        val msb = (crc >> 15) & 1
        val bitVal = (b >> bit) & 1
        crc = (crc << 1) & 0xFFFF
        if ((msb ^ bitVal) != 0) {
          crc ^= 0x8005
        }
        bit -= 1
      }
      i += 1
    }
    crc
  }

  private def mulPoly(aInit: Int, bInit: Int, poly: Int): Int = {
    var a = aInit
    var b = bInit
    var c = 0
    while (a != 0) {
      if ((a & 1) != 0) {
        c ^= b
      }
      a >>= 1
      b <<= 1
      if ((b & (1 << 16)) != 0) {
        b ^= poly
      }
    }
    c
  }

  private def powPoly(aInit: Int, nInit: Int, poly: Int): Int = {
    var a = aInit
    var n = nInit
    var r = 1
    while (n > 0) {
      if ((n & 1) != 0) {
        r = mulPoly(r, a, poly)
      }
      a = mulPoly(a, a, poly)
      n >>= 1
    }
    r
  }

  private def packetizePes(
      pid: Int,
      pesBytes: Array[Byte],
      pcrBaseOpt: Option[Long],
      nextCc: () => Int
  ): ByteString = {
    val b = ByteString.newBuilder
    var offset = 0
    val totalPesLen = pesBytes.length
    var isFirst = true

    while (offset < totalPesLen) {
      val pkt = Array.fill[Byte](PacketSize)(0xFF.toByte)
      val cc = nextCc() & 0x0F
      pkt(0) = 0x47.toByte
      val pusiBit = if (isFirst) 0x40 else 0x00
      pkt(1) = (pusiBit | ((pid >> 8) & 0x1F)).toByte
      pkt(2) = (pid & 0xFF).toByte

      val remainingPes = totalPesLen - offset

      if (isFirst && pcrBaseOpt.isDefined) {
        val pcrBase = pcrBaseOpt.get
        if (remainingPes >= 176) {
          pkt(3) = (0x30 | cc).toByte
          pkt(4) = 7.toByte
          pkt(5) = 0x10.toByte
          writePcrBase(pkt, 0, pcrBase)
          System.arraycopy(pesBytes, offset, pkt, 12, 176)
          offset += 176
        } else {
          pkt(3) = (0x30 | cc).toByte
          val afLen = 183 - remainingPes
          pkt(4) = afLen.toByte
          pkt(5) = 0x10.toByte
          writePcrBase(pkt, 0, pcrBase)
          System.arraycopy(pesBytes, offset, pkt, 5 + afLen, remainingPes)
          offset += remainingPes
        }
      } else {
        if (remainingPes >= 184) {
          pkt(3) = (0x10 | cc).toByte
          System.arraycopy(pesBytes, offset, pkt, 4, 184)
          offset += 184
        } else {
          val stuffBytes = 184 - remainingPes
          if (stuffBytes == 1) {
            pkt(3) = (0x30 | cc).toByte
            pkt(4) = 0.toByte
            System.arraycopy(pesBytes, offset, pkt, 5, remainingPes)
            offset += remainingPes
          } else {
            pkt(3) = (0x30 | cc).toByte
            val afLen = stuffBytes - 1
            pkt(4) = afLen.toByte
            pkt(5) = 0x00.toByte
            System.arraycopy(pesBytes, offset, pkt, 5 + afLen, remainingPes)
            offset += remainingPes
          }
        }
      }
      val _ = b ++= ByteString.fromArray(pkt)
      isFirst = false
    }
    b.result()
  }

  def synthesizeVideoFreezeFrame(
      videoPid: Int,
      videoStreamType: Option[Int],
      pts: Long,
      includePcr: Boolean,
      maxSliceRow: Int,
      nextCc: () => Int
  ): ByteString = {
    val isH264 = videoStreamType.contains(0x1B)

    val videoPayload: Array[Byte] = if (isH264) {
      val aud = Array[Byte](0x00.toByte, 0x00.toByte, 0x01.toByte, 0x09.toByte, 0x30.toByte, 0x00.toByte)
      val slice = Array[Byte](0x00.toByte, 0x00.toByte, 0x01.toByte, 0x01.toByte, 0xE0.toByte, 0x00.toByte)
      val out = new Array[Byte](aud.length + slice.length)
      System.arraycopy(aud, 0, out, 0, aud.length)
      System.arraycopy(slice, 0, out, aud.length, slice.length)
      out
    } else {
      val picHdr = Array[Byte](
        0x00.toByte, 0x00.toByte, 0x01.toByte, 0x00.toByte,
        0x00.toByte, 0x17.toByte, 0xFF.toByte, 0xF8.toByte
      )
      val picExt = Array[Byte](
        0x00.toByte, 0x00.toByte, 0x01.toByte, 0xB5.toByte,
        0x11.toByte, 0x1F.toByte, 0xF3.toByte, 0x41.toByte, 0x80.toByte
      )
      val sliceRows = math.max(1, math.min(maxSliceRow, 68))
      val out = new Array[Byte](picHdr.length + picExt.length + (sliceRows * 6))
      System.arraycopy(picHdr, 0, out, 0, picHdr.length)
      var writePos = picHdr.length
      System.arraycopy(picExt, 0, out, writePos, picExt.length)
      writePos += picExt.length
      var row = 1
      while (row <= sliceRows) {
        out(writePos) = 0x00.toByte
        out(writePos + 1) = 0x00.toByte
        out(writePos + 2) = 0x01.toByte
        out(writePos + 3) = row.toByte
        out(writePos + 4) = 0x38.toByte
        out(writePos + 5) = 0x00.toByte
        writePos += 6
        row += 1
      }
      out
    }

    val pesHeaderLen = 19
    val pesTotalLen = pesHeaderLen + videoPayload.length
    val pes = new Array[Byte](pesTotalLen)
    pes(0) = 0x00.toByte
    pes(1) = 0x00.toByte
    pes(2) = 0x01.toByte
    pes(3) = 0xE0.toByte

    val pesPayloadLen = 3 + 10 + videoPayload.length
    if (pesPayloadLen <= 0xFFFF) {
      pes(4) = ((pesPayloadLen >> 8) & 0xFF).toByte
      pes(5) = (pesPayloadLen & 0xFF).toByte
    } else {
      pes(4) = 0.toByte
      pes(5) = 0.toByte
    }
    pes(6) = 0x80.toByte
    pes(7) = 0xC0.toByte
    pes(8) = 10.toByte
    encodePtsBytes(pes, 9, 3, pts & PtsMask)
    encodePtsBytes(pes, 14, 1, pts & PtsMask)
    System.arraycopy(videoPayload, 0, pes, 19, videoPayload.length)

    val pcrOpt = if (includePcr) Some(pts) else None
    packetizePes(videoPid, pes, pcrOpt, nextCc)
  }

  def synthesizeSilentAudioFrame(
      audioPid: Int,
      streamType: Option[Int],
      formatOpt: Option[AudioFormat],
      pts: Long,
      nextCc: () => Int
  ): ByteString = {
    val isAdts = streamType.contains(0x0F) || streamType.contains(0x11) || formatOpt.exists(_.isInstanceOf[AdtsFormat])

    if (isAdts) {
      val adtsPayload = Array[Byte](0x21.toByte, 0x10.toByte, 0x04.toByte, 0x60.toByte, 0x8C.toByte, 0x1C.toByte)
      val adtsHeader = formatOpt match {
        case Some(AdtsFormat(raw)) if raw.length >= 7 =>
          val copy = raw.take(7).toArray
          copy(3) = ((copy(3) & 0xFC) | ((13 >> 11) & 0x03)).toByte
          copy(4) = ((13 >> 3) & 0xFF).toByte
          copy(5) = ((copy(5) & 0x1F) | ((13 & 0x07) << 5)).toByte
          copy
        case _ =>
          Array[Byte](0xFF.toByte, 0xF1.toByte, 0x50.toByte, 0x80.toByte, 0x01.toByte, 0xBF.toByte, 0xFC.toByte)
      }
      val frame = new Array[Byte](13)
      System.arraycopy(adtsHeader, 0, frame, 0, 7)
      System.arraycopy(adtsPayload, 0, frame, 7, 6)

      val pes = new Array[Byte](6 + 3 + 5 + 13)
      pes(0) = 0x00.toByte
      pes(1) = 0x00.toByte
      pes(2) = 0x01.toByte
      pes(3) = 0xC0.toByte
      val pesPayloadLen = 3 + 5 + 13
      pes(4) = ((pesPayloadLen >> 8) & 0xFF).toByte
      pes(5) = (pesPayloadLen & 0xFF).toByte
      pes(6) = 0x80.toByte
      pes(7) = 0x80.toByte
      pes(8) = 5.toByte
      encodePtsBytes(pes, 9, 2, pts & PtsMask)
      System.arraycopy(frame, 0, pes, 14, 13)

      packetizePes(audioPid, pes, None, nextCc)
    } else {
      val frame = new Array[Byte](224)
      frame(0) = 0x0B.toByte
      frame(1) = 0x77.toByte
      frame(4) = 0x04.toByte
      frame(5) = 0x40.toByte
      val acmod = formatOpt match {
        case Some(Ac3Format(_, _, _, _, a)) => a
        case _ => 2
      }
      frame(6) = ((acmod & 0x07) << 5).toByte

      val c1 = ac3Crc16(frame, 4, 140 - 4)
      val crcInv = powPoly(Crc16Poly >> 1, (8 * 140) - 16, Crc16Poly)
      val crc1 = mulPoly(crcInv, c1, Crc16Poly)
      frame(2) = ((crc1 >> 8) & 0xFF).toByte
      frame(3) = (crc1 & 0xFF).toByte

      val crc2 = ac3Crc16(frame, 2, 224 - 4)
      frame(222) = ((crc2 >> 8) & 0xFF).toByte
      frame(223) = (crc2 & 0xFF).toByte

      val pes = new Array[Byte](6 + 3 + 5 + 224)
      pes(0) = 0x00.toByte
      pes(1) = 0x00.toByte
      pes(2) = 0x01.toByte
      pes(3) = 0xBD.toByte
      val pesPayloadLen = 3 + 5 + 224
      pes(4) = ((pesPayloadLen >> 8) & 0xFF).toByte
      pes(5) = (pesPayloadLen & 0xFF).toByte
      pes(6) = 0x80.toByte
      pes(7) = 0x80.toByte
      pes(8) = 5.toByte
      encodePtsBytes(pes, 9, 2, pts & PtsMask)
      System.arraycopy(frame, 0, pes, 14, 224)

      packetizePes(audioPid, pes, None, nextCc)
    }
  }
}

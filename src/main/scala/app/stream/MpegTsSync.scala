package app.stream

import org.apache.pekko.NotUsed
import org.apache.pekko.stream.{Attributes, FlowShape, Inlet, Outlet}
import org.apache.pekko.stream.scaladsl.{Flow, Source}
import org.apache.pekko.stream.stage.{GraphStage, GraphStageLogic, InHandler, OutHandler}
import org.apache.pekko.util.ByteString

import java.util.concurrent.atomic.AtomicReference

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.{Failure, Success}

object MpegTsSync {
  val PacketSize: Int = 188
  val NullPid: Int = 0x1FFF
  val PatPid: Int = 0x0000

  // 188-byte MPEG-TS packet with sync byte 0x47, Null PID 0x1FFF, adaptation field only (0x20),
  // adaptation_field_length 183, discontinuity_indicator = 1 (0x80), and 182 0xFF stuffing bytes.
  val MPEGTS_DISCONTINUITY_PACKET: ByteString = {
    val arr = Array.fill[Byte](PacketSize)(0xFF.toByte)
    arr(0) = 0x47.toByte
    arr(1) = 0x1F.toByte
    arr(2) = 0xFF.toByte
    arr(3) = 0x20.toByte // adaptation field only, CC = 0
    arr(4) = 183.toByte  // adaptation field length (188 - 5)
    arr(5) = 0x80.toByte // discontinuity_indicator = 1
    ByteString(arr)
  }

  // 188-byte standard MPEG-TS null/stuffing packet (ISO/IEC 13818-1) with sync byte 0x47,
  // Null PID 0x1FFF, payload only (0x10), CC = 0, and 184 0xFF payload bytes.
  val MPEGTS_NULL_PACKET: ByteString = {
    val arr = Array.fill[Byte](PacketSize)(0xFF.toByte)
    arr(0) = 0x47.toByte
    arr(1) = 0x1F.toByte
    arr(2) = 0xFF.toByte
    arr(3) = 0x10.toByte // payload only, CC = 0
    ByteString(arr)
  }

  def preRollNullChunk(packetCount: Int = 7): ByteString = {
    val count = math.max(1, packetCount)
    val single = MPEGTS_NULL_PACKET
    val b = ByteString.newBuilder
    var i = 0
    while (i < count) {
      b ++= single
      i += 1
    }
    b.result()
  }

  /** Emits only whole 188-byte packets. A trailing partial packet is dropped on completion
    * (e.g. a short ranged read during a reception drop); missing data is acceptable. */
  def alignPackets: Flow[ByteString, ByteString, NotUsed] =
    Flow[ByteString]
      .statefulMap(() => ByteString.empty)(
        (carry, chunk) => {
          val all = carry ++ chunk
          val whole = (all.length / PacketSize) * PacketSize
          (all.drop(whole), all.take(whole))
        }
      , _ => None
      )
      .filter(_.nonEmpty)

  def isValidPacketHeader(arr: Array[Byte], offset: Int, len: Int): Boolean =
    offset >= 0 &&
    offset + PacketSize <= len &&
    arr(offset) == 0x47.toByte &&
    (arr(offset + 3) & 0xC0) == 0 && {
      val pid = ((arr(offset + 1) & 0x1F) << 8) | (arr(offset + 2) & 0xFF)
      (pid < 0x0002 || pid >= 0x0010) && {
        val afc = (arr(offset + 3) & 0x30) >> 4
        afc match {
          case 1 => true
          case 2 => (arr(offset + 4) & 0xFF) <= 183
          case 3 => (arr(offset + 4) & 0xFF) <= 182
          case _ => false
        }
      }
    }

  def findNextSync(arr: Array[Byte], start: Int, len: Int): Int = {
    var scan = math.max(0, start)
    var found = -1
    // Pass 1: Look for confirmed sync with 2 consecutive valid packet headers when buffer space permits
    while (scan + 2 * PacketSize <= len && found < 0) {
      if (isValidPacketHeader(arr, scan, len) && isValidPacketHeader(arr, scan + PacketSize, len)) {
        found = scan
      } else {
        scan += 1
      }
    }
    // Pass 2: Fall back to single packet header if buffer has no consecutive pairs (e.g. short buffer or isolated packet)
    if (found < 0) {
      scan = math.max(0, start)
      while (scan + PacketSize <= len && found < 0) {
        if (isValidPacketHeader(arr, scan, len)) {
          found = scan
        } else {
          scan += 1
        }
      }
    }
    found
  }

  def withPreRollKeepAlive(
    realSourceFuture: Future[Source[ByteString, NotUsed]]
  , interval: FiniteDuration = 100.millis
  , chunkPackets: Int = 7
  ): Source[ByteString, NotUsed] = {
    realSourceFuture.value match {
      case Some(Success(source)) => source
      case Some(Failure(ex)) => Source.failed(ex)
      case None =>
        val chunk = preRollNullChunk(chunkPackets)
        val preRollSource: Source[ByteString, NotUsed] =
          Source.tick(0.millis, interval, chunk)
            .takeWhile { _ =>
              realSourceFuture.value match {
                case None => true
                case Some(Success(_)) => false
                case Some(Failure(ex)) => throw ex
              }
            }
            .mapMaterializedValue(_ => NotUsed)

        val delayedRealSource = Source.futureSource(realSourceFuture)
        preRollSource
          .concat(delayedRealSource)
    }
  }

  def discontinuityPacket(pid: Int, cc: Int = 0): ByteString = {
    val arr = Array.fill[Byte](PacketSize)(0xFF.toByte)
    arr(0) = 0x47.toByte
    arr(1) = ((pid >> 8) & 0x1F).toByte
    arr(2) = (pid & 0xFF).toByte
    arr(3) = (0x20 | (cc & 0x0F)).toByte // adaptation field only, CC = cc
    arr(4) = 183.toByte  // adaptation field length (188 - 5)
    arr(5) = 0x80.toByte // discontinuity_indicator = 1
    ByteString(arr)
  }

  final case class PmtStreamPids(pcrPid: Option[Int], videoPid: Option[Int])

  def isVideoType(streamType: Int): Boolean =
    streamType match {
      case 0x01 | 0x02 | 0x1B | 0x24 => true // MPEG-1, MPEG-2, H.264, H.265/HEVC
      case _ => false
    }

  def extractPmtStreamPids(packet: Array[Byte], offset: Int): Option[PmtStreamPids] = {
    val pusi = (packet(offset + 1) & 0x40) != 0
    val afc = (packet(offset + 3) & 0x30) >> 4
    val payloadOffset =
      if (afc == 1) offset + 4
      else if (afc == 3) offset + 5 + (packet(offset + 4) & 0xFF)
      else offset + PacketSize

    if (payloadOffset < offset + PacketSize) {
      val pointerField = if (pusi) packet(payloadOffset) & 0xFF else 0
      val tableOffset = payloadOffset + (if (pusi) 1 + pointerField else 0)
      if (tableOffset + 12 < offset + PacketSize) {
        val tableId = packet(tableOffset) & 0xFF
        if (tableId == 0x02) {
          val sectionLength = ((packet(tableOffset + 1) & 0x0F) << 8) | (packet(tableOffset + 2) & 0xFF)
          val pcrPid = ((packet(tableOffset + 8) & 0x1F) << 8) | (packet(tableOffset + 9) & 0xFF)
          val progInfoLen = ((packet(tableOffset + 10) & 0x0F) << 8) | (packet(tableOffset + 11) & 0xFF)
          var entryPos = tableOffset + 12 + progInfoLen
          val endPos = math.min(offset + PacketSize, tableOffset + 3 + sectionLength - 4)
          var videoPidOpt: Option[Int] = None
          while (entryPos + 5 <= endPos) {
            val streamType = packet(entryPos) & 0xFF
            val elemPid = ((packet(entryPos + 1) & 0x1F) << 8) | (packet(entryPos + 2) & 0xFF)
            val esInfoLen = ((packet(entryPos + 3) & 0x0F) << 8) | (packet(entryPos + 4) & 0xFF)
            if (videoPidOpt.isEmpty && isVideoType(streamType)) {
              videoPidOpt = Some(elemPid)
            }
            entryPos += 5 + math.max(0, esInfoLen)
          }
          val validPcr = if (pcrPid > 0 && pcrPid != NullPid) Some(pcrPid) else None
          Some(PmtStreamPids(validPcr, videoPidOpt))
        } else None
      } else None
    } else None
  }

  final case class CachedHeaders(
    pat: Option[ByteString] = None
  , pmt: Option[ByteString] = None
  , pcrPid: Option[Int] = None
  , videoPid: Option[Int] = None
  ) {
    def isEmpty: Boolean = pat.isEmpty && pmt.isEmpty

    def syncPrefix: ByteString = {
      val b = ByteString.newBuilder
      videoPid.filter(p => p > 0 && p != NullPid).foreach { pid =>
        b ++= discontinuityPacket(pid)
      }
      pcrPid.filter(p => p > 0 && p != NullPid && !videoPid.contains(p)).foreach { pid =>
        b ++= discontinuityPacket(pid)
      }
      b ++= MPEGTS_DISCONTINUITY_PACKET
      pat.foreach(b ++= _)
      pmt.foreach(b ++= _)
      b.result()
    }
  }

  def extractPmtPid(packet: Array[Byte], offset: Int): Option[Int] = {
    val pusi = (packet(offset + 1) & 0x40) != 0
    val afc = (packet(offset + 3) & 0x30) >> 4
    val payloadOffset =
      if (afc == 1) offset + 4
      else if (afc == 3) offset + 5 + (packet(offset + 4) & 0xFF)
      else offset + PacketSize

    if (payloadOffset < offset + PacketSize) {
      val pointerField = if (pusi) packet(payloadOffset) & 0xFF else 0
      val tableOffset = payloadOffset + (if (pusi) 1 + pointerField else 0)
      if (tableOffset + 8 < offset + PacketSize) {
        val tableId = packet(tableOffset) & 0xFF
        if (tableId == 0x00) {
          val sectionLength = ((packet(tableOffset + 1) & 0x0F) << 8) | (packet(tableOffset + 2) & 0xFF)
          var entryPos = tableOffset + 8
          val endPos = math.min(offset + PacketSize, tableOffset + 3 + sectionLength - 4)
          var pmtPidOpt: Option[Int] = None
          while (entryPos + 4 <= endPos && pmtPidOpt.isEmpty) {
            val programNum = ((packet(entryPos) & 0xFF) << 8) | (packet(entryPos + 1) & 0xFF)
            val pmtPid = ((packet(entryPos + 2) & 0x1F) << 8) | (packet(entryPos + 3) & 0xFF)
            if (programNum != 0) {
              pmtPidOpt = Some(pmtPid)
            }
            entryPos += 4
          }
          pmtPidOpt
        } else None
      } else None
    } else None
  }

  def cacheFlow(
    cachedHeadersRef: AtomicReference[CachedHeaders]
  , onHeadersUpdated: CachedHeaders => Unit = _ => ()
  ): Flow[ByteString, ByteString, NotUsed] =
    Flow.fromGraph(new CacheStage(cachedHeadersRef, onHeadersUpdated))

  def primeClientSource(
    source: Source[ByteString, NotUsed]
  , cachedHeadersRef: AtomicReference[CachedHeaders]
  ): Source[ByteString, NotUsed] =
    Source.lazySource { () =>
      val headers = cachedHeadersRef.get()
      if (headers.isEmpty) source
      else Source.single(headers.syncPrefix).concat(source)
    }.mapMaterializedValue(_ => NotUsed)

  private final class CacheStage(
    cachedHeadersRef: AtomicReference[CachedHeaders]
  , onHeadersUpdated: CachedHeaders => Unit
  ) extends GraphStage[FlowShape[ByteString, ByteString]] {
    val in: Inlet[ByteString] = Inlet("MpegTsSync.in")
    val out: Outlet[ByteString] = Outlet("MpegTsSync.out")
    override val shape: FlowShape[ByteString, ByteString] = FlowShape(in, out)

    override def createLogic(attrs: Attributes): GraphStageLogic = new GraphStageLogic(shape) {
      private var carry: ByteString = ByteString.empty
      private var detectedPmtPid: Option[Int] = None

      setHandler(in, new InHandler {
        override def onPush(): Unit = {
          val incoming = grab(in)
          val combined = carry ++ incoming
          val len = combined.length
          val arr = combined.toArray
          var pos = 0
          var updated = cachedHeadersRef.get()
          val outputBuilder = ByteString.newBuilder

          while (pos + PacketSize <= len) {
            if (arr(pos) == 0x47.toByte) {
              val pid = ((arr(pos + 1) & 0x1F) << 8) | (arr(pos + 2) & 0xFF)
              if (pid == PatPid) {
                val patPacket = ByteString(java.util.Arrays.copyOfRange(arr, pos, pos + PacketSize))
                extractPmtPid(arr, pos).foreach { pmtPid =>
                  detectedPmtPid = Some(pmtPid)
                }
                updated = updated.copy(pat = Some(patPacket))
              } else if (detectedPmtPid.contains(pid)) {
                val pmtPacket = ByteString(java.util.Arrays.copyOfRange(arr, pos, pos + PacketSize))
                val streamPids = extractPmtStreamPids(arr, pos)
                updated = updated.copy(
                  pmt = Some(pmtPacket)
                , pcrPid = streamPids.flatMap(_.pcrPid).orElse(updated.pcrPid)
                , videoPid = streamPids.flatMap(_.videoPid).orElse(updated.videoPid)
                )
              }

              val isTei = (arr(pos + 1) & 0x80) != 0
              if (isTei) {
                outputBuilder ++= MPEGTS_NULL_PACKET
              } else {
                outputBuilder ++= ByteString.fromArray(arr, pos, PacketSize)
              }
              pos += PacketSize
            } else {
              val nextSync = findNextSync(arr, pos + 1, len)
              if (nextSync >= 0) {
                pos = nextSync
              } else {
                pos = len - PacketSize + 1
              }
            }
          }

          carry = combined.drop(pos)

          val prev = cachedHeadersRef.get()
          if (updated != prev) {
            cachedHeadersRef.set(updated)
            onHeadersUpdated(updated)
          }

          val result = outputBuilder.result()
          if (result.nonEmpty) {
            push(out, result)
          } else {
            pull(in)
          }
        }

        override def onUpstreamFinish(): Unit = {
          if (carry.length >= PacketSize) {
            val arr = carry.toArray
            var pos = 0
            val outputBuilder = ByteString.newBuilder
            while (pos + PacketSize <= arr.length) {
              if (arr(pos) == 0x47.toByte) {
                val isTei = (arr(pos + 1) & 0x80) != 0
                if (isTei) outputBuilder ++= MPEGTS_NULL_PACKET
                else outputBuilder ++= ByteString.fromArray(arr, pos, PacketSize)
                pos += PacketSize
              } else {
                val next = findNextSync(arr, pos + 1, arr.length)
                if (next >= 0) pos = next
                else pos = arr.length
              }
            }
            val result = outputBuilder.result()
            if (result.nonEmpty) {
              emit(out, result)
            }
          }
          completeStage()
        }
      })

      setHandler(out, new OutHandler {
        override def onPull(): Unit = pull(in)
      })
    }
  }
}

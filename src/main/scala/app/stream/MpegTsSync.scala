package app.stream

import org.apache.pekko.NotUsed
import org.apache.pekko.stream.{Attributes, FlowShape, Inlet, Outlet}
import org.apache.pekko.stream.scaladsl.{Flow, Source}
import org.apache.pekko.stream.stage.{GraphStage, GraphStageLogic, InHandler, OutHandler}
import org.apache.pekko.util.ByteString

import org.slf4j.LoggerFactory

import java.util.concurrent.atomic.AtomicReference

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.{Failure, Success}

object MpegTsSync {
  private val log = LoggerFactory.getLogger(getClass)
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

  final case class PmtStreamPids(
    pcrPid: Option[Int]
  , videoPid: Option[Int]
  , videoStreamType: Option[Int] = None
  , audioPids: Seq[Int] = Seq.empty
  )

  def isVideoType(streamType: Int): Boolean =
    streamType match {
      case 0x01 | 0x02 | 0x1B | 0x24 => true // MPEG-1, MPEG-2, H.264, H.265/HEVC
      case _ => false
    }

  def isAudioType(streamType: Int): Boolean =
    streamType match {
      case 0x03 | 0x04 | 0x0F | 0x11 | 0x81 | 0x87 => true // MPEG-1/2 audio, ADTS AAC, LATM AAC, ATSC AC-3, E-AC-3
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
          var videoStreamTypeOpt: Option[Int] = None
          val audioPidsBuilder = Vector.newBuilder[Int]
          while (entryPos + 5 <= endPos) {
            val streamType = packet(entryPos) & 0xFF
            val elemPid = ((packet(entryPos + 1) & 0x1F) << 8) | (packet(entryPos + 2) & 0xFF)
            val esInfoLen = ((packet(entryPos + 3) & 0x0F) << 8) | (packet(entryPos + 4) & 0xFF)
            if (videoPidOpt.isEmpty && isVideoType(streamType) && elemPid > 0 && elemPid != NullPid) {
              videoPidOpt = Some(elemPid)
              videoStreamTypeOpt = Some(streamType)
            } else if (isAudioType(streamType) && elemPid > 0 && elemPid != NullPid) {
              audioPidsBuilder += elemPid
            }
            entryPos += 5 + math.max(0, esInfoLen)
          }
          val validPcr = if (pcrPid > 0 && pcrPid != NullPid) Some(pcrPid) else None
          Some(PmtStreamPids(validPcr, videoPidOpt, videoStreamTypeOpt, audioPidsBuilder.result().distinct))
        } else None
      } else None
    } else None
  }

  final case class CachedHeaders(
    pat: Option[ByteString] = None
  , pmt: Option[ByteString] = None
  , pcrPid: Option[Int] = None
  , videoPid: Option[Int] = None
  , videoStreamType: Option[Int] = None
  , audioPids: Seq[Int] = Seq.empty
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
      audioPids.distinct
        .filter(p => p > 0 && p != NullPid && !videoPid.contains(p) && !pcrPid.contains(p))
        .foreach { pid =>
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
      val aligned = source.via(
        alignToIFrame(
          videoPid = headers.videoPid
        , videoStreamType = headers.videoStreamType
        , audioPids = headers.audioPids
        )
      )
      if (headers.isEmpty) aligned
      else Source.single(headers.syncPrefix).concat(aligned)
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
      private var teiedPids: Set[Int] = Set.empty
      private val lastCcByPid = scala.collection.mutable.Map.empty[Int, Int]
      private var corruptVideoGop: Boolean = false
      private var currentGopDroppedPackets: Int = 0
      private var currentGopTeiPackets: Int = 0
      private var currentGopCcErrors: Int = 0
      private var totalTeiPackets: Long = 0L
      private var totalCcErrors: Long = 0L
      private var totalDroppedGops: Long = 0L
      private var totalDroppedGopPackets: Long = 0L

      private def processPacket(
        arr: Array[Byte]
      , pos: Int
      , currentHeaders: CachedHeaders
      , outputBuilder: org.apache.pekko.util.ByteStringBuilder
      ): CachedHeaders = {
        var updated = currentHeaders
        val isTei = (arr(pos + 1) & 0x80) != 0
        val pusi = (arr(pos + 1) & 0x40) != 0
        val pid = ((arr(pos + 1) & 0x1F) << 8) | (arr(pos + 2) & 0xFF)
        val afc = (arr(pos + 3) & 0x30) >> 4
        val cc = arr(pos + 3) & 0x0F
        val hasPayload = afc == 1 || afc == 3
        val hasAdaptation = afc == 2 || afc == 3
        val isDiscontinuity =
          hasAdaptation && (arr(pos + 4) & 0xFF) > 0 && (arr(pos + 5) & 0x80) != 0

        val isElementaryPid =
          pid > 0 && pid != NullPid && pid != PatPid && !detectedPmtPid.contains(pid)

        val isCcError =
          if (isTei || !isElementaryPid) {
            false
          } else if (isDiscontinuity) {
            if (hasPayload) {
              val _ = lastCcByPid.put(pid, cc)
            } else {
              val _ = lastCcByPid.remove(pid)
            }
            false
          } else if (hasPayload) {
            val err = lastCcByPid.get(pid) match {
              case Some(prevCc) => cc != ((prevCc + 1) & 0x0F) && cc != prevCc
              case None => false
            }
            val _ = lastCcByPid.put(pid, cc)
            err
          } else {
            false
          }

        if (isTei) {
          totalTeiPackets += 1
          if (updated.videoPid.contains(pid)) {
            if (!corruptVideoGop) {
              corruptVideoGop = true
              totalDroppedGops += 1
            }
            currentGopTeiPackets += 1
            currentGopDroppedPackets += 1
            totalDroppedGopPackets += 1
          } else if (pid > 0 && pid != NullPid) {
            teiedPids += pid
          }
          outputBuilder ++= MPEGTS_NULL_PACKET
        } else {
          if (pid == PatPid) {
            val patPacket = ByteString(java.util.Arrays.copyOfRange(arr, pos, pos + PacketSize))
            extractPmtPid(arr, pos).foreach { pmtPid =>
              detectedPmtPid = Some(pmtPid)
            }
            updated = updated.copy(pat = Some(patPacket))
          } else if (detectedPmtPid.contains(pid)) {
            val pmtPacket = ByteString(java.util.Arrays.copyOfRange(arr, pos, pos + PacketSize))
            val streamPids = extractPmtStreamPids(arr, pos)
            val nextAudioPids = streamPids.map(_.audioPids).filter(_.nonEmpty).getOrElse(updated.audioPids)
            updated = updated.copy(
              pmt = Some(pmtPacket)
            , pcrPid = streamPids.flatMap(_.pcrPid).orElse(updated.pcrPid)
            , videoPid = streamPids.flatMap(_.videoPid).orElse(updated.videoPid)
            , videoStreamType = streamPids.flatMap(_.videoStreamType).orElse(updated.videoStreamType)
            , audioPids = nextAudioPids
            )
          }

          if (updated.videoPid.contains(pid)) {
            val payloadOffset =
              if (afc == 1) pos + 4
              else if (afc == 3) pos + 5 + (arr(pos + 4) & 0xFF)
              else pos + PacketSize
            val payloadLen = (pos + PacketSize) - payloadOffset
            val isKeyframeStart =
              payloadLen > 0 && hasKeyframeHeader(arr, payloadOffset, payloadLen, updated.videoStreamType)

            if (isKeyframeStart || (corruptVideoGop && pusi && currentGopDroppedPackets >= DefaultMaxDroppedPackets)) {
              if (corruptVideoGop) {
                if (isCcError) {
                  totalCcErrors += 1
                  currentGopCcErrors += 1
                }
                log.warn(
                  "[mpegts] gated corrupt video GOP on pid={} (droppedPackets={} tei={} ccErrors={} totalDroppedGops={})"
                , pid
                , currentGopDroppedPackets
                , currentGopTeiPackets
                , currentGopCcErrors
                , totalDroppedGops
                )
                corruptVideoGop = false
                currentGopDroppedPackets = 0
                currentGopTeiPackets = 0
                currentGopCcErrors = 0
                teiedPids -= pid
                outputBuilder ++= discontinuityPacket(pid, (cc - 1) & 0x0F)
                outputBuilder ++= ByteString.fromArray(arr, pos, PacketSize)
              } else if (isCcError) {
                totalCcErrors += 1
                outputBuilder ++= discontinuityPacket(pid, (cc - 1) & 0x0F)
                outputBuilder ++= ByteString.fromArray(arr, pos, PacketSize)
              } else {
                outputBuilder ++= ByteString.fromArray(arr, pos, PacketSize)
              }
            } else if (corruptVideoGop || isCcError) {
              if (isCcError) {
                totalCcErrors += 1
                currentGopCcErrors += 1
              }
              if (!corruptVideoGop) {
                corruptVideoGop = true
                totalDroppedGops += 1
              }
              currentGopDroppedPackets += 1
              totalDroppedGopPackets += 1
              outputBuilder ++= MPEGTS_NULL_PACKET
            } else {
              outputBuilder ++= ByteString.fromArray(arr, pos, PacketSize)
            }
          } else {
            if (isCcError) {
              totalCcErrors += 1
              if (pusi) {
                teiedPids -= pid
                outputBuilder ++= discontinuityPacket(pid, (cc - 1) & 0x0F)
                outputBuilder ++= ByteString.fromArray(arr, pos, PacketSize)
              } else {
                teiedPids += pid
                outputBuilder ++= MPEGTS_NULL_PACKET
              }
            } else if (teiedPids.contains(pid)) {
              if (pusi) {
                teiedPids -= pid
                outputBuilder ++= ByteString.fromArray(arr, pos, PacketSize)
              } else {
                outputBuilder ++= MPEGTS_NULL_PACKET
              }
            } else {
              outputBuilder ++= ByteString.fromArray(arr, pos, PacketSize)
            }
          }
        }
        updated
      }

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
              updated = processPacket(arr, pos, updated, outputBuilder)
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
            var updated = cachedHeadersRef.get()
            val outputBuilder = ByteString.newBuilder
            while (pos + PacketSize <= arr.length) {
              if (arr(pos) == 0x47.toByte) {
                updated = processPacket(arr, pos, updated, outputBuilder)
                pos += PacketSize
              } else {
                val next = findNextSync(arr, pos + 1, arr.length)
                if (next >= 0) pos = next
                else pos = arr.length
              }
            }
            val prev = cachedHeadersRef.get()
            if (updated != prev) {
              cachedHeadersRef.set(updated)
              onHeadersUpdated(updated)
            }
            val result = outputBuilder.result()
            if (result.nonEmpty) {
              emit(out, result)
            }
          }
          if (totalTeiPackets > 0 || totalCcErrors > 0) {
            log.info(
              "[mpegts] stream ended with transport stats: totalTei={} totalCcErrors={} totalDroppedGops={} totalDroppedGopPackets={}"
            , totalTeiPackets
            , totalCcErrors
            , totalDroppedGops
            , totalDroppedGopPackets
            )
          }
          completeStage()
        }
      })

      setHandler(out, new OutHandler {
        override def onPull(): Unit = pull(in)
      })
    }
  }

  def hasKeyframeHeader(arr: Array[Byte], offset: Int, len: Int): Boolean =
    hasKeyframeHeader(arr, offset, len, None)

  def hasKeyframeHeader(arr: Array[Byte], offset: Int, len: Int, videoStreamType: Option[Int]): Boolean = {
    val isMpeg2Only = videoStreamType.exists(st => st == 0x01 || st == 0x02)
    val isH264Only = videoStreamType.contains(0x1B)
    var i = offset
    val end = offset + len - 4
    var found = false
    while (i <= end && !found) {
      if (arr(i) == 0x00.toByte && arr(i + 1) == 0x00.toByte && arr(i + 2) == 0x01.toByte) {
        val code = arr(i + 3) & 0xFF
        if (!isH264Only && (code == 0xB3 || code == 0xB8)) {
          // MPEG-1/2 Sequence Header (0xB3) or GOP Header (0xB8)
          found = true
        } else if (!isMpeg2Only && (code & 0x80) == 0) {
          // H.264 NAL unit with forbidden_zero_bit == 0; check for IDR (5) or SPS (7).
          // Excluded when streamType is known to be MPEG-1/2 because 0x01..0xAF are MPEG-2 vertical slice start codes.
          val nalType = code & 0x1F
          if (nalType == 5 || nalType == 7) {
            found = true
          } else {
            i += 1
          }
        } else {
          i += 1
        }
      } else {
        i += 1
      }
    }
    found
  }

  val DefaultMaxDroppedPackets: Int = 8000

  def alignToIFrame(
    videoPid: Option[Int] = None
  , maxDroppedPackets: Int = DefaultMaxDroppedPackets
  , videoStreamType: Option[Int] = None
  , audioPids: Seq[Int] = Seq.empty
  ): Flow[ByteString, ByteString, NotUsed] =
    Flow.fromGraph(new AlignToIFrameStage(videoPid, maxDroppedPackets, videoStreamType, audioPids))

  private final class AlignToIFrameStage(
    videoPid: Option[Int]
  , maxDroppedPackets: Int
  , initialVideoStreamType: Option[Int]
  , initialAudioPids: Seq[Int]
  ) extends GraphStage[FlowShape[ByteString, ByteString]] {
    val in: Inlet[ByteString] = Inlet("MpegTsSync.alignToIFrame.in")
    val out: Outlet[ByteString] = Outlet("MpegTsSync.alignToIFrame.out")
    override val shape: FlowShape[ByteString, ByteString] = FlowShape(in, out)

    override def createLogic(attrs: Attributes): GraphStageLogic = new GraphStageLogic(shape) {
      private val startTimeNanos: Long = System.nanoTime()
      private var carry: ByteString = ByteString.empty
      private var isAligned = false
      private var currentVideoPid: Option[Int] = videoPid
      private var currentVideoStreamType: Option[Int] = initialVideoStreamType
      private var knownAudioPids: Set[Int] = initialAudioPids.toSet
      private var alignedAudioPids: Set[Int] = Set.empty
      private var detectedPmtPid: Option[Int] = None
      private var droppedPackets = 0

      private def allAudioAligned: Boolean =
        knownAudioPids.isEmpty || alignedAudioPids.size >= knownAudioPids.size

      setHandler(in, new InHandler {
        override def onPush(): Unit = {
          val incoming = grab(in)
          if (isAligned && allAudioAligned) {
            if (carry.nonEmpty) {
              val combined = carry ++ incoming
              carry = ByteString.empty
              push(out, combined)
            } else {
              push(out, incoming)
            }
          } else {
            val combined = carry ++ incoming
            val len = combined.length
            val arr = combined.toArray
            var pos = 0
            val outputBuilder = ByteString.newBuilder

            while (pos + PacketSize <= len) {
              if (arr(pos) == 0x47.toByte) {
                val pid = ((arr(pos + 1) & 0x1F) << 8) | (arr(pos + 2) & 0xFF)
                if (isAligned) {
                  if (knownAudioPids.contains(pid) && !alignedAudioPids.contains(pid)) {
                    val pusi = (arr(pos + 1) & 0x40) != 0
                    if (pusi) {
                      alignedAudioPids += pid
                      outputBuilder ++= ByteString.fromArray(arr, pos, PacketSize)
                    }
                  } else {
                    outputBuilder ++= ByteString.fromArray(arr, pos, PacketSize)
                  }
                } else {
                  if (pid == PatPid) {
                    extractPmtPid(arr, pos).foreach { pmtPid =>
                      detectedPmtPid = Some(pmtPid)
                    }
                    outputBuilder ++= ByteString.fromArray(arr, pos, PacketSize)
                  } else if (detectedPmtPid.contains(pid)) {
                    extractPmtStreamPids(arr, pos).foreach { pids =>
                      if (currentVideoPid.isEmpty) {
                        currentVideoPid = pids.videoPid
                      }
                      if (currentVideoStreamType.isEmpty) {
                        currentVideoStreamType = pids.videoStreamType
                      }
                      if (pids.audioPids.nonEmpty) {
                        knownAudioPids ++= pids.audioPids
                      }
                    }
                    outputBuilder ++= ByteString.fromArray(arr, pos, PacketSize)
                  } else {
                    val afc = (arr(pos + 3) & 0x30) >> 4
                    val payloadOffset =
                      if (afc == 1) pos + 4
                      else if (afc == 3) pos + 5 + (arr(pos + 4) & 0xFF)
                      else pos + PacketSize
                    val payloadLen = (pos + PacketSize) - payloadOffset

                    val isTargetVideo = currentVideoPid.contains(pid)
                    val isCandidateVideo = currentVideoPid.isEmpty && !knownAudioPids.contains(pid) && pid != NullPid && pid > 0

                    if ((isTargetVideo || isCandidateVideo) && payloadLen > 0 && hasKeyframeHeader(arr, payloadOffset, payloadLen, currentVideoStreamType)) {
                      if (currentVideoPid.isEmpty) currentVideoPid = Some(pid)
                      isAligned = true
                      val elapsedMs = (System.nanoTime() - startTimeNanos) / 1000000
                      log.info(s"Aligned to I-Frame on PID ${currentVideoPid.getOrElse(pid)} (dropped $droppedPackets leading packets in ${elapsedMs}ms)")
                      outputBuilder ++= ByteString.fromArray(arr, pos, PacketSize)
                    } else {
                      droppedPackets += 1
                      if (droppedPackets > maxDroppedPackets) {
                        val elapsedMs = (System.nanoTime() - startTimeNanos) / 1000000
                        log.warn(s"Max dropped packets ($droppedPackets in ${elapsedMs}ms) exceeded while waiting for I-Frame; forcing alignment")
                        isAligned = true
                        alignedAudioPids = knownAudioPids
                        outputBuilder ++= ByteString.fromArray(arr, pos, PacketSize)
                      }
                    }
                  }
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

            if (!isAligned && pos == 0 && len >= PacketSize && findNextSync(arr, 0, len) < 0) {
              isAligned = true
              alignedAudioPids = knownAudioPids
              outputBuilder ++= combined
              pos = len
            }

            carry = combined.drop(pos)
            val result = outputBuilder.result()
            if (result.nonEmpty) {
              push(out, result)
            } else {
              pull(in)
            }
          }
        }

        override def onUpstreamFinish(): Unit = {
          if (carry.nonEmpty) {
            emit(out, carry)
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

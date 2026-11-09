package app.stream

import org.apache.pekko.NotUsed
import org.apache.pekko.stream.{Attributes, FlowShape, Inlet, Outlet}
import org.apache.pekko.stream.scaladsl.{Flow, Source}
import org.apache.pekko.stream.stage.{GraphStage, GraphStageLogic, InHandler, OutHandler, TimerGraphStageLogic}
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
          val all = if (carry.isEmpty) chunk else carry ++ chunk
          val whole = (all.length / PacketSize) * PacketSize
          val nextCarry = if (whole == all.length) ByteString.empty else all.drop(whole).compact
          (nextCarry, all.take(whole))
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
  , audioStreamTypes: Map[Int, Int] = Map.empty
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
          val audioStreamTypesBuilder = Map.newBuilder[Int, Int]
          while (entryPos + 5 <= endPos) {
            val streamType = packet(entryPos) & 0xFF
            val elemPid = ((packet(entryPos + 1) & 0x1F) << 8) | (packet(entryPos + 2) & 0xFF)
            val esInfoLen = ((packet(entryPos + 3) & 0x0F) << 8) | (packet(entryPos + 4) & 0xFF)
            if (videoPidOpt.isEmpty && isVideoType(streamType) && elemPid > 0 && elemPid != NullPid) {
              videoPidOpt = Some(elemPid)
              videoStreamTypeOpt = Some(streamType)
            } else if (isAudioType(streamType) && elemPid > 0 && elemPid != NullPid) {
              audioPidsBuilder += elemPid
              audioStreamTypesBuilder += (elemPid -> streamType)
            }
            entryPos += 5 + math.max(0, esInfoLen)
          }
          val validPcr = if (pcrPid > 0 && pcrPid != NullPid) Some(pcrPid) else None
          Some(PmtStreamPids(validPcr, videoPidOpt, videoStreamTypeOpt, audioPidsBuilder.result().distinct, audioStreamTypesBuilder.result()))
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
  , audioStreamTypes: Map[Int, Int] = Map.empty
  , lockedSeqHeader: Option[ByteString] = None
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

  val DefaultFreezeThreshold: FiniteDuration = 10.seconds
  val DefaultFreezeTickInterval: FiniteDuration = 100.millis
  val DefaultMetricsInterval: FiniteDuration = 30.seconds

  def configuredFreezeThreshold: FiniteDuration =
    scala.util.Try(app.AppContext.config.stream.freezeThresholdSec).toOption
      .map(sec => if (sec <= 0) Duration.Zero else sec.seconds)
      .getOrElse(DefaultFreezeThreshold)

  def configuredMetricsInterval: FiniteDuration =
    scala.util.Try(app.AppContext.config.stream.metricsIntervalSec).toOption
      .map(sec => if (sec <= 0) Duration.Zero else sec.seconds)
      .getOrElse(DefaultMetricsInterval)

  def cacheFlow(
    cachedHeadersRef: AtomicReference[CachedHeaders]
  , onHeadersUpdated: CachedHeaders => Unit = _ => ()
  , freezeThreshold: FiniteDuration = configuredFreezeThreshold
  , freezeTickInterval: FiniteDuration = DefaultFreezeTickInterval
  , label: String = ""
  , metricsInterval: FiniteDuration = configuredMetricsInterval
  , hlsCollector: Option[StreamMetrics.HlsCollector] = None
  , latestMetricsRef: Option[AtomicReference[StreamMetrics.WindowSnapshot]] = None
  ): Flow[ByteString, ByteString, NotUsed] =
    Flow.fromGraph(
      new CacheStage(
        cachedHeadersRef
      , onHeadersUpdated
      , freezeThreshold
      , freezeTickInterval
      , label
      , metricsInterval
      , hlsCollector
      , latestMetricsRef
      )
    )

  def signedPtsDiff(a: Long, b: Long): Long = {
    val raw = (a - b) & FreezeFramePacer.PtsMask
    if ((raw & 0x100000000L) != 0L) raw - 0x200000000L else raw
  }

  def primeClientSource(
    source: Source[ByteString, NotUsed]
  , cachedHeadersRef: AtomicReference[CachedHeaders]
  , label: String = ""
  ): Source[ByteString, NotUsed] =
    Source.lazySource { () =>
      val headers = cachedHeadersRef.get()
      val aligned = source.via(
        alignToIFrame(
          videoPid = headers.videoPid
        , videoStreamType = headers.videoStreamType
        , audioPids = headers.audioPids
        , audioStreamTypes = headers.audioStreamTypes
        , lockedSeqHeader = headers.lockedSeqHeader
        , label = label
        )
      )
      if (headers.isEmpty) aligned
      else Source.single(headers.syncPrefix).concat(aligned)
    }.mapMaterializedValue(_ => NotUsed)

  private def sameTablePacket(existingOpt: Option[ByteString], arr: Array[Byte], pos: Int): Boolean =
    existingOpt match {
      case Some(existing) if existing.length == PacketSize =>
        var i = 0
        var same = true
        while (i < PacketSize && same) {
          if (i == 3) {
            same = (existing(3) & 0xF0) == (arr(pos + 3) & 0xF0)
          } else {
            same = existing(i) == arr(pos + i)
          }
          i += 1
        }
        same
      case _ => false
    }

  private final class CacheStage(
    cachedHeadersRef: AtomicReference[CachedHeaders]
  , onHeadersUpdated: CachedHeaders => Unit
  , freezeThreshold: FiniteDuration
  , freezeTickInterval: FiniteDuration
  , label: String
  , metricsInterval: FiniteDuration
  , hlsCollector: Option[StreamMetrics.HlsCollector]
  , latestMetricsRef: Option[AtomicReference[StreamMetrics.WindowSnapshot]]
  ) extends GraphStage[FlowShape[ByteString, ByteString]] {
    val in: Inlet[ByteString] = Inlet("MpegTsSync.in")
    val out: Outlet[ByteString] = Outlet("MpegTsSync.out")
    override val shape: FlowShape[ByteString, ByteString] = FlowShape(in, out)

    override def createLogic(attrs: Attributes): GraphStageLogic = new TimerGraphStageLogic(shape) {
      private var carry: ByteString = ByteString.empty
      private var detectedPmtPid: Int = -1
      private var parsedPmt: Boolean = false
      private var candidatePat: Option[ByteString] = None
      private var candidatePmt: Option[ByteString] = None
      private val lastInCcByPid = Array.fill[Byte](8192)((-1).toByte)
      private val outboundCcByPid = Array.fill[Byte](8192)((-1).toByte)
      private var droppingVideoSlice: Boolean = false
      private var requireVideoPesStart: Boolean = false
      private var droppingAudioPids: Set[Int] = Set.empty
      private var droppingOtherPids: Set[Int] = Set.empty
      private var maxSliceRow: Int = FreezeFramePacer.DefaultMaxSliceRow
      private var candidateSeqHeader: Option[ByteString] = None
      private var lockedSeqHeader: Option[ByteString] = None
      private val learnedAudioFormats = scala.collection.mutable.Map.empty[Int, FreezeFramePacer.AudioFormat]
      private var seenLiveKeyframe: Boolean = false
      private var lastLiveDataNanos: Long = System.nanoTime()
      private var freezeActive: Boolean = false
      private var freezeTicksCount: Int = 0
      private var lastInputVideoPts: Long = -1L
      private var lastOutputPts: Long = -1L
      private var mediaClockAnchorPts: Long = -1L
      private var mediaClockAnchorNanos: Long = 0L
      private var ptsDeltaTicks: Long = 0L
      private var pendingOutlierPts: Option[Long] = None
      private var totalTeiPackets: Long = 0L
      private var totalCcErrors: Long = 0L
      private var totalClampedSlices: Long = 0L
      private var totalScrubbedPts: Long = 0L
      private var totalFreezeBursts: Long = 0L
      private var lastLoggedTei: Long = 0L
      private var lastLoggedCc: Long = 0L
      private var lastLoggedClamped: Long = 0L
      private var lastLoggedScrubbed: Long = 0L
      private var lastStatsLogNanos: Long = System.nanoTime()
      private val statsLogIntervalNanos: Long = 30.seconds.toNanos
      private val labelPrefix: String = if (label.nonEmpty) s" label=$label" else ""

      private var windowStartNanos: Long = System.nanoTime()
      private var windowRealPackets: Long = 0L
      private var windowNullPackets: Long = 0L
      private var windowElementaryPackets: Long = 0L
      private var windowErrorPackets: Long = 0L
      private var windowVideoFramesClean: Long = 0L
      private var windowVideoFramesTotal: Long = 0L
      private var currentVideoFrameStarted: Boolean = false
      private var currentVideoFrameCorrupt: Boolean = false
      private var windowAudioFramesClean: Long = 0L
      private var windowAudioFramesTotal: Long = 0L
      private var currentAudioFrameStarted: Set[Int] = Set.empty
      private var currentAudioFrameCorrupt: Set[Int] = Set.empty
      private var windowKeyframes: Long = 0L
      private var windowTei: Long = 0L
      private var windowCcErr: Long = 0L
      private var windowSliceDrops: Long = 0L
      private var windowPtsScrubs: Long = 0L
      private var windowSyncLossBytes: Long = 0L
      private var windowPtsAdvanceTicks: Long = 0L
      private var lastContinuousVideoPts: Long = -1L
      private var pendingMetricOutlierPts: Option[Long] = None
      private var latestVideoPts: Long = -1L
      private var latestAudioPts: Long = -1L
      private var initialSnapshotPublished: Boolean = false

      private def recordInputPts(arr: Array[Byte], pktOffset: Int, isVideo: Boolean): Unit = {
        val pesOff = FreezeFramePacer.findPesHeaderOffset(arr, pktOffset)
        if (pesOff >= 0) {
          FreezeFramePacer.readPtsDts(arr, pesOff, pktOffset + PacketSize).foreach { case (inPts, _) =>
            if (isVideo) {
              latestVideoPts = inPts
              if (lastContinuousVideoPts >= 0L) {
                val delta = signedPtsDiff(inPts, lastContinuousVideoPts)
                if (delta > 0L && delta <= FreezeFramePacer.MaxSinglePacketPtsJumpTicks) {
                  windowPtsAdvanceTicks += delta
                  lastContinuousVideoPts = inPts
                  pendingMetricOutlierPts = None
                } else if (delta < -FreezeFramePacer.RebaseThresholdTicks || delta > FreezeFramePacer.MaxSinglePacketPtsJumpTicks) {
                  if (pendingMetricOutlierPts.exists(p => math.abs(signedPtsDiff(inPts, p)) <= FreezeFramePacer.RebaseThresholdTicks)) {
                    lastContinuousVideoPts = inPts
                    pendingMetricOutlierPts = None
                  } else {
                    pendingMetricOutlierPts = Some(inPts)
                  }
                } else {
                  pendingMetricOutlierPts = None
                }
              } else {
                lastContinuousVideoPts = inPts
              }
            } else {
              latestAudioPts = inPts
            }
          }
        }
      }

      private def computeAvSkewMs(): Long =
        if (latestVideoPts >= 0L && latestAudioPts >= 0L) {
          val diffTicks = signedPtsDiff(latestVideoPts, latestAudioPts)
          if (math.abs(diffTicks) <= 900000L) (diffTicks * 1000L) / 90000L else 0L
        } else 0L

      private def finalizeOpenFrames(): Unit = {
        if (currentVideoFrameStarted) {
          windowVideoFramesTotal += 1L
          if (!currentVideoFrameCorrupt) {
            windowVideoFramesClean += 1L
          }
          currentVideoFrameStarted = false
          currentVideoFrameCorrupt = false
        }
        currentAudioFrameStarted.foreach { apid =>
          windowAudioFramesTotal += 1L
          if (!currentAudioFrameCorrupt.contains(apid)) {
            windowAudioFramesClean += 1L
          }
        }
        currentAudioFrameStarted = Set.empty
        currentAudioFrameCorrupt = Set.empty
      }

      private def buildCurrentWindowSnapshot(nowNanos: Long, resetHls: Boolean): StreamMetrics.WindowSnapshot = {
        val elapsedSec = math.max(0.001, (nowNanos - windowStartNanos).toDouble / 1000000000.0)
        val hlsStats = hlsCollector match {
          case Some(c) => if (resetHls) c.snapshotAndResetWindow() else c.currentWindowStats()
          case None => StreamMetrics.HlsWindowStats()
        }
        val openVideoTotal = if (!resetHls && currentVideoFrameStarted) 1L else 0L
        val openVideoClean = if (!resetHls && currentVideoFrameStarted && !currentVideoFrameCorrupt) 1L else 0L
        val openAudioTotal = if (!resetHls) currentAudioFrameStarted.size.toLong else 0L
        val openAudioClean =
          if (!resetHls) currentAudioFrameStarted.count(apid => !currentAudioFrameCorrupt.contains(apid)).toLong
          else 0L

        StreamMetrics.WindowSnapshot.compute(
          windowSec = elapsedSec
        , realPackets = windowRealPackets
        , nullPackets = windowNullPackets
        , elementaryPackets = windowElementaryPackets
        , errorPackets = windowErrorPackets
        , videoFramesClean = windowVideoFramesClean + openVideoClean
        , videoFramesTotal = windowVideoFramesTotal + openVideoTotal
        , audioFramesClean = windowAudioFramesClean + openAudioClean
        , audioFramesTotal = windowAudioFramesTotal + openAudioTotal
        , keyframes = windowKeyframes
        , tei = windowTei
        , ccErr = windowCcErr
        , sliceDrops = windowSliceDrops
        , ptsScrubs = windowPtsScrubs
        , syncLossBytes = windowSyncLossBytes
        , ptsAdvanceTicks = windowPtsAdvanceTicks
        , avSkewMs = computeAvSkewMs()
        , hls = hlsStats
        )
      }

      private def resetWindowCounters(nowNanos: Long): Unit = {
        windowStartNanos = nowNanos
        windowRealPackets = 0L
        windowNullPackets = 0L
        windowElementaryPackets = 0L
        windowErrorPackets = 0L
        windowVideoFramesClean = 0L
        windowVideoFramesTotal = 0L
        windowAudioFramesClean = 0L
        windowAudioFramesTotal = 0L
        windowKeyframes = 0L
        windowTei = 0L
        windowCcErr = 0L
        windowSliceDrops = 0L
        windowPtsScrubs = 0L
        windowSyncLossBytes = 0L
        windowPtsAdvanceTicks = 0L
      }

      private def emitMetricsWindow(nowNanos: Long, logOutput: Boolean): Unit = {
        finalizeOpenFrames()
        val snap = buildCurrentWindowSnapshot(nowNanos, resetHls = true)
        latestMetricsRef.foreach(_.set(snap))
        initialSnapshotPublished = true
        if (logOutput) {
          log.info(snap.formatLogLine(label))
        }
        resetWindowCounters(nowNanos)
      }

      private def nextOutboundCc(pid: Int, hasPayload: Boolean): Int =
        if (hasPayload) {
          val next = (outboundCcByPid(pid).toInt + 1) & 0x0F
          outboundCcByPid(pid) = next.toByte
          next
        } else {
          val cur = outboundCcByPid(pid).toInt
          if (cur < 0) 0 else cur & 0x0F
        }

      private def emitWithOutboundCc(
        arr: Array[Byte]
      , pktOffset: Int
      , pid: Int
      , hasPayload: Boolean
      , outputBuilder: org.apache.pekko.util.ByteStringBuilder
      ): Unit = {
        val outCc = nextOutboundCc(pid, hasPayload)
        arr(pktOffset + 3) = ((arr(pktOffset + 3) & 0xF0) | outCc).toByte
        val _ = outputBuilder.putBytes(arr, pktOffset, PacketSize)
      }

      private def normalizePacketTimestamps(arr: Array[Byte], pktOffset: Int, isVideo: Boolean): Unit = {
        val pktEnd = pktOffset + PacketSize
        val pesOff = FreezeFramePacer.findPesHeaderOffset(arr, pktOffset)
        if (pesOff >= 0) {
          FreezeFramePacer.readPtsDts(arr, pesOff, pktEnd).foreach { case (inPts, inDtsOpt) =>
            val outPts = if (isVideo) {
              val chosen = if (lastOutputPts >= 0) {
                val mappedPts = (inPts + ptsDeltaTicks) & FreezeFramePacer.PtsMask
                val diffFromOut = signedPtsDiff(mappedPts, lastOutputPts)
                val inputJump = if (lastInputVideoPts >= 0) math.abs(signedPtsDiff(inPts, lastInputVideoPts)) else 0L
                if (inputJump > FreezeFramePacer.MaxSinglePacketPtsJumpTicks) {
                  if (pendingOutlierPts.exists(p => math.abs(signedPtsDiff(inPts, p)) <= FreezeFramePacer.RebaseThresholdTicks)) {
                    ptsDeltaTicks = (lastOutputPts + FreezeFramePacer.DefaultVideoStepTicks - inPts) & FreezeFramePacer.PtsMask
                    pendingOutlierPts = None
                    lastInputVideoPts = inPts
                    (inPts + ptsDeltaTicks) & FreezeFramePacer.PtsMask
                  } else {
                    pendingOutlierPts = Some(inPts)
                    totalScrubbedPts += 1L
                    windowPtsScrubs += 1L
                    (lastOutputPts + FreezeFramePacer.DefaultVideoStepTicks) & FreezeFramePacer.PtsMask
                  }
                } else if (diffFromOut < -FreezeFramePacer.RebaseThresholdTicks || diffFromOut > FreezeFramePacer.RebaseThresholdTicks) {
                  ptsDeltaTicks = (lastOutputPts + FreezeFramePacer.DefaultVideoStepTicks - inPts) & FreezeFramePacer.PtsMask
                  pendingOutlierPts = None
                  lastInputVideoPts = inPts
                  (inPts + ptsDeltaTicks) & FreezeFramePacer.PtsMask
                } else {
                  pendingOutlierPts = None
                  lastInputVideoPts = inPts
                  mappedPts
                }
              } else {
                lastInputVideoPts = inPts
                (inPts + ptsDeltaTicks) & FreezeFramePacer.PtsMask
              }
              if (lastOutputPts < 0 || signedPtsDiff(chosen, lastOutputPts) > 0) {
                lastOutputPts = chosen
              }
              latestVideoPts = chosen
              chosen
            } else {
              val mappedPts = (inPts + ptsDeltaTicks) & FreezeFramePacer.PtsMask
              if (lastOutputPts >= 0 && math.abs(signedPtsDiff(mappedPts, lastOutputPts)) > FreezeFramePacer.MaxSinglePacketPtsJumpTicks) {
                totalScrubbedPts += 1L
                windowPtsScrubs += 1L
                lastOutputPts
              } else {
                mappedPts
              }
            }

            val stepDelta = (outPts - inPts) & FreezeFramePacer.PtsMask
            val outDtsOpt = inDtsOpt.map(d => (d + stepDelta) & FreezeFramePacer.PtsMask)
            if (outPts != inPts || outDtsOpt != inDtsOpt) {
              FreezeFramePacer.writePtsDts(arr, pesOff, pktEnd, outPts, outDtsOpt)
            }
          }
        }

        FreezeFramePacer.readPcrBase(arr, pktOffset).foreach { inPcr =>
          val outPcr = (inPcr + ptsDeltaTicks) & FreezeFramePacer.PtsMask
          if (ptsDeltaTicks != 0L) {
            FreezeFramePacer.writePcrBase(arr, pktOffset, outPcr)
          }
          if (lastOutputPts < 0) {
            lastOutputPts = outPcr
          }
        }
      }

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
          pid > 0 && pid != NullPid && pid != PatPid && pid != detectedPmtPid

        if (pid == NullPid) {
          windowNullPackets += 1L
          if (!freezeActive) {
            val _ = outputBuilder ++= MPEGTS_NULL_PACKET
          }
        } else {
          windowRealPackets += 1L
          if (isElementaryPid) {
            windowElementaryPackets += 1L
          }
          if (isDiscontinuity && updated.videoPid.contains(pid)) {
            lastContinuousVideoPts = -1L
            latestVideoPts = -1L
            latestAudioPts = -1L
            pendingMetricOutlierPts = None
          }
          val isCcError =
            if (isTei || !isElementaryPid) {
              false
            } else if (isDiscontinuity) {
              lastInCcByPid(pid) = if (hasPayload) cc.toByte else (-1).toByte
              false
            } else if (hasPayload) {
              val prevCc = lastInCcByPid(pid).toInt
              val err = prevCc >= 0 && cc != ((prevCc + 1) & 0x0F) && cc != prevCc
              lastInCcByPid(pid) = cc.toByte
              err
            } else {
              false
            }

          if (isElementaryPid && (isTei || isCcError)) {
            windowErrorPackets += 1L
          }

          val payloadOffset =
            if (afc == 1) pos + 4
            else if (afc == 3) pos + 5 + (arr(pos + 4) & 0xFF)
            else pos + PacketSize
          val payloadLen = math.max(0, (pos + PacketSize) - payloadOffset)

          if (isTei) {
            totalTeiPackets += 1L
            windowTei += 1L
            if (updated.videoPid.contains(pid)) {
              droppingVideoSlice = true
              if (pusi) {
                requireVideoPesStart = true
              }
              if (pusi && currentVideoFrameStarted) {
                windowVideoFramesTotal += 1L
                if (!currentVideoFrameCorrupt) {
                  windowVideoFramesClean += 1L
                }
              }
              currentVideoFrameStarted = true
              currentVideoFrameCorrupt = true
            } else if (updated.audioPids.contains(pid)) {
              droppingAudioPids += pid
              if (pusi && currentAudioFrameStarted.contains(pid)) {
                windowAudioFramesTotal += 1L
                if (!currentAudioFrameCorrupt.contains(pid)) {
                  windowAudioFramesClean += 1L
                }
              }
              currentAudioFrameStarted += pid
              currentAudioFrameCorrupt += pid
            } else if (isElementaryPid) {
              droppingOtherPids += pid
            }
            val _ = outputBuilder ++= MPEGTS_NULL_PACKET
          } else if (pid == PatPid) {
            if (detectedPmtPid < 0 || updated.pat.isEmpty) {
              extractPmtPid(arr, pos) match {
                case Some(pmtPid) =>
                  val patPacket = ByteString.fromArray(arr, pos, PacketSize)
                  detectedPmtPid = pmtPid
                  candidatePat = None
                  updated = updated.copy(pat = Some(patPacket))
                case None => ()
              }
              emitWithOutboundCc(arr, pos, pid, hasPayload, outputBuilder)
            } else if (sameTablePacket(updated.pat, arr, pos)) {
              candidatePat = None
              emitWithOutboundCc(arr, pos, pid, hasPayload, outputBuilder)
            } else {
              extractPmtPid(arr, pos) match {
                case Some(pmtPid) if sameTablePacket(candidatePat, arr, pos) =>
                  val patPacket = ByteString.fromArray(arr, pos, PacketSize)
                  detectedPmtPid = pmtPid
                  candidatePat = None
                  updated = updated.copy(pat = Some(patPacket))
                  emitWithOutboundCc(arr, pos, pid, hasPayload, outputBuilder)
                case Some(_) =>
                  candidatePat = Some(ByteString.fromArray(arr, pos, PacketSize))
                  val goodPat = updated.pat.get.toArray
                  emitWithOutboundCc(goodPat, 0, pid, hasPayload = true, outputBuilder)
                case None =>
                  candidatePat = None
                  val goodPat = updated.pat.get.toArray
                  emitWithOutboundCc(goodPat, 0, pid, hasPayload = true, outputBuilder)
              }
            }
          } else if (pid == detectedPmtPid) {
            if (!parsedPmt || updated.pmt.isEmpty) {
              val streamPids = extractPmtStreamPids(arr, pos)
              if (streamPids.isDefined) {
                parsedPmt = true
                candidatePmt = None
                val pmtPacket = ByteString.fromArray(arr, pos, PacketSize)
                val nextAudioPids = streamPids.map(_.audioPids).filter(_.nonEmpty).getOrElse(updated.audioPids)
                val nextAudioStreamTypes = updated.audioStreamTypes ++ streamPids.map(_.audioStreamTypes).getOrElse(Map.empty)
                updated = updated.copy(
                  pmt = Some(pmtPacket)
                , pcrPid = streamPids.flatMap(_.pcrPid).orElse(updated.pcrPid)
                , videoPid = streamPids.flatMap(_.videoPid).orElse(updated.videoPid)
                , videoStreamType = streamPids.flatMap(_.videoStreamType).orElse(updated.videoStreamType)
                , audioPids = nextAudioPids
                , audioStreamTypes = nextAudioStreamTypes
                )
              }
              emitWithOutboundCc(arr, pos, pid, hasPayload, outputBuilder)
            } else if (sameTablePacket(updated.pmt, arr, pos)) {
              candidatePmt = None
              emitWithOutboundCc(arr, pos, pid, hasPayload, outputBuilder)
            } else {
              val streamPids = extractPmtStreamPids(arr, pos)
              val hasValidStreams = streamPids.exists { pids =>
                (updated.videoPid.isEmpty || pids.videoPid.isDefined) &&
                (updated.audioPids.isEmpty || pids.audioPids.nonEmpty)
              }
              if (hasValidStreams && sameTablePacket(candidatePmt, arr, pos)) {
                candidatePmt = None
                val pmtPacket = ByteString.fromArray(arr, pos, PacketSize)
                val pids = streamPids.get
                val nextAudioPids = if (pids.audioPids.nonEmpty) pids.audioPids else updated.audioPids
                val nextAudioStreamTypes = updated.audioStreamTypes ++ pids.audioStreamTypes
                updated = updated.copy(
                  pmt = Some(pmtPacket)
                , pcrPid = pids.pcrPid.orElse(updated.pcrPid)
                , videoPid = pids.videoPid.orElse(updated.videoPid)
                , videoStreamType = pids.videoStreamType.orElse(updated.videoStreamType)
                , audioPids = nextAudioPids
                , audioStreamTypes = nextAudioStreamTypes
                )
                emitWithOutboundCc(arr, pos, pid, hasPayload, outputBuilder)
              } else {
                candidatePmt = if (hasValidStreams) Some(ByteString.fromArray(arr, pos, PacketSize)) else None
                val goodPmt = updated.pmt.get.toArray
                emitWithOutboundCc(goodPmt, 0, pid, hasPayload = true, outputBuilder)
              }
            }
          } else if (updated.videoPid.contains(pid)) {
            val isMpeg2 = updated.videoStreamType.isEmpty || updated.videoStreamType.contains(0x01) || updated.videoStreamType.contains(0x02)
            val pesOff = if (pusi) FreezeFramePacer.findPesHeaderOffset(arr, pos) else -1
            val hasVideoPesStart = pusi && pesOff >= 0
            val esPayloadOffset =
              if (hasVideoPesStart && pesOff + 9 <= pos + PacketSize) {
                math.min(pos + PacketSize, pesOff + 9 + (arr(pesOff + 8) & 0xFF))
              } else {
                payloadOffset
              }
            val esPayloadLen = math.max(0, (pos + PacketSize) - esPayloadOffset)
            val hasOutOfBoundsSlice = !isTei && isMpeg2 && hasPayload && esPayloadLen > 0 &&
              FreezeFramePacer.hasOutOfBoundsMpeg2Slice(arr, esPayloadOffset, esPayloadLen, maxSliceRow)
            if (hasVideoPesStart && !freezeActive) {
              if (currentVideoFrameStarted) {
                windowVideoFramesTotal += 1L
                if (!currentVideoFrameCorrupt) {
                  windowVideoFramesClean += 1L
                }
              }
              currentVideoFrameStarted = true
              currentVideoFrameCorrupt = isCcError || hasOutOfBoundsSlice
            } else if (!freezeActive && (isCcError || hasOutOfBoundsSlice)) {
              currentVideoFrameStarted = true
              currentVideoFrameCorrupt = true
            }
            if (isCcError) {
              totalCcErrors += 1L
              windowCcErr += 1L
              droppingVideoSlice = true
              requireVideoPesStart = true
            }
            if (hasOutOfBoundsSlice) {
              totalClampedSlices += 1L
              windowSliceDrops += 1L
              droppingVideoSlice = true
              if (pusi) {
                requireVideoPesStart = true
              }
            }
            val isKeyStart = hasPayload && payloadLen > 0 && hasKeyframeHeader(arr, payloadOffset, payloadLen, updated.videoStreamType)
            if (freezeActive) {
              if (isKeyStart) {
                log.info(
                  "[mpegts] live keyframe resumed after freeze-frame pacing{} silenceMs={} bursts={}"
                , labelPrefix
                , (System.nanoTime() - lastLiveDataNanos) / 1000000L
                , freezeTicksCount
                )
                freezeActive = false
                freezeTicksCount = 0
                droppingVideoSlice = false
                requireVideoPesStart = false
                seenLiveKeyframe = true
                if (!currentVideoFrameStarted) {
                  currentVideoFrameStarted = true
                  currentVideoFrameCorrupt = isCcError || hasOutOfBoundsSlice
                }
              } else {
                val _ = outputBuilder ++= MPEGTS_NULL_PACKET
              }
            }

            if (!freezeActive) {
              var sliceStuffTargetOffset = -1
              if (droppingVideoSlice && !hasOutOfBoundsSlice) {
                if (hasVideoPesStart || isKeyStart) {
                  droppingVideoSlice = false
                  requireVideoPesStart = false
                } else if (!pusi && !requireVideoPesStart && !isCcError && hasPayload && payloadLen > 0) {
                  val validSliceOff = FreezeFramePacer.findFirstValidVideoSliceOffset(arr, payloadOffset, payloadLen, updated.videoStreamType, maxSliceRow)
                  if (validSliceOff >= 0) {
                    droppingVideoSlice = false
                    if (validSliceOff > payloadOffset) {
                      sliceStuffTargetOffset = validSliceOff
                    }
                  }
                }
              }

              if (droppingVideoSlice) {
                currentVideoFrameStarted = true
                currentVideoFrameCorrupt = true
                if (!hasOutOfBoundsSlice) {
                  windowSliceDrops += 1L
                }
                FreezeFramePacer.readPcrBase(arr, pos) match {
                  case Some(rawPcr) =>
                    val normPcr = (rawPcr + ptsDeltaTicks) & FreezeFramePacer.PtsMask
                    val pcrPkt = FreezeFramePacer.pcrOnlyPacket(pid, nextOutboundCc(pid, hasPayload = false), normPcr)
                    val _ = outputBuilder.putBytes(pcrPkt, 0, PacketSize)
                  case None =>
                    val _ = outputBuilder ++= MPEGTS_NULL_PACKET
                }
              } else {
                if (isKeyStart) {
                  seenLiveKeyframe = true
                  windowKeyframes += 1L
                }
                val now = System.nanoTime()
                if (pusi || isKeyStart) {
                  lastLiveDataNanos = now
                }
                if (sliceStuffTargetOffset > pos) {
                  val _ = FreezeFramePacer.stuffPacketPrefix(arr, pos, sliceStuffTargetOffset)
                }
                val pktAfc = (arr(pos + 3) & 0x30) >> 4
                val pktPayloadOffset =
                  if (pktAfc == 1) pos + 4
                  else if (pktAfc == 3) pos + 5 + (arr(pos + 4) & 0xFF)
                  else pos + PacketSize
                val pktPayloadLen = math.max(0, (pos + PacketSize) - pktPayloadOffset)
                if (isMpeg2 && pktPayloadLen > 0) {
                  if (lockedSeqHeader.isEmpty && pusi) {
                    FreezeFramePacer.parseMpeg2SequenceHeader(arr, pktPayloadOffset, pktPayloadLen).foreach { case (hdr, rows) =>
                      if (candidateSeqHeader.exists(c => c.take(7) == hdr.take(7))) {
                        maxSliceRow = rows
                        lockedSeqHeader = Some(hdr)
                      } else {
                        candidateSeqHeader = Some(hdr)
                      }
                    }
                  }
                  val effectiveSeqHdr = lockedSeqHeader.orElse(candidateSeqHeader)
                  if (effectiveSeqHdr.isDefined && updated.lockedSeqHeader != effectiveSeqHdr) {
                    updated = updated.copy(lockedSeqHeader = effectiveSeqHdr)
                  }
                  val clamped = FreezeFramePacer.sanitizeMpeg2Payload(arr, pktPayloadOffset, pktPayloadLen, maxSliceRow, lockedSeqHeader).toLong
                  if (clamped > 0L) {
                    totalClampedSlices += clamped
                    windowSliceDrops += clamped
                    currentVideoFrameStarted = true
                    currentVideoFrameCorrupt = true
                  }
                }
                recordInputPts(arr, pos, isVideo = true)
                normalizePacketTimestamps(arr, pos, isVideo = true)
                if (lastOutputPts >= 0) {
                  if (mediaClockAnchorPts < 0) {
                    mediaClockAnchorPts = lastOutputPts
                    mediaClockAnchorNanos = now
                  } else {
                    val elapsedTicks = ((now - mediaClockAnchorNanos) * 90000L) / 1000000000L
                    val expectedPts = (mediaClockAnchorPts + elapsedTicks) & FreezeFramePacer.PtsMask
                    if (signedPtsDiff(lastOutputPts, expectedPts) > 0) {
                      mediaClockAnchorPts = lastOutputPts
                      mediaClockAnchorNanos = now
                    }
                  }
                }
                emitWithOutboundCc(arr, pos, pid, hasPayload = (pktAfc == 1 || pktAfc == 3), outputBuilder)
              }
            }
          } else if (updated.audioPids.contains(pid)) {
            if (freezeActive) {
              val _ = outputBuilder ++= MPEGTS_NULL_PACKET
            } else {
              val streamTypeOpt = updated.audioStreamTypes.get(pid)
              if (isCcError) {
                totalCcErrors += 1L
                windowCcErr += 1L
                droppingAudioPids += pid
                currentAudioFrameStarted += pid
                currentAudioFrameCorrupt += pid
              }
              if (droppingAudioPids.contains(pid)) {
                if (pusi && hasPayload && payloadLen > 0 && FreezeFramePacer.hasValidAudioSyncword(arr, payloadOffset, payloadLen, streamTypeOpt)) {
                  droppingAudioPids -= pid
                }
              }
              if (pusi && hasPayload && payloadLen > 0) {
                if (currentAudioFrameStarted.contains(pid)) {
                  windowAudioFramesTotal += 1L
                  if (!currentAudioFrameCorrupt.contains(pid)) {
                    windowAudioFramesClean += 1L
                  }
                }
                currentAudioFrameStarted += pid
                if (droppingAudioPids.contains(pid) || isCcError) {
                  currentAudioFrameCorrupt += pid
                } else {
                  currentAudioFrameCorrupt -= pid
                }
              } else if (droppingAudioPids.contains(pid)) {
                currentAudioFrameStarted += pid
                currentAudioFrameCorrupt += pid
              }
              if (droppingAudioPids.contains(pid)) {
                val _ = outputBuilder ++= MPEGTS_NULL_PACKET
              } else {
                if (pusi && hasPayload && payloadLen > 0 && !learnedAudioFormats.contains(pid)) {
                  FreezeFramePacer.extractAudioFormat(arr, payloadOffset, payloadLen, streamTypeOpt).foreach { fmt =>
                    val _ = learnedAudioFormats.put(pid, fmt)
                  }
                }
                normalizePacketTimestamps(arr, pos, isVideo = false)
                if (updated.audioPids.headOption.contains(pid)) {
                  recordInputPts(arr, pos, isVideo = false)
                }
                emitWithOutboundCc(arr, pos, pid, hasPayload, outputBuilder)
              }
            }
          } else {
            if (isCcError) {
              totalCcErrors += 1L
              windowCcErr += 1L
              if (pusi) droppingOtherPids -= pid else droppingOtherPids += pid
            } else if (droppingOtherPids.contains(pid) && pusi) {
              droppingOtherPids -= pid
            }
            if (droppingOtherPids.contains(pid)) {
              val _ = outputBuilder ++= MPEGTS_NULL_PACKET
            } else {
              normalizePacketTimestamps(arr, pos, isVideo = false)
              emitWithOutboundCc(arr, pos, pid, hasPayload, outputBuilder)
            }
          }
        }
        updated
      }

      override def preStart(): Unit = {
        windowStartNanos = System.nanoTime()
        if (freezeThreshold.toNanos > 0 && freezeTickInterval.toNanos > 0) {
          scheduleWithFixedDelay("freeze-pacer", freezeTickInterval, freezeTickInterval)
        }
        if (metricsInterval.toNanos > 0) {
          scheduleWithFixedDelay("metrics-logger", metricsInterval, metricsInterval)
        }
      }

      override protected def onTimer(timerKey: Any): Unit = {
        if (timerKey == "metrics-logger") {
          emitMetricsWindow(System.nanoTime(), logOutput = true)
        } else if (isAvailable(out) && seenLiveKeyframe && freezeThreshold.toNanos > 0) {
          val now = System.nanoTime()
          val headers = cachedHeadersRef.get()
          headers.videoPid.foreach { vpid =>
            val videoSilenceNanos = now - lastLiveDataNanos
            val thresholdTicks = (freezeThreshold.toNanos * 90000L) / 1000000000L
            val ptsDeficitTicks =
              if (mediaClockAnchorPts >= 0 && lastOutputPts >= 0) {
                val elapsedTicks = ((now - mediaClockAnchorNanos) * 90000L) / 1000000000L
                val expectedPts = (mediaClockAnchorPts + elapsedTicks) & FreezeFramePacer.PtsMask
                signedPtsDiff(expectedPts, lastOutputPts)
              } else 0L
            val isSilent = videoSilenceNanos >= freezeThreshold.toNanos
            val isDeficit = ptsDeficitTicks >= thresholdTicks && videoSilenceNanos >= freezeTickInterval.toNanos
            if (isSilent || isDeficit) {
              if (!freezeActive) {
                log.info(
                  "[mpegts] freeze-frame pacing activated{} silenceMs={} ptsDeficitMs={}"
                , labelPrefix
                , videoSilenceNanos / 1000000L
                , (ptsDeficitTicks * 1000L) / 90000L
                )
              }
              freezeActive = true
              totalFreezeBursts += 1L
              val b = ByteString.newBuilder
              if (freezeTicksCount % 5 == 0) {
                headers.pat.foreach { patBs =>
                  val patArr = patBs.toArray
                  emitWithOutboundCc(patArr, 0, PatPid, hasPayload = true, b)
                }
                headers.pmt.foreach { pmtBs =>
                  val pmtArr = pmtBs.toArray
                  val pmtPid = ((pmtArr(1) & 0x1F) << 8) | (pmtArr(2) & 0xFF)
                  emitWithOutboundCc(pmtArr, 0, pmtPid, hasPayload = true, b)
                }
              }
              freezeTicksCount += 1
              val framesToEmit = if (isDeficit && !isSilent) math.min(6, math.max(3, (ptsDeficitTicks / FreezeFramePacer.DefaultVideoStepTicks).toInt)) else 3
              var f = 0
              while (f < framesToEmit) {
                val basePts = if (lastOutputPts >= 0) lastOutputPts else 90000L
                lastOutputPts = (basePts + FreezeFramePacer.DefaultVideoStepTicks) & FreezeFramePacer.PtsMask
                val _ = b ++= FreezeFramePacer.synthesizeVideoFreezeFrame(
                  vpid
                , headers.videoStreamType
                , lastOutputPts
                , includePcr = true
                , maxSliceRow
                , () => nextOutboundCc(vpid, hasPayload = true)
                )
                headers.audioPids.foreach { apid =>
                  val stOpt = headers.audioStreamTypes.get(apid)
                  val fmtOpt = learnedAudioFormats.get(apid)
                  val _ = b ++= FreezeFramePacer.synthesizeSilentAudioFrame(
                    apid
                  , stOpt
                  , fmtOpt
                  , lastOutputPts
                  , () => nextOutboundCc(apid, hasPayload = true)
                  )
                }
                f += 1
              }
              push(out, b.result())
            }
          }
        }
      }

      setHandler(in, new InHandler {
        override def onPush(): Unit = {
          val incoming = grab(in)
          val combined = if (carry.isEmpty) incoming else carry ++ incoming
          val len = combined.length
          val arr = combined.toArray
          var pos = 0
          var updated = cachedHeadersRef.get()
          val outputBuilder = ByteString.newBuilder
          outputBuilder.sizeHint(len)

          while (pos + PacketSize <= len) {
            if (arr(pos) == 0x47.toByte) {
              updated = processPacket(arr, pos, updated, outputBuilder)
              pos += PacketSize
            } else {
              val nextSync = findNextSync(arr, pos + 1, len)
              if (nextSync >= 0) {
                windowSyncLossBytes += (nextSync - pos).toLong
                pos = nextSync
              } else {
                val nextPos = len - PacketSize + 1
                windowSyncLossBytes += math.max(0, nextPos - pos).toLong
                pos = nextPos
              }
            }
          }

          carry =
            if (pos >= len) ByteString.empty
            else ByteString.fromArray(arr, pos, len - pos)

          val prev = cachedHeadersRef.get()
          if (updated != prev) {
            cachedHeadersRef.set(updated)
            onHeadersUpdated(updated)
          }

          if (!initialSnapshotPublished && latestMetricsRef.isDefined && windowRealPackets >= 100L) {
            initialSnapshotPublished = true
            val interim = buildCurrentWindowSnapshot(System.nanoTime(), resetHls = false)
            latestMetricsRef.foreach(_.set(interim))
          }

          val hasNewAnomalies =
            totalTeiPackets > lastLoggedTei ||
              totalCcErrors > lastLoggedCc ||
              totalClampedSlices > lastLoggedClamped ||
              totalScrubbedPts > lastLoggedScrubbed
          if (hasNewAnomalies) {
            val now = System.nanoTime()
            if (now - lastStatsLogNanos >= statsLogIntervalNanos) {
              log.warn(
                "[mpegts] transport anomalies{} tei={} (+{}) ccErrors={} (+{}) clampedSlices={} (+{}) scrubbedPts={} (+{})"
              , labelPrefix
              , totalTeiPackets
              , totalTeiPackets - lastLoggedTei
              , totalCcErrors
              , totalCcErrors - lastLoggedCc
              , totalClampedSlices
              , totalClampedSlices - lastLoggedClamped
              , totalScrubbedPts
              , totalScrubbedPts - lastLoggedScrubbed
              )
              lastLoggedTei = totalTeiPackets
              lastLoggedCc = totalCcErrors
              lastLoggedClamped = totalClampedSlices
              lastLoggedScrubbed = totalScrubbedPts
              lastStatsLogNanos = now
            }
          }

          val result = outputBuilder.result()
          if (result.nonEmpty) {
            push(out, result)
          } else {
            if (!hasBeenPulled(in)) {
              pull(in)
            }
          }
        }

        override def onUpstreamFinish(): Unit = {
          if (carry.length >= PacketSize) {
            val arr = carry.toArray
            var pos = 0
            var updated = cachedHeadersRef.get()
            val outputBuilder = ByteString.newBuilder
            outputBuilder.sizeHint(arr.length)
            while (pos + PacketSize <= arr.length) {
              if (arr(pos) == 0x47.toByte) {
                updated = processPacket(arr, pos, updated, outputBuilder)
                pos += PacketSize
              } else {
                val next = findNextSync(arr, pos + 1, arr.length)
                if (next >= 0) {
                  windowSyncLossBytes += (next - pos).toLong
                  pos = next
                } else {
                  windowSyncLossBytes += (arr.length - pos).toLong
                  pos = arr.length
                }
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
          if (windowRealPackets > 0L || windowNullPackets > 0L) {
            emitMetricsWindow(System.nanoTime(), logOutput = metricsInterval.toNanos > 0L)
          }
          if (totalTeiPackets > 0 || totalCcErrors > 0 || totalClampedSlices > 0 || totalScrubbedPts > 0 || totalFreezeBursts > 0) {
            log.info(
              "[mpegts] stream ended with transport stats:{} totalTei={} totalCcErrors={} totalClampedSlices={} totalScrubbedPts={} totalFreezeBursts={}"
            , labelPrefix
            , totalTeiPackets
            , totalCcErrors
            , totalClampedSlices
            , totalScrubbedPts
            , totalFreezeBursts
            )
          }
          completeStage()
        }
      })

      setHandler(out, new OutHandler {
        override def onPull(): Unit = {
          if (!hasBeenPulled(in)) {
            pull(in)
          }
        }
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
  , audioStreamTypes: Map[Int, Int] = Map.empty
  , lockedSeqHeader: Option[ByteString] = None
  , label: String = ""
  ): Flow[ByteString, ByteString, NotUsed] =
    Flow.fromGraph(new AlignToIFrameStage(videoPid, maxDroppedPackets, videoStreamType, audioPids, audioStreamTypes, lockedSeqHeader, label))

  private final class AlignToIFrameStage(
    videoPid: Option[Int]
  , maxDroppedPackets: Int
  , initialVideoStreamType: Option[Int]
  , initialAudioPids: Seq[Int]
  , initialAudioStreamTypes: Map[Int, Int]
  , lockedSeqHeader: Option[ByteString]
  , label: String
  ) extends GraphStage[FlowShape[ByteString, ByteString]] {
    val in: Inlet[ByteString] = Inlet("MpegTsSync.alignToIFrame.in")
    val out: Outlet[ByteString] = Outlet("MpegTsSync.alignToIFrame.out")
    override val shape: FlowShape[ByteString, ByteString] = FlowShape(in, out)

    override def createLogic(attrs: Attributes): GraphStageLogic = new GraphStageLogic(shape) {
      private val startTimeNanos: Long = System.nanoTime()
      private val labelPrefix: String = if (label.nonEmpty) s" label=$label" else ""
      private var carry: ByteString = ByteString.empty
      private var isAligned = false
      private var currentVideoPid: Option[Int] = videoPid
      private var currentVideoStreamType: Option[Int] = initialVideoStreamType
      private var knownAudioPids: Set[Int] = initialAudioPids.toSet
      private var currentAudioStreamTypes: Map[Int, Int] = initialAudioStreamTypes
      private var alignedAudioPids: Set[Int] = Set.empty
      private var detectedPmtPid: Int = -1
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
            val combined = if (carry.isEmpty) incoming else carry ++ incoming
            val len = combined.length
            val arr = combined.toArray
            var pos = 0
            val outputBuilder = ByteString.newBuilder

            while (pos + PacketSize <= len) {
              if (arr(pos) == 0x47.toByte) {
                val pid = ((arr(pos + 1) & 0x1F) << 8) | (arr(pos + 2) & 0xFF)
                if (pid == PatPid) {
                  extractPmtPid(arr, pos).foreach { pmtPid =>
                    detectedPmtPid = pmtPid
                  }
                  val _ = outputBuilder.putBytes(arr, pos, PacketSize)
                } else if (pid == detectedPmtPid) {
                  extractPmtStreamPids(arr, pos).foreach { pids =>
                    if (currentVideoPid.isEmpty) {
                      currentVideoPid = pids.videoPid
                    }
                    if (currentVideoStreamType.isEmpty) {
                      currentVideoStreamType = pids.videoStreamType
                    }
                    if (pids.audioPids.nonEmpty) {
                      knownAudioPids = knownAudioPids ++ pids.audioPids
                    }
                    if (pids.audioStreamTypes.nonEmpty) {
                      currentAudioStreamTypes = currentAudioStreamTypes ++ pids.audioStreamTypes
                    }
                  }
                  val _ = outputBuilder.putBytes(arr, pos, PacketSize)
                } else if (knownAudioPids.contains(pid)) {
                  if (alignedAudioPids.contains(pid)) {
                    val _ = outputBuilder.putBytes(arr, pos, PacketSize)
                  } else {
                    val pusi = (arr(pos + 1) & 0x40) != 0
                    if (pusi) {
                      val afc = (arr(pos + 3) & 0x30) >> 4
                      val payloadOffset =
                        if (afc == 1) pos + 4
                        else if (afc == 3) pos + 5 + (arr(pos + 4) & 0xFF)
                        else pos + PacketSize
                      val payloadLen = math.max(0, (pos + PacketSize) - payloadOffset)
                      val hasPes = FreezeFramePacer.findPesHeaderOffset(arr, pos) >= 0
                      val syncOk = !hasPes || !currentAudioStreamTypes.contains(pid) ||
                        (payloadLen > 0 && FreezeFramePacer.hasValidAudioSyncword(arr, payloadOffset, payloadLen, currentAudioStreamTypes.get(pid)))
                      if (syncOk) {
                        alignedAudioPids = alignedAudioPids + pid
                        val _ = outputBuilder.putBytes(arr, pos, PacketSize)
                      }
                    }
                  }
                } else if (isAligned) {
                  val _ = outputBuilder.putBytes(arr, pos, PacketSize)
                } else if (pid == NullPid) {
                  ()
                } else {
                  val afc = (arr(pos + 3) & 0x30) >> 4
                  val payloadOffset =
                    if (afc == 1) pos + 4
                    else if (afc == 3) pos + 5 + (arr(pos + 4) & 0xFF)
                    else pos + PacketSize
                  val payloadLen = math.max(0, (pos + PacketSize) - payloadOffset)

                  val isTargetVideo = currentVideoPid.contains(pid)
                  val isCandidateVideo = currentVideoPid.isEmpty && !knownAudioPids.contains(pid) && pid != NullPid && pid > 0
                  val isMpeg2 = currentVideoStreamType.isEmpty || currentVideoStreamType.contains(0x01) || currentVideoStreamType.contains(0x02)

                  if ((isTargetVideo || isCandidateVideo) && payloadLen > 0 && hasKeyframeHeader(arr, payloadOffset, payloadLen, currentVideoStreamType)) {
                    val resolvedVpid = currentVideoPid.getOrElse(pid)
                    if (currentVideoPid.isEmpty) currentVideoPid = Some(resolvedVpid)
                    isAligned = true
                    val elapsedMs = (System.nanoTime() - startTimeNanos) / 1000000
                    log.info(s"[mpegts]$labelPrefix Aligned to I-Frame on PID $resolvedVpid (dropped $droppedPackets leading packets in ${elapsedMs}ms)")
                    if (isMpeg2 && lockedSeqHeader.isDefined && !FreezeFramePacer.hasMpeg2SequenceHeader(arr, payloadOffset, payloadLen)) {
                      val prevCc = ((arr(pos + 3) & 0x0F) - 1) & 0x0F
                      val _ = outputBuilder ++= FreezeFramePacer.synthesizeSequenceHeaderPacket(resolvedVpid, lockedSeqHeader.get, prevCc)
                    }
                    val _ = outputBuilder.putBytes(arr, pos, PacketSize)
                  } else if (isTargetVideo || isCandidateVideo) {
                    droppedPackets += 1
                    if (droppedPackets > maxDroppedPackets) {
                      val elapsedMs = (System.nanoTime() - startTimeNanos) / 1000000
                      log.warn(s"[mpegts]$labelPrefix Max dropped packets ($droppedPackets in ${elapsedMs}ms) exceeded while waiting for I-Frame; forcing alignment")
                      isAligned = true
                      alignedAudioPids = knownAudioPids
                      if (isMpeg2 && lockedSeqHeader.isDefined && (payloadLen <= 0 || !FreezeFramePacer.hasMpeg2SequenceHeader(arr, payloadOffset, payloadLen))) {
                        val prevCc = ((arr(pos + 3) & 0x0F) - 1) & 0x0F
                        val _ = outputBuilder ++= FreezeFramePacer.synthesizeSequenceHeaderPacket(currentVideoPid.getOrElse(pid), lockedSeqHeader.get, prevCc)
                      }
                      val _ = outputBuilder.putBytes(arr, pos, PacketSize)
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

            if (!isAligned && len >= PacketSize && findNextSync(arr, 0, len) < 0) {
              isAligned = true
              alignedAudioPids = knownAudioPids
              outputBuilder ++= combined
              pos = len
            }

            carry =
              if (pos >= len) ByteString.empty
              else ByteString.fromArray(arr, pos, len - pos)
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

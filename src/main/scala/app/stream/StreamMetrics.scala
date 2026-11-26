package app.stream

import app.metrics.ServiceMetrics
import com.scalarometer.statistics.{Counter, Histogram}

import java.util.Locale

object StreamMetrics {
  val PacketSize: Int = 188
  val PtsTicksPerSecond: Double = 90000.0
  val BurstToleranceSec: Double = 2.0
  val BurstMinRatio: Double = 0.80

  final case class HlsWindowStats(
    segs: Long = 0L
  , maxGapMs: Long = 0L
  , shortReads: Long = 0L
  , seqGaps: Long = 0L
  , segRetries: Long = 0L
  , stallPolls: Long = 0L
  , retunes: Long = 0L
  , segGapP50Ms: Long = 0L
  , segGapP95Ms: Long = 0L
  , segFetchP95Ms: Long = 0L
  , segFetchMaxMs: Long = 0L
  )

  object HlsCollector {
    val MaxWindowSnapshots: Int = 128
  }

  final class HlsCollector(serviceMetrics: Option[ServiceMetrics] = None) {
    private val windowLock = new Object
    private val segsCounter = Counter("hls.segs")
    private val shortReadsCounter = Counter("hls.short_reads")
    private val seqGapsCounter = Counter("hls.seq_gaps")
    private val segRetriesCounter = Counter("hls.seg_retries")
    private val stallPollsCounter = Counter("hls.stall_polls")
    private val retunesCounter = Counter("hls.retunes")
    private val segGapHistogram = Histogram("hls.seg_gap.ms", max_snapshots = HlsCollector.MaxWindowSnapshots)
    private val segFetchHistogram = Histogram("hls.seg_fetch.ms", max_snapshots = HlsCollector.MaxWindowSnapshots)
    private var exactMaxGapMs: Long = 0L
    private var exactMaxFetchMs: Long = 0L

    def onSegmentCompleted(gapMs: Long, isShortRead: Boolean, fetchMs: Long = -1L): Unit = {
      windowLock.synchronized {
        val _ = segsCounter.increment()
        if (gapMs >= 0L) {
          val _ = segGapHistogram.incrementByAndRecordSnapshot(gapMs)
          if (gapMs > exactMaxGapMs) exactMaxGapMs = gapMs
        }
        if (fetchMs >= 0L) {
          val _ = segFetchHistogram.incrementByAndRecordSnapshot(fetchMs)
          if (fetchMs > exactMaxFetchMs) exactMaxFetchMs = fetchMs
        }
        if (isShortRead) {
          val _ = shortReadsCounter.increment()
        }
      }
      serviceMetrics.foreach(_.recordHlsSegmentCompleted(isShortRead, fetchMs))
    }

    def onPlaylistPolled(pollMs: Long): Unit =
      if (pollMs >= 0L) {
        serviceMetrics.foreach(_.recordHlsPollMs(pollMs))
      }

    def onSequenceGap(gap: Int): Unit =
      if (gap > 0) {
        windowLock.synchronized {
          val _ = seqGapsCounter.increment(gap.toLong)
        }
      }

    def onSegmentRetry(): Unit = {
      windowLock.synchronized {
        val _ = segRetriesCounter.increment()
      }
      serviceMetrics.foreach(_.recordHlsSegmentRetry())
    }

    def onStallPoll(): Unit = {
      windowLock.synchronized {
        val _ = stallPollsCounter.increment()
      }
      serviceMetrics.foreach(_.recordHlsStallPoll())
    }

    def onRetune(): Unit =
      windowLock.synchronized {
        val _ = retunesCounter.increment()
      }

    private def captureWindowStats(reset: Boolean): HlsWindowStats = {
      var segs = 0L
      var maxGap = 0L
      var shortReads = 0L
      var seqGaps = 0L
      var segRetries = 0L
      var stallPolls = 0L
      var retunes = 0L
      var maxFetch = 0L
      var gapSorted: Array[Long] = Array.emptyLongArray
      var fetchSorted: Array[Long] = Array.emptyLongArray

      windowLock.synchronized {
        segs = segsCounter.value
        maxGap = exactMaxGapMs
        shortReads = shortReadsCounter.value
        seqGaps = seqGapsCounter.value
        segRetries = segRetriesCounter.value
        stallPolls = stallPollsCounter.value
        retunes = retunesCounter.value
        maxFetch = exactMaxFetchMs
        gapSorted = segGapHistogram.snapshotsSortedArray
        fetchSorted = segFetchHistogram.snapshotsSortedArray
        if (reset) {
          segsCounter.reset()
          shortReadsCounter.reset()
          seqGapsCounter.reset()
          segRetriesCounter.reset()
          stallPollsCounter.reset()
          retunesCounter.reset()
          segGapHistogram.reset()
          segFetchHistogram.reset()
          exactMaxGapMs = 0L
          exactMaxFetchMs = 0L
        }
      }

      val gapP50 =
        if (gapSorted.nonEmpty) math.round(segGapHistogram.percentileValue(Histogram.Percentile.P_050, gapSorted))
        else 0L
      val gapP95 =
        if (gapSorted.nonEmpty) math.round(segGapHistogram.percentileValue(Histogram.Percentile.P_095, gapSorted))
        else 0L
      val fetchP95 =
        if (fetchSorted.nonEmpty) math.round(segFetchHistogram.percentileValue(Histogram.Percentile.P_095, fetchSorted))
        else 0L

      HlsWindowStats(
        segs = segs
      , maxGapMs = maxGap
      , shortReads = shortReads
      , seqGaps = seqGaps
      , segRetries = segRetries
      , stallPolls = stallPolls
      , retunes = retunes
      , segGapP50Ms = gapP50
      , segGapP95Ms = gapP95
      , segFetchP95Ms = fetchP95
      , segFetchMaxMs = maxFetch
      )
    }

    def currentWindowStats(): HlsWindowStats =
      captureWindowStats(reset = false)

    def snapshotAndResetWindow(): HlsWindowStats =
      captureWindowStats(reset = true)
  }

  final case class WindowSnapshot(
    windowSec: Double
  , signalQualityPct: Double
  , feedQualityPct: Double
  , symbolQualityPct: Double
  , bitrateMbps: Double
  , networkRateBps: Long = 0L
  , fps: Double
  , ptsAdvanceSec: Double
  , avSkewMs: Long
  , videoFramesClean: Long
  , videoFramesTotal: Long
  , audioFramesClean: Long
  , audioFramesTotal: Long
  , keyframes: Long
  , tei: Long
  , ccErr: Long
  , sliceDrops: Long
  , ptsScrubs: Long
  , syncLossBytes: Long = 0L
  , keepalivePct: Double = 0.0
  , segs: Long = 0L
  , maxGapMs: Long = 0L
  , shortReads: Long = 0L
  , seqGaps: Long = 0L
  , segRetries: Long = 0L
  , stallPolls: Long = 0L
  , retunes: Long = 0L
  , hls: HlsWindowStats = HlsWindowStats()
  , segGapP50Ms: Long = 0L
  , segGapP95Ms: Long = 0L
  , segFetchP95Ms: Long = 0L
  , segFetchMaxMs: Long = 0L
  ) {
    def signalStrengthPercentInt: Int =
      math.round(feedQualityPct).toInt.max(0).min(100)

    def signalQualityPercentInt: Int =
      math.round(signalQualityPct).toInt.max(0).min(100)

    def symbolQualityPercentInt: Int =
      math.round(symbolQualityPct).toInt.max(0).min(100)

    def formatLogLine(label: String): String =
      String.format(
        Locale.ROOT
      , "[stream:metrics] channel=\"%s\" windowSec=%.1f signalQualityPct=%.1f feedQualityPct=%.1f symbolQualityPct=%.1f bitrateMbps=%.2f fps=%.2f ptsAdvanceSec=%.1f avSkewMs=%d videoFrames=%d/%d audioFrames=%d/%d keyframes=%d tei=%d ccErr=%d sliceDrops=%d ptsScrubs=%d syncLossBytes=%d keepalivePct=%.1f segs=%d maxGapMs=%d shortReads=%d seqGaps=%d segRetries=%d stallPolls=%d retunes=%d segGapP50Ms=%d segGapP95Ms=%d segFetchP95Ms=%d segFetchMaxMs=%d"
      , label
      , java.lang.Double.valueOf(windowSec)
      , java.lang.Double.valueOf(signalQualityPct)
      , java.lang.Double.valueOf(feedQualityPct)
      , java.lang.Double.valueOf(symbolQualityPct)
      , java.lang.Double.valueOf(bitrateMbps)
      , java.lang.Double.valueOf(fps)
      , java.lang.Double.valueOf(ptsAdvanceSec)
      , java.lang.Long.valueOf(avSkewMs)
      , java.lang.Long.valueOf(videoFramesClean)
      , java.lang.Long.valueOf(videoFramesTotal)
      , java.lang.Long.valueOf(audioFramesClean)
      , java.lang.Long.valueOf(audioFramesTotal)
      , java.lang.Long.valueOf(keyframes)
      , java.lang.Long.valueOf(tei)
      , java.lang.Long.valueOf(ccErr)
      , java.lang.Long.valueOf(sliceDrops)
      , java.lang.Long.valueOf(ptsScrubs)
      , java.lang.Long.valueOf(syncLossBytes)
      , java.lang.Double.valueOf(keepalivePct)
      , java.lang.Long.valueOf(segs)
      , java.lang.Long.valueOf(maxGapMs)
      , java.lang.Long.valueOf(shortReads)
      , java.lang.Long.valueOf(seqGaps)
      , java.lang.Long.valueOf(segRetries)
      , java.lang.Long.valueOf(stallPolls)
      , java.lang.Long.valueOf(retunes)
      , java.lang.Long.valueOf(segGapP50Ms)
      , java.lang.Long.valueOf(segGapP95Ms)
      , java.lang.Long.valueOf(segFetchP95Ms)
      , java.lang.Long.valueOf(segFetchMaxMs)
      )
  }

  object WindowSnapshot {
    val initial: WindowSnapshot = WindowSnapshot(
      windowSec = 0.0
    , signalQualityPct = 100.0
    , feedQualityPct = 100.0
    , symbolQualityPct = 100.0
    , bitrateMbps = 0.0
    , networkRateBps = 0L
    , fps = 0.0
    , ptsAdvanceSec = 0.0
    , avSkewMs = 0L
    , videoFramesClean = 0L
    , videoFramesTotal = 0L
    , audioFramesClean = 0L
    , audioFramesTotal = 0L
    , keyframes = 0L
    , tei = 0L
    , ccErr = 0L
    , sliceDrops = 0L
    , ptsScrubs = 0L
    )

    def compute(
      windowSec: Double
    , realPackets: Long
    , nullPackets: Long
    , elementaryPackets: Long
    , errorPackets: Long
    , videoFramesClean: Long
    , videoFramesTotal: Long
    , audioFramesClean: Long
    , audioFramesTotal: Long
    , keyframes: Long
    , tei: Long
    , ccErr: Long
    , sliceDrops: Long
    , ptsScrubs: Long
    , syncLossBytes: Long
    , ptsAdvanceTicks: Long
    , avSkewMs: Long
    , hls: HlsWindowStats = HlsWindowStats()
    ): WindowSnapshot = {
      val safeWindowSec = math.max(0.001, windowSec)
      val symbolQualityPct =
        if (elementaryPackets > 0L) {
          val cleanElem = math.max(0L, elementaryPackets - errorPackets)
          (cleanElem.toDouble * 100.0) / elementaryPackets.toDouble
        } else if (realPackets > 0L) {
          val cleanReal = math.max(0L, realPackets - errorPackets)
          (cleanReal.toDouble * 100.0) / realPackets.toDouble
        } else {
          0.0
        }

      val signalQualityPct =
        if (videoFramesTotal > 0L && audioFramesTotal > 0L) {
          val vPct = (videoFramesClean.toDouble * 100.0) / videoFramesTotal.toDouble
          val aPct = (audioFramesClean.toDouble * 100.0) / audioFramesTotal.toDouble
          (0.8 * vPct) + (0.2 * aPct)
        } else if (videoFramesTotal > 0L) {
          (videoFramesClean.toDouble * 100.0) / videoFramesTotal.toDouble
        } else if (audioFramesTotal > 0L) {
          (audioFramesClean.toDouble * 100.0) / audioFramesTotal.toDouble
        } else if (realPackets > 0L) {
          symbolQualityPct
        } else {
          0.0
        }

      val totalFrames = videoFramesTotal + audioFramesTotal
      val ptsAdvanceSec = math.max(0.0, ptsAdvanceTicks.toDouble / PtsTicksPerSecond)
      val noHlsAnomalies =
        hls.stallPolls == 0L && hls.shortReads == 0L && hls.seqGaps == 0L && hls.retunes == 0L

      val deliveryRatio =
        if (realPackets <= 0L) {
          0.0
        } else if (ptsAdvanceTicks <= 0L && totalFrames == 0L) {
          if (noHlsAnomalies) 1.0 else 0.0
        } else if (
          noHlsAnomalies &&
          ptsAdvanceSec >= math.max(safeWindowSec * BurstMinRatio, safeWindowSec - BurstToleranceSec)
        ) {
          1.0
        } else if (ptsAdvanceTicks > 0L) {
          math.min(1.0, math.max(0.0, ptsAdvanceSec / safeWindowSec))
        } else {
          1.0
        }

      val feedQualityPct = math.min(100.0, math.max(0.0, deliveryRatio * 100.0))
      val bpsDouble = (realPackets.toDouble * PacketSize.toDouble * 8.0) / safeWindowSec
      val networkRateBps = math.round(bpsDouble).max(0L)
      val bitrateMbps = bpsDouble / 1000000.0
      val fps = videoFramesTotal.toDouble / safeWindowSec
      val totalPackets = realPackets + nullPackets
      val keepalivePct =
        if (totalPackets > 0L) (nullPackets.toDouble * 100.0) / totalPackets.toDouble
        else 0.0

      WindowSnapshot(
        windowSec = windowSec
      , signalQualityPct = signalQualityPct
      , feedQualityPct = feedQualityPct
      , symbolQualityPct = symbolQualityPct
      , bitrateMbps = bitrateMbps
      , networkRateBps = networkRateBps
      , fps = fps
      , ptsAdvanceSec = ptsAdvanceSec
      , avSkewMs = avSkewMs
      , videoFramesClean = videoFramesClean
      , videoFramesTotal = videoFramesTotal
      , audioFramesClean = audioFramesClean
      , audioFramesTotal = audioFramesTotal
      , keyframes = keyframes
      , tei = tei
      , ccErr = ccErr
      , sliceDrops = sliceDrops
      , ptsScrubs = ptsScrubs
      , syncLossBytes = syncLossBytes
      , keepalivePct = keepalivePct
      , segs = hls.segs
      , maxGapMs = hls.maxGapMs
      , shortReads = hls.shortReads
      , seqGaps = hls.seqGaps
      , segRetries = hls.segRetries
      , stallPolls = hls.stallPolls
      , retunes = hls.retunes
      , hls = hls
      , segGapP50Ms = hls.segGapP50Ms
      , segGapP95Ms = hls.segGapP95Ms
      , segFetchP95Ms = hls.segFetchP95Ms
      , segFetchMaxMs = hls.segFetchMaxMs
      )
    }
  }
}

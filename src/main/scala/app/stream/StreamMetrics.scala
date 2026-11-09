package app.stream

import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

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
  )

  final class HlsCollector {
    private val segsCounter = new AtomicLong(0L)
    private val maxGapMsGauge = new AtomicLong(0L)
    private val shortReadsCounter = new AtomicLong(0L)
    private val seqGapsCounter = new AtomicLong(0L)
    private val segRetriesCounter = new AtomicLong(0L)
    private val stallPollsCounter = new AtomicLong(0L)
    private val retunesCounter = new AtomicLong(0L)

    def onSegmentCompleted(gapMs: Long, isShortRead: Boolean): Unit = {
      val _ = segsCounter.incrementAndGet()
      if (gapMs > 0L) {
        val _ = maxGapMsGauge.accumulateAndGet(gapMs, math.max)
      }
      if (isShortRead) {
        val _ = shortReadsCounter.incrementAndGet()
      }
    }

    def onSequenceGap(gap: Int): Unit =
      if (gap > 0) {
        val _ = seqGapsCounter.addAndGet(gap.toLong)
      }

    def onSegmentRetry(): Unit = {
      val _ = segRetriesCounter.incrementAndGet()
    }

    def onStallPoll(): Unit = {
      val _ = stallPollsCounter.incrementAndGet()
    }

    def onRetune(): Unit = {
      val _ = retunesCounter.incrementAndGet()
    }

    def currentWindowStats(): HlsWindowStats =
      HlsWindowStats(
        segs = segsCounter.get()
      , maxGapMs = maxGapMsGauge.get()
      , shortReads = shortReadsCounter.get()
      , seqGaps = seqGapsCounter.get()
      , segRetries = segRetriesCounter.get()
      , stallPolls = stallPollsCounter.get()
      , retunes = retunesCounter.get()
      )

    def snapshotAndResetWindow(): HlsWindowStats =
      HlsWindowStats(
        segs = segsCounter.getAndSet(0L)
      , maxGapMs = maxGapMsGauge.getAndSet(0L)
      , shortReads = shortReadsCounter.getAndSet(0L)
      , seqGaps = seqGapsCounter.getAndSet(0L)
      , segRetries = segRetriesCounter.getAndSet(0L)
      , stallPolls = stallPollsCounter.getAndSet(0L)
      , retunes = retunesCounter.getAndSet(0L)
      )
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
      , "[stream:metrics] channel=\"%s\" windowSec=%.1f signalQualityPct=%.1f feedQualityPct=%.1f symbolQualityPct=%.1f bitrateMbps=%.2f fps=%.2f ptsAdvanceSec=%.1f avSkewMs=%d videoFrames=%d/%d audioFrames=%d/%d keyframes=%d tei=%d ccErr=%d sliceDrops=%d ptsScrubs=%d syncLossBytes=%d keepalivePct=%.1f segs=%d maxGapMs=%d shortReads=%d seqGaps=%d segRetries=%d stallPolls=%d retunes=%d"
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
      )
    }
  }
}

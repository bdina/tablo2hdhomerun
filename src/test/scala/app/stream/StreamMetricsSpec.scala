package app.stream

import app.tuner.HDHomeRunStatus
import org.junit.runner.RunWith
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.junit.JUnitRunner
import spray.json._

@RunWith(classOf[JUnitRunner])
class StreamMetricsSpec extends AnyFlatSpec with Matchers {

  "StreamMetrics.WindowSnapshot.compute" should "report 100% signal, feed, and symbol quality for a clean 30s window" in {
    val hls = StreamMetrics.HlsWindowStats(segs = 15, maxGapMs = 1980L)
    val snap = StreamMetrics.WindowSnapshot.compute(
      windowSec = 30.0
    , realPackets = 124500L
    , nullPackets = 0L
    , elementaryPackets = 120000L
    , errorPackets = 0L
    , videoFramesClean = 1798L
    , videoFramesTotal = 1798L
    , audioFramesClean = 937L
    , audioFramesTotal = 937L
    , keyframes = 30L
    , tei = 0L
    , ccErr = 0L
    , sliceDrops = 0L
    , ptsScrubs = 0L
    , syncLossBytes = 0L
    , ptsAdvanceTicks = 30L * 90000L
    , avSkewMs = -12L
    , hls = hls
    )

    val _ = snap.signalQualityPct shouldBe 100.0
    val _ = snap.feedQualityPct shouldBe 100.0
    val _ = snap.symbolQualityPct shouldBe 100.0
    val _ = snap.signalStrengthPercentInt shouldBe 100
    val _ = snap.signalQualityPercentInt shouldBe 100
    val _ = snap.symbolQualityPercentInt shouldBe 100
    val _ = snap.keepalivePct shouldBe 0.0
    val _ = snap.ptsAdvanceSec shouldBe 30.0
    val _ = snap.fps shouldBe (1798.0 / 30.0 +- 0.01)
    val _ = snap.bitrateMbps shouldBe ((124500.0 * 188.0 * 8.0) / 30.0 / 1000000.0 +- 0.01)
    snap.networkRateBps shouldBe math.round((124500.0 * 188.0 * 8.0) / 30.0)
  }

  it should "weight signalQualityPct 80% video and 20% audio when both are present, and handle single-stream windows" in {
    val both = StreamMetrics.WindowSnapshot.compute(
      windowSec = 10.0
    , realPackets = 1000L
    , nullPackets = 0L
    , elementaryPackets = 900L
    , errorPackets = 90L
    , videoFramesClean = 50L
    , videoFramesTotal = 100L // 50% clean
    , audioFramesClean = 100L
    , audioFramesTotal = 100L // 100% clean
    , keyframes = 5L
    , tei = 10L
    , ccErr = 80L
    , sliceDrops = 0L
    , ptsScrubs = 0L
    , syncLossBytes = 0L
    , ptsAdvanceTicks = 5L * 90000L // 5s advance in 10s window (5s < 10s - 2s tolerance => 50%)
    , avSkewMs = 0L
    , hls = StreamMetrics.HlsWindowStats()
    )

    // 0.8 * 50% + 0.2 * 100% = 60%
    val _ = both.signalQualityPct shouldBe (60.0 +- 0.01)
    val _ = both.feedQualityPct shouldBe (50.0 +- 0.01)
    val _ = both.symbolQualityPct shouldBe (90.0 +- 0.01)

    val videoOnly = StreamMetrics.WindowSnapshot.compute(
      windowSec = 10.0
    , realPackets = 1000L
    , nullPackets = 0L
    , elementaryPackets = 900L
    , errorPackets = 0L
    , videoFramesClean = 75L
    , videoFramesTotal = 100L
    , audioFramesClean = 0L
    , audioFramesTotal = 0L
    , keyframes = 5L
    , tei = 0L
    , ccErr = 0L
    , sliceDrops = 0L
    , ptsScrubs = 0L
    , syncLossBytes = 0L
    , ptsAdvanceTicks = 9L * 90000L // 9s in 10s window is within 2s 1-segment burst tolerance => 100%
    , avSkewMs = 0L
    , hls = StreamMetrics.HlsWindowStats()
    )
    val _ = videoOnly.signalQualityPct shouldBe (75.0 +- 0.01)
    val _ = videoOnly.feedQualityPct shouldBe 100.0

    val stalled = StreamMetrics.WindowSnapshot.compute(
      windowSec = 30.0
    , realPackets = 0L
    , nullPackets = 750L
    , elementaryPackets = 0L
    , errorPackets = 0L
    , videoFramesClean = 0L
    , videoFramesTotal = 0L
    , audioFramesClean = 0L
    , audioFramesTotal = 0L
    , keyframes = 0L
    , tei = 0L
    , ccErr = 0L
    , sliceDrops = 0L
    , ptsScrubs = 0L
    , syncLossBytes = 0L
    , ptsAdvanceTicks = 0L
    , avSkewMs = 0L
    , hls = StreamMetrics.HlsWindowStats(stallPolls = 15)
    )
    val _ = stalled.signalQualityPct shouldBe 0.0
    val _ = stalled.feedQualityPct shouldBe 0.0
    val _ = stalled.symbolQualityPct shouldBe 0.0
    stalled.keepalivePct shouldBe 100.0
  }

  "StreamMetrics.WindowSnapshot.formatLogLine" should "format all key=value operational metrics" in {
    val hls = StreamMetrics.HlsWindowStats(
      segs = 15
    , maxGapMs = 2110L
    , shortReads = 1
    , seqGaps = 2
    , segRetries = 3
    , stallPolls = 4
    , retunes = 1
    , segGapP50Ms = 1990L
    , segGapP95Ms = 2080L
    , segFetchP95Ms = 85L
    , segFetchMaxMs = 112L
    )
    val snap = StreamMetrics.WindowSnapshot.compute(
      windowSec = 30.0
    , realPackets = 124500L
    , nullPackets = 0L
    , elementaryPackets = 120000L
    , errorPackets = 120L
    , videoFramesClean = 1790L
    , videoFramesTotal = 1800L
    , audioFramesClean = 937L
    , audioFramesTotal = 937L
    , keyframes = 30L
    , tei = 2L
    , ccErr = 5L
    , sliceDrops = 3L
    , ptsScrubs = 1L
    , syncLossBytes = 188L
    , ptsAdvanceTicks = 30L * 90000L
    , avSkewMs = -12L
    , hls = hls
    )
    val line = snap.formatLogLine("CBS 2.1 (a1b2c3d4)")
    val _ = line should startWith("[stream:metrics] channel=\"CBS 2.1 (a1b2c3d4)\"")
    val _ = line should include("windowSec=30.0")
    val _ = line should include("signalQualityPct=99.6")
    val _ = line should include("feedQualityPct=100.0")
    val _ = line should include("symbolQualityPct=99.9")
    val _ = line should include("videoFrames=1790/1800")
    val _ = line should include("audioFrames=937/937")
    val _ = line should include("keyframes=30")
    val _ = line should include("tei=2 ccErr=5 sliceDrops=3 ptsScrubs=1 syncLossBytes=188")
    val _ = line should include("segs=15 maxGapMs=2110 shortReads=1 seqGaps=2 segRetries=3 stallPolls=4 retunes=1")
    line should include("segGapP50Ms=1990 segGapP95Ms=2080 segFetchP95Ms=85 segFetchMaxMs=112")
  }

  "StreamMetrics.HlsCollector" should "accumulate percentiles/counters, fan out to ServiceMetrics, and atomically reset" in {
    val serviceMetrics = new app.metrics.ServiceMetrics()
    val collector = new StreamMetrics.HlsCollector(Some(serviceMetrics))
    collector.onSegmentCompleted(gapMs = 1800L, isShortRead = false, fetchMs = 40L)
    collector.onSegmentCompleted(gapMs = 2450L, isShortRead = true, fetchMs = 120L)
    collector.onSegmentCompleted(gapMs = 1950L, isShortRead = false, fetchMs = 60L)
    collector.onSegmentCompleted(gapMs = 2000L, isShortRead = false) // default fetchMs = -1L ignored
    collector.onPlaylistPolled(15L)
    collector.onSequenceGap(3)
    collector.onSegmentRetry()
    collector.onSegmentRetry()
    collector.onStallPoll()
    collector.onRetune()

    val peek = collector.currentWindowStats()
    val _ = peek.segs shouldBe 4
    val _ = peek.maxGapMs shouldBe 2450L
    val _ = peek.shortReads shouldBe 1
    val _ = peek.seqGaps shouldBe 3
    val _ = peek.segRetries shouldBe 2
    val _ = peek.stallPolls shouldBe 1
    val _ = peek.retunes shouldBe 1
    val _ = peek.segGapP50Ms shouldBe 1975L
    val _ = peek.segGapP95Ms should be >= 2000L
    val _ = peek.segFetchMaxMs shouldBe 120L
    val _ = peek.segFetchP95Ms should be >= 60L

    // ServiceMetrics receives lifetime fan-out without conflating onRetune() with cold retunes
    val _ = serviceMetrics.hlsSegsCompleted.value shouldBe 4L
    val _ = serviceMetrics.hlsSegsShortReads.value shouldBe 1L
    val _ = serviceMetrics.hlsSegsRetries.value shouldBe 2L
    val _ = serviceMetrics.hlsStallPolls.value shouldBe 1L
    val _ = serviceMetrics.retunesCold.value shouldBe 0L
    val _ = serviceMetrics.hlsSegFetchMs.numberOfSnapshots shouldBe 3
    val _ = serviceMetrics.hlsPollMs.max shouldBe 15L

    val snap = collector.snapshotAndResetWindow()
    val _ = snap shouldBe peek

    val afterReset = collector.currentWindowStats()
    afterReset shouldBe StreamMetrics.HlsWindowStats()
  }

  it should "preserve exact maxGapMs and segFetchMaxMs even past MaxWindowSnapshots" in {
    val collector = new StreamMetrics.HlsCollector()
    var i = 0
    while (i < StreamMetrics.HlsCollector.MaxWindowSnapshots + 10) {
      collector.onSegmentCompleted(gapMs = 1000L + i.toLong, isShortRead = false, fetchMs = 20L + i.toLong)
      i += 1
    }
    val stats = collector.snapshotAndResetWindow()
    val _ = stats.segs shouldBe (StreamMetrics.HlsCollector.MaxWindowSnapshots + 10).toLong
    val _ = stats.maxGapMs shouldBe (1000L + StreamMetrics.HlsCollector.MaxWindowSnapshots + 9).toLong
    stats.segFetchMaxMs shouldBe (20L + StreamMetrics.HlsCollector.MaxWindowSnapshots + 9).toLong
  }

  "HDHomeRunStatus" should "serialize active and idle tuners in SiliconDust status.json format" in {
    import HDHomeRunStatus.tunerStatusFormat

    val activeSnap = StreamMetrics.WindowSnapshot.compute(
      windowSec = 10.0
    , realPackets = 41500L
    , nullPackets = 0L
    , elementaryPackets = 40000L
    , errorPackets = 0L
    , videoFramesClean = 600L
    , videoFramesTotal = 600L
    , audioFramesClean = 312L
    , audioFramesTotal = 312L
    , keyframes = 10L
    , tei = 0L
    , ccErr = 0L
    , sliceDrops = 0L
    , ptsScrubs = 0L
    , syncLossBytes = 0L
    , ptsAdvanceTicks = 10L * 90000L
    , avSkewMs = 0L
    , hls = StreamMetrics.HlsWindowStats()
    )

    val activeInfo = HDHomeRunStatus.ActiveTunerInfo(
      channelId = "ch-1"
    , vctNumber = "2.1"
    , vctName = "CBS"
    , snapshot = activeSnap
    )

    val jsArr = HDHomeRunStatus.buildStatusArray(totalTuners = 2, active = Seq(activeInfo))
    val elements = jsArr.elements
    val _ = elements.size shouldBe 2

    val t0 = elements(0).asJsObject.fields
    val _ = t0("Resource") shouldBe JsString("tuner0")
    val _ = t0("VctNumber") shouldBe JsString("2.1")
    val _ = t0("VctName") shouldBe JsString("CBS")
    val _ = t0("SignalStrengthPercent") shouldBe JsNumber(100)
    val _ = t0("SignalQualityPercent") shouldBe JsNumber(100)
    val _ = t0("SymbolQualityPercent") shouldBe JsNumber(100)
    val _ = t0("NetworkRate") shouldBe JsNumber(activeSnap.networkRateBps)
    val _ = t0("TargetIP") shouldBe JsString("http")

    // Idle tuner must only have Resource
    val t1 = elements(1).asJsObject.fields
    val _ = t1.keySet shouldBe Set("Resource")
    val _ = t1("Resource") shouldBe JsString("tuner1")

    // Round-trip deserialization
    val parsed0 = elements(0).convertTo[HDHomeRunStatus.TunerStatus]
    val _ = parsed0.Resource shouldBe "tuner0"
    val _ = parsed0.VctNumber shouldBe Some("2.1")
    val _ = parsed0.SignalQualityPercent shouldBe Some(100)
    val parsed1 = elements(1).convertTo[HDHomeRunStatus.TunerStatus]
    val _ = parsed1.Resource shouldBe "tuner1"
    parsed1.VctNumber shouldBe None
  }
}


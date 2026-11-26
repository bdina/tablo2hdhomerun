package app.metrics

import org.apache.pekko.http.scaladsl.model.{ContentTypes, StatusCodes}
import org.apache.pekko.http.scaladsl.testkit.ScalatestRouteTest
import org.junit.runner.RunWith
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.junit.JUnitRunner
import spray.json._

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

@RunWith(classOf[JUnitRunner])
class ServiceMetricsSpec extends AnyFlatSpec with Matchers with ScalatestRouteTest {

  "ServiceMetrics" should "preserve fixed lifetime counters while resetting interval latency distributions" in {
    val metrics = new ServiceMetrics()
    metrics.recordSessionOpened()
    metrics.recordSessionAttached()
    metrics.recordIdleGraceReused()
    metrics.recordIdleGraceEvicted()
    metrics.recordRejectedNoTuners()
    metrics.recordRejectedFailed()
    metrics.recordColdRetune()
    metrics.recordAudioLossRetune()
    metrics.recordStreamFreeze()
    metrics.recordHlsSegmentCompleted(isShortRead = true, fetchMs = 45L)
    metrics.recordHlsSegmentCompleted(isShortRead = false, fetchMs = 95L)
    metrics.recordHlsSegmentCompleted(isShortRead = false, fetchMs = -1L)
    metrics.recordHlsSegmentRetry()
    metrics.recordHlsStallPoll()
    metrics.recordHlsProbeClosed()
    metrics.recordAuthRefresh()
    metrics.recordTuneLockMs(8500L)
    metrics.recordTuneLockMs(-10L)
    metrics.recordHlsPollMs(12L)
    metrics.recordHlsPollMs(24L)
    metrics.recordHlsPollMs(-1L)
    metrics.recordKeepaliveProbeMs(18L)
    metrics.recordKeepaliveProbeMs(30L)

    val beforeReset = metrics.registry.stats(reset = true)
    val _ = beforeReset("sessions.opened").longValue() shouldBe 1L
    val _ = beforeReset("sessions.attached").longValue() shouldBe 1L
    val _ = beforeReset("sessions.idle_grace_reused").longValue() shouldBe 1L
    val _ = beforeReset("sessions.idle_grace_evicted").longValue() shouldBe 1L
    val _ = beforeReset("sessions.rejected.no_tuners").longValue() shouldBe 1L
    val _ = beforeReset("sessions.rejected.failed").longValue() shouldBe 1L
    val _ = beforeReset("retunes.cold").longValue() shouldBe 1L
    val _ = beforeReset("retunes.audio_loss").longValue() shouldBe 1L
    val _ = beforeReset("stream.freezes").longValue() shouldBe 1L
    val _ = beforeReset("hls.segs.completed").longValue() shouldBe 3L
    val _ = beforeReset("hls.segs.short_reads").longValue() shouldBe 1L
    val _ = beforeReset("hls.segs.retries").longValue() shouldBe 1L
    val _ = beforeReset("hls.stall_polls").longValue() shouldBe 1L
    val _ = beforeReset("hls.probes.closed").longValue() shouldBe 1L
    val _ = beforeReset("auth.refreshes").longValue() shouldBe 1L
    val _ = beforeReset("tune.lock.ms.0.5").doubleValue() shouldBe 8500.0
    val _ = beforeReset("hls.seg.fetch.ms.0.5").doubleValue() shouldBe 70.0
    val _ = beforeReset("hls.poll.ms.min").longValue() shouldBe 12L
    val _ = beforeReset("hls.poll.ms.max").longValue() shouldBe 24L
    val _ = beforeReset("hls.poll.ms.avg").doubleValue() shouldBe 18.0
    val _ = beforeReset("keepalive.probe.ms.min").longValue() shouldBe 18L
    val _ = beforeReset("keepalive.probe.ms.max").longValue() shouldBe 30L

    val afterReset = metrics.registry.stats(reset = false)
    // Fixed counters persist across reset
    val _ = afterReset("sessions.opened").longValue() shouldBe 1L
    val _ = afterReset("hls.segs.completed").longValue() shouldBe 3L
    // Resettable distributions clear cleanly
    val _ = metrics.tuneLockMs.isEmpty shouldBe true
    val _ = metrics.hlsSegFetchMs.isEmpty shouldBe true
    val _ = metrics.hlsPollMs.isEmpty shouldBe true
    metrics.keepaliveProbeMs.isEmpty shouldBe true
  }

  it should "register scoped session gauges and cleanly unregister them on close" in {
    val metrics = new ServiceMetrics()
    val live = new AtomicInteger(2)
    val opening = new AtomicInteger(1)
    val idle = new AtomicInteger(1)
    val clients = new AtomicInteger(3)

    val handle = metrics.registerSessionGauges(
      tunersCapacity = () => 4
    , sessionsLive = () => live.get()
    , sessionsOpening = () => opening.get()
    , sessionsIdleGrace = () => idle.get()
    , clientsActive = () => clients.get()
    )

    val json = metrics.formatJson().parseJson.asJsObject.fields
    val _ = json("tuners.capacity") shouldBe JsNumber(4)
    val _ = json("sessions.live") shouldBe JsNumber(2)
    val _ = json("sessions.opening") shouldBe JsNumber(1)
    val _ = json("sessions.idle_grace") shouldBe JsNumber(1)
    val _ = json("clients.active") shouldBe JsNumber(3)

    live.set(0)
    clients.set(0)
    val updated = metrics.formatLogfmt()
    val _ = updated should include("sessions.live=0")
    val _ = updated should include("clients.active=0")

    handle.close()
    handle.close() // idempotent
    val _ = handle.isClosed shouldBe true
    val _ = metrics.registry.contains("tuners.capacity") shouldBe false
    val _ = metrics.registry.contains("sessions.live") shouldBe false
    val _ = metrics.registry.contains("sessions.opening") shouldBe false
    val _ = metrics.registry.contains("sessions.idle_grace") shouldBe false
    metrics.registry.contains("clients.active") shouldBe false
  }

  it should "suppress idle PeriodicReporter ticks and resume immediately on activity or active sessions" in {
    val metrics = new ServiceMetrics()
    val live = new AtomicInteger(0)
    val handle = metrics.registerSessionGauges(
      tunersCapacity = () => 4
    , sessionsLive = () => live.get()
    , sessionsOpening = () => 0
    , sessionsIdleGrace = () => 0
    , clientsActive = () => live.get()
    )
    val emitted = new CopyOnWriteArrayList[String]()
    val reporterOpt = metrics.startReporter(
      intervalSec = 3600
    , flushOnClose = true
    , onLine = line => { val _ = emitted.add(line) }
    )
    val _ = reporterOpt shouldBe defined
    val reporter = reporterOpt.get
    try {
      // Idle with no activity since startup -> suppressed
      val _ = reporter.reportOnce()
      val _ = emitted.size() shouldBe 0

      // Activity occurs -> emits once, resets interval metrics, then suppresses next idle tick
      metrics.recordSessionOpened()
      metrics.recordTuneLockMs(4200L)
      val _ = reporter.reportOnce()
      val _ = emitted.size() shouldBe 1
      val _ = emitted.get(0) should startWith("[service:metrics] ")
      val _ = emitted.get(0) should include("sessions.opened=1")
      val _ = emitted.get(0) should include("tune.lock.ms.0.5=4200.0")

      val _ = reporter.reportOnce()
      val _ = emitted.size() shouldBe 1

      // Active session keeps reporting even without new counter bumps
      live.set(1)
      val _ = reporter.reportOnce()
      val _ = emitted.size() shouldBe 2
      val _ = emitted.get(1) should include("sessions.live=1")

      // Close with flushOnClose=true flushes while active session is present
      reporter.close()
      val _ = emitted.size() shouldBe 3
    } finally {
      reporter.close()
      handle.close()
    }
  }

  it should "disable PeriodicReporter when intervalSec <= 0 and guard JMX when disabled" in {
    val metrics = new ServiceMetrics()
    val _ = metrics.startReporter(0) shouldBe None
    val _ = metrics.startReporter(-5) shouldBe None
    val _ = metrics.initializeJmxIfEnabled() shouldBe false
    metrics.unregisterJmxIfEnabled()
  }

  "ServiceMetrics.route" should "serve JSON metrics with no-store/CORS when enabled and 404 when disabled" in {
    val metrics = new ServiceMetrics()
    metrics.recordSessionOpened()
    metrics.recordTuneLockMs(1500L)

    val enabledRoute = ServiceMetrics.route(enabled = true, metrics)
    val _ = Get("/metrics.json") ~> enabledRoute ~> check {
      val _ = status shouldBe StatusCodes.OK
      val _ = contentType shouldBe ContentTypes.`application/json`
      val _ = header("Cache-Control").map(_.value) shouldBe Some("no-store")
      val _ = header("Access-Control-Allow-Origin").map(_.value) shouldBe Some("*")
      val obj = responseAs[String].parseJson.asJsObject.fields
      val _ = obj("sessions.opened") shouldBe JsNumber(1)
      val _ = obj("tune.lock.ms.0.5") shouldBe JsNumber(1500.0)
      // Reading /metrics.json must not reset interval histograms
      metrics.tuneLockMs.isEmpty shouldBe false
    }

    val _ = Options("/metrics.json") ~> enabledRoute ~> check {
      val _ = status shouldBe StatusCodes.OK
      header("Access-Control-Allow-Origin").map(_.value) shouldBe Some("*")
    }

    val disabledRoute = ServiceMetrics.route(enabled = false, metrics)
    Get("/metrics.json") ~> disabledRoute ~> check {
      status shouldBe StatusCodes.NotFound
    }
  }
}

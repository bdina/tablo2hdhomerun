package app.metrics

import com.scalarometer.statistics.{
  AggregateCounter
, Counter
, Gauge
, Histogram
, MetricScope
, MetricsFormat
, PeriodicReporter
, Registry
}
import org.apache.pekko.http.scaladsl.model.{ContentTypes, HttpEntity, HttpResponse, StatusCodes}
import org.apache.pekko.http.scaladsl.model.headers.{`Cache-Control`, CacheDirectives, RawHeader}
import org.apache.pekko.http.scaladsl.server.Directives._
import org.apache.pekko.http.scaladsl.server.Route
import org.slf4j.LoggerFactory

import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.{AtomicBoolean, AtomicLong}

final class ServiceMetrics {
  import ServiceMetrics._

  private val log = LoggerFactory.getLogger(classOf[ServiceMetrics])
  val registry: Registry = Registry()

  private val activityEpoch = new AtomicLong(0L)
  private val activeSessionChecks = new CopyOnWriteArrayList[() => Boolean]()
  private val jmxRegistered = new AtomicBoolean(false)

  // Fixed lifetime counters (never reset across periodic reporting intervals)
  val sessionsOpened: Counter = Counter("sessions.opened")
  val sessionsAttached: Counter = Counter("sessions.attached")
  val sessionsIdleGraceReused: Counter = Counter("sessions.idle_grace_reused")
  val sessionsIdleGraceEvicted: Counter = Counter("sessions.idle_grace_evicted")
  val sessionsRejectedNoTuners: Counter = Counter("sessions.rejected.no_tuners")
  val sessionsRejectedFailed: Counter = Counter("sessions.rejected.failed")
  val retunesCold: Counter = Counter("retunes.cold")
  val retunesAudioLoss: Counter = Counter("retunes.audio_loss")
  val streamFreezes: Counter = Counter("stream.freezes")
  val hlsSegsCompleted: Counter = Counter("hls.segs.completed")
  val hlsSegsShortReads: Counter = Counter("hls.segs.short_reads")
  val hlsSegsRetries: Counter = Counter("hls.segs.retries")
  val hlsStallPolls: Counter = Counter("hls.stall_polls")
  val hlsProbesClosed: Counter = Counter("hls.probes.closed")
  val authRefreshes: Counter = Counter("auth.refreshes")

  // Resettable interval latency distributions (right-sized: 2 histograms + 2 O(1) aggregates)
  val tuneLockMs: Histogram = Histogram("tune.lock.ms", max_snapshots = MaxLatencySnapshots)
  val hlsSegFetchMs: Histogram = Histogram("hls.seg.fetch.ms", max_snapshots = MaxLatencySnapshots)
  val hlsPollMs: AggregateCounter = AggregateCounter("hls.poll.ms")
  val keepaliveProbeMs: AggregateCounter = AggregateCounter("keepalive.probe.ms")

  private val fixedCounters: List[Counter] = List(
    sessionsOpened
  , sessionsAttached
  , sessionsIdleGraceReused
  , sessionsIdleGraceEvicted
  , sessionsRejectedNoTuners
  , sessionsRejectedFailed
  , retunesCold
  , retunesAudioLoss
  , streamFreezes
  , hlsSegsCompleted
  , hlsSegsShortReads
  , hlsSegsRetries
  , hlsStallPolls
  , hlsProbesClosed
  , authRefreshes
  )

  fixedCounters.foreach { c =>
    val _ = registry.registerFixedCounter(c)
  }

  val _ = (registry ++= List(tuneLockMs, hlsSegFetchMs, hlsPollMs, keepaliveProbeMs))

  private def markActivity(): Unit = {
    val _ = activityEpoch.incrementAndGet()
  }

  private def hasActiveSessions: Boolean = {
    val it = activeSessionChecks.iterator()
    var active = false
    while (it.hasNext && !active) {
      if (it.next().apply()) active = true
    }
    active
  }

  def recordSessionOpened(): Unit = {
    val _ = sessionsOpened.increment()
    markActivity()
  }

  def recordSessionAttached(): Unit = {
    val _ = sessionsAttached.increment()
    markActivity()
  }

  def recordIdleGraceReused(): Unit = {
    val _ = sessionsIdleGraceReused.increment()
    markActivity()
  }

  def recordIdleGraceEvicted(): Unit = {
    val _ = sessionsIdleGraceEvicted.increment()
    markActivity()
  }

  def recordRejectedNoTuners(): Unit = {
    val _ = sessionsRejectedNoTuners.increment()
    markActivity()
  }

  def recordRejectedFailed(): Unit = {
    val _ = sessionsRejectedFailed.increment()
    markActivity()
  }

  def recordColdRetune(): Unit = {
    val _ = retunesCold.increment()
    markActivity()
  }

  def recordAudioLossRetune(): Unit = {
    val _ = retunesAudioLoss.increment()
    markActivity()
  }

  def recordStreamFreeze(): Unit = {
    val _ = streamFreezes.increment()
    markActivity()
  }

  def recordHlsSegmentCompleted(isShortRead: Boolean, fetchMs: Long): Unit = {
    val _ = hlsSegsCompleted.increment()
    if (isShortRead) {
      val _ = hlsSegsShortReads.increment()
    }
    if (fetchMs >= 0L) {
      val _ = hlsSegFetchMs.incrementByAndRecordSnapshot(fetchMs)
    }
    markActivity()
  }

  def recordHlsSegmentRetry(): Unit = {
    val _ = hlsSegsRetries.increment()
    markActivity()
  }

  def recordHlsStallPoll(): Unit = {
    val _ = hlsStallPolls.increment()
    markActivity()
  }

  def recordHlsProbeClosed(): Unit = {
    val _ = hlsProbesClosed.increment()
    markActivity()
  }

  def recordAuthRefresh(): Unit = {
    val _ = authRefreshes.increment()
    markActivity()
  }

  def recordTuneLockMs(elapsedMs: Long): Unit =
    if (elapsedMs >= 0L) {
      val _ = tuneLockMs.incrementByAndRecordSnapshot(elapsedMs)
      markActivity()
    }

  def recordHlsPollMs(elapsedMs: Long): Unit =
    if (elapsedMs >= 0L) {
      val _ = hlsPollMs.increment(elapsedMs)
      markActivity()
    }

  def recordKeepaliveProbeMs(elapsedMs: Long): Unit =
    if (elapsedMs >= 0L) {
      val _ = keepaliveProbeMs.increment(elapsedMs)
      markActivity()
    }

  def registerSessionGauges(
    tunersCapacity: () => Int
  , sessionsLive: () => Int
  , sessionsOpening: () => Int
  , sessionsIdleGrace: () => Int
  , clientsActive: () => Int
  ): SessionGaugeHandle = {
    val scope = registry.scoped(
      Gauge.int("tuners.capacity")(tunersCapacity())
    , Gauge.int("sessions.live")(sessionsLive())
    , Gauge.int("sessions.opening")(sessionsOpening())
    , Gauge.int("sessions.idle_grace")(sessionsIdleGrace())
    , Gauge.int("clients.active")(clientsActive())
    )
    val check: () => Boolean = () =>
      sessionsLive() > 0 || sessionsOpening() > 0 || sessionsIdleGrace() > 0
    val _ = activeSessionChecks.add(check)
    new SessionGaugeHandle(scope, () => {
      val _ = activeSessionChecks.remove(check)
    })
  }

  def formatJson(reset: Boolean = false): String =
    MetricsFormat.Json.format(registry, reset = reset)

  def formatLogfmt(reset: Boolean = false): String =
    MetricsFormat.Logfmt.format(registry, reset = reset)

  def startReporter(
    intervalSec: Int
  , flushOnClose: Boolean = true
  , onLine: String => Unit = line => log.info(line)
  ): Option[PeriodicReporter] =
    if (intervalSec <= 0) {
      None
    } else {
      val lastReportedEpoch = new AtomicLong(activityEpoch.get())
      val reporter = PeriodicReporter.start(
        registry = registry
      , interval = Duration.ofSeconds(intervalSec.toLong)
      , format = MetricsFormat.Logfmt
      , reset = true
      , prefix = "[service:metrics] "
      , skipWhenEmpty = true
      , flushOnClose = flushOnClose
      , threadName = "tablo-service-metrics"
      ) { line =>
        val currentEpoch = activityEpoch.get()
        if (hasActiveSessions || currentEpoch != lastReportedEpoch.get()) {
          lastReportedEpoch.set(currentEpoch)
          onLine(line)
        }
      }
      Some(reporter)
    }

  def initializeJmxIfEnabled(): Boolean =
    if (System.getProperty("com.sun.management.jmxremote") != null && jmxRegistered.compareAndSet(false, true)) {
      registry.initializeJmx()
      true
    } else {
      false
    }

  def unregisterJmxIfEnabled(): Unit =
    if (jmxRegistered.compareAndSet(true, false)) {
      registry.unregisterJmx()
    }
}

object ServiceMetrics {
  val MaxLatencySnapshots: Int = 128

  final class SessionGaugeHandle private[metrics] (
    private val scope: MetricScope
  , private val onClose: () => Unit
  ) extends AutoCloseable {
    private val closed = new AtomicBoolean(false)

    def isClosed: Boolean = closed.get()

    override def close(): Unit =
      if (closed.compareAndSet(false, true)) {
        scope.close()
        onClose()
      }
  }

  private val corsHeaders = List(
    RawHeader("Access-Control-Allow-Origin", "*")
  , RawHeader("Access-Control-Allow-Methods", "GET, OPTIONS")
  , RawHeader("Access-Control-Allow-Headers", "Content-Type")
  )

  def route(enabled: => Boolean, metrics: ServiceMetrics): Route =
    path("metrics.json") {
      options {
        respondWithHeaders(corsHeaders) {
          complete(StatusCodes.OK)
        }
      } ~
      get {
        if (!enabled) {
          respondWithHeaders(corsHeaders) {
            complete(HttpResponse(StatusCodes.NotFound, entity = "Metrics endpoint disabled"))
          }
        } else {
          respondWithHeaders(`Cache-Control`(CacheDirectives.`no-store`) :: corsHeaders) {
            complete(HttpEntity(ContentTypes.`application/json`, metrics.formatJson(reset = false)))
          }
        }
      }
    }
}

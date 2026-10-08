package app.stream

import org.apache.pekko.NotUsed
import org.apache.pekko.stream.{Attributes, FlowShape, Inlet, Outlet}
import org.apache.pekko.stream.scaladsl.{Flow, RestartSource, Source}
import org.apache.pekko.stream.RestartSettings
import org.apache.pekko.stream.stage.{GraphStage, GraphStageLogic, InHandler, OutHandler, TimerGraphStageLogic}
import org.apache.pekko.util.ByteString

import org.slf4j.LoggerFactory

import app.AppContext

import scala.concurrent.duration._

object ResilientHlsSource {
  val log = LoggerFactory.getLogger(this.getClass)

  // Configuration (from AppContext.config.stream.resilient)
  val nullPacketIntervalMs: Int = 40

  // MPEG-TS null packet constant (188 bytes, sync byte 0x47, PID 0x1FFF)
  val MPEGTS_NULL_PACKET: ByteString = {
    val arr = Array.fill[Byte](188)(0xFF.toByte)
    arr(0) = 0x47.toByte
    arr(1) = 0x1F.toByte
    arr(2) = 0xFF.toByte
    arr(3) = 0x10.toByte // adaptation field control = payload only, continuity counter = 0
    ByteString(arr)
  }

  val MPEGTS_DISCONTINUITY_PACKET: ByteString = MpegTsSync.MPEGTS_DISCONTINUITY_PACKET

  final class StallTimeoutException(message: String) extends java.util.concurrent.TimeoutException(message)

  private sealed trait Elem
  private final case class Real(data: ByteString) extends Elem
  private case object GapFill extends Elem

  def apply(
    streamFactory: () => Source[ByteString, ?]
  , streamName: String
  , recoveryTimeout: FiniteDuration = AppContext.config.stream.resilient.recoveryTimeoutSec.seconds
  , stallTimeout: FiniteDuration = AppContext.config.stream.resilient.stallTimeoutSec.seconds
  , tuneTimeout: FiniteDuration = AppContext.config.stream.resilient.tuneTimeoutSec.seconds
  , retryDelay: FiniteDuration = AppContext.config.stream.resilient.retryDelaySec.seconds
  , resumePrefixSupplier: () => Option[ByteString] = () => None
  ): Source[ByteString, ?] = {
    // Fixed delay between retunes (min == max, no jitter): predictable freeze length.
    val restartSettings = RestartSettings(minBackoff = retryDelay, maxBackoff = retryDelay, randomFactor = 0.0)
    val attempts = new java.util.concurrent.atomic.AtomicInteger(0)
    RestartSource.withBackoff(restartSettings) { () =>
      val n = attempts.incrementAndGet()
      if (n == 1) log.info(s"[$streamName] stream connect attempt=$n")
      else log.warn(s"[$streamName] stream recovery retune attempt=$n")
      val inner = streamFactory().via(StallWatchdog.flow(tuneTimeout, stallTimeout, streamName))
      if (n == 1) {
        inner.map(data => Real(data))
      } else {
        // The single resume injection point: discontinuity + cached PAT/PMT before new live video.
        val prefix = resumePrefixSupplier().getOrElse(MPEGTS_DISCONTINUITY_PACKET)
        inner.statefulMapConcat { () =>
          var isFirst = true
          chunk =>
            if (isFirst) {
              isFirst = false
              List(Real(prefix ++ chunk))
            } else {
              List(Real(chunk))
            }
        }
      }
    }
    .keepAlive(nullPacketIntervalMs.millis, () => GapFill)
    .via(RecoveryTimeout.flow(recoveryTimeout, streamName))
    .map {
      case Real(data) => data
      case GapFill => MPEGTS_NULL_PACKET
    }
  }

  // Fails the inner (per-tune) stream when it stops making progress, which triggers a retune.
  // Before the first element the longer tune timeout applies (cold /watch takes ~11s).
  private object StallWatchdog {
    def flow(tuneTimeout: FiniteDuration, stallTimeout: FiniteDuration, streamName: String): Flow[ByteString, ByteString, NotUsed] =
      Flow.fromGraph(new Stage(tuneTimeout, stallTimeout, streamName))

    private final class Stage(
      tuneTimeout: FiniteDuration
    , stallTimeout: FiniteDuration
    , streamName: String
    ) extends GraphStage[FlowShape[ByteString, ByteString]] {
      val in: Inlet[ByteString] = Inlet("StallWatchdog.in")
      val out: Outlet[ByteString] = Outlet("StallWatchdog.out")
      override val shape: FlowShape[ByteString, ByteString] = FlowShape(in, out)

      override def createLogic(attrs: Attributes): GraphStageLogic = new TimerGraphStageLogic(shape) with InHandler with OutHandler {
        private var started = false
        private var lastPushNanos = System.nanoTime()
        private val microStalls = scala.collection.mutable.Queue.empty[Long]

        override def preStart(): Unit = scheduleOnce("watchdog", tuneTimeout)

        override def onPush(): Unit = {
          val now = System.nanoTime()
          if (started) {
            val gap = now - lastPushNanos
            if (gap > 1000000000L) { // 1s
              microStalls.enqueue(now)
              while (microStalls.nonEmpty && (now - microStalls.front) > 60000000000L) { // 60s
                microStalls.dequeue()
              }
              if (microStalls.size > 5) {
                log.warn(s"[$streamName] too many micro-stalls (> 5 in 60s), retuning")
                failStage(new StallTimeoutException(s"$streamName too many micro-stalls"))
                return
              }
            }
          }
          started = true
          lastPushNanos = now
          scheduleOnce("watchdog", stallTimeout)
          push(out, grab(in))
        }

        override def onPull(): Unit = pull(in)

        override protected def onTimer(timerKey: Any): Unit = {
          val detail =
            if (started) s"no new data for ${stallTimeout.toSeconds}s"
            else s"tune produced no data within ${tuneTimeout.toSeconds}s"
          log.warn(s"[$streamName] $detail, retuning")
          failStage(new StallTimeoutException(s"$streamName $detail"))
        }

        setHandlers(in, out, this)
      }
    }
  }

  // After keepAlive: only Real backend bytes reset the timer; null keepalive does not.
  private object RecoveryTimeout {
    def flow(timeout: FiniteDuration, streamName: String): Flow[Elem, Elem, NotUsed] =
      Flow.fromGraph(new Stage(timeout, streamName))

    private final class Stage(timeout: FiniteDuration, streamName: String) extends GraphStage[FlowShape[Elem, Elem]] {
      val in: Inlet[Elem] = Inlet("RecoveryTimeout.in")
      val out: Outlet[Elem] = Outlet("RecoveryTimeout.out")
      override val shape: FlowShape[Elem, Elem] = FlowShape(in, out)

      override def createLogic(attrs: Attributes): GraphStageLogic = new TimerGraphStageLogic(shape) with InHandler with OutHandler {
        private var lastRealNanos = System.nanoTime()
        private var hadGap = false
        private val checkInterval = (timeout / 4).max(50.millis)

        override def preStart(): Unit = scheduleWithFixedDelay("recovery-check", checkInterval, checkInterval)

        override protected def onTimer(timerKey: Any): Unit =
          if (System.nanoTime() - lastRealNanos > timeout.toNanos) {
            log.error(s"[$streamName] no real data for ${timeout.toSeconds}s, ending stream terminally")
            failStage(new StallTimeoutException(s"$streamName no real data for ${timeout.toSeconds}s"))
          }

        override def onPush(): Unit = {
          val elem = grab(in)
          elem match {
            case Real(_) =>
              val now = System.nanoTime()
              if (hadGap) log.info(s"[$streamName] real data resumed after ${(now - lastRealNanos) / 1000000}ms")
              hadGap = false
              lastRealNanos = now
            case GapFill =>
              hadGap = true
          }
          push(out, elem)
        }

        override def onPull(): Unit = pull(in)

        setHandlers(in, out, this)
      }
    }
  }
}
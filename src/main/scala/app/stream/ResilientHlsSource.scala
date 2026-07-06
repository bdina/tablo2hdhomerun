package app.stream

import org.apache.pekko.NotUsed
import org.apache.pekko.stream.{Attributes, FlowShape, Inlet, Outlet}
import org.apache.pekko.stream.scaladsl.{Flow, RestartSource, Source}
import org.apache.pekko.stream.RestartSettings
import org.apache.pekko.stream.stage.{GraphStage, GraphStageLogic, InHandler, OutHandler, TimerGraphStageLogic}
import org.apache.pekko.util.ByteString

import org.slf4j.LoggerFactory

import app.AppContext

import scala.compiletime.uninitialized
import scala.concurrent.duration._

object ResilientHlsSource {
  val log = LoggerFactory.getLogger(this.getClass)

  // Configuration (from AppContext.config.stream.resilient)
  val nullPacketIntervalMs: Int = 80

  // MPEG-TS null packet constant (188 bytes, sync byte 0x47, PID 0x1FFF)
  val MPEGTS_NULL_PACKET: ByteString = {
    val arr = Array.fill[Byte](188)(0xFF.toByte)
    arr(0) = 0x47.toByte
    arr(1) = 0x1F.toByte
    arr(2) = 0xFF.toByte
    arr(3) = 0x10.toByte // adaptation field control = payload only, continuity counter = 0
    ByteString(arr)
  }

  private sealed trait Elem
  private final case class Real(data: ByteString) extends Elem
  private case object GapFill extends Elem

  def apply(
    streamFactory: () => Source[ByteString, ?]
  , streamName: String
  , recoveryTimeout: FiniteDuration = AppContext.config.stream.resilient.recoveryTimeoutSec.seconds
  , minBackoff: FiniteDuration = AppContext.config.stream.resilient.retryMinBackoffSec.seconds
  , maxBackoff: FiniteDuration = AppContext.config.stream.resilient.retryMaxBackoffSec.seconds
  ): Source[ByteString, ?] = {
    val maxGapSec = AppContext.config.stream.resilient.maxGapSec

    val restartSettings = RestartSettings(
      minBackoff = minBackoff
    , maxBackoff = maxBackoff
    , randomFactor = 0.2
    )
    val attempts = new java.util.concurrent.atomic.AtomicInteger(0)
    RestartSource.withBackoff(restartSettings) { () =>
      val n = attempts.incrementAndGet()
      if (n == 1) log.info(s"[$streamName] stream connect attempt=$n")
      else log.warn(s"[$streamName] stream recovery retune attempt=$n")
      streamFactory().idleTimeout(maxGapSec.seconds).map(Real(_))
    }
    .keepAlive(nullPacketIntervalMs.millis, () => GapFill)
    .via(RecoveryTimeout.flow(recoveryTimeout, streamName))
    .map {
      case Real(data) => data
      case GapFill => MPEGTS_NULL_PACKET
    }
  }

  // After keepAlive: only Real backend bytes reset the timer; null keepalive does not.
  private object RecoveryTimeout {
    def flow(timeout: FiniteDuration, streamName: String): Flow[Elem, Elem, NotUsed] =
      Flow.fromGraph(new Stage(timeout, streamName))

    private final class Stage(timeout: FiniteDuration, streamName: String)
        extends GraphStage[FlowShape[Elem, Elem]] {
      val in: Inlet[Elem] = Inlet("RecoveryTimeout.in")
      val out: Outlet[Elem] = Outlet("RecoveryTimeout.out")
      override val shape: FlowShape[Elem, Elem] = FlowShape(in, out)

      override def createLogic(attrs: Attributes): GraphStageLogic = new TimerGraphStageLogic(shape) {
        private var lastRealNanos = System.nanoTime()
        private var padding = false
        private val checkInterval = (timeout / 4).max(50.millis)
        private var failAsync: org.apache.pekko.stream.stage.AsyncCallback[Throwable] = uninitialized

        override def preStart(): Unit = {
          failAsync = getAsyncCallback(failStage)
          scheduleWithFixedDelay("recovery-check", checkInterval, checkInterval)
        }

        override protected def onTimer(timerKey: Any): Unit = {
          if (System.nanoTime() - lastRealNanos > timeout.toNanos) {
            log.error(s"[$streamName] recovery timeout ${timeout.toSeconds}s with no real data, ending stream")
            failAsync.invoke(new java.util.concurrent.TimeoutException(s"$streamName recovery timeout"))
          }
        }

        setHandler(in, new InHandler {
          override def onPush(): Unit = {
            val elem = grab(in)
            elem match {
              case Real(_) =>
                if (padding) {
                  log.debug("[{}] gap-fill ended, real data resumed", streamName)
                  padding = false
                }
                lastRealNanos = System.nanoTime()
              case GapFill =>
                if (!padding) {
                  log.debug("[{}] gap-fill started, emitting null packets", streamName)
                  padding = true
                }
            }
            push(out, elem)
          }
        })

        setHandler(out, new OutHandler {
          override def onPull(): Unit = pull(in)
        })
      }
    }
  }
}
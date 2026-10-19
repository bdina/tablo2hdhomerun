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
  , maxRetryDelay: FiniteDuration = AppContext.config.stream.resilient.maxRetryDelaySec.seconds
  , slateThreshold: FiniteDuration = AppContext.config.stream.resilient.stallTimeoutSec.seconds
  , enableSlateVideo: Boolean = AppContext.config.stream.resilient.enableSlateVideo
  , resumePrefixSupplier: () => Option[ByteString] = () => None
  , videoPidSupplier: () => Option[Int] = () => None
  , videoStreamTypeSupplier: () => Option[Int] = () => None
  , audioPidsSupplier: () => Seq[Int] = () => Seq.empty
  , keyframeTimeout: FiniteDuration = Duration.Zero
  ): Source[ByteString, ?] = {
    val effectiveMax = if (maxRetryDelay < retryDelay) retryDelay else maxRetryDelay
    val effectiveKeyframeTimeout =
      if (keyframeTimeout.toNanos > 0) keyframeTimeout
      else (stallTimeout * 2).max(12.seconds)
    val restartSettings = RestartSettings(minBackoff = retryDelay, maxBackoff = effectiveMax, randomFactor = 0.2)
    val attempts = new java.util.concurrent.atomic.AtomicInteger(0)
    RestartSource.withBackoff(restartSettings) { () =>
      val n = attempts.incrementAndGet()
      if (n == 1) log.info(s"[$streamName] stream connect attempt=$n")
      else log.warn(s"[$streamName] stream recovery retune attempt=$n")
      val inner = streamFactory().via(
        StallWatchdog.flow(
          tuneTimeout = tuneTimeout
        , stallTimeout = stallTimeout
        , streamName = streamName
        , keyframeTimeout = effectiveKeyframeTimeout
        , videoPidSupplier = videoPidSupplier
        , videoStreamTypeSupplier = videoStreamTypeSupplier
        )
      )
      if (n == 1) {
        inner.map(data => Real(data))
      } else {
        val aligned = inner.via(
          MpegTsSync.alignToIFrame(
            videoPid = videoPidSupplier()
          , videoStreamType = videoStreamTypeSupplier()
          , audioPids = audioPidsSupplier()
          )
        )
        val prefix = resumePrefixSupplier().getOrElse(MPEGTS_DISCONTINUITY_PACKET)
        aligned.statefulMapConcat { () =>
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
    .via(RecoveryTimeout.flow(recoveryTimeout, streamName, slateThreshold, enableSlateVideo))
  }

  // Fails the inner (per-tune) stream when it stops making progress, which triggers a retune.
  // Before the first element the longer tune timeout applies (cold /watch takes ~11s).
  private[app] object StallWatchdog {
    def flow(
      tuneTimeout: FiniteDuration
    , stallTimeout: FiniteDuration
    , streamName: String
    , keyframeTimeout: FiniteDuration = Duration.Zero
    , videoPidSupplier: () => Option[Int] = () => None
    , videoStreamTypeSupplier: () => Option[Int] = () => None
    ): Flow[ByteString, ByteString, NotUsed] =
      Flow.fromGraph(new Stage(tuneTimeout, stallTimeout, streamName, keyframeTimeout, videoPidSupplier, videoStreamTypeSupplier))

    private final class Stage(
      tuneTimeout: FiniteDuration
    , stallTimeout: FiniteDuration
    , streamName: String
    , keyframeTimeout: FiniteDuration
    , videoPidSupplier: () => Option[Int]
    , videoStreamTypeSupplier: () => Option[Int]
    ) extends GraphStage[FlowShape[ByteString, ByteString]] {
      val in: Inlet[ByteString] = Inlet("StallWatchdog.in")
      val out: Outlet[ByteString] = Outlet("StallWatchdog.out")
      override val shape: FlowShape[ByteString, ByteString] = FlowShape(in, out)

      override def createLogic(attrs: Attributes): GraphStageLogic = new TimerGraphStageLogic(shape) with InHandler with OutHandler {
        private var started = false
        private var lastPushNanos = System.nanoTime()
        private var lastKeyframeNanos = System.nanoTime()
        private var monitoringKeyframes = false
        private var lastVideoCc = -1
        private var inCandidateKeyframe = false
        private var candidateKeyframeCleanPackets = 0
        private val checkInterval = {
          val minTimeout = if (stallTimeout < tuneTimeout) stallTimeout else tuneTimeout
          (minTimeout / 4).max(50.millis).min(250.millis)
        }

        override def preStart(): Unit = scheduleWithFixedDelay("watchdog", checkInterval, checkInterval)

        override def onPush(): Unit = {
          val now = System.nanoTime()
          val chunk = grab(in)
          started = true
          lastPushNanos = now

          if (keyframeTimeout.toNanos > 0) {
            videoPidSupplier() match {
              case Some(vpid) if chunk.length >= MpegTsSync.PacketSize && chunk(0) == 0x47.toByte =>
                monitoringKeyframes = true
                val arr = chunk.toArray
                val streamType = videoStreamTypeSupplier()
                var pos = 0
                var foundCleanKey = false
                while (pos + MpegTsSync.PacketSize <= arr.length) {
                  if (arr(pos) == 0x47.toByte) {
                    val pid = ((arr(pos + 1) & 0x1F) << 8) | (arr(pos + 2) & 0xFF)
                    if (pid == vpid) {
                      val isTei = (arr(pos + 1) & 0x80) != 0
                      val pusi = (arr(pos + 1) & 0x40) != 0
                      val afc = (arr(pos + 3) & 0x30) >> 4
                      val hasPayload = (afc & 0x01) != 0
                      val hasAdaptation = (afc & 0x02) != 0
                      val isDiscontinuity =
                        hasAdaptation && (arr(pos + 4) & 0xFF) > 0 && (arr(pos + 5) & 0x80) != 0
                      val cc = arr(pos + 3) & 0x0F

                      val isCcError =
                        if (isTei) false
                        else if (isDiscontinuity) {
                          lastVideoCc = if (hasPayload) cc else -1
                          false
                        } else if (hasPayload) {
                          val prev = lastVideoCc
                          lastVideoCc = cc
                          prev >= 0 && cc != ((prev + 1) & 0x0F) && cc != prev
                        } else {
                          false
                        }

                      if (isTei || isCcError) {
                        if (isTei) lastVideoCc = -1
                        inCandidateKeyframe = false
                        candidateKeyframeCleanPackets = 0
                      } else if (hasPayload) {
                        val payloadOffset =
                          if (afc == 1) pos + 4
                          else if (afc == 3) pos + 5 + (arr(pos + 4) & 0xFF)
                          else pos + MpegTsSync.PacketSize
                        val payloadLen = (pos + MpegTsSync.PacketSize) - payloadOffset
                        val isKeyStart =
                          payloadLen > 0 && MpegTsSync.hasKeyframeHeader(arr, payloadOffset, payloadLen, streamType)

                        if (isKeyStart) {
                          if (inCandidateKeyframe) {
                            foundCleanKey = true
                          }
                          inCandidateKeyframe = true
                          candidateKeyframeCleanPackets = 1
                        } else if (inCandidateKeyframe) {
                          if (pusi && candidateKeyframeCleanPackets >= 2) {
                            foundCleanKey = true
                            inCandidateKeyframe = false
                            candidateKeyframeCleanPackets = 0
                          } else {
                            candidateKeyframeCleanPackets += 1
                          }
                        }
                      }
                    }
                  }
                  pos += MpegTsSync.PacketSize
                }
                if (inCandidateKeyframe && candidateKeyframeCleanPackets >= 2) {
                  foundCleanKey = true
                  inCandidateKeyframe = false
                  candidateKeyframeCleanPackets = 0
                }
                if (foundCleanKey) {
                  lastKeyframeNanos = now
                }
              case _ =>
                lastKeyframeNanos = now
            }
          }

          push(out, chunk)
        }

        override def onPull(): Unit = pull(in)

        override protected def onTimer(timerKey: Any): Unit = {
          val now = System.nanoTime()
          val elapsedNanos = now - lastPushNanos
          if (started) {
            if (elapsedNanos > stallTimeout.toNanos) {
              val detail = s"no new data for ${stallTimeout.toSeconds}s"
              log.warn(s"[$streamName] $detail, retuning")
              failStage(new StallTimeoutException(s"$streamName $detail"))
            } else if (monitoringKeyframes && keyframeTimeout.toNanos > 0 && (now - lastKeyframeNanos) > keyframeTimeout.toNanos) {
              val detail = s"no decodable keyframe for ${keyframeTimeout.toSeconds}s"
              log.warn(s"[$streamName] $detail, retuning")
              failStage(new StallTimeoutException(s"$streamName $detail"))
            }
          } else {
            if (elapsedNanos > tuneTimeout.toNanos) {
              val detail = s"tune produced no data within ${tuneTimeout.toSeconds}s"
              log.warn(s"[$streamName] $detail, retuning")
              failStage(new StallTimeoutException(s"$streamName $detail"))
            }
          }
        }

        setHandlers(in, out, this)
      }
    }
  }

  // After keepAlive: only Real backend bytes reset the timer; null keepalive does not.
  private object RecoveryTimeout {
    def flow(
      timeout: FiniteDuration
    , streamName: String
    , slateThreshold: FiniteDuration
    , enableSlateVideo: Boolean
    ): Flow[Elem, ByteString, NotUsed] =
      Flow.fromGraph(new Stage(timeout, streamName, slateThreshold, enableSlateVideo))

    private final class Stage(
      timeout: FiniteDuration
    , streamName: String
    , slateThreshold: FiniteDuration
    , enableSlateVideo: Boolean
    ) extends GraphStage[FlowShape[Elem, ByteString]] {
      val in: Inlet[Elem] = Inlet("RecoveryTimeout.in")
      val out: Outlet[ByteString] = Outlet("RecoveryTimeout.out")
      override val shape: FlowShape[Elem, ByteString] = FlowShape(in, out)

      override def createLogic(attrs: Attributes): GraphStageLogic = new TimerGraphStageLogic(shape) with InHandler with OutHandler {
        private var lastRealNanos = System.nanoTime()
        private var hadGap = false
        private var slateActive = false
        private val slateLoop = new SlateVideoSource.Loop(chunkPackets = 24)
        private val gapLogThresholdNanos = slateThreshold.toNanos
        private val checkInterval =
          if (timeout.toNanos <= 0) 5.seconds
          else (timeout / 4).max(50.millis)

        override def preStart(): Unit = scheduleWithFixedDelay("recovery-check", checkInterval, checkInterval)

        override protected def onTimer(timerKey: Any): Unit =
          if (timeout.toNanos > 0 && System.nanoTime() - lastRealNanos > timeout.toNanos) {
            log.error(s"[$streamName] no real data for ${timeout.toSeconds}s, ending stream terminally")
            failStage(new StallTimeoutException(s"$streamName no real data for ${timeout.toSeconds}s"))
          }

        override def onPush(): Unit = {
          val elem = grab(in)
          elem match {
            case Real(data) =>
              val now = System.nanoTime()
              if (hadGap && (now - lastRealNanos) >= gapLogThresholdNanos) log.info(s"[$streamName] real data resumed after ${(now - lastRealNanos) / 1000000}ms")
              hadGap = false

              val outData = if (slateActive) {
                ByteString(MPEGTS_DISCONTINUITY_PACKET) ++ data
              } else data

              slateActive = false
              lastRealNanos = now
              push(out, outData)
            case GapFill =>
              hadGap = true
              val elapsed = System.nanoTime() - lastRealNanos
              if (enableSlateVideo && elapsed >= slateThreshold.toNanos) {
                if (!slateActive) {
                  slateActive = true
                }
                push(out, slateLoop.nextChunk())
              } else {
                push(out, MPEGTS_NULL_PACKET)
              }
          }
        }

        override def onPull(): Unit = pull(in)

        setHandlers(in, out, this)
      }
    }
  }
}

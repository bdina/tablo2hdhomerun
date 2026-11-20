package app.stream

import scala.concurrent.duration._

object AudioLossMonitor {
  val DefaultTimeout: FiniteDuration = 30.seconds
  val MaxBackoffTimeout: FiniteDuration = 300.seconds
  val DefaultMinLiveVideoFrames: Long = 150L
  val DefaultCleanAudioFramesToRecover: Int = 3
  val DefaultCleanAudioFramesToResetBackoff: Int = 100
  val DefaultMaxInterFrameStep: FiniteDuration = 10.seconds
  private val DegradedVideoFrameThreshold: Long = 15L

  final case class Config(
    timeout: FiniteDuration = DefaultTimeout
  , maxBackoffTimeout: FiniteDuration = MaxBackoffTimeout
  , minLiveVideoFrames: Long = DefaultMinLiveVideoFrames
  , cleanAudioFramesToRecover: Int = DefaultCleanAudioFramesToRecover
  , cleanAudioFramesToResetBackoff: Int = DefaultCleanAudioFramesToResetBackoff
  , maxInterFrameStep: FiniteDuration = DefaultMaxInterFrameStep
  ) {
    def isEnabled: Boolean = timeout.toNanos > 0L

    def effectiveTimeoutNanos(consecutiveRetunes: Int): Long =
      if (!isEnabled) 0L
      else {
        val shift = math.min(consecutiveRetunes.max(0), 10)
        val scaled = timeout.toNanos.toDouble * (1L << shift).toDouble
        val cap = math.max(timeout.toNanos, maxBackoffTimeout.toNanos)
        math.min(scaled, cap.toDouble).toLong
      }
  }

  final case class TriggerInfo(
    audioLossMs: Long
  , liveVideoFrames: Long
  , droppedAudioFrames: Long
  , ptsScrubbedAudioFrames: Long
  , consecutiveRetunes: Int
  , nextTimeoutSec: Long
  ) {
    def detail: String =
      s"audioLossMs=$audioLossMs liveVideoFrames=$liveVideoFrames droppedAudioFrames=$droppedAudioFrames ptsScrubbedAudio=$ptsScrubbedAudioFrames consecutiveRetunes=$consecutiveRetunes nextTimeoutSec=$nextTimeoutSec"
  }

  final case class State(
    seenCleanLiveAudio: Boolean = false
  , activeAudioLossNanos: Long = 0L
  , lastLiveVideoNanos: Long = 0L
  , liveVideoFramesSinceLoss: Long = 0L
  , droppedAudioFramesSinceLoss: Long = 0L
  , ptsScrubbedAudioSinceLoss: Long = 0L
  , cleanAudioRecoveryStreak: Int = 0
  , cleanAudioSinceReset: Int = 0
  , consecutiveRetunes: Int = 0
  ) {
    private def isMonitoringEligible: Boolean =
      seenCleanLiveAudio || droppedAudioFramesSinceLoss > 0L

    private def isDegraded: Boolean =
      droppedAudioFramesSinceLoss > 0L || liveVideoFramesSinceLoss >= DegradedVideoFrameThreshold

    def onCleanLiveAudio(config: Config): State = {
      val nextStreak = cleanAudioRecoveryStreak + 1
      val nextSinceReset = if (consecutiveRetunes > 0) cleanAudioSinceReset + 1 else 0
      val nextConsecutive =
        if (consecutiveRetunes > 0 && nextSinceReset >= config.cleanAudioFramesToResetBackoff) 0
        else consecutiveRetunes
      val recovered = !isDegraded || nextStreak >= config.cleanAudioFramesToRecover
      if (recovered) {
        copy(
          seenCleanLiveAudio = true
        , activeAudioLossNanos = 0L
        , lastLiveVideoNanos = 0L
        , liveVideoFramesSinceLoss = 0L
        , droppedAudioFramesSinceLoss = 0L
        , ptsScrubbedAudioSinceLoss = 0L
        , cleanAudioRecoveryStreak = 0
        , cleanAudioSinceReset = if (nextConsecutive == 0) 0 else nextSinceReset
        , consecutiveRetunes = nextConsecutive
        )
      } else {
        copy(
          seenCleanLiveAudio = true
        , cleanAudioRecoveryStreak = nextStreak
        , cleanAudioSinceReset = if (nextConsecutive == 0) 0 else nextSinceReset
        , consecutiveRetunes = nextConsecutive
        )
      }
    }

    def onDroppedLiveAudio(isPtsScrub: Boolean): State =
      copy(
        droppedAudioFramesSinceLoss = droppedAudioFramesSinceLoss + 1L
      , ptsScrubbedAudioSinceLoss = ptsScrubbedAudioSinceLoss + (if (isPtsScrub) 1L else 0L)
      , cleanAudioRecoveryStreak = 0
      , cleanAudioSinceReset = 0
      )

    def onLiveVideoFrame(nowNanos: Long, hasPrimaryAudioPid: Boolean, config: Config): State =
      if (!hasPrimaryAudioPid || !isMonitoringEligible) {
        this
      } else {
        val maxStepNanos = math.max(1L, config.maxInterFrameStep.toNanos)
        val stepNanos =
          if (lastLiveVideoNanos > 0L) math.max(0L, math.min(nowNanos - lastLiveVideoNanos, maxStepNanos))
          else 0L
        copy(
          activeAudioLossNanos = activeAudioLossNanos + stepNanos
        , lastLiveVideoNanos = nowNanos
        , liveVideoFramesSinceLoss = liveVideoFramesSinceLoss + 1L
        )
      }

    def onPause: State =
      if (lastLiveVideoNanos == 0L && cleanAudioRecoveryStreak == 0) this
      else copy(lastLiveVideoNanos = 0L, cleanAudioRecoveryStreak = 0)

    def onDiscontinuity: State =
      copy(
        activeAudioLossNanos = 0L
      , lastLiveVideoNanos = 0L
      , liveVideoFramesSinceLoss = 0L
      , droppedAudioFramesSinceLoss = 0L
      , ptsScrubbedAudioSinceLoss = 0L
      , cleanAudioRecoveryStreak = 0
      )

    def evaluate(
      hasPrimaryAudioPid: Boolean
    , freezeActive: Boolean
    , confirmedReplay: Boolean
    , config: Config
    ): (State, Option[TriggerInfo]) =
      if (!config.isEnabled || !hasPrimaryAudioPid) {
        (this, None)
      } else if (freezeActive || confirmedReplay) {
        (onPause, None)
      } else if (
        isMonitoringEligible &&
        liveVideoFramesSinceLoss >= config.minLiveVideoFrames
      ) {
        val thresholdNanos = config.effectiveTimeoutNanos(consecutiveRetunes)
        if (activeAudioLossNanos >= thresholdNanos) {
          val nextRetunes = consecutiveRetunes + 1
          val info = TriggerInfo(
            audioLossMs = activeAudioLossNanos / 1000000L
          , liveVideoFrames = liveVideoFramesSinceLoss
          , droppedAudioFrames = droppedAudioFramesSinceLoss
          , ptsScrubbedAudioFrames = ptsScrubbedAudioSinceLoss
          , consecutiveRetunes = nextRetunes
          , nextTimeoutSec = config.effectiveTimeoutNanos(nextRetunes) / 1000000000L
          )
          val nextState = onDiscontinuity.copy(
            consecutiveRetunes = nextRetunes
          , cleanAudioSinceReset = 0
          )
          (nextState, Some(info))
        } else {
          (this, None)
        }
      } else {
        (this, None)
      }
  }
}

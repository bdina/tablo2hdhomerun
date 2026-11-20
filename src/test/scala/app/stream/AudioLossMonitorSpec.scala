package app.stream

import org.junit.runner.RunWith
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.junit.JUnitRunner

import scala.concurrent.duration._

@RunWith(classOf[JUnitRunner])
class AudioLossMonitorSpec extends AnyFlatSpec with Matchers {

  private val defaultCfg = AudioLossMonitor.Config()
  private val secNanos = 1_000_000_000L

  private def advanceLiveVideo(
    initial: AudioLossMonitor.State
  , startNanos: Long
  , frameCount: Int
  , stepNanos: Long
  , cfg: AudioLossMonitor.Config = defaultCfg
  , hasPrimaryAudioPid: Boolean = true
  ): (AudioLossMonitor.State, Long) = {
    var st = initial
    var t = startNanos
    var i = 0
    while (i < frameCount) {
      st = st.onLiveVideoFrame(t, hasPrimaryAudioPid, cfg)
      t += stepNanos
      i += 1
    }
    (st, t)
  }

  "AudioLossMonitor" should "never trigger on healthy interleaved audio and video" in {
    var st = AudioLossMonitor.State()
    var t = 10L * secNanos
    var triggered = 0
    var i = 0
    while (i < 1200) {
      st = st.onLiveVideoFrame(t, hasPrimaryAudioPid = true, defaultCfg)
      st = st.onCleanLiveAudio(defaultCfg)
      val (next, trig) = st.evaluate(hasPrimaryAudioPid = true, freezeActive = false, confirmedReplay = false, defaultCfg)
      st = next
      if (trig.isDefined) triggered += 1
      t += 33_366_667L
      i += 1
    }
    val _ = triggered shouldBe 0
    val _ = st.activeAudioLossNanos shouldBe 0L
    st.seenCleanLiveAudio shouldBe true
  }

  it should "trigger after 30s of active live video with PTS-scrubbed audio loss" in {
    var st = AudioLossMonitor.State().onCleanLiveAudio(defaultCfg)
    var t = 100L * secNanos
    var triggerOpt: Option[AudioLossMonitor.TriggerInfo] = None

    // Simulate 11 x 3s HLS bursts (90 video frames + 130 scrubbed audio frames per 3s segment)
    var seg = 0
    while (seg < 12 && triggerOpt.isEmpty) {
      var f = 0
      while (f < 90) {
        st = st.onLiveVideoFrame(t, hasPrimaryAudioPid = true, defaultCfg)
        t += 500_000L
        f += 1
      }
      var a = 0
      while (a < 130) {
        st = st.onDroppedLiveAudio(isPtsScrub = true)
        a += 1
      }
      val (next, trig) = st.evaluate(hasPrimaryAudioPid = true, freezeActive = false, confirmedReplay = false, defaultCfg)
      st = next
      triggerOpt = trig
      t += (3L * secNanos) - (90L * 500_000L)
      seg += 1
    }

    val info = triggerOpt.getOrElse(fail("Expected sustained audio loss trigger"))
    val _ = info.audioLossMs should be >= 30000L
    val _ = info.liveVideoFrames should be >= AudioLossMonitor.DefaultMinLiveVideoFrames
    val _ = info.droppedAudioFrames should be >= 1300L
    val _ = info.ptsScrubbedAudioFrames shouldBe info.droppedAudioFrames
    val _ = info.consecutiveRetunes shouldBe 1
    val _ = info.nextTimeoutSec shouldBe 60L
    val _ = st.activeAudioLossNanos shouldBe 0L
    st.consecutiveRetunes shouldBe 1
  }

  it should "trigger when audio disappears completely (0 audio packets) after clean live audio" in {
    val started = AudioLossMonitor.State().onCleanLiveAudio(defaultCfg)
    val (afterVideo, _) = advanceLiveVideo(started, 50L * secNanos, frameCount = 930, stepNanos = 33_366_667L)
    val (next, trig) = afterVideo.evaluate(hasPrimaryAudioPid = true, freezeActive = false, confirmedReplay = false, defaultCfg)
    val info = trig.getOrElse(fail("Expected trigger on silent audio disappearance"))
    val _ = info.droppedAudioFrames shouldBe 0L
    val _ = info.audioLossMs should be >= 30000L
    next.consecutiveRetunes shouldBe 1
  }

  it should "trigger on cold-start wedged audio when dropped audio packets arrive from t=0" in {
    val st = AudioLossMonitor.State().onDroppedLiveAudio(isPtsScrub = true)
    val (afterVideo, _) = advanceLiveVideo(st, 10L * secNanos, frameCount = 930, stepNanos = 33_366_667L)
    val (_, trig) = afterVideo.evaluate(hasPrimaryAudioPid = true, freezeActive = false, confirmedReplay = false, defaultCfg)
    val info = trig.getOrElse(fail("Expected trigger on cold-start wedged audio"))
    val _ = info.droppedAudioFrames shouldBe 1L
    info.ptsScrubbedAudioFrames shouldBe 1L
  }

  it should "never trigger on video-only streams or channels without any observed audio" in {
    val (noAudioPid, _) = advanceLiveVideo(AudioLossMonitor.State(), 10L * secNanos, frameCount = 1000, stepNanos = 33_366_667L, hasPrimaryAudioPid = false)
    val (_, trig1) = noAudioPid.evaluate(hasPrimaryAudioPid = false, freezeActive = false, confirmedReplay = false, defaultCfg)
    val _ = trig1 shouldBe None

    val (declaredButNeverSeen, _) = advanceLiveVideo(AudioLossMonitor.State(), 10L * secNanos, frameCount = 1000, stepNanos = 33_366_667L, hasPrimaryAudioPid = true)
    val (_, trig2) = declaredButNeverSeen.evaluate(hasPrimaryAudioPid = true, freezeActive = false, confirmedReplay = false, defaultCfg)
    trig2 shouldBe None
  }

  it should "not falsely trigger after a 35s freeze or confirmed replay on a healthy stream" in {
    var st = AudioLossMonitor.State().onCleanLiveAudio(defaultCfg)
    st = st.onLiveVideoFrame(10L * secNanos, hasPrimaryAudioPid = true, defaultCfg)
    val (paused, trigDuringFreeze) = st.evaluate(hasPrimaryAudioPid = true, freezeActive = true, confirmedReplay = false, defaultCfg)
    val _ = trigDuringFreeze shouldBe None
    val _ = paused.activeAudioLossNanos shouldBe 0L

    // 35 seconds later, live video resumes and audio follows immediately
    val resumed = paused.onLiveVideoFrame(45L * secNanos, hasPrimaryAudioPid = true, defaultCfg)
    val (afterResume, trigOnResume) = resumed.evaluate(hasPrimaryAudioPid = true, freezeActive = false, confirmedReplay = false, defaultCfg)
    val _ = trigOnResume shouldBe None
    val _ = afterResume.activeAudioLossNanos shouldBe 0L

    val (replayPaused, trigDuringReplay) = afterResume.evaluate(hasPrimaryAudioPid = true, freezeActive = false, confirmedReplay = true, defaultCfg)
    val _ = trigDuringReplay shouldBe None
    replayPaused.lastLiveVideoNanos shouldBe 0L
  }

  it should "pause accumulation during a freeze and resume accurately on an active audio wedge" in {
    val st = AudioLossMonitor.State().onCleanLiveAudio(defaultCfg)
    // 20s of active live video without audio (600 frames)
    val (beforeFreeze, t1) = advanceLiveVideo(st, 10L * secNanos, frameCount = 601, stepNanos = 33_333_334L)
    val (stillBelow, trig1) = beforeFreeze.evaluate(hasPrimaryAudioPid = true, freezeActive = false, confirmedReplay = false, defaultCfg)
    val _ = trig1 shouldBe None
    val _ = (stillBelow.activeAudioLossNanos / secNanos) shouldBe 20L

    // 15s RF freeze: accumulation pauses without adding freeze time or resetting progress
    val (paused, trig2) = stillBelow.evaluate(hasPrimaryAudioPid = true, freezeActive = true, confirmedReplay = false, defaultCfg)
    val _ = trig2 shouldBe None
    val _ = (paused.activeAudioLossNanos / secNanos) shouldBe 20L

    // Resume at t1 + 15s: first frame re-anchors without jumping 15s
    val resumeT = t1 + 15L * secNanos
    val afterFirstFrame = paused.onLiveVideoFrame(resumeT, hasPrimaryAudioPid = true, defaultCfg)
    val _ = (afterFirstFrame.activeAudioLossNanos / secNanos) shouldBe 20L

    // 10s more of live video without audio hits 30s and triggers
    val (afterMore, _) = advanceLiveVideo(afterFirstFrame, resumeT + 33_333_334L, frameCount = 305, stepNanos = 33_333_334L)
    val (_, trig3) = afterMore.evaluate(hasPrimaryAudioPid = true, freezeActive = false, confirmedReplay = false, defaultCfg)
    val info = trig3.getOrElse(fail("Expected trigger after 20s + 10s active audio loss spanning freeze"))
    info.audioLossMs should be >= 30000L
  }

  it should "require 3 consecutive clean audio frames to clear a degraded audio-loss state" in {
    var st = AudioLossMonitor.State().onCleanLiveAudio(defaultCfg)
    st = st.onDroppedLiveAudio(isPtsScrub = true)
    val (degraded, _) = advanceLiveVideo(st, 10L * secNanos, frameCount = 200, stepNanos = 50_000_000L)
    val _ = degraded.activeAudioLossNanos should be > 0L

    // 1 or 2 stray clean frames do not clear degraded state
    val s1 = degraded.onCleanLiveAudio(defaultCfg)
    val _ = s1.activeAudioLossNanos should be > 0L
    val s2 = s1.onCleanLiveAudio(defaultCfg)
    val _ = s2.activeAudioLossNanos should be > 0L

    // Interrupted by another drop resets recovery streak
    val droppedAgain = s2.onDroppedLiveAudio(isPtsScrub = true)
    val _ = droppedAgain.cleanAudioRecoveryStreak shouldBe 0

    // 3 consecutive clean frames clear the degraded state
    val recovered = droppedAgain.onCleanLiveAudio(defaultCfg).onCleanLiveAudio(defaultCfg).onCleanLiveAudio(defaultCfg)
    val _ = recovered.activeAudioLossNanos shouldBe 0L
    recovered.droppedAudioFramesSinceLoss shouldBe 0L
  }

  it should "apply exponential backoff up to 300s across consecutive retunes and reset after 100 clean audio frames" in {
    val _ = defaultCfg.effectiveTimeoutNanos(0) / secNanos shouldBe 30L
    val _ = defaultCfg.effectiveTimeoutNanos(1) / secNanos shouldBe 60L
    val _ = defaultCfg.effectiveTimeoutNanos(2) / secNanos shouldBe 120L
    val _ = defaultCfg.effectiveTimeoutNanos(3) / secNanos shouldBe 240L
    val _ = defaultCfg.effectiveTimeoutNanos(4) / secNanos shouldBe 300L
    val _ = defaultCfg.effectiveTimeoutNanos(8) / secNanos shouldBe 300L

    // First retune at 30s
    val st = AudioLossMonitor.State().onCleanLiveAudio(defaultCfg)
    val (at30s, t1) = advanceLiveVideo(st, 10L * secNanos, frameCount = 910, stepNanos = 33_333_334L)
    val (afterFirst, trig1) = at30s.evaluate(hasPrimaryAudioPid = true, freezeActive = false, confirmedReplay = false, defaultCfg)
    val _ = trig1.map(_.consecutiveRetunes) shouldBe Some(1)

    // Discontinuity from cold retune preserves backoff count
    val afterDisc = afterFirst.onDiscontinuity
    val _ = afterDisc.consecutiveRetunes shouldBe 1

    // 35s after retune is still below 60s backoff
    val (at35s, t2) = advanceLiveVideo(afterDisc, t1, frameCount = 1060, stepNanos = 33_333_334L)
    val (stillWaiting, trigEarly) = at35s.evaluate(hasPrimaryAudioPid = true, freezeActive = false, confirmedReplay = false, defaultCfg)
    val _ = trigEarly shouldBe None

    // 30s more (total 65s >= 60s) triggers second retune
    val (at65s, _) = advanceLiveVideo(stillWaiting, t2, frameCount = 910, stepNanos = 33_333_334L)
    val (afterSecond, trig2) = at65s.evaluate(hasPrimaryAudioPid = true, freezeActive = false, confirmedReplay = false, defaultCfg)
    val _ = trig2.map(_.consecutiveRetunes) shouldBe Some(2)
    val _ = trig2.map(_.nextTimeoutSec) shouldBe Some(120L)

    // 100 clean live audio frames reset consecutiveRetunes back to 0
    var recovered = afterSecond
    var i = 0
    while (i < AudioLossMonitor.DefaultCleanAudioFramesToResetBackoff) {
      recovered = recovered.onCleanLiveAudio(defaultCfg)
      i += 1
    }
    recovered.consecutiveRetunes shouldBe 0
  }

  it should "be completely disabled when timeout <= 0" in {
    val disabledCfg = defaultCfg.copy(timeout = Duration.Zero)
    val started = AudioLossMonitor.State().onCleanLiveAudio(disabledCfg)
    val (afterVideo, _) = advanceLiveVideo(started, 10L * secNanos, frameCount = 1200, stepNanos = 33_333_334L, cfg = disabledCfg)
    val (_, trig) = afterVideo.evaluate(hasPrimaryAudioPid = true, freezeActive = false, confirmedReplay = false, disabledCfg)
    trig shouldBe None
  }
}

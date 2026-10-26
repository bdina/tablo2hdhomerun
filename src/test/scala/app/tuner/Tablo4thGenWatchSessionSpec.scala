package app.tuner

import org.junit.runner.RunWith
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.junit.JUnitRunner

import java.time.Instant

import org.scalatest.concurrent.ScalaFutures
import scala.concurrent.{Future, Promise}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._

@RunWith(classOf[JUnitRunner])
class Tablo4thGenWatchSessionSpec extends AnyFlatSpec with Matchers with ScalaFutures {
  implicit override val patienceConfig: PatienceConfig = PatienceConfig(timeout = 5.seconds)

  private def response(
    token: Option[String] = Some("watch-token")
  , expires: Option[String] = Some("2024-12-31T23:59:59Z")
  , keepalive: Option[Int] = Some(30)
  , playlistUrl: Option[String] = Some("http://example.com/playlist.m3u8")
  ): Tablo4thGen.Channel.Response.Watch4thGenResponse =
    Tablo4thGen.Channel.Response.Watch4thGenResponse(
      token = token
    , expires = expires
    , keepalive = keepalive
    , playlist_url = playlistUrl
    )

  "WatchSession.sessionPath" should "build player session path" in {
    Tablo4thGen.Channel.WatchSession.sessionPath("abc") shouldBe "/player/sessions/abc"
  }

  "WatchSession.keepalivePath" should "build keepalive path" in {
    Tablo4thGen.Channel.WatchSession.keepalivePath("abc") shouldBe "/player/sessions/abc/keepalive"
  }

  "WatchSession.fromResponse" should "build a session from watch response" in {
    Tablo4thGen.Channel.WatchSession.fromResponse(response()) match {
      case Right(session) =>
        val _ = session.playlistUrl shouldBe "http://example.com/playlist.m3u8"
        val _ = session.keepalive shouldBe Some(30)
        session.expires shouldBe Some(Instant.parse("2024-12-31T23:59:59Z"))
      case Left(_) => fail("expected session")
    }
  }

  it should "fail when playlist url is missing" in {
    Tablo4thGen.Channel.WatchSession.fromResponse(response(playlistUrl = None)) shouldBe Left("No playlist URL returned")
  }

  it should "preserve fallback token when response omits token" in {
    Tablo4thGen.Channel.WatchSession.fromResponse(response(token = None), Some("abc")) match {
      case Right(session) => session.token shouldBe Some("abc")
      case Left(_) => fail("expected session")
    }
  }

  "WatchSession.keepaliveDelaySec" should "return None when token is missing" in {
    val session = Tablo4thGen.Channel.WatchSession.Session(None, None, Some(165), "http://example.com/pl.m3u8")
    Tablo4thGen.Channel.WatchSession.keepaliveDelaySec(session) shouldBe None
  }

  it should "return None when keepalive is missing" in {
    val session = Tablo4thGen.Channel.WatchSession.Session(Some("abc"), None, None, "http://example.com/pl.m3u8")
    Tablo4thGen.Channel.WatchSession.keepaliveDelaySec(session) shouldBe None
  }

  it should "schedule before the tablo keepalive interval" in {
    val now = Instant.parse("2026-06-30T19:46:00Z")
    val session = Tablo4thGen.Channel.WatchSession.Session(
      Some("abc")
    , Some(Instant.parse("2026-06-30T19:49:15Z"))
    , Some(165)
    , "http://example.com/pl.m3u8"
    )
    Tablo4thGen.Channel.WatchSession.keepaliveDelaySec(session, now) shouldBe Some(135)
  }

  it should "clamp when expires is sooner than keepalive interval" in {
    val now = Instant.parse("2026-06-30T19:48:50Z")
    val session = Tablo4thGen.Channel.WatchSession.Session(
      Some("abc")
    , Some(Instant.parse("2026-06-30T19:49:15Z"))
    , Some(165)
    , "http://example.com/pl.m3u8"
    )
    Tablo4thGen.Channel.WatchSession.keepaliveDelaySec(session, now) shouldBe Some(5)
  }

  it should "use keepalive lead branch for small intervals" in {
    val session = Tablo4thGen.Channel.WatchSession.Session(
      Some("abc")
    , None
    , Some(9)
    , "http://example.com/pl.m3u8"
    )
    Tablo4thGen.Channel.WatchSession.keepaliveDelaySec(session) shouldBe Some(6)
  }

  it should "floor delay at five seconds" in {
    val session = Tablo4thGen.Channel.WatchSession.Session(
      Some("abc")
    , None
    , Some(6)
    , "http://example.com/pl.m3u8"
    )
    Tablo4thGen.Channel.WatchSession.keepaliveDelaySec(session) shouldBe Some(5)
  }

  "WatchSession.shouldRefreshSession" should "refresh when expiry is missing" in {
    val session = Tablo4thGen.Channel.WatchSession.Session(None, None, Some(30), "http://example.com/pl.m3u8")
    Tablo4thGen.Channel.WatchSession.shouldRefreshSession(session) shouldBe true
  }

  it should "refresh when session is near expiry" in {
    val now = Instant.parse("2024-12-31T23:59:20Z")
    val session = Tablo4thGen.Channel.WatchSession.Session(
      None
    , Some(Instant.parse("2024-12-31T23:59:30Z"))
    , Some(30)
    , "http://example.com/pl.m3u8"
    )
    Tablo4thGen.Channel.WatchSession.shouldRefreshSession(session, now) shouldBe true
  }

  it should "keep a fresh session" in {
    val now = Instant.parse("2024-12-31T23:00:00Z")
    val session = Tablo4thGen.Channel.WatchSession.Session(
      None
    , Some(Instant.parse("2024-12-31T23:59:59Z"))
    , Some(30)
    , "http://example.com/pl.m3u8"
    )
    Tablo4thGen.Channel.WatchSession.shouldRefreshSession(session, now) shouldBe false
  }

  "WatchSession.playlistChanged" should "detect playlist url changes" in {
    val before = Tablo4thGen.Channel.WatchSession.Session(
      Some("abc")
    , None
    , Some(165)
    , "http://example.com/pl.m3u8?a=1"
    )
    val after = before.copy(playlistUrl = "http://example.com/pl.m3u8?a=2")
    val _ = Tablo4thGen.Channel.WatchSession.playlistChanged(before, after) shouldBe true
    val _ = Tablo4thGen.Channel.WatchSession.playlistChanged(before, before) shouldBe false
  }

  "WatchSession.releaseThenTune" should "not tune until release completes" in {
    val releaseP = Promise[Unit]()
    var tuneCalls = 0
    val tuneF = () => {
      tuneCalls += 1
      Future.successful("tuned-session")
    }

    val resF = Tablo4thGen.Channel.WatchSession.releaseThenTune(() => releaseP.future, tuneF)
    val _ = tuneCalls shouldBe 0

    releaseP.success(())
    val _ = resF.futureValue shouldBe "tuned-session"
    val _ = tuneCalls shouldBe 1
  }

  it should "still tune when release fails" in {
    val resF = Tablo4thGen.Channel.WatchSession.releaseThenTune(
      () => Future.failed(new RuntimeException("release failed")),
      () => Future.successful("tuned-after-failed-release")
    )
    resF.futureValue shouldBe "tuned-after-failed-release"
  }

  it should "still tune when release throws synchronously" in {
    val resF = Tablo4thGen.Channel.WatchSession.releaseThenTune(
      () => throw new RuntimeException("release boom"),
      () => Future.successful("tuned-after-throw")
    )
    resF.futureValue shouldBe "tuned-after-throw"
  }

  "WatchSession.computeFlapDelayMillis" should "return zero for non-positive flap counts" in {
    val _ = Tablo4thGen.Channel.WatchSession.computeFlapDelayMillis(0) shouldBe 0L
    Tablo4thGen.Channel.WatchSession.computeFlapDelayMillis(-1) shouldBe 0L
  }

  it should "escalate exponentially up to four seconds cap" in {
    val _ = Tablo4thGen.Channel.WatchSession.computeFlapDelayMillis(1) shouldBe 2000L
    val _ = Tablo4thGen.Channel.WatchSession.computeFlapDelayMillis(2) shouldBe 4000L
    val _ = Tablo4thGen.Channel.WatchSession.computeFlapDelayMillis(3) shouldBe 4000L
    val _ = Tablo4thGen.Channel.WatchSession.computeFlapDelayMillis(4) shouldBe 4000L
    val _ = Tablo4thGen.Channel.WatchSession.computeFlapDelayMillis(5) shouldBe 4000L
    Tablo4thGen.Channel.WatchSession.computeFlapDelayMillis(10) shouldBe 4000L
  }

  "WatchSession.isFlapping" should "detect stalls occurring within thirty seconds of data resumption" in {
    val resumeNanos = 10000000000L
    val flapNanos = resumeNanos + 15.seconds.toNanos
    val stableNanos = resumeNanos + 35.seconds.toNanos

    val _ = Tablo4thGen.Channel.WatchSession.isFlapping(resumeNanos, flapNanos) shouldBe true
    Tablo4thGen.Channel.WatchSession.isFlapping(resumeNanos, stableNanos) shouldBe false
  }

  "WatchSession.FlapState" should "reset flap count to zero on stall after stable streaming" in {
    val initialResume = 10000000000L
    val stableStall = initialResume + 35.seconds.toNanos
    val state = Tablo4thGen.Channel.WatchSession.FlapState(
      dataResumedNanos = initialResume
    , consecutiveFlaps = 3
    )

    val updated = state.onStall(stableStall)
    val _ = updated.consecutiveFlaps shouldBe 0
    val _ = updated.isStalling shouldBe true
    val _ = updated.stallStartTimeNanos shouldBe stableStall
    updated.dataResumedNanos shouldBe initialResume
  }

  it should "increment flap count on stall within thirty seconds of resumption" in {
    val initialResume = 10000000000L
    val flapStall = initialResume + 10.seconds.toNanos
    val state = Tablo4thGen.Channel.WatchSession.FlapState(
      dataResumedNanos = initialResume
    , consecutiveFlaps = 1
    )

    val updated = state.onStall(flapStall)
    val _ = updated.consecutiveFlaps shouldBe 2
    val _ = updated.isStalling shouldBe true
    updated.stallStartTimeNanos shouldBe flapStall
  }

  it should "be idempotent during ongoing stall" in {
    val initialResume = 10000000000L
    val firstStall = initialResume + 10.seconds.toNanos
    val secondStall = firstStall + 5.seconds.toNanos
    val state = Tablo4thGen.Channel.WatchSession.FlapState(
      dataResumedNanos = initialResume
    , consecutiveFlaps = 1
    )

    val stallingState = state.onStall(firstStall)
    val _ = stallingState.consecutiveFlaps shouldBe 2
    val _ = stallingState.stallStartTimeNanos shouldBe firstStall

    val duplicateStall = stallingState.onStall(secondStall)
    duplicateStall shouldBe stallingState
  }

  it should "update dataResumedNanos and clear stalling on segment received after stall" in {
    val initialResume = 10000000000L
    val stallNanos = initialResume + 10.seconds.toNanos
    val recoveryNanos = stallNanos + 2.seconds.toNanos
    val stallingState = Tablo4thGen.Channel.WatchSession.FlapState(
      dataResumedNanos = initialResume
    , consecutiveFlaps = 2
    , isStalling = true
    , stallStartTimeNanos = stallNanos
    )

    val recovered = stallingState.onSegmentReceived(recoveryNanos)
    val _ = recovered.isStalling shouldBe false
    val _ = recovered.dataResumedNanos shouldBe recoveryNanos
    val _ = recovered.stallStartTimeNanos shouldBe 0L
    recovered.consecutiveFlaps shouldBe 2
  }

  it should "preserve dataResumedNanos during continuous streaming without stalls" in {
    val resumeNanos = 10000000000L
    val activeState = Tablo4thGen.Channel.WatchSession.FlapState(
      dataResumedNanos = resumeNanos
    , consecutiveFlaps = 0
    , isStalling = false
    )

    val nextPacketNanos = resumeNanos + 2.seconds.toNanos
    val afterPacket = activeState.onSegmentReceived(nextPacketNanos)
    val _ = afterPacket shouldBe activeState
    afterPacket.dataResumedNanos shouldBe resumeNanos
  }

  "WatchSession.decideRecovery" should "select Soft recovery on attempt 2 when stall is fresh and session valid" in {
    val stallNanos = 10000000000L
    val state = Tablo4thGen.Channel.WatchSession.FlapState(
      dataResumedNanos = stallNanos - 10.seconds.toNanos
    , consecutiveFlaps = 1
    , isStalling = true
    , stallStartTimeNanos = stallNanos
    )

    val decision = Tablo4thGen.Channel.WatchSession.decideRecovery(
      flapState = state
    , attempt = 2
    , softRetryAttempted = false
    , nowNanos = stallNanos + 5.seconds.toNanos
    , shouldRefreshSession = false
    )

    decision shouldBe Tablo4thGen.Channel.WatchSession.RecoveryDecision.Soft(15.seconds)
  }

  it should "escalate to Cold recovery when a soft retry was already attempted" in {
    val stallNanos = 10000000000L
    val state = Tablo4thGen.Channel.WatchSession.FlapState(
      dataResumedNanos = stallNanos - 10.seconds.toNanos
    , consecutiveFlaps = 1
    , isStalling = true
    , stallStartTimeNanos = stallNanos
    )

    val decision = Tablo4thGen.Channel.WatchSession.decideRecovery(
      flapState = state
    , attempt = 3
    , softRetryAttempted = true
    , nowNanos = stallNanos + 5.seconds.toNanos
    , shouldRefreshSession = false
    )

    decision shouldBe Tablo4thGen.Channel.WatchSession.RecoveryDecision.Cold(2000L)
  }

  it should "escalate to Cold recovery when stall duration exceeds soft threshold" in {
    val stallNanos = 10000000000L
    val state = Tablo4thGen.Channel.WatchSession.FlapState(
      dataResumedNanos = stallNanos - 10.seconds.toNanos
    , consecutiveFlaps = 2
    , isStalling = true
    , stallStartTimeNanos = stallNanos
    )

    val decision = Tablo4thGen.Channel.WatchSession.decideRecovery(
      flapState = state
    , attempt = 2
    , softRetryAttempted = false
    , nowNanos = stallNanos + 46.seconds.toNanos
    , shouldRefreshSession = false
    )

    decision shouldBe Tablo4thGen.Channel.WatchSession.RecoveryDecision.Cold(4000L)
  }

  it should "escalate to Cold recovery when session needs refresh" in {
    val stallNanos = 10000000000L
    val state = Tablo4thGen.Channel.WatchSession.FlapState(
      dataResumedNanos = stallNanos - 10.seconds.toNanos
    , consecutiveFlaps = 0
    , isStalling = true
    , stallStartTimeNanos = stallNanos
    )

    val decision = Tablo4thGen.Channel.WatchSession.decideRecovery(
      flapState = state
    , attempt = 2
    , softRetryAttempted = false
    , nowNanos = stallNanos + 2.seconds.toNanos
    , shouldRefreshSession = true
    )

    decision shouldBe Tablo4thGen.Channel.WatchSession.RecoveryDecision.Cold(0L)
  }
}

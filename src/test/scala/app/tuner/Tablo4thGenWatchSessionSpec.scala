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

  "WatchSession.coldRetune" should "skip release when sessionClosedByTablo is true" in {
    var releaseCalled = false
    val resF = Tablo4thGen.Channel.WatchSession.coldRetune(
      sessionClosedByTablo = true
    , release = () => {
        releaseCalled = true
        Future.successful(())
      }
    , tune = () => Future.successful("tuned-directly")
    )
    val _ = resF.futureValue shouldBe "tuned-directly"
    releaseCalled shouldBe false
  }

  it should "call release before tune when sessionClosedByTablo is false" in {
    var releaseCalled = false
    val resF = Tablo4thGen.Channel.WatchSession.coldRetune(
      sessionClosedByTablo = false
    , release = () => {
        releaseCalled = true
        Future.successful(())
      }
    , tune = () => Future.successful("tuned-after-release")
    )
    val _ = resF.futureValue shouldBe "tuned-after-release"
    releaseCalled shouldBe true
  }

  "WatchSession.isSessionClosedStatus" should "treat 400, 401, 403, 404, and 410 as closed and 5xx as transient" in {
    import org.apache.pekko.http.scaladsl.model.StatusCodes
    val _ = Tablo4thGen.Channel.WatchSession.isSessionClosedStatus(StatusCodes.NotFound) shouldBe true
    val _ = Tablo4thGen.Channel.WatchSession.isSessionClosedStatus(StatusCodes.Gone) shouldBe true
    val _ = Tablo4thGen.Channel.WatchSession.isSessionClosedStatus(StatusCodes.Unauthorized) shouldBe true
    val _ = Tablo4thGen.Channel.WatchSession.isSessionClosedStatus(StatusCodes.Forbidden) shouldBe true
    val _ = Tablo4thGen.Channel.WatchSession.isSessionClosedStatus(StatusCodes.BadRequest) shouldBe true
    val _ = Tablo4thGen.Channel.WatchSession.isSessionClosedStatus(StatusCodes.InternalServerError) shouldBe false
    Tablo4thGen.Channel.WatchSession.isSessionClosedStatus(StatusCodes.ServiceUnavailable) shouldBe false
  }

  "WatchSession.classifyProbeResult" should "return Alive, detect playlist changes, and reset failure count on success" in {
    import scala.util.Success
    val prev = Tablo4thGen.Channel.WatchSession.Session(
      Some("tok-1")
    , Some(Instant.parse("2026-06-30T19:48:50Z"))
    , Some(165)
    , "http://example.com/pl.m3u8?a=1"
    )
    val nextSame = prev.copy(expires = Some(Instant.parse("2026-06-30T19:51:35Z")))
    val (outcome1, failures1) = Tablo4thGen.Channel.WatchSession.classifyProbeResult(
      previous = prev
    , result = Success(nextSame)
    , consecutiveFailures = 2
    , maxFailures = 4
    )
    val _ = outcome1 shouldBe Tablo4thGen.Channel.WatchSession.ProbeOutcome.Alive(nextSame, playlistChanged = false)
    val _ = failures1 shouldBe 0

    val nextRolled = nextSame.copy(playlistUrl = "http://example.com/pl.m3u8?a=2")
    val (outcome2, failures2) = Tablo4thGen.Channel.WatchSession.classifyProbeResult(
      previous = prev
    , result = Success(nextRolled)
    , consecutiveFailures = 1
    , maxFailures = 4
    )
    val _ = outcome2 shouldBe Tablo4thGen.Channel.WatchSession.ProbeOutcome.Alive(nextRolled, playlistChanged = true)
    failures2 shouldBe 0
  }

  it should "return ClosedByTablo immediately on 404 or 410" in {
    import org.apache.pekko.http.scaladsl.model.StatusCodes
    import scala.util.Failure
    val prev = Tablo4thGen.Channel.WatchSession.Session(Some("tok-1"), None, Some(165), "http://example.com/pl.m3u8")
    val err404 = Tablo4thGen.Error.SessionFailed("session POST failed: 404", Some(StatusCodes.NotFound))
    val (outcome, failures) = Tablo4thGen.Channel.WatchSession.classifyProbeResult(
      previous = prev
    , result = Failure(err404)
    , consecutiveFailures = 0
    , maxFailures = 4
    )
    val _ = outcome shouldBe Tablo4thGen.Channel.WatchSession.ProbeOutcome.ClosedByTablo
    failures shouldBe 1
  }

  it should "tolerate transient 5xx or network errors up to maxFailures before returning ProbeExhausted" in {
    import org.apache.pekko.http.scaladsl.model.StatusCodes
    import scala.util.Failure
    val prev = Tablo4thGen.Channel.WatchSession.Session(Some("tok-1"), None, Some(165), "http://example.com/pl.m3u8")
    val err503 = Tablo4thGen.Error.SessionFailed("session POST failed: 503", Some(StatusCodes.ServiceUnavailable))

    var failures = 0
    (1 to 3).foreach { expected =>
      val (outcome, nextFailures) = Tablo4thGen.Channel.WatchSession.classifyProbeResult(
        previous = prev
      , result = Failure(err503)
      , consecutiveFailures = failures
      , maxFailures = 4
      )
      val _ = outcome shouldBe Tablo4thGen.Channel.WatchSession.ProbeOutcome.TransientFailure
      val _ = nextFailures shouldBe expected
      failures = nextFailures
    }

    val (finalOutcome, finalFailures) = Tablo4thGen.Channel.WatchSession.classifyProbeResult(
      previous = prev
    , result = Failure(new RuntimeException("connection timed out"))
    , consecutiveFailures = failures
    , maxFailures = 4
    )
    val _ = finalOutcome shouldBe Tablo4thGen.Channel.WatchSession.ProbeOutcome.ProbeExhausted
    finalFailures shouldBe 4
  }

  "WatchSession.initialTuneTimeout" should "default to 60 seconds when recoveryTimeoutSec is 0 or negative and use configured value when positive" in {
    val _ = Tablo4thGen.Channel.WatchSession.initialTuneTimeout(0) shouldBe 60.seconds
    val _ = Tablo4thGen.Channel.WatchSession.initialTuneTimeout(-1) shouldBe 60.seconds
    Tablo4thGen.Channel.WatchSession.initialTuneTimeout(25) shouldBe 25.seconds
  }

  "WatchSession.classifyWatch503" should "distinguish tuner exhaustion from signal lock or generic 503 failures" in {
    val _ = Tablo4thGen.Channel.WatchSession.classifyWatch503("""{"error":"No available tuners"}""") shouldBe Tablo4thGen.Error.NoAvailableTuners
    val signalErr = Tablo4thGen.Channel.WatchSession.classifyWatch503("""{"error":"Weak signal: failed to lock channel"}""")
    val _ = signalErr shouldBe a[Tablo4thGen.Error.WatchFailed]
    val _ = signalErr.getMessage should include("Weak signal")
    val emptyErr = Tablo4thGen.Channel.WatchSession.classifyWatch503("")
    val _ = emptyErr shouldBe a[Tablo4thGen.Error.WatchFailed]
    emptyErr.getMessage should include("watch 503")
  }

  "WatchSession.retryInitialTune" should "stop retrying immediately when cancelled" in {
    import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}
    val cancelled = new AtomicBoolean(false)
    val attempts = new AtomicInteger(0)
    val dummyCancellable = new org.apache.pekko.actor.Cancellable {
      override def cancel(): Boolean = true
      override def isCancelled: Boolean = false
    }

    val fut = Tablo4thGen.Channel.WatchSession.retryInitialTune(
      tune = { n =>
        val _ = attempts.incrementAndGet()
        if (n == 2) cancelled.set(true)
        Future.failed(new RuntimeException(s"fail-$n"))
      }
    , onCancelRelease = _ => ()
    , isCancelled = () => cancelled.get()
    , scheduleRetry = (_, task) => {
        task()
        dummyCancellable
      }
    , onRetryTimer = _ => ()
    , timeoutBudget = 60.seconds
    , retryDelay = 10.millis
    )

    val ex = fut.failed.futureValue
    val _ = ex shouldBe Tablo4thGen.Error.WatchCancelled
    attempts.get() shouldBe 2
  }

  it should "release the Tablo session if an in-flight tune succeeds after cancellation" in {
    import java.util.concurrent.atomic.AtomicBoolean
    val cancelled = new AtomicBoolean(false)
    val releasedToken = new java.util.concurrent.atomic.AtomicReference[Option[String]](None)
    val tunePromise = Promise[Tablo4thGen.Channel.WatchSession.Session]()
    val dummyCancellable = new org.apache.pekko.actor.Cancellable {
      override def cancel(): Boolean = true
      override def isCancelled: Boolean = false
    }

    val fut = Tablo4thGen.Channel.WatchSession.retryInitialTune(
      tune = _ => tunePromise.future
    , onCancelRelease = s => releasedToken.set(s.token)
    , isCancelled = () => cancelled.get()
    , scheduleRetry = (_, _) => dummyCancellable
    , onRetryTimer = _ => ()
    , timeoutBudget = 60.seconds
    )

    cancelled.set(true)
    tunePromise.success(Tablo4thGen.Channel.WatchSession.Session(Some("leaked-tok"), None, Some(165), "http://example.com/pl.m3u8"))

    val ex = fut.failed.futureValue
    val _ = ex shouldBe Tablo4thGen.Error.WatchCancelled
    releasedToken.get() shouldBe Some("leaked-tok")
  }
}

package app.tuner

import org.apache.pekko.http.scaladsl.model.{HttpHeader, StatusCode, StatusCodes}
import org.apache.pekko.http.scaladsl.model.headers.RawHeader
import org.apache.pekko.stream.StreamTcpException
import org.junit.runner.RunWith
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.junit.JUnitRunner

import java.io.IOException
import java.util.concurrent.{CompletionException, ExecutionException, TimeoutException}

import app.stream.{HlsBackend, ResilientHlsSource}

@RunWith(classOf[JUnitRunner])
class HDHomeRunErrorSpec extends AnyFlatSpec with Matchers {

  "HDHomeRunError" should "expose SiliconDust HTTP statuses and X-HDHomeRun-Error headers" in {
    val cases: Seq[(HDHomeRunError, StatusCode, String)] = Seq(
      (HDHomeRunError.UnknownChannel, StatusCodes.NotFound, "801 Unknown Channel")
    , (HDHomeRunError.UnknownTranscodeProfile, StatusCodes.ServiceUnavailable, "802 Unknown Transcode Profile")
    , (HDHomeRunError.SystemBusy, StatusCodes.ServiceUnavailable, "803 System Busy")
    , (HDHomeRunError.TunerInUse, StatusCodes.ServiceUnavailable, "804 Tuner In Use")
    , (HDHomeRunError.AllTunersInUse, StatusCodes.ServiceUnavailable, "805 All Tuners In Use")
    , (HDHomeRunError.TuneFailed, StatusCodes.ServiceUnavailable, "806 Tune Failed")
    , (HDHomeRunError.NoVideoData, StatusCodes.ServiceUnavailable, "807 No Video Data")
    )

    cases.foreach { case (err, expectedStatus, expectedHeader) =>
      val resp = err.toHttpResponse()
      val _ = resp.status shouldBe expectedStatus
      val _ = resp.headers.collectFirst {
        case h: HttpHeader if h.is("x-hdhomerun-error") => h.value()
      } shouldBe Some(expectedHeader)
    }
    succeed
  }

  it should "preserve extra headers and allow custom response body overrides" in {
    val cors = RawHeader("Access-Control-Allow-Origin", "*")
    val resp = HDHomeRunError.UnknownChannel.toHttpResponse(
      extraHeaders = Seq(cors)
    , bodyOverride = Some("Channel v99.9 not found")
    )
    val _ = resp.status shouldBe StatusCodes.NotFound
    val _ = resp.headers.exists(_.is("access-control-allow-origin")) shouldBe true
    resp.headers.find(_.is("x-hdhomerun-error")).map(_.value()) shouldBe Some("801 Unknown Channel")
  }

  "HDHomeRunError.parseErrorPayload" should "extract 4th gen nested and Legacy flat JSON error fields safely" in {
    val fourthGen = HDHomeRunError.parseErrorPayload(
      """{"error":{"code":"playback_failed","details":"no_signal_lock","description":"Unable to tune or get signal lock"}}"""
    )
    val _ = fourthGen.code shouldBe Some("playback_failed")
    val _ = fourthGen.details shouldBe Some("no_signal_lock")
    val _ = fourthGen.message shouldBe Some("Unable to tune or get signal lock")
    val _ = HDHomeRunError.isWeakSignal(fourthGen) shouldBe true
    val _ = HDHomeRunError.isTunerExhausted(fourthGen) shouldBe false

    val legacy = HDHomeRunError.parseErrorPayload(
      """{"error":"no_available_tuners","message":"No tuners are available for streaming"}"""
    )
    val _ = legacy.code shouldBe Some("no_available_tuners")
    val _ = legacy.message shouldBe Some("No tuners are available for streaming")
    val _ = HDHomeRunError.isTunerExhausted(legacy) shouldBe true

    val plain = HDHomeRunError.parseErrorPayload("Channel scan busy")
    val _ = plain.code shouldBe None
    val _ = HDHomeRunError.isSystemBusy(plain) shouldBe true

    val _ = HDHomeRunError.classifyUpstreamHttpFailure(StatusCodes.Conflict, "")._1 shouldBe HDHomeRunError.FailureCategory.NoAvailableTuners
    val _ = HDHomeRunError.classifyUpstreamHttpFailure(StatusCodes.Conflict, """{"error":"other"}""")._1 shouldBe HDHomeRunError.FailureCategory.TuneFailed
    HDHomeRunError.classifyUpstreamHttpFailure(StatusCodes.ServiceUnavailable, "")._1 shouldBe HDHomeRunError.FailureCategory.EmptyBodyTuneFailure
  }

  "HDHomeRunError.fromThrowable" should "map 4th gen, Legacy, HLS, and transport exceptions cleanly" in {
    val _ = HDHomeRunError.fromThrowable(Tablo4thGen.Error.NoAvailableTuners) shouldBe HDHomeRunError.AllTunersInUse
    val _ = HDHomeRunError.fromThrowable(TabloLegacy.Channel.Response.NoAvailableTunersError) shouldBe HDHomeRunError.AllTunersInUse
    val _ = HDHomeRunError.fromThrowable(Tablo4thGen.Error.ChannelNotFound("missing")) shouldBe HDHomeRunError.UnknownChannel
    val _ = HDHomeRunError.fromThrowable(TabloLegacy.Channel.Response.ChannelNotFoundError("missing")) shouldBe HDHomeRunError.UnknownChannel
    val _ = HDHomeRunError.fromThrowable(Tablo4thGen.Error.NoSignalLock("no_signal_lock")) shouldBe HDHomeRunError.TuneFailed
    val _ = HDHomeRunError.fromThrowable(Tablo4thGen.Error.WatchFailed("watch failed")) shouldBe HDHomeRunError.TuneFailed
    val _ = HDHomeRunError.fromThrowable(TabloLegacy.Channel.Response.TuneFailedError("tune failed")) shouldBe HDHomeRunError.TuneFailed
    val _ = HDHomeRunError.fromThrowable(Tablo4thGen.Error.NoVideoData("no playlist")) shouldBe HDHomeRunError.NoVideoData
    val _ = HDHomeRunError.fromThrowable(TabloLegacy.Channel.Response.NoVideoDataError("empty")) shouldBe HDHomeRunError.NoVideoData
    val _ = HDHomeRunError.fromThrowable(HlsBackend.HlsError.SessionEnded) shouldBe HDHomeRunError.NoVideoData
    val _ = HDHomeRunError.fromThrowable(new ResilientHlsSource.StallTimeoutException("stalled")) shouldBe HDHomeRunError.NoVideoData
    val _ = HDHomeRunError.fromThrowable(Tablo4thGen.Error.SystemBusy("busy")) shouldBe HDHomeRunError.SystemBusy
    val _ = HDHomeRunError.fromThrowable(Tablo4thGen.Error.SessionTokenMissing) shouldBe HDHomeRunError.SystemBusy
    val _ = HDHomeRunError.fromThrowable(TabloLegacy.Channel.Response.SystemBusyError("busy")) shouldBe HDHomeRunError.SystemBusy
    val _ = HDHomeRunError.fromThrowable(new IOException("connection reset")) shouldBe HDHomeRunError.SystemBusy
    val _ = HDHomeRunError.fromThrowable(new StreamTcpException("tcp fail")) shouldBe HDHomeRunError.SystemBusy
    val _ = HDHomeRunError.fromThrowable(new TimeoutException("ask timeout")) shouldBe HDHomeRunError.SystemBusy
    val _ = HDHomeRunError.fromThrowable(new ExecutionException(new CompletionException(Tablo4thGen.Error.NoSignalLock("wrapped")))) shouldBe HDHomeRunError.TuneFailed
    val _ = HDHomeRunError.fromThrowable(new StreamTcpException("connect failed")) shouldBe HDHomeRunError.SystemBusy
    HDHomeRunError.fromThrowable(new RuntimeException("unexpected")) shouldBe HDHomeRunError.TuneFailed
  }
}

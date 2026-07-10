package app.tuner

import org.junit.runner.RunWith
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.junit.JUnitRunner

import java.time.Instant

@RunWith(classOf[JUnitRunner])
class Tablo4thGenWatchSessionSpec extends AnyFlatSpec with Matchers {

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
}

package app.tuner

import org.junit.runner.RunWith
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.junit.JUnitRunner

import java.time.Instant

@RunWith(classOf[JUnitRunner])
class Tablo4thGenWatchSessionSpec extends AnyFlatSpec with Matchers {

  private def response(
    expires: Option[String] = Some("2024-12-31T23:59:59Z")
  , keepalive: Option[Int] = Some(30)
  , playlistUrl: Option[String] = Some("http://example.com/playlist.m3u8")
  ): Tablo4thGen.Channel.Response.Watch4thGenResponse =
    Tablo4thGen.Channel.Response.Watch4thGenResponse(
      token = Some("watch-token")
    , expires = expires
    , keepalive = keepalive
    , playlist_url = playlistUrl
    )

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
}

package app.stream

import org.junit.runner.RunWith
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.junit.JUnitRunner

import org.apache.pekko.http.scaladsl.model.headers.{EntityTag, `If-None-Match`, RawHeader}

@RunWith(classOf[JUnitRunner])
class HlsPlaylistFetchSpec extends AnyFlatSpec with Matchers {

  private val sampleModified = "Wed, 21 Oct 2015 07:28:00 GMT"

  "HlsPlaylistFetch.conditionalHeaders" should "include If-None-Match when etag is present" in {
    val headers = HlsPlaylistFetch.conditionalHeaders(HlsPlaylistFetch.Validators(etag = Some("abc")))
    headers.exists(_.isInstanceOf[`If-None-Match`]) shouldBe true
  }

  it should "include If-Modified-Since when lastModified is present" in {
    val headers = HlsPlaylistFetch.conditionalHeaders(
      HlsPlaylistFetch.Validators(lastModified = Some(sampleModified))
    )
    headers.collect { case h: RawHeader if h.name == "If-Modified-Since" => h.value } shouldBe Seq(sampleModified)
  }

  it should "return empty headers when validators are absent" in {
    HlsPlaylistFetch.conditionalHeaders(HlsPlaylistFetch.Validators()) shouldBe empty
  }

  "HlsPlaylistFetch.extractValidators" should "read etag and last-modified from response headers" in {
    val headers = Seq(
      RawHeader("ETag", "\"etag-1\"")
    , RawHeader("Last-Modified", sampleModified)
    )
    val validators = HlsPlaylistFetch.extractValidators(headers)
    val _ = validators.etag shouldBe Some("etag-1")
    validators.lastModified should not be empty
  }

  "HlsPlaylistPoller.onPlaylistNotModified" should "increment stall polls without emitting segments" in {
    val state = HlsPlaylistPoller.PollState(
      "http://host/pl.m3u8"
    , lastSeq = 12
    , lastTargetDuration = 6
    , stallPolls = 0
    , fetchFailures = 0
    , loggedFirstSegment = true
    , etag = Some("etag-1")
    )
    HlsPlaylistPoller.onPlaylistNotModified(state, maxStallPolls = 3) match {
      case HlsPlaylistPoller.Emit(next, segments) =>
        val _ = next.stallPolls shouldBe 1
        val _ = next.lastAdvanced shouldBe false
        segments shouldBe empty
      case _ => fail("expected emit")
    }
  }

  it should "fail after repeated not-modified polls" in {
    val state = HlsPlaylistPoller.PollState(
      "http://host/pl.m3u8"
    , lastSeq = 12
    , lastTargetDuration = 6
    , stallPolls = 2
    , fetchFailures = 0
    , loggedFirstSegment = true
    )
    HlsPlaylistPoller.onPlaylistNotModified(state, maxStallPolls = 3) shouldBe
      HlsPlaylistPoller.Fail(HlsBackend.HlsError.PlaylistStall)
  }

  it should "not re-emit after not-modified then unchanged playlist body" in {
    var state = HlsPlaylistPoller.PollState(
      "http://host/pl.m3u8"
    , lastSeq = 12
    , lastTargetDuration = 6
    , stallPolls = 0
    , fetchFailures = 0
    , loggedFirstSegment = true
    , emittedKeys = Set("10|http://host/a.ts", "11|http://host/b.ts")
    , etag = Some("etag-1")
    )
    HlsPlaylistPoller.onPlaylistNotModified(state, maxStallPolls = 3) match {
      case HlsPlaylistPoller.Emit(next, segments) =>
        val _ = segments shouldBe empty
        state = next
      case _ => fail("expected not-modified emit")
    }
    val p = M3U8.Playlist(
      targetDuration = 6
    , mediaSequence = 10
    , segments = Seq(M3U8.Segment("a.ts", 6.0, None, None), M3U8.Segment("b.ts", 6.0, None, None))
    , isEndList = false
    )
    HlsPlaylistPoller.onPlaylist(state, p, maxStallPolls = 3, defaultPollSec = 2) match {
      case HlsPlaylistPoller.Emit(_, segments) => segments shouldBe empty
      case _ => fail("expected unchanged playlist emit")
    }
  }
}

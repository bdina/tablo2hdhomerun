package app.stream

import org.junit.runner.RunWith
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.junit.JUnitRunner

@RunWith(classOf[JUnitRunner])
class HlsPlaylistPollerSpec extends AnyFlatSpec with Matchers {

  private def playlist(
    mediaSequence: Int,
    segmentUris: Seq[String],
    endList: Boolean = false
  ): M3U8.Playlist =
    M3U8.Playlist(
      targetDuration = 6,
      mediaSequence = mediaSequence,
      segments = segmentUris.map(uri => M3U8.Segment(uri, 6.0, None, None)),
      isEndList = endList
    )

  "HlsPlaylistPoller.onPlaylist" should "emit segments on normal advance" in {
    val state = HlsPlaylistPoller.initial("http://host/pl.m3u8")
    val p = playlist(10, Seq("a.ts", "b.ts"))
    HlsPlaylistPoller.onPlaylist(state, p, maxStallPolls = 3, defaultPollSec = 2) match {
      case HlsPlaylistPoller.Emit(next, segments) =>
        val _ = next.lastSeq shouldBe 12
        segments should not be empty
      case HlsPlaylistPoller.Fail(_) => fail("expected emit")
    }
  }

  it should "fail on ENDLIST" in {
    val state = HlsPlaylistPoller.initial("http://host/pl.m3u8")
    val p = playlist(0, Seq("a.ts"), endList = true)
    HlsPlaylistPoller.onPlaylist(state, p, 3, 2) shouldBe HlsPlaylistPoller.Fail(HlsBackend.HlsError.SessionEnded)
  }

  it should "fail after repeated stall polls" in {
    var state = HlsPlaylistPoller.initial("http://host/pl.m3u8")
    val p = playlist(5, Seq("a.ts", "b.ts"))
    val first = HlsPlaylistPoller.onPlaylist(state, p, maxStallPolls = 2, defaultPollSec = 2)
    first match {
      case HlsPlaylistPoller.Emit(next, _) => state = next
      case _ => fail("expected first emit")
    }
    val second = HlsPlaylistPoller.onPlaylist(state, p, maxStallPolls = 2, defaultPollSec = 2)
    second match {
      case HlsPlaylistPoller.Emit(next, _) => state = next
      case _ => fail("expected second emit")
    }
    HlsPlaylistPoller.onPlaylist(state, p, maxStallPolls = 2, defaultPollSec = 2) shouldBe
      HlsPlaylistPoller.Fail(HlsBackend.HlsError.PlaylistStall)
  }

  "HlsPlaylistPoller.onFetchError" should "fail when max failures reached" in {
    val state = HlsPlaylistPoller.PollState("http://host/pl.m3u8", 0, 0, 0, 1, false)
    HlsPlaylistPoller.onFetchError(state, maxFetchFailures = 2) shouldBe
      HlsPlaylistPoller.Fail(HlsBackend.HlsError.PollExhausted)
  }
}

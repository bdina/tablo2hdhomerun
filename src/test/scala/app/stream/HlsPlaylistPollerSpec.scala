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

  private def byteRangePlaylist(mediaSequence: Int, segments: Seq[M3U8.Segment]): M3U8.Playlist =
    M3U8.Playlist(
      targetDuration = 6,
      mediaSequence = mediaSequence,
      segments = segments,
      isEndList = false,
      version = Some(4)
    )

  "HlsPlaylistPoller.onPlaylist" should "emit segments on normal advance" in {
    val state = HlsPlaylistPoller.initial("http://host/pl.m3u8")
    val p = playlist(10, Seq("a.ts", "b.ts"))
    HlsPlaylistPoller.onPlaylist(state, p, maxStallPolls = 3, defaultPollSec = 2) match {
      case HlsPlaylistPoller.Emit(next, segments) =>
        val _ = next.lastSeq shouldBe 12
        val _ = segments should not be empty
        val _ = segments.head.sequence shouldBe 10
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

  it should "not re-emit segments on unchanged playlist polls" in {
    var state = HlsPlaylistPoller.initial("http://host/pl.m3u8")
    val p = playlist(5, Seq("a.ts", "b.ts"))
    val first = HlsPlaylistPoller.onPlaylist(state, p, maxStallPolls = 3, defaultPollSec = 2)
    first match {
      case HlsPlaylistPoller.Emit(next, segments) =>
        val _ = segments should not be empty
        state = next
      case _ => fail("expected first emit")
    }
    HlsPlaylistPoller.onPlaylist(state, p, maxStallPolls = 3, defaultPollSec = 2) match {
      case HlsPlaylistPoller.Emit(next, segments) =>
        val _ = next.stallPolls shouldBe 1
        segments shouldBe empty
      case _ => fail("expected second emit with no segments")
    }
  }

  it should "emit distinct byte ranges for the same URI" in {
    val state = HlsPlaylistPoller.initial("http://host/pl.m3u8")
    val p = byteRangePlaylist(
      0
    , Seq(
        M3U8.Segment("stream.ts", 6.0, None, Some((0L, 1000L)))
      , M3U8.Segment("stream.ts", 6.0, None, Some((1000L, 1000L)))
      )
    )
    HlsPlaylistPoller.onPlaylist(state, p, maxStallPolls = 3, defaultPollSec = 2) match {
      case HlsPlaylistPoller.Emit(_, segments) =>
        val _ = segments should have size 2
        val _ = segments.map(_.url).distinct shouldBe Seq("http://host/stream.ts")
        segments.map(_.byteRange) shouldBe Seq(Some((0L, 1000L)), Some((1000L, 1000L)))
      case _ => fail("expected emit")
    }
  }

  "HlsPlaylistPoller.onFetchError" should "fail when max failures reached" in {
    val state = HlsPlaylistPoller.PollState("http://host/pl.m3u8", 0, 0, 0, 1, false)
    HlsPlaylistPoller.onFetchError(state, maxFetchFailures = 2) shouldBe
      HlsPlaylistPoller.Fail(HlsBackend.HlsError.PollExhausted)
  }

  "HlsPlaylistPoller.pollDelaySec" should "return zero for first poll" in {
    val state = HlsPlaylistPoller.initial("http://host/pl.m3u8")
    HlsPlaylistPoller.pollDelaySec(state, playlistAdvanced = true, targetDurationSec = 6, defaultPollSec = 2) shouldBe 0
  }

  it should "wait target duration after advance" in {
    val state = HlsPlaylistPoller.PollState("http://host/pl.m3u8", 12, 6, 0, 0, true, Set.empty, lastAdvanced = true)
    HlsPlaylistPoller.pollDelaySec(state, playlistAdvanced = true, targetDurationSec = 6, defaultPollSec = 2) shouldBe 6
  }

  it should "wait half target duration when unchanged" in {
    val state = HlsPlaylistPoller.PollState("http://host/pl.m3u8", 12, 6, 1, 0, true, Set.empty, lastAdvanced = false)
    HlsPlaylistPoller.pollDelaySec(state, playlistAdvanced = false, targetDurationSec = 6, defaultPollSec = 2) shouldBe 3
  }

  it should "clamp delay to configured min and max" in {
    val state = HlsPlaylistPoller.PollState("http://host/pl.m3u8", 12, 20, 0, 0, true, Set.empty, lastAdvanced = true)
    val _ = HlsPlaylistPoller.pollDelaySec(state, playlistAdvanced = true, targetDurationSec = 20, defaultPollSec = 2) shouldBe 10
    HlsPlaylistPoller.pollDelaySec(state, playlistAdvanced = false, targetDurationSec = 1, defaultPollSec = 2) shouldBe 1
  }

  it should "bound emittedKeys to the current playlist window" in {
    var state = HlsPlaylistPoller.initial("http://host/pl.m3u8")
    val windowSize = 2
    (0 until 10).foreach { seq =>
      val p = playlist(seq, Seq(s"seg${seq}.ts", s"seg${seq + 1}.ts"))
      HlsPlaylistPoller.onPlaylist(state, p, maxStallPolls = 20, defaultPollSec = 2) match {
        case HlsPlaylistPoller.Emit(next, _) => state = next
        case _ => fail(s"expected emit at seq=$seq")
      }
      state.emittedKeys.size should be <= windowSize
    }
  }
}

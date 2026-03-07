package app.stream

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class HlsBackendSpec extends AnyFlatSpec with Matchers {

  "HlsBackend" should "have name hls" in {
    HlsBackend.name shouldBe "hls"
  }

  it should "implement StreamBackend" in {
    HlsBackend.isInstanceOf[StreamBackend] shouldBe true
  }

  "M3U8.resolveSegmentUri" should "build absolute segment URL from relative and base" in {
    val base = "http://host/dir/playlist.m3u8"
    val rel = "seg0.ts"
    val resolved = M3U8.resolveSegmentUri(rel, base)
    resolved shouldBe "http://host/dir/seg0.ts"
  }

  "M3U8.Playlist" should "expose targetDuration for polling interval" in {
    val p = M3U8.Playlist(targetDuration = 6, mediaSequence = 0, segments = Seq.empty, isEndList = false)
    p.targetDuration shouldBe 6
  }
}

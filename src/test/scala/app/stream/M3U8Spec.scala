package app.stream

import org.junit.runner.RunWith
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.junit.JUnitRunner

@RunWith(classOf[JUnitRunner])
class M3U8Spec extends AnyFlatSpec with Matchers {

  "M3U8.parse" should "parse a minimal media playlist" in {
    val raw =
      """#EXTM3U
        |#EXT-X-TARGETDURATION:10
        |#EXT-X-MEDIA-SEQUENCE:0
        |#EXTINF:10.0,
        |segment0.ts
        |#EXTINF:10.0,
        |segment1.ts
        |#EXT-X-ENDLIST
        |""".stripMargin
    val p = M3U8.parse(raw)
    val _ = p.targetDuration shouldBe 10
    val _ = p.mediaSequence shouldBe 0
    val _ = p.isEndList shouldBe true
    val _ = p.segments should have size 2
    val _ = p.segments(0).uri shouldBe "segment0.ts"
    val _ = p.segments(0).duration shouldBe 10.0
    p.segments(1).uri shouldBe "segment1.ts"
  }

  it should "use defaults when tags are missing" in {
    val raw =
      """#EXTM3U
        |#EXTINF:5.0,
        |a.ts
        |""".stripMargin
    val p = M3U8.parse(raw)
    val _ = p.targetDuration shouldBe 10
    val _ = p.mediaSequence shouldBe 0
    val _ = p.isEndList shouldBe false
    val _ = p.segments should have size 1
    p.segments(0).duration shouldBe 5.0
  }

  it should "parse EXTINF with title" in {
    val raw =
      """#EXTM3U
        |#EXT-X-TARGETDURATION:8
        |#EXTINF:8.0,Segment title
        |seg.ts
        |""".stripMargin
    val p = M3U8.parse(raw)
    val _ = p.segments should have size 1
    val _ = p.segments(0).title shouldBe Some("Segment title")
    p.segments(0).duration shouldBe 8.0
  }

  it should "resolve relative segment URI against base" in {
    val base = "http://example.com/path/playlist.m3u8"
    val _ = M3U8.resolveSegmentUri("seg.ts", base) shouldBe "http://example.com/path/seg.ts"
    M3U8.resolveSegmentUri("sub/seg.ts", base) shouldBe "http://example.com/path/sub/seg.ts"
  }

  it should "leave absolute segment URI unchanged" in {
    val base = "http://example.com/playlist.m3u8"
    val abs = "https://cdn.example.com/seg.ts"
    M3U8.resolveSegmentUri(abs, base) shouldBe abs
  }

  it should "detect endlist" in {
    val withEnd =
      """#EXTM3U
        |#EXT-X-TARGETDURATION:10
        |#EXTINF:10.0,
        |x.ts
        |#EXT-X-ENDLIST
        |""".stripMargin
    val _ = M3U8.parse(withEnd).isEndList shouldBe true
    val withoutEnd =
      """#EXTM3U
        |#EXT-X-TARGETDURATION:10
        |#EXTINF:10.0,
        |x.ts
        |""".stripMargin
    M3U8.parse(withoutEnd).isEndList shouldBe false
  }

  it should "parse EXT-X-BYTERANGE with length only" in {
    val raw =
      """#EXTM3U
        |#EXT-X-TARGETDURATION:10
        |#EXT-X-MEDIA-SEQUENCE:0
        |#EXTINF:10.0,
        |#EXT-X-BYTERANGE:1000
        |seg0.ts
        |#EXTINF:10.0,
        |#EXT-X-BYTERANGE:2000
        |seg1.ts
        |""".stripMargin
    val p = M3U8.parse(raw)
    val _ = p.segments should have size 2
    val _ = p.segments(0).byteRange shouldBe Some((0L, 1000L))
    p.segments(1).byteRange shouldBe Some((1000L, 2000L))
  }

  it should "parse EXT-X-BYTERANGE with length@offset" in {
    val raw =
      """#EXTM3U
        |#EXT-X-TARGETDURATION:10
        |#EXTINF:10.0,
        |#EXT-X-BYTERANGE:500@1000
        |seg.ts
        |""".stripMargin
    val p = M3U8.parse(raw)
    val _ = p.segments should have size 1
    p.segments(0).byteRange shouldBe Some((1000L, 500L))
  }

  it should "return defaults and empty segments for empty playlist" in {
    val raw = "#EXTM3U"
    val p = M3U8.parse(raw)
    val _ = p.targetDuration shouldBe 10
    val _ = p.mediaSequence shouldBe 0
    val _ = p.isEndList shouldBe false
    p.segments shouldBe empty
  }

  it should "parse when EXTM3U is missing" in {
    val raw =
      """#EXT-X-TARGETDURATION:6
        |#EXTINF:6.0,
        |a.ts
        |""".stripMargin
    val p = M3U8.parse(raw)
    val _ = p.targetDuration shouldBe 6
    val _ = p.segments should have size 1
    p.segments(0).uri shouldBe "a.ts"
  }

  it should "handle malformed EXTINF with default duration" in {
    val raw =
      """#EXTM3U
        |#EXT-X-TARGETDURATION:10
        |#EXTINF:,
        |seg.ts
        |""".stripMargin
    val p = M3U8.parse(raw)
    val _ = p.segments should have size 1
    val _ = p.segments(0).duration shouldBe 0.0
    p.segments(0).title shouldBe None
  }

  it should "parse URI-only line as segment with zero duration" in {
    val raw =
      """#EXTM3U
        |#EXT-X-TARGETDURATION:10
        |bare.ts
        |""".stripMargin
    val p = M3U8.parse(raw)
    val _ = p.segments should have size 1
    val _ = p.segments(0).uri shouldBe "bare.ts"
    val _ = p.segments(0).duration shouldBe 0.0
    p.segments(0).title shouldBe None
  }

  it should "parse EXT-X-VERSION 4" in {
    val raw =
      """#EXTM3U
        |#EXT-X-VERSION:4
        |#EXT-X-TARGETDURATION:6
        |#EXTINF:6.0,
        |a.ts
        |""".stripMargin
    val p = M3U8.parse(raw)
    val _ = p.version shouldBe Some(4)
    p.targetDuration shouldBe 6
  }

  it should "leave version None when EXT-X-VERSION is missing" in {
    val raw =
      """#EXTM3U
        |#EXT-X-TARGETDURATION:6
        |#EXTINF:6.0,
        |a.ts
        |""".stripMargin
    M3U8.parse(raw).version shouldBe None
  }

  it should "leave version None when EXT-X-VERSION is malformed" in {
    val raw =
      """#EXTM3U
        |#EXT-X-VERSION:abc
        |#EXTINF:6.0,
        |a.ts
        |""".stripMargin
    M3U8.parse(raw).version shouldBe None
  }

  it should "parse version 4 with byte ranges" in {
    val raw =
      """#EXTM3U
        |#EXT-X-VERSION:4
        |#EXT-X-TARGETDURATION:10
        |#EXT-X-MEDIA-SEQUENCE:0
        |#EXTINF:10.0,
        |#EXT-X-BYTERANGE:1000
        |seg0.ts
        |#EXTINF:10.0,
        |#EXT-X-BYTERANGE:2000
        |seg1.ts
        |""".stripMargin
    val p = M3U8.parse(raw)
    val _ = p.version shouldBe Some(4)
    val _ = p.segments should have size 2
    val _ = p.segments(0).byteRange shouldBe Some((0L, 1000L))
    p.segments(1).byteRange shouldBe Some((1000L, 2000L))
  }

  it should "ignore unknown tags before, between, and after segments" in {
    val raw =
      """#EXTM3U
        |#EXT-X-UNKNOWN:foo
        |#EXT-X-TARGETDURATION:10
        |#EXTINF:10.0,
        |#EXT-X-DISCONTINUITY
        |seg0.ts
        |#EXT-X-KEY:METHOD=NONE
        |#EXTINF:10.0,
        |seg1.ts
        |#EXT-X-PROGRAM-DATE-TIME:2020-01-01T00:00:00Z
        |""".stripMargin
    val p = M3U8.parse(raw)
    val _ = p.targetDuration shouldBe 10
    val _ = p.segments should have size 2
    val _ = p.segments(0).uri shouldBe "seg0.ts"
    p.segments(1).uri shouldBe "seg1.ts"
  }

  it should "ignore blank lines and trim whitespace" in {
    val raw =
      """#EXTM3U
        |
        |#EXT-X-TARGETDURATION:10
        |
        |#EXTINF:10.0,
        |  seg0.ts
        |
        |#EXTINF:5.0,
        |seg1.ts
        |""".stripMargin
    val p = M3U8.parse(raw)
    val _ = p.segments should have size 2
    val _ = p.segments(0).uri shouldBe "seg0.ts"
    p.segments(1).uri shouldBe "seg1.ts"
  }

  it should "attach pending byte range to URI-only segment" in {
    val raw =
      """#EXTM3U
        |#EXT-X-TARGETDURATION:10
        |#EXT-X-BYTERANGE:500@1000
        |bare.ts
        |""".stripMargin
    val p = M3U8.parse(raw)
    val _ = p.segments should have size 1
    val _ = p.segments(0).uri shouldBe "bare.ts"
    val _ = p.segments(0).duration shouldBe 0.0
    p.segments(0).byteRange shouldBe Some((1000L, 500L))
  }

  it should "attach byte range before EXTINF to the next segment" in {
    val raw =
      """#EXTM3U
        |#EXT-X-TARGETDURATION:10
        |#EXT-X-BYTERANGE:1000
        |#EXTINF:10.0,
        |seg0.ts
        |""".stripMargin
    val p = M3U8.parse(raw)
    val _ = p.segments should have size 1
    val _ = p.segments(0).uri shouldBe "seg0.ts"
    p.segments(0).byteRange shouldBe Some((0L, 1000L))
  }

  it should "use defaults for malformed target duration and media sequence" in {
    val raw =
      """#EXTM3U
        |#EXT-X-TARGETDURATION:abc
        |#EXT-X-MEDIA-SEQUENCE:xyz
        |#EXTINF:10.0,
        |seg.ts
        |""".stripMargin
    val p = M3U8.parse(raw)
    val _ = p.targetDuration shouldBe 10
    val _ = p.mediaSequence shouldBe 0
    p.segments should have size 1
  }

  it should "use zero byte range for malformed BYTERANGE values" in {
    val raw =
      """#EXTM3U
        |#EXT-X-TARGETDURATION:10
        |#EXTINF:10.0,
        |#EXT-X-BYTERANGE:abc@def
        |seg.ts
        |""".stripMargin
    val p = M3U8.parse(raw)
    val _ = p.segments should have size 1
    p.segments(0).byteRange shouldBe Some((0L, 0L))
  }

  it should "return segment URI unchanged when base URL is invalid" in {
    M3U8.resolveSegmentUri("seg.ts", "not a valid uri") shouldBe "seg.ts"
  }
}

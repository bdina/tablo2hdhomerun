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
}

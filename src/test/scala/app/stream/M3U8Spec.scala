package app.stream

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

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
    p.targetDuration shouldBe 10
    p.mediaSequence shouldBe 0
    p.isEndList shouldBe true
    p.segments should have size 2
    p.segments(0).uri shouldBe "segment0.ts"
    p.segments(0).duration shouldBe 10.0
    p.segments(1).uri shouldBe "segment1.ts"
  }

  it should "use defaults when tags are missing" in {
    val raw =
      """#EXTM3U
        |#EXTINF:5.0,
        |a.ts
        |""".stripMargin
    val p = M3U8.parse(raw)
    p.targetDuration shouldBe 10
    p.mediaSequence shouldBe 0
    p.isEndList shouldBe false
    p.segments should have size 1
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
    p.segments should have size 1
    p.segments(0).title shouldBe Some("Segment title")
    p.segments(0).duration shouldBe 8.0
  }

  it should "resolve relative segment URI against base" in {
    val base = "http://example.com/path/playlist.m3u8"
    M3U8.resolveSegmentUri("seg.ts", base) shouldBe "http://example.com/path/seg.ts"
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
    M3U8.parse(withEnd).isEndList shouldBe true
    val withoutEnd =
      """#EXTM3U
        |#EXT-X-TARGETDURATION:10
        |#EXTINF:10.0,
        |x.ts
        |""".stripMargin
    M3U8.parse(withoutEnd).isEndList shouldBe false
  }
}

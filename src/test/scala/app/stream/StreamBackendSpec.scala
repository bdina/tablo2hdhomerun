package app.stream

import org.junit.runner.RunWith
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.junit.JUnitRunner

@RunWith(classOf[JUnitRunner])
class StreamBackendSpec extends AnyFlatSpec with Matchers {

  "StreamBackend.apply()" should "return a StreamBackend instance" in {
    StreamBackend().isInstanceOf[StreamBackend] shouldBe true
  }

  "StreamBackend.apply(backendName)" should "return FFmpegBackend for ffmpeg" in {
    val backend = StreamBackend("ffmpeg")
    val _ = backend.eq(FFmpegBackend) shouldBe true
    backend.name shouldBe "ffmpeg"
  }

  it should "return HlsBackend for hls" in {
    val backend = StreamBackend("hls")
    val _ = backend.eq(HlsBackend) shouldBe true
    backend.name shouldBe "hls"
  }

  it should "return FFmpegBackend for unknown name" in {
    StreamBackend("other").eq(FFmpegBackend) shouldBe true
  }
}

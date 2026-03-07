package app.stream

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class FFmpegBackendSpec extends AnyFlatSpec with Matchers {

  "FFmpegBackend" should "have name ffmpeg" in {
    FFmpegBackend.name shouldBe "ffmpeg"
  }

  it should "implement StreamBackend" in {
    FFmpegBackend.isInstanceOf[StreamBackend] shouldBe true
  }
}

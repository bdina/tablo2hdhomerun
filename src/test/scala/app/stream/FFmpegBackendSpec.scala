package app.stream

import org.junit.runner.RunWith
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.junit.JUnitRunner

@RunWith(classOf[JUnitRunner])
class FFmpegBackendSpec extends AnyFlatSpec with Matchers {

  "FFmpegBackend" should "have name ffmpeg" in {
    FFmpegBackend.name shouldBe "ffmpeg"
  }

  it should "implement StreamBackend" in {
    FFmpegBackend.isInstanceOf[StreamBackend] shouldBe true
  }
}

package app.stream

import org.junit.runner.RunWith
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.junit.JUnitRunner

import app.config.AppConfig
import app.AppContext
import app.tuner.TabloLegacy.Response.Discover

@RunWith(classOf[JUnitRunner])
class StreamBackendSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {

  override def beforeAll(): Unit = {
    val config = AppConfig.load(Map.empty).config
    val discover = Discover(
      friendlyName = "Tablo Legacy Gen Proxy",
      localIp = config.proxy.ip,
      protocol = config.tablo.protocol,
      port = config.proxy.port
    )
    AppContext.initialize(config, discover)
  }

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

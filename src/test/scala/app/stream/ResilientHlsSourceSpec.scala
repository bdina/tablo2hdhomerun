package app.stream

import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.stream.scaladsl.{Sink, Source}
import org.apache.pekko.stream.testkit.scaladsl.TestSink
import org.apache.pekko.util.ByteString
import org.junit.runner.RunWith
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatestplus.junit.JUnitRunner

import scala.concurrent.duration._
import java.util.concurrent.atomic.AtomicBoolean

import app.config.AppConfig
import app.AppContext
import app.tuner.TabloLegacy.Response.Discover

@RunWith(classOf[JUnitRunner])
class ResilientHlsSourceSpec extends ScalaTestWithActorTestKit with AnyWordSpecLike with Matchers {

  override def beforeAll(): Unit = {
    super.beforeAll()
    val config = AppConfig.load(Map.empty).config
    val discover = Discover(
      friendlyName = "Tablo Legacy Gen Proxy",
      localIp = config.proxy.ip,
      protocol = config.tablo.protocol,
      port = config.proxy.port
    )
    AppContext.initialize(config, discover)
  }

  "ResilientHlsSource" should {

    "construct valid MPEG-TS null packets" in {
      val packet = ResilientHlsSource.MPEGTS_NULL_PACKET
      val _ = packet.length shouldBe 188
      val _ = packet(0) shouldBe 0x47.toByte
      val _ = packet(1) shouldBe 0x1F.toByte
      val _ = packet(2) shouldBe 0xFF.toByte
      val _ = packet(3) shouldBe 0x10.toByte
      val _ = packet(4) shouldBe 0xFF.toByte
    }

    "pass through a successful stream without modification" in {
      val testData = ByteString("real-data")
      val factory = () => {
        Source.single(testData)
      }

      val wrappedSource = ResilientHlsSource(factory, "test-stream", recoveryTimeout = 30.seconds)
      val result = wrappedSource.runWith(Sink.head).futureValue

      result shouldBe testData
    }

    "emit null keepalive when factory always fails" in {
      val factory = () => Source.failed(new RuntimeException("always fails"))
      val wrappedSource = ResilientHlsSource(
        factory
        , "test-null-keepalive"
        , recoveryTimeout = 30.seconds
        , minBackoff = 1.second
        , maxBackoff = 1.second
      )
      // While the inner source is down, keepAlive should emit MPEG-TS null packets.
      implicit val classicSystemProvider: org.apache.pekko.actor.ClassicActorSystemProvider = system.classicSystem
      val probe = wrappedSource.runWith(TestSink[ByteString]())
      val _ = probe.ensureSubscription()
      val first = probe.requestNext(2.seconds)
      val _ = first shouldBe ResilientHlsSource.MPEGTS_NULL_PACKET
      probe.cancel()
    }

    "end stream after recovery timeout with no real data" in {
      val factory = () => Source.failed(new RuntimeException("always fails"))
      val wrappedSource = ResilientHlsSource(
        factory
        , "test-timeout"
        , recoveryTimeout = 300.millis
        , minBackoff = 50.millis
        , maxBackoff = 50.millis
      )
      val failed = wrappedSource.runWith(Sink.ignore).failed.futureValue
      failed shouldBe a[java.util.concurrent.TimeoutException]
    }

    "stay alive when real data arrives faster than recovery timeout" in {
      val factory = () => Source.tick(80.millis, 80.millis, ByteString("x"))
      val wrappedSource = ResilientHlsSource(
        factory
      , "test-timer-reset"
      , recoveryTimeout = 400.millis
      , minBackoff = 1.second
      , maxBackoff = 1.second
      )
      val result = wrappedSource.takeWithin(600.millis).runWith(Sink.seq).futureValue
      result.size should be > 3
    }

    "timeout when only null keepalive follows initial real data" in {
      val sentReal = new AtomicBoolean(false)
      val factory = () =>
        if (sentReal.compareAndSet(false, true))
          Source.single(ByteString("once"))
        else
          Source.never
      val wrappedSource = ResilientHlsSource(
        factory
      , "test-null-no-reset"
      , recoveryTimeout = 300.millis
      , minBackoff = 1.second
      , maxBackoff = 1.second
      )
      val failed = wrappedSource.runWith(Sink.ignore).failed.futureValue
      failed shouldBe a[java.util.concurrent.TimeoutException]
    }
  }
}

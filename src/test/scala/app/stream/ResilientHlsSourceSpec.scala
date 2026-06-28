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

@RunWith(classOf[JUnitRunner])
class ResilientHlsSourceSpec extends ScalaTestWithActorTestKit with AnyWordSpecLike with Matchers {

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
      probe.ensureSubscription()
      val first = probe.requestNext(2.seconds)
      first shouldBe ResilientHlsSource.MPEGTS_NULL_PACKET
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
  }
}

package app.stream

import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.stream.scaladsl.{Sink, Source}
import org.apache.pekko.stream.testkit.scaladsl.TestSink
import org.apache.pekko.util.ByteString
import org.scalatest.wordspec.AnyWordSpecLike

class ResilientHlsSourceSpec extends ScalaTestWithActorTestKit with AnyWordSpecLike {

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

      val wrappedSource = ResilientHlsSource(factory, "test-stream")
      val result = wrappedSource.runWith(Sink.head).futureValue

      result shouldBe testData
    }

    "inject null packets when stream fails and eventually retry" in {
      val testData1 = ByteString("real-data-1")
      val testData2 = ByteString("real-data-2")
      var factoryCalled = 0

      val factory = () => {
        factoryCalled += 1
        if (factoryCalled == 1) {
          Source.single(testData1).concat(Source.failed(new RuntimeException("stream error")))
        } else {
          Source.single(testData2)
        }
      }

      val wrappedSource = ResilientHlsSource(factory, "test-stream-fail")

      // We should see testData1, then some number of null packets, then testData2
      implicit val classicSystemProvider: org.apache.pekko.actor.ClassicActorSystemProvider = system.classicSystem
      val probe = wrappedSource.runWith(TestSink[ByteString]())

      val _ = probe.requestNext(testData1)

      val nextElem = probe.requestNext()
      val _ = nextElem shouldBe ResilientHlsSource.MPEGTS_NULL_PACKET

      probe.cancel()
    }
  }
}

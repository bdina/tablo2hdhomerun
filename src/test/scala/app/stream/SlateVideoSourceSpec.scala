package app.stream

import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.stream.scaladsl.Sink
import org.junit.runner.RunWith
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatestplus.junit.JUnitRunner

@RunWith(classOf[JUnitRunner])
class SlateVideoSourceSpec extends ScalaTestWithActorTestKit with AnyWordSpecLike with Matchers {

  "SlateVideoSource" should {

    "load a valid non-empty MPEG-TS slate resource" in {
      val bytes = SlateVideoSource.slateBytes
      val _ = bytes.nonEmpty shouldBe true
      val _ = (bytes.length % 188) shouldBe 0
      val _ = bytes(0) shouldBe 0x47.toByte
      SlateVideoSource.packetCount should be > 0
    }

    "create valid packet-aligned chunks" in {
      val chunks = SlateVideoSource.createChunks(24)
      val _ = chunks.nonEmpty shouldBe true
      for (chunk <- chunks) {
        val _ = (chunk.length % 188) shouldBe 0
        chunk(0) shouldBe 0x47.toByte
      }
    }

    "cycle endlessly via Loop" in {
      val loop = new SlateVideoSource.Loop(chunkPackets = 24)
      val totalChunks = SlateVideoSource.createChunks(24).size
      val _ = totalChunks should be > 0

      // Read more than one full cycle of chunks
      for (_ <- 0 until (totalChunks * 2 + 3)) {
        val chunk = loop.nextChunk()
        val _ = chunk.nonEmpty shouldBe true
        val _ = (chunk.length % 188) shouldBe 0
        chunk(0) shouldBe 0x47.toByte
      }
    }

    "emit streamed chunks from source" in {
      val chunks = SlateVideoSource.source(chunkPackets = 10)
        .take(3)
        .runWith(Sink.seq)
        .futureValue

      val _ = chunks.size shouldBe 3
      for (c <- chunks) {
        val _ = (c.length % 188) shouldBe 0
        c(0) shouldBe 0x47.toByte
      }
    }
  }
}

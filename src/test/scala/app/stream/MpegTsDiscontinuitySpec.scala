package app.stream

import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.stream.scaladsl.{Sink, Source}
import org.apache.pekko.util.ByteString
import org.junit.runner.RunWith
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatestplus.junit.JUnitRunner

@RunWith(classOf[JUnitRunner])
class MpegTsDiscontinuitySpec extends ScalaTestWithActorTestKit with AnyWordSpecLike with Matchers {

  private def packetWithAdaptation(cc: Int): ByteString = {
    val arr = Array.fill[Byte](188)(0x00.toByte)
    arr(0) = 0x47.toByte
    arr(1) = 0x00.toByte
    arr(2) = 0x10.toByte
    arr(3) = (0x30 | (cc & 0x0F)).toByte
    arr(4) = 0x01.toByte
    arr(5) = 0x00.toByte
    ByteString(arr)
  }

  private def packetPayloadOnly(cc: Int): ByteString = {
    val arr = Array.fill[Byte](188)(0x00.toByte)
    arr(0) = 0x47.toByte
    arr(1) = 0x00.toByte
    arr(2) = 0x10.toByte
    arr(3) = (0x10 | (cc & 0x0F)).toByte
    ByteString(arr)
  }

  "MpegTsDiscontinuity" should {
    "set discontinuity on first adaptation packets" in {
      val flow = MpegTsDiscontinuity.markFirstPackets(2)
      val input = packetWithAdaptation(0) ++ packetWithAdaptation(1) ++ packetPayloadOnly(2)
      val out = Source.single(input).via(flow).runWith(Sink.head).futureValue
      val _ = (out(5) & 0x80) should not be 0
      val secondPacketOffset = 188
      val _ = (out(secondPacketOffset + 5) & 0x80) should not be 0
      val thirdPacketOffset = 376
      val _ = (out(thirdPacketOffset + 5) & 0x80) shouldBe 0
    }

    "reassemble a packet split across chunks" in {
      val flow = MpegTsDiscontinuity.markFirstPackets(1)
      val packet = packetWithAdaptation(0)
      val first = packet.take(100)
      val second = packet.drop(100)
      val out = Source(List(first, second)).via(flow).runWith(Sink.fold(ByteString.empty)(_ ++ _)).futureValue
      val _ = out.length shouldBe 188
      (out(5) & 0x80) should not be 0
    }

    "pass through packets beyond count without marking" in {
      val flow = MpegTsDiscontinuity.markFirstPackets(1)
      val input = packetWithAdaptation(0) ++ packetWithAdaptation(1) ++ packetPayloadOnly(2)
      val out = Source.single(input).via(flow).runWith(Sink.head).futureValue
      val _ = (out(5) & 0x80) should not be 0
      val secondPacketOffset = 188
      (out(secondPacketOffset + 5) & 0x80) shouldBe 0
    }
  }
}

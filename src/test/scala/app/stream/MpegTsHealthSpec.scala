package app.stream

import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.stream.scaladsl.{Sink, Source}
import org.apache.pekko.util.ByteString
import org.junit.runner.RunWith
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatestplus.junit.JUnitRunner

@RunWith(classOf[JUnitRunner])
class MpegTsHealthSpec extends ScalaTestWithActorTestKit with AnyWordSpecLike with Matchers {

  private def packet(pidHi: Int, pidLo: Int, cc: Int, payload: Boolean): ByteString = {
    val arr = Array.fill[Byte](188)(0xFF.toByte)
    arr(0) = 0x47.toByte
    arr(1) = pidHi.toByte
    arr(2) = pidLo.toByte
    val afc = if (payload) { 0x10 } else { 0x00 }
    arr(3) = (afc | (cc & 0x0F)).toByte
    ByteString(arr)
  }

  private def nullPacket(cc: Int): ByteString = packet(0x1F, 0xFF, cc, payload = false)

  private def videoPacket(cc: Int): ByteString = packet(0x00, 0x10, cc, payload = true)

  "MpegTsHealth" should {
    "pass through clean stream" in {
      val settings = MpegTsHealth.Settings(windowSec = 1, ccMax = 100, syncMax = 100, nullRatioMax = 0.9, enforce = false)
      val stream = (0 until 10).map(i => videoPacket(i & 0x0F)).foldLeft(ByteString.empty)(_ ++ _)
      val out = Source.single(stream).via(MpegTsHealth.monitor(settings)).runWith(Sink.head).futureValue
      out shouldBe stream
    }

    "fail when enforce is true and null ratio is high" in {
      val settings = MpegTsHealth.Settings(windowSec = 1, ccMax = 100, syncMax = 100, nullRatioMax = 0.5, enforce = true)
      val stream = (0 until 10).map(_ => nullPacket(0)).foldLeft(ByteString.empty)(_ ++ _)
      val failed = Source
        .single(stream)
        .via(MpegTsHealth.monitor(settings))
        .runWith(Sink.ignore)
        .failed
        .futureValue
      failed shouldBe a[HlsBackend.HlsError.TsHealthDegraded]
    }

    "reassemble packets split across chunks" in {
      val settings = MpegTsHealth.Settings(windowSec = 1, ccMax = 100, syncMax = 100, nullRatioMax = 0.9, enforce = false)
      val p = videoPacket(0)
      val first = p.take(100)
      val second = p.drop(100)
      val out = Source(List(first, second))
        .via(MpegTsHealth.monitor(settings))
        .runWith(Sink.fold(ByteString.empty)(_ ++ _))
        .futureValue
      out shouldBe p
    }

    "fail when enforce is true and continuity-counter errors exceed threshold" in {
      val settings = MpegTsHealth.Settings(windowSec = 1, ccMax = 0, syncMax = 100, nullRatioMax = 0.9, enforce = true)
      val stream = videoPacket(0) ++ videoPacket(5)
      val failed = Source
        .single(stream)
        .via(MpegTsHealth.monitor(settings))
        .runWith(Sink.ignore)
        .failed
        .futureValue
      failed shouldBe a[HlsBackend.HlsError.TsHealthDegraded]
    }

    "fail when enforce is true and sync-byte loss exceeds threshold" in {
      val settings = MpegTsHealth.Settings(windowSec = 1, ccMax = 100, syncMax = 0, nullRatioMax = 0.9, enforce = true)
      val stream = ByteString(Array.fill[Byte](10)(0x00.toByte)) ++ videoPacket(0)
      val failed = Source
        .single(stream)
        .via(MpegTsHealth.monitor(settings))
        .runWith(Sink.ignore)
        .failed
        .futureValue
      failed shouldBe a[HlsBackend.HlsError.TsHealthDegraded]
    }
  }
}

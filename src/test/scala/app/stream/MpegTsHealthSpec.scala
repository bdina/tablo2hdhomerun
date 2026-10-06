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

  private def videoPacket(cc: Int, pusi: Boolean = false): ByteString =
    packet(if (pusi) 0x40 else 0x00, 0x10, cc, payload = true)

  private def teiPacket(cc: Int, pusi: Boolean = false): ByteString =
    packet(if (pusi) 0xC0 else 0x80, 0x10, cc, payload = true)

  "MpegTsHealth" should {
    "pass through clean stream" in {
      val settings = MpegTsHealth.Settings(windowSec = 1, ccMax = 100, syncMax = 100, nullRatioMax = 0.9, enforce = false)
      val stream = (0 until 10).map(i => videoPacket(i & 0x0F)).foldLeft(ByteString.empty)(_ ++ _)
      val out = Source.single(stream).via(MpegTsHealth.monitor(settings)).runWith(Sink.head).futureValue
      out shouldBe stream
    }

    "sanitize TEI-flagged packets into null packets" in {
      val settings = MpegTsHealth.Settings(windowSec = 1, ccMax = 100, syncMax = 100, nullRatioMax = 0.9, enforce = false, teiMax = 100)
      val damaged = teiPacket(3)
      val out = Source.single(damaged).via(MpegTsHealth.monitor(settings)).runWith(Sink.head).futureValue
      val _ = out.length shouldBe 188
      val _ = out(0) shouldBe 0x47.toByte
      val _ = out(1) shouldBe 0x1F.toByte // Null PID hi (TEI bit cleared!)
      val _ = out(2) shouldBe 0xFF.toByte // Null PID lo
      out(3) shouldBe 0x10.toByte // payload only, CC = 0
    }

    "fail when enforce is true and TEI errors exceed threshold" in {
      val settings = MpegTsHealth.Settings(windowSec = 1, ccMax = 100, syncMax = 100, nullRatioMax = 0.9, enforce = true, teiMax = 0)
      val stream = teiPacket(0)
      val failed = Source
        .single(stream)
        .via(MpegTsHealth.monitor(settings))
        .runWith(Sink.ignore)
        .failed
        .futureValue
      failed shouldBe a[HlsBackend.HlsError.TsHealthDegraded]
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

    "drop non-sync bytes from output stream when enforce is false" in {
      val settings = MpegTsHealth.Settings(windowSec = 1, ccMax = 100, syncMax = 100, nullRatioMax = 0.9, enforce = false)
      val junk = ByteString(Array.fill[Byte](10)(0x00.toByte))
      val p = videoPacket(0)
      val stream = junk ++ p
      val out = Source
        .single(stream)
        .via(MpegTsHealth.monitor(settings))
        .runWith(Sink.fold(ByteString.empty)(_ ++ _))
        .futureValue
      out shouldBe p
    }

    "not count continuity errors when discontinuity_indicator is signaled" in {
      val settings = MpegTsHealth.Settings(windowSec = 10, ccMax = 0, syncMax = 100, nullRatioMax = 0.9, enforce = true)
      val discPacket = {
        val arr = Array.fill[Byte](188)(0xFF.toByte)
        arr(0) = 0x47.toByte
        arr(1) = 0x00.toByte
        arr(2) = 0x10.toByte // PID 0x0010 (same as videoPacket)
        arr(3) = 0x20.toByte // adaptation field only, CC = 0
        arr(4) = 183.toByte  // adaptation field length
        arr(5) = 0x80.toByte // discontinuity_indicator = 1
        ByteString(arr)
      }

      // Packet 1: CC = 0
      // Packet 2: Discontinuity packet on same PID
      // Packet 3: CC = 7 (arbitrary jump after signaled discontinuity)
      // Packet 4: CC = 8 (consecutive to 7)
      val stream = videoPacket(0) ++ discPacket ++ videoPacket(7) ++ videoPacket(8)
      val out = Source
        .single(stream)
        .via(MpegTsHealth.monitor(settings))
        .runWith(Sink.fold(ByteString.empty)(_ ++ _))
        .futureValue

      out.length shouldBe (188 * 4)
    }

    "fail inline during onPush when errors exceed threshold without waiting for timer" in {
      // Set windowSec to 3600 (1 hour) to prove degradation occurs inline on packet push
      val settings = MpegTsHealth.Settings(windowSec = 3600, ccMax = 0, syncMax = 100, nullRatioMax = 0.9, enforce = true)
      val stream = videoPacket(0) ++ videoPacket(5)
      val failed = Source
        .single(stream)
        .via(MpegTsHealth.monitor(settings))
        .runWith(Sink.ignore)
        .failed
        .futureValue
      failed shouldBe a[HlsBackend.HlsError.TsHealthDegraded]
    }

    "inject discontinuity packet before packet with continuity counter jump when enforce is false" in {
      val settings = MpegTsHealth.Settings(windowSec = 10, ccMax = 100, syncMax = 100, nullRatioMax = 0.9, enforce = false)
      val stream = videoPacket(0) ++ videoPacket(5, pusi = true) ++ videoPacket(6)
      val out = Source
        .single(stream)
        .via(MpegTsHealth.monitor(settings))
        .runWith(Sink.fold(ByteString.empty)(_ ++ _))
        .futureValue

      val _ = out.length shouldBe (188 * 4)
      val _ = out.take(188) shouldBe videoPacket(0)
      val _ = out.slice(188, 188 * 2) shouldBe MpegTsSync.discontinuityPacket(0x0010, cc = 0)
      val _ = out.slice(188 * 2, 188 * 3) shouldBe videoPacket(5, pusi = true)
      val _ = out.slice(188 * 3, 188 * 4) shouldBe videoPacket(6)
    }

    "nullify mid-frame packets after continuity counter jump until next pusi" in {
      val settings = MpegTsHealth.Settings(windowSec = 10, ccMax = 100, syncMax = 100, nullRatioMax = 0.9, enforce = false)
      val stream = videoPacket(0) ++ videoPacket(5, pusi = false) ++ videoPacket(6, pusi = false) ++ videoPacket(7, pusi = true) ++ videoPacket(8)
      val out = Source
        .single(stream)
        .via(MpegTsHealth.monitor(settings))
        .runWith(Sink.fold(ByteString.empty)(_ ++ _))
        .futureValue

      val _ = out.length shouldBe (188 * 6)
      val _ = out.take(188) shouldBe videoPacket(0)
      val _ = out.slice(188, 188 * 2) shouldBe ByteString(MpegTsHealth.NullPacketArray)
      val _ = out.slice(188 * 2, 188 * 3) shouldBe ByteString(MpegTsHealth.NullPacketArray)
      val _ = out.slice(188 * 3, 188 * 4) shouldBe MpegTsSync.discontinuityPacket(0x0010, cc = 6)
      val _ = out.slice(188 * 4, 188 * 5) shouldBe videoPacket(7, pusi = true)
      val _ = out.slice(188 * 5, 188 * 6) shouldBe videoPacket(8)
    }

    "inject discontinuity packet on next packet after TEI corruption on same PID when pusi is true" in {
      val settings = MpegTsHealth.Settings(windowSec = 10, ccMax = 100, syncMax = 100, nullRatioMax = 0.9, enforce = false)
      val stream = videoPacket(0) ++ teiPacket(1) ++ videoPacket(2, pusi = true)
      val out = Source
        .single(stream)
        .via(MpegTsHealth.monitor(settings))
        .runWith(Sink.fold(ByteString.empty)(_ ++ _))
        .futureValue

      val _ = out.length shouldBe (188 * 4)
      val _ = out.take(188) shouldBe videoPacket(0)
      val _ = out.slice(188, 188 * 2) shouldBe ByteString(MpegTsHealth.NullPacketArray)
      val _ = out.slice(188 * 2, 188 * 3) shouldBe MpegTsSync.discontinuityPacket(0x0010, cc = 0)
      val _ = out.slice(188 * 3, 188 * 4) shouldBe videoPacket(2, pusi = true)
    }

    "nullify mid-frame packets after TEI corruption until next pusi" in {
      val settings = MpegTsHealth.Settings(windowSec = 10, ccMax = 100, syncMax = 100, nullRatioMax = 0.9, enforce = false)
      val stream = videoPacket(0) ++ teiPacket(1) ++ videoPacket(2, pusi = false) ++ videoPacket(3, pusi = true) ++ videoPacket(4)
      val out = Source
        .single(stream)
        .via(MpegTsHealth.monitor(settings))
        .runWith(Sink.fold(ByteString.empty)(_ ++ _))
        .futureValue

      val _ = out.length shouldBe (188 * 6)
      val _ = out.take(188) shouldBe videoPacket(0)
      val _ = out.slice(188, 188 * 2) shouldBe ByteString(MpegTsHealth.NullPacketArray)
      val _ = out.slice(188 * 2, 188 * 3) shouldBe ByteString(MpegTsHealth.NullPacketArray)
      val _ = out.slice(188 * 3, 188 * 4) shouldBe MpegTsSync.discontinuityPacket(0x0010, cc = 2)
      val _ = out.slice(188 * 4, 188 * 5) shouldBe videoPacket(3, pusi = true)
      val _ = out.slice(188 * 5, 188 * 6) shouldBe videoPacket(4)
    }

    "inject discontinuity packet for active PIDs and general discontinuity upon sync loss recovery" in {
      val settings = MpegTsHealth.Settings(windowSec = 10, ccMax = 100, syncMax = 100, nullRatioMax = 0.9, enforce = false)
      val junk = ByteString(Array.fill[Byte](10)(0x00.toByte))
      val stream = videoPacket(0) ++ junk ++ videoPacket(1, pusi = true)
      val out = Source
        .single(stream)
        .via(MpegTsHealth.monitor(settings))
        .runWith(Sink.fold(ByteString.empty)(_ ++ _))
        .futureValue

      val _ = out.length shouldBe (188 * 4)
      val _ = out.take(188) shouldBe videoPacket(0)
      val _ = out.slice(188, 188 * 2) shouldBe MpegTsSync.discontinuityPacket(0x0010, cc = 0)
      val _ = out.slice(188 * 2, 188 * 3) shouldBe MpegTsSync.MPEGTS_DISCONTINUITY_PACKET
      val _ = out.slice(188 * 3, 188 * 4) shouldBe videoPacket(1, pusi = true)
    }

    "clear active PID continuity on NullPid discontinuity packet" in {
      val settings = MpegTsHealth.Settings(windowSec = 10, ccMax = 0, syncMax = 100, nullRatioMax = 0.9, enforce = true)
      val stream = videoPacket(0) ++ MpegTsSync.MPEGTS_DISCONTINUITY_PACKET ++ videoPacket(7, pusi = true)
      val out = Source
        .single(stream)
        .via(MpegTsHealth.monitor(settings))
        .runWith(Sink.fold(ByteString.empty)(_ ++ _))
        .futureValue

      val _ = out.length shouldBe (188 * 4)
      val _ = out.take(188) shouldBe videoPacket(0)
      val _ = out.slice(188, 188 * 2) shouldBe MpegTsSync.discontinuityPacket(0x0010, cc = 0)
      val _ = out.slice(188 * 2, 188 * 3) shouldBe MpegTsSync.MPEGTS_DISCONTINUITY_PACKET
      val _ = out.slice(188 * 3, 188 * 4) shouldBe videoPacket(7, pusi = true)
    }
  }
}

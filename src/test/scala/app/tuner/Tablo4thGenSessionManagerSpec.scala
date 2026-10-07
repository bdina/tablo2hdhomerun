package app.tuner

import org.apache.pekko.NotUsed
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.util.ByteString
import org.junit.runner.RunWith
import org.scalatest.concurrent.Eventually
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatestplus.junit.JUnitRunner

import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}

import scala.concurrent.duration._

@RunWith(classOf[JUnitRunner])
class Tablo4thGenSessionManagerSpec extends ScalaTestWithActorTestKit with AnyWordSpecLike with Matchers with Eventually {

  import Tablo4thGen.Channel.SessionManager
  import SessionManager.{Command, Request, Response, RejectReason, TabloSessionMeta}

  private def meta(token: String = "tok-1"): TabloSessionMeta =
    TabloSessionMeta(token, None, Some(165), "http://example.com/pl.m3u8")

  private def hubSource: Source[ByteString, NotUsed] =
    Source.repeat(ByteString(1, 2, 3)).take(8)

  private def spawnManager(
    startRunner: (String, ActorRef[Request]) => Unit
  , totalTuners: Int = 4
  , idleGrace: FiniteDuration = 200.millis
  ): ActorRef[Request] =
    testKit.spawn(SessionManager(startRunner, totalTuners, idleGrace))

  "SessionManager" should {

    "call startRunner once for Opening and enqueue a second Acquire" in {
      val runnerCalls = new AtomicInteger(0)
      val selfRef = new AtomicReference[ActorRef[Request]](null)
      val mgr = spawnManager { (channelId, self) =>
        val _ = runnerCalls.incrementAndGet()
        selfRef.set(self)
        val _ = channelId shouldBe "ch-1"
        ()
      }
      val probeA = testKit.createTestProbe[Response.Acquire]()
      val probeB = testKit.createTestProbe[Response.Acquire]()

      mgr ! Request.Acquire("ch-1", "client-a", probeA.ref)
      mgr ! Request.Acquire("ch-1", "client-b", probeB.ref)

      val _ = eventually(timeout(3.seconds), interval(50.millis)) {
        val _ = runnerCalls.get() shouldBe 1
        selfRef.get() should not be null
      }

      selfRef.get() ! Command.CheckIn("ch-1", meta(), hubSource, () => ())
      val _ = probeA.expectMessageType[Response.Attached]
      val _ = probeB.expectMessageType[Response.Attached]
    }

    "reject waiters on AcquireFailed and clear the entry" in {
      val selfRef = new AtomicReference[ActorRef[Request]](null)
      val mgr = spawnManager { (_, self) => selfRef.set(self) }
      val probe = testKit.createTestProbe[Response.Acquire]()

      mgr ! Request.Acquire("ch-fail", "client-a", probe.ref)
      val _ = eventually(timeout(3.seconds), interval(50.millis)) {
        selfRef.get() should not be null
      }

      selfRef.get() ! Command.AcquireFailed("ch-fail", new RuntimeException("watch failed"))
      val rejected = probe.expectMessageType[Response.Rejected]
      val _ = rejected.reason match {
        case RejectReason.Failed(ex) =>
          ex.getMessage shouldBe "watch failed"
        case other => fail(s"unexpected $other")
      }

      val probe2 = testKit.createTestProbe[Response.Acquire]()
      val runnerCalls = new AtomicInteger(0)
      val mgr2 = spawnManager { (_, self) =>
        val _ = runnerCalls.incrementAndGet()
        self ! Command.CheckIn("ch-fail", meta(), hubSource, () => ())
      }
      mgr2 ! Request.Acquire("ch-fail", "client-b", probe2.ref)
      val _ = probe2.expectMessageType[Response.Attached]
      runnerCalls.get() shouldBe 1
    }

    "attach a second Live client and keep session until last Release" in {
      val selfRef = new AtomicReference[ActorRef[Request]](null)
      val teardownCount = new AtomicInteger(0)
      val mgr = spawnManager({ (_, self) => selfRef.set(self) }, idleGrace = 5.seconds)
      val probeA = testKit.createTestProbe[Response.Acquire]()
      val probeB = testKit.createTestProbe[Response.Acquire]()

      mgr ! Request.Acquire("ch-live", "client-a", probeA.ref)
      val _ = eventually(timeout(3.seconds), interval(50.millis)) {
        selfRef.get() should not be null
      }
      selfRef.get() ! Command.CheckIn("ch-live", meta(), hubSource, () => { val _ = teardownCount.incrementAndGet() })
      val _ = probeA.expectMessageType[Response.Attached]

      mgr ! Request.Acquire("ch-live", "client-b", probeB.ref)
      val _ = probeB.expectMessageType[Response.Attached]

      mgr ! Request.Release("ch-live", "client-a")
      Thread.sleep(100)
      val _ = teardownCount.get() shouldBe 0

      mgr ! Request.Release("ch-live", "client-b")
      Thread.sleep(100)
      teardownCount.get() shouldBe 0
    }

    "cancel IdleGrace on Acquire without calling startRunner again" in {
      val runnerCalls = new AtomicInteger(0)
      val selfRef = new AtomicReference[ActorRef[Request]](null)
      val teardownCount = new AtomicInteger(0)
      val mgr = spawnManager(
        startRunner = { (_, self) =>
          val _ = runnerCalls.incrementAndGet()
          selfRef.set(self)
        }
      , idleGrace = 2.seconds
      )
      val probeA = testKit.createTestProbe[Response.Acquire]()
      val probeB = testKit.createTestProbe[Response.Acquire]()

      mgr ! Request.Acquire("ch-grace", "client-a", probeA.ref)
      val _ = eventually(timeout(3.seconds), interval(50.millis)) {
        selfRef.get() should not be null
      }
      selfRef.get() ! Command.CheckIn("ch-grace", meta(), hubSource, () => { val _ = teardownCount.incrementAndGet() })
      val _ = probeA.expectMessageType[Response.Attached]

      mgr ! Request.Release("ch-grace", "client-a")
      mgr ! Request.Acquire("ch-grace", "client-b", probeB.ref)
      val _ = probeB.expectMessageType[Response.Attached]
      val _ = runnerCalls.get() shouldBe 1
      teardownCount.get() shouldBe 0
    }

    "invoke teardown once after IdleGrace expires" in {
      val selfRef = new AtomicReference[ActorRef[Request]](null)
      val teardownCount = new AtomicInteger(0)
      val mgr = spawnManager(
        startRunner = { (_, self) => selfRef.set(self) }
      , idleGrace = 150.millis
      )
      val probe = testKit.createTestProbe[Response.Acquire]()

      mgr ! Request.Acquire("ch-expire", "client-a", probe.ref)
      val _ = eventually(timeout(3.seconds), interval(50.millis)) {
        selfRef.get() should not be null
      }
      selfRef.get() ! Command.CheckIn("ch-expire", meta(), hubSource, () => { val _ = teardownCount.incrementAndGet() })
      val _ = probe.expectMessageType[Response.Attached]

      mgr ! Request.Release("ch-expire", "client-a")
      val _ = eventually(timeout(3.seconds), interval(50.millis)) {
        teardownCount.get() shouldBe 1
      }

      val runnerCalls = new AtomicInteger(0)
      val mgr2 = spawnManager(
        startRunner = { (_, self) =>
          val _ = runnerCalls.incrementAndGet()
          self ! Command.CheckIn("ch-expire", meta("tok-2"), hubSource, () => ())
        }
      , idleGrace = 150.millis
      )
      val probe2 = testKit.createTestProbe[Response.Acquire]()
      mgr2 ! Request.Acquire("ch-expire", "client-b", probe2.ref)
      val _ = probe2.expectMessageType[Response.Attached]
      runnerCalls.get() shouldBe 1
    }

    "reject a new channel when at tuner capacity but allow attach on existing channel" in {
      val selfRef = new AtomicReference[ActorRef[Request]](null)
      val mgr = spawnManager(
        startRunner = { (_, self) => selfRef.set(self) }
      , totalTuners = 1
      , idleGrace = 5.seconds
      )
      val probeA = testKit.createTestProbe[Response.Acquire]()
      val probeB = testKit.createTestProbe[Response.Acquire]()
      val probeC = testKit.createTestProbe[Response.Acquire]()

      mgr ! Request.Acquire("ch-cap", "client-a", probeA.ref)
      val _ = eventually(timeout(3.seconds), interval(50.millis)) {
        selfRef.get() should not be null
      }
      selfRef.get() ! Command.CheckIn("ch-cap", meta(), hubSource, () => ())
      val _ = probeA.expectMessageType[Response.Attached]

      mgr ! Request.Acquire("ch-other", "client-b", probeB.ref)
      val _ = probeB.expectMessage(Response.Rejected(RejectReason.NoTuners))

      mgr ! Request.Acquire("ch-cap", "client-c", probeC.ref)
      val _ = probeC.expectMessageType[Response.Attached]
    }

    "teardown on unexpected CheckIn when not Opening" in {
      val teardownCount = new AtomicInteger(0)
      val mgr = spawnManager(startRunner = (_, _) => ())
      mgr ! Command.CheckIn("ch-unexpected", meta(), hubSource, () => { val _ = teardownCount.incrementAndGet() })
      eventually(timeout(3.seconds), interval(50.millis)) {
        teardownCount.get() shouldBe 1
      }
    }

    "prime attached clients with cached headers when available" in {
      import app.stream.MpegTsSync
      val selfRef = new AtomicReference[ActorRef[Request]](null)
      val mgr = spawnManager { (_, self) => selfRef.set(self) }
      val probe = testKit.createTestProbe[Response.Acquire]()

      val patPacket = ByteString(Array.fill[Byte](188)(0x11.toByte))
      val pmtPacket = ByteString(Array.fill[Byte](188)(0x22.toByte))
      val headers = MpegTsSync.CachedHeaders(Some(patPacket), Some(pmtPacket))
      val cachedHeadersRef = new AtomicReference(headers)

      mgr ! Request.Acquire("ch-prime", "client-a", probe.ref)
      val _ = eventually(timeout(3.seconds), interval(50.millis)) {
        selfRef.get() should not be null
      }

      val payload = ByteString("live-data")
      val source = Source.single(payload)

      selfRef.get() ! Command.CheckIn("ch-prime", meta(), source, () => (), cachedHeadersRef)
      val attached = probe.expectMessageType[Response.Attached]
      val outBytes = attached.source.runWith(org.apache.pekko.stream.scaladsl.Sink.fold(ByteString.empty)(_ ++ _)).futureValue

      val _ = outBytes.take(188) shouldBe MpegTsSync.MPEGTS_DISCONTINUITY_PACKET
      val _ = outBytes.slice(188, 376) shouldBe patPacket
      val _ = outBytes.slice(376, 564) shouldBe pmtPacket
      outBytes.drop(564) shouldBe payload
    }

    "expose defaultIdleGrace as 75 seconds by default" in {
      val _ = SessionManager.defaultIdleGrace shouldBe 75.seconds
      SessionManager.IdleGrace shouldBe 75.seconds
    }

    "persist channel headers in channelHeaderCache across session lifecycles" in {
      import app.stream.MpegTsSync
      SessionManager.clearCachedHeaders()
      val selfRef = new AtomicReference[ActorRef[Request]](null)
      val teardownCount = new AtomicInteger(0)
      val mgr = spawnManager(
        startRunner = { (_, self) => selfRef.set(self) }
      , totalTuners = 2
      , idleGrace = 100.millis
      )
      val probe = testKit.createTestProbe[Response.Acquire]()

      val patPacket = ByteString(Array.fill[Byte](188)(0x33.toByte))
      val pmtPacket = ByteString(Array.fill[Byte](188)(0x44.toByte))
      val headers = MpegTsSync.CachedHeaders(Some(patPacket), Some(pmtPacket))
      val cachedHeadersRef = new AtomicReference(headers)

      mgr ! Request.Acquire("ch-persist", "client-1", probe.ref)
      val _ = eventually(timeout(3.seconds), interval(50.millis)) {
        selfRef.get() should not be null
      }

      val payload = ByteString("stream-data")
      val source = Source.single(payload)

      selfRef.get() ! Command.CheckIn("ch-persist", meta(), source, () => { val _ = teardownCount.incrementAndGet() }, cachedHeadersRef)
      val _ = probe.expectMessageType[Response.Attached]

      // Headers should now be in the persistent cache
      val cached = SessionManager.getCachedHeaders("ch-persist")
      val _ = cached.pat shouldBe Some(patPacket)
      val _ = cached.pmt shouldBe Some(pmtPacket)

      // Release client to trigger idle-grace and eventual teardown
      mgr ! Request.Release("ch-persist", "client-1")
      val _ = eventually(timeout(3.seconds), interval(50.millis)) {
        teardownCount.get() shouldBe 1
      }

      // Headers should remain in the persistent cache even after session removal
      val afterTeardown = SessionManager.getCachedHeaders("ch-persist")
      val _ = afterTeardown.pat shouldBe Some(patPacket)
      afterTeardown.pmt shouldBe Some(pmtPacket)
    }

    "populate cachedHeadersRef in attachClient from channelHeaderCache when ref is empty" in {
      import app.stream.MpegTsSync
      SessionManager.clearCachedHeaders()
      val patPacket = ByteString(Array.fill[Byte](188)(0x55.toByte))
      val pmtPacket = ByteString(Array.fill[Byte](188)(0x66.toByte))
      val headers = MpegTsSync.CachedHeaders(Some(patPacket), Some(pmtPacket))
      SessionManager.setCachedHeaders("ch-fallback", headers)

      val selfRef = new AtomicReference[ActorRef[Request]](null)
      val mgr = spawnManager { (_, self) => selfRef.set(self) }
      val probe = testKit.createTestProbe[Response.Acquire]()

      mgr ! Request.Acquire("ch-fallback", "client-fb", probe.ref)
      val _ = eventually(timeout(3.seconds), interval(50.millis)) {
        selfRef.get() should not be null
      }

      val emptyRef = new AtomicReference(MpegTsSync.CachedHeaders())
      val payload = ByteString("stream-fallback")
      val source = Source.single(payload)

      selfRef.get() ! Command.CheckIn("ch-fallback", meta(), source, () => (), emptyRef)
      val attached = probe.expectMessageType[Response.Attached]
      val outBytes = attached.source.runWith(org.apache.pekko.stream.scaladsl.Sink.fold(ByteString.empty)(_ ++ _)).futureValue

      // Out bytes should have been primed with headers from channelHeaderCache
      val _ = outBytes.take(188) shouldBe MpegTsSync.MPEGTS_DISCONTINUITY_PACKET
      val _ = outBytes.slice(188, 376) shouldBe patPacket
      val _ = outBytes.slice(376, 564) shouldBe pmtPacket
      val _ = outBytes.drop(564) shouldBe payload

      // emptyRef should now have been updated with fallback headers
      val _ = emptyRef.get().pat shouldBe Some(patPacket)
      emptyRef.get().pmt shouldBe Some(pmtPacket)
    }

    "drain hubSource during IdleGrace to avoid backpressuring upstream" in {
      val selfRef = new AtomicReference[ActorRef[Request]](null)
      val mgr = spawnManager(
        startRunner = { (_, self) => selfRef.set(self) }
      , idleGrace = 2.seconds
      )
      val probeA = testKit.createTestProbe[Response.Acquire]()
      val probeB = testKit.createTestProbe[Response.Acquire]()

      val drainedCounter = new AtomicInteger(0)
      val hub = Source.repeat(ByteString(0x47, 0x1F, 0xFF, 0x10))
        .map { bs =>
          val _ = drainedCounter.incrementAndGet()
          bs
        }

      mgr ! Request.Acquire("ch-drain-test", "client-1", probeA.ref)
      val _ = eventually(timeout(3.seconds), interval(50.millis)) {
        selfRef.get() should not be null
      }
      selfRef.get() ! Command.CheckIn("ch-drain-test", meta(), hub, () => ())
      val attachedA = probeA.expectMessageType[Response.Attached]

      // Client A takes 2 elements then finishes
      val clientAFut = attachedA.source.take(2).runWith(org.apache.pekko.stream.scaladsl.Sink.seq)
      val _ = clientAFut.futureValue.length shouldBe 2

      // Release Client A: manager transitions to IdleGrace and attaches drain sink
      mgr ! Request.Release("ch-drain-test", "client-1")

      // In IdleGrace, drain sink continues consuming elements so upstream doesn't stall
      val _ = eventually(timeout(3.seconds), interval(50.millis)) {
        drainedCounter.get() should be > 2
      }

      // Client B acquires during IdleGrace: cancels timer and shuts down drain switch
      mgr ! Request.Acquire("ch-drain-test", "client-2", probeB.ref)
      val attachedB = probeB.expectMessageType[Response.Attached]
      val clientBFut = attachedB.source.take(2).runWith(org.apache.pekko.stream.scaladsl.Sink.seq)
      val _ = clientBFut.futureValue.length shouldBe 2
    }

    "tear down and remove a Live session on UpstreamEnded so the next Acquire starts a new runner" in {
      val runnerCalls = new AtomicInteger(0)
      val teardownCalls = new AtomicInteger(0)
      val selfRef = new AtomicReference[ActorRef[Request]](null)
      val mgr = spawnManager({ (_, self) =>
        val _ = runnerCalls.incrementAndGet()
        selfRef.set(self)
      }, idleGrace = 5.seconds)
      val probe = testKit.createTestProbe[Response.Acquire]()

      mgr ! Request.Acquire("ch-up-ended", "client-1", probe.ref)
      val _ = eventually(timeout(3.seconds), interval(50.millis)) {
        selfRef.get() should not be null
      }
      selfRef.get() ! Command.CheckIn("ch-up-ended", meta().copy(leaseId = "L1"), hubSource, () => {
        val _ = teardownCalls.incrementAndGet()
      })
      val _ = probe.expectMessageType[Response.Attached]

      // UpstreamEnded for lease L1 triggers immediate teardown and removal
      mgr ! Command.UpstreamEnded("ch-up-ended", "L1")
      val _ = eventually(timeout(3.seconds), interval(50.millis)) {
        teardownCalls.get() shouldBe 1
      }

      // Next acquire must call startRunner again
      val probe2 = testKit.createTestProbe[Response.Acquire]()
      mgr ! Request.Acquire("ch-up-ended", "client-2", probe2.ref)
      eventually(timeout(3.seconds), interval(50.millis)) {
        runnerCalls.get() shouldBe 2
      }
    }

    "ignore UpstreamEnded with a stale leaseId" in {
      val runnerCalls = new AtomicInteger(0)
      val teardownCalls = new AtomicInteger(0)
      val selfRef = new AtomicReference[ActorRef[Request]](null)
      val mgr = spawnManager({ (_, self) =>
        val _ = runnerCalls.incrementAndGet()
        selfRef.set(self)
      }, idleGrace = 5.seconds)
      val probe = testKit.createTestProbe[Response.Acquire]()

      mgr ! Request.Acquire("ch-stale-lease", "client-1", probe.ref)
      val _ = eventually(timeout(3.seconds), interval(50.millis)) {
        selfRef.get() should not be null
      }
      selfRef.get() ! Command.CheckIn("ch-stale-lease", meta().copy(leaseId = "L2"), hubSource, () => {
        val _ = teardownCalls.incrementAndGet()
      })
      val _ = probe.expectMessageType[Response.Attached]

      // UpstreamEnded with stale lease L1 should be ignored
      mgr ! Command.UpstreamEnded("ch-stale-lease", "L1")
      Thread.sleep(100)
      val _ = teardownCalls.get() shouldBe 0

      // Second acquire attaches to existing Live session without calling startRunner
      val probe2 = testKit.createTestProbe[Response.Acquire]()
      mgr ! Request.Acquire("ch-stale-lease", "client-2", probe2.ref)
      val _ = probe2.expectMessageType[Response.Attached]
      runnerCalls.get() shouldBe 1
    }

    "tear down an IdleGrace session on UpstreamEnded without waiting for grace expiry" in {
      val teardownCalls = new AtomicInteger(0)
      val selfRef = new AtomicReference[ActorRef[Request]](null)
      val mgr = spawnManager({ (_, self) =>
        selfRef.set(self)
      }, idleGrace = 60.seconds)
      val probe = testKit.createTestProbe[Response.Acquire]()

      mgr ! Request.Acquire("ch-idle-ended", "client-1", probe.ref)
      val _ = eventually(timeout(3.seconds), interval(50.millis)) {
        selfRef.get() should not be null
      }
      selfRef.get() ! Command.CheckIn("ch-idle-ended", meta().copy(leaseId = "L1"), hubSource, () => {
        val _ = teardownCalls.incrementAndGet()
      })
      val _ = probe.expectMessageType[Response.Attached]

      // Client releases -> enters 60s IdleGrace
      mgr ! Request.Release("ch-idle-ended", "client-1")
      Thread.sleep(100)
      val _ = teardownCalls.get() shouldBe 0

      // UpstreamEnded arrives during IdleGrace -> teardown immediately
      mgr ! Command.UpstreamEnded("ch-idle-ended", "L1")
      val _ = eventually(timeout(3.seconds), interval(50.millis)) {
        teardownCalls.get() shouldBe 1
      }

      // Later GraceExpired should not invoke teardown again
      mgr ! Command.GraceExpired("ch-idle-ended")
      Thread.sleep(100)
      teardownCalls.get() shouldBe 1
    }
  }
}

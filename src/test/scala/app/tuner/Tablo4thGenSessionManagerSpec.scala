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
        runnerCalls.incrementAndGet()
        selfRef.set(self)
        channelId shouldBe "ch-1"
      }
      val probeA = testKit.createTestProbe[Response.Acquire]()
      val probeB = testKit.createTestProbe[Response.Acquire]()

      mgr ! Request.Acquire("ch-1", "client-a", probeA.ref)
      mgr ! Request.Acquire("ch-1", "client-b", probeB.ref)

      eventually(timeout(3.seconds), interval(50.millis)) {
        runnerCalls.get() shouldBe 1
        selfRef.get() should not be null
      }

      selfRef.get() ! Command.CheckIn("ch-1", meta(), hubSource, () => ())
      probeA.expectMessageType[Response.Attached]
      probeB.expectMessageType[Response.Attached]
    }

    "reject waiters on AcquireFailed and clear the entry" in {
      val selfRef = new AtomicReference[ActorRef[Request]](null)
      val mgr = spawnManager { (_, self) => selfRef.set(self) }
      val probe = testKit.createTestProbe[Response.Acquire]()

      mgr ! Request.Acquire("ch-fail", "client-a", probe.ref)
      eventually(timeout(3.seconds), interval(50.millis)) {
        selfRef.get() should not be null
      }

      selfRef.get() ! Command.AcquireFailed("ch-fail", new RuntimeException("watch failed"))
      val rejected = probe.expectMessageType[Response.Rejected]
      rejected.reason match {
        case RejectReason.Failed(ex) =>
          ex.getMessage shouldBe "watch failed"
        case other => fail(s"unexpected $other")
      }

      val probe2 = testKit.createTestProbe[Response.Acquire]()
      val runnerCalls = new AtomicInteger(0)
      val mgr2 = spawnManager { (_, self) =>
        runnerCalls.incrementAndGet()
        self ! Command.CheckIn("ch-fail", meta(), hubSource, () => ())
      }
      mgr2 ! Request.Acquire("ch-fail", "client-b", probe2.ref)
      probe2.expectMessageType[Response.Attached]
      runnerCalls.get() shouldBe 1
    }

    "attach a second Live client and keep session until last Release" in {
      val selfRef = new AtomicReference[ActorRef[Request]](null)
      val teardownCount = new AtomicInteger(0)
      val mgr = spawnManager({ (_, self) => selfRef.set(self) }, idleGrace = 5.seconds)
      val probeA = testKit.createTestProbe[Response.Acquire]()
      val probeB = testKit.createTestProbe[Response.Acquire]()

      mgr ! Request.Acquire("ch-live", "client-a", probeA.ref)
      eventually(timeout(3.seconds), interval(50.millis)) {
        selfRef.get() should not be null
      }
      selfRef.get() ! Command.CheckIn("ch-live", meta(), hubSource, () => teardownCount.incrementAndGet())
      probeA.expectMessageType[Response.Attached]

      mgr ! Request.Acquire("ch-live", "client-b", probeB.ref)
      probeB.expectMessageType[Response.Attached]

      mgr ! Request.Release("ch-live", "client-a")
      Thread.sleep(100)
      teardownCount.get() shouldBe 0

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
          runnerCalls.incrementAndGet()
          selfRef.set(self)
        }
      , idleGrace = 2.seconds
      )
      val probeA = testKit.createTestProbe[Response.Acquire]()
      val probeB = testKit.createTestProbe[Response.Acquire]()

      mgr ! Request.Acquire("ch-grace", "client-a", probeA.ref)
      eventually(timeout(3.seconds), interval(50.millis)) {
        selfRef.get() should not be null
      }
      selfRef.get() ! Command.CheckIn("ch-grace", meta(), hubSource, () => teardownCount.incrementAndGet())
      probeA.expectMessageType[Response.Attached]

      mgr ! Request.Release("ch-grace", "client-a")
      mgr ! Request.Acquire("ch-grace", "client-b", probeB.ref)
      probeB.expectMessageType[Response.Attached]
      runnerCalls.get() shouldBe 1
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
      eventually(timeout(3.seconds), interval(50.millis)) {
        selfRef.get() should not be null
      }
      selfRef.get() ! Command.CheckIn("ch-expire", meta(), hubSource, () => teardownCount.incrementAndGet())
      probe.expectMessageType[Response.Attached]

      mgr ! Request.Release("ch-expire", "client-a")
      eventually(timeout(3.seconds), interval(50.millis)) {
        teardownCount.get() shouldBe 1
      }

      val runnerCalls = new AtomicInteger(0)
      val mgr2 = spawnManager(
        startRunner = { (_, self) =>
          runnerCalls.incrementAndGet()
          self ! Command.CheckIn("ch-expire", meta("tok-2"), hubSource, () => ())
        }
      , idleGrace = 150.millis
      )
      val probe2 = testKit.createTestProbe[Response.Acquire]()
      mgr2 ! Request.Acquire("ch-expire", "client-b", probe2.ref)
      probe2.expectMessageType[Response.Attached]
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
      eventually(timeout(3.seconds), interval(50.millis)) {
        selfRef.get() should not be null
      }
      selfRef.get() ! Command.CheckIn("ch-cap", meta(), hubSource, () => ())
      probeA.expectMessageType[Response.Attached]

      mgr ! Request.Acquire("ch-other", "client-b", probeB.ref)
      probeB.expectMessage(Response.Rejected(RejectReason.NoTuners))

      mgr ! Request.Acquire("ch-cap", "client-c", probeC.ref)
      probeC.expectMessageType[Response.Attached]
    }

    "teardown on unexpected CheckIn when not Opening" in {
      val teardownCount = new AtomicInteger(0)
      val mgr = spawnManager(startRunner = (_, _) => ())
      mgr ! Command.CheckIn("ch-unexpected", meta(), hubSource, () => teardownCount.incrementAndGet())
      eventually(timeout(3.seconds), interval(50.millis)) {
        teardownCount.get() shouldBe 1
      }
    }
  }
}

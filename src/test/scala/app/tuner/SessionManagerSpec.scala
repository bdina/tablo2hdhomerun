package app.tuner

import org.apache.pekko
import pekko.NotUsed
import pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import pekko.actor.typed.ActorRef
import pekko.stream.scaladsl.Source
import pekko.stream.testkit.scaladsl.TestSink
import pekko.util.ByteString
import org.junit.runner.RunWith
import org.scalatest.concurrent.Eventually
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatestplus.junit.JUnitRunner

import app.tuner.SessionManager._

import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}

import scala.concurrent.{Future, Promise}
import scala.concurrent.duration._

@RunWith(classOf[JUnitRunner])
class SessionManagerSpec
  extends ScalaTestWithActorTestKit
  with AnyWordSpecLike
  with Matchers
  with Eventually {

  private val shortSettings = Settings(
    openTimeout = 500.millis
  , askTimeout = 2.seconds
  , materializationTimeout = 200.millis
  , closeTimeout = 500.millis
  , backpressureTimeout = 300.millis
  )

  private def player(id: String): PlayerSession =
    PlayerSession(id, s"http://example/$id.m3u8", None, Some(30))

  private final class StubBackend(
    openImpl: ChannelKey => Future[PlayerSession]
  , var tuners: Int = 2
  ) extends SessionBackend {
    val openCount = new AtomicInteger(0)
    val closeCount = new AtomicInteger(0)
    val closedIds = new AtomicReference(Vector.empty[String])

    def open(channel: ChannelKey): Future[PlayerSession] = {
      val _ = openCount.incrementAndGet()
      openImpl(channel)
    }

    def close(sessionId: SessionId): Future[Unit] = {
      val _ = closeCount.incrementAndGet()
      closedIds.getAndUpdate(_ :+ sessionId)
      Future.successful(())
    }

    def totalTuners: Int = tuners

    def refreshTuners(): Future[Int] = Future.successful(tuners)
  }

  private final class StubRuntimeFactory(
    sourceFactory: () => Source[ByteString, NotUsed] =
      () => Source.never[ByteString].mapMaterializedValue(_ => NotUsed)
  ) extends RuntimeFactory {
    val startCount = new AtomicInteger(0)
    val stopCount = new AtomicInteger(0)

    def start(
      channel: ChannelKey
    , session: PlayerSession
    , onTerminated: Option[Throwable] => Unit
    , requestReplace: (SessionId, ActorRef[ReplaceResult]) => Unit
    , backpressureTimeout: FiniteDuration
    ): SessionRuntime = {
      val _ = startCount.incrementAndGet()
      SessionRuntime(
        sessionId = session.sessionId
      , hubSource = sourceFactory()
      , stop = () => { val _ = stopCount.incrementAndGet() }
      )
    }
  }

  "SessionManager" should {

    "share one opener for concurrent same-channel acquires" in {
      val openGate = Promise[PlayerSession]()
      val backend = new StubBackend(_ => openGate.future, tuners = 2)
      val runtime = new StubRuntimeFactory()
      val manager = spawn(SessionManager(backend, runtime, shortSettings))
      val probe1 = createTestProbe[AcquireResult]()
      val probe2 = createTestProbe[AcquireResult]()
      manager ! Acquire(Gen4Channel("ch1"), probe1.ref)
      manager ! Acquire(Gen4Channel("ch1"), probe2.ref)
      eventually {
        backend.openCount.get() shouldBe 1
      }
      openGate.success(player("tok-1"))
      val a1 = probe1.expectMessageType[Attached]
      val a2 = probe2.expectMessageType[Attached]
      a1.attachmentId should not be a2.attachmentId
      runtime.startCount.get() shouldBe 1
    }

    "reject an extra distinct channel when at capacity" in {
      val backend = new StubBackend(
        { case Gen4Channel(id) => Future.successful(player(s"tok-$id")) }
      , tuners = 1
      )
      val runtime = new StubRuntimeFactory()
      val manager = spawn(SessionManager(backend, runtime, shortSettings))
      val first = createTestProbe[AcquireResult]()
      val second = createTestProbe[AcquireResult]()
      manager ! Acquire(Gen4Channel("a"), first.ref)
      val attached = first.expectMessageType[Attached]
      val sub = attached.source.runWith(TestSink[ByteString]())
      val _ = sub.request(1)
      manager ! Acquire(Gen4Channel("b"), second.ref)
      second.expectMessage(NoAvailableTuners)
      backend.openCount.get() shouldBe 1
      sub.cancel()
    }

    "count Opening reservations toward capacity" in {
      val gate = Promise[PlayerSession]()
      val backend = new StubBackend(_ => gate.future, tuners = 1)
      val runtime = new StubRuntimeFactory()
      val manager = spawn(SessionManager(backend, runtime, shortSettings))
      val first = createTestProbe[AcquireResult]()
      val second = createTestProbe[AcquireResult]()
      manager ! Acquire(Gen4Channel("a"), first.ref)
      manager ! Acquire(Gen4Channel("b"), second.ref)
      second.expectMessage(NoAvailableTuners)
      gate.success(player("tok-a"))
      val attached = first.expectMessageType[Attached]
      backend.openCount.get() shouldBe 1
      val sub = attached.source.runWith(TestSink[ByteString]())
      val _ = sub.request(1)
      sub.cancel()
    }

    "fail waiters when open fails with no tuners" in {
      val backend = new StubBackend(_ => Future.failed(NoAvailableTunersError), tuners = 2)
      val runtime = new StubRuntimeFactory()
      val manager = spawn(SessionManager(backend, runtime, shortSettings))
      val probe = createTestProbe[AcquireResult]()
      manager ! Acquire(Gen4Channel("ch1"), probe.ref)
      probe.expectMessage(NoAvailableTuners)
    }

    "keep session active after one of two attachments ends" in {
      val backend = new StubBackend(_ => Future.successful(player("tok")), tuners = 2)
      val runtime = new StubRuntimeFactory()
      val manager = spawn(SessionManager(backend, runtime, shortSettings))
      val p1 = createTestProbe[AcquireResult]()
      val p2 = createTestProbe[AcquireResult]()
      manager ! Acquire(Gen4Channel("ch"), p1.ref)
      manager ! Acquire(Gen4Channel("ch"), p2.ref)
      val a1 = p1.expectMessageType[Attached]
      val a2 = p2.expectMessageType[Attached]
      val c1 = a1.source.runWith(TestSink[ByteString]())
      val c2 = a2.source.runWith(TestSink[ByteString]())
      val _ = c1.request(1)
      val _ = c2.request(1)
      c1.cancel()
      Thread.sleep(150)
      backend.closeCount.get() shouldBe 0
      c2.cancel()
      eventually {
        backend.closeCount.get() shouldBe 1
      }
    }

    "close exactly once when last attachment ends" in {
      val backend = new StubBackend(_ => Future.successful(player("tok-last")), tuners = 2)
      val runtime = new StubRuntimeFactory()
      val manager = spawn(SessionManager(backend, runtime, shortSettings))
      val probe = createTestProbe[AcquireResult]()
      manager ! Acquire(Gen4Channel("ch"), probe.ref)
      val attached = probe.expectMessageType[Attached]
      val control = attached.source.runWith(TestSink[ByteString]())
      val _ = control.request(1)
      control.cancel()
      eventually {
        backend.closeCount.get() shouldBe 1
        runtime.stopCount.get() shouldBe 1
      }
    }

    "expire a never-materialized attachment and close the session" in {
      val backend = new StubBackend(_ => Future.successful(player("tok-exp")), tuners = 2)
      val runtime = new StubRuntimeFactory()
      val manager = spawn(SessionManager(backend, runtime, shortSettings))
      val probe = createTestProbe[AcquireResult]()
      manager ! Acquire(Gen4Channel("ch"), probe.ref)
      val _ = probe.expectMessageType[Attached]
      eventually(timeout(1.second), interval(50.millis)) {
        backend.closeCount.get() shouldBe 1
        runtime.stopCount.get() shouldBe 1
      }
    }

    "ignore duplicate attachment end messages" in {
      val backend = new StubBackend(_ => Future.successful(player("tok-dup")), tuners = 2)
      val runtime = new StubRuntimeFactory()
      val manager = spawn(SessionManager(backend, runtime, shortSettings))
      val probe = createTestProbe[AcquireResult]()
      manager ! Acquire(Gen4Channel("ch"), probe.ref)
      val attached = probe.expectMessageType[Attached]
      val control = attached.source.runWith(TestSink[ByteString]())
      val _ = control.request(1)
      control.cancel()
      manager ! AttachmentEnded(attached.attachmentId, None)
      manager ! AttachmentEnded(attached.attachmentId, None)
      eventually {
        backend.closeCount.get() shouldBe 1
      }
    }

    "close once on upstream termination" in {
      val backend = new StubBackend(_ => Future.successful(player("tok-up")), tuners = 2)
      var terminate: Option[Throwable] => Unit = _ => ()
      val runtime = new RuntimeFactory {
        def start(
          channel: ChannelKey
        , session: PlayerSession
        , onTerminated: Option[Throwable] => Unit
        , requestReplace: (SessionId, ActorRef[ReplaceResult]) => Unit
        , backpressureTimeout: FiniteDuration
        ): SessionRuntime = {
          terminate = onTerminated
          SessionRuntime(
            session.sessionId
          , Source.never[ByteString].mapMaterializedValue(_ => NotUsed)
          , () => ()
          )
        }
      }
      val manager = spawn(SessionManager(backend, runtime, shortSettings))
      val probe = createTestProbe[AcquireResult]()
      manager ! Acquire(Gen4Channel("ch"), probe.ref)
      val attached = probe.expectMessageType[Attached]
      val control = attached.source.runWith(TestSink[ByteString]())
      val _ = control.request(1)
      terminate(Some(new RuntimeException("boom")))
      eventually {
        backend.closeCount.get() shouldBe 1
      }
      control.cancel()
    }

    "replace closes old session before opening the next" in {
      val events = new AtomicReference(Vector.empty[String])
      val backend = new SessionBackend {
        def open(channel: ChannelKey): Future[PlayerSession] = {
          events.getAndUpdate(_ :+ "open")
          val n = events.get().count(_ == "open")
          Future.successful(player(s"tok-$n"))
        }
        def close(sessionId: SessionId): Future[Unit] = {
          events.getAndUpdate(_ :+ s"close:$sessionId")
          Future.successful(())
        }
        def totalTuners: Int = 2
        def refreshTuners(): Future[Int] = Future.successful(2)
      }
      var replaceFn: (SessionId, ActorRef[ReplaceResult]) => Unit = (_, _) => ()
      val runtime = new RuntimeFactory {
        def start(
          channel: ChannelKey
        , session: PlayerSession
        , onTerminated: Option[Throwable] => Unit
        , requestReplace: (SessionId, ActorRef[ReplaceResult]) => Unit
        , backpressureTimeout: FiniteDuration
        ): SessionRuntime = {
          replaceFn = requestReplace
          SessionRuntime(
            session.sessionId
          , Source.never[ByteString].mapMaterializedValue(_ => NotUsed)
          , () => ()
          )
        }
      }
      val manager = spawn(SessionManager(backend, runtime, shortSettings))
      val probe = createTestProbe[AcquireResult]()
      manager ! Acquire(Gen4Channel("ch"), probe.ref)
      val attached = probe.expectMessageType[Attached]
      val control = attached.source.runWith(TestSink[ByteString]())
      val _ = control.request(1)
      val replaceProbe = createTestProbe[ReplaceResult]()
      replaceFn("tok-1", replaceProbe.ref)
      val replaced = replaceProbe.expectMessageType[Replaced]
      replaced.session.sessionId shouldBe "tok-2"
      eventually {
        val e = events.get()
        val closeIdx = e.indexWhere(_.startsWith("close:tok-1"))
        val secondOpen = e.zipWithIndex.collect { case ("open", i) if i > 0 => i }.head
        closeIdx should be < secondOpen
      }
      control.cancel()
    }

    "block new opens after capacity decreases without ending active sessions" in {
      val backend = new StubBackend(
        { case Gen4Channel(id) => Future.successful(player(s"tok-$id")) }
      , tuners = 2
      )
      val runtime = new StubRuntimeFactory()
      val manager = spawn(SessionManager(backend, runtime, shortSettings))
      val a = createTestProbe[AcquireResult]()
      val b = createTestProbe[AcquireResult]()
      manager ! Acquire(Gen4Channel("a"), a.ref)
      manager ! Acquire(Gen4Channel("b"), b.ref)
      val aa = a.expectMessageType[Attached]
      val bb = b.expectMessageType[Attached]
      val ca = aa.source.runWith(TestSink[ByteString]())
      val cb = bb.source.runWith(TestSink[ByteString]())
      val _ = ca.request(1)
      val _ = cb.request(1)
      backend.tuners = 1
      val c = createTestProbe[AcquireResult]()
      manager ! Acquire(Gen4Channel("c"), c.ref)
      c.expectMessage(NoAvailableTuners)
      backend.closeCount.get() shouldBe 0
      ca.cancel()
      cb.cancel()
    }
  }
}

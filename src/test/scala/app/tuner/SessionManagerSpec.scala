package app.tuner

import org.apache.pekko
import pekko.NotUsed
import pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import pekko.actor.typed.ActorRef
import pekko.stream.KillSwitches
import pekko.stream.scaladsl.Source
import pekko.stream.testkit.scaladsl.TestSink
import pekko.util.ByteString
import org.junit.runner.RunWith
import org.scalatest.concurrent.Eventually
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatestplus.junit.JUnitRunner

import app.tuner.SessionManager._
import app.tuner.SessionManager.Router._

import java.util.UUID
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
  , replaceTimeout = 400.millis
  , startupRefreshTimeout = 5.seconds
  )

  private def player(id: String): PlayerSession =
    PlayerSession(id, s"http://example/$id.m3u8", None, Some(30))

  private final case class TestFailure(message: String) extends Exception(message)

  private final case class StubBackend(
    openImpl: ChannelKey => Future[PlayerSession]
  , initialTuners: Int = 2
  ) extends SessionBackend {
    @volatile var tuners: Int = initialTuners
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

  private final case class StubRuntimeFactory(
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
    , onSessionUpdated: PlayerSession => Unit
    , backpressureTimeout: FiniteDuration
    , replaceTimeout: FiniteDuration
    ): SessionRuntime = {
      val _ = startCount.incrementAndGet()
      SessionRuntime(
        sessionId = session.sessionId
      , hubSource = sourceFactory()
      , stop = () => { val _ = stopCount.incrementAndGet() }
      )
    }
  }

  private final case class CapturingRuntimeFactory(
    onTerminatedRef: AtomicReference[Option[Throwable] => Unit] =
      new AtomicReference[Option[Throwable] => Unit](_ => ())
  , replaceFnRef: AtomicReference[(SessionId, ActorRef[ReplaceResult]) => Unit] =
      new AtomicReference[(SessionId, ActorRef[ReplaceResult]) => Unit]((_, _) => ())
  , sessionUpdatedRef: AtomicReference[PlayerSession => Unit] =
      new AtomicReference[PlayerSession => Unit](_ => ())
  , resumeKeepaliveCount: AtomicInteger = new AtomicInteger(0)
  ) extends RuntimeFactory {
    def start(
      channel: ChannelKey
    , session: PlayerSession
    , onTerminated: Option[Throwable] => Unit
    , requestReplace: (SessionId, ActorRef[ReplaceResult]) => Unit
    , onSessionUpdated: PlayerSession => Unit
    , backpressureTimeout: FiniteDuration
    , replaceTimeout: FiniteDuration
    ): SessionRuntime = {
      onTerminatedRef.set(onTerminated)
      replaceFnRef.set(requestReplace)
      sessionUpdatedRef.set(onSessionUpdated)
      SessionRuntime(
        session.sessionId
      , Source.never[ByteString].mapMaterializedValue(_ => NotUsed)
      , () => ()
      , resumeKeepalive = () => { val _ = resumeKeepaliveCount.incrementAndGet() }
      )
    }
  }

  private final case class EventRecordingBackend(
    events: AtomicReference[Vector[String]] = new AtomicReference(Vector.empty[String])
  , tuners: Int = 2
  ) extends SessionBackend {
    def open(channel: ChannelKey): Future[PlayerSession] = {
      events.getAndUpdate(_ :+ "open")
      val n = events.get().count(_ == "open")
      Future.successful(player(s"tok-$n"))
    }
    def close(sessionId: SessionId): Future[Unit] = {
      events.getAndUpdate(_ :+ s"close:$sessionId")
      Future.successful(())
    }
    def totalTuners: Int = tuners
    def refreshTuners(): Future[Int] = Future.successful(tuners)
  }

  private final case class GatedOpenBackend(
    openResults: AtomicReference[Vector[Promise[PlayerSession]]] =
      new AtomicReference(Vector.empty[Promise[PlayerSession]])
  , closeCount: AtomicInteger = new AtomicInteger(0)
  , tuners: Int = 2
  ) extends SessionBackend {
    def open(channel: ChannelKey): Future[PlayerSession] = {
      val promise = Promise[PlayerSession]()
      openResults.getAndUpdate(_ :+ promise)
      promise.future
    }
    def close(sessionId: SessionId): Future[Unit] = {
      val _ = closeCount.incrementAndGet()
      Future.successful(())
    }
    def totalTuners: Int = tuners
    def refreshTuners(): Future[Int] = Future.successful(tuners)
  }

  private final case class DeferredRefreshBackend(
    refreshGate: Promise[Int]
  , tuners: Int = 4
  ) extends SessionBackend {
    def open(channel: ChannelKey): Future[PlayerSession] =
      Future.successful(player("tok"))
    def close(sessionId: SessionId): Future[Unit] = Future.successful(())
    def totalTuners: Int = tuners
    def refreshTuners(): Future[Int] = refreshGate.future
  }

  private final case class HungCloseBackend(
    closeGate: Promise[Unit]
  , openCount: AtomicInteger = new AtomicInteger(0)
  , tuners: Int = 2
  ) extends SessionBackend {
    def open(channel: ChannelKey): Future[PlayerSession] = {
      val n = openCount.incrementAndGet()
      Future.successful(player(if (n == 1) "tok-1" else s"tok-$n"))
    }
    def close(sessionId: SessionId): Future[Unit] = closeGate.future
    def totalTuners: Int = tuners
    def refreshTuners(): Future[Int] = Future.successful(tuners)
  }

  private final case class FailingThenOkCloseBackend(
    closeResults: AtomicReference[Vector[Promise[Unit]]] =
      new AtomicReference(Vector.empty[Promise[Unit]])
  , openCount: AtomicInteger = new AtomicInteger(0)
  , closeCount: AtomicInteger = new AtomicInteger(0)
  , tuners: Int = 2
  ) extends SessionBackend {
    def open(channel: ChannelKey): Future[PlayerSession] = {
      val n = openCount.incrementAndGet()
      Future.successful(player(if (n == 1) "tok-1" else s"tok-$n"))
    }
    def close(sessionId: SessionId): Future[Unit] = {
      val _ = closeCount.incrementAndGet()
      val promise = Promise[Unit]()
      closeResults.getAndUpdate(_ :+ promise)
      promise.future
    }
    def totalTuners: Int = tuners
    def refreshTuners(): Future[Int] = Future.successful(tuners)
  }

  "SessionManager" should {

    "share one opener for concurrent same-channel acquires" in {
      val openGate = Promise[PlayerSession]()
      val backend = StubBackend(_ => openGate.future, initialTuners = 2)
      val runtime = StubRuntimeFactory()
      val manager = spawn(SessionManager(backend, runtime, shortSettings))
      val probe1 = createTestProbe[AcquireResult]()
      val probe2 = createTestProbe[AcquireResult]()
      manager ! Acquire(ChannelKey("ch1"), probe1.ref)
      manager ! Acquire(ChannelKey("ch1"), probe2.ref)
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
      val backend = StubBackend(
        { case ChannelKey(id) => Future.successful(player(s"tok-$id")) }
      , initialTuners = 1
      )
      val runtime = StubRuntimeFactory()
      val manager = spawn(SessionManager(backend, runtime, shortSettings))
      val first = createTestProbe[AcquireResult]()
      val second = createTestProbe[AcquireResult]()
      manager ! Acquire(ChannelKey("a"), first.ref)
      val attached = first.expectMessageType[Attached]
      val sub = attached.source.runWith(TestSink[ByteString]())
      val _ = sub.request(1)
      manager ! Acquire(ChannelKey("b"), second.ref)
      second.expectMessage(NoAvailableTuners)
      backend.openCount.get() shouldBe 1
      sub.cancel()
    }

    "count Opening reservations toward capacity" in {
      val gate = Promise[PlayerSession]()
      val backend = StubBackend(_ => gate.future, initialTuners = 1)
      val runtime = StubRuntimeFactory()
      val manager = spawn(SessionManager(backend, runtime, shortSettings))
      val first = createTestProbe[AcquireResult]()
      val second = createTestProbe[AcquireResult]()
      manager ! Acquire(ChannelKey("a"), first.ref)
      manager ! Acquire(ChannelKey("b"), second.ref)
      second.expectMessage(NoAvailableTuners)
      gate.success(player("tok-a"))
      val attached = first.expectMessageType[Attached]
      backend.openCount.get() shouldBe 1
      val sub = attached.source.runWith(TestSink[ByteString]())
      val _ = sub.request(1)
      sub.cancel()
    }

    "fail waiters when open fails with no tuners" in {
      val backend = StubBackend(_ => Future.failed(Error.NoAvailableTuners), initialTuners = 2)
      val runtime = StubRuntimeFactory()
      val manager = spawn(SessionManager(backend, runtime, shortSettings))
      val probe = createTestProbe[AcquireResult]()
      manager ! Acquire(ChannelKey("ch1"), probe.ref)
      probe.expectMessage(NoAvailableTuners)
    }

    "keep session active after one of two attachments ends" in {
      val backend = StubBackend(_ => Future.successful(player("tok")), initialTuners = 2)
      val runtime = StubRuntimeFactory()
      val manager = spawn(SessionManager(backend, runtime, shortSettings))
      val p1 = createTestProbe[AcquireResult]()
      val p2 = createTestProbe[AcquireResult]()
      manager ! Acquire(ChannelKey("ch"), p1.ref)
      manager ! Acquire(ChannelKey("ch"), p2.ref)
      val a1 = p1.expectMessageType[Attached]
      val a2 = p2.expectMessageType[Attached]
      val c1 = a1.source.runWith(TestSink[ByteString]())
      val c2 = a2.source.runWith(TestSink[ByteString]())
      val _ = c1.request(1)
      val _ = c2.request(1)
      c1.cancel()
      eventually {
        backend.closeCount.get() shouldBe 0
      }
      c2.cancel()
      eventually {
        backend.closeCount.get() shouldBe 1
      }
    }

    "close exactly once when last attachment ends" in {
      val backend = StubBackend(_ => Future.successful(player("tok-last")), initialTuners = 2)
      val runtime = StubRuntimeFactory()
      val manager = spawn(SessionManager(backend, runtime, shortSettings))
      val probe = createTestProbe[AcquireResult]()
      manager ! Acquire(ChannelKey("ch"), probe.ref)
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
      val backend = StubBackend(_ => Future.successful(player("tok-exp")), initialTuners = 2)
      val runtime = StubRuntimeFactory()
      val manager = spawn(SessionManager(backend, runtime, shortSettings))
      val probe = createTestProbe[AcquireResult]()
      manager ! Acquire(ChannelKey("ch"), probe.ref)
      val _ = probe.expectMessageType[Attached]
      eventually(timeout(1.second), interval(50.millis)) {
        backend.closeCount.get() shouldBe 1
        runtime.stopCount.get() shouldBe 1
      }
    }

    "ignore duplicate attachment end messages" in {
      val backend = StubBackend(_ => Future.successful(player("tok-dup")), initialTuners = 2)
      val runtime = StubRuntimeFactory()
      val manager = spawn(SessionManager(backend, runtime, shortSettings))
      val probe = createTestProbe[AcquireResult]()
      manager ! Acquire(ChannelKey("ch"), probe.ref)
      val attached = probe.expectMessageType[Attached]
      val control = attached.source.runWith(TestSink[ByteString]())
      val _ = control.request(1)
      control.cancel()
      manager ! AttachmentSignal(attached.attachmentId, AttachmentEnded(None))
      manager ! AttachmentSignal(attached.attachmentId, AttachmentEnded(None))
      eventually {
        backend.closeCount.get() shouldBe 1
      }
    }

    "close once on upstream termination" in {
      val backend = StubBackend(_ => Future.successful(player("tok-up")), initialTuners = 2)
      val runtime = CapturingRuntimeFactory()
      val manager = spawn(SessionManager(backend, runtime, shortSettings))
      val probe = createTestProbe[AcquireResult]()
      manager ! Acquire(ChannelKey("ch"), probe.ref)
      val attached = probe.expectMessageType[Attached]
      val control = attached.source.runWith(TestSink[ByteString]())
      val _ = control.request(1)
      runtime.onTerminatedRef.get().apply(Some(TestFailure("boom")))
      eventually {
        backend.closeCount.get() shouldBe 1
      }
      control.cancel()
    }

    "replace closes old session before opening the next" in {
      val backend = EventRecordingBackend()
      val runtime = CapturingRuntimeFactory()
      val manager = spawn(SessionManager(backend, runtime, shortSettings))
      val probe = createTestProbe[AcquireResult]()
      manager ! Acquire(ChannelKey("ch"), probe.ref)
      val attached = probe.expectMessageType[Attached]
      val control = attached.source.runWith(TestSink[ByteString]())
      val _ = control.request(1)
      val replaceProbe = createTestProbe[ReplaceResult]()
      runtime.replaceFnRef.get().apply("tok-1", replaceProbe.ref)
      val replaced = replaceProbe.expectMessageType[Replaced]
      replaced.session.sessionId shouldBe "tok-2"
      eventually {
        val e = backend.events.get()
        val closeIdx = e.indexWhere(_.startsWith("close:tok-1"))
        val secondOpen = e.zipWithIndex.collect { case ("open", i) if i > 0 => i }.head
        closeIdx should be < secondOpen
      }
      control.cancel()
    }

    "block new opens after capacity decreases without ending active sessions" in {
      val backend = StubBackend(
        { case ChannelKey(id) => Future.successful(player(s"tok-$id")) }
      , initialTuners = 2
      )
      val runtime = StubRuntimeFactory()
      val manager = spawn(SessionManager(backend, runtime, shortSettings))
      val a = createTestProbe[AcquireResult]()
      val b = createTestProbe[AcquireResult]()
      manager ! Acquire(ChannelKey("a"), a.ref)
      manager ! Acquire(ChannelKey("b"), b.ref)
      val aa = a.expectMessageType[Attached]
      val bb = b.expectMessageType[Attached]
      val ca = aa.source.runWith(TestSink[ByteString]())
      val cb = bb.source.runWith(TestSink[ByteString]())
      val _ = ca.request(1)
      val _ = cb.request(1)
      backend.tuners = 1
      manager ! TunersUpdated(1)
      val c = createTestProbe[AcquireResult]()
      manager ! Acquire(ChannelKey("c"), c.ref)
      c.expectMessage(NoAvailableTuners)
      backend.closeCount.get() shouldBe 0
      ca.cancel()
      cb.cancel()
    }

    "close a late open success after timeout starts a newer reservation" in {
      val firstOpen = Promise[PlayerSession]()
      val secondOpen = Promise[PlayerSession]()
      val openCalls = new AtomicInteger(0)
      val backend = StubBackend(
        _ => {
          if (openCalls.incrementAndGet() == 1) firstOpen.future
          else secondOpen.future
        }
      , initialTuners = 1
      )
      val runtime = StubRuntimeFactory()
      val manager = spawn(SessionManager(backend, runtime, shortSettings.copy(openTimeout = 100.millis)))
      val first = createTestProbe[AcquireResult]()
      manager ! Acquire(ChannelKey("ch"), first.ref)
      first.expectMessageType[AcquireFailed]
      val second = createTestProbe[AcquireResult]()
      manager ! Acquire(ChannelKey("ch"), second.ref)
      eventually {
        openCalls.get() shouldBe 2
      }
      firstOpen.success(player("tok-stale"))
      secondOpen.success(player("tok-fresh"))
      val attached = second.expectMessageType[Attached]
      eventually {
        backend.closedIds.get() should contain("tok-stale")
      }
      val control = attached.source.runWith(TestSink[ByteString]())
      val _ = control.request(1)
      control.cancel()
    }

    "retry replace open after a transient failure" in {
      val backend = GatedOpenBackend()
      val runtime = CapturingRuntimeFactory()
      val manager = spawn(SessionManager(backend, runtime, shortSettings))
      val probe = createTestProbe[AcquireResult]()
      manager ! Acquire(ChannelKey("ch"), probe.ref)
      eventually {
        backend.openResults.get().size shouldBe 1
      }
      backend.openResults.get().head.success(player("tok-1"))
      val attached = probe.expectMessageType[Attached]
      val control = attached.source.runWith(TestSink[ByteString]())
      val _ = control.request(1)
      val failProbe = createTestProbe[ReplaceResult]()
      runtime.replaceFnRef.get().apply("tok-1", failProbe.ref)
      eventually {
        backend.openResults.get().size shouldBe 2
      }
      backend.openResults.get()(1).failure(TestFailure("transient"))
      failProbe.expectMessageType[ReplaceFailed]
      val retryProbe = createTestProbe[ReplaceResult]()
      runtime.replaceFnRef.get().apply("tok-1", retryProbe.ref)
      eventually {
        backend.openResults.get().size shouldBe 3
      }
      backend.openResults.get()(2).success(player("tok-2"))
      retryProbe.expectMessageType[Replaced].session.sessionId shouldBe "tok-2"
      control.cancel()
    }

    "queue acquire during closing and reopen after close completes" in {
      val backend = StubBackend(_ => Future.successful(player("tok-reopen")), initialTuners = 1)
      val runtime = StubRuntimeFactory()
      val manager = spawn(SessionManager(backend, runtime, shortSettings))
      val first = createTestProbe[AcquireResult]()
      manager ! Acquire(ChannelKey("ch"), first.ref)
      val attached = first.expectMessageType[Attached]
      val control = attached.source.runWith(TestSink[ByteString]())
      val _ = control.request(1)
      control.cancel()
      eventually {
        backend.closeCount.get() shouldBe 1
      }
      val second = createTestProbe[AcquireResult]()
      manager ! Acquire(ChannelKey("ch"), second.ref)
      val reattached = second.expectMessageType[Attached]
      eventually {
        backend.openCount.get() should be >= 2
      }
      val control2 = reattached.source.runWith(TestSink[ByteString]())
      val _ = control2.request(1)
      control2.cancel()
    }

    "apply TunersUpdated before admitting opens" in {
      val refreshGate = Promise[Int]()
      val backend = DeferredRefreshBackend(refreshGate)
      val runtime = StubRuntimeFactory()
      val manager = spawn(SessionManager(backend, runtime, shortSettings))
      val probe = createTestProbe[AcquireResult]()
      manager ! Acquire(ChannelKey("ch"), probe.ref)
      probe.expectNoMessage(150.millis)
      refreshGate.success(1)
      val attached = probe.expectMessageType[Attached]
      val control = attached.source.runWith(TestSink[ByteString]())
      val _ = control.request(1)
      control.cancel()
    }

    "grant attachments while a replace is in progress" in {
      val backend = GatedOpenBackend()
      val runtime = CapturingRuntimeFactory()
      val manager = spawn(SessionManager(backend, runtime, shortSettings))
      val first = createTestProbe[AcquireResult]()
      manager ! Acquire(ChannelKey("ch"), first.ref)
      eventually {
        backend.openResults.get().size shouldBe 1
      }
      backend.openResults.get().head.success(player("tok-1"))
      val attached = first.expectMessageType[Attached]
      val control = attached.source.runWith(TestSink[ByteString]())
      val _ = control.request(1)
      val replaceProbe = createTestProbe[ReplaceResult]()
      runtime.replaceFnRef.get().apply("tok-1", replaceProbe.ref)
      eventually {
        backend.openResults.get().size shouldBe 2
      }
      val second = createTestProbe[AcquireResult]()
      manager ! Acquire(ChannelKey("ch"), second.ref)
      val duringReplace = second.expectMessageType[Attached]
      val control2 = duringReplace.source.runWith(TestSink[ByteString]())
      val _ = control2.request(1)
      backend.openResults.get()(1).success(player("tok-2"))
      replaceProbe.expectMessageType[Replaced].session.sessionId shouldBe "tok-2"
      control.cancel()
      control2.cancel()
    }

    "fail replace when close/open exceeds replaceTimeout" in {
      val closeGate = Promise[Unit]()
      val backend = HungCloseBackend(closeGate)
      val runtime = CapturingRuntimeFactory()
      val manager = spawn(SessionManager(backend, runtime, shortSettings))
      val probe = createTestProbe[AcquireResult]()
      manager ! Acquire(ChannelKey("ch"), probe.ref)
      val attached = probe.expectMessageType[Attached]
      val control = attached.source.runWith(TestSink[ByteString]())
      val _ = control.request(1)
      val replaceProbe = createTestProbe[ReplaceResult]()
      runtime.replaceFnRef.get().apply("tok-1", replaceProbe.ref)
      val failed = replaceProbe.expectMessageType[ReplaceFailed]
      failed.cause shouldBe a[SessionManager.Error.ReplaceTimedOut]
      closeGate.success(())
      control.cancel()
    }

    "block replace open until a timed-out close completes" in {
      val closeGate = Promise[Unit]()
      val backend = HungCloseBackend(closeGate)
      val runtime = CapturingRuntimeFactory()
      val manager = spawn(SessionManager(backend, runtime, shortSettings))
      val probe = createTestProbe[AcquireResult]()
      manager ! Acquire(ChannelKey("ch"), probe.ref)
      val attached = probe.expectMessageType[Attached]
      val control = attached.source.runWith(TestSink[ByteString]())
      val _ = control.request(1)
      backend.openCount.get() shouldBe 1
      val replaceProbe = createTestProbe[ReplaceResult]()
      runtime.replaceFnRef.get().apply("tok-1", replaceProbe.ref)
      replaceProbe.expectMessageType[ReplaceFailed].cause shouldBe a[SessionManager.Error.ReplaceTimedOut]
      val busyProbe = createTestProbe[ReplaceResult]()
      runtime.replaceFnRef.get().apply("tok-1", busyProbe.ref)
      busyProbe.expectMessageType[ReplaceFailed].cause shouldBe SessionManager.Error.ReplaceAlreadyInProgress
      backend.openCount.get() shouldBe 1
      closeGate.success(())
      val replaced = eventually {
        val retryProbe = createTestProbe[ReplaceResult]()
        runtime.replaceFnRef.get().apply("tok-1", retryProbe.ref)
        retryProbe.receiveMessage(shortSettings.replaceTimeout) match {
          case Replaced(session) => session
          case ReplaceFailed(SessionManager.Error.ReplaceAlreadyInProgress) =>
            throw new Exception("waiting for late close")
          case other => fail(s"unexpected replace result: $other")
        }
      }
      replaced.sessionId shouldBe "tok-2"
      backend.openCount.get() shouldBe 2
      control.cancel()
    }

    "apply session id updates from shared stream keepalive" in {
      val backend = StubBackend(_ => Future.successful(player("tok-1")), initialTuners = 2)
      val runtime = CapturingRuntimeFactory()
      val manager = spawn(SessionManager(backend, runtime, shortSettings))
      val probe = createTestProbe[AcquireResult]()
      manager ! Acquire(ChannelKey("ch"), probe.ref)
      val attached = probe.expectMessageType[Attached]
      val control = attached.source.runWith(TestSink[ByteString]())
      val _ = control.request(1)
      runtime.sessionUpdatedRef.get().apply(player("tok-2"))
      val replaceProbe = createTestProbe[ReplaceResult]()
      runtime.replaceFnRef.get().apply("tok-1", replaceProbe.ref)
      replaceProbe.expectMessageType[ReplaceFailed]
      runtime.replaceFnRef.get().apply("tok-2", replaceProbe.ref)
      replaceProbe.expectMessageType[Replaced]
      control.cancel()
    }

    "return to active after replace close failure and retry close before open" in {
      val backend = FailingThenOkCloseBackend()
      val runtime = CapturingRuntimeFactory()
      val manager = spawn(SessionManager(backend, runtime, shortSettings))
      val probe = createTestProbe[AcquireResult]()
      manager ! Acquire(ChannelKey("ch"), probe.ref)
      val attached = probe.expectMessageType[Attached]
      val control = attached.source.runWith(TestSink[ByteString]())
      val _ = control.request(1)
      val failProbe = createTestProbe[ReplaceResult]()
      runtime.replaceFnRef.get().apply("tok-1", failProbe.ref)
      eventually {
        backend.closeResults.get().size shouldBe 1
      }
      backend.closeResults.get().head.failure(TestFailure("delete failed"))
      failProbe.expectMessageType[ReplaceFailed]
      backend.openCount.get() shouldBe 1
      val retryProbe = createTestProbe[ReplaceResult]()
      runtime.replaceFnRef.get().apply("tok-1", retryProbe.ref)
      eventually {
        backend.closeResults.get().size shouldBe 2
      }
      backend.closeResults.get()(1).success(())
      eventually {
        backend.openCount.get() shouldBe 2
      }
      retryProbe.expectMessageType[Replaced].session.sessionId shouldBe "tok-2"
      control.cancel()
    }

    "ignore session id updates while replace close is outstanding" in {
      val closeGate = Promise[Unit]()
      val backend = HungCloseBackend(closeGate)
      val runtime = CapturingRuntimeFactory()
      val manager = spawn(SessionManager(backend, runtime, shortSettings))
      val probe = createTestProbe[AcquireResult]()
      manager ! Acquire(ChannelKey("ch"), probe.ref)
      val attached = probe.expectMessageType[Attached]
      val control = attached.source.runWith(TestSink[ByteString]())
      val _ = control.request(1)
      val replaceProbe = createTestProbe[ReplaceResult]()
      runtime.replaceFnRef.get().apply("tok-1", replaceProbe.ref)
      runtime.sessionUpdatedRef.get().apply(player("tok-rotated"))
      replaceProbe.expectMessageType[ReplaceFailed].cause shouldBe a[SessionManager.Error.ReplaceTimedOut]
      closeGate.success(())
      val retryProbe = createTestProbe[ReplaceResult]()
      eventually {
        runtime.replaceFnRef.get().apply("tok-1", retryProbe.ref)
        retryProbe.receiveMessage(shortSettings.replaceTimeout) match {
          case Replaced(session) => session.sessionId shouldBe "tok-2"
          case ReplaceFailed(SessionManager.Error.ReplaceAlreadyInProgress) =>
            throw new Exception("waiting for late close")
          case other => fail(s"unexpected replace result: $other")
        }
      }
      control.cancel()
    }

    "resume keepalive when late replace close fails after timeout" in {
      val backend = FailingThenOkCloseBackend()
      val runtime = CapturingRuntimeFactory()
      val manager = spawn(SessionManager(backend, runtime, shortSettings.copy(replaceTimeout = 100.millis)))
      val probe = createTestProbe[AcquireResult]()
      manager ! Acquire(ChannelKey("ch"), probe.ref)
      val attached = probe.expectMessageType[Attached]
      val control = attached.source.runWith(TestSink[ByteString]())
      val _ = control.request(1)
      val replaceProbe = createTestProbe[ReplaceResult]()
      runtime.replaceFnRef.get().apply("tok-1", replaceProbe.ref)
      eventually {
        backend.closeResults.get().size shouldBe 1
      }
      replaceProbe.expectMessageType[ReplaceFailed].cause shouldBe a[SessionManager.Error.ReplaceTimedOut]
      runtime.resumeKeepaliveCount.get() shouldBe 0
      backend.closeResults.get().head.failure(TestFailure("late delete failed"))
      eventually {
        runtime.resumeKeepaliveCount.get() shouldBe 1
      }
      val retryProbe = createTestProbe[ReplaceResult]()
      runtime.replaceFnRef.get().apply("tok-1", retryProbe.ref)
      eventually {
        backend.closeResults.get().size shouldBe 2
      }
      backend.closeResults.get()(1).success(())
      eventually {
        backend.openCount.get() shouldBe 2
      }
      retryProbe.expectMessageType[Replaced].session.sessionId shouldBe "tok-2"
      control.cancel()
    }

    "wrap open-step failures as ReplaceOpenFailed" in {
      val backend = GatedOpenBackend()
      val runtime = CapturingRuntimeFactory()
      val manager = spawn(SessionManager(backend, runtime, shortSettings))
      val probe = createTestProbe[AcquireResult]()
      manager ! Acquire(ChannelKey("ch"), probe.ref)
      eventually {
        backend.openResults.get().size shouldBe 1
      }
      backend.openResults.get().head.success(player("tok-1"))
      val attached = probe.expectMessageType[Attached]
      val control = attached.source.runWith(TestSink[ByteString]())
      val _ = control.request(1)
      val failProbe = createTestProbe[ReplaceResult]()
      runtime.replaceFnRef.get().apply("tok-1", failProbe.ref)
      eventually {
        backend.openResults.get().size shouldBe 2
      }
      backend.openResults.get()(1).failure(TestFailure("open boom"))
      val failed = failProbe.expectMessageType[ReplaceFailed]
      failed.cause shouldBe a[SessionManager.Error.ReplaceOpenFailed]
      control.cancel()
    }

    "not open after replace close timeout until late close completes" in {
      val closeGate = Promise[Unit]()
      val backend = HungCloseBackend(closeGate)
      val runtime = CapturingRuntimeFactory()
      val manager = spawn(SessionManager(backend, runtime, shortSettings))
      val probe = createTestProbe[AcquireResult]()
      manager ! Acquire(ChannelKey("ch"), probe.ref)
      val attached = probe.expectMessageType[Attached]
      val control = attached.source.runWith(TestSink[ByteString]())
      val _ = control.request(1)
      val replaceProbe = createTestProbe[ReplaceResult]()
      runtime.replaceFnRef.get().apply("tok-1", replaceProbe.ref)
      replaceProbe.expectMessageType[ReplaceFailed].cause shouldBe a[SessionManager.Error.ReplaceTimedOut]
      replaceProbe.expectNoMessage(100.millis)
      backend.openCount.get() shouldBe 1
      closeGate.success(())
      eventually {
        val retryProbe = createTestProbe[ReplaceResult]()
        runtime.replaceFnRef.get().apply("tok-1", retryProbe.ref)
        retryProbe.receiveMessage(shortSettings.replaceTimeout) match {
          case Replaced(session) =>
            session.sessionId shouldBe "tok-2"
            backend.openCount.get() shouldBe 2
          case ReplaceFailed(SessionManager.Error.ReplaceAlreadyInProgress) =>
            throw new Exception("waiting for late close")
          case other => fail(s"unexpected replace result: $other")
        }
      }
      control.cancel()
    }

    "skip close on ReadyToRetryOpen replace" in {
      val backend = GatedOpenBackend()
      val runtime = CapturingRuntimeFactory()
      val manager = spawn(SessionManager(backend, runtime, shortSettings))
      val probe = createTestProbe[AcquireResult]()
      manager ! Acquire(ChannelKey("ch"), probe.ref)
      eventually {
        backend.openResults.get().size shouldBe 1
      }
      backend.openResults.get().head.success(player("tok-1"))
      val attached = probe.expectMessageType[Attached]
      val control = attached.source.runWith(TestSink[ByteString]())
      val _ = control.request(1)
      val failProbe = createTestProbe[ReplaceResult]()
      runtime.replaceFnRef.get().apply("tok-1", failProbe.ref)
      eventually {
        backend.openResults.get().size shouldBe 2
      }
      backend.openResults.get()(1).failure(TestFailure("transient"))
      failProbe.expectMessageType[ReplaceFailed]
      backend.closeCount.get() shouldBe 1
      val retryProbe = createTestProbe[ReplaceResult]()
      runtime.replaceFnRef.get().apply("tok-1", retryProbe.ref)
      eventually {
        backend.openResults.get().size shouldBe 3
      }
      backend.closeCount.get() shouldBe 1
      backend.openResults.get()(2).success(player("tok-2"))
      retryProbe.expectMessageType[Replaced].session.sessionId shouldBe "tok-2"
      backend.closeCount.get() shouldBe 1
      control.cancel()
    }

    "close stale late replace open success as orphan" in {
      val openGate = Promise[PlayerSession]()
      val openCalls = new AtomicInteger(0)
      val backend = StubBackend(
        _ => {
          val n = openCalls.incrementAndGet()
          if (n == 1) Future.successful(player("tok-1"))
          else openGate.future
        }
      , initialTuners = 2
      )
      val runtime = CapturingRuntimeFactory()
      val manager = spawn(SessionManager(backend, runtime, shortSettings.copy(replaceTimeout = 100.millis)))
      val probe = createTestProbe[AcquireResult]()
      manager ! Acquire(ChannelKey("ch"), probe.ref)
      val attached = probe.expectMessageType[Attached]
      val control = attached.source.runWith(TestSink[ByteString]())
      val _ = control.request(1)
      val replaceProbe = createTestProbe[ReplaceResult]()
      runtime.replaceFnRef.get().apply("tok-1", replaceProbe.ref)
      replaceProbe.expectMessageType[ReplaceFailed].cause shouldBe a[SessionManager.Error.ReplaceTimedOut]
      replaceProbe.expectNoMessage(50.millis)
      openGate.success(player("tok-late"))
      eventually {
        backend.closedIds.get() should contain("tok-late")
      }
      control.cancel()
    }
  }

  private val routerChannel = ChannelKey("51")
  private val routerOther = ChannelKey("71")

  private def routerRuntime(id: String): SessionRuntime =
    SessionRuntime(id, Source.never[ByteString].mapMaterializedValue(_ => NotUsed), () => (), () => ())

  private def routerActiveState(sessionId: String, attachments: Map[UUID, AttachmentState] = Map.empty): ManagerState = {
    val rs = SessionRuntimeState(routerRuntime(sessionId), attachments)
    initialState(2).copy(
      channels = Map(routerChannel -> Active(rs))
    , sessionIndex = Map(sessionId -> routerChannel)
    , startupGate = None
    )
  }

  "SessionManager.Router.decide" should {
    "reject acquire when at capacity" in {
      val probe = testKit.createTestProbe[AcquireResult]()
      val opening = initialState(1).copy(
        channels = Map(routerChannel -> Opening(UUID.randomUUID(), Vector.empty))
      , startupGate = None
      )
      val (next, effects) = decide(opening, AcquireRequested(routerOther, probe.ref), shortSettings)
      next.channels.contains(routerOther) shouldBe false
      effects should contain(ReplyAcquire(probe.ref, NoAvailableTuners))
      effects.collect { case r: RefreshTuners => r } should not be empty
    }

    "queue concurrent acquires onto one Opening reservation" in {
      val first = testKit.createTestProbe[AcquireResult]()
      val second = testKit.createTestProbe[AcquireResult]()
      val reservationId = UUID.randomUUID()
      val state = initialState(2).copy(
        channels = Map(routerChannel -> Opening(reservationId, Vector(PendingAcquire(first.ref))))
      , startupGate = None
      )
      val (next, effects) = decide(state, AcquireRequested(routerChannel, second.ref), shortSettings)
      effects shouldBe empty
      next.channels(routerChannel) match {
        case Opening(id, waiters) =>
          id shouldBe reservationId
          waiters.map(_.replyTo) shouldBe Vector(first.ref, second.ref)
        case other => fail(s"expected Opening, got $other")
      }
    }

    "emit StartRuntime on matching open success" in {
      val waiter = testKit.createTestProbe[AcquireResult]()
      val reservationId = UUID.randomUUID()
      val session = player("s1")
      val state = initialState(2).copy(
        channels = Map(routerChannel -> Opening(reservationId, Vector(PendingAcquire(waiter.ref))))
      , startupGate = None
      )
      val (next, effects) = decide(state, OpenCompleted(routerChannel, reservationId, Right(session)), shortSettings)
      next.channels(routerChannel) shouldBe a[Opening]
      effects shouldBe Vector(StartRuntime(routerChannel, session, Vector(PendingAcquire(waiter.ref))))
    }

    "close orphan on stale open success" in {
      val current = UUID.randomUUID()
      val stale = UUID.randomUUID()
      val state = initialState(2).copy(
        channels = Map(routerChannel -> Opening(current, Vector.empty))
      , startupGate = None
      )
      val (_, effects) = decide(state, OpenCompleted(routerChannel, stale, Right(player("orphan"))), shortSettings)
      effects shouldBe Vector(CloseOrphan(routerChannel, "orphan"))
    }

    "grant attachment while replacing" in {
      val probe = testKit.createTestProbe[AcquireResult]()
      val rs = SessionRuntimeState(routerRuntime("s1"), Map.empty)
      val state = initialState(2).copy(
        channels = Map(routerChannel -> Replacing(rs, "s1", ClosingPrior))
      , sessionIndex = Map("s1" -> routerChannel)
      , startupGate = None
      )
      val (next, effects) = decide(state, AcquireRequested(routerChannel, probe.ref), shortSettings)
      next.channels(routerChannel) shouldBe a[Replacing]
      effects.collect { case g: GrantAttachment => g.replyTo } shouldBe Vector(probe.ref)
    }

    "begin closing when last attachment ends" in {
      val attachmentId = UUID.randomUUID()
      val ks = KillSwitches.shared("att")
      val state = routerActiveState("s1", Map(attachmentId -> Granted(ks)))
      val (next, effects) = decide(state, AttachmentObserved(attachmentId, AttachmentEnded(None)), shortSettings)
      next.channels(routerChannel) shouldBe a[Closing]
      effects.collect { case StopRuntime(_) => true } should not be empty
      effects.collect { case RunClose(_, "s1", _) => true } should not be empty
    }

    "progress replace close success to open step" in {
      val reply = testKit.createTestProbe[ReplaceResult]()
      val attemptId = UUID.randomUUID()
      val ks = KillSwitches.shared("rep")
      val rs = SessionRuntimeState(routerRuntime("s1"), Map.empty)
      val state = initialState(2).copy(
        channels = Map(routerChannel -> Replacing(rs, "s1", ClosingPrior, Some(reply.ref), attemptId, Some(ks)))
      , sessionIndex = Map("s1" -> routerChannel)
      , startupGate = None
      )
      val (next, effects) = decide(
        state
      , ReplaceCloseCompleted(routerChannel, "s1", attemptId, reply.ref, CloseOk)
      , shortSettings
      )
      next.channels(routerChannel) match {
        case Replacing(_, "s1", OpeningNext, Some(ref), aid, Some(_)) =>
          ref shouldBe reply.ref
          aid shouldBe attemptId
        case other => fail(s"expected OpeningNext replacing, got $other")
      }
      next.sessionIndex.contains("s1") shouldBe false
      effects.collect { case r: RunReplaceOpen => r.attemptId } shouldBe Vector(attemptId)
    }

    "move to WaitingForLateClose on replace close timeout" in {
      val reply = testKit.createTestProbe[ReplaceResult]()
      val attemptId = UUID.randomUUID()
      val ks = KillSwitches.shared("rep")
      val rs = SessionRuntimeState(routerRuntime("s1"), Map.empty)
      val state = initialState(2).copy(
        channels = Map(routerChannel -> Replacing(rs, "s1", ClosingPrior, Some(reply.ref), attemptId, Some(ks)))
      , sessionIndex = Map("s1" -> routerChannel)
      , startupGate = None
      )
      val (next, effects) = decide(
        state
      , ReplaceCloseCompleted(routerChannel, "s1", attemptId, reply.ref, CloseTimedOut)
      , shortSettings
      )
      next.channels(routerChannel) match {
        case Replacing(_, "s1", WaitingForLateClose, None, aid, None) =>
          aid shouldBe attemptId
        case other => fail(s"expected WaitingForLateClose, got $other")
      }
      effects should contain(ReplyReplace(reply.ref, ReplaceFailed(Error.ReplaceTimedOut("51"))))
    }

    "ready retry open after late replace close" in {
      val attemptId = UUID.randomUUID()
      val rs = SessionRuntimeState(routerRuntime("s1"), Map.empty)
      val state = initialState(2).copy(
        channels = Map(routerChannel -> Replacing(rs, "s1", WaitingForLateClose, None, attemptId, None))
      , sessionIndex = Map("s1" -> routerChannel)
      , startupGate = None
      )
      val (next, effects) = decide(
        state
      , ReplaceLateCloseCompleted(routerChannel, "s1", Right(()))
      , shortSettings
      )
      next.channels(routerChannel) match {
        case Replacing(_, "s1", ReadyToRetryOpen, None, aid, None) =>
          aid shouldBe attemptId
        case other => fail(s"expected ReadyToRetryOpen, got $other")
      }
      effects shouldBe empty
    }

    "flush startup gate on TunersRefreshed" in {
      val probe = testKit.createTestProbe[AcquireResult]()
      val state = initialState(2).copy(
        startupGate = Some(Vector(routerChannel -> probe.ref))
      )
      val (next, effects) = decide(state, TunersRefreshed(2), shortSettings)
      next.startupGate shouldBe None
      next.channels(routerChannel) shouldBe a[Opening]
      effects.head shouldBe CancelStartupTimer
      effects.collect { case r: RunOpen => r.channel } shouldBe Vector(routerChannel)
    }

    "activate via RuntimeStarted and grant waiters" in {
      val waiter = testKit.createTestProbe[AcquireResult]()
      val state = initialState(2).copy(
        channels = Map(routerChannel -> Opening(UUID.randomUUID(), Vector(PendingAcquire(waiter.ref))))
      , startupGate = None
      )
      val rt = routerRuntime("s1")
      val (next, effects) = decide(
        state
      , RuntimeStarted(routerChannel, rt, Vector(PendingAcquire(waiter.ref)))
      , shortSettings
      )
      next.channels(routerChannel) shouldBe a[Active]
      next.sessionIndex("s1") shouldBe routerChannel
      effects.collect { case g: GrantAttachment => g.replyTo } shouldBe Vector(waiter.ref)
    }
  }
}

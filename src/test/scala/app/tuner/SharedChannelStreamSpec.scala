package app.tuner

import org.apache.pekko
import pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import pekko.actor.typed.{ActorRef, ActorSystem}
import pekko.stream.scaladsl.{BroadcastHub, Keep, Source}
import pekko.stream.testkit.TestPublisher
import pekko.stream.testkit.scaladsl.{TestSink, TestSource}
import pekko.util.ByteString
import org.junit.runner.RunWith
import org.scalatest.BeforeAndAfterAll
import org.scalatest.concurrent.Eventually
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatestplus.junit.JUnitRunner

import app.AppContext
import app.config.AppConfig
import app.stream.StreamBackend
import app.tuner.SessionManager.{PlayerSession, ReplaceFailed, ReplaceResult}
import app.tuner.SharedChannelStream.{
  KeepaliveIdle
, KeepaliveOps
, ReplaceAttemptOwner
, ScheduleKeepalive
, ScheduleKeepaliveRetry
, settleKeepaliveAction
}
import app.tuner.TabloLegacy.Response.Discover

import java.time.Instant
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger, AtomicReference}

import scala.concurrent.{Await, Future, Promise}
import scala.concurrent.duration._
import scala.util.{Failure, Success}

@RunWith(classOf[JUnitRunner])
class SharedChannelStreamSpec
  extends ScalaTestWithActorTestKit
  with AnyWordSpecLike
  with Matchers
  with Eventually
  with BeforeAndAfterAll {

  override def beforeAll(): Unit = {
    super.beforeAll()
    val config = AppConfig.load(Map.empty).config
    val discover = Discover(
      friendlyName = "Tablo Legacy Gen Proxy"
    , localIp = config.proxy.ip
    , protocol = config.tablo.protocol
    , port = config.proxy.port
    )
    AppContext.initialize(config, discover)
  }

  "BroadcastHub fan-out" should {

    "deliver identical subsequent bytes to two subscribers" in {
      val (probe, pub) = TestSource[ByteString]().preMaterialize()
      val hubSource =
        pub.toMat(BroadcastHub.sink[ByteString](startAfterNrOfConsumers = 2, bufferSize = 256))(Keep.right).run()
      val s1 = hubSource.runWith(TestSink[ByteString]())
      val s2 = hubSource.runWith(TestSink[ByteString]())
      val _ = s1.request(1)
      val _ = s2.request(1)
      val chunk = ByteString("abc")
      val _ = probe.sendNext(chunk)
      val _ = s1.expectNext(chunk)
      val _ = s2.expectNext(chunk)
      s1.cancel()
      s2.cancel()
      probe.sendComplete()
    }

    "give late subscribers only subsequent live bytes" in {
      val (probe, pub) = TestSource[ByteString]().preMaterialize()
      val hubSource =
        pub.toMat(BroadcastHub.sink[ByteString](startAfterNrOfConsumers = 1, bufferSize = 256))(Keep.right).run()
      val early = hubSource.runWith(TestSink[ByteString]())
      val _ = early.request(2)
      val _ = probe.sendNext(ByteString("early"))
      val _ = early.expectNext(ByteString("early"))
      val late = hubSource.runWith(TestSink[ByteString]())
      val _ = late.request(1)
      val _ = probe.sendNext(ByteString("late"))
      val _ = early.expectNext(ByteString("late"))
      val _ = late.expectNext(ByteString("late"))
      early.cancel()
      late.cancel()
      probe.sendComplete()
    }

    "fail a slow subscriber via backpressureTimeout while another continues" in {
      val (probe, pub) = TestSource[ByteString]().preMaterialize()
      val hubSource =
        pub.toMat(BroadcastHub.sink[ByteString](startAfterNrOfConsumers = 2, bufferSize = 8))(Keep.right).run()
      val slow =
        hubSource
          .backpressureTimeout(200.millis)
          .runWith(TestSink[ByteString]())
      val fast =
        hubSource
          .backpressureTimeout(2.seconds)
          .runWith(TestSink[ByteString]())
      val _ = slow.request(1)
      val _ = fast.request(10)
      val _ = probe.sendNext(ByteString("1"))
      val _ = slow.expectNext(ByteString("1"))
      val _ = fast.expectNext(ByteString("1"))
      val _ = probe.sendNext(ByteString("2"))
      val _ = fast.expectNext(ByteString("2"))
      val _ = slow.expectError()
      val _ = probe.sendNext(ByteString("3"))
      val _ = fast.expectNext(ByteString("3"))
      fast.cancel()
      probe.sendComplete()
    }
  }

  "ReplaceAttemptOwner" should {

    "fail the active attempt on abort and remain idempotent" in {
      implicit val ec = system.executionContext
      val owner = new ReplaceAttemptOwner
      val attempt = owner.begin().toOption.get
      owner.abort(SessionManager.Error.UpstreamTerminated)
      eventually {
        attempt.promise.future.value match {
          case Some(Failure(SessionManager.Error.UpstreamTerminated)) => succeed
          case other => fail(s"unexpected abort result: $other")
        }
      }
      owner.abort(SessionManager.Error.UpstreamTerminated)
      owner.begin().isRight shouldBe true
    }

    "reject overlapping begin without orphaning the first promise" in {
      val owner = new ReplaceAttemptOwner
      val first = owner.begin().toOption.get
      owner.begin() shouldBe Left(SessionManager.Error.ReplaceAlreadyInProgress)
      first.promise.isCompleted shouldBe false
      owner.isCurrent(first) shouldBe true
    }

    "ignore stale release after a newer attempt is active" in {
      val owner = new ReplaceAttemptOwner
      val first = owner.begin().toOption.get
      val _ = first.promise.trySuccess(
        PlayerSession("tok", "http://invalid.example/x.m3u8", None, None)
      )
      owner.release(first)
      val second = owner.begin().toOption.get
      owner.release(first)
      owner.isCurrent(second) shouldBe true
      second.promise.isCompleted shouldBe false
    }

    "mark a completed generation as not current after begin of the next attempt" in {
      val owner = new ReplaceAttemptOwner
      val first = owner.begin().toOption.get
      val firstGen = first.generation
      val _ = first.promise.trySuccess(
        PlayerSession("tok", "http://invalid.example/x.m3u8", None, None)
      )
      owner.release(first)
      val second = owner.begin().toOption.get
      owner.isCurrent(first) shouldBe false
      second.generation should be > firstGen
      owner.isCurrent(second) shouldBe true
    }
  }

  "settleKeepaliveAction" should {

    "schedule normal keepalive when the update still applies" in {
      settleKeepaliveAction(stillCurrent = true, applied = true) shouldBe ScheduleKeepalive
    }

    "schedule retry when generation is current but session id no longer matches" in {
      settleKeepaliveAction(stillCurrent = true, applied = false) shouldBe ScheduleKeepaliveRetry
    }

    "stay idle when the generation is no longer current" in {
      settleKeepaliveAction(stillCurrent = false, applied = false) shouldBe KeepaliveIdle
      settleKeepaliveAction(stillCurrent = false, applied = true) shouldBe KeepaliveIdle
    }
  }

  "SharedChannelStream.startShared" should {

    "report termination when stop() shuts down the shared upstream" in {
      implicit val mat = pekko.stream.Materializer(system)
      implicit val ec = system.executionContext
      val terminated = new AtomicBoolean(false)
      val runtime = SharedChannelStream.startShared(
        channelLabel = "test-complete"
      , firstSession = PlayerSession(
          "tok"
        , "http://invalid.example/x.m3u8"
        , Some(Instant.now().plusSeconds(3600))
        , None
        )
      , keepaliveOps = None
      , onTerminated = _ => terminated.set(true)
      , requestReplace = (_, _) => ()
      , onSessionUpdated = _ => ()
      , backpressureTimeout = 1.second
      , replaceTimeout = 500.millis
      )
      val sink = runtime.hubSource.runWith(TestSink[ByteString]())
      val _ = sink.request(1)
      runtime.stop()
      eventually(timeout(3.seconds), interval(50.millis)) {
        terminated.get() shouldBe true
      }
      sink.cancel()
    }

    "fail replace when SessionManager never replies and retry after timeout" in {
      implicit val mat = pekko.stream.Materializer(system)
      implicit val ec = system.executionContext
      val replaceCalls = new AtomicInteger(0)
      val runtime = SharedChannelStream.startShared(
        channelLabel = "test-replace-timeout"
      , firstSession = PlayerSession("tok", "http://invalid.example/x.m3u8", None, None)
      , keepaliveOps = None
      , onTerminated = _ => ()
      , requestReplace = (_, _) => { val _ = replaceCalls.incrementAndGet() }
      , onSessionUpdated = _ => ()
      , backpressureTimeout = 1.second
      , replaceTimeout = 200.millis
      )
      val sink = runtime.hubSource.runWith(TestSink[ByteString]())
      val _ = sink.request(1)
      eventually(timeout(3.seconds), interval(50.millis)) {
        replaceCalls.get() should be >= 1
      }
      eventually(timeout(8.seconds), interval(100.millis)) {
        replaceCalls.get() should be >= 2
      }
      runtime.stop()
      sink.cancel()
    }

    "resume keepalive after a replace failure settles into a fresh session" in {
      implicit val mat = pekko.stream.Materializer(system)
      implicit val ec = system.executionContext
      val keepaliveCount = new AtomicInteger(0)
      val replaceCalls = new AtomicInteger(0)
      val ops = KeepaliveOps(
        keepalive = session => {
          val _ = keepaliveCount.incrementAndGet()
          Future.successful(session)
        }
      , fetch = session => Future.successful(session)
      )
      val runtime = SharedChannelStream.startShared(
        channelLabel = "test-keepalive-resume"
      , firstSession = PlayerSession(
          "tok"
        , "http://invalid.example/x.m3u8"
        , Some(Instant.now().minusSeconds(1))
        , Some(6)
        )
      , keepaliveOps = Some(ops)
      , onTerminated = _ => ()
      , requestReplace = (_, replyTo) => {
          val n = replaceCalls.incrementAndGet()
          if (n == 1)
            replyTo ! ReplaceFailed(SessionManager.Error.ReplaceTimedOut("test-keepalive-resume"))
          else
            replyTo ! SessionManager.Replaced(
              PlayerSession(
                "tok-2"
              , "http://invalid.example/x.m3u8"
              , Some(Instant.now().plusSeconds(3600))
              , Some(6)
              )
            )
        }
      , onSessionUpdated = _ => ()
      , backpressureTimeout = 1.second
      , replaceTimeout = 500.millis
      )
      val sink = runtime.hubSource.runWith(TestSink[ByteString]())
      val _ = sink.request(1)
      eventually(timeout(8.seconds), interval(100.millis)) {
        replaceCalls.get() should be >= 2
      }
      eventually(timeout(10.seconds), interval(100.millis)) {
        keepaliveCount.get() should be >= 1
      }
      runtime.stop()
      sink.cancel()
    }

    "ignore late replace success after reply timeout" in {
      implicit val mat = pekko.stream.Materializer(system)
      implicit val ec = system.executionContext
      val replyRef = new AtomicReference[Option[ActorRef[ReplaceResult]]](None)
      val replaceIds = new AtomicReference(Vector.empty[String])
      val runtime = SharedChannelStream.startShared(
        channelLabel = "test-late-replace"
      , firstSession = PlayerSession(
          "tok"
        , "http://invalid.example/x.m3u8"
        , Some(Instant.now().minusSeconds(1))
        , None
        )
      , keepaliveOps = None
      , onTerminated = _ => ()
      , requestReplace = (sessionId, replyTo) => {
          replaceIds.getAndUpdate(_ :+ sessionId)
          replyRef.set(Some(replyTo))
        }
      , onSessionUpdated = _ => ()
      , backpressureTimeout = 1.second
      , replaceTimeout = 150.millis
      )
      val sink = runtime.hubSource.runWith(TestSink[ByteString]())
      val _ = sink.request(1)
      eventually(timeout(3.seconds), interval(50.millis)) {
        replyRef.get().isDefined shouldBe true
      }
      val firstReply = replyRef.get().get
      eventually(timeout(8.seconds), interval(100.millis)) {
        replaceIds.get().size should be >= 2
      }
      val idsBeforeLate = replaceIds.get()
      firstReply ! SessionManager.Replaced(
        PlayerSession("tok-late", "http://invalid.example/late.m3u8", None, None)
      )
      eventually(timeout(5.seconds), interval(50.millis)) {
        replaceIds.get().size should be >= idsBeforeLate.size
      }
      replaceIds.get().foreach(_ shouldBe "tok")
      runtime.stop()
      sink.cancel()
    }

    "notify session updates when keepalive changes the player token" in {
      implicit val mat = pekko.stream.Materializer(system)
      implicit val ec = system.executionContext
      val updatedIds = new AtomicReference(Vector.empty[String])
      val keepaliveCount = new AtomicInteger(0)
      val ops = KeepaliveOps(
        keepalive = session => {
          val _ = keepaliveCount.incrementAndGet()
          Future.successful(session.copy(sessionId = "tok-next", keepalive = Some(30)))
        }
      , fetch = session => Future.successful(session)
      )
      val runtime = SharedChannelStream.startShared(
        channelLabel = "test-session-update"
      , firstSession = PlayerSession(
          "tok"
        , "http://invalid.example/x.m3u8"
        , Some(Instant.now().plusSeconds(32))
        , Some(6)
        )
      , keepaliveOps = Some(ops)
      , onTerminated = _ => ()
      , requestReplace = (_, replyTo: ActorRef[ReplaceResult]) =>
          replyTo ! ReplaceFailed(SessionManager.Error.ReplaceNotActive("test"))
      , onSessionUpdated = session => updatedIds.getAndUpdate(_ :+ session.sessionId)
      , backpressureTimeout = 1.second
      , replaceTimeout = 500.millis
      )
      eventually(timeout(8.seconds), interval(100.millis)) {
        keepaliveCount.get() should be >= 1
        updatedIds.get() should contain("tok-next")
      }
      runtime.stop()
    }

    "wait for fetch to settle before scheduling keepalive retry" in {
      implicit val mat = pekko.stream.Materializer(system)
      implicit val ec = system.executionContext
      val keepaliveCalls = new AtomicInteger(0)
      val fetchStarted = Promise[Unit]()
      val fetchGate = Promise[PlayerSession]()
      val ops = KeepaliveOps(
        keepalive = session => {
          val n = keepaliveCalls.incrementAndGet()
          if (n == 1) Future.failed(new Exception("keepalive boom"))
          else Future.successful(session)
        }
      , fetch = _ => {
          val _ = fetchStarted.trySuccess(())
          fetchGate.future
        }
      )
      val runtime = SharedChannelStream.startShared(
        channelLabel = "test-fetch-before-retry"
      , firstSession = PlayerSession(
          "tok"
        , "http://invalid.example/x.m3u8"
        , Some(Instant.now().plusSeconds(3600))
        , Some(6)
        )
      , keepaliveOps = Some(ops)
      , onTerminated = _ => ()
      , requestReplace = (_, replyTo) =>
          replyTo ! ReplaceFailed(SessionManager.Error.ReplaceNotActive("test-fetch-before-retry"))
      , onSessionUpdated = _ => ()
      , backpressureTimeout = 1.second
      , replaceTimeout = 500.millis
      )
      eventually(timeout(8.seconds), interval(100.millis)) {
        fetchStarted.isCompleted shouldBe true
      }
      keepaliveCalls.get() shouldBe 1
      fetchGate.success(
        PlayerSession(
          "tok"
        , "http://invalid.example/x.m3u8"
        , Some(Instant.now().plusSeconds(3600))
        , Some(6)
        )
      )
      eventually(timeout(12.seconds), interval(100.millis)) {
        keepaliveCalls.get() should be >= 2
      }
      runtime.stop()
    }

    "wait for failed fetch to settle before scheduling keepalive retry" in {
      implicit val mat = pekko.stream.Materializer(system)
      implicit val ec = system.executionContext
      val keepaliveCalls = new AtomicInteger(0)
      val fetchStarted = Promise[Unit]()
      val fetchGate = Promise[PlayerSession]()
      val ops = KeepaliveOps(
        keepalive = session => {
          val n = keepaliveCalls.incrementAndGet()
          if (n == 1) Future.failed(new Exception("keepalive boom"))
          else Future.successful(session)
        }
      , fetch = _ => {
          val _ = fetchStarted.trySuccess(())
          fetchGate.future
        }
      )
      val runtime = SharedChannelStream.startShared(
        channelLabel = "test-fetch-fail-before-retry"
      , firstSession = PlayerSession(
          "tok"
        , "http://invalid.example/x.m3u8"
        , Some(Instant.now().plusSeconds(3600))
        , Some(6)
        )
      , keepaliveOps = Some(ops)
      , onTerminated = _ => ()
      , requestReplace = (_, replyTo) =>
          replyTo ! ReplaceFailed(SessionManager.Error.ReplaceNotActive("test-fetch-fail-before-retry"))
      , onSessionUpdated = _ => ()
      , backpressureTimeout = 1.second
      , replaceTimeout = 500.millis
      )
      eventually(timeout(8.seconds), interval(100.millis)) {
        fetchStarted.isCompleted shouldBe true
      }
      keepaliveCalls.get() shouldBe 1
      fetchGate.failure(new Exception("fetch boom"))
      eventually(timeout(15.seconds), interval(100.millis)) {
        keepaliveCalls.get() should be >= 2
      }
      runtime.stop()
    }

    "ignore stale in-flight keepalive after replace has begun" in {
      implicit val mat = pekko.stream.Materializer(system)
      implicit val ec = system.executionContext
      val initialNow = Instant.parse("2026-01-01T00:00:00Z")
      val clock = new AtomicReference(initialNow)
      val keepaliveGate = Promise[PlayerSession]()
      val passedFlagCheck = Promise[Unit]()
      val releaseAfterReplace = Promise[Unit]()
      val replaceRequested = Promise[ActorRef[ReplaceResult]]()
      val updatedIds = new AtomicReference(Vector.empty[String])
      val backendCalls = new AtomicInteger(0)
      val firstProbe = new AtomicReference[Option[TestPublisher.Probe[ByteString]]](None)
      val owner = new ReplaceAttemptOwner
      val firstSession = PlayerSession(
        "tok"
      , "http://invalid.example/x.m3u8"
      , Some(initialNow.plusSeconds(3600))
      , Some(6)
      )
      val backend = new StreamBackend {
        override val name: String = "test-controlled"
        override def stream(playlistUrl: String, label: String = "")(
          implicit system: ActorSystem[?]
        ): Source[ByteString, ?] = {
          val n = backendCalls.incrementAndGet()
          if (n == 1) {
            val (probe, source) = TestSource[ByteString]().preMaterialize()
            firstProbe.set(Some(probe))
            source
          } else
            Source.single(ByteString("replacement")) ++ Source.maybe[ByteString]
        }
      }
      val ops = KeepaliveOps(
        keepalive = _ => keepaliveGate.future
      , fetch = session => Future.successful(session)
      )
      val runtime = SharedChannelStream.startShared(
        channelLabel = "test-stale-keepalive"
      , firstSession = firstSession
      , keepaliveOps = Some(ops)
      , onTerminated = _ => ()
      , requestReplace = (_, replyTo) => { val _ = replaceRequested.trySuccess(replyTo) }
      , onSessionUpdated = session => { val _ = updatedIds.getAndUpdate(_ :+ session.sessionId) }
      , backpressureTimeout = 1.second
      , replaceTimeout = 10.seconds
      , replaceAttempts = owner
      , streamBackend = backend
      , now = () => clock.get()
      , afterKeepaliveProgressCheck = () => {
          val _ = passedFlagCheck.trySuccess(())
          Await.result(releaseAfterReplace.future, 15.seconds)
        }
      )
      val sink = runtime.hubSource.runWith(TestSink[ByteString]())
      val _ = sink.request(1)
      eventually(timeout(3.seconds), interval(50.millis)) {
        firstProbe.get().isDefined shouldBe true
      }
      eventually(timeout(8.seconds), interval(100.millis)) {
        passedFlagCheck.isCompleted shouldBe true
      }
      clock.set(initialNow.plusSeconds(4000))
      firstProbe.get().get.sendError(new RuntimeException("force replace"))
      val replyTo = Await.result(replaceRequested.future, 5.seconds)
      replyTo ! SessionManager.Replaced(
        firstSession.copy(
          expires = Some(initialNow.plusSeconds(8000))
        , keepalive = Some(6)
        )
      )
      eventually(timeout(5.seconds), interval(50.millis)) {
        owner.activeFuture.isEmpty shouldBe true
        backendCalls.get() should be >= 2
      }
      releaseAfterReplace.success(())
      keepaliveGate.success(
        firstSession.copy(
          sessionId = "tok-stale"
        , playlistUrl = "http://invalid.example/stale.m3u8"
        )
      )
      val settleChecked = Promise[Boolean]()
      import org.apache.pekko.actor.typed.scaladsl.adapter._
      system.toClassic.scheduler.scheduleOnce(1.second) {
        settleChecked.success(updatedIds.get().contains("tok-stale"))
      }(ec)
      eventually(timeout(3.seconds), interval(50.millis)) {
        settleChecked.isCompleted shouldBe true
      }
      settleChecked.future.value match {
        case Some(Success(false)) => succeed
        case other => fail(s"stale keepalive was applied unexpectedly: $other")
      }
      runtime.stop()
      sink.cancel()
    }

    "abort the in-flight replace promise and complete the hub on stop" in {
      implicit val mat = pekko.stream.Materializer(system)
      implicit val ec = system.executionContext
      val replaceStarted = Promise[Unit]()
      val owner = new ReplaceAttemptOwner
      val runtime = SharedChannelStream.startShared(
        channelLabel = "test-stop-replace"
      , firstSession = PlayerSession(
          "tok"
        , "http://invalid.example/x.m3u8"
        , Some(Instant.now().minusSeconds(1))
        , None
        )
      , keepaliveOps = None
      , onTerminated = _ => ()
      , requestReplace = (_, _) => { val _ = replaceStarted.trySuccess(()) }
      , onSessionUpdated = _ => ()
      , backpressureTimeout = 1.second
      , replaceTimeout = 5.seconds
      , replaceAttempts = owner
      )
      val sink = runtime.hubSource.runWith(TestSink[ByteString]())
      val _ = sink.request(1)
      eventually(timeout(3.seconds), interval(50.millis)) {
        replaceStarted.isCompleted shouldBe true
      }
      val replaceFut = owner.activeFuture.getOrElse(fail("expected an active replace future"))
      runtime.stop()
      eventually(timeout(3.seconds), interval(50.millis)) {
        replaceFut.value match {
          case Some(Failure(SessionManager.Error.UpstreamTerminated)) => succeed
          case other => fail(s"unexpected replace abort result: $other")
        }
      }
      eventually(timeout(3.seconds), interval(50.millis)) {
        sink.expectComplete()
      }
    }
  }
}

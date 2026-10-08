package app.stream

import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.stream.scaladsl.{Sink, Source}
import org.apache.pekko.stream.testkit.scaladsl.TestSink
import org.apache.pekko.util.ByteString
import org.junit.runner.RunWith
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatestplus.junit.JUnitRunner

import scala.concurrent.duration._
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}

import app.config.AppConfig
import app.AppContext
import app.tuner.TabloLegacy.Response.Discover

@RunWith(classOf[JUnitRunner])
class ResilientHlsSourceSpec extends ScalaTestWithActorTestKit with AnyWordSpecLike with Matchers {

  override def beforeAll(): Unit = {
    super.beforeAll()
    val config = AppConfig.load(Map.empty).config
    val discover = Discover(
      friendlyName = "Tablo Legacy Gen Proxy",
      localIp = config.proxy.ip,
      protocol = config.tablo.protocol,
      port = config.proxy.port
    )
    AppContext.initialize(config, discover)
  }

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

    "construct valid MPEG-TS discontinuity packets" in {
      val packet = ResilientHlsSource.MPEGTS_DISCONTINUITY_PACKET
      val _ = packet.length shouldBe 188
      val _ = packet(0) shouldBe 0x47.toByte
      val _ = (packet(5) & 0x80) should not be 0
    }

    "inject resume prefix when stream restarts after failure" in {
      val attemptCount = new AtomicInteger(0)
      val customPrefix = ByteString("retune-prefix-")
      val stream1 = ByteString("stream-1")
      val stream2 = ByteString("stream-2")
      val failPromise = scala.concurrent.Promise[ByteString]()

      val factory = () => {
        val a = attemptCount.incrementAndGet()
        if (a == 1) {
          Source.single(stream1).concat(Source.future(failPromise.future))
        } else {
          Source.single(stream2)
        }
      }

      val wrappedSource = ResilientHlsSource(
        factory
      , "test-retune-prefix"
      , recoveryTimeout = 5.seconds
      , retryDelay = 50.millis
      , resumePrefixSupplier = () => Some(customPrefix)
      )

      val probe = wrappedSource.runWith(TestSink[ByteString]())
      val _ = probe.ensureSubscription()
      val _ = probe.requestNext(2.seconds) shouldBe stream1

      failPromise.failure(new RuntimeException("stream 1 failure"))

      var next = probe.requestNext(2.seconds)
      while (next == ResilientHlsSource.MPEGTS_NULL_PACKET) {
        next = probe.requestNext(2.seconds)
      }
      val _ = next shouldBe (customPrefix ++ stream2)
      probe.cancel()
    }

    "retune when the inner stream goes silent after data (stall watchdog)" in {
      val attempts = new AtomicInteger(0)
      val data1 = ByteString("data-one")
      val data2 = ByteString("data-two")
      val customPrefix = ByteString("resume-prefix-")

      val factory = () => {
        val a = attempts.incrementAndGet()
        if (a == 1) {
          Source.single(data1).concat(Source.never)
        } else {
          Source.single(data2)
        }
      }

      val wrappedSource = ResilientHlsSource(
        factory
      , "test-stall-watchdog"
      , stallTimeout = 300.millis
      , tuneTimeout = 600.millis
      , retryDelay = 100.millis
      , recoveryTimeout = 5.seconds
      , resumePrefixSupplier = () => Some(customPrefix)
      )

      val probe = wrappedSource.runWith(TestSink[ByteString]())
      val _ = probe.ensureSubscription()
      val _ = probe.requestNext(2.seconds) shouldBe data1

      var next = probe.requestNext(3.seconds)
      while (next == ResilientHlsSource.MPEGTS_NULL_PACKET) {
        next = probe.requestNext(3.seconds)
      }
      val _ = next shouldBe (customPrefix ++ data2)
      val _ = attempts.get() shouldBe 2
      probe.cancel()
    }

    "retune when a tune produces no data within tuneTimeout" in {
      val attempts = new AtomicInteger(0)
      val data = ByteString("data-after-tune-timeout")
      val customPrefix = ByteString("prefix-")

      val factory = () => {
        val a = attempts.incrementAndGet()
        if (a == 1) {
          Source.never
        } else {
          Source.single(data)
        }
      }

      val wrappedSource = ResilientHlsSource(
        factory
      , "test-tune-timeout"
      , stallTimeout = 200.millis
      , tuneTimeout = 400.millis
      , retryDelay = 100.millis
      , recoveryTimeout = 5.seconds
      , resumePrefixSupplier = () => Some(customPrefix)
      )

      val probe = wrappedSource.runWith(TestSink[ByteString]())
      val _ = probe.ensureSubscription()

      var next = probe.requestNext(3.seconds)
      while (next == ResilientHlsSource.MPEGTS_NULL_PACKET) {
        next = probe.requestNext(3.seconds)
      }
      val _ = next shouldBe (customPrefix ++ data)
      val _ = attempts.get() shouldBe 2
      probe.cancel()
    }

    "not stall-fail a slow first element that arrives before tuneTimeout" in {
      val attempts = new AtomicInteger(0)
      val data = ByteString("slow-first-data")

      val factory = () => {
        val _ = attempts.incrementAndGet()
        Source.future(
          org.apache.pekko.pattern.after(
            300.millis
          , system.classicSystem.scheduler
          )(scala.concurrent.Future.successful(data))(system.executionContext)
        )
      }

      val wrappedSource = ResilientHlsSource(
        factory
      , "test-slow-first-element"
      , stallTimeout = 150.millis
      , tuneTimeout = 800.millis
      , retryDelay = 100.millis
      , recoveryTimeout = 5.seconds
      )

      val probe = wrappedSource.runWith(TestSink[ByteString]())
      val _ = probe.ensureSubscription()

      var next = probe.requestNext(3.seconds)
      while (next == ResilientHlsSource.MPEGTS_NULL_PACKET) {
        next = probe.requestNext(3.seconds)
      }
      val _ = next shouldBe data
      val _ = attempts.get() shouldBe 1
      probe.cancel()
    }

    "pass through a successful stream without modification" in {
      val testData = ByteString("real-data")
      val factory = () => {
        Source.single(testData)
      }

      val wrappedSource = ResilientHlsSource(factory, "test-stream", recoveryTimeout = 30.seconds)
      val result = wrappedSource.runWith(Sink.head).futureValue

      result shouldBe testData
    }

    "emit null keepalive when factory always fails" in {
      val factory = () => Source.failed(new RuntimeException("always fails"))
      val wrappedSource = ResilientHlsSource(
        factory
      , "test-null-keepalive"
      , recoveryTimeout = 30.seconds
      , retryDelay = 1.second
      )
      // While the inner source is down, keepAlive should emit MPEG-TS null packets.
      val probe = wrappedSource.runWith(TestSink[ByteString]())
      val _ = probe.ensureSubscription()
      val first = probe.requestNext(2.seconds)
      val _ = first shouldBe ResilientHlsSource.MPEGTS_NULL_PACKET
      probe.cancel()
    }

    "fail stream terminally after recovery timeout with no real data" in {
      val factory = () => Source.failed(new RuntimeException("always fails"))
      val wrappedSource = ResilientHlsSource(
        factory
      , "test-timeout"
      , recoveryTimeout = 300.millis
      , retryDelay = 50.millis
      )
      val ex = wrappedSource.runWith(Sink.ignore).failed.futureValue
      val _ = ex shouldBe a[ResilientHlsSource.StallTimeoutException]
    }

    "stay alive when real data arrives faster than recovery timeout" in {
      val factory = () => Source.tick(80.millis, 80.millis, ByteString("x"))
      val wrappedSource = ResilientHlsSource(
        factory
      , "test-timer-reset"
      , recoveryTimeout = 400.millis
      , retryDelay = 1.second
      )
      val result = wrappedSource.takeWithin(600.millis).runWith(Sink.seq).futureValue
      result.size should be > 3
    }

    "fail stream terminally when only null keepalive follows initial real data" in {
      val sentReal = new AtomicBoolean(false)
      val factory = () =>
        if (sentReal.compareAndSet(false, true))
          Source.single(ByteString("once"))
        else
          Source.never
      val wrappedSource = ResilientHlsSource(
        factory
      , "test-null-no-reset"
      , recoveryTimeout = 300.millis
      , retryDelay = 50.millis
      )
      val ex = wrappedSource.runWith(Sink.ignore).failed.futureValue
      val _ = ex shouldBe a[ResilientHlsSource.StallTimeoutException]
    }

    "fail stream when too many micro-stalls occur within window" in {
      val watchdogFlow = ResilientHlsSource.StallWatchdog.flow(
        tuneTimeout = 5.seconds
      , stallTimeout = 5.seconds
      , streamName = "test-micro-stalls"
      , microStallGap = 20.millis
      , microStallWindow = 5.seconds
      , maxMicroStalls = 2
      )

      val source = Source(List(1, 2, 3, 4, 5, 6))
        .map { x =>
          Thread.sleep(50)
          ByteString(x.toString)
        }
        .via(watchdogFlow)

      val ex = source.runWith(Sink.ignore).failed.futureValue
      val _ = ex shouldBe a[ResilientHlsSource.StallTimeoutException]
    }
  }
}

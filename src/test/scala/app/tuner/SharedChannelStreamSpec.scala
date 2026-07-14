package app.tuner

import org.apache.pekko
import pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import pekko.stream.scaladsl.{BroadcastHub, Keep}
import pekko.stream.testkit.scaladsl.{TestSink, TestSource}
import pekko.util.ByteString
import org.junit.runner.RunWith
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatestplus.junit.JUnitRunner

import scala.concurrent.duration._

@RunWith(classOf[JUnitRunner])
class SharedChannelStreamSpec
  extends ScalaTestWithActorTestKit
  with AnyWordSpecLike
  with Matchers {

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
}

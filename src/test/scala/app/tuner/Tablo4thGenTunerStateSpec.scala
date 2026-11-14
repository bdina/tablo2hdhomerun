package app.tuner

import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.junit.runner.RunWith
import org.scalatest.concurrent.Eventually
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatestplus.junit.JUnitRunner

import java.util.concurrent.atomic.AtomicInteger

import scala.concurrent.{Await, Future}
import scala.concurrent.duration._

@RunWith(classOf[JUnitRunner])
class Tablo4thGenTunerStateSpec extends ScalaTestWithActorTestKit with AnyWordSpecLike with Matchers with Eventually {

  import Tablo4thGen.Lineup
  import Tablo4thGen.TunerState
  import TunerState.{Snapshot, TuneOrigin, TunedChannel, TunerSlot, TunerStateActor}

  private implicit val ec: scala.concurrent.ExecutionContext = system.executionContext

  "TunerState.isValidChannelPath" should {
    "accept device /guide/channels/<digits> paths and reject traversal or query paths" in {
      val _ = TunerState.isValidChannelPath("/guide/channels/4141") shouldBe true
      val _ = TunerState.isValidChannelPath(" /guide/channels/1 ") shouldBe true
      val _ = TunerState.isValidChannelPath("/guide/channels/S19604_009_01") shouldBe false
      val _ = TunerState.isValidChannelPath("/guide/channels/../server/info") shouldBe false
      val _ = TunerState.isValidChannelPath("/guide/channels/4141?lh=1") shouldBe false
      TunerState.isValidChannelPath(null) shouldBe false
    }
  }

  "TunerState.resolveSnapshot" should {
    "classify proxy-owned channels as Self and other active channels as External" in {
      Lineup.clearChannelLabels()
      val _ = Lineup.registerChannel(Lineup.ChannelLineup(
        identifier = "S20431_004_01"
      , name = "WBZDT1"
      , kind = "ota"
      , ota = Some(Lineup.OtaChannelInfo(4, 1, Some("WBZDT1"), Some("CBS"), None, None, Some(true)))
      , ott = None
      ))
      val _ = Lineup.registerChannel(Lineup.ChannelLineup(
        identifier = "S19604_009_01"
      , name = "WMUR"
      , kind = "ota"
      , ota = Some(Lineup.OtaChannelInfo(9, 1, Some("WMUR"), Some("ABC"), None, None, Some(true)))
      , ott = None
      ))

      val slots = Seq(
        TunerSlot(in_use = true, channel = Some("/guide/channels/4140"), recording = None, channel_identifier = Some("S20431_004_01"))
      , TunerSlot(in_use = true, channel = Some("/guide/channels/4141"), recording = None, channel_identifier = Some("S19604_009_01"))
      , TunerSlot(in_use = false, channel = None, recording = None, channel_identifier = None)
      )

      val fetchCalls = new AtomicInteger(0)
      val snap = Await.result(
        TunerState.resolveSnapshot(
          slots = slots
        , activeChannelStates = Map("S20431_004_01" -> "live")
        , fetchChannel = _ => {
            val _ = fetchCalls.incrementAndGet()
            Future.successful(None)
          }
        )
      , 3.seconds
      )

      val _ = fetchCalls.get() shouldBe 0
      val _ = snap.totalSlots shouldBe 3
      val _ = snap.inUseSlots shouldBe 2
      val _ = snap.selfCount shouldBe 1
      val _ = snap.externalCount shouldBe 1
      val _ = snap.tunedChannels.size shouldBe 2
      val _ = snap.tunedChannels(0) shouldBe TunedChannel(
        slotIndex = 0
      , channelId = "S20431_004_01"
      , channelPath = Some("/guide/channels/4140")
      , label = "CBS 4.1"
      , origin = TuneOrigin.Self("live")
      , recording = None
      )
      snap.tunedChannels(1) shouldBe TunedChannel(
        slotIndex = 1
      , channelId = "S19604_009_01"
      , channelPath = Some("/guide/channels/4141")
      , label = "ABC 9.1"
      , origin = TuneOrigin.External
      , recording = None
      )
    }

    "resolve missing Lineup metadata via fallback /guide/channels/<id> fetch and filter non-OTA channels" in {
      Lineup.clearChannelLabels()
      val slots = Seq(
        TunerSlot(in_use = true, channel = Some("/guide/channels/4141"), recording = None, channel_identifier = Some("S19604_009_01"))
      , TunerSlot(in_use = true, channel = Some("/guide/channels/9999"), recording = None, channel_identifier = None)
      )

      val fetchCalls = new AtomicInteger(0)
      val snap = Await.result(
        TunerState.resolveSnapshot(
          slots = slots
        , activeChannelStates = Map.empty
        , fetchChannel = {
            case "/guide/channels/4141" =>
              val _ = fetchCalls.incrementAndGet()
              Future.successful(Some(Lineup.DeviceChannelObject(
                object_id = 4141
              , path = "/guide/channels/4141"
              , channel = Lineup.DeviceChannelInfo(
                  call_sign = "WMUR"
                , name = Some("WMUR")
                , major = 9
                , minor = 1
                , network = Some("ABC")
                , channel_identifier = "S19604_009_01"
                , source = Some("ota")
                )
              )))
            case "/guide/channels/9999" =>
              val _ = fetchCalls.incrementAndGet()
              Future.successful(Some(Lineup.DeviceChannelObject(
                object_id = 9999
              , path = "/guide/channels/9999"
              , channel = Lineup.DeviceChannelInfo(
                  call_sign = "FAST1"
                , name = Some("FAST Channel")
                , major = 0
                , minor = 0
                , network = None
                , channel_identifier = "OTT_FAST_1"
                , source = Some("ott")
                )
              )))
            case _ =>
              Future.successful(None)
          }
        )
      , 3.seconds
      )

      val _ = fetchCalls.get() shouldBe 2
      val _ = Lineup.hasChannelLabel("S19604_009_01") shouldBe true
      val _ = snap.totalSlots shouldBe 2
      val _ = snap.inUseSlots shouldBe 2
      val _ = snap.tunedChannels.size shouldBe 1
      snap.tunedChannels.head shouldBe TunedChannel(
        slotIndex = 0
      , channelId = "S19604_009_01"
      , channelPath = Some("/guide/channels/4141")
      , label = "ABC 9.1"
      , origin = TuneOrigin.External
      , recording = None
      )
    }

    "ignore in_use cooldown slots without channel or channel_identifier while preserving inUseSlots" in {
      Lineup.clearChannelLabels()
      val slots = Seq(
        TunerSlot(in_use = true, channel = None, recording = None, channel_identifier = None)
      , TunerSlot(in_use = false, channel = None, recording = None, channel_identifier = None)
      )

      val snap = Await.result(
        TunerState.resolveSnapshot(slots, Map.empty, _ => Future.successful(None))
      , 3.seconds
      )

      val _ = snap.totalSlots shouldBe 2
      val _ = snap.inUseSlots shouldBe 1
      snap.tunedChannels shouldBe Seq.empty
    }
  }

  "TunerStateActor and startStreamWithPoller" should {
    "poll periodically, update state in actor, and stop on killSwitch shutdown" in {
      val actor = testKit.spawn(TunerStateActor())
      val stateProbe = testKit.createTestProbe[Snapshot]()
      val pollCount = new AtomicInteger(0)

      val ks = TunerState.startStreamWithPoller(
        tunerStateActor = actor
      , pollFn = () => {
          val n = pollCount.incrementAndGet()
          val origin = if (n % 2 == 1) TuneOrigin.Self("idle-grace") else TuneOrigin.External
          Future.successful(Snapshot(
            totalSlots = 8
          , inUseSlots = 1
          , tunedChannels = Seq(
              TunedChannel(0, "S19604_009_01", Some("/guide/channels/4141"), "ABC 9.1", origin, None)
            )
          ))
        }
      , pollInterval = 60.millis
      , initialDelay = 10.millis
      )(system)

      val _ = eventually(timeout(3.seconds), interval(30.millis)) {
        actor ! TunerStateActor.Request.GetState(stateProbe.ref)
        val snap = stateProbe.receiveMessage()
        val _ = snap.totalSlots shouldBe 8
        snap.tunedChannels.map(_.channelId) shouldBe Seq("S19604_009_01")
      }

      ks.shutdown()
      val countAtShutdown = pollCount.get()
      Thread.sleep(150)
      pollCount.get() should be <= (countAtShutdown + 1)
    }
  }
}

package app.tuner

import org.apache.pekko
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.scaladsl.adapter._
import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.http.scaladsl.model.{StatusCodes, Uri}
import org.apache.pekko.http.scaladsl.server.Route
import org.apache.pekko.http.scaladsl.testkit.ScalatestRouteTest
import org.junit.runner.RunWith
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.junit.JUnitRunner

import spray.json._

import app.AppContext
import app.config.AppConfig

import scala.concurrent.duration._
import org.apache.pekko.http.scaladsl.testkit.RouteTestTimeout

trait Tablo4thGenRouteSpecBase extends AnyFlatSpecLike with Matchers with ScalatestRouteTest {

  implicit def defaultTimeout: RouteTestTimeout =
    RouteTestTimeout(5.seconds)

  def typedSystem: pekko.actor.typed.ActorSystem[pekko.NotUsed] =
    system.toTyped.asInstanceOf[pekko.actor.typed.ActorSystem[pekko.NotUsed]]

  def createStub4thGenLineupActor(): org.apache.pekko.actor.typed.ActorRef[Tablo4thGen.Lineup.LineupActor.Request] = {
    val sampleChannel = JsObject(
      "GuideNumber" -> JsString("4.1"),
      "GuideName" -> JsString("WUSA-HD"),
      "URL" -> JsString("http://127.0.0.1:8080/channel/chan-1"),
      "HD" -> JsNumber(1),
      "type" -> JsString("antenna")
    )
    val behavior: Behavior[Tablo4thGen.Lineup.LineupActor.Request] = Behaviors.receiveMessage {
      case Tablo4thGen.Lineup.LineupActor.Request.Fetch(replyTo) =>
        replyTo ! Tablo4thGen.Lineup.LineupActor.Response.Fetch(Seq(sampleChannel), null)
        Behaviors.same
      case Tablo4thGen.Lineup.LineupActor.Request.Status(replyTo) =>
        replyTo ! Tablo4thGen.Lineup.LineupActor.Response.Status(0, 1, null)
        Behaviors.same
      case _ =>
        Behaviors.same
    }
    system.spawn(behavior, s"stub-lineup-4g-${java.util.UUID.randomUUID()}")
  }

  def createStubSessionManager(rejectNoTuners: Boolean = false): org.apache.pekko.actor.typed.ActorRef[Tablo4thGen.Channel.SessionManager.Request] = {
    val behavior: Behavior[Tablo4thGen.Channel.SessionManager.Request] = Behaviors.receiveMessage {
      case Tablo4thGen.Channel.SessionManager.Request.Acquire(channelId, _, replyTo) =>
        if (rejectNoTuners) {
          replyTo ! Tablo4thGen.Channel.SessionManager.Response.Rejected(Tablo4thGen.Channel.SessionManager.RejectReason.NoTuners)
        } else {
          replyTo ! Tablo4thGen.Channel.SessionManager.Response.Attached(pekko.stream.scaladsl.Source.empty)
        }
        Behaviors.same
      case Tablo4thGen.Channel.SessionManager.Request.GetSessionMeta(channelId, replyTo) =>
        val meta = Tablo4thGen.Channel.SessionManager.TabloSessionMeta(
          token = "test-token",
          expires = None,
          keepalive = None,
          playlistUrl = s"http://127.0.0.1:8080/test-upstream/$channelId/playlist.m3u8"
        )
        replyTo ! Some(meta)
        Behaviors.same
      case Tablo4thGen.Channel.SessionManager.Request.GetTunerStatuses(replyTo) =>
        val snap = app.stream.StreamMetrics.WindowSnapshot.compute(
          windowSec = 30.0
        , realPackets = 124500L
        , nullPackets = 0L
        , elementaryPackets = 120000L
        , errorPackets = 0L
        , videoFramesClean = 1800L
        , videoFramesTotal = 1800L
        , audioFramesClean = 900L
        , audioFramesTotal = 900L
        , keyframes = 30L
        , tei = 0L
        , ccErr = 0L
        , sliceDrops = 0L
        , ptsScrubs = 0L
        , syncLossBytes = 0L
        , ptsAdvanceTicks = 30L * 90000L
        , avSkewMs = 0L
        , hls = app.stream.StreamMetrics.HlsWindowStats()
        )
        val active = Seq(
          HDHomeRunStatus.ActiveTunerInfo(
            channelId = "chan-1"
          , vctNumber = "4.1"
          , vctName = "WUSA-HD"
          , snapshot = snap
          )
        )
        replyTo ! HDHomeRunStatus.buildTunerStatuses(2, active)
        Behaviors.same
      case _ =>
        Behaviors.same
    }
    system.spawn(behavior, s"stub-session-mgr-${java.util.UUID.randomUUID()}")
  }

  def stubAuthContext: Tablo4thGen.Auth.AuthContext =
    Tablo4thGen.Auth.AuthContext(
      accessToken = "token",
      lighthouseToken = "lhtoken",
      deviceKey = "devkey",
      hashKey = "hashkey",
      deviceUrl = Uri("http://127.0.0.1:8887"),
      profileId = "prof-1",
      serverId = "serv-1"
    )

  def routesWithManager(
    sessionManager: org.apache.pekko.actor.typed.ActorRef[Tablo4thGen.Channel.SessionManager.Request]
  ): Route =
    routesWithEnv(sessionManager, Map.empty)

  def routesWithEnv(
    sessionManager: org.apache.pekko.actor.typed.ActorRef[Tablo4thGen.Channel.SessionManager.Request]
  , envOverrides: Map[String, String]
  ): Route = {
    val config = AppConfig.load(envOverrides).config
    val discover = TabloLegacy.Response.Discover(
      friendlyName = "Tablo 4th Gen Proxy",
      localIp = config.proxy.ip,
      protocol = config.tablo.protocol,
      port = config.proxy.port,
      tunerCount = 2,
      deviceId = "12345678"
    )
    AppContext.initialize(config, discover)
    AppContext.initialize(typedSystem)
    val lineup = createStub4thGenLineupActor()
    Tablo4thGen.routes(lineup, sessionManager, stubAuthContext)(typedSystem)
  }

  def routes(rejectNoTuners: Boolean = false, envOverrides: Map[String, String] = Map.empty): Route = {
    val sessionManager = createStubSessionManager(rejectNoTuners)
    routesWithEnv(sessionManager, envOverrides)
  }
}

@RunWith(classOf[JUnitRunner])
class Tablo4thGenRouteSpec extends Tablo4thGenRouteSpecBase {

  "POST /lineup.post?scan=start" should "return 200 OK in 4th Gen mode" in {
    Post("/lineup.post?scan=start") ~> routes() ~> check {
      status shouldBe StatusCodes.OK
    }
  }

  "POST /lineup.post?scan=abort" should "return 200 OK in 4th Gen mode" in {
    Post("/lineup.post?scan=abort") ~> routes() ~> check {
      status shouldBe StatusCodes.OK
    }
  }

  "GET /lineup.m3u" should "return 200 OK and standard M3U playlist" in {
    Get("/lineup.m3u") ~> routes() ~> check {
      val _ = status shouldBe StatusCodes.OK
      val body = responseAs[String]
      val _ = body should include("#EXTM3U")
      val _ = body should include("#EXTINF:-1")
      val _ = body should include("WUSA-HD")
      body should include(".m3u8")
    }
  }

  "GET /channel/no-tuners" should "return 503 Service Unavailable when tuners are exhausted" in {
    Get("/channel/test-chan") ~> routes(rejectNoTuners = true) ~> check {
      status shouldBe StatusCodes.ServiceUnavailable
    }
  }

  "GET /channel/:id" should "return 200 OK with video/mp2t when warm" in {
    Get("/channel/test-chan") ~> routes() ~> check {
      val _ = status shouldBe StatusCodes.OK
      contentType.mediaType.subType shouldBe "mp2t"
    }
  }

  it should "return 200 OK with video/mp2t immediately via pre-roll keepalive when cold" in {
    val delayedBehavior: Behavior[Tablo4thGen.Channel.SessionManager.Request] = Behaviors.receive { (_, msg) =>
      msg match {
        case Tablo4thGen.Channel.SessionManager.Request.Acquire(_, _, replyTo) =>
          val _ = system.scheduler.scheduleOnce(500.millis, new Runnable {
            override def run(): Unit =
              replyTo ! Tablo4thGen.Channel.SessionManager.Response.Attached(org.apache.pekko.stream.scaladsl.Source.empty)
          })(system.dispatcher)
          Behaviors.same
        case _ =>
          Behaviors.same
      }
    }
    val delayedManager = system.spawn(delayedBehavior, s"stub-delayed-mgr-${java.util.UUID.randomUUID()}")
    Get("/channel/cold-chan") ~> routesWithManager(delayedManager) ~> check {
      val _ = status shouldBe StatusCodes.OK
    }
  }

  "GET /auto/v4.1" should "return 200 OK with video/mp2t when channel is found in lineup" in {
    Get("/auto/v4.1") ~> routes() ~> check {
      val _ = status shouldBe StatusCodes.OK
      contentType.mediaType.subType shouldBe "mp2t"
    }
  }

  "GET /auto/v99.9" should "return 404 Not Found when channel is not in lineup" in {
    Get("/auto/v99.9") ~> routes() ~> check {
      status shouldBe StatusCodes.NotFound
    }
  }

  "GET /auto/v4.1" should "return 503 Service Unavailable when tuners are exhausted" in {
    Get("/auto/v4.1") ~> routes(rejectNoTuners = true) ~> check {
      status shouldBe StatusCodes.ServiceUnavailable
    }
  }

  "GET /status.json" should "return 200 OK with SiliconDust HDHomeRun tuner status array" in {
    Get("/status.json") ~> routes() ~> check {
      val _ = status shouldBe StatusCodes.OK
      val arr = responseAs[String].parseJson.asInstanceOf[JsArray].elements
      val _ = arr.size shouldBe 2
      val t0 = arr(0).asJsObject.fields
      val _ = t0("Resource") shouldBe JsString("tuner0")
      val _ = t0("VctNumber") shouldBe JsString("4.1")
      val _ = t0("VctName") shouldBe JsString("WUSA-HD")
      val _ = t0("SignalStrengthPercent") shouldBe JsNumber(100)
      val _ = t0("SignalQualityPercent") shouldBe JsNumber(100)
      val _ = t0("SymbolQualityPercent") shouldBe JsNumber(100)
      val _ = t0.contains("NetworkRate") shouldBe true

      val t1 = arr(1).asJsObject.fields
      val _ = t1.keySet shouldBe Set("Resource")
      t1("Resource") shouldBe JsString("tuner1")
    }
  }

  "GET /channel/:id/segment/:url" should "return 403 Forbidden for external target hosts" in {
    val encodedEvil = java.net.URLEncoder.encode("http://evil.example.com/seg.ts", "UTF-8")
    Get(s"/channel/chan-1/segment/$encodedEvil") ~> routes() ~> check {
      status shouldBe StatusCodes.Forbidden
    }
  }

  "Tablo4thGen.Channel.isAllowedSegmentUri" should "allow Tablo device hosts over HTTP/HTTPS and reject others" in {
    val config = AppConfig.load(Map("TABLO_IP" -> "192.168.1.50")).config
    AppContext.initialize(config, TabloLegacy.Response.Discover("Tablo 4th Gen Proxy", config.proxy.ip, config.tablo.protocol, config.proxy.port))
    val auth = stubAuthContext
    val _ = Tablo4thGen.Channel.isAllowedSegmentUri("http://127.0.0.1:8887/stream/seg0.ts", Some(auth)) shouldBe true
    val _ = Tablo4thGen.Channel.isAllowedSegmentUri("https://192.168.1.50:8887/stream/seg0.ts", Some(auth)) shouldBe true
    val _ = Tablo4thGen.Channel.isAllowedSegmentUri("http://evil.example.com/seg0.ts", Some(auth)) shouldBe false
    Tablo4thGen.Channel.isAllowedSegmentUri("file:///etc/passwd", Some(auth)) shouldBe false
  }

  "ENABLE_HLS_ENDPOINT=false" should "disable HLS and M3U routes with 404 Not Found" in {
    val r = routes(envOverrides = Map("ENABLE_HLS_ENDPOINT" -> "false"))
    Get("/lineup.m3u") ~> r ~> check {
      val _ = status shouldBe StatusCodes.NotFound
    }
    Get("/channel/chan-1.m3u8") ~> r ~> check {
      val _ = status shouldBe StatusCodes.NotFound
    }
    Get("/hls/chan-1/stream.m3u8") ~> r ~> check {
      val _ = status shouldBe StatusCodes.NotFound
    }
    val encodedLocal = java.net.URLEncoder.encode("http://127.0.0.1:8887/seg.ts", "UTF-8")
    Get(s"/channel/chan-1/segment/$encodedLocal") ~> r ~> check {
      val _ = status shouldBe StatusCodes.NotFound
    }
    Get("/channel/chan-1") ~> r ~> check {
      status shouldBe StatusCodes.OK
    }
  }
}

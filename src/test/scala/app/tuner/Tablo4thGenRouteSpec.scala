package app.tuner

import org.apache.pekko
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.scaladsl.adapter._
import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.http.scaladsl.model.{StatusCode, StatusCodes, Uri}
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

  given defaultTimeout: RouteTestTimeout =
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

  def createStubSessionManagerWithReject(
    rejection: Option[Tablo4thGen.Channel.SessionManager.RejectReason] = None
  , emptySessionMeta: Boolean = false
  ): org.apache.pekko.actor.typed.ActorRef[Tablo4thGen.Channel.SessionManager.Request] = {
    val behavior: Behavior[Tablo4thGen.Channel.SessionManager.Request] = Behaviors.receiveMessage {
      case Tablo4thGen.Channel.SessionManager.Request.Acquire(_, _, replyTo) =>
        rejection match {
          case Some(reason) =>
            replyTo ! Tablo4thGen.Channel.SessionManager.Response.Rejected(reason)
          case None =>
            replyTo ! Tablo4thGen.Channel.SessionManager.Response.Attached(pekko.stream.scaladsl.Source.empty)
        }
        Behaviors.same
      case Tablo4thGen.Channel.SessionManager.Request.GetSessionMeta(channelId, replyTo) =>
        if (emptySessionMeta || rejection.isDefined) {
          replyTo ! None
        } else {
          val meta = Tablo4thGen.Channel.SessionManager.TabloSessionMeta(
            token = "test-token",
            expires = None,
            keepalive = None,
            playlistUrl = s"http://127.0.0.1:8080/test-upstream/$channelId/playlist.m3u8"
          )
          replyTo ! Some(meta)
        }
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

  def createStubSessionManager(rejectNoTuners: Boolean = false): org.apache.pekko.actor.typed.ActorRef[Tablo4thGen.Channel.SessionManager.Request] =
    createStubSessionManagerWithReject(
      if (rejectNoTuners) Some(Tablo4thGen.Channel.SessionManager.RejectReason.NoTuners) else None
    )

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
    Tablo4thGen.routes(lineup, sessionManager, stubAuthContext)(using typedSystem)
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

  "GET /channel/no-tuners" should "return 503 Service Unavailable with X-HDHomeRun-Error 805 when tuners are exhausted" in {
    Get("/channel/test-chan") ~> routes(rejectNoTuners = true) ~> check {
      val _ = status shouldBe StatusCodes.ServiceUnavailable
      header("X-HDHomeRun-Error").map(_.value()) shouldBe Some("805 All Tuners In Use")
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
          })(using system.dispatcher)
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

  "GET /auto/v99.9" should "return 404 Not Found with X-HDHomeRun-Error 801 when channel is not in lineup" in {
    Get("/auto/v99.9") ~> routes() ~> check {
      val _ = status shouldBe StatusCodes.NotFound
      header("X-HDHomeRun-Error").map(_.value()) shouldBe Some("801 Unknown Channel")
    }
  }

  "GET /auto/v4.1" should "return 503 Service Unavailable with X-HDHomeRun-Error 805 when tuners are exhausted" in {
    Get("/auto/v4.1") ~> routes(rejectNoTuners = true) ~> check {
      val _ = status shouldBe StatusCodes.ServiceUnavailable
      header("X-HDHomeRun-Error").map(_.value()) shouldBe Some("805 All Tuners In Use")
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

  "Channel stream rejection mappings" should "emit standard X-HDHomeRun-Error headers for 4th gen errors" in {
    val cases: Seq[(Tablo4thGen.Channel.SessionManager.RejectReason, StatusCode, String)] = Seq(
      (Tablo4thGen.Channel.SessionManager.RejectReason.Failed(Tablo4thGen.Error.NoSignalLock("no_signal_lock")), StatusCodes.ServiceUnavailable, "806 Tune Failed")
    , (Tablo4thGen.Channel.SessionManager.RejectReason.Failed(Tablo4thGen.Error.NoAvailableTuners), StatusCodes.ServiceUnavailable, "805 All Tuners In Use")
    , (Tablo4thGen.Channel.SessionManager.RejectReason.Failed(Tablo4thGen.Error.NoVideoData("no playlist")), StatusCodes.ServiceUnavailable, "807 No Video Data")
    , (Tablo4thGen.Channel.SessionManager.RejectReason.Failed(app.stream.HlsBackend.HlsError.SessionEnded), StatusCodes.ServiceUnavailable, "807 No Video Data")
    , (Tablo4thGen.Channel.SessionManager.RejectReason.Failed(Tablo4thGen.Error.ChannelNotFound("missing")), StatusCodes.NotFound, "801 Unknown Channel")
    , (Tablo4thGen.Channel.SessionManager.RejectReason.Failed(Tablo4thGen.Error.SystemBusy("busy")), StatusCodes.ServiceUnavailable, "803 System Busy")
    )

    cases.foreach { case (reason, expectedStatus, expectedHdhr) =>
      val mgr = createStubSessionManagerWithReject(Some(reason))
      val r = routesWithEnv(mgr, Map("STREAM_PRE_ROLL_KEEP_ALIVE" -> "false"))
      val _ = Get("/channel/chan-1") ~> r ~> check {
        val _ = status shouldBe expectedStatus
        header("X-HDHomeRun-Error").map(_.value()) shouldBe Some(expectedHdhr)
      }
      val _ = Get("/hls/chan-1/stream.m3u8") ~> r ~> check {
        val _ = status shouldBe expectedStatus
        val _ = header("X-HDHomeRun-Error").map(_.value()) shouldBe Some(expectedHdhr)
        header("Access-Control-Allow-Origin").map(_.value()) shouldBe Some("*")
      }
      ()
    }

    val noMetaMgr = createStubSessionManagerWithReject(None, emptySessionMeta = true)
    Get("/channel/chan-1.m3u8") ~> routesWithManager(noMetaMgr) ~> check {
      val _ = status shouldBe StatusCodes.ServiceUnavailable
      header("X-HDHomeRun-Error").map(_.value()) shouldBe Some("803 System Busy")
    }
  }

  it should "return 503 X-HDHomeRun-Error 803 when lineup resolution fails or lineup actor is absent" in {
    val silentLineup = system.spawn(Behaviors.ignore[Tablo4thGen.Lineup.LineupActor.Request], s"silent-lineup-${java.util.UUID.randomUUID()}")
    val mgr = createStubSessionManager()
    val config = AppConfig.load(Map.empty).config
    val discover = TabloLegacy.Response.Discover("Tablo 4th Gen Proxy", config.proxy.ip, config.tablo.protocol, config.proxy.port, 2, "12345678")
    AppContext.initialize(config, discover)
    AppContext.initialize(typedSystem)
    val routeWithTimeoutLineup = Tablo4thGen.Channel.route(mgr, Some(silentLineup), Some(stubAuthContext))(using typedSystem)
    val _ = Get("/auto/v4.1") ~> routeWithTimeoutLineup ~> check {
      val _ = status shouldBe StatusCodes.ServiceUnavailable
      header("X-HDHomeRun-Error").map(_.value()) shouldBe Some("803 System Busy")
    }
    val routeWithoutLineup = Tablo4thGen.Channel.route(mgr, None, Some(stubAuthContext))(using typedSystem)
    val _ = Get("/auto/v4.1") ~> routeWithoutLineup ~> check {
      val _ = status shouldBe StatusCodes.ServiceUnavailable
      header("X-HDHomeRun-Error").map(_.value()) shouldBe Some("803 System Busy")
    }
    succeed
  }

  it should "return 503 X-HDHomeRun-Error 807 when upstream HLS playlist returns non-2xx" in {
    val server = new java.net.ServerSocket(0, 4, java.net.InetAddress.getByName("127.0.0.1"))
    val thread = new Thread(() => {
      try {
        val client = server.accept()
        try {
          val reader = new java.io.BufferedReader(new java.io.InputStreamReader(client.getInputStream, "US-ASCII"))
          var line = reader.readLine()
          while (line != null && line.nonEmpty) {
            line = reader.readLine()
          }
          val resp = "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
          client.getOutputStream.write(resp.getBytes("US-ASCII"))
          client.getOutputStream.flush()
        } finally {
          client.close()
        }
      } catch {
        case _: Exception => ()
      }
    }, "stub-hls-playlist-server")
    thread.setDaemon(true)
    thread.start()

    try {
      val port = server.getLocalPort
      val upstreamFailMgr = system.spawn(Behaviors.receiveMessage[Tablo4thGen.Channel.SessionManager.Request] {
        case Tablo4thGen.Channel.SessionManager.Request.GetSessionMeta(_, replyTo) =>
          val meta = Tablo4thGen.Channel.SessionManager.TabloSessionMeta(
            token = "tok-upstream"
          , expires = None
          , keepalive = None
          , playlistUrl = s"http://127.0.0.1:$port/pl.m3u8"
          )
          replyTo ! Some(meta)
          Behaviors.same
        case _ =>
          Behaviors.same
      }, s"stub-upstream-fail-${java.util.UUID.randomUUID()}")
      Get("/hls/chan-1/stream.m3u8") ~> routesWithManager(upstreamFailMgr) ~> check {
        val _ = status shouldBe StatusCodes.ServiceUnavailable
        val _ = header("X-HDHomeRun-Error").map(_.value()) shouldBe Some("807 No Video Data")
        header("Access-Control-Allow-Origin").map(_.value()) shouldBe Some("*")
      }
    } finally {
      server.close()
      thread.join(2000)
    }
  }
}

package app

import org.apache.pekko
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.scaladsl.adapter._
import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.http.scaladsl.model.{StatusCode, StatusCodes}
import org.apache.pekko.http.scaladsl.server.Route
import org.apache.pekko.http.scaladsl.testkit.{RouteTestTimeout, ScalatestRouteTest}
import org.junit.runner.RunWith
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.junit.JUnitRunner

import org.apache.pekko.http.scaladsl.server.RouteConcatenation._
import spray.json._

import scala.concurrent.duration._

import app.config.AppConfig
import app.tuner.HDHomeRunError
import app.tuner.TabloLegacy._
import app.tuner.TabloLegacy.Response.Discover

trait LegacyRouteSpecBase extends AnyFlatSpecLike with Matchers with ScalatestRouteTest {

  implicit def defaultTimeout: RouteTestTimeout =
    RouteTestTimeout(5.seconds)

  def typedSystem: pekko.actor.typed.ActorSystem[pekko.NotUsed] =
    system.toTyped.asInstanceOf[pekko.actor.typed.ActorSystem[pekko.NotUsed]]

  def createStubLineupActor(): org.apache.pekko.actor.typed.ActorRef[Lineup.LineupActor.Request] = {
    val sampleChannel = JsObject(
      "GuideNumber" -> JsString("4.1"),
      "GuideName" -> JsString("WUSA-HD"),
      "URL" -> JsString("http://127.0.0.1:8080/channel/101"),
      "type" -> JsString("antenna")
    )
    val behavior: Behavior[Lineup.LineupActor.Request] = Behaviors.receiveMessage {
      case Lineup.LineupActor.Request.Fetch(replyTo) =>
        replyTo ! Lineup.LineupActor.Response.Fetch(Seq(sampleChannel), null)
        Behaviors.same
      case Lineup.LineupActor.Request.Status(replyTo) =>
        replyTo ! Lineup.LineupActor.Response.Status(0, 1, null)
        Behaviors.same
      case _ =>
        Behaviors.same
    }
    system.spawn(behavior, s"stub-lineup-${java.util.UUID.randomUUID()}")
  }

  def legacyRoutes: Route = legacyRoutesWithEnv(Map.empty)

  def legacyRoutesWithEnv(envOverrides: Map[String, String]): Route = {
    val config = AppConfig.load(envOverrides).config
    val discover = Discover(
      friendlyName = "Tablo Legacy Gen Proxy",
      localIp = config.proxy.ip,
      protocol = config.tablo.protocol,
      port = config.proxy.port
    )
    AppContext.initialize(config, discover)
    AppContext.initialize(typedSystem)
    val stubLineup = createStubLineupActor()
    Response.Discover.route ~ Lineup.route(stubLineup) ~ Channel.route(stubLineup) ~ Favicon.route
  }
}

@RunWith(classOf[JUnitRunner])
class LegacyRouteSpec extends LegacyRouteSpecBase {

  "GET /discover.json" should "return 200 and JSON with FriendlyName and BaseURL" in {
    Get("/discover.json") ~> legacyRoutes ~> check {
      val _ = status shouldBe StatusCodes.OK
      val body = responseAs[String]
      val _ = body should include("FriendlyName")
      val _ = body should include("BaseURL")
      val json = body.parseJson.asJsObject
      json.fields.get("FriendlyName").isDefined shouldBe true
    }
  }

  "GET /lineup.json" should "return 200 and JSON array" in {
    Get("/lineup.json") ~> legacyRoutes ~> check {
      val _ = status shouldBe StatusCodes.OK
      val body = responseAs[String]
      body.parseJson match {
        case JsArray(_) =>
        case _ => fail("expected JSON array")
      }
    }
  }

  "GET /lineup_status.json" should "return 200 and ScanInProgress/ScanPossible" in {
    Get("/lineup_status.json") ~> legacyRoutes ~> check {
      val _ = status shouldBe StatusCodes.OK
      val body = responseAs[String]
      val _ = body should include("ScanInProgress")
      body should include("ScanPossible")
    }
  }

  "GET /favicon.ico" should "return 200" in {
    Get("/favicon.ico") ~> legacyRoutes ~> check {
      status shouldBe StatusCodes.OK
    }
  }

  "POST /lineup.post?scan=start" should "return 200 OK" in {
    Post("/lineup.post?scan=start") ~> legacyRoutes ~> check {
      status shouldBe StatusCodes.OK
    }
  }

  "POST /lineup.post?scan=abort" should "return 200 OK" in {
    Post("/lineup.post?scan=abort") ~> legacyRoutes ~> check {
      status shouldBe StatusCodes.OK
    }
  }

  "GET /status.json" should "return 200 OK and tuner array in Legacy mode" in {
    Channel.clearActiveStreams()
    Get("/status.json") ~> legacyRoutes ~> check {
      val _ = status shouldBe StatusCodes.OK
      val arr = responseAs[String].parseJson.asInstanceOf[JsArray].elements
      val _ = arr.size shouldBe 2
      arr(0).asJsObject.fields("Resource") shouldBe JsString("tuner0")
    }
  }

  "GET /lineup.m3u" should "return 200 OK with channel URL in Legacy mode" in {
    Get("/lineup.m3u") ~> legacyRoutes ~> check {
      val _ = status shouldBe StatusCodes.OK
      val body = responseAs[String]
      val _ = body should include("#EXTM3U")
      val _ = body should include("WUSA-HD")
      val _ = body should include("http://127.0.0.1:8080/channel/101")
      body should not include ".m3u8"
    }
  }

  it should "return 404 Not Found when ENABLE_HLS_ENDPOINT=false in Legacy mode" in {
    Get("/lineup.m3u") ~> legacyRoutesWithEnv(Map("ENABLE_HLS_ENDPOINT" -> "false")) ~> check {
      status shouldBe StatusCodes.NotFound
    }
  }

  "GET /auto/v99.9" should "return 404 Not Found with X-HDHomeRun-Error 801 in Legacy mode" in {
    Get("/auto/v99.9") ~> legacyRoutes ~> check {
      val _ = status shouldBe StatusCodes.NotFound
      header("X-HDHomeRun-Error").map(_.value()) shouldBe Some("801 Unknown Channel")
    }
  }

  "GET /auto/v4.1" should "return 503 with X-HDHomeRun-Error 803 when Legacy lineup fails or is missing" in {
    val silentLineup = system.spawn(Behaviors.ignore[Lineup.LineupActor.Request], s"legacy-silent-${java.util.UUID.randomUUID()}")
    val config = AppConfig.load(Map.empty).config
    val discover = Discover("Tablo Legacy Gen Proxy", config.proxy.ip, config.tablo.protocol, config.proxy.port)
    AppContext.initialize(config, discover)
    AppContext.initialize(typedSystem)
    val _ = Get("/auto/v4.1") ~> Channel.route(Some(silentLineup)) ~> check {
      val _ = status shouldBe StatusCodes.ServiceUnavailable
      header("X-HDHomeRun-Error").map(_.value()) shouldBe Some("803 System Busy")
    }
    Get("/auto/v4.1") ~> Channel.route(None) ~> check {
      val _ = status shouldBe StatusCodes.ServiceUnavailable
      header("X-HDHomeRun-Error").map(_.value()) shouldBe Some("803 System Busy")
    }
  }

  "TabloLegacy.Channel response classifiers" should "map tuner and watch HTTP responses cleanly" in {
    val _ = Channel.classifyTunersResponse(StatusCodes.OK, """[{"in_use":false}]""").map(_.size) shouldBe Right(1)
    val _ = Channel.classifyTunersResponse(StatusCodes.ServiceUnavailable, """{"error":"busy"}""")
      .left.map(HDHomeRunError.fromThrowable) shouldBe Left(HDHomeRunError.SystemBusy)

    val validWatch = """{"token":"tok1","expires":"2026-01-01T00:00:00Z","keepalive":120,"playlist_url":"http://127.0.0.1/pl.m3u8","video_details":{"width":1920,"height":1080}}"""
    val _ = Channel.classifyWatchResponse(StatusCodes.OK, validWatch).map(_.token) shouldBe Right("tok1")

    val blankPlaylist = """{"token":"tok1","expires":"2026-01-01T00:00:00Z","keepalive":120,"playlist_url":"","video_details":{"width":1920,"height":1080}}"""
    val _ = Channel.classifyWatchResponse(StatusCodes.OK, blankPlaylist)
      .left.map(HDHomeRunError.fromThrowable) shouldBe Left(HDHomeRunError.NoVideoData)

    val _ = Channel.classifyWatchResponse(StatusCodes.NotFound, """{"error":"not_found","message":"missing"}""")
      .left.map(HDHomeRunError.fromThrowable) shouldBe Left(HDHomeRunError.UnknownChannel)

    val _ = Channel.classifyWatchResponse(StatusCodes.Conflict, """{"error":"no_available_tuners"}""")
      .left.map(HDHomeRunError.fromThrowable) shouldBe Left(HDHomeRunError.AllTunersInUse)

    val _ = Channel.classifyWatchResponse(StatusCodes.ServiceUnavailable, """{"error":"weak signal"}""")
      .left.map(HDHomeRunError.fromThrowable) shouldBe Left(HDHomeRunError.TuneFailed)

    val _ = Channel.classifyWatchResponse(StatusCodes.InternalServerError, """{"error":"internal_error"}""")
      .left.map(HDHomeRunError.fromThrowable) shouldBe Left(HDHomeRunError.TuneFailed)

    Channel.classifyWatchResponse(StatusCodes.ServiceUnavailable, """{"error":"system_busy"}""")
      .left.map(HDHomeRunError.fromThrowable) shouldBe Left(HDHomeRunError.SystemBusy)
  }

  "Legacy GET /channel/:id" should "emit SiliconDust X-HDHomeRun-Error headers for upstream Tablo errors" in {
    def withStubTablo(handler: String => (String, String))(fn: Int => Unit): Unit = {
      val server = new java.net.ServerSocket(0, 8, java.net.InetAddress.getByName("127.0.0.1"))
      @volatile var running = true
      val thread = new Thread(() => {
        while (running) {
          try {
            val client = server.accept()
            try {
              val in = client.getInputStream
              val reader = new java.io.BufferedReader(new java.io.InputStreamReader(in, "US-ASCII"))
              val requestLine = Option(reader.readLine()).getOrElse("")
              var line = reader.readLine()
              var contentLen = 0
              while (line != null && line.nonEmpty) {
                val lower = line.toLowerCase
                if (lower.startsWith("content-length:")) {
                  contentLen = line.substring("content-length:".length).trim.toIntOption.getOrElse(0)
                }
                line = reader.readLine()
              }
              if (contentLen > 0) {
                val buf = new Array[Char](contentLen)
                var readTotal = 0
                while (readTotal < contentLen) {
                  val n = reader.read(buf, readTotal, contentLen - readTotal)
                  if (n < 0) readTotal = contentLen else readTotal += n
                }
              }
              val (statusLine, body) = handler(requestLine)
              val bodyBytes = body.getBytes("UTF-8")
              val rawResp = s"$statusLine\r\nContent-Type: application/json\r\nContent-Length: ${bodyBytes.length}\r\nConnection: close\r\n\r\n"
              val out = client.getOutputStream
              out.write(rawResp.getBytes("US-ASCII"))
              out.write(bodyBytes)
              out.flush()
            } finally {
              client.close()
            }
          } catch {
            case _: Exception => ()
          }
        }
      }, "legacy-stub-tablo")
      thread.setDaemon(true)
      thread.start()
      try fn(server.getLocalPort)
      finally {
        running = false
        server.close()
        thread.join(2000)
      }
    }

    val scenarios: Seq[(String, String => (String, String), StatusCode, String)] = Seq(
      ("all tuners busy in /server/tuners", (req: String) =>
        if (req.contains("/server/tuners")) ("HTTP/1.1 200 OK", """[{"in_use":true},{"in_use":true}]""")
        else ("HTTP/1.1 200 OK", "{}")
      , StatusCodes.ServiceUnavailable, "805 All Tuners In Use")
    , ("watch 409 conflict", (req: String) =>
        if (req.contains("/server/tuners")) ("HTTP/1.1 200 OK", """[{"in_use":false}]""")
        else ("HTTP/1.1 409 Conflict", """{"error":"no_available_tuners","message":"No tuners"}""")
      , StatusCodes.ServiceUnavailable, "805 All Tuners In Use")
    , ("watch 404 unknown channel", (req: String) =>
        if (req.contains("/server/tuners")) ("HTTP/1.1 200 OK", """[{"in_use":false}]""")
        else ("HTTP/1.1 404 Not Found", """{"error":"not_found","message":"Unknown"}""")
      , StatusCodes.NotFound, "801 Unknown Channel")
    , ("watch 503 tune failed", (req: String) =>
        if (req.contains("/server/tuners")) ("HTTP/1.1 200 OK", """[{"in_use":false}]""")
        else ("HTTP/1.1 503 Service Unavailable", """{"error":"playback_failed","message":"no_signal_lock"}""")
      , StatusCodes.ServiceUnavailable, "806 Tune Failed")
    , ("watch 200 blank playlist_url", (req: String) =>
        if (req.contains("/server/tuners")) ("HTTP/1.1 200 OK", """[{"in_use":false}]""")
        else ("HTTP/1.1 200 OK", """{"token":"tok1","expires":"2026-01-01T00:00:00Z","keepalive":120,"playlist_url":"","video_details":{"width":1920,"height":1080}}""")
      , StatusCodes.ServiceUnavailable, "807 No Video Data")
    )

    scenarios.foreach { case (_, handler, expectedStatus, expectedHdhr) =>
      withStubTablo(handler) { port =>
        val r = legacyRoutesWithEnv(Map("TABLO_IP" -> "127.0.0.1", "TABLO_PORT" -> port.toString))
        val _ = Get("/channel/101") ~> r ~> check {
          val _ = status shouldBe expectedStatus
          header("X-HDHomeRun-Error").map(_.value()) shouldBe Some(expectedHdhr)
        }
        ()
      }
    }
  }

  "FFMpegDelegate.buildCommand" should "preserve paths containing spaces as single arguments" in {
    val input = java.nio.file.Paths.get("/tmp/my recordings/show name.ts")
    val args = app.sys.FFMpegDelegate.buildCommand(input)
    val _ = args should contain("/tmp/my recordings/show name.ts")
    args should contain("/tmp/my recordings/show name.mp4")
  }

  "Tablo2HDHomeRun.appVersion" should "resolve project version from build resources instead of dev" in {
    val version = Tablo2HDHomeRun.appVersion
    val _ = version should not be empty
    version should not be "dev"
  }

  "Tablo2HDHomeRun.checkHealth" should "return true for 200 OK and false for non-200 or unreachable port" in {
    def withStubHttp(statusLine: String)(fn: Int => Unit): Unit = {
      val server = new java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))
      val thread = new Thread(() => {
        try {
          val client = server.accept()
          try {
            client.getOutputStream.write(s"$statusLine\r\nContent-Length: 2\r\n\r\nOK".getBytes("US-ASCII"))
            client.getOutputStream.flush()
          } finally {
            client.close()
          }
        } catch {
          case _: Exception => ()
        }
      }, "healthcheck-test-server")
      thread.setDaemon(true)
      thread.start()
      try fn(server.getLocalPort)
      finally {
        server.close()
        thread.join(2000)
      }
    }

    withStubHttp("HTTP/1.1 200 OK") { port =>
      val _ = Tablo2HDHomeRun.checkHealth("127.0.0.1", port, timeoutMs = 1000) shouldBe true
    }
    withStubHttp("HTTP/1.1 500 Internal Server Error") { port =>
      val _ = Tablo2HDHomeRun.checkHealth("127.0.0.1", port, timeoutMs = 1000) shouldBe false
    }
    val closedSocket = new java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))
    val closedPort = closedSocket.getLocalPort
    closedSocket.close()
    Tablo2HDHomeRun.checkHealth("127.0.0.1", closedPort, timeoutMs = 500) shouldBe false
  }
}

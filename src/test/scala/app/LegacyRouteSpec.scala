package app

import org.apache.pekko
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.scaladsl.adapter._
import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.apache.pekko.http.scaladsl.server.Route
import org.apache.pekko.http.scaladsl.testkit.ScalatestRouteTest
import org.junit.runner.RunWith
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.junit.JUnitRunner

import org.apache.pekko.http.scaladsl.server.RouteConcatenation._
import spray.json._

import app.config.AppConfig
import app.tuner.TabloLegacy._
import app.tuner.TabloLegacy.Response.Discover

trait LegacyRouteSpecBase extends AnyFlatSpecLike with Matchers with ScalatestRouteTest {

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

  "GET /auto/v99.9" should "return 404 Not Found in Legacy mode" in {
    Get("/auto/v99.9") ~> legacyRoutes ~> check {
      status shouldBe StatusCodes.NotFound
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
          case _: Throwable => ()
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

package app.tuner

import org.apache.pekko
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.scaladsl.adapter._
import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.http.scaladsl.model.{ContentTypes, HttpEntity, StatusCodes, Uri}
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

  implicit def defaultTimeout(implicit system: org.apache.pekko.actor.ActorSystem): RouteTestTimeout =
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
          val meta = Tablo4thGen.Channel.SessionManager.TabloSessionMeta(
            token = "test-token",
            expires = None,
            keepalive = None,
            playlistUrl = s"http://127.0.0.1:8080/test-upstream/$channelId/playlist.m3u8"
          )
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

  def routes(rejectNoTuners: Boolean = false): Route = {
    val config = AppConfig.load(Map.empty).config
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
    val sessionManager = createStubSessionManager(rejectNoTuners)
    Tablo4thGen.routes(lineup, sessionManager, stubAuthContext)(typedSystem)
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
      status shouldBe StatusCodes.OK
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
}

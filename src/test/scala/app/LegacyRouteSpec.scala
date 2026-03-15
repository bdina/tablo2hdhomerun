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

import app.tuner.TabloLegacy._

trait LegacyRouteSpecBase extends AnyFlatSpecLike with Matchers with ScalatestRouteTest {

  def typedSystem: pekko.actor.typed.ActorSystem[pekko.NotUsed] =
    system.toTyped.asInstanceOf[pekko.actor.typed.ActorSystem[pekko.NotUsed]]

  def createStubLineupActor(): org.apache.pekko.actor.typed.ActorRef[Lineup.LineupActor.Request] = {
    val behavior: Behavior[Lineup.LineupActor.Request] = Behaviors.receiveMessage {
      case Lineup.LineupActor.Request.Fetch(replyTo) =>
        replyTo ! Lineup.LineupActor.Response.Fetch(Seq.empty, null)
        Behaviors.same
      case Lineup.LineupActor.Request.Status(replyTo) =>
        replyTo ! Lineup.LineupActor.Response.Status(0, 1, null)
        Behaviors.same
      case _ =>
        Behaviors.same
    }
    system.spawn(behavior, s"stub-lineup-${java.util.UUID.randomUUID()}")
  }

  def legacyRoutes: Route = {
    AppContext.initialize(typedSystem)
    val stubLineup = createStubLineupActor()
    Response.Discover.route ~ Lineup.route(stubLineup) ~ Favicon.route
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
}

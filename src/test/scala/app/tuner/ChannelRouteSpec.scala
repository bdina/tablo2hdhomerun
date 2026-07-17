package app.tuner

import org.apache.pekko
import pekko.actor.typed.scaladsl.Behaviors
import pekko.actor.typed.scaladsl.adapter._
import pekko.actor.typed.{ActorRef, Behavior}
import pekko.http.scaladsl.model.StatusCodes
import pekko.http.scaladsl.server.Route
import pekko.http.scaladsl.testkit.ScalatestRouteTest
import pekko.stream.scaladsl.Source
import pekko.util.ByteString
import org.junit.runner.RunWith
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.junit.JUnitRunner

import app.tuner.SessionManager._

import scala.concurrent.duration._
import java.util.UUID

@RunWith(classOf[JUnitRunner])
class ChannelRouteSpec extends AnyFlatSpecLike with Matchers with ScalatestRouteTest {

  def typedSystem: pekko.actor.typed.ActorSystem[pekko.NotUsed] =
    system.toTyped.asInstanceOf[pekko.actor.typed.ActorSystem[pekko.NotUsed]]

  def createStubSessionManager(
    responseFactory: ChannelKey => AcquireResult
  , delay: FiniteDuration = 0.millis
  ): ActorRef[SessionManager.Command] = {
    val behavior: Behavior[SessionManager.Command] = Behaviors.receiveMessage {
      case Acquire(channel, replyTo) =>
        if (delay.length > 0) {
          import system.dispatcher
          val _ = system.scheduler.scheduleOnce(delay) {
            replyTo ! responseFactory(channel)
          }
        } else {
          replyTo ! responseFactory(channel)
        }
        Behaviors.same
      case _ =>
        Behaviors.same
    }
    system.spawn(behavior, s"stub-sm-${UUID.randomUUID()}")
  }

  def createGen4Route(sm: ActorRef[SessionManager.Command]): Route =
    Tablo4thGen.Channel.route(sm, Settings(askTimeout = 100.millis))(typedSystem)

  "GET /channel/{id} 4th gen" should "return 200 and streaming content on success" in {
    val sm = createStubSessionManager(_ => Attached(UUID.randomUUID(), Source.single(ByteString("data"))))
    val route = createGen4Route(sm)

    Get("/channel/test-ch") ~> route ~> check {
      val _ = status shouldBe StatusCodes.OK
      val _ = contentType.mediaType.mainType shouldBe "video"
      val _ = contentType.mediaType.subType shouldBe "mp2t"
      val chunks = responseAs[String]
      val _ = chunks shouldBe "data"
    }
  }

  it should "return 503 when no tuners are available" in {
    val sm = createStubSessionManager(_ => NoAvailableTuners)
    val route = createGen4Route(sm)

    Get("/channel/test-ch") ~> route ~> check {
      val _ = status shouldBe StatusCodes.ServiceUnavailable
      val _ = responseAs[String] shouldBe "No available tuners"
    }
  }

  it should "return 500 when acquire fails" in {
    val sm = createStubSessionManager(_ => AcquireFailed(new Exception("test failure")))
    val route = createGen4Route(sm)

    Get("/channel/test-ch") ~> route ~> check {
      val _ = status shouldBe StatusCodes.InternalServerError
      val _ = responseAs[String] shouldBe "Unable to stream channel"
    }
  }

  it should "return 500 on ask timeout" in {
    val sm = createStubSessionManager(_ => NoAvailableTuners, delay = 250.millis)
    val route = createGen4Route(sm)

    Get("/channel/test-ch") ~> route ~> check {
      val _ = status shouldBe StatusCodes.InternalServerError
      val _ = responseAs[String] shouldBe "Unable to stream channel"
    }
  }
}

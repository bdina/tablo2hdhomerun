package app

import org.apache.pekko

import pekko.actor.typed.scaladsl.Behaviors
import pekko.actor.typed.{ActorSystem, Behavior}
import pekko.http.scaladsl.Http
import pekko.http.scaladsl.model.{HttpResponse, StatusCodes}
import pekko.http.scaladsl.server.Directives.{complete, extractRequest, handleRejections}
import pekko.http.scaladsl.server.RouteConcatenation._
import pekko.http.scaladsl.server.{RejectionHandler, Route}

import java.net.InetAddress
import java.nio.file.Paths

import org.slf4j.LoggerFactory

import scala.io.StdIn
import scala.util.{Failure, Success, Try}

@main def tablo2hdhomerunApp(args: String*): Unit = {
  val daemon = args.contains("-d")
  Dependencies.verify()
  Tablo2HDHomeRun.start(daemon)
}

object AppContext {
  @volatile private var _system: ActorSystem[pekko.NotUsed] = scala.compiletime.uninitialized
  implicit def system: ActorSystem[pekko.NotUsed] = _system
  private[app] def initialize(s: ActorSystem[pekko.NotUsed]): Unit = { _system = s }
}

object Dependencies {
  val log = LoggerFactory.getLogger(this.getClass)

  import scala.sys.process._
  val devNull = ProcessLogger(_ => {}, _ => {})

  def verify() = {
    val ffmpegCheck = Try { "ffmpeg -version".!<(devNull) }.getOrElse(-1)
    if (ffmpegCheck != 0) {
      log.info("[dependencies] missing dependency -> ffmpeg (check installation)")
      System.exit(1)
    }
  }
}

object Tablo2HDHomeRun {
  val log = LoggerFactory.getLogger(this.getClass)

  val media = scala.sys.env.get("MEDIA_ROOT")

  def start(daemon: Boolean): Unit = {
    val system = ActorSystem(apply(daemon), "tablo2hdhomerun-system")
    AppContext.initialize(system)
  }

  def apply(daemon: Boolean): Behavior[pekko.NotUsed] = Behaviors.setup { context =>
    media.foreach { case root =>
      log.info(s"[apply] media root set -> $root")

      val monitor = context.spawn(app.sys.FsMonitor(), "monitor-actor", pekko.actor.typed.Props.empty)
      context.log.info(s"[apply] created monitor actor $monitor")

      implicit val timeout: pekko.util.Timeout = pekko.util.Timeout(3, java.util.concurrent.TimeUnit.SECONDS)

      val path = Paths.get(root)
      context.ask[app.sys.FsMonitor.Watch,app.sys.FsMonitor.Ack](monitor, ref => app.sys.FsMonitor.Watch(path=path,ext=Seq("ts,","mkv"),replyTo=ref)) {
        case Success(app.sys.FsMonitor.Ack(p,_)) =>
          context.log.info(s"[apply] received ACK($p)")
          pekko.NotUsed
        case Failure(ex) =>
          context.log.info(s"[apply] received FAILURE with ${ex.getMessage}")
          pekko.NotUsed
      }
    }

    log.info(s"[apply] TABLO_GEN = $TABLO_GEN")

    val routes = TABLO_GEN match {
      case "4thgen" =>
        log.info("[apply] initializing 4th generation Tablo support")
        implicit val sys: ActorSystem[?] = context.system
        val authContext = app.tuner.Tablo4thGen.Auth.initialize()
        val lineup = context.spawn(app.tuner.Tablo4thGen.Lineup.LineupActor(authContext), "lineup-actor-4thgen")
        app.tuner.Tablo4thGen.routes(lineup, authContext)
      case _ =>
        log.info("[apply] initializing legacy Tablo support")
        val lineup = context.spawn(Lineup.LineupActor(), "lineup-actor", pekko.actor.typed.Props.empty)
        Response.Discover.route ~ Lineup.route(lineup) ~ Channel.route ~ Guide.route ~ Favicon.route
    }

    startHttp(routes, daemon)

    Behaviors.empty
  }

  implicit def system: ActorSystem[pekko.NotUsed] = AppContext.system

  implicit def ec: scala.concurrent.ExecutionContext = system.executionContext


  val Response = app.tuner.TabloLegacy.Response
  val Lineup = app.tuner.TabloLegacy.Lineup
  val Guide = app.tuner.TabloLegacy.Guide
  val Channel = app.tuner.TabloLegacy.Channel
  val Favicon = app.tuner.TabloLegacy.Favicon

  val TABLO_IP = InetAddress.getByName(scala.sys.env.getOrElse("TABLO_IP","127.0.0.1"))
  val TABLO_PROTOCOL = "http"
  val TABLO_PORT = 8885

  val PROXY_IP = InetAddress.getByName(scala.sys.env.getOrElse("PROXY_IP","127.0.0.1"))
  val PROXY_PORT = 8080

  val TABLO_GEN = scala.sys.env.getOrElse("TABLO_GEN", "legacy")
  val TABLO_EMAIL = scala.sys.env.get("TABLO_EMAIL")
  val TABLO_PASSWORD = scala.sys.env.get("TABLO_PASSWORD")
  val TABLO_DEVICE_NAME = scala.sys.env.get("TABLO_DEVICE_NAME")

  import Response.Discover
  val discoverFriendlyName = TABLO_GEN match {
    case "4thgen" => "Tablo 4th Gen Proxy"
    case _ => "Tablo Legacy Gen Proxy"
  }
  val discover = Discover(friendlyName=discoverFriendlyName,localIp=PROXY_IP)

  lazy val HttpCtx = Http()
  def startHttp(routes: Route, daemon: Boolean): Unit = {
    val loggedRejectionHandler =
      RejectionHandler
        .newBuilder()
        .handleNotFound { // Handle the case where no route matched (empty rejections)
          extractRequest { request =>
            log.info(s"[NotFound] unknown path - $request")
            complete(HttpResponse(StatusCodes.NotFound, entity = "Resource not found"))
          }
        }
        .result() // Add other handlers for specific rejections if needed

    val bindingFuture =
      Http()
        .newServerAt(PROXY_IP.getHostAddress.toString, PROXY_PORT)
        .bind {
          handleRejections(loggedRejectionHandler) { routes }
        }

    val break = if (daemon) "CTRL-C" else "RETURN"
    val url = s"http://${PROXY_IP.getHostAddress}:${PROXY_PORT}"
    val modeText = if (TABLO_GEN == "4thgen") " (4th Gen mode)" else ""
    val message = s"Server now online$modeText. Please navigate to ${url}\nPress ${break} to stop..."

    println(message)
    if (!daemon) {
      StdIn.readLine() : Unit // let it run until user presses return
    } else {
      Monitor.ref.synchronized { Monitor.ref.wait() }
    }

    bindingFuture
      .flatMap(_.unbind()) // trigger unbinding from the port
      .onComplete(_ => system.terminate()) // and shutdown when done
  }
}


case class Monitor()
object Monitor {
  val ref = Monitor()
}

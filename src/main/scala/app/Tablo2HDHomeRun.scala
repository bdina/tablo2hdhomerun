package app

import org.apache.pekko

import pekko.actor.typed.scaladsl.Behaviors
import pekko.actor.typed.{ActorSystem, Behavior}
import pekko.http.scaladsl.Http
import pekko.http.scaladsl.model.{HttpResponse, StatusCodes}
import pekko.http.scaladsl.server.Directives.{complete, extractRequest, handleRejections}
import pekko.http.scaladsl.server.{RejectionHandler, Route}
import pekko.http.scaladsl.server.RouteConcatenation._

import java.nio.file.Paths

import org.slf4j.LoggerFactory

import scala.io.StdIn
import scala.util.{Failure, Success, Try}

import app.config.{AppConfig, TabloAuthEnv, TabloGen}
import app.sys.LogConfig
import app.tuner.TabloLegacy.Response.Discover

@main def tablo2hdhomerunApp(args: String*): Unit = {
  val loaded = AppConfig.load()
  LogConfig.configure(loaded.logging)
  val daemon = args.contains("-d")
  Dependencies.verify(loaded.config)
  Tablo2HDHomeRun.start(loaded.config, loaded.tabloAuth, daemon)
}

object AppContext {
  @volatile private var _config: AppConfig = scala.compiletime.uninitialized
  @volatile private var _discover: Discover = scala.compiletime.uninitialized
  @volatile private var _system: ActorSystem[pekko.NotUsed] = scala.compiletime.uninitialized

  def config: AppConfig = _config
  def discover: Discover = _discover
  implicit def system: ActorSystem[pekko.NotUsed] = _system

  private[app] def initialize(config: AppConfig, discover: Discover): Unit = {
    _config = config
    _discover = discover
  }

  private[app] def initialize(system: ActorSystem[pekko.NotUsed]): Unit = { _system = system }
}

object Dependencies {
  val log = LoggerFactory.getLogger(this.getClass)

  import scala.sys.process._
  val devNull = ProcessLogger(_ => {}, _ => {})

  def verify(config: AppConfig) = {
    if (!config.stream.backend.isHls) {
      val ffmpegCheck = Try { "ffmpeg -version".!<(devNull) }.getOrElse(-1)
      if (ffmpegCheck != 0) {
        log.error("[startup] missing dependency name=ffmpeg")
        System.exit(1)
      }
    }
  }
}

object Tablo2HDHomeRun {
  val log = LoggerFactory.getLogger(this.getClass)

  def start(config: AppConfig, tabloAuth: TabloAuthEnv, daemon: Boolean): Unit = {
    val discover = buildDiscover(config)
    AppContext.initialize(config, discover)
    val system = ActorSystem(apply(config, tabloAuth, daemon), "tablo2hdhomerun-system")
    AppContext.initialize(system)
  }

  private def buildDiscover(config: AppConfig): Discover =
    Discover(
      friendlyName = config.tablo.gen.discoverFriendlyName
    , localIp = config.proxy.ip
    , protocol = config.tablo.protocol
    , port = config.proxy.port
    )

  private def appVersion: String =
    Option(getClass.getPackage.getImplementationVersion)
      .orElse {
        Try {
          val source = getClass.getProtectionDomain.getCodeSource
          if (source == null) None
          else {
            val jar = new java.util.jar.JarFile(source.getLocation.getPath)
            try Option(jar.getManifest.getMainAttributes.getValue("App-Version"))
            finally jar.close()
          }
        }.toOption.flatten
      }
      .getOrElse("dev")

  def logStartupConfig(config: AppConfig): Unit =
    log.info(
      "[startup] ready version={} tabloGen={} tabloIp={} proxyIp={} proxyPort={} streamBackend={} mediaRoot={}"
    , appVersion
    , config.tablo.gen.envValue
    , config.tablo.ipHost
    , config.proxy.bindHost
    , config.proxy.bindPort
    , config.stream.backend.envValue
    , config.mediaRoot.getOrElse("none")
    )

  def apply(config: AppConfig, tabloAuth: TabloAuthEnv, daemon: Boolean): Behavior[pekko.NotUsed] = Behaviors.setup { context =>
    config.mediaRoot.foreach { case root =>
      val monitor = context.spawn(app.sys.FsMonitor(), "monitor-actor", pekko.actor.typed.Props.empty)
      context.log.debug("[scan] monitor spawned root={}", root)

      implicit val timeout: pekko.util.Timeout = pekko.util.Timeout(3, java.util.concurrent.TimeUnit.SECONDS)

      val path = Paths.get(root)
      context.ask[app.sys.FsMonitor.Watch,app.sys.FsMonitor.Ack](monitor, ref => app.sys.FsMonitor.Watch(path=path,ext=Seq("ts,","mkv"),replyTo=ref)) {
        case Success(app.sys.FsMonitor.Ack(p,_)) =>
          context.log.debug("[scan] monitor ack path={}", p)
          pekko.NotUsed
        case Failure(ex) =>
          log.warn("[scan] monitor failed root={}", root, ex)
          pekko.NotUsed
      }
    }

    logStartupConfig(config)

    val routes = config.tablo.gen match {
      case TabloGen.FourthGen =>
        implicit val sys: ActorSystem[?] = context.system
        val authContext = app.tuner.Tablo4thGen.Auth.initialize(tabloAuth)
        val lineup = context.spawn(app.tuner.Tablo4thGen.Lineup.LineupActor(authContext), "lineup-actor-4thgen")
        val sessionBackend = app.tuner.Tablo4thGen.Channel.SessionBackend(authContext)
        val runtimeFactory = app.tuner.SharedChannelStream.runtimeFactory { _ =>
          Some(
            app.tuner.SharedChannelStream.KeepaliveOps(
              sessionBackend.keepalive
            , sessionBackend.fetch
            )
          )
        }
        val sessionManager =
          context.spawn(
            app.tuner.SessionManager(sessionBackend, runtimeFactory)
          , "session-manager-4thgen"
          )
        app.tuner.Tablo4thGen.routes(lineup, authContext, sessionManager)
      case TabloGen.Legacy =>
        implicit val sys: ActorSystem[?] = context.system
        val lineup = context.spawn(Lineup.LineupActor(), "lineup-actor", pekko.actor.typed.Props.empty)
        val sessionBackend = Channel.SessionBackend()
        val runtimeFactory = app.tuner.SharedChannelStream.runtimeFactory(_ => None)
        val sessionManager =
          context.spawn(
            app.tuner.SessionManager(sessionBackend, runtimeFactory)
          , "session-manager-legacy"
          )
        Response.Discover.route ~
        Lineup.route(lineup) ~
        Channel.route(sessionManager) ~
        Guide.route ~
        Favicon.route
    }

    startHttp(config, routes, daemon)

    Behaviors.empty
  }

  implicit def system: ActorSystem[pekko.NotUsed] = AppContext.system

  implicit def ec: scala.concurrent.ExecutionContext = system.executionContext

  val Response = app.tuner.TabloLegacy.Response
  val Lineup = app.tuner.TabloLegacy.Lineup
  val Guide = app.tuner.TabloLegacy.Guide
  val Channel = app.tuner.TabloLegacy.Channel
  val Favicon = app.tuner.TabloLegacy.Favicon

  lazy val HttpCtx = Http()
  def startHttp(config: AppConfig, routes: Route, daemon: Boolean): Unit = {
    val loggedRejectionHandler =
      RejectionHandler
        .newBuilder()
        .handleNotFound { // Handle the case where no route matched (empty rejections)
          extractRequest { request =>
            log.warn("[http] not found method={} path={}", request.method.value, request.uri.path)
            complete(HttpResponse(StatusCodes.NotFound, entity = "Resource not found"))
          }
        }
        .result() // Add other handlers for specific rejections if needed

    val bindingFuture =
      Http()
        .newServerAt(config.proxy.bindHost, config.proxy.bindPort)
        .bind {
          handleRejections(loggedRejectionHandler) { routes }
        }

    val break = if (daemon) "CTRL-C" else "RETURN"
    val url = s"http://${config.proxy.bindHost}:${config.proxy.bindPort}"
    val modeText = config.tablo.gen.startupModeText
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
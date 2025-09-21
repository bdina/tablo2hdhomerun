package app

import org.apache.pekko

import pekko.actor.typed.scaladsl.{ AbstractBehavior, ActorContext, Behaviors }
import pekko.actor.typed.{ ActorRef, ActorSystem, Behavior }

import pekko.http.scaladsl.Http
import pekko.http.scaladsl.model._
import pekko.http.scaladsl.server.RejectionHandler
import pekko.http.scaladsl.server.Directives._

import pekko.stream._
import pekko.stream.scaladsl._

import java.io.File
import java.nio.file.{Path,Paths}
import java.net.InetAddress

import org.slf4j.{Logger,LoggerFactory}

import scala.concurrent.duration._
import scala.io.StdIn
import scala.util.{Failure,Success,Try}

import spray.json._
import DefaultJsonProtocol._

import scala.xml.{Elem, NodeSeq}
import scala.xml.transform._

object FsMonitor {
  val log = LoggerFactory.getLogger(this.getClass)

  sealed trait Command
  final case class Watch(path: Path, ext: Seq[String], replyTo: ActorRef[Ack]) extends Command

  sealed trait Response
  final case class Ack(path: Path, from: ActorRef[Watch]) extends Response

  def apply(): Behavior[Watch] = Behaviors.receive {
    case (context, message) =>
      val worker = context.spawn(FsNotify(message.path, message.ext), "FsNotify-worker")
      context.log.info(s"[fsmonitor:exec] created new worker $worker for path ${message.path}")
      worker ! FsNotify.Poll
      Behaviors.same
  }
}

object FsNotify {
  val log = LoggerFactory.getLogger(this.getClass)

  sealed trait Command
  case object Poll extends Command

  private final case class FsScanResponse(response: FsScan.Response) extends Command
  private final case class FsQueueResponse(response: FsQueue.Response) extends Command

  object FsQueue {
    implicit val system: ActorSystem[Nothing] = AppContext.system

    val bufferSize = 100 // Reduced buffer size to limit memory usage

    import java.io.File
    case class QueueProxy(
      override val context: ActorContext[QueueProxy.Request]
    , file: File
    ) extends AbstractBehavior[QueueProxy.Request](context) {
      val log = context.log

      val ffmpegResponseMapper: ActorRef[FFMpegDelegate.Response] =
        context.messageAdapter(resp => QueueProxy.FFMpegResponse(resp))

      val worker = {
        val uuid = java.util.UUID.randomUUID
        val worker = context.spawn(FFMpegDelegate(file), s"ffmpeg-delegate-${uuid}")
        log.info(s"[queue:proxy] send convert message to worker $worker")
        worker ! FFMpegDelegate.Request.Start(replyTo=ffmpegResponseMapper)
        worker
      }

      val killSwitch = KillSwitches.shared(s"queue-proxy-killswitch-${java.util.UUID.randomUUID}")
      log.info("[queue:proxy] via kill switch {}", killSwitch)
      val _ =
        Source
          .tick(10.second, 10.second, ())
          .via(killSwitch.flow)
          .map { case _ =>
            worker ! FFMpegDelegate.Request.Status(replyTo=ffmpegResponseMapper)
          }
          .runWith(Sink.ignore)

      var parent: Option[ActorRef[QueueProxy.Response]] = None

      override def onMessage(msg: QueueProxy.Request) = msg match {
        case QueueProxy.Request.Notify(replyTo) =>
          parent = Some(replyTo)
          Behaviors.same
        case QueueProxy.FFMpegResponse(resp) =>
          log.info(s"[queue:proxy] received response -> $resp")
          resp match {
            case FFMpegDelegate.Response.Status(code,_) if code != 0 =>
              log.info(s"[queue:proxy] status -> $code")
              Behaviors.same
            case FFMpegDelegate.Response.Status(code,replyTo) =>
              log.info(s"[queue:proxy] signal stop -> $replyTo")
              replyTo ! FFMpegDelegate.Request.Stop(replyTo=ffmpegResponseMapper)
              parent.foreach (ref => ref ! QueueProxy.Response.Complete)
              killSwitch.shutdown()
              Behaviors.same
          }
      }
    }
    object QueueProxy {
      sealed trait Request
      object Request {
        case class Notify(replyTo: ActorRef[QueueProxy.Response]) extends Request
      }

      private final case class FFMpegResponse(response: FFMpegDelegate.Response) extends Request

      sealed trait Response
      object Response {
        case object Complete extends Response
        case object Pending extends Response
      }

      def apply(file: File): Behavior[QueueProxy.Request] =
        Behaviors.setup {
          case context =>
            QueueProxy(context, file)
        }
    }

    sealed trait Response
    object Response {
      case class Complete(p: Path) extends Response
   }

    case class Enqueue(p: Path, replyTo: ActorRef[FsQueue.Response])

    import org.apache.pekko.actor.typed.scaladsl.AskPattern._
    import org.apache.pekko.util.Timeout
    import scala.concurrent.Future
    val queue =
      Source
        .queue[FsQueue.Enqueue](bufferSize)
        .mapAsync(parallelism=1) {
          case Enqueue(p,replyTo) =>
            val uuid = java.util.UUID.randomUUID
            val proxy = system.systemActorOf(QueueProxy(p.toFile), s"ffmpeg-proxy-${uuid}")
            system.log.info(s"[queue] send convert message to proxy $proxy")

            implicit val ec: scala.concurrent.ExecutionContext = system.executionContext
            implicit val timeout: Timeout = 10.minutes
            val status: Future[QueueProxy.Response] =
              proxy.ask(ref => QueueProxy.Request.Notify(replyTo=ref))

            status.onComplete {
              case Success(QueueProxy.Response.Complete) =>
                system.log.info(s"[queue] task complete")
                replyTo ! FsQueue.Response.Complete(p)
              case Success(QueueProxy.Response.Pending) =>
                ()
              case _ =>
                ()
            }

            Future.successful(p.toFile)
        }
        .toMat(Sink.foreach(p => system.log.info(s"[queue] completed $p")))(Keep.left)
        .run()

    def +=(e: FsQueue.Enqueue): Unit = queue.offer(e) : Unit
  }

  def apply(path: Path, ext: Seq[String]): Behavior[Command] =
    Behaviors.setup[Command] { case context =>
      val fsResponseMapper: ActorRef[FsScan.Response] =
        context.messageAdapter(resp => FsScanResponse(resp))
      val fsQueueResponseMapper: ActorRef[FsQueue.Response] =
        context.messageAdapter(resp => FsQueueResponse(resp))

      Behaviors.receive { case (context, message) =>
        message match {
          case Poll =>
            context.log.info(s"[fsnotify:schedule] received signal to start poll")
            implicit val system = AppContext.system
            val _ =
              Source
                .tick(10.second, 10.second, ())
                .map { case _ =>
                  val uuid = java.util.UUID.randomUUID
                  val worker = system.systemActorOf(FsScan(ext), s"FsScan-scan-${uuid}")
                  system.log.info(s"[fsnotify:schedule] send scan message to worker $worker")
                  worker ! FsScan.Scan(root=path,replyTo=fsResponseMapper)
                }
                .runWith(Sink.ignore)
            Behaviors.same
          case FsScanResponse(resp @ FsScan.Ack(paths,_)) =>
            context.log.info(s"[fsnotify:schedule] received $resp message")
            paths.foreach { case path =>
              context.log.info(s"[fsnotify:schedule] put $path in queue")
              FsQueue += FsQueue.Enqueue(p=path,replyTo=fsQueueResponseMapper)
            }
            Behaviors.same
          case FsQueueResponse(FsQueue.Response.Complete(p)) =>
            context.log.info(s"[fsnotify:schedule] remove $p from cache")
            Behaviors.same
        }
      }
    }
}

object FsUtil {
  import java.nio.charset.Charset
  import java.nio.file.{Files,Path}
  import java.nio.file.attribute.{UserDefinedFileAttributeView => Xattr}
  object Attr {
    object Key {
      val FS_STATE = "fs.state"
    }
    object Value {
      val LOCK = "lock"
      val ENCODED = "encoded"
    }
    def write(path: Path, key: String, value: String): Try[Int] = Try {
      val xattr = Files.getFileAttributeView(path, classOf[Xattr])
      xattr.write(key, Charset.defaultCharset.encode(value))
    }
    def lock(path: Path): Try[Unit] =
      write(path, Key.FS_STATE, Value.LOCK).map(_ => ())
    def encoded(path: Path): Try[Unit] =
      write(path, Key.FS_STATE, Value.ENCODED).map(_ => ())
  }
}

object FsScan {
  import scala.concurrent.{ExecutionContext,Future}
  import ExecutionContext.Implicits.global

  val log = LoggerFactory.getLogger(this.getClass)

  sealed trait Command
  final case class Scan(root: Path, replyTo: ActorRef[Ack]) extends Command
  case object Stop extends Command

  sealed trait Response
  final case class Ack(paths: Seq[Path], from: ActorRef[Scan]) extends Response

  def apply(ext: Seq[String]): Behavior[Command] = Behaviors.receive {
    case (context, message) =>
      message match {
        case Scan(root,replyTo) =>
          context.log.info(s"[standby] scan $root (replyTo $replyTo)")
          val self = context.self
          scan(root=root,ext=ext)(using context.log).foreach {
            case result =>
              log.info(s"[standby] scanned $root with result $result - tell $replyTo")
              replyTo ! Ack(paths=result,from=self)
              self ! Stop
          }
          context.log.info(s"[standby] scan in future space")
          Behaviors.same
        case Stop =>
          context.log.info(s"[standby] shutting down")
          Behaviors.stopped
      }
  }

  private def scan(root: Path, ext: Seq[String])(implicit log: Logger): Future[Seq[Path]] = Future {
    import java.nio.file.Files
    import java.nio.file.attribute.{UserDefinedFileAttributeView => Xattr}
    import scala.jdk.CollectionConverters._
    import scala.jdk.StreamConverters._

    // Use streaming to avoid loading entire directory tree into memory
    Files.find(root, Integer.MAX_VALUE, (_, a) => a.isRegularFile)
      .toScala(LazyList) // Use LazyList instead of Vector for memory efficiency
      .filter { case path =>
        val kind = ext.find(path.getFileName.toFile.getName.endsWith(_))
        val scanned = Try {
          Files.getFileAttributeView(path, classOf[Xattr])
               .list
               .asScala
               .exists(_ == FsUtil.Attr.Key.FS_STATE)
        }.getOrElse(false)
        val hit = kind.isDefined && !scanned
        log.info(s"[scan] found $kind (state: $scanned) - hit $hit")
        hit
      }
      .toSeq // Convert to Seq only at the end
  }
}

object AppContext {
  implicit val system: ActorSystem[pekko.NotUsed] = ActorSystem(Tablo2HDHomeRun(), "tablo2hdhomerun-system")
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

object Tablo2HDHomeRun extends App {
  val log = LoggerFactory.getLogger(this.getClass)

  private final case class FsMonitorResponse(response: FsMonitor.Response)

  val media = sys.env.get("MEDIA_ROOT")

  Dependencies.verify()

  def apply(): Behavior[pekko.NotUsed] = Behaviors.setup { context =>
    media.foreach { case root =>
      log.info(s"[apply] media root set -> $root")

      val monitor = context.spawn(FsMonitor(), "monitor-actor")
      context.log.info(s"[apply] created monitor actor $monitor")

      implicit val timeout: pekko.util.Timeout = 3.seconds

      val path = Paths.get(root)
      context.ask[FsMonitor.Watch,FsMonitor.Ack](monitor, ref => FsMonitor.Watch(path=path,ext=Seq("ts,","mkv"),replyTo=ref)) {
        case Success(FsMonitor.Ack(p,_)) =>
          context.log.info(s"[apply] received ACK($p)")
          pekko.NotUsed
        case Failure(ex) =>
          context.log.info(s"[apply] received FAILURE with ${ex.getMessage}")
          pekko.NotUsed
      }
    }

    val lineup = context.spawn(Lineup.LineupActor(), "lineup-actor")
    startHttp(lineup)

    Behaviors.empty
  }

  implicit val system: ActorSystem[Nothing] = AppContext.system

  implicit val ec: scala.concurrent.ExecutionContext = system.executionContext

  object Response {
    object JsonProtocol {
      implicit object inetAddressFormat extends JsonFormat[InetAddress] {
        override def read(js : JsValue) : InetAddress = js match {
          case JsString(value) =>
            Try(InetAddress.getByName(value)) match {
              case Success(inetAddress) =>
                inetAddress
              case Failure(t) =>
                deserializationError(s"could not parse InetAddress -> ${t.getMessage}")
            }
          case _ =>
            deserializationError(s"Expected String for InetAddress, but got $js")
        }
        override def write(inetAddress : InetAddress) : JsValue = JsString(inetAddress.getHostAddress)
      }
      implicit object uriFormat extends JsonFormat[Uri] {
        override def read(js: JsValue): Uri = js match {
          case JsString(value) =>
            Try(Uri(value)) match {
              case Success(uri) =>
                uri
              case Failure(t) =>
                deserializationError(s"could not parse Uri -> ${t.getMessage}")
            }
          case _ =>
            deserializationError(s"Expected Uri path, but got $js")
        }
        override def write(uri : Uri) : JsValue = JsString(uri.toString)
      }
    }
    case class Discover(
      FriendlyName: String
    , LocalIP: InetAddress
    , BaseURL: Uri
    , LineupURL: Uri
    , Manufacturer: String = "tablo2hdhomerun"
    , ModelNumber: String = "HDHR3-US"
    , FirmwareName: String = "hdhomerun3_atsc"
    , FirmwareVersion: String = "20240101"
    , DeviceID: String = "12345678" // TODO hash friendly name
    , DeviceAuth: String = "tabloauth123"
    ) {
      def proxyAddress(inetAddress: InetAddress, port: Int) =
        BaseURL.withHost(inetAddress.getHostAddress.toString).withPort(port)
    }
    object Discover {
      def apply(friendlyName: String, localIp: InetAddress): Discover = {
        val proxyUri = Uri(s"${TABLO_PROTOCOL}://${localIp.getHostAddress}:${PROXY_PORT}")
        Discover(FriendlyName=friendlyName, LocalIP=localIp, BaseURL=proxyUri, LineupURL=proxyUri.withPath(Uri.Path("/lineup.json")))
      }
      object JsonProtocol {
        import Response.JsonProtocol.{inetAddressFormat,uriFormat}
        implicit val discoverFormat: JsonFormat[Discover] = jsonFormat10(Discover.apply)
      }

      val route =
        path("discover.json") {
          get {
            import Discover.JsonProtocol.discoverFormat
            val response = discover.toJson
            log.info(s"[discover] discover.json (GET) - $response")
            complete(HttpEntity(ContentTypes.`application/json`, response.compactPrint))
          }
        }
    }
  }

  val TABLO_IP = InetAddress.getByName(sys.env.getOrElse("TABLO_IP","127.0.0.1"))
  val TABLO_PROTOCOL = "http"
  val TABLO_PORT = 8885

  val PROXY_IP = InetAddress.getByName(sys.env.getOrElse("PROXY_IP","127.0.0.1"))
  val PROXY_PORT = 8080

  import Response.Discover
  val discover = Discover(friendlyName="Tablo Legacy Gen Proxy",localIp=PROXY_IP)

  import pekko.http.scaladsl.unmarshalling.Unmarshal
  import pekko.http.scaladsl.marshalling.Marshal

  import scala.concurrent.Future

  val HttpCtx = Http()

  object Lineup {
    case class ChannelInfo(
      call_sign: String
    , call_sign_src: String
    , major: Int
    , minor: Int
    , network: Option[String]
    , resolution: String
    , favourite: Boolean
    , tms_station_id: String
    , tms_affiliate_id: String
    , source: String
    )

    case class ChannelObject(
      object_id: Int
    , path: String
    , channel: ChannelInfo
    )

    object JsonProtocol extends DefaultJsonProtocol {
      implicit val channelInfoFormat: JsonFormat[ChannelInfo] = jsonFormat10(ChannelInfo.apply)
      implicit val channelObjectFormat: JsonFormat[ChannelObject] = jsonFormat3(ChannelObject.apply)
    }

    object Proxy {
      object Request {
        val baseUrl = discover.proxyAddress(TABLO_IP,TABLO_PORT)
        val getUri = baseUrl.withPath(Uri.Path("/guide/channels"))
        val postUri = baseUrl.withPath(Uri.Path("/batch"))

        val httpRequest = HttpRequest(uri = getUri)

        def postRequest(entity: RequestEntity) = HttpRequest(
          method = HttpMethods.POST
        , uri = postUri
        , entity = entity.withContentType(ContentTypes.`application/json`)
        )
      }
      object Response {
        object ChannelObject {
          def jsValue(obj: ChannelObject): JsValue = {
            val num = s"${obj.channel.major}.${obj.channel.minor}"
            val url = s"${discover.BaseURL.withPath(Uri.Path(s"/channel/${obj.object_id}"))}"
            val src = s"${discover.BaseURL.withPath(Uri.Path(s"/guide/channels/${obj.object_id}/watch"))}"
            JsObject(
              "GuideNumber" -> JsString(num)
            , "GuideName" -> JsString(obj.channel.call_sign)
            , "URL" -> JsString(url)
            , "type" -> JsString(obj.channel.source)
            , "srcURL" -> JsString(src)
            )
          }
        }
      }
    }

    object LineupActor {
      sealed trait Request
      object Request {
        case class Fetch(replyTo: ActorRef[Response.Fetch]) extends Request
        case class Status(replyTo: ActorRef[Response.Status]) extends Request
      }

      sealed trait Response
      object Response {
        case class Fetch(channels: Seq[JsValue], replyTo: ActorRef[Request.Fetch]) extends Response
        case class Status(scanInProgress: Int, scanPossible: Int, replyTo: ActorRef[Request.Fetch]) extends Response
      }

      sealed trait Command extends Request
      object Command {
        case class Store(channels: Seq[JsValue]) extends Command
        case object Scan extends Command
      }

      trait Error extends Exception
      object Error {
        case class ServerError(f: File) extends Exception(s"server error: $f") with Error
      }

      val log = LoggerFactory.getLogger(this.getClass)

      implicit val ec: scala.concurrent.ExecutionContextExecutor = scala.concurrent.ExecutionContext.global

      def apply(): Behavior[Request] = Behaviors.setup { context =>
        // Use WeakReference for cache to allow GC when memory pressure is high
        var cache: (scala.concurrent.duration.Deadline, java.lang.ref.WeakReference[Seq[JsValue]]) =
          (0.seconds.fromNow, new java.lang.ref.WeakReference(Seq.empty))
        var scanInProgress: Boolean = false

        val HttpCtx = Http()

        def scan(): Future[Seq[JsValue]] =
          HttpCtx
            .singleRequest(Proxy.Request.httpRequest)
            .flatMap { response =>
              log.info(s"[lineup-actor] guide/channels (GET) - $response")
              Unmarshal(response.entity).to[String].map(_.parseJson.convertTo[Seq[String]])
            }
            .flatMap { paths =>
              import JsonProtocol._
              import pekko.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
              Marshal(paths.toJson)
                .to[RequestEntity]
                .flatMap { entity =>
                  HttpCtx.singleRequest(Proxy.Request.postRequest(entity)).flatMap { response =>
                    log.info(s"[lineup-actor] batch (POST) - $response")
                    Unmarshal(response.entity).to[String].map(_.parseJson.convertTo[Map[String, ChannelObject]])
                  }
                }
            }
            .map { data =>
              log.info(s"[lineup-actor] channels found: ${data.size}")
              val channels =
                data
                  .map { case (path,obj) =>
                    log.info(s"[lineup-actor] $path => ${obj.channel.call_sign}, ${obj.channel.network.getOrElse("None")}")
                    Proxy.Response.ChannelObject.jsValue(obj)
                  }
                  .toSeq

              context.self ! Command.Store(channels)

              channels
            }

        context.self ! Command.Scan

        Behaviors.receiveMessage {
          case Command.Store(channels) =>
            log.info(s"[lineup-actor] store channels: ${channels.size}")

            cache = (1.day.fromNow, new java.lang.ref.WeakReference(channels))
            scanInProgress = false

            Behaviors.same

          case Command.Scan if scanInProgress =>
            log.info("[lineup-actor] channel scan requested ; already in progress (suppress)")

            Behaviors.same

          case Command.Scan =>
            log.info("[lineup-actor] channel scan requested")

            scanInProgress = true
            scan() : Unit

            Behaviors.same

          case Request.Status(sender) =>
            log.info("[lineup-actor] channel status requested")

            val (scanning,possible) = if (scanInProgress) (1,0) else (0,1)
            sender ! Response.Status(scanInProgress=scanning, scanPossible=possible, replyTo=context.self)

            Behaviors.same

          case Request.Fetch(replyTo) if cache._1.isOverdue() =>
            log.info("[lineup-actor] channel fetch")

            val sender = replyTo

            scanInProgress = true
            scan().foreach { channels =>
              sender ! LineupActor.Response.Fetch(channels, context.self)
            }

            Behaviors.same

          case Request.Fetch(sender) =>
            val (_,channelsRef) = cache
            val channels = channelsRef.get.getOrElse(Seq.empty)

            log.info(s"[lineup-actor] channels found: ${channels.size}")
            sender ! LineupActor.Response.Fetch(channels, context.self)

            Behaviors.same
        }
      }
    }

    object Response {
      case class LineupStatus(
        ScanInProgress: Int = 0
      , ScanPossible: Int = 1
      , Source: String = "Antenna"
      , SourceList: Seq[String] = List("Antenna")
      )
      object LineupStatus {
        object JsonProtocol {
          implicit val lineupStatusFormat: JsonFormat[LineupStatus] = jsonFormat4(LineupStatus.apply)
        }
      }
    }

    def route(lineupActor: ActorRef[Lineup.LineupActor.Request]) =
      path("lineup.json") {
        get {
          import pekko.actor.typed.scaladsl.AskPattern._
          implicit val timeout: pekko.util.Timeout = 3.seconds
          val lineupF: Future[LineupActor.Response.Fetch] = lineupActor.ask(replyTo => LineupActor.Request.Fetch(replyTo))

          onComplete(lineupF) {
            case Success(LineupActor.Response.Fetch(channels,_)) =>
              complete(HttpEntity(ContentTypes.`application/json`, channels.toJson.compactPrint))
            case Failure(ex) =>
              log.info(s"[lineup] Failed: ${ex.getMessage}")
              complete(HttpResponse(StatusCodes.InternalServerError, entity = "Unable to produce channel lineup"))
          }
        }
      } ~
      path("lineup_status.json") {
        get {
          import pekko.actor.typed.scaladsl.AskPattern._
          implicit val timeout: pekko.util.Timeout = 3.seconds
          val statusF: Future[LineupActor.Response.Status] = lineupActor.ask(replyTo => LineupActor.Request.Status(replyTo))

          onComplete(statusF) {
            case Success(LineupActor.Response.Status(scanInProgress,scanPossible,_)) =>
              import Response.LineupStatus.JsonProtocol.lineupStatusFormat
              val response = Response.LineupStatus(ScanInProgress=scanInProgress,ScanPossible=scanPossible).toJson
              log.info(s"[lineup_status] lineup_status.json (GET) - $response")
              complete(HttpEntity(ContentTypes.`application/json`, response.compactPrint))
            case Failure(ex) =>
              log.info(s"[lineup_status] Failed: ${ex.getMessage}")
              complete(HttpResponse(StatusCodes.InternalServerError, entity = "Unable to get lineup status"))
          }
        }
      }
  }

  object Guide {
    case class Program(
      id: String
    , title: String
    , description: Option[String]
    , start_time: String
    , end_time: String
    , channel_id: Int
    , episode_title: Option[String]
    , season_number: Option[Int]
    , episode_number: Option[Int]
    , year: Option[Int]
    , genre: Option[String]
    , rating: Option[String]
    , is_movie: Boolean = false
    , is_sports: Boolean = false
    , is_news: Boolean = false
    )

    case class ChannelGuide(
      channel_id: Int
    , call_sign: String
    , major: Int
    , minor: Int
    , programs: Seq[Program]
    )

    object JsonProtocol extends DefaultJsonProtocol {
      implicit val programFormat: JsonFormat[Program] = jsonFormat15(Program.apply)
      implicit val channelGuideFormat: JsonFormat[ChannelGuide] = jsonFormat5(ChannelGuide.apply)
    }

    object Proxy {
      object Request {
        val baseUrl = discover.proxyAddress(TABLO_IP,TABLO_PORT)

        // Try to discover program guide endpoints
        def getProgramsUri(channelId: Int, startTime: Option[String] = None, endTime: Option[String] = None) = {
          val baseUri = baseUrl.withPath(Uri.Path(s"/guide/channels/$channelId/programs"))
          val queryParams = (startTime, endTime) match {
            case (Some(start), Some(end)) => s"?start=$start&end=$end"
            case (Some(start), None) => s"?start=$start"
            case (None, Some(end)) => s"?end=$end"
            case _ => ""
          }
          Uri(baseUri.toString + queryParams)
        }

        def getScheduleUri(startTime: Option[String] = None, endTime: Option[String] = None) = {
          val baseUri = baseUrl.withPath(Uri.Path("/guide/schedule"))
          val queryParams = (startTime, endTime) match {
            case (Some(start), Some(end)) => s"?start=$start&end=$end"
            case (Some(start), None) => s"?start=$start"
            case (None, Some(end)) => s"?end=$end"
            case _ => ""
          }
          Uri(baseUri.toString + queryParams)
        }

        def httpRequest(uri: Uri) = HttpRequest(uri = uri)
      }

      object Response {
        // Try to parse program data from various possible Tablo API response formats
        case class TabloProgram(
          id: Option[String]
        , title: Option[String]
        , description: Option[String]
        , start_time: Option[String]
        , end_time: Option[String]
        , episode_title: Option[String]
        , season_number: Option[Int]
        , episode_number: Option[Int]
        , year: Option[Int]
        , genre: Option[String]
        , rating: Option[String]
        , is_movie: Option[Boolean]
        , is_sports: Option[Boolean]
        , is_news: Option[Boolean]
        )

        object TabloProgram {
          object JsonProtocol {
            implicit val tabloProgramFormat: JsonFormat[TabloProgram] = jsonFormat14(TabloProgram.apply)
          }
        }

        def convertToProgram(tabloProgram: TabloProgram, channelId: Int): Program = {
          Program(
            id = tabloProgram.id.getOrElse(s"prog_${System.currentTimeMillis()}")
          , title = tabloProgram.title.getOrElse("Unknown Program")
          , description = tabloProgram.description
          , start_time = tabloProgram.start_time.getOrElse("")
          , end_time = tabloProgram.end_time.getOrElse("")
          , channel_id = channelId
          , episode_title = tabloProgram.episode_title
          , season_number = tabloProgram.season_number
          , episode_number = tabloProgram.episode_number
          , year = tabloProgram.year
          , genre = tabloProgram.genre
          , rating = tabloProgram.rating
          , is_movie = tabloProgram.is_movie.getOrElse(false)
          , is_sports = tabloProgram.is_sports.getOrElse(false)
          , is_news = tabloProgram.is_news.getOrElse(false)
          )
        }
      }
    }

  object GuideActor {
    sealed trait Request
    object Request {
      case class FetchGuide(replyTo: ActorRef[Response.FetchGuide]) extends Request
      case class FetchChannelGuide(channelId: Int, replyTo: ActorRef[Response.FetchChannelGuide]) extends Request
    }

    sealed trait Response
    object Response {
      case class FetchGuide(guide: Seq[ChannelGuide], replyTo: ActorRef[Request.FetchGuide]) extends Response
      case class FetchChannelGuide(channelGuide: ChannelGuide, replyTo: ActorRef[Request.FetchChannelGuide]) extends Response
    }

    sealed trait Command extends Request
    object Command {
      case class StoreGuide(guide: Seq[ChannelGuide]) extends Command
      case object Scan extends Command
    }

    val log = LoggerFactory.getLogger(this.getClass)

    implicit val ec: scala.concurrent.ExecutionContextExecutor = scala.concurrent.ExecutionContext.global

    // Singleton actor instance to avoid creating new actors per request
    private var _instance: Option[ActorRef[Request]] = None

    def instance: ActorRef[Request] = _instance.getOrElse {
      val actor = AppContext.system.systemActorOf(apply(), "guide-actor-singleton")
      _instance = Some(actor)
      actor
    }

      def apply(): Behavior[Request] = Behaviors.setup { context =>
        // Use WeakReference for cache to allow GC when memory pressure is high
        var cache: (scala.concurrent.duration.Deadline, java.lang.ref.WeakReference[Seq[ChannelGuide]]) =
          (0.seconds.fromNow, new java.lang.ref.WeakReference(Seq.empty))
        var scanInProgress: Boolean = false

        val HttpCtx = Http()

        def generateFallbackPrograms(channelId: Int, callSign: String): Seq[Program] = {
          // Generate sample program data when Tablo API doesn't provide it
          val now = java.time.Instant.now()

          // Use LazyList for memory efficiency - only materialize when needed
          LazyList.range(0, 24).map { hour =>
            val startTime = now.plusSeconds(hour * 3600)
            val endTime = startTime.plusSeconds(3600) // 1 hour programs

            val programTitle = hour match {
              case 0 | 1 | 2 | 3 | 4 | 5 => "Late Night Programming"
              case 6 | 7 | 8 | 9 => "Morning News"
              case 10 | 11 | 12 | 13 => "Daytime Programming"
              case 14 | 15 | 16 | 17 => "Afternoon Shows"
              case 18 | 19 | 20 | 21 => "Prime Time"
              case 22 | 23 => "Evening News"
            }

            val description = s"Programming on $callSign"
            val genre = hour match {
              case 6 | 7 | 8 | 9 | 22 | 23 => Some("News")
              case 18 | 19 | 20 | 21 => Some("Drama")
              case _ => Some("General")
            }

            Program(
              id = s"fallback_${channelId}_${hour}",
              title = programTitle,
              description = Some(description),
              start_time = startTime.toString,
              end_time = endTime.toString,
              channel_id = channelId,
              episode_title = None,
              season_number = None,
              episode_number = None,
              year = None,
              genre = genre,
              rating = None,
              is_movie = false,
              is_sports = false,
              is_news = hour match { case 6 | 7 | 8 | 9 | 22 | 23 => true; case _ => false }
            )
          }.toSeq
        }

        def fetchProgramsForChannel(channelId: Int): Future[Seq[Program]] = {
          // Try multiple possible endpoints for program data
          val endpoints = Seq(
            Proxy.Request.getProgramsUri(channelId),
            Proxy.Request.getScheduleUri().withPath(Uri.Path(s"/guide/channels/$channelId")),
            Proxy.Request.baseUrl.withPath(Uri.Path(s"/guide/channels/$channelId/schedule"))
          )

          def tryEndpoint(uri: Uri): Future[Seq[Program]] = {
            HttpCtx.singleRequest(Proxy.Request.httpRequest(uri)).flatMap { response =>
              log.info(s"[guide-actor] trying endpoint $uri - $response")
              if (response.status.isSuccess()) {
                Unmarshal(response.entity).to[String].map { body =>
                  log.info(s"[guide-actor] response body: $body")
                  // Try to parse as JSON array of programs
                  Try {
                    import Proxy.Response.TabloProgram.JsonProtocol.tabloProgramFormat
                    body.parseJson.convertTo[Seq[Proxy.Response.TabloProgram]]
                      .map(Proxy.Response.convertToProgram(_, channelId))
                  }.getOrElse {
                    // If that fails, try to parse as a single program object
                    Try {
                      import Proxy.Response.TabloProgram.JsonProtocol.tabloProgramFormat
                      val program = body.parseJson.convertTo[Proxy.Response.TabloProgram]
                      Seq(Proxy.Response.convertToProgram(program, channelId))
                    }.getOrElse {
                      log.warn(s"[guide-actor] could not parse program data from $uri")
                      Seq.empty
                    }
                  }
                }
              } else {
                log.info(s"[guide-actor] endpoint $uri returned ${response.status}")
                Future.successful(Seq.empty)
              }
            }.recover {
              case ex =>
                log.warn(s"[guide-actor] failed to fetch from $uri: ${ex.getMessage}")
                Seq.empty
            }
          }

          // Try endpoints in sequence until one succeeds
          endpoints.foldLeft(Future.successful(Seq.empty[Program])) { (acc, uri) =>
            acc.flatMap { programs =>
              if (programs.nonEmpty) {
                Future.successful(programs)
              } else {
                tryEndpoint(uri)
              }
            }
          }.map { programs =>
            if (programs.isEmpty) {
              log.info(s"[guide-actor] no programs found for channel $channelId, generating fallback data")
              generateFallbackPrograms(channelId, "Unknown")
            } else {
              programs
            }
          }
        }

        def scan(): Future[Seq[ChannelGuide]] = {
          // First get the list of channels
          val channelsUri = Proxy.Request.baseUrl.withPath(Uri.Path("/guide/channels"))
          HttpCtx.singleRequest(Proxy.Request.httpRequest(channelsUri)).flatMap { response =>
            log.info(s"[guide-actor] guide/channels (GET) - $response")
            if (response.status.isSuccess()) {
              Unmarshal(response.entity).to[String].map(_.parseJson.convertTo[Seq[String]]).flatMap { paths =>
                import Lineup.JsonProtocol._
                import pekko.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
                Marshal(paths.toJson)
                  .to[RequestEntity]
                  .flatMap { entity =>
                    val batchUri = Proxy.Request.baseUrl.withPath(Uri.Path("/batch"))
                    val batchRequest = HttpRequest(
                      method = HttpMethods.POST,
                      uri = batchUri,
                      entity = entity.withContentType(ContentTypes.`application/json`)
                    )
                    HttpCtx.singleRequest(batchRequest).flatMap { batchResponse =>
                      log.info(s"[guide-actor] batch (POST) - $batchResponse")
                      Unmarshal(batchResponse.entity).to[String].map(_.parseJson.convertTo[Map[String, Lineup.ChannelObject]])
                    }
                  }
              }.flatMap { channelData =>
                log.info(s"[guide-actor] channels found: ${channelData.size}")

                // Fetch programs for each channel
                val channelFutures = channelData.map { case (path, channelObj) =>
                  fetchProgramsForChannel(channelObj.object_id).map { programs =>
                    ChannelGuide(
                      channel_id = channelObj.object_id,
                      call_sign = channelObj.channel.call_sign,
                      major = channelObj.channel.major,
                      minor = channelObj.channel.minor,
                      programs = programs
                    )
                  }
                }

                Future.sequence(channelFutures.toSeq)
              }
            } else {
              log.warn(s"[guide-actor] failed to get channels: ${response.status}")
              Future.successful(Seq.empty)
            }
          }.recover {
            case ex =>
              log.error(s"[guide-actor] scan failed: ${ex.getMessage}")
              Seq.empty
          }
        }

        context.self ! Command.Scan

        Behaviors.receiveMessage {
          case Command.StoreGuide(guide) =>
            log.info(s"[guide-actor] store guide: ${guide.size} channels")
            cache = (1.hour.fromNow, new java.lang.ref.WeakReference(guide))
            scanInProgress = false
            Behaviors.same

          case Command.Scan if scanInProgress =>
            log.info("[guide-actor] guide scan requested ; already in progress (suppress)")
            Behaviors.same

          case Command.Scan =>
            log.info("[guide-actor] guide scan requested")
            scanInProgress = true
            scan().foreach { guide =>
              context.self ! Command.StoreGuide(guide)
            }
            Behaviors.same

          case Request.FetchGuide(replyTo) if cache._1.isOverdue() =>
            log.info("[guide-actor] guide fetch - cache expired")
            scanInProgress = true
            scan().foreach { guide =>
              replyTo ! Response.FetchGuide(guide, context.self)
            }
            Behaviors.same

          case Request.FetchGuide(replyTo) =>
            val (_, guideRef) = cache
            val guide = guideRef.get.getOrElse(Seq.empty)
            log.info(s"[guide-actor] guide fetch from cache: ${guide.size} channels")
            replyTo ! Response.FetchGuide(guide, context.self)
            Behaviors.same

          case Request.FetchChannelGuide(channelId, replyTo) =>
            val (_, guideRef) = cache
            val guide = guideRef.get.getOrElse(Seq.empty)
            val channelGuide = guide.find(_.channel_id == channelId).getOrElse {
              ChannelGuide(channelId, "Unknown", 0, 0, Seq.empty)
            }
            replyTo ! Response.FetchChannelGuide(channelGuide, context.self)
            Behaviors.same
        }
      }
    }

    object XMLTVFormatter {
      def formatTimestamp(timeStr: String): String = {
        // Try to parse various timestamp formats and convert to XMLTV format
        Try {
          val formatter = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
          val instant = java.time.Instant.parse(timeStr)
          instant.toString
        }.getOrElse {
          Try {
            val formatter = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            val localDateTime = java.time.LocalDateTime.parse(timeStr, formatter)
            localDateTime.atZone(java.time.ZoneId.systemDefault()).toInstant.toString
          }.getOrElse {
            java.time.Instant.now().toString
          }
        }
      }

      def formatChannel(channelGuide: ChannelGuide): Elem = {
        val channelId = s"${channelGuide.major}.${channelGuide.minor}"
        <channel id={channelId}>
          <display-name>{channelGuide.call_sign}</display-name>
          <display-name>{channelId}</display-name>
          <display-name>{s"${channelGuide.call_sign} (${channelId})"}</display-name>
        </channel>
      }

      def formatProgram(program: Program, channelGuide: ChannelGuide): Elem = {
        val channelId = s"${channelGuide.major}.${channelGuide.minor}"
        val startTime = formatTimestamp(program.start_time)
        val stopTime = formatTimestamp(program.end_time)

        <programme start={startTime} stop={stopTime} channel={channelId}>
          <title>{program.title}</title>
          {program.episode_title.map(ep => <sub-title>{ep}</sub-title>).getOrElse(NodeSeq.Empty)}
          {program.description.map(desc => <desc>{desc}</desc>).getOrElse(NodeSeq.Empty)}
          {program.year.map(year => <date>{year}</date>).getOrElse(NodeSeq.Empty)}
          {program.genre.map(genre => <category>{genre}</category>).getOrElse(NodeSeq.Empty)}
          {program.rating.map(rating => <rating><value>{rating}</value></rating>).getOrElse(NodeSeq.Empty)}
          {if (program.is_movie) <category>Movie</category> else NodeSeq.Empty}
          {if (program.is_sports) <category>Sports</category> else NodeSeq.Empty}
          {if (program.is_news) <category>News</category> else NodeSeq.Empty}
        </programme>
      }

      def formatGuide(guide: Seq[ChannelGuide]): Elem = {
        val channels = guide.map(formatChannel)
        val programmes = guide.flatMap(channelGuide =>
          channelGuide.programs.map(program => formatProgram(program, channelGuide))
        )

        <tv>
          {channels}
          {programmes}
        </tv>
      }
    }

    val route =
      path("guide.xml") {
        get {
          import pekko.actor.typed.scaladsl.AskPattern._
          implicit val timeout: pekko.util.Timeout = 10.seconds

          val guideF: Future[GuideActor.Response.FetchGuide] = GuideActor.instance.ask(replyTo => GuideActor.Request.FetchGuide(replyTo))

          onComplete(guideF) {
            case Success(GuideActor.Response.FetchGuide(guide, _)) =>
              log.info(s"[guide] guide.xml (GET) - ${guide.size} channels")

              // Stream XML generation to avoid loading entire response in memory
              val xmlStream = Source.fromIterator(() =>
                Iterator("<tv>") ++
                guide.iterator.flatMap(channelGuide =>
                  Iterator(XMLTVFormatter.formatChannel(channelGuide).toString) ++
                  channelGuide.programs.iterator.map(program =>
                    XMLTVFormatter.formatProgram(program, channelGuide).toString
                  )
                ) ++
                Iterator("</tv>")
              ).map(ByteString(_))

              complete(HttpEntity.Chunked.fromData(ContentTypes.`text/xml(UTF-8)`, xmlStream))
            case Failure(ex) =>
              log.info(s"[guide] Failed: ${ex.getMessage}")
              // Return empty XMLTV format on failure
              val emptyGuide = <tv></tv>
              complete(HttpEntity(ContentTypes.`text/xml(UTF-8)`, emptyGuide.toString))
          }
        }
      }
  }

  object Channel {
    object Request {
      case class WatchRequest(
        bandwidth: Int = 1000
      , no_fast_startup: Boolean = false
      )
      object WatchRequest {
        object JsonProtocol {
          implicit val watchRequestFormat: JsonFormat[WatchRequest] = jsonFormat2(WatchRequest.apply)
        }
        import JsonProtocol.watchRequestFormat
        val watchRequestJson = Request.WatchRequest().toJson
        def httpRequest(uri: Uri) = HttpRequest(
          method = HttpMethods.POST
        , uri = uri
        , entity = HttpEntity(ContentTypes.`application/json`, watchRequestJson.compactPrint)
        )
      }
      object Tuners {
        val uri = discover.proxyAddress(TABLO_IP,TABLO_PORT).withPath(Uri.Path(s"/server/tuners"))
        val httpRequest = HttpRequest(uri = uri)
      }
    }
    object Response {
      case class VideoDetails(width: Int = 0, height: Int = 0)
      case class WatchResponse(
        token: String // df9586a4-5548-48a9-87f2-1191a8a0df8b
      , expires: String // 2025-06-18T02:55:38Z
      , keepalive: Int // 120
      , playlist_url: Uri // http://192.168.11.219:80/stream/pl.m3u8?-_XhOSWWP5qBhXIVMu6c7g
      , bif_url_sd: Option[Uri]
      , bif_url_hd: Option[Uri]
      , video_details: VideoDetails
      )
      case object NoAvailableTunersError extends Exception("No available tuners")
      object JsonProtocol {
        import Tablo2HDHomeRun.Response.JsonProtocol.uriFormat
        implicit val videoDetailsFormat: JsonFormat[VideoDetails] = jsonFormat2(VideoDetails.apply)
        implicit val watchResponseFormat: JsonFormat[WatchResponse] = jsonFormat7(WatchResponse.apply)
      }

      case class Tuners(in_use: Boolean, channel: Option[Uri], recording: Option[Uri])
      object Tuners {
        object JsonProtocol {
          import Tablo2HDHomeRun.Response.JsonProtocol.uriFormat
          implicit val tunersFormat: JsonFormat[Tuners] = jsonFormat3(Tuners.apply)
        }
      }
    }

    val route =
      path("channel" / LongNumber) { channelId =>
        get {
          import Response.JsonProtocol._

          val watchUri =
            discover
              .proxyAddress(TABLO_IP,TABLO_PORT)
              .withPath(Uri.Path(s"/guide/channels/$channelId/watch"))

          val tunersFuture: Future[Seq[Response.Tuners]] =
            HttpCtx.singleRequest(Request.Tuners.httpRequest).flatMap { response =>
              import Response.Tuners.JsonProtocol.tunersFormat
              log.info(s"[channel] server/tuners (POST) - $response")
              Unmarshal(response.entity).to[String].map(_.parseJson.convertTo[Seq[Response.Tuners]])
            }

          val watchFuture: Future[Response.WatchResponse] = tunersFuture.flatMap { tuners =>
            val available = tuners.filterNot(_.in_use).size
            log.info(s"[channel] available tuners - $available")
            if (available > 0) {
              HttpCtx.singleRequest(Request.WatchRequest.httpRequest(watchUri)).flatMap { response =>
                log.info(s"[channel] guide/channels/$channelId/watch (POST) - $response")
                Unmarshal(response.entity).to[String].map(_.parseJson.convertTo[Response.WatchResponse])
              }
            } else {
              log.info(s"[channel] no available tuners (0/${tuners.size})")
              Future.failed(Response.NoAvailableTunersError)
            }
          }

          import pekko.util._

          def stream(playlist_url: Uri): Source[ByteString, ?] = Source.lazySource { () =>
            val ffmpegCmd = Array(
              "ffmpeg"
            , "-i", playlist_url.toString
            , "-c", "copy"
            , "-f", "mpegts"
            , "-v", "repeat+level+panic"
            , "pipe:1"
            )

            val process = sys.runtime.exec(ffmpegCmd)
            log.info(s"[channel] execute command line - ${ffmpegCmd.mkString(" ")} => spawn ${process.pid}")

            StreamConverters
              .fromInputStream(() => process.getInputStream) // stream the stdout of ffmpeg
              .watchTermination() { (_, done) =>
                log.info(s"[channel] started http stream - ffmpeg (pid ${process.pid})")
                done.onComplete {
                  case Success(_) =>
                    log.info(s"[channel] terminating ffmpeg (kill pid ${process.pid})")
                    process.destroy()
                  case Failure(ex) =>
                    log.info(s"[channel] stream failed: ${ex.getMessage} (kill pid ${process.pid})")
                    process.destroy()
                }
              }
          }

          onComplete (watchFuture) {
            case Success(data) =>
              log.info(s"[channel] tuned to channel - $data")
              val `video/mp2t` = MediaType.customBinary("video", "mp2t", MediaType.NotCompressible)
              complete(HttpEntity.Chunked.fromData(ContentType(`video/mp2t`), stream(data.playlist_url)))
            case Failure(ex) =>
              log.info(s"[channel] failed to start stream - ${ex.getMessage}")
              complete(HttpResponse(StatusCodes.InternalServerError, entity = "Unable to stream channel"))
          }
        }
      }
  }

  object Favicon {
    val route =
      path("favicon.ico") {
        get {
          log.info("[favicon] favicon.ico (no-op) (GET)")
          complete(HttpEntity(ContentTypes.`text/html(UTF-8)`, ""))
        }
      }
  }

  def startHttp(lineupActor: ActorRef[Lineup.LineupActor.Request]): Unit = {
    val routes =
      Discover.route ~
      Lineup.route(lineupActor) ~
      Channel.route ~
      Guide.route ~
      Favicon.route

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

    val daemon = if (args.length == 1) args(0) == "-d" else false
    val break = if (daemon) "CTRL-C" else "RETURN"
    val url = s"http://${PROXY_IP.getHostAddress}:${PROXY_PORT}"
    val message = s"Server now online. Please navigate to ${url}\nPress ${break} to stop..."

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

object FFMpegDelegate {
  sealed trait Request
  object Request {
    case class Status(replyTo: ActorRef[Response]) extends Request
    case class Start(replyTo: ActorRef[Response]) extends Request
    case class Stop(replyTo: ActorRef[Response]) extends Request
  }

  sealed trait Response
  object Response {
    case class Status(code: Int, replyTo: ActorRef[Request]) extends Response
  }

  trait Error extends Exception
  object Error {
    case class DuplicateFile(f: File) extends Exception(s"duplicate file: $f") with Error
    case class IncompleteFile(f: File) extends Exception(s"incomplete file: $f") with Error
  }

  def apply(file: File): Behavior[Request] =
    Behaviors.setup(context => FFMpegDelegate(context, file))
}
case class FFMpegDelegate(
  override val context: ActorContext[FFMpegDelegate.Request]
, file: File
) extends AbstractBehavior[FFMpegDelegate.Request](context) {
  val log = LoggerFactory.getLogger(this.getClass)

  import FFMpegDelegate._
  import scala.sys.process._

  val pLog = ProcessLogger(line => log.info(line), line => log.info(line))

  def cmd(in: Path) = {
    log.info("[transcode] in - '{}'", in)
    val parent = in.getParent.toString
    val root = getBaseName(in.toFile).getOrElse("")
    log.info("[transcode] root - '{}'", root)
    val out = s"${parent}/${root}.mp4"
    val cmd = s"ffmpeg -hwaccel qsv -c:v h264_qsv -i ${in} -c:v h264_qsv -global_quality 30 ${out}"
    log.info("[transcode] command - {}", cmd)
    cmd
  }

  var proc: Option[Process] = None

  implicit val ec: scala.concurrent.ExecutionContextExecutor = scala.concurrent.ExecutionContext.global

  override def onMessage(message: Request): Behavior[Request] = message match {
    case Request.Status(sender) =>
      log.info("[transcode] received status request from {}", sender)
      val code = proc.fold (0) (p => if (!p.isAlive()) p.exitValue() else 1)
      sender ! Response.Status(code=code,replyTo=context.self)
      Behaviors.same
    case Request.Start(sender) if proc.isEmpty =>
      log.info("[transcode] request start for {}", sender)
      prepare(file) match {
        case Success(path) =>
          log.info("[transcode] convert path {}", path)
          proc = Some(cmd(path).run(pLog))
          sender ! Response.Status(code=1,replyTo=context.self)
        case other =>
          log.info("[transcode] fail wth {}", other)
          sender ! Response.Status(code=0,replyTo=context.self)
      }
      Behaviors.same
    case Request.Stop(sender) if proc.nonEmpty =>
      log.info("[transcode] received stop from {}", sender)
      proc.foreach(_.destroy())
      proc = None
      release(file) match {
        case Success(_) =>
          sender ! Response.Status(code=0,replyTo=context.self)
        case Failure(t) =>
          log.error("[transcode] failure - {}", t.getMessage)
          sender ! Response.Status(code=1,replyTo=context.self)
      }
      Behaviors.same
    case other =>
      log.info("[transcode] unhandled {}", other)
      Behaviors.same
  }

  def getBaseName(file: File): Option[String] = {
    log.info("[getBaseName] incoming file {}", file)
    val name = file.getName
    val result = if (!name.contains(".")) {
      None
    } else {
      val ext = name.substring(name.lastIndexOf(".") + 1)
      Some(name.substring(0, name.length - ext.length - 1))
    }
    log.info(s"[getBaseName] base name $result")
    result
  }

  val destExt = "mp4"

  def prepare(file: File): Try[Path] = {
    log.info("[prepare] incoming {}", file)
    val path = file.toPath

    val result = FsUtil.Attr.lock(path)
    log.info(s"[prepare] set state on path $path ? $result")

    val rootPath = file.getParentFile.toPath

    log.info("[prepare] root path {}", rootPath)
    val destName =
      getBaseName(file)
        .map(n => s"${n}.${destExt}")
        .getOrElse("")

    val destFile = Paths.get(rootPath.toString, destName)
    if (destFile.toFile.exists) {
      log.info(s"[prepare] destination already exists: $destFile")
      Failure(Error.DuplicateFile(file))
    } else if (file.lastModified > System.currentTimeMillis - 30000) {
      log.info(s"The file $file was modified less than 30 seconds ago. It may still be in state of being copied to this location. Skipping it for now.")
      Failure(Error.IncompleteFile(file))
    } else {
      Success(file.toPath)
    }
  }

  def release(file: File): Try[Unit] = {
    val path = file.toPath
    log.info(s"[release] drop state of $path")
    FsUtil.Attr.encoded(path)
  }
}

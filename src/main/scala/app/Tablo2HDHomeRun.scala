package app

import org.apache.pekko

import pekko.actor.typed.scaladsl.{ AbstractBehavior, ActorContext, Behaviors }
import pekko.actor.typed.{ ActorRef, ActorSystem, Behavior }

import pekko.http.scaladsl.Http
import pekko.http.scaladsl.model._
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
  final case object Poll extends Command

  private final case class FsScanResponse(response: FsScan.Response) extends Command
  private final case class FsQueueResponse(response: FsQueue.Response) extends Command

  object FsQueue {
    implicit val system: ActorSystem[app.Tablo2HDHomeRun.Command] = AppContext.system

    val bufferSize = 1000

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
              parent.map(ref => ref ! QueueProxy.Response.Complete)
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
    val queue = Source
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
            case fail =>
              ()
          }

          Future.successful(p.toFile)
      }
      .toMat(Sink.foreach(p => system.log.info(s"[queue] completed $p")))(Keep.left)
      .run()

    def +=(e: FsQueue.Enqueue): Unit = queue.offer(e)
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
          case FsScanResponse(resp @ FsScan.Ack(paths,replyTo)) =>
            context.log.info(s"[fsnotify:schedule] received $resp message")
            paths.foreach { case path =>
              context.log.info(s"[fsnotify:schedule] put $path in queue")
              FsQueue += FsQueue.Enqueue(p=path,replyTo=fsQueueResponseMapper)
            }
            Behaviors.same
          case FsQueueResponse(resp @ FsQueue.Response.Complete(p)) =>
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
  final case object Stop extends Command

  sealed trait Response
  final case class Ack(paths: Seq[Path], from: ActorRef[Scan]) extends Response

  def apply(ext: Seq[String]): Behavior[Command] = Behaviors.receive {
    case (context, message) =>
      message match {
        case Scan(root,replyTo) =>
          context.log.info(s"[standby] scan $root (replyTo $replyTo)")
          val self = context.self
          scan(root=root,ext=ext)(context.log).map {
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
    Files.find(root, Integer.MAX_VALUE, (_, a) => a.isRegularFile)
      .toScala(Vector)
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
  }
}

object AppContext {
  implicit val system: ActorSystem[Tablo2HDHomeRun.Command] =
    ActorSystem(Tablo2HDHomeRun(), "tablo2hdhomerun-daemon")
}

object Tablo2HDHomeRun extends App {
  val log = LoggerFactory.getLogger(this.getClass)

  sealed trait Command
  final case class StartWatch(path: Path) extends Command

  private final case class FsMonitorResponse(response: FsMonitor.Response) extends Command

  def apply(): Behavior[Command] = Behaviors.setup { context =>
    val responseMapper: ActorRef[FsMonitor.Response] =
      context.messageAdapter(resp => FsMonitorResponse(resp))

    Behaviors.receiveMessage[Command] {
      case StartWatch(path) =>
        val monitor = context.spawn(FsMonitor(), "monitor")
        context.log.info(s"[apply] created monitor $monitor")
        monitor ! FsMonitor.Watch(path=path,ext=Seq("ts,","mkv"),replyTo=responseMapper)
        Behaviors.same
      case FsMonitorResponse(resp) =>
        context.log.info(s"[apply] received response $resp - shutdown")
        Behaviors.stopped
    }
  }

  implicit val system: ActorSystem[app.Tablo2HDHomeRun.Command] = AppContext.system
  // needed for the future flatMap/onComplete in the end
  implicit val ec: scala.concurrent.ExecutionContext = system.executionContext

//  val root = if (args.length == 1) args(0) else "/tmp"

//  system ! StartWatch(Paths.get(root))
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
    , DeviceID: String = "12345678"
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
    }

    case class Channel(
      GuideNumber: String
    , GuideName: String
    , Tags: Seq[Channel.Tag]
    , URL: Uri
    )
    object Channel {
      sealed trait Tag
      object Tag {
        case object Favorite extends Tag
        case object DRM extends Tag
      }

      object JsonProtocol {
        import Response.JsonProtocol.uriFormat
        implicit object tagFormat extends JsonFormat[Tag] {
          def read(js: JsValue): Tag = js match {
            case JsString("Favorite") =>
              Tag.Favorite
            case JsString("DRM") =>
              Tag.DRM
            case _ =>
              deserializationError(s"Expected Tag value (Favorite or DRM), but got: $js")
          }
          def write(tag: Tag): JsValue = tag match {
            case Tag.Favorite =>
              JsString("Favorite")
            case Tag.DRM =>
              JsString("DRM")
          }
        }
        implicit val channelFormat: JsonFormat[Channel] = jsonFormat4(Channel.apply)
      }
    }
  }

//  val ARG_IP = if (args.length == 1) args(0) else "128.0.0.1"

  val TABLO_IP = InetAddress.getByName("192.168.11.219")
  val TABLO_PROTOCOL = "http"
  val TABLO_PORT = 8885

  val PROXY_IP = InetAddress.getByName("192.168.11.5")
  val PROXY_PORT = 8080

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

  import Response.Discover
  val discover = Discover(friendlyName="Tablo Legacy Gen Proxy",localIp=PROXY_IP)

  import pekko.http.scaladsl.unmarshalling.Unmarshal
  import pekko.http.scaladsl.marshalling.Marshal

  import scala.concurrent.Future

  val route =
    path("discover.json") {
      get {
        import Discover.JsonProtocol.discoverFormat
        complete(HttpEntity(ContentTypes.`application/json`, discover.toJson.compactPrint))
      }
    } ~
    path("lineup.json") {
      get {
        import pekko.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._

        import JsonProtocol._

        val baseUrl = discover.proxyAddress(TABLO_IP,TABLO_PORT)
        val getUri = baseUrl.withPath(Uri.Path("/guide/channels"))
        val postUri = baseUrl.withPath(Uri.Path("/batch"))

        val responseFuture: Future[HttpResponse] = Http().singleRequest(HttpRequest(uri = getUri))
        val channelPathsFuture: Future[Seq[String]] = responseFuture.flatMap { response =>
          log.info(s"[lineup] guide/channels (GET) - $response")
          Unmarshal(response.entity).to[String].map(_.parseJson.convertTo[Seq[String]])
        }
        val detailedInfoFuture: Future[Map[String, ChannelObject]] = channelPathsFuture.flatMap { paths =>
          val jsonEntityFuture = Marshal(paths.toJson).to[RequestEntity]
          jsonEntityFuture.flatMap { entity =>
            val postRequest = HttpRequest(
              method = HttpMethods.POST
            , uri = postUri
            , entity = entity.withContentType(ContentTypes.`application/json`)
            )

            Http().singleRequest(postRequest).flatMap { response =>
              log.info(s"[lineup] batch (POST) - $response")
              Unmarshal(response.entity).to[String].map(_.parseJson.convertTo[Map[String, ChannelObject]])
            }
          }
        }

        onComplete(detailedInfoFuture) {
          case Success(data) =>
            log.info("[lineup] channel details:")
            val response = data.map { case (path,info @ ChannelObject(_,_,channel)) =>
              log.info(s"[lineup] $path => ${channel.call_sign}, ${channel.network.getOrElse("None")}")
              val num = s"${channel.major}.${channel.minor}"
              val url = s"${discover.BaseURL.withPath(Uri.Path(s"/channel/${info.object_id}"))}"
              val src = s"${discover.BaseURL.withPath(Uri.Path(s"/guide/channels/${info.object_id}/watch"))}"
              JsObject(
                "GuideNumber" -> JsString(num)
              , "GuideName" -> JsString(channel.call_sign)
              , "URL" -> JsString(url)
              , "type" -> JsString(channel.source)
              , "srcURL" -> JsString(src)
              )
            }
            complete(HttpEntity(ContentTypes.`application/json`, response.toJson.compactPrint))
          case Failure(ex) =>
            log.info(s"[lineup] Failed: ${ex.getMessage}")
            complete(HttpResponse(StatusCodes.InternalServerError, entity = "Unable to produce channel lineup"))
        }
      }
    } ~
    path("lineup_status.json") {
      get {
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

        import LineupStatus.JsonProtocol.lineupStatusFormat
        complete(HttpEntity(ContentTypes.`application/json`, LineupStatus().toJson.compactPrint))
      }
    } ~
    path("channel" / LongNumber) { channelId =>
      get {
        object Request {
          case class WatchRequest(
            bandwidth: Int = 1000
          , no_fast_startup: Boolean = false
          )
          object WatchRequest {
            object JsonProtocol {
              implicit val watchRequestFormat: JsonFormat[WatchRequest] = jsonFormat2(WatchRequest.apply)
            }

            val uri = discover.proxyAddress(TABLO_IP,TABLO_PORT).withPath(Uri.Path(s"/guide/channels/$channelId/watch"))
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

        import Response.JsonProtocol._

        val tunersFuture: Future[Seq[Response.Tuners]] =
          Http().singleRequest(Request.Tuners.httpRequest).flatMap { response =>
            import Response.Tuners.JsonProtocol.tunersFormat
            log.info(s"[channel] server/tuners (POST) - $response")
            Unmarshal(response.entity).to[String].map(_.parseJson.convertTo[Seq[Response.Tuners]])
          }

        case object NoAvailableTunersError extends Exception("No available tuners")

        val watchFuture: Future[Response.WatchResponse] = tunersFuture.flatMap { tuners =>
          val available = tuners.filterNot(_.in_use).size
          log.info(s"[channel] available tuners - $available")
          if (available > 0) {
            import Request.WatchRequest.JsonProtocol.watchRequestFormat
            val httpRequest = HttpRequest(
              method = HttpMethods.POST
            , uri = Request.WatchRequest.uri
            , entity = HttpEntity(ContentTypes.`application/json`, Request.WatchRequest().toJson.compactPrint)
            )
            Http().singleRequest(httpRequest).flatMap { response =>
              log.info(s"[channel] guide/channels/$channelId/watch (POST) - $response")
              Unmarshal(response.entity).to[String].map(_.parseJson.convertTo[Response.WatchResponse])
            }
          } else {
            log.info(s"[channel] no available tuners (0/${tuners.size})")
            Future.failed(NoAvailableTunersError)
          }
        }

        import pekko.util._

        def stream(playlist_url: Uri): Source[ByteString, _] = Source.lazySource { () =>
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
    } ~
    path("guide.xml") {
      get {
        log.info("[guide] fetch guide")
        complete(HttpEntity(ContentTypes.`text/xml(UTF-8)`, "<XML></XML>"))
      }
    } ~
    path("favicon.ico") {
      get {
        log.info("[favicon] fetch favicon (no-op)")
        complete(HttpEntity(ContentTypes.`text/html(UTF-8)`, ""))
      }
    }

  val bindingFuture = Http().newServerAt(PROXY_IP.getHostAddress.toString, PROXY_PORT).bind(route)

  println(s"Server now online. Please navigate to http://${PROXY_IP}:${PROXY_PORT}\nPress RETURN to stop...")
    StdIn.readLine() // let it run until user presses return
    bindingFuture
      .flatMap(_.unbind()) // trigger unbinding from the port
      .onComplete(_ => system.terminate()) // and shutdown when done
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
    case class Status(code: Int,replyTo: ActorRef[Request]) extends Response
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
      proc.map(_.destroy())
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

object EmbeddedTranscoder extends App {

  import org.jcodec.api.transcode.SinkImpl
  import org.jcodec.api.transcode.SourceImpl
  import org.jcodec.api.transcode.Transcoder
  import org.jcodec.common.Codec
  import org.jcodec.common.Format
  import org.jcodec.common.JCodecUtil
  import org.jcodec.common.Tuple.{triple,_3 => Triple}
  import org.jcodec.common.TrackType

  import org.slf4j.LoggerFactory

  import scala.jdk.CollectionConverters._

  val log = LoggerFactory.getLogger(this.getClass)

  val input = Paths.get("ts/sample.ts").toFile
  val output = Paths.get("/tmp/sample.m4v").toFile

  val inFormat = JCodecUtil.detectFormat(input)
  val demuxer = JCodecUtil.createM2TSDemuxer(input, TrackType.VIDEO)

  log.info(s"input video ? ${inFormat.isVideo} (demuxer ${demuxer.v0} | ${demuxer.v1})")

  val tracks = demuxer.v1.getVideoTracks.asScala
  val trackNo = tracks.foldLeft (0) { case (acc, track) =>
    val decoder = JCodecUtil.detectDecoder(track.nextFrame.getData)
    log.info(s"track decoder - ${decoder}")
    acc
  }

  log.info(s"transcode $input -> $output")
  log.info(s"  input ${Codec.MPEG2} | ${demuxer.v0} | ${trackNo}")

  val videoCodec: Triple[Integer,Integer,Codec] = triple(demuxer.v0, trackNo, Codec.MPEG2)
  val audioCodec: Triple[Integer,Integer,Codec] = triple(0, 0, Codec.AC3)
  val source = new SourceImpl(input.getAbsolutePath, inFormat/*Format.MPEG_TS*/, videoCodec, audioCodec)
  val sink = new SinkImpl(output.getAbsolutePath, Format.H264, Codec.H264, Codec.AAC)

  val builder = Transcoder.newTranscoder()
  builder.addSource(source)
  builder.addSink(sink)
  builder.setAudioMapping(0, 0, false)
  builder.setVideoMapping(0, 0, false)

  val transcoder = builder.create()
  transcoder.transcode()

  log.info("normal termination")
}

object VideoTranscoder extends App {
  import org.jcodec.api.SequenceEncoder
  import org.jcodec.common.model.Picture
  import org.jcodec.common.model.ColorSpace
  import java.awt.image.BufferedImage
  import java.io.File
  import javax.imageio.ImageIO

  // Input and output file paths
  val inputFilePath = "path/to/input/video.mpg"
  val outputFilePath = "path/to/output/video.mp4"

  // Load the video
  val video = new File(inputFilePath)

  // Create a SequenceEncoder for H.264
  val encoder = SequenceEncoder.createSequenceEncoder(new File(outputFilePath), 25)

  // Read frames from the video and encode them to H.264
  val imageReader = ImageIO.getImageReadersByFormatName("jpeg").next()
  imageReader.setInput(ImageIO.createImageInputStream(video))

  val numFrames = imageReader.getNumImages(true)
  for (i <- 0 until numFrames) {
    val bufferedImage: BufferedImage = imageReader.read(i)
    val picture = fromBufferedImage(bufferedImage)
    encoder.encodeNativeFrame(picture)
  }

  // Finalize the encoding
  encoder.finish()

  println("Transcoding complete.")

  // Convert BufferedImage to JCodec Picture
  def fromBufferedImage(image: BufferedImage): Picture = {
    val width = image.getWidth
    val height = image.getHeight
    val pixels = new Array[Array[Byte]](3)
    pixels(0) = new Array[Byte](width * height)
    pixels(1) = new Array[Byte](width * height)
    pixels(2) = new Array[Byte](width * height)

    var counter = 0
    for (y <- 0 until height) {
      for (x <- 0 until width) {
        val rgb = image.getRGB(x, y)
        pixels(0)(counter) = ((rgb >> 16) & 0xFF).toByte
        pixels(1)(counter) = ((rgb >> 8) & 0xFF).toByte
        pixels(2)(counter) = (rgb & 0xFF).toByte
        counter += 1
      }
    }

    Picture.createPicture(width, height, pixels, ColorSpace.RGB)
  }
}

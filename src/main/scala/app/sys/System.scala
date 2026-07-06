package app.sys

import java.io.File
import java.nio.file.{Path, Paths}

import org.apache.pekko

import pekko.actor.typed.scaladsl.{AbstractBehavior, ActorContext, Behaviors}
import pekko.actor.typed.{ActorRef, ActorSystem, Behavior}
import pekko.stream.KillSwitches
import pekko.stream.scaladsl.{Keep, Sink, Source}

import org.slf4j.{Logger, LoggerFactory}

import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}

import app.AppContext

object FsUtil {
  import java.nio.charset.Charset
  import java.nio.file.{Files, Path}
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
  import scala.concurrent.{ExecutionContext, Future}
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
          context.log.debug("[scan] start root={}", root)
          val self = context.self
          scan(root=root,ext=ext)(using log).foreach {
            case result =>
              log.info("[scan] complete root={} queued={}", root, result.size)
              replyTo ! Ack(paths=result,from=self)
              self ! Stop
          }
          Behaviors.same
        case Stop =>
          context.log.debug("[scan] shutdown")
          Behaviors.stopped
      }
  }

  private def scan(root: Path, ext: Seq[String])(implicit log: Logger): Future[Seq[Path]] = Future {
    import java.nio.file.Files
    import java.nio.file.attribute.{UserDefinedFileAttributeView => Xattr}
    import scala.jdk.CollectionConverters._
    import scala.jdk.StreamConverters._

    var examined = 0
    val result = Files
      .find(root, Integer.MAX_VALUE, (_, a) => a.isRegularFile)
      .toScala(LazyList)
      .filter { case path =>
        examined += 1
        val kind = ext.find(path.getFileName.toFile.getName.endsWith(_))
        val scanned =
          Try {
            Files
              .getFileAttributeView(path, classOf[Xattr])
              .list
              .asScala
              .exists(_ == FsUtil.Attr.Key.FS_STATE)
          }
          .getOrElse(false)
        val hit = kind.isDefined && !scanned
        log.debug("[scan] file path={} hit={}", path, hit)
        hit
      }
      .toSeq
    log.debug("[scan] examined root={} count={}", root, examined)
    result
  }
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

  val pLog = ProcessLogger(
    line => log.debug("[transcode] ffmpeg stdout: {}", line)
  , line => log.debug("[transcode] ffmpeg stderr: {}", line)
  )

  def cmd(in: Path) = {
    log.debug("[transcode] input path={}", in)
    val parent = in.getParent.toString
    val root = getBaseName(in.toFile).getOrElse("")
    val out = s"${parent}/${root}.mp4"
    val cmd = s"ffmpeg -hwaccel qsv -c:v h264_qsv -i ${in} -c:v h264_qsv -global_quality 30 ${out}"
    log.info("[transcode] command file={}", in.getFileName)
    cmd
  }

  var proc: Option[Process] = None

  implicit val ec: scala.concurrent.ExecutionContextExecutor = scala.concurrent.ExecutionContext.global

  override def onMessage(message: Request): Behavior[Request] = message match {
    case Request.Status(sender) =>
      log.debug("[transcode] status request")
      val code = proc.fold (0) (p => if (!p.isAlive()) p.exitValue() else 1)
      sender ! Response.Status(code=code,replyTo=context.self)
      Behaviors.same
    case Request.Start(sender) if proc.isEmpty =>
      log.info("[transcode] start file={}", file)
      prepare(file) match {
        case Success(path) =>
          proc = Some(cmd(path).run(pLog))
          sender ! Response.Status(code=1,replyTo=context.self)
        case Failure(ex) =>
          log.warn("[transcode] start skipped file={} reason={}", file, ex.getMessage)
          sender ! Response.Status(code=0,replyTo=context.self)
      }
      Behaviors.same
    case Request.Stop(sender) if proc.nonEmpty =>
      log.info("[transcode] stop file={}", file)
      proc.foreach(_.destroy())
      proc = None
      release(file) match {
        case Success(_) =>
          log.info("[transcode] complete file={}", file)
          sender ! Response.Status(code=0,replyTo=context.self)
        case Failure(t) =>
          log.error("[transcode] release failed file={}", file, t)
          sender ! Response.Status(code=1,replyTo=context.self)
      }
      Behaviors.same
    case other =>
      log.debug("[transcode] unhandled message={}", other)
      Behaviors.same
  }

  def getBaseName(file: File): Option[String] = {
    val name = file.getName
    if (!name.contains(".")) {
      None
    } else {
      val ext = name.substring(name.lastIndexOf(".") + 1)
      Some(name.substring(0, name.length - ext.length - 1))
    }
  }

  val destExt = "mp4"

  def prepare(file: File): Try[Path] = {
    val path = file.toPath

    val result = FsUtil.Attr.lock(path)
    log.debug("[transcode] lock path={} success={}", path, result.isSuccess)

    val rootPath = file.getParentFile.toPath

    val destName =
      getBaseName(file)
        .map(n => s"${n}.${destExt}")
        .getOrElse("")

    val destFile = Paths.get(rootPath.toString, destName)
    if (destFile.toFile.exists) {
      log.warn("[transcode] duplicate destination file={}", file)
      Failure(Error.DuplicateFile(file))
    } else if (file.lastModified > System.currentTimeMillis - 30000) {
      log.warn("[transcode] file still copying file={}", file)
      Failure(Error.IncompleteFile(file))
    } else {
      Success(file.toPath)
    }
  }

  def release(file: File): Try[Unit] = {
    val path = file.toPath
    log.debug("[transcode] release path={}", path)
    FsUtil.Attr.encoded(path)
  }
}

object FsNotify {
  val log = LoggerFactory.getLogger(this.getClass)

  sealed trait Command
  case object Poll extends Command

  private final case class FsScanResponse(response: FsScan.Response) extends Command
  private final case class FsQueueResponse(response: FsQueue.Response) extends Command

  object FsQueue {
    implicit def system: ActorSystem[Nothing] = AppContext.system

    val bufferSize = 100

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
        log.debug("[transcode] worker spawned file={}", file)
        worker ! FFMpegDelegate.Request.Start(replyTo=ffmpegResponseMapper)
        worker
      }

      val killSwitch = KillSwitches.shared(s"queue-proxy-killswitch-${java.util.UUID.randomUUID}")
      log.debug("[transcode] proxy started file={}", file)
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
          log.debug("[transcode] proxy response resp={}", resp)
          resp match {
            case FFMpegDelegate.Response.Status(code,_) if code != 0 =>
              Behaviors.same
            case FFMpegDelegate.Response.Status(code,replyTo) =>
              log.debug("[transcode] proxy stopping exitCode={}", code)
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

    import pekko.actor.typed.scaladsl.AskPattern._
    import pekko.util.Timeout
    import scala.concurrent.Future
    val queue =
      Source
        .queue[FsQueue.Enqueue](bufferSize)
        .mapAsync(parallelism=1) {
          case Enqueue(p,replyTo) =>
            val uuid = java.util.UUID.randomUUID
            val proxy = system.systemActorOf(QueueProxy(p.toFile), s"ffmpeg-proxy-${uuid}")
            log.debug("[transcode] queue proxy file={}", p)

            implicit val ec: scala.concurrent.ExecutionContext = system.executionContext
            implicit val timeout: Timeout = 10.minutes
            val status: Future[QueueProxy.Response] =
              proxy.ask(ref => QueueProxy.Request.Notify(replyTo=ref))

            status.onComplete {
              case Success(QueueProxy.Response.Complete) =>
                log.info("[transcode] queue complete file={}", p)
                replyTo ! FsQueue.Response.Complete(p)
              case Success(QueueProxy.Response.Pending) =>
                ()
              case _ =>
                ()
            }

            Future.successful(p.toFile)
        }
        .toMat(Sink.foreach(p => log.debug("[transcode] queue processed file={}", p)))(Keep.left)
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
            context.log.debug("[scan] poll scheduled root={}", path)
            implicit val system = AppContext.system
            val _ =
              Source
                .tick(10.second, 10.second, ())
                .map { case _ =>
                  val uuid = java.util.UUID.randomUUID
                  val worker = system.systemActorOf(FsScan(ext), s"FsScan-scan-${uuid}")
                  worker ! FsScan.Scan(root=path,replyTo=fsResponseMapper)
                }
                .runWith(Sink.ignore)
            Behaviors.same
          case FsScanResponse(FsScan.Ack(paths,_)) =>
            if (paths.nonEmpty) log.info("[scan] enqueue count={} root={}", paths.size, path)
            paths.foreach { case queuedPath =>
              log.debug("[scan] enqueue path={}", queuedPath)
              FsQueue += FsQueue.Enqueue(p=queuedPath,replyTo=fsQueueResponseMapper)
            }
            Behaviors.same
          case FsQueueResponse(FsQueue.Response.Complete(p)) =>
            log.info("[transcode] finished path={}", p)
            Behaviors.same
        }
      }
    }
}

object FsMonitor {
  val log = LoggerFactory.getLogger(this.getClass)

  sealed trait Command
  final case class Watch(path: Path, ext: Seq[String], replyTo: ActorRef[Ack]) extends Command

  sealed trait Response
  final case class Ack(path: Path, from: ActorRef[Watch]) extends Response

  def apply(): Behavior[Watch] = Behaviors.receive {
    case (context, message) =>
      val worker = context.spawn(FsNotify(message.path, message.ext), "FsNotify-worker")
      context.log.info("[scan] monitor started root={}", message.path)
      worker ! FsNotify.Poll
      Behaviors.same
  }
}
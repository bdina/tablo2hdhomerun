package app.sys

import java.io.File
import java.nio.file.{Path, Paths}

import org.apache.pekko

import pekko.actor.typed.scaladsl.Behaviors
import pekko.actor.typed.{ActorRef, ActorSystem, Behavior}
import pekko.stream.scaladsl.{Keep, Sink, Source}

import org.slf4j.{Logger, LoggerFactory}

import scala.concurrent.duration._
import scala.util.{Failure, Success, Try, boundary}
import scala.util.boundary.break
import scala.util.control.NoStackTrace

import app.AppContext

/** Typed domain failures for local filesystem and transcoding workflows. */
sealed trait TranscodeError extends Exception with NoStackTrace

object TranscodeError {
  case class DuplicateFile(f: File) extends Exception(s"duplicate file: $f") with TranscodeError
  case class IncompleteFile(f: File) extends Exception(s"incomplete file: $f") with TranscodeError
  case class InvalidPath(f: File) extends Exception(s"file has no parent directory: $f") with TranscodeError
  case class MissingXattr(path: Path) extends Exception(s"user-defined file attributes unsupported for path: $path") with TranscodeError
}

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
    def write(path: Path, key: String, value: String): Try[Int] =
      Option(Files.getFileAttributeView(path, classOf[Xattr])) match {
        case Some(xattr) => Try(xattr.write(key, Charset.defaultCharset.encode(value)))
        case None        => Failure(TranscodeError.MissingXattr(path))
      }
    def lock(path: Path): Try[Unit] =
      write(path, Key.FS_STATE, Value.LOCK).map(_ => ())
    def encoded(path: Path): Try[Unit] =
      write(path, Key.FS_STATE, Value.ENCODED).map(_ => ())
  }
}

object FsScan {
  import scala.concurrent.{ExecutionContext, Future}

  val log = LoggerFactory.getLogger(this.getClass)

  sealed trait Command
  final case class Scan(root: Path, replyTo: ActorRef[Ack]) extends Command
  case object Stop extends Command
  private final case class ScanFinished(result: Try[Seq[Path]], root: Path, replyTo: ActorRef[Ack]) extends Command

  sealed trait Response
  final case class Ack(paths: Seq[Path], from: ActorRef[Scan]) extends Response

  def apply(ext: Seq[String]): Behavior[Command] = Behaviors.receive {
    case (context, message) =>
      message match {
        case Scan(root, replyTo) =>
          context.log.debug("[scan] start root={}", root)
          context.pipeToSelf(scan(root = root, ext = ext)(using log, context.executionContext))(res =>
            ScanFinished(res, root, replyTo)
          )
          Behaviors.same
        case ScanFinished(Success(result), root, replyTo) =>
          log.info("[scan] complete root={} queued={}", root, result.size)
          replyTo ! Ack(paths = result, from = context.self)
          Behaviors.stopped
        case ScanFinished(Failure(ex), root, replyTo) =>
          log.warn("[scan] failed root={} error={}", root, ex.getMessage)
          replyTo ! Ack(paths = Seq.empty, from = context.self)
          Behaviors.stopped
        case Stop =>
          context.log.debug("[scan] shutdown")
          Behaviors.stopped
      }
  }

  private def scan(root: Path, ext: Seq[String])(using log: Logger, ec: ExecutionContext): Future[Seq[Path]] = Future {
    import java.nio.file.Files
    import java.nio.file.attribute.{UserDefinedFileAttributeView => Xattr}
    import scala.jdk.CollectionConverters._
    import scala.util.Using

    var examined = 0
    val result = Using.resource(Files.find(root, Integer.MAX_VALUE, (_, a) => a.isRegularFile)) { stream =>
      val builder = Vector.newBuilder[Path]
      val it = stream.iterator()
      while (it.hasNext) {
        val path = it.next()
        examined += 1
        val fileName = path.getFileName.toString.toLowerCase
        val kind = ext.exists(e => fileName.endsWith(s".${e.toLowerCase.stripPrefix(".")}"))
        val scanned =
          Try {
            Files
              .getFileAttributeView(path, classOf[Xattr])
              .list
              .asScala
              .exists(_ == FsUtil.Attr.Key.FS_STATE)
          }
          .getOrElse(false)
        val hit = kind && !scanned
        log.debug("[scan] file path={} hit={}", path, hit)
        if (hit) {
          builder += path
        }
      }
      builder.result()
    }
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

  type Error = TranscodeError
  val Error = TranscodeError

  def getBaseName(file: File): Option[String] = {
    val name = file.getName
    if (!name.contains(".")) {
      None
    } else {
      val ext = name.substring(name.lastIndexOf(".") + 1)
      Some(name.substring(0, name.length - ext.length - 1))
    }
  }

  def buildCommand(in: Path): Seq[String] = {
    val parent = in.getParent.toString
    val root = getBaseName(in.toFile).getOrElse("")
    val out = s"${parent}/${root}.mp4"
    Seq(
      "ffmpeg"
    , "-hwaccel", "qsv"
    , "-c:v", "h264_qsv"
    , "-i", in.toString
    , "-c:v", "h264_qsv"
    , "-global_quality", "30"
    , out
    )
  }

  val destExt = "mp4"
  private val companionLog = LoggerFactory.getLogger(this.getClass)

  /** Validates transcode input readiness and acquires the extended-attribute lock without throwing. */
  def prepare(file: File): Try[Path] = boundary {
    val path = file.toPath

    val result = FsUtil.Attr.lock(path)
    companionLog.debug("[transcode] lock path={} success={}", path, result.isSuccess)

    val parent = Option(file.getParentFile) match {
      case Some(p) => p
      case None    => break(Failure(TranscodeError.InvalidPath(file)))
    }

    val destName =
      getBaseName(file)
        .map(n => s"${n}.${destExt}")
        .getOrElse("")

    val destFile = Paths.get(parent.toPath.toString, destName)
    if (destFile.toFile.exists) {
      companionLog.warn("[transcode] duplicate destination file={}", file)
      break(Failure(TranscodeError.DuplicateFile(file)))
    }
    if (file.lastModified > System.currentTimeMillis - 30000) {
      companionLog.warn("[transcode] file still copying file={}", file)
      break(Failure(TranscodeError.IncompleteFile(file)))
    }
    Success(file.toPath)
  }

  def apply(file: File): Behavior[Request] = Behaviors.setup { context =>
    import scala.sys.process._

    val log = companionLog
    val pLog = ProcessLogger(
      line => log.debug("[transcode] ffmpeg stdout: {}", line)
    , line => log.debug("[transcode] ffmpeg stderr: {}", line)
    )

    def cmd(in: Path): Seq[String] = {
      log.debug("[transcode] input path={}", in)
      val args = buildCommand(in)
      log.info("[transcode] command file={}", in.getFileName)
      args
    }

    def release(f: File): Try[Unit] = {
      val path = f.toPath
      log.debug("[transcode] release path={}", path)
      FsUtil.Attr.encoded(path)
    }

    var proc: Option[Process] = None

    Behaviors.receiveMessage {
      case Request.Status(sender) =>
        log.debug("[transcode] status request")
        val code = proc.fold(0)(p => if (!p.isAlive()) p.exitValue() else 1)
        sender ! Response.Status(code = code, replyTo = context.self)
        Behaviors.same
      case Request.Start(sender) if proc.isEmpty =>
        log.info("[transcode] start file={}", file)
        prepare(file) match {
          case Success(path) =>
            proc = Some(Process(cmd(path)).run(pLog))
            sender ! Response.Status(code = 1, replyTo = context.self)
          case Failure(ex) =>
            log.warn("[transcode] start skipped file={} reason={}", file, ex.getMessage)
            sender ! Response.Status(code = 0, replyTo = context.self)
        }
        Behaviors.same
      case Request.Stop(sender) if proc.nonEmpty =>
        log.info("[transcode] stop file={}", file)
        proc.foreach(_.destroy())
        proc = None
        release(file) match {
          case Success(_) =>
            log.info("[transcode] complete file={}", file)
            sender ! Response.Status(code = 0, replyTo = context.self)
          case Failure(t) =>
            log.error("[transcode] release failed file={}", file, t)
            sender ! Response.Status(code = 1, replyTo = context.self)
        }
        Behaviors.same
      case Request.Stop(sender) =>
        sender ! Response.Status(code = 0, replyTo = context.self)
        Behaviors.same
      case other =>
        log.debug("[transcode] unhandled message={}", other)
        Behaviors.same
    }
  }
}

object FsNotify {
  val log = LoggerFactory.getLogger(this.getClass)

  sealed trait Command
  case object Poll extends Command
  private case object PollTick extends Command
  private case object PollTimerKey

  private final case class FsScanResponse(response: FsScan.Response) extends Command
  private final case class FsQueueResponse(response: FsQueue.Response) extends Command

  object FsQueue {
    inline given system: ActorSystem[Nothing] = AppContext.system

    val bufferSize = 100

    import java.io.File
    object QueueProxy {
      sealed trait Request
      object Request {
        case class Notify(replyTo: ActorRef[QueueProxy.Response]) extends Request
      }

      private case object PollStatus extends Request
      private case object PollStatusKey
      private final case class FFMpegResponse(response: FFMpegDelegate.Response) extends Request

      sealed trait Response
      object Response {
        case object Complete extends Response
        case object Pending extends Response
      }

      def apply(file: File): Behavior[QueueProxy.Request] =
        Behaviors.setup { context =>
          Behaviors.withTimers { timers =>
            val log = context.log
            val ffmpegResponseMapper: ActorRef[FFMpegDelegate.Response] =
              context.messageAdapter(resp => FFMpegResponse(resp))

            val worker = {
              val uuid = java.util.UUID.randomUUID
              val spawned = context.spawn(FFMpegDelegate(file), s"ffmpeg-delegate-$uuid")
              log.debug("[transcode] worker spawned file={}", file)
              spawned ! FFMpegDelegate.Request.Start(replyTo = ffmpegResponseMapper)
              spawned
            }

            log.debug("[transcode] proxy started file={}", file)
            timers.startTimerWithFixedDelay(PollStatusKey, PollStatus, 10.seconds, 10.seconds)

            var parent: Option[ActorRef[QueueProxy.Response]] = None
            var stopping = false
            var completed = false

            Behaviors.receiveMessage {
              case Request.Notify(replyTo) =>
                if (completed) {
                  replyTo ! Response.Complete
                  Behaviors.stopped
                } else {
                  parent = Some(replyTo)
                  Behaviors.same
                }
              case PollStatus =>
                worker ! FFMpegDelegate.Request.Status(replyTo = ffmpegResponseMapper)
                Behaviors.same
              case FFMpegResponse(resp) =>
                log.debug("[transcode] proxy response resp={}", resp)
                resp match {
                  case FFMpegDelegate.Response.Status(code, _) if stopping =>
                    log.debug("[transcode] proxy stopped exitCode={}", code)
                    completed = true
                    parent match {
                      case Some(ref) =>
                        ref ! Response.Complete
                        Behaviors.stopped
                      case None =>
                        Behaviors.same
                    }
                  case FFMpegDelegate.Response.Status(code, _) if code != 0 =>
                    Behaviors.same
                  case FFMpegDelegate.Response.Status(code, replyTo) =>
                    log.debug("[transcode] proxy stopping exitCode={}", code)
                    stopping = true
                    timers.cancel(PollStatusKey)
                    replyTo ! FFMpegDelegate.Request.Stop(replyTo = ffmpegResponseMapper)
                    Behaviors.same
                }
            }
          }
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

            given scala.concurrent.ExecutionContext = system.executionContext
            given Timeout = 10.minutes
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
    Behaviors.setup[Command] { context =>
      Behaviors.withTimers { timers =>
        val fsResponseMapper: ActorRef[FsScan.Response] =
          context.messageAdapter(resp => FsScanResponse(resp))
        val fsQueueResponseMapper: ActorRef[FsQueue.Response] =
          context.messageAdapter(resp => FsQueueResponse(resp))

        def triggerScan(): Unit = {
          val worker = context.spawnAnonymous(FsScan(ext))
          worker ! FsScan.Scan(root = path, replyTo = fsResponseMapper)
        }

        Behaviors.receiveMessage {
          case Poll =>
            context.log.debug("[scan] poll scheduled root={}", path)
            timers.startTimerWithFixedDelay(PollTimerKey, PollTick, 10.seconds, 10.seconds)
            triggerScan()
            Behaviors.same
          case PollTick =>
            triggerScan()
            Behaviors.same
          case FsScanResponse(FsScan.Ack(paths, _)) =>
            if (paths.nonEmpty) log.info("[scan] enqueue count={} root={}", paths.size, path)
            paths.foreach { queuedPath =>
              log.debug("[scan] enqueue path={}", queuedPath)
              FsQueue += FsQueue.Enqueue(p = queuedPath, replyTo = fsQueueResponseMapper)
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
      val worker = context.spawn(FsNotify(message.path, message.ext), s"FsNotify-worker-${java.util.UUID.randomUUID}")
      context.log.info("[scan] monitor started root={}", message.path)
      worker ! FsNotify.Poll
      message.replyTo ! Ack(path = message.path, from = context.self)
      Behaviors.same
  }
}
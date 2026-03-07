package app.stream

import org.apache.pekko

import pekko.actor.typed.ActorSystem
import pekko.stream.scaladsl.{Source, StreamConverters}
import pekko.util.ByteString

import org.slf4j.LoggerFactory

import scala.util.{Failure, Success}

object FFmpegBackend extends StreamBackend {
  val log = LoggerFactory.getLogger(this.getClass)
  override def name: String = "ffmpeg"

  override def stream(playlistUrl: String)(implicit system: ActorSystem[?]): Source[ByteString, ?] = {
    implicit val ec: scala.concurrent.ExecutionContext = system.executionContext
    Source.lazySource { () =>
      val ffmpegCmd = Array(
        "ffmpeg"
      , "-i", playlistUrl
      , "-c", "copy"
      , "-f", "mpegts"
      , "-v", "repeat+level+panic"
      , "pipe:1"
      )
      val process = scala.sys.runtime.exec(ffmpegCmd)
      log.info(s"[stream:ffmpeg] execute - ${ffmpegCmd.mkString(" ")} => spawn ${process.pid}")
      StreamConverters
        .fromInputStream(() => process.getInputStream)
        .watchTermination() { (_, done) =>
          log.info(s"[stream:ffmpeg] started (pid ${process.pid})")
          done.onComplete {
            case Success(_) =>
              log.info(s"[stream:ffmpeg] terminating (pid ${process.pid})")
              process.destroy()
            case Failure(ex) =>
              log.info(s"[stream:ffmpeg] failed: ${ex.getMessage} (kill pid ${process.pid})")
              process.destroy()
          }
        }
    }
  }
}

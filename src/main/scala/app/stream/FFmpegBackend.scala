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

  override def stream(playlistUrl: String, label: String = "")(implicit system: ActorSystem[?]): Source[ByteString, ?] = {
    implicit val ec: scala.concurrent.ExecutionContext = system.executionContext
    Source.lazySource { () =>
      val ffmpegCmd = Array(
        "ffmpeg"
      , "-reconnect", "1"
      , "-reconnect_streamed", "1"
      , "-reconnect_delay_max", "10"
      , "-err_detect", "ignore_err"
      , "-i", playlistUrl
      , "-c", "copy"
      , "-f", "mpegts"
      , "-mpegts_flags", "+initial_discontinuity"
      , "-v", "warning"
      , "pipe:1"
      )
      val process = scala.sys.runtime.exec(ffmpegCmd)
      log.info("[stream:ffmpeg] start label={} pid={} playlistUrl={}", label, process.pid, playlistUrl)
      StreamConverters
        .fromInputStream(() => process.getInputStream)
        .watchTermination() { (_, done) =>
          done.onComplete {
            case Success(_) =>
              log.info("[stream:ffmpeg] complete pid={}", process.pid)
              process.destroy()
            case Failure(ex) =>
              log.warn("[stream:ffmpeg] failed pid={}", process.pid, ex)
              process.destroy()
          }
        }
    }
  }
}
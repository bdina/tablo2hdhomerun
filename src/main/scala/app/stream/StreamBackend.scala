package app.stream

import org.apache.pekko

import pekko.actor.typed.ActorSystem
import pekko.stream.scaladsl.Source
import pekko.util.ByteString

import app.Tablo2HDHomeRun

trait StreamBackend {
  def stream(playlistUrl: String)(implicit system: ActorSystem[?]): Source[ByteString, ?]
  def name: String
}

object StreamBackend {
  def apply(): StreamBackend = apply(Tablo2HDHomeRun.STREAM_BACKEND)
  def apply(backendName: String): StreamBackend = backendName match {
    case "hls" => HlsBackend
    case _ => FFmpegBackend
  }
}

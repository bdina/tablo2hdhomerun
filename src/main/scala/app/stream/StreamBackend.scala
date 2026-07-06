package app.stream

import org.apache.pekko

import pekko.actor.typed.ActorSystem
import pekko.stream.scaladsl.Source
import pekko.util.ByteString

import app.AppContext
import app.config.StreamBackendKind

trait StreamBackend {
  def stream(playlistUrl: String, label: String = "")(implicit system: ActorSystem[?]): Source[ByteString, ?]
  def name: String
}

object StreamBackend {
  def apply(): StreamBackend = apply(AppContext.config.stream.backend)

  def apply(kind: StreamBackendKind): StreamBackend = kind match {
    case StreamBackendKind.Hls => HlsBackend
    case StreamBackendKind.Ffmpeg => FFmpegBackend
  }

  def apply(backendName: String): StreamBackend = apply(StreamBackendKind.fromEnv(backendName))
}
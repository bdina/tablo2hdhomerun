package app.config

enum StreamBackendKind {
  case Hls
  case Ffmpeg

  def isHls: Boolean = this == Hls

  def envValue: String = this match {
    case Hls => "hls"
    case Ffmpeg => "ffmpeg"
  }
}

object StreamBackendKind {
  def fromEnv(value: String): StreamBackendKind = value match {
    case "hls" => StreamBackendKind.Hls
    case _ => StreamBackendKind.Ffmpeg
  }
}

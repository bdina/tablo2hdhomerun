package app

import org.apache.pekko.http.scaladsl.model.Uri

import scala.concurrent.Future

import spray.json._
import DefaultJsonProtocol._

case class ChannelData(
  id: String
, name: String
, major: Int
, minor: Int
, channelType: String
, network: Option[String]
, resolution: String
, logoUrl: Option[String]
, callSign: String
, affiliate: Option[String]
)

object ChannelData {
  object JsonProtocol extends DefaultJsonProtocol {
    implicit val channelDataFormat: JsonFormat[ChannelData] = jsonFormat10(ChannelData.apply)
  }

  def toLineupJson(channel: ChannelData, baseUrl: Uri): JsValue = {
    val num = s"${channel.major}.${channel.minor}"
    val url = s"${baseUrl.withPath(Uri.Path(s"/channel/${channel.id}"))}"
    JsObject(
      "GuideNumber" -> JsString(num)
    , "GuideName" -> JsString(channel.name)
    , "URL" -> JsString(url)
    , "type" -> JsString(channel.channelType)
    )
  }
}

case class ProgramData(
  id: String
, title: String
, description: Option[String]
, startTime: String
, endTime: String
, channelId: String
, episodeTitle: Option[String]
, seasonNumber: Option[Int]
, episodeNumber: Option[Int]
, year: Option[Int]
, genre: Option[String]
, rating: Option[String]
, isMovie: Boolean
, isSports: Boolean
, isNews: Boolean
)

object ProgramData {
  object JsonProtocol extends DefaultJsonProtocol {
    implicit val programDataFormat: JsonFormat[ProgramData] = jsonFormat15(ProgramData.apply)
  }
}

case class ChannelGuideData(
  channelId: String
, callSign: String
, major: Int
, minor: Int
, programs: Seq[ProgramData]
)

object ChannelGuideData {
  object JsonProtocol extends DefaultJsonProtocol {
    import ProgramData.JsonProtocol.programDataFormat
    implicit val channelGuideDataFormat: JsonFormat[ChannelGuideData] = jsonFormat5(ChannelGuideData.apply)
  }
}

case class TunerStatus(
  inUse: Boolean
, channel: Option[Uri]
, recording: Option[Uri]
)

object TunerStatus {
  object JsonProtocol extends DefaultJsonProtocol {
    implicit object uriFormat extends JsonFormat[Uri] {
      override def read(js: JsValue): Uri = js match {
        case JsString(value) => Uri(value)
        case JsNull => Uri.Empty
        case _ => deserializationError(s"Expected Uri path, but got $js")
      }
      override def write(uri: Uri): JsValue = JsString(uri.toString)
    }
    implicit val tunerStatusFormat: JsonFormat[TunerStatus] = jsonFormat3(TunerStatus.apply)
  }
}

case class WatchSession(
  token: String
, expires: String
, keepalive: Int
, playlistUrl: Uri
, videoWidth: Int
, videoHeight: Int
)

object WatchSession {
  object JsonProtocol extends DefaultJsonProtocol {
    implicit object uriFormat extends JsonFormat[Uri] {
      override def read(js: JsValue): Uri = js match {
        case JsString(value) => Uri(value)
        case JsNull => Uri.Empty
        case _ => deserializationError(s"Expected Uri path, but got $js")
      }
      override def write(uri: Uri): JsValue = JsString(uri.toString)
    }
    implicit val watchSessionFormat: JsonFormat[WatchSession] = jsonFormat6(WatchSession.apply)
  }
}

trait TunerBackend {
  def friendlyName: String
  def getChannels(): Future[Seq[ChannelData]]
  def getGuide(): Future[Seq[ChannelGuideData]]
  def getChannelGuide(channelId: String): Future[ChannelGuideData]
  def getTunerStatus(): Future[Seq[TunerStatus]]
  def watchChannel(channelId: String): Future[WatchSession]
  def tunerCount: Int
  def deviceId: String
}

object TunerBackend {
  case object NoAvailableTunersError extends Exception("No available tuners")
  case class AuthenticationError(message: String) extends Exception(message)
  case class DeviceNotFoundError(message: String) extends Exception(message)
  case object MissingCredentialsError extends Exception("Missing credentials")
  case class ServerInfoError(status: String) extends Exception(s"Failed to get server info: $status")
  case class WatchError(status: String) extends Exception(s"Failed to start watch: $status")
}

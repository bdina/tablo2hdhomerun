package app

import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.model._
import org.apache.pekko.http.scaladsl.unmarshalling.Unmarshal
import org.apache.pekko.http.scaladsl.marshalling.Marshal

import java.net.InetAddress
import java.time.Instant

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

import spray.json._
import DefaultJsonProtocol._

import org.slf4j.LoggerFactory

case class LegacyTunerBackend(
  ip: InetAddress
, port: Int
)(implicit system: ActorSystem[?], ec: ExecutionContext) extends TunerBackend {

  private val log = LoggerFactory.getLogger(this.getClass)
  private val httpCtx = Http()
  private val baseUrl = Uri(s"http://${ip.getHostAddress}:$port")

  override def friendlyName: String = "Tablo Legacy Gen Proxy"

  override def tunerCount: Int = 4

  override def deviceId: String = "12345678"

  private case class LegacyChannelInfo(
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

  private case class LegacyChannelObject(
    object_id: Int
  , path: String
  , channel: LegacyChannelInfo
  )

  private object LegacyJsonProtocol extends DefaultJsonProtocol {
    implicit val legacyChannelInfoFormat: JsonFormat[LegacyChannelInfo] = jsonFormat10(LegacyChannelInfo.apply)
    implicit val legacyChannelObjectFormat: JsonFormat[LegacyChannelObject] = jsonFormat3(LegacyChannelObject.apply)
  }

  private case class LegacyTuners(in_use: Boolean, channel: Option[Uri], recording: Option[Uri])

  private object LegacyTunersProtocol extends DefaultJsonProtocol {
    implicit object uriFormat extends JsonFormat[Uri] {
      override def read(js: JsValue): Uri = js match {
        case JsString(value) => Uri(value)
        case JsNull => Uri.Empty
        case _ => deserializationError(s"Expected Uri path, but got $js")
      }
      override def write(uri: Uri): JsValue = JsString(uri.toString)
    }
    implicit val legacyTunersFormat: JsonFormat[LegacyTuners] = jsonFormat3(LegacyTuners.apply)
  }

  private case class LegacyVideoDetails(width: Int = 0, height: Int = 0)

  private case class LegacyWatchResponse(
    token: String
  , expires: String
  , keepalive: Int
  , playlist_url: Uri
  , bif_url_sd: Option[Uri]
  , bif_url_hd: Option[Uri]
  , video_details: LegacyVideoDetails
  )

  private object LegacyWatchProtocol extends DefaultJsonProtocol {
    implicit object uriFormat extends JsonFormat[Uri] {
      override def read(js: JsValue): Uri = js match {
        case JsString(value) => Uri(value)
        case JsNull => Uri.Empty
        case _ => deserializationError(s"Expected Uri path, but got $js")
      }
      override def write(uri: Uri): JsValue = JsString(uri.toString)
    }
    implicit val legacyVideoDetailsFormat: JsonFormat[LegacyVideoDetails] = jsonFormat2(LegacyVideoDetails.apply)
    implicit val legacyWatchResponseFormat: JsonFormat[LegacyWatchResponse] = jsonFormat7(LegacyWatchResponse.apply)
  }

  override def getChannels(): Future[Seq[ChannelData]] = {
    val channelsUri = baseUrl.withPath(Uri.Path("/guide/channels"))
    val batchUri = baseUrl.withPath(Uri.Path("/batch"))

    httpCtx.singleRequest(HttpRequest(uri = channelsUri)).flatMap { response =>
      log.info(s"[legacy-backend] guide/channels (GET) - $response")
      Unmarshal(response.entity).to[String].map(_.parseJson.convertTo[Seq[String]])
    }.flatMap { paths =>
      import LegacyJsonProtocol._
      import org.apache.pekko.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
      Marshal(paths.toJson).to[RequestEntity].flatMap { entity =>
        val request = HttpRequest(
          method = HttpMethods.POST
        , uri = batchUri
        , entity = entity.withContentType(ContentTypes.`application/json`)
        )
        httpCtx.singleRequest(request).flatMap { response =>
          log.info(s"[legacy-backend] batch (POST) - $response")
          Unmarshal(response.entity).to[String].map(_.parseJson.convertTo[Map[String, LegacyChannelObject]])
        }
      }
    }.map { data =>
      log.info(s"[legacy-backend] channels found: ${data.size}")
      data.map { case (_, obj) =>
        ChannelData(
          id = obj.object_id.toString
        , name = obj.channel.call_sign
        , major = obj.channel.major
        , minor = obj.channel.minor
        , channelType = obj.channel.source
        , network = obj.channel.network
        , resolution = obj.channel.resolution
        , logoUrl = None
        , callSign = obj.channel.call_sign
        , affiliate = Some(obj.channel.tms_affiliate_id)
        )
      }.toSeq
    }
  }

  override def getGuide(): Future[Seq[ChannelGuideData]] = {
    getChannels().flatMap { channels =>
      val channelFutures = channels.map { channel =>
        getChannelGuide(channel.id).recover {
          case ex =>
            log.warn(s"[legacy-backend] failed to get guide for channel ${channel.id}: ${ex.getMessage}")
            ChannelGuideData(
              channelId = channel.id
            , callSign = channel.callSign
            , major = channel.major
            , minor = channel.minor
            , programs = generateFallbackPrograms(channel.id, channel.callSign)
            )
        }
      }
      Future.sequence(channelFutures)
    }
  }

  override def getChannelGuide(channelId: String): Future[ChannelGuideData] = {
    val programsUri = baseUrl.withPath(Uri.Path(s"/guide/channels/$channelId/programs"))

    httpCtx.singleRequest(HttpRequest(uri = programsUri)).flatMap { response =>
      log.info(s"[legacy-backend] guide/channels/$channelId/programs (GET) - $response")
      if (response.status.isSuccess()) {
        Unmarshal(response.entity).to[String].map { body =>
          log.info(s"[legacy-backend] response body: $body")
          Try {
            body.parseJson.convertTo[Seq[JsObject]].map(parseProgramData(_, channelId))
          }.getOrElse(Seq.empty)
        }
      } else {
        log.info(s"[legacy-backend] endpoint returned ${response.status}")
        Future.successful(Seq.empty[ProgramData])
      }
    }.recover {
      case ex =>
        log.warn(s"[legacy-backend] failed to fetch programs: ${ex.getMessage}")
        Seq.empty[ProgramData]
    }.flatMap { programs =>
      getChannels().map { channels =>
        val channel = channels.find(_.id == channelId)
        val finalPrograms = if (programs.isEmpty) {
          generateFallbackPrograms(channelId, channel.map(_.callSign).getOrElse("Unknown"))
        } else {
          programs
        }
        ChannelGuideData(
          channelId = channelId
        , callSign = channel.map(_.callSign).getOrElse("Unknown")
        , major = channel.map(_.major).getOrElse(0)
        , minor = channel.map(_.minor).getOrElse(0)
        , programs = finalPrograms
        )
      }
    }
  }

  private def parseProgramData(js: JsObject, channelId: String): ProgramData = {
    val fields = js.fields
    ProgramData(
      id = fields.get("id").map(_.convertTo[String]).getOrElse(s"prog_${System.currentTimeMillis()}")
    , title = fields.get("title").map(_.convertTo[String]).getOrElse("Unknown Program")
    , description = fields.get("description").map(_.convertTo[String])
    , startTime = fields.get("start_time").map(_.convertTo[String]).getOrElse("")
    , endTime = fields.get("end_time").map(_.convertTo[String]).getOrElse("")
    , channelId = channelId
    , episodeTitle = fields.get("episode_title").map(_.convertTo[String])
    , seasonNumber = fields.get("season_number").flatMap(v => Try(v.convertTo[Int]).toOption)
    , episodeNumber = fields.get("episode_number").flatMap(v => Try(v.convertTo[Int]).toOption)
    , year = fields.get("year").flatMap(v => Try(v.convertTo[Int]).toOption)
    , genre = fields.get("genre").map(_.convertTo[String])
    , rating = fields.get("rating").map(_.convertTo[String])
    , isMovie = fields.get("is_movie").flatMap(v => Try(v.convertTo[Boolean]).toOption).getOrElse(false)
    , isSports = fields.get("is_sports").flatMap(v => Try(v.convertTo[Boolean]).toOption).getOrElse(false)
    , isNews = fields.get("is_news").flatMap(v => Try(v.convertTo[Boolean]).toOption).getOrElse(false)
    )
  }

  private def generateFallbackPrograms(channelId: String, callSign: String): Seq[ProgramData] = {
    val now = Instant.now()
    (0 until 24).map { hour =>
      val startTime = now.plusSeconds(hour * 3600)
      val endTime = startTime.plusSeconds(3600)

      val programTitle = hour match {
        case 0 | 1 | 2 | 3 | 4 | 5 => "Late Night Programming"
        case 6 | 7 | 8 | 9 => "Morning News"
        case 10 | 11 | 12 | 13 => "Daytime Programming"
        case 14 | 15 | 16 | 17 => "Afternoon Shows"
        case 18 | 19 | 20 | 21 => "Prime Time"
        case 22 | 23 => "Evening News"
      }

      val genre = hour match {
        case 6 | 7 | 8 | 9 | 22 | 23 => Some("News")
        case 18 | 19 | 20 | 21 => Some("Drama")
        case _ => Some("General")
      }

      val isNews = hour match {
        case 6 | 7 | 8 | 9 | 22 | 23 => true
        case _ => false
      }

      ProgramData(
        id = s"fallback_${channelId}_$hour"
      , title = programTitle
      , description = Some(s"Programming on $callSign")
      , startTime = startTime.toString
      , endTime = endTime.toString
      , channelId = channelId
      , episodeTitle = None
      , seasonNumber = None
      , episodeNumber = None
      , year = None
      , genre = genre
      , rating = None
      , isMovie = false
      , isSports = false
      , isNews = isNews
      )
    }
  }

  override def getTunerStatus(): Future[Seq[TunerStatus]] = {
    val tunersUri = baseUrl.withPath(Uri.Path("/server/tuners"))
    httpCtx.singleRequest(HttpRequest(uri = tunersUri)).flatMap { response =>
      import LegacyTunersProtocol._
      log.info(s"[legacy-backend] server/tuners (GET) - $response")
      Unmarshal(response.entity).to[String].map { body =>
        body.parseJson.convertTo[Seq[LegacyTuners]].map { tuner =>
          TunerStatus(
            inUse = tuner.in_use
          , channel = tuner.channel
          , recording = tuner.recording
          )
        }
      }
    }
  }

  override def watchChannel(channelId: String): Future[WatchSession] = {
    getTunerStatus().flatMap { tuners =>
      val available = tuners.filterNot(_.inUse).size
      log.info(s"[legacy-backend] available tuners - $available")

      if (available > 0) {
        val watchUri = baseUrl.withPath(Uri.Path(s"/guide/channels/$channelId/watch"))
        val watchRequest = JsObject(
          "bandwidth" -> JsNumber(1000)
        , "no_fast_startup" -> JsBoolean(false)
        )

        val request = HttpRequest(
          method = HttpMethods.POST
        , uri = watchUri
        , entity = HttpEntity(ContentTypes.`application/json`, watchRequest.compactPrint)
        )

        httpCtx.singleRequest(request).flatMap { response =>
          import LegacyWatchProtocol._
          log.info(s"[legacy-backend] guide/channels/$channelId/watch (POST) - $response")
          Unmarshal(response.entity).to[String].map { body =>
            val watchResp = body.parseJson.convertTo[LegacyWatchResponse]
            WatchSession(
              token = watchResp.token
            , expires = watchResp.expires
            , keepalive = watchResp.keepalive
            , playlistUrl = watchResp.playlist_url
            , videoWidth = watchResp.video_details.width
            , videoHeight = watchResp.video_details.height
            )
          }
        }
      } else {
        log.info(s"[legacy-backend] no available tuners (0/${tuners.size})")
        Future.failed(TunerBackend.NoAvailableTunersError)
      }
    }
  }
}

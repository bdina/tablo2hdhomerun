package app

import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.model._
import org.apache.pekko.http.scaladsl.model.headers._
import org.apache.pekko.http.scaladsl.unmarshalling.Unmarshal

import java.time.LocalDate
import java.time.format.DateTimeFormatter

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

import spray.json._

import org.slf4j.LoggerFactory

case class Gen4TunerBackend(
  auth: Gen4Authentication
, session: LighthouseSession
)(implicit system: ActorSystem[?], ec: ExecutionContext) extends TunerBackend {

  private val log = LoggerFactory.getLogger(this.getClass)
  private val httpCtx = Http()

  private val lighthouseBaseUrl = "https://lighthousetv.ewscloud.com/api/v2"
  private val deviceBaseUrl = Uri(session.deviceUrl)

  private var cachedTunerCount: Int = 4

  override def friendlyName: String = s"Tablo 4th Gen - ${session.deviceName}"

  override def tunerCount: Int = cachedTunerCount

  override def deviceId: String = session.deviceId.hashCode.toHexString.take(8).toUpperCase

  private case class Gen4ChannelOta(
    major: Int
  , minor: Int
  , callSign: String
  , network: Option[String]
  )

  private case class Gen4ChannelOtt(
    streamUrl: String
  , provider: Option[String]
  , canRecord: Boolean
  )

  private case class Gen4ChannelLogo(
    lightLarge: Option[String]
  , darkLarge: Option[String]
  , lightSmall: Option[String]
  , darkSmall: Option[String]
  )

  private case class Gen4Channel(
    identifier: String
  , name: String
  , kind: String
  , logos: Option[Seq[Gen4ChannelLogo]]
  , ota: Option[Gen4ChannelOta]
  , ott: Option[Gen4ChannelOtt]
  )

  private object Gen4ChannelProtocol extends DefaultJsonProtocol {
    implicit val gen4ChannelOtaFormat: JsonFormat[Gen4ChannelOta] = jsonFormat4(Gen4ChannelOta.apply)
    implicit val gen4ChannelOttFormat: JsonFormat[Gen4ChannelOtt] = jsonFormat3(Gen4ChannelOtt.apply)
    implicit val gen4ChannelLogoFormat: JsonFormat[Gen4ChannelLogo] = jsonFormat4(Gen4ChannelLogo.apply)
    implicit val gen4ChannelFormat: JsonFormat[Gen4Channel] = jsonFormat6(Gen4Channel.apply)
  }

  private case class Gen4Airing(
    identifier: String
  , title: String
  , channel: String
  , datetime: String
  , duration: Int
  , kind: String
  , description: Option[String]
  , genres: Option[Seq[String]]
  )

  private object Gen4AiringProtocol extends DefaultJsonProtocol {
    implicit val gen4AiringFormat: JsonFormat[Gen4Airing] = jsonFormat8(Gen4Airing.apply)
  }

  private case class Gen4ServerInfo(
    model: Gen4ModelInfo
  )

  private case class Gen4ModelInfo(
    name: String
  , tuners: Int
  )

  private object Gen4ServerInfoProtocol extends DefaultJsonProtocol {
    implicit val gen4ModelInfoFormat: JsonFormat[Gen4ModelInfo] = jsonFormat2(Gen4ModelInfo.apply)
    implicit val gen4ServerInfoFormat: JsonFormat[Gen4ServerInfo] = jsonFormat1(Gen4ServerInfo.apply)
  }

  private case class Gen4WatchResponse(
    token: String
  , expires: String
  , keepalive: Int
  , playlist_url: String
  , video_details: Option[Gen4VideoDetails]
  )

  private case class Gen4VideoDetails(
    container_format: Option[String]
  , flags: Option[Seq[String]]
  )

  private object Gen4WatchProtocol extends DefaultJsonProtocol {
    implicit val gen4VideoDetailsFormat: JsonFormat[Gen4VideoDetails] = jsonFormat2(Gen4VideoDetails.apply)
    implicit val gen4WatchResponseFormat: JsonFormat[Gen4WatchResponse] = jsonFormat5(Gen4WatchResponse.apply)
  }

  override def getChannels(): Future[Seq[ChannelData]] = {
    val channelsUrl = s"$lighthouseBaseUrl/account/${session.token}/guide/channels/"

    auth.getCredentials match {
      case Some(creds) =>
        val authHeader = Authorization(OAuth2BearerToken(creds.accessToken))
        val lhHeader = RawHeader("X-Lighthouse-Token", session.token)

        val request = HttpRequest(
          method = HttpMethods.GET
        , uri = channelsUrl
        , headers = List(authHeader, lhHeader)
        )

        httpCtx.singleRequest(request).flatMap { response =>
          log.info(s"[gen4-backend] guide/channels (GET) - ${response.status}")
          if (response.status.isSuccess()) {
            import Gen4ChannelProtocol._
            Unmarshal(response.entity).to[String].map { body =>
              val channels = body.parseJson.convertTo[Seq[Gen4Channel]]
              log.info(s"[gen4-backend] channels found: ${channels.size}")
              channels.map(convertChannel)
            }
          } else {
            Unmarshal(response.entity).to[String].flatMap { body =>
              log.warn(s"[gen4-backend] failed to get channels: ${response.status} - $body")
              Future.successful(Seq.empty)
            }
          }
        }

      case None =>
        Future.failed(TunerBackend.AuthenticationError("Not authenticated"))
    }
  }

  private def convertChannel(ch: Gen4Channel): ChannelData = {
    val (major, minor, callSign, network) = ch.ota match {
      case Some(ota) => (ota.major, ota.minor, ota.callSign, ota.network)
      case None => (0, 0, ch.name, None)
    }

    val logoUrl = ch.logos.flatMap(_.headOption).flatMap(_.lightLarge)

    ChannelData(
      id = ch.identifier
    , name = ch.name
    , major = major
    , minor = minor
    , channelType = ch.kind
    , network = network
    , resolution = "1080"
    , logoUrl = logoUrl
    , callSign = callSign
    , affiliate = network
    )
  }

  override def getGuide(): Future[Seq[ChannelGuideData]] = {
    getChannels().flatMap { channels =>
      val today = LocalDate.now()
      val dates = (0 until 3).map(today.plusDays(_))

      val channelFutures = channels.map { channel =>
        val dateFutures = dates.map { date =>
          getAiringsForChannelDate(channel.id, date)
        }

        Future.sequence(dateFutures).map { airingsPerDay =>
          val allPrograms = airingsPerDay.flatten
          ChannelGuideData(
            channelId = channel.id
          , callSign = channel.callSign
          , major = channel.major
          , minor = channel.minor
          , programs = allPrograms
          )
        }.recover {
          case ex =>
            log.warn(s"[gen4-backend] failed to get guide for channel ${channel.id}: ${ex.getMessage}")
            ChannelGuideData(
              channelId = channel.id
            , callSign = channel.callSign
            , major = channel.major
            , minor = channel.minor
            , programs = Seq.empty
            )
        }
      }

      Future.sequence(channelFutures)
    }
  }

  private def getAiringsForChannelDate(channelId: String, date: LocalDate): Future[Seq[ProgramData]] = {
    val dateStr = date.format(DateTimeFormatter.ISO_LOCAL_DATE)
    val airingsUrl = s"$lighthouseBaseUrl/account/guide/channels/$channelId/airings/$dateStr/"

    auth.getCredentials match {
      case Some(creds) =>
        val authHeader = Authorization(OAuth2BearerToken(creds.accessToken))
        val lhHeader = RawHeader("X-Lighthouse-Token", session.token)

        val request = HttpRequest(
          method = HttpMethods.GET
        , uri = airingsUrl
        , headers = List(authHeader, lhHeader)
        )

        httpCtx.singleRequest(request).flatMap { response =>
          log.info(s"[gen4-backend] airings/$dateStr (GET) - ${response.status}")
          if (response.status.isSuccess()) {
            import Gen4AiringProtocol._
            Unmarshal(response.entity).to[String].map { body =>
              Try {
                body.parseJson.convertTo[Seq[Gen4Airing]].map(convertAiring(_, channelId))
              }.getOrElse(Seq.empty)
            }
          } else {
            Future.successful(Seq.empty)
          }
        }.recover {
          case ex =>
            log.warn(s"[gen4-backend] failed to get airings: ${ex.getMessage}")
            Seq.empty
        }

      case None =>
        Future.failed(TunerBackend.AuthenticationError("Not authenticated"))
    }
  }

  private def convertAiring(airing: Gen4Airing, channelId: String): ProgramData = {
    val endTime = Try {
      val start = java.time.Instant.parse(airing.datetime)
      start.plusSeconds(airing.duration).toString
    }.getOrElse(airing.datetime)

    val genre = airing.genres.flatMap(_.headOption)
    val isMovie = airing.kind == "movieAiring"
    val isSports = airing.kind == "sportEvent"
    val isNews = genre.exists(_.toLowerCase.contains("news"))

    ProgramData(
      id = airing.identifier
    , title = airing.title
    , description = airing.description
    , startTime = airing.datetime
    , endTime = endTime
    , channelId = channelId
    , episodeTitle = None
    , seasonNumber = None
    , episodeNumber = None
    , year = None
    , genre = genre
    , rating = None
    , isMovie = isMovie
    , isSports = isSports
    , isNews = isNews
    )
  }

  override def getChannelGuide(channelId: String): Future[ChannelGuideData] = {
    getChannels().flatMap { channels =>
      val channel = channels.find(_.id == channelId)

      val today = LocalDate.now()
      val dates = (0 until 3).map(today.plusDays(_))

      val dateFutures = dates.map { date =>
        getAiringsForChannelDate(channelId, date)
      }

      Future.sequence(dateFutures).map { airingsPerDay =>
        val allPrograms = airingsPerDay.flatten
        ChannelGuideData(
          channelId = channelId
        , callSign = channel.map(_.callSign).getOrElse("Unknown")
        , major = channel.map(_.major).getOrElse(0)
        , minor = channel.map(_.minor).getOrElse(0)
        , programs = allPrograms
        )
      }
    }
  }

  override def getTunerStatus(): Future[Seq[TunerStatus]] = {
    fetchServerInfo().map { _ =>
      (0 until cachedTunerCount).map { _ =>
        TunerStatus(inUse = false, channel = None, recording = None)
      }
    }.recover {
      case _ =>
        (0 until cachedTunerCount).map { _ =>
          TunerStatus(inUse = false, channel = None, recording = None)
        }
    }
  }

  private def fetchServerInfo(): Future[Gen4ServerInfo] = {
    val serverInfoPath = "/server/info"
    val serverInfoUrl = deviceBaseUrl.withPath(Uri.Path(serverInfoPath)).withQuery(Uri.Query("lh" -> session.token))

    val headers = List(
      auth.createDateHeader()
    , auth.createUserAgentHeader()
    ) ++ auth.createDeviceAuthHeader("GET", serverInfoPath, None).toList

    val request = HttpRequest(
      method = HttpMethods.GET
    , uri = serverInfoUrl
    , headers = headers
    )

    httpCtx.singleRequest(request).flatMap { response =>
      log.info(s"[gen4-backend] server/info (GET) - ${response.status}")
      if (response.status.isSuccess()) {
        import Gen4ServerInfoProtocol._
        Unmarshal(response.entity).to[String].map { body =>
          val serverInfo = body.parseJson.convertTo[Gen4ServerInfo]
          cachedTunerCount = serverInfo.model.tuners
          log.info(s"[gen4-backend] device has ${serverInfo.model.tuners} tuners")
          serverInfo
        }
      } else {
        Unmarshal(response.entity).to[String].flatMap { body =>
          log.warn(s"[gen4-backend] failed to get server info: ${response.status} - $body")
          Future.failed(TunerBackend.ServerInfoError(response.status.toString))
        }
      }
    }
  }

  override def watchChannel(channelId: String): Future[WatchSession] = {
    val watchPath = s"/guide/channels/$channelId/watch"
    val watchUrl = deviceBaseUrl.withPath(Uri.Path(watchPath)).withQuery(Uri.Query("lh" -> session.token))

    val watchBody = JsObject(
      "platform" -> JsString("ios")
    , "bandwidth" -> JsNull
    , "extra" -> JsObject(
        "deviceOS" -> JsString("iOS")
      , "deviceOSVersion" -> JsString("18.4")
      , "deviceMake" -> JsString("Apple")
      , "deviceModel" -> JsString("iPhone")
      )
    )
    val bodyStr = watchBody.compactPrint

    val headers = List(
      auth.createDateHeader()
    , auth.createUserAgentHeader()
    ) ++ auth.createDeviceAuthHeader("POST", watchPath, Some(bodyStr)).toList

    val request = HttpRequest(
      method = HttpMethods.POST
    , uri = watchUrl
    , headers = headers
    , entity = HttpEntity(ContentTypes.`application/json`, bodyStr)
    )

    httpCtx.singleRequest(request).flatMap { response =>
      log.info(s"[gen4-backend] watch (POST) - ${response.status}")
      if (response.status.isSuccess()) {
        import Gen4WatchProtocol._
        Unmarshal(response.entity).to[String].map { body =>
          val watchResp = body.parseJson.convertTo[Gen4WatchResponse]
          WatchSession(
            token = watchResp.token
          , expires = watchResp.expires
          , keepalive = watchResp.keepalive
          , playlistUrl = Uri(watchResp.playlist_url)
          , videoWidth = 1920
          , videoHeight = 1080
          )
        }
      } else if (response.status.intValue() == 503) {
        log.info("[gen4-backend] no available tuners")
        Future.failed(TunerBackend.NoAvailableTunersError)
      } else {
        Unmarshal(response.entity).to[String].flatMap { body =>
          log.warn(s"[gen4-backend] failed to start watch: ${response.status} - $body")
          Future.failed(TunerBackend.WatchError(response.status.toString))
        }
      }
    }
  }
}

object Gen4TunerBackend {
  private val log = LoggerFactory.getLogger(this.getClass)

  def create(
    email: String
  , password: String
  , deviceName: Option[String]
  )(implicit system: ActorSystem[?], ec: ExecutionContext): Future[Gen4TunerBackend] = {
    val auth = Gen4Authentication(email, password)

    log.info("[gen4-backend] authenticating with Lighthouse...")
    auth.authenticate(deviceName).map { session =>
      log.info(s"[gen4-backend] authenticated, selected device: ${session.deviceName}")
      Gen4TunerBackend(auth, session)
    }
  }
}

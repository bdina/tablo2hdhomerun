package app.tuner

import org.apache.pekko

import pekko.actor.typed.scaladsl.Behaviors
import pekko.actor.typed.{ActorRef, ActorSystem, Behavior}
import pekko.http.scaladsl.Http
import pekko.http.scaladsl.model.{ContentTypes, HttpEntity, HttpMethod, HttpMethods, HttpRequest, HttpResponse, MediaType, StatusCodes, Uri}
import pekko.http.scaladsl.server.Directives._
import pekko.stream.scaladsl.Source

import java.time.{LocalDate, ZonedDateTime, ZoneOffset}
import java.time.format.DateTimeFormatter
import java.security.MessageDigest

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

import org.apache.pekko.pattern.after

import org.slf4j.LoggerFactory

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}

import spray.json._
import DefaultJsonProtocol._

import app.{AppContext, Tablo2HDHomeRun}
import app.config.TabloAuthEnv
import app.sys.LogConfig

object Tablo4thGen {
  val log = LoggerFactory.getLogger(this.getClass)

  val LIGHTHOUSE_BASE_URL = "https://lighthousetv.ewscloud.com/api/v2"
  val USER_AGENT = "Tablo-FAST/1.7.0 (Mobile; iPhone; iOS 18.4)"

  sealed trait Error extends Exception
  object Error {
    case object MissingEmail extends Exception("TABLO_EMAIL is required for 4th gen mode") with Error
    case object MissingPassword extends Exception("TABLO_PASSWORD is required for 4th gen mode") with Error
    case class LoginFailed(message: String) extends Exception(s"Login failed: $message") with Error
    case object NoProfilesFound extends Exception("No profiles found in account") with Error
    case class DeviceNotFound(name: String) extends Exception(s"No device found matching name: $name") with Error
    case object NoDevicesFound extends Exception("No devices found in account") with Error
    case class SelectFailed(message: String) extends Exception(s"Select failed: $message") with Error
    case object NoAvailableTuners extends Exception("No available tuners") with Error
  }

  object Auth {
    val log = LoggerFactory.getLogger(this.getClass)

    case class AuthContext(
      accessToken: String
    , lighthouseToken: String
    , deviceKey: String
    , hashKey: String
    , deviceUrl: Uri
    , profileId: String
    , serverId: String
    )

    case class LoginRequest(email: String, password: String)
    case class LoginResponse(access_token: Option[String], token_type: Option[String], is_verified: Option[Boolean], code: Option[Int], message: Option[String])
    case class AccountProfile(identifier: String, name: String)
    case class AccountDevice(serverId: String, name: String, `type`: Option[String], url: Option[String], reachability: Option[String])
    case class AccountInfo(identifier: Option[String], profiles: Option[Seq[AccountProfile]], devices: Option[Seq[AccountDevice]], code: Option[Int], message: Option[String])
    case class SelectRequest(pid: String, sid: String)
    case class SelectResponse(token: Option[String], code: Option[Int], message: Option[String])

    object JsonProtocol extends DefaultJsonProtocol {
      implicit val loginRequestFormat: JsonFormat[LoginRequest] = jsonFormat2(LoginRequest.apply)
      implicit val loginResponseFormat: JsonFormat[LoginResponse] = jsonFormat5(LoginResponse.apply)
      implicit val accountProfileFormat: JsonFormat[AccountProfile] = jsonFormat2(AccountProfile.apply)
      implicit val accountDeviceFormat: JsonFormat[AccountDevice] = jsonFormat5(AccountDevice.apply)
      implicit val accountInfoFormat: JsonFormat[AccountInfo] = jsonFormat5(AccountInfo.apply)
      implicit val selectRequestFormat: JsonFormat[SelectRequest] = jsonFormat2(SelectRequest.apply)
      implicit val selectResponseFormat: JsonFormat[SelectResponse] = jsonFormat3(SelectResponse.apply)
    }

    import scala.concurrent.Await
    import pekko.http.scaladsl.unmarshalling.Unmarshal

    @volatile private var _authContext: Option[AuthContext] = None

    def authContext: Option[AuthContext] = _authContext

    def initialize(tabloAuth: TabloAuthEnv)(implicit system: ActorSystem[?]): AuthContext = {
      val HttpCtx = Http()

      val tablo = AppContext.config.tablo
      val email = tabloAuth.email.getOrElse {
        log.error("[auth] missing env name=TABLO_EMAIL")
        throw Tablo4thGen.Error.MissingEmail
      }
      val password = tabloAuth.password.getOrElse {
        log.error("[auth] missing env name=TABLO_PASSWORD")
        throw Tablo4thGen.Error.MissingPassword
      }

      log.info("[auth] initialize email={}", email)

      import JsonProtocol._
      implicit val ec = system.executionContext

      val authFuture: Future[AuthContext] = for {
        loginResp <- {
          val loginUri = Uri(s"$LIGHTHOUSE_BASE_URL/login/")
          val loginBody = LoginRequest(email, password).toJson.compactPrint
          log.debug("[auth] http POST /login/")
          val request = HttpRequest(
            method = HttpMethods.POST
          , uri = loginUri
          , entity = HttpEntity(ContentTypes.`application/json`, loginBody)
          )
          HttpCtx.singleRequest(request).flatMap { response =>
            log.debug("[auth] login status={}", response.status.intValue())
            Unmarshal(response.entity).to[String].map(_.parseJson.convertTo[LoginResponse])
          }
        }
        accessToken = loginResp.access_token.getOrElse {
          throw Tablo4thGen.Error.LoginFailed(loginResp.message.getOrElse("unknown error"))
        }
        accountInfo <- {
          val accountUri = Uri(s"$LIGHTHOUSE_BASE_URL/account/")
          log.debug("[auth] http GET /account/")
          import pekko.http.scaladsl.model.headers._
          val request = HttpRequest(
            method = HttpMethods.GET
          , uri = accountUri
          , headers = Seq(Authorization(OAuth2BearerToken(accessToken)))
          )
          HttpCtx.singleRequest(request).flatMap { response =>
            log.debug("[auth] account status={}", response.status.intValue())
            Unmarshal(response.entity).to[String].map(_.parseJson.convertTo[AccountInfo])
          }
        }
        profile = accountInfo.profiles.flatMap(_.headOption).getOrElse {
          throw Tablo4thGen.Error.NoProfilesFound
        }
        device = {
          val devices = accountInfo.devices.getOrElse(Seq.empty)
          val deviceNameFilter = tablo.deviceName
          deviceNameFilter match {
            case Some(name) =>
              devices.find(_.name.toLowerCase.contains(name.toLowerCase)).getOrElse {
                throw Tablo4thGen.Error.DeviceNotFound(name)
              }
            case None =>
              devices.find(_.reachability.contains("local")).orElse(devices.headOption).getOrElse {
                throw Tablo4thGen.Error.NoDevicesFound
              }
          }
        }
        _ = log.info("[auth] device selected name={} serverId={}", device.name, device.serverId)
        selectResp <- {
          val selectUri = Uri(s"$LIGHTHOUSE_BASE_URL/account/select/")
          val selectBody = SelectRequest(pid = profile.identifier, sid = device.serverId).toJson.compactPrint
          log.debug("[auth] http POST /account/select/")
          import pekko.http.scaladsl.model.headers._
          val request = HttpRequest(
            method = HttpMethods.POST
          , uri = selectUri
          , headers = Seq(Authorization(OAuth2BearerToken(accessToken)))
          , entity = HttpEntity(ContentTypes.`application/json`, selectBody)
          )
          HttpCtx.singleRequest(request).flatMap { response =>
            log.debug("[auth] select status={}", response.status.intValue())
            Unmarshal(response.entity).to[String].map { body =>
              log.debug("[auth] select body={}", LogConfig.truncate(body))
              body.parseJson.convertTo[SelectResponse]
            }
          }
        }
        lighthouseToken = selectResp.token.getOrElse {
          throw Tablo4thGen.Error.SelectFailed(selectResp.message.getOrElse("unknown error"))
        }
      } yield {
        val deviceUrl = device.url.map(Uri(_)).getOrElse {
          Uri(tablo.deviceBaseUri)
        }
        AuthContext(
          accessToken = accessToken
        , lighthouseToken = lighthouseToken
        , deviceKey = tablo.deviceKey
        , hashKey = tablo.hashKey
        , deviceUrl = deviceUrl
        , profileId = profile.identifier
        , serverId = device.serverId
        )
      }

      val ctx = Await.result(authFuture, 30.seconds)
      _authContext = Some(ctx)
      log.info("[auth] complete deviceUrl={}", ctx.deviceUrl)
      ctx
    }
  }

  object Hmac {
    val log = LoggerFactory.getLogger(this.getClass)

    private def secretKeySpec(key: String, algorithm: String): SecretKeySpec =
      SecretKeySpec(key.getBytes("UTF-8"), algorithm)

    def md5Hex(input: String): String = {
      val md = MessageDigest.getInstance("MD5")
      val digest = md.digest(input.getBytes("UTF-8"))
      digest.map("%02x".format(_)).mkString
    }

    def hmacMd5Hex(data: String, key: String): String = {
      val mac = Mac.getInstance("HmacMD5")
      mac.init(secretKeySpec(key, "HmacMD5"))
      val hmacBytes = mac.doFinal(data.getBytes("UTF-8"))
      hmacBytes.map("%02x".format(_)).mkString
    }

    def sign(method: String, path: String, body: Option[String], hashKey: String, deviceKey: String): (String, String) = {
      val bodyMd5 = body.map(md5Hex).getOrElse("")
      val date = DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.now(ZoneOffset.UTC))
      val signatureString = s"$method\n$path\n$bodyMd5\n$date"
      val hmac = hmacMd5Hex(signatureString, hashKey)
      val authHeader = s"tablo:$deviceKey:$hmac"
      log.debug("[auth] hmac sign method={} path={}", method, path)
      (authHeader, date)
    }

    def signedHeaders(method: String, path: String, body: Option[String], authContext: Auth.AuthContext): Seq[pekko.http.scaladsl.model.HttpHeader] = {
      val (authHeader, date) = sign(method, path, body, authContext.hashKey, authContext.deviceKey)
      import pekko.http.scaladsl.model.headers._
      Seq(
        RawHeader("Authorization", authHeader)
      , RawHeader("Date", date)
      , RawHeader("User-Agent", USER_AGENT)
      , RawHeader("Lighthouse", authContext.lighthouseToken)
      )
    }
  }

  object Lineup {
    val log = LoggerFactory.getLogger(this.getClass)

    case class OtaChannelInfo(major: Int, minor: Int, callSign: Option[String], network: Option[String], streamUrl: Option[String], provider: Option[String], canRecord: Option[Boolean])
    case class OttChannelInfo(major: Option[Int], minor: Option[Int], callSign: Option[String], network: Option[String], streamUrl: Option[String], provider: Option[String], canRecord: Option[Boolean])
    case class ChannelLineup(identifier: String, name: String, kind: String, ota: Option[OtaChannelInfo], ott: Option[OttChannelInfo])

    object JsonProtocol extends DefaultJsonProtocol {
      implicit val otaChannelInfoFormat: JsonFormat[OtaChannelInfo] = jsonFormat7(OtaChannelInfo.apply)
      implicit val ottChannelInfoFormat: JsonFormat[OttChannelInfo] = jsonFormat7(OttChannelInfo.apply)
      implicit val channelLineupFormat: JsonFormat[ChannelLineup] = jsonFormat5(ChannelLineup.apply)
    }

    object LineupActor {
      sealed trait Request
      object Request {
        case class Fetch(replyTo: ActorRef[Response.Fetch]) extends Request
        case class Status(replyTo: ActorRef[Response.Status]) extends Request
      }

      sealed trait Response
      object Response {
        case class Fetch(channels: Seq[JsValue], replyTo: ActorRef[Request.Fetch]) extends Response
        case class Status(scanInProgress: Int, scanPossible: Int, replyTo: ActorRef[Request.Fetch]) extends Response
      }

      sealed trait Command extends Request
      object Command {
        case class Store(channels: Seq[JsValue]) extends Command
        case object Scan extends Command
      }

      implicit val ec: scala.concurrent.ExecutionContextExecutor = scala.concurrent.ExecutionContext.global

      def apply(authContext: Auth.AuthContext): Behavior[Request] = Behaviors.setup { context =>
        implicit val system: ActorSystem[Nothing] = context.system
        var cache: (scala.concurrent.duration.Deadline, Seq[JsValue]) = (0.seconds.fromNow, Seq.empty)
        var scanInProgress: Boolean = false

        val HttpCtx = Http()

        def channelToJsValue(channel: ChannelLineup): JsValue = {
          val (major, minor, callSign, source) = channel.kind match {
            case "ota" =>
              val ota = channel.ota.getOrElse(OtaChannelInfo(0, 0, None, None, None, None, None))
              (ota.major, ota.minor, ota.callSign.getOrElse(channel.name), "antenna")
            case "ott" =>
              val ott = channel.ott.getOrElse(OttChannelInfo(None, None, None, None, None, None, None))
              (ott.major.getOrElse(0), ott.minor.getOrElse(0), ott.callSign.getOrElse(channel.name), "streaming")
            case _ =>
              (0, 0, channel.name, "unknown")
          }
          val num = s"$major.$minor"
          val url = s"${AppContext.discover.BaseURL.withPath(Uri.Path(s"/channel/${channel.identifier}"))}"
          val src = s"${AppContext.discover.BaseURL.withPath(Uri.Path(s"/guide/channels/${channel.identifier}/watch"))}"
          JsObject(
            "GuideNumber" -> JsString(num)
          , "GuideName" -> JsString(callSign)
          , "URL" -> JsString(url)
          , "type" -> JsString(source)
          , "srcURL" -> JsString(src)
          )
        }

        def scan(): Future[Seq[JsValue]] = {
          import Lineup.JsonProtocol._
          import pekko.http.scaladsl.unmarshalling.Unmarshal

          val channelsUri = Uri(s"$LIGHTHOUSE_BASE_URL/account/${authContext.lighthouseToken}/guide/channels/")
          log.debug("[lineup] http GET /guide/channels")

          import pekko.http.scaladsl.model.headers._
          val request = HttpRequest(
            method = HttpMethods.GET
          , uri = channelsUri
          , headers = Seq(
              Authorization(OAuth2BearerToken(authContext.accessToken))
            , RawHeader("Lighthouse", authContext.lighthouseToken)
            )
          )

          HttpCtx.singleRequest(request).flatMap { response =>
            log.debug("[lineup] http GET /guide/channels status={}", response.status.intValue())
            Unmarshal(response.entity).to[String].map { body =>
              val channels = body.parseJson.convertTo[Seq[ChannelLineup]]
              log.info("[lineup] scan complete count={}", channels.size)
              val jsChannels = channels.map(channelToJsValue)
              context.self ! Command.Store(jsChannels)
              jsChannels
            }
          }.recover {
            case ex =>
              log.error("[lineup] scan failed", ex)
              Seq.empty
          }
        }

        context.self ! Command.Scan

        Behaviors.receiveMessage {
          case Command.Store(channels) =>
            log.info("[lineup] store count={}", channels.size)
            cache = (1.day.fromNow, channels)
            scanInProgress = false
            Behaviors.same

          case Command.Scan if scanInProgress =>
            log.debug("[lineup] scan already in progress")
            Behaviors.same

          case Command.Scan =>
            log.info("[lineup] scan requested")
            scanInProgress = true
            scan() : Unit
            Behaviors.same

          case Request.Status(sender) =>
            log.debug("[lineup] status requested")
            val (scanning, possible) = if (scanInProgress) (1, 0) else (0, 1)
            sender ! Response.Status(scanInProgress = scanning, scanPossible = possible, replyTo = context.self)
            Behaviors.same

          case Request.Fetch(replyTo) if cache._1.isOverdue() =>
            log.debug("[lineup] fetch cache expired")
            val sender = replyTo
            scanInProgress = true
            scan().foreach { channels =>
              sender ! Response.Fetch(channels, context.self)
            }
            Behaviors.same

          case Request.Fetch(sender) =>
            val (_, channels) = cache
            log.debug("[lineup] fetch cache count={}", channels.size)
            sender ! Response.Fetch(channels, context.self)
            Behaviors.same
        }
      }
    }

    object Response {
      type LineupStatus = Tablo2HDHomeRun.Lineup.Response.LineupStatus
      val LineupStatus = Tablo2HDHomeRun.Lineup.Response.LineupStatus
    }

    def route(lineupActor: ActorRef[LineupActor.Request])(implicit system: ActorSystem[?]) = {
      import pekko.actor.typed.scaladsl.AskPattern._

      path("lineup.json") {
        get {
          implicit val timeout: pekko.util.Timeout = 3.seconds
          val lineupF: Future[LineupActor.Response.Fetch] = lineupActor.ask(replyTo => LineupActor.Request.Fetch(replyTo))

          onComplete(lineupF) {
            case Success(LineupActor.Response.Fetch(channels, _)) =>
              complete(HttpEntity(ContentTypes.`application/json`, channels.toJson.compactPrint))
            case Failure(ex) =>
              log.warn("[lineup] request failed", ex)
              complete(HttpResponse(StatusCodes.InternalServerError, entity = "Unable to produce channel lineup"))
          }
        }
      } ~
      path("lineup_status.json") {
        get {
          implicit val timeout: pekko.util.Timeout = 3.seconds
          val statusF: Future[LineupActor.Response.Status] = lineupActor.ask(replyTo => LineupActor.Request.Status(replyTo))

          onComplete(statusF) {
            case Success(LineupActor.Response.Status(scanInProgress, scanPossible, _)) =>
              import Lineup.Response.LineupStatus.JsonProtocol.lineupStatusFormat
              val response = Lineup.Response.LineupStatus(ScanInProgress = scanInProgress, ScanPossible = scanPossible).toJson
              log.debug("[lineup] lineup_status served scanInProgress={} scanPossible={}", scanInProgress, scanPossible)
              complete(HttpEntity(ContentTypes.`application/json`, response.compactPrint))
            case Failure(ex) =>
              log.warn("[lineup] lineup_status failed", ex)
              complete(HttpResponse(StatusCodes.InternalServerError, entity = "Unable to get lineup status"))
          }
        }
      }
    }
  }

  object Guide {
    val log = LoggerFactory.getLogger(this.getClass)

    case class SeasonInfo(kind: Option[String], number: Option[Int], string: Option[String])
    case class EpisodeInfo(season: Option[SeasonInfo], episodeNumber: Option[Int], originalAirDate: Option[String], rating: Option[String])
    case class MovieAiringInfo(releaseYear: Option[Int], filmRating: Option[String], qualityRating: Option[Double])
    case class SportEventInfo(season: Option[String])
    case class AiringChannel(identifier: String)
    case class GuideAiring(
      identifier: String
    , title: String
    , channel: Option[AiringChannel]
    , datetime: String
    , description: Option[String]
    , kind: String
    , duration: Int
    , genres: Option[Seq[String]]
    , episode: Option[EpisodeInfo]
    , movieAiring: Option[MovieAiringInfo]
    , sportEvent: Option[SportEventInfo]
    )

    object JsonProtocol extends DefaultJsonProtocol {
      implicit val seasonInfoFormat: JsonFormat[SeasonInfo] = jsonFormat3(SeasonInfo.apply)
      implicit val episodeInfoFormat: JsonFormat[EpisodeInfo] = jsonFormat4(EpisodeInfo.apply)
      implicit val movieAiringInfoFormat: JsonFormat[MovieAiringInfo] = jsonFormat3(MovieAiringInfo.apply)
      implicit val sportEventInfoFormat: JsonFormat[SportEventInfo] = jsonFormat1(SportEventInfo.apply)
      implicit val airingChannelFormat: JsonFormat[AiringChannel] = jsonFormat1(AiringChannel.apply)
      implicit val guideAiringFormat: JsonFormat[GuideAiring] = jsonFormat11(GuideAiring.apply)
    }

    def route(authContext: Auth.AuthContext)(implicit system: ActorSystem[?]) = {
      import pekko.actor.typed.scaladsl.AskPattern._

      path("guide.xml") {
        get {
          implicit val timeout: pekko.util.Timeout = 10.seconds

          val guideF: Future[GuideActor.Response.FetchGuide] =
            GuideActor.instance(authContext).ask(replyTo => GuideActor.Request.FetchGuide(replyTo))

          onComplete(guideF) {
            case Success(GuideActor.Response.FetchGuide(guide, _)) =>
              log.debug("[guide] served count={}", guide.size)

              import pekko.util.ByteString
              val xmlStream =
                Source
                  .fromIterator { () =>
                    Iterator("<tv>") ++
                    guide.iterator.flatMap(channelGuide =>
                      Iterator(Tablo2HDHomeRun.Guide.XMLTVFormatter.formatChannel(channelGuide).toString) ++
                      channelGuide.programs.iterator.map(program =>
                        Tablo2HDHomeRun.Guide.XMLTVFormatter.formatProgram(program, channelGuide).toString
                      )
                    ) ++
                    Iterator("</tv>")
                  }
                  .map(ByteString(_))

              complete(HttpEntity.Chunked.fromData(ContentTypes.`text/xml(UTF-8)`, xmlStream))
            case Failure(ex) =>
              log.warn("[guide] request failed", ex)
              val emptyGuide = <tv></tv>
              complete(HttpEntity(ContentTypes.`text/xml(UTF-8)`, emptyGuide.toString))
          }
        }
      }
    }
  }

  object GuideActor {
    val log = LoggerFactory.getLogger(this.getClass)

    sealed trait Request
    object Request {
      case class FetchGuide(replyTo: ActorRef[Response.FetchGuide]) extends Request
    }

    sealed trait Response
    object Response {
      case class FetchGuide(guide: Seq[Tablo2HDHomeRun.Guide.ChannelGuide], replyTo: ActorRef[Request.FetchGuide]) extends Response
    }

    sealed trait Command extends Request
    object Command {
      case class StoreGuide(guide: Seq[Tablo2HDHomeRun.Guide.ChannelGuide]) extends Command
      case object Scan extends Command
    }

    implicit val ec: scala.concurrent.ExecutionContextExecutor = scala.concurrent.ExecutionContext.global

    private var _instance: Option[ActorRef[Request]] = None

    def instance(authContext: Auth.AuthContext)(implicit system: ActorSystem[?]): ActorRef[Request] = _instance.getOrElse {
      val actor = system.systemActorOf(apply(authContext), "guide-actor-4thgen-singleton", pekko.actor.typed.Props.empty)
      _instance = Some(actor)
      actor
    }

    def apply(authContext: Auth.AuthContext): Behavior[Request] = Behaviors.setup { context =>
      implicit val system: ActorSystem[Nothing] = context.system
      var cache: (scala.concurrent.duration.Deadline, Seq[Tablo2HDHomeRun.Guide.ChannelGuide]) = (0.seconds.fromNow, Seq.empty)
      var scanInProgress: Boolean = false

      val HttpCtx = Http()

      def airingToProgram(airing: Guide.GuideAiring, channelId: String): Tablo2HDHomeRun.Guide.Program = {
        val startInstant = java.time.Instant.parse(airing.datetime)
        val endInstant = startInstant.plusSeconds(airing.duration)

        val (seasonNum, episodeNum) = airing.episode match {
          case Some(ep) =>
            (ep.season.flatMap(_.number), ep.episodeNumber)
          case None => (None, None)
        }

        val year = airing.movieAiring.flatMap(_.releaseYear)
        val rating = airing.episode.flatMap(_.rating).orElse(airing.movieAiring.flatMap(_.filmRating))
        val genre = airing.genres.flatMap(_.headOption)

        val isMovie = airing.kind == "movieAiring"
        val isSports = airing.kind == "sportEvent"
        val isNews = airing.genres.exists(_.exists(_.toLowerCase.contains("news")))

        Tablo2HDHomeRun.Guide.Program(
          id = airing.identifier
        , title = airing.title
        , description = airing.description
        , start_time = startInstant.toString
        , end_time = endInstant.toString
        , channel_id = channelId.hashCode
        , episode_title = None
        , season_number = seasonNum
        , episode_number = episodeNum
        , year = year
        , genre = genre
        , rating = rating
        , is_movie = isMovie
        , is_sports = isSports
        , is_news = isNews
        )
      }

      def fetchAiringsForChannel(channelId: String, channelName: String, major: Int, minor: Int): Future[Tablo2HDHomeRun.Guide.ChannelGuide] = {
        import Guide.JsonProtocol._
        import pekko.http.scaladsl.unmarshalling.Unmarshal
        import pekko.http.scaladsl.model.headers._

        val today = LocalDate.now()
        val dates = (0 to 2).map(today.plusDays(_).toString)

        val airingsFutures = dates.map { date =>
          val airingsUri = Uri(s"$LIGHTHOUSE_BASE_URL/account/guide/channels/$channelId/airings/$date/")
          log.debug("[guide] http GET /airings channelId={} date={}", channelId, date)

          val request = HttpRequest(
            method = HttpMethods.GET
          , uri = airingsUri
          , headers = Seq(
              Authorization(OAuth2BearerToken(authContext.accessToken))
            , RawHeader("Lighthouse", authContext.lighthouseToken)
            )
          )

          HttpCtx.singleRequest(request).flatMap { response =>
            if (response.status.isSuccess()) {
              Unmarshal(response.entity).to[String].map { body =>
                Try(body.parseJson.convertTo[Seq[Guide.GuideAiring]]).getOrElse(Seq.empty)
              }
            } else {
              log.debug("[guide] airings status={} channelId={} date={}", response.status.intValue(), channelId, date)
              val _ = response.entity.discardBytes()
              Future.successful(Seq.empty[Guide.GuideAiring])
            }
          }.recover {
            case ex =>
              log.warn("[guide] airings failed channelId={} date={}", channelId, date, ex)
              Seq.empty[Guide.GuideAiring]
          }
        }

        Future.sequence(airingsFutures).map { allAirings =>
          val programs = allAirings.flatten.map(airing => airingToProgram(airing, channelId))
          Tablo2HDHomeRun.Guide.ChannelGuide(
            channel_id = channelId.hashCode
          , call_sign = channelName
          , major = major
          , minor = minor
          , programs = programs
          )
        }
      }

      def scan(): Future[Seq[Tablo2HDHomeRun.Guide.ChannelGuide]] = {
        import Lineup.JsonProtocol._
        import pekko.http.scaladsl.unmarshalling.Unmarshal
        import pekko.http.scaladsl.model.headers._

        val channelsUri = Uri(s"$LIGHTHOUSE_BASE_URL/account/${authContext.lighthouseToken}/guide/channels/")
        log.debug("[guide] http GET /guide/channels")

        val request = HttpRequest(
          method = HttpMethods.GET
        , uri = channelsUri
        , headers = Seq(
            Authorization(OAuth2BearerToken(authContext.accessToken))
          , RawHeader("Lighthouse", authContext.lighthouseToken)
          )
        )

        HttpCtx.singleRequest(request).flatMap { response =>
          log.debug("[guide] http GET /guide/channels status={}", response.status.intValue())
          Unmarshal(response.entity).to[String].flatMap { body =>
            val channels = body.parseJson.convertTo[Seq[Lineup.ChannelLineup]]
            log.info("[guide] scan channels count={}", channels.size)

            val guideFutures = channels.map { channel =>
              val (major, minor, callSign) = channel.kind match {
                case "ota" =>
                  val ota = channel.ota.getOrElse(Lineup.OtaChannelInfo(0, 0, None, None, None, None, None))
                  (ota.major, ota.minor, ota.callSign.getOrElse(channel.name))
                case "ott" =>
                  val ott = channel.ott.getOrElse(Lineup.OttChannelInfo(None, None, None, None, None, None, None))
                  (ott.major.getOrElse(0), ott.minor.getOrElse(0), ott.callSign.getOrElse(channel.name))
                case _ =>
                  (0, 0, channel.name)
              }
              fetchAiringsForChannel(channel.identifier, callSign, major, minor)
            }

            Future.sequence(guideFutures)
          }
        }.recover {
          case ex =>
            log.error("[guide] scan failed", ex)
            Seq.empty
        }
      }

      context.self ! Command.Scan

      Behaviors.receiveMessage {
        case Command.StoreGuide(guide) =>
          log.info("[guide] store count={}", guide.size)
          cache = (1.hour.fromNow, guide)
          scanInProgress = false
          Behaviors.same

        case Command.Scan if scanInProgress =>
          log.debug("[guide] scan already in progress")
          Behaviors.same

        case Command.Scan =>
          log.info("[guide] scan requested")
          scanInProgress = true
          scan().foreach { guide =>
            context.self ! Command.StoreGuide(guide)
          }
          Behaviors.same

        case Request.FetchGuide(replyTo) if cache._1.isOverdue() =>
          log.debug("[guide] fetch cache expired")
          scanInProgress = true
          scan().foreach { guide =>
            replyTo ! Response.FetchGuide(guide, context.self)
          }
          Behaviors.same

        case Request.FetchGuide(replyTo) =>
          val (_, guide) = cache
          log.debug("[guide] fetch cache count={}", guide.size)
          replyTo ! Response.FetchGuide(guide, context.self)
          Behaviors.same
      }
    }
  }

  object Channel {
    val log = LoggerFactory.getLogger(this.getClass)

    object Request {
      case class Watch4thGenExtra(
        deviceOS: String
      , deviceOSVersion: String
      , deviceMake: String
      , deviceModel: String
      , width: Int
      , height: Int
      , deviceId: String
      , lang: String
      , limitedAdTracking: Int
      )

      case class Watch4thGenRequest(
        device_id: String
      , bandwidth: Option[String]
      , platform: String
      , extra: Watch4thGenExtra
      )

      object Watch4thGenRequest {
        def forDevice(deviceId: String): Watch4thGenRequest = Watch4thGenRequest(
          device_id = deviceId
        , bandwidth = None
        , platform = "ios"
        , extra = Watch4thGenExtra(
            deviceOS = "iOS"
          , deviceOSVersion = "18.4"
          , deviceMake = "Apple"
          , deviceModel = "iPhone15,2"
          , width = 393
          , height = 852
          , deviceId = deviceId
          , lang = "en_US"
          , limitedAdTracking = 1
          )
        )

        object JsonProtocol extends DefaultJsonProtocol {
          implicit val watch4thGenExtraFormat: JsonFormat[Watch4thGenExtra] = new JsonFormat[Watch4thGenExtra] {
            def write(x: Watch4thGenExtra): JsValue = JsObject(
              "deviceOS" -> JsString(x.deviceOS)
            , "deviceOSVersion" -> JsString(x.deviceOSVersion)
            , "deviceMake" -> JsString(x.deviceMake)
            , "deviceModel" -> JsString(x.deviceModel)
            , "width" -> JsNumber(x.width)
            , "height" -> JsNumber(x.height)
            , "deviceId" -> JsString(x.deviceId)
            , "lang" -> JsString(x.lang)
            , "limitedAdTracking" -> JsNumber(x.limitedAdTracking)
            )
            def read(value: JsValue): Watch4thGenExtra =
              value.asJsObject.getFields(
                "deviceOS", "deviceOSVersion", "deviceMake", "deviceModel"
              , "width", "height", "deviceId", "lang", "limitedAdTracking"
              ) match {
                case Seq(
                  JsString(deviceOS), JsString(deviceOSVersion), JsString(deviceMake), JsString(deviceModel)
                , JsNumber(width), JsNumber(height), JsString(deviceId), JsString(lang), JsNumber(limitedAdTracking)
                ) =>
                  Watch4thGenExtra(
                    deviceOS = deviceOS
                  , deviceOSVersion = deviceOSVersion
                  , deviceMake = deviceMake
                  , deviceModel = deviceModel
                  , width = width.toInt
                  , height = height.toInt
                  , deviceId = deviceId
                  , lang = lang
                  , limitedAdTracking = limitedAdTracking.toInt
                  )
                case _ => deserializationError("Watch4thGenExtra expected")
              }
          }
          implicit val watch4thGenRequestFormat: JsonFormat[Watch4thGenRequest] = new JsonFormat[Watch4thGenRequest] {
            def write(x: Watch4thGenRequest): JsValue = JsObject(
              "device_id" -> JsString(x.device_id)
            , "bandwidth" -> x.bandwidth.map(JsString(_)).getOrElse(JsNull)
            , "platform" -> JsString(x.platform)
            , "extra" -> x.extra.toJson
            )
            def read(value: JsValue): Watch4thGenRequest =
              value.asJsObject.getFields("device_id", "bandwidth", "platform", "extra") match {
                case Seq(JsString(deviceId), bandwidth, JsString(platform), extra) =>
                  Watch4thGenRequest(
                    device_id = deviceId
                  , bandwidth = bandwidth match {
                      case JsString(s) => Some(s)
                      case JsNull => None
                      case _ => deserializationError("Watch4thGenRequest.bandwidth expected string or null")
                    }
                  , platform = platform
                  , extra = extra.convertTo[Watch4thGenExtra]
                  )
                case _ => deserializationError("Watch4thGenRequest expected")
              }
          }
        }
      }
    }

    object Response {
      case class ServerModel(name: Option[String], tuners: Option[Int])
      case class ServerInfo(model: Option[ServerModel])
      case class Watch4thGenResponse(token: Option[String], expires: Option[String], keepalive: Option[Int], playlist_url: Option[String])

      object JsonProtocol extends DefaultJsonProtocol {
        implicit val serverModelFormat: JsonFormat[ServerModel] = jsonFormat2(ServerModel.apply)
        implicit val serverInfoFormat: JsonFormat[ServerInfo] = jsonFormat1(ServerInfo.apply)
        implicit val watch4thGenResponseFormat: JsonFormat[Watch4thGenResponse] = jsonFormat4(Watch4thGenResponse.apply)
      }
    }

    object WatchSession {
      val expiryRetuneLeadSec: Int = 30
      val keepaliveRetrySec: Int = 10
      val watchRetryMax: Int = 2
      val watchRetryDelaySec: Int = 1

      case class Session(
        token: Option[String]
      , expires: Option[java.time.Instant]
      , keepalive: Option[Int]
      , playlistUrl: String
      )

      def sessionPath(token: String): String = s"/player/sessions/$token"

      def keepalivePath(token: String): String = s"${sessionPath(token)}/keepalive"

      def fromResponse(response: Response.Watch4thGenResponse): Either[String, Session] =
        fromResponse(response, None)

      def fromResponse(response: Response.Watch4thGenResponse, fallbackToken: Option[String]): Either[String, Session] =
        response.playlist_url match {
          case Some(url) =>
            Right(Session(
              token = response.token.orElse(fallbackToken)
            , expires = response.expires.flatMap(value => scala.util.Try(java.time.Instant.parse(value)).toOption)
            , keepalive = response.keepalive
            , playlistUrl = url
            ))
          case None => Left("No playlist URL returned")
        }

      def keepaliveDelaySec(session: Session, now: java.time.Instant = java.time.Instant.now()): Option[Int] =
        (session.token, session.keepalive) match {
          case (Some(_), Some(keepalive)) =>
            val lead = math.min(expiryRetuneLeadSec, math.max(1, keepalive / 3))
            var delay = math.max(5, keepalive - lead)
            session.expires.foreach { exp =>
              val untilExpiry = java.time.Duration.between(now, exp).getSeconds.toInt
              val expiryDelay = math.max(5, untilExpiry - expiryRetuneLeadSec)
              if (expiryDelay < delay) delay = expiryDelay
            }
            Some(delay)
          case _ => None
        }

      def isNearExpiry(session: Session, now: java.time.Instant = java.time.Instant.now()): Boolean =
        session.expires.exists(exp => !now.isBefore(exp.minusSeconds(expiryRetuneLeadSec.toLong)))

      def shouldRefreshSession(session: Session, now: java.time.Instant = java.time.Instant.now()): Boolean =
        session.expires.isEmpty || isNearExpiry(session, now)

      def playlistChanged(before: Session, after: Session): Boolean =
        before.playlistUrl != after.playlistUrl
    }

    final case class SessionBackend(authContext: Auth.AuthContext)(implicit system: ActorSystem[?])
        extends SessionManager.SessionBackend {
      import pekko.http.scaladsl.unmarshalling.Unmarshal
      import org.apache.pekko.actor.typed.scaladsl.adapter._
      implicit val ec: scala.concurrent.ExecutionContext = system.executionContext
      val HttpCtx = Http()
      private val scheduler = system.toClassic.scheduler
      @volatile var totalTuners: Int = 4

      def refreshTuners(): Future[Int] = fetchServerInfo()

      def open(channel: SessionManager.ChannelKey): Future[SessionManager.PlayerSession] =
        watchChannel(channel.value).flatMap { data =>
          WatchSession.fromResponse(data) match {
            case Right(session) =>
              session.token match {
                case Some(sessionId) =>
                  Future.successful(
                    SessionManager.PlayerSession(
                      sessionId = sessionId
                    , playlistUrl = session.playlistUrl
                    , expires = session.expires
                    , keepalive = session.keepalive
                    )
                  )
                case None => Future.failed(new RuntimeException("session token missing"))
              }
            case Left(message) => Future.failed(new RuntimeException(message))
          }
        }

      def close(sessionId: SessionManager.SessionId): Future[Unit] = endSession(sessionId)

      def keepalive(session: SessionManager.PlayerSession): Future[SessionManager.PlayerSession] =
        requestSession(WatchSession.keepalivePath(session.sessionId), HttpMethods.POST, Some(session.sessionId))
          .flatMap(toPlayerSession)

      def fetch(session: SessionManager.PlayerSession): Future[SessionManager.PlayerSession] =
        requestSession(WatchSession.sessionPath(session.sessionId), HttpMethods.GET, Some(session.sessionId))
          .flatMap(toPlayerSession)

      private def toPlayerSession(session: WatchSession.Session): Future[SessionManager.PlayerSession] =
        session.token match {
          case Some(sessionId) =>
            Future.successful(
              SessionManager.PlayerSession(
                sessionId = sessionId
              , playlistUrl = session.playlistUrl
              , expires = session.expires
              , keepalive = session.keepalive
              )
            )
          case None => Future.failed(new RuntimeException("session token missing"))
        }

      def fetchServerInfo(): Future[Int] = {
        import Channel.Response.JsonProtocol._

        val serverInfoUri = authContext.deviceUrl.withPath(Uri.Path("/server/info")).withQuery(Uri.Query("lh" -> "1"))
        val headers = Hmac.signedHeaders("GET", "/server/info", None, authContext)

        log.debug("[channel] http GET /server/info")
        val request = HttpRequest(
          method = HttpMethods.GET
        , uri = serverInfoUri
        , headers = headers.toList
        )

        HttpCtx.singleRequest(request).flatMap { response =>
          log.debug("[channel] server/info status={}", response.status.intValue())
          if (response.status.isSuccess()) {
            Unmarshal(response.entity).to[String].map { body =>
              val serverInfo = body.parseJson.convertTo[Response.ServerInfo]
              val tuners = serverInfo.model.flatMap(_.tuners).getOrElse(4)
              totalTuners = tuners
              tuners
            }
          } else {
            val _ = response.entity.discardBytes()
            Future.successful(totalTuners)
          }
        }.recover {
          case ex =>
            log.warn("[channel] server info failed", ex)
            totalTuners
        }
      }

      def watchChannel(channelId: String, attempt: Int = 0): Future[Response.Watch4thGenResponse] = {
        import Channel.Request.Watch4thGenRequest.JsonProtocol.watch4thGenRequestFormat
        import Channel.Response.JsonProtocol.watch4thGenResponseFormat

        val watchUri = authContext.deviceUrl.withPath(Uri.Path(s"/guide/channels/$channelId/watch")).withQuery(Uri.Query("lh" -> "1"))
        val deviceId = java.util.UUID.randomUUID.toString
        val watchBody = Request.Watch4thGenRequest.forDevice(deviceId).toJson.compactPrint
        val headers =
          Hmac.signedHeaders("POST", s"/guide/channels/$channelId/watch", Some(watchBody), authContext)
        val request = HttpRequest(
          method = HttpMethods.POST
        , uri = watchUri
        , headers = headers.toList
        , entity = HttpEntity(ContentTypes.`application/json`, watchBody)
        )
        log.info("[4thgen-channel] guide/channels/{}/watch (POST) - {}", channelId, watchUri)
        HttpCtx.singleRequest(request).flatMap { response =>
          log.debug("[channel] watch status={} attempt={}", response.status.intValue(), attempt)
          response.status match {
            case StatusCodes.ServiceUnavailable if attempt < WatchSession.watchRetryMax =>
              val _ = response.entity.discardBytes()
              log.debug("[channel] watch 503 retry attempt={}", attempt + 1)
              after(WatchSession.watchRetryDelaySec.seconds, scheduler)(watchChannel(channelId, attempt + 1))
            case StatusCodes.ServiceUnavailable =>
              val _ = response.entity.discardBytes()
              Future.failed(Error.NoAvailableTuners)
            case status if !status.isSuccess() =>
              Unmarshal(response.entity).to[String].flatMap { body =>
                Future.failed(new RuntimeException(s"watch failed: ${status.intValue()} ${LogConfig.truncate(body)}"))
              }
            case _ =>
              Unmarshal(response.entity).to[String].map { body =>
                log.debug("[channel] watch body={}", LogConfig.truncate(body))
                body.parseJson.convertTo[Response.Watch4thGenResponse]
              }
          }
        }
      }

      def endSession(token: String): Future[Unit] = {
        val path = WatchSession.sessionPath(token)
        val uri = authContext.deviceUrl.withPath(Uri.Path(path)).withQuery(Uri.Query("lh" -> "1"))
        val headers = Hmac.signedHeaders("DELETE", path, None, authContext)
        val request = HttpRequest(method = HttpMethods.DELETE, uri = uri, headers = headers.toList)
        log.debug("[channel] session DELETE {}", path)
        HttpCtx.singleRequest(request).flatMap { response =>
          if (response.status.isSuccess() || response.status == StatusCodes.NoContent) {
            val _ = response.entity.discardBytes()
            Future.successful(())
          } else {
            Unmarshal(response.entity).to[String].flatMap { body =>
              Future.failed(new RuntimeException(s"session DELETE failed: ${response.status.intValue()} ${LogConfig.truncate(body)}"))
            }
          }
        }
      }

      def parseSessionResponse(body: String, fallbackToken: Option[String]): Either[String, WatchSession.Session] = {
        import Channel.Response.JsonProtocol.watch4thGenResponseFormat
        WatchSession.fromResponse(body.parseJson.convertTo[Response.Watch4thGenResponse], fallbackToken)
      }

      def requestSession(
        path: String
      , method: HttpMethod
      , fallbackToken: Option[String]
      ): Future[WatchSession.Session] = {
        val uri = authContext.deviceUrl.withPath(Uri.Path(path)).withQuery(Uri.Query("lh" -> "1"))
        val headers = Hmac.signedHeaders(method.value, path, None, authContext)
        val request = HttpRequest(method = method, uri = uri, headers = headers.toList)
        HttpCtx.singleRequest(request).flatMap { response =>
          if (response.status.isSuccess()) {
            Unmarshal(response.entity).to[String].flatMap { body =>
              parseSessionResponse(body, fallbackToken) match {
                case Right(session) => Future.successful(session)
                case Left(message) => Future.failed(new RuntimeException(message))
              }
            }
          } else {
            val _ = response.entity.discardBytes()
            Future.failed(new RuntimeException(s"session ${method.value} failed: ${response.status.intValue()}"))
          }
        }
      }
    }

    def route(sessionManager: ActorRef[SessionManager.Command], settings: SessionManager.Settings = SessionManager.Settings())(implicit system: ActorSystem[?]) = {
      import pekko.actor.typed.scaladsl.AskPattern._
      implicit val timeout: pekko.util.Timeout = settings.askTimeout

      path("channel" / Segment) { channelId =>
        get {
          val acquire =
            sessionManager.ask[SessionManager.AcquireResult](
              SessionManager.Acquire(SessionManager.ChannelKey(channelId), _)
            )

          onComplete(acquire) {
            case Success(SessionManager.Attached(attachmentId, source)) =>
              log.info("[channel] streaming channelId={} attachment={}", channelId, attachmentId.toString.take(8))
              val `video/mp2t` = MediaType.customBinary("video", "mp2t", MediaType.NotCompressible)
              complete(
                HttpEntity.Chunked.fromData(
                  pekko.http.scaladsl.model.ContentType(`video/mp2t`),
                  source
                )
              )
            case Success(SessionManager.NoAvailableTuners) =>
              log.warn("[channel] no available tuners channelId={}", channelId)
              complete(HttpResponse(StatusCodes.ServiceUnavailable, entity = "No available tuners"))
            case Success(SessionManager.AcquireFailed(ex)) =>
              log.warn("[channel] acquire failed channelId={}", channelId, ex)
              complete(HttpResponse(StatusCodes.InternalServerError, entity = "Unable to stream channel"))
            case Failure(ex) =>
              log.warn("[channel] acquire ask failed channelId={}", channelId, ex)
              complete(HttpResponse(StatusCodes.InternalServerError, entity = "Unable to stream channel"))
          }
        }
      }
    }
  }

  def routes(lineupActor: ActorRef[Lineup.LineupActor.Request], authContext: Auth.AuthContext, sessionManager: ActorRef[SessionManager.Command])(implicit system: ActorSystem[?]) = {
    Tablo2HDHomeRun.Response.Discover.route ~
    Lineup.route(lineupActor) ~
    Guide.route(authContext) ~
    Channel.route(sessionManager) ~
    Tablo2HDHomeRun.Favicon.route
  }
}
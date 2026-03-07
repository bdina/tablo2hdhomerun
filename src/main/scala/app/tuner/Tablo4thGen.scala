package app.tuner

import org.apache.pekko

import pekko.actor.typed.scaladsl.Behaviors
import pekko.actor.typed.{ActorRef, ActorSystem, Behavior}
import pekko.http.scaladsl.Http
import pekko.http.scaladsl.model.{ContentTypes, HttpEntity, HttpMethods, HttpRequest, HttpResponse, MediaType, StatusCodes, Uri}
import pekko.http.scaladsl.server.Directives._
import pekko.stream.scaladsl.Source
import pekko.util.ByteString

import java.util.concurrent.atomic.AtomicInteger
import java.time.{LocalDate, ZonedDateTime, ZoneOffset}
import java.time.format.DateTimeFormatter
import java.security.MessageDigest

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

import org.slf4j.LoggerFactory

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}

import spray.json._
import DefaultJsonProtocol._

import app.Tablo2HDHomeRun
import app.stream.StreamBackend

object Tablo4thGen {
  val log = LoggerFactory.getLogger(this.getClass)

  val LIGHTHOUSE_BASE_URL = "https://lighthousetv.ewscloud.com/api/v2"
  val USER_AGENT = "Tablo-FAST/1.7.0 (Mobile; iPhone; iOS 18.4)"
  val DefaultHashKey = "6l8jU5N43cEilqItmT3U2M2PFM3qPziilXqau9ys"
  val DefaultDeviceKey = "ljpg6ZkwShVv8aI12E2LP55Ep8vq1uYDPvX0DdTB"
  def deviceHashKey: String = scala.sys.env.get("TABLO_HASH_KEY").orElse(scala.sys.env.get("HashKey")).getOrElse(DefaultHashKey)
  def deviceDeviceKey: String = scala.sys.env.get("TABLO_DEVICE_KEY").orElse(scala.sys.env.get("DeviceKey")).getOrElse(DefaultDeviceKey)

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

    def initialize()(implicit system: ActorSystem[?]): AuthContext = {
      val HttpCtx = Http()

      val email = Tablo2HDHomeRun.TABLO_EMAIL.getOrElse {
        log.error("[4thgen-auth] TABLO_EMAIL environment variable is required for 4th gen mode")
        throw Tablo4thGen.Error.MissingEmail
      }
      val password = Tablo2HDHomeRun.TABLO_PASSWORD.getOrElse {
        log.error("[4thgen-auth] TABLO_PASSWORD environment variable is required for 4th gen mode")
        throw Tablo4thGen.Error.MissingPassword
      }

      log.info(s"[4thgen-auth] initializing authentication for $email")

      import JsonProtocol._
      implicit val ec = system.executionContext

      val authFuture: Future[AuthContext] = for {
        loginResp <- {
          val loginUri = Uri(s"$LIGHTHOUSE_BASE_URL/login/")
          val loginBody = LoginRequest(email, password).toJson.compactPrint
          log.info(s"[4thgen-auth] login (POST) - $loginUri")
          val request = HttpRequest(
            method = HttpMethods.POST
          , uri = loginUri
          , entity = HttpEntity(ContentTypes.`application/json`, loginBody)
          )
          HttpCtx.singleRequest(request).flatMap { response =>
            log.info(s"[4thgen-auth] login response - ${response.status}")
            Unmarshal(response.entity).to[String].map(_.parseJson.convertTo[LoginResponse])
          }
        }
        accessToken = loginResp.access_token.getOrElse {
          throw Tablo4thGen.Error.LoginFailed(loginResp.message.getOrElse("unknown error"))
        }
        accountInfo <- {
          val accountUri = Uri(s"$LIGHTHOUSE_BASE_URL/account/")
          log.info(s"[4thgen-auth] account (GET) - $accountUri")
          import pekko.http.scaladsl.model.headers._
          val request = HttpRequest(
            method = HttpMethods.GET
          , uri = accountUri
          , headers = Seq(Authorization(OAuth2BearerToken(accessToken)))
          )
          HttpCtx.singleRequest(request).flatMap { response =>
            log.info(s"[4thgen-auth] account response - ${response.status}")
            Unmarshal(response.entity).to[String].map(_.parseJson.convertTo[AccountInfo])
          }
        }
        profile = accountInfo.profiles.flatMap(_.headOption).getOrElse {
          throw Tablo4thGen.Error.NoProfilesFound
        }
        device = {
          val devices = accountInfo.devices.getOrElse(Seq.empty)
          val deviceNameFilter = Tablo2HDHomeRun.TABLO_DEVICE_NAME
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
        _ = log.info(s"[4thgen-auth] selected device: ${device.name} (${device.serverId})")
        selectResp <- {
          val selectUri = Uri(s"$LIGHTHOUSE_BASE_URL/account/select/")
          val selectBody = SelectRequest(pid = profile.identifier, sid = device.serverId).toJson.compactPrint
          log.info(s"[4thgen-auth] select (POST) - $selectUri")
          import pekko.http.scaladsl.model.headers._
          val request = HttpRequest(
            method = HttpMethods.POST
          , uri = selectUri
          , headers = Seq(Authorization(OAuth2BearerToken(accessToken)))
          , entity = HttpEntity(ContentTypes.`application/json`, selectBody)
          )
          HttpCtx.singleRequest(request).flatMap { response =>
            log.info(s"[4thgen-auth] select response - ${response.status}")
            Unmarshal(response.entity).to[String].map { body =>
              log.info(s"[4thgen-auth] select body - $body")
              body.parseJson.convertTo[SelectResponse]
            }
          }
        }
        lighthouseToken = selectResp.token.getOrElse {
          throw Tablo4thGen.Error.SelectFailed(selectResp.message.getOrElse("unknown error"))
        }
      } yield {
        val deviceUrl = device.url.map(Uri(_)).getOrElse {
          Uri(s"http://${Tablo2HDHomeRun.TABLO_IP.getHostAddress}:${Tablo2HDHomeRun.TABLO_PORT}")
        }
        AuthContext(
          accessToken = accessToken
        , lighthouseToken = lighthouseToken
        , deviceKey = Tablo4thGen.deviceDeviceKey
        , hashKey = Tablo4thGen.deviceHashKey
        , deviceUrl = deviceUrl
        , profileId = profile.identifier
        , serverId = device.serverId
        )
      }

      val ctx = Await.result(authFuture, 30.seconds)
      _authContext = Some(ctx)
      log.info(s"[4thgen-auth] authentication complete - device URL: ${ctx.deviceUrl}")
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
      log.debug(s"[4thgen-hmac] signature for $method $path")
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
          val url = s"${Tablo2HDHomeRun.discover.BaseURL.withPath(Uri.Path(s"/channel/${channel.identifier}"))}"
          val src = s"${Tablo2HDHomeRun.discover.BaseURL.withPath(Uri.Path(s"/guide/channels/${channel.identifier}/watch"))}"
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
          log.info(s"[4thgen-lineup] guide/channels (GET) - $channelsUri")

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
            log.info(s"[4thgen-lineup] guide/channels response - ${response.status}")
            Unmarshal(response.entity).to[String].map { body =>
              val channels = body.parseJson.convertTo[Seq[ChannelLineup]]
              log.info(s"[4thgen-lineup] channels found: ${channels.size}")
              val jsChannels = channels.map(channelToJsValue)
              context.self ! Command.Store(jsChannels)
              jsChannels
            }
          }.recover {
            case ex =>
              log.error(s"[4thgen-lineup] scan failed: ${ex.getMessage}")
              Seq.empty
          }
        }

        context.self ! Command.Scan

        Behaviors.receiveMessage {
          case Command.Store(channels) =>
            log.info(s"[4thgen-lineup] store channels: ${channels.size}")
            cache = (1.day.fromNow, channels)
            scanInProgress = false
            Behaviors.same

          case Command.Scan if scanInProgress =>
            log.info("[4thgen-lineup] channel scan requested ; already in progress (suppress)")
            Behaviors.same

          case Command.Scan =>
            log.info("[4thgen-lineup] channel scan requested")
            scanInProgress = true
            scan() : Unit
            Behaviors.same

          case Request.Status(sender) =>
            log.info("[4thgen-lineup] channel status requested")
            val (scanning, possible) = if (scanInProgress) (1, 0) else (0, 1)
            sender ! Response.Status(scanInProgress = scanning, scanPossible = possible, replyTo = context.self)
            Behaviors.same

          case Request.Fetch(replyTo) if cache._1.isOverdue() =>
            log.info("[4thgen-lineup] channel fetch - cache expired")
            val sender = replyTo
            scanInProgress = true
            scan().foreach { channels =>
              sender ! Response.Fetch(channels, context.self)
            }
            Behaviors.same

          case Request.Fetch(sender) =>
            val (_, channels) = cache
            log.info(s"[4thgen-lineup] channels found: ${channels.size}")
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
              log.info(s"[4thgen-lineup] Failed: ${ex.getMessage}")
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
              log.info(s"[4thgen-lineup] lineup_status.json (GET) - $response")
              complete(HttpEntity(ContentTypes.`application/json`, response.compactPrint))
            case Failure(ex) =>
              log.info(s"[4thgen-lineup] Failed: ${ex.getMessage}")
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
              log.info(s"[4thgen-guide] guide.xml (GET) - ${guide.size} channels")

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
              log.info(s"[4thgen-guide] Failed: ${ex.getMessage}")
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
          log.info(s"[4thgen-guide] airings (GET) - $airingsUri")

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
              log.info(s"[4thgen-guide] airings response - ${response.status}")
              val _ = response.entity.discardBytes()
              Future.successful(Seq.empty[Guide.GuideAiring])
            }
          }.recover {
            case ex =>
              log.warn(s"[4thgen-guide] failed to fetch airings for $channelId/$date: ${ex.getMessage}")
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
        log.info(s"[4thgen-guide] guide/channels (GET) - $channelsUri")

        val request = HttpRequest(
          method = HttpMethods.GET
        , uri = channelsUri
        , headers = Seq(
            Authorization(OAuth2BearerToken(authContext.accessToken))
          , RawHeader("Lighthouse", authContext.lighthouseToken)
          )
        )

        HttpCtx.singleRequest(request).flatMap { response =>
          log.info(s"[4thgen-guide] guide/channels response - ${response.status}")
          Unmarshal(response.entity).to[String].flatMap { body =>
            val channels = body.parseJson.convertTo[Seq[Lineup.ChannelLineup]]
            log.info(s"[4thgen-guide] channels found: ${channels.size}")

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
            log.error(s"[4thgen-guide] scan failed: ${ex.getMessage}")
            Seq.empty
        }
      }

      context.self ! Command.Scan

      Behaviors.receiveMessage {
        case Command.StoreGuide(guide) =>
          log.info(s"[4thgen-guide] store guide: ${guide.size} channels")
          cache = (1.hour.fromNow, guide)
          scanInProgress = false
          Behaviors.same

        case Command.Scan if scanInProgress =>
          log.info("[4thgen-guide] guide scan requested ; already in progress (suppress)")
          Behaviors.same

        case Command.Scan =>
          log.info("[4thgen-guide] guide scan requested")
          scanInProgress = true
          scan().foreach { guide =>
            context.self ! Command.StoreGuide(guide)
          }
          Behaviors.same

        case Request.FetchGuide(replyTo) if cache._1.isOverdue() =>
          log.info("[4thgen-guide] guide fetch - cache expired")
          scanInProgress = true
          scan().foreach { guide =>
            replyTo ! Response.FetchGuide(guide, context.self)
          }
          Behaviors.same

        case Request.FetchGuide(replyTo) =>
          val (_, guide) = cache
          log.info(s"[4thgen-guide] guide fetch from cache: ${guide.size} channels")
          replyTo ! Response.FetchGuide(guide, context.self)
          Behaviors.same
      }
    }
  }

  object Channel {
    val log = LoggerFactory.getLogger(this.getClass)

    object Request {
      case class Watch4thGenRequest(device_id: String, bandwidth: Option[String], platform: String)
      object Watch4thGenRequest {
        object JsonProtocol extends DefaultJsonProtocol {
          implicit val watch4thGenRequestFormat: JsonFormat[Watch4thGenRequest] = jsonFormat3(Watch4thGenRequest.apply)
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

    private val activeStreams = new AtomicInteger(0)
    @volatile private var totalTuners: Int = 4

    def route(authContext: Auth.AuthContext)(implicit system: ActorSystem[?]) = {
      import pekko.http.scaladsl.unmarshalling.Unmarshal
      implicit val ec: scala.concurrent.ExecutionContext = system.executionContext
      val HttpCtx = Http()

      def fetchServerInfo(): Future[Int] = {
        import Channel.Response.JsonProtocol._

        val serverInfoUri = authContext.deviceUrl.withPath(Uri.Path("/server/info")).withQuery(Uri.Query("lh" -> "1"))
        val headers = Hmac.signedHeaders("GET", "/server/info", None, authContext)

        log.info(s"[4thgen-channel] server/info (GET) - $serverInfoUri")
        val request = HttpRequest(
          method = HttpMethods.GET
        , uri = serverInfoUri
        , headers = headers.toList
        )

        HttpCtx.singleRequest(request).flatMap { response =>
          log.info(s"[4thgen-channel] server/info response - ${response.status}")
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
            log.warn(s"[4thgen-channel] failed to get server info: ${ex.getMessage}")
            totalTuners
        }
      }

      path("channel" / Segment) { channelId =>
        get {
          import Channel.Request.Watch4thGenRequest.JsonProtocol.watch4thGenRequestFormat
          import Channel.Response.JsonProtocol.watch4thGenResponseFormat

          val watchUri = authContext.deviceUrl.withPath(Uri.Path(s"/guide/channels/$channelId/watch")).withQuery(Uri.Query("lh" -> "1"))
          val deviceId = java.util.UUID.randomUUID.toString
          val watchBody = Request.Watch4thGenRequest(device_id = deviceId, bandwidth = None, platform = "ios").toJson.compactPrint
          val headers = Hmac.signedHeaders("POST", s"/guide/channels/$channelId/watch", Some(watchBody), authContext)

          val tunerCheckFuture = fetchServerInfo().map { tuners =>
            val available = tuners - activeStreams.get()
            log.info(s"[4thgen-channel] available tuners - $available/$tuners")
            available > 0
          }

          val watchFuture: Future[Response.Watch4thGenResponse] = tunerCheckFuture.flatMap { available =>
            if (available) {
              log.info(s"[4thgen-channel] guide/channels/$channelId/watch (POST) - $watchUri")
              val request = HttpRequest(
                method = HttpMethods.POST
              , uri = watchUri
              , headers = headers.toList
              , entity = HttpEntity(ContentTypes.`application/json`, watchBody)
              )
              HttpCtx.singleRequest(request).flatMap { response =>
                log.info(s"[4thgen-channel] watch response - ${response.status}")
                Unmarshal(response.entity).to[String].map { body =>
                  log.info(s"[4thgen-channel] watch body - $body")
                  body.parseJson.convertTo[Response.Watch4thGenResponse]
                }
              }
            } else {
              log.info(s"[4thgen-channel] no available tuners")
              Future.failed(Error.NoAvailableTuners)
            }
          }

          def streamWithTunerTracking(playlistUrl: String): Source[ByteString, ?] =
            Source.lazySource { () =>
              val n = activeStreams.incrementAndGet()
              log.info(s"[4thgen-channel] active streams: $n")
              StreamBackend().stream(playlistUrl).watchTermination() { (_, done) =>
                done.onComplete {
                  case Success(_) =>
                    val m = activeStreams.decrementAndGet()
                    log.info(s"[4thgen-channel] stream ended, active streams: $m")
                  case Failure(ex) =>
                    val m = activeStreams.decrementAndGet()
                    log.info(s"[4thgen-channel] stream failed: ${ex.getMessage}, active streams: $m")
                }
              }
            }

          onComplete(watchFuture) {
            case Success(data) =>
              data.playlist_url match {
                case Some(url) =>
                  log.info(s"[4thgen-channel] tuned to channel - playlist: $url")
                  val `video/mp2t` = MediaType.customBinary("video", "mp2t", MediaType.NotCompressible)
                  complete(HttpEntity.Chunked.fromData(pekko.http.scaladsl.model.ContentType(`video/mp2t`), streamWithTunerTracking(url)))
                case None =>
                  log.info(s"[4thgen-channel] no playlist URL in response")
                  complete(HttpResponse(StatusCodes.InternalServerError, entity = "No playlist URL returned"))
              }
            case Failure(ex) =>
              log.info(s"[4thgen-channel] failed to start stream - ${ex.getMessage}")
              complete(HttpResponse(StatusCodes.InternalServerError, entity = "Unable to stream channel"))
          }
        }
      }
    }
  }

  def routes(lineupActor: ActorRef[Lineup.LineupActor.Request], authContext: Auth.AuthContext)(implicit system: ActorSystem[?]) = {
    Tablo2HDHomeRun.Response.Discover.route ~
    Lineup.route(lineupActor) ~
    Guide.route(authContext) ~
    Channel.route(authContext) ~
    Tablo2HDHomeRun.Favicon.route
  }
}

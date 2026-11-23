package app.tuner

import org.apache.pekko

import pekko.NotUsed
import pekko.actor.typed.scaladsl.AskPattern.{Askable, schedulerFromActorSystem}
import pekko.actor.typed.scaladsl.Behaviors
import pekko.actor.typed.{ActorRef, ActorSystem, Behavior}
import pekko.http.scaladsl.Http
import pekko.http.scaladsl.model.{ContentTypes, HttpEntity, HttpHeader, HttpMethod, HttpMethods, HttpRequest, HttpResponse, MediaType, StatusCode, StatusCodes, Uri}
import pekko.http.scaladsl.server.Directives._
import pekko.stream.scaladsl.{BroadcastHub, Keep, Source}
import pekko.stream.{KillSwitches, UniqueKillSwitch}
import pekko.util.ByteString
import pekko.util.Timeout

import java.util.concurrent.{ConcurrentHashMap, ConcurrentMap}
import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}
import java.time.{LocalDate, ZonedDateTime, ZoneOffset}
import java.time.format.DateTimeFormatter
import java.security.MessageDigest

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

import org.apache.pekko.pattern.after

import org.slf4j.LoggerFactory

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.{Failure, Success, Try, boundary}
import scala.util.boundary.break

import spray.json._
import DefaultJsonProtocol._

import app.{AppContext, Tablo2HDHomeRun}
import app.config.TabloAuthEnv
import app.stream.{HlsError, HlsPlaylistPoller, MpegTsSync, StreamBackend}
import app.sys.{LogConfig, SafeJson}

object Tablo4thGen {
  val log = LoggerFactory.getLogger(this.getClass)
  type WithSystem[A] = ActorSystem[?] ?=> A

  val LIGHTHOUSE_BASE_URL = "https://lighthousetv.ewscloud.com/api/v2"
  val USER_AGENT = "Tablo-FAST/1.7.0 (Mobile; iPhone; iOS 18.4)"

  type Error = FourthGenError
  val Error = FourthGenError

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
      given loginRequestFormat: JsonFormat[LoginRequest] = jsonFormat2(LoginRequest.apply)
      given loginResponseFormat: JsonFormat[LoginResponse] = jsonFormat5(LoginResponse.apply)
      given accountProfileFormat: JsonFormat[AccountProfile] = jsonFormat2(AccountProfile.apply)
      given accountDeviceFormat: JsonFormat[AccountDevice] = jsonFormat5(AccountDevice.apply)
      given accountInfoFormat: JsonFormat[AccountInfo] = jsonFormat5(AccountInfo.apply)
      given selectRequestFormat: JsonFormat[SelectRequest] = jsonFormat2(SelectRequest.apply)
      given selectResponseFormat: JsonFormat[SelectResponse] = jsonFormat3(SelectResponse.apply)
    }

    import scala.concurrent.Await
    import pekko.http.scaladsl.unmarshalling.Unmarshal

    case class TabloCredentials(email: String, password: String)
    case class SelectedTarget(profile: AccountProfile, device: AccountDevice)

    @volatile private var _authContext: Option[AuthContext] = None
    @volatile private var _tabloAuth: Option[TabloAuthEnv] = None

    def authContext: Option[AuthContext] = _authContext

    def validateCredentials(tabloAuth: TabloAuthEnv): TabloCredentials | FourthGenError = boundary {
      val email = tabloAuth.email match {
        case Some(value) => value
        case None =>
          log.error("[auth] missing env name=TABLO_EMAIL")
          break(FourthGenError.MissingEmail)
      }
      val password = tabloAuth.password match {
        case Some(value) => value
        case None =>
          log.error("[auth] missing env name=TABLO_PASSWORD")
          break(FourthGenError.MissingPassword)
      }
      TabloCredentials(email, password)
    }

    def selectProfileAndDevice(
      accountInfo: AccountInfo
    , deviceNameFilter: Option[String]
    ): SelectedTarget | FourthGenError = boundary {
      val profile = accountInfo.profiles.flatMap(_.headOption) match {
        case Some(p) => p
        case None    => break(FourthGenError.NoProfilesFound)
      }
      val devices = accountInfo.devices.getOrElse(Seq.empty)
      val device = deviceNameFilter match {
        case Some(name) =>
          devices.find(_.name.toLowerCase.contains(name.toLowerCase)) match {
            case Some(d) => d
            case None    => break(FourthGenError.DeviceNotFound(name))
          }
        case None =>
          devices.find(_.reachability.contains("local")).orElse(devices.headOption) match {
            case Some(d) => d
            case None    => break(FourthGenError.NoDevicesFound)
          }
      }
      SelectedTarget(profile, device)
    }

    def authenticate(tabloAuth: TabloAuthEnv): WithSystem[Future[AuthContext]] = {
      import JsonProtocol.given
      val system = summon[ActorSystem[?]]
      given scala.concurrent.ExecutionContext = system.executionContext

      validateCredentials(tabloAuth) match {
        case err: FourthGenError => Future.failed(err)
        case TabloCredentials(email, password) =>
          val HttpCtx = Http()
          val tablo = AppContext.config.tablo
          log.info("[auth] initialize email={}", email)

          def postLogin(): Future[String] = {
            val loginUri = Uri(s"$LIGHTHOUSE_BASE_URL/login/")
            val loginBody = LoginRequest(email, password).toJson.compactPrint
            log.debug("[auth] http POST /login/")
            val request = HttpRequest(
              method = HttpMethods.POST
            , uri = loginUri
            , entity = HttpEntity(ContentTypes.`application/json`, loginBody)
            )
            HttpCtx.singleRequest(request).flatMap { response =>
              val code = response.status.intValue()
              log.debug("[auth] login status={}", code)
              Unmarshal(response.entity).to[String].flatMap { body =>
                SafeJson.parseAs[LoginResponse](body) match {
                  case Success(loginResp) =>
                    loginResp.access_token match {
                      case Some(token) => Future.successful(token)
                      case None =>
                        val msg = loginResp.message.getOrElse(if (response.status.isSuccess()) "unknown error" else s"HTTP $code")
                        Future.failed(FourthGenError.LoginFailed(msg))
                    }
                  case Failure(ex) =>
                    if (!response.status.isSuccess()) {
                      Future.failed(FourthGenError.LoginFailed(s"HTTP $code ${LogConfig.truncate(body)}"))
                    } else {
                      Future.failed(ex)
                    }
                }
              }
            }
          }

          def fetchAccount(accessToken: String): Future[SelectedTarget] = {
            val accountUri = Uri(s"$LIGHTHOUSE_BASE_URL/account/")
            log.debug("[auth] http GET /account/")
            import pekko.http.scaladsl.model.headers._
            val request = HttpRequest(
              method = HttpMethods.GET
            , uri = accountUri
            , headers = Seq(Authorization(OAuth2BearerToken(accessToken)))
            )
            HttpCtx.singleRequest(request).flatMap { response =>
              val code = response.status.intValue()
              log.debug("[auth] account status={}", code)
              Unmarshal(response.entity).to[String].flatMap { body =>
                SafeJson.parseAs[AccountInfo](body) match {
                  case Success(accountInfo) =>
                    selectProfileAndDevice(accountInfo, tablo.deviceName) match {
                      case err: FourthGenError    => Future.failed(err)
                      case target: SelectedTarget => Future.successful(target)
                    }
                  case Failure(ex) =>
                    if (!response.status.isSuccess()) {
                      Future.failed(FourthGenError.LoginFailed(s"account HTTP $code ${LogConfig.truncate(body)}"))
                    } else {
                      Future.failed(ex)
                    }
                }
              }
            }
          }

          def selectDevice(accessToken: String, target: SelectedTarget): Future[String] = {
            val selectUri = Uri(s"$LIGHTHOUSE_BASE_URL/account/select/")
            val selectBody = SelectRequest(pid = target.profile.identifier, sid = target.device.serverId).toJson.compactPrint
            log.debug("[auth] http POST /account/select/")
            import pekko.http.scaladsl.model.headers._
            val request = HttpRequest(
              method = HttpMethods.POST
            , uri = selectUri
            , headers = Seq(Authorization(OAuth2BearerToken(accessToken)))
            , entity = HttpEntity(ContentTypes.`application/json`, selectBody)
            )
            HttpCtx.singleRequest(request).flatMap { response =>
              val code = response.status.intValue()
              log.debug("[auth] select status={}", code)
              Unmarshal(response.entity).to[String].flatMap { body =>
                log.debug("[auth] select body={}", LogConfig.truncate(body))
                SafeJson.parseAs[SelectResponse](body) match {
                  case Success(selectResp) =>
                    selectResp.token match {
                      case Some(token) => Future.successful(token)
                      case None =>
                        val msg = selectResp.message.getOrElse(if (response.status.isSuccess()) "unknown error" else s"HTTP $code")
                        Future.failed(FourthGenError.SelectFailed(msg))
                    }
                  case Failure(ex) =>
                    if (!response.status.isSuccess()) {
                      Future.failed(FourthGenError.SelectFailed(s"HTTP $code ${LogConfig.truncate(body)}"))
                    } else {
                      Future.failed(ex)
                    }
                }
              }
            }
          }

          for {
            accessToken <- postLogin()
            target <- fetchAccount(accessToken)
            _ = log.info("[auth] device selected name={} serverId={}", target.device.name, target.device.serverId)
            lighthouseToken <- selectDevice(accessToken, target)
          } yield {
            val deviceUrl = target.device.url.map(Uri(_)).getOrElse(Uri(tablo.deviceBaseUri))
            val ctx = AuthContext(
              accessToken = accessToken
            , lighthouseToken = lighthouseToken
            , deviceKey = tablo.deviceKey
            , hashKey = tablo.hashKey
            , deviceUrl = deviceUrl
            , profileId = target.profile.identifier
            , serverId = target.device.serverId
            )
            _authContext = Some(ctx)
            log.info("[auth] complete deviceUrl={}", ctx.deviceUrl)
            ctx
          }
      }
    }

    def initialize(tabloAuth: TabloAuthEnv): WithSystem[Try[AuthContext]] = {
      _tabloAuth = Some(tabloAuth)
      Try(Await.result(authenticate(tabloAuth), 30.seconds))
    }

    def refresh(): WithSystem[Future[AuthContext]] = {
      _tabloAuth match {
        case Some(tabloAuth) =>
          log.info("[auth] refreshing cloud authentication tokens")
          authenticate(tabloAuth)
        case None =>
          _authContext match {
            case Some(ctx) => Future.successful(ctx)
            case None => Future.failed(FourthGenError.MissingEmail)
          }
      }
    }
  }

  object Hmac {
    val log = LoggerFactory.getLogger(this.getClass)
    private val LowerHex = java.util.HexFormat.of()

    private def secretKeySpec(key: String, algorithm: String): SecretKeySpec =
      SecretKeySpec(key.getBytes(java.nio.charset.StandardCharsets.UTF_8), algorithm)

    def md5Hex(input: String): String = {
      val md = MessageDigest.getInstance("MD5")
      val digest = md.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8))
      LowerHex.formatHex(digest)
    }

    def hmacMd5Hex(data: String, key: String): String = {
      val mac = Mac.getInstance("HmacMD5")
      mac.init(secretKeySpec(key, "HmacMD5"))
      val hmacBytes = mac.doFinal(data.getBytes(java.nio.charset.StandardCharsets.UTF_8))
      LowerHex.formatHex(hmacBytes)
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
    case class DeviceChannelInfo(
      call_sign: String
    , name: Option[String]
    , major: Int
    , minor: Int
    , network: Option[String]
    , channel_identifier: String
    , source: Option[String]
    )
    case class DeviceChannelObject(
      object_id: Int
    , path: String
    , channel: DeviceChannelInfo
    )

    object JsonProtocol extends DefaultJsonProtocol {
      given otaChannelInfoFormat: JsonFormat[OtaChannelInfo] = jsonFormat7(OtaChannelInfo.apply)
      given ottChannelInfoFormat: JsonFormat[OttChannelInfo] = jsonFormat7(OttChannelInfo.apply)
      given channelLineupFormat: JsonFormat[ChannelLineup] = jsonFormat5(ChannelLineup.apply)
      given deviceChannelInfoFormat: JsonFormat[DeviceChannelInfo] =
        jsonFormat(DeviceChannelInfo.apply, "call_sign", "name", "major", "minor", "network", "channel_identifier", "source")
      given deviceChannelObjectFormat: JsonFormat[DeviceChannelObject] =
        jsonFormat(DeviceChannelObject.apply, "object_id", "path", "channel")
    }

    final case class VctInfo(vctNumber: String, vctName: String, label: String = "")

    private val channelVctInfo = new java.util.concurrent.ConcurrentHashMap[String, VctInfo]()

    def formatChannelLabel(channel: ChannelLineup): String = {
      val (major, minor, networkOpt, callSignOpt) = channel.kind match {
        case "ota" =>
          val ota = channel.ota.getOrElse(OtaChannelInfo(0, 0, None, None, None, None, None))
          (ota.major, ota.minor, ota.network, ota.callSign)
        case "ott" =>
          val ott = channel.ott.getOrElse(OttChannelInfo(None, None, None, None, None, None, None))
          (ott.major.getOrElse(0), ott.minor.getOrElse(0), ott.network, ott.callSign)
        case _ =>
          (0, 0, None, None)
      }
      val title = networkOpt.map(_.trim).filter(_.nonEmpty)
        .orElse(callSignOpt.map(_.trim).filter(_.nonEmpty))
        .orElse(Option(channel.name).map(_.trim).filter(_.nonEmpty))
        .getOrElse(channel.identifier)
      if (major > 0) s"$title $major.$minor" else title
    }

    def registerChannel(channel: ChannelLineup): String = {
      val label = formatChannelLabel(channel)
      if (channel.identifier != null && channel.identifier.nonEmpty && label.nonEmpty) {
        val (major, minor, callSignOpt) = channel.kind match {
          case "ota" =>
            val ota = channel.ota.getOrElse(OtaChannelInfo(0, 0, None, None, None, None, None))
            (ota.major, ota.minor, ota.callSign)
          case "ott" =>
            val ott = channel.ott.getOrElse(OttChannelInfo(None, None, None, None, None, None, None))
            (ott.major.getOrElse(0), ott.minor.getOrElse(0), ott.callSign)
          case _ =>
            (0, 0, None)
        }
        val vctNumber = if (major > 0) s"$major.$minor" else "0.0"
        val vctName = callSignOpt.map(_.trim).filter(_.nonEmpty)
          .orElse(Option(channel.name).map(_.trim).filter(_.nonEmpty))
          .getOrElse(channel.identifier)
        val _ = channelVctInfo.put(channel.identifier, VctInfo(vctNumber, vctName, label))
      }
      label
    }

    def registerDeviceChannel(obj: DeviceChannelObject): String = {
      val ch = obj.channel
      val kind = ch.source.getOrElse("ota")
      val lineup = ChannelLineup(
        identifier = ch.channel_identifier
      , name = ch.name.getOrElse(ch.call_sign)
      , kind = kind
      , ota = Some(OtaChannelInfo(
          major = ch.major
        , minor = ch.minor
        , callSign = Some(ch.call_sign)
        , network = ch.network
        , streamUrl = None
        , provider = None
        , canRecord = None
        ))
      , ott = None
      )
      registerChannel(lineup)
    }

    def registerChannelsFromJson(channels: Seq[JsValue]): Unit =
      channels.foreach { ch =>
        val _ = Try {
          val fields = ch.asJsObject.fields
          val guideNumber = fields.get("GuideNumber").collect { case JsString(s) => s.trim }.filter(s => s.nonEmpty && s != "0.0")
          val guideName = fields.get("GuideName").collect { case JsString(s) => s.trim }.filter(_.nonEmpty)
          val channelIdOpt = fields.get("URL").collect { case JsString(u) =>
            val clean = u.stripSuffix(".m3u8")
            val idx = clean.lastIndexOf('/')
            if (idx >= 0 && idx < clean.length - 1) clean.substring(idx + 1)
            else clean
          }.filter(_.nonEmpty)
          for {
            channelId <- channelIdOpt
            name <- guideName
          } {
            val label = guideNumber.map(num => s"$name $num").getOrElse(name)
            val vctNum = guideNumber.getOrElse("0.0")
            val _ = channelVctInfo.putIfAbsent(channelId, VctInfo(vctNum, name, label))
          }
        }
      }

    def hasChannelLabel(channelId: String): Boolean =
      channelId != null && channelId.nonEmpty && Option(channelVctInfo.get(channelId)).exists(_.label.nonEmpty)

    def channelLabel(channelId: String): String =
      Option(channelVctInfo.get(channelId)).map(_.label).filter(_.nonEmpty).getOrElse(channelId)

    def vctInfo(channelId: String): VctInfo =
      Option(channelVctInfo.get(channelId)).getOrElse(VctInfo(channelId, channelId))

    def streamLabel(channelId: String, leaseId: String): String =
      s"${channelLabel(channelId)} ($leaseId)"

    def clearChannelLabels(): Unit =
      channelVctInfo.clear()

    def isHd(kind: String, minor: Int, name: String, callSign: Option[String]): Boolean = {
      val nameUpper = name.toUpperCase
      val callUpper = callSign.map(_.toUpperCase).getOrElse("")
      def hasHdMarker(s: String): Boolean =
        s.contains("-HD") || s.contains(" HD") || s.contains("_HD") ||
        s.endsWith("HD") || s.contains("-DT") || s.contains("HDTV")

      kind match {
        case "ota" =>
          minor == 1 || hasHdMarker(nameUpper) || hasHdMarker(callUpper)
        case "ott" =>
          nameUpper.contains("HD") || callUpper.contains("HD") || hasHdMarker(nameUpper) || hasHdMarker(callUpper)
        case _ =>
          false
      }
    }

    def channelToJsValue(channel: ChannelLineup, baseUrl: Uri): JsValue = {
      val _ = registerChannel(channel)
      val (major, minor, callSign, source, isHdChannel) = channel.kind match {
        case "ota" =>
          val ota = channel.ota.getOrElse(OtaChannelInfo(0, 0, None, None, None, None, None))
          (ota.major, ota.minor, ota.callSign.getOrElse(channel.name), "antenna", isHd("ota", ota.minor, channel.name, ota.callSign))
        case "ott" =>
          val ott = channel.ott.getOrElse(OttChannelInfo(None, None, None, None, None, None, None))
          val minor = ott.minor.getOrElse(0)
          (ott.major.getOrElse(0), minor, ott.callSign.getOrElse(channel.name), "streaming", isHd("ott", minor, channel.name, ott.callSign))
        case _ =>
          (0, 0, channel.name, "unknown", false)
      }
      val num = s"$major.$minor"
      val url = s"${baseUrl.withPath(Uri.Path(s"/channel/${channel.identifier}"))}"
      val src = s"${baseUrl.withPath(Uri.Path(s"/guide/channels/${channel.identifier}/watch"))}"
      JsObject(
        "GuideNumber" -> JsString(num)
      , "GuideName" -> JsString(callSign)
      , "URL" -> JsString(url)
      , "HD" -> JsNumber(if (isHdChannel) 1 else 0)
      , "type" -> JsString(source)
      , "srcURL" -> JsString(src)
      )
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
        case object ScanFailed extends Command
      }

      def apply(authContext: Auth.AuthContext): Behavior[Request] = Behaviors.setup { context =>
        given ActorSystem[Nothing] = context.system
        given scala.concurrent.ExecutionContext = context.executionContext
        val HttpCtx = Http()

        def channelToJsValue(channel: ChannelLineup): JsValue =
          Lineup.channelToJsValue(channel, AppContext.discover.BaseURL)

        def fetchGuideChannels(ctx: Auth.AuthContext, allowRefresh: Boolean): Future[Seq[ChannelLineup]] = {
          import Lineup.JsonProtocol.given
          import pekko.http.scaladsl.unmarshalling.Unmarshal
          import pekko.http.scaladsl.model.headers._

          val channelsUri = Uri(s"$LIGHTHOUSE_BASE_URL/account/${ctx.lighthouseToken}/guide/channels/")
          log.debug("[lineup] http GET /guide/channels")
          val request = HttpRequest(
            method = HttpMethods.GET
          , uri = channelsUri
          , headers = Seq(
              Authorization(OAuth2BearerToken(ctx.accessToken))
            , RawHeader("Lighthouse", ctx.lighthouseToken)
            )
          )

          HttpCtx.singleRequest(request).flatMap { response =>
            log.debug("[lineup] http GET /guide/channels status={}", response.status.intValue())
            if ((response.status == StatusCodes.Unauthorized || response.status == StatusCodes.Forbidden) && allowRefresh) {
              val _ = response.entity.discardBytes()
              Auth.refresh().flatMap(refreshed => fetchGuideChannels(refreshed, allowRefresh = false))
            } else if (!response.status.isSuccess()) {
              val code = response.status.intValue()
              val _ = response.entity.discardBytes()
              Future.failed(FourthGenError.SessionFailed(s"guide channels HTTP $code", Some(response.status)))
            } else {
              Unmarshal(response.entity).to[String].flatMap { body =>
                Future.fromTry(SafeJson.parseAs[Seq[ChannelLineup]](body))
              }
            }
          }
        }

        def scan(): Future[Seq[JsValue]] = {
          val activeAuth = Auth.authContext.getOrElse(authContext)
          fetchGuideChannels(activeAuth, allowRefresh = true).map { channels =>
            log.info("[lineup] scan complete count={}", channels.size)
            channels.map(channelToJsValue)
          }
        }

        withScanBehavior(context, () => scan(), autoScanOnStart = true)
      }

      private[tuner] def withScan(
        scanFn: () => Future[Seq[JsValue]]
      , autoScanOnStart: Boolean = true
      ): Behavior[Request] = Behaviors.setup { context =>
        withScanBehavior(context, scanFn, autoScanOnStart)
      }

      private def withScanBehavior(
        context: org.apache.pekko.actor.typed.scaladsl.ActorContext[Request]
      , scanFn: () => Future[Seq[JsValue]]
      , autoScanOnStart: Boolean
      ): Behavior[Request] = {
        var cache: (scala.concurrent.duration.Deadline, Seq[JsValue]) = (0.seconds.fromNow, Seq.empty)
        var scanInProgress: Boolean = false
        var pendingWaiters: List[ActorRef[Response.Fetch]] = Nil

        def triggerScan(): Unit =
          if (!scanInProgress) {
            scanInProgress = true
            context.pipeToSelf(scanFn()) {
              case Success(channels) => Command.Store(channels)
              case Failure(ex) =>
                log.error("[lineup] scan failed", ex)
                Command.ScanFailed
            }
          }

        if (autoScanOnStart) {
          context.self ! Command.Scan
        }

        Behaviors.receiveMessage {
          case Command.Store(channels) =>
            log.info("[lineup] store count={}", channels.size)
            Lineup.registerChannelsFromJson(channels)
            if (channels.nonEmpty || cache._2.isEmpty) {
              cache = (1.day.fromNow, channels)
            } else {
              log.warn("[lineup] keeping existing cached lineup count={} after empty scan", cache._2.size)
              cache = (5.minutes.fromNow, cache._2)
            }
            scanInProgress = false
            val waiters = pendingWaiters
            pendingWaiters = Nil
            waiters.reverse.foreach(_ ! Response.Fetch(cache._2, context.self))
            Behaviors.same

          case Command.ScanFailed =>
            scanInProgress = false
            if (cache._2.nonEmpty) {
              cache = (1.minute.fromNow, cache._2)
            }
            val waiters = pendingWaiters
            pendingWaiters = Nil
            waiters.reverse.foreach(_ ! Response.Fetch(cache._2, context.self))
            Behaviors.same

          case Command.Scan if scanInProgress =>
            log.debug("[lineup] scan already in progress")
            Behaviors.same

          case Command.Scan =>
            log.info("[lineup] scan requested")
            triggerScan()
            Behaviors.same

          case Request.Status(sender) =>
            log.debug("[lineup] status requested")
            val (scanning, possible) = if (scanInProgress) (1, 0) else (0, 1)
            sender ! Response.Status(scanInProgress = scanning, scanPossible = possible, replyTo = context.self)
            Behaviors.same

          case Request.Fetch(replyTo) if cache._1.isOverdue() =>
            log.debug("[lineup] fetch cache expired")
            if (cache._2.nonEmpty) {
              replyTo ! Response.Fetch(cache._2, context.self)
              triggerScan()
            } else {
              pendingWaiters = replyTo :: pendingWaiters
              triggerScan()
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

    def route(lineupActor: ActorRef[LineupActor.Request]): WithSystem[pekko.http.scaladsl.server.Route] = {
      path("lineup.json") {
        get {
          given pekko.util.Timeout = 3.seconds
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
          given pekko.util.Timeout = 3.seconds
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
      } ~
      path("lineup.post") {
        post {
          parameter("scan".optional) { scanOpt =>
            log.info("[lineup] lineup.post scan={}", scanOpt.getOrElse("none"))
            scanOpt match {
              case Some("abort") =>
                complete(StatusCodes.OK)
              case _ =>
                lineupActor ! LineupActor.Command.Scan
                complete(StatusCodes.OK)
            }
          }
        }
      } ~
      path("lineup.m3u" | "lineup.m3u8") {
        get {
          val hlsEnabled = Option(AppContext.config).forall(_.proxy.enableHlsEndpoint)
          if (!hlsEnabled) {
            complete(HttpResponse(StatusCodes.NotFound, entity = "HLS endpoint disabled"))
          } else {
            given pekko.util.Timeout = 3.seconds
            val lineupF: Future[LineupActor.Response.Fetch] = lineupActor.ask(replyTo => LineupActor.Request.Fetch(replyTo))
            onComplete(lineupF) {
              case Success(LineupActor.Response.Fetch(channels, _)) =>
                val sb = new java.lang.StringBuilder("#EXTM3U\n")
                channels.foreach { ch =>
                  val obj = ch.asJsObject.fields
                  val name = obj.get("GuideName").map { case JsString(s) => s; case _ => "" }.getOrElse("")
                  val num = obj.get("GuideNumber").map { case JsString(s) => s; case _ => "" }.getOrElse("")
                  val url = obj.get("URL").map { case JsString(s) => s; case _ => "" }.getOrElse("")
                  val hlsUrl = s"${url}.m3u8"
                  val _ = sb.append(s"""#EXTINF:-1 tvg-id="$num" tvg-name="$name" tvg-chno="$num" group-title="Antenna",$name\n""")
                  val _ = sb.append(s"$hlsUrl\n")
                }
                val mpegurl = MediaType.customBinary("application", "x-mpegURL", MediaType.NotCompressible)
                complete(HttpEntity(pekko.http.scaladsl.model.ContentType(mpegurl), ByteString(sb.toString())))
              case Failure(ex) =>
                log.warn("[lineup] lineup.m3u failed", ex)
                complete(HttpResponse(StatusCodes.InternalServerError, entity = "Unable to produce M3U lineup"))
            }
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
      given seasonInfoFormat: JsonFormat[SeasonInfo] = jsonFormat3(SeasonInfo.apply)
      given episodeInfoFormat: JsonFormat[EpisodeInfo] = jsonFormat4(EpisodeInfo.apply)
      given movieAiringInfoFormat: JsonFormat[MovieAiringInfo] = jsonFormat3(MovieAiringInfo.apply)
      given sportEventInfoFormat: JsonFormat[SportEventInfo] = jsonFormat1(SportEventInfo.apply)
      given airingChannelFormat: JsonFormat[AiringChannel] = jsonFormat1(AiringChannel.apply)
      given guideAiringFormat: JsonFormat[GuideAiring] = jsonFormat11(GuideAiring.apply)
    }

    def route(authContext: Auth.AuthContext): WithSystem[pekko.http.scaladsl.server.Route] = {
      path("guide.xml") {
        get {
          given pekko.util.Timeout = 10.seconds

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

    private var _instance: Option[ActorRef[Request]] = None

    def instance(authContext: Auth.AuthContext): WithSystem[ActorRef[Request]] = synchronized {
      _instance.getOrElse {
        val actor = summon[ActorSystem[?]].systemActorOf(apply(authContext), "guide-actor-4thgen-singleton", pekko.actor.typed.Props.empty)
        _instance = Some(actor)
        actor
      }
    }

    def apply(authContext: Auth.AuthContext): Behavior[Request] = Behaviors.setup { context =>
      given ActorSystem[Nothing] = context.system
      given scala.concurrent.ExecutionContext = context.executionContext
      var cache: (scala.concurrent.duration.Deadline, Seq[Tablo2HDHomeRun.Guide.ChannelGuide]) = (0.seconds.fromNow, Seq.empty)
      var scanInProgress: Boolean = false
      var pendingWaiters: List[ActorRef[Response.FetchGuide]] = Nil

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

      def fetchAiringsForChannel(channelId: String, channelName: String, major: Int, minor: Int, currentAuth: Auth.AuthContext): Future[Tablo2HDHomeRun.Guide.ChannelGuide] = {
        import Guide.JsonProtocol.given
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
              Authorization(OAuth2BearerToken(currentAuth.accessToken))
            , RawHeader("Lighthouse", currentAuth.lighthouseToken)
            )
          )

          HttpCtx.singleRequest(request).flatMap { response =>
            if (response.status.isSuccess()) {
              Unmarshal(response.entity).to[String].map { body =>
                SafeJson.parseAs[Seq[Guide.GuideAiring]](body).getOrElse(Seq.empty)
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

      def scan(retriedAuth: Boolean = false): Future[Seq[Tablo2HDHomeRun.Guide.ChannelGuide]] = {
        import Lineup.JsonProtocol.given
        import pekko.http.scaladsl.unmarshalling.Unmarshal
        import pekko.http.scaladsl.model.headers._

        val currentAuth = Auth.authContext.getOrElse(authContext)
        val channelsUri = Uri(s"$LIGHTHOUSE_BASE_URL/account/${currentAuth.lighthouseToken}/guide/channels/")
        log.debug("[guide] http GET /guide/channels")

        val request = HttpRequest(
          method = HttpMethods.GET
        , uri = channelsUri
        , headers = Seq(
            Authorization(OAuth2BearerToken(currentAuth.accessToken))
          , RawHeader("Lighthouse", currentAuth.lighthouseToken)
          )
        )

        HttpCtx.singleRequest(request).flatMap { response =>
          val code = response.status.intValue()
          log.debug("[guide] http GET /guide/channels status={}", code)
          if ((code == 401 || code == 403) && !retriedAuth) {
            val _ = response.entity.discardBytes()
            log.warn("[guide] Lighthouse returned status={}, refreshing cloud auth tokens", code)
            Auth.refresh().flatMap(_ => scan(retriedAuth = true))
          } else if (!response.status.isSuccess()) {
            val _ = response.entity.discardBytes()
            log.warn("[guide] Lighthouse /guide/channels returned non-success status={}", code)
            Future.successful(Seq.empty)
          } else {
            Unmarshal(response.entity).to[String].flatMap { body =>
              SafeJson.parseAs[Seq[Lineup.ChannelLineup]](body) match {
                case Failure(ex) =>
                  log.error("[guide] failed to parse channels JSON", ex)
                  Future.successful(Seq.empty)
                case Success(channels) =>
                  channels.foreach(Lineup.registerChannel)
                  log.info("[guide] scan channels count={}", channels.size)

                  Source(channels.toVector)
                    .mapAsync(4) { channel =>
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
                      fetchAiringsForChannel(channel.identifier, callSign, major, minor, currentAuth)
                    }
                    .runWith(pekko.stream.scaladsl.Sink.seq)
              }
            }
          }
        }
      }

      def triggerScan(): Unit =
        if (!scanInProgress) {
          scanInProgress = true
          context.pipeToSelf(scan()) {
            case Success(guide) => Command.StoreGuide(guide)
            case Failure(ex) =>
              log.error("[guide] scan failed", ex)
              Command.StoreGuide(Seq.empty)
          }
        }

      context.self ! Command.Scan

      Behaviors.receiveMessage {
        case Command.StoreGuide(guide) =>
          log.info("[guide] store count={}", guide.size)
          if (guide.nonEmpty || cache._2.isEmpty) {
            cache = (1.hour.fromNow, guide)
          } else {
            log.warn("[guide] scan returned empty guide; retaining cached guide count={}", cache._2.size)
            cache = (5.minutes.fromNow, cache._2)
          }
          scanInProgress = false
          val waiters = pendingWaiters
          pendingWaiters = Nil
          waiters.reverse.foreach(_ ! Response.FetchGuide(cache._2, context.self))
          Behaviors.same

        case Command.Scan if scanInProgress =>
          log.debug("[guide] scan already in progress")
          Behaviors.same

        case Command.Scan =>
          log.info("[guide] scan requested")
          triggerScan()
          Behaviors.same

        case Request.FetchGuide(replyTo) if cache._1.isOverdue() =>
          if (cache._2.nonEmpty) {
            log.debug("[guide] fetch cache expired; serving stale cache count={} and refreshing in background", cache._2.size)
            triggerScan()
            replyTo ! Response.FetchGuide(cache._2, context.self)
          } else {
            log.debug("[guide] fetch cache expired and empty; scanning")
            pendingWaiters = replyTo :: pendingWaiters
            triggerScan()
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
          given watch4thGenExtraFormat: JsonFormat[Watch4thGenExtra] = new JsonFormat[Watch4thGenExtra] {
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
          given watch4thGenRequestFormat: JsonFormat[Watch4thGenRequest] = new JsonFormat[Watch4thGenRequest] {
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
      case class ServerInfo(model: Option[ServerModel], tuners: Option[Int] = None) {
        def detectedTuners: Option[Int] = tuners.orElse(model.flatMap(_.tuners))
      }
      case class Watch4thGenResponse(token: Option[String], expires: Option[String], keepalive: Option[Int], playlist_url: Option[String])

      object JsonProtocol extends DefaultJsonProtocol {
        given serverModelFormat: JsonFormat[ServerModel] = jsonFormat2(ServerModel.apply)
        given serverInfoFormat: JsonFormat[ServerInfo] = jsonFormat2(ServerInfo.apply)
        given watch4thGenResponseFormat: JsonFormat[Watch4thGenResponse] = jsonFormat4(Watch4thGenResponse.apply)
      }
    }

    object WatchSession {
      val expiryRetuneLeadSec: Int = 30
      val keepaliveRetrySec: Int = 10
      val watchRetryMax: Int = 2
      val watchRetryDelaySec: Int = 1
      val initialTuneTimeoutSec: Int = 60

      def initialTuneTimeout(configuredRecoveryTimeoutSec: Int): FiniteDuration =
        if (configuredRecoveryTimeoutSec > 0) configuredRecoveryTimeoutSec.seconds
        else initialTuneTimeoutSec.seconds

      def classifyWatch503(body: String): Tablo4thGen.Error =
        classifyWatchFailure(StatusCodes.ServiceUnavailable, body)

      /** Classifies non-2xx HTTP responses from Tablo /guide/channels/{id}/watch. */
      def classifyWatchFailure(status: StatusCode, body: String): Tablo4thGen.Error = {
        val codeInt = status.intValue()
        val (category, truncated) = HDHomeRunError.classifyUpstreamHttpFailure(status, body)
        category match {
          case HDHomeRunError.FailureCategory.ChannelNotFound =>
            Tablo4thGen.Error.ChannelNotFound(s"watch $codeInt: $truncated")
          case HDHomeRunError.FailureCategory.NoAvailableTuners =>
            Tablo4thGen.Error.NoAvailableTuners
          case HDHomeRunError.FailureCategory.WeakSignal =>
            Tablo4thGen.Error.NoSignalLock(s"watch $codeInt: $truncated")
          case HDHomeRunError.FailureCategory.SystemBusy =>
            Tablo4thGen.Error.SystemBusy(s"watch $codeInt: $truncated")
          case HDHomeRunError.FailureCategory.EmptyBodyTuneFailure =>
            Tablo4thGen.Error.WatchFailed("watch 503: service unavailable (signal lock or tuner busy)")
          case HDHomeRunError.FailureCategory.TuneFailed =>
            val prefix = if (status == StatusCodes.ServiceUnavailable) "watch 503:" else s"watch failed: $codeInt"
            Tablo4thGen.Error.WatchFailed(s"$prefix $truncated")
        }
      }

      def retryInitialTune(
        tune: Int => Future[Session]
      , onCancelRelease: Session => Unit
      , isCancelled: () => Boolean
      , scheduleRetry: (FiniteDuration, () => Unit) => pekko.actor.Cancellable
      , onRetryTimer: pekko.actor.Cancellable => Unit
      , timeoutBudget: FiniteDuration
      , retryDelay: FiniteDuration = 2.seconds
      , onAttemptFailed: (Int, Throwable, FiniteDuration) => Unit = (_, _, _) => ()
      )(using ec: scala.concurrent.ExecutionContext): Future[Session] = {
        val promise = scala.concurrent.Promise[Session]()
        val deadline = System.nanoTime() + timeoutBudget.toNanos

        def attempt(n: Int): Unit =
          if (isCancelled()) {
            val _ = promise.tryFailure(Tablo4thGen.Error.WatchCancelled)
          } else {
            Try(tune(n)).fold(Future.failed, identity).onComplete {
              case Success(session) =>
                if (isCancelled()) {
                  onCancelRelease(session)
                  val _ = promise.tryFailure(Tablo4thGen.Error.WatchCancelled)
                } else {
                  val _ = promise.trySuccess(session)
                }
              case Failure(ex) =>
                if (isCancelled()) {
                  val _ = promise.tryFailure(Tablo4thGen.Error.WatchCancelled)
                } else if (timeoutBudget.toNanos > 0 && System.nanoTime() >= deadline) {
                  val _ = promise.tryFailure(ex)
                } else {
                  onAttemptFailed(n, ex, retryDelay)
                  val timer = scheduleRetry(retryDelay, () => attempt(n + 1))
                  onRetryTimer(timer)
                  if (isCancelled()) {
                    val _ = timer.cancel()
                    val _ = promise.tryFailure(Tablo4thGen.Error.WatchCancelled)
                  }
                }
            }(using ec)
          }

        attempt(1)
        promise.future
      }

      case class Session(
        token: Option[String]
      , expires: Option[java.time.Instant]
      , keepalive: Option[Int]
      , playlistUrl: String
      )

      def sessionPath(token: String): String = s"/player/sessions/$token"

      def keepalivePath(token: String): String = s"${sessionPath(token)}/keepalive"

      def decodeSession(
        response: Response.Watch4thGenResponse
      , fallbackToken: Option[String] = None
      ): Session | FourthGenError =
        response.playlist_url.map(_.trim).filter(_.nonEmpty) match {
          case Some(url) =>
            Session(
              token = response.token.orElse(fallbackToken)
            , expires = response.expires.flatMap(value => scala.util.Try(java.time.Instant.parse(value)).toOption)
            , keepalive = response.keepalive
            , playlistUrl = url
            )
          case None => FourthGenError.NoVideoData("No playlist URL returned")
        }

      def fromResponse(response: Response.Watch4thGenResponse): Either[String, Session] =
        fromResponse(response, None)

      def fromResponse(response: Response.Watch4thGenResponse, fallbackToken: Option[String]): Either[String, Session] =
        decodeSession(response, fallbackToken) match {
          case session: Session   => Right(session)
          case err: FourthGenError => Left(err.getMessage)
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

      enum ProbeOutcome derives CanEqual {
        case Alive(session: Session, playlistChanged: Boolean)
        case TransientFailure
        case ClosedByTablo
        case ProbeExhausted
      }

      def isSessionClosedStatus(status: StatusCode): Boolean =
        status == StatusCodes.NotFound ||
        status == StatusCodes.Gone ||
        status == StatusCodes.Unauthorized ||
        status == StatusCodes.Forbidden ||
        status == StatusCodes.BadRequest

      def isSessionClosedError(error: Throwable): Boolean =
        error match {
          case Error.SessionFailed(_, Some(status)) => isSessionClosedStatus(status)
          case Error.SessionTokenMissing => true
          case _ => false
        }

      def classifyProbeResult(
        previous: Session
      , result: Try[Session]
      , consecutiveFailures: Int
      , maxFailures: Int
      ): (ProbeOutcome, Int) =
        result match {
          case Success(updated) =>
            (ProbeOutcome.Alive(updated, playlistChanged(previous, updated)), 0)
          case Failure(ex) if isSessionClosedError(ex) =>
            (ProbeOutcome.ClosedByTablo, consecutiveFailures + 1)
          case Failure(_) =>
            val nextFailures = consecutiveFailures + 1
            if (nextFailures >= maxFailures) (ProbeOutcome.ProbeExhausted, nextFailures)
            else (ProbeOutcome.TransientFailure, nextFailures)
        }

      /** Release the previous Tablo session before re-watching so the device performs a cold tune
        * (fresh tuner lock + new segment file) instead of re-attaching to a stalled pipeline.
        * A failed release is ignored; the tune always runs. */
      def releaseThenTune[A](
        release: () => Future[Unit]
      , tune: () => Future[A]
      )(using ec: scala.concurrent.ExecutionContext): Future[A] =
        Try(release()).fold(Future.failed, identity).recover { case _ => () }.flatMap(_ => tune())

      def coldRetune[A](
        sessionClosedByTablo: Boolean
      , release: () => Future[Unit]
      , tune: () => Future[A]
      )(using ec: scala.concurrent.ExecutionContext): Future[A] =
        if (sessionClosedByTablo) tune()
        else releaseThenTune(release, tune)

      enum LateRetuneAction derives CanEqual {
        case Accept
        case ReleaseLeaked
        case ReleaseSuperseded
      }

      def classifyColdRetuneCompletion(
        attempt: Int
      , currentAttempt: Int
      , leaseStopped: Boolean
      ): LateRetuneAction =
        if (leaseStopped) LateRetuneAction.ReleaseLeaked
        else if (attempt != currentAttempt) LateRetuneAction.ReleaseSuperseded
        else LateRetuneAction.Accept
    }

    object SessionIds {
      opaque type ChannelId <: String = String
      object ChannelId {
        inline def apply(value: String): ChannelId = value
        extension (id: ChannelId) {
          inline def asString: String = id
        }
      }

      opaque type ClientId <: String = String
      object ClientId {
        inline def apply(value: String): ClientId = value
        extension (id: ClientId) {
          inline def asString: String = id
        }
      }

      opaque type LeaseId <: String = String
      object LeaseId {
        inline def apply(value: String): LeaseId = value
        extension (id: LeaseId) {
          inline def asString: String = id
        }
      }
    }

    object SessionManager {
      export SessionIds.{ChannelId, ClientId, LeaseId}

      val channelHeaderCache: ConcurrentMap[String, MpegTsSync.CachedHeaders] =
        new ConcurrentHashMap[String, MpegTsSync.CachedHeaders]()

      def getCachedHeaders(channelId: String): MpegTsSync.CachedHeaders =
        Option(channelHeaderCache.get(channelId)).getOrElse(MpegTsSync.CachedHeaders())

      def setCachedHeaders(channelId: String, headers: MpegTsSync.CachedHeaders): Unit =
        if (!headers.isEmpty) {
          val _ = channelHeaderCache.put(channelId, headers)
        }

      def clearCachedHeaders(): Unit =
        channelHeaderCache.clear()

      def defaultIdleGrace: FiniteDuration =
        Option(AppContext.config).map(_.proxy.idleGraceSec.seconds).getOrElse(180.seconds)
      val IdleGrace: FiniteDuration = 180.seconds
      val BroadcastHubBufferSize: Int = 1024

      sealed trait Request

      object Request {
        case class Acquire(channelId: String, clientId: String, replyTo: ActorRef[Response.Acquire]) extends Request
        case class Release(channelId: String, clientId: String) extends Request
        case class SetTotalTuners(total: Int) extends Request
        case class GetSessionMeta(channelId: String, replyTo: ActorRef[Option[TabloSessionMeta]]) extends Request
        case class GetTunerStatuses(replyTo: ActorRef[Seq[HDHomeRunStatus.TunerStatus]]) extends Request
        case class GetActiveChannelStates(replyTo: ActorRef[Map[String, String]]) extends Request
        case class ShutdownAll(replyTo: ActorRef[pekko.Done]) extends Request
      }

      sealed trait Response

      object Response {
        sealed trait Acquire
        case class Attached(source: Source[ByteString, NotUsed]) extends Acquire
        case class Rejected(reason: RejectReason) extends Acquire
      }

      sealed trait RejectReason
      object RejectReason {
        case object NoTuners extends RejectReason
        case class Failed(cause: Throwable) extends RejectReason
      }

      case class TabloSessionMeta(
        token: String
      , expires: Option[java.time.Instant]
      , keepalive: Option[Int]
      , playlistUrl: String
      , leaseId: String = ""
      , metricsRef: AtomicReference[ app.stream.StreamMetrics.WindowSnapshot ] =
          new AtomicReference(app.stream.StreamMetrics.WindowSnapshot.initial)
      , isHealthy: () => Boolean = () => true
      )

      sealed trait Command extends Request

      object Command {
        case class CheckIn(
          channelId: String
        , meta: TabloSessionMeta
        , hubSource: Source[ByteString, NotUsed]
        , teardown: () => Unit
        , cachedHeadersRef: AtomicReference[MpegTsSync.CachedHeaders] =
            new AtomicReference(MpegTsSync.CachedHeaders())
        ) extends Command

        case class AcquireFailed(channelId: String, cause: Throwable) extends Command
        case class GraceExpired(channelId: String) extends Command
        case class UpstreamEnded(channelId: String, leaseId: String) extends Command
        case class SessionUnhealthy(channelId: String, leaseId: String, reason: String) extends Command
      }

      private sealed trait SessionState

      private object SessionState {
        case class Waiter(clientId: ClientId, replyTo: ActorRef[Response.Acquire])
        case class Opening(waiters: Vector[Waiter], cancel: () => Unit = () => ()) extends SessionState

        case class Live(
          meta: TabloSessionMeta
        , hubSource: Source[ByteString, NotUsed]
        , teardown: () => Unit
        , clientIds: Set[ClientId]
        , cachedHeadersRef: AtomicReference[MpegTsSync.CachedHeaders] =
            new AtomicReference(MpegTsSync.CachedHeaders())
        ) extends SessionState

        case class IdleGrace(
          meta: TabloSessionMeta
        , hubSource: Source[ByteString, NotUsed]
        , teardown: () => Unit
        , cachedHeadersRef: AtomicReference[MpegTsSync.CachedHeaders] =
            new AtomicReference(MpegTsSync.CachedHeaders())
        , drainSwitch: Option[pekko.stream.UniqueKillSwitch] = None
        , enteredAtNanos: Long = System.nanoTime()
        ) extends SessionState
      }

      private case class GraceTimerKey(channelId: ChannelId)

      def apply(
        startRunner: (String, ActorRef[Request]) => Unit
      , totalTuners: Int = 4
      , idleGrace: FiniteDuration = defaultIdleGrace
      ): Behavior[Request] =
        withCancel(
          startRunner = { (channelId, self) =>
            startRunner(channelId, self)
            () => ()
          }
        , totalTuners = totalTuners
        , idleGrace = idleGrace
        )

      def withCancel(
        startRunner: (String, ActorRef[Request]) => () => Unit
      , totalTuners: Int = 4
      , idleGrace: FiniteDuration = defaultIdleGrace
      ): Behavior[Request] = Behaviors.setup { context =>
        Behaviors.withTimers { timers =>
        var sessions = Map.empty[ChannelId, SessionState]
        var tuners = totalTuners

        def occupied: Int = sessions.size

        def openChannel(channelId: ChannelId, clientId: ClientId, replyTo: ActorRef[Response.Acquire]): Unit = {
          val waiter = SessionState.Waiter(clientId, replyTo)
          sessions += channelId -> SessionState.Opening(Vector(waiter))
          context.log.info(
            "[session] opening channel={} channelId={} clientId={} occupied={}/{}"
          , Lineup.channelLabel(channelId.asString)
          , channelId.asString
          , clientId.asString
          , occupied
          , tuners
          )
          val cancel = startRunner(channelId.asString, context.self)
          sessions.get(channelId) match {
            case Some(opening: SessionState.Opening) =>
              sessions += channelId -> opening.copy(cancel = cancel)
            case _ =>
              ()
          }
        }

        def attachClient(
          channelId: ChannelId
        , clientId: ClientId
        , hubSource: Source[ByteString, NotUsed]
        , cachedHeadersRef: AtomicReference[MpegTsSync.CachedHeaders]
        , replyTo: ActorRef[Response.Acquire]
        ): Unit = {
          val current = cachedHeadersRef.get()
          if (current.isEmpty) {
            val fallback = getCachedHeaders(channelId.asString)
            if (!fallback.isEmpty) {
              val _ = cachedHeadersRef.compareAndSet(current, fallback)
            }
          }
          val chLabel = Lineup.channelLabel(channelId.asString)
          context.log.info(
            "[session] attach channel={} channelId={} clientId={} occupied={}/{}"
          , chLabel
          , channelId.asString
          , clientId.asString
          , occupied
          , tuners
          )
          val primed = MpegTsSync.primeClientSource(hubSource, cachedHeadersRef, label = chLabel)
          replyTo ! Response.Attached(primed)
        }

        def rejectWaiters(waiters: Vector[SessionState.Waiter], reason: RejectReason): Unit =
          waiters.foreach(w => w.replyTo ! Response.Rejected(reason))

        def enterIdleGrace(channelId: ChannelId, live: SessionState.Live): SessionState.IdleGrace = {
          val headers = live.cachedHeadersRef.get()
          if (!headers.isEmpty) {
            setCachedHeaders(channelId.asString, headers)
          }
          timers.startSingleTimer(GraceTimerKey(channelId), Command.GraceExpired(channelId.asString), idleGrace)
          context.log.info(
            "[session] idle-grace channel={} channelId={} duration={}s token={} occupied={}/{}"
          , Lineup.channelLabel(channelId.asString)
          , channelId.asString
          , idleGrace.toSeconds
          , LogConfig.truncate(live.meta.token)
          , occupied
          , tuners
          )
          val drainSwitch = {
            import pekko.stream.scaladsl.{Keep, Sink}
            import pekko.stream.KillSwitches
            given org.apache.pekko.actor.ActorSystem = context.system.classicSystem
            val drainGraph = live.hubSource.viaMat(KillSwitches.single)(Keep.right).to(Sink.ignore)
            Try(drainGraph.run()) match {
              case Success(switch) => Some(switch)
              case Failure(ex) =>
                context.log.debug("[session] idle-grace drain switch error channelId={}", channelId.asString, ex)
                None
            }
          }
          SessionState.IdleGrace(live.meta, live.hubSource, live.teardown, live.cachedHeadersRef, drainSwitch, System.nanoTime())
        }

        def teardownAndRemove(channelId: ChannelId, teardown: () => Unit): Unit = {
          sessions.get(channelId).foreach {
            case idle: SessionState.IdleGrace => idle.drainSwitch.foreach(_.shutdown())
            case _ => ()
          }
          sessions -= channelId
          Try(teardown()).failed.foreach { ex =>
            context.log.warn("[session] teardown failed channelId={}", channelId.asString, ex)
          }
          context.log.info(
            "[session] removed channel={} channelId={} occupied={}/{}"
          , Lineup.channelLabel(channelId.asString)
          , channelId.asString
          , occupied
          , tuners
          )
        }

        Behaviors.receiveMessage {
          case Request.GetTunerStatuses(replyTo) =>
            val activeInfos = sessions.toSeq.sortBy(_._1.asString).map {
              case (chId, state) =>
                val vct = Lineup.vctInfo(chId.asString)
                val snap = state match {
                  case live: SessionState.Live => live.meta.metricsRef.get()
                  case idle: SessionState.IdleGrace => idle.meta.metricsRef.get()
                  case _: SessionState.Opening => app.stream.StreamMetrics.WindowSnapshot.initial
                }
                HDHomeRunStatus.ActiveTunerInfo(
                  channelId = chId.asString
                , vctNumber = vct.vctNumber
                , vctName = vct.vctName
                , snapshot = snap
                )
            }
            replyTo ! HDHomeRunStatus.buildTunerStatuses(tuners, activeInfos)
            Behaviors.same

          case Request.GetActiveChannelStates(replyTo) =>
            val states = sessions.map {
              case (chId, _: SessionState.Opening) => chId.asString -> "opening"
              case (chId, _: SessionState.Live) => chId.asString -> "live"
              case (chId, _: SessionState.IdleGrace) => chId.asString -> "idle-grace"
            }
            replyTo ! states
            Behaviors.same

          case Request.GetSessionMeta(rawChannelId, replyTo) =>
            val channelId = ChannelId(rawChannelId)
            val metaOpt = sessions.get(channelId).flatMap {
              case live: SessionState.Live =>
                Some(live.meta)
              case idle: SessionState.IdleGrace =>
                timers.startSingleTimer(GraceTimerKey(channelId), Command.GraceExpired(channelId.asString), idleGrace)
                sessions += channelId -> idle.copy(enteredAtNanos = System.nanoTime())
                Some(idle.meta)
              case _ =>
                None
            }
            replyTo ! metaOpt
            Behaviors.same

          case Request.SetTotalTuners(total) =>
            tuners = math.max(1, total)
            Behaviors.same

          case Request.Acquire(rawChannelId, rawClientId, replyTo) =>
            val channelId = ChannelId(rawChannelId)
            val clientId = ClientId(rawClientId)
            sessions.get(channelId) match {
              case None =>
                if (occupied >= tuners) {
                  val oldestIdle = sessions.collect {
                    case (chId, idle: SessionState.IdleGrace) => (chId, idle)
                  }.toList.sortBy(_._2.enteredAtNanos).headOption

                  oldestIdle match {
                    case Some((idleChannelId, idle)) =>
                      context.log.info(
                        "[session] evicting oldest idle-grace channel={} channelId={} to accommodate new channel={} channelId={} clientId={}"
                      , Lineup.channelLabel(idleChannelId.asString)
                      , idleChannelId.asString
                      , Lineup.channelLabel(channelId.asString)
                      , channelId.asString
                      , clientId.asString
                      )
                      timers.cancel(GraceTimerKey(idleChannelId))
                      teardownAndRemove(idleChannelId, idle.teardown)
                      openChannel(channelId, clientId, replyTo)
                    case None =>
                      val activeChannels = sessions.keys.map(id => s"${Lineup.channelLabel(id.asString)} [${id.asString}]").mkString(", ")
                      context.log.warn(
                        "[session] no-tuners rejecting channel={} channelId={} clientId={} occupied={}/{} active=[{}]"
                      , Lineup.channelLabel(channelId.asString)
                      , channelId.asString
                      , clientId.asString
                      , occupied
                      , tuners
                      , activeChannels
                      )
                      replyTo ! Response.Rejected(RejectReason.NoTuners)
                  }
                } else {
                  openChannel(channelId, clientId, replyTo)
                }
                Behaviors.same

              case Some(opening @ SessionState.Opening(waiters, _)) =>
                sessions += channelId -> opening.copy(waiters = waiters :+ SessionState.Waiter(clientId, replyTo))
                context.log.info(
                  "[session] enqueue channel={} channelId={} clientId={} waiters={}"
                , Lineup.channelLabel(channelId.asString)
                , channelId.asString
                , clientId.asString
                , waiters.size + 1
                )
                Behaviors.same

              case Some(live: SessionState.Live) =>
                if (live.clientIds.contains(clientId)) {
                  attachClient(channelId, clientId, live.hubSource, live.cachedHeadersRef, replyTo)
                } else {
                  sessions += channelId -> live.copy(clientIds = live.clientIds + clientId)
                  attachClient(channelId, clientId, live.hubSource, live.cachedHeadersRef, replyTo)
                }
                Behaviors.same

              case Some(idle: SessionState.IdleGrace) =>
                timers.cancel(GraceTimerKey(channelId))
                if (idle.meta.isHealthy()) {
                  idle.drainSwitch.foreach(_.shutdown())
                  sessions += channelId -> SessionState.Live(idle.meta, idle.hubSource, idle.teardown, Set(clientId), idle.cachedHeadersRef)
                  context.log.info(
                    "[session] grace-cancel channel={} channelId={} clientId={}"
                  , Lineup.channelLabel(channelId.asString)
                  , channelId.asString
                  , clientId.asString
                  )
                  attachClient(channelId, clientId, idle.hubSource, idle.cachedHeadersRef, replyTo)
                } else {
                  context.log.info(
                    "[session] evicting unhealthy idle-grace session channel={} channelId={} leaseId={} clientId={}"
                  , Lineup.channelLabel(channelId.asString)
                  , channelId.asString
                  , idle.meta.leaseId
                  , clientId.asString
                  )
                  teardownAndRemove(channelId, idle.teardown)
                  openChannel(channelId, clientId, replyTo)
                }
                Behaviors.same
            }

          case Request.Release(rawChannelId, rawClientId) =>
            val channelId = ChannelId(rawChannelId)
            val clientId = ClientId(rawClientId)
            sessions.get(channelId) match {
              case Some(live: SessionState.Live) if live.clientIds.contains(clientId) =>
                val remaining = live.clientIds - clientId
                if (remaining.nonEmpty) {
                  sessions += channelId -> live.copy(clientIds = remaining)
                  context.log.info(
                    "[session] release channel={} channelId={} clientId={} clients={}"
                  , Lineup.channelLabel(channelId.asString)
                  , channelId.asString
                  , clientId.asString
                  , remaining.size
                  )
                } else if (live.meta.isHealthy()) {
                  sessions += channelId -> enterIdleGrace(channelId, live.copy(clientIds = Set.empty))
                } else {
                  context.log.info(
                    "[session] skipping idle-grace for unhealthy session channel={} channelId={} leaseId={}"
                  , Lineup.channelLabel(channelId.asString)
                  , channelId.asString
                  , live.meta.leaseId
                  )
                  teardownAndRemove(channelId, live.teardown)
                }
              case Some(opening @ SessionState.Opening(waiters, cancel)) =>
                val remaining = waiters.filterNot(_.clientId == clientId)
                if (remaining.nonEmpty) {
                  sessions += channelId -> opening.copy(waiters = remaining)
                  context.log.info(
                    "[session] opening release channel={} channelId={} clientId={} remainingWaiters={}"
                  , Lineup.channelLabel(channelId.asString)
                  , channelId.asString
                  , clientId.asString
                  , remaining.size
                  )
                } else {
                  sessions -= channelId
                  Try(cancel()).failed.foreach { ex =>
                    context.log.warn("[session] opening cancel failed channelId={}", channelId.asString, ex)
                  }
                  context.log.info(
                    "[session] opening cancelled channel={} channelId={} all waiters released"
                  , Lineup.channelLabel(channelId.asString)
                  , channelId.asString
                  )
                }
              case Some(_: SessionState.IdleGrace) =>
                context.log.debug("[session] release ignored idle-grace channelId={} clientId={}", channelId.asString, clientId.asString)
              case None =>
                context.log.debug("[session] release ignored absent channelId={} clientId={}", channelId.asString, clientId.asString)
              case Some(_: SessionState.Live) =>
                context.log.debug("[session] release unknown client channelId={} clientId={}", channelId.asString, clientId.asString)
            }
            Behaviors.same

          case Command.CheckIn(rawChannelId, meta, hubSource, teardown, cachedHeadersRef) =>
            val channelId = ChannelId(rawChannelId)
            val headers = cachedHeadersRef.get()
            if (!headers.isEmpty) {
              setCachedHeaders(channelId.asString, headers)
            }
            sessions.get(channelId) match {
              case Some(SessionState.Opening(waiters, _)) =>
                val clientIds = waiters.map(_.clientId).toSet
                sessions += channelId -> SessionState.Live(meta, hubSource, teardown, clientIds, cachedHeadersRef)
                context.log.info(
                  "[session] check-in channel={} channelId={} token={} clients={} occupied={}/{}"
                , Lineup.channelLabel(channelId.asString)
                , channelId.asString
                , LogConfig.truncate(meta.token)
                , clientIds.size
                , occupied
                , tuners
                )
                waiters.foreach(w => attachClient(channelId, w.clientId, hubSource, cachedHeadersRef, w.replyTo))
              case other =>
                context.log.warn("[session] check-in unexpected state channelId={} state={}", channelId.asString, other)
                val _ = Try(teardown())
            }
            Behaviors.same

          case Command.AcquireFailed(rawChannelId, cause) =>
            val channelId = ChannelId(rawChannelId)
            sessions.get(channelId) match {
              case Some(SessionState.Opening(waiters, _)) =>
                sessions -= channelId
                context.log.warn(
                  "[session] acquire-failed channel={} channelId={} waiters={}"
                , Lineup.channelLabel(channelId.asString)
                , channelId.asString
                , waiters.size
                , cause
                )
                rejectWaiters(waiters, RejectReason.Failed(cause))
              case _ =>
                context.log.warn("[session] acquire-failed ignored channelId={}", channelId.asString, cause)
            }
            Behaviors.same

          case Command.GraceExpired(rawChannelId) =>
            val channelId = ChannelId(rawChannelId)
            sessions.get(channelId) match {
              case Some(idle: SessionState.IdleGrace) =>
                val headers = idle.cachedHeadersRef.get()
                if (!headers.isEmpty) {
                  setCachedHeaders(channelId.asString, headers)
                }
                context.log.info("[session] grace-expired channel={} channelId={}", Lineup.channelLabel(channelId.asString), channelId.asString)
                teardownAndRemove(channelId, idle.teardown)
              case _ =>
                context.log.debug("[session] grace-expired ignored channelId={}", channelId.asString)
            }
            Behaviors.same

          case Command.UpstreamEnded(rawChannelId, rawLeaseId) =>
            val channelId = ChannelId(rawChannelId)
            val leaseId = LeaseId(rawLeaseId)
            sessions.get(channelId) match {
              case Some(live: SessionState.Live) if LeaseId(live.meta.leaseId) == leaseId =>
                context.log.warn(
                  "[session] upstream-ended channel={} channelId={} clients={}"
                , Lineup.channelLabel(channelId.asString)
                , channelId.asString
                , live.clientIds.size
                )
                teardownAndRemove(channelId, live.teardown)
              case Some(idle: SessionState.IdleGrace) if LeaseId(idle.meta.leaseId) == leaseId =>
                timers.cancel(GraceTimerKey(channelId))
                context.log.warn(
                  "[session] upstream-ended channel={} channelId={} during idle-grace"
                , Lineup.channelLabel(channelId.asString)
                , channelId.asString
                )
                teardownAndRemove(channelId, idle.teardown)
              case _ =>
                context.log.debug("[session] upstream-ended ignored channelId={} leaseId={}", channelId.asString, leaseId.asString)
            }
            Behaviors.same

          case Command.SessionUnhealthy(rawChannelId, rawLeaseId, reason) =>
            val channelId = ChannelId(rawChannelId)
            val leaseId = LeaseId(rawLeaseId)
            sessions.get(channelId) match {
              case Some(idle: SessionState.IdleGrace) if LeaseId(idle.meta.leaseId) == leaseId =>
                timers.cancel(GraceTimerKey(channelId))
                context.log.info(
                  "[session] ending idle-grace for unhealthy session channel={} channelId={} leaseId={} reason={}"
                , Lineup.channelLabel(channelId.asString)
                , channelId.asString
                , leaseId.asString
                , reason
                )
                teardownAndRemove(channelId, idle.teardown)
              case _ =>
                context.log.debug("[session] session-unhealthy ignored channelId={} leaseId={} reason={}", channelId.asString, leaseId.asString, reason)
            }
            Behaviors.same

          case Request.ShutdownAll(replyTo) =>
            timers.cancelAll()
            val count = sessions.size
            sessions.foreach {
              case (channelId, SessionState.Opening(waiters, cancel)) =>
                Try(cancel()).failed.foreach { ex =>
                  context.log.warn("[session] shutdown cancel failed channelId={}", channelId.asString, ex)
                }
                rejectWaiters(waiters, RejectReason.NoTuners)
              case (channelId, live: SessionState.Live) =>
                Try(live.teardown()).failed.foreach { ex =>
                  context.log.warn("[session] shutdown teardown failed channelId={}", channelId.asString, ex)
                }
              case (channelId, idle: SessionState.IdleGrace) =>
                idle.drainSwitch.foreach(_.shutdown())
                Try(idle.teardown()).failed.foreach { ex =>
                  context.log.warn("[session] shutdown teardown failed channelId={}", channelId.asString, ex)
                }
            }
            sessions = Map.empty
            context.log.info("[session] shutdown-all closedSessions={}", count)
            replyTo ! pekko.Done
            Behaviors.same
        }
        }
      }
    }

    object SessionRunner {
      def start(
        channelId: String
      , authContext: Auth.AuthContext
      , onCheckIn: (SessionManager.TabloSessionMeta, Source[ByteString, NotUsed], () => Unit, AtomicReference[MpegTsSync.CachedHeaders]) => Unit
      , onFailed: Throwable => Unit
      , onUpstreamEnded: String => Unit
      , onSessionUnhealthy: (String, String) => Unit = (_, _) => ()
      ): WithSystem[Unit] = {
        val _ = startCancellable(channelId, authContext, onCheckIn, onFailed, onUpstreamEnded, onSessionUnhealthy)
      }

      def startCancellable(
        channelId: String
      , authContext: Auth.AuthContext
      , onCheckIn: (SessionManager.TabloSessionMeta, Source[ByteString, NotUsed], () => Unit, AtomicReference[MpegTsSync.CachedHeaders]) => Unit
      , onFailed: Throwable => Unit
      , onUpstreamEnded: String => Unit
      , onSessionUnhealthy: (String, String) => Unit = (_, _) => ()
      ): WithSystem[() => Unit] = {
        val system = summon[ActorSystem[?]]
        given ec: scala.concurrent.ExecutionContext = system.executionContext
        val HttpCtx = Http()
        val scheduler = system.classicSystem.scheduler
        val leaseId = java.util.UUID.randomUUID.toString.take(8)
        val channelDisplay = Lineup.channelLabel(channelId)
        val streamLabel = Lineup.streamLabel(channelId, leaseId)
        val watchUri = authContext.deviceUrl.withPath(Uri.Path(s"/guide/channels/$channelId/watch")).withQuery(Uri.Query("lh" -> "1"))
        val deviceId = java.util.UUID.randomUUID.toString
        import Channel.Request.Watch4thGenRequest.JsonProtocol.watch4thGenRequestFormat
        import Channel.Response.JsonProtocol.watch4thGenResponseFormat
        import pekko.http.scaladsl.unmarshalling.Unmarshal
        val watchBody = Request.Watch4thGenRequest.forDevice(deviceId).toJson.compactPrint
        val cancelled = new AtomicBoolean(false)
        @volatile var retryTimer: Option[pekko.actor.Cancellable] = None
        @volatile var activeTeardown: Option[() => Unit] = None
        val hlsCollector = new app.stream.StreamMetrics.HlsCollector()
        val latestMetricsRef = new AtomicReference[app.stream.StreamMetrics.WindowSnapshot](
          app.stream.StreamMetrics.WindowSnapshot.initial
        )

        def watchChannel(attempt: Int = 0): Future[Response.Watch4thGenResponse] =
          if (cancelled.get()) {
            Future.failed(Error.WatchCancelled)
          } else {
            val headers =
              Hmac.signedHeaders("POST", s"/guide/channels/$channelId/watch", Some(watchBody), authContext)
            val request = HttpRequest(
              method = HttpMethods.POST
            , uri = watchUri
            , headers = headers.toList
            , entity = HttpEntity(ContentTypes.`application/json`, watchBody)
            )
            HttpCtx.singleRequest(request).flatMap { response =>
              log.debug("[channel] watch status={} leaseId={} attempt={}", response.status.intValue(), leaseId, attempt)
              if (cancelled.get()) {
                val _ = response.entity.discardBytes()
                Future.failed(Error.WatchCancelled)
              } else {
                response.status match {
                  case StatusCodes.ServiceUnavailable if attempt < WatchSession.watchRetryMax =>
                    Unmarshal(response.entity).to[String].recover { case _ => "" }.flatMap { body =>
                      log.debug(
                        "[channel] watch 503 retry leaseId={} attempt={} body={}"
                      , leaseId
                      , attempt + 1
                      , LogConfig.truncate(body.trim)
                      )
                      if (cancelled.get()) Future.failed(Error.WatchCancelled)
                      else after(WatchSession.watchRetryDelaySec.seconds, scheduler)(watchChannel(attempt + 1))
                    }
                  case StatusCodes.ServiceUnavailable =>
                    Unmarshal(response.entity).to[String].recover { case _ => "" }.flatMap { body =>
                      val err = WatchSession.classifyWatch503(body)
                      log.warn(
                        "[channel] watch 503 exhausted leaseId={} channelId={} body={}"
                      , leaseId
                      , channelId
                      , LogConfig.truncate(body.trim)
                      )
                      Future.failed(err)
                    }
                  case status if !status.isSuccess() =>
                    Unmarshal(response.entity).to[String].recover { case _ => "" }.flatMap { body =>
                      Future.failed(WatchSession.classifyWatchFailure(status, body))
                    }
                  case _ =>
                    Unmarshal(response.entity).to[String].flatMap { body =>
                      log.debug("[channel] watch body={} leaseId={}", LogConfig.truncate(body), leaseId)
                      Future.fromTry(SafeJson.parseAs[Response.Watch4thGenResponse](body))
                    }
                }
              }
            }
          }

        def endSession(token: String): Future[Unit] = {
          val path = WatchSession.sessionPath(token)
          val uri = authContext.deviceUrl.withPath(Uri.Path(path)).withQuery(Uri.Query("lh" -> "1"))
          val headers = Hmac.signedHeaders("DELETE", path, None, authContext)
          val request = HttpRequest(method = HttpMethods.DELETE, uri = uri, headers = headers.toList)
          log.debug("[channel] session DELETE {} leaseId={}", path, leaseId)
          HttpCtx.singleRequest(request).flatMap { response =>
            if (response.status.isSuccess() || response.status == StatusCodes.NoContent) {
              val _ = response.entity.discardBytes()
              Future.successful(())
            } else {
              Unmarshal(response.entity).to[String].flatMap { body =>
                Future.failed(Tablo4thGen.Error.SessionDeleteFailed(s"session DELETE failed: ${response.status.intValue()} ${LogConfig.truncate(body)}"))
              }
            }
          }
        }

        def endSessionIfNeeded(token: Option[String], except: Option[String]): Unit =
          token.filter(t => !except.contains(t)).foreach { t =>
            endSession(t).recover {
              case ex => log.warn("[channel] session end failed leaseId={} token={}", leaseId, t, ex)
            }(using ec)
          }

        def parseSessionResponse(body: String, fallbackToken: Option[String]): WatchSession.Session | FourthGenError =
          SafeJson.parseAs[Response.Watch4thGenResponse](body) match {
            case Failure(ex) => FourthGenError.SessionFailed(Option(ex.getMessage).getOrElse("invalid session JSON"))
            case Success(resp) => WatchSession.decodeSession(resp, fallbackToken)
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
                  case session: WatchSession.Session    => Future.successful(session)
                  case err: FourthGenError.NoVideoData => Future.failed(FourthGenError.SessionFailed(err.getMessage))
                  case err: FourthGenError             => Future.failed(err)
                }
              }
            } else {
              val _ = response.entity.discardBytes()
              Future.failed(
                FourthGenError.SessionFailed(
                  s"session ${method.value} failed: ${response.status.intValue()}"
                , Some(response.status)
                )
              )
            }
          }
        }

        def keepaliveSession(session: WatchSession.Session): Future[WatchSession.Session] =
          session.token match {
            case Some(token) =>
              log.debug("[channel] keepalive POST {} leaseId={}", WatchSession.keepalivePath(token), leaseId)
              requestSession(WatchSession.keepalivePath(token), HttpMethods.POST, Some(token))
            case None =>
              Future.failed(FourthGenError.SessionTokenMissing)
          }

        def retuneWatchSession(): Future[WatchSession.Session] =
          watchChannel().flatMap { data =>
            WatchSession.decodeSession(data) match {
              case newSession: WatchSession.Session =>
                Future.successful(newSession)
              case err: FourthGenError =>
                Future.failed(err)
            }
          }

        def streamFromWatchSession(
          session: WatchSession.Session
        , streamKillSwitch: AtomicReference[Option[UniqueKillSwitch]]
        , lastSeqRef: java.util.concurrent.atomic.AtomicInteger
        , isRecovery: Boolean
        , checkSessionAlive: () => Future[Boolean]
        , onSegmentAdvanced: () => Unit
        ): Source[ByteString, ?] = {
          log.info(
            "[4thgen-channel] stream session channel={} channelId={} leaseId={} expires={} keepalive={} playlist={}"
          , channelDisplay
          , channelId
          , leaseId
          , session.expires.map(_.toString).getOrElse("unknown")
          , session.keepalive.map(_.toString).getOrElse("unknown")
          , LogConfig.truncate(session.playlistUrl)
          )
          val liveEdgeCount = if (isRecovery) HlsPlaylistPoller.recoveryLiveEdgeSegmentCount else HlsPlaylistPoller.liveEdgeSegmentCount
          val startingSeq = lastSeqRef.get()
          StreamBackend()
            .stream(
              session.playlistUrl
            , streamLabel
            , initialSeq = startingSeq
            , onSeqAdvanced = seq => {
                lastSeqRef.set(seq)
                onSegmentAdvanced()
              }
            , liveEdgeCount = liveEdgeCount
            , checkSessionAlive = checkSessionAlive
            , metricsCollector = Some(hlsCollector)
            )
            .viaMat(KillSwitches.single)(Keep.right)
            .mapMaterializedValue { killSwitch =>
              streamKillSwitch.set(Some(killSwitch))
              killSwitch
            }
        }

        def onTuneSuccess(firstSession: WatchSession.Session, token: String): Unit = {
          val currentSession = new AtomicReference[WatchSession.Session](firstSession)
          val streamKillSwitch = new AtomicReference[Option[UniqueKillSwitch]](None)
          val lastSeqRef = new java.util.concurrent.atomic.AtomicInteger(0)
          val playlistRollover = new AtomicBoolean(false)
          val sessionClosedByTablo = new AtomicBoolean(false)
          val probeFailures = new java.util.concurrent.atomic.AtomicInteger(0)
          val maxProbeFailures = Option(AppContext.config).map(_.stream.hls.pollFailuresMax).getOrElse(4)
          @volatile var keepaliveTask: Option[pekko.actor.Cancellable] = None
          val leaseStopped = new AtomicBoolean(false)
          val tornDown = new AtomicBoolean(false)
          var keepaliveMissingLogged = false

          def cancelKeepalive(): Unit = {
            leaseStopped.set(true)
            keepaliveTask.foreach(_.cancel())
            keepaliveTask = None
          }

          def scheduleKeepaliveRetry(): Unit =
            if (!leaseStopped.get()) {
              keepaliveTask.foreach(_.cancel())
              keepaliveTask = Some(
                scheduler.scheduleOnce(WatchSession.keepaliveRetrySec.seconds) { runKeepalive() }(using ec)
              )
            }

          def scheduleKeepalive(): Unit =
            if (!leaseStopped.get()) {
              keepaliveTask.foreach(_.cancel())
              WatchSession.keepaliveDelaySec(currentSession.get()) match {
                case Some(delaySec) =>
                  keepaliveTask = Some(
                    scheduler.scheduleOnce(delaySec.seconds) { runKeepalive() }(using ec)
                  )
                case None =>
                  if (!keepaliveMissingLogged) {
                    keepaliveMissingLogged = true
                    log.debug("[channel] keepalive disabled leaseId={} missing token or interval", leaseId)
                  }
              }
            }

          def probeSessionAlive(): Future[Boolean] =
            if (leaseStopped.get()) Future.successful(false)
            else {
              val previous = currentSession.get()
              keepaliveSession(previous).transformWith { result =>
                if (leaseStopped.get()) Future.successful(false)
                else {
                  val (outcome, nextFailures) =
                    WatchSession.classifyProbeResult(previous, result, probeFailures.get(), maxProbeFailures)
                  probeFailures.set(nextFailures)
                  outcome match {
                    case WatchSession.ProbeOutcome.Alive(updated, changed) =>
                      currentSession.set(updated)
                      log.debug(
                        "[channel] keepalive ok channel={} leaseId={} expires={} keepalive={}"
                      , channelDisplay
                      , leaseId
                      , updated.expires.map(_.toString).getOrElse("unknown")
                      , updated.keepalive.map(_.toString).getOrElse("unknown")
                      )
                      scheduleKeepalive()
                      if (changed) {
                        log.info("[channel] playlist url changed channel={} leaseId={}, restarting hls", channelDisplay, leaseId)
                        playlistRollover.set(true)
                        lastSeqRef.set(0)
                        streamKillSwitch.get().foreach(_.shutdown())
                      }
                      Future.successful(true)

                    case WatchSession.ProbeOutcome.TransientFailure =>
                      log.warn(
                        "[channel] keepalive transient failure ({}/{}) channel={} leaseId={}"
                      , nextFailures
                      , maxProbeFailures
                      , channelDisplay
                      , leaseId
                      )
                      scheduleKeepaliveRetry()
                      Future.successful(true)

                    case WatchSession.ProbeOutcome.ClosedByTablo =>
                      log.warn("[channel] session closed by tablo channel={} leaseId={}, triggering cold retune", channelDisplay, leaseId)
                      sessionClosedByTablo.set(true)
                      streamKillSwitch.get().foreach(_.abort(HlsError.SessionEnded))
                      Future.successful(false)

                    case WatchSession.ProbeOutcome.ProbeExhausted =>
                      log.warn(
                        "[channel] keepalive failures exhausted ({}/{}) channel={} leaseId={}, triggering cold retune"
                      , nextFailures
                      , maxProbeFailures
                      , channelDisplay
                      , leaseId
                      )
                      streamKillSwitch.get().foreach(_.abort(HlsError.SessionEnded))
                      Future.successful(false)
                  }
                }
              }(using ec)
            }

          def runKeepalive(): Unit = {
            val _ = probeSessionAlive()
          }

          import app.stream.ResilientHlsSource
          val attemptCount = new java.util.concurrent.atomic.AtomicInteger(0)
          val inRecovery = new AtomicBoolean(false)
          val isFrozen = new AtomicBoolean(false)

          def streamFactory(): Source[ByteString, ?] = {
            val attempt = attemptCount.incrementAndGet()
            val session = currentSession.get()
            val isRollover = playlistRollover.compareAndSet(true, false)

            if ((attempt == 1 || isRollover) && !WatchSession.shouldRefreshSession(session)) {
              streamFromWatchSession(
                session
              , streamKillSwitch
              , lastSeqRef
              , isRecovery = isRollover
              , checkSessionAlive = () => probeSessionAlive()
              , onSegmentAdvanced = () => inRecovery.set(false)
              )
            } else {
              inRecovery.set(true)
              onSessionUnhealthy(leaseId, s"cold-retune-attempt-$attempt")
              val closedByTablo = sessionClosedByTablo.getAndSet(false)
              log.info(
                "[4thgen-channel] cold recovery retune attempt={} channel={} channelId={} leaseId={} closedByTablo={}"
              , attempt
              , channelDisplay
              , channelId
              , leaseId
              , closedByTablo
              )
              keepaliveTask.foreach(_.cancel())
              val oldToken = session.token
              Source.futureSource(
                WatchSession.coldRetune(
                  sessionClosedByTablo = closedByTablo
                , release = () => oldToken.map(endSession).getOrElse(Future.successful(()))
                , tune = () => retuneWatchSession()
                ).map { newSession =>
                  WatchSession.classifyColdRetuneCompletion(attempt, attemptCount.get(), leaseStopped.get()) match {
                    case WatchSession.LateRetuneAction.ReleaseLeaked =>
                      log.info(
                        "[4thgen-channel] releasing late cold-retune session after teardown attempt={} channel={} leaseId={}"
                      , attempt
                      , channelDisplay
                      , leaseId
                      )
                      endSessionIfNeeded(newSession.token, None)
                      Source.empty
                    case WatchSession.LateRetuneAction.ReleaseSuperseded =>
                      log.info(
                        "[4thgen-channel] discarding superseded cold-retune session attempt={} currentAttempt={} channel={} leaseId={}"
                      , attempt
                      , attemptCount.get()
                      , channelDisplay
                      , leaseId
                      )
                      if (newSession.token != currentSession.get().token) {
                        endSessionIfNeeded(newSession.token, None)
                      }
                      Source.empty
                    case WatchSession.LateRetuneAction.Accept =>
                      currentSession.set(newSession)
                      if (leaseStopped.get()) {
                        log.info(
                          "[4thgen-channel] releasing late cold-retune session after concurrent teardown attempt={} channel={} leaseId={}"
                        , attempt
                        , channelDisplay
                        , leaseId
                        )
                        endSessionIfNeeded(newSession.token, None)
                        Source.empty
                      } else {
                        probeFailures.set(0)
                        lastSeqRef.set(0)
                        scheduleKeepalive()
                        streamFromWatchSession(
                          newSession
                        , streamKillSwitch
                        , lastSeqRef
                        , isRecovery = true
                        , checkSessionAlive = () => probeSessionAlive()
                        , onSegmentAdvanced = () => inRecovery.set(false)
                        )
                      }
                  }
                }(using ec)
              )
            }
          }

          val initialHeaders = SessionManager.getCachedHeaders(channelId)
          val cachedHeadersRef = new AtomicReference[MpegTsSync.CachedHeaders](initialHeaders)
          val headersReadyPromise = scala.concurrent.Promise[Unit]()
          if (initialHeaders.pat.isDefined && initialHeaders.pmt.isDefined) {
            val _ = headersReadyPromise.trySuccess(())
          }

          val resStreamName =
            if (channelDisplay == channelId) s"4thgen-channel-$channelId"
            else s"4thgen-channel-$channelId ($channelDisplay)"

          val metricsInterval =
            Option(AppContext.config).map(_.stream.metricsIntervalSec.seconds).getOrElse(30.seconds)
          val retuneTrigger = new ResilientHlsSource.RetuneTrigger()

          val ((outerKillSwitch, upstreamDone), hubSource) =
            ResilientHlsSource(
              streamFactory = () => streamFactory()
            , streamName = resStreamName
            , resumePrefixSupplier = () => Some(cachedHeadersRef.get().syncPrefix)
            , videoPidSupplier = () => cachedHeadersRef.get().videoPid
            , videoStreamTypeSupplier = () => cachedHeadersRef.get().videoStreamType
            , audioPidsSupplier = () => cachedHeadersRef.get().audioPids
            , audioStreamTypesSupplier = () => cachedHeadersRef.get().audioStreamTypes
            , lockedSeqHeaderSupplier = () => cachedHeadersRef.get().lockedSeqHeader
            , metricsCollector = Some(hlsCollector)
            , retuneTrigger = Some(retuneTrigger)
            )
              .via(MpegTsSync.cacheFlow(
                cachedHeadersRef
              , updated => {
                  SessionManager.setCachedHeaders(channelId, updated)
                  if (updated.pat.isDefined && updated.pmt.isDefined) {
                    val _ = headersReadyPromise.trySuccess(())
                  }
                }
              , label = streamLabel
              , metricsInterval = metricsInterval
              , hlsCollector = Some(hlsCollector)
              , latestMetricsRef = Some(latestMetricsRef)
              , onAudioLossRetune = detail => {
                  playlistRollover.set(false)
                  val _ = retuneTrigger.trigger(HlsError.SustainedAudioLoss(detail))
                }
              , onFreezeStateChanged = frozen => {
                  isFrozen.set(frozen)
                  if (frozen) onSessionUnhealthy(leaseId, "freeze-active")
                  else inRecovery.set(false)
                }
              ))
              .viaMat(KillSwitches.single)(Keep.right)
              .watchTermination(Keep.both)
              .toMat(BroadcastHub.sink[ByteString](SessionManager.BroadcastHubBufferSize))(Keep.both)
              .run()

          scheduleKeepalive()
          log.info(
            "[channel] tuned channel={} leaseId={} channelId={} expires={} keepalive={}"
          , channelDisplay
          , leaseId
          , channelId
          , firstSession.expires.map(_.toString).getOrElse("unknown")
          , firstSession.keepalive.map(_.toString).getOrElse("unknown")
          )

          val meta = SessionManager.TabloSessionMeta(
            token = token
          , expires = firstSession.expires
          , keepalive = firstSession.keepalive
          , playlistUrl = firstSession.playlistUrl
          , leaseId = leaseId
          , metricsRef = latestMetricsRef
          , isHealthy = () =>
              !tornDown.get() && !inRecovery.get() && !isFrozen.get() && {
                val snap = latestMetricsRef.get()
                snap.windowSec <= 0.0 || snap.feedQualityPct > 0.0 || snap.segs > 0L
              }
          )

          val teardown: () => Unit = () => {
            if (tornDown.compareAndSet(false, true)) {
              cancelKeepalive()
              outerKillSwitch.shutdown()
              endSessionIfNeeded(currentSession.get().token, None)
              log.info("[channel] teardown channel={} leaseId={} channelId={}", channelDisplay, leaseId, channelId)
            }
          }
          activeTeardown = Some(teardown)
          if (cancelled.get()) {
            teardown()
          }

          val _ = scheduler.scheduleOnce(1500.millis) {
            val _ = headersReadyPromise.trySuccess(())
          }(using ec)

          // Serializes check-in vs upstream-ended so SessionManager always sees them in order.
          val lifecycleLock = new Object
          var checkedIn = false

          headersReadyPromise.future.onComplete { _ =>
            lifecycleLock.synchronized {
              if (!tornDown.get() && !checkedIn) {
                checkedIn = true
                onCheckIn(meta, hubSource, teardown, cachedHeadersRef)
              }
            }
          }(using ec)

          // Upstream ended on its own (recovery timeout or failure): release the Tablo session and
          // remove the SessionManager entry so the channel is immediately re-tunable.
          upstreamDone.onComplete { result =>
            lifecycleLock.synchronized {
              if (!tornDown.get()) {
                log.warn("[channel] upstream ended channel={} leaseId={} channelId={} result={}", channelDisplay, leaseId, channelId, result)
                teardown()
                if (checkedIn) {
                  onUpstreamEnded(leaseId)
                } else {
                  checkedIn = true
                  val cause = result match {
                    case Failure(ex) => ex
                    case Success(_) => Tablo4thGen.Error.NoVideoData("stream ended before check-in")
                  }
                  onFailed(cause)
                }
              }
            }
          }(using ec)
        }

        val configuredRecoverySec = Option(AppContext.config).map(_.stream.resilient.recoveryTimeoutSec).getOrElse(0)
        val initialTuneBudget = WatchSession.initialTuneTimeout(configuredRecoverySec)
        val retryDelay = 2.seconds

        val tuneFuture = WatchSession.retryInitialTune(
          tune = { attempt =>
            if (attempt == 1) {
              log.info("[4thgen-channel] guide/channels/{}/watch (POST) channel={} leaseId={} - {}", channelId, channelDisplay, leaseId, watchUri)
            } else {
              log.info("[4thgen-channel] guide/channels/{}/watch (POST) channel={} leaseId={} attempt={} - {}", channelId, channelDisplay, leaseId, attempt, watchUri)
            }
            retuneWatchSession().flatMap { session =>
              if (session.token.isDefined) Future.successful(session)
              else Future.failed(Tablo4thGen.Error.SessionTokenMissing)
            }(using ec)
          }
        , onCancelRelease = session => endSessionIfNeeded(session.token, None)
        , isCancelled = () => cancelled.get()
        , scheduleRetry = (delay, task) => scheduler.scheduleOnce(delay)(task())(using ec)
        , onRetryTimer = timer => retryTimer = Some(timer)
        , timeoutBudget = initialTuneBudget
        , retryDelay = retryDelay
        , onAttemptFailed = (attempt, ex, delay) =>
            log.info("[4thgen-channel] initial tune channel={} leaseId={} attempt={} failed ({}), retrying in {}s...", channelDisplay, leaseId, attempt, ex.getMessage, delay.toSeconds)
        )(using ec)

        tuneFuture.onComplete {
          case Success(firstSession) =>
            firstSession.token.foreach(token => onTuneSuccess(firstSession, token))
          case Failure(Error.WatchCancelled) =>
            ()
          case Failure(ex) =>
            if (!cancelled.get()) {
              log.warn("[4thgen-channel] initial tune failed after {}s opening timeout channel={} channelId={} leaseId={}", initialTuneBudget.toSeconds, channelDisplay, channelId, leaseId, ex)
              onFailed(ex)
            }
        }(using ec)

        () => {
          if (cancelled.compareAndSet(false, true)) {
            retryTimer.foreach(_.cancel())
            retryTimer = None
            activeTeardown.foreach(_.apply())
            log.info("[4thgen-channel] tune cancelled channel={} leaseId={} channelId={}", channelDisplay, leaseId, channelId)
          }
        }
      }
    }

    def isAllowedSegmentUri(
      rawUri: String
    , authContextOpt: Option[Auth.AuthContext] = None
    ): Boolean = {
      Try(Uri(rawUri)).toOption.exists { uri =>
        val scheme = uri.scheme.toLowerCase
        val validScheme = scheme == "http" || scheme == "https"
        val targetHost = uri.authority.host.address().toLowerCase
        val allowedHosts = Set(
          authContextOpt.orElse(Auth.authContext).map(_.deviceUrl.authority.host.address().toLowerCase)
        , Option(AppContext.config).map(_.tablo.ipHost.toLowerCase)
        ).flatten.filter(_.nonEmpty)
        validScheme && targetHost.nonEmpty && allowedHosts.contains(targetHost)
      }
    }

    def route(
      sessionManager: ActorRef[SessionManager.Request]
    ): WithSystem[pekko.http.scaladsl.server.Route] =
      route(sessionManager, None, None)

    def route(
      sessionManager: ActorRef[SessionManager.Request]
    , lineupActor: ActorRef[Lineup.LineupActor.Request]
    ): WithSystem[pekko.http.scaladsl.server.Route] =
      route(sessionManager, Some(lineupActor), None)

    def route(
      sessionManager: ActorRef[SessionManager.Request]
    , lineupActor: ActorRef[Lineup.LineupActor.Request]
    , authContext: Auth.AuthContext
    ): WithSystem[pekko.http.scaladsl.server.Route] =
      route(sessionManager, Some(lineupActor), Some(authContext))

    def route(
      sessionManager: ActorRef[SessionManager.Request]
    , lineupActorOpt: Option[ActorRef[Lineup.LineupActor.Request]]
    , authContextOpt: Option[Auth.AuthContext] = None
    ): WithSystem[pekko.http.scaladsl.server.Route] = {
      import pekko.http.scaladsl.unmarshalling.Unmarshal
      val system = summon[ActorSystem[?]]
      given ec: scala.concurrent.ExecutionContext = system.executionContext
      val askTimeout = Option(AppContext.config).map { c =>
        if (c.stream.resilient.recoveryTimeoutSec <= 0) 7.days
        else c.stream.resilient.recoveryTimeoutSec.seconds + 10.seconds
      }.getOrElse(70.seconds)
      given pekko.util.Timeout = Timeout(askTimeout)
      val HttpCtx = Http()
      @volatile var cachedTuners: Option[Int] = None

      def getOrFetchTotalTuners(): Future[Int] = cachedTuners match {
        case Some(tuners) => Future.successful(tuners)
        case None =>
          val tuners = if (AppContext.discover != null && AppContext.discover.TunerCount > 0) {
            AppContext.discover.TunerCount
          } else {
            2
          }
          cachedTuners = Some(tuners)
          Future.successful(tuners)
      }

      val streamingHeaders = Seq(
        pekko.http.scaladsl.model.headers.RawHeader("Cache-Control", "no-cache, no-store, must-revalidate")
      , pekko.http.scaladsl.model.headers.RawHeader("Pragma", "no-cache")
      , pekko.http.scaladsl.model.headers.RawHeader("Expires", "0")
      , pekko.http.scaladsl.model.headers.Connection("close")
      )

      val corsHeaders = Seq(
        pekko.http.scaladsl.model.headers.RawHeader("Access-Control-Allow-Origin", "*")
      , pekko.http.scaladsl.model.headers.RawHeader("Access-Control-Allow-Methods", "GET, OPTIONS")
      , pekko.http.scaladsl.model.headers.RawHeader("Access-Control-Allow-Headers", "*")
      )

      def rejectStreamResponse(
        error: HDHomeRunError
      , channelDisplay: String
      , channelId: String
      , clientId: String
      , reason: String
      , cause: Option[Throwable] = None
      , extraHeaders: Seq[HttpHeader] = Nil
      , bodyOverride: Option[String] = None
      ): HttpResponse =
        HDHomeRunError.rejectResponse(
          log = log
        , error = error
        , context = s"channel=$channelDisplay channelId=$channelId clientId=$clientId"
        , reason = reason
        , cause = cause
        , extraHeaders = extraHeaders
        , bodyOverride = bodyOverride
        )

      def fetchAndRewritePlaylist(channelId: String, playlistUrl: String): Future[HttpResponse] =
        HttpCtx.singleRequest(HttpRequest(uri = playlistUrl)).flatMap { resp =>
          if (resp.status.isSuccess()) {
            Unmarshal(resp.entity).to[String].map { body =>
              val lines = body.linesIterator.map { line =>
                val trimmed = line.trim
                if (trimmed.isEmpty || trimmed.startsWith("#")) trimmed
                else {
                  val resolved = app.stream.M3U8.resolveSegmentUri(trimmed, playlistUrl)
                  val encoded = java.net.URLEncoder.encode(resolved, "UTF-8")
                  s"${AppContext.discover.BaseURL.withPath(Uri.Path(s"/channel/$channelId/segment/$encoded"))}"
                }
              }.mkString("\n") + "\n"

              val mpegurl = MediaType.customBinary("application", "x-mpegURL", MediaType.NotCompressible)
              HttpResponse(
                status = StatusCodes.OK,
                entity = HttpEntity(pekko.http.scaladsl.model.ContentType(mpegurl), ByteString(lines))
              ).withHeaders(corsHeaders)
            }
          } else {
            val _ = resp.entity.discardBytes()
            Future.successful(
              rejectStreamResponse(
                HDHomeRunError.NoVideoData
              , channelDisplay = Lineup.channelLabel(channelId)
              , channelId = channelId
              , clientId = s"hls-$channelId"
              , reason = s"upstream playlist status ${resp.status.intValue()}"
              , extraHeaders = corsHeaders
              , bodyOverride = Some("Failed to fetch upstream playlist")
              )
            )
          }
        }

      def hlsEnabled: Boolean = Option(AppContext.config).forall(_.proxy.enableHlsEndpoint)

      def fetchHlsPlaylist(channelId: String): Future[HttpResponse] = {
        val hlsClientId = s"hls-$channelId"
        val channelDisplay = Lineup.channelLabel(channelId)
        sessionManager.ask[Option[SessionManager.TabloSessionMeta]](replyTo =>
          SessionManager.Request.GetSessionMeta(channelId, replyTo)
        ).flatMap {
          case Some(meta) =>
            fetchAndRewritePlaylist(channelId, meta.playlistUrl)
          case None =>
            getOrFetchTotalTuners().flatMap { tuners =>
              sessionManager ! SessionManager.Request.SetTotalTuners(tuners)
              sessionManager.ask[SessionManager.Response.Acquire](replyTo =>
                SessionManager.Request.Acquire(channelId, hlsClientId, replyTo)
              ).flatMap {
                case SessionManager.Response.Attached(_) =>
                  sessionManager ! SessionManager.Request.Release(channelId, hlsClientId)
                  sessionManager.ask[Option[SessionManager.TabloSessionMeta]](replyTo =>
                    SessionManager.Request.GetSessionMeta(channelId, replyTo)
                  ).flatMap {
                    case Some(meta) =>
                      fetchAndRewritePlaylist(channelId, meta.playlistUrl)
                    case None =>
                      Future.successful(
                        rejectStreamResponse(
                          HDHomeRunError.SystemBusy
                        , channelDisplay = channelDisplay
                        , channelId = channelId
                        , clientId = hlsClientId
                        , reason = "session meta missing after attach"
                        , extraHeaders = corsHeaders
                        , bodyOverride = Some("Unable to get session playlist")
                        )
                      )
                  }
                case SessionManager.Response.Rejected(SessionManager.RejectReason.NoTuners) =>
                  Future.successful(
                    rejectStreamResponse(
                      HDHomeRunError.AllTunersInUse
                    , channelDisplay = channelDisplay
                    , channelId = channelId
                    , clientId = hlsClientId
                    , reason = "no available tuners"
                    , extraHeaders = corsHeaders
                    )
                  )
                case SessionManager.Response.Rejected(SessionManager.RejectReason.Failed(ex)) =>
                  Future.successful(
                    rejectStreamResponse(
                      HDHomeRunError.fromThrowable(ex)
                    , channelDisplay = channelDisplay
                    , channelId = channelId
                    , clientId = hlsClientId
                    , reason = Option(ex.getMessage).getOrElse(ex.getClass.getSimpleName)
                    , cause = Some(ex)
                    , extraHeaders = corsHeaders
                    )
                  )
              }
            }
        }.recover {
          case ex =>
            rejectStreamResponse(
              HDHomeRunError.fromThrowable(ex)
            , channelDisplay = channelDisplay
            , channelId = channelId
            , clientId = hlsClientId
            , reason = Option(ex.getMessage).getOrElse(ex.getClass.getSimpleName)
            , cause = Some(ex)
            , extraHeaders = corsHeaders
            )
        }
      }

      def streamChannel(channelId: String): pekko.http.scaladsl.server.Route = {
        val clientId = java.util.UUID.randomUUID.toString
        val channelDisplay = Lineup.channelLabel(channelId)
        val `video/mp2t` = MediaType.customBinary("video", "mp2t", MediaType.NotCompressible)
        val contentType = pekko.http.scaladsl.model.ContentType(`video/mp2t`)

        val acquireFut = getOrFetchTotalTuners().flatMap { tuners =>
          sessionManager ! SessionManager.Request.SetTotalTuners(tuners)
          sessionManager.ask[SessionManager.Response.Acquire](replyTo =>
            SessionManager.Request.Acquire(channelId, clientId, replyTo)
          )
        }

        def trackClientStream(
          channelId: String
        , clientId: String
        , source: Source[ByteString, NotUsed]
        ): Source[ByteString, NotUsed] = {
          val startNanos = System.nanoTime()
          val bytesSent = new java.util.concurrent.atomic.AtomicLong(0L)

          source
            .map { chunk =>
              val _ = bytesSent.addAndGet(chunk.size)
              chunk
            }
            .watchTermination { (_, done) =>
              done.onComplete { result =>
                val durationMs = (System.nanoTime() - startNanos) / 1000000L
                val totalBytes = bytesSent.get()
                val outcome = result match {
                  case Success(_) => "downstream-closed"
                  case Failure(ex) => s"failed: ${ex.getMessage}"
                }
                log.info(
                  "[channel] client disconnected channel={} channelId={} clientId={} durationMs={} bytes={} outcome={}"
                , Lineup.channelLabel(channelId)
                , channelId
                , clientId
                , durationMs
                , totalBytes
                , outcome
                )
                sessionManager ! SessionManager.Request.Release(channelId, clientId)
              }(using ec)
              pekko.NotUsed
            }
        }

        def completeAcquireResult(
          result: Try[SessionManager.Response.Acquire]
        , attachedLogTag: String
        ): pekko.http.scaladsl.server.Route =
          result match {
            case Success(SessionManager.Response.Attached(source)) =>
              log.info("[channel] {} channel={} channelId={} clientId={}", attachedLogTag, channelDisplay, channelId, clientId)
              val tracked = trackClientStream(channelId, clientId, source)
              respondWithHeaders(streamingHeaders) {
                complete(HttpEntity.Chunked.fromData(contentType, tracked))
              }
            case Success(SessionManager.Response.Rejected(SessionManager.RejectReason.NoTuners)) =>
              complete(
                rejectStreamResponse(
                  HDHomeRunError.AllTunersInUse
                , channelDisplay = channelDisplay
                , channelId = channelId
                , clientId = clientId
                , reason = "no available tuners"
                )
              )
            case Success(SessionManager.Response.Rejected(SessionManager.RejectReason.Failed(ex))) =>
              complete(
                rejectStreamResponse(
                  HDHomeRunError.fromThrowable(ex)
                , channelDisplay = channelDisplay
                , channelId = channelId
                , clientId = clientId
                , reason = Option(ex.getMessage).getOrElse("acquire failed")
                , cause = Some(ex)
                )
              )
            case Failure(ex) =>
              complete(
                rejectStreamResponse(
                  HDHomeRunError.fromThrowable(ex)
                , channelDisplay = channelDisplay
                , channelId = channelId
                , clientId = clientId
                , reason = Option(ex.getMessage).getOrElse("acquire ask failed")
                , cause = Some(ex)
                )
              )
          }

        val preRollEnabled = Option(AppContext.config).forall(_.proxy.enablePreRollKeepAlive)
        if (!preRollEnabled) {
          onComplete(acquireFut)(completeAcquireResult(_, "attached"))
        } else {
          val fastWindow = 50.millis
          val fastTimer = after(fastWindow, system.classicSystem.scheduler)(Future.successful(None))(using ec)
          val acquireAttempt = acquireFut.map(resp => Some(resp))(using ec)
          val initialDecision = Future.firstCompletedOf(Seq(acquireAttempt, fastTimer))(using ec)

          onComplete(initialDecision) {
            case Success(Some(resp)) =>
              completeAcquireResult(Success(resp), "fast-attached")
            case Success(None) =>
              log.info("[channel] pre-roll keepalive active channel={} channelId={} clientId={}", channelDisplay, channelId, clientId)
              val realSourceFut: Future[Source[ByteString, NotUsed]] = acquireFut.flatMap {
                case SessionManager.Response.Attached(source) =>
                  log.info("[channel] attached channel={} channelId={} clientId={}", Lineup.channelLabel(channelId), channelId, clientId)
                  Future.successful(source)
                case SessionManager.Response.Rejected(SessionManager.RejectReason.NoTuners) =>
                  log.info("[channel] no available tuners channel={} channelId={} clientId={}", Lineup.channelLabel(channelId), channelId, clientId)
                  Future.failed(Tablo4thGen.Error.NoAvailableTuners)
                case SessionManager.Response.Rejected(SessionManager.RejectReason.Failed(ex)) =>
                  log.warn("[channel] acquire failed channel={} channelId={} clientId={}", Lineup.channelLabel(channelId), channelId, clientId, ex)
                  Future.failed(ex)
              }(using ec)

              val interval = Option(AppContext.config).map(_.proxy.preRollIntervalMs.millis).getOrElse(100.millis)
              val packets = Option(AppContext.config).map(_.proxy.preRollPackets).getOrElse(7)

              val compositeSource = MpegTsSync.withPreRollKeepAlive(realSourceFut, interval, packets)
              val tracked = trackClientStream(channelId, clientId, compositeSource)
              respondWithHeaders(streamingHeaders) {
                complete(HttpEntity.Chunked.fromData(contentType, tracked))
              }
            case Failure(ex) =>
              completeAcquireResult(Failure(ex), "fast-attached")
          }
        }
      }

      path("status.json") {
        options {
          respondWithHeaders(corsHeaders) {
            complete(StatusCodes.OK)
          }
        } ~
        get {
          val statusTimeout = Timeout(3.seconds)
          val statusFut = getOrFetchTotalTuners().flatMap { tuners =>
            sessionManager ! SessionManager.Request.SetTotalTuners(tuners)
            sessionManager.ask[Seq[HDHomeRunStatus.TunerStatus]](replyTo =>
              SessionManager.Request.GetTunerStatuses(replyTo)
            )(using statusTimeout, system.scheduler)
          }
          onComplete(statusFut) {
            case Success(statuses) =>
              import HDHomeRunStatus.tunerStatusFormat
              val json = statuses.toJson.compactPrint
              respondWithHeaders(corsHeaders) {
                complete(HttpEntity(ContentTypes.`application/json`, json))
              }
            case Failure(ex) =>
              log.warn("[status] failed to query tuner statuses", ex)
              respondWithHeaders(corsHeaders) {
                complete(HttpResponse(StatusCodes.InternalServerError, entity = "Unable to query tuner status"))
              }
          }
        }
      } ~
      path("channel" / Segment / "segment" / Remaining) { (channelId, encodedSegment) =>
        options {
          respondWithHeaders(corsHeaders) {
            complete(StatusCodes.OK)
          }
        } ~
        get {
          if (!hlsEnabled) {
            complete(HttpResponse(StatusCodes.NotFound, headers = corsHeaders.toList, entity = "HLS endpoint disabled"))
          } else {
            val decoded = Try(java.net.URLDecoder.decode(encodedSegment, "UTF-8")).getOrElse(encodedSegment)
            if (!isAllowedSegmentUri(decoded, authContextOpt)) {
              log.warn("[channel] forbidden segment URI channelId={} uri={}", channelId, LogConfig.truncate(decoded))
              complete(HttpResponse(StatusCodes.Forbidden, headers = corsHeaders.toList, entity = "Forbidden segment URI"))
            } else {
              val request = HttpRequest(uri = decoded)
              val fut = HttpCtx.singleRequest(request).map { resp =>
                if (resp.status.isSuccess()) {
                  val `video/mp2t` = MediaType.customBinary("video", "mp2t", MediaType.NotCompressible)
                  val contentType = pekko.http.scaladsl.model.ContentType(`video/mp2t`)
                  val segHeaders = corsHeaders ++ Seq(
                    pekko.http.scaladsl.model.headers.RawHeader("Cache-Control", "public, max-age=86400")
                  )
                  HttpResponse(StatusCodes.OK, headers = segHeaders.toList, entity = HttpEntity(contentType, resp.entity.dataBytes))
                } else {
                  val _ = resp.entity.discardBytes()
                  HttpResponse(resp.status, headers = corsHeaders.toList, entity = "Failed to fetch segment")
                }
              }.recover { case ex =>
                log.warn("[channel] segment fetch failed channelId={} error={}", channelId, ex.getMessage)
                HttpResponse(StatusCodes.BadGateway, headers = corsHeaders.toList, entity = "Segment fetch failed")
              }
              complete(fut)
            }
          }
        }
      } ~
      path("hls" / Segment / Segment) { (channelId, _) =>
        options {
          respondWithHeaders(corsHeaders) {
            complete(StatusCodes.OK)
          }
        } ~
        get {
          if (!hlsEnabled) {
            complete(HttpResponse(StatusCodes.NotFound, headers = corsHeaders.toList, entity = "HLS endpoint disabled"))
          } else {
            complete(fetchHlsPlaylist(channelId))
          }
        }
      } ~
      path("channel" / Segment) { rawId =>
        options {
          respondWithHeaders(corsHeaders) {
            complete(StatusCodes.OK)
          }
        } ~
        get {
          if (rawId.endsWith(".m3u8")) {
            if (!hlsEnabled) {
              complete(HttpResponse(StatusCodes.NotFound, headers = corsHeaders.toList, entity = "HLS endpoint disabled"))
            } else {
              val channelId = rawId.stripSuffix(".m3u8")
              complete(fetchHlsPlaylist(channelId))
            }
          } else {
            streamChannel(rawId)
          }
        }
      } ~
      path("auto" / ("v" ~ Segment)) { guideNumber =>
        options {
          respondWithHeaders(corsHeaders) {
            complete(StatusCodes.OK)
          }
        } ~
        get {
          lineupActorOpt match {
            case Some(lineupActor) =>
              given pekko.util.Timeout = Timeout(3.seconds)
              val fetchF: Future[Lineup.LineupActor.Response.Fetch] =
                lineupActor.ask(replyTo => Lineup.LineupActor.Request.Fetch(replyTo))
              onComplete(fetchF) {
                case Success(Lineup.LineupActor.Response.Fetch(channels, _)) =>
                  val matchedChannelId = channels.iterator.flatMap { ch =>
                    val obj = ch.asJsObject.fields
                    val gn = obj.get("GuideNumber").collect { case JsString(s) => s }
                    if (gn.contains(guideNumber)) {
                      obj.get("URL").collect { case JsString(u) =>
                        val clean = u.stripSuffix(".m3u8")
                        val idx = clean.lastIndexOf('/')
                        if (idx >= 0 && idx < clean.length - 1) clean.substring(idx + 1)
                        else clean
                      }
                    } else None
                  }.nextOption()

                  matchedChannelId match {
                    case Some(channelId) =>
                      streamChannel(channelId)
                    case None =>
                      complete(
                        rejectStreamResponse(
                          HDHomeRunError.UnknownChannel
                        , channelDisplay = s"v$guideNumber"
                        , channelId = s"v$guideNumber"
                        , clientId = "auto"
                        , reason = "channel not found in lineup"
                        , bodyOverride = Some(s"Channel v$guideNumber not found")
                        )
                      )
                  }
                case Failure(ex) =>
                  complete(
                    rejectStreamResponse(
                      HDHomeRunError.SystemBusy
                    , channelDisplay = s"v$guideNumber"
                    , channelId = s"v$guideNumber"
                    , clientId = "auto"
                    , reason = "lineup resolution failed"
                    , cause = Some(ex)
                    , bodyOverride = Some("Unable to resolve channel lineup")
                    )
                  )
              }
            case None =>
              complete(
                rejectStreamResponse(
                  HDHomeRunError.SystemBusy
                , channelDisplay = s"v$guideNumber"
                , channelId = s"v$guideNumber"
                , clientId = "auto"
                , reason = "lineup actor unavailable"
                , bodyOverride = Some("Channel lineup not available")
                )
              )
          }
        }
      }
    }
  }

  object TunerState {
    val log = LoggerFactory.getLogger(this.getClass)

    case class TunerSlot(
      in_use: Boolean
    , channel: Option[String]
    , recording: Option[String]
    , channel_identifier: Option[String]
    )

    object JsonProtocol extends DefaultJsonProtocol {
      given tunerSlotFormat: JsonFormat[TunerSlot] =
        jsonFormat(TunerSlot.apply, "in_use", "channel", "recording", "channel_identifier")
    }

    enum TuneOrigin derives CanEqual {
      case Self(sessionState: String)
      case External

      def tag: String = this match {
        case Self(sessionState) => s"self:$sessionState"
        case External           => "external"
      }
      def isSelf: Boolean = this match {
        case _: Self  => true
        case External => false
      }
    }

    final case class TunedChannel(
      slotIndex: Int
    , channelId: String
    , channelPath: Option[String]
    , label: String
    , origin: TuneOrigin
    , recording: Option[String]
    ) {
      def formatLogEntry: String = {
        val recSuffix = recording.filter(_.nonEmpty).map(r => s" rec=$r").getOrElse("")
        s"slot=$slotIndex $label ($channelId) [${origin.tag}]$recSuffix"
      }
    }

    final case class Snapshot(
      totalSlots: Int
    , inUseSlots: Int
    , tunedChannels: Seq[TunedChannel]
    ) {
      def selfCount: Int = tunedChannels.count(_.origin.isSelf)
      def externalCount: Int = tunedChannels.count(ch => !ch.origin.isSelf)
    }

    object Snapshot {
      val empty: Snapshot = Snapshot(0, 0, Seq.empty)
    }

    private val ValidChannelPathPattern = "^/guide/channels/[0-9]+$".r

    def isValidChannelPath(path: String): Boolean =
      path != null && ValidChannelPathPattern.matches(path.trim)

    def fetchTunerSlots(authContext: Auth.AuthContext): WithSystem[Future[Seq[TunerSlot]]] = {
      import JsonProtocol.given
      import pekko.http.scaladsl.unmarshalling.Unmarshal
      val system = summon[ActorSystem[?]]
      given scala.concurrent.ExecutionContext = system.executionContext
      val activeAuth = Auth.authContext.getOrElse(authContext)
      val tunersUri = activeAuth.deviceUrl.withPath(Uri.Path("/server/tuners")).withQuery(Uri.Query("lh" -> "1"))
      val headers = Hmac.signedHeaders("GET", "/server/tuners", None, activeAuth)
      val request = HttpRequest(
        method = HttpMethods.GET
      , uri = tunersUri
      , headers = headers.toList
      )
      log.debug("[tuner-state] http GET /server/tuners uri={}", tunersUri)
      Http().singleRequest(request).flatMap { response =>
        if (response.status.isSuccess()) {
          Unmarshal(response.entity).to[String].flatMap { body =>
            Future.fromTry(SafeJson.parseAs[Seq[TunerSlot]](body))
          }
        } else {
          val statusCode = response.status.intValue()
          val _ = response.entity.discardBytes()
          Future.failed(FourthGenError.SystemBusy(s"Tablo /server/tuners returned HTTP $statusCode"))
        }
      }
    }

    def fetchDeviceChannel(
      channelPath: String
    , authContext: Auth.AuthContext
    ): WithSystem[Future[Option[Lineup.DeviceChannelObject]]] = {
      import Lineup.JsonProtocol.given
      import pekko.http.scaladsl.unmarshalling.Unmarshal
      val system = summon[ActorSystem[?]]
      given scala.concurrent.ExecutionContext = system.executionContext
      val trimmedPath = if (channelPath == null) "" else channelPath.trim
      if (!isValidChannelPath(trimmedPath)) {
        log.debug("[tuner-state] skipping invalid channel path={}", trimmedPath)
        Future.successful(None)
      } else {
        val activeAuth = Auth.authContext.getOrElse(authContext)
        val channelUri = activeAuth.deviceUrl.withPath(Uri.Path(trimmedPath)).withQuery(Uri.Query("lh" -> "1"))
        val headers = Hmac.signedHeaders("GET", trimmedPath, None, activeAuth)
        val request = HttpRequest(
          method = HttpMethods.GET
        , uri = channelUri
        , headers = headers.toList
        )
        log.debug("[tuner-state] http GET {} uri={}", trimmedPath, channelUri)
        Http().singleRequest(request).flatMap { response =>
          if (response.status.isSuccess()) {
            Unmarshal(response.entity).to[String].flatMap { body =>
              SafeJson.parseAs[Lineup.DeviceChannelObject](body) match {
                case Success(obj) =>
                  if (obj.channel.source.forall(_ == "ota")) {
                    val _ = Lineup.registerDeviceChannel(obj)
                  }
                  Future.successful(Some(obj))
                case Failure(ex) =>
                  Future.failed(ex)
              }
            }
          } else {
            val statusCode = response.status.intValue()
            val _ = response.entity.discardBytes()
            log.debug("[tuner-state] device channel fetch status={} path={}", statusCode, trimmedPath)
            Future.successful(None)
          }
        }.recover { case ex =>
          log.debug("[tuner-state] device channel fetch failed path={} error={}", trimmedPath, ex.getMessage)
          None
        }
      }
    }

    def resolveSnapshot(
      slots: Seq[TunerSlot]
    , activeChannelStates: Map[String, String]
    , fetchChannel: String => Future[Option[Lineup.DeviceChannelObject]]
    )(using ec: scala.concurrent.ExecutionContext): Future[Snapshot] = {
      val activeSlots = slots.zipWithIndex.filter { case (slot, _) =>
        slot.in_use && (slot.channel_identifier.exists(_.nonEmpty) || slot.channel.exists(_.nonEmpty))
      }

      Future.traverse(activeSlots) { case (slot, idx) =>
        val rawIdOpt = slot.channel_identifier.map(_.trim).filter(_.nonEmpty)
        val pathOpt = slot.channel.map(_.trim).filter(_.nonEmpty)
        val needsFetch = rawIdOpt.forall(id => !Lineup.hasChannelLabel(id))

        val fetchedOptFut: Future[Option[Lineup.DeviceChannelObject]] =
          if (needsFetch) {
            pathOpt.filter(isValidChannelPath) match {
              case Some(path) => fetchChannel(path).recover { case _ => None }
              case None => Future.successful(None)
            }
          } else {
            Future.successful(None)
          }

        fetchedOptFut.map { devObjOpt =>
          val isNonOta = devObjOpt.exists(_.channel.source.exists(_ != "ota"))
          if (isNonOta) {
            None
          } else {
            val registeredLabelOpt = devObjOpt.map(Lineup.registerDeviceChannel)
            val resolvedId = rawIdOpt
              .orElse(devObjOpt.map(_.channel.channel_identifier).filter(_.nonEmpty))
              .orElse(pathOpt)
              .getOrElse("")
            if (resolvedId.isEmpty) {
              None
            } else {
              val label =
                if (Lineup.hasChannelLabel(resolvedId)) Lineup.channelLabel(resolvedId)
                else registeredLabelOpt.getOrElse(Lineup.channelLabel(resolvedId))
              val origin = activeChannelStates.get(resolvedId) match {
                case Some(state) => TuneOrigin.Self(state)
                case None => TuneOrigin.External
              }
              Some(TunedChannel(
                slotIndex = idx
              , channelId = resolvedId
              , channelPath = pathOpt
              , label = label
              , origin = origin
              , recording = slot.recording
              ))
            }
          }
        }
      }.map { resolvedOpts =>
        Snapshot(
          totalSlots = slots.size
        , inUseSlots = slots.count(_.in_use)
        , tunedChannels = resolvedOpts.flatten
        )
      }
    }

    def pollOnce(
      sessionManager: ActorRef[Channel.SessionManager.Request]
    , authContext: Auth.AuthContext
    ): WithSystem[Future[Snapshot]] = {
      val system = summon[ActorSystem[?]]
      given scala.concurrent.ExecutionContext = system.executionContext
      given Timeout = Timeout(3.seconds)
      val slotsFut = fetchTunerSlots(authContext)
      val activeStatesFut = sessionManager.ask[Map[String, String]](replyTo =>
        Channel.SessionManager.Request.GetActiveChannelStates(replyTo)
      )
      for {
        slots <- slotsFut
        activeStates <- activeStatesFut
        snapshot <- resolveSnapshot(slots, activeStates, path => fetchDeviceChannel(path, authContext))
      } yield snapshot
    }

    object TunerStateActor {
      sealed trait Request
      object Request {
        final case class GetState(replyTo: ActorRef[Snapshot]) extends Request
      }
      sealed trait Command extends Request
      object Command {
        final case class UpdateState(snapshot: Snapshot) extends Command
        final case class PollFailed(cause: Throwable) extends Command
      }

      def apply(): Behavior[Request] = Behaviors.setup { _ =>
        var current: Snapshot = Snapshot.empty

        Behaviors.receiveMessage {
          case Command.UpdateState(snapshot) =>
            val prevHadTuned = current.tunedChannels.nonEmpty
            current = snapshot
            if (snapshot.tunedChannels.nonEmpty) {
              val details = snapshot.tunedChannels.map(_.formatLogEntry).mkString(", ")
              log.info(
                "[tuner-state] tuned={}/{} inUse={} self={} external={} channels=[{}]"
              , snapshot.tunedChannels.size
              , snapshot.totalSlots
              , snapshot.inUseSlots
              , snapshot.selfCount
              , snapshot.externalCount
              , details
              )
            } else if (prevHadTuned) {
              log.info(
                "[tuner-state] tuned=0/{} inUse={} all channels released"
              , snapshot.totalSlots
              , snapshot.inUseSlots
              )
            } else {
              log.debug(
                "[tuner-state] idle tuned=0/{} inUse={}"
              , snapshot.totalSlots
              , snapshot.inUseSlots
              )
            }
            Behaviors.same

          case Command.PollFailed(cause) =>
            log.warn("[tuner-state] poll failed error={}", cause.getMessage)
            Behaviors.same

          case Request.GetState(replyTo) =>
            replyTo ! current
            Behaviors.same
        }
      }
    }

    def startStream(
      tunerStateActor: ActorRef[TunerStateActor.Request]
    , sessionManager: ActorRef[Channel.SessionManager.Request]
    , authContext: Auth.AuthContext
    , pollInterval: FiniteDuration = 30.seconds
    , initialDelay: FiniteDuration = 5.seconds
    ): WithSystem[UniqueKillSwitch] = {
      startStreamWithPoller(
        tunerStateActor = tunerStateActor
      , pollFn = () => pollOnce(sessionManager, authContext)
      , pollInterval = pollInterval
      , initialDelay = initialDelay
      )
    }

    def startStreamWithPoller(
      tunerStateActor: ActorRef[TunerStateActor.Request]
    , pollFn: () => Future[Snapshot]
    , pollInterval: FiniteDuration = 30.seconds
    , initialDelay: FiniteDuration = 5.seconds
    ): WithSystem[UniqueKillSwitch] = {
      given scala.concurrent.ExecutionContext = summon[ActorSystem[?]].executionContext
      log.info("[tuner-state] starting poll stream interval={}s", pollInterval.toSeconds)
      val (killSwitch, done) = Source
        .tick(initialDelay, pollInterval, ())
        .viaMat(KillSwitches.single)(Keep.right)
        .mapAsync(1) { _ =>
          pollFn().map { snapshot =>
            tunerStateActor ! TunerStateActor.Command.UpdateState(snapshot)
          }.recover { case ex =>
            tunerStateActor ! TunerStateActor.Command.PollFailed(ex)
          }
        }
        .toMat(pekko.stream.scaladsl.Sink.ignore)(Keep.both)
        .run()

      done.onComplete {
        case Success(_) => log.debug("[tuner-state] poll stream completed")
        case Failure(ex) => log.warn("[tuner-state] poll stream terminated with error={}", ex.getMessage)
      }
      killSwitch
    }
  }

  def detectTunerCount(
    authContext: Auth.AuthContext
  , timeout: FiniteDuration = 5.seconds
  ): WithSystem[Int] = {
    import Channel.Response.JsonProtocol.given
    import pekko.http.scaladsl.unmarshalling.Unmarshal
    val system = summon[ActorSystem[?]]
    given scala.concurrent.ExecutionContext = system.executionContext
    val HttpCtx = Http()

    val serverInfoUri = authContext.deviceUrl.withPath(Uri.Path("/server/info")).withQuery(Uri.Query("lh" -> "1"))
    val headers = Hmac.signedHeaders("GET", "/server/info", None, authContext)

    log.debug("[startup] querying /server/info for tuner count uri={}", serverInfoUri)
    val request = HttpRequest(
      method = HttpMethods.GET
    , uri = serverInfoUri
    , headers = headers.toList
    )

    val fut = HttpCtx.singleRequest(request).flatMap { response =>
      log.debug("[startup] server/info status={}", response.status.intValue())
      if (response.status.isSuccess()) {
        Unmarshal(response.entity).to[String].map { body =>
          SafeJson.parseAs[Channel.Response.ServerInfo](body)
            .toOption
            .flatMap(_.detectedTuners)
            .getOrElse(2)
        }
      } else {
        val _ = response.entity.discardBytes()
        log.warn("[startup] server/info returned non-success status={}, defaulting to 2 tuners", response.status.intValue())
        Future.successful(2)
      }
    }.recover { case ex =>
      log.warn("[startup] failed to fetch server/info from Tablo, defaulting to 2 tuners: {}", ex.getMessage)
      2
    }

    Try(scala.concurrent.Await.result(fut, timeout)).getOrElse(2)
  }

  def routes(
    lineupActor: ActorRef[Lineup.LineupActor.Request]
  , sessionManager: ActorRef[Channel.SessionManager.Request]
  , authContext: Auth.AuthContext
  ): WithSystem[pekko.http.scaladsl.server.Route] =
    Tablo2HDHomeRun.Response.Discover.route ~
      Lineup.route(lineupActor) ~
      Guide.route(authContext) ~
      Channel.route(sessionManager, lineupActor, authContext) ~
      Tablo2HDHomeRun.Favicon.route
}
package app

import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.model._
import org.apache.pekko.http.scaladsl.model.headers._
import org.apache.pekko.http.scaladsl.unmarshalling.Unmarshal

import java.security.MessageDigest
import java.time.{ZoneOffset, ZonedDateTime}
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

import scala.concurrent.{ExecutionContext, Future}

object SecretKeySpecExtension {
  extension (key: String) {
    def toSecretKeySpec(algorithm: String): SecretKeySpec =
      SecretKeySpec(key.getBytes("UTF-8"), algorithm)
  }
}

import spray.json._
import DefaultJsonProtocol._

import org.slf4j.LoggerFactory

import SecretKeySpecExtension._

case class LighthouseCredentials(
  accessToken: String
, tokenType: String
, isVerified: Boolean
)

object LighthouseCredentials {
  object JsonProtocol extends DefaultJsonProtocol {
    implicit val lighthouseCredentialsFormat: JsonFormat[LighthouseCredentials] = jsonFormat(
      LighthouseCredentials.apply
    , "access_token"
    , "token_type"
    , "is_verified"
    )
  }
}

case class AccountProfile(
  identifier: String
, name: String
)

object AccountProfile {
  object JsonProtocol extends DefaultJsonProtocol {
    implicit val accountProfileFormat: JsonFormat[AccountProfile] = jsonFormat2(AccountProfile.apply)
  }
}

case class AccountDevice(
  serverId: String
, name: String
, url: String
)

object AccountDevice {
  object JsonProtocol extends DefaultJsonProtocol {
    implicit val accountDeviceFormat: JsonFormat[AccountDevice] = jsonFormat3(AccountDevice.apply)
  }
}

case class AccountInfo(
  identifier: String
, profiles: Seq[AccountProfile]
, devices: Seq[AccountDevice]
)

object AccountInfo {
  object JsonProtocol extends DefaultJsonProtocol {
    import AccountProfile.JsonProtocol.accountProfileFormat
    import AccountDevice.JsonProtocol.accountDeviceFormat
    implicit val accountInfoFormat: JsonFormat[AccountInfo] = jsonFormat3(AccountInfo.apply)
  }
}

case class LighthouseSession(
  token: String
, profileId: String
, deviceId: String
, deviceUrl: String
, deviceName: String
)

case class Gen4Authentication(
  email: String
, password: String
)(implicit system: ActorSystem[?], ec: ExecutionContext) {

  private val log = LoggerFactory.getLogger(this.getClass)
  private val httpCtx = Http()

  private val lighthouseBaseUrl = "https://lighthousetv.ewscloud.com/api/v2"

  private var credentials: Option[LighthouseCredentials] = None
  private var accountInfo: Option[AccountInfo] = None
  private var session: Option[LighthouseSession] = None
  private var deviceKey: Option[String] = None
  private var hashKey: Option[String] = None

  def login(): Future[LighthouseCredentials] = {
    val loginUrl = s"$lighthouseBaseUrl/login/"
    val loginBody = JsObject(
      "email" -> JsString(email)
    , "password" -> JsString(password)
    )

    val request = HttpRequest(
      method = HttpMethods.POST
    , uri = loginUrl
    , entity = HttpEntity(ContentTypes.`application/json`, loginBody.compactPrint)
    )

    httpCtx.singleRequest(request).flatMap { response =>
      log.info(s"[gen4-auth] login (POST) - ${response.status}")
      if (response.status.isSuccess()) {
        import LighthouseCredentials.JsonProtocol.lighthouseCredentialsFormat
        Unmarshal(response.entity).to[String].map { body =>
          val creds = body.parseJson.convertTo[LighthouseCredentials]
          credentials = Some(creds)
          creds
        }
      } else {
        Unmarshal(response.entity).to[String].flatMap { body =>
          Future.failed(TunerBackend.AuthenticationError(s"Login failed: ${response.status} - $body"))
        }
      }
    }
  }

  def getAccount(): Future[AccountInfo] = {
    credentials match {
      case Some(creds) =>
        val accountUrl = s"$lighthouseBaseUrl/account/"
        val authHeader = Authorization(OAuth2BearerToken(creds.accessToken))

        val request = HttpRequest(
          method = HttpMethods.GET
        , uri = accountUrl
        , headers = List(authHeader)
        )

        httpCtx.singleRequest(request).flatMap { response =>
          log.info(s"[gen4-auth] account (GET) - ${response.status}")
          if (response.status.isSuccess()) {
            import AccountInfo.JsonProtocol.accountInfoFormat
            Unmarshal(response.entity).to[String].map { body =>
              val info = body.parseJson.convertTo[AccountInfo]
              accountInfo = Some(info)
              info
            }
          } else {
            Unmarshal(response.entity).to[String].flatMap { body =>
              Future.failed(TunerBackend.AuthenticationError(s"Get account failed: ${response.status} - $body"))
            }
          }
        }

      case None =>
        Future.failed(TunerBackend.AuthenticationError("Not logged in"))
    }
  }

  def selectDevice(deviceName: Option[String] = None): Future[LighthouseSession] = {
    (credentials, accountInfo) match {
      case (Some(creds), Some(info)) =>
        val device = deviceName match {
          case Some(name) =>
            info.devices.find(_.name.toLowerCase == name.toLowerCase)
              .getOrElse(throw TunerBackend.DeviceNotFoundError(s"Device '$name' not found"))
          case None =>
            info.devices.headOption
              .getOrElse(throw TunerBackend.DeviceNotFoundError("No devices found"))
        }

        val profile = info.profiles.headOption
          .getOrElse(throw TunerBackend.AuthenticationError("No profiles found"))

        val selectUrl = s"$lighthouseBaseUrl/account/select/"
        val selectBody = JsObject(
          "pid" -> JsString(profile.identifier)
        , "sid" -> JsString(device.serverId)
        )

        val authHeader = Authorization(OAuth2BearerToken(creds.accessToken))

        val request = HttpRequest(
          method = HttpMethods.POST
        , uri = selectUrl
        , headers = List(authHeader)
        , entity = HttpEntity(ContentTypes.`application/json`, selectBody.compactPrint)
        )

        httpCtx.singleRequest(request).flatMap { response =>
          log.info(s"[gen4-auth] select (POST) - ${response.status}")
          if (response.status.isSuccess()) {
            Unmarshal(response.entity).to[String].map { body =>
              val json = body.parseJson.asJsObject
              val token = json.fields("token").convertTo[String]
              val sess = LighthouseSession(
                token = token
              , profileId = profile.identifier
              , deviceId = device.serverId
              , deviceUrl = device.url
              , deviceName = device.name
              )
              session = Some(sess)
              sess
            }
          } else {
            Unmarshal(response.entity).to[String].flatMap { body =>
              Future.failed(TunerBackend.AuthenticationError(s"Select device failed: ${response.status} - $body"))
            }
          }
        }

      case (None, _) =>
        Future.failed(TunerBackend.AuthenticationError("Not logged in"))
      case (_, None) =>
        Future.failed(TunerBackend.AuthenticationError("Account info not loaded"))
    }
  }

  def setDeviceKeys(deviceKeyValue: String, hashKeyValue: String): Unit = {
    deviceKey = Some(deviceKeyValue)
    hashKey = Some(hashKeyValue)
  }

  def getSession: Option[LighthouseSession] = session
  def getCredentials: Option[LighthouseCredentials] = credentials

  def authenticate(deviceNameOpt: Option[String] = None): Future[LighthouseSession] = {
    for {
      _ <- login()
      _ <- getAccount()
      sess <- selectDevice(deviceNameOpt)
    } yield sess
  }

  def createDeviceAuthHeader(method: String, path: String, body: Option[String] = None): Option[RawHeader] = {
    (deviceKey, hashKey) match {
      case (Some(dk), Some(hk)) =>
        val dateFormatter = DateTimeFormatter
          .ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.ENGLISH)
          .withZone(ZoneOffset.UTC)
        val date = dateFormatter.format(ZonedDateTime.now(ZoneOffset.UTC))

        val bodyMd5 = body match {
          case Some(b) if b.nonEmpty =>
            val md = MessageDigest.getInstance("MD5")
            md.digest(b.getBytes("UTF-8")).map("%02x".format(_)).mkString
          case _ => ""
        }

        val signatureString = s"$method\n$path\n$bodyMd5\n$date"
        log.debug(s"[gen4-auth] signature string: $signatureString")

        val hmac = Mac.getInstance("HmacMD5")
        hmac.init(hk.toSecretKeySpec("HmacMD5"))
        val hmacBytes = hmac.doFinal(signatureString.getBytes("UTF-8"))
        val hmacHex = hmacBytes.map("%02x".format(_)).mkString

        Some(RawHeader("Authorization", s"tablo:$dk:$hmacHex"))

      case _ =>
        log.warn("[gen4-auth] device keys not set, cannot create auth header")
        None
    }
  }

  def createDateHeader(): RawHeader = {
    val dateFormatter = DateTimeFormatter
      .ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.ENGLISH)
      .withZone(ZoneOffset.UTC)
    val date = dateFormatter.format(ZonedDateTime.now(ZoneOffset.UTC))
    RawHeader("Date", date)
  }

  def createUserAgentHeader(): RawHeader = RawHeader("User-Agent", "Tablo-FAST/1.7.0 (Mobile; iPhone; iOS 18.4)")
}

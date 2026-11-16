package app.tuner

import org.apache.pekko.http.scaladsl.model.{HttpHeader, HttpResponse, StatusCode, StatusCodes}
import org.apache.pekko.http.scaladsl.model.headers.RawHeader
import org.apache.pekko.stream.StreamTcpException

import java.io.IOException
import java.util.Locale
import java.util.concurrent.{CompletionException, ExecutionException, TimeoutException}

import scala.annotation.tailrec
import scala.util.Try

import spray.json._

import app.stream.{HlsBackend, ResilientHlsSource}
import app.sys.LogConfig
import org.slf4j.Logger

/** SiliconDust HDHomeRun HTTP API error codes and X-HDHomeRun-Error response mappings. */
enum HDHomeRunError(
  val code: Int
, val reason: String
, val httpStatus: StatusCode
, val defaultBody: String
) {
  case UnknownChannel extends HDHomeRunError(801, "Unknown Channel", StatusCodes.NotFound, "Unknown channel")
  case UnknownTranscodeProfile extends HDHomeRunError(802, "Unknown Transcode Profile", StatusCodes.ServiceUnavailable, "Unknown transcode profile")
  case SystemBusy extends HDHomeRunError(803, "System Busy", StatusCodes.ServiceUnavailable, "System busy")
  case TunerInUse extends HDHomeRunError(804, "Tuner In Use", StatusCodes.ServiceUnavailable, "Tuner in use")
  case AllTunersInUse extends HDHomeRunError(805, "All Tuners In Use", StatusCodes.ServiceUnavailable, "No available tuners")
  case TuneFailed extends HDHomeRunError(806, "Tune Failed", StatusCodes.ServiceUnavailable, "Tune failed")
  case NoVideoData extends HDHomeRunError(807, "No Video Data", StatusCodes.ServiceUnavailable, "No video data")

  def headerValue: String = s"$code $reason"

  def header: HttpHeader = RawHeader(HDHomeRunError.HeaderName, headerValue)

  def toHttpResponse(
    extraHeaders: Seq[HttpHeader] = Nil
  , bodyOverride: Option[String] = None
  ): HttpResponse =
    HttpResponse(
      status = httpStatus
    , headers = (header +: extraHeaders).toList
    , entity = bodyOverride.getOrElse(defaultBody)
    )
}

object HDHomeRunError {
  val HeaderName: String = "X-HDHomeRun-Error"

  final case class ErrorPayload(
    code: Option[String]
  , details: Option[String]
  , message: Option[String]
  , rawTrimmed: String
  ) {
    val searchableLower: String =
      Seq(code, details, message, Some(rawTrimmed)).flatten.mkString(" ").toLowerCase(Locale.ROOT)

    def hasCode(expected: String): Boolean =
      code.exists(_.equalsIgnoreCase(expected)) || details.exists(_.equalsIgnoreCase(expected))
  }

  private def jsonString(fields: Map[String, JsValue], key: String): Option[String] =
    fields.get(key).collect {
      case JsString(s) if s.trim.nonEmpty => s.trim
    }

  /** Parses Tablo 4th Gen and Legacy JSON error envelopes without reflection (GraalVM native safe). */
  def parseErrorPayload(body: String): ErrorPayload = {
    val trimmed = Option(body).getOrElse("").trim
    val parsedObj =
      if (trimmed.isEmpty) None
      else Try(trimmed.parseJson).toOption.collect { case obj: JsObject => obj.fields }

    parsedObj match {
      case Some(fields) =>
        val nestedFields = fields.get("error").collect { case obj: JsObject => obj.fields }
        val errorString = jsonString(fields, "error")
        val code = nestedFields.flatMap(f => jsonString(f, "code")).orElse(jsonString(fields, "code")).orElse(errorString)
        val details = nestedFields.flatMap(f => jsonString(f, "details")).orElse(jsonString(fields, "details"))
        val message = nestedFields
          .flatMap(f => jsonString(f, "description").orElse(jsonString(f, "message")))
          .orElse(jsonString(fields, "description"))
          .orElse(jsonString(fields, "message"))
        ErrorPayload(code = code, details = details, message = message, rawTrimmed = trimmed)
      case None =>
        ErrorPayload(code = None, details = None, message = None, rawTrimmed = trimmed)
    }
  }

  def isWeakSignal(payload: ErrorPayload): Boolean = {
    val text = payload.searchableLower
    payload.hasCode("no_signal_lock") ||
    payload.hasCode("playback_failed") ||
    text.contains("no_signal_lock") ||
    text.contains("signal lock") ||
    text.contains("weak signal") ||
    text.contains("no signal") ||
    text.contains("signal")
  }

  def isTunerExhausted(payload: ErrorPayload): Boolean = {
    val text = payload.searchableLower
    payload.hasCode("no_available_tuners") ||
    text.contains("no_available_tuners") ||
    text.contains("tuner")
  }

  def isSystemBusy(payload: ErrorPayload): Boolean = {
    val text = payload.searchableLower
    payload.hasCode("system_busy") ||
    text.contains("busy") ||
    text.contains("scan")
  }

  enum FailureCategory {
    case ChannelNotFound
    case NoAvailableTuners
    case WeakSignal
    case SystemBusy
    case EmptyBodyTuneFailure
    case TuneFailed
  }

  /** Classifies upstream Tablo watch HTTP status and response payload into a shared failure category. */
  def classifyUpstreamHttpFailure(
    status: StatusCode
  , body: String
  ): (FailureCategory, String) = {
    val payload = parseErrorPayload(body)
    val truncated = LogConfig.truncate(payload.rawTrimmed)
    val category = status match {
      case StatusCodes.NotFound =>
        FailureCategory.ChannelNotFound
      case StatusCodes.Unauthorized | StatusCodes.Forbidden | StatusCodes.BadGateway | StatusCodes.GatewayTimeout =>
        FailureCategory.SystemBusy
      case StatusCodes.Conflict =>
        if (isWeakSignal(payload)) FailureCategory.WeakSignal
        else if (isTunerExhausted(payload) || payload.rawTrimmed.isEmpty) FailureCategory.NoAvailableTuners
        else FailureCategory.TuneFailed
      case StatusCodes.ServiceUnavailable if payload.rawTrimmed.isEmpty =>
        FailureCategory.EmptyBodyTuneFailure
      case _ if payload.hasCode("not_found") =>
        FailureCategory.ChannelNotFound
      case _ if isWeakSignal(payload) =>
        FailureCategory.WeakSignal
      case _ if isTunerExhausted(payload) =>
        FailureCategory.NoAvailableTuners
      case _ if isSystemBusy(payload) =>
        FailureCategory.SystemBusy
      case _ =>
        FailureCategory.TuneFailed
    }
    (category, truncated)
  }

  /** Logs an HDHomeRun route rejection uniformly and returns the formatted HTTP error response. */
  def rejectResponse(
    log: Logger
  , error: HDHomeRunError
  , context: String
  , reason: String
  , cause: Option[Throwable] = None
  , extraHeaders: Seq[HttpHeader] = Nil
  , bodyOverride: Option[String] = None
  ): HttpResponse = {
    val statusCode = java.lang.Integer.valueOf(error.httpStatus.intValue())
    cause match {
      case Some(ex) =>
        log.warn(
          "[channel] stream rejected {} status={} hdhrError=\"{}\" reason={}"
        , context
        , statusCode
        , error.headerValue
        , reason
        , ex
        )
      case None =>
        log.info(
          "[channel] stream rejected {} status={} hdhrError=\"{}\" reason={}"
        , context
        , statusCode
        , error.headerValue
        , reason
        )
    }
    error.toHttpResponse(extraHeaders = extraHeaders, bodyOverride = bodyOverride)
  }

  @tailrec
  private def unwrap(ex: Throwable): Throwable = ex match {
    case _: CompletionException | _: ExecutionException if ex.getCause != null =>
      unwrap(ex.getCause)
    case other =>
      other
  }

  /** Maps internal 4th Gen, Legacy, stream, and transport exceptions to SiliconDust HDHomeRun errors. */
  def fromThrowable(ex: Throwable): HDHomeRunError = unwrap(ex) match {
    case Tablo4thGen.Error.NoAvailableTuners => AllTunersInUse
    case TabloLegacy.Channel.Response.NoAvailableTunersError => AllTunersInUse
    case _: Tablo4thGen.Error.ChannelNotFound => UnknownChannel
    case _: TabloLegacy.Channel.Response.ChannelNotFoundError => UnknownChannel
    case _: Tablo4thGen.Error.NoSignalLock => TuneFailed
    case _: Tablo4thGen.Error.WatchFailed => TuneFailed
    case _: TabloLegacy.Channel.Response.TuneFailedError => TuneFailed
    case _: Tablo4thGen.Error.NoVideoData => NoVideoData
    case _: TabloLegacy.Channel.Response.NoVideoDataError => NoVideoData
    case _: HlsBackend.HlsError => NoVideoData
    case _: ResilientHlsSource.StallTimeoutException => NoVideoData
    case _: Tablo4thGen.Error.SystemBusy => SystemBusy
    case Tablo4thGen.Error.SessionTokenMissing => SystemBusy
    case _: Tablo4thGen.Error.SessionFailed => SystemBusy
    case _: Tablo4thGen.Error.SessionDeleteFailed => SystemBusy
    case Tablo4thGen.Error.WatchCancelled => SystemBusy
    case _: Tablo4thGen.Error.LoginFailed => SystemBusy
    case _: Tablo4thGen.Error.DeviceNotFound => SystemBusy
    case _: Tablo4thGen.Error.SelectFailed => SystemBusy
    case Tablo4thGen.Error.MissingEmail | Tablo4thGen.Error.MissingPassword | Tablo4thGen.Error.NoProfilesFound | Tablo4thGen.Error.NoDevicesFound => SystemBusy
    case _: TabloLegacy.Channel.Response.SystemBusyError => SystemBusy
    case _: StreamTcpException | _: IOException | _: TimeoutException | _: scala.concurrent.TimeoutException => SystemBusy
    case _ => TuneFailed
  }
}

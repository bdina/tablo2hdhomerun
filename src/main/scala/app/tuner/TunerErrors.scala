package app.tuner

import org.apache.pekko.http.scaladsl.model.StatusCode

import scala.util.control.NoStackTrace

/** Typed domain errors for Tablo 4th Gen authentication, session management, and tuning. */
sealed trait FourthGenError extends Exception with NoStackTrace

object FourthGenError {
  case object MissingEmail extends Exception("TABLO_EMAIL is required for 4th gen mode") with FourthGenError
  case object MissingPassword extends Exception("TABLO_PASSWORD is required for 4th gen mode") with FourthGenError
  case class LoginFailed(message: String) extends Exception(s"Login failed: $message") with FourthGenError
  case object NoProfilesFound extends Exception("No profiles found in account") with FourthGenError
  case class DeviceNotFound(name: String) extends Exception(s"No device found matching name: $name") with FourthGenError
  case object NoDevicesFound extends Exception("No devices found in account") with FourthGenError
  case class SelectFailed(message: String) extends Exception(s"Select failed: $message") with FourthGenError
  case object NoAvailableTuners extends Exception("No available tuners") with FourthGenError
  case object WatchCancelled extends Exception("Watch cancelled") with FourthGenError
  case class SessionFailed(message: String, status: Option[StatusCode] = None) extends Exception(message) with FourthGenError
  case class SessionDeleteFailed(message: String) extends Exception(message) with FourthGenError
  case object SessionTokenMissing extends Exception("Session token missing") with FourthGenError
  case class WatchFailed(message: String) extends Exception(message) with FourthGenError
  case class NoSignalLock(message: String) extends Exception(message) with FourthGenError
  case class NoVideoData(message: String) extends Exception(message) with FourthGenError
  case class ChannelNotFound(message: String) extends Exception(message) with FourthGenError
  case class SystemBusy(message: String) extends Exception(message) with FourthGenError
}

/** Typed domain errors for legacy Tablo HTTP and tuner workflows. */
sealed trait LegacyError extends Exception with NoStackTrace

object LegacyError {
  case object NoAvailableTunersError extends Exception("No available tuners") with LegacyError
  case class ChannelNotFoundError(message: String) extends Exception(message) with LegacyError
  case class TuneFailedError(message: String) extends Exception(message) with LegacyError
  case class NoVideoDataError(message: String) extends Exception(message) with LegacyError
  case class SystemBusyError(message: String) extends Exception(message) with LegacyError
}

lazy val WatchSession: Tablo4thGen.Channel.WatchSession.type = Tablo4thGen.Channel.WatchSession

lazy val SessionManager: Tablo4thGen.Channel.SessionManager.type = Tablo4thGen.Channel.SessionManager

lazy val SessionRunner: Tablo4thGen.Channel.SessionRunner.type = Tablo4thGen.Channel.SessionRunner

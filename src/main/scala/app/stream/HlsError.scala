package app.stream

import org.apache.pekko.http.scaladsl.model.StatusCode

import scala.util.control.NoStackTrace

/** Non-throwing NoStackTrace error ADT for HLS playlist/segment retrieval. */
sealed trait HlsError extends RuntimeException with NoStackTrace

object HlsError {
  case class PlaylistFetchError(status: StatusCode) extends RuntimeException(s"playlist fetch failed: $status") with HlsError
  case class SegmentFetchError(status: StatusCode) extends RuntimeException(s"segment fetch failed: $status") with HlsError
  case class SegmentRangeMismatch(detail: String) extends RuntimeException(s"segment range mismatch: $detail") with HlsError
  case class Unauthorized(status: StatusCode) extends RuntimeException(s"segment unauthorized: $status") with HlsError
  case object SegmentNotReady extends RuntimeException("newest segment not ready") with HlsError
  case object SessionEnded extends RuntimeException("playlist ended (EXT-X-ENDLIST)") with HlsError
  case object PollExhausted extends RuntimeException("playlist poll failures exhausted") with HlsError
  case object UnexpectedNotModified extends RuntimeException("unexpected 304 without validators") with HlsError
}

/** Validation errors returned by HlsSegmentFetch.RangeRequest.create without throwing. */
enum RangeError(val message: String) extends Exception(message) with NoStackTrace derives CanEqual {
  case InvalidOffset(offset: Long) extends RangeError(s"offset must be >= 0, got $offset")
  case InvalidLength(length: Long) extends RangeError(s"length must be > 0, got $length")
}

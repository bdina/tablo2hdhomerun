package app.stream

import org.apache.pekko.http.scaladsl.model.{HttpHeader, HttpRequest, StatusCode, StatusCodes}
import org.apache.pekko.http.scaladsl.model.headers.{ByteRange, Range}

import scala.util.boundary
import scala.util.boundary.break

object HlsSegmentFetch {
  final case class RangeRequest private (offset: Long, length: Long) {
    def endInclusive: Long = offset + length - 1
  }

  object RangeRequest {
    /** Validates and constructs a RangeRequest without throwing exceptions. */
    def create(offset: Long, length: Long): RangeRequest | RangeError =
      boundary {
        if (offset < 0) break(RangeError.InvalidOffset(offset))
        if (length <= 0) break(RangeError.InvalidLength(length))
        new RangeRequest(offset, length)
      }
  }

  enum SegmentFetchDecision derives CanEqual {
    case Accept
    case Fail(error: HlsError)
    case Retry(reason: String)
  }
  export SegmentFetchDecision.{Accept, Fail, Retry}

  def isSafeByteRange(offset: Long, length: Long): Boolean =
    offset >= 0 && length > 0

  def safeByteRange(byteRange: Option[(Long, Long)]): Option[(Long, Long)] =
    byteRange.filter { case (offset, length) => isSafeByteRange(offset, length) }

  def buildRangeHeader(byteRange: Option[(Long, Long)]): Option[Range] =
    safeByteRange(byteRange).map { case (offset, length) =>
      Range(ByteRange(offset, offset + length - 1))
    }

  def buildSegmentRequest(url: String, byteRange: Option[(Long, Long)]): HttpRequest = {
    val base = HttpRequest(uri = url)
    buildRangeHeader(byteRange).map(base.addHeader).getOrElse(base)
  }

  private def isServerError(status: StatusCode): Boolean = {
    val code = status.intValue()
    code >= 500 && code <= 599
  }

  def headerValue(headers: Seq[HttpHeader], name: String): Option[String] =
    headers.find(_.lowercaseName() == name.toLowerCase).map(_.value())

  def parseContentRange(value: String): Option[(Long, Long)] = {
    val prefix = "bytes "
    if (!value.startsWith(prefix)) None
    else {
      val parts = value.substring(prefix.length).split("/", 2)
      if (parts.length < 1) None
      else {
        val rangeParts = parts(0).split("-", 2)
        if (rangeParts.length != 2) None
        else {
          val start = scala.util.Try(rangeParts(0).toLong).toOption
          val end = scala.util.Try(rangeParts(1).toLong).toOption
          start.flatMap(s => end.map(e => (s, e)))
        }
      }
    }
  }

  def validateRangedResponse(
    status: StatusCode
  , headers: Seq[HttpHeader]
  , request: RangeRequest
  , retriesLeft: Int
  ): SegmentFetchDecision = {
    if (status == StatusCodes.PartialContent) {
      headerValue(headers, "Content-Range") match {
        case Some(value) =>
          parseContentRange(value) match {
            // A short read (segment stopped growing during a reception drop) is accepted;
            // the missing tail is dropped downstream and the poller moves on.
            case Some((start, end)) if start == request.offset && end <= request.endInclusive => Accept
            case Some((start, end)) =>
              if (retriesLeft > 0) Retry(s"range mismatch: expected ${request.offset}-${request.endInclusive}, got $start-$end")
              else Fail(HlsError.SegmentRangeMismatch(s"expected ${request.offset}-${request.endInclusive}, got $start-$end"))
            case None =>
              if (retriesLeft > 0) Retry("missing or invalid Content-Range")
              else Fail(HlsError.SegmentRangeMismatch("missing or invalid Content-Range"))
          }
        case None => Accept
      }
    } else if (status.isSuccess()) {
      if (retriesLeft > 0) Retry(s"expected 206 Partial Content, got ${status.intValue()}")
      else Fail(HlsError.SegmentRangeMismatch(s"expected 206 Partial Content, got ${status.intValue()}"))
    } else if (status == StatusCodes.RangeNotSatisfiable) {
      if (retriesLeft > 0) Retry(s"range not satisfiable for ${request.offset}-${request.endInclusive}")
      else Fail(HlsError.SegmentRangeMismatch(s"range not satisfiable for ${request.offset}-${request.endInclusive}"))
    } else {
      classifyStatus(status, retriesLeft)
    }
  }

  def classifyStatus(status: StatusCode, retriesLeft: Int): SegmentFetchDecision =
    status match {
      case StatusCodes.NotFound =>
        if (retriesLeft > 0) Retry("segment not ready")
        else Fail(HlsError.SegmentNotReady)
      case StatusCodes.Unauthorized | StatusCodes.Forbidden =>
        Fail(HlsError.Unauthorized(status))
      case StatusCodes.RangeNotSatisfiable =>
        Fail(HlsError.SegmentRangeMismatch(s"range not satisfiable (${status.intValue()})"))
      case _ if isServerError(status) =>
        if (retriesLeft > 0) Retry(s"server error ${status.intValue()}")
        else Fail(HlsError.SegmentFetchError(status))
      case _ if status.isFailure() =>
        if (retriesLeft > 0) Retry(s"segment fetch failed (${status.intValue()})")
        else Fail(HlsError.SegmentFetchError(status))
      case _ => Accept
    }

  def decideSegmentResponse(
    status: StatusCode
  , headers: Seq[HttpHeader]
  , byteRange: Option[(Long, Long)]
  , retriesLeft: Int
  ): SegmentFetchDecision =
    byteRange match {
      case Some((offset, length)) =>
        RangeRequest.create(offset, length) match {
          case req: RangeRequest => validateRangedResponse(status, headers, req, retriesLeft)
          case _: RangeError => classifyStatus(status, retriesLeft)
        }
      case None => classifyStatus(status, retriesLeft)
    }
}
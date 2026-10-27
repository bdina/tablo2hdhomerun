package app.stream

import org.apache.pekko.http.scaladsl.model.{HttpHeader, HttpRequest, StatusCode, StatusCodes}
import org.apache.pekko.http.scaladsl.model.headers.{ByteRange, Range}

object HlsSegmentFetch {
  final case class RangeRequest(offset: Long, length: Long) {
    require(offset >= 0, s"offset must be >= 0, got $offset")
    require(length > 0, s"length must be > 0, got $length")
    def endInclusive: Long = offset + length - 1
  }

  sealed trait SegmentFetchDecision
  case object Accept extends SegmentFetchDecision
  final case class Fail(error: HlsBackend.HlsError) extends SegmentFetchDecision
  final case class Retry(reason: String) extends SegmentFetchDecision

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
              else Fail(HlsBackend.HlsError.SegmentRangeMismatch(s"expected ${request.offset}-${request.endInclusive}, got $start-$end"))
            case None =>
              if (retriesLeft > 0) Retry("missing or invalid Content-Range")
              else Fail(HlsBackend.HlsError.SegmentRangeMismatch("missing or invalid Content-Range"))
          }
        case None => Accept
      }
    } else if (status.isSuccess()) {
      if (retriesLeft > 0) Retry(s"expected 206 Partial Content, got ${status.intValue()}")
      else Fail(HlsBackend.HlsError.SegmentRangeMismatch(s"expected 206 Partial Content, got ${status.intValue()}"))
    } else if (status == StatusCodes.RangeNotSatisfiable) {
      if (retriesLeft > 0) Retry(s"range not satisfiable for ${request.offset}-${request.endInclusive}")
      else Fail(HlsBackend.HlsError.SegmentRangeMismatch(s"range not satisfiable for ${request.offset}-${request.endInclusive}"))
    } else {
      classifyStatus(status, retriesLeft)
    }
  }

  def classifyStatus(status: StatusCode, retriesLeft: Int): SegmentFetchDecision =
    status match {
      case StatusCodes.NotFound =>
        if (retriesLeft > 0) Retry("segment not ready")
        else Fail(HlsBackend.HlsError.SegmentNotReady)
      case StatusCodes.Unauthorized | StatusCodes.Forbidden =>
        Fail(HlsBackend.HlsError.Unauthorized(status))
      case StatusCodes.RangeNotSatisfiable =>
        Fail(HlsBackend.HlsError.SegmentRangeMismatch(s"range not satisfiable (${status.intValue()})"))
      case _ if isServerError(status) =>
        if (retriesLeft > 0) Retry(s"server error ${status.intValue()}")
        else Fail(HlsBackend.HlsError.SegmentFetchError(status))
      case _ if status.isFailure() =>
        if (retriesLeft > 0) Retry(s"segment fetch failed (${status.intValue()})")
        else Fail(HlsBackend.HlsError.SegmentFetchError(status))
      case _ => Accept
    }

  def decideSegmentResponse(
    status: StatusCode
  , headers: Seq[HttpHeader]
  , byteRange: Option[(Long, Long)]
  , retriesLeft: Int
  ): SegmentFetchDecision =
    safeByteRange(byteRange) match {
      case Some((offset, length)) =>
        validateRangedResponse(status, headers, RangeRequest(offset, length), retriesLeft)
      case None => classifyStatus(status, retriesLeft)
    }

  val DefaultTrickleRatioThreshold: Double = 0.15
  val DefaultMinBaselineBytesPerSec: Double = 200000.0
  val DefaultMaxConsecutiveTrickleSegments: Int = 5
  val DefaultBaselineEmaAlpha: Double = 0.30

  sealed trait TrickleDecision {
    def state: TrickleState
  }
  final case class HealthySegment(state: TrickleState, rateBytesPerSec: Double) extends TrickleDecision
  final case class TrickleSegment(state: TrickleState, rateBytesPerSec: Double, baselineBytesPerSec: Double) extends TrickleDecision
  final case class TrickleCollapsed(state: TrickleState, rateBytesPerSec: Double, baselineBytesPerSec: Double) extends TrickleDecision

  final case class TrickleState(
    baselineBytesPerSec: Double = 0.0
  , consecutiveTrickleCount: Int = 0
  , trickleRatioThreshold: Double = DefaultTrickleRatioThreshold
  , minBaselineBytesPerSec: Double = DefaultMinBaselineBytesPerSec
  , maxConsecutiveTrickleSegments: Int = DefaultMaxConsecutiveTrickleSegments
  ) {
    def recordSegment(bytes: Long, durationSec: Double): TrickleDecision = {
      val effectiveDuration = if (durationSec > 0.1) durationSec else 2.0
      val rate = math.max(0.0, bytes.toDouble) / effectiveDuration
      if (baselineBytesPerSec < minBaselineBytesPerSec) {
        if (rate >= minBaselineBytesPerSec) {
          HealthySegment(copy(baselineBytesPerSec = rate, consecutiveTrickleCount = 0), rate)
        } else {
          HealthySegment(this, rate)
        }
      } else if (rate < baselineBytesPerSec * trickleRatioThreshold) {
        val nextCount = consecutiveTrickleCount + 1
        val nextState = copy(consecutiveTrickleCount = nextCount)
        if (nextCount >= maxConsecutiveTrickleSegments) {
          TrickleCollapsed(nextState, rate, baselineBytesPerSec)
        } else {
          TrickleSegment(nextState, rate, baselineBytesPerSec)
        }
      } else {
        val nextBaseline = ((1.0 - DefaultBaselineEmaAlpha) * baselineBytesPerSec) + (DefaultBaselineEmaAlpha * rate)
        HealthySegment(copy(baselineBytesPerSec = nextBaseline, consecutiveTrickleCount = 0), rate)
      }
    }
  }
}

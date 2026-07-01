package app.stream

import org.junit.runner.RunWith
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.junit.JUnitRunner

import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.apache.pekko.http.scaladsl.model.headers.RawHeader

@RunWith(classOf[JUnitRunner])
class HlsSegmentFetchSpec extends AnyFlatSpec with Matchers {

  private val rangeRequest = HlsSegmentFetch.RangeRequest(offset = 1000L, length = 500L)

  "HlsSegmentFetch.validateRangedResponse" should "accept matching 206 Content-Range" in {
    val headers = Seq(RawHeader("Content-Range", "bytes 1000-1499/5000"))
    HlsSegmentFetch.validateRangedResponse(StatusCodes.PartialContent, headers, rangeRequest, retriesLeft = 0) shouldBe
      HlsSegmentFetch.Accept
  }

  it should "reject 200 OK for ranged requests" in {
    HlsSegmentFetch.validateRangedResponse(StatusCodes.OK, Seq.empty, rangeRequest, retriesLeft = 0) shouldBe a[HlsSegmentFetch.Fail]
  }

  it should "reject mismatched Content-Range" in {
    val headers = Seq(RawHeader("Content-Range", "bytes 0-499/5000"))
    HlsSegmentFetch.validateRangedResponse(StatusCodes.PartialContent, headers, rangeRequest, retriesLeft = 0) shouldBe a[HlsSegmentFetch.Fail]
  }

  it should "reject 416 for ranged requests" in {
    HlsSegmentFetch.validateRangedResponse(StatusCodes.RangeNotSatisfiable, Seq.empty, rangeRequest, retriesLeft = 0) shouldBe
      a[HlsSegmentFetch.Fail]
  }

  "HlsSegmentFetch.classifyStatus" should "retry 404 while retries remain" in {
    HlsSegmentFetch.classifyStatus(StatusCodes.NotFound, retriesLeft = 1) shouldBe
      HlsSegmentFetch.Retry("segment not ready")
  }

  it should "fail 404 when retries are exhausted" in {
    HlsSegmentFetch.classifyStatus(StatusCodes.NotFound, retriesLeft = 0) shouldBe
      HlsSegmentFetch.Fail(HlsBackend.HlsError.SegmentNotReady)
  }

  it should "fail fast on 401" in {
    HlsSegmentFetch.classifyStatus(StatusCodes.Unauthorized, retriesLeft = 3) shouldBe
      HlsSegmentFetch.Fail(HlsBackend.HlsError.Unauthorized(StatusCodes.Unauthorized))
  }

  it should "retry 503 while retries remain" in {
    HlsSegmentFetch.classifyStatus(StatusCodes.ServiceUnavailable, retriesLeft = 1) shouldBe
      HlsSegmentFetch.Retry("server error 503")
  }

  "HlsSegmentFetch.decideSegmentResponse" should "accept non-ranged success responses" in {
    HlsSegmentFetch.decideSegmentResponse(StatusCodes.OK, Seq.empty, None, retriesLeft = 0) shouldBe
      HlsSegmentFetch.Accept
  }
}

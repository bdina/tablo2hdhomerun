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

  it should "reject 200 OK for ranged requests when retries exhausted" in {
    HlsSegmentFetch.validateRangedResponse(StatusCodes.OK, Seq.empty, rangeRequest, retriesLeft = 0) shouldBe a[HlsSegmentFetch.Fail]
  }

  it should "retry 200 OK for ranged requests when retries remain" in {
    HlsSegmentFetch.validateRangedResponse(StatusCodes.OK, Seq.empty, rangeRequest, retriesLeft = 1) shouldBe a[HlsSegmentFetch.Retry]
  }

  it should "reject mismatched Content-Range when retries exhausted" in {
    val headers = Seq(RawHeader("Content-Range", "bytes 0-499/5000"))
    HlsSegmentFetch.validateRangedResponse(StatusCodes.PartialContent, headers, rangeRequest, retriesLeft = 0) shouldBe a[HlsSegmentFetch.Fail]
  }

  it should "retry mismatched Content-Range when retries remain" in {
    val headers = Seq(RawHeader("Content-Range", "bytes 2000-2499/5000"))
    HlsSegmentFetch.validateRangedResponse(StatusCodes.PartialContent, headers, rangeRequest, retriesLeft = 2) shouldBe a[HlsSegmentFetch.Retry]
  }

  it should "accept a short 206 whose start matches the requested offset" in {
    val headers = Seq(RawHeader("Content-Range", "bytes 1000-1200/5000"))
    HlsSegmentFetch.validateRangedResponse(StatusCodes.PartialContent, headers, rangeRequest, retriesLeft = 0) shouldBe
      HlsSegmentFetch.Accept
  }

  it should "reject 416 for ranged requests when retries exhausted" in {
    HlsSegmentFetch.validateRangedResponse(StatusCodes.RangeNotSatisfiable, Seq.empty, rangeRequest, retriesLeft = 0) shouldBe
      a[HlsSegmentFetch.Fail]
  }

  it should "retry 416 for ranged requests when retries remain" in {
    HlsSegmentFetch.validateRangedResponse(StatusCodes.RangeNotSatisfiable, Seq.empty, rangeRequest, retriesLeft = 1) shouldBe
      a[HlsSegmentFetch.Retry]
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

  it should "treat zero or negative length byte-ranges as un-ranged and accept 200 OK" in {
    val _ = HlsSegmentFetch.decideSegmentResponse(StatusCodes.OK, Seq.empty, Some((1000L, 0L)), retriesLeft = 0) shouldBe
      HlsSegmentFetch.Accept
    val _ = HlsSegmentFetch.decideSegmentResponse(StatusCodes.OK, Seq.empty, Some((1000L, -10L)), retriesLeft = 0) shouldBe
      HlsSegmentFetch.Accept
    HlsSegmentFetch.decideSegmentResponse(StatusCodes.OK, Seq.empty, Some((-5L, 500L)), retriesLeft = 0) shouldBe
      HlsSegmentFetch.Accept
  }

  "HlsSegmentFetch.safeByteRange" should "accept valid positive byte ranges" in {
    val _ = HlsSegmentFetch.safeByteRange(Some((0L, 100L))) shouldBe Some((0L, 100L))
    HlsSegmentFetch.safeByteRange(Some((1000L, 500L))) shouldBe Some((1000L, 500L))
  }

  it should "filter out zero, negative length, or negative offset ranges" in {
    val _ = HlsSegmentFetch.safeByteRange(Some((1000L, 0L))) shouldBe None
    val _ = HlsSegmentFetch.safeByteRange(Some((1000L, -1L))) shouldBe None
    val _ = HlsSegmentFetch.safeByteRange(Some((-1L, 500L))) shouldBe None
    HlsSegmentFetch.safeByteRange(None) shouldBe None
  }

  "HlsSegmentFetch.buildRangeHeader" should "create Pekko Range header for safe byte ranges" in {
    val header = HlsSegmentFetch.buildRangeHeader(Some((1000L, 500L)))
    val _ = header shouldBe defined
    val _ = header.get.value() shouldBe "bytes=1000-1499"
  }

  it should "return None for invalid byte ranges" in {
    val _ = HlsSegmentFetch.buildRangeHeader(Some((1000L, 0L))) shouldBe None
    val _ = HlsSegmentFetch.buildRangeHeader(Some((1000L, -10L))) shouldBe None
    val _ = HlsSegmentFetch.buildRangeHeader(Some((-1L, 100L))) shouldBe None
    val _ = HlsSegmentFetch.buildRangeHeader(None) shouldBe None
  }

  "HlsSegmentFetch.buildSegmentRequest" should "include Range header when byteRange is valid" in {
    val req = HlsSegmentFetch.buildSegmentRequest("http://host/seg.ts", Some((1000L, 500L)))
    val _ = req.getHeader("Range").isPresent shouldBe true
    val _ = req.getHeader("Range").get().value() shouldBe "bytes=1000-1499"
  }

  it should "omit Range header when byteRange is zero, negative, or None" in {
    val _ = HlsSegmentFetch.buildSegmentRequest("http://host/seg.ts", Some((1000L, 0L))).getHeader("Range").isPresent shouldBe false
    val _ = HlsSegmentFetch.buildSegmentRequest("http://host/seg.ts", Some((1000L, -5L))).getHeader("Range").isPresent shouldBe false
    val _ = HlsSegmentFetch.buildSegmentRequest("http://host/seg.ts", None).getHeader("Range").isPresent shouldBe false
  }

  "HlsSegmentFetch.RangeRequest" should "reject negative offset or non-positive length" in {
    val _ = an[IllegalArgumentException] should be thrownBy HlsSegmentFetch.RangeRequest(-1L, 100L)
    val _ = an[IllegalArgumentException] should be thrownBy HlsSegmentFetch.RangeRequest(0L, 0L)
    an[IllegalArgumentException] should be thrownBy HlsSegmentFetch.RangeRequest(100L, -10L)
  }
}

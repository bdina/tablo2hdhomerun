package app.stream

import org.apache.pekko

import pekko.actor.typed.ActorSystem

import pekko.http.scaladsl

import scaladsl.Http
import scaladsl.model.{HttpRequest, StatusCode, StatusCodes}
import scaladsl.model.headers.{ByteRange, Range}
import scaladsl.unmarshalling.Unmarshal

import pekko.stream.scaladsl.Source
import pekko.util.ByteString

import org.apache.pekko.pattern.after
import org.slf4j.LoggerFactory

import app.AppContext
import app.sys.LogConfig

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.{Failure, Success}

object HlsBackend extends StreamBackend {
  val log = LoggerFactory.getLogger(this.getClass)
  override def name: String = "hls"
  val defaultPollSec: Int = 2

  sealed trait HlsError extends Throwable
  object HlsError {
    case class PlaylistFetchError(status: StatusCode) extends RuntimeException(s"playlist fetch failed: ${status}") with HlsError
    case class SegmentFetchError(status: StatusCode) extends RuntimeException(s"segment fetch failed: ${status}") with HlsError
    case class SegmentRangeMismatch(detail: String) extends RuntimeException(s"segment range mismatch: $detail") with HlsError
    case class Unauthorized(status: StatusCode) extends RuntimeException(s"segment unauthorized: ${status}") with HlsError
    case object SegmentNotReady extends RuntimeException("newest segment not ready") with HlsError
    case object SessionEnded extends RuntimeException("playlist ended (EXT-X-ENDLIST)") with HlsError
    case object PlaylistStall extends RuntimeException("playlist media-sequence stalled") with HlsError
    case object PollExhausted extends RuntimeException("playlist poll failures exhausted") with HlsError
    final case class TsHealthDegraded(detail: String) extends RuntimeException(s"ts health degraded: $detail") with HlsError
  }

  private[stream] def pollOutcomeToFuture(
    outcome: HlsPlaylistPoller.Outcome
  ): Future[Option[(HlsPlaylistPoller.PollState, Seq[HlsPlaylistPoller.SegmentInfo])]] =
    outcome match {
      case HlsPlaylistPoller.Emit(next, segments) => Future.successful(Some((next, segments)))
      case HlsPlaylistPoller.Fail(err) => Future.failed(err)
    }

  type SegmentInfo = HlsPlaylistPoller.SegmentInfo
  type PollState = HlsPlaylistPoller.PollState

  private def healthSettings: MpegTsHealth.Settings = AppContext.config.stream.hls.health

  override def stream(playlistUrl: String, label: String = "")(implicit system: ActorSystem[?]): Source[ByteString, ?] = {
    import org.apache.pekko.actor.typed.scaladsl.adapter._
    implicit val ec: scala.concurrent.ExecutionContext = system.executionContext
    val mat: pekko.stream.Materializer = pekko.stream.SystemMaterializer(system).materializer
    val http = Http(system.toClassic)
    val bytesOut = new java.util.concurrent.atomic.AtomicLong(0L)
    val lastSeqOut = new java.util.concurrent.atomic.AtomicInteger(0)
    val hlsConfig = AppContext.config.stream.hls
    val heartbeatSec = hlsConfig.heartbeatSec
    var heartbeatTask: Option[org.apache.pekko.actor.Cancellable] = None
    def fetchPlaylistBody(url: String)(implicit mat: pekko.stream.Materializer): Future[String] =
      fetchPlaylist(url, HlsPlaylistFetch.Validators()).flatMap {
        case Left(body) => Future.successful(body)
        case Right(_) => Future.failed(new IllegalStateException("unexpected 304 without validators"))
      }
    def fetchPlaylist(
      url: String
    , validators: HlsPlaylistFetch.Validators
    )(implicit mat: pekko.stream.Materializer): Future[Either[String, Unit]] = {
      val headers = HlsPlaylistFetch.conditionalHeaders(validators)
      val request = HttpRequest(uri = url).withHeaders(headers)
      http.singleRequest(request).flatMap { response =>
        if (response.status == StatusCodes.NotModified) {
          val _ = response.entity.discardBytes()
          Future.successful(Right(()))
        } else if (response.status.isSuccess()) {
          Unmarshal(response.entity).to[String].map(body => Left(body))
        } else {
          val _ = response.entity.discardBytes()
          Future.failed(HlsError.PlaylistFetchError(response.status))
        }
      }
    }
    def fetchPlaylistWithState(state: PollState)(implicit mat: pekko.stream.Materializer): Future[(PollState, Option[String])] = {
      val validators = HlsPlaylistFetch.fromPollState(state)
      val headers = HlsPlaylistFetch.conditionalHeaders(validators)
      val request = HttpRequest(uri = state.baseUrl).withHeaders(headers)
      http.singleRequest(request).flatMap { response =>
        val responseValidators = HlsPlaylistFetch.extractValidators(response.headers)
        val nextValidators = HlsPlaylistFetch.Validators(
          etag = responseValidators.etag.orElse(validators.etag)
        , lastModified = responseValidators.lastModified.orElse(validators.lastModified)
        )
        val nextState = HlsPlaylistFetch.applyToPollState(state, nextValidators)
        if (response.status == StatusCodes.NotModified) {
          val _ = response.entity.discardBytes()
          Future.successful((nextState, None))
        } else if (response.status.isSuccess()) {
          Unmarshal(response.entity).to[String].map(body => (nextState, Some(body)))
        } else {
          val _ = response.entity.discardBytes()
          Future.failed(HlsError.PlaylistFetchError(response.status))
        }
      }
    }
    def fetchSegmentSource(url: String, byteRange: Option[(Long, Long)]): Source[ByteString, ?] = {
      val maxSegmentRetries = 3
      val segmentRetryDelay = 500.millis
      val scheduler: pekko.actor.Scheduler = system.toClassic.scheduler
      def retryAfter(retriesLeft: Int, message: String, nextSource: => Source[ByteString, ?]): Source[ByteString, ?] = {
        log.warn(s"[stream:hls] $message, retries left: $retriesLeft")
        Source
          .futureSource(after(segmentRetryDelay, scheduler)(Future.successful(nextSource)))
          .mapMaterializedValue(_ => pekko.NotUsed)
      }
      def failSource(error: HlsError): Source[ByteString, ?] = Source.failed(error)
      def attemptFetch(retriesLeft: Int): Source[ByteString, ?] = {
        val request = byteRange match {
          case Some((offset, length)) =>
            log.debug("[stream:hls] segment range fetch offset={} length={} url={}", offset, length, url)
            HttpRequest(uri = url).addHeader(Range(ByteRange(offset, offset + length - 1)))
          case None => HttpRequest(uri = url)
        }
        Source.futureSource(
          http.singleRequest(request).map { response =>
            HlsSegmentFetch.decideSegmentResponse(
              response.status
            , response.headers
            , byteRange
            , retriesLeft
            ) match {
              case HlsSegmentFetch.Accept =>
                response.entity.dataBytes
              case HlsSegmentFetch.Retry(reason) if retriesLeft > 0 =>
                val _ = response.entity.discardBytes()
                retryAfter(retriesLeft, reason, attemptFetch(retriesLeft - 1))
              case HlsSegmentFetch.Retry(_) =>
                val _ = response.entity.discardBytes()
                failSource(HlsError.SegmentNotReady)
              case HlsSegmentFetch.Fail(err) =>
                val _ = response.entity.discardBytes()
                failSource(err)
            }
          }.recover { case ex if retriesLeft > 0 =>
            retryAfter(retriesLeft, s"segment fetch error: ${ex.getMessage}", attemptFetch(retriesLeft - 1))
          }
        ).mapMaterializedValue(_ => pekko.NotUsed)
      }
      attemptFetch(maxSegmentRetries)
    }
    def resolveMediaPlaylistUrl(url: String)(implicit mat: pekko.stream.Materializer): Future[String] =
      fetchPlaylistBody(url).flatMap { body =>
        val lines = body.linesIterator.map(_.trim).filter(_.nonEmpty).toVector
        val isMaster = lines.exists(_.startsWith("#EXT-X-STREAM-INF"))
        if (isMaster) {
          val variantUriOpt = lines.zipWithIndex.find(_._1.startsWith("#EXT-X-STREAM-INF")).flatMap { case (_, i) =>
            if (i + 1 < lines.length) {
              val next = lines(i + 1).trim
              if (!next.startsWith("#") && next.nonEmpty) Some(next) else None
            } else None
          }
          variantUriOpt match {
            case Some(relative) =>
              val resolved = M3U8.resolveSegmentUri(relative, url)
              log.debug("[stream:hls] master playlist variant={}", resolved)
              resolveMediaPlaylistUrl(resolved)(mat)
            case None => Future.successful(url)
          }
        } else {
          val playlist = M3U8.parse(body)
          if (playlist.segments.nonEmpty) {
            Future.successful(url)
          } else {
            val m3u8Line = lines.find(line => !line.startsWith("#") && line.contains(".m3u8"))
            m3u8Line match {
              case Some(relative) =>
                val resolved = M3U8.resolveSegmentUri(relative, url)
                fetchPlaylistBody(resolved)(mat).map(_ => resolved)
              case None => Future.successful(url)
            }
          }
        }
      }
    def pollIntervalSecFromState(state: PollState): Int =
      HlsPlaylistPoller.pollDelaySec(
        state
      , state.lastAdvanced
      , state.lastTargetDuration
      , defaultPollSec
      )
    def step(state: PollState)(implicit mat: pekko.stream.Materializer): Future[Option[(PollState, Seq[SegmentInfo])]] = {
      val doFetch: () => Future[Option[(PollState, Seq[SegmentInfo])]] = () => {
        fetchPlaylistWithState(state)(mat)
          .map { case (fetchedState, bodyOpt) =>
            bodyOpt match {
              case None =>
                log.debug("[stream:hls] playlist not-modified lastSeq={}", fetchedState.lastSeq)
                HlsPlaylistPoller.onPlaylistNotModified(fetchedState, hlsConfig.stallPolls)
              case Some(body) =>
                val playlist = M3U8.parse(body)
                log.debug(
                  "[stream:hls] playlist version={} target={} mediaSequence={} segments={} pollDelay={}"
                , playlist.version.map(_.toString).getOrElse("unknown")
                , playlist.targetDuration
                , playlist.mediaSequence
                , playlist.segments.size
                , pollIntervalSecFromState(fetchedState)
                )
                HlsPlaylistPoller.onPlaylist(
                  fetchedState
                , playlist
                , hlsConfig.stallPolls
                , defaultPollSec
                )
            }
          }
          .recover { case ex =>
            val outcome = HlsPlaylistPoller.onFetchError(state, hlsConfig.pollFailuresMax)
            outcome match {
              case HlsPlaylistPoller.Emit(_, _) =>
                log.warn("[stream:hls] poll failed retrying error={}", ex.getMessage)
              case HlsPlaylistPoller.Fail(_) =>
                log.warn("[stream:hls] poll failed error={}", ex.getMessage)
            }
            outcome
          }
          .flatMap { outcome =>
            outcome match {
              case HlsPlaylistPoller.Fail(err) =>
                log.warn("[stream:hls] retune reason={}", err.getMessage)
                Future.failed(err)
              case HlsPlaylistPoller.Emit(next, segments) =>
                lastSeqOut.set(next.lastSeq)
                if (segments.nonEmpty && !state.loggedFirstSegment) {
                  val head = segments.head
                  val last = segments.last
                  val rangeDesc = head.byteRange.map { case (o, l) => s" range=$o-${o + l - 1}" }.getOrElse("")
                  log.info(
                    "[stream:hls] emit label={} count={} firstSeq={} lastSeq={} firstUrl={}{}"
                  , label
                  , segments.size
                  , head.sequence
                  , last.sequence
                  , LogConfig.truncate(head.url)
                  , rangeDesc
                  )
                }
                Future.successful(Some((next, segments)))
            }
          }
      }
      val scheduler: pekko.actor.Scheduler = system.toClassic.scheduler
      val delaySec = pollIntervalSecFromState(state)
      if (delaySec <= 0) {
        doFetch()
      } else {
        val delay = delaySec.seconds
        after(delay, scheduler)(Future.successful(())).flatMap(_ => doFetch())
      }
    }
    Source.lazySource { () =>
      log.info("[stream:hls] materialized label={} playlistUrl={}", label, playlistUrl)
      heartbeatTask = Some(
        system.toClassic.scheduler.scheduleWithFixedDelay(heartbeatSec.seconds, heartbeatSec.seconds) { () =>
          log.debug("[stream:hls] heartbeat label={} lastSeq={} bytes={}", label, lastSeqOut.get(), bytesOut.get())
        }(ec)
      )
      Source.futureSource(
        resolveMediaPlaylistUrl(playlistUrl)(mat).map { url =>
          log.debug("[stream:hls] resolved media playlist={}", url)
          Source.unfoldAsync(HlsPlaylistPoller.initial(url))(s => step(s)(mat)).flatMapConcat { segments =>
            if (segments.isEmpty) Source.empty
            else Source(segments)
              .flatMapConcat { seg => fetchSegmentSource(seg.url, seg.byteRange) }
              .map { chunk =>
                val _ = bytesOut.addAndGet(chunk.size)
                chunk
              }
              .via(MpegTsDiscontinuity.markFirstPackets(20))
              .via(MpegTsHealth.monitor(healthSettings))
          }
        }
      ).watchTermination() { (_, done) =>
        done.onComplete {
          case Success(_) =>
            heartbeatTask.foreach(_.cancel())
            log.info("[stream:hls] complete label={}", label)
          case Failure(ex) =>
            heartbeatTask.foreach(_.cancel())
            log.warn("[stream:hls] failed label={}", label, ex)
        }
      }
    }
  }
}

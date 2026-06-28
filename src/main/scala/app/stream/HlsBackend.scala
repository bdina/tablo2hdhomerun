package app.stream

import org.apache.pekko

import pekko.actor.typed.ActorSystem

import pekko.http.scaladsl

import scaladsl.Http
import scaladsl.model.{HttpRequest, StatusCode}
import scaladsl.model.headers.{ByteRange, Range}
import scaladsl.unmarshalling.Unmarshal

import pekko.stream.scaladsl.Source
import pekko.util.ByteString

import org.apache.pekko.pattern.after
import org.slf4j.LoggerFactory

import app.Tablo2HDHomeRun

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
    case object SessionEnded extends RuntimeException("playlist ended (EXT-X-ENDLIST)") with HlsError
    case object PlaylistStall extends RuntimeException("playlist media-sequence stalled") with HlsError
    case object PollExhausted extends RuntimeException("playlist poll failures exhausted") with HlsError
    case object SegmentGap extends RuntimeException("no segment bytes within gap window") with HlsError
    final case class TsHealthDegraded(detail: String) extends RuntimeException(s"ts health degraded: $detail") with HlsError
  }

  type SegmentInfo = HlsPlaylistPoller.SegmentInfo
  type PollState = HlsPlaylistPoller.PollState

  private def healthSettings: MpegTsHealth.Settings =
    MpegTsHealth.Settings(
      windowSec = Tablo2HDHomeRun.STREAM_HLS_HEALTH_WINDOW_SEC
    , ccMax = Tablo2HDHomeRun.STREAM_HLS_CC_ERROR_MAX
    , syncMax = Tablo2HDHomeRun.STREAM_HLS_SYNC_LOSS_MAX
    , nullRatioMax = Tablo2HDHomeRun.STREAM_HLS_NULL_RATIO_MAX
    , enforce = Tablo2HDHomeRun.STREAM_HLS_HEALTH_ENFORCE
    )

  override def stream(playlistUrl: String)(implicit system: ActorSystem[?]): Source[ByteString, ?] = {
    import org.apache.pekko.actor.typed.scaladsl.adapter._
    implicit val ec: scala.concurrent.ExecutionContext = system.executionContext
    val mat: pekko.stream.Materializer = pekko.stream.SystemMaterializer(system).materializer
    val http = Http(system.toClassic)
    val bytesOut = new java.util.concurrent.atomic.AtomicLong(0L)
    val lastSeqOut = new java.util.concurrent.atomic.AtomicInteger(0)
    val heartbeatSec = Tablo2HDHomeRun.STREAM_HLS_HEARTBEAT_SEC
    var heartbeatTask: Option[org.apache.pekko.actor.Cancellable] = None
    def fetchPlaylistBody(url: String)(implicit mat: pekko.stream.Materializer): Future[String] =
      http.singleRequest(HttpRequest(uri = url)).flatMap { response =>
        if (response.status.isSuccess()) {
          Unmarshal(response.entity).to[String]
        } else {
          val _ = response.entity.discardBytes()
          Future.failed(HlsError.PlaylistFetchError(response.status))
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
      def attemptFetch(retriesLeft: Int): Source[ByteString, ?] = {
        val request = byteRange match {
          case Some((offset, length)) => HttpRequest(uri = url).addHeader(Range(ByteRange(offset, offset + length - 1)))
          case None => HttpRequest(uri = url)
        }
        Source.futureSource(
          http.singleRequest(request).map { response =>
            if (response.status.isSuccess()) response.entity.dataBytes
            else {
              val _ = response.entity.discardBytes()
              if (retriesLeft > 0) {
                retryAfter(retriesLeft, s"segment fetch failed (${response.status})", attemptFetch(retriesLeft - 1))
              } else {
                Source.failed(HlsError.SegmentFetchError(response.status))
              }
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
    def pollIntervalSecFromTarget(targetSec: Int): Int = (targetSec / 2).max(1)
    def applyOutcome(
      outcome: HlsPlaylistPoller.Outcome
    , priorLogged: Boolean
    ): Future[Option[(PollState, Seq[SegmentInfo])]] = {
      outcome match {
        case HlsPlaylistPoller.Emit(next, segments) =>
          lastSeqOut.set(next.lastSeq)
          if (segments.nonEmpty && !priorLogged) {
            val rangeDesc = segments.head._2.map { case (o, l) => s" range=$o-${o + l - 1}" }.getOrElse("")
            log.info("[stream:hls] segments count={} first={}{}", segments.size, segments.head._1, rangeDesc)
          }
          Future.successful(Some((next, segments)))
        case HlsPlaylistPoller.Fail(err) =>
          Future.failed(err)
      }
    }
    def step(state: PollState)(implicit mat: pekko.stream.Materializer): Future[Option[(PollState, Seq[SegmentInfo])]] = {
      val doFetch: () => Future[Option[(PollState, Seq[SegmentInfo])]] = () => {
        fetchPlaylistBody(state.baseUrl).flatMap { body =>
          val playlist = M3U8.parse(body)
          val outcome = HlsPlaylistPoller.onPlaylist(
            state
            , playlist
            , Tablo2HDHomeRun.STREAM_HLS_STALL_POLLS
            , defaultPollSec
          )
          applyOutcome(outcome, state.loggedFirstSegment)
        }.recoverWith { case ex =>
          val outcome = HlsPlaylistPoller.onFetchError(state, Tablo2HDHomeRun.STREAM_HLS_POLL_FAILURES_MAX)
          outcome match {
            case HlsPlaylistPoller.Fail(fetchErr) =>
              log.warn("[stream:hls] poll failed error={}", ex.getMessage)
              Future.failed(fetchErr)
            case HlsPlaylistPoller.Emit(next, segments) =>
              log.warn("[stream:hls] poll failed retrying error={}", ex.getMessage)
              Future.successful(Some((next, segments)))
          }
        }
      }
      val scheduler: pekko.actor.Scheduler = system.toClassic.scheduler
      val pollTarget = if (state.lastTargetDuration > 0) state.lastTargetDuration else defaultPollSec
      val delaySec = if (state.lastSeq == 0) 0 else pollIntervalSecFromTarget(pollTarget)
      if (delaySec <= 0) {
        doFetch()
      } else {
        val delay = delaySec.seconds
        after(delay, scheduler)(Future.successful(())).flatMap(_ => doFetch())
      }
    }
    Source.lazySource { () =>
      log.info("[stream:hls] materialized playlistUrl={}", playlistUrl)
      heartbeatTask = Some(
        system.toClassic.scheduler.scheduleWithFixedDelay(heartbeatSec.seconds, heartbeatSec.seconds) { () =>
          log.info("[stream:hls] heartbeat lastSeq={} bytes={}", lastSeqOut.get(), bytesOut.get())
        }(ec)
      )
      Source.futureSource(
        resolveMediaPlaylistUrl(playlistUrl)(mat).map { url =>
          log.debug("[stream:hls] resolved media playlist={}", url)
          Source.unfoldAsync(HlsPlaylistPoller.initial(url))(s => step(s)(mat)).flatMapConcat { segments =>
            if (segments.isEmpty) Source.empty
            else Source(segments)
              .flatMapConcat { case (u, br) => fetchSegmentSource(u, br) }
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
            log.info("[stream:hls] complete")
          case Failure(ex) =>
            heartbeatTask.foreach(_.cancel())
            log.warn("[stream:hls] failed", ex)
        }
      }
    }
  }
}

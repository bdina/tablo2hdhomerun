package app.stream

import org.apache.pekko

import pekko.actor.typed.ActorSystem

import pekko.http.scaladsl

import scaladsl.Http
import scaladsl.model.{HttpRequest, StatusCodes}
import scaladsl.model.headers.{ByteRange, Range}
import scaladsl.unmarshalling.Unmarshal

import pekko.stream.OverflowStrategy
import pekko.stream.scaladsl.Source
import pekko.util.ByteString

import org.apache.pekko.pattern.after
import org.slf4j.LoggerFactory

import app.AppContext
import app.sys.LogConfig
import com.scalarometer.statistics.StopWatch

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.{Failure, Success}

object HlsBackend extends StreamBackend {
  val log = LoggerFactory.getLogger(this.getClass)
  override def name: String = "hls"
  val defaultPollSec: Int = 2

  type HlsError = app.stream.HlsError
  val HlsError = app.stream.HlsError

  private[stream] def pollOutcomeToFuture(
    outcome: HlsPlaylistPoller.Outcome
  ): Future[Option[(HlsPlaylistPoller.PollState, Seq[HlsPlaylistPoller.SegmentInfo])]] =
    outcome match {
      case HlsPlaylistPoller.Emit(next, segments) => Future.successful(Some((next, segments)))
      case HlsPlaylistPoller.Fail(err) => Future.failed(err)
    }

  type SegmentInfo = HlsPlaylistPoller.SegmentInfo
  type PollState = HlsPlaylistPoller.PollState
  type WithMaterializer[A] = pekko.stream.Materializer ?=> A

  override def stream(
    playlistUrl: String
  , label: String = ""
  , initialSeq: Int = 0
  , onSeqAdvanced: Int => Unit = _ => ()
  , liveEdgeCount: Int = HlsPlaylistPoller.liveEdgeSegmentCount
  , checkSessionAlive: () => Future[Boolean] = () => Future.successful(true)
  , metricsCollector: Option[StreamMetrics.HlsCollector] = None
  , initialEmittedKeys: Set[String] = Set.empty
  , onSegmentKeyCompleted: String => Unit = _ => ()
  )(using system: ActorSystem[?]): Source[ByteString, ?] = {
    given ec: scala.concurrent.ExecutionContext = system.executionContext
    given pekko.stream.Materializer = pekko.stream.SystemMaterializer(system).materializer
    val http = Http(system.classicSystem)
    val bytesOut = new java.util.concurrent.atomic.AtomicLong(0L)
    val lastSeqOut = new java.util.concurrent.atomic.AtomicInteger(initialSeq)
    val hlsConfig = AppContext.config.stream.hls
    val heartbeatSec = hlsConfig.heartbeatSec
    var heartbeatTask: Option[org.apache.pekko.actor.Cancellable] = None
    def fetchPlaylistBody(url: String): WithMaterializer[Future[String]] =
      fetchPlaylist(url, HlsPlaylistFetch.Validators()).flatMap {
        case Left(body) => Future.successful(body)
        case Right(_) => Future.failed(HlsError.UnexpectedNotModified)
      }
    def fetchPlaylist(
      url: String
    , validators: HlsPlaylistFetch.Validators
    ): WithMaterializer[Future[Either[String, Unit]]] = {
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
    def fetchPlaylistWithState(state: PollState): WithMaterializer[Future[(PollState, Option[String])]] = {
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
      val scheduler: pekko.actor.Scheduler = system.classicSystem.scheduler
      def retryAfter(retriesLeft: Int, message: String, nextSource: => Source[ByteString, ?]): Source[ByteString, ?] = {
        metricsCollector.foreach(_.onSegmentRetry())
        log.warn("[stream:hls] segment retry label={} reason={} retriesLeft={}", label, message, retriesLeft)
        Source
          .futureSource(after(segmentRetryDelay, scheduler)(Future.successful(nextSource)))
          .mapMaterializedValue(_ => pekko.NotUsed)
      }
      def failSource(error: HlsError): Source[ByteString, ?] = Source.failed(error)
      def attemptFetch(retriesLeft: Int): Source[ByteString, ?] =
        byteRange match {
          case Some((offset, length)) if !HlsSegmentFetch.isSafeByteRange(offset, length) =>
            log.warn("[stream:hls] skipping segment with invalid byte range label={} offset={} length={} url={}", label, offset, length, url)
            Source.empty[ByteString]
          case _ =>
            val request = HlsSegmentFetch.safeByteRange(byteRange) match {
              case Some((offset, length)) =>
                log.debug("[stream:hls] segment range fetch label={} offset={} length={} url={}", label, offset, length, url)
                HttpRequest(uri = url).addHeader(Range(ByteRange(offset, offset + length - 1)))
              case None =>
                HttpRequest(uri = url)
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
                response.entity.dataBytes.watchTermination { (_, done) =>
                  done.onComplete {
                    case Success(_) => ()
                    case Failure(_) => val _ = response.entity.discardBytes()
                  }(using ec)
                }
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
    def resolveMediaPlaylistUrl(url: String): WithMaterializer[Future[String]] =
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
              resolveMediaPlaylistUrl(resolved)
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
                fetchPlaylistBody(resolved).map(_ => resolved)
              case None => Future.successful(url)
            }
          }
        }
      }
    def pollIntervalMsFromState(state: PollState): Long =
      HlsPlaylistPoller.pollDelayMs(
        state
      , state.lastAdvanced
      , state.lastTargetDuration
      , defaultPollSec
      )
    def step(state: PollState): WithMaterializer[Future[Option[(PollState, Seq[SegmentInfo])]]] = {
      val doFetch: () => Future[Option[(PollState, Seq[SegmentInfo])]] = () => {
        val pollStart = StopWatch.Marker.now
        fetchPlaylistWithState(state)
          .andThen { case _ =>
            metricsCollector.foreach(_.onPlaylistPolled(pollStart.elapsed))
          }
          .map { case (fetchedState, bodyOpt) =>
            bodyOpt match {
              case None =>
                log.debug("[stream:hls] playlist not-modified label={} lastSeq={}", label, fetchedState.lastSeq)
                HlsPlaylistPoller.onPlaylistNotModified(fetchedState)
              case Some(body) =>
                val playlist = M3U8.parse(body)
                log.debug(
                  "[stream:hls] playlist label={} version={} target={} mediaSequence={} segments={} pollDelayMs={}"
                , label
                , playlist.version.map(_.toString).getOrElse("unknown")
                , playlist.targetDuration
                , playlist.mediaSequence
                , playlist.segments.size
                , pollIntervalMsFromState(fetchedState)
                )
                HlsPlaylistPoller.onPlaylist(
                  fetchedState
                , playlist
                , defaultPollSec
                )
            }
          }
          .recover { case ex =>
            val outcome = HlsPlaylistPoller.onFetchError(state, hlsConfig.pollFailuresMax, ex)
            outcome match {
              case HlsPlaylistPoller.Emit(_, _) =>
                log.warn("[stream:hls] poll failed retrying label={} error={}", label, ex.getMessage)
              case HlsPlaylistPoller.Fail(_) =>
                log.warn("[stream:hls] poll failed label={} error={}", label, ex.getMessage)
            }
            outcome
          }
          .flatMap { rawOutcome =>
            HlsPlaylistPoller.resolveStallOutcome(
              rawOutcome
            , () => checkSessionAlive()
            , state
            , hlsConfig.pollFailuresMax
            , hlsConfig.maxStallSec
            , onProbe = reason =>
                log.info(
                  "[stream:hls] playlist stalled, probing session liveness label={} reason={} stallPolls={} lastSeq={}"
                , label
                , reason.label
                , state.stallPolls + 1
                , state.lastSeq
                )
            )
          }
          .flatMap { outcome =>
            outcome match {
              case HlsPlaylistPoller.Fail(err) =>
                log.warn("[stream:hls] retune label={} reason={}", label, err.getMessage)
                Future.failed(err)
              case HlsPlaylistPoller.Emit(next, segments) =>
                if (next.stallPolls > state.stallPolls) {
                  metricsCollector.foreach(_.onStallPoll())
                }
                if (state.lastSeq == 0 && next.lastSeq > 0 && segments.isEmpty) {
                  log.info(
                    "[stream:hls] suppressing stale retune segments label={} throughSeq={}"
                  , label
                  , next.lastSeq - 1
                  )
                }
                if (next.fetchFailures > state.fetchFailures && segments.isEmpty) {
                  log.info(
                    "[stream:hls] playlist fetch retry ({}/{}) label={}"
                  , next.fetchFailures
                  , hlsConfig.pollFailuresMax
                  , label
                  )
                }
                segments.headOption.foreach { head =>
                  if (state.stallPolls >= HlsPlaylistPoller.sessionProbeMinPolls) {
                    log.info(
                      "[stream:hls] playlist resumed after stall label={} stallPolls={} seq={}"
                    , label
                    , state.stallPolls
                    , head.sequence
                    )
                  }
                  if (state.lastSeq > 0 && head.sequence > state.lastSeq) {
                    val gap = head.sequence - state.lastSeq
                    metricsCollector.foreach(_.onSequenceGap(gap))
                    log.warn(
                      "[stream:hls] sequence gap detected label={} expected={} received={} gap={}"
                    , label
                    , state.lastSeq
                    , head.sequence
                    , gap
                    )
                  }
                  if (!state.loggedFirstSegment) {
                    val lastSeq = segments.lastOption.map(_.sequence).getOrElse(head.sequence)
                    val rangeDesc = HlsSegmentFetch.safeByteRange(head.byteRange).map { case (o, l) => s" range=$o-${o + l - 1}" }.getOrElse("")
                    log.info(
                      "[stream:hls] emit label={} count={} firstSeq={} lastSeq={} firstUrl={}{}"
                    , label
                    , segments.size
                    , head.sequence
                    , lastSeq
                    , LogConfig.truncate(head.url)
                    , rangeDesc
                    )
                  }
                }
                Future.successful(Some((next, segments)))
            }
          }
      }
      val scheduler: pekko.actor.Scheduler = system.classicSystem.scheduler
      val delayMs = pollIntervalMsFromState(state)
      if (delayMs <= 0L) {
        doFetch()
      } else {
        val delay = delayMs.millis
        after(delay, scheduler)(Future.successful(())).flatMap(_ => doFetch())
      }
    }
    Source.lazySource { () =>
      log.info("[stream:hls] materialized label={} playlistUrl={}", label, playlistUrl)
      val lastArrivalNanos = new java.util.concurrent.atomic.AtomicLong(System.nanoTime())
      heartbeatTask = Some(
        system.classicSystem.scheduler.scheduleWithFixedDelay(heartbeatSec.seconds, heartbeatSec.seconds) { () =>
          log.debug("[stream:hls] heartbeat label={} lastSeq={} bytes={}", label, lastSeqOut.get(), bytesOut.get())
        }(using ec)
      )
      Source.futureSource(
        resolveMediaPlaylistUrl(playlistUrl).map { url =>
          log.debug("[stream:hls] resolved media playlist label={} url={}", label, url)
          Source.unfoldAsync(HlsPlaylistPoller.initial(url, initialSeq, liveEdgeCount, initialEmittedKeys))(s => step(s))
            .mapConcat(identity)
            .buffer(2, OverflowStrategy.backpressure)
            .flatMapConcat { seg =>
              val segStart = StopWatch.Marker.now
              val segStartNanos = segStart.timestamp
              val gapMs = (segStartNanos - lastArrivalNanos.getAndSet(segStartNanos)) / StopWatch.NanosPerMilli
              val segBytes = new java.util.concurrent.atomic.AtomicLong(0L)
              val expectedLengthOpt = HlsSegmentFetch.safeByteRange(seg.byteRange).map(_._2)

              val countedSource = fetchSegmentSource(seg.url, seg.byteRange)
                .map { chunk =>
                  val _ = segBytes.addAndGet(chunk.size)
                  chunk
                }
                .via(MpegTsSync.alignPackets)
                .map { chunk =>
                  val _ = bytesOut.addAndGet(chunk.size)
                  chunk
                }
                .watchTermination { (_, done) =>
                  done.onComplete {
                    case Success(_) =>
                      val fetchMs = segStart.elapsed
                      val totalSegBytes = segBytes.get()
                      lastSeqOut.set(seg.sequence + 1)
                      log.info(
                        "[stream:hls] segment completed label={} seq={} duration={}s bytes={} gapMs={} fetchMs={}"
                      , label
                      , seg.sequence
                      , seg.duration
                      , totalSegBytes
                      , gapMs
                      , fetchMs
                      )
                      val isShortRead = expectedLengthOpt.exists(expected => totalSegBytes < expected)
                      expectedLengthOpt.foreach { expected =>
                        if (totalSegBytes < expected) {
                          log.warn(
                            "[stream:hls] segment short read label={} seq={} expected={} received={} url={}"
                          , label
                          , seg.sequence
                          , expected
                          , totalSegBytes
                          , LogConfig.truncate(seg.url)
                          )
                        }
                      }
                      metricsCollector.foreach(_.onSegmentCompleted(gapMs, isShortRead, fetchMs))
                      onSegmentKeyCompleted(HlsPlaylistPoller.segmentKey(seg))
                      onSeqAdvanced(seg.sequence + 1)
                    case Failure(ex) =>
                      log.warn("[stream:hls] segment fetch aborted label={} seq={} error={}", label, seg.sequence, ex.getMessage)
                  }(using ec)
                }

              countedSource
            }
        }
      ).watchTermination { (_, done) =>
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
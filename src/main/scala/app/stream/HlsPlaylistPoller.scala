package app.stream

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._

object HlsPlaylistPoller {
  val liveEdgeSegmentCount: Int = 3
  val defaultRecoveryLiveEdgeSegmentCount: Int = 3
  val sessionProbeMinPolls: Int = 12
  val sessionProbeIntervalPolls: Int = 4

  def recoveryLiveEdgeSegmentCount: Int =
    scala.util.Try(app.AppContext.config.stream.hls.recoveryLiveEdgeSegments).getOrElse(defaultRecoveryLiveEdgeSegmentCount)

  final case class SegmentInfo(
    url: String
  , byteRange: Option[(Long, Long)]
  , sequence: Int
  , duration: Double
  )

  final case class PollState(
    baseUrl: String
  , lastSeq: Int
  , lastTargetDuration: Int
  , stallPolls: Int
  , fetchFailures: Int
  , loggedFirstSegment: Boolean
  , emittedKeys: Set[String] = Set.empty
  , lastAdvanced: Boolean = false
  , etag: Option[String] = None
  , lastModified: Option[String] = None
  , liveEdgeCount: Int = liveEdgeSegmentCount
  , lastSegmentDuration: Double = 0.0
  , stallStartNanos: Long = 0L
  )

  enum Outcome derives CanEqual {
    case Emit(next: PollState, segments: Seq[SegmentInfo])
    case Fail(error: Throwable)
  }
  export Outcome.{Emit, Fail}

  def initial(
    baseUrl: String
  , lastSeq: Int = 0
  , liveEdgeCount: Int = liveEdgeSegmentCount
  , initialEmittedKeys: Set[String] = Set.empty
  ): PollState =
    PollState(baseUrl, lastSeq, 0, 0, 0, false, emittedKeys = initialEmittedKeys, liveEdgeCount = liveEdgeCount)

  def shouldProbeSession(state: PollState): Boolean =
    state.stallPolls >= sessionProbeMinPolls &&
      ((state.stallPolls - sessionProbeMinPolls) % sessionProbeIntervalPolls == 0)

  def resolveStallOutcome(
    outcome: Outcome
  , checkSessionAlive: () => Future[Boolean]
  , state: PollState = PollState("", 0, 0, 0, 0, false)
  , maxFetchFailures: Int = 4
  , maxStallSec: Int = 0
  , nowNanos: Long = System.nanoTime()
  )(using ec: ExecutionContext): Future[Outcome] =
    outcome match {
      case Emit(next, segments)
          if segments.isEmpty && maxStallSec > 0 && next.stallStartNanos > 0L &&
            (nowNanos - next.stallStartNanos) >= maxStallSec.seconds.toNanos =>
        Future.successful(Fail(HlsError.PlaylistStalled(maxStallSec)))
      case emit @ Emit(next, segments) if segments.isEmpty && shouldProbeSession(next) =>
        checkSessionAlive().map { alive =>
          if (alive) emit
          else Fail(HlsError.SessionEnded)
        }
      case fail @ Fail(HlsError.PlaylistFetchError(status))
          if status == org.apache.pekko.http.scaladsl.model.StatusCodes.NotFound =>
        checkSessionAlive().map { alive =>
          val nextFailures = state.fetchFailures + 1
          if (alive && nextFailures < maxFetchFailures) {
            Emit(
              state.copy(
                fetchFailures = nextFailures
              , stallPolls = state.stallPolls + 1
              , lastAdvanced = false
              , stallStartNanos = if (state.stallStartNanos > 0L) state.stallStartNanos else nowNanos
              )
            , Seq.empty
            )
          } else if (!alive) {
            Fail(HlsError.SessionEnded)
          } else {
            fail
          }
        }
      case other =>
        Future.successful(other)
    }

  val pollDelayMinSec: Int = 1
  val pollDelayMaxSec: Int = 10
  val pollDelayMinMs: Long = 500L
  val pollDelayMaxMs: Long = 10000L

  def pollDelaySec(
    state: PollState
  , playlistAdvanced: Boolean
  , targetDurationSec: Int
  , defaultPollSec: Int
  ): Int = {
    if (state.lastSeq == 0) 0
    else {
      val target = if (targetDurationSec > 0) targetDurationSec else defaultPollSec
      val raw = if (playlistAdvanced) target else (target / 2).max(1)
      raw.max(pollDelayMinSec).min(pollDelayMaxSec)
    }
  }

  def pollDelayMs(
    state: PollState
  , playlistAdvanced: Boolean
  , targetDurationSec: Int
  , defaultPollSec: Int
  ): Long = {
    if (state.lastSeq == 0) 0L
    else if (!playlistAdvanced) pollDelayMinMs
    else if (state.lastSegmentDuration.isFinite && state.lastSegmentDuration > 0.0) {
      val rawMs = (state.lastSegmentDuration * 750.0).toLong
      rawMs.max(pollDelayMinMs).min(pollDelayMaxMs)
    } else {
      pollDelaySec(state, playlistAdvanced, targetDurationSec, defaultPollSec).toLong * 1000L
    }
  }

  def segmentKey(info: SegmentInfo): String =
    info.byteRange match {
      case Some((offset, length)) => s"${info.sequence}|${info.url}|$offset|$length"
      case None => s"${info.sequence}|${info.url}"
    }

  private def segmentInfos(baseUrl: String, playlist: M3U8.Playlist): Seq[SegmentInfo] =
    playlist.segments.zipWithIndex.flatMap { case (seg, idx) =>
      if (seg.byteRange.exists(_._2 <= 0)) None
      else
        Some(
          SegmentInfo(
            url = M3U8.resolveSegmentUri(seg.uri, baseUrl)
          , byteRange = seg.byteRange
          , sequence = playlist.mediaSequence + idx
          , duration = seg.duration
          )
        )
    }

  val sequenceResetThreshold: Int = 10

  def onPlaylist(
    state: PollState
  , playlist: M3U8.Playlist
  , defaultPollSec: Int
  , nowNanos: Long = System.nanoTime()
  ): Outcome = {
    if (playlist.isEndList) {
      Fail(HlsError.SessionEnded)
    } else {
      val allSegments = segmentInfos(state.baseUrl, playlist)
      val newLastSeq = playlist.mediaSequence + playlist.segments.size
      val maxKnownEmittedSeq =
        allSegments.filter(seg => state.emittedKeys.contains(segmentKey(seg))).map(_.sequence).maxOption
      val hasKnownEmittedKey = maxKnownEmittedSeq.isDefined
      val isReset =
        state.lastSeq > 0 &&
        allSegments.lastOption.exists { lastSeg =>
          lastSeg.sequence < (state.lastSeq - math.max(sequenceResetThreshold, allSegments.size)) ||
            (playlist.mediaSequence <= 1 && newLastSeq < state.lastSeq && !hasKnownEmittedKey)
        }
      val isFirstPoll = state.lastSeq == 0 || isReset
      val effectiveLastSeq = if (isReset) 0 else state.lastSeq
      val candidates = if (isFirstPoll) {
        val count = if (isReset) 1 else state.liveEdgeCount
        maxKnownEmittedSeq match {
          case Some(knownSeq) => allSegments.filter(_.sequence > knownSeq).takeRight(count)
          case None => allSegments.takeRight(count)
        }
      } else {
        allSegments.filter(_.sequence >= effectiveLastSeq)
      }
      val segments = candidates.filter(seg => !state.emittedKeys.contains(segmentKey(seg)))
      val advanced = segments.nonEmpty || (!hasKnownEmittedKey && (isFirstPoll || newLastSeq > effectiveLastSeq))
      val nextStall = if (advanced) 0 else state.stallPolls + 1
      val nextStallStartNanos =
        if (nextStall == 0) 0L
        else if (state.stallStartNanos > 0L) state.stallStartNanos
        else nowNanos
      val nextTarget = if (playlist.targetDuration > 0) playlist.targetDuration else defaultPollSec
      val nextSegDuration = allSegments.lastOption.map(_.duration).filter(_ > 0.0).getOrElse(state.lastSegmentDuration)
      val playlistKeys = allSegments.map(segmentKey).toSet
      val newEmittedKeys = (state.emittedKeys ++ segments.map(segmentKey)).intersect(playlistKeys)
      val nextLastSeq = if (isReset) newLastSeq else math.max(state.lastSeq, newLastSeq)
      Emit(
        state.copy(
          lastSeq = nextLastSeq
        , lastTargetDuration = nextTarget
        , stallPolls = nextStall
        , fetchFailures = 0
        , loggedFirstSegment = state.loggedFirstSegment || segments.nonEmpty
        , emittedKeys = newEmittedKeys
        , lastAdvanced = advanced
        , lastSegmentDuration = nextSegDuration
        , stallStartNanos = nextStallStartNanos
        )
      , segments
      )
    }
  }

  def isFatalError(error: Throwable): Boolean =
    error match {
      case HlsError.PlaylistFetchError(status) =>
        import org.apache.pekko.http.scaladsl.model.StatusCodes
        status == StatusCodes.NotFound ||
        status == StatusCodes.Gone ||
        status == StatusCodes.Unauthorized ||
        status == StatusCodes.Forbidden
      case _ => false
    }

  def onFetchError(state: PollState, maxFetchFailures: Int, error: Throwable): Outcome =
    if (isFatalError(error)) {
      Fail(error)
    } else {
      val n = state.fetchFailures + 1
      if (n >= maxFetchFailures) {
        Fail(HlsError.PollExhausted)
      } else {
        Emit(state.copy(fetchFailures = n), Seq.empty)
      }
    }

  def onFetchError(state: PollState, maxFetchFailures: Int): Outcome =
    onFetchError(state, maxFetchFailures, HlsError.PollExhausted)

  def onPlaylistNotModified(
    state: PollState
  , nowNanos: Long = System.nanoTime()
  ): Outcome =
    Emit(
      state.copy(
        stallPolls = state.stallPolls + 1
      , fetchFailures = 0
      , lastAdvanced = false
      , stallStartNanos = if (state.stallStartNanos > 0L) state.stallStartNanos else nowNanos
      )
    , Seq.empty
    )
}
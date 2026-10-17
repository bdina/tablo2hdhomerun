package app.stream

object HlsPlaylistPoller {
  val liveEdgeSegmentCount: Int = 3
  val defaultRecoveryLiveEdgeSegmentCount: Int = 3
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
  , failOnStall: Boolean = false
  )

  sealed trait Outcome
  final case class Emit(next: PollState, segments: Seq[SegmentInfo]) extends Outcome
  final case class Fail(error: Throwable) extends Outcome

  def initial(
    baseUrl: String
  , lastSeq: Int = 0
  , liveEdgeCount: Int = liveEdgeSegmentCount
  , failOnStall: Boolean = false
  ): PollState =
    PollState(baseUrl, lastSeq, 0, 0, 0, false, liveEdgeCount = liveEdgeCount, failOnStall = failOnStall)

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
    if (state.lastSeq == 0 || state.failOnStall) 0
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
    if (state.lastSeq == 0 || state.failOnStall) 0L
    else if (state.lastSegmentDuration > 0.0 && state.lastSegmentDuration < targetDurationSec.toDouble) {
      val segMs = (state.lastSegmentDuration * 1000.0).toLong
      val rawMs = if (playlistAdvanced) (segMs * 3L) / 4L else (segMs * 2L) / 5L
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
  ): Outcome = {
    if (playlist.isEndList) {
      Fail(HlsBackend.HlsError.SessionEnded)
    } else {
      val allSegments = segmentInfos(state.baseUrl, playlist)
      val newLastSeq = playlist.mediaSequence + playlist.segments.size
      val isReset =
        state.lastSeq > 0 &&
        allSegments.nonEmpty &&
        (allSegments.last.sequence < (state.lastSeq - math.max(sequenceResetThreshold, allSegments.size)) ||
          (playlist.mediaSequence <= 1 && allSegments.last.sequence < state.lastSeq))
      val isFirstPoll = state.lastSeq == 0 || isReset
      val effectiveLastSeq = if (isReset) 0 else state.lastSeq
      val candidates = if (isFirstPoll) {
        val count = if (isReset) 1 else state.liveEdgeCount
        allSegments.takeRight(count)
      } else {
        allSegments.filter(_.sequence >= effectiveLastSeq)
      }
      val segments = candidates.filter(seg => !state.emittedKeys.contains(segmentKey(seg)))
      val advanced = isFirstPoll || segments.nonEmpty || (newLastSeq > effectiveLastSeq)
      if (state.failOnStall && !advanced) {
        Fail(HlsBackend.HlsError.PlaylistStalled)
      } else {
        // stall handling lives in ResilientHlsSource's StallWatchdog
        val nextStall = if (advanced) 0 else state.stallPolls + 1
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
          , failOnStall = false
          )
        , segments
        )
      }
    }
  }

  def isFatalError(error: Throwable): Boolean =
    error match {
      case HlsBackend.HlsError.PlaylistFetchError(status) =>
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
        Fail(HlsBackend.HlsError.PollExhausted)
      } else {
        Emit(state.copy(fetchFailures = n), Seq.empty)
      }
    }

  def onFetchError(state: PollState, maxFetchFailures: Int): Outcome =
    onFetchError(state, maxFetchFailures, HlsBackend.HlsError.PollExhausted)

  def onPlaylistNotModified(state: PollState): Outcome =
    if (state.failOnStall) {
      Fail(HlsBackend.HlsError.PlaylistStalled)
    } else {
      Emit(
        state.copy(
          stallPolls = state.stallPolls + 1
        , fetchFailures = 0
        , lastAdvanced = false
        )
      , Seq.empty
      )
    }
}
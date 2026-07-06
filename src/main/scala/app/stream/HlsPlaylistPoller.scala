package app.stream

object HlsPlaylistPoller {
  val liveEdgeSegmentCount: Int = 3

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
  )

  sealed trait Outcome
  final case class Emit(next: PollState, segments: Seq[SegmentInfo]) extends Outcome
  final case class Fail(error: Throwable) extends Outcome

  def initial(baseUrl: String): PollState =
    PollState(baseUrl, 0, 0, 0, 0, false)

  val pollDelayMinSec: Int = 1
  val pollDelayMaxSec: Int = 10

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

  def segmentKey(info: SegmentInfo): String =
    info.byteRange match {
      case Some((offset, length)) => s"${info.sequence}|${info.url}|$offset|$length"
      case None => s"${info.sequence}|${info.url}"
    }

  private def segmentInfos(baseUrl: String, playlist: M3U8.Playlist): Seq[SegmentInfo] =
    playlist.segments.zipWithIndex.map { case (seg, idx) =>
      SegmentInfo(
        url = M3U8.resolveSegmentUri(seg.uri, baseUrl)
      , byteRange = seg.byteRange
      , sequence = playlist.mediaSequence + idx
      , duration = seg.duration
      )
    }

  def onPlaylist(
    state: PollState
  , playlist: M3U8.Playlist
  , maxStallPolls: Int
  , defaultPollSec: Int
  ): Outcome = {
    if (playlist.isEndList) {
      Fail(HlsBackend.HlsError.SessionEnded)
    } else {
      val isFirstPoll = state.lastSeq == 0
      val allSegments = segmentInfos(state.baseUrl, playlist)
      val candidates = if (isFirstPoll)
        allSegments.takeRight(liveEdgeSegmentCount)
      else
        allSegments.filter(_.sequence >= state.lastSeq)
      val segments = candidates.filter(seg => !state.emittedKeys.contains(segmentKey(seg)))
      val newLastSeq = playlist.mediaSequence + playlist.segments.size
      val advanced = newLastSeq > state.lastSeq
      val nextStall = if (advanced) 0 else state.stallPolls + 1
      if (!advanced && nextStall >= maxStallPolls) {
        Fail(HlsBackend.HlsError.PlaylistStall)
      } else {
        val nextTarget = if (playlist.targetDuration > 0) playlist.targetDuration else defaultPollSec
        val playlistKeys = allSegments.map(segmentKey).toSet
        val newEmittedKeys = (state.emittedKeys ++ segments.map(segmentKey)).intersect(playlistKeys)
        Emit(
          state.copy(
            lastSeq = newLastSeq
          , lastTargetDuration = nextTarget
          , stallPolls = nextStall
          , fetchFailures = 0
          , loggedFirstSegment = state.loggedFirstSegment || segments.nonEmpty
          , emittedKeys = newEmittedKeys
          , lastAdvanced = advanced
          )
          , segments
        )
      }
    }
  }

  def onFetchError(state: PollState, maxFetchFailures: Int): Outcome = {
    val n = state.fetchFailures + 1
    if (n >= maxFetchFailures) {
      Fail(HlsBackend.HlsError.PollExhausted)
    } else {
      Emit(state.copy(fetchFailures = n), Seq.empty)
    }
  }

  def onPlaylistNotModified(state: PollState, maxStallPolls: Int): Outcome = {
    val nextStall = state.stallPolls + 1
    if (nextStall >= maxStallPolls) {
      Fail(HlsBackend.HlsError.PlaylistStall)
    } else {
      Emit(
        state.copy(
          stallPolls = nextStall
        , fetchFailures = 0
        , lastAdvanced = false
        )
      , Seq.empty
      )
    }
  }
}
package app.stream

object HlsPlaylistPoller {
  type SegmentInfo = (String, Option[(Long, Long)])

  final case class PollState(
    baseUrl: String
  , lastSeq: Int
  , lastTargetDuration: Int
  , stallPolls: Int
  , fetchFailures: Int
  , loggedFirstSegment: Boolean
  )

  sealed trait Outcome
  final case class Emit(next: PollState, segments: Seq[SegmentInfo]) extends Outcome
  final case class Fail(error: Throwable) extends Outcome

  def initial(baseUrl: String): PollState =
    PollState(baseUrl, 0, 0, 0, 0, false)

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
      val startIndex = if (isFirstPoll)
        (playlist.segments.size - 3).max(0)
      else
        (state.lastSeq - playlist.mediaSequence).max(0)
      val toEmit = playlist.segments.drop(startIndex)
      val segments = toEmit.map(seg => (M3U8.resolveSegmentUri(seg.uri, state.baseUrl), seg.byteRange))
      val newLastSeq = playlist.mediaSequence + playlist.segments.size
      val advanced = newLastSeq > state.lastSeq
      val nextStall = if (advanced) 0 else state.stallPolls + 1
      if (!advanced && nextStall >= maxStallPolls) {
        Fail(HlsBackend.HlsError.PlaylistStall)
      } else {
        val nextTarget = if (playlist.targetDuration > 0) playlist.targetDuration else defaultPollSec
        Emit(
          state.copy(
            lastSeq = newLastSeq
          , lastTargetDuration = nextTarget
          , stallPolls = nextStall
          , fetchFailures = 0
          , loggedFirstSegment = state.loggedFirstSegment || segments.nonEmpty
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
}

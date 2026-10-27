# Session Manager Architecture Documentation

## Overview

The Session Manager keeps track of Tablo tuner/channel leases for the 4th-gen proxy path. When a media client requests
`GET /channel/{channelId}`, the proxy acquires a shared session for that channel instead of opening a new Tablo watch
session per HTTP client.

A mapping is maintained of `channelId` → Tablo session metadata and a Pekko Streams `BroadcastHub` source. The first
request for a channel establishes the upstream MPEG-TS pipeline; subsequent clients attach to the same hub. `BroadcastHub`
backpressures the producer when the slowest subscriber is 1024 elements behind.

```text
Tablo4thGen.Channel.SessionRunner  --->  SessionManager  --->  N proxy clients
(watch / keepalive / retune)              (Actor)               (BroadcastHub outlet)
```

**SessionManager** only tracks leases and hub fan-out. It does not call Tablo HTTP.

**Tablo4thGen** owns Tablo communication (`POST /watch`, keepalive, `DELETE`), builds the upstream stream, materializes
the `BroadcastHub`, then checks the hub into SessionManager for reuse.

## Goals

### Playback Resilience & End-User Experience
- **Uninterrupted Plex Playback**: Keep Plex clients playing during periods of OTA channel and tuner instability without crashing or requiring manual viewer intervention.
- **Warm Client Keepalive with Frozen Video & Decoupled Audio**: When upstream segments pause for > 750ms or media clock falls behind wall clock, `FreezeFramePacer` emits live-compatible `PAT`/`PMT`, MPEG-2 repeat/skip P-frames (or H.264 keepalive units), and silent AC-3/AAC frames every 100ms. Plex's ingest grabber (`-codec copy -f ssegment`) continues writing 1s segments of the frozen picture while the existing Tablo `/watch` session is preserved for up to 45s. On fast-attach during `idle-grace`, `AlignToIFrameStage` decouples audio alignment—emitting audio packets immediately upon their first `PUSI = 1` PES packet rather than dropping them while waiting for a video I-frame—preventing FFmpeg's `avformat_find_stream_info` probe from exhausting its 1.5 MB buffer and locking into a Video-Only transcode pipeline (Failure Mode G). In addition, cached locked Sequence Headers (`00 00 01 B3`) are injected on GOP boundaries or forced alignment to prevent `Invalid frame dimensions 0x0`.
- **Live-Edge Resumption (No Seen-Content Replay)**: Fresh segments arrive from a new Tablo segment file after a cold tune; a single resume prefix (multi-PID discontinuity for video, audio, PCR, and null PIDs + cached PAT/PMT) resets decoder timelines without rewind loops or replayed video.
- **Viewer-Controlled Persistence**: By default (`STREAM_RECOVERY_TIMEOUT_SEC=0`), keep the channel tuned indefinitely while a client is attached; if a finite `STREAM_RECOVERY_TIMEOUT_SEC` is configured, cleanly terminate the stream after that outage duration and remove the session manager entry immediately so the channel is re-tunable right away.

### Tuner & Session Management
- One Tablo player session per channel while any proxy client (or idle grace) holds it
- Multiplex MPEG-TS to N HDHomeRun clients via `BroadcastHub`
- Seamless keepalive / playlist change / retune for attached clients (no client reconnect required)
- Clear logs: `channelId`, `clientId`, `tabloToken` (truncated), attached client count, session state

## Non-goals (v1)

- Legacy Tablo SessionManager integration
- SessionManager calling Tablo HTTP itself
- Per-channel typed actors for the session runner
- Long warm pools beyond idle grace
- Per-client resilience after the hub

## Decisions (v1)

| Topic | Choice |
|-------|--------|
| Hub materialization | Tablo4thGen materializes upstream + `BroadcastHub`, then `CheckIn` |
| Retune | Two-tier recovery governed by `WatchSession.decideRecovery`: Tier 1 soft recovery retains existing `/watch` session for up to `softRecoveryThresholdSec = 45s` (`STREAM_STALL_TIMEOUT_SEC = 45s`), fast-polling `pls.m3u8` every 500ms when stalled with `failOnStallPoll = false` (watchdog `softRecoveryTimeoutSec = 15s`). Upstream trickle collapse watchdog (`HlsSegmentFetch.TrickleState`) tracks rolling EMA throughput (`alpha = 0.30`, `>= 200 KB/s` baseline); segments `< 15%` baseline suppress `onSeqAdvanced` and trip `SegmentTrickleCollapse` after 5 consecutive occurrences. Escalates to Tier 2 cold retune (DELETE + POST /watch with reset `lastSeqRef`) only if stall exceeds 45s, trickle collapse occurs, or session expires; flap dampening backoff (capped at 4s) if stalls recur within 30s of recovery; hub stays up |
| Stream priming | Dynamic PAT/PMT & multi-PID discontinuity packets prepended on client attach via `MpegTsSync`; decoupled audio alignment emits audio immediately on first `PUSI=1` with valid syncword; locked Sequence Headers (`lockedSeqHeader`) injected on GOP boundary or forced alignment; continuous monotonic PCR/PTS/DTS timeline normalization and outbound CC rewriting in `MpegTsSync.cacheFlow` & `FreezeFramePacer`; persisted across session lifecycles in `SessionManager.channelHeaderCache` |
| Client identity | Per-request UUID for Acquire/Release and logging |
| Scope | 4th gen only |
| Idle grace | 180s default (configurable via `SESSION_IDLE_GRACE_SEC`) after last client leaves (channel surfing / app reload) |
| Pre-roll keepalive | Immediate HTTP 200 chunked response with MPEG-TS null packets (PID 0x1FFF, ~100 kbps) while cold-tuning, switching smoothly to live stream upon `CheckIn` with primed PAT/PMT headers |
| BroadcastHub buffer | 1024 elements |
| Session runner | Functions/object inside `Tablo4thGen.Channel` (not a typed actor) |
| Upstream failure | Runner `watchTermination` → teardown → `UpstreamEnded(channelId, leaseId)` → entry removed immediately; clients' streams complete |

## Topology

```text
GET /channel/{id}
       │
       ▼
SessionManager ──Acquire / Release──► Map[channelId → Session]
       ▲
       │ CheckIn(hubSource, teardown, cachedHeaders)
       │
Tablo4thGen.Channel.SessionRunner
  POST /watch
       │
       ▼
  StreamBackend → StallWatchdog → ResilientHlsSource (FreezeFramePacer) → MpegTsSync.cacheFlow → KillSwitch → watchTermination → BroadcastHub.sink(1024)
                         ▲
                         │
              keepalive / playlist change / near-expiry retune
```

Retune and keepalive run inside the single session runner for that channel. They restart the inner `streamFactory`
without shutting the outer KillSwitch or the hub. During transient upstream pauses (`> 750ms`), `FreezeFramePacer` emits
live-compatible `PAT`/`PMT`, MPEG-2 repeat/skip P-frames (or H.264 keepalive units), and silent AC-3 (`0x0B77` with valid CRC-16) or
ADTS AAC frames every 100ms so downstream Plex ingest grabbers (`-codec copy -f ssegment`) keep segmenting without timing out.
Pacer activation is strictly **video-anchored** (`lastLiveDataNanos` updates solely on emitted live video packets `!freezeActive && !droppingVideoSlice`),
so incoming PAT/PMT, audio, or discarded packets cannot reset the pacer. Furthermore, `CacheStage` anchors a real-time media clock
(`mediaClockAnchorPts`, `mediaClockAnchorNanos`): when upstream segment trickle causes `lastOutputPts` to fall behind wall clock by
`>= freezeThreshold` (`67,500` ticks = 750ms), it synthesizes catch-up freeze frames + silent audio (up to 6 frames per 100ms tick) while
seamlessly rebasing subsequent live video timestamps.
During dropouts, `HlsPlaylistPoller` polls `pls.m3u8` every 500ms when unchanged (`lastAdvanced == false`), and `WatchSession.decideRecovery`
preserves the `/watch` session up to `softRecoveryThresholdSec = 45s` (`failOnStallPoll = false`, `softRecoveryTimeoutSec = 15s`),
removing the 16s clean-keyframe retune failure from `c57c238` so the existing hardware tuner lock can ride out the RF fade.
Concurrently, `HlsSegmentFetch.TrickleState` monitors rolling EMA throughput (`alpha = 0.30`, `>= 200 KB/s` baseline): segments falling below 15%
throughput are suppressed from calling `onSeqAdvanced` (preventing `WatchSession.FlapState` from falsely clearing stall tracking) and trip
`HlsError.SegmentTrickleCollapse` after 5 consecutive occurrences to initiate recovery.
If a prolonged outage (> 45s) requires a Tier 2 cold retune, HLS media sequence numbers (`lastSeqRef`) are reset for the fresh session
to resume directly at the live edge (`STREAM_RECOVERY_LIVE_EDGE_SEGMENTS`, default 3).
`MpegTsSync.cacheFlow` performs surgical slice-level sanitization (`droppingVideoSlice`) resuming on valid MPEG-2 slice start codes
(`00 00 01 [01..maxSliceRow]`), clamps slice vertical positions (`<= maxSliceRow`, e.g. 68 on 1080i) to eliminate VAAPI `slice below image`
crashes, locks confirmed sequence header geometry (cached in `MpegTsSync.CachedHeaders.lockedSeqHeader`), gates audio on valid syncwords
(`0x0B77`/`0xFFF`), rewrites outbound CCs per PID to prevent demuxer discontinuities, and maintains monotonic PCR/PTS/DTS timelines (`ptsDeltaTicks`).
When clients attach, `MpegTsSync.primeClientSource` primes the stream with cached PAT, PMT, and discontinuity packets and gates
attachment via `MpegTsSync.alignToIFrame`. `AlignToIFrameStage` decouples audio alignment from video keyframe alignment, immediately emitting
each audio PID on its first `PUSI = 1` PES packet so FFmpeg's `avformat_find_stream_info` probe buffer does not starve of audio in the first 1.5 MB
during `BroadcastHub` fast-attach inrush (preventing Failure Mode G video-only lock). Only dropped video packets count toward `maxDroppedPackets` (8000).
If attachment occurs on a GOP header (`00 00 01 B8`) missing an in-band Sequence Header or reaches forced alignment, `AlignToIFrameStage` injects
`FreezeFramePacer.synthesizeSequenceHeaderPacket` to ensure valid frame dimensions.
Known channel PAT/PMT tables are persisted in `SessionManager.channelHeaderCache` across session lifecycles so that subsequent tunes
(even after idle grace expires) immediately have format tables ready. On brand new cold starts, `SessionRunner` coordinates check-in until
the first segment's headers are parsed (or up to 1.5s fallback), guaranteeing clients receive stream metadata before format probing
timeouts fire. The KillSwitch and upstream `watchTermination` are used for teardown (refcount 0 and idle grace expired, or upstream ended
after recovery timeout).

## State machine

```text
(absent) --Acquire--> Opening --CheckIn--> Live --last Release--> IdleGrace
              │                      ▲        │                       │
              │                      │        │ UpstreamEnded         │ UpstreamEnded / 180s
              └--AcquireFailed--> (absent)    ▼                       ▼
                                           teardown                teardown
                                              │                       │
                                              ▼                       ▼
                                           (absent)                (absent)
```

| State | Behavior |
|-------|----------|
| **Opening** | One Tablo open in flight; further Acquires enqueue waiters |
| **Live** | `hubSource` available; `clientCount ≥ 1`; keepalive running in runner |
| **IdleGrace** | `clientCount == 0`; 180s timer armed (configurable); Acquire cancels timer and attaches (no new `/watch`) |
| **absent** | No map entry; next Acquire starts Opening |

## Scala ADT / Behavior sketch

Placement sketch (names can move during implementation):

```text
app.tuner.Tablo4thGen.Channel.SessionManager   // typed actor
app.tuner.Tablo4thGen.Channel.SessionRunner    // object / functions
```

Style matches existing 4th-gen actors (`LineupActor`): nested `Request` / `Response` / `Command`, `Behaviors.setup` +
`Behaviors.receiveMessage`, mutable map inside setup.

### Public protocol

```scala
object SessionManager {
  val IdleGrace: FiniteDuration = 180.seconds
  val BroadcastHubBufferSize: Int = 1024

  sealed trait Request

  object Request {
    case class Acquire(
      channelId: String
    , clientId: String
    , replyTo: ActorRef[Response.Acquire]
    ) extends Request

    case class Release(
      channelId: String
    , clientId: String
    ) extends Request

    /** Optional: refresh tuner capacity from GET /server/info (route or runner). */
    case class SetTotalTuners(total: Int) extends Request
  }

  sealed trait Response

  object Response {
    sealed trait Acquire
    case class Attached(source: Source[ByteString, NotUsed]) extends Acquire
    case class Rejected(reason: RejectReason) extends Acquire
  }

  sealed trait RejectReason
  object RejectReason {
    case object NoTuners extends RejectReason
    case class Failed(cause: Throwable) extends RejectReason
  }

  case class TabloSessionMeta(
    token: String
  , expires: Option[java.time.Instant]
  , keepalive: Option[Int]
  , playlistUrl: String
  , leaseId: String = ""
  )

  /** Internal + runner callbacks. Extends Request so one Behavior handles all messages. */
  sealed trait Command extends Request

  object Command {
    case class CheckIn(
      channelId: String
    , meta: TabloSessionMeta
    , hubSource: Source[ByteString, NotUsed]
    , teardown: () => Unit
    ) extends Command

    case class AcquireFailed(
      channelId: String
    , cause: Throwable
    ) extends Command

    /** Fired by scheduleOnce when IdleGrace expires. */
    case class GraceExpired(channelId: String) extends Command

    /** Fired when upstream completes or fails, triggering immediate teardown and removal. */
    case class UpstreamEnded(channelId: String, leaseId: String) extends Command
  }
}
```

When upstream terminates (recovery timeout or failure), SessionRunner tears down and sends `UpstreamEnded(channelId, leaseId)`.
SessionManager immediately tears down and removes the entry, making the channel immediately re-tunable without waiting for
idle grace expiry.

### Internal session state

```scala
object SessionManager {
  private sealed trait SessionState

  private object SessionState {
    case class Waiter(
      clientId: String
    , replyTo: ActorRef[Response.Acquire]
    )

    case class Opening(
      waiters: Vector[Waiter]
    ) extends SessionState

    case class Live(
      meta: TabloSessionMeta
    , hubSource: Source[ByteString, NotUsed]
    , teardown: () => Unit
    , clientIds: Set[String]
    ) extends SessionState

    case class IdleGrace(
      meta: TabloSessionMeta
    , hubSource: Source[ByteString, NotUsed]
    , teardown: () => Unit
    , graceTimer: pekko.actor.Cancellable
    , drainSwitch: Option[UniqueKillSwitch] = None
    ) extends SessionState
  }
}
```

`clientIds` (rather than a bare counter) makes Release idempotent if termination fires twice and keeps logs precise.
Occupancy for tuner checks is `sessions.size` (one slot per map entry), not `clientIds.size`.

### Behavior

```scala
object SessionManager {
  def apply(
    startRunner: (String, ActorRef[Request]) => Unit
  , totalTuners: Int = 4
  ): Behavior[Request] = Behaviors.setup { context =>
    var sessions = Map.empty[String, SessionState]
    var tuners = totalTuners

    def occupied: Int = sessions.size

    def attachClient(
      channelId: String
    , clientId: String
    , hubSource: Source[ByteString, NotUsed]
    , replyTo: ActorRef[Response.Acquire]
    ): Unit = {
      context.log.info(
        "[session] attach channelId={} clientId={} occupied={}"
      , channelId, clientId, occupied
      )
      replyTo ! Response.Attached(hubSource)
    }

    def rejectWaiters(waiters: Vector[SessionState.Waiter], reason: RejectReason): Unit =
      waiters.foreach(w => w.replyTo ! Response.Rejected(reason))

    def enterIdleGrace(channelId: String, live: SessionState.Live): SessionState.IdleGrace = {
      val drainSwitch = {
        import pekko.stream.scaladsl.{Keep, Sink}
        import pekko.stream.KillSwitches
        implicit val classicSystem: org.apache.pekko.actor.ActorSystem = context.system.classicSystem
        val drainGraph = live.hubSource.viaMat(KillSwitches.single)(Keep.right).to(Sink.ignore)
        Try(drainGraph.run()) match {
          case Success(switch) => Some(switch)
          case Failure(ex) =>
            context.log.debug("[session] idle-grace drain switch error channelId={}", channelId, ex)
            None
        }
      }
      val timer = context.scheduleOnce(IdleGrace, context.self, Command.GraceExpired(channelId))
      context.log.info("[session] idle-grace channelId={} token={}", channelId, LogConfig.truncate(live.meta.token))
      SessionState.IdleGrace(live.meta, live.hubSource, live.teardown, timer, drainSwitch)
    }

    def teardownAndRemove(channelId: String, teardown: () => Unit): Unit = {
      sessions.get(channelId).foreach {
        case idle: SessionState.IdleGrace => idle.drainSwitch.foreach(_.shutdown())
        case _ => ()
      }
      sessions -= channelId
      Try(teardown()).failed.foreach { ex =>
        context.log.warn("[session] teardown failed channelId={}", channelId, ex)
      }
      context.log.info("[session] removed channelId={} occupied={}", channelId, occupied)
    }

    Behaviors.receiveMessage {
      case Request.SetTotalTuners(total) =>
        tuners = math.max(1, total)
        Behaviors.same

      case Request.Acquire(channelId, clientId, replyTo) =>
        sessions.get(channelId) match {
          case None =>
            if (occupied >= tuners) {
              replyTo ! Response.Rejected(RejectReason.NoTuners)
            } else {
              sessions += channelId -> SessionState.Opening(Vector(SessionState.Waiter(clientId, replyTo)))
              context.log.info("[session] opening channelId={} clientId={} occupied={}", channelId, clientId, occupied)
              startRunner(channelId, context.self)
            }
            Behaviors.same

          case Some(SessionState.Opening(waiters)) =>
            sessions += channelId -> SessionState.Opening(waiters :+ SessionState.Waiter(clientId, replyTo))
            context.log.info("[session] enqueue channelId={} clientId={} waiters={}", channelId, clientId, waiters.size + 1)
            Behaviors.same

          case Some(live: SessionState.Live) =>
            if (live.clientIds.contains(clientId)) {
              replyTo ! Response.Attached(live.hubSource)
            } else {
              sessions += channelId -> live.copy(clientIds = live.clientIds + clientId)
              attachClient(channelId, clientId, live.hubSource, replyTo)
            }
            Behaviors.same

          case Some(idle: SessionState.IdleGrace) =>
            idle.graceTimer.cancel()
            idle.drainSwitch.foreach(_.shutdown())
            sessions += channelId -> SessionState.Live(idle.meta, idle.hubSource, idle.teardown, Set(clientId))
            context.log.info("[session] grace-cancel channelId={} clientId={}", channelId, clientId)
            attachClient(channelId, clientId, idle.hubSource, replyTo)
            Behaviors.same
        }

      case Request.Release(channelId, clientId) =>
        sessions.get(channelId) match {
          case Some(live: SessionState.Live) if live.clientIds.contains(clientId) =>
            val remaining = live.clientIds - clientId
            if (remaining.nonEmpty) {
              sessions += channelId -> live.copy(clientIds = remaining)
              context.log.info(
                "[session] release channelId={} clientId={} clients={}"
              , channelId, clientId, remaining.size
              )
            } else {
              sessions += channelId -> enterIdleGrace(channelId, live.copy(clientIds = Set.empty))
            }
          case Some(_: SessionState.Opening) =>
            context.log.debug("[session] release ignored opening channelId={} clientId={}", channelId, clientId)
          case Some(_: SessionState.IdleGrace) =>
            context.log.debug("[session] release ignored idle-grace channelId={} clientId={}", channelId, clientId)
          case None =>
            context.log.debug("[session] release ignored absent channelId={} clientId={}", channelId, clientId)
          case Some(live: SessionState.Live) =>
            context.log.debug("[session] release unknown client channelId={} clientId={}", channelId, clientId)
        }
        Behaviors.same

      case Command.CheckIn(channelId, meta, hubSource, teardown) =>
        sessions.get(channelId) match {
          case Some(SessionState.Opening(waiters)) =>
            val clientIds = waiters.map(_.clientId).toSet
            sessions += channelId -> SessionState.Live(meta, hubSource, teardown, clientIds)
            context.log.info(
              "[session] check-in channelId={} token={} clients={}"
            , channelId, LogConfig.truncate(meta.token), clientIds.size
            )
            waiters.foreach(w => attachClient(channelId, w.clientId, hubSource, w.replyTo))
          case other =>
            context.log.warn("[session] check-in unexpected state channelId={} state={}", channelId, other)
            try teardown()
            catch { case _: Throwable => () }
        }
        Behaviors.same

      case Command.AcquireFailed(channelId, cause) =>
        sessions.get(channelId) match {
          case Some(SessionState.Opening(waiters)) =>
            sessions -= channelId
            context.log.warn("[session] acquire-failed channelId={} waiters={}", channelId, waiters.size, cause)
            rejectWaiters(waiters, RejectReason.Failed(cause))
          case _ =>
            context.log.warn("[session] acquire-failed ignored channelId={}", channelId, cause)
        }
        Behaviors.same

      case Command.GraceExpired(channelId) =>
        sessions.get(channelId) match {
          case Some(idle: SessionState.IdleGrace) =>
            context.log.info("[session] grace-expired channelId={}", channelId)
            teardownAndRemove(channelId, idle.teardown)
          case _ =>
            context.log.debug("[session] grace-expired ignored channelId={}", channelId)
        }
        Behaviors.same

      case Command.UpstreamEnded(channelId, leaseId) =>
        sessions.get(channelId) match {
          case Some(live: SessionState.Live) if live.meta.leaseId == leaseId =>
            context.log.warn("[session] upstream-ended channelId={} clients={}", channelId, live.clientIds.size)
            teardownAndRemove(channelId, live.teardown)
          case Some(idle: SessionState.IdleGrace) if idle.meta.leaseId == leaseId =>
            val _ = idle.graceTimer.cancel()
            context.log.warn("[session] upstream-ended channelId={} during idle-grace", channelId)
            teardownAndRemove(channelId, idle.teardown)
          case _ =>
            context.log.debug("[session] upstream-ended ignored channelId={} leaseId={}", channelId, leaseId)
        }
        Behaviors.same
    }
  }
}
```

### `startRunner` wiring

SessionManager does not import HTTP details. The parent (route setup / `Channel` object) supplies a function that starts
`SessionRunner` and posts back `CheckIn` / `AcquireFailed` / `UpstreamEnded`:

```scala
val sessionManager: ActorRef[SessionManager.Request] =
  context.spawn(
    SessionManager(
      startRunner = { (channelId, self) =>
        SessionRunner.start(
          channelId = channelId
        , authContext = authContext
        , onCheckIn = (meta, hubSource, teardown) =>
            self ! SessionManager.Command.CheckIn(channelId, meta, hubSource, teardown)
        , onFailed = cause =>
            self ! SessionManager.Command.AcquireFailed(channelId, cause)
        , onUpstreamEnded = endedLeaseId =>
            self ! SessionManager.Command.UpstreamEnded(channelId, endedLeaseId)
        )
      }
    )
  , "session-manager"
  )
```

`SessionRunner.start` runs asynchronously (Futures on the system dispatcher). It must not block the actor thread.

### SessionRunner surface (non-actor)

```scala
object SessionRunner {
  def start(
    channelId: String
  , authContext: Auth.AuthContext
  , onCheckIn: (SessionManager.TabloSessionMeta, Source[ByteString, NotUsed], () => Unit) => Unit
  , onFailed: Throwable => Unit
  , onUpstreamEnded: String => Unit
  )(implicit system: ActorSystem[?]): Unit

  // Materialization sketch inside start after successful watch:
  //
  // val (killSwitch, hubSource) =
  //   ResilientHlsSource(streamFactory = () => streamFactory(), streamName = s"4thgen-channel-$channelId")
  //     .viaMat(KillSwitches.single)(Keep.right)
  //     .toMat(BroadcastHub.sink[ByteString](SessionManager.BroadcastHubBufferSize))(Keep.both)
  //     .run()
  //
  // val teardown: () => Unit = { ... cancel keepalive; killSwitch.shutdown(); DELETE session ... }
  // onCheckIn(meta, hubSource, teardown)
}
```

### Route ask pattern

```scala
val clientId = java.util.UUID.randomUUID.toString
val acquireFut: Future[SessionManager.Response.Acquire] =
  sessionManager.ask(replyTo => SessionManager.Request.Acquire(channelId, clientId, replyTo))

onComplete(acquireFut) {
  case Success(SessionManager.Response.Attached(source)) =>
    val tracked = source.watchTermination() { (_, done) =>
      done.onComplete(_ => sessionManager ! SessionManager.Request.Release(channelId, clientId))
    }
    complete(HttpEntity.Chunked.fromData(contentType, tracked))
  case Success(SessionManager.Response.Rejected(SessionManager.RejectReason.NoTuners)) =>
    complete(HttpResponse(StatusCodes.InternalServerError, entity = "No available tuners"))
  case Success(SessionManager.Response.Rejected(SessionManager.RejectReason.Failed(_))) =>
    complete(HttpResponse(StatusCodes.InternalServerError, entity = "Unable to stream channel"))
  case Failure(_) =>
    complete(HttpResponse(StatusCodes.InternalServerError, entity = "Unable to stream channel"))
}
```

### Invariants

- At most one `Opening` / `Live` / `IdleGrace` entry per `channelId`
- `startRunner` is invoked only when transitioning `absent → Opening`
- `CheckIn` / `AcquireFailed` are only meaningful in `Opening`; unexpected delivery tears down the hub if needed
- `GraceExpired` for a channel that left `IdleGrace` (Acquire cancelled the timer) is ignored
- `UpstreamEnded` with a non-matching `leaseId` is ignored
- `Release` for an unknown `clientId` is ignored
- `teardown` is idempotent; SessionManager may call it once on grace expiry, and runner must tolerate a second call

## Responsibilities

### HTTP route (`Tablo4thGen.Channel.route`)

1. Generate `clientId = UUID`
2. Ask SessionManager `Acquire(channelId, clientId, replyTo)`
3. On `Attached`, serve chunked `video/mp2t` from `source`, with `watchTermination` → `Release`
4. On `Rejected(NoTuners)` / `Rejected(Failed(_))`, return the same class of HTTP errors as today

### SessionManager (typed actor)

See [Scala ADT / Behavior sketch](#scala-adt--behavior-sketch) for the full message handlers. Summary:

- Serialize Opening per `channelId` so concurrent first-hits do not both call `/watch`
- On map miss: invoke injected `startRunner` (ends in `Command.CheckIn` or `Command.AcquireFailed`)
- On `CheckIn`: store hub + teardown; reply `Attached` to all waiters; track `clientIds`; state `Live`
- On `Acquire` hit (`Live` or `IdleGrace`): cancel grace timer if needed; add `clientId`; reply `Attached(hubSource)`
- On `Release`: remove `clientId`; if empty → `IdleGrace` + `GraceExpired` timer; on fire → `teardown()` and remove entry
- Capacity: `sessions.size` vs `totalTuners`; attach to an existing channel does not consume an extra slot

### Tablo4thGen.Channel.SessionRunner (object / functions, not an actor)

Prefer extracting today's nested watch/keepalive/stream logic into a named object for readability, without introducing
a per-channel typed actor (smaller diff, one new actor total: SessionManager).

`start(channelId, authContext, onCheckIn, onFailed, onUpstreamEnded)`:

1. `POST /guide/channels/{id}/watch` (existing 503 retry behavior)
2. Materialize roughly:
   `ResilientHlsSource(streamFactory) → MpegTsSync.cacheFlow → KillSwitches.single → watchTermination → BroadcastHub.sink(bufferSize = 1024)`
3. Start the keepalive loop (moved out of per-request `streamWithTunerTracking`)
4. Invoke `onCheckIn(meta, hubSource, teardown)` (caller turns this into `Command.CheckIn`)
5. On `watchTermination`, invoke `teardown()` and `onUpstreamEnded(leaseId)`

`streamFactory`, keepalive failure retry, playlist-URL change restart, and near-expiry retune keep the same behavior as
the current per-request path, but as **one instance per channel lease**.

`teardown`:

- Cancel keepalive
- `killSwitch.shutdown()`
- `DELETE /player/sessions/{token}`
- Idempotent (second call is a no-op / logs and ignores DELETE errors)

## Failure matrix

| Event | Behavior |
|-------|----------|
| Initial watch fails while Opening | Retries with backoff (up to `STREAM_RECOVERY_TIMEOUT_SEC` if > 0, or 60s opening budget) before `AcquireFailed` → `Rejected(Failed(_))`; client receives pre-roll null keepalive |
| Client releases while Opening | Waiter removed; if all waiters release, Opening is cancelled and tune retries cease |
| Keepalive fails | Existing retry / fetch session; retune if needed; clients unaffected |
| Playlist URL change | Inner kill/restart under resilient source (`playlistRollover`); hub stays; clients seamless |
| Near-expiry retune | New Tablo token inside runner; old token DELETE; hub stays |
| Reception drop (no new bytes for 45s) | Stall watchdog trips after `STREAM_STALL_TIMEOUT_SEC` (45s) → `WatchSession.decideRecovery` holds existing `/watch` session up to `softRecoveryThresholdSec = 45s` (polling `pls.m3u8` every 500ms with `failOnStallPoll = false` and watchdog `softRecoveryTimeoutSec = 15s`); `FreezeFramePacer` emits 100ms repeat P-frames and silent audio to keep Plex warm; `MpegTsSync.cacheFlow` performs slice-level recovery (`droppingVideoSlice`); if outage exceeds 45s, escalates to Tier 2 cold retune (`DELETE` old session, `POST /watch`, reset `lastSeqRef`) with flap dampening backoff (capped at 4s) if stalls recur within 30s of recovery baseline; resumes with cached syncPrefix and continuous monotonic timeline |
| Upstream trickle collapse (Failure Mode E2) | Microscopic 117–160 KB segments arrive every 2–3s (~97% payload drop) with only ~0.1s video → `HlsSegmentFetch.TrickleState` suppresses `onSeqAdvanced` (< 15% EMA baseline) so `WatchSession.FlapState` continues tracking stall; `FreezeFramePacer` generates media-clock deficit catch-up frames (up to 6 frames per 100ms tick); after 5 consecutive trickle segments, `HlsError.SegmentTrickleCollapse` trips to escalate to recovery |
| Fast-attach audio starvation (Failure Mode G) | Client attaches during `idle-grace` inrush while video awaits I-frame → `AlignToIFrameStage` decouples audio alignment, independently emitting each audio PID on first `PUSI=1` with valid syncword. Excludes audio and null packets from `droppedPackets` counter (`maxDroppedPackets = 8000`). FFmpeg's `avformat_find_stream_info` probe sees audio within 1.5 MB and initializes audio transcode pipeline (`streamType="2"`) |
| Extended outage (`STREAM_RECOVERY_TIMEOUT_SEC`) | By default (`0`), keeps retrying every up to `STREAM_MAX_RETRY_DELAY_SEC` (15s) with null keepalives while client is connected; if configured > 0, stream completes cleanly → runner teardown → `UpstreamEnded(channelId, leaseId)` → entry removed immediately |
| User remote stop during recovery | Client disconnects → `Release` drain → IdleGrace → killSwitch shutdown cancels retune loop |
| Slow client (lags past 1024 buffer) | BroadcastHub backpressures the producer when slowest subscriber is 1024 elements behind |
| Acquire during IdleGrace | Cancel timer; attach; no new `/watch` |
| Teardown after Tablo session already gone | Log and ignore DELETE errors |

## Idle grace and BroadcastHub backpressure

When zero subscribers remain during IdleGrace, `BroadcastHub` buffers fill, which halts upstream pulls. In practice, this caused upstream pipelines under `StallWatchdog` to trip with no new data within the stall timeout, forcing spurious `/watch` retunes to the Tablo hardware while in grace.

To prevent upstream backpressure stalls, `SessionManager` attaches an active `Sink.ignore` drain managed by a `UniqueKillSwitch` upon entering `IdleGrace`. This maintains live stream flow and keepalives without stalls. When a new client connects (`Acquire` grace-cancel) or when `GraceExpired` fires, the drain kill switch is immediately shut down.

## Tuner accounting

A tuner slot is one session in `Opening | Live | IdleGrace`.

- New channel when tuners are available → `Opening`
- New channel when at capacity (`occupied >= tuners`) with an idle-grace session → oldest `IdleGrace` session is evicted and torn down to free a tuner slot
- New channel when at capacity with all tuners actively streaming → `Rejected(NoTuners)`
- Second client on an existing channel → attach only

## Defaults

| Setting | v1 value |
|---------|----------|
| Idle grace | 180 seconds (configurable via `SESSION_IDLE_GRACE_SEC`) |
| BroadcastHub buffer | 1024 elements |
| Recovery live edge | 3 segments (~7.5s cushion, configurable via `STREAM_RECOVERY_LIVE_EDGE_SEGMENTS`, default 3) |

Hardcoded for v1 is fine; promote to config later if needed.

## Logging

Correlate with:

- `channelId`
- `clientId` (proxy request UUID)
- `tabloToken` (truncated)
- attached `clientIds.size` (or waiter count while Opening)
- `state` (`Opening` / `Live` / `IdleGrace`)
- `occupied` / `tuners` when rejecting or opening

## Migration

1. Add SessionManager actor; spawn from 4th-gen setup alongside `LineupActor`
2. Extract watch / keepalive / stream block into `SessionRunner.start` / `teardown`
3. Materialize `BroadcastHub` in the runner; `CheckIn` instead of returning a per-request `Source`
4. Slim `Channel.route` to Acquire → chunked response → Release on termination
5. Replace `activeStreams` client counter with SessionManager session occupancy for tuner checks

## Implementation order

1. SessionManager + protocol tests (Opening waiters, refcount, grace cancel/fire)
2. `SessionRunner` extract + single-client parity (no sharing yet)
3. `BroadcastHub` CheckIn + dual-client share
4. Tuner accounting by session count

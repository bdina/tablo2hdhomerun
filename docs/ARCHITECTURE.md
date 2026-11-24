# Tablo2HDHomeRun - Architecture Documentation

## Overview

Tablo2HDHomeRun is an HTTP proxy server that exposes a TabloTV DVR device as an HDHomeRun-compatible tuner. This allows applications and media servers that support HDHomeRun devices (like Plex, Jellyfin, or Channels DVR) to stream live TV from a Tablo device.

## Project Goals & End-User Experience

The primary vision for Tablo2HDHomeRun is to make your network TV viewing experience feel like a **traditional standard TV set**: you tune into a station, and it just plays until you turn it off. Like a standard TV, poor reception or periodic atmospheric events may cause the channel data to drop or corrupt temporarily, but as long as you keep the TV tuned to that station, it recovers on its own and continues playing. The ultimate goal is to allow you to leave a fringe channel playing all day in Plex completely uninterrupted, while the proxy manages re-tuning and keeping the stream active.

To achieve this, Tablo2HDHomeRun is designed to provide an **optimized, resilient live TV playback experience for Plex clients** (as well as Jellyfin and Channels DVR). Over-the-air (OTA) television reception is subject to RF multipath interference, antenna flutter, and tuner dropouts. Conventional network tuners propagate these interruptions downstream, causing Plex client apps (Apple TV, iOS, Roku, Android TV, Plex Web) to crash playback with fatal error dialogs (e.g., "Playback Error", "Can't Play This: Format isn't supported") and forcing the viewer to manually exit back to the guide and re-tune.

Tablo2HDHomeRun is built to deliver this seamless, couch-friendly viewing experience governed by a **4-pillar fringe-signal resilience architecture**:

1. **Surgical Slice-Level & Audio Syncword Sanitization + Outbound Continuity Counter (`CC`) Rewriting ([`MpegTsSync.cacheFlow`](file:///home/bdina/git/tablo2hdhomerun/src/main/scala/app/stream/MpegTsSync.scala#L290-L299) & [`FreezeFramePacer`](file:///home/bdina/git/tablo2hdhomerun/src/main/scala/app/stream/FreezeFramePacer.scala))**:
   - Replaces whole-GOP gating (`corruptVideoGop` from `c57c238`) with slice-level recovery (`droppingVideoSlice`) that resumes decoding on the next valid MPEG-2 slice start code (`00 00 01 [01..maxSliceRow]`) after isolated in-frame (`PUSI=0`) errors, or gates recovery on the next clean `PUSI=1` PES frame header (`requireVideoPesStart`) after continuity counter (`CC`) gaps or dropped `PUSI=1` packets so P/B-frame slices are never spliced onto a preceding I-frame without a Picture Start Code (`00 00 01 00`). Orphaned partial-slice prefix bytes are stuffed into the adaptation field while preserving adaptation-field `PCR` on `TEI=0` packets.
   - **Out-of-Bounds Slice Gating ([`hasOutOfBoundsMpeg2Slice`](file:///home/bdina/git/tablo2hdhomerun/src/main/scala/app/stream/FreezeFramePacer.scala#L300-L332)) & Adaptation Field Flag Safety ([`stuffPacketPrefix`](file:///home/bdina/git/tablo2hdhomerun/src/main/scala/app/stream/FreezeFramePacer.scala#L376-L423))**: Unflagged corrupt packets (both `PUSI=0` and `PUSI=1` elementary payloads) containing out-of-bounds MPEG-2 slice start codes (`code > maxSliceRow`) trigger surgical slice dropping (`droppingVideoSlice = true`, replaced with `0x1FFF` null packets or raw `pcrOnlyPacket`) rather than in-place start-code zeroing (which would merge corrupt bytes into the preceding slice). When `stuffPacketPrefix` expands a 1-byte adaptation field (`afc == 3, oldAfLen == 0`), it initializes the new adaptation flags byte to `0x00` so bogus `discontinuity_indicator` or `PCR` flags are never set.
   - **PAT/PMT Candidate Confirmation & Table Restoration**: Once initial `PAT` and `PMT` tables are learned, `CacheStage` requires 2 consecutive identical table packets (`candidatePat` / `candidatePmt`) before mutating `detectedPmtPid`, `videoPid`, or `audioPids`, restoring the known-good cached `PAT`/`PMT` packet in the outbound stream whenever a single bit-flipped table packet arrives during OTA interference.
   - Guards against FFmpeg/VAAPI "poison pills": clamps out-of-bounds MPEG-2 slice rows (`> maxSliceRow`, e.g. `> 68` on 1080i) to prevent `slice below image` decoder crashes, locks the 12-byte MPEG-2 Sequence Header (`00 00 01 B3`) geometry once confirmed, and gates corrupted audio PIDs until a `PUSI=1` packet with a valid `0x0B77` (AC-3) or `0xFFF` (ADTS AAC) syncword arrives.
   - **Locked Sequence Header Caching & Fast-Attach Injection**: `MpegTsSync.CachedHeaders` caches `lockedSeqHeader: Option[ByteString]` once confirmed by `CacheStage`. When `AlignToIFrameStage` aligns on a GOP header (`00 00 01 B8`) without an in-band Sequence Header or forces alignment on a `PUSI=1` PES frame boundary after `DefaultMaxAlignmentWait` (`3.seconds` from the first dropped video packet), it prepends `FreezeFramePacer.synthesizeSequenceHeaderPacket` (`PUSI = 1` 188-byte TS packet carrying a 9-byte PES header + 12-byte `00 00 01 B3` Sequence Header) so FFmpeg always initializes valid frame dimensions instead of logging `[mpeg2video] Invalid frame dimensions 0x0`.
   - **Decoupled Audio Alignment & Wall-Clock Video Alignment**: `AlignToIFrameStage` decouples audio alignment from video keyframe alignment. Dropping `knownAudioPids` packets while `isAligned == false` previously caused FFmpeg's `avformat_find_stream_info` probe on fast-attach (`BroadcastHub` inrush during `idle-grace`) to see zero audio packets in the first 1.5 MB and permanently lock a Video-Only transcode pipeline (`streamType="2"` omitted, Failure Mode G). `AlignToIFrameStage` now aligns each audio PID independently on its first `PUSI = 1` PES packet (with valid AC-3 `0x0B77` / ADTS `0xFFF` syncword when streamType is known) and emits audio packets immediately even while `isAligned == false` for video. Video keyframe fail-open alignment is governed by a 3-second wall-clock threshold (`DefaultMaxAlignmentWait = 3.seconds`) anchored on the first dropped video packet (`firstDroppedVideoNanos`) and gated on `PUSI=1` PES frame boundaries, preventing fast-attach in-memory `BroadcastHub` bursts (> 8,000 packets in ~100ms) or inter-segment `0x1FFF` keepalive gaps from prematurely forcing mid-GOP alignment.
   - Continuously rewrites outbound 4-bit Continuity Counters (`CC`) per PID so downstream demuxers (Plex `ssegment` and VAAPI) see zero `CC` discontinuities when corrupted packets are dropped.
2. **Control-Plane Session-Presence Probing, `500ms` Fast Polling & Cold Retune (`HlsPlaylistPoller`, `ResilientHlsSource`, `WatchSession`)**:
   - Polls `pls.m3u8` every `500ms` whenever `lastAdvanced == false` (`304 Not Modified` or unchanged sequence) and disables arbitrary byte-stall timeouts (`stallTimeout = Duration.Zero` by default) so distorted reception rides out on the existing hardware tuner lock for as long as the Tablo keeps the `/watch` session open rather than thrashing tuner state.
   - **Active Keepalive Session Probing (`checkSessionAlive` & `WatchSession.classifyProbeResult`) & Unchanged Playlist Stall Cap (`e811d2f`, `3969fe1`, `5cd1bc5`, `d9a52dd`, `5058ba1`)**: Starting at the 12th consecutive `500ms` stall poll (`sessionProbeMinPolls = 12`, `~6s` of unchanged polls, then every 4th poll `~2s`, `sessionProbeIntervalPolls = 4`, logged with `reason=unchanged`) or immediately when `pls.m3u8` returns `404 Not Found` (`reason=404`, `5058ba1`), `HlsPlaylistPoller.resolveStallOutcome` invokes `checkSessionAlive` (`POST /player/sessions/{token}/keepalive?lh=1`). While the Tablo reports `200 OK` (`ProbeOutcome.Alive`), the stream stays attached (`404` on `pls.m3u8` is treated as a transient error up to `pollFailuresMax`). When the Tablo returns `4xx` (`ProbeOutcome.ClosedByTablo`), transient probe errors exceed `pollFailuresMax` (`ProbeOutcome.ProbeExhausted`), or an unchanged playlist stalls for `>= STREAM_HLS_MAX_STALL_SEC` (default `60s`, `<= 0` disables; `HlsError.PlaylistStalled`), `HlsBackend` fails immediately and `SessionRunner` executes `WatchSession.coldRetune` (`POST /watch`, skipping `DELETE` when the Tablo already closed the session, guarding late completions against teardown leaks or superseded attempts via `WatchSession.classifyColdRetuneCompletion` in `5cd1bc5`, and seeding `HlsPlaylistPoller` with the last 8 completed segment keys so stale cached Tablo buffers are suppressed and playback resumes strictly on new content via `d9a52dd`). `ResilientHlsSource` configures `RestartSettings` with `maxRestartsWithin = (tuneTimeout + maxRetryDelay).max(retryDelay)` (default `45s` with `STREAM_TUNE_TIMEOUT_SEC=30`, `e811d2f`, `5cd1bc5`) so slow-failing cold retunes (`~6.1s–24s` `503 no_signal_lock` / weak locks) genuinely back off (`1s → 2s → 4s → 8s → 15s`) rather than resetting to `[1]`.
   - **Health-Gated `IdleGrace` Lifecycle (`ecd3da8`)**: `TabloSessionMeta.isHealthy` tracks real-time session health (`!tornDown && !inRecovery && !isFrozen` via `MpegTsSync.cacheFlow`'s `onFreezeStateChanged` callback plus latest feed metrics). On last-client `Release`, unhealthy/retuning sessions skip `IdleGrace` and tear down immediately; on `Acquire`, stale/unhealthy `IdleGrace` sessions are evicted for a fresh `Opening` tune instead of fast-attaching into a freeze-only stream; and `Command.SessionUnhealthy` ends `IdleGrace` immediately if an idle session freezes or enters cold retune with zero connected clients (while leaving active `Live` sessions untouched).
   - **Sustained Audio-Loss Cold Retune ([`AudioLossMonitor`](file:///home/bdina/git/tablo2hdhomerun/src/main/scala/app/stream/AudioLossMonitor.scala), `2309c92`, `14e5091`)**: Guards against upstream Tablo audio encoder wedges (Failure Mode H) where video continues flowing and `/keepalive` stays `200 OK` while primary-PID (`audioPids.headOption`) audio is continuously scrubbed (`> 2s` PTS drift / corrupt syncword) or vanishes completely. When active non-frozen live video reaches `STREAM_AUDIO_LOSS_TIMEOUT_SEC` (default `30s`, `>= 150` video frames, `<= 0` disables) with zero recovered primary audio, `MpegTsSync.cacheFlow` fires `ResilientHlsSource.RetuneTrigger` (`HlsError.SustainedAudioLoss`) inside `RestartSource.withBackoff` so `WatchSession.coldRetune` cleanly recycles the Tablo session (`DELETE` + fresh `POST /watch`) without disconnecting downstream clients. Pauses during `freezeActive` / `confirmedReplay`, ignores secondary SAP tracks and video-only streams, and applies exponential backoff (`30s → 60s → 120s → 240s → 300s` cap, resetting after `100` clean live audio frames) to prevent retune storms on broadcaster-side audio outages.
3. **Burst-Safe HLS Segment Handling & 10s Freeze-Frame Pacer ([`FreezeFramePacer`](file:///home/bdina/git/tablo2hdhomerun/src/main/scala/app/stream/FreezeFramePacer.scala))**:
   - **Burst-Safe HLS Segment Handling (`DefaultFreezeThreshold = 10.seconds`, `STREAM_FREEZE_THRESHOLD_SEC`)**: Because HLS segments (~2.0s of media) are fetched in ~50ms network bursts followed by 1.5s–2.4s polling intervals, the default freeze threshold is set to `10.seconds` (well above normal inter-segment intervals and moderate jitter) so synthetic frames are never injected between healthy HLS segments and leading audio/video packets of subsequent segments are never gated. During normal inter-segment intervals and brief RF pauses (`< 10s`), `ResilientHlsSource.keepAlive` emits standard `0x1FFF` null packets every 40ms while client decoders naturally hold the last decoded frame.
   - **Active Freeze-Frame Synthesis on Prolonged Stalls (`>= 10s`)**: When an upstream Tablo segment stall or media-clock deficit reaches `freezeThreshold` (default `10s`, configurable via `STREAM_FREEZE_THRESHOLD_SEC`), `FreezeFramePacer` emits live-compatible `PAT`/`PMT`, spec-compliant MPEG-2 zero-motion P-frames (`picture_coding_extension` + skipped macroblock slices matched to locked sequence header width via `0f57f35`, or H.264 keepalive access units) on `videoPid`, and silent AC-3 (`0x0B77` with valid CRC-16) or ADTS AAC frames on `audioPids` every `100ms`, ensuring Plex's ingest grabber (`-codec copy -f ssegment`) and downstream client HLS buffers continue advancing without timing out. Resumes live A/V seamlessly on the first live keyframe start header (`hasKeyframeHeader`) advancing past pre-stall watermarks (`9a2dd6d`).
   - **Video-Anchored Activation**: `lastLiveDataNanos` in `CacheStage` is anchored strictly to **emitted live video packets** (`!freezeActive && !droppingVideoSlice`), preventing incoming `PAT`/`PMT`, audio packets, or discarded non-keyframe packets during `freezeActive` from suppressing timer-driven freeze-frame synthesis.
   - **Real-Time Media Clock Anchor & Post-Freeze Resumption (`9a2dd6d`)**: `CacheStage` tracks `mediaClockAnchorPts` and `mediaClockAnchorNanos`. If upstream trickle collapse delivers sparse video and `lastOutputPts` falls behind real-time wall clock by `>= freezeThreshold`, it synthesizes catch-up freeze frames + silent audio (up to 6 frames per `100ms` tick). When a live keyframe resumes after `freezeActive`, confirmed replay, or an MPEG-TS discontinuity packet (`pendingTimelineSync`), `mediaClockAnchorPts` re-anchors to the resumed `lastOutputPts` (preventing permanent `ptsDeficit` latching on subsequent inter-segment gaps) and `ptsDeltaTicks` synchronizes monotonically across video and audio.
4. **B-Frame-Safe Native STC Timestamp Preservation & Always-On Monotonic Timeline Normalizer**:
   - **B-Frame-Safe Native Hardware STC Timestamp Preservation & Replay Suppression (`9a2dd6d`)**: `MpegTsSync.cacheFlow` preserves Tablo's native hardware System Time Clock (STC) `PTS`, `DTS`, and `PCR` timestamps untouched on clean streams (`ptsDeltaTicks == 0L`). Because MPEG-2 and H.264 broadcasts transmit B-frames in decode (`DTS`) order where B-frame `PTS` values are naturally lower than the preceding I/P anchor frame (`diffFromOut < 0` within `RebaseThresholdTicks` = `0.5s`), allowing negative decode-order `PTS` diffs within `RebaseThresholdTicks` prevents B-frame presentation order scrambling and artificial `ptsDeltaTicks` clock acceleration across video, audio, and PCR tracks. When upstream regresses into already-played timestamps of the active continuity epoch (`[firstInputVideoPts - MaxBFrameReorderTicks, maxInputVideoPts]`), `CacheStage` suppresses replayed video and audio frames (keeping freeze pacing active if stalled) until a clean live keyframe advances strictly past `maxInputVideoPts`, resetting watermarks cleanly on `MPEGTS_DISCONTINUITY_PACKET` or confirmed clock resets.
   - **Always-On Monotonic `PCR` / `PTS` / `DTS` Normalizer**: `normalizePacketTimestamps` is always active in `CacheStage` (even when `freezeThreshold` is set to `0`), scrubbing single-packet bit-flipped PES timestamp outliers (`> 2s` jump) in-place so corrupt timestamps never cause downstream A/V skew spikes, and maintaining a continuous monotonic output offset (`ptsDeltaTicks`) across synthetic freeze-frame injection, media-clock deficit catch-up, and `/watch` session retunes.

### Acceptable Data Loss & Resiliency Philosophy

Over-the-air broadcast streams during antenna flutter, wind gusts, or fringe reception inevitably suffer transport data loss. The proxy's design deliberately prioritizes **uninterrupted client playback and zero human intervention by isolating stream damage surgically while preserving tuner lock**.

- **Ride out dropouts on existing hardware tuner lock via control-plane session probing**: Early implementations triggered rapid cold retunes on dropouts (e.g. 8s stall watchdog and immediate `304 Not Modified` escalation in `6598c07`, or 16s clean-keyframe starvation in `c57c238`). Under the 4-pillar architecture, arbitrary byte-stall timeouts are disabled by default (`stallTimeout = Duration.Zero`) and the playlist poller drops to a rapid 500ms cadence during stalls, probing `POST /player/sessions/{token}/keepalive?lh=1` once unchanged polls reach `sessionProbeMinPolls = 12` (`~6s` of unchanged polls, then every 4th poll `~2s`, per `3969fe1`) or immediately on playlist `404` so transient RF nulls recover on the existing hardware tuner lock for as long as the Tablo keeps the session open.
- **Surgical slice-level sanitization & out-of-bounds slice gating over whole-GOP gating**: When corrupted TS packets arrive (flagged with `TEI` or broken CC), earlier versions dropped the entire video GOP (`corruptVideoGop` in `c57c238`). The proxy now isolates damage to individual MPEG-2 slices (`droppingVideoSlice`), immediately resuming on the next valid slice start code (`00 00 01 [01..maxSliceRow]`) or picture header, converting orphaned partial-slice fragments into adaptation field stuffing and preserving adaptation-field PCRs. Unflagged corrupt packets containing out-of-bounds MPEG-2 slice start codes (`code > maxSliceRow`) trigger surgical slice dropping (`droppingVideoSlice = true`, replaced with `0x1FFF` null packets or raw `pcrOnlyPacket`) rather than in-place start-code zeroing (which would merge corrupt bytes into the preceding slice). When [`stuffPacketPrefix`](file:///home/bdina/git/tablo2hdhomerun/src/main/scala/app/stream/FreezeFramePacer.scala#L376-L423) expands a 1-byte adaptation field (`afc == 3, oldAfLen == 0`), it initializes the new adaptation flags byte to `0x00` so bogus `discontinuity_indicator` or `PCR` flags are never set.
- **Demuxer and transcoder safety (VAAPI poison pills & header injection)**: FFmpeg and hardware VAAPI decoders are protected from crashing on broadcast bit errors:
  - Slice row vertical positions are clamped to `<= maxSliceRow` (e.g., row 68 for 1080i) to eliminate `slice below image` fatal decoder asserts.
  - The 12-byte MPEG-2 Sequence Header (`00 00 01 B3`) geometry is locked once established and cached in `CachedHeaders.lockedSeqHeader`. If a client attaches at a GOP boundary missing an in-band Sequence Header or reaches forced alignment, the proxy synthesizes a `00 00 01 B3` packet to prevent `Invalid frame dimensions 0x0`.
  - Corrupted audio streams are silenced until a `PUSI=1` packet with a verified audio syncword (`0x0B77` for AC-3 with valid CRC-16, or `0xFFF` for ADTS AAC) arrives.
  - Outbound 4-bit Continuity Counters (`CC`) are rewritten continuously per PID so downstream muxers never see CC jumps when corrupt packets are dropped.
- **Decoupled audio alignment protecting client format probing**: Gating audio on video keyframes previously caused FFmpeg's `avformat_find_stream_info` probe on fast-attach (`BroadcastHub` inrush during `idle-grace`) to starve of audio in the first 1.5 MB, locking into a video-only transcode pipeline. Audio alignment is now fully decoupled: each audio PID aligns on its first `PUSI=1` PES packet and begins emitting immediately.
- **Burst-Safe HLS Segment Handling & Freeze Pacing**: Because HLS segments (~2.0s of media) are fetched in ~50ms network bursts followed by 1.5s–2.4s polling intervals, the freeze threshold defaults to `10s` (`DefaultFreezeThreshold = 10.seconds`, `STREAM_FREEZE_THRESHOLD_SEC`; `<= 0` disables freeze pacing) so synthetic frames are never injected between healthy HLS segments and leading audio/video packets of subsequent segments are never gated. During normal inter-segment intervals and brief RF pauses (`< 10s`), `ResilientHlsSource.keepAlive` emits standard `0x1FFF` null packets every 40ms while client decoders naturally hold the last decoded frame. When upstream video pauses or falls behind wall clock for `>= freezeThreshold`, `FreezeFramePacer` generates valid MPEG-2 repeat P-frames and silent audio every 100ms. Video-anchoring ensures incoming audio or non-video packets cannot suppress freeze-frame pacing. If upstream segment trickle reduces delivered video below real-time cadence, `CacheStage` detects the media-clock deficit against wall clock and emits up to 6 catch-up frames per tick, maintaining an uninterrupted 1s segment cadence in Plex's ingest grabber.
- **Native Hardware STC Timestamp Preservation & Timeline Continuity**: On clean live streams (`ptsDeltaTicks == 0L`), `MpegTsSync.cacheFlow` preserves Tablo's native hardware System Time Clock (STC) `PTS`, `DTS`, and `PCR` timestamps untouched. Because MPEG-2 and H.264 broadcasts transmit B-frames in decode (`DTS`) order where B-frame `PTS` values are naturally lower than the preceding I/P anchor frame (`diffFromOut < 0` within `RebaseThresholdTicks`), preserving native timestamps prevents B-frame presentation order scrambling and prevents artificial `ptsDeltaTicks` clock acceleration across video, audio, and PCR tracks. Single-packet PES timestamp bit-flips (> 2s jumps) are scrubbed in-place (`ptsScrubs`), and a monotonic offset (`ptsDeltaTicks`) normalizes PCR, PTS, and DTS across synthetic freeze-frame injection, media-clock deficit catch-up, session restarts, and retunes.
- **Immediate cold retune on session closure**: When the Tablo closes the `/watch` session (`4xx` on keepalive probe or `#EXT-X-ENDLIST` on `pls.m3u8`) or transient probe errors exceed `pollFailuresMax` (default 4), `WatchSession.coldRetune` immediately establishes a new `/watch` session (skipping `DELETE` when the Tablo already closed the old session, resetting `lastSeqRef` while retaining recent completed segment keys via `d9a52dd` to prevent replaying stale cached segments, and applying `STREAM_TUNE_TIMEOUT_SEC` = 30s per `5cd1bc5` and `RestartSource.withBackoff` retry bounds) while `ResilientHlsSource.keepAlive` maintains downstream stream continuity.

## Technology Stack

| Component | Technology |
|-----------|------------|
| Language | Scala 3.10.1 (`given`/`using`, context functions, `enum`, `opaque type`, `boundary`/`break`) |
| Runtime | JVM (Java 25+ with AppCDS, virtual threads, `VarHandle`, `HexFormat`) or GraalVM 25 Native Image |
| HTTP Framework | Apache Pekko HTTP 2.0.0 (`host-connection-pool.max-connections = 8`) |
| Actor System | Apache Pekko Actor Typed 2.0.0 (functional behaviors, `withTimers`, `pipeToSelf`, `CoordinatedShutdown`) |
| JSON | Spray JSON |
| XML | scala-xml |
| Build | Gradle 9.3.1 with Shadow plugin 9.6.1 |
| Containerization | Docker (Ubuntu 24.04 / `scratch` native & Temurin 25 Alpine JVM with `HEALTHCHECK`) |

## System Architecture

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                           Media Client Applications                         │
│              (Plex, Jellyfin, Channels DVR, VLC, etc.)                      │
└─────────────────────────────────────────────────────────────────────────────┘
                                       │
                                       │ HTTP (HDHomeRun API)
                                       ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                         Tablo2HDHomeRun Proxy Server                        │
│                              (Port 8080)                                    │
│  ┌─────────────────┐  ┌──────────────────┐  ┌─────────────────────────────┐ │
│  │   HTTP Routes   │  │   Actor System   │  │   Stream Processing         │ │
│  │  ─────────────  │  │  ──────────────  │  │  ─────────────────────────  │ │
│  │  /discover.json │  │  LineupActor     │  │  FFmpeg or HLS backend      │ │
│  │  /lineup.json   │  │  GuideActor      │  │  (STREAM_BACKEND env)       │ │
│  │  /lineup_status │  │  FsMonitor       │  │  Chunked HTTP streaming     │ │
│  │  /channel/{id}  │  │  FsNotify        │  │                             │ │
│  │  /auto/v{chan}  │  │  FFMpegDelegate  │  │                             │ │
│  │  /guide.xml     │  │                  │  │                             │ │
│  └─────────────────┘  └──────────────────┘  └─────────────────────────────┘ │
└─────────────────────────────────────────────────────────────────────────────┘
                                       │
                                       │ HTTP (Tablo API)
                                       ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                            TabloTV DVR Device                               │
│                    (Port 8887 4th gen / 8885 legacy)                        │
│  ┌──────────────────────────────────────────────────────────────────────┐   │
│  │  /guide/channels  │  /batch  │  /guide/channels/{id}/watch           │   │
│  └──────────────────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────────────┘
```

## Core Components

### HTTP Server Layer

The server exposes HDHomeRun-compatible REST endpoints:

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/discover.json` | GET | Device discovery metadata (DeviceID, IP, capabilities) |
| `/lineup.json` | GET | Channel lineup in HDHomeRun format |
| `/lineup_status.json` | GET | Scan status (ScanInProgress, ScanPossible) |
| `/channel/{id}` | GET | Live MPEG-TS stream for a channel |
| `/auto/v{channel}` | GET | Live MPEG-TS stream by virtual channel number (e.g. `/auto/v4.1`) |
| `/guide.xml` | GET | XMLTV-format electronic program guide |

### Actor System

Built on Apache Pekko's typed actor model for concurrent, fault-tolerant processing:

#### LineupActor

- Manages channel lineup cache (1-day TTL)
- Fetches channels from Tablo `/guide/channels` and `/batch` endpoints
- Transforms Tablo channel format to HDHomeRun JSON format
- Coalesces concurrent cold-cache `Fetch` requests onto a single asynchronous scan via `context.pipeToSelf` (`2a70433`)

#### GuideActor

- Manages program guide cache (1-hour TTL)
- Fetches program schedules for all channels and coalesces cold-cache `FetchGuide` callers via `context.pipeToSelf` (`2a70433`)
- Generates fallback programming when Tablo API unavailable
- Singleton pattern to reduce resource usage

#### FsMonitor / FsNotify / FsScan

- Optional filesystem monitoring for media file transcoding using functional actor behaviors and `Behaviors.withTimers` (`2a70433`)
- `FsScan` completes directory walks via `context.pipeToSelf` and stops after replying (`2a70433`)
- `QueueProxy` polls `FFMpegDelegate` via `withTimers` rather than stream kill switches (`2a70433`)
- Tracks processed files via extended file attributes (`user.fs.state`)

#### FFMpegDelegate

- Functional actor managing FFmpeg transcoding processes (`2a70433`)
- Handles lifecycle (start, status, stop)
- Intel QuickSync Video (QSV) hardware acceleration support
- Uses top-level `TranscodeError` (`NoStackTrace` value ADT, aliased as `FFMpegDelegate.Error`) and `Try[Path]` with `boundary`/`break` instead of throwing exceptions during file validation and extended-attribute locking (`ae9a35e`, `8ae6357`)

#### CoordinatedShutdown (`Tablo2HDHomeRun`, `2a70433`)

- Orchestrates ordered graceful teardown across Pekko 2.0 phases (`pekko.coordinated-shutdown.default-phase-timeout = 10 s`):
  1. `PhaseServiceUnbind`: unbinds the HTTP server port
  2. `PhaseServiceRequestsDone`: stops 4th-gen `TunerState` polling and asks `SessionManager` (`ShutdownAll`) to release active/opening/idle-grace Tablo sessions
  3. `PhaseActorSystemTerminate`: closes the UDP port 65001 discovery socket

#### TunerStateActor (4th Gen)

- Maintains the latest snapshot of Tablo 4th Gen hardware tuner activity (`Snapshot`)
- Fed by a periodic Pekko stream (`TunerState.startStream`, default `30s`, configured via `TUNER_STATE_POLL_INTERVAL_SEC`) that polls `GET /server/tuners?lh=1` on the local Tablo device
- Resolves human-readable OTA channel labels via `Lineup` with a fallback to `GET /guide/channels/{id}?lh=1`
- Queries `SessionManager` (`GetActiveChannelStates`) to log whether each tuned channel is controlled by `tablo2hdhomerun` (`[self:<state>]`) or externally by another app (`[external]`), without altering `SessionManager` tuner count gating

### Stream Processing Pipeline

The live channel stream can use one of two backends, selected by `STREAM_BACKEND`:

- **hls** (default): Fetches M3U8 playlists and TS segments directly via HTTP; no external process. Optimized for HLS v4 byte-range playlists with sub-second adaptive polling (polling at 75% of `#EXTINF` segment duration after advance — even when Tablo emits `#EXT-X-TARGETDURATION:1` per `3969fe1` — and down to 500ms whenever unchanged or stalling via `HlsPlaylistPoller`), conditional playlist requests (`ETag` / `Last-Modified`), safe byte-range validation (filtering zero-length or negative sub-ranges to prevent range header errors), tolerant `206 Partial Content` validation for ranged segment fetches (accepting short reads during reception drops), per-segment packet alignment via `MpegTsSync.alignPackets`, and automatic discontinuity + I-frame realignment after any sequence gap, short read, or segment fetch error. When `pls.m3u8` stops advancing (`stallPolls > 0`, including during `#EXT-X-MEDIA-SEQUENCE: 1` sub-3600 segment windows per `255d354`), `HlsPlaylistPoller` probes the Tablo 4th-Gen control plane (`POST /player/sessions/{token}/keepalive?lh=1`) starting at `sessionProbeMinPolls = 12` consecutive `500ms` stall polls and every 4th poll thereafter (`3969fe1`) via `checkSessionAlive`, continuing to wait on the existing HLS stream while the session exists (`200 OK`) and failing with `HlsError.SessionEnded` immediately when the Tablo closes the session (`4xx`).
- **ffmpeg**: Spawns an FFmpeg subprocess to convert HLS to MPEG-TS. Requires FFmpeg on PATH. Includes reconnect and error-detect flags to survive transient stream drops.

The inner per-tune stream is bounded at startup by `StallWatchdog` (`STREAM_TUNE_TIMEOUT_SEC`, default 30s per `5cd1bc5` for initial connection / hardware cold tuning) and wrapped by `ResilientHlsSource`. Once initial bytes arrive, HLS liveness is governed by control-plane session-presence probing rather than an arbitrary byte-stall timer (`stallTimeout = Duration.Zero` by default), so an active `/watch` session rides out RF dropouts on its existing hardware tuner lock for as long as the Tablo keeps the session open. Because HLS segments (~2.0s of media) are fetched in ~50ms network bursts followed by 1.5s–2.4s polling intervals, the freeze threshold defaults to `10s` (`DefaultFreezeThreshold = 10.seconds`, `STREAM_FREEZE_THRESHOLD_SEC`; `<= 0` disables freeze pacing) so synthetic frames are never injected between healthy HLS segments and leading audio/video packets of subsequent segments are never gated. During normal inter-segment intervals and brief RF pauses (`< 10s`), `ResilientHlsSource.keepAlive` emits standard `0x1FFF` null packets every 40ms while client decoders naturally hold the last decoded frame. When upstream pauses or media-clock deficits reach `freezeThreshold` (`>= 10s` by default), `FreezeFramePacer` emits live-compatible `PAT`/`PMT`, spec-compliant MPEG-2 zero-motion P-frames (`picture_coding_extension` + skipped macroblock slices sized to the locked sequence header width via `0f57f35`, or H.264 keepalive access units) on `videoPid`, and silent AC-3 (`0x0B77` with valid CRC-16) or ADTS AAC frames on `audioPids` every 100ms. This keeps Plex's ingest grabber (`-codec copy -f ssegment`) writing 1s segments continuously of the frozen picture. Within `MpegTsSync.CacheStage`, activation is strictly **video-anchored**: `lastLiveDataNanos` updates solely on emitted live video packets (`!freezeActive && !droppingVideoSlice`), preventing incoming PAT/PMT, audio, or discarded packets during freeze states from resetting the pacer. Furthermore, `CacheStage` anchors a real-time media clock (`mediaClockAnchorPts`, `mediaClockAnchorNanos`): when upstream segment trickle causes `lastOutputPts` to fall behind wall clock by `>= freezeThreshold`, it synthesizes catch-up freeze frames + silent audio (up to 6 frames per 100ms tick) while seamlessly rebasing subsequent live video timestamps. When live video resumes, live A/V is restored seamlessly on the first live keyframe start header (`hasKeyframeHeader`) advancing past pre-stall watermarks (`9a2dd6d`), re-anchoring `mediaClockAnchorPts` and synchronizing `ptsDeltaTicks` monotonically.

When the Tablo closes the session (`404`/`410`/`401`/`403`/`400` on `POST /player/sessions/{token}/keepalive?lh=1` or `#EXT-X-ENDLIST` / `404` on `pls.m3u8`) or after `pollFailuresMax` (default 4) consecutive transient errors, `HlsBackend` fails with `HlsError.SessionEnded` and `SessionRunner` immediately fires a cold retune (`WatchSession.coldRetune` → `POST /watch`, skipping `DELETE` when the keepalive probe already confirmed the session is closed). `RestartSource.withBackoff` applies exponential backoff starting at `STREAM_RETRY_DELAY_SEC` (default 1s) up to `STREAM_MAX_RETRY_DELAY_SEC` (default 15s). HLS media sequence tracking (`lastSeqRef`) is reset on cold retunes so that fresh live-edge segments (`STREAM_RECOVERY_LIVE_EDGE_SEGMENTS`, default 3) immediately replenish client buffers. When fresh broadcast bytes resume following a retune, `ResilientHlsSource` passes incoming data through `MpegTsSync.alignToIFrame` (dropping leading video slices until a codec-matched MPEG-2 Sequence/GOP or H.264 IDR/SPS boundary is hit and aligning audio independently) and prepends a single resume prefix consisting of multi-PID discontinuity packets (video, audio, PCR, and null PIDs) and cached PAT/PMT headers. `RecoveryTimeout` monitors end-to-end byte progress: by default (`STREAM_RECOVERY_TIMEOUT_SEC=0`), recovery continues indefinitely while the viewer stays connected; if configured to a positive value, the stream completes cleanly after that duration without real broadcast data.

Before fanning out via `BroadcastHub`, streams pass through `MpegTsSync.cacheFlow`, `KillSwitches.single`, and `watchTermination`. `MpegTsSync.cacheFlow` executes surgical slice-level sanitization, poison pill protection, outbound continuity counter rewriting, and native timestamp preservation:
- **Slice-Level Sanitization & Out-of-Bounds Gating**: Rather than dropping whole GOPs on `TEI` or `CC` jumps (`corruptVideoGop` in `c57c238`), `MpegTsSync` flags `droppingVideoSlice` and resumes decoding on the next valid MPEG-2 slice start code (`00 00 01 [01..maxSliceRow]`) or `PUSI=1` frame header. Unflagged corrupt packets containing out-of-bounds MPEG-2 slice start codes (`code > maxSliceRow`) trigger surgical slice dropping (`droppingVideoSlice = true`, replaced with `0x1FFF` null packets or raw `pcrOnlyPacket`) rather than in-place start-code zeroing (which would merge corrupt bytes into the preceding slice). When `stuffPacketPrefix` expands a 1-byte adaptation field (`afc == 3, oldAfLen == 0`), it initializes the new adaptation flags byte to `0x00` so bogus `discontinuity_indicator` or `PCR` flags are never set. Partial-slice prefix bytes are converted to adaptation field stuffing, and adaptation-field PCRs are preserved on `TEI=0` packets.
- **FFmpeg/VAAPI Poison Pill Protection & Sequence Header/Extension Sanitization (`4c36514`, `9a2e143`)**: Slice row vertical positions are clamped to `<= maxSliceRow` (e.g. `<= 68` on 1080i) to eliminate `slice below image` decoder crashes. The full 22-byte MPEG-2 Sequence Header (`00 00 01 B3`) + validated `Sequence Extension` (`00 00 01 B5 1x`, requiring `chroma_format == 4:2:0`, `horizontal/vertical_size_extension == 0`, and `marker_bit == 1`) is locked via 2-packet confirmation (`MpegTsSync.SeqHeaderLockState`, supporting 12-byte → 22-byte upgrades including split `PUSI=1`/`PUSI=0` packets) and cached in `MpegTsSync.CachedHeaders.lockedSeqHeader`. Once locked, `FreezeFramePacer.sanitizeMpeg2PayloadDetailed` restores corrupted `0xB3` bytes `0..10` and byte `11` bits `7..2` in-stream while preserving live custom intra/non-intra quantiser matrices (`12`/`76`/`140`-byte skip strides, clearing stray quantiser bits if `0xB5 1x` sits immediately at `i + 12`), and restores corrupted 10-byte `0xB5 1x` `Sequence Extension` headers (protecting `progressive_sequence`, `chroma_format`, `horizontal/vertical_size_extension`, and `extension_start_code_identifier` immediately following `0xB3`, while strictly leaving `0xB5 8x` `picture_coding_extension` and `0xB2` closed-caption user data untouched) to prevent mid-stream VAAPI `Parsed_hwupload_4` filtergraph crashes and software `libx264` fallback. Header restorations are counted zero-allocation via `MpegTsTypes.Mpeg2SanitizeResult` and reported as `restoredSeqHeaders` in the 30s `[mpegts] transport anomalies` log. When `AlignToIFrameStage` aligns on a GOP header (`00 00 01 B8`) without an in-band Sequence Header or reaches forced alignment on a `PUSI=1` PES frame boundary after `DefaultMaxAlignmentWait` (`3.seconds`), it injects `FreezeFramePacer.synthesizeSequenceHeaderPacket` (a 188-byte `PUSI = 1` TS packet carrying PES header + locked sequence header/extension) so FFmpeg decoders always initialize valid frame dimensions rather than failing with `Invalid frame dimensions 0x0`. Corrupted audio PIDs are gated until a `PUSI=1` packet arrives with a valid `0x0B77` (AC-3 with valid CRC-16) or `0xFFF` (ADTS AAC) syncword.
- **Decoupled Audio Alignment & Wall-Clock Video Alignment**: To prevent FFmpeg's `avformat_find_stream_info` probe from failing on fast-attach (`BroadcastHub` inrush during `idle-grace`), `AlignToIFrameStage` decouples audio alignment from video keyframe alignment. Each audio PID aligns independently on its first `PUSI = 1` PES packet and begins emitting immediately even while `isAligned == false` for video, preventing audio starvation in the initial 1.5 MB probe buffer (Failure Mode G). Video keyframe fail-open alignment is governed by a 3-second wall-clock threshold (`DefaultMaxAlignmentWait = 3.seconds`) anchored on the first dropped video packet (`firstDroppedVideoNanos`) and gated on `PUSI=1` PES frame boundaries, preventing fast-attach in-memory `BroadcastHub` bursts (> 8,000 packets in ~100ms) or inter-segment `0x1FFF` keepalive gaps from prematurely forcing mid-GOP alignment.
- **Outbound Continuity Counter Rewriting**: 4-bit CCs are rewritten sequentially per PID continuously, ensuring downstream demuxers (Plex `ssegment` and VAAPI) see zero CC discontinuities when corrupted packets are dropped.
- **Native Hardware STC Timestamp Preservation, Replay Suppression & Monotonic PCR/PTS/DTS Normalization (`9a2dd6d`)**: `MpegTsSync.cacheFlow` preserves Tablo's native hardware System Time Clock (STC) `PTS`, `DTS`, and `PCR` timestamps untouched during healthy streaming. Because MPEG-2 and H.264 broadcasts transmit B-frames in decode (`DTS`) order where B-frame `PTS` values are naturally lower than the preceding I/P anchor frame (`diffFromOut < 0` within `RebaseThresholdTicks`), preserving native timestamps prevents B-frame presentation order scrambling and prevents artificial `ptsDeltaTicks` clock acceleration across video, audio, and PCR tracks. Unconfirmed single-packet PES timestamp bit-flips (`PTS` jumps `> 2s`, `DTS` outliers where `PTS - DTS < 0` or `PTS - DTS > 2s`, or malformed PES timestamp headers) trigger PES frame dropping (`droppingVideoSlice = true; requireVideoPesStart = true` for video and `droppingAudioPids` for audio, counted in `ptsScrubs`), while backward regressions into already-played timestamps of the active continuity epoch are suppressed (along with paired audio on confirmed replays) until a clean keyframe advances past `maxInputVideoPts`. Confirmed 2-packet PTS resets, discontinuity splices, and synthetic freeze-frame resumptions synchronize `ptsDeltaTicks` monotonically, re-anchor `mediaClockAnchorPts`, and preserve valid `PTS - DTS` decode offsets.

Streams fan out to subscribers via a 1024-element `BroadcastHub` buffer. When new or reconnecting clients attach to the hub, `MpegTsSync.primeClientSource` prepends cached PAT/PMT tables and multi-PID discontinuity markers, and gates the client stream via `MpegTsSync.alignToIFrame` so downstream decoders avoid mid-GOP decode errors or invalid frame dimensions on attach (subject to the 3s wall-clock fail-open alignment threshold anchored on the first dropped video packet and gated on `PUSI=1`). When upstream terminates, `watchTermination` notifies the runner, which tears down hardware leases and sends `UpstreamEnded` to `SessionManager`, immediately removing the channel entry so it is re-tunable without waiting for idle grace expiry.

```
┌──────────────────┐     ┌──────────────────┐     ┌─────────────────────────────────────────┐
│  Tablo Device    │────▶│  Stream Backend  │────▶│ ResilientHlsSource                      │
│  /watch endpoint │     │  (HlsBackend or  │     │ (Control-Plane Session Probe, 500ms     │
└──────────────────┘     │   FFmpeg)        │     │  Fast Polling, & 40ms Null Keepalive)   │
                         └──────────────────┘     └─────────────────────────────────────────┘
                                                                       │
                                                                       ▼
                                                  ┌─────────────────────────────────────────┐
                                                  │ MpegTsSync.cacheFlow                    │
                                                  │ (Slice Recovery, Outbound CC Rewriting, │
                                                  │  Audio Syncword, Timeline Normalizer,   │
                                                  │  StreamMetrics.WindowSnapshot)          │
                                                  └─────────────────────────────────────────┘
                                                                       │
                                                                       ▼
                                                  ┌─────────────────────────────────────────┐
                                                  │ BroadcastHub Fan-out                    │
                                                  │ + Header Priming & I-Frame Gate         │
                                                  └─────────────────────────────────────────┘
                                                                       │
                                                                       ▼
                                                  ┌─────────────────────────────────────────┐
                                                  │  MPEG-TS Output (Chunked HTTP)          │
                                                  └─────────────────────────────────────────┘
```

## Data Flow Workflows

### Channel Discovery Workflow

```
1. Client requests GET /lineup.json
2. LineupActor checks cache validity (1-day deadline)
3. If cache expired:
   a. If cached lineup is non-empty, serve stale lineup immediately and trigger background refresh
   b. GET /guide/channels from Tablo (refreshing Lighthouse cloud tokens on 401/403)
   c. POST /batch to Tablo with paths → returns channel details
   d. Transform each channel to HDHomeRun format:
      - GuideNumber: "{major}.{minor}"
      - GuideName: call_sign
      - URL: proxy channel URL
4. Return JSON array of channels
```

### Live TV Streaming Workflow

```
1. Client requests GET /channel/{channelId}
2. Proxy checks tuner availability via SessionManager / Lineup
3. Tuner acquisition & pre-roll keepalive:
   a. If session is already warm (active or idle grace), stream connects immediately (0 ms)
   b. If cold start tuning (8-9s hardware lock), proxy immediately returns HTTP 200 chunked response
      and emits periodic standard MPEG-TS null packets (PID 0x1FFF) every 100ms
      to bridge the startup delay and prevent Plex/HDHomeRun client read timeouts
   c. SessionManager acquires tuner from Tablo hardware (/guide/channels/{id}/watch)
   d. Receive watch response with HLS playlist URL, expiry, and keepalive metadata
   e. Use selected stream backend (FFmpeg or HLS) to produce MPEG-TS from playlist URL
   f. Pre-roll keepalive seamlessly switches directly over to real MPEG-TS data when ready, while
      client attachment primes with cached PAT/PMT, discontinuity headers, and cached locked Sequence Headers (injecting 00 00 01 B3 if attaching at GOP boundary or forced alignment), emitting decoupled audio packets immediately
4. 4th gen session maintenance:
   a. Periodically POST /player/sessions/{token}/keepalive while the client stream is active
   b. When pls.m3u8 stops advancing, HlsPlaylistPoller polls every 500ms and probes POST /player/sessions/{token}/keepalive?lh=1 starting at 12 consecutive stall polls (~6s of unchanged polls, then every 4th poll ~2s, per 3969fe1), continuing to wait on the existing HLS stream while the session exists (200 OK) and triggering an immediate cold retune when the Tablo closes the session (4xx). By default (`DefaultFreezeThreshold = 10.seconds`, `STREAM_FREEZE_THRESHOLD_SEC`), healthy bursty ~2s HLS segments run without false triggers while ResilientHlsSource.keepAlive emits standard 0x1FFF null packets every 40ms during polling intervals; when upstream video pauses or falls behind wall clock for >= freezeThreshold, FreezeFramePacer emits MPEG-2 repeat P-frames and silent AC-3/AAC frames every 100ms.
   c. MpegTsSync preserves native hardware STC PTS/DTS/PCR timestamps untouched on clean live streams across B-frames (`ptsDeltaTicks == 0L`), performs surgical slice-level dropping (droppingVideoSlice) on TEI, CC errors, and out-of-bounds slice codes (hasOutOfBoundsMpeg2Slice), ensures adaptation field flag safety (oldAfLen == 0 in stuffPacketPrefix), clamps slice rows <= maxSliceRow, locks and sanitizes 22-byte MPEG-2 Sequence Header (`00 00 01 B3`, quantiser-matrix-aware per `4c36514`) + Sequence Extension (`00 00 01 B5 1x` per `9a2e143`, cached in CachedHeaders.lockedSeqHeader), gates audio on valid syncwords (0x0B77 / 0xFFF), rewrites outbound CCs sequentially per PID, normalizes monotonic PCR/PTS/DTS timeline offsets across freeze synthesis and retunes, and caches PAT/PMT headers (persisted per channel in SessionManager.channelHeaderCache across session lifecycles for fast subsequent tunes)
5. Client teardown:
   a. On client disconnect, tuner enters idle grace period (default 180s) for instant reconnect,
      actively draining the BroadcastHub via a background sink to avoid upstream backpressure stalls
   b. If idle grace expires without reconnection, DELETE /player/sessions/{token} to release hardware tuner
   c. If upstream ends on its own (when a finite STREAM_RECOVERY_TIMEOUT_SEC is configured and reached), the runner tears down and SessionManager removes the entry immediately (no idle grace)
6. On pre-stream failure (prior to HTTP 200 commit), return SiliconDust HTTP error responses via `HDHomeRunError` with `X-HDHomeRun-Error` headers (`404` + `801 Unknown Channel`, `503` + `803 System Busy` / `805 All Tuners In Use` / `806 Tune Failed` / `807 No Video Data`). Domain error ADTs (`FourthGenError`, `LegacyError`, `HlsError`, `RangeError`, `TranscodeError` extending `NoStackTrace`) and startup `Auth.initialize` (`Try[AuthContext]`) return deterministic `Try`, union (`A | E`), or `Future.failed` values without project `throw`s; JSON decoding goes through `SafeJson.parseAs` after HTTP status validation.
```

### Program Guide Workflow

```
1. Client requests GET /guide.xml
2. GuideActor fetches channel list from Tablo
3. For each channel, attempt to fetch programs from:
   - /guide/channels/{id}/programs
   - /guide/channels/{id}/schedule
4. If no program data, generate fallback schedule (24 hours)
5. Format as XMLTV using streaming XML generation
6. Return chunked HTTP response
```

### File Transcoding Workflow (Optional)

```
1. FsMonitor spawns FsNotify worker for MEDIA_ROOT
2. FsNotify polls filesystem every 10 seconds
3. FsScan finds files matching extensions (.ts, .mkv)
4. Files without user.fs.state attribute are queued
5. FsQueue creates QueueProxy for each file
6. FFMpegDelegate transcodes using QSV acceleration:
   ffmpeg -hwaccel qsv -c:v h264_qsv -i {input} \
          -c:v h264_qsv -global_quality 30 {output}.mp4
7. On completion, set user.fs.state=encoded attribute
```

## Configuration

### Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `TABLO_GEN` | `4thgen` | Tablo generation: `4thgen` or `legacy` |
| `TABLO_IP` | `127.0.0.1` | IP address of the Tablo DVR device |
| `TABLO_PORT` | `8887` (4th gen) / `8885` (legacy) | Tablo device API port |
| `PROXY_IP` | `127.0.0.1` | IP address for the proxy to bind to |
| `STREAM_BACKEND` | `hls` | Live stream backend: `hls` or `ffmpeg` |
| `STREAM_METRICS_INTERVAL_SEC` | `30` | Interval in seconds for periodic `[stream:metrics]` operational INFO logs (`<= 0` disables periodic logs while keeping `/status.json` snapshots updated) |
| `STREAM_FREEZE_THRESHOLD_SEC` | `10` | Seconds of upstream video silence or media-clock deficit before `FreezeFramePacer` synthesizes repeat P-frames and silent audio (`<= 0` disables freeze-frame synthesis) |
| `STREAM_AUDIO_LOSS_TIMEOUT_SEC` | `30` | Seconds of active non-frozen live video (`>= 150` frames) with zero recovered primary-PID audio before triggering an automatic cold retune (`<= 0` disables; exponential backoff up to `300s`) |
| `STREAM_HLS_MAX_STALL_SEC` | `60` | Maximum seconds an unchanged HLS playlist may stall before escalating to `HlsError.PlaylistStalled` and cold retune (`<= 0` disables cap) |
| `STREAM_STALL_TIMEOUT_SEC` | `45` | Resilient stall timeout configuration in seconds (`0` disables byte-stall watchdog; 4th-gen HLS uses control-plane keepalive session probing) |
| `STREAM_TUNE_TIMEOUT_SEC` | `30` | Max seconds to wait for tune / cold start data |
| `STREAM_RETRY_DELAY_SEC` | `1` | Delay in seconds between retune attempts |
| `STREAM_MAX_RETRY_DELAY_SEC` | `15` | Maximum backoff delay in seconds for prolonged outages |
| `STREAM_RECOVERY_TIMEOUT_SEC` | `0` | Total outage duration before cleanly ending stream (`0` = infinite recovery while client is connected) |
| `STREAM_ENABLE_SLATE_VIDEO` | `false` | Emit looping slate video during dropouts. Default `false` uses freeze-frame null packets. |
| `STREAM_PRE_ROLL_KEEP_ALIVE` | `true` | Emit periodic null MPEG-TS packets during cold tune to prevent client timeouts |
| `STREAM_PRE_ROLL_INTERVAL_MS`| `100` | Interval in ms between pre-roll keepalive chunks |
| `STREAM_PRE_ROLL_PACKETS` | `7` | Number of 188-byte null packets per keepalive chunk (7 = 1316 bytes MTU) |
| `SESSION_IDLE_GRACE_SEC` | `180` | Idle grace period (in seconds) to retain tuner session |
| `TUNER_STATE_POLL_INTERVAL_SEC` | `30` | Interval in seconds to poll and log Tablo 4th Gen hardware tuner state (`<= 0` disables polling) |
| `MEDIA_ROOT` | (none) | Optional path for media file transcoding |

### Fixed Configuration

| Setting | Value | Description |
|---------|-------|-------------|
| Tablo Port | 8887 (4th gen) / 8885 (legacy) | Standard Tablo API port |
| Proxy Port | 8080 | HDHomeRun proxy listening port |
| Protocol | HTTP | All communication is unencrypted |

## Build System

### Gradle Tasks

| Task | Description |
|------|-------------|
| `gradle build` | Compile and test |
| `gradle shadowJar` | Create uber-JAR with all dependencies |
| `gradle nativeImage` | Build GraalVM native executable |
| `gradle scalaCli` | Run Scala CLI build |

### Native Image Build

The project includes custom Gradle plugins in `buildSrc/`:

#### NativeImageTask Options

- `--static` - Statically linked binary
- `--libc=musl` - Use musl libc for smaller binaries
- `-march=native` - Optimize for host CPU

#### Heap Configuration

- Min Heap: 32MB
- Max Heap: 128MB
- Max New Gen: 64MB

#### JVM & Dispatcher Runtime Configuration

- **JVM Runtime Alignment (`Dockerfile.jvm`, Java 25)**: Runs with `-XX:+UseCompactObjectHeaders -XX:+UseSerialGC -XX:SharedArchiveFile=app-cds.jsa -Xshare:auto -Xms32m -Xmx128m -XX:MaxNewSize=64m -Xss256k -XX:+ExitOnOutOfMemoryError` (matching Native Image Serial GC and heap ceilings while loading a build-time AppCDS archive and keeping Tiered/C2 JIT active).
- **Artifact & Resource Packaging**: `shadowJar` excludes Scala 3 compile-time `**/*.tasty` reflection metadata not needed at runtime, and Gradle `mergeReferenceConf` declares input JARs and `layout.buildDirectory` outputs for incremental builds.
- **Native Container Runtime (`Dockerfile.native`)**: `FROM scratch` includes `/etc/ssl/certs/ca-certificates.crt` (`SSL_CERT_FILE`), `/usr/share/zoneinfo`, `STOPSIGNAL SIGTERM`, OCI metadata labels, and a built-in `HEALTHCHECK` probing `/opt/tablo2hdhomerun/tablo2hdhomerun --healthcheck`.
- **Pekko Dispatcher & Pool Bounds (`application.conf`, `2a70433`)**: `default-dispatcher.fork-join-executor.parallelism-min = 2`, `internal-dispatcher.fork-join-executor.parallelism-min = 1`, `default-blocking-io-dispatcher.thread-pool-executor.fixed-pool-size = 4`, `http.host-connection-pool.max-connections = 8`, and `coordinated-shutdown.default-phase-timeout = 10 s`.
- **Healthcheck Fast-Path**: `--healthcheck` resolves `PROXY_IP`/`PROXY_PORT` before full `AppConfig.load()` initialization and runs cleanly with `-XX:MinHeapSize=4m -XX:MaxHeapSize=16m -XX:MaxNewSize=8m`.

## Project Structure

```
tablo2hdhomerun/
├── build.gradle              # Main build configuration
├── settings.gradle           # Project settings
├── buildSrc/                 # Custom Gradle plugins
│   └── src/main/groovy/
│       └── compiler/
│           ├── NativeImage.groovy    # Native image task
│           └── ScalaNative.groovy    # Scala native support
├── src/main/
│   ├── scala/app/
│   │   ├── Tablo2HDHomeRun.scala        # Main application
│   │   ├── config/                      # Typed configuration (AppConfig)
│   │   ├── sys/                         # System utilities, config logging, transcode & JSON helpers
│   │   │   ├── SafeJson.scala           # Safe Try-based Spray-JSON decoding
│   │   │   └── System.scala             # FsUtil, FsScan, FFMpegDelegate, TranscodeError
│   │   ├── stream/                      # Stream backends & MPEG-TS sanitization
│   │   │   ├── StreamBackend.scala      # Trait and factory
│   │   │   ├── FFmpegBackend.scala      # FFmpeg subprocess backend
│   │   │   ├── FreezeFramePacer.scala   # MPEG-2/H.264/audio sanitization & freeze synthesis
│   │   │   ├── HlsBackend.scala         # HLS-native backend
│   │   │   ├── HlsError.scala           # Flat HlsError & RangeError ADTs (NoStackTrace)
│   │   │   ├── HlsPlaylistFetch.scala   # Conditional playlist request helpers
│   │   │   ├── HlsPlaylistPoller.scala  # Adaptive HLS polling & session-presence probing
│   │   │   ├── HlsSegmentFetch.scala    # Byte-range segment fetch validation
│   │   │   ├── M3U8.scala               # M3U8 playlist parser
│   │   │   ├── MpegTsDiscontinuity.scala # MPEG-TS continuity & PCR/PTS helpers
│   │   │   ├── MpegTsSync.scala         # Slice recovery, CC rewriting, I-frame alignment, stream metrics
│   │   │   ├── MpegTsTypes.scala        # Opaque types (Pid, ContinuityCounter, PtsTicks, OptPts)
│   │   │   ├── ResilientHlsSource.scala # Outer stream supervisor & keepalive
│   │   │   └── StreamMetrics.scala      # Signal/feed/symbol quality snapshot & HLS collector
│   │   └── tuner/                       # Tablo legacy & 4th gen implementations
│   │       ├── HDHomeRunDiscovery.scala # UDP 65001 broadcast discovery responder
│   │       ├── HDHomeRunError.scala     # SiliconDust HTTP status & X-HDHomeRun-Error mapper
│   │       ├── HDHomeRunStatus.scala    # SiliconDust GET /status.json tuner telemetry model
│   │       ├── Tablo4thGen.scala        # 4th gen API, SessionManager, WatchSession
│   │       ├── TabloLegacy.scala        # Legacy Tablo API
│   │       └── TunerErrors.scala        # Flat FourthGenError, LegacyError ADTs & package aliases
│   └── resources/META-INF/
│       └── native-image/                # GraalVM configuration
│           └── reachability-metadata.json
├── Dockerfile.jvm            # JVM-based container
├── Dockerfile.native         # Native image container
└── docs/                     # Documentation
    ├── ARCHITECTURE.md       # This file
    ├── USAGE.md              # Usage guide
    ├── session-manager.md    # 4th gen SessionManager & resilience design
    └── playback-scenarios/         # Empirical OTA playback scenario analyses & benchmarks
```

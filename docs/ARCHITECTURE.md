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
   - **Locked Sequence Header Caching & Fast-Attach Injection**: `MpegTsSync.CachedHeaders` caches `lockedSeqHeader: Option[ByteString]` once confirmed by `CacheStage`. When `AlignToIFrameStage` aligns on a GOP header (`00 00 01 B8`) without an in-band Sequence Header or forces alignment at `maxDroppedPackets` (8000), it prepends `FreezeFramePacer.synthesizeSequenceHeaderPacket` (`PUSI = 1` 188-byte TS packet carrying a 9-byte PES header + 12-byte `00 00 01 B3` Sequence Header) so FFmpeg always initializes valid frame dimensions instead of logging `[mpeg2video] Invalid frame dimensions 0x0`.
   - **Decoupled Audio Alignment**: `AlignToIFrameStage` decouples audio alignment from video keyframe alignment. Dropping `knownAudioPids` packets while `isAligned == false` previously caused FFmpeg's `avformat_find_stream_info` probe on fast-attach (`BroadcastHub` inrush during `idle-grace`) to see zero audio packets in the first 1.5 MB and permanently lock a Video-Only transcode pipeline (`streamType="2"` omitted, Failure Mode G). `AlignToIFrameStage` now aligns each audio PID independently on its first `PUSI = 1` PES packet (with valid AC-3 `0x0B77` / ADTS `0xFFF` syncword when streamType is known) and emits audio packets immediately even while `isAligned == false` for video. Null packets (`0x1FFF`) and audio packets are excluded from the `droppedPackets` counter (`maxDroppedPackets = 8000`), ensuring only dropped video packets count toward forced alignment.
   - Continuously rewrites outbound 4-bit Continuity Counters (`CC`) per PID so downstream demuxers (Plex `ssegment` and VAAPI) see zero `CC` discontinuities when corrupted packets are dropped.
2. **Control-Plane Session-Presence Probing, `500ms` Fast Polling & Cold Retune (`HlsPlaylistPoller`, `ResilientHlsSource`, `WatchSession`)**:
   - Polls `pls.m3u8` every `500ms` whenever `lastAdvanced == false` (`304 Not Modified` or unchanged sequence) and disables arbitrary byte-stall timeouts (`stallTimeout = Duration.Zero` by default) so distorted reception rides out on the existing hardware tuner lock for as long as the Tablo keeps the `/watch` session open rather than thrashing tuner state.
   - **Active Keepalive Session Probing (`checkSessionAlive` & `WatchSession.classifyProbeResult`)**: Every 4th consecutive `500ms` stall poll (`~2s`, `sessionProbeIntervalPolls = 4`) or immediately when `pls.m3u8` returns `404 Not Found`, `HlsPlaylistPoller.resolveStallOutcome` invokes `checkSessionAlive` (`POST /player/sessions/{token}/keepalive?lh=1`). While the Tablo reports `200 OK` (`ProbeOutcome.Alive`), the stream stays attached (`404` on `pls.m3u8` is treated as a transient error up to `pollFailuresMax`). When the Tablo returns `4xx` (`ProbeOutcome.ClosedByTablo`) or transient probe errors exceed `pollFailuresMax` (`ProbeOutcome.ProbeExhausted`), `HlsBackend` fails immediately with `HlsError.SessionEnded` and `SessionRunner` executes `WatchSession.coldRetune` (`POST /watch`, skipping `DELETE` when the Tablo already closed the session).
3. **Burst-Safe HLS Segment Handling & 10s Freeze-Frame Pacer ([`FreezeFramePacer`](file:///home/bdina/git/tablo2hdhomerun/src/main/scala/app/stream/FreezeFramePacer.scala))**:
   - **Burst-Safe HLS Segment Handling (`DefaultFreezeThreshold = 10.seconds`, `STREAM_FREEZE_THRESHOLD_SEC`)**: Because HLS segments (~2.0s of media) are fetched in ~50ms network bursts followed by 1.5s–2.4s polling intervals, the default freeze threshold is set to `10.seconds` (well above normal inter-segment intervals and moderate jitter) so synthetic frames are never injected between healthy HLS segments and leading audio/video packets of subsequent segments are never gated. During normal inter-segment intervals and brief RF pauses (`< 10s`), `ResilientHlsSource.keepAlive` emits standard `0x1FFF` null packets every 40ms while client decoders naturally hold the last decoded frame.
   - **Active Freeze-Frame Synthesis on Prolonged Stalls (`>= 10s`)**: When an upstream Tablo segment stall or media-clock deficit reaches `freezeThreshold` (default `10s`, configurable via `STREAM_FREEZE_THRESHOLD_SEC`), `FreezeFramePacer` emits live-compatible `PAT`/`PMT`, MPEG-2 repeat/skip P-frames (or H.264 keepalive access units) on `videoPid`, and silent AC-3 (`0x0B77` with valid CRC-16) or ADTS AAC frames on `audioPids` every `100ms`, ensuring Plex's ingest grabber (`-codec copy -f ssegment`) and downstream client HLS buffers continue advancing without timing out. Resumes live A/V seamlessly on the first live keyframe start header (`hasKeyframeHeader`).
   - **Video-Anchored Activation**: `lastLiveDataNanos` in `CacheStage` is anchored strictly to **emitted live video packets** (`!freezeActive && !droppingVideoSlice`), preventing incoming `PAT`/`PMT`, audio packets, or discarded non-keyframe packets during `freezeActive` from suppressing timer-driven freeze-frame synthesis.
   - **Real-Time Media Clock Anchor & Deficit Catch-Up**: `CacheStage` tracks `mediaClockAnchorPts` and `mediaClockAnchorNanos`. If upstream trickle collapse delivers sparse video and `lastOutputPts` falls behind real-time wall clock by `>= freezeThreshold`, it synthesizes catch-up freeze frames + silent audio (up to 6 frames per `100ms` tick) while rebasing subsequent live timestamps seamlessly.
4. **B-Frame-Safe Native STC Timestamp Preservation & Always-On Monotonic Timeline Normalizer**:
   - **B-Frame-Safe Native Hardware STC Timestamp Preservation**: `MpegTsSync.cacheFlow` preserves Tablo's native hardware System Time Clock (STC) `PTS`, `DTS`, and `PCR` timestamps untouched on clean streams (`ptsDeltaTicks == 0L`). Because MPEG-2 and H.264 broadcasts transmit B-frames in decode (`DTS`) order where B-frame `PTS` values are naturally lower than the preceding I/P anchor frame (`diffFromOut < 0` within `RebaseThresholdTicks` = `0.5s`), allowing negative decode-order `PTS` diffs within `RebaseThresholdTicks` prevents B-frame presentation order scrambling and prevents artificial `ptsDeltaTicks` clock acceleration across video, audio, and PCR tracks.
   - **Always-On Monotonic `PCR` / `PTS` / `DTS` Normalizer**: `normalizePacketTimestamps` is always active in `CacheStage` (even when `freezeThreshold` is set to `0`), scrubbing single-packet bit-flipped PES timestamp outliers (`> 2s` jump) in-place so corrupt timestamps never cause downstream A/V skew spikes, and maintaining a continuous monotonic output offset (`ptsDeltaTicks`) across synthetic freeze-frame injection, media-clock deficit catch-up, and `/watch` session retunes.

### Acceptable Data Loss & Resiliency Philosophy

Over-the-air broadcast streams during antenna flutter, wind gusts, or fringe reception inevitably suffer transport data loss. The proxy's design deliberately prioritizes **uninterrupted client playback and zero human intervention by isolating stream damage surgically while preserving tuner lock**.

- **Ride out dropouts on existing hardware tuner lock via control-plane session probing**: Early implementations triggered rapid cold retunes on dropouts (e.g. 8s stall watchdog and immediate `304 Not Modified` escalation in `6598c07`, or 16s clean-keyframe starvation in `c57c238`). Under the 4-pillar architecture, arbitrary byte-stall timeouts are disabled by default (`stallTimeout = Duration.Zero`) and the playlist poller drops to a rapid 500ms cadence during stalls, probing `POST /player/sessions/{token}/keepalive?lh=1` every 4th consecutive 500ms stall poll (~2s) or immediately on playlist `404` so transient RF nulls recover on the existing hardware tuner lock for as long as the Tablo keeps the session open.
- **Surgical slice-level sanitization & out-of-bounds slice gating over whole-GOP gating**: When corrupted TS packets arrive (flagged with `TEI` or broken CC), earlier versions dropped the entire video GOP (`corruptVideoGop` in `c57c238`). The proxy now isolates damage to individual MPEG-2 slices (`droppingVideoSlice`), immediately resuming on the next valid slice start code (`00 00 01 [01..maxSliceRow]`) or picture header, converting orphaned partial-slice fragments into adaptation field stuffing and preserving adaptation-field PCRs. Unflagged corrupt packets containing out-of-bounds MPEG-2 slice start codes (`code > maxSliceRow`) trigger surgical slice dropping (`droppingVideoSlice = true`, replaced with `0x1FFF` null packets or raw `pcrOnlyPacket`) rather than in-place start-code zeroing (which would merge corrupt bytes into the preceding slice). When [`stuffPacketPrefix`](file:///home/bdina/git/tablo2hdhomerun/src/main/scala/app/stream/FreezeFramePacer.scala#L376-L423) expands a 1-byte adaptation field (`afc == 3, oldAfLen == 0`), it initializes the new adaptation flags byte to `0x00` so bogus `discontinuity_indicator` or `PCR` flags are never set.
- **Demuxer and transcoder safety (VAAPI poison pills & header injection)**: FFmpeg and hardware VAAPI decoders are protected from crashing on broadcast bit errors:
  - Slice row vertical positions are clamped to `<= maxSliceRow` (e.g., row 68 for 1080i) to eliminate `slice below image` fatal decoder asserts.
  - The 12-byte MPEG-2 Sequence Header (`00 00 01 B3`) geometry is locked once established and cached in `CachedHeaders.lockedSeqHeader`. If a client attaches at a GOP boundary missing an in-band Sequence Header or reaches forced alignment, the proxy synthesizes a `00 00 01 B3` packet to prevent `Invalid frame dimensions 0x0`.
  - Corrupted audio streams are silenced until a `PUSI=1` packet with a verified audio syncword (`0x0B77` for AC-3 with valid CRC-16, or `0xFFF` for ADTS AAC) arrives.
  - Outbound 4-bit Continuity Counters (`CC`) are rewritten continuously per PID so downstream muxers never see CC jumps when corrupt packets are dropped.
- **Decoupled audio alignment protecting client format probing**: Gating audio on video keyframes previously caused FFmpeg's `avformat_find_stream_info` probe on fast-attach (`BroadcastHub` inrush during `idle-grace`) to starve of audio in the first 1.5 MB, locking into a video-only transcode pipeline. Audio alignment is now fully decoupled: each audio PID aligns on its first `PUSI=1` PES packet and begins emitting immediately.
- **Burst-Safe HLS Segment Handling & Freeze Pacing**: Because HLS segments (~2.0s of media) are fetched in ~50ms network bursts followed by 1.5s–2.0s polling intervals, synthetic freeze-frame injection is disabled by default (`DefaultFreezeThreshold = Duration.Zero`) so synthetic frames are never injected between healthy HLS segments and leading audio/video packets of subsequent segments are never gated. During inter-segment intervals and brief RF pauses, `ResilientHlsSource.keepAlive` emits standard `0x1FFF` null packets every 40ms while client decoders naturally hold the last decoded frame. When configured with a positive threshold, `FreezeFramePacer` generates valid MPEG-2 repeat P-frames and silent audio every 100ms whenever segment progress pauses. Video-anchoring ensures incoming audio or non-video packets cannot suppress freeze-frame pacing. If upstream segment trickle reduces delivered video below real-time cadence, `CacheStage` detects the media-clock deficit against wall clock and emits up to 6 catch-up frames per tick, maintaining an uninterrupted 1s segment cadence in Plex's ingest grabber.
- **Native Hardware STC Timestamp Preservation & Timeline Continuity**: By default (`DefaultFreezeThreshold = Duration.Zero`), `MpegTsSync.cacheFlow` preserves Tablo's native hardware System Time Clock (STC) `PTS`, `DTS`, and `PCR` timestamps untouched. Because MPEG-2 and H.264 broadcasts transmit B-frames in decode (`DTS`) order where B-frame `PTS` values are naturally lower than the preceding I/P anchor frame (`diffFromOut < 0` within `RebaseThresholdTicks`), preserving native timestamps prevents B-frame presentation order scrambling and prevents artificial `ptsDeltaTicks` clock acceleration across video, audio, and PCR tracks. PES timestamp bit-flips (> 2s jumps) are scrubbed when normalization is active, and a monotonic offset (`ptsDeltaTicks`) normalizes PCR, PTS, and DTS across synthetic freeze-frame frames, session restarts, and retunes.
- **Immediate cold retune on session closure**: When the Tablo closes the `/watch` session (`4xx` on keepalive probe or `#EXT-X-ENDLIST` on `pls.m3u8`) or transient probe errors exceed `pollFailuresMax` (default 4), `WatchSession.coldRetune` immediately establishes a new `/watch` session (skipping `DELETE` when the Tablo already closed the old session, resetting `lastSeqRef` for the new session, and applying `STREAM_TUNE_TIMEOUT_SEC` = 20s and `RestartSource.withBackoff` retry bounds) while `ResilientHlsSource.keepAlive` maintains downstream stream continuity.

## Technology Stack

| Component | Technology |
|-----------|------------|
| Language | Scala 3.9.0 |
| Runtime | JVM (Java 24+) or GraalVM Native Image |
| HTTP Framework | Apache Pekko HTTP 1.4.0 |
| Actor System | Apache Pekko Actor Typed 1.7.0 |
| JSON | Spray JSON |
| XML | scala-xml |
| Build | Gradle 9.3.1 with Shadow plugin 9.6.1 |
| Containerization | Docker (Ubuntu 24.04 base) |

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

#### GuideActor

- Manages program guide cache (1-hour TTL)
- Fetches program schedules for all channels
- Generates fallback programming when Tablo API unavailable
- Singleton pattern to reduce resource usage

#### FsMonitor / FsNotify

- Optional filesystem monitoring for media file transcoding
- Scans directories for `.ts` and `.mkv` files
- Tracks processed files via extended file attributes (`user.fs.state`)

#### FFMpegDelegate

- Manages FFmpeg transcoding processes
- Handles lifecycle (start, status, stop)
- Intel QuickSync Video (QSV) hardware acceleration support

### Stream Processing Pipeline

The live channel stream can use one of two backends, selected by `STREAM_BACKEND`:

- **hls** (default): Fetches M3U8 playlists and TS segments directly via HTTP; no external process. Optimized for HLS v4 byte-range playlists with sub-second adaptive polling (polling at 75% of actual segment duration after advance and down to 500ms whenever unchanged or stalling via `HlsPlaylistPoller`), conditional playlist requests (`ETag` / `Last-Modified`), safe byte-range validation (filtering zero-length or negative sub-ranges to prevent range header errors), tolerant `206 Partial Content` validation for ranged segment fetches (accepting short reads during reception drops), per-segment packet alignment via `MpegTsSync.alignPackets`, and automatic discontinuity + I-frame realignment after any sequence gap, short read, or segment fetch error. When `pls.m3u8` stops advancing (`stallPolls > 0`), `HlsPlaylistPoller` probes the Tablo 4th-Gen control plane (`POST /player/sessions/{token}/keepalive?lh=1`) every 4th consecutive 500ms stall poll (`~2s`) via `checkSessionAlive`, continuing to wait on the existing HLS stream while the session exists (`200 OK`) and failing with `HlsError.SessionEnded` immediately when the Tablo closes the session (`4xx`).
- **ffmpeg**: Spawns an FFmpeg subprocess to convert HLS to MPEG-TS. Requires FFmpeg on PATH. Includes reconnect and error-detect flags to survive transient stream drops.

The inner per-tune stream is bounded at startup by `StallWatchdog` (`STREAM_TUNE_TIMEOUT_SEC`, default 20s for initial connection / hardware cold tuning) and wrapped by `ResilientHlsSource`. Once initial bytes arrive, HLS liveness is governed by control-plane session-presence probing rather than an arbitrary byte-stall timer (`stallTimeout = Duration.Zero` by default), so an active `/watch` session rides out RF dropouts on its existing hardware tuner lock for as long as the Tablo keeps the session open. Because HLS segments (~2.0s of media) are fetched in ~50ms network bursts followed by 1.5s–2.0s polling intervals, synthetic freeze-frame injection is disabled by default (`DefaultFreezeThreshold = Duration.Zero`) so synthetic frames are never injected between healthy HLS segments and leading audio/video packets of subsequent segments are never gated. During inter-segment intervals and RF pauses, `ResilientHlsSource.keepAlive` emits standard `0x1FFF` null packets every 40ms while client decoders naturally hold the last decoded frame. When configured with a positive freeze threshold (`freezeThreshold > Duration.Zero`), `FreezeFramePacer` emits live-compatible `PAT`/`PMT`, MPEG-2 repeat/skip P-frames (or H.264 keepalive access units) on `videoPid`, and silent AC-3 (`0x0B77` with valid CRC-16) or ADTS AAC frames on `audioPids` every 100ms when pauses exceed the threshold. This keeps Plex's ingest grabber (`-codec copy -f ssegment`) writing 1s segments continuously of the frozen picture. Within `MpegTsSync.CacheStage`, activation is strictly **video-anchored**: `lastLiveDataNanos` updates solely on emitted live video packets (`!freezeActive && !droppingVideoSlice`), preventing incoming PAT/PMT, audio, or discarded packets during freeze states from resetting the pacer. Furthermore, `CacheStage` anchors a real-time media clock (`mediaClockAnchorPts`, `mediaClockAnchorNanos`): when upstream segment trickle causes `lastOutputPts` to fall behind wall clock by `>= freezeThreshold`, it synthesizes catch-up freeze frames + silent audio (up to 6 frames per 100ms tick) while seamlessly rebasing subsequent live video timestamps. When live video resumes, live A/V is restored seamlessly on the first live keyframe start header (`hasKeyframeHeader`).

When the Tablo closes the session (`404`/`410`/`401`/`403`/`400` on `POST /player/sessions/{token}/keepalive?lh=1` or `#EXT-X-ENDLIST` / `404` on `pls.m3u8`) or after `pollFailuresMax` (default 4) consecutive transient errors, `HlsBackend` fails with `HlsError.SessionEnded` and `SessionRunner` immediately fires a cold retune (`WatchSession.coldRetune` → `POST /watch`, skipping `DELETE` when the keepalive probe already confirmed the session is closed). `RestartSource.withBackoff` applies exponential backoff starting at `STREAM_RETRY_DELAY_SEC` (default 1s) up to `STREAM_MAX_RETRY_DELAY_SEC` (default 15s). HLS media sequence tracking (`lastSeqRef`) is reset on cold retunes so that fresh live-edge segments (`STREAM_RECOVERY_LIVE_EDGE_SEGMENTS`, default 3) immediately replenish client buffers. When fresh broadcast bytes resume following a retune, `ResilientHlsSource` passes incoming data through `MpegTsSync.alignToIFrame` (dropping leading video slices until a codec-matched MPEG-2 Sequence/GOP or H.264 IDR/SPS boundary is hit and aligning audio independently) and prepends a single resume prefix consisting of multi-PID discontinuity packets (video, audio, PCR, and null PIDs) and cached PAT/PMT headers. `RecoveryTimeout` monitors end-to-end byte progress: by default (`STREAM_RECOVERY_TIMEOUT_SEC=0`), recovery continues indefinitely while the viewer stays connected; if configured to a positive value, the stream completes cleanly after that duration without real broadcast data.

Before fanning out via `BroadcastHub`, streams pass through `MpegTsSync.cacheFlow`, `KillSwitches.single`, and `watchTermination`. `MpegTsSync.cacheFlow` executes surgical slice-level sanitization, poison pill protection, outbound continuity counter rewriting, and native timestamp preservation:
- **Slice-Level Sanitization & Out-of-Bounds Gating**: Rather than dropping whole GOPs on `TEI` or `CC` jumps (`corruptVideoGop` in `c57c238`), `MpegTsSync` flags `droppingVideoSlice` and resumes decoding on the next valid MPEG-2 slice start code (`00 00 01 [01..maxSliceRow]`) or `PUSI=1` frame header. Unflagged corrupt packets containing out-of-bounds MPEG-2 slice start codes (`code > maxSliceRow`) trigger surgical slice dropping (`droppingVideoSlice = true`, replaced with `0x1FFF` null packets or raw `pcrOnlyPacket`) rather than in-place start-code zeroing (which would merge corrupt bytes into the preceding slice). When `stuffPacketPrefix` expands a 1-byte adaptation field (`afc == 3, oldAfLen == 0`), it initializes the new adaptation flags byte to `0x00` so bogus `discontinuity_indicator` or `PCR` flags are never set. Partial-slice prefix bytes are converted to adaptation field stuffing, and adaptation-field PCRs are preserved on `TEI=0` packets.
- **FFmpeg/VAAPI Poison Pill Protection & Sequence Header Injection**: Slice row vertical positions are clamped to `<= maxSliceRow` (e.g. `<= 68` on 1080i) to eliminate `slice below image` decoder crashes. The 12-byte MPEG-2 Sequence Header (`00 00 01 B3`) geometry is locked once confirmed and cached in `MpegTsSync.CachedHeaders.lockedSeqHeader`. When `AlignToIFrameStage` aligns on a GOP header (`00 00 01 B8`) without an in-band Sequence Header or reaches forced alignment at `maxDroppedPackets` (8000), it injects `FreezeFramePacer.synthesizeSequenceHeaderPacket` (a 188-byte `PUSI = 1` TS packet carrying PES header + locked sequence header) so FFmpeg decoders always initialize valid frame dimensions rather than failing with `Invalid frame dimensions 0x0`. Corrupted audio PIDs are gated until a `PUSI=1` packet arrives with a valid `0x0B77` (AC-3 with valid CRC-16) or `0xFFF` (ADTS AAC) syncword.
- **Decoupled Audio Alignment**: To prevent FFmpeg's `avformat_find_stream_info` probe from failing on fast-attach (`BroadcastHub` inrush during `idle-grace`), `AlignToIFrameStage` decouples audio alignment from video keyframe alignment. Each audio PID aligns independently on its first `PUSI = 1` PES packet and begins emitting immediately even while `isAligned == false` for video. Null packets (`0x1FFF`) and audio packets are excluded from the `droppedPackets` counter (`maxDroppedPackets = 8000`), ensuring only dropped video packets count toward forced alignment and preventing audio starvation in the initial 1.5 MB probe buffer (Failure Mode G).
- **Outbound Continuity Counter Rewriting**: 4-bit CCs are rewritten sequentially per PID continuously, ensuring downstream demuxers (Plex `ssegment` and VAAPI) see zero CC discontinuities when corrupted packets are dropped.
- **Native Hardware STC Timestamp Preservation & Monotonic PCR/PTS/DTS Normalization**: `MpegTsSync.cacheFlow` preserves Tablo's native hardware System Time Clock (STC) `PTS`, `DTS`, and `PCR` timestamps untouched during healthy streaming. Because MPEG-2 and H.264 broadcasts transmit B-frames in decode (`DTS`) order where B-frame `PTS` values are naturally lower than the preceding I/P anchor frame (`diffFromOut < 0` within `RebaseThresholdTicks`), preserving native timestamps prevents B-frame presentation order scrambling and prevents artificial `ptsDeltaTicks` clock acceleration across video, audio, and PCR tracks. Unconfirmed single-packet PES timestamp bit-flips (`PTS` jumps `> 2s`, `DTS` outliers where `PTS - DTS < 0` or `PTS - DTS > 2s`, or malformed PES timestamp headers) trigger PES frame dropping (`droppingVideoSlice = true; requireVideoPesStart = true` for video and `droppingAudioPids` for audio, counted in `ptsScrubs`) so neither corrupted `DTS` timestamps nor headless elementary slices reach downstream FFmpeg segmenters, while confirmed 2-packet PTS resets and synthetic freeze-frame injections rebase `ptsDeltaTicks` monotonically and preserve valid `PTS - DTS` decode offsets.

Streams fan out to subscribers via a 1024-element `BroadcastHub` buffer. When new or reconnecting clients attach to the hub, `MpegTsSync.primeClientSource` prepends cached PAT/PMT tables and multi-PID discontinuity markers, and gates the client stream via `MpegTsSync.alignToIFrame` so downstream decoders avoid mid-GOP decode errors or invalid frame dimensions on attach (subject to the 8000-packet fail-open alignment threshold). When upstream terminates, `watchTermination` notifies the runner, which tears down hardware leases and sends `UpstreamEnded` to `SessionManager`, immediately removing the channel entry so it is re-tunable without waiting for idle grace expiry.

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
   b. When pls.m3u8 stops advancing, HlsPlaylistPoller polls every 500ms and probes POST /player/sessions/{token}/keepalive?lh=1 every 4th consecutive stall poll (~2s), continuing to wait on the existing HLS stream while the session exists (200 OK) and triggering an immediate cold retune when the Tablo closes the session (4xx). By default (DefaultFreezeThreshold = Duration.Zero), synthetic freeze-frame injection is disabled to handle bursty ~2s HLS segments safely without false triggers, while ResilientHlsSource.keepAlive emits standard 0x1FFF null packets every 40ms during polling intervals. When active freeze pacing is configured, FreezeFramePacer emits MPEG-2 repeat P-frames and silent AC-3/AAC frames every 100ms when upstream pauses exceed the threshold.
   c. MpegTsSync preserves native hardware STC PTS/DTS/PCR timestamps untouched across B-frames (DefaultFreezeThreshold = Duration.Zero), performs surgical slice-level dropping (droppingVideoSlice) on TEI, CC errors, and out-of-bounds slice codes (hasOutOfBoundsMpeg2Slice), ensures adaptation field flag safety (oldAfLen == 0 in stuffPacketPrefix), clamps slice rows <= maxSliceRow, locks sequence header geometry (cached in CachedHeaders.lockedSeqHeader), gates audio on valid syncwords (0x0B77 / 0xFFF), rewrites outbound CCs sequentially per PID, normalizes monotonic PCR/PTS/DTS timeline offsets when active, and caches PAT/PMT headers (persisted per channel in SessionManager.channelHeaderCache across session lifecycles for fast subsequent tunes)
5. Client teardown:
   a. On client disconnect, tuner enters idle grace period (default 180s) for instant reconnect,
      actively draining the BroadcastHub via a background sink to avoid upstream backpressure stalls
   b. If idle grace expires without reconnection, DELETE /player/sessions/{token} to release hardware tuner
   c. If upstream ends on its own (when a finite STREAM_RECOVERY_TIMEOUT_SEC is configured and reached), the runner tears down and SessionManager removes the entry immediately (no idle grace)
6. If no tuners: return 503 Service Unavailable
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
| `STREAM_STALL_TIMEOUT_SEC` | `45` | Resilient stall timeout configuration in seconds (`0` disables byte-stall watchdog; 4th-gen HLS uses control-plane keepalive session probing) |
| `STREAM_TUNE_TIMEOUT_SEC` | `20` | Max seconds to wait for tune / cold start data |
| `STREAM_RETRY_DELAY_SEC` | `1` | Delay in seconds between retune attempts |
| `STREAM_MAX_RETRY_DELAY_SEC` | `15` | Maximum backoff delay in seconds for prolonged outages |
| `STREAM_RECOVERY_TIMEOUT_SEC` | `0` | Total outage duration before cleanly ending stream (`0` = infinite recovery while client is connected) |
| `STREAM_ENABLE_SLATE_VIDEO` | `false` | Emit looping slate video during dropouts. Default `false` uses freeze-frame null packets. |
| `STREAM_PRE_ROLL_KEEP_ALIVE` | `true` | Emit periodic null MPEG-TS packets during cold tune to prevent client timeouts |
| `STREAM_PRE_ROLL_INTERVAL_MS`| `100` | Interval in ms between pre-roll keepalive chunks |
| `STREAM_PRE_ROLL_PACKETS` | `7` | Number of 188-byte null packets per keepalive chunk (7 = 1316 bytes MTU) |
| `SESSION_IDLE_GRACE_SEC` | `180` | Idle grace period (in seconds) to retain tuner session |
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

- Min Heap: 128MB
- Max Heap: 128MB
- Max New Gen: 64MB

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
│   │   ├── stream/                      # Stream backends & MPEG-TS sanitization
│   │   │   ├── StreamBackend.scala      # Trait and factory
│   │   │   ├── FFmpegBackend.scala      # FFmpeg subprocess backend
│   │   │   ├── FreezeFramePacer.scala   # MPEG-2/H.264/audio sanitization & freeze synthesis
│   │   │   ├── HlsBackend.scala         # HLS-native backend
│   │   │   ├── HlsPlaylistFetch.scala   # Conditional playlist request helpers
│   │   │   ├── HlsPlaylistPoller.scala  # Adaptive HLS polling & session-presence probing
│   │   │   ├── HlsSegmentFetch.scala    # Byte-range segment fetch validation
│   │   │   ├── M3U8.scala               # M3U8 playlist parser
│   │   │   ├── MpegTsSync.scala         # Slice recovery, CC rewriting, I-frame alignment, stream metrics
│   │   │   ├── ResilientHlsSource.scala # Outer stream supervisor & keepalive
│   │   │   └── StreamMetrics.scala      # Signal/feed/symbol quality snapshot & HLS collector
│   │   └── tuner/                       # Tablo legacy & 4th gen implementations
│   │       ├── HDHomeRunDiscovery.scala # UDP 65001 broadcast discovery responder
│   │       ├── HDHomeRunStatus.scala    # SiliconDust GET /status.json tuner telemetry model
│   │       ├── Tablo4thGen.scala        # 4th gen API, SessionManager, WatchSession
│   │       └── TabloLegacy.scala        # Legacy Tablo API
│   └── resources/META-INF/
│       └── native-image/                # GraalVM configuration
│           └── reachability-metadata.json
├── Dockerfile.jvm            # JVM-based container
├── Dockerfile.native         # Native image container
└── docs/                     # Documentation
    ├── ARCHITECTURE.md       # This file
    ├── USAGE.md              # Usage guide
    ├── session-manager.md    # 4th gen SessionManager & resilience design
    └── playback-failure-scenarios/ # Empirical OTA failure scenario analyses
```

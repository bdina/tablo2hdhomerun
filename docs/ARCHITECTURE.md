# Tablo2HDHomeRun - Architecture Documentation

## Overview

Tablo2HDHomeRun is an HTTP proxy server that exposes a TabloTV DVR device as an HDHomeRun-compatible tuner. This allows applications and media servers that support HDHomeRun devices (like Plex, Jellyfin, or Channels DVR) to stream live TV from a Tablo device.

## Project Goals & End-User Experience

The primary vision for Tablo2HDHomeRun is to make your network TV viewing experience feel like a **traditional standard TV set**: you tune into a station, and it just plays until you turn it off. Like a standard TV, poor reception or periodic atmospheric events may cause the channel data to drop or corrupt temporarily, but as long as you keep the TV tuned to that station, it recovers on its own and continues playing. The ultimate goal is to allow you to leave a fringe channel playing all day in Plex completely uninterrupted, while the proxy manages re-tuning and keeping the stream active.

To achieve this, Tablo2HDHomeRun is designed to provide an **optimized, resilient live TV playback experience for Plex clients** (as well as Jellyfin and Channels DVR). Over-the-air (OTA) television reception is subject to RF multipath interference, antenna flutter, and tuner dropouts. Conventional network tuners propagate these interruptions downstream, causing Plex client apps (Apple TV, iOS, Roku, Android TV, Plex Web) to crash playback with fatal error dialogs (e.g., "Playback Error", "Can't Play This: Format isn't supported") and forcing the viewer to manually exit back to the guide and re-tune.

Tablo2HDHomeRun is built to deliver this seamless, couch-friendly viewing experience governed by a **4-pillar fringe-signal resilience architecture**:

1. **Surgical Slice-Level & Audio Syncword Sanitization + Outbound Continuity Counter (`CC`) Rewriting (`MpegTsSync.cacheFlow` & `FreezeFramePacer`)**:
   - Replaces whole-GOP gating (`corruptVideoGop` from `c57c238`) with slice-level recovery (`droppingVideoSlice`) that resumes decoding on the next valid MPEG-2 slice start code (`00 00 01 [01..maxSliceRow]`) or `PUSI=1` frame header, stuffing any orphaned partial-slice prefix bytes into the adaptation field and preserving adaptation-field `PCR` on `TEI=0` packets.
   - Guards against FFmpeg/VAAPI "poison pills": clamps out-of-bounds MPEG-2 slice rows (`> maxSliceRow`, e.g. `> 68` on 1080i) to prevent `slice below image` decoder crashes, locks the 12-byte MPEG-2 Sequence Header (`00 00 01 B3`) geometry once confirmed, and gates corrupted audio PIDs until a `PUSI=1` packet with a valid `0x0B77` (AC-3) or `0xFFF` (ADTS AAC) syncword arrives.
   - **Locked Sequence Header Caching & Fast-Attach Injection**: `MpegTsSync.CachedHeaders` caches `lockedSeqHeader: Option[ByteString]` once confirmed by `CacheStage`. When `AlignToIFrameStage` aligns on a GOP header (`00 00 01 B8`) without an in-band Sequence Header or forces alignment at `maxDroppedPackets` (8000), it prepends `FreezeFramePacer.synthesizeSequenceHeaderPacket` (`PUSI = 1` 188-byte TS packet carrying a 9-byte PES header + 12-byte `00 00 01 B3` Sequence Header) so FFmpeg always initializes valid frame dimensions instead of logging `[mpeg2video] Invalid frame dimensions 0x0`.
   - **Decoupled Audio Alignment**: `AlignToIFrameStage` decouples audio alignment from video keyframe alignment. Dropping `knownAudioPids` packets while `isAligned == false` previously caused FFmpeg's `avformat_find_stream_info` probe on fast-attach (`BroadcastHub` inrush during `idle-grace`) to see zero audio packets in the first 1.5 MB and permanently lock a Video-Only transcode pipeline (`streamType="2"` omitted, Failure Mode G). `AlignToIFrameStage` now aligns each audio PID independently on its first `PUSI = 1` PES packet (with valid AC-3 `0x0B77` / ADTS `0xFFF` syncword when streamType is known) and emits audio packets immediately even while `isAligned == false` for video. Null packets (`0x1FFF`) and audio packets are excluded from the `droppedPackets` counter (`maxDroppedPackets = 8000`), ensuring only dropped video packets count toward forced alignment.
   - Continuously rewrites outbound 4-bit Continuity Counters (`CC`) per PID so downstream demuxers (Plex `ssegment` and VAAPI) see zero `CC` discontinuities when corrupted packets are dropped.
2. **45-Second `/watch` Session Retention & `500ms` Fast Polling (`HlsPlaylistPoller`, `ResilientHlsSource`, `WatchSession`)**:
   - Polls `pls.m3u8` every `500ms` whenever `lastAdvanced == false` (`304 Not Modified`), holds the Tablo `/watch` session open for up to `45s` of RF dropout (`STREAM_STALL_TIMEOUT_SEC = 45`, soft recovery with `failOnStallPoll = false`, `softRecoveryThresholdSec = 45`, `softRecoveryTimeoutSec = 15`), and removes the 16s clean-keyframe retune failure from `c57c238` so distorted reception rides out on the existing hardware tuner lock rather than thrashing tuner state.
   - **Upstream "Trickle Collapse" Watchdog (`HlsSegmentFetch.TrickleState` & `HlsBackend.HlsError.SegmentTrickleCollapse`)**: Tracks an exponential moving average (EMA, `alpha = 0.30`) of healthy segment throughput (`>= 200 KB/s`). During fringe signal failure where the Tablo produces microscopic 117–160 KB segments every 2–3s with only ~0.1s of video (a 97% payload drop), segments whose byte rate drops below 15% of the rolling baseline are logged as `[stream:hls] trickle segment detected`, suppressed from calling `onSeqAdvanced` (preventing `WatchSession.FlapState` from falsely resetting to healthy), and after 5 consecutive trickle segments trigger `HlsError.SegmentTrickleCollapse` to escalate to session recovery.
3. **Active Freeze-Frame A/V Pacer (`FreezeFramePacer`)**:
   - When upstream segments pause for `> 750ms`, emits live-compatible `PAT`/`PMT`, MPEG-2 repeat/skip P-frames (or H.264 keepalive access units) on `videoPid`, and silent AC-3 (`0x0B77` with valid CRC-16) or ADTS AAC frames on `audioPids` every `100ms` so Plex's ingest grabber (`-codec copy -f ssegment`) continues writing 1s segments of the frozen picture without timing out. Resumes live A/V seamlessly on the first live keyframe start header (`hasKeyframeHeader`).
   - **Video-Anchored Activation**: `lastLiveDataNanos` in `CacheStage` is anchored strictly to **emitted live video packets** (`!freezeActive && !droppingVideoSlice`), preventing incoming `PAT`/`PMT`, audio packets, or discarded non-keyframe packets during `freezeActive` from suppressing timer-driven freeze-frame synthesis.
   - **Real-Time Media Clock Anchor & Deficit Catch-Up**: Tracks `mediaClockAnchorPts` and `mediaClockAnchorNanos`. When an upstream trickle collapse delivers sparse video, `CacheStage` detects when `lastOutputPts` falls behind real-time wall clock by `>= freezeThreshold` (`67,500` ticks = `750ms`) and synthesizes catch-up freeze frames + silent audio (up to 6 frames per `100ms` tick) while rebasing subsequent live timestamps seamlessly.
4. **Continuous Monotonic `PCR` / `PTS` / `DTS` Timeline Normalizer**:
   - Scrubs single-packet bit-flipped PES timestamp outliers (`> 2s` jump) and maintains a continuous monotonic output offset (`ptsDeltaTicks`) across synthetic freeze-frame injection, media-clock deficit catch-up, and `/watch` session retunes, preventing downstream transcoder clock desynchronization.

### Acceptable Data Loss & Resiliency Philosophy

Over-the-air broadcast streams during antenna flutter, wind gusts, or fringe reception inevitably suffer transport data loss. The proxy's design deliberately prioritizes **uninterrupted client playback and zero human intervention by isolating stream damage surgically while preserving tuner lock**.

- **Ride out dropouts on existing hardware tuner lock**: Early implementations triggered rapid cold retunes on dropouts (e.g. 8s stall watchdog and immediate `304 Not Modified` escalation in `6598c07`, or 16s clean-keyframe starvation in `c57c238`). Under the 4-pillar architecture, the Tablo `/watch` session is preserved for up to `STREAM_STALL_TIMEOUT_SEC` (default 45s). The playlist poller drops to a rapid 500ms cadence during stalls, and Tier 1 soft recovery (`softRecoveryThresholdSec = 45`, `softRecoveryTimeoutSec = 15`, `failOnStallPoll = false`) allows transient RF nulls to recover without dropping the hardware tuner.
- **Surgical slice-level sanitization over whole-GOP gating**: When corrupted TS packets arrive (flagged with `TEI` or broken CC), earlier versions dropped the entire video GOP (`corruptVideoGop` in `c57c238`). The proxy now isolates damage to individual MPEG-2 slices (`droppingVideoSlice`), immediately resuming on the next valid slice start code (`00 00 01 [01..maxSliceRow]`) or picture header, converting orphaned partial-slice fragments into adaptation field stuffing and preserving adaptation-field PCRs.
- **Demuxer and transcoder safety (VAAPI poison pills & header injection)**: FFmpeg and hardware VAAPI decoders are protected from crashing on broadcast bit errors:
  - Slice row vertical positions are clamped to `<= maxSliceRow` (e.g., row 68 for 1080i) to eliminate `slice below image` fatal decoder asserts.
  - The 12-byte MPEG-2 Sequence Header (`00 00 01 B3`) geometry is locked once established and cached in `CachedHeaders.lockedSeqHeader`. If a client attaches at a GOP boundary missing an in-band Sequence Header or reaches forced alignment, the proxy synthesizes a `00 00 01 B3` packet to prevent `Invalid frame dimensions 0x0`.
  - Corrupted audio streams are silenced until a `PUSI=1` packet with a verified audio syncword (`0x0B77` for AC-3 with valid CRC-16, or `0xFFF` for ADTS AAC) arrives.
  - Outbound 4-bit Continuity Counters (`CC`) are rewritten continuously per PID so downstream muxers never see CC jumps when corrupt packets are dropped.
- **Decoupled audio alignment protecting client format probing**: Gating audio on video keyframes previously caused FFmpeg's `avformat_find_stream_info` probe on fast-attach (`BroadcastHub` inrush during `idle-grace`) to starve of audio in the first 1.5 MB, locking into a video-only transcode pipeline. Audio alignment is now fully decoupled: each audio PID aligns on its first `PUSI=1` PES packet and begins emitting immediately.
- **Active A/V freeze pacing & media-clock deficit catch-up**: Rather than bare null packets, `FreezeFramePacer` generates valid MPEG-2 repeat P-frames and silent audio every 100ms whenever segment progress pauses for `> 750ms`. Video-anchoring ensures incoming audio or non-video packets cannot suppress freeze-frame pacing. If upstream segment trickle reduces delivered video below real-time cadence, `CacheStage` detects the media-clock deficit against wall clock and emits up to 6 catch-up frames per tick, maintaining an uninterrupted 1s segment cadence in Plex's ingest grabber.
- **Upstream Trickle Collapse Watchdog**: When hardware demodulator degradation causes microscopic 117–160 KB segments (~97% payload drop) that fool zero-byte watchdogs, `HlsSegmentFetch.TrickleState` tracks an EMA baseline (`alpha = 0.30`). Segments falling below 15% throughput suppress sequence advancement in `WatchSession.FlapState` and trigger `SegmentTrickleCollapse` after 5 consecutive occurrences.
- **Timeline continuity**: PES timestamp bit-flips (> 2s jumps) are scrubbed, and a monotonic offset (`ptsDeltaTicks`) normalizes PCR, PTS, and DTS across synthetic freeze-frame frames, session restarts, and retunes.
- **Two-tier recovery with flap dampening**: If RF loss exceeds the 45s session retention threshold, Tier 1 soft recovery escalates to Tier 2 cold retune (`DELETE` old session + `POST /watch`), which resets `lastSeqRef` for the new session and receives `STREAM_TUNE_TIMEOUT_SEC` (default 20s). Consecutive retunes within a 30s window trigger capped exponential backoff (capped at 4s: 2s, 4s) while `FreezeFramePacer` maintains downstream stream continuity. Stable streaming for 30s resets the flap counter to zero.

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

- **hls** (default): Fetches M3U8 playlists and TS segments directly via HTTP; no external process. Optimized for HLS v4 byte-range playlists with sub-second adaptive polling (polling at 75% of actual segment duration after advance and down to 500ms whenever unchanged or stalling via `HlsPlaylistPoller`), conditional playlist requests (`ETag` / `Last-Modified`), safe byte-range validation (filtering zero-length or negative sub-ranges to prevent range header errors), tolerant `206 Partial Content` validation for ranged segment fetches (accepting short reads during reception drops), per-segment packet alignment via `MpegTsSync.alignPackets`, and automatic discontinuity + I-frame realignment after any sequence gap, short read, or segment fetch error. In addition, `HlsSegmentFetch.TrickleState` tracks a rolling exponential moving average (EMA, `alpha = 0.30`) of healthy segment throughput (`>= 200 KB/s` baseline). Segments whose throughput drops below 15% of this baseline are logged as `[stream:hls] trickle segment detected`, suppressed from calling `onSeqAdvanced` (preventing `WatchSession.FlapState` from falsely clearing stall tracking), and after 5 consecutive trickle segments escalate to `HlsError.SegmentTrickleCollapse` to trigger session recovery.
- **ffmpeg**: Spawns an FFmpeg subprocess to convert HLS to MPEG-TS. Requires FFmpeg on PATH. Includes reconnect and error-detect flags to survive transient stream drops.

The inner per-tune stream is bounded by `StallWatchdog` and wrapped by `ResilientHlsSource`. `StallWatchdog` monitors byte progress using `STREAM_TUNE_TIMEOUT_SEC` (default 20s) for initial connection / hardware cold tuning and `STREAM_STALL_TIMEOUT_SEC` (default 45s) once streaming. In earlier versions (`c57c238`), a 16s clean-keyframe progress watchdog triggered aggressive cold retunes on reception flutter; under the 4-pillar architecture, this keyframe retune failure has been removed so distorted reception rides out on the existing hardware tuner lock. During transient pauses (`> 750ms`), `FreezeFramePacer` emits live-compatible `PAT`/`PMT`, MPEG-2 repeat/skip P-frames (or H.264 keepalive access units) on `videoPid`, and silent AC-3 (`0x0B77` with valid CRC-16) or ADTS AAC frames on `audioPids` every 100ms. This keeps Plex's ingest grabber (`-codec copy -f ssegment`) writing 1s segments continuously of the frozen picture. Within `MpegTsSync.CacheStage`, activation is strictly **video-anchored**: `lastLiveDataNanos` updates solely on emitted live video packets (`!freezeActive && !droppingVideoSlice`), preventing incoming PAT/PMT, audio, or discarded packets during freeze states from resetting the pacer. Furthermore, `CacheStage` anchors a real-time media clock (`mediaClockAnchorPts`, `mediaClockAnchorNanos`): when upstream segment trickle causes `lastOutputPts` to fall behind wall clock by `>= freezeThreshold` (`67,500` ticks = 750ms), it synthesizes catch-up freeze frames + silent audio (up to 6 frames per 100ms tick) while seamlessly rebasing subsequent live video timestamps. When live video resumes, live A/V is restored seamlessly on the first live keyframe start header (`hasKeyframeHeader`).

If upstream data ceases for `STREAM_STALL_TIMEOUT_SEC` (45s), Tier 1 soft recovery polls the existing Tablo `/watch` session with `failOnStallPoll = false`, allowing the tuner up to `softRecoveryTimeoutSec` (15s) to produce new segments before escalating. If an extended outage forces a Tier 2 cold retune (`DELETE` old session + `POST /watch`), `RestartSource.withBackoff` applies exponential backoff starting at `STREAM_RETRY_DELAY_SEC` (default 1s) up to `STREAM_MAX_RETRY_DELAY_SEC` (default 15s), with flap dampening capped at 4s for rapid retunes within 30s. HLS media sequence tracking (`lastSeqRef`) is maintained across soft restarts and reset on cold retunes so that fresh live-edge segments (`STREAM_RECOVERY_LIVE_EDGE_SEGMENTS`, default 3) immediately replenish client buffers. When fresh broadcast bytes resume following a retune, `ResilientHlsSource` passes incoming data through `MpegTsSync.alignToIFrame` (dropping leading video slices until a codec-matched MPEG-2 Sequence/GOP or H.264 IDR/SPS boundary is hit and aligning audio independently) and prepends a single resume prefix consisting of multi-PID discontinuity packets (video, audio, PCR, and null PIDs) and cached PAT/PMT headers. `RecoveryTimeout` monitors end-to-end byte progress: by default (`STREAM_RECOVERY_TIMEOUT_SEC=0`), recovery continues indefinitely while the viewer stays connected; if configured to a positive value, the stream completes cleanly after that duration without real broadcast data.

Before fanning out via `BroadcastHub`, streams pass through `MpegTsSync.cacheFlow`, `KillSwitches.single`, and `watchTermination`. `MpegTsSync.cacheFlow` executes surgical slice-level sanitization, poison pill protection, outbound continuity counter rewriting, and timeline normalization:
- **Slice-Level Sanitization**: Rather than dropping whole GOPs on `TEI` or `CC` jumps (`corruptVideoGop` in `c57c238`), `MpegTsSync` flags `droppingVideoSlice` and resumes decoding on the next valid MPEG-2 slice start code (`00 00 01 [01..maxSliceRow]`) or `PUSI=1` frame header. Partial-slice prefix bytes are converted to adaptation field stuffing, and adaptation-field PCRs are preserved on `TEI=0` packets.
- **FFmpeg/VAAPI Poison Pill Protection & Sequence Header Injection**: Slice row vertical positions are clamped to `<= maxSliceRow` (e.g. `<= 68` on 1080i) to eliminate `slice below image` decoder crashes. The 12-byte MPEG-2 Sequence Header (`00 00 01 B3`) geometry is locked once confirmed and cached in `MpegTsSync.CachedHeaders.lockedSeqHeader`. When `AlignToIFrameStage` aligns on a GOP header (`00 00 01 B8`) without an in-band Sequence Header or reaches forced alignment at `maxDroppedPackets` (8000), it injects `FreezeFramePacer.synthesizeSequenceHeaderPacket` (a 188-byte `PUSI = 1` TS packet carrying PES header + locked sequence header) so FFmpeg decoders always initialize valid frame dimensions rather than failing with `Invalid frame dimensions 0x0`. Corrupted audio PIDs are gated until a `PUSI=1` packet arrives with a valid `0x0B77` (AC-3 with valid CRC-16) or `0xFFF` (ADTS AAC) syncword.
- **Decoupled Audio Alignment**: To prevent FFmpeg's `avformat_find_stream_info` probe from failing on fast-attach (`BroadcastHub` inrush during `idle-grace`), `AlignToIFrameStage` decouples audio alignment from video keyframe alignment. Each audio PID aligns independently on its first `PUSI = 1` PES packet and begins emitting immediately even while `isAligned == false` for video. Null packets (`0x1FFF`) and audio packets are excluded from the `droppedPackets` counter (`maxDroppedPackets = 8000`), ensuring only dropped video packets count toward forced alignment and preventing audio starvation in the initial 1.5 MB probe buffer (Failure Mode G).
- **Outbound Continuity Counter Rewriting**: 4-bit CCs are rewritten sequentially per PID continuously, ensuring downstream demuxers (Plex `ssegment` and VAAPI) see zero CC discontinuities when corrupted packets are dropped.
- **Monotonic PCR/PTS/DTS Normalization**: Single-packet PES timestamp bit-flips (> 2s jumps) are scrubbed, and a monotonic timeline offset (`ptsDeltaTicks`) is maintained across synthetic freeze-frame injection, media-clock deficit catch-up frames, and retunes.

Streams fan out to subscribers via a 1024-element `BroadcastHub` buffer. When new or reconnecting clients attach to the hub, `MpegTsSync.primeClientSource` prepends cached PAT/PMT tables and multi-PID discontinuity markers, and gates the client stream via `MpegTsSync.alignToIFrame` so downstream decoders avoid mid-GOP decode errors or invalid frame dimensions on attach (subject to the 8000-packet fail-open alignment threshold). When upstream terminates, `watchTermination` notifies the runner, which tears down hardware leases and sends `UpstreamEnded` to `SessionManager`, immediately removing the channel entry so it is re-tunable without waiting for idle grace expiry.

```
┌──────────────────┐     ┌──────────────────┐     ┌─────────────────────────────────────────┐
│  Tablo Device    │────▶│  Stream Backend  │────▶│ ResilientHlsSource                      │
│  /watch endpoint │     │  (HlsBackend or  │     │ (45s Watchdog, 500ms Fast Polling,      │
└──────────────────┘     │   FFmpeg)        │     │  & FreezeFramePacer 100ms A/V Repeat)   │
                         └──────────────────┘     └─────────────────────────────────────────┘
                                                                       │
                                                                       ▼
                                                  ┌─────────────────────────────────────────┐
                                                  │ MpegTsSync.cacheFlow                    │
                                                  │ (Slice Recovery, Outbound CC Rewriting, │
                                                  │  Audio Syncword, Timeline Normalizer)   │
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
   b. ResilientHlsSource retains the /watch session for up to 45s (STREAM_STALL_TIMEOUT_SEC = 45) during RF dropouts, fast-polling pls.m3u8 every 500ms when unchanged (failOnStallPoll = false). FreezeFramePacer emits MPEG-2 repeat P-frames and silent AC-3/AAC frames every 100ms when upstream pauses > 750ms or media clock falls behind wall clock (up to 6 frames/tick). HlsSegmentFetch.TrickleState monitors throughput, suppressing onSeqAdvanced on trickle segments (< 15% EMA baseline) and triggering SegmentTrickleCollapse after 5 consecutive events.
   c. MpegTsSync performs surgical slice-level sanitization (droppingVideoSlice), clamps slice rows <= maxSliceRow, locks sequence header geometry (cached in CachedHeaders.lockedSeqHeader), gates audio on valid syncwords (0x0B77 / 0xFFF), rewrites outbound CCs sequentially per PID, normalizes monotonic PCR/PTS/DTS timeline offsets, and caches PAT/PMT headers (persisted per channel in SessionManager.channelHeaderCache across session lifecycles for fast subsequent tunes)
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
| `STREAM_STALL_TIMEOUT_SEC` | `45` | Seconds without new data before escalating from soft recovery to cold retune (default 45s rides out transient RF dropouts on existing hardware tuner lock) |
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
│   │   ├── Tablo2HDHomeRun.scala     # Main application
│   │   ├── stream/                    # Stream backends
│   │   │   ├── StreamBackend.scala   # Trait and factory
│   │   │   ├── FFmpegBackend.scala   # FFmpeg subprocess backend
│   │   │   ├── HlsBackend.scala      # HLS-native backend
│   │   │   └── M3U8.scala            # M3U8 playlist parser
│   │   └── tuner/                     # Tablo legacy/4th gen
│   └── resources/META-INF/
│       └── native-image/             # GraalVM configuration
│           └── reachability-metadata.json
├── Dockerfile.jvm            # JVM-based container
├── Dockerfile.native         # Native image container
└── docs/                     # Documentation
    ├── ARCHITECTURE.md       # This file
    └── USAGE.md              # Usage guide
```

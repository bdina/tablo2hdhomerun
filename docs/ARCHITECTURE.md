# Tablo2HDHomeRun - Architecture Documentation

## Overview

Tablo2HDHomeRun is an HTTP proxy server that exposes a TabloTV DVR device as an HDHomeRun-compatible tuner. This allows applications and media servers that support HDHomeRun devices (like Plex, Jellyfin, or Channels DVR) to stream live TV from a Tablo device.

## Project Goals & End-User Experience

The primary design goal of Tablo2HDHomeRun is to provide an **optimized, resilient live TV playback experience for Plex clients** (as well as Jellyfin and Channels DVR). Over-the-air (OTA) television reception is subject to RF multipath interference, antenna flutter, and tuner dropouts. Conventional network tuners propagate these interruptions downstream, causing Plex client apps (Apple TV, iOS, Roku, Android TV, Plex Web) to crash playback with fatal error dialogs (e.g., "Playback Error", "Can't Play This: Format isn't supported") and forcing the viewer to manually exit back to the guide and re-tune.

Tablo2HDHomeRun is built to deliver a seamless, couch-friendly viewing experience governed by four core resilience pillars:

1. **Uninterrupted Client Playback**: Plex client apps stay in an active playback state during RF glitches and temporary channel dropouts. The proxy absorbs stream degradations, preventing player termination and eliminating the need for manual viewer intervention.
2. **Warm Client Keepalive with Frozen Video**: When an upstream Tablo stream stalls or fails, the proxy continuously streams standards-compliant MPEG-TS null packets (PID `0x1FFF`) to the client every 40 ms while releasing the old Tablo session and performing a cold tune (`/watch`). Downstream Plex grabbers and transcoders stay fed with valid transport stream packets without timing out. The viewer experiences a clean video freeze rather than a crash or error modal.
3. **Live-Edge Resumption (No Seen-Content Replay)**: When the Tablo tuner recovers after a cold tune and valid broadcast packets resume from a fresh segment file, playback cuts directly to the live edge. A single resume prefix (multi-PID discontinuity + cached PAT/PMT headers) informs downstream decoders of the timeline reset without jarring rewind loops or replayed audio/video.
4. **Deterministic Clean Outage Termination**: If an outage is persistent and the proxy cannot successfully recover the stream after 60 seconds (`STREAM_RECOVERY_TIMEOUT_SEC`) without new bytes, the stream terminates cleanly. The session manager removes the entry immediately so the channel is re-tunable right away.

### Acceptable Data Loss & Resiliency Philosophy

Over-the-air broadcast streams during antenna flutter, wind gusts, or fringe reception inevitably suffer transport data loss. The proxy's design deliberately prioritizes **uninterrupted client playback and zero human intervention over attempting to salvage corrupted slices from degraded streams**.

- **Detect dropouts by lack of progress**: Dropouts are detected when no new bytes arrive for `STREAM_STALL_TIMEOUT_SEC` (default 8s), not by TS packet error counters. The Tablo 4th gen repackages broadcast MPEG-2 into clean TS framing even when RF reception drops, so CC/TEI checks do not detect ATSC signal loss.
- **Cold retune with fixed delay**: When a stall occurs, the proxy explicitly sends a DELETE request to release the old Tablo session, then executes a cold `POST /watch` (~11s hardware tune) with a fixed `STREAM_RETRY_DELAY_SEC` (default 1s) between retune attempts. `STREAM_TUNE_TIMEOUT_SEC` (default 20s) bounds each tune attempt.
- **Tolerant segment fetching**: Short ranged segment reads are accepted when the segment stops growing during a reception drop. Trailing partial packets are cleanly dropped by `MpegTsSync.alignPackets`. Missing data during recovery is expected and accepted.
- **Predictable freeze duration**: A typical dropout recovery cycle takes approximately 8s (stall watchdog) + 1s (retry delay) + ~11s (cold `/watch` tune) ≈ 20s of frozen frame.
- **Known limitation**: Marginal (not lost) RF reception can cause MPEG-2 slice corruption inside segments that the Tablo writes to the stream. These corrupted macroblocks pass through to Plex, where Plex transcoders may log non-fatal warnings (e.g. `mpeg2video` errors such as `slice below image` or `invalid cbp -1`). The proxy does not attempt to inspect or repair internal video slice data.

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
│  │  /guide.xml     │  │  FFMpegDelegate  │  │                             │ │
│  └─────────────────┘  └──────────────────┘  └─────────────────────────────┘ │
└─────────────────────────────────────────────────────────────────────────────┘
                                       │
                                       │ HTTP (Tablo API)
                                       ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                            TabloTV DVR Device                               │
│                              (Port 8885)                                    │
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

- **hls** (default): Fetches M3U8 playlists and TS segments directly via HTTP; no external process. Optimized for HLS v4 byte-range playlists with adaptive polling, conditional playlist requests (`ETag` / `Last-Modified`), safe byte-range validation (filtering zero-length or negative sub-ranges to prevent range header errors), tolerant `206 Partial Content` validation for ranged segment fetches (accepting short reads during reception drops), and per-segment packet alignment via `MpegTsSync.alignPackets` (cleanly dropping trailing partial packets).
- **ffmpeg**: Spawns an FFmpeg subprocess to convert HLS to MPEG-TS. Requires FFmpeg on PATH. Includes reconnect and error-detect flags to survive transient stream drops.

The inner per-tune stream is bounded by `StallWatchdog` and wrapped by `ResilientHlsSource`. `StallWatchdog` monitors byte progress using two thresholds: `STREAM_TUNE_TIMEOUT_SEC` (default 20s) for initial connection / hardware cold tuning, and `STREAM_STALL_TIMEOUT_SEC` (default 8s) once streaming. If the inner stream ceases producing data for 8 seconds, `StallWatchdog` fails the stage, triggering a retune via `RestartSource.withBackoff` after a fixed `STREAM_RETRY_DELAY_SEC` (default 1s). HLS media sequence tracking (`lastSeqRef`) is maintained across recovery retunes so that already-played segments from a stalled playlist are deduplicated and not re-emitted, preventing stream loops during reception drops. During retunes or silent gaps, `ResilientHlsSource` injects MPEG-TS null packets (PID 0x1FFF) every 40ms to keep the HTTP chunked transfer alive, providing the viewer with a stable frozen frame instead of an error dialog. When fresh broadcast bytes resume following a cold retune, `ResilientHlsSource` prepends a single resume prefix consisting of multi-PID discontinuity packets and cached PAT/PMT headers. `RecoveryTimeout` monitors end-to-end byte progress: if no real broadcast data arrives for `STREAM_RECOVERY_TIMEOUT_SEC` (default 60s), the stream completes cleanly.

Before fanning out via `BroadcastHub`, streams pass through `MpegTsSync.cacheFlow`, `KillSwitches.single`, and `watchTermination`. `MpegTsSync.cacheFlow` continuously captures the latest PAT (PID 0) and PMT (PID 0x100) tables. Streams fan out to subscribers via a 1024-element `BroadcastHub` buffer. When new or reconnecting clients attach to the hub, `MpegTsSync.primeClientSource` prepends the cached PAT/PMT tables and multi-PID discontinuity markers, guaranteeing that decoders in Plex always receive valid stream parameters and never encounter decoder crashes. When upstream terminates, `watchTermination` notifies the runner, which tears down hardware leases and sends `UpstreamEnded` to `SessionManager`, immediately removing the channel entry so it is re-tunable without waiting for idle grace expiry.

```
┌──────────────────┐     ┌──────────────────┐     ┌────────────────────────┐
│  Tablo Device    │────▶│  Stream Backend  │────▶│ ResilientHlsSource     │
│  /watch endpoint │     │  (ffmpeg or hls) │     │ (Watchdog, Null Padding│
└──────────────────┘     └──────────────────┘     │  & Cold Retune)        │
                                                  └────────────────────────┘
                                                            │
                                                            ▼
                                                  ┌────────────────────────┐
                                                  │ MpegTsSync.cacheFlow   │
                                                  │ (PAT/PMT Caching)      │
                                                  └────────────────────────┘
                                                            │
                                                            ▼
                                                  ┌────────────────────────┐
                                                  │ BroadcastHub Fan-out   │
                                                  │ + Header Priming       │
                                                  └────────────────────────┘
                                                            │
                                                            ▼
                                                  ┌────────────────────────┐
                                                  │  MPEG-TS Output        │
                                                  │  (Chunked HTTP)        │
                                                  └────────────────────────┘
```

## Data Flow Workflows

### Channel Discovery Workflow

```
1. Client requests GET /lineup.json
2. LineupActor checks cache validity (1-day deadline)
3. If cache expired or empty:
   a. GET /guide/channels from Tablo → returns channel paths
   b. POST /batch to Tablo with paths → returns channel details
   c. Transform each channel to HDHomeRun format:
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
      client attachment primes with cached PAT/PMT and discontinuity headers
4. 4th gen session maintenance:
   a. Periodically POST /player/sessions/{token}/keepalive while the client stream is active
   b. ResilientHlsSource releases the old session and cold-tunes via `/watch` when no new bytes arrive for 8s or the session ends
   c. MpegTsSync normalizes packet boundaries and caches PAT/PMT headers (persisted per channel
      in SessionManager.channelHeaderCache across session lifecycles for fast subsequent tunes)
5. Client teardown:
   a. On client disconnect, tuner enters idle grace period (default 75s) for instant reconnect,
      actively draining the BroadcastHub via a background sink to avoid upstream backpressure stalls
   b. If idle grace expires without reconnection, DELETE /player/sessions/{token} to release hardware tuner
   c. If upstream ends on its own (60s without progress), the runner tears down and SessionManager removes the entry immediately (no idle grace)
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
| `STREAM_STALL_TIMEOUT_SEC` | `8` | Seconds without new data before a cold retune |
| `STREAM_TUNE_TIMEOUT_SEC` | `20` | Max seconds to wait for tune / cold start data |
| `STREAM_RETRY_DELAY_SEC` | `1` | Delay in seconds between retune attempts |
| `STREAM_RECOVERY_TIMEOUT_SEC` | `60` | Total outage duration before cleanly ending stream |
| `STREAM_PRE_ROLL_KEEP_ALIVE` | `true` | Emit periodic null MPEG-TS packets during cold tune to prevent client timeouts |
| `STREAM_PRE_ROLL_INTERVAL_MS`| `100` | Interval in ms between pre-roll keepalive chunks |
| `STREAM_PRE_ROLL_PACKETS` | `7` | Number of 188-byte null packets per keepalive chunk (7 = 1316 bytes MTU) |
| `SESSION_IDLE_GRACE_SEC` | `75` | Idle grace period (in seconds) to retain tuner session |
| `MEDIA_ROOT` | (none) | Optional path for media file transcoding |

### Fixed Configuration

| Setting | Value | Description |
|---------|-------|-------------|
| Tablo Port | 8885 | Standard Tablo API port |
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

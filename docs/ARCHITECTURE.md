# Tablo2HDHomeRun - Architecture Documentation

## Overview

Tablo2HDHomeRun is an HTTP proxy server that exposes a TabloTV DVR device as an HDHomeRun-compatible tuner. This allows applications and media servers that support HDHomeRun devices (like Plex, Jellyfin, or Channels DVR) to stream live TV from a Tablo device.

## Project Goals & End-User Experience

The primary design goal of Tablo2HDHomeRun is to provide an **optimized, resilient live TV playback experience for Plex clients** (as well as Jellyfin and Channels DVR). Over-the-air (OTA) television reception is subject to RF multipath interference, antenna flutter, and tuner dropouts. Conventional network tuners propagate these interruptions downstream, causing Plex client apps (Apple TV, iOS, Roku, Android TV, Plex Web) to crash playback with fatal error dialogs (e.g., "Playback Error", "Can't Play This: Format isn't supported") and forcing the viewer to manually exit back to the guide and re-tune.

Tablo2HDHomeRun is built to deliver a seamless, couch-friendly viewing experience governed by four core resilience pillars:

1. **Uninterrupted Client Playback**: Plex client apps stay in an active playback state during RF glitches and temporary channel dropouts. The proxy absorbs stream degradations, preventing player termination and eliminating the need for manual viewer intervention.
2. **Warm Client Keepalive with Frozen Video**: When an upstream Tablo stream stalls or fails, the proxy immediately initiates background hardware re-tuning while continuously streaming standards-compliant MPEG-TS null packets (PID `0x1FFF`) to the client every 40 ms. Downstream Plex grabbers and transcoders stay fed with valid transport stream packets without timing out. The viewer experiences a brief, clean video freeze rather than a crash or error modal.
3. **Live-Edge Resumption (No Seen-Content Replay)**: When the Tablo tuner recovers and valid broadcast packets resume, playback cuts directly to the live edge. The session manager and HLS poller suppress segments already emitted prior to the dropout, while injecting ISO/IEC 13818-1 discontinuity markers and cached PAT/PMT headers. The viewer never experiences jarring rewind loops, time-warps, or replayed audio/video.
4. **Deterministic Clean Outage Termination**: If an outage is persistent and the proxy cannot successfully recover the stream after 60 seconds (`STREAM_RECOVERY_TIMEOUT_SEC`), the stream terminates cleanly. This gracefully ends playback on the Plex client rather than looping keepalive packets indefinitely for an abandoned television or dead channel.

### Acceptable Data Loss & Resiliency Philosophy

Over-the-air broadcast streams during antenna flutter, wind gusts, or fringe reception inevitably suffer transport data loss. The proxy's design deliberately prioritizes **uninterrupted client playback and zero human intervention over attempting to salvage corrupted slices from degraded streams**.

Attempting to limp through severe bitstream errors by passing partial frames downstream poses a catastrophic risk to hardware-accelerated decoders (e.g., Plex's Intel GPU/QSV transcoder), which crash on corrupted macroblock patterns (such as `invalid cbp -1` or `Warning MVs not available`) and display fatal error dialogs ("Playback Error: Recording failed. Please check your tuner or antenna").
Instead, Tablo2HDHomeRun treats missing broadcast data during recovery as entirely acceptable and expected:
- The moment signal degradation, packet drops, or TEI errors occur, `MpegTsHealth` operates with zero-tolerance thresholds (`ccMax = 0`, `teiMax = 0`, `syncMax = 0`) and instantly fast-fails the inner stream rather than leaking damaged macroblocks or corrupted slices downstream (which trigger fatal decoder syntax aborts like `slice below image` or `Missing picture start code`).
- `ResilientHlsSource` absorbs the outage by continuously emitting standards-compliant MPEG-TS null packets (PID `0x1FFF`) every 40 ms. This keeps the HTTP transfer and client grabbers fully active while presenting the viewer with a clean, stable **frozen video frame** during the reception drop.
- In the background, the proxy re-tunes the physical hardware tuner (`/watch`) to re-acquire clean signal lock.
- Once signal stability returns, the stream resumes cleanly at the live broadcast edge (`recoveryLiveEdgeSegmentCount = 2`). `ResilientHlsSource` and `MpegTsSync` prepend ISO/IEC 13818-1 multi-PID discontinuity markers and cached PAT/PMT tables so downstream decoders reset timelines and decode new live frames without choking on the missing gap.
- The viewer experiences a brief frozen frame during the gust of wind and playback automatically resumes hands-free, completely eliminating playback aborts and error modals.

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

- **hls** (default): Fetches M3U8 playlists and TS segments directly via HTTP; no external process. Optimized for HLS v4 byte-range playlists with adaptive polling, conditional playlist requests (`ETag` / `Last-Modified`), safe byte-range validation (filtering zero-length or negative sub-ranges to prevent range header errors), strict `206 Partial Content` validation for ranged segment fetches, and status-aware segment recovery. Wrapped by `ResilientHlsSource` for null-packet padding and retune backoff. On initial tune, the poller starts with 3 live-edge segments (`liveEdgeSegmentCount = 3`); upon recovery retunes, it cuts directly to the latest 2 segments (`recoveryLiveEdgeSegmentCount = 2`) and filters out any segment keys already emitted (`emittedKeys`) to guarantee zero seen-content replay.
- **ffmpeg**: Spawns an FFmpeg subprocess to convert HLS to MPEG-TS. Requires FFmpeg on PATH. Includes reconnect and error-detect flags to survive transient stream drops.

The resulting stream is monitored by `MpegTsHealth` and wrapped by `ResilientHlsSource`. `MpegTsHealth` sanitizes corrupt packets marked with the Transport Error Indicator (TEI) bit into MPEG-TS null packets (PID 0x1FFF) to protect downstream decoders (such as Plex's FFmpeg transcoder) from bitstream crashes. It checks for signaled discontinuities (`discontinuity_indicator = 1` in adaptation fields) to avoid false continuity counter penalties on resets, and evaluates health degradation inline during packet streaming (`onPush`) as well as across sliding time windows. When error thresholds (default `ccMax=10`, `syncMax=3`, `teiMax=3`) are breached, `MpegTsHealth` triggers instant stream degradation rather than allowing corrupt slices to overwhelm decoders. To prevent downstream decoder syntax crashes (such as FFmpeg `slice below image` or Apple VideoToolbox aborts), `MpegTsHealth` tracks elementary stream PIDs and suppresses corrupted mid-frame packet fragments (`PUSI = 0`) following TEI or continuity jumps until the next payload unit start indicator (`PUSI = 1`), while preserving continuity counters on injected discontinuity adaptation fields. If the stream backend fails, degrades, or connection to the Tablo drops, `ResilientHlsSource` automatically injects MPEG-TS null packets every 40ms to keep the HTTP chunked transfer alive, preventing downstream players like Plex from disconnecting. While bridging gaps with null packets for up to `STREAM_RECOVERY_TIMEOUT_SEC` (default 60s), the proxy actively retunes the physical Tablo hardware via `/watch` and cleans up old tuner leases on the device. When real data resumes after gap fill, `ResilientHlsSource` prepends cached PAT/PMT headers and multi-PID discontinuity marker packets (video PID, PCR PID, and null PID) to inform downstream decoders that frame state and timestamps reset. It also implements an `idleTimeout` and `RestartSource.withBackoff` to retry connection to the backend and enforce a maximum outage gap.
Before fanning out via `BroadcastHub`, streams pass through `MpegTsSync.dedupConsecutiveDiscontinuity` and `MpegTsSync.cacheFlow`. `MpegTsSync.cacheFlow` enforces strict 188-byte packet framing starting with `0x47`, validates unscrambled broadcast transport streams and non-reserved PIDs, uses multi-packet sync lock confirmation in `findNextSync` to prevent locking onto compressed video payloads during OTA noise, sanitizes TEI-corrupted packets into MPEG-TS null packets, and continuously captures the latest PAT (Program Association Table) on PID 0, PMT (Program Map Table), PCR PID, and elementary video PID. Streams fan out to subscribers via a 1024-element `BroadcastHub` smoothing buffer (providing 4–8 seconds of absorption against transcoder backpressure). When new or reconnecting clients attach to the hub, `MpegTsSync.primeClientSource` prepends the cached PAT, PMT, and multi-PID discontinuity packets, guaranteeing that decoders (like FFmpeg in Plex) always receive valid stream parameters, recognize stream resets across retunes, and never encounter framing or decoder crashes.

```
┌──────────────────┐     ┌──────────────────┐     ┌────────────────────────┐
│  Tablo Device    │────▶│  Stream Backend  │────▶│ ResilientHlsSource     │
│  /watch endpoint │     │  (ffmpeg or hls) │     │ (Padding & Retry Flow) │
└──────────────────┘     └──────────────────┘     └────────────────────────┘
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
   f. Pre-roll keepalive seamlessly switches over to real MPEG-TS data when ready, inserting
      explicit multi-PID MPEG-TS discontinuity packets and prepending primed PAT/PMT headers
4. 4th gen session maintenance:
   a. Periodically POST /player/sessions/{token}/keepalive while the client stream is active
   b. ResilientHlsSource retunes via `/watch` when the HLS session stalls, expires, or degrades
   c. MpegTsSync normalizes packet boundaries and caches PAT/PMT headers (persisted per channel
      in SessionManager.channelHeaderCache across session lifecycles for fast subsequent tunes)
5. Client teardown:
   a. On client disconnect, tuner enters idle grace period (default 75s) for instant reconnect,
      actively draining the BroadcastHub via a background sink to avoid upstream backpressure stalls
   b. If idle grace expires without reconnection, DELETE /player/sessions/{token} to release hardware tuner
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

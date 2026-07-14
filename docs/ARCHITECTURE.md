# Tablo2HDHomeRun - Architecture Documentation

## Overview

Tablo2HDHomeRun is an HTTP proxy server that exposes a TabloTV DVR device as an HDHomeRun-compatible tuner. This allows applications and media servers that support HDHomeRun devices (like Plex, Jellyfin, or Channels DVR) to stream live TV from a Tablo device.

Live channel sessions and tuner capacity are described in [session-sharing.md](session-sharing.md).

## Technology Stack

| Component | Technology |
|-----------|------------|
| Language | Scala 3.7.4 |
| Runtime | JVM (Java 24) or GraalVM Native Image |
| HTTP Framework | Apache Pekko HTTP 1.2.0 |
| Actor System | Apache Pekko Actor Typed 1.3.0 |
| JSON | Spray JSON |
| XML | scala-xml |
| Build | Gradle 8.14.3 with Shadow plugin |
| Containerization | Docker (Ubuntu 24.04 base) |

## System Architecture

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                           Media Client Applications                          │
│              (Plex, Jellyfin, Channels DVR, VLC, etc.)                       │
└─────────────────────────────────────────────────────────────────────────────┘
                                       │
                                       │ HTTP (HDHomeRun API)
                                       ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                         Tablo2HDHomeRun Proxy Server                         │
│                              (Port 8080)                                     │
│  ┌─────────────────┐  ┌──────────────────┐  ┌─────────────────────────────┐ │
│  │   HTTP Routes   │  │   Actor System   │  │   Stream Processing         │ │
│  │  ─────────────  │  │  ──────────────  │  │  ─────────────────────────  │ │
│  │  /discover.json │  │  LineupActor     │  │  FFmpeg or HLS backend     │ │
│  │  /lineup.json   │  │  GuideActor      │  │  (STREAM_BACKEND env)      │ │
│  │  /lineup_status │  │  FsMonitor       │  │  Chunked HTTP streaming     │ │
│  │  /channel/{id}  │  │  SessionManager  │  │  Shared BroadcastHub        │ │
│  │  /guide.xml     │  │  FFMpegDelegate  │  │                             │ │
│  └─────────────────┘  └──────────────────┘  └─────────────────────────────┘ │
└─────────────────────────────────────────────────────────────────────────────┘
                                       │
                                       │ HTTP (Tablo API)
                                       ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                            TabloTV DVR Device                                │
│                              (Port 8885)                                     │
│  ┌──────────────────────────────────────────────────────────────────────┐   │
│  │  /guide/channels  │  /batch  │  /guide/channels/{id}/watch          │   │
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

- **hls** (default): Fetches M3U8 playlists and TS segments directly via HTTP; no external process. Optimized for HLS v4 byte-range playlists with adaptive polling, conditional playlist requests (`ETag` / `Last-Modified`), strict `206 Partial Content` validation for ranged segment fetches, and status-aware segment recovery. Wrapped by `ResilientHlsSource` for null-packet padding and retune backoff.
- **ffmpeg**: Spawns an FFmpeg subprocess to convert HLS to MPEG-TS. Requires FFmpeg on PATH. Includes reconnect and error-detect flags to survive transient stream drops.

The resulting stream is wrapped by the `ResilientHlsSource`, which acts as a robust Pekko Streams `Flow`. If the stream backend fails or the connection to the Tablo drops, this wrapper automatically injects MPEG-TS null packets (PID 0x1FFF) to keep the HTTP chunked transfer alive, preventing downstream players like Plex from disconnecting. It also implements an `idleTimeout` and `RestartSource.withBackoff` to retry connection to the backend and enforce a maximum outage gap.

```
┌──────────────────┐     ┌──────────────────┐     ┌────────────────────────┐
│  Tablo Device    │────▶│  Stream Backend  │────▶│ ResilientHlsSource │
│  /watch endpoint │     │  (ffmpeg or hls) │     │ (Padding & Retry Flow) │
└──────────────────┘     └──────────────────┘     └────────────────────────┘
                                                            │
                                                            ▼
                                                  ┌──────────────────┐
                                                  │  MPEG-TS Output  │
                                                  │  (Chunked HTTP)  │
                                                  └──────────────────┘
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
2. Proxy asks SessionManager to acquire the channel
3. If the channel already has an active player session:
   a. Attach the client to the shared BroadcastHub source
4. Else if capacity reservations are below totalTuners:
   a. Reserve one local tuner slot (Opening)
   b. POST /guide/channels/{id}/watch to Tablo
   c. Materialize one shared HLS/FFmpeg upstream into a BroadcastHub
   d. 4th gen: keepalive once per session (not per client)
   e. Reply with an attachment source to each waiting client
5. Else or Tablo returns 503: respond with HTTP 503 No available tuners
6. On last client disconnect: stop upstream, DELETE player session (4th gen), free reservation
```

Same-channel clients share one Tablo player session. Distinct channels each consume one reservation.
See [session-sharing.md](session-sharing.md) for the state machine and edge cases.

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

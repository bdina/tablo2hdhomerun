# Tablo2HDHomeRun Architecture

This document describes the system architecture, component design, and data flows for the Tablo2HDHomeRun project.

## Table of Contents

1. [Overview](#overview)
2. [System Architecture](#system-architecture)
3. [Component Design](#component-design)
4. [Data Models](#data-models)
5. [Authentication Flows](#authentication-flows)
6. [Streaming Pipeline](#streaming-pipeline)
7. [Actor System](#actor-system)

---

## Overview

Tablo2HDHomeRun is a Scala application that acts as a protocol bridge between Tablo DVR devices and HDHomeRun-compatible clients (like Plex). It exposes an HDHomeRun-compatible HTTP API that translates requests to Tablo's proprietary API.

### Purpose

- **Bridge Protocol Gap**: Plex natively supports HDHomeRun devices but not Tablo. This proxy enables Plex to use Tablo as a live TV source.
- **Multi-Generation Support**: Supports both legacy Tablo devices (direct LAN API) and 4th Generation devices (cloud-authenticated).
- **Stream Conversion**: Converts Tablo's HLS streams to MPEG-TS format expected by HDHomeRun clients.

### Technology Stack

| Component | Technology |
|-----------|------------|
| Language | Scala 3.8.1 |
| Runtime | Java 24 |
| HTTP Server | Apache Pekko HTTP |
| Actor System | Apache Pekko Typed Actors |
| JSON Processing | spray-json |
| XML Processing | scala-xml |
| Streaming | ffmpeg (external) |
| Build | Gradle with Shadow plugin |
| Native Image | GraalVM native-image |

---

## System Architecture

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                           Tablo2HDHomeRun System                            │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│   ┌───────────────┐         ┌─────────────────┐         ┌───────────────┐   │
│   │               │         │                 │         │               │   │
│   │  Plex Media   │ ◄─────► │  Tablo2HDHomeRun│ ◄─────► │ Tablo Device  │   │
│   │    Server     │  HTTP   │     (Proxy)     │  HTTP   │   (Legacy)    │   │
│   │               │         │                 │         │               │   │
│   └───────────────┘         └────────┬────────┘         └───────────────┘   │
│                                      │                                      │
│                                      │ HTTPS                                │
│                                      ▼                                      │
│                             ┌─────────────────┐         ┌───────────────┐   │
│                             │                 │         │               │   │
│                             │   Lighthouse    │ ◄─────► │ Tablo Device  │   │
│                             │     Cloud       │  HTTP   │   (Gen 4)     │   │
│                             │                 │         │               │   │
│                             └─────────────────┘         └───────────────┘   │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

### Protocol Translation

```
HDHomeRun Protocol (Client-Facing)     Tablo Protocol (Backend)
─────────────────────────────────     ─────────────────────────
GET /discover.json        ◄────────►   Device identification
GET /lineup.json          ◄────────►   GET /guide/channels (batch)
GET /lineup_status.json   ◄────────►   Internal state
GET /channel/{id}         ◄────────►   POST /guide/channels/{id}/watch
GET /guide.xml            ◄────────►   GET /guide/channels/{id}/airings
```

---

## Component Design

### Core Components

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                              Application Entry                              │
├─────────────────────────────────────────────────────────────────────────────┤
│  @main tablo2hdhomerunApp                                                   │
│    ├── Dependencies.verify()      - Check ffmpeg availability              │
│    └── Tablo2HDHomeRun.start()    - Initialize actor system and HTTP       │
└─────────────────────────────────────────────────────────────────────────────┘
                                      │
                                      ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                              AppContext                                     │
├─────────────────────────────────────────────────────────────────────────────┤
│  Singleton holding:                                                         │
│    - ActorSystem[pekko.NotUsed]   - Root actor system                      │
│    - TunerBackend                 - Active backend implementation          │
└─────────────────────────────────────────────────────────────────────────────┘
                                      │
                    ┌─────────────────┴─────────────────┐
                    ▼                                   ▼
┌───────────────────────────────┐     ┌───────────────────────────────┐
│      TunerBackend (trait)     │     │        HTTP Routes            │
├───────────────────────────────┤     ├───────────────────────────────┤
│  - getChannels()              │     │  - Response.Discover.route    │
│  - getGuide()                 │     │  - Lineup.route               │
│  - getChannelGuide()          │     │  - Channel.route              │
│  - getTunerStatus()           │     │  - Guide.route                │
│  - watchChannel()             │     │  - Favicon.route              │
│  - friendlyName               │     └───────────────────────────────┘
│  - tunerCount                 │
│  - deviceId                   │
└───────────────────────────────┘
          ▲
          │ implements
          │
    ┌─────┴─────┐
    │           │
┌───▼────┐  ┌───▼─────────────┐
│ Legacy │  │ Gen4TunerBackend│
│ Tuner  │  │                 │
│Backend │  │ + Gen4Auth      │
└────────┘  └─────────────────┘
```

### TunerBackend Trait

The `TunerBackend` trait defines the contract for communicating with Tablo devices:

```scala
trait TunerBackend {
  def friendlyName: String
  def getChannels(): Future[Seq[ChannelData]]
  def getGuide(): Future[Seq[ChannelGuideData]]
  def getChannelGuide(channelId: String): Future[ChannelGuideData]
  def getTunerStatus(): Future[Seq[TunerStatus]]
  def watchChannel(channelId: String): Future[WatchSession]
  def tunerCount: Int
  def deviceId: String
}
```

### Backend Implementations

#### LegacyTunerBackend

For original Tablo devices accessible via direct LAN HTTP:

- **Connection**: Direct HTTP to device IP:port
- **Authentication**: None required
- **Channel Discovery**: `GET /guide/channels` + `POST /batch`
- **Streaming**: `POST /guide/channels/{id}/watch`

#### Gen4TunerBackend

For 4th generation Tablo devices requiring cloud authentication:

- **Connection**: HTTPS to Lighthouse cloud + HTTP to local device
- **Authentication**: OAuth Bearer tokens + HMAC-MD5 device signing
- **Channel Discovery**: Via Lighthouse API `/guide/channels/`
- **Streaming**: HMAC-signed `POST /guide/channels/{id}/watch`

### Gen4Authentication

Handles the multi-step authentication for 4th Gen devices:

```
┌──────────────────────────────────────────────────────────────────────┐
│                    Gen4 Authentication Flow                          │
├──────────────────────────────────────────────────────────────────────┤
│                                                                      │
│  1. login()                                                          │
│     POST /api/v2/login/                                              │
│     ├── Input: email, password                                       │
│     └── Output: LighthouseCredentials (access_token)                 │
│                                                                      │
│  2. getAccount()                                                     │
│     GET /api/v2/account/                                             │
│     ├── Input: Bearer access_token                                   │
│     └── Output: AccountInfo (profiles, devices)                      │
│                                                                      │
│  3. selectDevice()                                                   │
│     POST /api/v2/account/select/                                     │
│     ├── Input: profile_id, server_id                                 │
│     └── Output: LighthouseSession (token, device URL)                │
│                                                                      │
│  4. Device Communication                                             │
│     - createDeviceAuthHeader() → HMAC-MD5 signed Authorization       │
│     - createDateHeader() → RFC 1123 formatted date                   │
│                                                                      │
└──────────────────────────────────────────────────────────────────────┘
```

---

## Data Models

### Channel Data

```scala
case class ChannelData(
  id: String           // Unique channel identifier
, name: String         // Display name
, major: Int           // Major channel number (e.g., 5 in 5.1)
, minor: Int           // Minor channel number (e.g., 1 in 5.1)
, channelType: String  // "ota" or "ott"
, network: Option[String]
, resolution: String   // "1080", "720", etc.
, logoUrl: Option[String]
, callSign: String     // Station call sign (e.g., "WNBC")
, affiliate: Option[String]
)
```

### Program Data

```scala
case class ProgramData(
  id: String
, title: String
, description: Option[String]
, startTime: String    // ISO 8601 timestamp
, endTime: String      // ISO 8601 timestamp
, channelId: String
, episodeTitle: Option[String]
, seasonNumber: Option[Int]
, episodeNumber: Option[Int]
, year: Option[Int]
, genre: Option[String]
, rating: Option[String]
, isMovie: Boolean
, isSports: Boolean
, isNews: Boolean
)
```

### Watch Session

```scala
case class WatchSession(
  token: String        // Stream session token
, expires: String      // Session expiration time
, keepalive: Int       // Keepalive interval (seconds)
, playlistUrl: Uri     // HLS playlist URL
, videoWidth: Int
, videoHeight: Int
)
```

### HDHomeRun Discover Response

```scala
case class Discover(
  FriendlyName: String      // Device display name
, LocalIP: InetAddress      // Proxy server IP
, BaseURL: Uri              // Base URL for API calls
, LineupURL: Uri            // URL to fetch channel lineup
, Manufacturer: String      // "tablo2hdhomerun"
, ModelNumber: String       // "HDHR3-US" (emulated)
, FirmwareName: String      // "hdhomerun3_atsc"
, FirmwareVersion: String
, DeviceID: String          // 8-character hex ID
, DeviceAuth: String
, TunerCount: Int           // Number of available tuners
)
```

---

## Authentication Flows

### Legacy Device Flow

```
┌────────────┐                                    ┌──────────────┐
│   Client   │                                    │ Tablo Device │
└─────┬──────┘                                    └──────┬───────┘
      │                                                  │
      │  HTTP Request (no auth required)                 │
      │─────────────────────────────────────────────────►│
      │                                                  │
      │  Response                                        │
      │◄─────────────────────────────────────────────────│
      │                                                  │
```

### 4th Gen Device Flow

```
┌────────────┐     ┌──────────────┐     ┌──────────────┐
│   Client   │     │  Lighthouse  │     │ Tablo Device │
└─────┬──────┘     └──────┬───────┘     └──────┬───────┘
      │                   │                    │
      │  POST /login/     │                    │
      │──────────────────►│                    │
      │                   │                    │
      │  access_token     │                    │
      │◄──────────────────│                    │
      │                   │                    │
      │  GET /account/    │                    │
      │──────────────────►│                    │
      │                   │                    │
      │  devices[]        │                    │
      │◄──────────────────│                    │
      │                   │                    │
      │  POST /select/    │                    │
      │──────────────────►│                    │
      │                   │                    │
      │  session_token    │                    │
      │◄──────────────────│                    │
      │                   │                    │
      │  HMAC-signed request                   │
      │────────────────────────────────────────►│
      │                   │                    │
      │  Response                              │
      │◄────────────────────────────────────────│
      │                   │                    │
```

### HMAC-MD5 Signature Generation

```
Input String:
  {METHOD}\n
  {PATH}\n
  {MD5(body) or empty}\n
  {Date in RFC 1123}

Example:
  POST
  /guide/channels/S122912_503_01/watch
  d41d8cd98f00b204e9800998ecf8427e
  Sun, 01 Feb 2026 12:00:00 GMT

HMAC = HMAC-MD5(input, HashKey)

Authorization Header:
  tablo:{DeviceKey}:{HMAC hex}
```

---

## Streaming Pipeline

### Channel Streaming Flow

```
┌──────────┐    ┌─────────────────┐    ┌────────────┐    ┌────────────┐
│   Plex   │    │ Tablo2HDHomeRun │    │   Tablo    │    │   ffmpeg   │
└────┬─────┘    └────────┬────────┘    └─────┬──────┘    └─────┬──────┘
     │                   │                   │                 │
     │ GET /channel/{id} │                   │                 │
     │──────────────────►│                   │                 │
     │                   │                   │                 │
     │                   │ POST /watch       │                 │
     │                   │──────────────────►│                 │
     │                   │                   │                 │
     │                   │ playlist_url      │                 │
     │                   │◄──────────────────│                 │
     │                   │                   │                 │
     │                   │ spawn ffmpeg      │                 │
     │                   │─────────────────────────────────────►│
     │                   │                   │                 │
     │                   │                   │   GET playlist  │
     │                   │                   │◄─────────────────│
     │                   │                   │                 │
     │                   │                   │   HLS segments  │
     │                   │                   │─────────────────►│
     │                   │                   │                 │
     │  MPEG-TS stream   │                   │                 │
     │◄───────────────────────────────────────────────────────────│
     │                   │                   │                 │
```

### FFmpeg Command

```bash
ffmpeg \
  -i {playlist_url}           # HLS input from Tablo
  -c copy                     # Copy streams without re-encoding
  -f mpegts                   # Output as MPEG Transport Stream
  -v repeat+level+panic       # Minimal logging
  pipe:1                      # Output to stdout
```

### Stream Lifecycle Management

1. **Client connects** to `/channel/{id}`
2. **Backend.watchChannel()** requests HLS playlist from Tablo
3. **FFmpeg spawned** with playlist URL as input
4. **StreamConverters.fromInputStream** wraps ffmpeg stdout
5. **watchTermination** monitors stream completion
6. **On disconnect/error**: ffmpeg process is destroyed

---

## Actor System

### Actor Hierarchy

```
tablo2hdhomerun-system (root)
├── lineup-actor (Lineup.LineupActor)
│   └── Manages channel lineup caching and refresh
├── guide-actor (Guide.GuideActor)
│   └── Manages XMLTV guide caching and generation
└── monitor-actor (FsMonitor) [optional - media transcoding]
    └── FsNotify-worker
        └── FsScan workers
            └── QueueProxy / FFMpegDelegate workers
```

### LineupActor

Manages channel lineup with caching:

```scala
sealed trait Request
case class Fetch(replyTo: ActorRef[Response.Fetch]) extends Request
case class Status(replyTo: ActorRef[Response.Status]) extends Request

sealed trait Command extends Request
case class Store(channels: Seq[JsValue]) extends Command
case object Scan extends Command

// State
var cache: (Deadline, Seq[JsValue])  // Cache with 1-day TTL
var scanInProgress: Boolean
```

### GuideActor

Manages XMLTV guide data:

```scala
sealed trait Request
case class FetchGuide(replyTo: ActorRef[Response.FetchGuide]) extends Request

sealed trait Command extends Request
case class StoreGuide(guide: Seq[ChannelGuideData]) extends Command
case object Scan extends Command

// State
var cache: (Deadline, Seq[ChannelGuideData])  // Cache with 1-hour TTL
var scanInProgress: Boolean
```

### Message Flow Example

```
Client Request                 LineupActor                    Backend
     │                              │                           │
     │  GET /lineup.json            │                           │
     │─────────────────────────────►│                           │
     │                              │                           │
     │                              │ (cache expired?)          │
     │                              │                           │
     │                              │ getChannels()             │
     │                              │──────────────────────────►│
     │                              │                           │
     │                              │ Seq[ChannelData]          │
     │                              │◄──────────────────────────│
     │                              │                           │
     │                              │ Store(channels)           │
     │                              │──► self                   │
     │                              │                           │
     │  Response.Fetch(channels)    │                           │
     │◄─────────────────────────────│                           │
     │                              │                           │
```

---

## File System Monitor (Optional)

For media transcoding functionality:

```
┌──────────────┐     ┌─────────────┐     ┌──────────────┐
│  FsMonitor   │────►│  FsNotify   │────►│   FsScan     │
└──────────────┘     └──────┬──────┘     └──────────────┘
                            │
                            ▼
                     ┌─────────────┐
                     │  FsQueue    │
                     └──────┬──────┘
                            │
                            ▼
                     ┌─────────────┐     ┌──────────────┐
                     │ QueueProxy  │────►│FFMpegDelegate│
                     └─────────────┘     └──────────────┘
```

This subsystem:
- Monitors directories for new media files (.ts, .mkv)
- Queues files for transcoding via Intel QSV hardware acceleration
- Uses extended file attributes (xattr) to track processing state

---

## Error Handling

### TunerBackend Errors

```scala
object TunerBackend {
  case object NoAvailableTunersError extends Exception
  case class AuthenticationError(message: String) extends Exception
  case class DeviceNotFoundError(message: String) extends Exception
  case object MissingCredentialsError extends Exception
  case class ServerInfoError(status: String) extends Exception
  case class WatchError(status: String) extends Exception
}
```

### HTTP Response Codes

| Code | Scenario |
|------|----------|
| 200 | Successful request |
| 404 | Resource not found |
| 500 | Internal error (stream failure) |
| 503 | No available tuners |

---

## Deployment Modes

### JVM Mode

```bash
gradle shadowJar
java -jar build/libs/tablo2hdhomerun-{version}.jar [-d]
```

### Native Image

```bash
gradle shadowJar
native-image --static -jar build/libs/tablo2hdhomerun-{version}.jar

# Or via Docker
docker build -f Dockerfile.native --tag tablo2hdhomerun:{version} .
```

### Environment Configuration

| Variable | Default | Description |
|----------|---------|-------------|
| `TABLO_MODE` | `legacy` | Backend mode: `legacy` or `gen4` |
| `TABLO_IP` | `127.0.0.1` | Tablo device IP (legacy mode) |
| `TABLO_PORT` | `8885` | Tablo device port |
| `TABLO_EMAIL` | - | Tablo account email (gen4 mode) |
| `TABLO_PASSWORD` | - | Tablo account password (gen4 mode) |
| `TABLO_DEVICE_NAME` | - | Device name to select (gen4 mode) |
| `PROXY_IP` | `127.0.0.1` | Proxy server bind IP |
| `PROXY_PORT` | `8080` | Proxy server port |
| `MEDIA_ROOT` | - | Media directory for transcoding |

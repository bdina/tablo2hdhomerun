# Tablo2HDHomeRun - Usage Guide

## Prerequisites

1. **Tablo DVR** - A networked Tablo device (Legacy or 4th Generation) with an active antenna connection
2. **FFmpeg** - Optional by default (`STREAM_BACKEND=hls`). Required when using `STREAM_BACKEND=ffmpeg`
3. **Java 24** (JVM mode) or GraalVM CE (native image)
4. **Network access** between proxy host and Tablo device
5. **Tablo Account** (4th Gen only) - Email and password for Lighthouse cloud authentication

## Supported Devices

| Device Type | Environment Variable | Authentication |
|-------------|---------------------|----------------|
| 4th Generation Tablo | `TABLO_GEN=4thgen` (default) | Tablo account credentials |
| Legacy Tablo (Pre-4th Gen) | `TABLO_GEN=legacy` | None (local network) |

## Quick Start (4th Generation Tablo)

The default configuration targets 4th Generation Tablo (`TABLO_GEN=4thgen`). Set `TABLO_EMAIL` and `TABLO_PASSWORD` before starting.

### Step 1: Set Environment Variables

```bash
export TABLO_EMAIL=your-tablo-account@email.com
export TABLO_PASSWORD=your-tablo-password
export TABLO_IP=192.168.1.100
export PROXY_IP=0.0.0.0
```

`TABLO_GEN=4thgen` is the default and may be omitted.

### Step 2: Start the Proxy

```bash
./tablo2hdhomerun -d
```

The `-d` flag runs in daemon mode (no stdin prompt to exit). The proxy authenticates with the Tablo Lighthouse cloud service and discovers your device.

### Step 3: Verify Operation

```bash
# Test discovery endpoint
curl http://localhost:8080/discover.json

# Expected response:
{
  "FriendlyName": "Tablo 4th Gen Proxy",
  "LocalIP": "0.0.0.0",
  "BaseURL": "http://0.0.0.0:8080",
  ...
}
```

### Optional: Select Specific Device

If you have multiple Tablo devices on your account, specify which one to use:

```bash
export TABLO_DEVICE_NAME="Living Room"
```

The proxy selects the first device whose name contains the specified string (case-insensitive).

## Quick Start (Legacy Tablo)

Set `TABLO_GEN=legacy` for pre-4th Generation Tablo devices (no cloud credentials required).

### Step 1: Find Your Tablo IP

Locate your Tablo device IP address (typically via router DHCP lease table or Tablo mobile app).

### Step 2: Start the Proxy

```bash
export TABLO_GEN=legacy
export TABLO_IP=192.168.1.100
export PROXY_IP=0.0.0.0
./tablo2hdhomerun -d
```

The `-d` flag runs in daemon mode (no stdin prompt to exit).

### Step 3: Verify Operation

```bash
# Test discovery endpoint
curl http://localhost:8080/discover.json

# Expected response:
{
  "FriendlyName": "Tablo Legacy Gen Proxy",
  "LocalIP": "0.0.0.0",
  "BaseURL": "http://0.0.0.0:8080",
  "LineupURL": "http://0.0.0.0:8080/lineup.json",
  "Manufacturer": "tablo2hdhomerun",
  "ModelNumber": "HDHR3-US",
  "DeviceID": "12345678",
  ...
}

# Test channel lineup
curl http://localhost:8080/lineup.json
```

### Step 4: Configure Media Application

In your media application (Plex, Jellyfin, etc.):

1. Add a new HDHomeRun tuner
2. Enter the proxy URL: `http://<proxy-ip>:8080`
3. The application should auto-discover channels

## Deployment Options

### Option 1: JVM (Development)

```bash
gradle shadowJar
java -jar build/libs/tablo2hdhomerun-<version>.jar
```

### Option 2: Native Image (Production)

```bash
gradle nativeImage
./build/tablo2hdhomerun -d
```

### Option 3: Docker JVM

```bash
docker build -f Dockerfile.jvm --tag tablo2hdhomerun:jvm .
docker run -e TABLO_IP=192.168.1.100 -e PROXY_IP=0.0.0.0 \
           -p 8080:8080 tablo2hdhomerun:jvm \
           java -jar tablo2hdhomerun.jar -d
```

### Option 4: Docker Native (Recommended)

```bash
docker build -f Dockerfile.native --tag tablo2hdhomerun:native .
docker run -e TABLO_IP=192.168.1.100 -e PROXY_IP=0.0.0.0 \
           -p 8080:8080 --device /dev/dri:/dev/dri \
           tablo2hdhomerun:native
```

The native Docker image includes Intel Media driver for QSV hardware acceleration.

## Environment Variables

### Common Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `TABLO_GEN` | `4thgen` | Tablo generation: `4thgen` or `legacy` |
| `TABLO_IP` | `127.0.0.1` | IP address of the Tablo DVR device |
| `TABLO_PORT` | `8887` (4th gen) / `8885` (legacy) | Tablo device REST API port |
| `PROXY_IP` | `127.0.0.1` | IP address for the proxy to bind to |
| `STREAM_BACKEND` | `hls` | Live stream backend: `hls` or `ffmpeg` |
| `STREAM_MAX_GAP_SEC` | `60` | Maximum gap (in seconds) before a single HLS attempt is retuned |
| `LOG_LEVEL` | (none) | Application log level (e.g. `DEBUG`, `INFO`) |
| `PEKKO_LOG_LEVEL` | (same as `LOG_LEVEL`) | Pekko/Actor log level; falls back to `LOG_LEVEL` |
| `MEDIA_ROOT` | (none) | Optional path for media file transcoding |

### Weak OTA / Plex recovery (default `hls` backend)

Recovery retunes automatically on stalls, session end, unauthorized segment responses, range mismatches, or degraded MPEG-TS. Playback stays active while real video flows; null packets keep the HTTP connection alive during gaps. The stream ends when you stop it from the remote, or when `STREAM_RECOVERY_TIMEOUT_SEC` elapses with no real backend data (unattended TV on a dead channel).

The native HLS backend (`STREAM_BACKEND=hls`, default) adds:

- HLS v4 `#EXT-X-BYTERANGE` support with HTTP range requests
- Adaptive playlist polling based on `#EXT-X-TARGETDURATION`
- Conditional playlist requests when the Tablo device returns `ETag` or `Last-Modified`
- Strict validation of ranged segment responses (`206` + `Content-Range`)
- Distinct recovery errors for playlist stalls, segment-not-ready races, and auth failures
- 4th gen watch-session expiry awareness before recovery retune
- 4th gen Tablo player-session keepalive (`POST /player/sessions/{token}/keepalive`) while client playback is active
- 4th gen session teardown (`DELETE /player/sessions/{token}`) when the client disconnects or the session is replaced on retune

MPEG-TS null-packet keepalive (in `ResilientHlsSource`) and Tablo player-session keepalive are separate mechanisms: the former keeps the HTTP chunked response alive during HLS gaps; the latter renews the Tablo watch session token on the device.

| Variable | Default | Description |
|----------|---------|-------------|
| `STREAM_RETRY_MIN_BACKOFF_SEC` | `2` | Minimum delay between retune attempts |
| `STREAM_RETRY_MAX_BACKOFF_SEC` | `30` | Maximum delay between retune attempts |
| `STREAM_RECOVERY_TIMEOUT_SEC` | `60` | End stream after this many seconds without real backend data |
| `STREAM_HLS_STALL_POLLS` | `3` | Playlist polls with no media-sequence advance before retune |
| `STREAM_HLS_HEARTBEAT_SEC` | `60` | Interval for HLS stream heartbeat INFO logs |
| `STREAM_HLS_HEALTH_WINDOW_SEC` | `10` | MPEG-TS health metric sliding window |
| `STREAM_HLS_CC_ERROR_MAX` | `30` | Continuity-counter errors per window before degraded |
| `STREAM_HLS_SYNC_LOSS_MAX` | `10` | Sync-byte misalignments per window before degraded |
| `STREAM_HLS_NULL_RATIO_MAX` | `0.6` | Null-packet fraction per window before degraded |
| `STREAM_HLS_HEALTH_ENFORCE` | `false` | When `true`, degraded TS fails the stream and triggers retune |
| `STREAM_HLS_POLL_FAILURES_MAX` | `60` | Consecutive playlist fetch failures before retune |

### 4th Generation Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `TABLO_EMAIL` | (required for 4th gen) | Tablo account email for cloud authentication |
| `TABLO_PASSWORD` | (required for 4th gen) | Tablo account password |
| `TABLO_DEVICE_NAME` | (none) | Optional filter to select device by name |

## Streaming a Channel

### Direct HTTP Access

```bash
# Stream channel 12345 to VLC
vlc http://localhost:8080/channel/12345

# Stream to file
curl http://localhost:8080/channel/12345 -o recording.ts
```

### FFplay (Quick Test)

```bash
ffplay http://localhost:8080/channel/12345
```

## Program Guide

### XMLTV Format

```bash
curl http://localhost:8080/guide.xml -o guide.xml
```

The guide provides 24 hours of programming data in XMLTV format, compatible with guide importers in Plex, Jellyfin, and other DVR applications.

## Docker Deployment

### Basic Docker Run (4th Generation, default)

```bash
docker run -d \
  --name tablo-proxy \
  -e TABLO_EMAIL=your@email.com \
  -e TABLO_PASSWORD=yourpassword \
  -e TABLO_IP=192.168.1.100 \
  -e PROXY_IP=0.0.0.0 \
  -p 8080:8080 \
  tablo2hdhomerun:native
```

For legacy Tablo add `-e TABLO_GEN=legacy` and omit credentials.

### With Hardware Acceleration (Intel QSV)

```bash
docker run -d \
  --name tablo-proxy \
  -e TABLO_IP=192.168.1.100 \
  -e PROXY_IP=0.0.0.0 \
  -p 8080:8080 \
  --device /dev/dri:/dev/dri \
  tablo2hdhomerun:native
```

### With Media Transcoding

```bash
docker run -d \
  --name tablo-proxy \
  -e TABLO_IP=192.168.1.100 \
  -e PROXY_IP=0.0.0.0 \
  -e MEDIA_ROOT=/media \
  -p 8080:8080 \
  -v /path/to/recordings:/media \
  --device /dev/dri:/dev/dri \
  tablo2hdhomerun:native
```

### 4th Generation Tablo

Same as basic run above; `TABLO_GEN=4thgen` is the default and may be omitted.

```bash
docker run -d \
  --name tablo-proxy \
  -e TABLO_EMAIL=your@email.com \
  -e TABLO_PASSWORD=yourpassword \
  -e TABLO_IP=192.168.1.100 \
  -e PROXY_IP=0.0.0.0 \
  -p 8080:8080 \
  tablo2hdhomerun:native
```

## API Reference

| Endpoint | Response Type | Description |
|----------|---------------|-------------|
| `GET /discover.json` | JSON | Device metadata |
| `GET /lineup.json` | JSON | Channel array |
| `GET /lineup_status.json` | JSON | Scan status |
| `GET /channel/{id}` | `video/mp2t` | MPEG-TS stream |
| `GET /guide.xml` | `text/xml` | XMLTV program guide |
| `GET /favicon.ico` | Empty | No-op (avoids 404s) |

## Troubleshooting

### No Channels Found

1. Verify Tablo IP is correct (4th gen uses port 8887; legacy uses 8885)
2. Check network connectivity between proxy and Tablo
3. Ensure Tablo has completed initial channel scan

### Stream Won't Start

1. Check tuner availability (legacy: `curl http://<tablo-ip>:8885/server/tuners`; 4th gen tuners are managed via player sessions)
2. If using `STREAM_BACKEND=ffmpeg`: verify FFmpeg is installed (`ffmpeg -version`). The default `hls` backend does not require FFmpeg
3. Check for concurrent recording conflicts

### Container Issues

```bash
# View logs
docker logs tablo-proxy

# Check container status
docker inspect tablo-proxy
```

### Connection Refused

1. Ensure `PROXY_IP` is set to `0.0.0.0` for external access
2. Check firewall rules allow port 8080
3. Verify Docker port mapping with `docker ps`

### 4th Gen Authentication Failed

1. Verify `TABLO_EMAIL` and `TABLO_PASSWORD` are correct
2. Ensure your Tablo account email is verified
3. Check that the device is registered and reachable on the network
4. If using `TABLO_DEVICE_NAME`, verify the name matches your device

### 4th Gen No Devices Found

1. Log into the Tablo mobile app to verify your account has devices registered
2. Ensure the Tablo device is powered on and connected to the network
3. Try without `TABLO_DEVICE_NAME` to use any available device

### Poor Video Quality

1. Ensure sufficient network bandwidth between Tablo and proxy
2. Check for network congestion or interference
3. Verify Tablo recording quality settings

## Integration Examples

### Plex Live TV

1. Go to Settings > Live TV & DVR
2. Click "Set Up Plex DVR"
3. Enter `http://<proxy-ip>:8080` when prompted for tuner
4. Complete the channel scan and guide setup

### Jellyfin

1. Go to Dashboard > Live TV
2. Click "Add Tuner Device"
3. Select "HD Homerun" as tuner type
4. Enter `http://<proxy-ip>:8080` as the URL
5. Import guide from `http://<proxy-ip>:8080/guide.xml`

### Channels DVR

1. Go to Settings > Sources
2. Click "Add Source"
3. Select "HDHomeRun"
4. Enter the proxy IP address
5. Complete setup wizard

## Performance Tuning

### Memory Usage

The native image is configured with:
- Min Heap: 128MB
- Max Heap: 128MB
- Max New Gen: 64MB

For systems with limited memory, these can be adjusted at build time in `build.gradle`.

### Concurrent Streams

The number of concurrent streams is limited by:
1. Available Tablo tuners
2. Network bandwidth
3. FFmpeg process overhead

Typically, one tuner per concurrent stream is required.

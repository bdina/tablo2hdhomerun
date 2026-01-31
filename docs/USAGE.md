# Tablo2HDHomeRun - Usage Guide

## Prerequisites

1. **Tablo DVR** - A networked Tablo device with an active antenna connection
2. **FFmpeg** - Must be installed and available in PATH
3. **Java 24** (JVM mode) or GraalVM CE (native image)
4. **Network access** between proxy host and Tablo device

## Quick Start

### Step 1: Find Your Tablo IP

Locate your Tablo device IP address (typically via router DHCP lease table or Tablo mobile app).

### Step 2: Start the Proxy

```bash
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

| Variable | Default | Description |
|----------|---------|-------------|
| `TABLO_IP` | `127.0.0.1` | IP address of the Tablo DVR device |
| `PROXY_IP` | `127.0.0.1` | IP address for the proxy to bind to |
| `MEDIA_ROOT` | (none) | Optional path for media file transcoding |

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

### Basic Docker Run

```bash
docker run -d \
  --name tablo-proxy \
  -e TABLO_IP=192.168.1.100 \
  -e PROXY_IP=0.0.0.0 \
  -p 8080:8080 \
  tablo2hdhomerun:native
```

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

1. Verify Tablo IP is correct: `curl http://<tablo-ip>:8885/guide/channels`
2. Check network connectivity between proxy and Tablo
3. Ensure Tablo has completed initial channel scan

### Stream Won't Start

1. Check tuner availability: `curl http://<tablo-ip>:8885/server/tuners`
2. Verify FFmpeg is installed: `ffmpeg -version`
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

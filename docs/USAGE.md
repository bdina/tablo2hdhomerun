# Tablo2HDHomeRun Usage Guide

This guide covers installation, configuration, and operation of Tablo2HDHomeRun.

## Table of Contents

1. [Requirements](#requirements)
2. [Installation](#installation)
3. [Configuration](#configuration)
4. [Running the Server](#running-the-server)
5. [Plex Integration](#plex-integration)
6. [API Reference](#api-reference)
7. [Troubleshooting](#troubleshooting)

---

## Requirements

### Software Dependencies

| Component | Version | Required |
|-----------|---------|----------|
| Java | 24+ | Yes |
| Scala | 3.8.1 | Bundled |
| ffmpeg | Any recent | Yes |
| Gradle | 8.x | Build only |
| GraalVM | 21+ | Native build only |

### Network Requirements

- Local network access to Tablo device (port 8885)
- Internet access for 4th Gen devices (Lighthouse cloud)
- Open port for proxy server (default: 8080)

### Verify ffmpeg Installation

```bash
ffmpeg -version
# Should output version information without errors
```

---

## Installation

### Option 1: Build from Source

```bash
# Clone the repository
git clone https://github.com/yourusername/tablo2hdhomerun.git
cd tablo2hdhomerun

# Build the shadow JAR
gradle shadowJar

# The JAR is created at:
# build/libs/tablo2hdhomerun-{version}.jar
```

### Option 2: Native Image (Recommended for Production)

```bash
# Build shadow JAR first
gradle shadowJar

# Create native image
native-image --static -jar build/libs/tablo2hdhomerun-*.jar

# Or use Docker for a containerized native build
docker build -f Dockerfile.native --tag tablo2hdhomerun:latest .
```

### Option 3: Docker (JVM)

```bash
docker build -f Dockerfile.jvm --tag tablo2hdhomerun:latest .
```

---

## Configuration

### Environment Variables

Create a `.env` file or set environment variables:

#### Legacy Mode (Pre-Gen4 Tablo Devices)

```bash
# Device Mode
TABLO_MODE=legacy

# Tablo Device Network Settings
TABLO_IP=192.168.1.50       # Your Tablo device IP
TABLO_PORT=8885             # Default Tablo port

# Proxy Server Settings
PROXY_IP=0.0.0.0            # Bind to all interfaces
PROXY_PORT=8080             # Port for HDHomeRun API
```

#### Gen4 Mode (4th Generation Tablo Devices)

```bash
# Device Mode
TABLO_MODE=gen4

# Tablo Cloud Credentials
TABLO_EMAIL=your.email@example.com
TABLO_PASSWORD=your_password

# Optional: Select specific device (if you have multiple)
TABLO_DEVICE_NAME="Living Room Tablo"

# Proxy Server Settings
PROXY_IP=0.0.0.0
PROXY_PORT=8080
```

#### Optional Settings

```bash
# Media transcoding directory (optional)
MEDIA_ROOT=/path/to/media

# Note: TABLO_IP and TABLO_PORT are ignored in gen4 mode
# as the device URL is discovered from Lighthouse cloud
```

### Finding Your Tablo Device

#### Legacy Devices

1. Check your router's DHCP client list
2. Use network scanning:
   ```bash
   nmap -p 8885 192.168.1.0/24
   ```
3. Use mDNS/Bonjour if available

#### Gen4 Devices

Device URL is automatically discovered via Lighthouse cloud authentication.

---

## Running the Server

### Interactive Mode

```bash
# Using Java
java -jar build/libs/tablo2hdhomerun-*.jar

# Server runs until you press RETURN
```

### Daemon Mode

```bash
# Using Java
java -jar build/libs/tablo2hdhomerun-*.jar -d

# Server runs until CTRL-C or SIGTERM
```

### Native Image

```bash
./tablo2hdhomerun      # Interactive mode
./tablo2hdhomerun -d   # Daemon mode
```

### Docker

```bash
docker run -d \
  --name tablo2hdhomerun \
  -p 8080:8080 \
  -e TABLO_MODE=legacy \
  -e TABLO_IP=192.168.1.50 \
  -e PROXY_IP=0.0.0.0 \
  tablo2hdhomerun:latest -d
```

### Docker Compose

```yaml
version: '3.8'
services:
  tablo2hdhomerun:
    image: tablo2hdhomerun:latest
    container_name: tablo2hdhomerun
    restart: unless-stopped
    ports:
      - "8080:8080"
    environment:
      - TABLO_MODE=gen4
      - TABLO_EMAIL=${TABLO_EMAIL}
      - TABLO_PASSWORD=${TABLO_PASSWORD}
      - PROXY_IP=0.0.0.0
      - PROXY_PORT=8080
    command: ["-d"]
```

### Systemd Service

Create `/etc/systemd/system/tablo2hdhomerun.service`:

```ini
[Unit]
Description=Tablo to HDHomeRun Proxy
After=network.target

[Service]
Type=simple
User=tablo
Environment=TABLO_MODE=legacy
Environment=TABLO_IP=192.168.1.50
Environment=PROXY_IP=0.0.0.0
Environment=PROXY_PORT=8080
ExecStart=/usr/local/bin/tablo2hdhomerun -d
Restart=always
RestartSec=10

[Install]
WantedBy=multi-user.target
```

Enable and start:

```bash
sudo systemctl enable tablo2hdhomerun
sudo systemctl start tablo2hdhomerun
```

---

## Plex Integration

### Adding as a TV Tuner

1. Open Plex Web -> Settings -> Live TV & DVR
2. Click "Set Up Plex DVR"
3. Plex should automatically discover the device, or enter manually:
   ```
   http://{PROXY_IP}:{PROXY_PORT}
   ```
4. Follow the setup wizard to configure channels

### Manual Discovery Test

Verify the proxy is working:

```bash
# Check device discovery
curl http://192.168.1.100:8080/discover.json

# Expected output:
{
  "FriendlyName": "Tablo 4th Gen - Living Room",
  "DeviceID": "12345678",
  "BaseURL": "http://192.168.1.100:8080",
  "LineupURL": "http://192.168.1.100:8080/lineup.json",
  "TunerCount": 4,
  ...
}
```

### Channel Lineup

```bash
curl http://192.168.1.100:8080/lineup.json

# Expected output:
[
  {
    "GuideNumber": "5.1",
    "GuideName": "NBC",
    "URL": "http://192.168.1.100:8080/channel/S122912_503_01"
  },
  ...
]
```

### Guide Data (XMLTV)

```bash
curl http://192.168.1.100:8080/guide.xml -o guide.xml

# Import into Plex or other EPG applications
```

---

## API Reference

### Discovery Endpoints

#### GET /discover.json

Returns HDHomeRun-compatible device information.

**Response:**

```json
{
  "FriendlyName": "Tablo 4th Gen Proxy",
  "Manufacturer": "tablo2hdhomerun",
  "ModelNumber": "HDHR3-US",
  "FirmwareName": "hdhomerun3_atsc",
  "FirmwareVersion": "20240101",
  "DeviceID": "12345678",
  "DeviceAuth": "tabloauth123",
  "BaseURL": "http://192.168.1.100:8080",
  "LocalIP": "192.168.1.100",
  "LineupURL": "http://192.168.1.100:8080/lineup.json",
  "TunerCount": 4
}
```

### Lineup Endpoints

#### GET /lineup.json

Returns the channel lineup.

**Response:**

```json
[
  {
    "GuideNumber": "5.1",
    "GuideName": "NBC",
    "URL": "http://192.168.1.100:8080/channel/S122912_503_01",
    "type": "ota"
  }
]
```

#### GET /lineup_status.json

Returns channel scan status.

**Response:**

```json
{
  "ScanInProgress": 0,
  "ScanPossible": 1,
  "Source": "Antenna",
  "SourceList": ["Antenna"]
}
```

### Streaming Endpoints

#### GET /channel/{channelId}

Streams a channel as MPEG-TS.

**Parameters:**
- `channelId` - Channel identifier from lineup

**Response:**
- Content-Type: `video/mp2t`
- Body: MPEG Transport Stream

**Example:**

```bash
# Stream to file
curl http://192.168.1.100:8080/channel/S122912_503_01 -o stream.ts

# Play with ffplay
curl http://192.168.1.100:8080/channel/S122912_503_01 | ffplay -

# Play with VLC
vlc http://192.168.1.100:8080/channel/S122912_503_01
```

### Guide Endpoints

#### GET /guide.xml

Returns XMLTV-formatted program guide.

**Response:**
- Content-Type: `text/xml`
- Body: XMLTV XML document

**Example:**

```xml
<tv>
  <channel id="5.1">
    <display-name>NBC</display-name>
    <display-name>5.1</display-name>
  </channel>
  <programme start="2026-02-01T18:00:00Z" stop="2026-02-01T19:00:00Z" channel="5.1">
    <title>Evening News</title>
    <desc>Today's top stories.</desc>
  </programme>
</tv>
```

---

## Troubleshooting

### Common Issues

#### Server Won't Start

**Symptom:** "missing dependency -> ffmpeg"

**Solution:**

```bash
# Install ffmpeg
# Ubuntu/Debian
sudo apt install ffmpeg

# macOS
brew install ffmpeg

# Verify
ffmpeg -version
```

#### No Channels in Lineup

**Symptom:** `/lineup.json` returns `[]`

**Causes & Solutions:**

1. **Legacy mode - wrong IP:**
   ```bash
   # Verify Tablo is reachable
   curl http://192.168.1.50:8885/server/info
   ```

2. **Gen4 mode - authentication failed:**
   - Check `TABLO_EMAIL` and `TABLO_PASSWORD`
   - Check logs for authentication errors

3. **Network issues:**
   ```bash
   # Test connectivity
   ping 192.168.1.50
   nc -zv 192.168.1.50 8885
   ```

#### Stream Not Working

**Symptom:** 500 error on `/channel/{id}`

**Causes & Solutions:**

1. **No available tuners:**
   - Check if recordings are in progress
   - Check other streaming sessions
   - Wait for tuner to become available

2. **ffmpeg issue:**
   ```bash
   # Test ffmpeg manually
   ffmpeg -i "http://tablo-ip:8885/stream/test.m3u8" -c copy -f mpegts test.ts
   ```

#### Gen4 Authentication Fails

**Symptom:** "Login failed" or "Authentication failed"

**Solutions:**

1. Verify email and password are correct
2. Check internet connectivity
3. Ensure Lighthouse cloud is accessible:
   ```bash
   curl https://lighthousetv.ewscloud.com/api/v2/
   ```

### Logging

Application logs to stdout. Increase verbosity by configuring SLF4J:

```properties
# src/main/resources/simplelogger.properties
org.slf4j.simpleLogger.defaultLogLevel=debug
```

### Health Check

```bash
# Quick health check script
#!/bin/bash
PROXY="http://192.168.1.100:8080"

echo "Checking discover..."
curl -s "$PROXY/discover.json" | jq .FriendlyName

echo "Checking lineup..."
curl -s "$PROXY/lineup.json" | jq 'length'

echo "Checking status..."
curl -s "$PROXY/lineup_status.json" | jq .ScanInProgress
```

### Network Debugging

```bash
# Check if port is listening
netstat -tlnp | grep 8080

# Watch HTTP traffic (requires root)
tcpdump -i any port 8080 -A

# Check firewall rules
sudo iptables -L -n | grep 8080
```

---

## Performance Tuning

### Memory Settings

For native image:

```bash
# Configured in build.gradle
minHeap = 128  # MB
maxHeap = 128  # MB
maxNew = 64    # MB
```

For JVM:

```bash
java -Xms128m -Xmx256m -jar tablo2hdhomerun.jar
```

### Concurrent Streams

The number of concurrent streams is limited by:

1. Tablo device tuner count (2-4 typically)
2. Network bandwidth
3. System resources

### Caching Behavior

| Data | Cache Duration |
|------|----------------|
| Channel lineup | 1 day |
| Program guide | 1 hour |

To force refresh, restart the server.

---

## Quick Reference

### Environment Variables Summary

| Variable | Required | Default | Description |
|----------|----------|---------|-------------|
| `TABLO_MODE` | No | `legacy` | `legacy` or `gen4` |
| `TABLO_IP` | Legacy only | `127.0.0.1` | Tablo device IP |
| `TABLO_PORT` | No | `8885` | Tablo device port |
| `TABLO_EMAIL` | Gen4 only | - | Account email |
| `TABLO_PASSWORD` | Gen4 only | - | Account password |
| `TABLO_DEVICE_NAME` | No | - | Select specific device |
| `PROXY_IP` | No | `127.0.0.1` | Bind address |
| `PROXY_PORT` | No | `8080` | Listen port |
| `MEDIA_ROOT` | No | - | Transcoding directory |

### Common Commands

```bash
# Build
gradle shadowJar

# Run (interactive)
java -jar build/libs/tablo2hdhomerun-*.jar

# Run (daemon)
java -jar build/libs/tablo2hdhomerun-*.jar -d

# Test discovery
curl http://localhost:8080/discover.json

# Test lineup
curl http://localhost:8080/lineup.json

# Test stream
curl http://localhost:8080/channel/{id} | ffplay -
```

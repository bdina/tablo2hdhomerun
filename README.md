# Tablo2HDHomeRun

A HDHomeRun-compatible proxy for TabloTV DVR devices.

Tablo2HDHomeRun exposes a TabloTV DVR as an HDHomeRun tuner, enabling compatibility with media applications like Plex, Jellyfin, Channels DVR, and any software that supports HDHomeRun devices.

## Features

- HDHomeRun device emulation for TabloTV
- 4th Generation Tablo support (Tablo account / Lighthouse auth)
- Live TV streaming via MPEG-TS
- XMLTV program guide generation
- Channel lineup in HDHomeRun format
- Docker support with native image builds
- Optional media file transcoding with Intel QSV acceleration

## Requirements

- Java 11+ and Scala 3.8.1 (JVM mode)
- GraalVM CE (native image mode; Docker uses GraalVM CE 25)
- FFmpeg (optional by default; required when `STREAM_BACKEND=ffmpeg`)
- Network access to TabloTV device

## Quick Start

### Using Environment Variables

```bash
export TABLO_EMAIL=your@email.com       # Required (default is 4th gen Tablo)
export TABLO_PASSWORD=yourpassword
export TABLO_IP=192.168.1.100           # Your Tablo device IP
export PROXY_IP=0.0.0.0                 # Bind to all interfaces
./tablo2hdhomerun -d
```

For legacy Tablo set `TABLO_GEN=legacy` (no cloud credentials). See [Usage Guide](docs/USAGE.md).

### Using Docker

```bash
docker build -f Dockerfile.native --tag tablo2hdhomerun:latest .
docker run -d \
  --name tablo-proxy \
  -e TABLO_EMAIL=your@email.com \
  -e TABLO_PASSWORD=yourpassword \
  -e TABLO_IP=192.168.1.100 \
  -e PROXY_IP=0.0.0.0 \
  -p 8080:8080 \
  tablo2hdhomerun:latest
```

For legacy Tablo add `-e TABLO_GEN=legacy` (credentials not required).

## Building

Use `gradle` or `./gradlew` if using the Gradle wrapper.

### Create Shadow JAR

```bash
gradle shadowJar
java -jar build/libs/tablo2hdhomerun-<version>.jar
```

### Create Native Image

```bash
gradle nativeImage
./build/tablo2hdhomerun -d
```

### Docker Build (Native)

```bash
docker build -f Dockerfile.native --tag tablo2hdhomerun:<version> .
```

### Docker Build (JVM)

```bash
docker build -f Dockerfile.jvm --tag tablo2hdhomerun:<version> .
```

## Configuration

| Environment Variable | Default | Description |
|---------------------|---------|-------------|
| `TABLO_IP` | `127.0.0.1` | IP address of the Tablo DVR |
| `TABLO_GEN` | `4thgen` | Tablo generation: `4thgen` or `legacy` |
| `TABLO_EMAIL` | (required for 4th gen) | Tablo account email |
| `TABLO_PASSWORD` | (required for 4th gen) | Tablo account password |
| `TABLO_DEVICE_NAME` | (none) | Optional filter for 4th Gen device by name |
| `PROXY_IP` | `127.0.0.1` | IP address for proxy to bind |
| `STREAM_BACKEND` | `hls` | Live stream backend: `hls` or `ffmpeg` |
| `MEDIA_ROOT` | (none) | Optional media transcoding path |

## API Endpoints

| Endpoint | Description |
|----------|-------------|
| `GET /discover.json` | Device discovery metadata |
| `GET /lineup.json` | Channel lineup |
| `GET /lineup_status.json` | Scan status |
| `GET /channel/{id}` | Live MPEG-TS stream |
| `GET /guide.xml` | XMLTV program guide |

## Documentation

- [Architecture Guide](docs/ARCHITECTURE.md) - System design, components, and data flows
- [Usage Guide](docs/USAGE.md) - Deployment, configuration, and troubleshooting
- [Tuner Session Sharing Design](docs/session-sharing.md) - Shared channel sessions and tuner capacity
- [OpenAPI: Legacy Tablo API](docs/openapi/tablo-legacy.yaml) - Legacy Tablo TV device native API specification
- [OpenAPI: Tablo 4th Gen API](docs/openapi/tablo-4thgen.yaml) - Tablo 4th Generation API specification (cloud + local device)
- [Tablo 4th Gen API Guide](docs/tablo-api-guide.md) - Authentication, workflows, and PCAP regeneration
- [OpenAPI: HDHomeRun API](docs/openapi/hdhomerun.yaml) - HDHomeRun-compatible proxy API specification

## Integration

### Plex

1. Settings > Live TV & DVR > Set Up Plex DVR
2. Enter `http://<proxy-ip>:8080` as the tuner address

### Jellyfin

1. Dashboard > Live TV > Add Tuner Device
2. Select "HD Homerun" and enter `http://<proxy-ip>:8080`

### Channels DVR

1. Settings > Sources > Add Source > HDHomeRun
2. Enter the proxy IP address

## License

See [LICENSE](LICENSE) for details.

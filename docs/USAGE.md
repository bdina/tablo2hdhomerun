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
           -p 8080:8080 tablo2hdhomerun:jvm
```

`Dockerfile.jvm` runs Java 25 with a build-time AppCDS archive (`-XX:SharedArchiveFile=app-cds.jsa -Xshare:auto`), `-XX:+UseCompactObjectHeaders`, `-XX:+UseSerialGC`, `-Xms32m`, `-Xmx128m`, `-XX:MaxNewSize=64m`, `-Xss256k`, `STOPSIGNAL SIGTERM`, a built-in Docker `HEALTHCHECK`, and `-d` enabled by default.

### Option 4: Docker Native (Recommended)

```bash
docker build -f Dockerfile.native --tag tablo2hdhomerun:native .
docker run -e TABLO_IP=192.168.1.100 -e PROXY_IP=0.0.0.0 \
           -p 8080:8080 --device /dev/dri:/dev/dri \
           tablo2hdhomerun:native
```

`Dockerfile.native` packages the static musl binary on `scratch` with `/etc/ssl/certs/ca-certificates.crt` (for 4th Gen cloud TLS requests), `/usr/share/zoneinfo`, `STOPSIGNAL SIGTERM`, and a built-in `HEALTHCHECK`.

## Environment Variables

### Common Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `TABLO_GEN` | `4thgen` | Tablo generation: `4thgen` or `legacy` |
| `TABLO_IP` | `127.0.0.1` | IP address of the Tablo DVR device |
| `TABLO_PORT` | `8887` (4th gen) / `8885` (legacy) | Tablo device REST API port |
| `PROXY_IP` | `127.0.0.1` | IP address for the proxy to bind to |
| `STREAM_BACKEND` | `hls` | Live stream backend: `hls` or `ffmpeg` |
| `LOG_LEVEL` | (none) | Application log level (e.g. `DEBUG`, `INFO`) |
| `PEKKO_LOG_LEVEL` | (same as `LOG_LEVEL`) | Pekko/Actor log level; falls back to `LOG_LEVEL` |
| `MEDIA_ROOT` | (none) | Optional path for media file transcoding |

### Weak OTA / Plex recovery (default `hls` backend)

Recovery retunes automatically on Tablo session closure, playlist end (`#EXT-X-ENDLIST`), unauthorized segment responses, range mismatches, or stream completion. Playback stays active while real video flows; MPEG-TS null packets keep the HTTP connection alive during gaps. By default (`STREAM_RECOVERY_TIMEOUT_SEC=0`), the stream stays tuned indefinitely while the client remains connected, leaving it up to the viewer to stop playback or change the channel (or you can configure a finite `STREAM_RECOVERY_TIMEOUT_SEC` to end unattended streams on a dead channel).

Transport data loss during severe OTA signal interruptions (such as antenna flutter or wind gusts) is expected and acceptable. Rather than tearing down the hardware tuner on transient RF multipath or leaking damaged macroblocks to downstream hardware decoders (e.g., Plex Intel GPU transcoder), the proxy prioritizes hands-free playback continuity:
1. **Control-Plane Session-Presence Probing**: When `pls.m3u8` stops advancing (`304 Not Modified` or unchanged sequence), `HlsPlaylistPoller` switches to fast `500ms` polling and probes the Tablo 4th-Gen control plane (`POST /player/sessions/{token}/keepalive?lh=1`) starting at the 12th consecutive stall poll (`~6s` of unchanged polls, then every 4th poll `~2s`, per `3969fe1`) or immediately on playlist `404`. While the Tablo keeps the session open (`200 OK`), the stream stays attached to the existing hardware tuner lock to ride out transient RF dropouts without tuner thrashing.
2. **Continuous Downstream Keepalive**: During inter-segment polling intervals, RF pauses, and retunes, `ResilientHlsSource` holds the connection by continuously emitting MPEG-TS null packets (PID `0x1FFF`) every 40ms, providing a clean freeze-frame experience (default: `STREAM_ENABLE_SLATE_VIDEO=false`). Optionally, `STREAM_ENABLE_SLATE_VIDEO=true` can be enabled to emit a looping "Please Stand By" MPEG-TS slate video (`slate.ts`) after a stall.
3. **Immediate Cold Retune on Session Closure**: When the Tablo closes the `/watch` session (`4xx` on keepalive probe or `#EXT-X-ENDLIST` on `pls.m3u8`) or transient probe errors exceed `STREAM_HLS_POLL_FAILURES_MAX` (default 4), `WatchSession.coldRetune` immediately requests a fresh `/watch` session (skipping `DELETE` when the Tablo already closed the old session, resetting sequence tracking, and applying exponential backoff capped at `STREAM_MAX_RETRY_DELAY_SEC`).
4. **Surgical Slice-Level Sanitization, Frame-Header Gating & Fast-Attach Alignment**: `MpegTsSync.cacheFlow` and `FreezeFramePacer` isolate transport damage (`TEI=1`, continuity counter jumps, and unflagged out-of-bounds MPEG-2 slice codes `> maxSliceRow` on both `PUSI=0` and `PUSI=1` packets) to individual video slices (`droppingVideoSlice`), resuming on the next valid slice start code (`00 00 01 [01..maxSliceRow]`) after isolated in-frame errors or gating until the next clean `PUSI=1` PES frame header (`requireVideoPesStart`) after `CC` gaps or dropped `PUSI=1` packets, while preserving adaptation-field PCRs, locking and sanitizing the 22-byte MPEG-2 Sequence Header (`00 00 01 B3`, quantiser-matrix-aware per `4c36514`) and validated `Sequence Extension` (`00 00 01 B5 1x` per `9a2e143`, protecting `progressive_sequence`, `chroma_format` `4:2:0`, and `horizontal/vertical_size_extension` while leaving `0xB5 8x` `picture_coding_extension` untouched), requiring 2-packet candidate confirmation before updating learned `PAT`/`PMT` PIDs, gating corrupted audio until a valid `0x0B77` (AC-3) or `0xFFF` (ADTS) syncword arrives, dropping corrupt PES frames when single-packet `PTS`/`DTS` timestamps jump `> 2s`, and rewriting outbound 4-bit Continuity Counters (`CC`) sequentially per PID. If upstream video stalls for `>= 10s` (`STREAM_FREEZE_THRESHOLD_SEC=10`), `FreezeFramePacer` injects valid `PAT`/`PMT` + MPEG-2 skipped P-frames + exact codec-rate silent AC-3 (`31.25 fps`) / ADTS AAC (`46.875 fps` at `48 kHz`, `f415a15`) frames with strictly monotonic live audio splicing and gates live resumption on the next keyframe (`hasKeyframeHeader`); during non-frozen live video, if a learned audio PID falls `>= 1.5s` behind live video while audio is wedged or dropped, silent frames bridge the gap `250 ms` behind video (`b1188e5`) so downstream `-codec:a copy` muxers never latch a permanent A/V offset while `AudioLossMonitor` continues monitoring live audio health independently. On retune or fast-attach, `MpegTsSync.alignToIFrame` aligns audio PIDs independently on their first `PUSI = 1` PES packet (preventing FFmpeg audio probe starvation) and aligns video on the first keyframe within a 3-second wall-clock window (`DefaultMaxAlignmentWait = 3.seconds` anchored on the first dropped video packet, gating any forced alignment on `PUSI=1` PES frame boundaries and injecting a synthesized `00 00 01 B3` + `00 00 01 B5 1x` Sequence Header packet if attaching at a bare GOP or forced boundary).

The native HLS backend (`STREAM_BACKEND=hls`, default) adds:

- HLS v4 `#EXT-X-BYTERANGE` support with HTTP range requests
- Sub-second adaptive playlist polling (`500ms` during stalls, 75% of `#EXTINF` segment duration when advancing even when Tablo declares `#EXT-X-TARGETDURATION:1` per `3969fe1`)
- Conditional playlist requests when the Tablo device returns `ETag` or `Last-Modified`
- Short ranged segment reads accepted during reception drops; trailing partial packets cleanly dropped by `MpegTsSync.alignPackets`
- Surgical MPEG-2 slice-level recovery (`droppingVideoSlice`), out-of-bounds slice row clamping (`<= maxSliceRow`), quantiser-safe Sequence Header (`00 00 01 B3`, `4c36514`) & validated `Sequence Extension` (`00 00 01 B5 1x`, `9a2e143`) locking/sanitization with `restoredSeqHeaders` anomaly logging, and continuous outbound `CC` rewriting
- 4th gen watch-session expiry awareness before recovery retune, plus automatic Lighthouse cloud token refresh on `401`/`403`
- 4th gen Tablo player-session keepalive (`POST /player/sessions/{token}/keepalive?lh=1`) both periodically and as an active liveness probe during playlist stalls or `404` responses
- 4th gen session teardown (`DELETE /player/sessions/{token}`) when the client disconnects and idle grace expires
- Cold retune (`WatchSession.coldRetune`) with true exponential backoff (capped at `STREAM_MAX_RETRY_DELAY_SEC`, default `15s`, with `tuneTimeout + maxRetryDelay` `45s` reset window via `e811d2f` & `5cd1bc5`) when the Tablo closes a session or an unchanged playlist exceeds `STREAM_HLS_MAX_STALL_SEC` (`60s` default); late cold-retune completions are guarded against teardown leaks and superseded attempts (`5cd1bc5`), stale cached segments are suppressed across cold retunes so playback resumes strictly on new content (`d9a52dd`), liveness probe logs distinguish `reason=unchanged` vs. `reason=404` (`5058ba1`), and health-gated `IdleGrace` (`ecd3da8`) immediately tears down frozen or retuning leases on client disconnect (and evicts stale `IdleGrace` leases on reconnect) so background retunes cease when viewers leave
- Sustained-audio-loss cold retune (`AudioLossMonitor` + `ResilientHlsSource.RetuneTrigger`, `STREAM_AUDIO_LOSS_TIMEOUT_SEC=30`) that recycles wedged Tablo `/watch` sessions if primary audio drops out during active live video (paused during freezes/replays, backed off up to `300s` on broadcaster outages)
- Single resume prefix injection on retune: multi-PID discontinuity packets plus cached PAT/PMT tables so downstream decoders cleanly reset timelines
- Decoupled audio `PUSI` alignment and codec-aware video I-Frame / GOP alignment (`alignToIFrame`) with synthesized MPEG-2 Sequence Header injection when needed

> [!NOTE]
> **Hardware Decoder Protection**: Both `TEI`-flagged transport errors and unflagged RF bit errors in MPEG-2 slice headers (`code > maxSliceRow`), Sequence Headers (`00 00 01 B3`, including broadcasts carrying custom quantiser matrices per `4c36514`), or `Sequence Extension` headers (`00 00 01 B5 1x` per `9a2e143`) are sanitized in-stream by `MpegTsSync.cacheFlow` and `FreezeFramePacer` before reaching Plex's FFmpeg/VAAPI transcoder, preventing fatal `slice below image`, `Invalid frame dimensions 0x0`, and `Parsed_hwupload_4` filtergraph crashes (software `libx264` fallback) while preserving Tablo's native hardware STC timestamps across B-frames.

MPEG-TS null-packet keepalive and slate video generation (in `ResilientHlsSource`) and Tablo player-session keepalive are separate mechanisms: the former keeps the HTTP chunked response and transcoder pipeline alive during HLS gaps; the latter renews and probes the Tablo watch session token on the device.

| Variable | Default | Description |
| :--- | :--- | :--- |
| `STREAM_METRICS_INTERVAL_SEC` | `30` | Interval in seconds for periodic `[stream:metrics]` operational `INFO` logs (`<= 0` disables periodic logging) |
| `STREAM_FREEZE_THRESHOLD_SEC` | `10` | Seconds of upstream video silence or media-clock deficit before `FreezeFramePacer` synthesizes repeat P-frames and silent audio (`<= 0` disables freeze-frame synthesis) |
| `STREAM_AUDIO_LOSS_TIMEOUT_SEC` | `30` | Seconds of active non-frozen live video (`>= 150` frames) with zero recovered primary-PID audio before triggering an automatic cold retune (`<= 0` disables; exponential backoff up to `300s`) |
| `STREAM_STALL_TIMEOUT_SEC` | `45` | Resilient stall timeout configuration in seconds (`0` disables byte-stall watchdog; 4th-gen HLS uses control-plane keepalive session probing) |
| `STREAM_TUNE_TIMEOUT_SEC` | `30` | Max seconds to wait for initial or retuned data |
| `STREAM_RETRY_DELAY_SEC` | `1` | Initial delay in seconds between retune attempts |
| `STREAM_MAX_RETRY_DELAY_SEC` | `15` | Maximum backoff delay in seconds for prolonged outages |
| `STREAM_RECOVERY_TIMEOUT_SEC` | `0` | Total outage duration before ending stream (`0` = infinite recovery while viewer is tuned) |
| `STREAM_ENABLE_SLATE_VIDEO` | `false` | Enable looping "Please Stand By" slate video during dropouts. Default `false` uses a freeze-frame experience with null packets. |
| `STREAM_RECOVERY_LIVE_EDGE_SEGMENTS` | `3` | Number of live-edge segments (~7.5s cushion) fetched upon stream recovery to prime player/transcoder buffers |
| `STREAM_HLS_HEARTBEAT_SEC` | `60` | Interval for HLS stream heartbeat DEBUG logs |
| `STREAM_HLS_POLL_FAILURES_MAX` | `4` | Consecutive playlist fetch or keepalive probe failures before retune |
| `STREAM_HLS_MAX_STALL_SEC` | `60` | Maximum seconds an unchanged HLS playlist may stall before triggering cold retune (`<= 0` disables cap) |

### Operational Stream Quality Metrics (`[stream:metrics]` & `GET /status.json`)

While a channel is tuned, `MpegTsSync.cacheFlow` and `StreamMetrics.HlsCollector` continuously measure transport, elementary frame, media clock, and HLS segment delivery health over a rolling window (default `30s`, configurable via `STREAM_METRICS_INTERVAL_SEC`).

Every 30 seconds (and on stream completion), an `INFO` log line is emitted:

```text
[stream:metrics] channel="CBS 2.1 (a1b2c3d4)" windowSec=30.0 signalQualityPct=100.0 feedQualityPct=100.0 symbolQualityPct=100.0 bitrateMbps=6.62 fps=59.94 ptsAdvanceSec=30.0 avSkewMs=-12 videoFrames=1798/1798 audioFrames=937/937 keyframes=30 tei=0 ccErr=0 sliceDrops=0 ptsScrubs=0 syncLossBytes=0 keepalivePct=0.0 segs=15 maxGapMs=1980 shortReads=0 seqGaps=0 segRetries=0 stallPolls=0 retunes=0
```

- **`signalQualityPct` (`0.0–100.0%`)**: Percentage of decoded video PES frames (80% weight) and audio PES frames (20% weight) delivered completely intact without `TEI`, continuity counter (`CC`) discontinuities, out-of-bounds MPEG-2 slice drops, or invalid audio syncwords.
- **`feedQualityPct` (`0.0–100.0%`)**: Real-time media clock completeness (`ptsAdvanceSec / windowSec`), with a 1-segment (`2.0s`) burst boundary tolerance so healthy segmented HLS delivery reports `100.0%`. Drops when upstream stalls or experiences Failure Mode E2 trickle collapse.
- **`symbolQualityPct` (`0.0–100.0%`)**: Percentage of elementary stream MPEG-TS packets free of transport errors (`TEI`, `CC` jumps, slice drops, or syncword misses).
- **Co-existence with `[mpegts] transport anomalies`**: The periodic `[stream:metrics]` `INFO` log co-exists with the 30s `[mpegts] transport anomalies` `WARN` log, which fires only when transport errors occur.
- **SiliconDust HDHomeRun `GET /status.json`**: Live per-tuner metrics are also exposed via `GET /status.json`, mapping `feedQualityPct` to `SignalStrengthPercent`, `signalQualityPct` to `SignalQualityPercent`, `symbolQualityPct` to `SymbolQualityPercent`, and real MPEG-TS throughput to `NetworkRate` (bps).

### 4th Generation Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `TABLO_EMAIL` | (required for 4th gen) | Tablo account email for cloud authentication |
| `TABLO_PASSWORD` | (required for 4th gen) | Tablo account password |
| `TABLO_DEVICE_NAME` | (none) | Optional filter to select device by name |
| `DEVICE_ID` | `12345678` | HDHomeRun device identifier (see [DeviceID Guide](hdhomerun-device-id.md)) |
| `TUNER_COUNT` | `2` | Number of virtual tuners reported in discover.json |
| `ENABLE_UDP_DISCOVERY` | `true` | Enable UDP 65001 HDHomeRun broadcast auto-discovery responder |
| `ENABLE_HLS_ENDPOINT` | `true` | Enable open-standard HLS playlist (`.m3u8` and `.m3u`) endpoints |
| `SESSION_IDLE_GRACE_SEC` | `180` | Idle grace period (in seconds) to keep tuner session active after client disconnects |
| `STREAM_PRE_ROLL_KEEP_ALIVE` | `true` | Stream MPEG-TS null packets (PID 0x1FFF) immediately on cold tunes to satisfy Plex grabber timeouts |
| `STREAM_PRE_ROLL_INTERVAL_MS` | `100` | Pre-roll keepalive emission interval in milliseconds |
| `STREAM_PRE_ROLL_PACKETS` | `7` | Number of 188-byte MPEG-TS null packets per pre-roll emission chunk (7 packets = 1316 bytes) |
| `TUNER_STATE_POLL_INTERVAL_SEC` | `30` | Interval in seconds to poll Tablo 4th Gen tuner state and log active internal/external channels (`<= 0` disables polling) |

### Plex Live TV & DVR Recommendations

1. **Cold Tuning & Pre-Roll Keep-Alive**: Tablo 4th Gen hardware takes 8.5 to 9.0 seconds to lock onto an OTA broadcast frequency and start internal transcoding. Plex Media Server's Live TV grabber times out if zero bytes are received within ~9 seconds. With `STREAM_PRE_ROLL_KEEP_ALIVE=true` (enabled by default), `tablo2hdhomerun` responds immediately with `HTTP 200 OK Chunked` and trickles ISO/IEC 13818-1 compliant MPEG-TS null packets (~100 kbps) to keep Plex's connection alive and active. As soon as the Tablo tuner finishes locking, the stream transitions seamlessly to live broadcast.
2. **Channel Lineup Cleanup**: In Plex Web (**Settings $\rightarrow$ Live TV & DVR $\rightarrow$ Channels**), disable any un-tunable or weak subchannels (for example, subchannels that consistently fail to lock due to weak antenna reception). Pre-stream failures (as well as Legacy watch errors, tuner exhaustion, and unknown channels) return SiliconDust `X-HDHomeRun-Error` headers (`801 Unknown Channel`, `803 System Busy`, `805 All Tuners In Use`, `806 Tune Failed`, `807 No Video Data`); note that once `HTTP 200 OK` pre-roll keepalive bytes start flowing to keep Plex waiting across 4th-gen cold retries, HTTP cannot retroactively emit a trailing `503`.
3. **All-Day Viewing & Transcode Scratch Sizing**: Plex Media Server retains an unpruned live time-shift buffer in `/transcode` for the entire duration of a live TV session (~1.2–5.5 GB/hour depending on broadcast bitrate, or ~10 GB over 7.5 hours). For all-day TV watching (8–16+ hours), mount `/transcode` to an NVMe/SSD directory or size your `tmpfs` ramdisk to at least `24G` (`tmpfs: - /transcode:size=24G,mode=1777`) so Plex does not abort the session with `No space left on device`.
4. **Ideal End-User Experience & Failure Recovery**:
   - **Continuous Playback During Instability**: When OTA reception drops or the Tablo tuner hiccups, Plex clients (Apple TV, iOS, Android TV, Roku, Plex Web) remain in active playback state instead of exiting with fatal error dialogs ("Playback Error" or "Can't Play This: Format isn't supported").
   - **Frozen Video Over Fatal Errors**: The proxy trickles MPEG-TS null packets every 40 ms to keep Plex client grabbers warm while re-tuning the Tablo tuner in the background. The viewer observes a momentary frozen picture (~17–19s per cold dropout) while the signal recovers, requiring zero manual intervention.
   - **No Seen-Content Replay**: Upon tuner recovery, streaming reconnects strictly at the live edge with a 3-segment (~7.5s) cushion (via `STREAM_RECOVERY_LIVE_EDGE_SEGMENTS`, default 3). Video and audio segments already seen prior to the interruption are never re-sent, preventing disorienting loops or time-warps while immediately rebuilding downstream transcode buffers.
   - **Viewer-Controlled Persistence**: By default (`STREAM_RECOVERY_TIMEOUT_SEC=0`), the proxy keeps the channel tuned indefinitely during extended weather outages with continuous null keepalives and 15s-capped retry backoff until reception returns or the viewer exits playback. Set `STREAM_RECOVERY_TIMEOUT_SEC` to a positive value (e.g. `60`) if you prefer the proxy to terminate the stream automatically after a fixed outage duration.

## Streaming a Channel

### Direct HTTP Access

```bash
# Stream channel 12345 to VLC
vlc http://localhost:8080/channel/12345

# Stream virtual channel (SiliconDust HDHomeRun compatible route)
vlc http://localhost:8080/auto/v4.1

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
| `GET /discover.json` | JSON | HDHomeRun device metadata (`DeviceID`, `TunerCount`, `BaseURL`, etc.) |
| `GET /status.json` | JSON | SiliconDust HDHomeRun per-tuner status array (`Resource`, `VctNumber`, `VctName`, `SignalStrengthPercent`, `SignalQualityPercent`, `SymbolQualityPercent`, `NetworkRate`, `TargetIP`) |
| `GET /lineup.json` | JSON | HDHomeRun channel lineup array with GuideName/GuideNumber |
| `GET /lineup_status.json` | JSON | Channel scan status (`ScanInProgress`, `ScanPossible`, `Source`) |
| `POST /lineup.post?scan=start` | Empty / 200 OK | Trigger channel lineup refresh / scan |
| `POST /lineup.post?scan=abort` | Empty / 200 OK | Abort channel scan |
| `GET /lineup.m3u` | `application/x-mpegURL` | Open standard M3U channel playlist with EXTINF tags |
| `GET /channel/{id}` | `video/mp2t` | Continuous chunked MPEG-TS stream (for Plex, HDHR clients) |
| `GET /auto/v{guideNumber}` | `video/mp2t` | Resolve virtual channel guide number and stream MPEG-TS (with SiliconDust `X-HDHomeRun-Error` error headers) |
| `GET /channel/{id}.m3u8` | `application/x-mpegURL` | Native HLS playlist with rewritten proxied segment URIs |
| `GET /channel/{id}/segment/{url}` | `video/mp2t` | Proxied HLS video segment with CORS and caching headers |
| `GET /guide.xml` | `text/xml` | XMLTV program guide |
| `GET /favicon.ico` | Empty | No-op (avoids 404s) |
| `UDP 65001` | Binary | HDHomeRun protocol auto-discovery broadcast listener/responder |

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

1. Ensure `tablo2hdhomerun` is running. In a co-located Docker setup, use Docker Compose with `depends_on: tablo2hdhomerun: condition: service_healthy`.
2. Go to Plex Settings > Live TV & DVR.
3. Plex will auto-discover the tuner via UDP 65001 or you can enter `http://<proxy-ip>:8080` manually.
4. With `DEVICE_ID=12345678`, existing Plex DVR pairings and channel mappings are preserved.
5. If tuners become busy, the proxy returns `503 Service Unavailable`, allowing Plex to cleanly queue or reschedule recordings.

#### Recommended Co-located `docker-compose.yml`

```yaml
services:
  tablo2hdhomerun:
    container_name: tablo2hdhomerun
    image: tablo2hdhomerun:latest
    restart: always
    network_mode: host
    environment:
      - TABLO_IP=192.168.2.28
      - TABLO_GEN=4thgen
      - TABLO_EMAIL=user@example.com
      - TABLO_PASSWORD=secret
      - PROXY_IP=192.168.2.24
      - STREAM_BACKEND=hls
      - DEVICE_ID=12345678
      - TUNER_COUNT=2
      - ENABLE_UDP_DISCOVERY=true
      - ENABLE_HLS_ENDPOINT=true
    healthcheck:
      test: ["CMD", "./tablo2hdhomerun", "--healthcheck"]
      interval: 10s
      timeout: 3s
      retries: 3
      start_period: 5s
    mem_limit: 512m

  plex:
    container_name: plex
    image: plexinc/pms-docker:plexpass
    devices:
      - /dev/dri:/dev/dri
    restart: always
    environment:
      - TZ=America/New_York
      - PLEX_CLAIM=your-claim-token
    network_mode: host
    volumes:
      - /media/ext-hd/usb/media/plex/config:/config
      - /media/ext-hd/usb/media/plex/dvr:/data/dvr
      - /media/ext-hd/raid/media:/data/media
      - /media/ext-hd/ssd/movies:/data/sdd/movies
    tmpfs:
      - /transcode:size=24G,mode=1777
    mem_limit: 32g
    depends_on:
      tablo2hdhomerun:
        condition: service_healthy
```

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
- Min Heap: 32MB
- Max Heap: 128MB
- Max New Gen: 64MB

`Dockerfile.jvm` aligns the Java 25 development/JVM runtime to the same heap ceiling using `-XX:+UseCompactObjectHeaders`, `-XX:+UseSerialGC`, `-XX:SharedArchiveFile=app-cds.jsa -Xshare:auto`, `-Xms32m`, `-Xmx128m`, `-XX:MaxNewSize=64m`, and `-Xss256k` (while keeping Tiered/C2 JIT compilation enabled). In `docker-compose.example.yml` and container `HEALTHCHECK` definitions, `--healthcheck` probes override the native heap flags with `-XX:MinHeapSize=4m -XX:MaxHeapSize=16m -XX:MaxNewSize=8m` to minimize transient probe memory. `application.conf` (`2a70433`) caps Pekko HTTP `host-connection-pool.max-connections = 8` and enables graceful `CoordinatedShutdown` on `SIGTERM`.

For systems with limited memory, native image heap bounds can be adjusted at build time in `build.gradle`.

### Concurrent Streams

The number of concurrent streams is limited by:
1. Available Tablo tuners
2. Network bandwidth
3. FFmpeg process overhead

Typically, one tuner per concurrent stream is required.

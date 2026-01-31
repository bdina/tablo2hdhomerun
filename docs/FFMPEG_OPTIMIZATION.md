# FFmpeg Optimization Guide

This document analyzes the ffmpeg usage in Tablo2HDHomeRun and provides optimization recommendations for different use cases.

## Table of Contents

1. [Current Implementation](#current-implementation)
2. [Understanding the Pipeline](#understanding-the-pipeline)
3. [Optimization Options](#optimization-options)
4. [Recommended Configurations](#recommended-configurations)
5. [Troubleshooting](#troubleshooting)

---

## Current Implementation

### Location in Code

```
src/Device.js lines 202-208
```

### Current Command

```bash
ffmpeg -i {playlist_url} -c copy -f mpegts -v repeat+level+{log_level} pipe:1
```

### Breakdown

| Argument | Purpose |
|----------|---------|
| `-i {playlist_url}` | Input: HLS playlist from Tablo device |
| `-c copy` | Stream copy (no transcoding) |
| `-f mpegts` | Output format: MPEG Transport Stream |
| `-v repeat+level+{log_level}` | Logging verbosity |
| `pipe:1` | Output to stdout (piped to HTTP response) |

### What It Does

```
┌─────────────┐      ┌─────────────┐      ┌─────────────┐
│   Tablo     │      │   ffmpeg    │      │    Plex     │
│   Device    │─────►│  (remux)    │─────►│   Client    │
│             │ HLS  │             │MPEG-TS             │
│  .m3u8/.ts  │      │  -c copy    │      │             │
└─────────────┘      └─────────────┘      └─────────────┘
```

The current implementation:
- **No transcoding** - Video/audio pass through unchanged (`-c copy`)
- **Format conversion only** - HLS container to MPEG-TS container
- **Zero quality loss** - Bitstream is identical
- **Low CPU usage** - Only demuxing/remuxing, no encoding

---

## Understanding the Pipeline

### HLS Input Characteristics

Tablo delivers streams as HTTP Live Streaming (HLS):

```
playlist.m3u8
├── segment_001.ts (2-10 seconds of video)
├── segment_002.ts
├── segment_003.ts
└── ... (new segments added as stream continues)
```

**Key Properties:**
- Segmented delivery (typically 2-6 second chunks)
- Variable segment availability (network dependent)
- May include multiple quality levels (adaptive bitrate)

### MPEG-TS Output Requirements

Plex/HDHomeRun expects continuous MPEG-TS:

```
┌────────────────────────────────────────────────────────┐
│ Continuous MPEG-TS stream with periodic PAT/PMT       │
│ ────────────────────────────────────────────────────► │
│ No gaps, consistent timing, resync-friendly           │
└────────────────────────────────────────────────────────┘
```

**Key Requirements:**
- Continuous byte stream
- Regular PAT/PMT packets for decoder sync
- No gaps or timing discontinuities

---

## Optimization Options

### Input Options

#### Reduce Startup Latency

```bash
-fflags +nobuffer         # Disable input buffering
-fflags +fastseek         # Enable fast seeking in segments
-flags low_delay          # Minimize processing delay
-analyzeduration 1000000  # Analyze only 1 second (default 5s)
-probesize 1000000        # Probe only 1MB of data
```

**Impact:**
- Faster initial playback (1-2s vs 3-5s)
- May miss metadata on unusual streams
- Recommended for live TV where speed matters

#### Handle Corrupt Data

```bash
-fflags +discardcorrupt   # Discard corrupt packets instead of failing
-err_detect ignore_err    # Ignore decode errors
```

**Impact:**
- More resilient to network glitches
- May produce brief artifacts instead of failures
- Recommended for unstable networks

#### Network Reconnection

```bash
-reconnect 1              # Enable reconnection
-reconnect_at_eof 1       # Reconnect if stream ends
-reconnect_streamed 1     # Reconnect for streaming protocols
-reconnect_delay_max 5    # Max 5 seconds between retries
```

**Impact:**
- Automatic recovery from network hiccups
- Stream continues after brief outages
- Essential for reliable long-duration viewing

#### Timeouts

```bash
-timeout 30000000         # 30 second connection timeout (microseconds)
-rw_timeout 10000000      # 10 second read/write timeout
```

**Impact:**
- Prevents hanging on unresponsive servers
- Faster failure detection
- Allows reconnection logic to trigger

---

### Output Options

#### MPEG-TS Optimization

```bash
-mpegts_flags +resend_headers  # Resend PAT/PMT regularly
-pat_period 0.1                # PAT every 0.1 seconds
-sdt_period 0.5                # SDT every 0.5 seconds
```

**Impact:**
- Better decoder synchronization
- Faster channel change on client
- Helps Plex maintain stream sync

#### Flush Behavior

```bash
-flush_packets 1          # Flush output packets immediately
-max_muxing_queue_size 1024  # Larger queue for timing variations
```

**Impact:**
- Lower output latency with flush
- Better stability with larger queue
- Trade-off between latency and smoothness

---

### General Options

#### Prevent Stdin Reading

```bash
-nostdin                  # Don't read from stdin
```

**Impact:**
- Prevents ffmpeg from blocking on input
- Important for daemon/service usage

#### Error Handling

```bash
-xerror                   # Exit on any error
-hide_banner              # Suppress startup banner
```

---

## Recommended Configurations

### Profile 1: Low Latency (Live Sports)

Optimized for minimum delay. Best for live events where real-time matters.

```bash
ffmpeg \
  -nostdin \
  -fflags +nobuffer+fastseek+discardcorrupt \
  -flags low_delay \
  -analyzeduration 500000 \
  -probesize 500000 \
  -i {playlist_url} \
  -c copy \
  -f mpegts \
  -flush_packets 1 \
  -v repeat+level+warning \
  pipe:1
```

| Metric | Value |
|--------|-------|
| Startup time | ~1-2 seconds |
| Latency | ~2-4 seconds behind live |
| Stability | Medium |
| CPU usage | Very low |

---

### Profile 2: Balanced (Recommended Default)

Good balance of speed, latency, and reliability. Best for general viewing.

```bash
ffmpeg \
  -nostdin \
  -fflags +nobuffer+fastseek+discardcorrupt \
  -flags low_delay \
  -analyzeduration 1000000 \
  -probesize 1000000 \
  -reconnect 1 \
  -reconnect_at_eof 1 \
  -reconnect_streamed 1 \
  -reconnect_delay_max 5 \
  -i {playlist_url} \
  -c copy \
  -f mpegts \
  -mpegts_flags +resend_headers \
  -flush_packets 1 \
  -v repeat+level+warning \
  pipe:1
```

| Metric | Value |
|--------|-------|
| Startup time | ~2-3 seconds |
| Latency | ~4-6 seconds behind live |
| Stability | High |
| CPU usage | Very low |

---

### Profile 3: Maximum Stability (Weak Network)

Prioritizes reliability over speed. Best for unstable connections.

```bash
ffmpeg \
  -nostdin \
  -fflags +discardcorrupt+igndts \
  -reconnect 1 \
  -reconnect_at_eof 1 \
  -reconnect_streamed 1 \
  -reconnect_delay_max 10 \
  -rw_timeout 15000000 \
  -i {playlist_url} \
  -c copy \
  -f mpegts \
  -mpegts_flags +resend_headers \
  -max_muxing_queue_size 2048 \
  -v repeat+level+warning \
  pipe:1
```

| Metric | Value |
|--------|-------|
| Startup time | ~4-5 seconds |
| Latency | ~8-10 seconds behind live |
| Stability | Very high |
| CPU usage | Very low |

---

### Profile 4: Debug Mode

For troubleshooting stream issues.

```bash
ffmpeg \
  -nostdin \
  -report \
  -i {playlist_url} \
  -c copy \
  -f mpegts \
  -v repeat+level+debug \
  pipe:1
```

| Metric | Value |
|--------|-------|
| `-report` | Creates detailed log file |
| `-v debug` | Maximum logging verbosity |

---

## JavaScript Implementation

### Current Code (Device.js)

```javascript
const ffmpeg = spawn('ffmpeg', [
    '-i', channelJSON.playlist_url,
    '-c', 'copy',
    '-f', 'mpegts',
    '-v', `repeat+level+${FFMPEG_LOG_LEVEL}`,
    'pipe:1'
]);
```

### Recommended Update

```javascript
const ffmpegArgs = [
    // Prevent stdin reading
    '-nostdin',
    // Input optimizations
    '-fflags', '+nobuffer+fastseek+discardcorrupt',
    '-flags', 'low_delay',
    '-analyzeduration', '1000000',
    '-probesize', '1000000',
    // Reconnection handling
    '-reconnect', '1',
    '-reconnect_at_eof', '1',
    '-reconnect_streamed', '1',
    '-reconnect_delay_max', '5',
    // Input source
    '-i', channelJSON.playlist_url,
    // Stream copy (no transcoding)
    '-c', 'copy',
    // Output format
    '-f', 'mpegts',
    '-mpegts_flags', '+resend_headers',
    '-flush_packets', '1',
    // Logging
    '-v', `repeat+level+${FFMPEG_LOG_LEVEL}`,
    // Output to stdout
    'pipe:1'
];

const ffmpeg = spawn('ffmpeg', ffmpegArgs);
```

### Configurable Profiles

```javascript
const FFMPEG_PROFILES = {
    low_latency: [
        '-nostdin',
        '-fflags', '+nobuffer+fastseek+discardcorrupt',
        '-flags', 'low_delay',
        '-analyzeduration', '500000',
        '-probesize', '500000',
    ],
    balanced: [
        '-nostdin',
        '-fflags', '+nobuffer+fastseek+discardcorrupt',
        '-flags', 'low_delay',
        '-analyzeduration', '1000000',
        '-probesize', '1000000',
        '-reconnect', '1',
        '-reconnect_at_eof', '1',
        '-reconnect_streamed', '1',
        '-reconnect_delay_max', '5',
    ],
    stable: [
        '-nostdin',
        '-fflags', '+discardcorrupt+igndts',
        '-reconnect', '1',
        '-reconnect_at_eof', '1',
        '-reconnect_streamed', '1',
        '-reconnect_delay_max', '10',
        '-rw_timeout', '15000000',
    ]
};

function getFFmpegArgs(playlistUrl, profile = 'balanced') {
    const inputOpts = FFMPEG_PROFILES[profile] || FFMPEG_PROFILES.balanced;
    return [
        ...inputOpts,
        '-i', playlistUrl,
        '-c', 'copy',
        '-f', 'mpegts',
        '-mpegts_flags', '+resend_headers',
        '-flush_packets', '1',
        '-v', `repeat+level+${FFMPEG_LOG_LEVEL}`,
        'pipe:1'
    ];
}
```

---

## Troubleshooting

### Common Issues

#### Stream Starts Then Stops

**Cause:** HLS segment loading timeout

**Solution:**
```bash
-rw_timeout 10000000      # Increase timeout
-reconnect 1              # Enable reconnection
```

#### Choppy Playback

**Cause:** Input buffer underrun

**Solution:**
```bash
-thread_queue_size 512    # Larger input buffer
-max_muxing_queue_size 1024
```

#### Long Startup Time

**Cause:** Excessive stream analysis

**Solution:**
```bash
-analyzeduration 500000   # Reduce to 0.5 seconds
-probesize 500000         # Reduce probe size
```

#### Audio/Video Sync Issues

**Cause:** Discontinuity in source stream

**Solution:**
```bash
-fflags +genpts           # Regenerate presentation timestamps
-async 1                  # Audio sync (if re-encoding)
```

#### "Non-monotonous DTS" Warnings

**Cause:** Timestamp issues in source

**Solution:**
```bash
-fflags +igndts           # Ignore DTS (use PTS only)
-fflags +discardcorrupt   # Discard bad packets
```

---

## Performance Comparison

| Profile | Startup | Latency | Network Resilience | Best For |
|---------|---------|---------|-------------------|----------|
| Current | 3-5s | Medium | Low | Basic use |
| Low Latency | 1-2s | Low | Low | Sports, news |
| Balanced | 2-3s | Medium | High | Daily use |
| Stable | 4-5s | High | Very High | Weak WiFi |

---

## CPU/Memory Impact

All configurations use `-c copy` (stream copy), so:

| Resource | Usage | Notes |
|----------|-------|-------|
| CPU | ~1-3% | Demux/remux only |
| Memory | ~20-50 MB | Per ffmpeg instance |
| Network | = stream bitrate | Typically 5-15 Mbps |

Transcoding is NOT performed, so CPU usage remains minimal regardless of video resolution or codec.

---

## References

- [FFmpeg HLS Documentation](https://ffmpeg.org/ffmpeg-formats.html#hls-1)
- [FFmpeg MPEG-TS Muxer](https://ffmpeg.org/ffmpeg-formats.html#mpegts-1)
- [FFmpeg Protocols (Reconnection)](https://ffmpeg.org/ffmpeg-protocols.html)

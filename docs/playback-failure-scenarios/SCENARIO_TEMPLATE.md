# Playback Failure Scenarios & Log Accounting

This document captures detailed timelines, cross-component accounting, and root-cause analyses for live TV streaming failures observed during playback. These empirical incident reports serve as architectural reference cases to drive resiliency, stream stability, and watchdog design improvements in `tablo2hdhomerun`.

---

## 1. System Topology & Test Environment

| Component | Identifier / IP | Version / Details | Role |
| :--- | :--- | :--- | :--- |
| **Tablo Tuner** | `192.168.2.28:8887` / `:80` | Tablo 4th Gen (`SID_<DEVICE_ID>`) | Hardware tuner, ATSC demodulator, HLS packager |
| **tablo2hdhomerun** | `192.168.2.24:8080` | Scala 3.x / Pekko ([tablo2hdhomerun:<commit_hash>](file:///home/bdina/git/tablo2hdhomerun/src/main/scala/app/stream/ResilientHlsSource.scala)); stall timeout `<N>s`, idle grace `<N>s` | HDHomeRun API emulator, HLS demuxer/proxy |
| **Plex Media Server** | `192.168.2.24:32400` | PMS `v<version>` (Docker container) | Network tuner client, VAAPI hardware transcoder |
| **Plex Client** | `<client_ip>` | `<client_app_and_version>` on `<device>` | Downstream player |
| **RF Broadcast Transmitter** | `<transmitter_location>` (~<distance> miles <direction>) | `<station_callsign>` (<channel_name>, Physical UHF RF <rf_channel>) | OTA broadcast origin |
| **Weather Observation Station** | KMHT (Manchester-Boston Regional Airport, NH) | Surface Meteorological Station | Local weather & wind reference |

> [!NOTE]
> **Timezone Alignment**:
> - `tablo2hdhomerun` logs in **UTC**.
> - `Plex Media Server` logs and local weather observations are in **EDT/EST (UTC-4 / UTC-5)**.
> - In this document, all timeline entries list both timestamps for correlated analysis.

---

## 2. Executive Summary of Incident

On `<incident_date>`, a live streaming session on `<channel_name>` (`<channel_id>`) ran for **<duration>** (`<start_time_local>` – `<end_time_local>` / `<start_time_utc>` – `<end_time_utc>`), encountering `<summary_of_failure_modes>`.

### Key Session Metrics:
- **Total Observed Window**: `<total_elapsed_time>`
- **Hardware Tuner Leases**: `<lease_ids_and_retention_details>`
- **Downstream Streaming Sessions**: `<number_of_sessions_and_durations>`
- **Watchdog Auto-Retunes**: `<retune_count_and_triggers>`
- **Manual Human Interventions**: `<number_of_manual_re-tunes>`
- **Transcode Crashes**: `<count_of_fatal_transcode_exits>`
- **Decoder Error Count**: `<approximate_decoder_error_count_in_plex>`

### Environmental & Meteorological Summary
- Observations at **KMHT** recorded `<wind_speed_range>`, peak gusts of **`<peak_gust_mph>`**, and `<sky_conditions>`.
- `<Discussion of RF propagation impact: e.g., did gusts induce antenna deflection or dynamic multipath fading, or was the atmosphere calm, ruling out environmental triggers?>`

### Summary of Failure Progression:
1. **Initial Baseline**: `<Initial tune parameters, latency, early stability>`
2. **Environmental / Trigger Event**: `<Wind gust, timeout, watchdog trip, or initialization error>`
3. **Downstream Degradation**: `<Plex transcoder reaction, macroblock corruption, packet rejection>`
4. **Proxy Reaction**: `<Watchdog trip, silent corruption blind spot, or fast-attach reuse>`
5. **Session Termination**: `<Client disconnect, fatal crash, or idle-grace expiry>`

---

## 3. Incident Timeline & Log Accounting

### 3.1 Session Metadata
- **Channel**: `<Display Name>` (`<Channel ID>` / `<Callsign>`, Physical UHF RF `<Channel>`)
- **Hardware Lease ID**: `<lease_id>`
- **Tablo Session Token**: `<session_token>`
- **Plex Client**: `<Client app, platform, IP, user>`
- **Client & Transcode Breakdown**:
  - **Session 1**: Client `<client_id>` / Transcode `<transcode_key>` (`<start_time>` – `<end_time>`)
  - **Session 2**: Client `<client_id>` / Transcode `<transcode_key>` (`<start_time>` – `<end_time>`)

---

### 3.2 Environmental & Meteorological Context (KMHT Weather)

Observations recorded at Manchester-Boston Regional Airport (KMHT), situated in the reception corridor for Boston OTA broadcasts into Southern New Hampshire:

| Local Time (EDT/EST) | UTC Time | Sustained Wind | Wind Gusts | Direction | Sky & Weather Conditions | RF Propagation Impact |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **<HH:MM AM/PM>** | **<HH:MM>** | <X> mph | None / <Y> mph | <DIR> | <Sky> | <Baseline observation> |
| **<HH:MM AM/PM>** | **<HH:MM>** | <X> mph | **<Y> mph** | <DIR> | <Sky> | **<Trigger Event>**: <RF multipath/fade impact> |
| **<HH:MM AM/PM>** | **<HH:MM>** | <X> mph | None / <Y> mph | <DIR> | <Sky> | <Post-incident state> |

---

### 3.3 Correlated Chronological Timeline

| UTC Timestamp | Local Timestamp | Component | Event / Log Details | Significance |
| :--- | :--- | :--- | :--- | :--- |
| **HH:MM:SS.mmm** | **HH:MM:SS.mmm** | Plex PMS | `[Req#.../Grabber/...] Unable to find title...` | PMS initiates tune request for channel (`/auto/v...`). |
| **HH:MM:SS.mmm** | **HH:MM:SS.mmm** | Proxy | `[session] opening channelId=... clientId=...` | Proxy receives request and initiates channel session. |
| **HH:MM:SS.mmm** | **HH:MM:SS.mmm** | Proxy -> Tablo | `POST /guide/channels/.../watch?lh=1` | Watch API invoked on Tablo hardware. |
| **HH:MM:SS.mmm** | **HH:MM:SS.mmm** | Proxy | `[channel] tuned leaseId=...` | Tablo locks carrier and returns initial playlist. |
| **HH:MM:SS.mmm** | **HH:MM:SS.mmm** | Plex Transcoder | `[TPU] Preparing driver ivd for GPU Intel HD Graphics 530` | Transcode pipeline initializes. |
| **HH:MM:SS.mmm** | **HH:MM:SS.mmm** | Plex Transcoder | `ERROR [mpeg2video] ...` | Downstream packet decoding error. |
| **HH:MM:SS.mmm** | **HH:MM:SS.mmm** | Proxy | `WARN [stream:hls] ...` | Proxy watchdog trigger or stream recovery. |
| **HH:MM:SS.mmm** | **HH:MM:SS.mmm** | Plex Client / Proxy | `[session] idle-grace ...` | Client drops stream; proxy enters idle grace. |

---

## 4. Cross-Component Impact Matrix

| Component | Normal Operating State | Observed State During Incident | Consequence & Impact |
| :--- | :--- | :--- | :--- |
| **Tablo 4th Gen** (`192.168.2.28`) | Continuous demodulation and clean HLS segment generation. | `<Observed behavior: e.g. RF lock drop, trickle corruption, continuous retuning>` | `<Impact on device state and tuner resource allocation>` |
| **tablo2hdhomerun** (`192.168.2.24:8080`) | Reliable segment ingestion, watchdog monitoring, clean null padding. | `<Observed behavior: watchdog trip, blind spot, or fast-attach>` | `<Impact on stream continuity and downstream client pipe>` |
| **Plex Media Server** (`192.168.2.24:32400`) | Smooth VAAPI transcoding and DASH fragment publishing. | `<Observed behavior: macroblock errors, transcode runner death>` | `<Impact on transcode queue and container delivery>` |
| **Plex Client** (`<client_ip>`) | Steady 60fps playback without buffering. | `<Observed behavior: black screen, frozen frames, fatal error>` | `<Impact on user experience>` |

---

## 5. Diagnostic Log Artifacts & Error Accounting

### 5.1 Plex Transcoder Syntax Errors
```text
<Verbatim excerpt of representative FFmpeg / mpeg2video error lines>
```

### 5.2 Proxy Diagnostic Lines
```text
<Verbatim excerpt of representative tablo2hdhomerun log lines>
```

### 5.3 Proxy-Side Health Measurements in the Bad Window
State what the proxy itself observed (not only Plex symptoms) so hypotheses can be confirmed or ruled out:

| Measurement | Value in baseline | Value in bad window |
| :--- | :--- | :--- |
| Segment bytes vs. expected (`byteRange` length / short `206` reads) | `<value>` | `<value>` |
| Segment arrival interval (`EXTINF` vs. actual) | `<value>` | `<value>` |
| TEI packets / continuity-counter errors per PID | `<value>` | `<value>` |
| I-frames per segment | `<value>` | `<value>` |
| Longest zero-byte gap | `<value>` | `<value>` |

If the proxy did not log a measurement, record it as `not logged` and add a logging recommendation in section 7.

---

## 6. Root Cause Technical Deep Dive

### `<Primary Root Cause Title>`
`<Detailed technical explanation analyzing the code, timing, protocol interactions, or physical RF conditions responsible for the failure.>`

---

## 7. Architecture & Design Recommendations

### 1. `<Recommendation 1: Code / Resiliency Fix>`
- **Action**: `<Specific code change in ResilientHlsSource, SessionManager, or Watchdog>`
- **Expected Outcome**: `<How this eliminates the failure pattern>`

### 2. `<Recommendation 2: Operational / Diagnostic Enhancement>`
- **Action**: `<Diagnostic metric, TEI counter, or health log addition>`
- **Expected Outcome**: `<How this operationalizes observability for debug and optimization>`

# Playback Scenarios Guide

This directory maintains empirical incident reports and test benchmarks for live TV streaming scenarios observed during playback through `tablo2hdhomerun`, capturing both failure incidents and successful stability benchmarks. These documents serve as architectural reference cases to drive stream resiliency, watchdog design, error recovery mechanisms, and performance verification.

---

## 1. Why Meteorological & Environmental Context is Mandatory

`tablo2hdhomerun` bridges over-the-air (OTA) ATSC 1.0 broadcast radio frequencies to downstream IP clients (such as Plex Media Server). In 8VSB broadcast reception across fringe or suburban paths (~40–50 miles):
- **Wind Gusts & Foliage Agitation**: Moving tree canopies along the transmission line-of-sight and mechanical antenna mast deflection induce dynamic multipath interference and rapid carrier phase jitter.
- **Atmospheric Conditions**: Strong temperature inversions, precipitation fronts, or gust fronts create transient RF nulls and signal fades.
- **Disentangling Root Causes**: Without correlated weather data, it is impossible to distinguish between:
  1. *Internal proxy software flaws* (e.g., watchdog false-positives tripping on regular HLS segment cadence).
  2. *Upstream RF signal collapses* (e.g., demodulator losing lock due to wind gusts).
  3. *Downstream transcoding issues* (e.g., mid-GOP boundary start failures or client playback timeouts).

Every scenario document must establish whether environmental conditions were calm (ruling out RF interference) or adverse (identifying physical RF triggers).

---

## 2. Scenario Catalog & Index

The scenarios in this catalog are classified into three operational categories:
- **Success (Benchmark)**: Uninterrupted continuous playback, validated performance metrics, zero unexpected crashes/retunes, and verified audio/video synchronization across multi-hour runs.
- **Failure**: Service degradation, fatal transcode crashes, retune storms, audio/video loss, or viewer-impacting error dialogs. These are mapped to specific Failure Modes (A–G) and analyzed to drive architectural fixes.
- **Mixed / Resilience Validation**: Scenarios that stress-tested the system against challenging environmental or infrastructure conditions (e.g. wind gusts, Wi-Fi drops, downstream tmpfs disk limits), successfully demonstrating recovery mechanisms (e.g. idle-grace fast-attach, MPEG-2 slice clamping, FreezeFramePacer bridging) while uncovering edge cases.

| Scenario Document | Classification | Date & Window | Primary Channel | Environmental Trigger | Primary Outcome / Failure Mode | Status |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| [2026.10.07_0.scenario.md](file:///home/bdina/git/tablo2hdhomerun/docs/playback-scenarios/2026.10.07_0.scenario.md) | **Failure** | 2026-10-07 21:25–23:08 EDT | CBS 4.1 (RF 20) | Calm (6–12 mph S, no gusts) | Watchdog false-positives (`microStallGap = 1s` tripped on normal HLS pacing); 194 cold retunes. | Resolved (`187e437`) |
| [2026.10.08_0.scenario.md](file:///home/bdina/git/tablo2hdhomerun/docs/playback-scenarios/2026.10.08_0.scenario.md) | **Failure** | 2026-10-08 07:27–09:05 EDT | CBS 4.1 (RF 20) | Calm (5–10 mph SSW, no gusts) | Continuous 273 retune storm every 21s from watchdog misconfiguration; client abandonment. | Resolved (`187e437`) |
| [2026.10.08_1.scenario.md](file:///home/bdina/git/tablo2hdhomerun/docs/playback-scenarios/2026.10.08_1.scenario.md) | **Failure** | 2026-10-08 09:42–12:04 EDT | CBS 4.1 (RF 20) | Gusts to **29 mph** WSW/NNW | Wind gust front induced heavy RF packet corruption, compounding retunes to crash Plex transcoder. | Retune storm resolved (`187e437`); gust corruption open (see 2026.10.13_0) |
| [2026.10.09_0.scenario.md](file:///home/bdina/git/tablo2hdhomerun/docs/playback-scenarios/2026.10.09_0.scenario.md) | **Failure** | 2026-10-08 21:50–21:55 EDT | CBS 4.1 (RF 20) | Calm (5 mph S, no gusts) | Plex transcoder mid-GOP start quirk (`Invalid frame dimensions 0x0`) on iPad; resolved via `idle-grace`. | Resolved: client attach gated on I-frame + synthesized `00 00 01 B3` Sequence Header (`7ad93be`) |
| [2026.10.09_1.scenario.md](file:///home/bdina/git/tablo2hdhomerun/docs/playback-scenarios/2026.10.09_1.scenario.md) | **Failure** | 2026-10-09 14:50–15:32 EDT | CBS 4.1 (RF 20) | Peak gusts to **32 mph** SSW | Wind-induced ATSC lock loss; 27.5s recovery exceeded client tolerance, causing playback restart. | Resolved via control-plane session-presence probing (`dea1c71`) & 40ms `0x1FFF` keepalives (replaces `6598c07` immediate `304` escalation) |
| [2026.10.10_0.scenario.md](file:///home/bdina/git/tablo2hdhomerun/docs/playback-scenarios/2026.10.10_0.scenario.md) | **Failure** | 2026-10-09 23:19 – 10-10 10:06 EDT | CBS 4.1 (RF 20) & PBS 2.3 (RF 32) | Daytime breeze (8–13 mph NW) | Morning canopy breeze caused clustered dropouts on RF 20 while RF 32 ran flawlessly. | Resolved via control-plane session-presence probing (`dea1c71`) & 500ms fast polling riding out canopy flutter |
| [2026.10.13_0.scenario.md](file:///home/bdina/git/tablo2hdhomerun/docs/playback-scenarios/2026.10.13_0.scenario.md) | **Failure** | 2026-10-13 17:43–21:24 EDT | CBS 4.1 (RF 20) | Gusts to **29–32 mph** WNW | Corrupt ATSC bytes passed silently by zero-byte watchdog, causing Plex crash and fast-attach trap. | Resolved via slice-level sanitization, outbound CC rewriting & `FreezeFramePacer` |
| [2026.10.14_0.scenario.md](file:///home/bdina/git/tablo2hdhomerun/docs/playback-scenarios/2026.10.14_0.scenario.md) | **Mixed / Resilience Validation** | 2026-10-14 19:50–21:35 EDT | CBS 4.1 (RF 20) | Shift to N, **11.5 mph** sustained | Validated I-frame gating & capped dampening across 13 successful retunes (~6.5s); silent demodulator corruption ended 58m Roku session. | Resolved via slice row clamping (`<= maxSliceRow`) & locked sequence header geometry |
| [2026.10.15_0.scenario.md](file:///home/bdina/git/tablo2hdhomerun/docs/playback-scenarios/2026.10.15_0.scenario.md) | **Mixed / Resilience Validation** | 2026-10-15 19:53–21:44 EDT | CBS 4.1 (RF 20) | Calm (0 mph, no gusts, clear) | Validated 180s idle-grace hand-off (Apple TV attached in 28s) & multi-client grabber multiplexing; silent 5,541-error RF packet burst starved player buffer. | Resolved via `FreezeFramePacer` (100ms repeat P-frames & silent audio) feeding Plex `ssegment` grabber |
| [2026.10.16_0.scenario.md](file:///home/bdina/git/tablo2hdhomerun/docs/playback-scenarios/2026.10.16_0.scenario.md) | **Mixed / Benchmark** | 2026-10-16 08:11–15:59 EDT | CBS 4.1 (RF 20) | Calm (3–11 mph S, no gusts) | Benchmark 7h 47m continuous stream (44.75 GB); Plex 10 GB transcode tmpfs ramdisk overflow (`No space left on device`); validated 180s idle-grace fast-attach in 59s. | Validated benchmark stability (`5f8c22d`); Mode F operational |
| [2026.10.17_0.scenario.md](file:///home/bdina/git/tablo2hdhomerun/docs/playback-scenarios/2026.10.17_0.scenario.md) | **Mixed / Resilience Validation** | 2026-10-17 21:44–22:56 EDT | CBS 4.1 (RF 20) | Calm (0–8 mph NNW, no gusts) | Sustained 71m iPhone stream (6.78 GB); instantaneous I-frame lock (2 packets dropped in 0ms); late 45-error burst before clean disconnect and 180s idle-grace teardown. | Validated startup gating & lease hold (`9b9e68f`); Mode E precursor |
| [2026.10.18_0.scenario.md](file:///home/bdina/git/tablo2hdhomerun/docs/playback-scenarios/2026.10.18_0.scenario.md) | **Failure** | 2026-10-18 13:28–14:47 EDT | CBS 4.1 (RF 20) | Calm (0–4 mph VRB, no gusts) | Plex on Apple TV 4K failed repeatedly from 7,821 decode errors and three 8s Tablo hardware stalls; comparative control experiment using official Tablo client absorbed freezes, self-recovered, and maintained channel uptime. | Open: Downstream Plex pipeline fragility; proposed synthetic filler, TEI gating, and retune bridging |
| [2026.10.26_0.scenario.md](file:///home/bdina/git/tablo2hdhomerun/docs/playback-scenarios/2026.10.26_0.scenario.md) | **Failure** | 2026-10-26 17:50–18:10 EDT | CBS 4.1 (RF 20) | Calm (3.5 mph SSW, no gusts) | Upstream 160 KB segment trickle collapse froze video; fast-attach keyframe drop storm (8,001 pkts in 74ms) discarded all audio, causing total audio loss on Apple TV. | Open: Mode E trickle watchdog & Mode G audio preservation in `alignToIFrame` (`c57c238`) |
| [2026.10.27_0.scenario.md](file:///home/bdina/git/tablo2hdhomerun/docs/playback-scenarios/2026.10.27_0.scenario.md) | **Success (Benchmark)** | 2026-10-27 16:35–20:34 EDT | CBS 4.1 (RF 20) | Calm (0–7 mph NNW, no gusts) | Benchmark 4h continuous stream (22.8 GB); 2h 41m active Apple TV 4K playback in locked A/V sync; zero retunes; absorbed 6.5s delivery gap & 21 packet errors. | Validated All-Day TV architecture (`7db3318`); Failure Modes E, E2, & G resolved |
| [2026.10.28_0.scenario.md](file:///home/bdina/git/tablo2hdhomerun/docs/playback-scenarios/2026.10.28_0.scenario.md) | **Mixed / Comparative Analysis** | 2026-10-28 10:25–12:13 EDT | CBS 4.1 (RF 20) | Calm (3–8 mph VRB, no gusts) | Side-by-side test (Plex Web vs TabloTV iPad); Tablo Wi-Fi dropout; fringe RF error storm (7,557 decode errors) caused PMS A/V desync and crash; validated 180s idle-grace fast-attaches (0ms, 183ms, 2.6s) and 43m stable stream. | Validated idle-grace recovery & identified transcode A/V desync sensitivity (`5259855`) |
| [2026.11.08_0.scenario.md](file:///home/bdina/git/tablo2hdhomerun/docs/playback-scenarios/2026.11.08_0.scenario.md) | **Mixed / Multi-Channel Benchmark** | 2026-11-08 07:05–10:13 EST | CBS 4.1 (RF 20), NBC 15.1 (RF 32), ABC 9.1 (RF 9) | Gusts to **26 kt (30 mph)** WNW/NW | CBS demodulator lock failure (503) timed out PMS grabber at 25s; NBC segment delivery jitter starved Roku buffer into 121s hang; ABC 2.5h stream hit 10GB `/transcode` tmpfs low disk space warnings and upstream packager stalls. | Multi-channel benchmark (`d09cc2f`); validates MPEG-2 slice clamping (88 clamped slices), 180s idle-grace fast-attach, and `FreezeFramePacer` 46s bridge |
| [2026.11.11_0.scenario.md](file:///home/bdina/git/tablo2hdhomerun/docs/playback-scenarios/2026.11.11_0.scenario.md) | **Mixed / Direct Stream vs. Transcoder Validation** | 2026-11-11 10:29–13:15 EST | COZITV 15.2, CBS 4.1 (RF 20) | Fair (9–13 mph NW, no gusts) | Manual A/B test on Roku (`dev=3`): Direct Stream produced 7 consecutive rapid aborts (28s–358s), including a `74.8s` drop with `100%` signal/feed and zero upstream errors; switching back to PMS VAAPI transcoder immediately stabilized playback (`18m 35s` CBS 4.1 despite `76.8s` blackout + `49.9s` cold retune; `1h 43m 52s` COZITV 15.2 despite `-1986ms` skew & `43.5s` blackout). | Validates Direct Stream intolerance vs. PMS VAAPI transcoder isolation (`7a0e263` / `v0.0.17`); PMS hourly SQLite (`0.905x` vs `+17.0%`) & `stallPolls` pre-publish metric inflation analyzed |



> [!NOTE]
> **Code state when reading scenarios**: the stall watchdog threshold was 8s until `88067da`, 5s from `88067da` to `d3bdac8`, and restored to 8s with clamped check interval for stability. Every scenario states the commit it was captured against; the `Status` column records what has changed since.

---

## 3. Workflow for Documenting a Scenario

When capturing a new playback scenario (whether diagnosing a failure or validating a success benchmark), follow this systematic procedure:

### Step 1: File Naming Convention
Name the scenario file according to the incident date and daily sequence number:
```text
docs/playback-scenarios/YYYY.MM.DD_<index>.scenario.md
```
Example: `2026.10.14_0.scenario.md`

### Step 2: Log Collection
Extract logs covering the active window across all participating components:
1. **`tablo2hdhomerun`**:
   ```bash
   docker logs --timestamps tablo2hdhomerun > tablo2hdhomerun.log
   ```
2. **Plex Media Server**:
   Inspect `Plex Media Server.log` and `Plex Transcoder Statistics.*.log` inside `/config/Library/Application Support/Plex Media Server/Logs/`.
3. **Tablo Device Status**:
   Extract tune latencies, HTTP response codes, and session tokens from proxy log lines (`[channel] tuned`, `[stream:hls] emit`).

### Step 3: Meteorological Observation Retrieval
Retrieve hourly surface observations from the reference weather station covering the reception path (typically **KMHT** for Manchester-Boston Regional Airport, NH):
- Station: `KMHT` (METAR / Surface Meteorological Station)
- Key parameters: Sustained wind speed, peak wind gusts, wind direction, sky cover, precipitation.
- Sources: National Weather Service / NOAA METAR history or Weather Underground station history for KMHT.

### Step 4: Timezone Normalization
Always maintain correlated dual-timestamp columns:
- **UTC**: Native logging format for `tablo2hdhomerun`.
- **Local Time (EDT / EST)**: Native logging format for Plex Media Server and weather observations.

### Step 5: Draft the Scenario Using the Template
Copy [`SCENARIO_TEMPLATE.md`](file:///home/bdina/git/tablo2hdhomerun/docs/playback-scenarios/SCENARIO_TEMPLATE.md) and populate all standard sections, setting the classification (`Success (Benchmark)`, `Failure`, or `Mixed / Resilience Validation`).

---

## 4. Scenario Classification & Failure Mode Taxonomy

### 4.1 Scenario Classification Taxonomy
Every scenario report must classify the observed session into one of the following primary categories:

1. **Success (Benchmark)**:
   - **Definition**: The live stream met or exceeded all target playback stability objectives without viewer-impacting failure.
   - **Characteristics**: Continuous multi-hour playback without crashes, zero watchdog false-positive retunes, flawless audio/video synchronization, and transparent absorption of transient upstream delivery gaps (e.g., via native STC timestamps and slice-level sanitization).
   - **Primary Reference**: [`2026.10.27_0.scenario.md`](file:///home/bdina/git/tablo2hdhomerun/docs/playback-scenarios/2026.10.27_0.scenario.md) (4-hour continuous stream benchmark on Apple TV 4K).

2. **Failure**:
   - **Definition**: The playback session terminated prematurely, crashed downstream decoders/transcoders, or resulted in fatal error dialogs on client devices.
   - **Characteristics**: Fatal transcode runner exits, infinite retune loops, complete audio loss, unrecoverable buffer starvation, or upstream tuner lock destruction.
   - **Mapping**: Mapped directly to canonical Failure Modes A through G in Section 4.2.

3. **Mixed / Resilience Validation**:
   - **Definition**: The session encountered adverse environmental conditions (e.g., wind gusts, RF multipath) or upstream/downstream anomalies (e.g., Tablo Wi-Fi dropouts, transcode ramdisk exhaustion), successfully demonstrating proxy recovery and mitigation mechanisms while discovering edge-case boundaries.
   - **Characteristics**: Validates resilience components (such as 180s idle-grace fast-attach, MPEG-2 slice row clamping, or `FreezeFramePacer` bridges) while capturing operational findings for future hardening.

### 4.2 Failure Mode Classification Taxonomy (Failure & Mixed Scenarios)

Classify failures and stress incidents into one or more canonical failure modes:

| Failure Mode | Symptoms | Root Cause Mechanism | Indicator in Logs | Status |
| :--- | :--- | :--- | :--- | :--- |
| **A. Watchdog False-Positive** | Periodic retunes every 18–22s on clean video | Watchdog threshold shorter than normal HLS segment creation cadence | `too many micro-stalls (> 5 in 60s), retuning` | Resolved (`187e437`) |
| **B. Environmental RF Dropout** | Audio squeaks, pixelation, sudden stream stall | Mast sway / canopy movement during gusts > 20 mph breaking ATSC carrier lock | `[stream:hls] playlist stalled, probing session liveness` | Resolved (`dea1c71`): Control-plane session-presence probing (`POST /player/sessions/{token}/keepalive?lh=1` every 4th 500ms stall poll or on playlist `404`), `stallTimeout = Duration.Zero`, 40ms `0x1FFF` keepalive null packets, `10s` freeze-frame & silent-audio synthesis (`DefaultFreezeThreshold = 10.seconds`, `STREAM_FREEZE_THRESHOLD_SEC`), and B-frame-safe timestamp normalization riding out canopy flutter on the existing hardware tuner lock |
| **C. Transcoder Keyframe Misalignment** | Black screen on client startup; client disconnects in < 30s | First HLS segment starts mid-GOP without valid PAT/PMT/IDR sequence headers | `Invalid frame dimensions 0x0` in Plex transcoder log | Resolved: client attach & retunes gated on codec-matched I-frame (`videoStreamType`), synthesized `00 00 01 B3` Sequence Header injection, and decoupled audio `PUSI=1` in `MpegTsSync` |
| **D. Fast-Attach Corrupt Tuner Trap** | Client crashes, re-tunes immediately, but crashes again within seconds | Proxy fast-attaches new client to existing degraded hardware tuner lease during idle-grace | `fast-attached` followed by rapid transcode runner crash | Resolved (`d3bdac8`): I-frame gating and clean retunes eliminated crash loop |
| **E. Silent Corruption Blind Spot** | Video frozen or corrupted on client, but proxy watchdog never trips | Tablo continues trickling fragmentary/corrupt TS packets; zero-byte watchdog sees traffic | Corrupt macroblocks in Plex while proxy logs `real data resumed after X ms` | Resolved: Surgical slice-level dropping (`droppingVideoSlice`) on `TEI`, `CC` errors, and out-of-bounds slice start codes (`hasOutOfBoundsMpeg2Slice`, `code > maxSliceRow`) on both `PUSI=0` and `PUSI=1` packets, gating recovery on `PUSI=1` (`requireVideoPesStart`) after `CC` gaps or dropped `PUSI=1` headers; adaptation field flag safety in `stuffPacketPrefix` (`oldAfLen == 0` initialized to `0x00`); locked sequence header geometry; 2-packet `PAT`/`PMT` candidate confirmation; audio syncword gating (`0x0B77`/`0xFFF`); outbound CC continuous rewriting; and always-on B-frame-safe `PTS`/`DTS` outlier scrubbing in `MpegTsSync.cacheFlow` |
| **E2. Upstream Trickle Collapse** | Extreme stutter or slow-motion freeze; proxy logs show normal 200/206 fetches; watchdog does not trip | Tablo collapses under fringe RF, emitting microscopic `117–160 KB` segments every 2–3s with only `~0.1s` video (~97% payload drop) | `[mpegts] transport anomalies`, `[stream:hls] playlist stalled, probing session liveness` | Resolved (`dea1c71`): Control-plane session-presence probing (`checkSessionAlive`) keeps the `/watch` session open while the Tablo tuner rides out the RF fade and immediately executes `WatchSession.coldRetune` when the Tablo closes the session (`4xx`). Burst-safe `10s` freeze threshold (`DefaultFreezeThreshold = 10.seconds`) prevents false triggers on 2s HLS bursts while bridging prolonged stalls and media-clock deficits |
| **F. Downstream Transcoder Buffer Overflow** | Stream abruptly drops after prolonged streaming (7+ hours); client ejected to guide | Downstream transcode server maintains unpruned live ingest seekback buffer on memory-backed `tmpfs`; ramdisk reaches 100% capacity | `Error submitting a packet to the muxer: No space left on device` in Plex; proxy logs `outcome=downstream-closed` | Operational: documented `/transcode` `size=24G` tmpfs / NVMe scratch sizing in `USAGE.md`; proxy 180s idle-grace rescued lease |
| **G. Post-Restart / Fast-Attach Audio Starvation** | Video plays smoothly, but audio is completely silent; Plex transcode session omits audio track | `AlignToIFrameStage` dropped audio packets while awaiting video I-frame (`isAligned == false`); `BroadcastHub` inrush saturated FFmpeg's 1.5 MB `avformat_find_stream_info` probe buffer with video-only data, locking a permanent video-only transcode pipeline (`streamType="2"` omitted) | Plex transcode XML omits audio; proxy logs show dropped audio packets during pre-alignment | Resolved (`7ad93be`): `AlignToIFrameStage` decouples audio alignment, independently emitting each audio PID on first `PUSI=1` with valid syncword (`0x0B77`/`0xFFF`). Audio/null packets excluded from `droppedPackets` counter (`maxDroppedPackets = 8000`) |


---

## 5. Architectural Standards

All scenario documentation must adhere to repository guidelines:
- Reference code symbols with exact clickable file links and line numbers (e.g., [`ResilientHlsSource.scala`](file:///home/bdina/git/tablo2hdhomerun/src/main/scala/app/stream/ResilientHlsSource.scala)).
- When referencing commits, use short 7-character commit hashes (e.g., `29f9c76`).
- Maintain objective empirical accounting with verbatim log lines and timestamps.
- Formulate concrete recommendations and testable action items for the codebase.

---

## 6. Fringe-Signal Resilience Architecture: Resolution of Scenarios 2026.10.09_1 – 2026.10.16_0

The empirical findings from scenarios `2026.10.09_1` through `2026.10.16_0` exposed critical failure modes across both the upstream tuner management layer and the downstream stream sanitization pipeline:

1. **Premature Tuner Lock Destruction (`2026.10.09_1`, `2026.10.10_0`)**:
   - *Problem*: In [`6598c07`](https://github.com/bdina/tablo2hdhomerun/commit/6598c07), soft recovery escalated immediately on `304 Not Modified` / frozen playlists to a Tier 2 cold retune (`DELETE` + `POST /watch`), tearing down the physical RF demodulator lock during transient canopy flutter or wind gusts. Furthermore, in [`c57c238`](https://github.com/bdina/tablo2hdhomerun/commit/c57c238), a strict 16s clean-keyframe watchdog triggered cold retunes even while audio and partial video packets were successfully arriving.
   - *Resolution* ([`dea1c71`](https://github.com/bdina/tablo2hdhomerun/commit/dea1c71)): Arbitrary byte-stall timeouts are disabled by default (`stallTimeout = Duration.Zero`) and replaced by control-plane session-presence probing (`checkSessionAlive`). When segments stop advancing, [`HlsPlaylistPoller.scala`](file:///home/bdina/git/tablo2hdhomerun/src/main/scala/app/stream/HlsPlaylistPoller.scala) drops to a rapid `500ms` polling cadence and probes `POST /player/sessions/{token}/keepalive?lh=1` every 4th consecutive stall poll (`~2s`, `sessionProbeIntervalPolls = 4`) or immediately on playlist `404`. While the Tablo reports `200 OK`, distorted or fading reception rides out on the existing hardware tuner lock without triggering tuner thrashing; when the Tablo closes the session (`4xx` → `ClosedByTablo`) or transient probe errors exceed `pollFailuresMax`, `WatchSession.coldRetune` immediately requests a fresh `/watch` session.

2. **Downstream Decoder Poison Pills & Demuxer Discontinuities (`2026.10.13_0`, `2026.10.14_0`)**:
   - *Problem*: In [`c57c238`](https://github.com/bdina/tablo2hdhomerun/commit/c57c238), any `TEI` bit or unexpected CC jump triggered whole-GOP gating (`corruptVideoGop`), discarding entire seconds of video until the next keyframe. When marginal packets slipped through without `TEI`, bit-flipped slice row coordinates (`> maxSliceRow`, e.g. row 95 on a 1080i 68-row picture) caused FFmpeg and hardware VAAPI decoders to crash with `slice below image` fatal asserts (`2026.10.14_0`). Dropping corrupted TS packets also created CC gaps that downstream demuxers flagged as unrecoverable errors.
   - *Resolution*: [`MpegTsSync.scala`](file:///home/bdina/git/tablo2hdhomerun/src/main/scala/app/stream/MpegTsSync.scala) replaces whole-GOP gating with surgical slice-level dropping (`droppingVideoSlice`) on `TEI`, `CC` errors, and unflagged out-of-bounds MPEG-2 slice start codes (`hasOutOfBoundsMpeg2Slice`, `code > maxSliceRow`). Corrupt packets are replaced with `0x1FFF` null packets or raw `pcrOnlyPacket` (preserving adaptation-field `PCR` on `TEI=0`) rather than in-place start-code zeroing (which would merge corrupt bytes into the preceding slice). In [`FreezeFramePacer.scala`](file:///home/bdina/git/tablo2hdhomerun/src/main/scala/app/stream/FreezeFramePacer.scala), `stuffPacketPrefix` adaptation field flag safety ensures that when expanding a 1-byte adaptation field (`afc == 3, oldAfLen == 0`), it initializes the new adaptation flags byte to `0x00` so bogus `discontinuity_indicator` or `PCR` flags are never set. Slice row indices are clamped to `<= maxSliceRow` to eliminate VAAPI crashes, and the 12-byte MPEG-2 Sequence Header (`00 00 01 B3`) geometry is locked once confirmed and cached in `MpegTsSync.CachedHeaders.lockedSeqHeader`. When `AlignToIFrameStage` aligns on a GOP header without an in-band Sequence Header or reaches forced alignment, it injects `FreezeFramePacer.synthesizeSequenceHeaderPacket` (`00 00 01 B3` Sequence Header TS packet) so FFmpeg always initializes valid frame dimensions instead of logging `[mpeg2video] Invalid frame dimensions 0x0` ([`7ad93be`](https://github.com/bdina/tablo2hdhomerun/commit/7ad93be)). Corrupted audio PIDs are gated until a `PUSI=1` packet with a valid `0x0B77` (AC-3) or `0xFFF` (ADTS AAC) syncword arrives, and outbound 4-bit Continuity Counters (`CC`) are rewritten continuously per PID.

3. **Downstream Transcoder & Grabber Starvation (`2026.10.13_0`, `2026.10.15_0`)**:
   - *Problem*: During upstream signal pauses or retunes, streaming bare null packets (PID `0x1FFF`) kept the HTTP TCP socket alive but starved Plex's `-codec copy -f ssegment` ingest grabber. Because null packets do not advance video or audio container tracks, Plex's segmented muxer timed out after 5–10 seconds of freeze, crashing the transcode session.
   - *Resolution*: Burst-safe HLS segment handling ensures synthetic freeze frames are never falsely injected during normal network bursts: because HLS segments (~2.0s of media) are fetched in ~50ms network bursts followed by 1.5s–2.4s polling intervals, `DefaultFreezeThreshold` defaults to `10.seconds` (`STREAM_FREEZE_THRESHOLD_SEC=10`) so leading audio/video packets of normal subsequent segments are never gated. During inter-segment polling intervals and brief RF pauses (`< 10s`), [`ResilientHlsSource.scala`](file:///home/bdina/git/tablo2hdhomerun/src/main/scala/app/stream/ResilientHlsSource.scala) emits standard `0x1FFF` null packets every 40ms while client decoders naturally hold the last decoded frame. When an upstream stall or media-clock deficit reaches `freezeThreshold` (`>= 10s`), [`FreezeFramePacer.scala`](file:///home/bdina/git/tablo2hdhomerun/src/main/scala/app/stream/FreezeFramePacer.scala) synthesizes standards-compliant transport stream packets every `100ms`: valid `PAT`/`PMT`, MPEG-2 repeat/skip P-frames (or H.264 keepalive units) on `videoPid`, and silent AC-3 (`0x0B77` with valid CRC-16) or ADTS AAC frames on `audioPids`. Activation is strictly video-anchored in `CacheStage` (`lastLiveDataNanos` updates only on emitted live video `!freezeActive && !droppingVideoSlice`), keeping downstream player buffers full until live keyframes resume (`hasKeyframeHeader`).

4. **Timeline Drift & Outlier Timestamp Glitches (`2026.10.15_0`, `2026.10.16_0`)**:
   - *Problem*: OTA bit errors occasionally corrupted PES presentation timestamps, producing wild forward or backward jumps (> 2s) that caused Plex transcoders to choke on non-monotonic DTS/PTS. Synthetic freeze frames and `/watch` retunes also introduced timeline resets. Furthermore, artificial timestamp rebasing on negative diffs scrambled B-frame presentation order.
   - *Resolution*: B-Frame-Safe Native STC Timestamp Preservation & Always-On Monotonic Timeline Normalizer in [`MpegTsSync.scala`](file:///home/bdina/git/tablo2hdhomerun/src/main/scala/app/stream/MpegTsSync.scala): `MpegTsSync.cacheFlow` preserves Tablo's native hardware System Time Clock (STC) `PTS`, `DTS`, and `PCR` timestamps untouched on clean streams (`ptsDeltaTicks == 0L`). Because MPEG-2 and H.264 broadcasts transmit B-frames in decode (`DTS`) order where B-frame `PTS` values are naturally lower than the preceding I/P anchor frame (`diffFromOut < 0` within `RebaseThresholdTicks` = `0.5s`), allowing negative decode-order `PTS` diffs within `RebaseThresholdTicks` prevents B-frame presentation order scrambling and prevents artificial `ptsDeltaTicks` clock acceleration across video, audio, and PCR tracks. Meanwhile, `normalizePacketTimestamps` is always active in `CacheStage`, scrubbing single-packet PES timestamp bit-flips (`> 2s` jumps) in-place and tracking an unbroken monotonic timeline offset (`ptsDeltaTicks`) across synthetic freeze frames, session rollovers, and retunes.

5. **Fast-Attach Audio Track Starvation & Probe Dropouts (Failure Mode G)**:
   - *Problem*: In [`c57c238`](https://github.com/bdina/tablo2hdhomerun/commit/c57c238), `AlignToIFrameStage` dropped all packets on `knownAudioPids` while waiting for a video keyframe (`isAligned == false`). On client fast-attach during `idle-grace` (`BroadcastHub` 1024-packet inrush) or after retune, FFmpeg's `avformat_find_stream_info` probe buffer (1.5 MB) was saturated entirely by leading video P/B packets before a keyframe arrived. Seeing zero audio packets, FFmpeg permanently locked a video-only pipeline (`streamType="2"` omitted), leaving playback completely silent.
   - *Resolution* ([`7ad93be`](https://github.com/bdina/tablo2hdhomerun/commit/7ad93be)): `AlignToIFrameStage` decouples audio alignment from video keyframe alignment. Each audio PID aligns independently on its first `PUSI = 1` PES packet (with valid AC-3 `0x0B77` or ADTS `0xFFF` syncword when streamType is known) and begins emitting immediately. Null (`0x1FFF`) and audio packets are excluded from the `droppedPackets` counter (`maxDroppedPackets = 8000`), ensuring only dropped video packets count toward forced alignment and preventing audio probe starvation.

6. **Upstream Demodulator Trickle Collapse & Media-Clock Deficit (Failure Mode E2)**:
   - *Problem*: Marginal ATSC signal degradation caused the Tablo transcoder to emit tiny 117–160 KB segment files every 2–3 seconds containing only ~0.1s of video (a ~97% payload collapse). The zero-byte stall watchdog never tripped because bytes kept trickling, starving downstream Plex transcoders into extreme stutter or freeze.
   - *Resolution* ([`7ad93be`](https://github.com/bdina/tablo2hdhomerun/commit/7ad93be), [`dea1c71`](https://github.com/bdina/tablo2hdhomerun/commit/dea1c71)): Under control-plane session-presence probing (`checkSessionAlive`), the `/watch` session stays open while the Tablo tuner rides out the RF fade and immediately executes `WatchSession.coldRetune` when the Tablo closes the session (`4xx`). Burst-safe handling with native STC timestamp preservation avoids false `FreezeFramePacer` triggers on 2s HLS bursts. Meanwhile, when active pacing is enabled, `CacheStage` anchors a real-time media clock (`mediaClockAnchorPts`/`mediaClockAnchorNanos`), detecting when `lastOutputPts` lags wall clock by `>= freezeThreshold` and synthesizing up to 6 catch-up freeze frames per 100ms tick while rebasing subsequent live video timestamps seamlessly.

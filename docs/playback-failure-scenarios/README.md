# Playback Failure Scenarios Guide

This directory maintains empirical incident reports and root-cause analyses for live TV streaming failures observed during playback through `tablo2hdhomerun`. These documents serve as architectural reference cases to drive stream resiliency, watchdog design, and error recovery mechanisms.

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

| Scenario Document | Date & Window | Primary Channel | Environmental Trigger | Primary Failure Mode | Status |
| :--- | :--- | :--- | :--- | :--- | :--- |
| [2026.10.07_0.scenario.md](file:///home/bdina/git/tablo2hdhomerun/docs/playback-failure-scenarios/2026.10.07_0.scenario.md) | 2026-10-07 21:25–23:08 EDT | CBS 4.1 (RF 20) | Calm (6–12 mph S, no gusts) | Watchdog false-positives (`microStallGap = 1s` tripped on normal HLS pacing); 194 cold retunes. | Resolved (`187e437`) |
| [2026.10.08_0.scenario.md](file:///home/bdina/git/tablo2hdhomerun/docs/playback-failure-scenarios/2026.10.08_0.scenario.md) | 2026-10-08 07:27–09:05 EDT | CBS 4.1 (RF 20) | Calm (5–10 mph SSW, no gusts) | Continuous 273 retune storm every 21s from watchdog misconfiguration; client abandonment. | Resolved (`187e437`) |
| [2026.10.08_1.scenario.md](file:///home/bdina/git/tablo2hdhomerun/docs/playback-failure-scenarios/2026.10.08_1.scenario.md) | 2026-10-08 09:42–12:04 EDT | CBS 4.1 (RF 20) | Gusts to **29 mph** WSW/NNW | Wind gust front induced heavy RF packet corruption, compounding retunes to crash Plex transcoder. | Retune storm resolved (`187e437`); gust corruption open (see 2026.10.13_0) |
| [2026.10.09_0.scenario.md](file:///home/bdina/git/tablo2hdhomerun/docs/playback-failure-scenarios/2026.10.09_0.scenario.md) | 2026-10-08 21:50–21:55 EDT | CBS 4.1 (RF 20) | Calm (5 mph S, no gusts) | Plex transcoder mid-GOP start quirk (`Invalid frame dimensions 0x0`) on iPad; resolved via `idle-grace`. | Open: I-frame alignment applies to retunes only, not client attach |
| [2026.10.09_1.scenario.md](file:///home/bdina/git/tablo2hdhomerun/docs/playback-failure-scenarios/2026.10.09_1.scenario.md) | 2026-10-09 14:50–15:32 EDT | CBS 4.1 (RF 20) | Peak gusts to **32 mph** SSW | Wind-induced ATSC lock loss; 27.5s recovery exceeded client tolerance, causing playback restart. | Resolved via 45s session retention & `FreezeFramePacer` (replaces `6598c07` immediate `304` escalation) |
| [2026.10.10_0.scenario.md](file:///home/bdina/git/tablo2hdhomerun/docs/playback-failure-scenarios/2026.10.10_0.scenario.md) | 2026-10-09 23:19 – 10-10 10:06 EDT | CBS 4.1 (RF 20) & PBS 2.3 (RF 32) | Daytime breeze (8–13 mph NW) | Morning canopy breeze caused clustered dropouts on RF 20 while RF 32 ran flawlessly. | Resolved via 45s session retention & 500ms fast polling riding out canopy flutter |
| [2026.10.13_0.scenario.md](file:///home/bdina/git/tablo2hdhomerun/docs/playback-failure-scenarios/2026.10.13_0.scenario.md) | 2026-10-13 17:43–21:24 EDT | CBS 4.1 (RF 20) | Gusts to **29–32 mph** WNW | Corrupt ATSC bytes passed silently by zero-byte watchdog, causing Plex crash and fast-attach trap. | Resolved via slice-level sanitization, outbound CC rewriting & `FreezeFramePacer` |
| [2026.10.14_0.scenario.md](file:///home/bdina/git/tablo2hdhomerun/docs/playback-failure-scenarios/2026.10.14_0.scenario.md) | 2026-10-14 19:50–21:35 EDT | CBS 4.1 (RF 20) | Shift to N, **11.5 mph** sustained | Validated I-frame gating & capped dampening across 13 successful retunes (~6.5s); silent demodulator corruption ended 58m Roku session. | Resolved via slice row clamping (`<= maxSliceRow`) & locked sequence header geometry |
| [2026.10.15_0.scenario.md](file:///home/bdina/git/tablo2hdhomerun/docs/playback-failure-scenarios/2026.10.15_0.scenario.md) | 2026-10-15 19:53–21:44 EDT | CBS 4.1 (RF 20) | Calm (0 mph, no gusts, clear) | Validated 180s idle-grace hand-off (Apple TV attached in 28s) & multi-client grabber multiplexing; silent 5,541-error RF packet burst starved player buffer. | Resolved via `FreezeFramePacer` (100ms repeat P-frames & silent audio) feeding Plex `ssegment` grabber |
| [2026.10.16_0.scenario.md](file:///home/bdina/git/tablo2hdhomerun/docs/playback-failure-scenarios/2026.10.16_0.scenario.md) | 2026-10-16 08:11–15:59 EDT | CBS 4.1 (RF 20) | Calm (3–11 mph S, no gusts) | Benchmark 7h 47m continuous stream (44.75 GB); Plex 10 GB transcode tmpfs ramdisk overflow (`No space left on device`); validated 180s idle-grace fast-attach in 59s. | Validated benchmark stability (`5f8c22d`); Mode F operational |



> [!NOTE]
> **Code state when reading scenarios**: the stall watchdog threshold was 8s until `88067da`, 5s from `88067da` to `d3bdac8`, and restored to 8s with clamped check interval for stability. Every scenario states the commit it was captured against; the `Status` column records what has changed since.

---

## 3. Workflow for Documenting a Scenario

When capturing a new playback failure, follow this systematic procedure:

### Step 1: File Naming Convention
Name the scenario file according to the incident date and daily sequence number:
```text
docs/playback-failure-scenarios/YYYY.MM.DD_<index>.scenario.md
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
Copy [`SCENARIO_TEMPLATE.md`](file:///home/bdina/git/tablo2hdhomerun/docs/playback-failure-scenarios/SCENARIO_TEMPLATE.md) and populate all standard sections.

---

## 4. Failure Mode Classification Taxonomy

Classify the incident into one or more canonical failure modes:

| Failure Mode | Symptoms | Root Cause Mechanism | Indicator in Logs | Status |
| :--- | :--- | :--- | :--- | :--- |
| **A. Watchdog False-Positive** | Periodic retunes every 18–22s on clean video | Watchdog threshold shorter than normal HLS segment creation cadence | `too many micro-stalls (> 5 in 60s), retuning` | Resolved (`187e437`) |
| **B. Environmental RF Dropout** | Audio squeaks, pixelation, sudden stream stall | Mast sway / canopy movement during gusts > 20 mph breaking ATSC carrier lock | `no new data for 45s, retuning` (8s before `5f8c22d`) | Resolved via 45s `/watch` session retention (`failOnStallPoll = false`, `softRecoveryThresholdSec = 45s`, `softRecoveryTimeoutSec = 15s`), 500ms fast polling, burst-safe HLS segment pacing with 40ms `0x1FFF` keepalive null packets during inter-segment intervals (`DefaultFreezeThreshold = Duration.Zero`), and native STC timestamp preservation riding out canopy flutter |
| **C. Transcoder Keyframe Misalignment** | Black screen on client startup; client disconnects in < 30s | First HLS segment starts mid-GOP without valid PAT/PMT/IDR sequence headers | `Invalid frame dimensions 0x0` in Plex transcoder log | Resolved: client attach & retunes gated on codec-matched I-frame (`videoStreamType`) and audio `PUSI=1` in `MpegTsSync` |
| **D. Fast-Attach Corrupt Tuner Trap** | Client crashes, re-tunes immediately, but crashes again within seconds | Proxy fast-attaches new client to existing degraded hardware tuner lease during idle-grace | `fast-attached` followed by rapid transcode runner crash | Resolved (`d3bdac8`): I-frame gating and clean retunes eliminated crash loop |
| **E. Silent Corruption Blind Spot** | Video frozen or corrupted on client, but proxy watchdog never trips | Tablo continues trickling fragmentary/corrupt TS packets; zero-byte watchdog sees traffic | Corrupt macroblocks in Plex while proxy logs `real data resumed after X ms` | Resolved: Surgical slice-level dropping (`droppingVideoSlice`) on `TEI`, `CC` errors, and out-of-bounds slice start codes (`hasOutOfBoundsMpeg2Slice`, `code > maxSliceRow`) replaced with `0x1FFF` null packets or raw `pcrOnlyPacket` (avoiding in-place zeroing corrupt merges); adaptation field flag safety in `stuffPacketPrefix` (`oldAfLen == 0` initialized to `0x00`); locked sequence header geometry; audio syncword gating (`0x0B77`/`0xFFF`); outbound CC continuous rewriting; and native STC timestamp preservation in `MpegTsSync.cacheFlow` (avoiding B-frame `PTS` reordering corruption and artificial `ptsDeltaTicks` clock acceleration; replacing whole-GOP gating from `c57c238`) |
| **E2. Upstream Trickle Collapse** | Extreme stutter or slow-motion freeze; proxy logs show normal 200/206 fetches; watchdog does not trip | Tablo collapses under fringe RF, emitting microscopic `117–160 KB` segments every 2–3s with only `~0.1s` video (~97% payload drop); sequence advances falsely resetting stall watchdog | `[stream:hls] trickle segment detected (X KB/s < 15% baseline)`, Plex buffer underruns | Resolved (`7ad93be`): `HlsSegmentFetch.TrickleState` tracks EMA throughput (`alpha = 0.30`, `>= 200 KB/s` baseline); segments `< 15%` baseline suppress `onSeqAdvanced` (preventing `WatchSession.FlapState` from falsely resetting stall tracking) and trigger `SegmentTrickleCollapse` after 5 consecutive occurrences to escalate to 45s `/watch` session recovery. Burst-safe handling with native STC timestamp preservation prevents false `FreezeFramePacer` triggers on 2s HLS bursts |
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
   - *Resolution*: The proxy holds the existing Tablo `/watch` session open for up to `45s` (`STREAM_STALL_TIMEOUT_SEC = 45`, `WatchSession.decideRecovery` with `softRecoveryThresholdSec = 45`, `softRecoveryTimeoutSec = 15`, `failOnStallPoll = false`). When segments stop advancing, [`HlsPlaylistPoller.scala`](file:///home/bdina/git/tablo2hdhomerun/src/main/scala/app/stream/HlsPlaylistPoller.scala) drops to a rapid `500ms` polling cadence, and the 16s keyframe retune failure is eliminated. Distorted or fading reception rides out on the existing hardware tuner lock without triggering tuner thrashing.

2. **Downstream Decoder Poison Pills & Demuxer Discontinuities (`2026.10.13_0`, `2026.10.14_0`)**:
   - *Problem*: In [`c57c238`](https://github.com/bdina/tablo2hdhomerun/commit/c57c238), any `TEI` bit or unexpected CC jump triggered whole-GOP gating (`corruptVideoGop`), discarding entire seconds of video until the next keyframe. When marginal packets slipped through without `TEI`, bit-flipped slice row coordinates (`> maxSliceRow`, e.g. row 95 on a 1080i 68-row picture) caused FFmpeg and hardware VAAPI decoders to crash with `slice below image` fatal asserts (`2026.10.14_0`). Dropping corrupted TS packets also created CC gaps that downstream demuxers flagged as unrecoverable errors.
   - *Resolution*: [`MpegTsSync.scala`](file:///home/bdina/git/tablo2hdhomerun/src/main/scala/app/stream/MpegTsSync.scala) replaces whole-GOP gating with surgical slice-level dropping (`droppingVideoSlice`) on `TEI`, `CC` errors, and unflagged out-of-bounds MPEG-2 slice start codes (`hasOutOfBoundsMpeg2Slice`, `code > maxSliceRow`). Corrupt packets are replaced with `0x1FFF` null packets or raw `pcrOnlyPacket` (preserving adaptation-field `PCR` on `TEI=0`) rather than in-place start-code zeroing (which would merge corrupt bytes into the preceding slice). In [`FreezeFramePacer.scala`](file:///home/bdina/git/tablo2hdhomerun/src/main/scala/app/stream/FreezeFramePacer.scala), `stuffPacketPrefix` adaptation field flag safety ensures that when expanding a 1-byte adaptation field (`afc == 3, oldAfLen == 0`), it initializes the new adaptation flags byte to `0x00` so bogus `discontinuity_indicator` or `PCR` flags are never set. Slice row indices are clamped to `<= maxSliceRow` to eliminate VAAPI crashes, and the 12-byte MPEG-2 Sequence Header (`00 00 01 B3`) geometry is locked once confirmed and cached in `MpegTsSync.CachedHeaders.lockedSeqHeader`. When `AlignToIFrameStage` aligns on a GOP header without an in-band Sequence Header or reaches forced alignment, it injects `FreezeFramePacer.synthesizeSequenceHeaderPacket` (`00 00 01 B3` Sequence Header TS packet) so FFmpeg always initializes valid frame dimensions instead of logging `[mpeg2video] Invalid frame dimensions 0x0` ([`7ad93be`](https://github.com/bdina/tablo2hdhomerun/commit/7ad93be)). Corrupted audio PIDs are gated until a `PUSI=1` packet with a valid `0x0B77` (AC-3) or `0xFFF` (ADTS AAC) syncword arrives, and outbound 4-bit Continuity Counters (`CC`) are rewritten continuously per PID.

3. **Downstream Transcoder & Grabber Starvation (`2026.10.13_0`, `2026.10.15_0`)**:
   - *Problem*: During upstream signal pauses or retunes, streaming bare null packets (PID `0x1FFF`) kept the HTTP TCP socket alive but starved Plex's `-codec copy -f ssegment` ingest grabber. Because null packets do not advance video or audio container tracks, Plex's segmented muxer timed out after 5–10 seconds of freeze, crashing the transcode session.
   - *Resolution*: Burst-safe HLS segment handling ensures synthetic freeze frames are never falsely injected during normal network bursts: because HLS segments (~2.0s of media) are fetched in ~50ms network bursts followed by 1.5s–2.0s polling intervals, synthetic freeze-frame injection is disabled by default (`DefaultFreezeThreshold = Duration.Zero`) so leading audio/video packets of subsequent segments are never gated. During inter-segment polling intervals and brief RF pauses, [`ResilientHlsSource.scala`](file:///home/bdina/git/tablo2hdhomerun/src/main/scala/app/stream/ResilientHlsSource.scala) emits standard `0x1FFF` null packets every 40ms while client decoders naturally hold the last decoded frame. When active freeze-frame pacing is explicitly enabled (`freezeThreshold > Duration.Zero`), [`FreezeFramePacer.scala`](file:///home/bdina/git/tablo2hdhomerun/src/main/scala/app/stream/FreezeFramePacer.scala) synthesizes standards-compliant transport stream packets every `100ms`: valid `PAT`/`PMT`, MPEG-2 repeat/skip P-frames (or H.264 keepalive units) on `videoPid`, and silent AC-3 (`0x0B77` with valid CRC-16) or ADTS AAC frames on `audioPids`. Activation is strictly video-anchored in `CacheStage` (`lastLiveDataNanos` updates only on emitted live video `!freezeActive && !droppingVideoSlice`), keeping downstream player buffers full until live keyframes resume (`hasKeyframeHeader`).

4. **Timeline Drift & Outlier Timestamp Glitches (`2026.10.15_0`, `2026.10.16_0`)**:
   - *Problem*: OTA bit errors occasionally corrupted PES presentation timestamps, producing wild forward or backward jumps (> 2s) that caused Plex transcoders to choke on non-monotonic DTS/PTS. Synthetic freeze frames and `/watch` retunes also introduced timeline resets. Furthermore, artificial timestamp rebasing on negative diffs scrambled B-frame presentation order.
   - *Resolution*: Native Hardware STC Timestamp Preservation (B-Frame Safe) in [`MpegTsSync.scala`](file:///home/bdina/git/tablo2hdhomerun/src/main/scala/app/stream/MpegTsSync.scala): By default (`DefaultFreezeThreshold = Duration.Zero`), `MpegTsSync.cacheFlow` preserves Tablo's native hardware System Time Clock (STC) `PTS`, `DTS`, and `PCR` timestamps untouched. Because MPEG-2 and H.264 broadcasts transmit B-frames in decode (`DTS`) order where B-frame `PTS` values are naturally lower than the preceding I/P anchor frame (`diffFromOut < 0` within `RebaseThresholdTicks`), preserving native timestamps prevents B-frame presentation order scrambling and prevents artificial `ptsDeltaTicks` clock acceleration across video, audio, and PCR tracks. When active timestamp normalization is enabled, the continuous monotonic timeline normalizer inside `MpegTsSync.scala` filters single-packet PES timestamp bit-flips and tracks an unbroken monotonic timeline offset (`ptsDeltaTicks`) across synthetic freeze frames, session rollovers, and retunes.

5. **Fast-Attach Audio Track Starvation & Probe Dropouts (Failure Mode G)**:
   - *Problem*: In [`c57c238`](https://github.com/bdina/tablo2hdhomerun/commit/c57c238), `AlignToIFrameStage` dropped all packets on `knownAudioPids` while waiting for a video keyframe (`isAligned == false`). On client fast-attach during `idle-grace` (`BroadcastHub` 1024-packet inrush) or after retune, FFmpeg's `avformat_find_stream_info` probe buffer (1.5 MB) was saturated entirely by leading video P/B packets before a keyframe arrived. Seeing zero audio packets, FFmpeg permanently locked a video-only pipeline (`streamType="2"` omitted), leaving playback completely silent.
   - *Resolution* ([`7ad93be`](https://github.com/bdina/tablo2hdhomerun/commit/7ad93be)): `AlignToIFrameStage` decouples audio alignment from video keyframe alignment. Each audio PID aligns independently on its first `PUSI = 1` PES packet (with valid AC-3 `0x0B77` or ADTS `0xFFF` syncword when streamType is known) and begins emitting immediately. Null (`0x1FFF`) and audio packets are excluded from the `droppedPackets` counter (`maxDroppedPackets = 8000`), ensuring only dropped video packets count toward forced alignment and preventing audio probe starvation.

6. **Upstream Demodulator Trickle Collapse & Media-Clock Deficit (Failure Mode E2)**:
   - *Problem*: Marginal ATSC signal degradation caused the Tablo transcoder to emit tiny 117–160 KB segment files every 2–3 seconds containing only ~0.1s of video (a ~97% payload collapse). The zero-byte stall watchdog never tripped because bytes kept trickling, and `WatchSession.FlapState` falsely marked the session healthy because sequence numbers advanced, starving downstream Plex transcoders into extreme stutter or freeze.
   - *Resolution* ([`7ad93be`](https://github.com/bdina/tablo2hdhomerun/commit/7ad93be)): [`HlsSegmentFetch.scala`](file:///home/bdina/git/tablo2hdhomerun/src/main/scala/app/stream/HlsSegmentFetch.scala) implements `TrickleState`, tracking a rolling EMA (`alpha = 0.30`) of healthy segment throughput (`>= 200 KB/s`). Segments dropping below 15% baseline are flagged as trickle segments, suppressed from calling `onSeqAdvanced` (allowing `WatchSession.FlapState` to continue tracking the stall under the 45s `/watch` session retention and 500ms fast polling architecture), and trigger `HlsError.SegmentTrickleCollapse` after 5 consecutive occurrences to initiate session recovery. Burst-safe handling with native STC timestamp preservation avoids false `FreezeFramePacer` triggers on 2s HLS bursts. Meanwhile, when active pacing is enabled, `CacheStage` anchors a real-time media clock (`mediaClockAnchorPts`/`mediaClockAnchorNanos`), detecting when `lastOutputPts` lags wall clock by `>= 67,500` ticks (750ms) and synthesizing up to 6 catch-up freeze frames per 100ms tick while rebasing subsequent live video timestamps seamlessly.

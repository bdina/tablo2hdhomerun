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
| [2026.10.09_1.scenario.md](file:///home/bdina/git/tablo2hdhomerun/docs/playback-failure-scenarios/2026.10.09_1.scenario.md) | 2026-10-09 14:50–15:32 EDT | CBS 4.1 (RF 20) | Peak gusts to **32 mph** SSW | Wind-induced ATSC lock loss; 27.5s recovery exceeded client tolerance, causing playback restart. | Mitigated (`88067da`, recovery 7.4–7.8s) |
| [2026.10.10_0.scenario.md](file:///home/bdina/git/tablo2hdhomerun/docs/playback-failure-scenarios/2026.10.10_0.scenario.md) | 2026-10-09 23:19 – 10-10 10:06 EDT | CBS 4.1 (RF 20) & PBS 2.3 (RF 32) | Daytime breeze (8–13 mph NW) | Morning canopy breeze caused clustered dropouts on RF 20 while RF 32 ran flawlessly. | Open: clustered dropouts exhaust client buffer; two-tier recovery (`7df5e7d`) not yet field-validated |
| [2026.10.13_0.scenario.md](file:///home/bdina/git/tablo2hdhomerun/docs/playback-failure-scenarios/2026.10.13_0.scenario.md) | 2026-10-13 17:43–21:24 EDT | CBS 4.1 (RF 20) | Gusts to **29–32 mph** WNW | Corrupt ATSC bytes passed silently by zero-byte watchdog, causing Plex crash and fast-attach trap. | Open |
| [2026.10.14_0.scenario.md](file:///home/bdina/git/tablo2hdhomerun/docs/playback-failure-scenarios/2026.10.14_0.scenario.md) | 2026-10-14 19:50–21:35 EDT | CBS 4.1 (RF 20) | Shift to N, **11.5 mph** sustained | Validated I-frame gating & capped dampening across 13 successful retunes (~6.5s); silent demodulator corruption ended 58m Roku session. | Validated Mode C & D fix (`d3bdac8`); Mode E open |
| [2026.10.15_0.scenario.md](file:///home/bdina/git/tablo2hdhomerun/docs/playback-failure-scenarios/2026.10.15_0.scenario.md) | 2026-10-15 19:53–21:44 EDT | CBS 4.1 (RF 20) | Calm (0 mph, no gusts, clear) | Validated 180s idle-grace hand-off (Apple TV attached in 28s) & multi-client grabber multiplexing; silent 5,541-error RF packet burst starved player buffer. | Validated 180s idle-grace & watchdog scoping (`5f8c22d`); Mode E open |
| [2026.10.16_0.scenario.md](file:///home/bdina/git/tablo2hdhomerun/docs/playback-failure-scenarios/2026.10.16_0.scenario.md) | 2026-10-16 08:11–15:59 EDT | CBS 4.1 (RF 20) | Calm (3–11 mph S, no gusts) | Benchmark 7h 47m continuous stream (44.75 GB); Plex 10 GB transcode tmpfs ramdisk overflow (`No space left on device`); validated 180s idle-grace fast-attach in 59s. | Validated benchmark stability (`5f8c22d`); Mode F operational |
| [2026.10.17_0.scenario.md](file:///home/bdina/git/tablo2hdhomerun/docs/playback-failure-scenarios/2026.10.17_0.scenario.md) | 2026-10-17 21:44–22:56 EDT | CBS 4.1 (RF 20) | Calm (0–8 mph NNW, no gusts) | Sustained 71m iPhone stream (6.78 GB); instantaneous I-frame lock (2 packets dropped in 0ms); late 45-error burst before clean disconnect and 180s idle-grace teardown. | Validated startup gating & lease hold (`9b9e68f`); Mode E precursor |
| [2026.10.18_0.scenario.md](file:///home/bdina/git/tablo2hdhomerun/docs/playback-failure-scenarios/2026.10.18_0.scenario.md) | 2026-10-18 13:28–14:47 EDT | CBS 4.1 (RF 20) | Calm (0–4 mph VRB, no gusts) | Plex on Apple TV 4K failed repeatedly from 7,821 decode errors and three 8s Tablo hardware stalls; comparative control experiment using official Tablo client absorbed freezes, self-recovered, and maintained channel uptime. | Open: Downstream Plex pipeline fragility; proposed synthetic filler, TEI gating, and retune bridging |



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
| **B. Environmental RF Dropout** | Audio squeaks, pixelation, sudden stream stall | Mast sway / canopy movement during gusts > 20 mph breaking ATSC carrier lock | `no new data for 5s, retuning` (8s before `88067da`) | Mitigated: ~7.5s recovery; physical RF loss cannot be removed by the proxy |
| **C. Transcoder Keyframe Misalignment** | Black screen on client startup; client disconnects in < 30s | First HLS segment starts mid-GOP without valid PAT/PMT/IDR sequence headers | `Invalid frame dimensions 0x0` in Plex transcoder log | Resolved (`d3bdac8`): client attach now gated on I-frame in `MpegTsSync` |
| **D. Fast-Attach Corrupt Tuner Trap** | Client crashes, re-tunes immediately, but crashes again within seconds | Proxy fast-attaches new client to existing degraded hardware tuner lease during idle-grace | `fast-attached` followed by rapid transcode runner crash | Resolved (`d3bdac8`): I-frame gating and clean retunes eliminated crash loop |
| **E. Silent Corruption Blind Spot** | Video frozen or corrupted on client, but proxy watchdog never trips | Tablo continues trickling fragmentary/corrupt TS packets; zero-byte watchdog sees traffic | Corrupt macroblocks in Plex while proxy logs `real data resumed after X ms` | Open; demonstrated in `2026.10.14_0` (649 decode errors); proposed in-line TEI/CC monitoring |
| **F. Downstream Transcoder Buffer Overflow** | Stream abruptly drops after prolonged streaming (7+ hours); client ejected to guide | Downstream transcode server maintains unpruned live ingest seekback buffer on memory-backed `tmpfs`; ramdisk reaches 100% capacity | `Error submitting a packet to the muxer: No space left on device` in Plex; proxy logs `outcome=downstream-closed` | Operational: requires host transcode tmpfs sizing (>=20GB) or NVMe scratch; proxy 180s idle-grace rescued lease |


---

## 5. Architectural Standards

All scenario documentation must adhere to repository guidelines:
- Reference code symbols with exact clickable file links and line numbers (e.g., [`ResilientHlsSource.scala`](file:///home/bdina/git/tablo2hdhomerun/src/main/scala/app/stream/ResilientHlsSource.scala)).
- When referencing commits, use short 7-character commit hashes (e.g., `29f9c76`).
- Maintain objective empirical accounting with verbatim log lines and timestamps.
- Formulate concrete recommendations and testable action items for the codebase.

# Tablo TV 4th Generation Local API — Usage Guide

This guide supplements `tablo-api-4g.yaml`, a reverse-engineered OpenAPI specification
derived from a packet capture of the official Tablo iOS client (v2.0.1 build 78) talking to
a Tablo 4G QUAD 128GB on the local network.

## Files in this directory

| File | Purpose |
|------|---------|
| `tablo-api-4g.yaml` | Machine-readable API specification (OpenAPI 3.1) |
| `tablo-api-guide.md` | This document — usage, security, and workflow context |
| `pcap_to_openapi.py` | Script to regenerate the spec from future PCAP captures |
| `tablo-credentials.txt` | Extracted tokens, hashes, and identifiers from the source PCAP |
| `tablo_capture-http_filtered.pcap` | Original packet capture |

> **Security warning:** `tablo-credentials.txt` contains live authentication material from your
> capture. Treat it like a password file. Do not commit it to version control or share it
> publicly. Add it to `.gitignore` if this directory is ever placed inside a repo.

## Architecture overview

The Tablo exposes two HTTP services on your LAN:

```
┌─────────────────┐         ┌──────────────────────────────────────┐
│  iOS Client     │  :8887  │  Tablo REST API                      │
│  (Tablo-FAST)   │────────▶│  guide, recordings, settings,        │
│                 │         │  player sessions, notifications      │
│                 │  :80    │                                      │
│                 │────────▶│  Tablo HLS Streaming                 │
│                 │         │  m3u8 playlists + MPEG-TS segments   │
└─────────────────┘         └──────────────────────────────────────┘
```

- **Port 8887** — JSON REST API. Most operations live here.
- **Port 80** — HLS video delivery. No Tablo auth header; access is gated by opaque stream
  tokens returned from the player session API.

The default server URL in the spec is `http://{tabloHost}:8887`. Replace `tabloHost` with your
Tablo's LAN IP (discovered via `GET /server/info`).

## Using the OpenAPI spec

### Viewing and exploring

Import `tablo-api-4g.yaml` into any OpenAPI-compatible tool:

- [Swagger Editor](https://editor.swagger.io/) — paste or upload the YAML
- [Stoplight Studio](https://stoplight.io/studio) — local desktop app
- VS Code with an OpenAPI extension (e.g. *OpenAPI (Swagger) Editor*)
- Code generators: `openapi-generator`, `oapi-codegen`, etc.

Each operation includes an `x-observed-count` field showing how many times it appeared in the
source capture. Endpoints with a count of zero were requested but had no captured response.

### Path parameter conventions

The spec normalizes volatile path segments into parameters:

| Parameter | Example value | Description |
|-----------|---------------|-------------|
| `{channelId}` | `S73354_009_02` | Channel identifier (`S{station}_{major}_{minor}`) |
| `{airingId}` | `LH-CEP000015221761-S20431_004_01-T1783866600` | Guide airing identifier |
| `{sessionId}` | `c3cc25a2-efb0-4c96-bf5a-4dc1371586fe` | Player session UUID |
| `{id}` | `1025238` | Numeric object ID (recordings, images, etc.) |

### Response schemas

JSON response schemas were inferred by merging observed response bodies. They represent the
shape of data returned during the capture but may not include fields that were absent in those
particular responses. Nullable or optional fields are not always marked.

---

## Security

Authentication is the main barrier to building a third-party client. This section documents
everything observed in the capture.

### Authorization header format

Authenticated requests on port 8887 carry a custom `Authorization` header:

```
Authorization: tablo:{deviceToken}:{requestHash}
```

| Component | Description | Stability |
|-----------|-------------|-----------|
| `deviceToken` | 43-character alphanumeric string issued during device pairing | Stable for a given client↔device pairing |
| `requestHash` | 32-character lowercase hex string (MD5 length) | **Changes per request** |

**Example from the capture:**

```
Authorization: tablo:ljpg6ZkwShVv8aI12E2LP55Ep8vq1uYDPvX0DdTB:a26790041b7d7f034cda9e140b1a89db
```

### How authentication behaves

Based on analysis of 1,120 requests in the capture:

1. **Discovery is unauthenticated.** The first `GET /server/info` (no query params, no auth
   header) succeeds without credentials. This is how the app finds and identifies the device.

2. **Everything else on :8887 requires auth** (except images — see below). Once paired, the app
   sends the `tablo:` header on guide, recordings, settings, player, and notification requests.

3. **The `lh` query flag.** Most authenticated requests include a bare `lh` parameter with no
   value (e.g. `/server/info?lh` or `/guide/airings?state=requested&lh&lh`). The name likely
   stands for "local hash." Its presence correlates with requests that include the auth header.
   It may signal to the server that a request hash is attached.

4. **The request hash is not a simple digest.** Tested candidates (MD5/HMAC-MD5 of path, URI,
   method+URI, token+URI) did not match observed hashes. The hash algorithm has not been
   reverse-engineered from this capture alone.

5. **Hash stability varies by endpoint:**

   | Pattern | Example | Implication |
   |---------|---------|-------------|
   | Same URI → same hash | `GET /server/info?lh` (repeated) | Hash is deterministic for that exact request |
   | Same URI → different hash | `POST .../keepalive?lh` (6 calls) | Hash includes a nonce or timestamp |
   | Different query → different hash | `/airings?date=2026-07-08` vs `date=2026-07-09` | Hash covers the full URI including query string |

6. **HLS streaming (port 80) uses separate tokens.** Stream URLs like
   `/stream/pl.m3u8?ox4jMUWRb5lpDPDo8-_DyA` use opaque query-string tokens issued in the
   `playlist_url` field of a `PlayerSession` response. No `Authorization` header is sent.

### Endpoints that do not require auth

| Endpoint | Notes |
|----------|-------|
| `GET /server/info` | Initial device discovery (no `?lh`, no header) |
| `GET /images/{id}` | Program/channel artwork (JPEG) |
| `GET /stream/pl.m3u8?{token}` | HLS playlist (token from player session) |
| `GET /stream/pls.m3u8?{token}` | HLS sub-playlist |
| `GET /stream/segw.ts?{token}` | HLS media segment |

### Obtaining a device token

The pairing flow was **not captured** in this PCAP — the iOS app was already paired. The device
token appears to be issued during initial setup when the app links to the Tablo over the LAN
(possibly via a Bluetooth or local discovery step before HTTP traffic begins).

To work with the API today without reverse-engineering the hash algorithm, you have two options:

1. **Replay captured credentials** — use the token/hash pairs in `tablo-credentials.txt`.
   Hashes are single-use for some endpoints, so replay only works for idempotent GETs with
   stable hashes.

2. **Proxy the official app** — capture fresh auth headers from the iOS client in real time
   (mitmproxy, Wireshark) and forward them to your client.

### User-Agent strings

Two User-Agent values were observed:

| Agent | Used for |
|-------|----------|
| `Tablo-FAST/2.0.1 (Mobile; iPhone; iOS 26.5)` | Most REST API calls |
| `Tablo/78 CFNetwork/3860.600.12 Darwin/25.5.0` | Notification stream connection |

The server may validate User-Agent. Consider matching these if requests are rejected.

### Credentials file reference

`tablo-credentials.txt` is organized into sections:

- **DEVICE** — server ID, firmware, IP, cache key
- **DEVICE TOKENS** — the pairing token and hash count
- **CLIENT DEVICE** — iOS device ID, model, app version
- **PLAYER SESSIONS** — UUIDs for active playback sessions
- **STREAM TOKENS** — opaque HLS access tokens per stream path
- **AUTHENTICATION LOG** — full list of `[frame] METHOD URI` with the exact `Authorization`
  header observed

---

## Common workflows

### 1. Device discovery

```http
GET /server/info HTTP/1.1
Host: 192.168.2.28:8887
```

No authentication required. Returns device name, firmware version, model, tuner count, and
`local_address`. Use `local_address` as `tabloHost` for all subsequent requests.

### 2. Browse the program guide

```http
GET /views/guide/channels/S73354_009_02/airings?date=2026-07-08&state=requested&lh&lh HTTP/1.1
Host: 192.168.2.28:8887
Authorization: tablo:{deviceToken}:{requestHash}
```

Returns an array of airing objects for the given channel and date. The app fetches two days
(today and tomorrow) for each visible channel.

### 3. List recordings

```http
GET /recordings/series?lh HTTP/1.1
GET /views/recordings/recent?sort=age&order=desc&failed=false&lh HTTP/1.1
GET /recordings/series/episodes/{id}?lh HTTP/1.1
```

Series summaries link to episode paths. Episode detail includes full video metadata, channel
info, playback position, and recording quality.

### 4. Watch live TV

This is the most involved flow:

```
┌──────────┐  POST /guide/channels/{id}/watch    ┌───────────┐
│  Client  │────────────────────────────────────▶│   Tablo   │
│          │◀────────── PlayerSession ───────────│  :8887    │
└──────────┘                                     └───────────┘
     │
     │  GET /stream/pl.m3u8?{token}
     ▼
┌───────────┐  GET /stream/segw.ts?{token}  ┌───────────┐
│  Client   │──────────────────────────────▶│   Tablo   │
│  (player) │◀──── HLS segments ────────────│    :80    │
└───────────┘                               └───────────┘
     │
     │  POST /player/sessions/{id}/keepalive  (every ~165s)
     ▼
┌───────────┐  DELETE /player/sessions/{id} ┌───────────┐
│  Client   │──────────────────────────────▶│   Tablo   │
│  (done)   │◀────────── 204 ───────────────│  :8887    │
└───────────┘                               └───────────┘
```

**Step 1 — Start playback:**

```http
POST /guide/channels/S73354_009_02/watch?lh HTTP/1.1
Host: 192.168.2.28:8887
Authorization: tablo:{deviceToken}:{requestHash}
Content-Type: application/json

{
  "bandwidth": null,
  "platform": "ios",
  "device_id": "531B3222-12BE-4C78-A45F-DEFBD2EF227F",
  "extra": {
    "deviceOS": "iOS",
    "deviceOSVersion": "26.5",
    "deviceMake": "Apple",
    "deviceModel": "iPhone15,2",
    "width": 393,
    "height": 852,
    "lang": "en_US",
    "limitedAdTracking": 1
  }
}
```

**Response (`PlayerSession`):**

```json
{
  "token": "c3cc25a2-efb0-4c96-bf5a-4dc1371586fe",
  "expires": "2026-07-08T12:50:14Z",
  "keepalive": 165,
  "playlist_url": "http://192.168.2.28:80/stream/pl.m3u8?ox4jMUWRb5lpDPDo8-_DyA",
  "video_details": { "container_format": "mpeg2", "flags": [] },
  "canRecord": true,
  "audio_details": { "container_format": "ac3" }
}
```

**Step 2 — Stream video** using `playlist_url` on port 80 (no auth header).

**Step 3 — Keep alive** by posting to `/player/sessions/{token}/keepalive?lh` before `expires`.
The `keepalive` field (165 seconds) is the recommended interval.

**Step 4 — End session** with `DELETE /player/sessions/{token}?lh` (returns 204).

### 6. Watch a recorded episode

Similar to live TV, but starts from a recording path:

```
POST /recordings/series/episodes/{id}/watch?lh
  → PlayerSession (includes bif_url_sd, bif_url_hd, recordings_path)

GET /stream/pl.m3u8?{token}   (port 80)
POST /player/sessions/{sessionId}/keepalive
DELETE /player/sessions/{sessionId}
```

### 7. Manage recordings

| Method | Path | Purpose |
|--------|------|---------|
| GET | `/recordings/series/{id}/seasons` | List season paths for a series |
| GET | `/recordings/series/seasons/{id}/episodes` | List episode paths in a season |
| GET | `/recordings/series/{id}/episodes` | List all episode paths for a series |
| PATCH | `/recordings/series/episodes/{id}` | Update playback position (`{"position": 111}`) |
| DELETE | `/recordings/series/episodes/{id}` | Delete a single recording |
| POST | `/recordings/series/{id}/delete` | Bulk delete (`{"filter": "watched"}`) |

### 8. Guide show recording rules

| Method | Path | Purpose |
|--------|------|---------|
| GET | `/guide/{showId}` | Series/show recording rule details |
| GET | `/guide/{showId}/airings` | Airings covered by a show recording rule |

Show IDs use the format `C{num}_SHOW_SH{num}` (e.g. `C627870_SHOW_SH005735150000`).

### 9. Real-time notifications

```http
GET /notifications/stream?client_type=ios&client_version=2.0.1&client_build=78
    &device_id={uuid}&device_type=iPhone15,2&lh HTTP/1.1
Authorization: tablo:{deviceToken}:{requestHash}
```

Opens a long-lived connection (likely Server-Sent Events). The response body was not fully
captured. The app maintains this connection in the background to receive recording status
changes, guide updates, and similar events.

---

## Regenerating the spec from a new capture

### Prerequisites

- [Wireshark](https://www.wireshark.org/) / `tshark` on your PATH
- Python 3.8+
- PyYAML: `pip install pyyaml`

### Capturing traffic

1. Connect your machine to the same LAN as the Tablo.
2. Start a capture filtered to the Tablo IP:
   ```
   host 192.168.2.28 and (tcp port 8887 or tcp port 80)
   ```
3. Use the official app to exercise the features you want documented.
4. Save as a `.pcap` file. An HTTP-only filtered copy (like `tablo_capture-http_filtered.pcap`)
   keeps file size manageable.

### Running the generator

```bash
# Single PCAP
python3 pcap_to_openapi.py tablo_capture-http_filtered.pcap

# Merge multiple PCAPs into one spec (recommended as you capture more workflows)
python3 pcap_to_openapi.py \
  tablo_capture-http_filtered.pcap \
  tablo_capture-http_filtered_live+recordings.pcap \
  -o tablo-api-4g.yaml

# Custom output paths:
python3 pcap_to_openapi.py my_capture.pcap \
  -o tablo-api-4g.yaml \
  --credentials tablo-credentials.txt

# Spec only (no credentials extract):
python3 pcap_to_openapi.py my_capture.pcap -o tablo-api-4g.yaml

# Override the Tablo IP if /server/info was not captured:
python3 pcap_to_openapi.py my_capture.pcap --host 192.168.2.50
```

When multiple PCAPs are provided, the tool merges all discovered endpoints into a single
spec. Endpoints seen in both captures combine their observed counts and JSON schemas.
Each operation includes an `x-source-pcaps` list showing which capture(s) contributed it.

The script will print a summary of paths and schemas generated.

### What the script does

1. Extracts HTTP requests and responses via `tshark`
2. Normalizes URL paths (replacing numeric IDs and UUIDs with `{id}`, `{sessionId}`, etc.)
3. Infers JSON schemas from observed response bodies
4. Maps status codes, query parameters, and auth requirements per endpoint
5. Writes an OpenAPI 3.1 YAML file and optionally a credentials extract

### Limitations

- Only documents endpoints **actually observed** in the capture
- JSON schemas are inferred from samples, not from server-side definitions
- Request hash algorithm is unknown — the spec documents the header format but cannot
  generate valid hashes
- Long-lived streams (notifications) may have incomplete response documentation
- TCP connection reuse can occasionally mis-associate responses in `tshark` output

---

## Known gaps

The following were not observed in this capture and are absent from the spec:

- Device pairing / token issuance flow
- Scheduling or canceling recordings (POST/DELETE on guide endpoints)
- Firmware update application (only check/info were seen)
- Remote/out-of-home access (all traffic is LAN-local)
- Request hash generation algorithm

Contributing additional PCAPs from other app workflows is the best way to expand coverage.

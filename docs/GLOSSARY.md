# Glossary

Definitions of terms, protocols, and concepts used in Tablo2HDHomeRun.

---

## Devices & Services

### HDHomeRun

A network-attached TV tuner device made by SiliconDust. Plex has built-in support for HDHomeRun devices, allowing them to be used as live TV sources. Tablo2HDHomeRun emulates the HDHomeRun HTTP API.

### Tablo 4th Gen

The fourth generation of Tablo DVR devices. Unlike earlier generations that used a proprietary interface, 4th Gen devices use a different API that requires cloud authentication through the Lighthouse service.

### Lighthouse (Cloud)

Tablo's cloud service at `lighthousetv.ewscloud.com`. Handles:
- User authentication (login)
- Device registration and discovery
- Profile management
- Channel lineup and guide data

### Plex Media Server

Popular media server software that can manage and stream media. Supports live TV and DVR functionality through compatible tuner devices like HDHomeRun.

---

## Channel Types

### OTA (Over-The-Air)

Traditional broadcast TV channels received via antenna. These are the primary channels from local TV stations (NBC, ABC, CBS, FOX, PBS, etc.). OTA channels consume a tuner when streaming.

### OTT (Over-The-Top)

Internet-streamed channels that don't require antenna reception. These are IPTV channels provided through Tablo's service. OTT channels typically do NOT consume a physical tuner slot since they're delivered via internet.

---

## Protocols & Formats

### HLS (HTTP Live Streaming)

Apple's streaming protocol using `.m3u8` playlist files that reference `.ts` segment files. Tablo devices deliver video as HLS streams.

### MPEG-TS (MPEG Transport Stream)

A container format for transmitting audio and video, commonly used for broadcast TV. HDHomeRun devices deliver video as MPEG-TS. The file extension is typically `.ts`.

### XMLTV

An XML format for TV program guide data. Plex can import XMLTV files for channel scheduling information. Tablo2HDHomeRun can generate XMLTV data from Tablo's guide.

### M3U8

The file extension for HLS playlist files. Contains URLs to video segments and metadata about the stream.

---

## Authentication

### Bearer Token

OAuth-style authentication token obtained from Lighthouse login. Sent in the `Authorization: Bearer {token}` header for cloud API requests.

### Lighthouse Token

A session token obtained after selecting a profile and device. Used for:
- Guide data requests (in URL path and `Lighthouse` header)
- Device authentication context

### HMAC-MD5 Signing

Authentication method for direct device requests. Involves:
1. Building a signature string from method, path, body hash, and date
2. Computing HMAC-MD5 with a secret key
3. Sending as `Authorization: tablo:{key}:{hmac}`

### Device Key / Hash Key

Secret keys used for HMAC authentication:
- **DeviceKey**: Identifies the client in the auth header
- **HashKey**: Secret used to compute the HMAC signature

---

## Data Structures

### Lineup

The list of available TV channels. Each channel entry contains:
- Channel number
- Network/station name
- Logo URL
- Stream URL

### Guide Data

Program schedule information including:
- Show titles and descriptions
- Air times and durations
- Episode numbers
- Ratings
- Images

### creds.bin

Encrypted file storing authentication credentials:
- Lighthouse tokens
- Selected device URL
- Profile information
- Tuner count

---

## Streaming Concepts

### Tuner

A physical component that can receive one broadcast channel at a time. Tablo 4th Gen devices typically have 2-4 tuners. The number of simultaneous OTA streams is limited by tuner count.

### Remuxing

Converting from one container format to another without re-encoding the video/audio. Tablo2HDHomeRun uses ffmpeg to remux HLS to MPEG-TS with `-c copy`.

### Transcoding

Re-encoding video/audio to a different codec. NOT used by Tablo2HDHomeRun (streams are passed through without quality loss).

### Playlist URL

The HLS `.m3u8` URL returned by Tablo when a stream is requested. This URL is fed to ffmpeg to access the stream.

---

## Configuration Terms

### Guide Days

Number of days of program guide data to fetch (1-7). More days = more API calls and storage.

### Lineup Update Interval

How often to refresh the channel lineup from Lighthouse (default: 30 days).

### Guide Update Interval

How often to refresh the XMLTV guide data (default: 24 hours).

### PseudoTV

Third-party software that creates custom TV channels from media files. Tablo2HDHomeRun can merge PseudoTV's XMLTV guide with Tablo guide data.

---

## File Types

| Extension | Description |
|-----------|-------------|
| `.bin` | Binary file (creds.bin - encrypted credentials) |
| `.json` | JSON data (lineup, schedules, guide cache) |
| `.xml` | XML data (XMLTV guide) |
| `.m3u8` | HLS playlist |
| `.ts` | MPEG Transport Stream segment |

---

## HTTP Status Codes Used

| Code | Meaning in Tablo2HDHomeRun |
|------|----------------------|
| 200 | Success |
| 204 | Success (no content) - OPTIONS preflight |
| 401 | Authentication failed |
| 404 | Channel or resource not found |
| 500 | Stream failed (tuner limit, device error) |
| 503 | Service unavailable (tuner limit) |

---

## RFC References

### RFC 1123 (Date Format)

Date format used in HMAC signing:
```
Sat, 31 Jan 2026 12:00:00 GMT
```

### RFC 7230-7235 (HTTP/1.1)

HTTP protocol specifications that the server implements.

---

## Tools

### ffmpeg

Open-source multimedia framework used to:
- Read HLS streams from Tablo
- Remux to MPEG-TS format
- Pipe output to HTTP response

Command used:
```bash
ffmpeg -i {playlist_url} -c copy -f mpegts pipe:1
```

### Express.js

Node.js web framework used for the HTTP server.

### dotenv

Node.js library for loading environment variables from `.env` files.

### Commander.js

Node.js library for parsing command-line arguments.

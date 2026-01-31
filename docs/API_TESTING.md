# API Testing Guide

This document provides curl commands for testing and discovering Tablo 4th Gen device APIs.

## Table of Contents

1. [Lighthouse Cloud API](#lighthouse-cloud-api)
2. [Tablo Device API](#tablo-device-api)
3. [HDHomeRun Emulation API](#hdhomerun-emulation-api-tablo2plex)
4. [Helper Scripts](#helper-scripts)

---

## Lighthouse Cloud API

These calls go to `lighthousetv.ewscloud.com` and use standard OAuth-style authentication.

### 1. Login

```bash
curl -X POST "https://lighthousetv.ewscloud.com/api/v2/login/" \
  -H "Content-Type: application/json" \
  -H "User-Agent: Tablo-FAST/2.0.0 (Mobile; iPhone; iOS 16.6)" \
  -H "Accept: */*" \
  -d '{
    "email": "your-email@example.com",
    "password": "your-password"
  }'
```

**Expected Response:**
```json
{
  "access_token": "eyJ0eXAiOiJKV1QiLCJhbGciOiJIUzI1NiJ9...",
  "token_type": "Bearer",
  "is_verified": true
}
```

**Save the token:**
```bash
ACCESS_TOKEN="Bearer eyJ0eXAiOiJKV1QiLCJhbGciOiJIUzI1NiJ9..."
```

---

### 2. Get Account Info

```bash
curl -X GET "https://lighthousetv.ewscloud.com/api/v2/account/" \
  -H "Authorization: ${ACCESS_TOKEN}" \
  -H "User-Agent: Tablo-FAST/2.0.0 (Mobile; iPhone; iOS 16.6)" \
  -H "Accept: */*"
```

**Expected Response:**
```json
{
  "identifier": "account_id_here",
  "profiles": [
    {
      "identifier": "profile_id",
      "name": "Main Profile"
    }
  ],
  "devices": [
    {
      "serverId": "ABC123",
      "name": "Living Room Tablo",
      "url": "http://192.168.1.50:8885",
      "type": "tablo_gen4",
      "reachability": "local"
    }
  ]
}
```

**Save for later:**
```bash
PROFILE_ID="profile_id_from_response"
SERVER_ID="serverId_from_response"
DEVICE_URL="http://192.168.1.50:8885"
```

---

### 3. Select Profile and Device

```bash
curl -X POST "https://lighthousetv.ewscloud.com/api/v2/account/select/" \
  -H "Authorization: ${ACCESS_TOKEN}" \
  -H "Content-Type: application/json" \
  -H "User-Agent: Tablo-FAST/2.0.0 (Mobile; iPhone; iOS 16.6)" \
  -H "Accept: */*" \
  -d "{
    \"pid\": \"${PROFILE_ID}\",
    \"sid\": \"${SERVER_ID}\"
  }"
```

**Expected Response:**
```json
{
  "token": "lighthouse_session_token_here"
}
```

**Save the token:**
```bash
LIGHTHOUSE_TOKEN="lighthouse_session_token_here"
```

---

### 4. Get Channel Lineup

```bash
curl -X GET "https://lighthousetv.ewscloud.com/api/v2/account/${LIGHTHOUSE_TOKEN}/guide/channels/" \
  -H "Authorization: ${ACCESS_TOKEN}" \
  -H "Lighthouse: ${LIGHTHOUSE_TOKEN}" \
  -H "User-Agent: Tablo-FAST/2.0.0 (Mobile; iPhone; iOS 16.6)" \
  -H "Accept: */*"
```

**Expected Response:**
```json
[
  {
    "identifier": "S122912_503_01",
    "name": "WNBC",
    "kind": "ota",
    "logos": [{"kind": "lightLarge", "url": "https://..."}],
    "ota": {
      "major": 5,
      "minor": 1,
      "callSign": "WNBC",
      "network": "NBC"
    }
  },
  ...
]
```

**Save a channel ID:**
```bash
CHANNEL_ID="S122912_503_01"
```

---

### 5. Get Guide Data for a Channel

```bash
DATE=$(date +%Y-%m-%d)

curl -X GET "https://lighthousetv.ewscloud.com/api/v2/account/guide/channels/${CHANNEL_ID}/airings/${DATE}/" \
  -H "Authorization: ${ACCESS_TOKEN}" \
  -H "Lighthouse: ${LIGHTHOUSE_TOKEN}" \
  -H "User-Agent: Tablo-FAST/2.0.0 (Mobile; iPhone; iOS 16.6)" \
  -H "Accept: */*"
```

**Expected Response:**
```json
[
  {
    "identifier": "airing_id",
    "title": "Morning News",
    "datetime": "2026-01-31T12:00:00Z",
    "duration": 3600,
    "kind": "episode",
    "show": {"title": "NBC News"},
    ...
  },
  ...
]
```

---

## Tablo Device API

These calls go directly to the Tablo device on your local network and require **HMAC-MD5 signing**.

### Understanding HMAC Authentication

Every device request needs an `Authorization` header with format:
```
Authorization: tablo:{DeviceKey}:{hmac_signature}
```

The HMAC is calculated from:
```
{METHOD}\n{PATH}\n{MD5_OF_BODY_OR_EMPTY}\n{DATE_RFC1123}
```

### Helper Function for Signing

Add this to your shell (bash/zsh):

```bash
# HMAC keys (same for all Tablo 4th Gen devices)
HASH_KEY="6l8jU5N43cEilqItmT3U2M2PFM3qPziilXqau9ys"
DEVICE_KEY="ljpg6ZkwShVv8aI12E2LP55Ep8vq1uYDPvX0DdTB"

# Generate RFC 1123 date
get_date() {
  date -u "+%a, %d %b %Y %H:%M:%S GMT"
}

# Calculate HMAC signature
calc_hmac() {
  local method="$1"
  local path="$2"
  local body="$3"
  local date="$4"

  local body_hash=""
  if [ -n "$body" ]; then
    body_hash=$(echo -n "$body" | md5sum | cut -d' ' -f1)
  fi

  local signature_input="${method}
${path}
${body_hash}
${date}"

  echo -n "$signature_input" | openssl dgst -md5 -hmac "$HASH_KEY" | cut -d' ' -f2
}

# Make signed request to Tablo device
tablo_request() {
  local method="$1"
  local url="$2"
  local path="$3"
  local body="$4"

  local date=$(get_date)
  local hmac=$(calc_hmac "$method" "$path" "$body" "$date")
  local auth="tablo:${DEVICE_KEY}:${hmac}"

  if [ "$method" = "GET" ]; then
    curl -X GET "${url}${path}?lh" \
      -H "Authorization: ${auth}" \
      -H "Date: ${date}" \
      -H "User-Agent: Tablo-FAST/1.7.0 (Mobile; iPhone; iOS 18.4)" \
      -H "Accept: */*" \
      -H "Connection: keep-alive"
  else
    curl -X POST "${url}${path}?lh" \
      -H "Authorization: ${auth}" \
      -H "Date: ${date}" \
      -H "User-Agent: Tablo-FAST/1.7.0 (Mobile; iPhone; iOS 18.4)" \
      -H "Accept: */*" \
      -H "Connection: keep-alive" \
      -H "Content-Type: application/x-www-form-urlencoded" \
      -d "$body"
  fi
}
```

---

### 1. Get Server Info

```bash
# Set your device URL
DEVICE_URL="http://192.168.1.50:8885"

# Using the helper function
tablo_request "GET" "$DEVICE_URL" "/server/info" ""
```

**Or manually:**

```bash
DEVICE_URL="http://192.168.1.50:8885"
DATE=$(date -u "+%a, %d %b %Y %H:%M:%S GMT")
PATH="/server/info"
METHOD="GET"

# Calculate signature (no body for GET)
SIGNATURE_INPUT="${METHOD}
${PATH}

${DATE}"

HMAC=$(echo -n "$SIGNATURE_INPUT" | openssl dgst -md5 -hmac "6l8jU5N43cEilqItmT3U2M2PFM3qPziilXqau9ys" | cut -d' ' -f2)

curl -X GET "${DEVICE_URL}${PATH}?lh" \
  -H "Authorization: tablo:ljpg6ZkwShVv8aI12E2LP55Ep8vq1uYDPvX0DdTB:${HMAC}" \
  -H "Date: ${DATE}" \
  -H "User-Agent: Tablo-FAST/1.7.0 (Mobile; iPhone; iOS 18.4)" \
  -H "Accept: */*"
```

**Expected Response:**
```json
{
  "model": {
    "name": "Tablo 4th Gen",
    "tuners": 4
  }
}
```

---

### 2. Watch a Channel (Start Stream)

```bash
DEVICE_URL="http://192.168.1.50:8885"
CHANNEL_ID="S122912_503_01"
UUID=$(uuidgen | tr '[:upper:]' '[:lower:]')

# Request body
BODY="{\"bandwidth\":null,\"device_id\":\"${UUID}\",\"platform\":\"ios\",\"extra\":{\"limitedAdTracking\":1,\"deviceOSVersion\":\"16.6\",\"lang\":\"en_US\",\"height\":1080,\"deviceId\":\"00000000-0000-0000-0000-000000000000\",\"width\":1920,\"deviceModel\":\"iPhone10,1\",\"deviceMake\":\"Apple\",\"deviceOS\":\"iOS\"}}"

# Using helper function
tablo_request "POST" "$DEVICE_URL" "/guide/channels/${CHANNEL_ID}/watch" "$BODY"
```

**Or manually:**

```bash
DEVICE_URL="http://192.168.1.50:8885"
CHANNEL_ID="S122912_503_01"
PATH="/guide/channels/${CHANNEL_ID}/watch"
METHOD="POST"
DATE=$(date -u "+%a, %d %b %Y %H:%M:%S GMT")

BODY='{"bandwidth":null,"device_id":"00000000-0000-0000-0000-000000000000","platform":"ios","extra":{"limitedAdTracking":1,"deviceOSVersion":"16.6","lang":"en_US","height":1080,"deviceId":"00000000-0000-0000-0000-000000000000","width":1920,"deviceModel":"iPhone10,1","deviceMake":"Apple","deviceOS":"iOS"}}'

# Calculate MD5 of body
BODY_MD5=$(echo -n "$BODY" | md5sum | cut -d' ' -f1)

# Build signature
SIGNATURE_INPUT="${METHOD}
${PATH}
${BODY_MD5}
${DATE}"

HMAC=$(echo -n "$SIGNATURE_INPUT" | openssl dgst -md5 -hmac "6l8jU5N43cEilqItmT3U2M2PFM3qPziilXqau9ys" | cut -d' ' -f2)

curl -X POST "${DEVICE_URL}${PATH}?lh" \
  -H "Authorization: tablo:ljpg6ZkwShVv8aI12E2LP55Ep8vq1uYDPvX0DdTB:${HMAC}" \
  -H "Date: ${DATE}" \
  -H "User-Agent: Tablo-FAST/1.7.0 (Mobile; iPhone; iOS 18.4)" \
  -H "Accept: */*" \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "$BODY"
```

**Expected Response:**
```json
{
  "token": "stream_session_token",
  "expires": "2026-01-31T13:00:00Z",
  "keepalive": 30,
  "playlist_url": "http://192.168.1.50:8885/stream/abc123/playlist.m3u8",
  "video_details": {
    "container_format": "mpeg-ts",
    "flags": []
  }
}
```

---

### 3. Play the Stream

Once you have the `playlist_url`, you can play it:

```bash
# With ffmpeg (save to file)
ffmpeg -i "http://192.168.1.50:8885/stream/abc123/playlist.m3u8" \
  -c copy -f mpegts output.ts

# With ffplay (watch live)
ffplay "http://192.168.1.50:8885/stream/abc123/playlist.m3u8"

# With VLC
vlc "http://192.168.1.50:8885/stream/abc123/playlist.m3u8"

# With mpv
mpv "http://192.168.1.50:8885/stream/abc123/playlist.m3u8"
```

---

## HDHomeRun Emulation API (Tablo2HDHomeRun)

These calls go to your Tablo2HDHomeRun server (no authentication required).

### 1. Discover Device

```bash
TABLO2HDHOMERUN="http://192.168.1.100:8181"

curl "${TABLO2HDHOMERUN}/discover.json"
```

**Expected Response:**
```json
{
  "FriendlyName": "Tablo 4th Gen Proxy",
  "Manufacturer": "tablo2plex",
  "ModelNumber": "HDHR3-US",
  "DeviceID": "12345678",
  "BaseURL": "http://192.168.1.100:8181",
  "LineupURL": "http://192.168.1.100:8181/lineup.json",
  "TunerCount": 4
}
```

---

### 2. Get Lineup

```bash
curl "${TABLO2HDHOMERUN}/lineup.json"
```

**Expected Response:**
```json
[
  {
    "GuideNumber": "5.1",
    "GuideName": "NBC",
    "ImageURL": "https://...",
    "Affiliate": "WNBC",
    "URL": "http://192.168.1.100:8181/channel/S122912_503_01"
  },
  ...
]
```

---

### 3. Get Lineup Status

```bash
curl "${TABLO2HDHOMERUN}/lineup_status.json"
```

**Expected Response:**
```json
{
  "ScanInProgress": 0,
  "ScanPossible": 1,
  "Source": "Antenna",
  "SourceList": ["Antenna"]
}
```

---

### 4. Stream a Channel

```bash
# Stream to file
curl "${TABLO2HDHOMERUN}/channel/S122912_503_01" -o stream.ts

# Or pipe to ffplay
curl "${TABLO2HDHOMERUN}/channel/S122912_503_01" | ffplay -

# Or pipe to VLC
curl "${TABLO2HDHOMERUN}/channel/S122912_503_01" | vlc -
```

---

### 5. Get Guide XML

```bash
curl "${TABLO2HDHOMERUN}/guide.xml" -o guide.xml

# Pretty print
curl "${TABLO2HDHOMERUN}/guide.xml" | xmllint --format -
```

---

## Helper Scripts

### Complete Test Script

Save as `test-tablo.sh`:

```bash
#!/bin/bash
set -e

# Configuration
HASH_KEY="6l8jU5N43cEilqItmT3U2M2PFM3qPziilXqau9ys"
DEVICE_KEY="ljpg6ZkwShVv8aI12E2LP55Ep8vq1uYDPvX0DdTB"

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

get_date() {
  date -u "+%a, %d %b %Y %H:%M:%S GMT"
}

calc_hmac() {
  local method="$1"
  local path="$2"
  local body="$3"
  local date="$4"

  local body_hash=""
  if [ -n "$body" ]; then
    body_hash=$(echo -n "$body" | md5sum | cut -d' ' -f1)
  fi

  local signature_input="${method}
${path}
${body_hash}
${date}"

  echo -n "$signature_input" | openssl dgst -md5 -hmac "$HASH_KEY" 2>/dev/null | cut -d' ' -f2
}

test_device() {
  local device_url="$1"
  local path="/server/info"
  local date=$(get_date)
  local hmac=$(calc_hmac "GET" "$path" "" "$date")
  local auth="tablo:${DEVICE_KEY}:${hmac}"

  echo -e "${YELLOW}Testing device: ${device_url}${NC}"
  echo -e "${YELLOW}Path: ${path}${NC}"
  echo -e "${YELLOW}Date: ${date}${NC}"
  echo -e "${YELLOW}HMAC: ${hmac}${NC}"
  echo ""

  response=$(curl -s -X GET "${device_url}${path}?lh" \
    -H "Authorization: ${auth}" \
    -H "Date: ${date}" \
    -H "User-Agent: Tablo-FAST/1.7.0 (Mobile; iPhone; iOS 18.4)" \
    -H "Accept: */*")

  if echo "$response" | grep -q "tuners"; then
    echo -e "${GREEN}SUCCESS!${NC}"
    echo "$response" | python3 -m json.tool 2>/dev/null || echo "$response"
  else
    echo -e "${RED}FAILED${NC}"
    echo "$response"
  fi
}

# Usage
if [ -z "$1" ]; then
  echo "Usage: $0 <device_url>"
  echo "Example: $0 http://192.168.1.50:8885"
  exit 1
fi

test_device "$1"
```

**Usage:**
```bash
chmod +x test-tablo.sh
./test-tablo.sh http://192.168.1.50:8885
```

---

### Device Discovery Script

Save as `discover-tablo.sh`:

```bash
#!/bin/bash

# Scan common ports for Tablo devices
echo "Scanning network for Tablo devices..."

SUBNET="${1:-192.168.1}"
PORT="8885"

for i in {1..254}; do
  IP="${SUBNET}.${i}"
  (
    timeout 1 bash -c "echo >/dev/tcp/${IP}/${PORT}" 2>/dev/null && \
    echo "Found device at ${IP}:${PORT}"
  ) &
done

wait
echo "Scan complete."
```

**Usage:**
```bash
chmod +x discover-tablo.sh
./discover-tablo.sh 192.168.1  # Scan 192.168.1.0/24
```

---

## Troubleshooting

### Common Issues

| Issue | Cause | Solution |
|-------|-------|----------|
| `401 Unauthorized` | HMAC mismatch | Check date format, verify keys |
| `Connection refused` | Wrong IP/port | Verify device URL |
| `Empty response` | Device busy | Wait and retry |
| `No playlist_url` | Tuners in use | Stop other streams |

### Verify HMAC Calculation

```bash
# Test HMAC calculation
METHOD="GET"
PATH="/server/info"
BODY=""
DATE="Sat, 31 Jan 2026 12:00:00 GMT"

SIGNATURE_INPUT="${METHOD}
${PATH}

${DATE}"

echo "Signature input:"
echo "$SIGNATURE_INPUT" | xxd

HMAC=$(echo -n "$SIGNATURE_INPUT" | openssl dgst -md5 -hmac "6l8jU5N43cEilqItmT3U2M2PFM3qPziilXqau9ys" | cut -d' ' -f2)
echo ""
echo "HMAC: $HMAC"
```

### Debug Mode

Add `-v` to curl for verbose output:

```bash
curl -v -X GET "${DEVICE_URL}/server/info?lh" \
  -H "Authorization: tablo:${DEVICE_KEY}:${HMAC}" \
  -H "Date: ${DATE}" \
  ...
```

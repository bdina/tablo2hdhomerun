# Security Model Documentation

This document details the authentication and authorization mechanisms used by Tablo 4th Generation devices and the Lighthouse cloud service.

## Table of Contents

1. [Security Architecture Overview](#security-architecture-overview)
2. [Authentication Layers](#authentication-layers)
3. [Lighthouse Cloud Authentication](#lighthouse-cloud-authentication)
4. [Device HMAC Authentication](#device-hmac-authentication)
5. [Credential Storage](#credential-storage)
6. [Offline Operation Analysis](#offline-operation-analysis)
7. [Security Considerations](#security-considerations)

---

## Security Architecture Overview

The Tablo 4th Gen ecosystem uses a **two-tier authentication model**:

```
┌────────────────────────────────────────────────────────────────────────────┐
│                         Security Architecture                              │
├────────────────────────────────────────────────────────────────────────────┤
│                                                                            │
│   ┌───────────────────┐                                                    │
│   │   Client          │                                                    │
│   │ (Tablo2HdHomeRun) │                                                    │
│   └──────┬────────────┘                                                    │
│          │                                                                 │
│          ├────────────────────────────┐                                    │
│          │                            │                                    │
│          ▼                            ▼                                    │
│   ┌─────────────────┐          ┌─────────────────┐                         │
│   │   Lighthouse    │          │  Tablo Device   │                         │
│   │     Cloud       │          │   (Local LAN)   │                         │
│   ├─────────────────┤          ├─────────────────┤                         │
│   │                 │          │                 │                         │
│   │  OAuth-style    │          │  HMAC-MD5       │                         │
│   │  Bearer Tokens  │          │  Signing        │                         │
│   │                 │          │                 │                         │
│   │  - Login        │          │  - Server Info  │                         │
│   │  - Account Info │          │  - Watch Stream │                         │
│   │  - Guide Data   │          │                 │                         │
│   │  - Device List  │          │                 │                         │
│   │                 │          │                 │                         │
│   └─────────────────┘          └─────────────────┘                         │
│                                                                            │
└────────────────────────────────────────────────────────────────────────────┘
```

### Key Finding: Separate Authentication Domains

**Lighthouse Cloud** and **Tablo Device** use **completely independent authentication mechanisms**:

| Aspect | Lighthouse Cloud | Tablo Device |
|--------|-----------------|--------------|
| Protocol | HTTPS | HTTP |
| Auth Type | OAuth Bearer Token | HMAC-MD5 Signature |
| Credentials | User email/password | Shared secret keys |
| Purpose | Account, discovery, guide | Streaming, device info |

---

## Authentication Layers

### Layer 1: Lighthouse Cloud (Account-Based)

```
User Email + Password
        │
        ▼
┌─────────────────┐
│  POST /login/   │ ──► access_token (JWT)
└─────────────────┘
        │
        ▼
┌─────────────────┐
│  GET /account/  │ ──► devices[], profiles[]
└─────────────────┘
        │
        ▼
┌─────────────────────┐
│ POST /account/select│ ──► Lighthouse token
└─────────────────────┘
        │
        ▼
   Guide Data Access
   Device Discovery
```

### Layer 2: Device Communication (HMAC-Based)

```
Device IP (from Lighthouse or manual)
        │
        ▼
┌─────────────────────────────────────┐
│  HMAC-MD5 Signature Generation      │
│                                     │
│  Input:                             │
│    METHOD + "\n" +                  │
│    PATH + "\n" +                    │
│    MD5(body) + "\n" +               │
│    DATE (RFC 1123)                  │
│                                     │
│  Key: HashKey (shared secret)       │
│                                     │
│  Output: "tablo:{DeviceKey}:{hmac}" │
└─────────────────────────────────────┘
        │
        ▼
   Device API Access
   (streaming, info)
```

---

## Lighthouse Cloud Authentication

### Login Flow

```http
POST https://lighthousetv.ewscloud.com/api/v2/login/
Content-Type: application/json

{
  "email": "user@example.com",
  "password": "userpassword"
}
```

**Response:**
```json
{
  "access_token": "eyJ0eXAiOiJKV1QiLCJhbGciOiJIUzI1NiJ9...",
  "token_type": "Bearer",
  "is_verified": true
}
```

### Account Access

```http
GET https://lighthousetv.ewscloud.com/api/v2/account/
Authorization: Bearer {access_token}
```

**Response includes:**
- Account identifier
- List of profiles (for multi-user households)
- List of registered devices (with local URLs)

### Device/Profile Selection

```http
POST https://lighthousetv.ewscloud.com/api/v2/account/select/
Authorization: Bearer {access_token}
Content-Type: application/json

{
  "pid": "profile_identifier",
  "sid": "device_server_id"
}
```

**Response:**
```json
{
  "token": "lighthouse_session_token"
}
```

### Guide Data Access

```http
GET https://lighthousetv.ewscloud.com/api/v2/account/{lighthouse_token}/guide/channels/
Authorization: Bearer {access_token}
Lighthouse: {lighthouse_token}
```

---

## Device HMAC Authentication

### The HMAC Signing Process

The Tablo device uses **HMAC-MD5** signatures for request authentication:

```javascript
// Step 1: Build signature input string
const signatureInput = [
    method,              // "GET" or "POST"
    path,                // "/guide/channels/{id}/watch"
    bodyHash,            // MD5 of body (hex) or empty string
    date                 // RFC 1123 format
].join("\n");

// Step 2: Compute HMAC-MD5
const hmac = HMAC_MD5(signatureInput, HashKey);

// Step 3: Build Authorization header
const auth = `tablo:${DeviceKey}:${hmac.hex()}`;
```

### Shared Secret Keys

**Critical Security Finding:** The HMAC keys are **hardcoded defaults** in the application:

```javascript
// From Encryption.js lines 675-679
const HashKey = process.env.HashKey ?? "6l8jU5N43cEilqItmT3U2M2PFM3qPziilXqau9ys";
const DeviceKey = process.env.DeviceKey ?? "ljpg6ZkwShVv8aI12E2LP55Ep8vq1uYDPvX0DdTB";
```

These keys appear to be:
- **Universal across all Tablo 4th Gen devices**
- **Not device-specific or user-specific**
- **Publicly available in client applications**

### Example Device Request

```http
POST http://192.168.1.50:8885/guide/channels/S122912_503_01/watch?lh
Host: 192.168.1.50:8885
Connection: keep-alive
Date: Sat, 31 Jan 2026 12:00:00 GMT
Accept: */*
User-Agent: Tablo-FAST/1.7.0 (Mobile; iPhone; iOS 18.4)
Content-Type: application/x-www-form-urlencoded
Content-Length: 234
Authorization: tablo:ljpg6ZkwShVv8aI12E2LP55Ep8vq1uYDPvX0DdTB:a1b2c3d4e5f67890...

{"device_id":"uuid","bandwidth":null,"platform":"ios","extra":{...}}
```

### Signature Verification

The device verifies requests by:
1. Extracting the HMAC from the Authorization header
2. Rebuilding the signature input from the request
3. Computing HMAC-MD5 with the same HashKey
4. Comparing the computed HMAC to the provided one

---

## Credential Storage

### What Gets Stored (creds.bin)

```javascript
{
  "lighthousetvAuthorization": "Bearer eyJ...",  // Lighthouse OAuth token
  "lighthousetvIdentifier": "account_id",        // Account identifier
  "profile": {                                   // Selected profile
    "identifier": "...",
    "name": "...",
    "date_joined": "...",
    "preferences": {}
  },
  "device": {                                    // Selected device
    "serverId": "...",
    "name": "Living Room Tablo",
    "type": "tablo_gen4",
    "url": "http://192.168.1.50:8885",          // Local device URL
    "version": "...",
    ...
  },
  "Lighthouse": "session_token",                 // Lighthouse session
  "UUID": "client_device_uuid",                  // Generated client ID
  "tuners": 4                                    // Tuner count
}
```

### Encryption Method

Credentials are encrypted using **AES-256-CBC** with:
- Key derived from XOR operations on an RSA constant
- IV generated using Mersenne Twister PRNG
- Random seed stored as first 4 bytes of file

```
creds.bin format:
┌──────────────────┬──────────────────────────────────┐
│  Seed (4 bytes)  │  AES-256-CBC Encrypted JSON      │
└──────────────────┴──────────────────────────────────┘
```

### What's NOT Stored

- User password (never persisted)
- User email (never persisted)
- Raw HMAC keys (hardcoded, not per-user)

---

## Offline Operation Analysis

### Can the Tablo Device Be Used Without Lighthouse?

**Short Answer: Partially Yes, with limitations.**

### What Requires Lighthouse (Online)

| Operation | Lighthouse Required | Reason |
|-----------|-------------------|--------|
| Initial login | **Yes** | OAuth authentication |
| Device discovery | **Yes** | Device URLs from account |
| Channel lineup | **Yes** | Channel IDs from cloud |
| Guide data | **Yes** | EPG from cloud service |
| Token refresh | **Yes** | Tokens expire |

### What Works Without Lighthouse (Offline)

| Operation | Works Offline | Requirements |
|-----------|--------------|--------------|
| Stream a channel | **Yes** | Device IP, channel ID, HMAC keys |
| Get server info | **Yes** | Device IP, HMAC keys |
| Get tuner count | **Yes** | Device IP, HMAC keys |

### Offline Operation Requirements

To operate the device without Lighthouse, you need:

1. **Device IP Address** - Must be known or discoverable via:
   - mDNS/Bonjour (if device broadcasts)
   - Network scanning
   - Static IP configuration
   - Previous Lighthouse lookup (cached)

2. **Channel Identifiers** - The opaque strings like `S122912_503_01`:
   - Must be cached from previous Lighthouse lookup
   - Format appears to be station-specific

3. **HMAC Keys** - Already public/hardcoded:
   - `HashKey`: `6l8jU5N43cEilqItmT3U2M2PFM3qPziilXqau9ys`
   - `DeviceKey`: `ljpg6ZkwShVv8aI12E2LP55Ep8vq1uYDPvX0DdTB`

### Offline Streaming Diagram

```
┌─────────────┐                              ┌─────────────────┐
│   Client    │                              │  Tablo Device   │
│             │                              │  (Local Only)   │
└──────┬──────┘                              └────────┬────────┘
       │                                              │
       │  POST /guide/channels/{cached_id}/watch      │
       │  Authorization: tablo:{key}:{hmac}           │
       │─────────────────────────────────────────────►│
       │                                              │
       │  { playlist_url: "http://..." }              │
       │◄─────────────────────────────────────────────│
       │                                              │
       │  GET {playlist_url}                          │
       │─────────────────────────────────────────────►│
       │                                              │
       │  HLS Stream                                  │
       │◄─────────────────────────────────────────────│
       │                                              │
       
       ✓ No Lighthouse cloud required for streaming
       ✓ No OAuth tokens needed
       ✓ Only HMAC signing (shared secrets)
```

### Practical Offline Limitations

1. **No Channel Discovery** - You can't get new channel IDs
2. **No Guide Data** - EPG must come from alternative source
3. **Token Expiration** - Lighthouse tokens will eventually expire
4. **No Device Discovery** - Can't find devices on new networks
5. **No Account Features** - Recording schedules, settings, etc.

---

## Security Considerations

### Strengths

| Aspect | Assessment |
|--------|------------|
| Credential encryption | AES-256-CBC with unique IVs |
| Password handling | Never stored, only transmitted during login |
| HTTPS for cloud | TLS encryption for sensitive data |
| Request signing | HMAC prevents request tampering |

### Weaknesses

| Aspect | Concern | Risk Level |
|--------|---------|------------|
| Shared HMAC keys | All devices use same keys | **Medium** |
| HTTP for device | Local traffic unencrypted | **Low** (LAN only) |
| Hardcoded secrets | Keys visible in source code | **Medium** |
| No device-specific auth | Any client with keys can access any device on LAN | **Medium** |

### Shared Key Implications

The universal HMAC keys mean:

1. **Any device on your LAN** that knows the keys can control your Tablo
2. **No per-device secrets** - devices don't have unique credentials
3. **Security relies on network isolation** - don't expose Tablo to internet

### Recommendations

1. **Keep Tablo on trusted network** - Not directly internet-accessible
2. **Use VPN for remote access** - Don't port-forward Tablo
3. **Firewall rules** - Limit Tablo access to known clients
4. **Monitor network** - Watch for unauthorized access attempts

---

## Summary: Authentication by Endpoint

| Endpoint | Service | Auth Method | Requires Cloud |
|----------|---------|-------------|----------------|
| POST /login/ | Lighthouse | None (credentials in body) | Yes |
| GET /account/ | Lighthouse | Bearer Token | Yes |
| POST /account/select/ | Lighthouse | Bearer Token | Yes |
| GET /guide/channels/ | Lighthouse | Bearer + Lighthouse Token | Yes |
| GET /server/info | Device | HMAC-MD5 | **No** |
| POST /watch | Device | HMAC-MD5 | **No** |

---

## Appendix: Key Values Reference

### HMAC Authentication Keys

```
HashKey:   6l8jU5N43cEilqItmT3U2M2PFM3qPziilXqau9ys
DeviceKey: ljpg6ZkwShVv8aI12E2LP55Ep8vq1uYDPvX0DdTB
```

### Credential Encryption Key Derivation

```
RSA Constant (default):
30818902818100B507AAAC6B6B1BA5CE02B8512381159ECFD9CD32D6EEADCAFF
459EA7E2210819C2D915F437E30871DDA190F19B8898038E1E7863A21699CDA5
BC6C84C49D935AFAFFE1D2F16B0C662DC8941D8751FB7A36AC22F5980EDF92FC
F7756FC6FCFD967A73303C7CD7030C681799C18E0A2F2D2B69C9F7BD8ADE0573
1BB179F354F0E90203010001

Derivation: XOR 32-bit words into 256-bit key buffer
```

### Date Format for HMAC

```
RFC 1123: "Sat, 31 Jan 2026 12:00:00 GMT"
```

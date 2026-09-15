# HDHomeRun DeviceID Specification & Generation Guide

This guide details the structure, checksum validation algorithm, model prefixes, and generation methods for SiliconDust HDHomeRun Device IDs used by `tablo2hdhomerun`.

---

## 1. Overview & Format

In the SiliconDust HDHomeRun ecosystem, a **DeviceID** is a 32-bit unsigned integer formatted as an **8-character hexadecimal string** (e.g., `1030001C`, `104A1234`).

### Representation
- **HTTP / JSON API (`/discover.json`)**: Formatted as an 8-character string:
  ```json
  "DeviceID": "1030001C"
  ```
- **UDP Discovery Protocol (Port 65001)**: Encoded as a 4-byte big-endian unsigned integer.
- **Plex Media Server DVR**: Forms the unique identifier for the tuner device:
  ```
  device://tv.plex.grabbers.hdhomerun/<DEVICE_ID>
  ```

---

## 2. Reserved & Special Device IDs

- `00000000`: Null / invalid device ID.
- `FFFFFFFF`: Wildcard broadcast address (used in discovery requests to target all devices on the network).

---

## 3. Hardware Model Prefixes

SiliconDust assigns device IDs with specific 3-hex-digit prefixes (`device_id >> 20`) indicating the hardware platform:

| Prefix (Hex) | Hardware Model | Tuners / Features |
|--------------|----------------|-------------------|
| `100xxxxx` | TECH-US / TECH3-US | Commercial dual tuner |
| `101xxxxx` | HDHR-US | 1st Gen Dual ATSC |
| `102xxxxx` | HDHR-T1-US | 1st Gen DVB-T |
| `103xxxxx` | **HDHR3-US** | **2-tuner ATSC (matches `tablo2hdhomerun` default model)** |
| `104xxxxx` | **HDHR4-2US** | **HDHomeRun CONNECT (2 tuners)** |
| `105xxxxx` | **HDHR4-4US / HDHR5-4US** | **HDHomeRun CONNECT QUATRO (4 tuners)** |
| `106xxxxx` | HDTC-2US | HDHomeRun EXTEND (Hardware H.264 Transcode) |
| `107xxxxx` | HDHR5-4K / HDFX-4K | HDHomeRun CONNECT 4K (ATSC 3.0 + ATSC 1.0) |
| `111xxxxx` | HDHR3-DT | Dual DVB-T |
| `120xxxxx` - `122xxxxx` | TECH3-EU / HDHR-EU / HDHR3-EU | European DVB-T/T2 models |
| `131xxxxx` - `132xxxxx` | HDHR3-CC / HDHR4-CC | HDHomeRun PRIME (CableCARD) |

---

## 4. SiliconDust Checksum Validation Algorithm

Official SiliconDust firmware, the `libhdhomerun` C library (`hdhomerun_discover.c`), and official HDHomeRun apps enforce a **4-bit nibble permutation checksum** across the 8 hex digits ($D_7 D_6 D_5 D_4 D_3 D_2 D_1 D_0$) to prevent mistyped IDs and digit transpositions:

### The Lookup Table
```c
static uint8_t lookup_table[16] = {
    0xA, 0x5, 0xF, 0x6, 0x7, 0xC, 0x1, 0xB,
    0x9, 0x2, 0x8, 0xD, 0x4, 0x3, 0xE, 0x0
};
```

### The Validation Logic
```c
bool hdhomerun_discover_validate_device_id(uint32_t device_id)
{
    uint8_t checksum = 0;

    checksum ^= lookup_table[(device_id >> 28) & 0x0F];
    checksum ^= (device_id >> 24) & 0x0F;
    checksum ^= lookup_table[(device_id >> 20) & 0x0F];
    checksum ^= (device_id >> 16) & 0x0F;
    checksum ^= lookup_table[(device_id >> 12) & 0x0F];
    checksum ^= (device_id >> 8) & 0x0F;
    checksum ^= lookup_table[(device_id >> 4) & 0x0F];
    checksum ^= (device_id >> 0) & 0x0F;

    return (checksum == 0);
}
```

### Check-Digit Formula
Because the final nibble $D_0$ is XORed directly with the accumulated checksum:
$$D_0 = \text{lookup\_table}[D_7] \oplus D_6 \oplus \text{lookup\_table}[D_5] \oplus D_4 \oplus \text{lookup\_table}[D_3] \oplus D_2 \oplus \text{lookup\_table}[D_1]$$

Any 7 hex digits ($D_7 \dots D_1$) uniquely determine a single valid 8th hex digit ($D_0$).

---

## 5. Generating a Unique Valid DeviceID

### Python Generator Script
Run this script to generate a random, fully compliant SiliconDust DeviceID with the proper model prefix and check digit:

```bash
python3 -c '
import os

T = [0xA, 0x5, 0xF, 0x6, 0x7, 0xC, 0x1, 0xB, 0x9, 0x2, 0x8, 0xD, 0x4, 0x3, 0xE, 0x0]

# Choose model prefix (e.g. 103 for HDHR3-US, 104 for CONNECT, 105 for QUATRO)
# plus 4 random hexadecimal characters:
prefix7 = "103" + os.urandom(2).hex().upper()

# Compute check digit:
val = int(prefix7, 16) << 4
c = T[(val >> 28) & 0xF] ^ ((val >> 24) & 0xF) ^ \
    T[(val >> 20) & 0xF] ^ ((val >> 16) & 0xF) ^ \
    T[(val >> 12) & 0xF] ^ ((val >> 8) & 0xF) ^ \
    T[(val >> 4) & 0xF]

device_id = f"{prefix7}{c:X}"
print(f"Generated HDHomeRun DeviceID: {device_id}")
'
```

### Pre-Computed Valid Examples
You can pick any of these valid Device IDs directly:
- `1030001C` (HDHR3-US 2-tuner series)
- `1030002A` (HDHR3-US 2-tuner series)
- `1040001D` (HDHR4 CONNECT series)
- `10500011` (HDHR4/5 QUATRO 4-tuner series)
- `12345674` (SiliconDust checksum-compliant version of the default `12345678`)

---

## 6. Configuring `tablo2hdhomerun`

### Docker Compose
In your `docker-compose.yml`:
```yaml
services:
  tablo2hdhomerun:
    image: tablo2hdhomerun:latest
    environment:
      - DEVICE_ID=1030001C
```

### Shell / Docker Run
```bash
docker run -d \
  -e DEVICE_ID=1030001C \
  ...
```

---

## 7. Important Considerations for Plex DVR

> [!IMPORTANT]
> **Existing Channel Mappings & Pairings**:
> Plex identifies tuners by their URI: `device://tv.plex.grabbers.hdhomerun/<DEVICE_ID>`.
> - `tablo2hdhomerun` defaults to `DEVICE_ID=12345678` specifically to preserve existing Plex DVR pairings without requiring channel remaps.
> - If you change `DEVICE_ID`, Plex will detect the proxy as a **new tuner device**.
> - You will need to navigate to **Plex Settings > Live TV & DVR**, set up the new tuner, and remove the previous tuner entry.

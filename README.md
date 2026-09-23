<div align="center">

# Telepad

An Android app that turns your phone into a trackpad and keyboard for Windows over Wi-Fi or Bluetooth.

[![CI Status](https://github.com/omsingh02/telepad/actions/workflows/ci.yml/badge.svg)](https://github.com/omsingh02/telepad/actions/workflows/ci.yml)
[![Latest Release](https://img.shields.io/github/v/release/omsingh02/telepad?color=blue&label=release)](https://github.com/omsingh02/telepad/releases/latest)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
[![Rust 1.80+](https://img.shields.io/badge/rust-1.80%2B-orange.svg)](https://www.rust-lang.org/)
[![Android API 28+](https://img.shields.io/badge/android-API%2028%2B-green.svg)](https://developer.android.com)

[Features](#features) •
[Protocol & Architecture](#protocol--architecture) •
[Gesture Mapping](#gesture-mapping) •
[Quick Start](#quick-start) •
[Building from Source](#building-from-source) •
[Troubleshooting](#troubleshooting)

</div>

---

## Overview

Telepad consists of two components:
1. **Android Client** (Kotlin / Jetpack Compose): Captures touch gestures, key presses, and media actions, then sends them over UDP or Bluetooth.
2. **Desktop Server** (Rust): Receives UDP packets, decrypts them, and injects mouse and keyboard events directly into the Windows input queue via Win32 `SendInput`.

### Connection Modes

- **Wi-Fi Mode**:
  - Uses UDP over LAN (default port `5000`).
  - End-to-end encrypted using the **Noise IK** handshake (`Noise_IK_25519_ChaChaPoly_BLAKE2s`).
  - Uses Trust-On-First-Use (TOFU): on the first connection, the client displays a 12-character fingerprint derived from the server's public key. Once verified, subsequent connections authenticate automatically.
  - Automatic discovery via IPv4 multicast (`239.255.42.67:5000`) and subnet broadcasts.
- **Bluetooth Mode**:
  - Uses the Android `BluetoothHidDevice` API.
  - The phone acts as a standard Bluetooth human interface device (mouse and keyboard).
  - Does not require running the desktop server executable.

---

## Downloads

Standalone pre-built binaries are available under [**Releases**](https://github.com/omsingh02/telepad/releases/latest):

| Component | Target Platform | File |
| :--- | :--- | :--- |
| **Android Client** | Android 9.0+ (API 28+) | `telepad-android-release.apk` |
| **Desktop Server** | Windows 10 / 11 (x86_64) | `telepad-server-windows-x86_64.exe` |

The Windows server is a single portable executable with no external runtime dependencies.

---

## Features

### Mouse & Trackpad
- **Tracking**: Motion delta tracking with sub-pixel float accumulation to avoid truncation errors on slow finger movement.
- **Sensitivity Curves**:
  - `Linear`: 1:1 direct mapping (default).
  - `Windows`: Polynomial curve matching Windows Enhanced Pointer Precision.
  - `macOS`: Cubic acceleration curve.
  - `Flat`: Fixed sensitivity regardless of swipe speed.
- **Gestures**:
  - Single-finger move: cursor movement.
  - Single-finger tap: left click.
  - Two-finger tap: right click.
  - Double-tap and drag: left click hold and drag.
  - Two-finger drag: scroll wheel (supports natural/inverted toggle).
  - Long press: right click.
- **On-Screen Buttons**: Optional dedicated physical Left and Right click buttons.

### Keyboard & Remote
- **Modifier Keys**: Toggleable sticky modifiers (<kbd>Ctrl</kbd>, <kbd>Shift</kbd>, <kbd>Alt</kbd>, <kbd>Win</kbd>).
- **Navigation Cluster**: Dedicated arrow keys, <kbd>Insert</kbd>, <kbd>Delete</kbd>, <kbd>Home</kbd>, <kbd>End</kbd>, <kbd>Page Up</kbd>, and <kbd>Page Down</kbd> injected with `KEYEVENTF_EXTENDEDKEY`.
- **Function Row**: Toggleable <kbd>F1</kbd>–<kbd>F12</kbd>, <kbd>Esc</kbd>, <kbd>Tab</kbd>, and <kbd>Caps Lock</kbd>.
- **Media Controls**: <kbd>Play/Pause</kbd>, <kbd>Next</kbd>, <kbd>Prev</kbd>, volume controls, and live Windows Now Playing metadata display (title, artist, album).
- **Clipboard Sync**: Two-way text clipboard transfer between phone and PC over the encrypted Wi-Fi channel.
- **Presentation Controls**: Large tap zones for <kbd>Page Up</kbd>, <kbd>Page Down</kbd>, and black screen (<kbd>B</kbd>).
- **Quick Shortcuts**: One-tap triggers for Copy, Paste, Cut, Undo, Redo, Alt+Tab, Task View, and Show Desktop.

---

## Gesture Mapping

| Gesture | Injected Win32 Input | Win32 Flags |
| :--- | :--- | :--- |
| **1-finger move** | Mouse move | `MOUSEEVENTF_MOVE` |
| **1-finger tap** | Left click | `MOUSEEVENTF_LEFTDOWN` then `MOUSEEVENTF_LEFTUP` |
| **2-finger tap** | Right click | `MOUSEEVENTF_RIGHTDOWN` then `MOUSEEVENTF_RIGHTUP` |
| **Double-tap & hold** | Left drag | `MOUSEEVENTF_LEFTDOWN` held until finger release |
| **2-finger vertical drag** | Scroll wheel | `MOUSEEVENTF_WHEEL` (multiplied by `WHEEL_DELTA = 120`) |
| **Long press** | Right click | `MOUSEEVENTF_RIGHTDOWN` then `MOUSEEVENTF_RIGHTUP` |

---

## Protocol & Architecture

Telepad uses a custom compact binary protocol over UDP.

```
+---------------------------------------------------------------------------------+
|                              CONNECTION LIFECYCLE                               |
+---------------------------------------------------------------------------------+

   ANDROID CLIENT                                           WINDOWS SERVER (RUST)
         |                                                           |
         | 1. Multicast Discovery (239.255.42.67:5000)               |
         |---------------------------------------------------------->|
         |    [0xC3][Magic: 8 bytes]                                 |
         |                                                           |
         | 2. Discovery Reply                                        |
         |<----------------------------------------------------------|
         |    [0xC4] + "TELEPAD_PONG:<Hostname>"                     |
         |                                                           |
         | 3. Pairing Request (First connection only)                |
         |---------------------------------------------------------->|
         |    [0xC5]                                                 |
         |<----------------------------------------------------------|
         |    [0xC6] + [32-byte X25519 static public key]            |
         |                                                           |
         |    [TOFU: Compare 12-char fingerprint against PC console] |
         |                                                           |
         | 4. Noise IK Handshake                                     |
         |---------------------------------------------------------->|
         |    Msg 1 [0xC0] + [96 bytes: e, es, s, ss]                |
         |<----------------------------------------------------------|
         |    Msg 2 [0xC1] + [48 bytes: e, ee, se]                   |
         |                                                           |
         | 5. Encrypted Transport Stream (ChaCha20-Poly1305)         |
         |==========================================================>|
         |    [0xC2] + [Encrypted payload + 16-byte Poly1305 tag]    |
         |                                                           |
         |                                                6. Win32 SendInput
         +                                                           +
```

### Protocol Specifications

- **Cipher Suite**: `Noise_IK_25519_ChaChaPoly_BLAKE2s`
  - DH: X25519
  - Cipher: ChaCha20-Poly1305 (16-byte authentication tag)
  - Hash: BLAKE2s
- **Wire Framing** (first byte of every UDP datagram):
  - `0xC0`: `WIRE_HANDSHAKE_INIT` (96 bytes)
  - `0xC1`: `WIRE_HANDSHAKE_RESP` (48 bytes)
  - `0xC2`: `WIRE_TRANSPORT` (encrypted payload)
  - `0xC3`: `WIRE_DISCOVERY_PROBE` (magic: `0x54, 0xE7, 0x9A, 0x03, 0x21, 0xC8, 0xBE, 0xFE` or `b"TELEPAD!"`)
  - `0xC4`: `WIRE_DISCOVERY_REPLY` (`"TELEPAD_PONG:<hostname>"`)
  - `0xC5`: `WIRE_PAIRING_INTRO_REQ`
  - `0xC6`: `WIRE_PAIRING_INTRO_RESP` (32 bytes public key)
- **Transport Payload Framing** (inside encrypted stream):
  - `0x01`: MouseMove (`[i16 dx][i16 dy]`, little-endian)
  - `0x02`: MouseButton (`[u8 button][u8 pressed]`)
  - `0x03`: Scroll (`[i16 delta]`)
  - `0x04`: KeyPress (`[u16 keycode][u8 modifiers]`)
  - `0x05`: KeyRelease (`[u16 keycode][u8 modifiers]`)
  - `0x06`: TextInput (`[u16 len][utf-8 bytes]`)
  - `0x07`: MediaCmd (`[u8 action]`)
  - `0x08`: VolumeCmd (`[u8 direction]`)
  - `0x09`: LockScreen
  - `0x0E`: ClipboardGet
  - `0x0F`: ClipboardSet (`[u16 len][utf-8 bytes]`)
  - `0x10`: LaunchAction (`[u8 action]`)
  - `0x11`: NowPlayingQuery
  - `0x80`: ClipboardData (`[u16 len][utf-8 bytes]`)
  - `0x81`: NowPlaying (`[flags][pos: i64][dur: i64][strings...]`)
- **Fingerprint Calculation**:
  - `SHA256(server_static_public_key)[0..6]` formatted as hex pairs: `XXYY · XXYY · XXYY`.
- **Key Storage**:
  - Windows: `%APPDATA%\Telepad\identity.key` and `trusted_clients.json`.
  - Android: `EncryptedSharedPreferences` backed by Android Keystore.

---

## Quick Start

### Wi-Fi Mode

1. **Start the Desktop Server**:
   Run `telepad-server-windows-x86_64.exe` on your Windows PC:
   ```cmd
   telepad-server-windows-x86_64.exe
   ```
   The server will print its identity and fingerprint:
   ```
   ==================================================
    Telepad Desktop Server v2.0.0 (Rust)
    Port:        5000
    Hostname:    DESKTOP-PC
    Fingerprint: 7F2A · B9C1 · 4E08
   ==================================================
   ```

2. **Connect from Android**:
   - Connect phone to the same local network.
   - Open Telepad. Discovered PCs appear in the list automatically.
   - Tap your PC.

3. **Verify Fingerprint (First Connection Only)**:
   - Check that the fingerprint on your phone matches the server console.
   - Tap **Trust this PC**. Subsequent connections pair automatically.

### Bluetooth Mode

> **Requirement**: Android 9.0+ with firmware support for the Bluetooth HID Device profile (`BluetoothHidDevice`). Some OEM builds disable this profile in software.

1. Open Android **Settings** $\rightarrow$ **Connected devices** $\rightarrow$ **Pair new device**.
2. Put your PC into Bluetooth discovery mode and complete pairing from Android settings.
3. Open Telepad $\rightarrow$ Switch to **Bluetooth Mode** $\rightarrow$ Tap your PC.

---

## Building from Source

### Prerequisites
- **Rust**: 1.80+ (`rustup toolchain install stable`)
- **Android**: JDK 17+, Android SDK 35

### 1. Build Desktop Server (Rust)

```bash
git clone https://github.com/omsingh02/telepad.git
cd telepad

cargo build --release --bin telepad-server
# Executable: target/release/telepad-server.exe
```

Run test suite:
```bash
cargo test --workspace
```

### 2. Build Android App (Kotlin / Compose)

```bash
cd android

# Build debug APK:
./gradlew assembleDebug
# Generated at: app/build/outputs/apk/debug/app-debug.apk

# Build release APK:
./gradlew assembleRelease

# Run unit tests:
./gradlew testDebugUnitTest
```

---

## Troubleshooting

### PC not found during auto-discovery
1. **Windows Firewall**: If Windows Firewall blocks UDP traffic, allow inbound UDP on port 5000:
   ```powershell
   New-NetFirewallRule -DisplayName "Telepad Server" -Direction Inbound -LocalPort 5000 -Protocol UDP -Action Allow
   ```
2. **Access Point Isolation**: Some guest Wi-Fi networks and mesh routers block device-to-device UDP traffic.
3. **Manual IP Connection**: Tap **Add Device Manually** in the app and enter your PC's local IP address directly (find it via `ipconfig` on Windows).

### Cursor feels jittery or lags
- **Curve Selection**: Check Settings $\rightarrow$ **Sensitivity & Ballistics**. `Linear` provides 1:1 hardware movement without synthetic acceleration.
- **Battery Optimization**: On Android, set Telepad battery usage to **Unrestricted** (`Settings` $\rightarrow$ `Apps` $\rightarrow$ `Telepad` $\rightarrow$ `Battery`) to prevent background thread throttling.

### Fingerprint Mismatch Warning
- If the server key was deleted or regenerated (e.g. server moved to a new machine with the same hostname), the app warns of a fingerprint mismatch to prevent Man-in-the-Middle attacks.
- Open **Settings** $\rightarrow$ **Paired Devices** on your phone, delete the old server entry, and reconnect to trust the new key.

---

## Project Structure

```
telepad/
├── android/                    # Android client (Kotlin + Jetpack Compose)
│   ├── app/src/main/           # UI, ViewModels, and Network/HID drivers
│   └── app/libs/               # Bundled Noise protocol library
├── crates/                     # Rust Desktop Server Workspace
│   ├── telepad-server/         # Tokio UDP listener, Win32 SendInput injection
│   ├── telepad-crypto/         # Noise IK handshake, ChaCha20-Poly1305, key storage
│   └── telepad-protocol/       # Binary wire protocol definitions and codecs
├── .agents/                    # Automation and device testing scripts
├── Cargo.toml                  # Cargo workspace
└── README.md
```

---

## License

Distributed under the [MIT License](LICENSE).


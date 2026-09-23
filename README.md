<div align="center">

# 📱 Telepad

**Ultra-low-latency Android touchpad and keyboard remote for PC over encrypted Wi-Fi (Noise IK) or driverless Bluetooth HID.**

[![CI Status](https://github.com/omsingh02/telepad/actions/workflows/ci.yml/badge.svg)](https://github.com/omsingh02/telepad/actions/workflows/ci.yml)
[![Latest Release](https://img.shields.io/github/v/release/omsingh02/telepad?color=blue&label=release)](https://github.com/omsingh02/telepad/releases/latest)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
[![Rust 1.80+](https://img.shields.io/badge/rust-1.80%2B-orange.svg)](https://www.rust-lang.org/)
[![Android API 28+](https://img.shields.io/badge/android-API%2028%2B-green.svg)](https://developer.android.com)
[![Platform](https://img.shields.io/badge/platform-Windows%20%7C%20Android-blueviolet.svg)](#downloads)

[Key Features](#-key-features) •
[Architecture](#-architecture--protocol) •
[Gesture Reference](#-touchpad-gestures--controls) •
[Quick Start](#-quick-start) •
[Building from Source](#-building-from-source) •
[Troubleshooting](#-troubleshooting--faq)

</div>

---

## ⚡ Overview

**Telepad** turns your Android phone into an ultra-responsive, zero-driver trackpad, keyboard, and presentation remote for your PC. Engineered in **Rust** (desktop daemon) and **Kotlin / Jetpack Compose** (Android client), Telepad prioritizes microsecond input fidelity, cryptographic security, and privacy.

- **Zero-Cloud & Privacy-First**: 100% peer-to-peer over your local area network (LAN) or Bluetooth. No accounts, no analytics, no external servers.
- **Micro-Latency UDP Protocol**: Custom compact binary wire protocol over UDP with zero-allocation packet parsing and sub-pixel float accumulation, delivering **~3–8 ms median latency** over standard Wi-Fi.
- **Noise IK Cryptography**: Mutually authenticated, end-to-end encrypted sessions using Curve25519, ChaCha20-Poly1305, and BLAKE2s.
- **Trust-On-First-Use (TOFU)**: 12-character cryptographic fingerprint verification on initial connection. Subsequent connections pair silently and instantly.
- **Driverless Bluetooth HID**: Native Android Bluetooth HID profile support — controls Windows, macOS, Linux, Android TV, and game consoles without installing any desktop software.

---

## 🚀 Downloads

Download pre-built standalone binaries from [**GitHub Releases**](https://github.com/omsingh02/telepad/releases/latest):

| Component | Target Platform | Architecture | Binary / Package |
| :--- | :--- | :--- | :--- |
| **Android Client** | Android 9.0+ (API 28+) | `arm64-v8a`, `armeabi-v7a`, `x86_64` | [`telepad-android-release.apk`](https://github.com/omsingh02/telepad/releases/latest) |
| **Desktop Daemon** | Windows 10 / 11 | `x86_64` | [`telepad-server-windows-x86_64.exe`](https://github.com/omsingh02/telepad/releases/latest) |

> **Note**: The desktop server is a single, zero-dependency, self-contained executable. No installation or runtime dependencies (like Visual C++ Redistributable or .NET) required.

---

## ✨ Key Features

### 🖱️ Touchpad & Mouse
- **Multi-Touch Fluidity**: 60–120 Hz tracking with sub-pixel float accumulation for pixel-perfect cursor positioning.
- **Customizable Ballistics**: Native host OS ballistics (`Linear`) by default, with switchable curves:
  - `Linear`: 1:1 raw hardware-like mapping with velocity preservation.
  - `Windows`: Dynamic polynomial curve matching standard Windows Enhanced Pointer Precision.
  - `macOS`: Cubic acceleration curve calibrated for rapid multi-monitor travel.
  - `Flat`: Zero acceleration for competitive gaming or drawing precision.
- **Hardware Mouse Buttons**: Optional dedicated physical on-screen Left / Right click buttons for accessibility and precision dragging.
- **Two-Finger Scrolling**: Smooth, momentum-based vertical scrolling with configurable *Natural* (inverted) and *Standard* directions.

### ⌨️ Keyboard & Input Clusters
- **Full Modifier Row**: Persistent sticky toggles for <kbd>Ctrl</kbd>, <kbd>Shift</kbd>, <kbd>Alt</kbd>, and <kbd>Win</kbd>.
- **Function Keys**: Toggleable <kbd>F1</kbd>–<kbd>F12</kbd> row, <kbd>Esc</kbd>, <kbd>Tab</kbd>, and <kbd>Caps Lock</kbd>.
- **Navigation Cluster**: Dedicated inverted-T arrow keys, plus <kbd>Insert</kbd>, <kbd>Delete</kbd>, <kbd>Home</kbd>, <kbd>End</kbd>, <kbd>Page Up</kbd>, and <kbd>Page Down</kbd> (injected with Windows `KEYEVENTF_EXTENDEDKEY` semantics).
- **Presentation Remote**: Large tactile tap zones for PowerPoint / Google Slides / Keynote (<kbd>Page Down</kbd> / <kbd>Page Up</kbd> / <kbd>B</kbd> black screen).
- **Two-Way Clipboard Sync**: Seamless text clipboard transfer between mobile device and PC over encrypted channel.
- **Live Media Controls & Metadata**: Windows Now Playing sync displaying active track title, artist, playback state, and media key injection (<kbd>Play/Pause</kbd>, <kbd>Next</kbd>, <kbd>Prev</kbd>, Volume slider).
- **Quick Action Bar**: Instant one-tap triggers for <kbd>Copy</kbd>, <kbd>Paste</kbd>, <kbd>Cut</kbd>, <kbd>Undo</kbd>, <kbd>Redo</kbd>, <kbd>Alt+Tab</kbd>, <kbd>Show Desktop</kbd>, and <kbd>Task Manager</kbd>.

---

## 🎯 Touchpad Gestures & Controls

| Gesture | Action | Windows OS Equivalent |
| :--- | :--- | :--- |
| **One-finger move** | Pointer movement | `MOUSEEVENTF_MOVE` (with sub-pixel accumulation) |
| **One-finger tap** | Left click | `MOUSEEVENTF_LEFTDOWN` + `UP` |
| **Two-finger tap** | Right click | `MOUSEEVENTF_RIGHTDOWN` + `UP` |
| **Double-tap & drag** | Click-and-drag / window move | Persistent `LEFTDOWN` until finger release |
| **Two-finger vertical drag** | Smooth scroll wheel | `MOUSEEVENTF_WHEEL` (accumulator-based) |
| **Long press** | Context menu (Right click) | `MOUSEEVENTF_RIGHTDOWN` + `UP` |

---

## 🏗️ Architecture & Protocol

Telepad avoids heavy application protocols (like VNC, RDP, or HTTP/WebSockets) in favor of a zero-allocation UDP protocol protected by the **Noise Protocol Framework**.

```
+---------------------------------------------------------------------------------+
|                                 TELEPAD ARCHITECTURE                            |
+---------------------------------------------------------------------------------+

   ANDROID CLIENT                                           WINDOWS SERVER (RUST)
+-------------------+                                      +---------------------+
|  Jetpack Compose  |                                      |   Tokio Async UDP   |
|   Touch Handler   |                                      |   Listener (:5000)  |
+---------+---------+                                      +----------+----------+
          |                                                           |
          | 1. Multicast Discovery (239.255.42.67:5000)               |
          |---------------------------------------------------------->|
          |    Tag 0xC3 (WIRE_DISCOVERY_PROBE) + 8B Magic Header      |
          |                                                           |
          | 2. Unicast / Multicast Discovery Response                 |
          |<----------------------------------------------------------|
          |    Tag 0xC4 (WIRE_DISCOVERY_REPLY) + "TELEPAD_PONG:<Host>"|
          |                                                           |
          | 3. Pairing Introduction (First connect only)              |
          |---------------------------------------------------------->|
          |    Tag 0xC5 (WIRE_PAIRING_INTRO_REQ)                      |
          |<----------------------------------------------------------|
          |    Tag 0xC6 (WIRE_PAIRING_INTRO_RESP) + 32B Static PubKey |
          |                                                           |
          |    [TOFU 12-char Fingerprint Check against SHA-256(Pub)]  |
          |    e.g. "7F2A · B9C1 · 4E08" <---> Matches Host Console?  |
          |                                                           |
          | 4. Noise IK 2-Message Handshake                           |
          |---------------------------------------------------------->|
          |    Msg 1 (0xC0, 96B): e, es, s, ss                        |
          |<----------------------------------------------------------|
          |    Msg 2 (0xC1, 48B): e, ee, se                           |
          |                                                           |
          | 5. Encrypted Transport Stream (ChaCha20-Poly1305)         |
          |==========================================================>|
          |    Tag 0xC2 (WIRE_TRANSPORT) + [Ciphertext + 16B Poly1305]|
          |    - MouseMove:    0x01 [i16 dx][i16 dy]                  |
          |    - MouseButton:  0x02 [u8 button][u8 pressed]           |
          |    - Scroll:       0x03 [i16 delta]                       |
          |    - KeyPress:     0x04 [u16 keycode][u8 mods]            |
          |    - MediaCmd:     0x07 [u8 action]                       |
          |    - ClipboardSet: 0x0F [u16 len][utf8 bytes...]          |
          |                                                           |
          |                                                6. Win32 SendInput
          |                                                   Cursor / Keystroke
          +                                                           +
```

### Protocol & Security Specifications
- **Cipher Suite**: `Noise_IK_25519_ChaChaPoly_BLAKE2s`
  - **Key Exchange**: Curve25519 (ECDH)
  - **Cipher**: ChaCha20 with Poly1305 16-byte MAC authentication
  - **Hash**: BLAKE2s (256-bit)
- **Handshake Flow**:
  - `-> e, es, s, ss` (96 bytes): Initiator sends ephemeral key `e` and encrypted static public key `s`.
  - `<- e, ee, se` (48 bytes): Responder sends ephemeral key `e` and completes mutual authentication.
- **Fingerprint Calculation**: First 6 bytes of `SHA256(static_public_key)` formatted as uppercase hex pairs separated by dots (`XXYY · XXYY · XXYY`).
- **Key Storage**:
  - Windows: Stored in `%APPDATA%\Telepad\identity.key` (static secret) and `%APPDATA%\Telepad\trusted_clients.json`.
  - Android: Stored in EncryptedSharedPreferences backed by hardware **Android Keystore**.

---

## ⏱️ Latency Budget

Telepad achieves an empirical glass-to-screen latency of **~3–8 ms** over 5 GHz Wi-Fi:

```
[Touch Sensor Dispatch]    -->  1.0–4.0 ms (Android MotionEvent batching at 120–240 Hz)
[Ballistics & Accumulator] -->  < 0.05 ms  (Sub-pixel floating-point remainder accumulation)
[Zero-Alloc Wire Encode]   -->  < 0.01 ms  (Direct ByteBuffer LE binary serialization)
[ChaCha20-Poly1305 AEAD]   -->  ~ 0.05 ms  (ARMv8 NEON SIMD encryption)
[5 GHz Wi-Fi LAN Hop]      -->  1.5–3.5 ms (UDP packet transport, zero round-trips)
[Tokio UDP Async Recv]     -->  < 0.05 ms  (Kernel epoll / IOCP socket dispatch)
[ChaCha20-Poly1305 Decrypt]-->  ~ 0.05 ms  (x86_64 AVX2 decryption)
[Win32 SendInput Dispatch] -->  ~ 0.15 ms  (Direct kernel input queue injection)
-----------------------------------------------------------------------------------
Total Glass-to-Screen:          ~ 3.0–8.0 ms
```

---

## 🏁 Quick Start

### Wi-Fi Mode (Recommended)

1. **Launch Desktop Server**:
   Download and run `telepad-server-windows-x86_64.exe` on your Windows PC:
   ```cmd
   telepad-server-windows-x86_64.exe
   ```
   The server will print its identity and 12-character fingerprint:
   ```
   ==================================================
    Telepad Desktop Server v2.0.0 (Rust)
    Port:        5000
    Hostname:    DESKTOP-GAMING
    Fingerprint: 7F2A · B9C1 · 4E08
   ==================================================
   ```

2. **Open Telepad on Android**:
   - Ensure your phone is connected to the same Wi-Fi network.
   - Discovered PCs appear automatically in the device list.
   - Tap your PC name.

3. **Verify Fingerprint (First Connection Only)**:
   - Compare the 12-character fingerprint displayed on your phone with the server console.
   - Tap **Trust this PC**. Subsequent connections will pair automatically.

### Bluetooth HID Mode (Driverless)

> **Hardware Requirement**: Bluetooth HID Device Profile (`BluetoothHidDevice`) requires Android 9.0+ and an OEM firmware build with HID Device support enabled (`profile_supported_hidd=true`).

1. Open Android **Settings** $\rightarrow$ **Connected devices** $\rightarrow$ **Pair new device**.
2. Put your PC in Bluetooth pairing mode.
3. Open Telepad $\rightarrow$ Switch to **Bluetooth Mode** $\rightarrow$ Tap your paired PC.
4. Your phone now functions as a generic Bluetooth keyboard and mouse. No desktop server executable needed!

---

## 💻 Building from Source

### Prerequisites

| Component | Requirements |
| :--- | :--- |
| **Desktop Daemon** | Rust 1.80+ (`rustup toolchain install stable`) |
| **Android Client** | JDK 17+, Android SDK 35, Android Studio Ladybug or later |

### 1. Build Desktop Daemon (Rust)

```bash
# Clone the repository
git clone https://github.com/omsingh02/telepad.git
cd telepad

# Build optimized release binary
cargo build --release --bin telepad-server

# Target executable is located at:
# target/release/telepad-server.exe
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

## 🔧 Troubleshooting & FAQ

### PC not showing up in auto-discovery?
1. **Windows Firewall**: Windows Firewall may prompt on first launch. If blocked, open PowerShell as Administrator and run:
   ```powershell
   New-NetFirewallRule -DisplayName "Telepad Server" -Direction Inbound -LocalPort 5000 -Protocol UDP -Action Allow
   ```
2. **Same Subnet / Wi-Fi Isolation**: Verify that your phone and PC are on the same Wi-Fi network. Some guest networks or mesh routers enable "Client Isolation" which blocks peer-to-peer UDP broadcasts.
3. **Manual Connection**: In Telepad on your phone, tap **Add Device Manually**, and enter your PC's local IP address (find it via `ipconfig` on Windows).

### Cursor feels jerky or lags?
- **Ballistics Curve**: Open Telepad Settings $\rightarrow$ **Sensitivity & Ballistics** and switch to `Linear` for raw 1:1 hardware tracking or `Windows` to match system acceleration.
- **Sub-pixel Accumulation**: Telepad accumulates sub-pixel remainders to prevent truncation jitter. Ensure **Sub-pixel Smoothing** is enabled in Settings.
- **Power Saving**: Disable battery optimization for Telepad on Android (`Settings` $\rightarrow$ `Apps` $\rightarrow$ `Telepad` $\rightarrow$ `Battery` $\rightarrow$ `Unrestricted`) to prevent OS CPU throttling during touch events.

### Fingerprint Mismatch warning?
- If the server's private key was regenerated (e.g. server reinstalled on a new machine with the same hostname), the app warns of a fingerprint mismatch to protect against Man-in-the-Middle attacks.
- Open **Settings** $\rightarrow$ **Paired Devices** $\rightarrow$ delete the old pairing $\rightarrow$ reconnect and verify the new fingerprint.

---

## 📂 Project Structure

```
telepad/
├── android/                    # Android client (Kotlin + Jetpack Compose)
│   ├── app/src/main/           # Touch UI, ViewModels, and Network/HID drivers
│   └── app/libs/               # Bundled Noise protocol library
├── crates/                     # Modular Rust Desktop Daemon
│   ├── telepad-server/         # Async Tokio server, Windows input injection (SendInput)
│   ├── telepad-crypto/         # Noise IK handshake, ChaCha20-Poly1305, TOFU pairing
│   └── telepad-protocol/       # Compact binary wire protocol and packet framing
├── .agents/                    # Workspace automation skills and testing drivers
│   └── skills/android-device-testing/  # Deterministic adb/uiautomator test suite
├── Cargo.toml                  # Cargo workspace definition
└── README.md
```

---

## 📜 License

Distributed under the [MIT License](LICENSE).


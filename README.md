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

Telepad avoids heavy protocols (like VNC, RDP, or HTTP/WebSockets) in favor of a zero-allocation UDP protocol protected by the **Noise Protocol Framework**.

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
          | 1. Multicast Discovery (239.255.0.1:5001)                 |
          |---------------------------------------------------------->|
          |    "TELEPAD_DISCOVER_V1"                                  |
          |                                                           |
          | 2. Unicast Discovery Response                             |
          |<----------------------------------------------------------|
          |    Hostname, Port, Static X25519 PubKey                   |
          |                                                           |
          | 3. Noise IK Handshake (Curve25519 + ChaChaPoly + BLAKE2s) |
          |==========================================================>|
          |    Stage 1: e, es, s, ss (Client sends ephemeral + static)|
          |                                                           |
          |    [First Connect: TOFU 12-char Fingerprint Check]        |
          |    "7F2A · B9C1 · 4E08" <---> Matches Host Console?       |
          |                                                           |
          | 4. Encrypted UDP Input Stream (ChaCha20-Poly1305)         |
          |==========================================================>|
          |    Packet Format: [Seq: u32][Payload: N bytes][MAC: 16B]   |
          |    - Motion (dx, dy float32)                              |
          |    - Button & Modifier Bitmasks                           |
          |    - Key Injection & Clipboard                            |
          |                                                           |
          |                                                5. Win32 SendInput
          |                                                   Cursor / Keystroke
          +                                                           +
```

### Security Details
- **Pattern**: `Noise_IK_25519_ChaChaPoly_BLAKE2s`
  - **I** (Identity known): Client initiates handshake with the server's static key obtained during discovery.
  - **K** (Known key): Both parties verify public keys against their local trust store.
- **Replay Protection**: Monotonically increasing 32-bit packet sequence counter with sliding window replay rejection.
- **Key Storage**: Trusted keys stored on Android using the hardware-backed **Android Keystore**, and on Windows under `%APPDATA%\Telepad\trusted_clients.json`.

---

## ⏱️ Latency Budget

Telepad achieves an end-to-end glass-to-screen latency of **~3–8 ms** over a 5 GHz Wi-Fi connection:

```
[Touch Sensor Sample]  -->  1.0 ms  (Android MotionEvent dispatch at 120 Hz)
[Ballistics & Accum]   -->  0.1 ms  (Sub-pixel floating point math)
[Zero-Alloc Encoding]  -->  0.05 ms (Binary wire protocol serialization)
[Noise Encryption]     -->  0.2 ms  (ChaCha20-Poly1305 on ARM NEON)
[Wi-Fi 5/6 LAN Packet] -->  1.5–4 ms(UDP LAN transport, 0 round-trips)
[Server Decrypt & Parse]--> 0.1 ms  (ChaCha20-Poly1305 on x86_64 AVX2)
[Win32 SendInput]      -->  0.2 ms  (Direct kernel input queue injection)
---------------------------------------------------------------------------
Total Glass-to-Input:       ~3.2–6.0 ms
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
   New-NetFirewallRule -DisplayName "Telepad Server" -Direction Inbound -LocalPort 5000,5001 -Protocol UDP -Action Allow
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


# Telepad — Full Architecture Rewrite & Restructure Plan

## Executive Summary

Telepad's current architecture is a functional proof-of-concept composed of a Win32 C99 desktop server and an Android Kotlin/Jetpack Compose client. While it successfully demonstrates low-latency Wi-Fi input via Noise IK encryption and Bluetooth HID, the system faces severe scalability, maintainability, security, and cross-platform limitations:

1. **Language & Ecosystem Divergence:** Maintaining separate C99 and Kotlin implementations of the protocol, serialization, and cryptographic logic doubles bug surface area and prevents feature parity.
2. **Platform Confinement:** The C server is tightly coupled to Windows Win32 APIs (`SendInput`, Winsock2, WinCrypt, Windows Clipboard). Linux (X11/Wayland) and macOS support are absent.
3. **Transport Fragility:** Plain UDP without fragmentation/reassembly causes clipboard data exceeding MTU (~1400 bytes) to drop or fail silently.
4. **Android Monolith:** The Android app is a single monolithic module lacking separation between networking, domain logic, and presentation layers.

This document presents a deeply researched, production-grade blueprint for rewriting and restructuring Telepad into a modern, memory-safe, cross-platform system.

---

## 1. Technology Decision Matrix

### 1.1 Core & Desktop Server: Rust vs Modern C++ vs Go vs Kotlin

| Evaluation Criteria | Rust (Recommended) | Modern C++ (C++20) | Go | Kotlin Multiplatform |
| :--- | :--- | :--- | :--- | :--- |
| **Memory Safety** | Compile-time ownership, zero CVEs from buffer overflows | Manual or smart-pointer discipline; data races still possible | Garbage-collected runtime pauses (unacceptable for 120Hz input) | JVM/Native runtime overhead, garbage collection pauses |
| **Low-Latency Input Loop** | Zero-cost abstractions, predictable microsecond latency | Zero-cost abstractions, microsecond latency | Stop-the-world GC jitter spikes (>5–15ms latency spikes) | GC pauses on Android Native / Desktop JVM |
| **Cross-Platform OS APIs** | Native access to Win32, macOS CoreGraphics, Linux uinput/Wayland | Native OS header access | CGo overhead per syscall degrades hot input paths | JNI/C-interop ceremony on every OS event |
| **Cryptography Ecosystem** | Mature Noise implementations (`snow`), `ring`, `subtle` | OpenSSL or vendored C libraries (`noise-c`) | Standard crypto or `flynn/noise` | Snapshot dependencies (`noise-java`) or Tink |
| **Binary Footprint** | Single compact statically-linked binary (< 6 MB) | Compact binary, but requires MSVC runtime redistributables | Larger binary (> 15 MB) with runtime embedded | Heavy JVM or K/Native runtime binary |

**Decision:** Adopt **Rust** for the shared core engine (`telepad-core`) and desktop server daemon (`telepad-server`).

### 1.2 Android & Mobile: Jetpack Compose + Rust Shared Core (via UniFFI)

- **Presentation Layer:** Jetpack Compose (Kotlin) remains the gold standard for Android UI development, providing declarative theming, dynamic animations, and edge-to-edge support.
- **Engine Layer:** Rust core compiled to `.so` via Android NDK, with type-safe Kotlin bindings generated automatically by Mozilla's **UniFFI**.
- **Benefits:**
  - Identical Noise IK handshake and cipher state machines on both phone and PC.
  - Zero divergence in wire framing, serialization, and packet validation.
  - Effortless future porting to iOS using Swift bindings generated from the exact same Rust core.

---

## 2. Wire Protocol & Transport Modernization

### 2.1 The MTU & Fragmentation Problem in Current Design

In the existing design, packets are sent as single UDP datagrams:
- A large clipboard copy (e.g. 10 KB code snippet or document text) exceeding Ethernet MTU (1500 bytes) will either get IP-fragmented (and routinely dropped by Wi-Fi routers) or truncated.
- There is no packet sequencing or retransmission for stateful data (clipboard, now-playing, server info), while input motion requires loss-tolerant, newest-wins semantics.

### 2.2 Dual-Channel Multiplexed Protocol (Input vs Control/Data)

The modernized protocol defines two distinct delivery guarantees over a single UDP socket:

```
+-------------------------------------------------------------------+
|                     Telepad Wire Protocol v2                      |
+---------------------------------+---------------------------------+
|   Channel 0: Unreliable / Hot   |    Channel 1: Reliable / State   |
|   - Mouse Movement (120 Hz)     |    - Handshake (Noise IK)       |
|   - Scroll Events               |    - Clipboard Sync (Chunked)   |
|   - Touchpad Gestures           |    - Now Playing Metadata       |
|   - Ping / Keep-Alive           |    - Ping Acknowledgements      |
+---------------------------------+---------------------------------+
```

#### Datagram Framing (Variable Length)
```
 0                   1                   2                   3
 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|  Magic (0x54) |  Version (02) | Channel ID (1)| Packet Flags  |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                       Sequence Number                         |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|        Payload Length         |    Chunk Index / Total Chunks |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                    Noise ChaCha20-Poly1305                    |
|             Encrypted Payload + 16-byte Poly1305 Tag          |
|                             ...                               |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
```

- **Zero-Copy Serialization:** Implemented using Rust's `zerocopy` or `postcard` (efficient, compact binary format designed for embedded and real-time systems).
- **Chunking Engine:** Any payload $> 1200$ bytes is split into chunks with an assembly window and reassembly timeout (500ms).

---

## 3. Cryptography & Security Architecture

### 3.1 Noise Protocol Framework: `Noise_IK_25519_ChaChaPoly_BLAKE2s`

1. **Identity Keys:**
   - Server: 32-byte X25519 static keypair stored securely in OS credential storage:
     - **Windows:** DPAPI-encrypted file in `%APPDATA%\Telepad\identity.key`.
     - **Linux:** Secret Service API / libsecret or kernel keyring.
     - **macOS:** Keychain Services (`kSecClassGenericPassword`).
   - Client (Phone): Android Keystore-backed AES-256-GCM wrapping the private key.
2. **Handshake Flow (1-RTT Mutual Auth):**
   - **Msg 1 ($\rightarrow$):** $e, es, s, ss$ (Initiator ephemeral, static, and encrypted DH operations). Server extracts and validates Client static public key against its pairing store.
   - **Msg 2 ($\leftarrow$):** $e, ee, se$. Handshake completes, establishing symmetric cipher pairs.
3. **Replay Attack & DoS Mitigation:**
   - Handshake pre-filtering: Drop handshakes from blocked IPs before doing costly DH operations.
   - Sliding replay window: 64-packet bitmask rejects duplicated sequence numbers on UDP packets.
   - Ephemeral session timeouts: Inactive sessions destroyed after 60 seconds of silent keep-alive failure.

---

## 4. Target Repository Structure

```
telepad/
├── .github/
│   └── workflows/
│       ├── ci.yml                 # Matrix CI: Windows, Linux, macOS, Android
│       └── release.yml            # Automated multiplatform release builds
├── crates/                        # Rust Workspace
│   ├── telepad-protocol/          # Wire protocol, packet types, codecs, serialization
│   │   ├── Cargo.toml
│   │   └── src/
│   │       ├── codec.rs
│   │       ├── packets.rs
│   │       └── lib.rs
│   ├── telepad-crypto/            # Noise IK implementation, fingerprinting, DPAPI/Keystore
│   │   ├── Cargo.toml
│   │   └── src/
│   │       ├── noise.rs
│   │       ├── fingerprint.rs
│   │       └── lib.rs
│   ├── telepad-core/              # Cross-platform engine (UniFFI bindings target)
│   │   ├── Cargo.toml
│   │   ├── uniffi.toml
│   │   └── src/
│   │       ├── connection.rs
│   │       ├── discovery.rs
│   │       └── lib.rs
│   ├── telepad-platform/          # Native OS hooks (Windows, Linux uinput, macOS)
│   │   ├── Cargo.toml
│   │   └── src/
│   │       ├── windows/           # SendInput, WinRT SMTC, Clipboard
│   │       ├── linux/             # uinput virtual devices, D-Bus MPRIS, wl-clipboard
│   │       ├── macos/             # CoreGraphics, AppleScript NowPlaying, NSPasteboard
│   │       └── lib.rs
│   └── telepad-server/            # Desktop Server Executable
│       ├── Cargo.toml
│       └── src/
│           ├── main.rs
│           ├── tray.rs            # Desktop System Tray (tray-icon crate)
│           └── cli.rs
├── android/                       # Multi-Module Android Architecture
│   ├── build.gradle.kts
│   ├── settings.gradle.kts
│   ├── gradle/libs.versions.toml
│   ├── core/
│   │   ├── model/                 # Domain entities (ServerInfo, ConnectionState, Event)
│   │   ├── crypto/                # Keystore / UniFFI bridge bindings
│   │   ├── datastore/             # UserPreferences & Settings DataStore
│   │   ├── bluetooth/             # Android Bluetooth HID Device profile
│   │   └── ui-kit/                # Shared theme tokens, status bar, glassmorphic buttons
│   ├── feature/
│   │   ├── touchpad/              # Touchpad gesture processor & interactive canvas
│   │   ├── keyboard/              # Soft keyboard, modifiers, shortcuts, quick actions
│   │   ├── pairing/               # TOFU QR code / fingerprint verification modal
│   │   ├── media/                 # Now Playing media controls widget
│   │   └── settings/              # Settings, themes, latency overlay configuration
│   └── app/                       # Application module: navigation, DI (Koin/Hilt), services
│       ├── src/main/java/com/omsingh/telepad/
│       │   ├── TelepadApplication.kt
│       │   ├── MainActivity.kt
│       │   └── service/
│       └── build.gradle.kts
├── docs/
│   ├── architecture.md
│   ├── protocol_spec.md
│   └── security_model.md
├── Cargo.toml                     # Root workspace manifest
├── .gitignore
├── LICENSE
└── README.md
```

---

## 5. Desktop Server Modernization (Cross-Platform)

### 5.1 OS Abstraction Layer (`telepad-platform`)

Using Rust traits, platform input injection and system integration are cleanly isolated:

```rust
pub trait PlatformInputBackend: Send + Sync {
    fn send_mouse_move(&self, dx: i32, dy: i32) -> Result<(), PlatformError>;
    fn send_mouse_button(&self, button: MouseButton, state: ElementState) -> Result<(), PlatformError>;
    fn send_scroll(&self, delta: f32) -> Result<(), PlatformError>;
    fn send_key(&self, keycode: KeyCode, state: ElementState) -> Result<(), PlatformError>;
}

pub trait PlatformClipboardBackend: Send + Sync {
    fn read_text(&self) -> Result<Option<String>, PlatformError>;
    fn write_text(&self, text: &str) -> Result<(), PlatformError>;
}

pub trait PlatformNowPlayingBackend: Send + Sync {
    fn subscribe(&self, tx: mpsc::Sender<NowPlayingState>) -> Result<(), PlatformError>;
}
```

### 5.2 Implementation Matrix
- **Windows:**
  - Input: `SendInput` via `windows-rs` crate (high-performance C-struct layout, zero allocation).
  - Media: Windows Media Session Manager (`GlobalSystemMediaTransportControlsSessionManager`) with async events.
  - Clipboard: Win32 Clipboard APIs with delayed rendering and clipboard format listener.
- **Linux:**
  - Input: Direct `/dev/uinput` kernel virtual device emission (supports raw evdev codes for zero-lag gaming/desktop input on both Wayland and X11).
  - Media: D-Bus MPRIS interface (`org.mpris.MediaPlayer2`).
  - Clipboard: D-Bus Wayland clipboard protocol / X11 selection.
- **macOS:**
  - Input: `CGEventPost` via CoreGraphics.
  - Media: `MRMediaRemote` private framework / ScriptingBridge.
  - Clipboard: `NSPasteboard`.

---

## 6. Android Client Refactoring (Multi-Module Clean Architecture)

### 6.1 Multi-Module Decoupling
The monolithic `:app` module is split into focused Gradle subprojects:
- `:core:model` contains pure Kotlin models with zero Android framework dependencies.
- `:core:bluetooth` encapsulates `BluetoothHidDevice` and descriptor compilation.
- `:feature:touchpad` contains the touch filter, pointer math, and gesture discrimination state machines.
- Tests can run on the host JVM in < 1 second without mocking the Android framework.

### 6.2 Gesture Discrimination State Machine
The gesture pipeline replaces ad-hoc flags with an explicit state machine:

```
[TouchDown]
    │
    ▼
(SingleFingerContact) ──[Hold past threshold]──> (LongPressRightClick)
    │
    ├──[Finger lifts < 250ms & < 15px]──> (PendingTapWindow)
    │                                          │
    │                   ┌──────────────────────┴──────────────────────┐
    │                   ▼                                             ▼
    │          [2nd TouchDown]                                [Window expires]
    │                 │                                               │
    │         (TapCandidate)                                     [Emit Click]
    │          │           │
    │          │           └──[Movement > 15px]──> (DragMode: Button Held)
    │          │                                          │
    │   [Lifts without move]                       [2nd TouchUp]
    │          │                                          │
    │   [Emit DoubleClick]                         [Emit DragEnd]
    │
    └──[Movement > 15px]──> (PointerTracking / MouseMove)
```

---

## 7. Step-by-Step Phased Migration Roadmap

### Phase 1: Core Protocol Extraction in Rust (`telepad-protocol` & `telepad-crypto`)
1. Create root `Cargo.toml` workspace and implement `telepad-protocol` with zero-copy binary deserialization.
2. Implement `telepad-crypto` with Noise IK using the `snow` crate.
3. Add end-to-end integration tests validating handshake, encryption, decryption, and replay filtering.

### Phase 2: Rust Server Daemon (`telepad-server`)
1. Implement Windows backend using `windows-rs` for `SendInput` and clipboard.
2. Implement desktop system tray icon (Windows/macOS/Linux) with status indication and "Forget Paired Devices" menu.
3. Validate parity against the existing Android client (connecting, moving mouse, typing, TOFU verification).

### Phase 3: UniFFI Integration for Android
1. Build `telepad-core` for Android targets (`aarch64-linux-android`, `x86_64-linux-android`).
2. Generate Kotlin bindings via UniFFI.
3. Replace `NoiseSession.kt` and `WifiInputDispatcher.kt` low-level socket loops with the Rust core engine.

### Phase 4: Android Multi-Module Restructure
1. Split `:app` into `:core:model`, `:core:bluetooth`, `:feature:touchpad`, `:feature:keyboard`.
2. Convert gesture handling to the formal state machine.
3. Ensure 100% unit test coverage for gesture classification and preferences migration.

### Phase 5: Cross-Platform Desktop Backends
1. Implement Linux `uinput` backend and MPRIS media listener.
2. Implement macOS CoreGraphics backend.
3. Release single-binary portable builds on GitHub Releases via automated matrix CI.

---

## 8. Conclusion & Production Readiness

The proposed architecture transforms Telepad from a hobbyist project into a commercial-grade, cross-platform utility. By centralizing cryptographic and protocol logic in a high-performance Rust core, we eliminate memory safety hazards, prevent UDP MTU packet loss, enable multi-OS desktop support, and achieve unified, robust engineering across all devices.

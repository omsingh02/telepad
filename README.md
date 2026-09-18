# Telepad

Turn your phone into a low-latency touchpad + keyboard for your PC over Wi-Fi or Bluetooth HID.

- **Wi-Fi mode**: UDP with Noise IK encryption (forward secret, mutually authenticated, ~3–8 ms median latency on LAN).
- **Bluetooth mode**: Standard HID profile — works as a driverless keyboard/mouse on Windows, macOS, Linux, Smart TVs.
- **TOFU pairing**: Verify a 12-character fingerprint on first connect, then silent forever.
- **No telemetry, no cloud, no accounts.**

## Features

- Multi-touch trackpad with configurable acceleration curves (Linear / macOS / Windows / Flat).
- Full keyboard with sticky modifiers, function row, navigation cluster, quick actions (Copy/Paste/Cut/Undo/Alt+Tab/Win+D).
- Clipboard sync between phone and PC (Wi-Fi mode).
- Now-playing card with media controls pulled from Windows SMTC.
- Presentation mode (large slide forward/back tap zones).
- Quick launchers for common system actions.
- Light/dark/system theme with dynamic color support on Android 12+.
- Optional foreground service to keep the connection alive when the app is backgrounded.

## Project structure

```
telepad/
├── android/          # Android client (Kotlin + Compose, minSdk 28, targetSdk 35)
├── server/           # Windows desktop server (C99, Win32 API)
├── README.md
└── LICENSE           # MIT
```

## Building

### Prerequisites

1. **Android Studio Ladybug** or newer (Gradle 8.10+, JDK 17+).
2. **CMake 3.21+** and **Visual Studio 2022 Build Tools** (for Windows server).
3. Two vendored dependencies you must download yourself:
   - **noise-java**: download `noise-java-0.5.0.jar` from <https://github.com/rweather/noise-java/releases> and drop it into `android/app/libs/`.
   - **noise-c**: `cd server && git clone https://github.com/rweather/noise-c third_party/noise-c`.

### Android

```bash
cd android
./gradlew assembleRelease
# APK: app/build/outputs/apk/release/app-release.apk
```

### Windows server

```bash
cd server
cmake -B build -G "Visual Studio 17 2022"
cmake --build build --config Release
# Binary: build/Release/telepad-server.exe
```

## Running

1. Launch `telepad-server.exe` on your Windows PC. It prints:
   ```
   Telepad server listening on 0.0.0.0:5000
   Hostname: DESKTOP-XYZ
   Fingerprint: 7F2A · B9C1 · 4E08
   ```
2. Open Telepad on your phone (same Wi-Fi network).
3. Tap your PC in the discovered list.
4. Verify the fingerprint shown on the phone matches the one printed on the PC, then tap **Trust this PC**.
5. Done. Touchpad and keyboard work immediately. Future connects are silent.

## Security model

- **Threat:** An attacker on the same LAN tries to intercept your typing.
- **Defense:** Noise IK handshake with a long-term server static key. The fingerprint you verify on first connect binds your phone to that specific server pubkey. Any subsequent MitM attempt produces a fingerprint mismatch (caught by the phone) or a decrypt failure (caught by Noise's authenticated encryption).
- **Forward secrecy:** ChaCha20-Poly1305 session keys are derived per-session from ephemeral X25519 keypairs. Past sessions cannot be decrypted even if either party's static key is later compromised.
- **No cloud, no accounts, no telemetry.** Pairing data lives on-device in EncryptedSharedPreferences (Android Keystore-wrapped) on the phone and in `%APPDATA%\Telepad\` on the PC.

## License

MIT. See LICENSE.
`

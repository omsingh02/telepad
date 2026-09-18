# Telepad — Integration & Build Guide

## After running `split.py`

You now have `./telepad/` with the project tree. Initialise git:

```bash
cd telepad
git init
git add .
git commit -m "Initial: Telepad v1.0.0"
```

## Vendored dependencies you must add

These are too large or binary to include in the text dump:

1. **noise-java** (Android crypto)
   - Download `noise-java-0.5.0.jar` from
     <https://github.com/rweather/noise-java/releases>
   - Drop into `android/app/libs/`
   - Gradle picks it up automatically via the `fileTree("libs")` rule.

2. **noise-c** (server crypto)
   ```bash
   cd server
   git clone https://github.com/rweather/noise-c third_party/noise-c
   ```
   CMake's `add_subdirectory(third_party/noise-c)` builds it as a static lib
   linked into `telepad-server`.

3. **Gradle wrapper jar** (Android)
   - Open the project in Android Studio once; it'll prompt to generate the
     wrapper jar. Alternatively from CLI:
     ```bash
     cd android
     gradle wrapper --gradle-version 8.10.2
     ```

## Building

**Android:**
```bash
cd android
./gradlew assembleRelease
# Output: app/build/outputs/apk/release/app-release.apk
```

**Windows server:**
```bash
cd server
cmake -B build -G "Visual Studio 17 2022"
cmake --build build --config Release
# Output: build/Release/telepad-server.exe
ctest --test-dir build  # runs the protocol-layout sanity test
```

## Known v1 deferrals (documented in code comments)

- **Linux/macOS server**: only Windows is wired. Add `input_linux.c` /
  `input_macos.c` + CMake conditionals for cross-platform.
- **Now-playing on Windows** is a safe stub returning "nothing playing."
  The phone UI hides the card gracefully. Full SMTC integration requires
  C++/WinRT and was deferred.
- **No Linux/macOS clipboard backend** for the same reason as above.
- **TouchpadProcessorTest** uses MockK to fake `MotionEvent`. On some
  JDKs/MockK combos this can be flaky — if so, move to instrumented tests.
- **MainNavigation.kt** has a `collectAsStateNonNull` helper marked in the
  Part 9 compile notes; the comments tell you how to inline it if your
  compiler grumbles.
- **PresentationModePad.kt** has the vestigial helper lines flagged in
  Part 7's notes — delete them as instructed.
- **TelepadConnectionService.kt** has a placeholder notification ID flagged
  in Part 5's notes — change to a plain integer literal as instructed.

## Pairing fl
````
Continuing from where Part 10's `INTEGRATION.md` was cut off:

\==== FILE: INTEGRATION.md (continued) ====

````markdown
## Pairing flow (TOFU)

1. **First run on PC:** `telepad-server.exe` generates an X25519 keypair under
   `%APPDATA%\Telepad\static.key`, persists it, and prints to the console:

   ```
   Telepad server identity
   ─────────────────────────────────────────────────
   Fingerprint: 7F2A · B9C1 · 4E08
   Pubkey:      <44-char base64>
   ─────────────────────────────────────────────────
   ```

2. **Phone discovers the PC** (multicast + mDNS + manual scan all converge).

3. **User taps the PC card.** Phone has no trusted pubkey yet → routes to
   `PairingScreen` → fetches the pubkey via `WIRE_PAIRING_INTRO_REQ` →
   displays the same fingerprint in giant monospace.

4. **User compares fingerprints**, taps **Trust this PC**. Phone persists
   `host → pubkey` in EncryptedSharedPreferences. Real Noise handshake runs.

5. **Every subsequent connect** is silent — phone just looks up the trusted
   pubkey, runs the handshake, no UI.

## Re-pairing scenarios

- **Server reinstall** (PC has new keypair): phone sees fingerprint mismatch
  → shows `ReKeyWarningDialog` from `PairingDialog.kt`. User chooses
  "Forget and re-pair" or "Cancel."
- **Phone reinstall**: server has the phone's old client pubkey in
  `trusted_clients.bin`. The new install pairs as a new client; the old
  client pubkey can be cleaned up via Settings → Privacy → Forget all
  trusted PCs (resets locally), or by deleting
  `%APPDATA%\Telepad\trusted_clients.bin` on the PC.

## Compile-fix checklist (from Part-level notes)

Before first build:

- [ ] **Part 5 — `TelepadConnectionService.kt`**: change
  `private const val NOTIFICATION_ID = 0xT3_LE.toInt()` to
  `private const val NOTIFICATION_ID = 0xC0FFEE`. Delete the two helper
  lines below the class (`private val Int.Companion.T3_LE` and the
  `_t3le_unused` line).
- [ ] **Part 7 — `PresentationModePad.kt`**: delete the trailing
  `private val Int.dp`, `private inline val …size`, `private val sizeStub`,
  and the manual `Modifier.size(dp:)` extension — Compose's built-ins handle this.
- [ ] **Part 7 — `OnboardingScreen.kt`**: delete the private `remember`
  shim at the bottom (the file already imports the real one via `mutableStateOf` chain).
- [ ] **Part 8 — `AddDeviceScreen.kt`**: in the Wi-Fi tab "Stop" button,
  replace the awkward `Row(verticalAlignment = …) { CircularProgressIndicator…; Spacer; Text }`
  with a clean `Row { CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(18.dp)); Text("  Stop") }`.
- [ ] **Part 8 — `ControlsScreen.kt`**: if `Icons.Filled.Browser` doesn't
  resolve in your IDE, swap for `Icons.Filled.Public`.
- [ ] **Part 9 — `MainNavigation.kt`**: the `collectAsStateNonNull` helper
  may type-check oddly. Inline `mainViewModel.pendingPairingFor.collectAsState().value`
  and `settingsViewModel.preferences.collectAsState().value` at call sites,
  delete the helper.
- [ ] **Part 9 — `HidReportEncodingTest.kt`**: if Robolectric/AndroidLoadTest
  complains about loading `HidReportDescriptor`, move the file under
  `androidTest/` instead.

## Running it

**PC:**
```cmd
cd server\build\Release
telepad-server.exe
```
Leave the console window open. Use Windows Defender exclusion for the binary
if you see input synthesis getting blocked.

**Phone:**
1. Open Telepad → onboarding.
2. PC appears in "Discovered" within ~3 seconds.
3. Tap it → pairing screen.
4. Verify fingerprint, tap Trust.
5. Touchpad screen opens. Move your finger. Cursor moves on PC. Done.

## What's next (v1.1 candidates)

In rough priority order:

1. **Linux server** via `/dev/uinput` (works under Wayland; needs udev rule).
2. **macOS server** via `CGEventPost` (needs Accessibility permission).
3. **Full SMTC integration** on Windows (C++/WinRT TU added to CMake).
4. **`platform_clipboard_*` for Linux/macOS** (X11 selections, NSPasteboard).
5. **Foreground service notification action** that actually disconnects
   (currently sends an intent the service doesn't handle yet).
6. **QR pairing** as an optional fast-path on top of the fingerprint flow.
7. **Multi-host control** (one phone → multiple PCs, switch with a chip).
8. **Per-app launchers** with user-customisable list.

## License attributions

| Component | License | Notes |
|---|---|---|
| Telepad | MIT | This codebase |
| noise-java | BSD 3-Clause | Vendored binary |
| noise-c | MIT | Vendored source |
| Material Icons | Apache 2.0 | Via Compose deps |
| AndroidX libs | Apache 2.0 | Via Compose BOM |
| Kotlin stdlib | Apache 2.0 | JetBrains |

A copy of each license is required in your published binaries. The Android
build system includes them automatically via `androidx.compose.material3`
attribution; for the server, include them as a `LICENSES/` folder before
distributing.
`

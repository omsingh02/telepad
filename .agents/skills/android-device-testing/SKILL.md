---
name: android-device-testing
description: Use this skill when testing, debugging, or driving an Android app on a connected physical device or emulator through adb — taking screenshots, reading the UI hierarchy, tapping or finding elements by text/resource-id/content-description instead of guessing pixel coordinates from a screenshot, waking or unlocking the screen, launching/relaunching an app and confirming it actually got focus, capturing logcat around a specific action, resolving which JDK gradlew should use, or handling more than one connected adb device. Trigger on adb, uiautomator, logcat, gradlew/JAVA_HOME setup, or any screenshot-then-guess-tap loop against an Android device.
---

# Android Device Testing

## Goal
One dependency-free tool (`scripts/android_agent.py`) that talks to `adb`/`uiautomator` directly, so driving and verifying an Android app on a real device takes single deterministic calls instead of long screenshot → guess → tap → screenshot loops.

## Why this exists
Ad hoc adb testing tends to reproduce four specific failure modes:

- **Screenshot round-trips.** `screencap` to `/sdcard`, `pull`, then `rm` — three adb calls and a device-side temp file for one image. On Windows, piping `adb exec-out` through a shell `>` redirect can also silently corrupt the PNG bytes (encoding/newline translation).
- **Blind coordinate tapping.** Finding a button by eyeballing a screenshot, guessing `x y`, then re-screenshotting to check whether it worked. Coordinates drift across screen sizes, densities, and OS versions; a button's text or resource-id doesn't.
- **Logcat races.** A `logcat -d` snapshot taken *after* the action frequently comes back empty, because the ring buffer already rotated past the relevant lines or the clear/action timing was off by a beat.
- **JAVA_HOME churn.** `gradlew` has no fixed JDK path, so every session re-discovers Android Studio's bundled JBR (or whichever JDK actually works) from scratch.

This tool fixes each directly: one-call byte-exact screenshots, `uiautomator dump`-based element lookup for tapping, a start/stop logcat capture that brackets an action in real time instead of guessing after the fact, and a one-time `gradle.properties` pin for the JDK.

## Setup (once per machine)
Requires Python 3.8+ and `adb` reachable — on PATH, or the tool falls back to the default Android SDK `platform-tools` location.

Pin the JDK gradlew should use, once, so it never needs rediscovering:

```
python scripts/android_agent.py java-home --fix-gradle <path-to-android-dir>
```

This probes, in order, the `JAVA_HOME` env var, Android Studio's and JetBrains IDEs' bundled JBRs, Eclipse Adoptium/Temurin installs, then `java` on PATH — and writes `org.gradle.java.home=<resolved path>` into `<android-dir>/gradle.properties`. Every later `gradlew` invocation in that project picks it up automatically; there's no need to set `$env:JAVA_HOME` per session again.

## Instructions
Default to the structural query (`dump` / `find` / `tap`) over a screenshot for anything about *where* or *whether* a UI element is on screen. Only take a screenshot when the question is genuinely visual (colour, layout, an image, an animation frame) — not to locate a tap target.

1. **Pick and inspect the device.**
   - `python scripts/android_agent.py devices`
     If more than one device/emulator is listed, pass `--serial <id>` to every other command. With exactly one connected, `--serial` can be omitted — it's auto-selected.
   - `python scripts/android_agent.py info`
     Prints comprehensive diagnostics: brand/model, Android OS & API level, display resolution & DPI, battery level/temp/charging status, WiFi IPv4, software keyboard state, and focused window.

2. **Wake, unlock, and keep awake.**
   - `python scripts/android_agent.py wait-awake --unlock`
     Polls `dumpsys power` for wakefulness; if asleep, sends KEYCODE_WAKEUP, dismisses keyguard, sends KEYCODE_MENU and resolution-aware swipe-up to clear lockscreens.
   - `python scripts/android_agent.py wait-awake --keep-awake [--timeout-mins 30]`
     Configures device screen timeout and prevents sleep while connected over USB.

3. **Find, wait, assert, tap, long-press, and type elements structurally.**
   - `python scripts/android_agent.py dump` — full current UI tree as JSON: bounds, text, resource-id, content-desc, class, clickable, enabled, for every node.
   - `python scripts/android_agent.py find --text "Pair"` (or `--id <resource-id>`, `--desc <content-desc>`) — matching elements as JSON. Add `--any` to include non-clickable elements (e.g. a status label).
   - `python scripts/android_agent.py wait-element --text "Connected" [--timeout 10] [--tap]` — waits until an element appears (or `--disappear`). Optionally auto-taps immediately once found.
   - `python scripts/android_agent.py assert-text "Settings"` — verifies text is present on screen, exits non-zero if not.
   - `python scripts/android_agent.py tap "Connect"` (or `tap --text "Connect"`, `tap 540 664`) — taps matching element or literal coordinates. Automatically falls back to text element center when text is nested inside Compose clickable surfaces.
   - `python scripts/android_agent.py long-press "Server 1" [--duration 1000]` — long-presses matching element or coordinates.
   - `python scripts/android_agent.py type "192.168.1.10" [--tap-text "IP address"] [--clear] [--enter] [--hide-keyboard]` — inputs text directly into fields, with optional focus tapping, atomic Ctrl+A deletion, Enter key, and keyboard dismissal.
   - `python scripts/android_agent.py current` — quickly check the currently focused window and package.

4. **Navigate and scroll.**
   - `python scripts/android_agent.py scroll [--direction down|up|left|right] [--distance 0.45]` — smooth gestures.
   - `python scripts/android_agent.py scroll-into-view --text "About" [--direction down] [--tap]` — scrolls until the target appears, optionally tapping it.
   - `python scripts/android_agent.py swipe <x1> <y1> <x2> <y2> [--duration 200]` — directional swipe between points.
   - `python scripts/android_agent.py back` / `home` — system Back / Home hardware buttons.
   - `python scripts/android_agent.py key <keycode>` — arbitrary Android keyevent (e.g. `KEYCODE_VOLUME_UP` or `24`).

5. **Install, launch, or relaunch deterministically.**
   - `python scripts/android_agent.py install path/to/app-debug.apk [--launch .MainActivity --package com.example.app]` — installs APK safely with resolved adb.
   - `python scripts/android_agent.py launch --package com.example.app --activity .MainActivity [--clear]` — force-stops, optionally clears app data, starts activity, and polls window focus until confirmed.

6. **Capture and inspect logcat.**
   - `python scripts/android_agent.py logcat [--tag MyTag] [--lines 50] [--grep "pattern"] [--clear]` — direct snapshot of recent logs.
   - `python scripts/android_agent.py logcat-start --tags TagA TagB --out session.log`, perform the action, `python scripts/android_agent.py logcat-stop`, then read `session.log` directly.

7. **Screenshot only when you need pixels.**
   - `python scripts/android_agent.py screenshot out.png`
     Reliable, byte-exact, non-hanging single-call capture via `exec-out screencap -p`.

## Constraints
- Don't tap eyeballed coordinates when `find` / `tap --text|--id|--desc` can resolve the same element structurally.
- Don't add a fixed sleep before checking device or app state; use `wait-awake` or `launch`'s built-in poll, or extend the script with a real wait condition if one is missing.
- `dump` / `find` / `tap` see the accessibility tree, not pixels. A custom-drawn surface (e.g. a raw touchpad/gesture canvas with no semantic children) won't produce elements — for that specific case, fall back to `screenshot` for visual confirmation and coordinate-based `tap --x --y` / `swipe`, and say that's why.
- Fix `JAVA_HOME` once via `java-home --fix-gradle`; don't set `$env:JAVA_HOME` / `export JAVA_HOME` ad hoc per command.

## Example
```
python scripts/android_agent.py devices
python scripts/android_agent.py wait-awake --unlock
python scripts/android_agent.py launch --package com.example.app --activity .MainActivity
python scripts/android_agent.py find --text "Settings"
python scripts/android_agent.py tap --text "Settings"
python scripts/android_agent.py screenshot after_settings.png
```

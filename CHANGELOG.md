# Changelog

All notable changes are listed here, newest first. The format follows [Keep a Changelog](https://keepachangelog.com), and the project uses [Semantic Versioning](https://semver.org). The Android app, the desktop server and the git tag share one version number.

## [2.0.0] - Unreleased

A new desktop server for Windows, Linux and macOS, and a redesigned Android app.

### Upgrading from 1.x

- Install the 2.0 app and the 2.0 server together.
- The server is a new program, not an update of the old one. It keeps its own files (`identity.key`, `trusted_clients.json`) and does not read the old server's `static.key` or `trusted_clients.bin`. The PC therefore gets a **new identity**: the app will say that the PC *has a new identity*, and you compare the fingerprint once to continue.
- Phones have to be **paired again**, on purpose. The 1.x server accepted every phone that connected, so its list of "paired" phones may include devices you never meant to allow. After installing, start the server and connect your phone within the first five minutes (see *Pairing* in the README).
- The fingerprint is longer: five groups of four characters instead of three. The first three groups are the same as before.

### Desktop server

- **Added** a cross-platform server written in Rust: Windows (`SendInput`), Linux (`/dev/uinput`, so Wayland and X11 both work) and macOS (CoreGraphics). It replaces the Windows-only C server.
- **Added** a console for the owner (`pair`, `close`, `list`, `forget`, `status`, `help`, `quit`) and options `--port`, `--verbose`, `--key-dir`, `--pair`, `--no-input`, `--insecure-accept-any-client`, and the platform options `--text-via-unicode` (Linux) and `--mac-ctrl-as-cmd` (macOS).
- **Added** a message that tells the phone which operating system the PC runs and what it supports, so the app can show the right key names and shortcuts. Older servers simply do not send it.
- **Security:** a phone must be paired before it can send input. New phones are accepted only while a pairing window is open: the first five minutes of the first run, or after `pair` / `--pair`. The window closes as soon as one phone has paired. A refused phone is told so at once and the console says why. `--insecure-accept-any-client` restores the old accept-everyone behavior for networks you fully control.
- **Security:** fingerprints are 80 bits (they were 48), so a look-alike key cannot be searched for in advance.
- **Fixed:** keys and mouse buttons a phone was holding are released when it goes silent for 10 seconds, when it is forgotten, and when the server shuts down, instead of staying pressed.
- **Sessions:** a session that has been silent for a minute expires, and the number of sessions is capped; when the cap is reached the least recently active one is dropped first.

### Android app

- **Redesigned** around three places: *Devices*, *Remote* (Pad, Keys, Media) and *Settings*. Material 3, with colors generated from the accent you pick or from your wallpaper (Material You) in light and dark, tested for contrast; portrait, landscape and tablets; TalkBack and reduced-motion support.
- **Added** a first-run walkthrough, a pull-to-refresh device list and clear explanations when a connection fails and what to do about it.
- **Touchpad:** a gesture engine with tap, double-tap, tap-and-drag, press-and-hold, two- and three-finger taps, momentum scrolling and a scroll strip; the pad shows what it understood. Mouse buttons that really hold. Four acceleration curves and a pad in the settings to try them on.
- **Keyboard:** the phone's keyboard types into the PC as you type (autocorrect works); sticky Ctrl, Alt, Shift and Win/⌘/Super; function keys; shortcuts that use the PC's own key names and combinations; clipboard buttons that never share anything automatically.
- **Media and remote:** playback and volume with hold-to-repeat, slide controls and PC actions (show desktop, task view, task manager, screenshot, files, browser, lock).
- **Connection:** a PC is identified by its key rather than its address, so a new IP address is still the same PC. The app notices when a PC stops answering, reconnects on its own and says why when it cannot, and warns if a PC answers with a different key than the one you paired. A PC is remembered only after you have confirmed its fingerprint and a secure connection has really worked. Optionally stays connected in the background with a notification that has play/pause, volume and disconnect.
- **Bluetooth:** the keyboard now sends system actions as the PC's own shortcuts, and tells you which characters it cannot type.
- **Changed:** PCs paired with 1.x are carried over, and the favorites list is folded into the device list.
- **Removed** the NDK UDP sender and the old screens.

### Project

- The release workflow stops unless the tag, the app version and the server version agree, and its test step now runs the debug unit tests (the release variant cannot run the screen tests).
- CI checks formatting (`cargo fmt`), builds a minified release APK, and runs hundreds of automated tests, among them networking tests against a stand-in PC that speaks the real protocol, tests of every trust decision, and screen tests that render every screen into the pictures in the README.
- Added `CONTRIBUTING.md`, `SECURITY.md` and this file.
- Android app versions now follow the project: `versionCode` is derived from `versionName`. (1.0.0 and 1.0.1 both shipped as `versionCode` 1.)

## [1.0.1] and [1.0.0]

The first releases: a Windows server written in C and the first version of the Android app.

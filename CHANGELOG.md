# Changelog

All notable changes are listed here, newest first. The format follows [Keep a Changelog](https://keepachangelog.com), and the project uses [Semantic Versioning](https://semver.org). The Android app, the desktop app and server and the git tag share one version number.

## [2.0.0] - 2026-10-07

A new desktop app and server for Windows, Linux and macOS, and a redesigned Android app.

### Upgrading from 1.x

- Install the 2.0 app and the 2.0 desktop app (or server) together.
- The server is a new program, not an update of the old one. It keeps its own files (`identity.key`, `trusted_clients.json`) and does not read the old server's `static.key` or `trusted_clients.bin`. The PC therefore gets a **new identity**: the app will say that the PC *has a new identity*, and you compare the fingerprint once to continue.
- Phones have to be **paired again**, on purpose. The 1.x server accepted every phone that connected, so its list of "paired" phones may include devices you never meant to allow. After installing, start Telepad on the PC and scan its QR code with the app (see *Pairing* in the README).
- The fingerprint is longer: five groups of four characters instead of three. The first three groups are the same as before.

### Desktop app

- **Added** `telepad`, the server as an app for double-clicking: an icon in the tray (the menu bar on a Mac), no window and no console. Its menu shows the state (*A phone is connected*, *Waiting for a phone*), **Pair a phone…**, **Start at login** and **Quit**.
- **Pairing by QR code:** the first start opens a page with a QR code, and **Pair a phone…** shows a new one. Scanning it with the app pairs that phone: the code carries the PC's key (so there is no fingerprint to compare) and a one-time token, good for five minutes and for one phone. The page is served to this computer only, under a secret address, and refuses requests from other sites.
- **Start at login:** a menu choice, and the Windows installer's option. It is a per-user entry (a `Run` value on Windows, a LaunchAgent on macOS, an XDG autostart entry on Linux) and starts Telepad quietly, with no page opening by itself.
- **One copy at a time:** starting Telepad again (from the Start menu, Spotlight or the applications menu) opens the page of the copy that is running instead of failing on the port. `telepad --quit` asks it to quit.
- **Packages:** a Windows installer (per user, no administrator, a Start menu entry and an uninstaller in Settings), a macOS app in a disk image, a Linux tarball with an install script, each with the Telepad icon, plus the console server as before. Windows' list of startup apps and SmartScreen show Telepad's name instead of a bare file name.
- **Linux, in one command:** `curl -fsSL https://telepad-app.vercel.app/install.sh | sh` installs Telepad the way the system likes (a `.deb` with apt, an `.rpm` with dnf or zypper, an Arch package with pacman, or the plain download in your home folder), after checking it against the release's checksums, and `--uninstall` removes it. The packages are also downloads of their own: they put Telepad in the applications menu with its icon and set up the permission it needs to type (`/dev/uinput`), so there is no terminal step. Each is installed, run and removed in a real Debian, Ubuntu, Fedora and Arch before it is published.
- **Added** a build attestation for every release file (check one with `gh attestation verify`), next to `SHA256SUMS`. Signing the Windows and macOS builds is prepared and switches on when the accounts exist: see [docs/code-signing.md](docs/code-signing.md). Until then Windows and macOS warn on first start, and the release notes say how to proceed.
- On a desktop without a tray (GNOME without its AppIndicator extension), Telepad opens its page when started instead, which has a **Quit** button.
- **A log file** for the tray app (`telepad.log` in its folder, two megabytes at most, readable by you only): what it did, never what you typed, so that a problem can be reported with something to attach. The page shows its place, the version, and links to updates, *Report a problem*, the privacy statement and the licenses. Telepad still never connects to the internet by itself, so it does not check for updates.

### Desktop server

- **Added** a cross-platform server written in Rust: Windows (`SendInput`), Linux (`/dev/uinput`, so Wayland and X11 both work) and macOS (CoreGraphics). It replaces the Windows-only C server.
- **Added** a console for the owner (`pair`, `qr`, `close`, `list`, `forget`, `status`, `help`, `quit`) and options `--port`, `--verbose`, `--key-dir`, `--pair`, `--no-input`, `--insecure-accept-any-client`, and the platform options `--text-via-unicode` (Linux) and `--mac-ctrl-as-cmd` (macOS).
- **Added** pairing by QR code, in the console as well: it prints a QR code (`qr [light]` for a new one) and the `telepad://pair?...` link it holds. The token travels as the payload of the Noise handshake's first message, encrypted to the PC's key, so a phone that has not seen the code cannot use it; a server that does not know about tokens ignores it, and a code is used up by the first phone that pairs with it.
- **Added** a message that tells the phone which operating system the PC runs and what it supports, so the app can show the right key names and shortcuts. Older servers simply do not send it.
- **Security:** a phone must be paired before it can send input. New phones are accepted only while a pairing window is open: the first five minutes of the first run, or after `pair` / `--pair`. The window closes as soon as one phone has paired. A refused phone is told so at once and the console says why. `--insecure-accept-any-client` restores the old accept-everyone behavior for networks you fully control.
- **Security:** fingerprints are 80 bits (they were 48), so a look-alike key cannot be searched for in advance.
- **Fixed:** if the input layer failed while handling a typed text, the error line in the log named the message *with its text*. It names the kind of message only now, and a test keeps it so.
- **Fixed:** keys and mouse buttons a phone was holding are released when it goes silent for 10 seconds, when it is forgotten, and when the server shuts down, instead of staying pressed.
- **Sessions:** a session that has been silent for a minute expires, and the number of sessions is capped; when the cap is reached the least recently active one is dropped first.

### Android app

- **Added** **Scan QR code** (Devices tab): point the camera at the code on the PC and the phone pairs and connects. A `telepad://pair` link opened from anywhere (a message, a camera app) does the same. The scanner explains a denied camera permission and says what is wrong with a code that is expired or damaged.

- **Redesigned** around three places: *Devices*, *Remote* (Pad, Keys, Media) and *Settings*. Material 3, with colors generated from the accent you pick or from your wallpaper (Material You) in light and dark, tested for contrast; portrait, landscape and tablets; TalkBack and reduced-motion support.
- **Added** **Settings → About → Open-source licenses**, a list of every library in the app with its license (made from the app's real dependencies at build time), and a link to the privacy statement. *Get Telepad for PC* now opens the website's download section, which offers the right file for the system.
- **Added** a first-run walkthrough, a pull-to-refresh device list and clear explanations when a connection fails and what to do about it.
- **Touchpad:** a gesture engine with tap, double-tap, tap-and-drag, press-and-hold, two- and three-finger taps, momentum scrolling and a scroll strip; the pad shows what it understood. Mouse buttons that really hold. Four acceleration curves and a pad in the settings to try them on.
- **Keyboard:** type with the phone's own keyboard (swipe typing, autocorrect and voice work; words are sent when the keyboard has finished with them), with the keys a phone keyboard lacks docked right above it: Esc, Tab, Ctrl, Alt, Shift, Win/⌘/Super, the arrows, Home, End and Delete, and F1 to F12, Page Up and Down, Insert and Print Screen on Fn. Tap Ctrl and then a letter on the phone's keyboard for Ctrl+C. Modifiers can be tapped for the next key, double-tapped to lock, or held with one finger while another taps a key, and one that is still on is shown on every tab. A full PC keyboard (what a phone held sideways shows) is a menu choice away, with touch areas that fill the gaps between keys. Shortcuts use the PC's own key names and combinations; clipboard buttons never share anything automatically.
- **Media and remote:** playback and volume with hold-to-repeat, slide controls and PC actions (show desktop, task view, task manager, screenshot, files, browser, lock).
- **Connection:** a PC is identified by its key rather than its address, so a new IP address is still the same PC. The app notices when a PC stops answering, reconnects on its own and says why when it cannot, and warns if a PC answers with a different key than the one you paired. A PC is remembered only after you have confirmed its fingerprint and a secure connection has really worked. Optionally stays connected in the background with a notification that has play/pause, volume and disconnect.
- **Bluetooth:** the keyboard now sends system actions as the PC's own shortcuts, and tells you which characters it cannot type.
- **Changed:** PCs paired with 1.x are carried over, and the favorites list is folded into the device list.
- **Removed** the NDK UDP sender and the old screens.

### Project

- Added a [Code of Conduct](CODE_OF_CONDUCT.md) and a [privacy statement](PRIVACY.md) (Telepad collects nothing), and the issue templates ask for what is needed to look into a problem (versions, the log).
- The desktop programs ship with the licenses of the libraries inside them (`THIRD_PARTY_LICENSES.md`, made from `Cargo.lock` by `scripts/update-licenses.sh`, in the installer, the disk image, the tarball and the console servers' archives); the release stops if it is out of date.
- A dependency check (`cargo deny`: known vulnerabilities, licenses the project can ship, sources) runs for every platform when the dependencies change and every week.
- Manifests for package managers (Arch's AUR, winget, Scoop, Homebrew) and an F-Droid / IzzyOnDroid store listing are prepared in `packaging/` and `android/fastlane/`, with a script that fills them in from a release. The Arch package is tried with `makepkg`; the others are ready to submit. The Linux download includes a udev rule that lets the person at the computer use `/dev/uinput` without a group.

- The release workflow stops unless the tag, the app version and the server version agree, and its test step now runs the debug unit tests (the release variant cannot run the screen tests). It publishes every file under a name with the version and one without it (so `releases/latest/download/telepad-android.apk` keeps working), uses this changelog as the release text, and its `SHA256SUMS` no longer lists itself.
- CI checks formatting (`cargo fmt`), runs Android lint (errors fail the build), builds a minified release APK, and runs hundreds of automated tests, among them networking tests against a stand-in PC that speaks the real protocol, tests of every trust decision, and screen tests that render every screen into the pictures in the README.
- Added `CONTRIBUTING.md`, `SECURITY.md` and this file, issue and pull request templates, Dependabot, a manual test plan for real devices (`docs/manual-testing.md`), and release scripts (`scripts/`).
- Added a landing page (`website/`), the logo and the other brand files (`docs/brand/`).
- Android app versions now follow the project: `versionCode` is derived from `versionName`. (1.0.0 and 1.0.1 both shipped as `versionCode` 1.)

## [1.0.1] and [1.0.0]

The first releases: a Windows server written in C and the first version of the Android app.

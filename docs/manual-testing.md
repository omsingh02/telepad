# Manual test plan

The automated tests cover the logic, the network protocol against a stand-in PC, and every screen as a picture. They cannot cover real hardware: radios, the on-screen keyboard, other apps, a real mouse pointer. Run this before a release, with a phone and a PC on the same Wi-Fi. It takes about 20 minutes. Note the phone, Android version and PC operating system in the release pull request.

## Setup

1. Install the APK (`./gradlew assembleDebug`, or the release APK) on the phone.
2. On the PC, either install the app the way a person would (the Windows installer, the macOS disk image, the Linux tarball with `./install.sh`: see *Installing and the tray app* below), or build and start the console server with `cargo run --release -p telepad-server -- --verbose`. Linux needs the one-time `uinput` setup from the README, and macOS needs Accessibility permission.

## Pairing by QR code

Start with a PC that has never paired (delete its config folder, or use `--key-dir` on an empty one) and an app with no PCs.

- [ ] The PC shows a QR code on its page (tray app) or in the terminal (console server). **Scan QR code** on the phone's *Devices* tab opens the camera (the permission is asked for then, not at start) and a frame to aim at the code.
- [ ] Scanning pairs and connects with no further step: no fingerprint to compare, and the *Devices* list shows the PC as connected.
- [ ] The same code scanned by a second phone is refused (it works once). A new code (**Pair a phone…** or `qr`) works for the second phone.
- [ ] A code older than five minutes is refused, and the app says the PC did not accept the phone and what to do.
- [ ] Deny the camera permission: the scanner says why and has a button for the settings. *Add by address* and the list still work.
- [ ] The `telepad://pair?...` link, sent to the phone as a message and tapped, opens Telepad and pairs.
- [ ] On a PC with several networks (Wi-Fi plus Tailscale or Docker) the phone still finds the PC, whichever address answers.
- [ ] Console server on a terminal with a white background: the code scans (it is drawn inverted), and `qr light` draws it the other way.
- [ ] A cut-off code (a short terminal window) does not scan; the hint says to make the window taller.

## Pairing and trust

- [ ] First start of the app shows the walkthrough once. *Skip* works, and closing the app and opening it again goes straight to *Devices*.
- [ ] The PC appears under *Nearby* within a few seconds, **once**: a PC that also has Tailscale or Docker networks must not be listed a second time.
- [ ] Tapping it shows a code that equals the *Fingerprint* in the server banner (five groups of four).
- [ ] *They match* connects, and the server prints `paired new device ... Pairing is closed again`.
- [ ] A second phone, or the same app after clearing its data, is refused with "hasn't accepted this phone". After `pair` in the server console, it connects.
- [ ] Restart the server: the phone reconnects by itself (Settings → Connection → *Connect automatically*).
- [ ] Stop the server, delete its `identity.key`, start it again: the app says the PC "has a new identity" and asks for the code again. It does not connect silently.

## Trackpad

- [ ] One finger moves the pointer. Tap clicks. Two quick taps double-click.
- [ ] Tap, then touch and move: drags (select some text).
- [ ] Two-finger scroll, with natural scrolling on and off. Momentum after a flick. The scroll strip works with one finger.
- [ ] Press and hold, and a two-finger tap, right-click. A three-finger tap middle-clicks.
- [ ] *Left* held with one thumb while another finger drags selects text.
- [ ] Settings → Touchpad: speed and acceleration change the feel, and the pad there does not move the real pointer.

## Keyboard

The Keys tab is mostly about how it behaves with the phone's own keyboard, which only a real phone can show.

- [ ] Tap the typing line: the phone's keyboard opens and the line and the key bar stay visible directly above it, not hidden under it.
- [ ] Type a sentence with a deliberate typo (`teh `): the PC receives `the ` and never shows the typo. Swipe-type a word: it arrives when the swipe ends.
- [ ] Menu → *Send each key at once*: every letter appears on the PC as it is pressed, and the phone keyboard's suggestions are gone.
- [ ] An accented letter and an emoji appear on the PC. Holding Backspace on the phone's keyboard deletes repeatedly on the PC.
- [ ] Enter sends the line and the typing line is empty afterwards.
- [ ] Type `ls` without a space and tap *Tab* on the bar: the PC shows `ls` and then the Tab, in that order.
- [ ] Tap *Ctrl*, then `c` on the phone's keyboard: the PC copies, and Ctrl is no longer lit. The same with *Super* then `2` (on a tiling desktop, it switches workspace).
- [ ] Double-tap *Ctrl*: a lock icon shows, several letters give Ctrl+letter, and tapping it again unlocks.
- [ ] Hold *Super* alone for half a second: the PC sees Super pressed by itself (a launcher or an overview opens).
- [ ] *Fn* shows F1 to F12, Page Up and Down, Insert and Print Screen. Sliding that row presses no key by accident.
- [ ] Holding an arrow key on the bar repeats on the PC. Letting go stops it.
- [ ] Menu → *PC keyboard*: letters, digits, punctuation, F1 to F12, the editing keys and arrows fit with nothing to scroll sideways. A touch between two keys always presses one of them. Hold *Super* with one finger and tap `2` with another: Super+2. With *Shift* on, the keys show their shifted symbols. The choice is kept when you switch to the Pad and back.
- [ ] Held sideways, the PC keyboard is shown by itself, with the shortcuts in a row above it.
- [ ] Leave *Ctrl* on and open the Pad: a *Ctrl + …* pill shows above the pad, and tapping it lets go.
- [ ] Shortcut chips (Copy, Paste, Undo, Switch app ...) do the right thing. On a Mac they use ⌘.
- [ ] *Paste from phone* puts the phone's clipboard text on the PC. *Copy from PC* brings the PC's back.

## Media and actions

- [ ] Play/pause, next and previous control a media player on the PC.
- [ ] Volume up and down repeat while held. Mute works.
- [ ] Slides: next and previous move a presentation. *Black screen* works.
- [ ] *Show desktop*, *Task view*, *Screenshot*, *Browser*, *Files* and *Lock screen* each do what their name says.

## Staying connected

- [ ] Switch the phone's Wi-Fi off and on: the app shows *Reconnecting* and recovers without help.
- [ ] Stop the server while a key is held down: nothing stays pressed on the PC.
- [ ] Settings → Connection → *Stay connected in the background*: leave the app, and the notification's play, volume and disconnect buttons work. Swiping the app away behaves sensibly.

## Bluetooth

- [ ] *Add device → Bluetooth* walks through pairing the phone with the PC. The pad and the keyboard work.
- [ ] Typing a character the Bluetooth keyboard cannot send shows the notice.

## Looks and accessibility

- [ ] Light, dark and the accent colors. On Android 12 or later, *Colors from your wallpaper*.
- [ ] Font size at its largest and display size at its largest: nothing is cut off or overlapping.
- [ ] Landscape, and a tablet if you have one.
- [ ] TalkBack: the pad announces its actions, every button has a name, focus order is sensible.
- [ ] System animations turned off: nothing moves.
- [ ] **Settings → About:** the version is right; *Source code and downloads*, *Report a problem* and *Privacy* open the right pages; *Open-source licenses* lists the libraries (scroll it; each has a license) and Back returns to About.
- [ ] *Get Telepad for PC* (Devices, and the first-run walkthrough) opens the website's download section.

## Installing and the tray app

Do this on each PC operating system you can get hold of, with the file the release workflow made, not a local build.

- [ ] **Windows:** the installer runs without an administrator prompt; SmartScreen's warning (unsigned build) can be passed with *More info → Run anyway*. Telepad appears in the Start menu with its icon and in *Settings → Apps*. The finished page offers to open it; doing so opens the QR page. The tray icon is the Telepad icon.
- [ ] **Windows:** *Task Manager → Startup apps* lists **Telepad** (not `telepad.exe`) with the publisher, once *Start at login* is on. Sign out and in: Telepad is running, in the tray, and **no browser page opened by itself**.
- [ ] **Windows:** run the installer again while Telepad is running: it updates and Telepad is not left running from the old file. Uninstall: Telepad quits, the Start menu entry and the startup entry go, and the paired phones in `%APPDATA%\Telepad` stay.
- [ ] **macOS:** open the disk image, drag Telepad to Applications, open it (*Open Anyway* if the build is not notarized). There is a menu bar icon and no Dock icon. The Accessibility prompt appears; after allowing it, the phone's typing and clicks arrive. Click the icon: **Pair a phone…** opens the QR page.
- [ ] **macOS:** open Telepad again from Spotlight while it runs: the QR page opens (no second copy). *Start at login* adds a *Telepad* item under *System Settings → General → Login Items*; after logging in again it is running, quietly.
- [ ] **Linux:** `./install.sh` from the tarball adds Telepad to the applications menu with its icon. On KDE (or GNOME with the AppIndicator extension) the tray icon appears and its menu works. On plain GNOME there is no icon and starting Telepad opens the page. *Start at login* creates `~/.config/autostart/telepad.desktop`; after logging in again Telepad is running with no page opened. `./install.sh --uninstall` removes everything it added.
- [ ] **All:** the menu's status line follows reality: *Waiting for a phone* with nothing connected, *A phone is connected* while one is, and says so when nothing is paired yet.
- [ ] **All:** the page's footer shows the version and the **Updates**, **Report a problem**, **Privacy** and **Licenses** links open the right pages; "This PC" shows where the log file is, that file exists and holds readable lines (and no text you typed while testing the keyboard). Ask for the same log after typing a distinctive sentence from the phone, and look for it in the file: it must not be there.
- [ ] **All:** *Quit* from the menu or the page stops Telepad, and keys a phone was holding are released. `telepad --quit` from a terminal does the same.
- [ ] **All:** the page with the QR code opens from the menu or the icon's left click, counts its five minutes down, shows a new code on a reload, and says when a phone has paired. It does not open from another computer (try the address with this PC's network name instead of `127.0.0.1`: it is refused).

## Per platform

- **Linux:** works on Wayland and on X11. Without `uinput` permission the error says what to do. `--text-via-unicode` types accented letters.
- **macOS:** the first run prompts for Accessibility. Without it nothing moves. `--mac-ctrl-as-cmd` swaps the keys as described.
- **Windows:** the firewall prompt appears once. Input does not reach elevated windows unless the server runs as administrator.

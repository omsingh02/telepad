# Manual test plan

The automated tests cover the logic, the network protocol against a stand-in PC, and every screen as a picture. They cannot cover real hardware: radios, the on-screen keyboard, other apps, a real mouse pointer. Run this before a release, with a phone and a PC on the same Wi-Fi. It takes about 20 minutes. Note the phone, Android version and PC operating system in the release pull request.

## Setup

1. Install the APK (`./gradlew assembleDebug`, or the release APK) on the phone.
2. Build and start the server on the PC with `cargo run --release -p telepad-server -- --verbose`. Linux needs the one-time `uinput` setup from the README, and macOS needs Accessibility permission.

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

- [ ] Typing in the Keys tab appears in a PC text field, including autocorrect, backspace, an accented letter and an emoji.
- [ ] The full keyboard shows letters, digits, punctuation, F1 to F12, the editing keys and arrows, and nothing needs scrolling sideways.
- [ ] Hold *Super* with one finger and tap `2` with another: the PC sees Super+2 (on a tiling desktop, it switches workspace). Letting go of Super ends the chord.
- [ ] Tap *Super* once, then a key: Super+key, once. Double-tap it: it stays on (a lock icon shows) until tapped again.
- [ ] Hold *Super* alone for half a second: the PC sees Super pressed by itself (a launcher or an overview opens).
- [ ] With *Shift* held or latched the keys show their shifted symbols, and the PC types them.
- [ ] *Ctrl* tapped once, then `c`, copies.
- [ ] Shortcut chips (Copy, Paste, Undo, Switch app ...) do the right thing. On a Mac they use ⌘.
- [ ] Holding an arrow key or Backspace repeats on the PC. Letting go stops it.
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

## Per platform

- **Linux:** works on Wayland and on X11. Without `uinput` permission the error says what to do. `--text-via-unicode` types accented letters.
- **macOS:** the first run prompts for Accessibility. Without it nothing moves. `--mac-ctrl-as-cmd` swaps the keys as described.
- **Windows:** the firewall prompt appears once. Input does not reach elevated windows unless the server runs as administrator.

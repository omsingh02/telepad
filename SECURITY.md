# Security Policy

Telepad lets a phone type on, and move the mouse of, a computer. A flaw in it can be serious, so please report problems privately and give us a chance to fix them before they are public.

## Reporting a vulnerability

Use GitHub's private reporting: open the repository's **Security** tab and choose **Report a vulnerability**. Please do not open a public issue or pull request for a security problem.

Tell us what you found, how to reproduce it, which versions are affected and what an attacker gains. A short proof of concept helps. We aim to reply within a week, to keep you posted while we work on a fix, and to credit you in the release notes if you would like that.

## Supported versions

Security fixes go into the latest release. Version 1 (the Windows-only C server) accepted every phone that could reach it, so anyone on the same network could control the computer; if you still run it, please upgrade.

## What Telepad protects, and what it does not

### It protects against

- **Eavesdropping and tampering on your network.** Wi-Fi traffic is encrypted and authenticated from end to end with the Noise protocol (`Noise_IK_25519_ChaChaPoly_BLAKE2s`). Other devices on the network cannot read what you type or inject input of their own, and a recorded packet cannot be played back a second time.
- **Strangers controlling your PC.** The server only accepts phones that have been *paired*. A phone can pair only while a pairing window is open on the PC: during the first five minutes of the very first run, or after you type `pair` in the server's console (or start it with `--pair`). The window closes by itself as soon as one phone has paired, and when its time is up.
- **Something pretending to be your PC.** The phone remembers each PC by its public key, after you have compared the fingerprint the phone shows with the one on the PC. If a PC later answers with a different key, the phone warns you and does not connect silently.
- **Secrets at rest.** The phone's own key and its list of paired PCs are stored encrypted, with a key kept in the Android Keystore, and are excluded from backups. The PC's identity key is protected with DPAPI on Windows and readable only by your user (file mode `0600`) on Linux and macOS.

### It does not protect against

- **Anyone on your network while pairing is open.** Pairing is approved by *opening the window*, not by confirming each phone on the PC: while it is open, any device that reaches the server can pair. That is why it is short, closes after one phone, and is closed by default. Open it only when you are about to pair, on a network you trust. The server console prints each phone it pairs, and `list` shows who is paired.
- **A fingerprint you do not compare.** The check that stops a fake PC is you comparing the code on the phone with the code on the PC. Tapping *They match* without looking gives that protection up. (The code is 80 bits of a hash of the key, so a look-alike key cannot be found in practice.)
- **A compromised phone or PC.** Malware on the phone can use the paired connection. Malware on the PC runs as you, so it can read the identity key and, of course, already controls the machine.
- **Someone holding your unlocked phone.** A remote control is a remote control; Telepad adds no lock of its own. Use your phone's screen lock.
- **Losing a phone.** Today the PC can only forget *every* paired phone at once (`forget` in the server console); then pair the phones you still use again. The phone side can replace its own key under *Settings → Privacy and security*.
- **Network-level denial of service.** Anyone on your network can jam Wi-Fi or send junk to the server's UDP port. The server drops what does not authenticate and limits how many sessions it keeps, but does not promise to stay responsive under a deliberate flood.
- **The internet.** Telepad is meant for a trusted local network. Do not forward UDP port 5000 to the internet.
- **Being noticed.** Discovery is unauthenticated: anyone on the network can learn that a PC runs Telepad, its name and its public key. The public key is not a secret.
- **Bluetooth mode.** There, security is whatever the Bluetooth link between the phone and the PC negotiated (ordinary Bluetooth pairing and link encryption). Telepad adds nothing to it.
- **What you send to the PC's clipboard.** It is sent only when you press the button, and encrypted on the way, but once it is on the PC's clipboard, other programs on the PC can read it.

## Where the keys live

| | Location |
| :--- | :--- |
| PC identity key | Windows `%APPDATA%\Telepad\identity.key` (DPAPI); macOS `~/Library/Application Support/Telepad/identity.key`; Linux `$XDG_CONFIG_HOME/telepad/identity.key` (default `~/.config/telepad/`), mode `0600`. Override the folder with `--key-dir`. |
| Paired phones (PC side) | `trusted_clients.json` in the same folder: the phones' public keys, nothing secret. |
| Phone's key and paired PCs | In the app's private storage, encrypted with `EncryptedSharedPreferences` and an Android Keystore key. |

Deleting `identity.key` gives the PC a new identity: every phone will then warn that the PC *has a new identity*, and you will compare the fingerprint again.

## Hardening tips

- Leave the default pairing policy alone. `--insecure-accept-any-client` accepts every phone forever and exists only for networks you completely control.
- Allow UDP port 5000 only from your local network in the PC's firewall.
- Compare the fingerprints. It takes five seconds, once per PC.
- After a phone is lost or sold, type `forget` on each PC it was paired with.

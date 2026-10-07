# Privacy

Telepad collects nothing. There is no account, no analytics, no advertising, no crash reporting and no server run by the developers that the app or the PC program talks to.

## What goes where

| What | Where it goes |
| :--- | :--- |
| What you type, tap and move on the phone | To the PC you paired it with, over your own Wi-Fi network (encrypted end to end) or over Bluetooth. Nowhere else. |
| What you send to the PC's clipboard, or copy from it | Only when you press the button, to that one PC or phone, encrypted. |
| The PC's name and addresses | In the QR code on the PC's screen, and in answers to the phone's search for PCs on the same network. The search is a broadcast on your local network. |
| Where the phone and the PC keep their keys and the list of paired devices | On the phone (in the app's private, encrypted storage, excluded from backups) and on the PC (in its Telepad folder, readable by you only). They are never uploaded. |
| A log file (the desktop app) | On the PC only, in Telepad's folder. It holds what the program did (starting, a phone connecting) and no typed text. Nothing sends it anywhere: if you report a problem, you choose whether to paste from it. |

## What the programs do not do

- They make no connection to the internet of their own. The desktop app serves its QR-code page to the same computer only (`127.0.0.1`), and the phone app talks only to your PC. (Your browser opens the project's website or GitHub only when you choose a link such as *Report a problem*.)
- They do not check for updates. New versions are on the [releases page](https://github.com/omsingh02/telepad/releases).
- The phone app asks for the camera only when you open the QR scanner, and uses it only to read the code. Nothing is recorded or kept. The Bluetooth permission is asked for only when you set up Bluetooth mode.

## Third parties

Telepad uses no third-party online service. GitHub hosts the source code and the downloads, and Vercel hosts the project's website; those are not part of the app, and your use of them is covered by their own policies. The website has no analytics or cookies.

## Questions

Open an [issue or discussion](https://github.com/omsingh02/telepad), or see [SECURITY.md](SECURITY.md) for how the connection is protected. If this ever changes, this file and the changelog will say so first.

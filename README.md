<div align="center">
  <h1>🕹️ WinRemotePad</h1>
  <p><strong>A beautifully simple, zero-dependency local remote control for your Windows PC.</strong></p>
  
  <p>
    <a href="https://github.com/omsingh02/WinRemotePad/stargazers"><img src="https://img.shields.io/github/stars/omsingh02/WinRemotePad?style=flat-square&color=yellow" alt="Stars" /></a>
    <a href="https://github.com/omsingh02/WinRemotePad/network/members"><img src="https://img.shields.io/github/forks/omsingh02/WinRemotePad?style=flat-square&color=blue" alt="Forks" /></a>
    <a href="https://github.com/omsingh02/WinRemotePad/issues"><img src="https://img.shields.io/github/issues/omsingh02/WinRemotePad?style=flat-square&color=red" alt="Issues" /></a>
    <img src="https://img.shields.io/badge/Python-Standard_Library-blue?style=flat-square&logo=python&logoColor=white" alt="Python" />
    <img src="https://img.shields.io/badge/Windows-Native_API-lightgrey?style=flat-square&logo=windows&logoColor=blue" alt="Windows" />
  </p>
</div>

<br />

**WinRemotePad** is a blazing-fast WebSocket server built entirely on Python's standard library. It instantly turns your smartphone into a premium remote control—giving you full access to your Windows desktop trackpad, media controls, and keyboard, without touching a single `pip install` or hefty GUI framework.

---

## ✨ Features

- **Responsive Touchpad:** Seamless raw mouse movements. Tap to click, two-finger tap for right-click, and drag two fingers up/down to scroll.
- **Media Mastery:** Play/Pause, Next/Prev, Volume Up/Down, and Mute are just one tap away.
- **Full Keyboard Emulation:** Start typing naturally on your phone to send text instantly. Features dedicated toggle buttons for native modifiers (`Ctrl`, `Alt`, `Shift`, and `Win`).
- **Zero-Bloat Architecture:** No Node.js, no hefty web-framework overhead, and absolutely zero external dependencies. Pure standard Python `socket` threading alongside raw `ctypes` bindings.

---

## 🚀 Quick Setup

First, ensure your phone and PC are connected to the **same Wi-Fi network**.

### 1. Run the server
Clone the repository and start the server natively from your terminal.

```bash
git clone https://github.com/omsingh02/WinRemotePad.git
cd WinRemotePad
python src/main.py
```

### 2. Connect from your device
Once running, the Python console will print a secure local network address:
```text
  WinRemotePad Server
  http://192.168.X.X:5000
```
Open that URL in your phone's browser, and you are immediately connected!

---

## 🛠️ Advanced Details

<details>
<summary><strong>Project Structure</strong></summary>
<br>

- `src/main.py`: A brilliantly simple backend that handles both standard HTTP fetching and WebSocket handshakes, injecting native Windows API events directly via `ctypes.windll.user32`.
- `src/index.html`: The lightweight frontend GUI. Designed strictly with system fonts and generic mobile-first CSS for near-zero latency rendering and parsing.
</details>

<details>
<summary><strong>Security & Connectivity</strong></summary>
<br>

When executing `main.py`, the socket server binds directly to `0.0.0.0:5000` to be accessible across your WLAN. For optimal security, this should only be run on a trusted home network. If you encounter issues connecting, momentarily verify if your Windows Firewall is blocking incoming Python network connections.
</details>

---

## ⌨️ Contribution

Built for hackers, automation enthusiasts, and couch-potatoes alike. Feel free to fork, experiment, and submit Pull Requests for any new macros or interface upgrades!

<br />

<div align="center">
  <sub>Extracted, refined, and made production-ready with ❤️ based on KDE Connect utilities.</sub>
</div>

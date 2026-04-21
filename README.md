<div align="center">
  <h1>🕹️ Telepad</h1>
  <p><strong>A beautifully simple, local remote control for your Windows & Linux PCs.</strong></p>
  
  <p>
    <a href="https://github.com/omsingh02/telepad/stargazers"><img src="https://img.shields.io/github/stars/omsingh02/telepad?style=flat-square&color=yellow" alt="Stars" /></a>
    <a href="https://github.com/omsingh02/telepad/network/members"><img src="https://img.shields.io/github/forks/omsingh02/telepad?style=flat-square&color=blue" alt="Forks" /></a>
    <a href="https://github.com/omsingh02/telepad/issues"><img src="https://img.shields.io/github/issues/omsingh02/telepad?style=flat-square&color=red" alt="Issues" /></a>
    <img src="https://img.shields.io/badge/Python-3.x-blue?style=flat-square&logo=python&logoColor=white" alt="Python" />
    <img src="https://img.shields.io/badge/Cross_Platform-Win_|_Linux-lightgrey?style=flat-square" alt="OS" />
  </p>
</div>

<br />

**Telepad** is a blazing-fast WebSocket server built mostly on Python's standard library. It instantly turns your smartphone into a premium remote control—giving you full access to your desktop trackpad, media controls, and keyboard, without touching heavy GUI frameworks. Built to flawlessly orchestrate raw native APIs.

---

## ✨ Features

- **Responsive Touchpad:** Seamless raw mouse movements. Tap to click, two-finger tap for right-click, and drag two fingers up/down to scroll.
- **Media Mastery:** Play/Pause, Next/Prev, Volume Up/Down, and Mute are just one tap away.
- **Full Keyboard Emulation:** Start typing naturally on your phone to send text instantly. Features dedicated toggle buttons for native modifiers (`Ctrl`, `Alt`, `Shift`, and `Win`/`Super`).
- **Zero-Bloat Frontend:** No Node.js, no hefty web-framework overhead. The single local `index.html` file runs entirely with optimized CSS and minimal socket hooks.

---

## 🚀 Quick Setup

First, ensure your phone and PC are connected to the **same Wi-Fi network**.

### Windows (Zero Dependency)
Clone the repository and start the server. No `pip` install needed!
```bash
git clone https://github.com/omsingh02/telepad.git
cd telepad
python src/main.py
```

### Linux (Wayland / X11 Compatible)
Linux requires the `evdev` package to inject inputs directly into the kernel's `/dev/uinput` mapping, ensuring perfect performance across even strict Wayland compositors (like Hyprland)!
```bash
git clone https://github.com/omsingh02/telepad.git
cd telepad

# Install evdev (Either via pip or your system package manager)
pip install -r requirements-linux.txt 
# or: sudo pacman -S python-evdev 

# Run the server. Elevated permission is required to access /dev/uinput!
sudo python src/main.py
```
> **Note:** If you do not want to run `sudo`, ensure your user belongs to the `input` group and your system has a udev rule permitting `/dev/uinput` read/write access.

---

### Connect from your device
Once running, the Python console will print a secure local network address:
```text
  Telepad Server Running
  http://192.168.X.X:5000
```
Open that URL in your phone's browser, and you are immediately connected!

---

## 🛠️ Advanced Details

<details>
<summary><strong>Project Structure</strong></summary>
<br>

- `src/main.py`: The unified backend. It abstracts OS boundaries using an `InputController` layout. On Windows it uses `ctypes.windll.user32`, and on Linux, it bootstraps a persistent virtual `evdev.UInput` device.
- `src/index.html`: The lightweight frontend GUI. Designed strictly with system fonts and generic mobile-first CSS for near-zero latency rendering and parsing.
</details>

<details>
<summary><strong>Security & Connectivity</strong></summary>
<br>

When executing `main.py`, the socket server binds directly to `0.0.0.0:5000` to be accessible across your WLAN. For optimal security, this should only be run on a trusted home network. If you encounter issues connecting, momentarily verify if your Firewall is blocking incoming Python network connections.
</details>

---

## ⌨️ Contribution

Built for hackers, automation enthusiasts, and couch-potatoes alike. Feel free to fork, experiment, and submit Pull Requests for any new macros or interface upgrades!

<br />

<div align="center">
  <sub>Extracted, refined, and modernized with ❤️ based on KDE Connect utilities.</sub>
</div>

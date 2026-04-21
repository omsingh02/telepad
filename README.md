<div align="center">
  <h1>✨ WinRemotePad ✨</h1>
  <p><strong>A blazing fast, zero-dependency Python server to control your Windows PC directly from your phone's browser.</strong></p>

  <p>
    <a href="https://github.com/omsingh02/WinRemotePad/stargazers"><img src="https://img.shields.io/github/stars/omsingh02/WinRemotePad?style=flat-square&color=yellow" alt="Stars" /></a>
    <a href="https://github.com/omsingh02/WinRemotePad/network/members"><img src="https://img.shields.io/github/forks/omsingh02/WinRemotePad?style=flat-square&color=blue" alt="Forks" /></a>
    <a href="https://github.com/omsingh02/WinRemotePad/issues"><img src="https://img.shields.io/github/issues/omsingh02/WinRemotePad?style=flat-square&color=red" alt="Issues" /></a>
    <img src="https://img.shields.io/badge/Python-3.x-blue?style=flat-square&logo=python&logoColor=white" alt="Python 3.x" />
    <img src="https://img.shields.io/badge/OS-Windows-lightgrey?style=flat-square&logo=windows&logoColor=blue" alt="Windows" />
  </p>
</div>

<hr />

## 🌟 Overview

**WinRemotePad** turns your smartphone into a fully-featured, ultra-responsive remote control for your Windows desktop. Built entirely on standard Python libraries, it doesn't require any hefty installations or `pip` packages. Just run the script, open the link on your phone, and command your PC!

---

## ⚡ Features

* **🖱️ Responsive Trackpad**: Seamless mouse movement, tap-to-click, two-finger right-click, and two-finger dragging for scrolling.
* **🎵 Media Controls**: Play, pause, skip tracks, and adjust the volume effortlessly from across the room.
* **⌨️ Full Keyboard Support**: Type naturally on your phone, and the text will be instantly sent to your PC. Includes common shortcuts and modifier keys (`Ctrl`, `Alt`, `Shift`, `Win`).
* **🔒 Quick Lock**: Put your PC to sleep or lock the screen with a single tap.
* **🚀 Zero Dependencies**: Runs cleanly relying purely on `ctypes`, basic WebSockets, and Python's built-ins!

---

## 📦 Requirements

* **Operating System**: **Windows** (Uses `ctypes.windll.user32` to precisely mimic mouse and keyboard hardware inputs).
* **Environment**: **Python 3.x** installed.
* **Network**: The host PC and your smartphone must be connected to the **same local area network (Wi-Fi)**.

---

## 🚀 Quick Start Guide

1. **Clone the repository**:
   ```bash
   git clone https://github.com/omsingh02/WinRemotePad.git
   cd WinRemotePad
   ```

2. **Launch the Server**:
   ```bash
   python src/main.py
   ```

3. **Connect Your Phone**:
   Watch the console output; it will display your local IP and port (e.g., `http://192.168.1.100:5000`). 
   Type that URL into your smartphone's web browser, and you are ready to shine! ✨

---

## 🏗️ Project Structure

Everything stays perfectly organized:
```text
WinRemotePad/
├── src/
│   ├── main.py        # The WebSocket server & Windows API bridge
│   └── index.html     # The beautiful mobile web UI
├── .gitignore
└── README.md          # You are here!
```

---

## 🤝 Contributing

Contributions, issues, and feature requests are always welcome! Feel free to check the [issues page](https://github.com/omsingh02/WinRemotePad/issues) if you want to contribute.

## 📝 Credits

This standalone project was expertly extracted, refined, and made production-ready from the original KDE Connect remote-control script structure.

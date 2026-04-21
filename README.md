# WinRemotePad

A lightweight, zero-dependency Python server that allows you to control a Windows PC from your phone's web browser. It features a responsive trackpad, media controls, and keyboard input over WebSockets.

## Requirements

- **OS:** Windows (Uses `ctypes.windll.user32` to simulate mouse/keyboard functionality)
- **Python:** 3.x (Built using only standard library modules, no `pip install` necessary)

## Features

- **Trackpad:** Move mouse, tap to click, two-finger tap to right-click, two-finger drag to scroll.
- **Media Controls:** Play/Pause, Next/Prev, Volume Up/Down, Mute.
- **Keyboard:** Full keyboard support including typing from phone, or sending specific combinations (Ctrl, Alt, Shift, Win).
- **System:** Lock PC screen instantly.

## Usage

1. Clone or download this repository to your Windows PC.
2. Ensure both your PC and your phone are connected to the same local network.
3. Run the script:
   ```bash
   python src/main.py
   ```
4. The console will display a local URL (e.g., `http://192.168.1.100:5000`).
5. Open this URL in your phone's browser.

## Project Structure

- `src/main.py` - The Python WebSocket server and input simulation script.
- `src/index.html` - The mobile web interface. 

## Credits
Extracted and made production-ready from the `kde-connect` remote control script.

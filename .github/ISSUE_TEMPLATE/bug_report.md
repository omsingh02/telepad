---
name: Bug report
about: Something does not work the way it should
labels: bug
---

**What happened**
What you did, what you expected, and what happened instead.

**How to reproduce it**
1.
2.

**Your setup**
- Phone and Android version:
- PC operating system (on Linux also the desktop, and whether it runs Wayland or X11):
- Telepad app version (Settings → About):
- Telepad on the PC: version (the footer of its page, or `telepad --version`), and whether you use the tray app or the console server:
- Connected over: Wi-Fi / Bluetooth
- How you installed it (installer, disk image, tarball, package manager):

**The log**
If the problem is about connecting or about input, attach the desktop app's log: `telepad.log` in Telepad's folder (its page shows the path; on Windows `%APPDATA%\Telepad`, on macOS `~/Library/Application Support/Telepad`, on Linux `~/.config/telepad`). It holds what the program did, never what you typed, but it has your PC's name and your phones' addresses, so look through it first. For more detail, start the program with `--verbose`, reproduce the problem, and attach what it wrote.

A screenshot or a short screen recording helps with anything you can see.

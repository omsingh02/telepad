#!/bin/sh
# After the package is installed: let the person at this computer use /dev/uinput, the virtual keyboard and mouse
# that Telepad types with, now, without waiting for a reboot. Every step is allowed to fail: a container, or a
# computer without udev, simply goes without, and the program says what is missing when it starts.
if command -v modprobe > /dev/null 2>&1; then
    modprobe uinput 2> /dev/null || true
fi
if command -v udevadm > /dev/null 2>&1; then
    udevadm control --reload-rules 2> /dev/null || true
    udevadm trigger --subsystem-match=misc --sysname-match=uinput 2> /dev/null || true
fi

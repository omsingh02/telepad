#!/bin/sh
# After the package is removed: the rule that came with it goes, so tell udev. (The paired phones stay in each
# person's own config folder, ~/.config/telepad, so that installing again keeps them.)
if command -v udevadm > /dev/null 2>&1; then
    udevadm control --reload-rules 2> /dev/null || true
fi

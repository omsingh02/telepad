#!/bin/sh
# Runs INSIDE an Arch container (see run.sh): installs the package with pacman -U, checks what it did, removes it
# and checks that it is gone. /pkg holds the packages, and $1 the version the program must report.
set -eu
fail() { echo "FAIL: $*" >&2; exit 1; }
expected="$1"

pacman -Sy --noconfirm > /dev/null 2>&1
pacman -U --noconfirm /pkg/*.pkg.tar.zst > /tmp/install.log 2>&1 || { cat /tmp/install.log; fail "pacman could not install the package"; }
echo "installed: $(pacman -Q telepad)"

[ "$(telepad --version)" = "telepad $expected" ] || fail "telepad --version says '$(telepad --version)', not 'telepad $expected'"
for f in /usr/bin/telepad /usr/share/applications/telepad.desktop /usr/lib/udev/rules.d/60-telepad-uinput.rules \
    /usr/lib/modules-load.d/telepad.conf /usr/share/licenses/telepad/LICENSE /usr/share/licenses/telepad/THIRD_PARTY_LICENSES.md \
    /usr/share/icons/hicolor/scalable/apps/telepad.svg /usr/share/icons/hicolor/48x48/apps/telepad.png; do
    [ -e "$f" ] || fail "$f is missing"
done
echo "depends: $(pacman -Qi telepad | sed -n 's/^Depends On *: //p')"

pacman -R --noconfirm telepad > /dev/null 2>&1
for f in /usr/bin/telepad /usr/lib/udev/rules.d/60-telepad-uinput.rules /usr/share/applications/telepad.desktop; do
    [ ! -e "$f" ] || fail "$f is still there after removing the package"
done
echo "ok: installs, runs, and removes cleanly"

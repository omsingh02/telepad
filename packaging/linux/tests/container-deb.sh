#!/bin/sh
# Runs INSIDE a Debian-family container (see run.sh): installs the .deb the way a person would, checks what it did,
# removes it and checks that it is gone. /pkg holds the packages, and $1 the version the program must report.
set -eu
export DEBIAN_FRONTEND=noninteractive
fail() { echo "FAIL: $*" >&2; exit 1; }
expected="$1"

apt-get update -qq > /dev/null
apt-get install -y -qq /pkg/*.deb > /tmp/install.log 2>&1 || { cat /tmp/install.log; fail "apt could not install the package"; }
echo "installed: $(dpkg-query -W -f='${Package} ${Version} ${Architecture}' telepad)"

[ "$(telepad --version)" = "telepad $expected" ] || fail "telepad --version says '$(telepad --version)', not 'telepad $expected'"
for f in /usr/bin/telepad /usr/share/applications/telepad.desktop /usr/lib/udev/rules.d/60-telepad-uinput.rules \
    /usr/lib/modules-load.d/telepad.conf /usr/share/doc/telepad/copyright \
    /usr/share/icons/hicolor/scalable/apps/telepad.svg /usr/share/icons/hicolor/48x48/apps/telepad.png; do
    [ -e "$f" ] || fail "$f is missing"
done
# Ubuntu's container images leave out most of /usr/share/doc (all but the copyright files), so this one is asked of
# the package's own file list rather than of the disk.
dpkg -L telepad | grep -q '/usr/share/doc/telepad/THIRD_PARTY_LICENSES.md$' || fail "the third-party licenses are not in the package"
# A missing library is reported as "libfoo.so => not found". (A binary built on a newer system also names weak
# symbols of newer glibc versions, which ldd mentions as "weak version ... not found": harmless, the program runs.)
[ "$(ldd /usr/bin/telepad | grep -c '=> not found' || true)" = 0 ] || fail "a library the program needs is missing"
grep -q uinput /usr/lib/modules-load.d/telepad.conf || fail "the module list does not name uinput"
grep -q 'TAG+="uaccess"' /usr/lib/udev/rules.d/60-telepad-uinput.rules || fail "the udev rule does not give the person access"
apt-get install -y -qq desktop-file-utils > /dev/null 2>&1
desktop-file-validate /usr/share/applications/telepad.desktop || fail "the desktop entry is not valid"
[ -e /etc/ufw/applications.d/telepad ] || fail "the ufw profile is missing"
apt-get install -y -qq ufw > /dev/null 2>&1
ufw app info Telepad | grep -q '5000/udp' || fail "ufw does not know Telepad as 5000/udp"
echo "dependencies: $(dpkg-query -W -f='${Depends}' telepad)"

apt-get remove -y -qq telepad > /dev/null 2>&1
for f in /usr/bin/telepad /usr/lib/udev/rules.d/60-telepad-uinput.rules /usr/share/applications/telepad.desktop; do
    [ ! -e "$f" ] || fail "$f is still there after removing the package"
done
echo "ok: installs, runs, and removes cleanly"

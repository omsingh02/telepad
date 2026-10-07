#!/bin/sh
# Runs INSIDE a Fedora-family container (see run.sh): installs the .rpm, checks what it did, removes it and checks
# that it is gone. /pkg holds the packages, and $1 the version the program must report.
set -eu
fail() { echo "FAIL: $*" >&2; exit 1; }
expected="$1"

dnf install -y -q /pkg/*.rpm > /tmp/install.log 2>&1 || { cat /tmp/install.log; fail "dnf could not install the package"; }
echo "installed: $(rpm -q --qf '%{NAME} %{VERSION} release %{RELEASE} %{ARCH}' telepad)"

[ "$(telepad --version)" = "telepad $expected" ] || fail "telepad --version says '$(telepad --version)', not 'telepad $expected'"
for f in /usr/bin/telepad /usr/share/applications/telepad.desktop /usr/lib/udev/rules.d/60-telepad-uinput.rules \
    /usr/lib/modules-load.d/telepad.conf /usr/share/licenses/telepad/LICENSE /usr/share/licenses/telepad/THIRD_PARTY_LICENSES.md \
    /usr/share/icons/hicolor/scalable/apps/telepad.svg /usr/share/icons/hicolor/48x48/apps/telepad.png; do
    [ -e "$f" ] || fail "$f is missing"
done
rpm -q --scripts telepad | grep -q udevadm || fail "the package does not tell udev about its rule"
echo "requires: $(rpm -q --requires telepad | grep -v -E '^(rpmlib|/bin/sh)' | tr '\n' ',')"
dnf install -y -q desktop-file-utils > /dev/null 2>&1
desktop-file-validate /usr/share/applications/telepad.desktop || fail "the desktop entry is not valid"

dnf remove -y -q telepad > /dev/null 2>&1
for f in /usr/bin/telepad /usr/lib/udev/rules.d/60-telepad-uinput.rules /usr/share/applications/telepad.desktop; do
    [ ! -e "$f" ] || fail "$f is still there after removing the package"
done
echo "ok: installs, runs, and removes cleanly"

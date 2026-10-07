#!/bin/sh
# Runs INSIDE a container (see installer.sh): the one-line installer, piped into sh the way the website says, then
# the program, then --uninstall. $1 is the version the program must report, $2 the kind of system (deb, rpm, arch).
set -eu
fail() { echo "FAIL: $*" >&2; exit 1; }
expected="$1"
kind="$2"

# What a real computer already has: something to download with (Fedora and Arch bring curl; Debian and Ubuntu's
# container images do not), and sudo for the part that tries it as an ordinary person.
case "$kind" in
    deb) export DEBIAN_FRONTEND=noninteractive; apt-get update -qq > /dev/null; apt-get install -y -qq curl ca-certificates sudo > /dev/null 2>&1 ;;
    rpm) dnf install -y -q sudo > /dev/null 2>&1 || true; command -v curl > /dev/null 2>&1 || dnf install -y -q curl > /dev/null 2>&1 ;;
    arch) pacman -Sy --noconfirm > /dev/null 2>&1; pacman -S --noconfirm --needed curl sudo > /dev/null 2>&1 ;;
esac
command -v curl > /dev/null 2>&1 || fail "the test container has no curl"

echo "--- as root, piped into sh"
cat /installer/install.sh | sh > /tmp/install.log 2>&1 || { cat /tmp/install.log; fail "the installer failed"; }
cat /tmp/install.log
[ "$(telepad --version)" = "telepad $expected" ] || fail "telepad --version says '$(telepad --version)', not 'telepad $expected'"
[ -e /usr/lib/udev/rules.d/60-telepad-uinput.rules ] || fail "the system package did not put the udev rule in place"
grep -q "installed with\|Installing with" /tmp/install.log || fail "the installer did not say how it installed"
cat /installer/install.sh | sh -s -- --uninstall > /tmp/uninstall.log 2>&1 || { cat /tmp/uninstall.log; fail "uninstalling failed"; }
[ ! -e /usr/bin/telepad ] || fail "uninstalling left the program behind"
echo "ok: installed as a $kind package and removed again"

echo "--- in a home folder (--user), no password"
cat /installer/install.sh | sh -s -- --user > /tmp/user.log 2>&1 || { cat /tmp/user.log; fail "the --user install failed"; }
[ "$("$HOME/.local/bin/telepad" --version)" = "telepad $expected" ] || fail "the program in ~/.local does not run"
[ -e "$HOME/.local/share/applications/telepad.desktop" ] || fail "no applications-menu entry in ~/.local"
cat /installer/install.sh | sh -s -- --uninstall > /tmp/user-uninstall.log 2>&1 || { cat /tmp/user-uninstall.log; fail "uninstalling the home-folder install failed"; }
[ ! -e "$HOME/.local/bin/telepad" ] || fail "uninstalling left ~/.local/bin/telepad behind"
echo "ok: installed into ~/.local and removed again"

# Someone who is not root, and asks for a password with sudo (here it never asks).
if [ "$kind" = deb ]; then
    echo "--- as an ordinary person with sudo"
    useradd -m person
    echo 'person ALL=(ALL) NOPASSWD:ALL' > /etc/sudoers.d/person
    cp /installer/install.sh /tmp/install-for-person.sh
    chmod 644 /tmp/install-for-person.sh
    su person -c 'cat /tmp/install-for-person.sh | sh' > /tmp/person.log 2>&1 || { cat /tmp/person.log; fail "the installer failed for an ordinary person"; }
    [ "$(telepad --version)" = "telepad $expected" ] || fail "the program is not installed for the ordinary person's install"
    su person -c 'cat /tmp/install-for-person.sh | sh -s -- --uninstall' > /dev/null 2>&1
    [ ! -e /usr/bin/telepad ] || fail "an ordinary person could not remove it"
    echo "ok: sudo install and removal as an ordinary person"
fi

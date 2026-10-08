#!/bin/sh
# Installs Telepad on Linux, the way that suits the system:
#
#   curl -fsSL https://telepad-app.vercel.app/install.sh | sh
#
# On Debian, Ubuntu, Mint and the like it installs the .deb with apt; on Fedora and openSUSE the .rpm; on Arch and
# Manjaro the Arch package. Those are system packages: they put Telepad in the applications menu, set up the
# permission it needs to type (/dev/uinput), and are removed with the system's own tools. Anywhere else it unpacks
# the download into your home folder (~/.local), which needs no password at all.
#
# It downloads from this project's GitHub releases, checks the file against the release's SHA256SUMS, and says what
# it is about to do before it does it. Read it first if you like: it is this file, https://telepad-app.vercel.app/install.sh,
# or https://github.com/omsingh02/telepad/blob/main/website/install.sh.
#
# Options, after `sh -s --` when piping:
#   --user           install into ~/.local with no password, even where a package would do
#   --version TAG    a particular release, such as v2.0.0 (the newest one with a Linux download is the default)
#   --uninstall      remove Telepad again, however it was installed
#   --help           this text
set -eu

REPO="${TELEPAD_REPO:-omsingh02/telepad}"
# The list of releases is the releases feed, an ordinary page of github.com. (GitHub's programming interface would also
# do, but it allows an address only 60 requests an hour, which a campus or a mobile network shares between thousands.)
FEED="${TELEPAD_FEED:-https://github.com/$REPO/releases.atom}"
DOWNLOADS="${TELEPAD_DOWNLOADS:-https://github.com/$REPO/releases/download}"

if [ -t 1 ]; then
    BOLD="$(printf '\033[1m')"; RED="$(printf '\033[31m')"; YELLOW="$(printf '\033[33m')"; OFF="$(printf '\033[0m')"
else
    BOLD="" RED="" YELLOW="" OFF=""
fi
say()  { printf '%s==>%s %s\n' "$BOLD" "$OFF" "$*"; }
note() { printf '    %s\n' "$*"; }
warn() { printf '%swarning:%s %s\n' "$YELLOW" "$OFF" "$*" >&2; }
die()  { printf '%serror:%s %s\n' "$RED" "$OFF" "$*" >&2; exit 1; }

usage() {
    cat <<'TEXT'
Installs Telepad on Linux.

  curl -fsSL https://telepad-app.vercel.app/install.sh | sh
  curl -fsSL https://telepad-app.vercel.app/install.sh | sh -s -- --user

Options:
  --user           install into ~/.local with no password, even where a package would do
  --version TAG    a particular release, such as v2.0.0 (the newest with a Linux download is the default)
  --uninstall      remove Telepad again, however it was installed
  --help           this text
TEXT
}

mode=install
force_user=0
want=""
while [ "$#" -gt 0 ]; do
    case "$1" in
        --user) force_user=1 ;;
        --uninstall) mode=uninstall ;;
        --version)
            [ "$#" -ge 2 ] || die "--version needs a release, such as v2.0.0"
            want="$2"; shift
            printf '%s' "$want" | grep -Eq '^v?[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.]+)?$' || die "that is not a release number (try v2.0.0)" ;;
        --help|-h) usage; exit 0 ;;
        *) die "unknown option $1 (see --help)" ;;
    esac
    shift
done

# --- What this is running on ---------------------------------------------------------------
[ "$(uname -s)" = Linux ] || die "this installer is for Linux. Windows and macOS have their own downloads: https://telepad-app.vercel.app/#download"
case "$(uname -m)" in
    x86_64|amd64) ;;
    *) die "Telepad is built for 64-bit Intel and AMD processors ($(uname -m) is something else). You can build it from source: https://github.com/$REPO#building-from-source" ;;
esac
if ldd --version 2>&1 | grep -qi musl; then
    die "Telepad needs the GNU C library (glibc), and this system (Alpine, for example) uses musl."
fi

have() { command -v "$1" > /dev/null 2>&1; }
if have curl; then
    fetch() { curl -fsSL --retry 2 -o "$2" "$1"; }
    fetch_text() { curl -fsSL --retry 2 "$1"; }
    exists() { curl -fsIL "$1" > /dev/null 2>&1; }
elif have wget; then
    fetch() { wget -q -O "$2" "$1"; }
    fetch_text() { wget -q -O - "$1"; }
    exists() { wget -q --spider "$1" > /dev/null 2>&1; }
else
    die "this needs curl or wget to download Telepad: install one (for example: sudo apt install curl) and run it again"
fi

# Who may change the system: root, or someone who can ask for it with sudo.
as_root=""
can_root=1
if [ "$(id -u)" -ne 0 ]; then
    if have sudo; then as_root="sudo"; elif have doas; then as_root="doas"; else can_root=0; fi
fi

# What to tell the person about the password: only when one will be asked for (not for root).
asks=""
if [ -n "$as_root" ]; then asks=" (it asks for your password)"; fi

# The way to install: a package of this system's kind, or the plain download in ~/.local.
method=tarball
manager=""
if [ "$force_user" = 0 ] && [ "$can_root" = 1 ]; then
    if have apt-get && have dpkg; then method=deb; manager=apt-get
    elif have dnf; then method=rpm; manager=dnf
    elif have zypper; then method=rpm; manager=zypper
    elif have yum; then method=rpm; manager=yum
    elif have pacman; then method=arch; manager=pacman
    fi
fi

# --- Taking Telepad away -------------------------------------------------------------------
# A package of this system, and/or an install in the home folder: either, or both, can be there.
packaged=""
if have dpkg-query && [ "$(dpkg-query -W -f='${Status}' telepad 2> /dev/null)" = "install ok installed" ]; then packaged=deb
elif have rpm && rpm -q telepad > /dev/null 2>&1; then packaged=rpm
elif have pacman && pacman -Q telepad > /dev/null 2>&1; then packaged=arch
fi
in_home=0
if [ -x "${HOME:-/nonexistent}/.local/bin/telepad" ]; then in_home=1; fi

if [ "$mode" = uninstall ]; then
    [ -n "$packaged" ] || [ "$in_home" = 1 ] || die "Telepad does not seem to be installed (no package, and no ~/.local/bin/telepad)"
    case "$packaged" in
        deb) say "Removing the Telepad package$asks"; $as_root apt-get remove -y telepad ;;
        rpm)
            say "Removing the Telepad package$asks"
            if have dnf; then $as_root dnf remove -y telepad
            elif have zypper; then $as_root zypper --non-interactive remove telepad
            else $as_root yum remove -y telepad; fi ;;
        arch) say "Removing the Telepad package$asks"; $as_root pacman -R --noconfirm telepad ;;
    esac
    if [ "$in_home" = 0 ]; then
        say "Telepad is removed. Your paired phones are kept in ~/.config/telepad; delete that folder to forget them."
        exit 0
    fi
    # The copy in the home folder is taken away by the download's own script, which knows what it put where.
    method=tarball
fi

# --- Which release -------------------------------------------------------------------------
tag=""
if [ -n "$want" ]; then
    case "$want" in v*) tag="$want" ;; *) tag="v$want" ;; esac
else
    say "Looking for the newest Telepad release"
    listing="$(fetch_text "$FEED")" || die "could not reach GitHub to see the releases (is the network up?)"
    stable=""
    preview=""
    # Newest first. A release counts only if it has the Linux download. A stable one is preferred; while there is
    # none (Telepad's 2.0 releases are still alphas), the newest pre-release will do. (A release is a pre-release
    # when its number has a hyphen: 2.0.0-alpha.4. The links inside a release's notes are written with &quot;, so
    # only the link that is the release's own matches.)
    for candidate in $(printf '%s' "$listing" | grep -o 'releases/tag/v[0-9][0-9A-Za-z.+-]*"' | sed 's|releases/tag/||; s|"$||'); do
        exists "$DOWNLOADS/$candidate/telepad-$candidate-linux-x86_64.tar.gz" || continue
        case "$candidate" in
            *-*) [ -n "$preview" ] || preview="$candidate" ;;
            *) stable="$candidate"; break ;;
        esac
    done
    tag="${stable:-$preview}"
    [ -n "$tag" ] || die "no Telepad release with a Linux download was found: see https://github.com/$REPO/releases"
fi
version="${tag#v}"

# --- Downloading, and checking what arrived ------------------------------------------------
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
chmod 755 "$tmp"   # so that apt, which downloads as its own user, can read what is in it

case "$method" in
    deb) asset="telepad-$tag-linux-x86_64.deb" ;;
    rpm) asset="telepad-$tag-linux-x86_64.rpm" ;;
    arch) asset="telepad-$tag-linux-x86_64.pkg.tar.zst" ;;
    *) asset="telepad-$tag-linux-x86_64.tar.gz" ;;
esac
# Telepad releases before the packages existed have only the plain download.
if [ "$mode" = install ] && [ "$method" != tarball ] && ! exists "$DOWNLOADS/$tag/$asset"; then
    warn "$tag has no $method package, so it is installed in your home folder instead"
    method=tarball
    asset="telepad-$tag-linux-x86_64.tar.gz"
fi

if [ "$mode" = install ]; then
    say "Telepad $version for Linux"
    case "$method" in
        tarball) note "It will be unpacked into ~/.local (your home folder): no password needed." ;;
        *)
            note "It will be installed with $manager as a system package$asks,"
            note "and it sets up the permission Telepad needs to type on this computer." ;;
    esac
fi

if [ "$method" = tarball ] && ! have bash; then
    die "unpacking into your home folder needs bash, which is not installed"
fi

say "Downloading $asset"
fetch "$DOWNLOADS/$tag/$asset" "$tmp/$asset" || die "could not download $DOWNLOADS/$tag/$asset"
fetch "$DOWNLOADS/$tag/SHA256SUMS" "$tmp/SHA256SUMS" || die "could not download the checksums for $tag"
wanted="$(awk -v f="$asset" '$2 == f || $2 == "*" f { print $1 }' "$tmp/SHA256SUMS")"
[ -n "$wanted" ] || die "$asset is not in the release's SHA256SUMS"
if have sha256sum; then actual="$(sha256sum "$tmp/$asset" | awk '{ print $1 }')"
elif have shasum; then actual="$(shasum -a 256 "$tmp/$asset" | awk '{ print $1 }')"
else die "cannot check the download: neither sha256sum nor shasum is here"; fi
[ "$wanted" = "$actual" ] || die "the download does not match its checksum (expected $wanted, got $actual): not installing it"
note "the checksum matches the release's SHA256SUMS"

# The rule that lets the person at the computer use /dev/uinput, and the module it belongs to, now and at every boot.
allow_typing() {
    $as_root install -m644 "$1" /etc/udev/rules.d/60-telepad-uinput.rules || return 1
    $as_root sh -c 'modprobe uinput 2> /dev/null; echo uinput > /etc/modules-load.d/telepad.conf; udevadm control --reload-rules; udevadm trigger --subsystem-match=misc --sysname-match=uinput'
}

# --- Installing ----------------------------------------------------------------------------
if [ "$mode" = uninstall ]; then
    say "Removing Telepad from your home folder"
    tar -xzf "$tmp/$asset" -C "$tmp"
    bash "$tmp"/telepad-*/install.sh --uninstall
    exit 0
fi

case "$method" in
    deb)
        say "Installing with apt"
        $as_root apt-get install -y "$tmp/$asset" ;;
    rpm)
        say "Installing with $manager"
        case "$manager" in
            dnf) $as_root dnf install -y "$tmp/$asset" ;;
            zypper) $as_root zypper --non-interactive install --allow-unsigned-rpm "$tmp/$asset" ;;
            *) $as_root yum install -y "$tmp/$asset" ;;
        esac ;;
    arch)
        say "Installing with pacman"
        $as_root pacman -U --noconfirm "$tmp/$asset" ;;
    tarball)
        say "Unpacking into ~/.local"
        tar -xzf "$tmp/$asset" -C "$tmp"
        bash "$tmp"/telepad-*/install.sh
        # What a package does by itself: let this person use /dev/uinput, the virtual keyboard and mouse.
        if [ ! -w /dev/uinput ] && [ "$can_root" = 1 ]; then
            say "Telepad needs permission to type on this computer (/dev/uinput)"
            rules="$(ls "$tmp"/telepad-*/60-telepad-uinput.rules)"
            note "This installs a small udev rule (the file $rules is in the download)${asks:+ and needs your password}."
            answer=y
            # Asked on the terminal even when this script arrives through a pipe, if there is a terminal.
            if (: < /dev/tty) 2> /dev/null; then
                printf '    Allow it? [Y/n] ' > /dev/tty
                read -r answer < /dev/tty || answer=y
            fi
            case "$answer" in
                n|N|no|No) note "Skipped. Telepad will say what to do when it starts without that permission." ;;
                *) allow_typing "$rules" || warn "could not set up the permission; Telepad will say what to do when it starts" ;;
            esac
        elif [ ! -w /dev/uinput ]; then
            warn "Telepad cannot open /dev/uinput yet. Ask whoever runs this computer to allow it (see https://github.com/$REPO#linux)."
        fi ;;
esac

echo
say "Telepad $version is installed"
note "Open Telepad from your applications menu (or run: telepad). Its first start shows a QR code:"
note "scan it with the Telepad app on your phone (Devices, Scan QR code)."
case "$method" in
    tarball) note "If the command is not found, add ~/.local/bin to your PATH." ;;
esac
note "To remove it later: curl -fsSL https://telepad-app.vercel.app/install.sh | sh -s -- --uninstall"

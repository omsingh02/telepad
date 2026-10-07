#!/usr/bin/env bash
# Puts the program into an application bundle, Telepad.app, which is what macOS wants to double-click.
#
#   packaging/macos/make-app.sh <version> <telepad-binary> <output-folder>
#
# <version> is the number, such as 2.0.0 (no "v", no "-alpha"). The bundle is written to <output-folder>/Telepad.app.
# This part runs anywhere; signing it and making the disk image need a Mac (sign-and-notarize.sh, make-dmg.sh).
set -euo pipefail

if [ "$#" -ne 3 ]; then
  echo "usage: $0 <version> <telepad-binary> <output-folder>" >&2
  exit 2
fi
version="$1"
binary="$2"
output="$3"
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo="$(cd "$here/../.." && pwd)"

case "$version" in
  *[!0-9.]* | "" | .* | *. | *..*)
    echo "make-app.sh: the version must be numbers and dots, such as 2.0.0, not '$version'" >&2
    exit 2
    ;;
esac

app="$output/Telepad.app"
mkdir -p "$output"
if [ -e "$app" ]; then
  echo "make-app.sh: $app is already there; remove it first" >&2
  exit 1
fi
mkdir -p "$app/Contents/MacOS" "$app/Contents/Resources"
install -m755 "$binary" "$app/Contents/MacOS/telepad"
install -m644 "$repo/packaging/icons/Telepad.icns" "$app/Contents/Resources/Telepad.icns"
install -m644 "$repo/LICENSE" "$app/Contents/Resources/LICENSE"
install -m644 "$repo/THIRD_PARTY_LICENSES.md" "$app/Contents/Resources/THIRD_PARTY_LICENSES.md"
sed "s/@VERSION@/$version/g" "$here/Info.plist.in" > "$app/Contents/Info.plist"
printf 'APPL????' > "$app/Contents/PkgInfo"

# A plist with a mistake is an app that macOS will not open, so look at it now rather than after release.
if command -v plutil > /dev/null 2>&1; then
  plutil -lint "$app/Contents/Info.plist"
elif command -v xmllint > /dev/null 2>&1; then
  xmllint --noout "$app/Contents/Info.plist"
fi
echo "Wrote $app"

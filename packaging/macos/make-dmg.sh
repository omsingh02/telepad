#!/usr/bin/env bash
# Makes the macOS download: a disk image with Telepad.app and a shortcut to Applications to drag it onto.
#
#   packaging/macos/make-dmg.sh <name> <Telepad.app> <output-folder>
#
# <name> is what the download is called, such as telepad-v2.0.0-macos-universal; the image is <name>.dmg.
# Needs a Mac (hdiutil). Signing and notarizing is sign-and-notarize.sh's job, run on the app before this and on
# the image after.
set -euo pipefail

if [ "$#" -ne 3 ]; then
  echo "usage: $0 <name> <Telepad.app> <output-folder>" >&2
  exit 2
fi
name="$1"
app="$2"
output="$3"
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

stage="$(mktemp -d)"
cleanup() { if [ -n "${stage:-}" ] && [ -d "$stage" ]; then rm -r -- "$stage"; fi; }
trap cleanup EXIT

# ditto keeps what a signature depends on (attributes, links) that a plain copy may lose.
ditto "$app" "$stage/Telepad.app"
ln -s /Applications "$stage/Applications"
mkdir -p "$output"
image="$output/$name.dmg"
rm -f -- "$image"

# hdiutil sometimes fails with "Resource busy" on a busy build machine; a second try almost always works.
for attempt in 1 2 3; do
  if hdiutil create -volname "Telepad" -srcfolder "$stage" -ov -format UDZO "$image"; then
    break
  fi
  if [ "$attempt" = 3 ]; then
    echo "make-dmg.sh: hdiutil failed three times" >&2
    exit 1
  fi
  sleep 5
done

"$here/sign-and-notarize.sh" dmg "$image"
echo "Wrote $image"

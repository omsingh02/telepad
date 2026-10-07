#!/usr/bin/env bash
# Signs what make-app.sh and make-dmg.sh made and, when Apple's credentials are there, has Apple notarize it.
#
#   packaging/macos/sign-and-notarize.sh app Telepad.app
#   packaging/macos/sign-and-notarize.sh dmg Telepad.dmg
#
# Without credentials the app only gets an "ad-hoc" signature, which is what a program needs to run at all on an
# Apple silicon Mac; Gatekeeper will still ask the person to allow it (System Settings > Privacy & Security > Open
# Anyway). With them it is signed with a Developer ID, notarized and stapled, and opens without asking.
#
# Environment, all from an Apple Developer account (see docs/code-signing.md):
#   APPLE_CERTIFICATE_BASE64    the "Developer ID Application" certificate with its key, as a .p12, base64-encoded
#   APPLE_CERTIFICATE_PASSWORD  the password the .p12 was exported with
#   APPLE_SIGNING_IDENTITY      its name, such as "Developer ID Application: Om Singh (ABCDE12345)"
#   APPLE_API_KEY_BASE64        an App Store Connect API key (.p8), base64-encoded, for notarization
#   APPLE_API_KEY_ID            that key's id
#   APPLE_API_ISSUER_ID         the issuer id shown beside the keys
set -euo pipefail

if [ "$#" -ne 2 ] || { [ "$1" != "app" ] && [ "$1" != "dmg" ]; }; then
  echo "usage: $0 app <Telepad.app> | dmg <Telepad.dmg>" >&2
  exit 2
fi
kind="$1"
target="$2"
bundle_id="io.github.omsingh02.telepad"

if [ "$(uname -s)" != "Darwin" ]; then
  echo "sign-and-notarize.sh: this needs a Mac (codesign and notarytool)" >&2
  exit 1
fi

identity="${APPLE_SIGNING_IDENTITY:-}"
if [ -z "$identity" ] || [ -z "${APPLE_CERTIFICATE_BASE64:-}" ]; then
  if [ "$kind" = "app" ]; then
    echo "No Apple signing identity: signing $target ad hoc (it will not be notarized)."
    codesign --force --sign - --identifier "$bundle_id" "$target"
    codesign --verify --strict --verbose=2 "$target"
  else
    echo "No Apple signing identity: leaving $target unsigned."
  fi
  exit 0
fi

work="$(mktemp -d)"
keychain="$work/telepad-signing.keychain-db"
cleanup() {
  security delete-keychain "$keychain" > /dev/null 2>&1 || true
  if [ -n "${work:-}" ] && [ -d "$work" ]; then rm -r -- "$work"; fi
}
trap cleanup EXIT

# A keychain of its own for the certificate, so that nothing is left on the machine afterwards.
password="$(uuidgen)"
security create-keychain -p "$password" "$keychain"
security set-keychain-settings -lut 3600 "$keychain"
security unlock-keychain -p "$password" "$keychain"
printf '%s' "$APPLE_CERTIFICATE_BASE64" | base64 --decode > "$work/certificate.p12"
security import "$work/certificate.p12" -k "$keychain" -P "${APPLE_CERTIFICATE_PASSWORD:-}" -T /usr/bin/codesign -T /usr/bin/security
security set-key-partition-list -S apple-tool:,apple:,codesign: -s -k "$password" "$keychain" > /dev/null
# Put it first in the search list, keeping what was there.
# shellcheck disable=SC2046
security list-keychains -d user -s "$keychain" $(security list-keychains -d user | tr -d '"')

# The hardened runtime and a secure timestamp are what notarization asks of a signature.
codesign --force --options runtime --timestamp --sign "$identity" --identifier "$bundle_id" "$target"
codesign --verify --strict --verbose=2 "$target"

if [ -z "${APPLE_API_KEY_BASE64:-}" ] || [ -z "${APPLE_API_KEY_ID:-}" ] || [ -z "${APPLE_API_ISSUER_ID:-}" ]; then
  echo "No notarization key: $target is signed but not notarized."
  exit 0
fi

printf '%s' "$APPLE_API_KEY_BASE64" | base64 --decode > "$work/AuthKey.p8"
if [ "$kind" = "app" ]; then
  # Apple takes a zip or a disk image, not a bundle.
  submission="$work/Telepad.zip"
  ditto -c -k --keepParent "$target" "$submission"
else
  submission="$target"
fi

echo "Sending $target to Apple to be notarized (this takes a few minutes)..."
result="$(xcrun notarytool submit "$submission" --key "$work/AuthKey.p8" --key-id "$APPLE_API_KEY_ID" \
  --issuer "$APPLE_API_ISSUER_ID" --wait --output-format plist)"
status="$(/usr/libexec/PlistBuddy -c 'Print :status' /dev/stdin <<< "$result")"
id="$(/usr/libexec/PlistBuddy -c 'Print :id' /dev/stdin <<< "$result")"
if [ "$status" != "Accepted" ]; then
  echo "Apple did not accept it (status: $status). Apple's log:" >&2
  xcrun notarytool log "$id" --key "$work/AuthKey.p8" --key-id "$APPLE_API_KEY_ID" --issuer "$APPLE_API_ISSUER_ID" >&2 || true
  exit 1
fi

# The ticket goes into the file itself, so that it opens without asking Apple, even offline.
xcrun stapler staple "$target"
xcrun stapler validate "$target"
echo "$target is signed, notarized and stapled."

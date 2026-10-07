#!/usr/bin/env bash
# Makes the Linux packages: a .deb (Debian, Ubuntu, Mint...), an .rpm (Fedora, openSUSE...) and an Arch package.
#
#   packaging/linux/build-packages.sh <tag> <name-prefix> <telepad-binary> <output-folder>
#
#   packaging/linux/build-packages.sh v2.0.0-alpha.2 telepad-v2.0.0-alpha.2-linux-x86_64 target/release/telepad dist
#     writes dist/telepad-v2.0.0-alpha.2-linux-x86_64.deb, .rpm and .pkg.tar.zst
#
# Needs nfpm on the PATH (a single program: https://github.com/goreleaser/nfpm/releases). Run it from anywhere.
set -euo pipefail

if [ "$#" -ne 4 ]; then
  echo "usage: $0 <tag> <name-prefix> <telepad-binary> <output-folder>" >&2
  exit 2
fi
tag="$1"
prefix="$2"
binary="$3"
output="$4"
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo="$(cd "$here/../.." && pwd)"

if ! command -v nfpm > /dev/null 2>&1; then
  echo "build-packages.sh: nfpm is not installed (https://github.com/goreleaser/nfpm/releases)" >&2
  exit 1
fi
[[ "$tag" =~ ^v[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.]+)?$ ]] || { echo "build-packages.sh: the tag must look like v2.0.0 or v2.0.0-alpha.2" >&2; exit 2; }
[ -x "$binary" ] || { echo "build-packages.sh: $binary is not an executable file" >&2; exit 1; }

work="$(mktemp -d)"
cleanup() { if [ -n "${work:-}" ] && [ -d "$work" ]; then rm -r -- "$work"; fi; }
trap cleanup EXIT
mkdir -p "$output"
output="$(cd "$output" && pwd)"
binary="$(cd "$(dirname "$binary")" && pwd)/$(basename "$binary")"

sed -e "s|@VERSION@|$tag|" -e "s|@BINARY@|$binary|" "$here/nfpm.yaml" > "$work/nfpm.yaml"

# The paths in the description are relative to the repository.
cd "$repo"
nfpm package --config "$work/nfpm.yaml" --packager deb --target "$output/$prefix.deb"
nfpm package --config "$work/nfpm.yaml" --packager rpm --target "$output/$prefix.rpm"
nfpm package --config "$work/nfpm.yaml" --packager archlinux --target "$output/$prefix.pkg.tar.zst"
ls -la "$output/$prefix".*

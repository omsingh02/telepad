#!/usr/bin/env bash
# Tries the one-line installer (website/install.sh) end to end, in real distributions, against a stand-in for GitHub
# on this machine that serves a release made of the packages in a folder:
#
#   packaging/linux/tests/installer.sh <folder with the .deb, .rpm, .pkg.tar.zst and .tar.gz> <tag> <version>
#
#   packaging/linux/tests/installer.sh dist v2.0.0-alpha.2 2.0.0
#
# <tag> is the release the files belong to (their names hold it), <version> what the program reports. It also checks
# which release the installer picks: a stable one over a newer pre-release, and a pre-release when there is no stable
# one with a Linux download. Needs Docker.
set -euo pipefail

if [ "$#" -ne 3 ]; then
  echo "usage: $0 <package-folder> <tag> <version>" >&2
  exit 2
fi
packages="$(cd "$1" && pwd)"
tag="$2"
version="$3"
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo="$(cd "$here/../../.." && pwd)"
command -v docker > /dev/null || { echo "installer.sh: Docker is needed for this" >&2; exit 1; }
[ -f "$repo/website/install.sh" ] || { echo "installer.sh: $repo/website/install.sh is not there (is it stashed?)" >&2; exit 1; }

site="$(mktemp -d)"
server=""
# shellcheck disable=SC2329 # run by the trap
cleanup() {
  if [ -n "$server" ]; then kill "$server" 2> /dev/null || true; fi
  if [ -n "${site:-}" ] && [ -d "$site" ]; then rm -r -- "$site"; fi
}
trap cleanup EXIT

# A release is a folder of files and their checksums, as GitHub serves it.
make_release() {  # make_release <tag>
  local t="$1" f name
  mkdir -p "$site/download/$t"
  for f in "$packages"/*; do
    name="$(basename "$f")"
    cp "$f" "$site/download/$t/${name//$tag/$t}"
  done
  (cd "$site/download/$t" && sha256sum -- * > SHA256SUMS)
}
listing() {  # listing "tag,prerelease" ... in the shape GitHub's API answers with, newest first
  local first=1 entry
  printf '['
  for entry in "$@"; do
    [ "$first" = 1 ] || printf ','
    first=0
    printf '{"url":"x","tag_name":"%s","name":"Telepad %s","draft":false,"prerelease":%s,"assets":[{"name":"SHA256SUMS"}]}' "${entry%,*}" "${entry%,*}" "${entry#*,}"
  done
  printf ']'
}

# 1. Only the pre-release has Linux downloads (as now): the installer must take it, and not the stable v1.0.1.
make_release "$tag"
mkdir -p "$site/repos/x"
listing "$tag,true" "v1.0.1,false" > "$site/repos/x/releases"

python3 -u -m http.server 0 --bind 127.0.0.1 --directory "$site" > "$site/server.log" 2>&1 &
server=$!
for _ in $(seq 1 50); do
  port="$(sed -nE 's/.*port ([0-9]+).*/\1/p' "$site/server.log" | head -n 1)"
  [ -n "$port" ] && break
  sleep 0.1
done
[ -n "${port:-}" ] || { echo "installer.sh: the stand-in server did not start" >&2; cat "$site/server.log" >&2; exit 1; }

failed=0
try() {  # try <image> <kind>
  echo "=== $1"
  if docker run --rm --network host \
      -e TELEPAD_API="http://127.0.0.1:$port/repos/x" -e TELEPAD_DOWNLOADS="http://127.0.0.1:$port/download" \
      --mount "type=bind,source=$repo/website/install.sh,target=/installer/install.sh,readonly" \
      --mount "type=bind,source=$here/container-installer.sh,target=/test.sh,readonly" \
      "$1" sh /test.sh "$version" "$2"; then :; else
    echo "=== FAILED on $1" >&2
    failed=1
  fi
}
try debian:12 deb
try ubuntu:24.04 deb
try fedora:latest rpm
try archlinux:latest arch

# 2. A stable release with Linux downloads wins over a newer pre-release.
stable="v${version}"
if [ "$stable" != "$tag" ]; then
  make_release "$stable"
  listing "v${version}-rc.9,true" "$stable,false" "$tag,true" > "$site/repos/x/releases"
  echo "=== which release is picked when there is a stable one"
  picked="$(docker run --rm --network host \
      -e TELEPAD_API="http://127.0.0.1:$port/repos/x" -e TELEPAD_DOWNLOADS="http://127.0.0.1:$port/download" \
      --mount "type=bind,source=$repo/website/install.sh,target=/installer/install.sh,readonly" debian:12 \
      sh -c 'apt-get update -qq > /dev/null; apt-get install -y -qq curl ca-certificates > /dev/null 2>&1; sh /installer/install.sh --user 2>&1 | grep -o "Telepad [0-9.]* for Linux" | head -n 1')"
  echo "picked: $picked"
  [ "$picked" = "Telepad $version for Linux" ] || { echo "FAILED: expected the stable release" >&2; failed=1; }
fi
exit "$failed"

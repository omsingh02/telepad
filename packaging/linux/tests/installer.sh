#!/usr/bin/env bash
# Tries the one-line installer (website/install.sh) end to end, in real distributions, against a stand-in for GitHub
# on this machine that serves a release made of the packages in a folder:
#
#   packaging/linux/tests/installer.sh <folder with the .deb, .rpm, .pkg.tar.zst and .tar.gz> <tag> <version>
#
#   packaging/linux/tests/installer.sh dist v2.0.0-alpha.2 2.0.0-alpha.2
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
listing() {  # listing tag ... as the releases feed lists them, newest first
  local tag
  printf '<?xml version="1.0" encoding="UTF-8"?>\n<feed xmlns="http://www.w3.org/2005/Atom">\n<title>Release notes from telepad</title>\n'
  for tag in "$@"; do
    # The notes carry a link to another release, escaped as GitHub writes it: it must not count as a release.
    printf '<entry>\n<id>tag:github.com,2008:Repository/1/%s</id>\n<link rel="alternate" type="text/html" href="https://github.com/omsingh02/telepad/releases/tag/%s"/>\n<title>Telepad %s</title>\n<content type="html">&lt;a href=&quot;https://github.com/omsingh02/telepad/releases/tag/v9.9.9&quot;&gt;x&lt;/a&gt;</content>\n</entry>\n' "$tag" "$tag" "$tag"
  done
  printf '</feed>\n'
}

# 1. Only the pre-release has Linux downloads (as now): the installer must take it, and not the stable v1.0.1
#    (which is in the feed, but has no Linux download).
make_release "$tag"
listing "$tag" "v1.0.1" > "$site/feed.atom"

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
      -e TELEPAD_FEED="http://127.0.0.1:$port/feed.atom" -e TELEPAD_DOWNLOADS="http://127.0.0.1:$port/download" \
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
base="${version%%-*}"   # the release a pre-release is the run-up to
stable="v${base}"
if [ "$stable" != "$tag" ]; then
  make_release "$stable"
  listing "v${base}-rc.9" "$stable" "$tag" > "$site/feed.atom"
  echo "=== which release is picked when there is a stable one"
  picked="$(docker run --rm --network host \
      -e TELEPAD_FEED="http://127.0.0.1:$port/feed.atom" -e TELEPAD_DOWNLOADS="http://127.0.0.1:$port/download" \
      --mount "type=bind,source=$repo/website/install.sh,target=/installer/install.sh,readonly" debian:12 \
      sh -c 'apt-get update -qq > /dev/null; apt-get install -y -qq curl ca-certificates > /dev/null 2>&1; sh /installer/install.sh --user 2>&1 | grep -o "Telepad [0-9.]* for Linux" | head -n 1')"
  echo "picked: $picked"
  [ "$picked" = "Telepad $base for Linux" ] || { echo "FAILED: expected the stable release" >&2; failed=1; }
fi
exit "$failed"

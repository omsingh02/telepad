#!/usr/bin/env bash
# Installs the Linux packages in real distributions (containers), the way a person would, and checks the result.
#
#   packaging/linux/tests/run.sh <folder with the .deb, .rpm and .pkg.tar.zst> <version the program reports>
#
#   packaging/linux/tests/run.sh dist 2.0.0
#
# Needs Docker. It takes a few minutes the first time, while the images are downloaded. Each package is tried on the
# systems it is for: the .deb on Debian and Ubuntu, the .rpm on Fedora, the Arch package on Arch.
set -euo pipefail

if [ "$#" -ne 2 ]; then
  echo "usage: $0 <package-folder> <version>" >&2
  exit 2
fi
packages="$(cd "$1" && pwd)"
version="$2"
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
command -v docker > /dev/null || { echo "run.sh: Docker is needed for this" >&2; exit 1; }

# Each container gets only the one kind of package it is meant to install.
failed=0
try() {  # try <image> <script> <glob of the package>
  local image="$1" script="$2" pattern="$3" stage
  stage="$(mktemp -d)"
  # shellcheck disable=SC2086 # the pattern is a glob on purpose
  cp "$packages"/$pattern "$stage"/
  echo "=== $image"
  # --mount, not -v: a missing file is an error with --mount, while -v would make a folder in its place.
  if docker run --rm --mount "type=bind,source=$stage,target=/pkg,readonly" --mount "type=bind,source=$here/$script,target=/test.sh,readonly" \
      "$image" sh /test.sh "$version"; then :; else
    echo "=== FAILED on $image" >&2
    failed=1
  fi
  rm -r -- "$stage"
}
try debian:12 container.sh '*.deb'
try ubuntu:24.04 container.sh '*.deb'
try fedora:latest container.sh '*.rpm'
try archlinux:latest container.sh '*.pkg.tar.zst'
exit "$failed"

#!/usr/bin/env bash
# Installs a prebuilt command line tool from a GitHub release, for a Linux runner, after checking its checksum, and
# puts it on the PATH of the steps that follow (when run in GitHub Actions).
#
#   scripts/install-ci-tool.sh EmbarkStudios/cargo-deny 0.20.2 cargo-deny
#
# It is for tools whose releases are named <tool>-<version>-x86_64-unknown-linux-musl.tar.gz with a .sha256 file
# beside them, as the Embark Studios tools' are. Needs gh (logged in, or GH_TOKEN set).
set -euo pipefail

if [ "$#" -ne 3 ]; then
  echo "usage: $0 <owner/repo> <version> <tool>" >&2
  exit 2
fi
repo="$1"
version="$2"
tool="$3"
asset="$tool-$version-x86_64-unknown-linux-musl"
dir="${RUNNER_TEMP:-${TMPDIR:-/tmp}}/$tool"

mkdir -p "$dir"
gh release download "$version" --repo "$repo" --pattern "$asset.tar.gz*" --dir "$dir" --clobber
echo "$(cat "$dir/$asset.tar.gz.sha256")  $dir/$asset.tar.gz" | sha256sum -c -
tar -xzf "$dir/$asset.tar.gz" -C "$dir"
if [ -n "${GITHUB_PATH:-}" ]; then
  echo "$dir/$asset" >> "$GITHUB_PATH"
fi
echo "$tool $version is in $dir/$asset"

#!/usr/bin/env bash
# Installs nfpm (https://nfpm.goreleaser.com), the program that builds the .deb, .rpm and Arch packages, on a Linux
# runner, after checking it against the checksums its release publishes, and puts it on the PATH of the steps that
# follow (when run in GitHub Actions).
#
#   scripts/install-nfpm.sh 2.47.0
#
# Needs gh (logged in, or GH_TOKEN set).
set -euo pipefail

if [ "$#" -ne 1 ]; then
  echo "usage: $0 <version, such as 2.47.0>" >&2
  exit 2
fi
version="$1"
asset="nfpm_${version}_Linux_x86_64.tar.gz"
dir="${RUNNER_TEMP:-${TMPDIR:-/tmp}}/nfpm"

mkdir -p "$dir"
gh release download "v$version" --repo goreleaser/nfpm --pattern "$asset" --pattern checksums.txt --dir "$dir" --clobber
(cd "$dir" && sha256sum --check --ignore-missing checksums.txt)
tar -xzf "$dir/$asset" -C "$dir"
if [ -n "${GITHUB_PATH:-}" ]; then
  echo "$dir" >> "$GITHUB_PATH"
fi
echo "nfpm $version is in $dir"

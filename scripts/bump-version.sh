#!/usr/bin/env bash
# Sets the version everywhere it lives, and starts a changelog section for it.
#
#   scripts/bump-version.sh 2.1.0
#
# The Android app, the desktop server and the git tag all carry one version (see CONTRIBUTING.md).
# This changes Cargo.toml, android/app/build.gradle.kts (its versionCode follows by itself),
# Cargo.lock, and adds "## [2.1.0] - Unreleased" to CHANGELOG.md if there is no such section yet.
# It does not commit anything.
set -euo pipefail

cd "$(git rev-parse --show-toplevel)"

version="${1:-}"
if [[ ! "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  echo "usage: scripts/bump-version.sh X.Y.Z   (for example 2.1.0)" >&2
  exit 1
fi

python3 - "$version" <<'PY'
import re
import sys
from pathlib import Path

version = sys.argv[1]


def edit(path, pattern, replacement, flags=0):
    text = Path(path).read_text(encoding="utf-8")
    new, count = re.subn(pattern, replacement, text, count=1, flags=flags)
    if count != 1:
        sys.exit(f"could not find the version line in {path}")
    Path(path).write_text(new, encoding="utf-8")


edit("Cargo.toml", r'^version = "[^"]+"', f'version = "{version}"', re.M)
edit("android/app/build.gradle.kts", r'^(\s*versionName = )"[^"]+"', rf'\1"{version}"', re.M)

changelog = Path("CHANGELOG.md")
text = changelog.read_text(encoding="utf-8")
if not re.search(rf"^## \[{re.escape(version)}\]", text, re.M):
    skeleton = f"## [{version}] - Unreleased\n\n### Added\n\n### Changed\n\n### Fixed\n\n"
    first = re.search(r"^## \[", text, re.M)
    if not first:
        sys.exit("CHANGELOG.md has no version sections to put the new one before")
    changelog.write_text(text[: first.start()] + skeleton + text[first.start():], encoding="utf-8")
PY

# Cargo.lock lists the workspace's own crates with their versions; refresh just those.
cargo update --workspace --offline > /dev/null 2>&1 || cargo update --workspace > /dev/null

echo "Version is now $version in:"
git --no-pager diff --stat -- Cargo.toml Cargo.lock android/app/build.gradle.kts CHANGELOG.md
echo
echo "Next: fill in the changelog section, commit, then run scripts/release.sh $version"

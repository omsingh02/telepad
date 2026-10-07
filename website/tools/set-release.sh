#!/usr/bin/env bash
# Points the website's download buttons at one release instead of "the latest".
#
#   website/tools/set-release.sh v2.0.0-alpha.1    while the newest release is a pre-release
#   website/tools/set-release.sh latest            back to the latest stable release
#
# GitHub's "latest release" skips pre-releases, so a page that links to it would keep offering
# the previous stable version. It changes files only; commit them and deploy afterwards.
set -euo pipefail

cd "$(git rev-parse --show-toplevel)"
tag="${1:-}"
[ -n "$tag" ] || { echo "usage: website/tools/set-release.sh <tag|latest>" >&2; exit 1; }

python3 - "$tag" <<'PY'
import re
import sys
from pathlib import Path

tag = sys.argv[1]
repo = "https://github.com/omsingh02/telepad/releases"
target = f"{repo}/latest/download/" if tag == "latest" else f"{repo}/download/{tag}/"
path = Path("website/index.html")
text, count = re.subn(re.escape(repo) + r"/(?:latest/download/|download/[^/\"]+/)", target, path.read_text(encoding="utf-8"))
path.write_text(text, encoding="utf-8")
print(f"website/index.html: {count} download link(s) now point at {tag}")
PY

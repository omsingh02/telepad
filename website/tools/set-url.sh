#!/usr/bin/env bash
# Points the website's canonical link, Open Graph tags, sitemap and robots.txt, and the README's
# "Website" link, at a new address (for example after adding a custom domain in Vercel).
#
#   website/tools/set-url.sh https://telepad.example.org
#
# It changes files only; commit them and deploy afterwards.
set -euo pipefail

cd "$(git rev-parse --show-toplevel)"
new="${1:-}"
[[ "$new" =~ ^https://[a-z0-9.-]+$ ]] || { echo "usage: website/tools/set-url.sh https://example.org   (no trailing slash)" >&2; exit 1; }

python3 - "$new" <<'PY'
import re
import sys
from pathlib import Path

new = sys.argv[1]
index = Path("website/index.html").read_text(encoding="utf-8")
match = re.search(r'<link rel="canonical" href="(https://[^"/]+)/">', index)
if not match:
    sys.exit("could not find the canonical link in website/index.html")
old = match.group(1)
if old == new:
    sys.exit(f"the website already uses {new}")

for name in ("website/index.html", "website/robots.txt", "website/sitemap.xml", "README.md"):
    path = Path(name)
    text = path.read_text(encoding="utf-8")
    path.write_text(text.replace(old, new), encoding="utf-8")
    print(f"{name}: {text.count(old)} replacement(s)")
PY

echo "Now: git commit, deploy the website, and update the repository's homepage:"
echo "    gh repo edit --homepage $new"

#!/usr/bin/env bash
# Points the package-manager manifests in packaging/ (Arch, Scoop, winget, Homebrew) at a release: the version, the
# download addresses and the SHA-256 of each file.
#
#   scripts/update-package-manifests.sh v2.0.0-alpha.2           reads SHA256SUMS from that release on GitHub
#   scripts/update-package-manifests.sh v2.0.0 --sums SHA256SUMS  reads a file you already have (for a try-out)
#
# It changes files only. Submitting them is yours to do: see packaging/README.md ("Package managers").
set -euo pipefail

tag="" sums_file=""
while [ "$#" -gt 0 ]; do
  case "$1" in
    --sums) sums_file="${2:?--sums needs a file}"; shift ;;
    -*) echo "unknown option $1" >&2; exit 2 ;;
    *) [ -z "$tag" ] || { echo "give one tag only" >&2; exit 2; }; tag="$1" ;;
  esac
  shift
done
[[ "$tag" =~ ^v[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.]+)?$ ]] || { echo "give a tag like v2.0.0 or v2.0.0-alpha.2" >&2; exit 2; }

cd "$(git rev-parse --show-toplevel)"
work="$(mktemp -d)"
cleanup() { if [ -n "${work:-}" ] && [ -d "$work" ]; then rm -r -- "$work"; fi; }
trap cleanup EXIT

if [ -z "$sums_file" ]; then
  gh release download "$tag" --pattern SHA256SUMS --dir "$work"
  sums_file="$work/SHA256SUMS"
fi

python3 - "$tag" "$sums_file" <<'PY'
import re
import sys
from pathlib import Path

tag, sums_path = sys.argv[1], sys.argv[2]
version = tag[1:]                       # 2.0.0-alpha.2
arch_version = version.replace("-", "_")  # 2.0.0_alpha.2: a pkgver cannot hold a hyphen

sums = {}
for line in Path(sums_path).read_text().splitlines():
    match = re.match(r"^([0-9a-f]{64})\s+\*?(\S+)$", line.strip())
    if match:
        sums[match.group(2)] = match.group(1)

def digest(name):
    if name not in sums:
        sys.exit(f"{name} is not in the checksums: has the release finished, and is it the right tag?")
    return sums[name]

def rewrite(path, substitutions):
    file = Path(path)
    text = file.read_text()
    for pattern, replacement in substitutions:
        text, count = re.subn(pattern, replacement, text, flags=re.M)
        if count == 0:
            sys.exit(f"{path}: found nothing to change for {pattern!r}")
    file.write_text(text)
    print(f"updated {path}")

linux = f"telepad-{tag}-linux-x86_64.tar.gz"
windows_setup = f"telepad-{tag}-windows-x86_64-setup.exe"
windows_exe = f"telepad-{tag}-windows-x86_64.exe"
mac = f"telepad-{tag}-macos-universal.dmg"

rewrite("packaging/arch/PKGBUILD", [
    (r"^_tag=.*$", f"_tag={tag}"),
    (r"^pkgver=.*$", f"pkgver={arch_version}"),
    (r"^pkgrel=.*$", "pkgrel=1"),
    (r"^sha256sums=\(.*\)$", f"sha256sums=('{digest(linux)}')"),
])
rewrite("packaging/scoop/telepad.json", [
    (r'^(\s*)"version": ".*",$', rf'\1"version": "{version}",'),
    (r'^(\s*)"url": ".*",$', rf'\1"url": "https://github.com/omsingh02/telepad/releases/download/{tag}/{windows_exe}#/telepad.exe",'),
    (r'^(\s*)"hash": ".*",$', rf'\1"hash": "{digest(windows_exe)}",'),
])
for name in ("omsingh02.Telepad.yaml", "omsingh02.Telepad.installer.yaml", "omsingh02.Telepad.locale.en-US.yaml"):
    subs = [(r"^PackageVersion: .*$", f"PackageVersion: {version}")]
    if name.endswith(".installer.yaml"):
        subs += [
            (r"^(\s*InstallerUrl: ).*$", rf"\1https://github.com/omsingh02/telepad/releases/download/{tag}/{windows_setup}"),
            (r"^(\s*InstallerSha256: ).*$", rf"\1{digest(windows_setup).upper()}"),
        ]
    if name.endswith(".locale.en-US.yaml"):
        subs += [(r"^ReleaseNotesUrl: .*$", f"ReleaseNotesUrl: https://github.com/omsingh02/telepad/releases/tag/{tag}")]
    rewrite(f"packaging/winget/{name}", subs)
rewrite("packaging/homebrew/telepad.rb", [
    (r'^(\s*)version ".*"$', rf'\1version "{version}"'),
    (r'^(\s*)sha256 ".*"$', rf'\1sha256 "{digest(mac)}"'),
])
PY

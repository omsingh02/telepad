#!/usr/bin/env bash
# Writes THIRD_PARTY_LICENSES.md: the licenses of the libraries inside the desktop programs, which the programs
# ship with (the installer, the disk image, the tarball).
#
#   scripts/update-licenses.sh           rewrite the file from Cargo.lock
#   scripts/update-licenses.sh --check   fail if the file is not what Cargo.lock would give (the release does this)
#
# Needs cargo-about (https://github.com/EmbarkStudios/cargo-about): `cargo install --locked cargo-about`, or a
# prebuilt binary from its releases page. Run it after a dependency update.
set -euo pipefail

cd "$(git rev-parse --show-toplevel)"
if ! command -v cargo-about > /dev/null 2>&1; then
  echo "update-licenses.sh: cargo-about is not installed (cargo install --locked cargo-about)" >&2
  exit 1
fi

work="$(mktemp -d)"
cleanup() { if [ -n "${work:-}" ] && [ -d "$work" ]; then rm -r -- "$work"; fi; }
trap cleanup EXIT

cargo about generate --locked about.hbs -o "$work/THIRD_PARTY_LICENSES.md"

# cargo-about lists the libraries under each license in an order that can differ from one run to the next, which
# would make --check fail on a file that is right: sorted, the same Cargo.lock gives the same file anywhere.
python3 scripts/sort-license-users.py "$work/THIRD_PARTY_LICENSES.md"

if [ "${1:-}" = "--check" ]; then
  if cmp -s "$work/THIRD_PARTY_LICENSES.md" THIRD_PARTY_LICENSES.md; then
    echo "THIRD_PARTY_LICENSES.md is up to date."
  else
    echo "THIRD_PARTY_LICENSES.md is out of date with Cargo.lock: run scripts/update-licenses.sh and commit the result." >&2
    exit 1
  fi
else
  cp "$work/THIRD_PARTY_LICENSES.md" THIRD_PARTY_LICENSES.md
  echo "Wrote THIRD_PARTY_LICENSES.md"
fi

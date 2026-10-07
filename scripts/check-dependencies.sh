#!/usr/bin/env bash
# Checks the dependencies of the desktop programs for known vulnerabilities (RustSec), licenses the project cannot
# ship, and unexpected sources, once for each platform it is built for (see deny.toml).
#
#   scripts/check-dependencies.sh
#
# Needs cargo-deny (https://github.com/EmbarkStudios/cargo-deny): `cargo install --locked cargo-deny`, or a
# prebuilt binary from its releases page.
set -euo pipefail

cd "$(git rev-parse --show-toplevel)"
if ! command -v cargo-deny > /dev/null 2>&1; then
  echo "check-dependencies.sh: cargo-deny is not installed (cargo install --locked cargo-deny)" >&2
  exit 1
fi

failed=0
for target in x86_64-pc-windows-msvc x86_64-unknown-linux-gnu aarch64-apple-darwin x86_64-apple-darwin; do
  echo "==> $target"
  cargo deny --target "$target" check --hide-inclusion-graph || failed=1
done
exit "$failed"

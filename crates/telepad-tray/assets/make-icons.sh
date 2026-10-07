#!/usr/bin/env bash
# Renders the tray icons as raw RGBA bytes (what the tray libraries take), from the brand icon.
#
#   icon-64.rgba       64x64, the app icon: Windows and Linux
#   template-44.rgba   44x44, a black shape on transparent: the macOS menu bar
#
# Needs: rsvg-convert and python3 with Pillow. Run it after changing docs/brand/icon.svg or tray-template.svg.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
work="$(mktemp -d)"
cleanup() { if [ -n "${work:-}" ] && [ -d "$work" ]; then rm -r -- "$work"; fi; }
trap cleanup EXIT

rsvg-convert -w 64 -h 64 "$here/../../../docs/brand/icon.svg" -o "$work/icon-64.png"
rsvg-convert -w 44 -h 44 "$here/tray-template.svg" -o "$work/template-44.png"
python3 - "$work" "$here" <<'PY'
import sys
from PIL import Image
work, here = sys.argv[1], sys.argv[2]
for name in ("icon-64", "template-44"):
    image = Image.open(f"{work}/{name}.png").convert("RGBA")
    open(f"{here}/{name}.rgba", "wb").write(image.tobytes())
    print(f"{name}.rgba: {image.size[0]}x{image.size[1]}, {len(image.tobytes())} bytes")
PY

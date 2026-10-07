#!/usr/bin/env bash
# Renders every icon a package needs from the brand icon (docs/brand/icon.svg).
#
#   icons/telepad.ico        Windows: the program's icon and the installer's (16 to 256 px)
#   icons/Telepad.icns       macOS: the app's icon (the artwork sits inside the margin macOS expects)
#   icons/telepad-<n>.png    Linux: the sizes the icon theme looks for, and the website's apple-touch fallbacks
#
# Needs: rsvg-convert, optipng and python3 with Pillow. Run it after changing docs/brand/icon.svg, and commit
# the results (the Windows build embeds telepad.ico, and no runner has the tools to make it).
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
source_svg="$here/../docs/brand/icon.svg"
out="$here/icons"
work="$(mktemp -d)"
cleanup() { if [ -n "${work:-}" ] && [ -d "$work" ]; then rm -r -- "$work"; fi; }
trap cleanup EXIT
mkdir -p "$out"

render() {  # render <pixels> <file>
  rsvg-convert -w "$1" -h "$1" "$source_svg" -o "$2"
}

# Linux and general use.
for size in 16 24 32 48 64 128 256 512; do
  render "$size" "$out/telepad-$size.png"
done
optipng -quiet -o4 "$out"/telepad-*.png

# Windows: one file, several sizes. Windows picks the nearest, so each is drawn at its own size, not scaled down.
for size in 16 24 32 48 64 128 256; do render "$size" "$work/win-$size.png"; done

# macOS: Big Sur's icon is the shape at about 80% of the square, with clear space around it.
for size in 16 32 64 128 256 512 1024; do
  inner=$(( size * 824 / 1024 ))
  render "$inner" "$work/mac-inner-$size.png"
done

python3 - "$work" "$out" <<'PY'
import struct
import sys
from PIL import Image

work, out = sys.argv[1], sys.argv[2]

# --- Windows .ico, written by Pillow from the individually drawn sizes -----------------------------------------
sizes = [16, 24, 32, 48, 64, 128, 256]
frames = [Image.open(f"{work}/win-{s}.png").convert("RGBA") for s in sizes]
frames[-1].save(
    f"{out}/telepad.ico",
    format="ICO",
    sizes=[(s, s) for s in sizes],
    append_images=frames[:-1],
)

# --- macOS .icns: a header and a list of PNG pictures, each tagged with its type ---------------------------------
def padded(size):
    inner = Image.open(f"{work}/mac-inner-{size}.png").convert("RGBA")
    canvas = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    canvas.paste(inner, ((size - inner.width) // 2, (size - inner.height) // 2), inner)
    return canvas

def png_bytes(image):
    import io
    buffer = io.BytesIO()
    image.save(buffer, format="PNG", optimize=True)
    return buffer.getvalue()

# type, pixel size. The "@2x" ones are the picture for twice the points, so they reuse the bigger size.
entries = [
    (b"icp4", 16), (b"icp5", 32), (b"icp6", 64),
    (b"ic07", 128), (b"ic08", 256), (b"ic09", 512), (b"ic10", 1024),
    (b"ic11", 32), (b"ic12", 64), (b"ic13", 256), (b"ic14", 512),
]
cache = {}
body = b""
for kind, size in entries:
    cache.setdefault(size, png_bytes(padded(size)))
    data = cache[size]
    body += kind + struct.pack(">I", len(data) + 8) + data
with open(f"{out}/Telepad.icns", "wb") as file:
    file.write(b"icns" + struct.pack(">I", len(body) + 8) + body)

for name in ("telepad.ico", "Telepad.icns"):
    import os
    print(f"{name}: {os.path.getsize(f'{out}/{name}')} bytes")
PY
echo "Icons written to $out"

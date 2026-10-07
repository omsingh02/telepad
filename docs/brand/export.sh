#!/usr/bin/env bash
# Renders the raster brand images from their sources.
#
#   docs/brand/social-preview.png        1280x640  GitHub "social preview" (upload it in the repository settings)
#   website/assets/img/og.png            1200x630  link preview of the website
#   website/assets/img/icon-*.png, apple-touch-icon.png, favicon.ico   from icon.svg and favicon.svg
#
# Needs: chromium, rsvg-convert, ImageMagick (magick), optipng, python3.
# The logo SVGs themselves come from build_logo.py.
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
repo="$(cd "$here/../.." && pwd)"
img="$repo/website/assets/img"
port=8096

# --- Icons -------------------------------------------------------------------------------
rsvg-convert -w 192 -h 192 "$here/icon.svg" -o "$img/icon-192.png"
rsvg-convert -w 512 -h 512 "$here/icon.svg" -o "$img/icon-512.png"
# iOS rounds the corners itself, so its icon is a full square without our own edge.
square="$(mktemp --suffix=.svg)"
sed -e 's/rx="24"/rx="0"/' -e '/stroke-opacity="\.16"/d' "$here/icon.svg" > "$square"
rsvg-convert -w 180 -h 180 "$square" -o "$img/apple-touch-icon.png"
rm -f "$square"
for size in 16 32 48; do rsvg-convert -w "$size" "$img/favicon.svg" -o "/tmp/telepad-favicon-$size.png"; done
magick /tmp/telepad-favicon-16.png /tmp/telepad-favicon-32.png /tmp/telepad-favicon-48.png "$img/favicon.ico"
rm -f /tmp/telepad-favicon-16.png /tmp/telepad-favicon-32.png /tmp/telepad-favicon-48.png
optipng -quiet -o4 "$img/icon-192.png" "$img/icon-512.png" "$img/apple-touch-icon.png"

# --- Social images -----------------------------------------------------------------------
# The page loads fonts and screenshots from neighbouring folders, so serve the repository.
python3 -m http.server "$port" --bind 127.0.0.1 --directory "$repo" > /dev/null 2>&1 &
server=$!
trap 'kill "$server" 2>/dev/null || true' EXIT
sleep 1

shoot() {  # shoot <width> <height> <query> <output>
  chromium --headless=new --disable-gpu --hide-scrollbars --force-device-scale-factor=1 \
    --window-size="$1,$2" --virtual-time-budget=5000 \
    --screenshot="$4" "http://127.0.0.1:$port/docs/brand/social-preview.html$3" 2> /dev/null
}
shoot 1280 640 "" "$here/social-preview.png"
shoot 1200 630 "?og" "$img/og.png"
optipng -quiet -o4 "$here/social-preview.png" "$img/og.png"

echo "Done. Upload docs/brand/social-preview.png under Settings > General > Social preview."

#!/usr/bin/env python3
"""Turns the app screenshots into the web images the landing page uses.

The screenshots are drawn by the Android screenshot tests (see CONTRIBUTING.md), so after a
design change: `./gradlew recordRoborazziDebug`, then run this script. It writes an AVIF and a
WebP of every picture listed below into website/assets/img/screens/.

Needs: Python 3 with Pillow built with AVIF and WebP support.
"""
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / "android" / "app" / "src" / "test" / "screenshots"
OUT = ROOT / "website" / "assets" / "img" / "screens"

# name -> width in pixels on the page at 2x. Phones are 822 px wide, so 640 keeps them sharp.
PICTURES = {
    "devices-connected": 640,
    "devices-list-dark": 640,
    "pairing-verify": 640,
    "pairing-verify-dark": 640,
    "remote-pad": 640,
    "remote-pad-dark": 640,
    "remote-keys": 640,
    "remote-keys-dark": 640,
    "remote-keys-mac": 640,
    "remote-media": 640,
    "remote-media-dark": 640,
    "settings-privacy": 640,
    "settings-touchpad": 640,
    "settings-appearance": 640,
    "onboarding": 640,
    "remote-pad-tablet": 900,
    "remote-pad-landscape": 1100,
}


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    total = 0
    for name, width in PICTURES.items():
        image = Image.open(SOURCE / f"{name}.png").convert("RGB")
        height = round(image.height * width / image.width)
        image = image.resize((width, height), Image.LANCZOS)
        for ext, options in (("avif", {"quality": 52, "speed": 4}), ("webp", {"quality": 82, "method": 6})):
            target = OUT / f"{name}.{ext}"
            image.save(target, **options)
            total += target.stat().st_size
        print(f"{name:24} {width}x{height}")
    print(f"{len(PICTURES) * 2} files, {total // 1024} KB in {OUT.relative_to(ROOT)}")


if __name__ == "__main__":
    main()

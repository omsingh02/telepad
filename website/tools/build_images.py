#!/usr/bin/env python3
"""Turns the app screenshots into the web images the landing page uses.

The screenshots are drawn by the Android screenshot tests (see CONTRIBUTING.md), so after a
design change: `./gradlew recordRoborazziDebug`, then run this script. It writes an AVIF and a
WebP of every picture listed below into website/assets/img/screens/.

Needs: Python 3 with Pillow built with AVIF and WebP support.
"""
from pathlib import Path

from PIL import Image, ImageChops, ImageDraw

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / "android" / "app" / "src" / "test" / "screenshots"
# The picture of the tray app's page, made from the real program by docs/brand/pc-page.sh.
PC_PAGE = ROOT / "docs" / "brand" / "pc-page.png"
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
    "pc-page": 640,
    "scan-qr": 640,
    "remote-pad-tablet": 900,
    "remote-pad-landscape": 1100,
}


def white_box(image, region, threshold=245):
    """The box around the pure white in a part of a picture (left, top, right, bottom), in the picture's own pixels."""
    crop = image.crop(region).convert("RGB")
    red, green, blue = crop.split()
    lightest_dark = ImageChops.darker(ImageChops.darker(red, green), blue)
    box = lightest_dark.point(lambda v: 255 if v >= threshold else 0).getbbox()
    if box is None:
        raise SystemExit(f"no white found in {region}")
    return (box[0] + region[0], box[1] + region[1], box[2] + region[0], box[3] + region[1])


def scan_with_code():
    """The scanner's screen with the PC's QR code in its frame, the way it looks when the phone is aimed at the PC.

    The screen tests draw the scanner without a camera, so their frame is empty."""
    scan = Image.open(SOURCE / "scan-camera.png").convert("RGB")
    page = Image.open(PC_PAGE).convert("RGB")

    # The frame is the white ring in the middle of the screen (the text below it is cut off by the region).
    frame = white_box(scan, (0, 300, scan.width, 1200))
    ring = 8  # the ring's thickness in pixels
    inner = (frame[0] + ring, frame[1] + ring, frame[2] - ring, frame[3] - ring)
    size = inner[2] - inner[0]

    # The code is the white square on the page.
    code = page.crop(white_box(page, (0, 250, page.width, 1100), threshold=250))
    code = code.resize((round(size * 0.84), round(size * 0.84)), Image.LANCZOS).convert("RGBA")
    code = code.rotate(-4, resample=Image.BICUBIC, expand=True)  # a hand does not hold a phone straight

    layer = Image.new("RGBA", scan.size, (0, 0, 0, 0))
    layer.paste(code, (inner[0] + (size - code.width) // 2, inner[1] + (size - code.height) // 2), code)
    # Only inside the frame: the frame's corners are round.
    mask = Image.new("L", scan.size, 0)
    ImageDraw.Draw(mask).rounded_rectangle(inner, radius=round(size * 0.1), fill=255)
    layer.putalpha(ImageChops.multiply(layer.getchannel("A"), mask))
    scan.paste(layer, (0, 0), layer)
    return scan


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    total = 0
    for name, width in PICTURES.items():
        if name == "pc-page":
            image = Image.open(PC_PAGE).convert("RGB")
        elif name == "scan-qr":
            image = scan_with_code()
        else:
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

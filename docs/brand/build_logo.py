#!/usr/bin/env python3
"""Generates the Telepad logo files: icon.svg, logo.svg and logo-dark.svg.

The icon is the Android launcher icon (android/app/src/main/res/drawable/ic_launcher_*.xml)
drawn as SVG. The wordmark is the word "Telepad" set in Cal Sans and converted to outlines,
so the logo looks the same everywhere and needs no font. Cal Sans is licensed under the SIL
Open Font License 1.1, which allows this.

Needs: Python 3, fontTools, and CalSans-Regular.ttf (https://github.com/calcom/font).
    python3 docs/brand/build_logo.py [path/to/CalSans-Regular.ttf]
"""
import sys
from pathlib import Path

from fontTools.pens.svgPathPen import SVGPathPen
from fontTools.pens.transformPen import TransformPen
from fontTools.ttLib import TTFont

OUT = Path(__file__).resolve().parent
FONT = Path(sys.argv[1] if len(sys.argv) > 1 else "/usr/share/fonts/CalSans-Regular.ttf")
WORD = "Telepad"

NAVY = "#0B1220"
INK_ON_DARK = "#F1F5F9"

# --- The icon -------------------------------------------------------------------------------
# 108 x 108 units, like the Android adaptive icon. The artwork is scaled up from the launcher's
# safe zone so that it fills a standalone icon.
ICON_BODY = """\
  <defs>
    <linearGradient id="tp-bg" x1="0" y1="0" x2="108" y2="108" gradientUnits="userSpaceOnUse">
      <stop offset="0" stop-color="#1E293B"/>
      <stop offset="1" stop-color="#0B1220"/>
    </linearGradient>
    <linearGradient id="tp-pointer" x1="44" y1="41" x2="58" y2="61" gradientUnits="userSpaceOnUse">
      <stop offset="0" stop-color="#7DD3FC"/>
      <stop offset=".5" stop-color="#38BDF8"/>
      <stop offset="1" stop-color="#2563EB"/>
    </linearGradient>
    <clipPath id="tp-shape"><rect width="108" height="108" rx="24"/></clipPath>
  </defs>
  <g clip-path="url(#tp-shape)">
    <rect width="108" height="108" fill="url(#tp-bg)"/>
    <g transform="translate(54 54) scale(1.3) translate(-54 -54)">
      <path d="M35,35H73A7,7 0 0 1 80,42V66A7,7 0 0 1 73,73H35A7,7 0 0 1 28,66V42A7,7 0 0 1 35,35Z" fill="#fff" fill-opacity=".14" stroke="#fff" stroke-opacity=".95" stroke-width="3.2"/>
      <path d="M29.6,62H78.4M54,62V71.4" stroke="#fff" stroke-opacity=".5" stroke-width="2.2" fill="none"/>
      <path d="M44,41L44,58L48.5,53.8L51.8,61L55,59.6L51.7,52.6L58,52.2Z" fill="url(#tp-pointer)"/>
    </g>
    <rect x=".5" y=".5" width="107" height="107" rx="23.5" fill="none" stroke="#fff" stroke-opacity=".16"/>
  </g>
"""


def icon_svg():
    return (
        '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 108 108" width="512" height="512" role="img" aria-label="Telepad">\n'
        "  <title>Telepad</title>\n" + ICON_BODY + "</svg>\n"
    )


# --- The wordmark ---------------------------------------------------------------------------
def kerning(font):
    """f(left, right) -> horizontal adjustment from the font's GPOS pair kerning."""
    gpos = font["GPOS"].table
    lookups = sorted({i for fr in gpos.FeatureList.FeatureRecord if fr.FeatureTag == "kern"
                      for i in fr.Feature.LookupListIndex})

    def value(left, right):
        for index in lookups:
            for sub in gpos.LookupList.Lookup[index].SubTable:
                if sub.LookupType == 9:
                    sub = sub.ExtSubTable
                if sub.LookupType != 2 or left not in sub.Coverage.glyphs:
                    continue
                if sub.Format == 1:
                    pair_set = sub.PairSet[sub.Coverage.glyphs.index(left)]
                    for record in pair_set.PairValueRecord:
                        if record.SecondGlyph == right:
                            return getattr(record.Value1, "XAdvance", 0) or 0
                else:
                    c1 = sub.ClassDef1.classDefs.get(left, 0)
                    c2 = sub.ClassDef2.classDefs.get(right, 0)
                    v = getattr(sub.Class1Record[c1].Class2Record[c2].Value1, "XAdvance", 0) or 0
                    if v:
                        return v
        return 0

    return value


def wordmark(font, text, tracking=0):
    """Returns (svg path data, width) in font units, y pointing down, baseline at 0."""
    glyphs = font.getGlyphSet()
    cmap = font.getBestCmap()
    kern = kerning(font)
    names = [cmap[ord(c)] for c in text]
    x, parts = 0, []
    for i, name in enumerate(names):
        pen = SVGPathPen(glyphs)
        glyphs[name].draw(TransformPen(pen, (1, 0, 0, -1, x, 0)))
        parts.append(pen.getCommands())
        x += glyphs[name].width + tracking
        if i + 1 < len(names):
            x += kern(name, names[i + 1])
    return " ".join(parts), x


def logo_svg(font, ink):
    upm = font["head"].unitsPerEm
    cap = font["OS/2"].sCapHeight
    x_height = font["OS/2"].sxHeight
    path, width = wordmark(font, WORD, tracking=-6)

    cap_units = 56                        # cap height of the wordmark, in icon units
    scale = cap_units / cap
    gap = 30
    text_x = 108 + gap
    baseline = 54 + (x_height * scale) / 2    # x-height band centred on the icon
    total = text_x + width * scale

    return (
        f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {total:.1f} 108" width="{total * 2:.0f}" height="216" role="img" aria-label="Telepad">\n'
        "  <title>Telepad</title>\n"
        + ICON_BODY.replace("tp-", "lg-")
        + f'  <path transform="translate({text_x} {baseline:.2f}) scale({scale:.5f})" fill="{ink}" d="{path}"/>\n'
        "</svg>\n"
    )


def main():
    font = TTFont(FONT)
    (OUT / "icon.svg").write_text(icon_svg(), encoding="utf-8")
    (OUT / "logo.svg").write_text(logo_svg(font, NAVY), encoding="utf-8")
    (OUT / "logo-dark.svg").write_text(logo_svg(font, INK_ON_DARK), encoding="utf-8")
    print("wrote icon.svg, logo.svg, logo-dark.svg in", OUT)


if __name__ == "__main__":
    main()

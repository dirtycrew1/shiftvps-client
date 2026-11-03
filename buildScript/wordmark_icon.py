"""Outline the approved text-only ShiftVPS icon from the bundled, pinned IBM Plex face."""

import hashlib
from pathlib import Path

from fontTools.pens.boundsPen import BoundsPen
from fontTools.pens.svgPathPen import SVGPathPen
from fontTools.pens.transformPen import TransformPen
from fontTools.ttLib import TTFont

ROOT = Path(__file__).resolve().parent.parent
FONT = ROOT / "composeApp/src/commonMain/composeResources/font/ibm_plex_sans_bold.ttf"
FONT_SHA256 = "9e6c74a889a700d707613d24548fe4ffa6bc59559a0689d2cf9e133bdcdafb2f"
CANVAS = 135.47
TEXT_COLOR = "#EDEDF0"
ACCENT_COLOR = "#9DAAFF"


def text_path(font, text, width, top):
    glyphs = font.getGlyphSet()
    cmap = font.getBestCmap()
    bounds = BoundsPen(glyphs)
    cursor = 0
    for character in text:
        name = cmap[ord(character)]
        glyphs[name].draw(TransformPen(bounds, (1, 0, 0, 1, cursor, 0)))
        cursor += font["hmtx"][name][0]
    left, bottom, right, ceiling = bounds.bounds
    scale = width / (right - left)
    x = (CANVAS - width) / 2 - left * scale
    y = top + ceiling * scale
    pen = SVGPathPen(glyphs, ntos=lambda value: f"{value:.5f}".rstrip("0").rstrip("."))
    cursor = 0
    for character in text:
        name = cmap[ord(character)]
        glyphs[name].draw(TransformPen(pen, (scale, 0, 0, -scale, x + cursor * scale, y)))
        cursor += font["hmtx"][name][0]
    return pen.getCommands(), (ceiling - bottom) * scale


def document(paths):
    body = "\n".join(f'  <path d="{path}" fill="{color}"/>' for path, color in paths)
    return f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {CANVAS} {CANVAS}">\n{body}\n</svg>\n'


def documents():
    if hashlib.sha256(FONT.read_bytes()).hexdigest() != FONT_SHA256:
        raise ValueError("Icon font differs from the pinned IBM Plex source")
    with TTFont(FONT) as font:
        shift, height = text_path(font, "SHIFT", 104, 27)
        vps, _ = text_path(font, "VPS", 104, 27 + height + 10)
        small, _ = text_path(font, "S", 75, 15)
    return {
        ROOT / "art/icon.svg": document([(shift, TEXT_COLOR), (vps, ACCENT_COLOR)]),
        ROOT / "art/icon-small.svg": document([(small, TEXT_COLOR)]),
    }


def generate():
    for path, source in documents().items():
        path.write_text(source, encoding="utf-8")


if __name__ == "__main__":
    generate()

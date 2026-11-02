#!/usr/bin/env python3
"""Validate the generated icon containers, dimensions and ShiftVPS artwork."""
import io
import struct
import xml.etree.ElementTree as xml
from pathlib import Path

from PIL import Image

import icon

ROOT = Path(__file__).resolve().parent.parent
ANDROID = ROOT / "composeApp" / "src" / "androidMain" / "res"
ANDROID_NAMESPACE = "{http://schemas.android.com/apk/res/android}"


def check_image(image, size, transparent):
    assert image.size == (size, size), (image.size, size)
    pixels = list(image.convert("RGBA").get_flattened_data())
    assert any(a > 200 and b > r + 30 and b > g + 30 for r, g, b, a in pixels), "blue arrow missing"
    assert any(a > 200 and min(r, g, b) > 220 for r, g, b, a in pixels), "white arrow missing"
    assert image.convert("RGBA").getpixel((0, 0))[3] == (0 if transparent else 255)


def main():
    assert icon.read_paths(icon.SOURCE_LARGE) != icon.read_paths(icon.SOURCE_SMALL), "size variants must remain distinct"
    for density, size in icon.ANDROID_MIPMAP_SIZES.items():
        path = ANDROID / f"mipmap-{density}" / "ic_launcher.webp"
        assert b"VP8L" in path.read_bytes(), "Android raster must be lossless WebP"
        with Image.open(path) as image:
            check_image(image, size, transparent=True)

    for filename, colors in (
        ("ic_launcher_foreground.xml", {"#5B72F2", "#F2F3F6"}),
        ("ic_launcher_monochrome.xml", {"#000000"}),
    ):
        root = xml.parse(ANDROID / "drawable" / filename).getroot()
        paths = root.findall(".//path")
        assert len(paths) == 2
        assert {path.attrib[ANDROID_NAMESPACE + "fillColor"] for path in paths} == colors
    tray = ROOT / "composeApp" / "src" / "commonMain" / "composeResources" / "drawable" / "ic_shiftvps.xml"
    assert len(xml.parse(tray).getroot().findall(".//path")) == 2
    assert xml.parse(ANDROID / "values" / "ic_launcher_background.xml").getroot().find("color").text == "#0E1014"

    with Image.open(ROOT / "launcher" / "icon.ico") as container:
        assert container.ico.sizes() == {(size, size) for size in icon.WINDOWS_SIZES}
        for size in icon.WINDOWS_SIZES:
            check_image(container.ico.getimage((size, size)), size, transparent=True)

    archive = (ROOT / "release" / "macos" / "desktop" / "icon.icns").read_bytes()
    magic, length = struct.unpack_from(">4sI", archive)
    assert magic == b"icns" and length == len(archive)
    offset, elements = 8, set()
    while offset < len(archive):
        name, length = struct.unpack_from(">4sI", archive, offset)
        key = name.decode("ascii")
        assert key in icon.MACOS_ELEMENTS and key not in elements and length >= 8
        with Image.open(io.BytesIO(archive[offset + 8:offset + length])) as image:
            check_image(image, icon.MACOS_ELEMENTS[key], transparent=True)
        elements.add(key)
        offset += length
    assert offset == len(archive) and elements == set(icon.MACOS_ELEMENTS)

    for path, transparent in (
        (ROOT / "release" / "linux" / "desktop" / "icon.png", True),
        (ROOT / "composeApp" / "src" / "androidMain" / "ic_launcher-playstore.png", False),
        (ROOT / "fastlane" / "metadata" / "android" / "en-US" / "images" / "icon.png", False),
    ):
        with Image.open(path) as image:
            check_image(image, 512, transparent)
    print("icons: verified 5 Android densities, 3 vectors, 7 ICO frames, 10 ICNS elements and 3 PNGs")


if __name__ == "__main__":
    main()

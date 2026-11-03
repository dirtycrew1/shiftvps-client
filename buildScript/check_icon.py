#!/usr/bin/env python3
"""Validate the generated icon containers, dimensions and ShiftVPS artwork."""
import io
import struct
import tempfile
import xml.etree.ElementTree as xml
from pathlib import Path

from PIL import Image

import icon
import wordmark_icon

ROOT = Path(__file__).resolve().parent.parent
ANDROID = ROOT / "composeApp" / "src" / "androidMain" / "res"
ANDROID_NAMESPACE = "{http://schemas.android.com/apk/res/android}"


def check_image(image, size, transparent):
    assert image.size == (size, size), (image.size, size)
    pixels = list(image.convert("RGBA").get_flattened_data())
    assert sum(a > 200 and min(r, g, b) > 210 for r, g, b, a in pixels) > size * size * 0.015, "wordmark missing"
    assert not any(a > 200 and r > g + 35 and r > b + 35 for r, g, b, a in pixels), "legacy red artwork remains"
    if size > icon.SMALL_ARTWORK_MAX_SIZE:
        assert any(a > 200 and b > r + 30 and b > g + 30 for r, g, b, a in pixels), "VPS accent missing"
    assert image.convert("RGBA").getpixel((0, 0))[3] == (0 if transparent else 255)


def check_adaptive_safe_zone(root):
    group = root.find("group")
    attributes = lambda name: group.attrib[ANDROID_NAMESPACE + name]
    paths = [
        {"d": path.attrib[ANDROID_NAMESPACE + "pathData"], "fill": path.attrib[ANDROID_NAMESPACE + "fillColor"]}
        for path in group.findall("path")
    ]
    svg = (
        f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {icon.CANVAS} {icon.CANVAS}">'
        f'<g transform="translate({attributes("translateX")},{attributes("translateY")}) '
        f'scale({attributes("scaleX")},{attributes("scaleY")})">{icon.render_paths(paths)}</g></svg>'
    )
    with tempfile.TemporaryDirectory() as folder:
        path = Path(folder) / "adaptive-safe-zone.png"
        icon.rasterize(svg, 216, path)
        with Image.open(path) as image:
            alpha = image.convert("RGBA").getchannel("A")
            for y in range(216):
                for x in range(216):
                    if alpha.getpixel((x, y)) > 200:
                        assert (x + 0.5 - 108)**2 + (y + 0.5 - 108)**2 <= 66**2, "adaptive text exceeds the 66dp safe circle"


def main():
    for path, expected in wordmark_icon.documents().items():
        assert path.read_text(encoding="utf-8") == expected, f"{path.name} must come from the pinned font outlines"
    assert icon.read_paths(icon.SOURCE_LARGE) != icon.read_paths(icon.SOURCE_SMALL), "size variants must remain distinct"
    for density, size in icon.ANDROID_MIPMAP_SIZES.items():
        path = ANDROID / f"mipmap-{density}" / "ic_launcher.webp"
        assert b"VP8L" in path.read_bytes(), "Android raster must be lossless WebP"
        with Image.open(path) as image:
            check_image(image, size, transparent=True)

    for filename, colors in (
        ("ic_launcher_foreground.xml", {wordmark_icon.TEXT_COLOR, wordmark_icon.ACCENT_COLOR}),
        ("ic_launcher_monochrome.xml", {"#000000"}),
    ):
        root = xml.parse(ANDROID / "drawable" / filename).getroot()
        paths = root.findall(".//path")
        assert len(paths) == 2
        assert {path.attrib[ANDROID_NAMESPACE + "fillColor"] for path in paths} == colors
    tray = ROOT / "composeApp" / "src" / "commonMain" / "composeResources" / "drawable" / "ic_shiftvps.xml"
    assert len(xml.parse(tray).getroot().findall(".//path")) == 2
    assert xml.parse(ANDROID / "values" / "ic_launcher_background.xml").getroot().find("color").text == icon.BACKGROUND
    for name in ("ic_shiftvps_notification.xml", "ic_service_active.xml", "ic_service_rest.xml"):
        root = xml.parse(ANDROID / "drawable" / name).getroot()
        paths = root.findall(".//path")
        assert len(paths) == 1, "small system icon must use the S outline"
        assert paths[0].attrib[ANDROID_NAMESPACE + "pathData"] == icon.read_paths(icon.SOURCE_SMALL)[0]["d"]
        assert {path.attrib[ANDROID_NAMESPACE + "fillColor"] for path in paths} == {"#000000"}
    foreground = xml.parse(ANDROID / "drawable/ic_launcher_foreground.xml").getroot()
    group = foreground.find("group")
    assert float(group.attrib[ANDROID_NAMESPACE + "scaleX"]) == float(group.attrib[ANDROID_NAMESPACE + "scaleY"])
    assert icon.ANDROID_MARK_FRACTION < 66 / icon.ANDROID_LAYER_DP, "adaptive artwork exceeds the central safe zone"
    check_adaptive_safe_zone(foreground)
    for name in ("ic_service_active.xml", "ic_service_rest.xml"):
        assert (ROOT / "composeApp/src/commonMain/composeResources/drawable" / name).read_bytes() == (ANDROID / "drawable" / name).read_bytes()

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
    with Image.open(ROOT / "art/icon-atlas.png") as atlas:
        assert atlas.size == (1100, 720)
    installer = (ROOT / "release/windows/desktop/installer.nsi").read_text(encoding="utf-8")
    assert '!define MUI_ICON "__HUSI_ICON_FILE__"' in installer
    assert '!define MUI_UNICON "__HUSI_ICON_FILE__"' in installer
    assert '"DisplayIcon" "$INSTDIR\\${APP_NAME}.exe,0"' in installer
    assert '"__HUSI_ICON_FILE__" "$ROOT_DIR/launcher/icon.ico"' in (ROOT / "release/windows/package.sh").read_text(encoding="utf-8")
    assert 'ICON "icon.ico"' in (ROOT / "launcher/icon.rc").read_text(encoding="utf-8")
    print("icons: verified pinned glyph outlines, Android safe zone/system masks, 7 ICO frames, 10 ICNS elements, atlas and package wiring")


if __name__ == "__main__":
    main()

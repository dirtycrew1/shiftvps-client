# ShiftVPS application icons

The application icon uses the text wordmark `SHIFT` / `VPS`. Sizes of 32 pixels and below use
the `S` glyph. Both outlines come from the bundled IBM Plex Sans Bold face documented in
[FONT-PROVENANCE.md](../FONT-PROVENANCE.md), with its pinned SHA-256 verified before generation.
The original SIL Open Font License remains in the repository and application resources.

`buildScript/wordmark_icon.py` converts the glyphs to path-only SVG sources. `art/icon.svg` and
`art/icon-small.svg` are generated inputs to the platform icon pipeline; change their generator
when changing the lettering. Colors follow the native website identity: near-black `#08080B`,
light text `#EDEDF0`, and accent `#9DAAFF`.

```sh
python3 -m pip install -r buildScript/icon-requirements.txt
make icon
python3 buildScript/check_icon.py
```

The pipeline uses `rsvg-convert` when available and the pinned resvg Python renderer otherwise.
ImageMagick and its pinned Pillow fallback write the platform containers. No system font lookup
is needed to render the outlined artwork.

Generated outputs cover Windows ICO frames, macOS ICNS elements, Android adaptive/themed and
legacy launchers, monochrome notification/Quick Settings artwork, the shared desktop window/tray
vector, Linux PNG and store metadata images. Generated assets must not be edited by hand.

`art/icon-atlas.png` is a labeled synthetic preview with native ICO sizes, Android masks and
monochrome notification samples. `check_icon.py` validates the source outlines, actual adaptive
vector ink against the central 66dp safe circle, alpha/palette, ICO/ICNS frame sets and packaging
references. The Windows compiler-only updater check also verifies the branded icon frames are
embedded in the installer, without executing the installer.

Windows uses the same generated family in the native launcher, Compose window/tray,
installer/uninstaller, shortcuts, file associations and Add/Remove Programs entry. Nucleus keeps
the application identity `ru.shiftvps.client` and display name `ShiftVPS`. macOS packaging supplies
the generated ICNS to the application bundle and launcher dock option. Actual OS rendering,
existing pinned shortcuts and OS icon caches are not verified by these generated previews.

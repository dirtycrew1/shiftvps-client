# ShiftVPS application mark

The two shift arrows are authored as paths in `icon.svg`. `icon-small.svg` has
heavier stems for 96 px and smaller outputs; keep both sources separate.
The mark uses ShiftVPS blue (`#5B72F2`) and off-white on a neutral dark plate.

Regenerate every platform asset with `make icon`. Never edit generated icons or
Android vector XML by hand. The existing rsvg-convert/ImageMagick pipeline is
preferred when installed. A portable, build-only fallback is available with:

```sh
python3 -m pip install -r buildScript/icon-requirements.txt
make icon
python3 buildScript/check_icon.py
```

To select a Python interpreter, set `PYTHON` before invoking `make icon`, for
example `PYTHON=/path/to/venv/bin/python make icon`. These Python packages are
not application/runtime dependencies. Existing upstream license and notices apply.

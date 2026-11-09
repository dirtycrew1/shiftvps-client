"""Render the additional two-letter flags from the pinned source archive."""

import re
import sys
from pathlib import Path
from zipfile import ZipFile

from resvg_py import svg_to_bytes

REVISION = "ac7e0eb3259eb62aa6100c435607e2fe03896a97"
PREFIX = f"flag-icons-{REVISION}/flags/4x3/"
TARGET = Path(__file__).resolve().parents[2] / "composeApp/src/commonMain/composeResources/drawable"


def main(archive_path):
    with ZipFile(archive_path) as archive:
        flags = {name[len(PREFIX):-4]: name for name in archive.namelist()
                 if name.startswith(PREFIX) and re.fullmatch(r"[a-z]{2}\.svg", name[len(PREFIX):])}
        if len(flags) != 257 or "xx" not in flags:
            raise ValueError("Expected the pinned flag-icons 4:3 archive")
        for code, name in sorted(flags.items()):
            target = TARGET / f"flag_{code}.png"
            if code == "xx" or target.with_suffix(".xml").is_file():
                continue
            png = svg_to_bytes(svg_string=archive.read(name).decode("utf-8"), width=112, height=84,
                               skip_system_fonts=True)
            target.write_bytes(png)


if __name__ == "__main__":
    main(sys.argv[1])

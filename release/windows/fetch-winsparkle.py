#!/usr/bin/env python3
"""Extract only the pinned official WinSparkle DLL and its license notices."""

import argparse
import hashlib
import os
from pathlib import Path
import shutil
import tempfile
import urllib.request
import zipfile

VERSION = "0.9.4"
ARCHIVE_URL = f"https://github.com/vslavik/winsparkle/releases/download/v{VERSION}/WinSparkle-{VERSION}.zip"
# GitHub release API's digest for the official binary archive.
ARCHIVE_SHA256 = "6037df37fc263bd1650a1c4949681a9d40ffe991d01f35892a406cb5d103c976"
LIBRARIES = {
    "amd64": ("x64", "9b43b1c16ee39fb9a91b5bd75138767898779510e0836be2919250607cdbe8ab"),
    "arm64": ("ARM64", "f38829a6b1ef853b9bed780e9e40614c100e0a3020318555c9f6681deade5756"),
}
NOTICES = ("COPYING", "COPYING.expat", "AUTHORS")


def verified_archive(cache: Path) -> Path:
    archive = cache / f"WinSparkle-{VERSION}.zip"
    if archive.is_file():
        if hashlib.sha256(archive.read_bytes()).hexdigest() == ARCHIVE_SHA256:
            return archive
        raise ValueError(f"WinSparkle cache checksum mismatch: {archive}")
    cache.mkdir(parents=True, exist_ok=True)
    descriptor, pending_name = tempfile.mkstemp(prefix="winsparkle-", suffix=".pending", dir=cache)
    pending = Path(pending_name)
    try:
        with os.fdopen(descriptor, "wb") as output:
            with urllib.request.urlopen(ARCHIVE_URL, timeout=120) as response:
                shutil.copyfileobj(response, output)
        if hashlib.sha256(pending.read_bytes()).hexdigest() != ARCHIVE_SHA256:
            raise ValueError("The official WinSparkle archive checksum did not match the pin")
        pending.replace(archive)
    finally:
        pending.unlink(missing_ok=True)
    return archive


def extract(archive: Path, architecture: str, output: Path) -> None:
    directory, expected_hash = LIBRARIES[architecture]
    with zipfile.ZipFile(archive) as bundle:
        prefix = f"WinSparkle-{VERSION}/"
        library = bundle.read(f"{prefix}{directory}/Release/WinSparkle.dll")
        if hashlib.sha256(library).hexdigest() != expected_hash:
            raise ValueError("WinSparkle DLL checksum did not match the architecture pin")
        output.mkdir(parents=True, exist_ok=True)
        (output / "WinSparkle.dll").write_bytes(library)
        notices = output / "licenses" / "WinSparkle"
        notices.mkdir(parents=True, exist_ok=True)
        for name in NOTICES:
            (notices / name).write_bytes(bundle.read(prefix + name))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--arch", required=True, choices=LIBRARIES)
    parser.add_argument("--output-dir", required=True, type=Path)
    parser.add_argument("--cache-dir", type=Path, default=Path(os.environ.get("XDG_CACHE_HOME", Path.home() / ".cache")) / "shiftvps" / "winsparkle")
    arguments = parser.parse_args()
    extract(verified_archive(arguments.cache_dir), arguments.arch, arguments.output_dir)

#!/usr/bin/env python3
"""Check the updater supply-chain pins and compile installers without executing them."""

import argparse
import hashlib
import importlib.util
from pathlib import Path
import subprocess
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("fetch_winsparkle", Path(__file__).with_name("fetch-winsparkle.py"))
fetch = importlib.util.module_from_spec(spec)
spec.loader.exec_module(fetch)


def check_pins(archive: Path, work: Path) -> None:
    assert hashlib.sha256(archive.read_bytes()).hexdigest() == fetch.ARCHIVE_SHA256
    kotlin = (ROOT / "composeApp/src/desktopMain/kotlin/fr/husi/shiftvps/DesktopWindowsUpdater.kt").read_text(encoding="utf-8")
    for arch, (_, expected) in fetch.LIBRARIES.items():
        output = work / arch
        fetch.extract(archive, arch, output)
        assert hashlib.sha256((output / "WinSparkle.dll").read_bytes()).hexdigest() == expected
        assert expected in kotlin, "Runtime and packaging DLL pins differ"
        assert all((output / "licenses/WinSparkle" / name).is_file() for name in fetch.NOTICES)
    bad_cache = work / "bad-cache"
    bad_cache.mkdir()
    (bad_cache / f"WinSparkle-{fetch.VERSION}.zip").write_bytes(b"tampered")
    try:
        fetch.verified_archive(bad_cache)
    except ValueError:
        pass
    else:
        raise AssertionError("Corrupt cached archive was accepted")
    bad_dll = work / "bad-dll.zip"
    with zipfile.ZipFile(bad_dll, "w") as bundle:
        bundle.writestr(f"WinSparkle-{fetch.VERSION}/x64/Release/WinSparkle.dll", b"tampered")
    try:
        fetch.extract(bad_dll, "amd64", work / "bad-output")
    except ValueError:
        pass
    else:
        raise AssertionError("Corrupt native updater DLL was accepted")


def compile_installer(compiler: Path, work: Path) -> None:
    stub = work / "inert-payload"
    stub.write_bytes(b"compiler-only placeholder, never executed")
    for bundled_runtime in (False, True):
        name = "bundled-runtime" if bundled_runtime else "thin"
        output = work / f"{name}-installer.exe"
        values = {
            "PACKAGE_NAME": "ru.shiftvps.client", "APP_NAME": "ShiftVPS", "APP_NAME_ZH_CN": "ShiftVPS",
            "APP_VERSION": "0.1.1", "APP_VERSION_CODE": "2", "APP_DESCRIPTION": "ShiftVPS",
            "APP_URL": "https://shiftvps.ru", "MAINTAINER": "ShiftVPS", "VI_VERSION": "0.1.1.0",
            "OUTPUT_FILE": str(output), "LICENSE_FILE": str(stub), "LAUNCHER_FILE": str(stub),
            "CORE_FILE": str(stub), "CORE_LIBRARY_NAME": "libcronet.dll", "CORE_LIBRARY_FILE": str(stub),
            "JAR_FILE": str(stub), "JAVA_OPTS_FILE": str(stub), "APP_ARGS_FILE": str(stub),
            "WINSPARKLE_FILE": str(work / "amd64/WinSparkle.dll"),
            "WINSPARKLE_COPYING_FILE": str(work / "amd64/licenses/WinSparkle/COPYING"),
            "WINSPARKLE_EXPAT_FILE": str(work / "amd64/licenses/WinSparkle/COPYING.expat"),
            "WINSPARKLE_AUTHORS_FILE": str(work / "amd64/licenses/WinSparkle/AUTHORS"),
            "URL_SCHEME_REGISTRY": "", "URL_SCHEME_UNREGISTRY": "",
            "RUNTIME_INSTALL": f'SetOutPath "$PayloadDir\\runtime"\nFile "{stub}"\nSetOutPath "$PayloadDir"' if bundled_runtime else "",
            "RUNTIME_UNINSTALL": 'RMDir /r "$INSTDIR\\runtime"' if bundled_runtime else "",
        }
        template = (ROOT / "release/windows/desktop/installer.nsi").read_text(encoding="utf-8")
        for key, value in values.items():
            template = template.replace(f"__HUSI_{key}__", value)
        assert "__HUSI_" not in template, "Unrendered installer placeholder"
        source = work / f"{name}.nsi"
        source.write_text(template, encoding="utf-8-sig")
        result = subprocess.run([str(compiler), "/V2", str(source)], text=True, capture_output=True)
        if result.returncode:
            raise AssertionError(result.stdout + result.stderr)
        assert output.is_file() and output.stat().st_size > 0
        print(f"NSIS compiler accepted {name} installer; installer was not executed")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--winsparkle-archive", required=True, type=Path)
    parser.add_argument("--makensis", type=Path)
    args = parser.parse_args()
    with tempfile.TemporaryDirectory(prefix="shiftvps-windows-updater-check-") as directory:
        temporary = Path(directory)
        check_pins(args.winsparkle_archive, temporary)
        print("Official archive, both native DLL pins, licenses and tamper rejection passed")
        if args.makensis:
            compile_installer(args.makensis, temporary)

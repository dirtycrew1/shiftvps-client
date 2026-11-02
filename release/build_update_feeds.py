#!/usr/bin/env python3
"""Produce signed update metadata offline. This command never uploads or runs artifacts."""

import argparse
import base64
from dataclasses import dataclass, replace
import hashlib
import hmac
import json
import mmap
import os
from pathlib import Path
import re
import stat
import subprocess
import sys
import tempfile
from urllib.parse import urlsplit
import xml.etree.ElementTree as ET

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ed25519, padding, rsa


ROOT = Path(__file__).resolve().parents[1]
SPARKLE = "http://www.andymatuschak.org/xml-namespaces/sparkle"
RELEASE_BASE = "https://github.com/dirtycrew1/shiftvps-client/releases/download"
ARTIFACT_TYPES = {
    **{f"android-{abi}": ".apk" for abi in ("arm64-v8a", "armeabi-v7a", "x86_64", "x86")},
    **{f"windows-{arch}": ".exe" for arch in ("amd64", "arm64")},
    **{f"macos-{arch}": ".dmg" for arch in ("amd64", "arm64")},
}
REQUIRED_ARTIFACTS = set(ARTIFACT_TYPES) - {"windows-arm64"}
KEY_NAMES = {"update-ed25519", "update-rsa"}
VERSION_PATTERN = re.compile(r"[0-9]+\.[0-9]+\.[0-9]+(?:[-+][0-9A-Za-z.-]+)?")
ASSET_PATTERN = re.compile(r"[A-Za-z0-9][A-Za-z0-9._-]{0,199}")
ET.register_namespace("sparkle", SPARKLE)


def compact_json(value):
    return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=True).encode("utf-8")


def unique_json_pairs(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("Duplicate JSON field")
        result[key] = value
    return result


def read_json(data):
    try:
        return json.loads(data, object_pairs_hook=unique_json_pairs)
    except (ValueError, UnicodeError, RecursionError):
        raise ValueError("Invalid JSON input") from None


def validate_version(version_name, version_code, previous_version_code, channel):
    if not isinstance(version_name, str) or len(version_name) > 64 or not VERSION_PATTERN.fullmatch(version_name):
        raise ValueError("Version name does not match the Android client version format")
    if type(version_code) is not int or not 1 <= version_code <= 2_100_000_000:
        raise ValueError("Version code must be an integer between 1 and 2100000000")
    if type(previous_version_code) is not int or not 0 <= previous_version_code < version_code:
        raise ValueError("Version code must be greater than the previous published version code")
    if channel != "stable":
        raise ValueError("Current clients support only the stable update channel")


def require_artifact_url(url, version_name, filename):
    """Accept exactly the canonical own-release HTTPS URL, without redirects or credentials."""
    if not ASSET_PATTERN.fullmatch(filename):
        raise ValueError("Artifact filename must use only safe ASCII release-asset characters")
    parsed = urlsplit(url)
    expected = f"{RELEASE_BASE}/v{version_name}/{filename}"
    if parsed.scheme != "https" or parsed.netloc != "github.com" or url != expected:
        raise ValueError("Artifact URL must refer to this version in the own GitHub release")


@dataclass(frozen=True)
class Artifact:
    key: str
    path: Path
    url: str
    size: int
    sha256: str
    identity: tuple
    signature: str | None = None


def file_identity(path):
    info = path.stat()
    return info.st_dev, info.st_ino, info.st_size, info.st_mtime_ns, info.st_ctime_ns


def inspect_artifacts(mapping, version_name, require_all=False):
    if not mapping or not set(mapping).issubset(ARTIFACT_TYPES):
        raise ValueError("Provide at least one artifact with a supported platform and architecture key")
    if require_all and not REQUIRED_ARTIFACTS.issubset(mapping):
        raise ValueError("The full release requires all seven currently supported platform and architecture artifacts")
    artifacts = []
    paths, names = set(), set()
    for key, value in sorted(mapping.items()):
        try:
            path = Path(value).resolve(strict=True)
        except (OSError, ValueError):
            raise ValueError("An explicitly mapped artifact does not exist") from None
        if not path.is_file() or path.suffix.lower() != ARTIFACT_TYPES[key]:
            raise ValueError("An artifact is not a regular file with the required platform extension")
        filename = path.name
        if path in paths or filename.lower() in names:
            raise ValueError("Duplicate artifact paths or release asset filenames are ambiguous")
        paths.add(path)
        names.add(filename.lower())
        url = f"{RELEASE_BASE}/v{version_name}/{filename}"
        require_artifact_url(url, version_name, filename)
        identity = file_identity(path)
        size = identity[2]
        maximum = 512 * 1024 * 1024 if key.startswith("android-") else 2 * 1024 * 1024 * 1024
        if not 1 <= size <= maximum:
            raise ValueError("An artifact is empty or exceeds the client download limit")
        digest = hashlib.sha256()
        with path.open("rb") as stream:
            for chunk in iter(lambda: stream.read(1024 * 1024), b""):
                digest.update(chunk)
        if file_identity(path) != identity:
            raise ValueError("An artifact changed while its metadata was being prepared")
        artifacts.append(Artifact(key, path, url, size, digest.hexdigest(), identity))
    return artifacts


def verify_signing_keys(ed_key, rsa_key, pinned):
    if not isinstance(ed_key, ed25519.Ed25519PrivateKey) or not isinstance(rsa_key, rsa.RSAPrivateKey):
        raise ValueError("Expected Ed25519 and RSA private signing keys")
    if rsa_key.key_size < 2048:
        raise ValueError("The Android update RSA signing key must be at least 2048 bits")
    try:
        expected_ed = base64.b64decode(pinned["update-ed25519"], validate=True)
        expected_rsa = base64.b64decode(pinned["update-rsa"], validate=True)
    except (ValueError, KeyError, TypeError):
        raise ValueError("Pinned public update keys are invalid") from None
    actual_ed = ed_key.public_key().public_bytes(serialization.Encoding.Raw, serialization.PublicFormat.Raw)
    actual_rsa = rsa_key.public_key().public_bytes(
        serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo,
    )
    if not hmac.compare_digest(expected_ed, actual_ed) or not hmac.compare_digest(expected_rsa, actual_rsa):
        raise ValueError("A signing key does not match the pinned client public key")


def sign_archive(artifact, key):
    if file_identity(artifact.path) != artifact.identity:
        raise ValueError("An artifact changed before it could be signed")
    with artifact.path.open("rb") as stream:
        with mmap.mmap(stream.fileno(), 0, access=mmap.ACCESS_READ) as content:
            signature = key.sign(content)
    if file_identity(artifact.path) != artifact.identity:
        raise ValueError("An artifact changed while it was being signed")
    return replace(artifact, signature=base64.b64encode(signature).decode("ascii"))


def appcast(artifact, version_name, version_code):
    rss = ET.Element("rss", {"version": "2.0"})
    channel = ET.SubElement(rss, "channel")
    ET.SubElement(channel, "title").text = "ShiftVPS updates"
    item = ET.SubElement(channel, "item")
    ET.SubElement(item, "title").text = f"ShiftVPS {version_name}"
    ET.SubElement(item, f"{{{SPARKLE}}}version").text = str(version_code)
    ET.SubElement(item, f"{{{SPARKLE}}}shortVersionString").text = version_name
    if artifact.key.startswith("macos-"):
        ET.SubElement(item, f"{{{SPARKLE}}}minimumSystemVersion").text = "13.0"
    attributes = {
        "url": artifact.url, "length": str(artifact.size), "type": "application/octet-stream",
        f"{{{SPARKLE}}}edSignature": artifact.signature,
        f"{{{SPARKLE}}}version": str(version_code),
        f"{{{SPARKLE}}}shortVersionString": version_name,
    }
    if artifact.key.startswith("windows-"):
        attributes[f"{{{SPARKLE}}}os"] = "windows"
    ET.SubElement(item, "enclosure", attributes)
    return ET.tostring(rss, encoding="utf-8", xml_declaration=True) + b"\n"


def build_outputs(mapping, version_name, version_code, previous_version_code, channel, ed_key, rsa_key, pinned,
                  require_all=False):
    validate_version(version_name, version_code, previous_version_code, channel)
    verify_signing_keys(ed_key, rsa_key, pinned)
    artifacts = inspect_artifacts(mapping, version_name, require_all)
    artifacts = [sign_archive(item, ed_key) if not item.key.startswith("android-") else item for item in artifacts]
    outputs = {}
    android = {item.key: {"url": item.url, "sha256": item.sha256, "size": item.size}
               for item in artifacts if item.key.startswith("android-")}
    if android:
        payload = compact_json({
            "schema": 1, "channel": channel, "version_code": version_code,
            "version": version_name, "artifacts": android,
        })
        signature = rsa_key.sign(payload, padding.PKCS1v15(), hashes.SHA256())
        outputs["android.json"] = compact_json({
            "payload": base64.b64encode(payload).decode("ascii"),
            "signature": base64.b64encode(signature).decode("ascii"),
        }) + b"\n"
        if len(outputs["android.json"]) > 64 * 1024:
            raise ValueError("The signed Android envelope exceeds the client metadata limit")
    for artifact in artifacts:
        if artifact.key.startswith("android-"):
            continue
        content = appcast(artifact, version_name, version_code)
        if artifact.key.startswith("macos-"):
            signature = base64.b64encode(ed_key.sign(content)).decode("ascii")
            # Sparkle 2.9+ authenticates exactly the bytes before this final comment.
            content += (f"<!-- sparkle-signatures:\nedSignature: {signature}\n"
                        f"length: {len(content)}\n-->\n").encode("ascii")
        outputs[f"{artifact.key}.xml"] = content
    for artifact in artifacts:
        if file_identity(artifact.path) != artifact.identity:
            raise ValueError("An artifact changed before all metadata was ready")
    inventory = {
        "schema": 1, "channel": channel, "version": version_name, "version_code": version_code,
        "previous_version_code": previous_version_code,
        "artifacts": {item.key: {"filename": item.path.name, "url": item.url, "size": item.size,
                                  "sha256": item.sha256,
                                  **({"ed25519_signature": item.signature} if item.signature else {})}
                      for item in artifacts},
        "metadata": {name: {"size": len(data), "sha256": hashlib.sha256(data).hexdigest()}
                     for name, data in outputs.items()},
    }
    outputs["inventory.json"] = compact_json(inventory) + b"\n"
    return outputs


def require_protected_password_file(path):
    """Reject password files readable or replaceable by unrelated local accounts."""
    path = path.resolve(strict=True)
    if not path.is_file():
        raise ValueError("Password input must be a regular protected JSON file")
    if os.name == "nt":
        # Read only ACLs. The subprocess receives a path on stdin, never passwords.
        command = r"""
$ErrorActionPreference = 'Stop'
$path = ConvertFrom-Json -InputObject ([Console]::In.ReadToEnd())
$allowed = @([Security.Principal.WindowsIdentity]::GetCurrent().User.Value, 'S-1-5-18', 'S-1-5-32-544')
foreach ($target in @($path, [IO.Path]::GetDirectoryName($path))) {
    $acl = if ([IO.Directory]::Exists($target)) {
        [IO.Directory]::GetAccessControl($target)
    } else {
        [IO.File]::GetAccessControl($target)
    }
    if ($acl.GetOwner([Security.Principal.SecurityIdentifier]).Value -notin $allowed) { exit 3 }
    $rules = $acl.GetAccessRules($true, $true, [Security.Principal.SecurityIdentifier])
    if ($rules.Count -eq 0) { exit 3 }
    foreach ($rule in $rules) {
        if ($rule.AccessControlType -eq 'Allow') {
            $sid = $rule.IdentityReference.Translate([Security.Principal.SecurityIdentifier]).Value
            # OWNER RIGHTS refers only to the already-validated owner, not another account.
            if ($sid -ne 'S-1-3-4' -and $sid -notin $allowed) { exit 3 }
        }
    }
}
exit 0
"""
        encoded = base64.b64encode(command.encode("utf-16-le")).decode("ascii")
        try:
            result = subprocess.run(
                [str(Path(os.environ.get("SystemRoot", "C:/Windows")) /
                     "System32/WindowsPowerShell/v1.0/powershell.exe"),
                 "-NoProfile", "-NonInteractive", "-EncodedCommand", encoded],
                input=json.dumps(str(path), ensure_ascii=True), text=True,
                capture_output=True, timeout=15, check=False,
            )
        except (OSError, subprocess.SubprocessError):
            raise ValueError("Unable to verify password-file protection; use stdin instead") from None
        if result.returncode:
            raise ValueError("Password file and its directory must allow access only to the owner, SYSTEM and Administrators")
    else:
        for target in (path, path.parent):
            info = target.stat()
            if info.st_uid != os.getuid() or stat.S_IMODE(info.st_mode) & 0o077:
                raise ValueError("Password file and its directory must be owner-only")
    return path


def read_passwords(stream):
    data = stream.read(65537)
    if len(data) > 65536:
        raise ValueError("Password input is too large")
    value = read_json(data)
    if not isinstance(value, dict) or set(value) != KEY_NAMES:
        raise ValueError("Password JSON must contain exactly update-ed25519 and update-rsa")
    if any(not isinstance(item, str) or not 1 <= len(item) <= 4096 for item in value.values()):
        raise ValueError("Each signing-key password must be a nonempty string")
    try:
        return {name: password.encode("utf-8") for name, password in value.items()}
    except UnicodeError:
        raise ValueError("Signing-key passwords must be valid UTF-8 strings") from None


def load_signing_keys(ed_path, rsa_path, passwords):
    keys = []
    for name, path in (("update-ed25519", ed_path), ("update-rsa", rsa_path)):
        try:
            data = path.read_bytes()
            if not data.startswith(b"-----BEGIN ENCRYPTED PRIVATE KEY-----"):
                raise ValueError("Unencrypted signing key")
            keys.append(serialization.load_pem_private_key(data, password=passwords[name]))
        except (OSError, ValueError, TypeError, KeyError):
            raise ValueError("Unable to read or decrypt an encrypted update signing key") from None
    return keys[0], keys[1]


def write_outputs(outputs, output):
    output = output.absolute()
    if output.exists() or output.is_symlink():
        raise ValueError("Output directory already exists; choose a fresh directory")
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix=".shiftvps-update-metadata-", dir=output.parent) as temporary:
        ready = Path(temporary) / "ready"
        ready.mkdir()
        for name, data in outputs.items():
            (ready / name).write_bytes(data)
        if output.exists() or output.is_symlink():
            raise ValueError("Output directory appeared during generation")
        os.replace(ready, output)


def parse_artifact_arguments(arguments):
    mapping = {}
    for argument in arguments:
        name, separator, path = argument.partition("=")
        if not separator or not path or name in mapping or name not in ARTIFACT_TYPES:
            raise ValueError("Each artifact must have a unique supported KEY=PATH mapping")
        mapping[name] = Path(path)
    return mapping


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--artifact", action="append", required=True, metavar="KEY=PATH")
    parser.add_argument("--require-all", action="store_true", help="Require all seven currently supported artifacts")
    parser.add_argument("--version-name", required=True)
    parser.add_argument("--version-code", required=True, type=int)
    parser.add_argument("--previous-version-code", required=True, type=int)
    parser.add_argument("--channel", required=True, choices=["stable"])
    parser.add_argument("--ed25519-key", required=True, type=Path)
    parser.add_argument("--rsa-key", required=True, type=Path)
    passwords = parser.add_mutually_exclusive_group(required=True)
    passwords.add_argument("--passwords-stdin", action="store_true")
    passwords.add_argument("--passwords-file", type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    try:
        mapping = parse_artifact_arguments(args.artifact)
        validate_version(args.version_name, args.version_code, args.previous_version_code, args.channel)
        # Find missing/ambiguous artifacts before requesting access to signing keys.
        inspect_artifacts(mapping, args.version_name, args.require_all)
        if args.passwords_file:
            path = require_protected_password_file(args.passwords_file)
            with path.open("rb") as stream:
                password_values = read_passwords(stream)
        else:
            password_values = read_passwords(sys.stdin.buffer)
        ed_key, rsa_key = load_signing_keys(args.ed25519_key, args.rsa_key, password_values)
        del password_values
        pinned = read_json((ROOT / "release/update-public-keys.json").read_bytes())
        outputs = build_outputs(mapping, args.version_name, args.version_code, args.previous_version_code,
                                args.channel, ed_key, rsa_key, pinned, args.require_all)
        write_outputs(outputs, args.output)
    except (ValueError, OSError) as error:
        # Never print parsed password data, key contents, command arguments or tracebacks.
        print(f"Update metadata was not published: {error.__class__.__name__}: "
              f"{error if isinstance(error, ValueError) else 'Unable to access a required local file'}", file=sys.stderr)
        return 1
    print(f"Prepared {len(outputs)} metadata files in a new local directory; nothing was uploaded.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

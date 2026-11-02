#!/usr/bin/env python3
"""Offline producer checks using only disposable synthetic artifacts and signing keys."""

import base64
import csv
from contextlib import redirect_stderr, redirect_stdout
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
import xml.etree.ElementTree as ET

from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ed25519, padding, rsa


spec = importlib.util.spec_from_file_location("build_update_feeds", Path(__file__).with_name("build_update_feeds.py"))
feeds = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = feeds
spec.loader.exec_module(feeds)


class UpdateFeedsTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.ed_key = ed25519.Ed25519PrivateKey.generate()
        cls.rsa_key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
        cls.pinned = {
            "update-ed25519": base64.b64encode(cls.ed_key.public_key().public_bytes(
                serialization.Encoding.Raw, serialization.PublicFormat.Raw,
            )).decode("ascii"),
            "update-rsa": base64.b64encode(cls.rsa_key.public_key().public_bytes(
                serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo,
            )).decode("ascii"),
        }

    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="shiftvps-feed-test-")
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.mapping = {}
        for name, extension in feeds.ARTIFACT_TYPES.items():
            path = self.root / f"ShiftVPS-0.1.1-{name}{extension}"
            path.write_bytes((f"synthetic release payload for {name}").encode("ascii"))
            self.mapping[name] = path

    def build(self, **overrides):
        arguments = dict(mapping=self.mapping, version_name="0.1.1", version_code=2,
                         previous_version_code=1, channel="stable", ed_key=self.ed_key,
                         rsa_key=self.rsa_key, pinned=self.pinned, require_all=True)
        arguments.update(overrides)
        return feeds.build_outputs(**arguments)

    def test_complete_matrix_android_rsa_roundtrip_and_inventory(self):
        outputs = self.build()
        self.assertEqual(set(outputs), {"android.json", "windows-amd64.xml", "windows-arm64.xml",
                                        "macos-amd64.xml", "macos-arm64.xml", "inventory.json"})
        envelope = json.loads(outputs["android.json"])
        self.assertEqual(set(envelope), {"payload", "signature"})
        payload = base64.b64decode(envelope["payload"], validate=True)
        signature = base64.b64decode(envelope["signature"], validate=True)
        self.rsa_key.public_key().verify(signature, payload, padding.PKCS1v15(), hashes.SHA256())
        manifest = json.loads(payload)
        self.assertEqual(set(manifest), {"schema", "channel", "version_code", "version", "artifacts"})
        self.assertEqual(manifest["schema"], 1)
        self.assertEqual(manifest["channel"], "stable")
        self.assertEqual(manifest["version_code"], 2)
        self.assertEqual(manifest["version"], "0.1.1")
        self.assertEqual(set(manifest["artifacts"]), {key for key in self.mapping if key.startswith("android-")})
        for key, entry in manifest["artifacts"].items():
            data = self.mapping[key].read_bytes()
            self.assertEqual(entry["size"], len(data))
            self.assertEqual(entry["sha256"], hashlib.sha256(data).hexdigest())
            feeds.require_artifact_url(entry["url"], "0.1.1", self.mapping[key].name)
        with self.assertRaises(InvalidSignature):
            self.rsa_key.public_key().verify(signature, payload + b" ", padding.PKCS1v15(), hashes.SHA256())
        inventory = json.loads(outputs["inventory.json"])
        self.assertEqual(len(inventory["artifacts"]), 8)
        for name, entry in inventory["metadata"].items():
            self.assertEqual(entry["size"], len(outputs[name]))
            self.assertEqual(entry["sha256"], hashlib.sha256(outputs[name]).hexdigest())

    def test_mac_footer_and_archives_use_exact_ed25519_signed_bytes(self):
        outputs = self.build()
        for architecture in ("amd64", "arm64"):
            signed = outputs[f"macos-{architecture}.xml"]
            content, separator, footer = signed.rpartition(b"<!-- sparkle-signatures:\n")
            self.assertTrue(separator)
            fields = dict(line.split(": ", 1) for line in footer.decode("ascii").splitlines() if ": " in line)
            self.assertEqual(int(fields["length"]), len(content))
            signature = base64.b64decode(fields["edSignature"], validate=True)
            self.ed_key.public_key().verify(signature, content)
            with self.assertRaises(InvalidSignature):
                self.ed_key.public_key().verify(signature, content + b" ")
            tree = ET.fromstring(signed)
            item = tree.find("./channel/item")
            self.assertEqual(item.find(f"{{{feeds.SPARKLE}}}version").text, "2")
            enclosure = item.find("enclosure")
            archive_signature = base64.b64decode(enclosure.attrib[f"{{{feeds.SPARKLE}}}edSignature"])
            data = self.mapping[f"macos-{architecture}"].read_bytes()
            self.assertEqual(int(enclosure.attrib["length"]), len(data))
            self.ed_key.public_key().verify(archive_signature, data)
            with self.assertRaises(InvalidSignature):
                self.ed_key.public_key().verify(archive_signature, data + b"!")

    def test_windows_enclosures_have_numeric_build_and_signed_exe_but_unsigned_feed(self):
        outputs = self.build()
        for architecture in ("amd64", "arm64"):
            content = outputs[f"windows-{architecture}.xml"]
            self.assertNotIn(b"sparkle-signatures:", content)
            item = ET.fromstring(content).find("./channel/item")
            enclosure = item.find("enclosure")
            self.assertEqual(enclosure.attrib[f"{{{feeds.SPARKLE}}}version"], "2")
            self.assertEqual(enclosure.attrib[f"{{{feeds.SPARKLE}}}shortVersionString"], "0.1.1")
            self.assertEqual(enclosure.attrib[f"{{{feeds.SPARKLE}}}os"], "windows")
            signature = base64.b64decode(enclosure.attrib[f"{{{feeds.SPARKLE}}}edSignature"])
            self.ed_key.public_key().verify(signature, self.mapping[f"windows-{architecture}"].read_bytes())

    def test_unexpected_keys_abort_without_writing_output(self):
        with self.assertRaisesRegex(ValueError, "pinned"):
            self.build(ed_key=ed25519.Ed25519PrivateKey.generate())
        wrong = dict(self.pinned, **{"update-rsa": self.pinned["update-ed25519"]})
        with self.assertRaisesRegex(ValueError, "pinned"):
            self.build(pinned=wrong)
        self.assertFalse((self.root / "output").exists())

    def test_missing_duplicate_unknown_and_incomplete_artifact_mappings_are_rejected(self):
        for mapping in ({}, {"unknown": self.mapping["windows-amd64"]},
                        {"windows-amd64": self.root / "missing.exe"},
                        {"windows-amd64": self.mapping["windows-amd64"]}):
            with self.subTest(keys=list(mapping)), self.assertRaises(ValueError):
                self.build(mapping=mapping)
        with self.assertRaises(ValueError):
            feeds.parse_artifact_arguments(["windows-amd64=a.exe", "windows-amd64=b.exe"])
        self.assertEqual(feeds.parse_artifact_arguments(["windows-amd64=a=b.exe"]),
                         {"windows-amd64": Path("a=b.exe")})
        duplicate = dict(self.mapping, **{"windows-arm64": self.mapping["windows-amd64"]})
        with self.assertRaisesRegex(ValueError, "ambiguous"):
            self.build(mapping=duplicate)

    def test_only_matching_canonical_github_release_urls_are_accepted(self):
        filename = self.mapping["windows-amd64"].name
        valid = f"{feeds.RELEASE_BASE}/v0.1.1/{filename}"
        feeds.require_artifact_url(valid, "0.1.1", filename)
        for url in (valid.replace("https:", "http:"), valid + "?token=x", valid + "#fragment",
                    valid.replace("github.com", "github.com.evil.example"),
                    valid.replace("github.com", "user:password@github.com"),
                    valid.replace("github.com", "github.com:443"), valid.replace("v0.1.1", "v0.1.0"),
                    valid.replace("dirtycrew1", "somebody-else"), valid.replace(filename, "../" + filename)):
            with self.subTest(url=url), self.assertRaises(ValueError):
                feeds.require_artifact_url(url, "0.1.1", filename)
        with self.assertRaises(ValueError):
            feeds.require_artifact_url("https://example.com/a?b.exe", "0.1.1", "a?b.exe")

    def test_versions_must_increase_and_match_current_client_contract(self):
        for changes in ({"version_code": 1}, {"version_code": 0}, {"version_code": True},
                        {"previous_version_code": -1}, {"previous_version_code": 2},
                        {"version_code": 2_100_000_001}, {"version_name": "v0.1.1"},
                        {"version_name": "../0.1.1"}, {"channel": "beta"}):
            with self.subTest(changes=changes), self.assertRaises(ValueError):
                self.build(**changes)
        feeds.validate_version("0.1.0", 1, 0, "stable")
        feeds.validate_version("1.0.0-rc.1", 10, 9, "stable")

    def test_encrypted_test_keys_and_password_input_never_enter_outputs(self):
        passwords = {"update-ed25519": b"synthetic-ed-password", "update-rsa": b"synthetic-rsa-password"}
        paths = []
        for name, key in (("update-ed25519", self.ed_key), ("update-rsa", self.rsa_key)):
            path = self.root / f"{name}.pem"
            path.write_bytes(key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8,
                                              serialization.BestAvailableEncryption(passwords[name])))
            paths.append(path)
        ed_key, rsa_key = feeds.load_signing_keys(*paths, passwords)
        outputs = self.build(ed_key=ed_key, rsa_key=rsa_key)
        combined = b"".join(outputs.values())
        for password in passwords.values():
            self.assertNotIn(password, combined)
        self.assertNotIn(b"PRIVATE KEY", combined)
        decoded = feeds.read_passwords(io.BytesIO(feeds.compact_json({name: item.decode() for name, item in passwords.items()})))
        self.assertEqual(decoded, passwords)
        with self.assertRaisesRegex(ValueError, "decrypt"):
            feeds.load_signing_keys(*paths, {name: b"wrong" for name in passwords})
        paths[0].write_bytes(self.ed_key.private_bytes(serialization.Encoding.PEM,
                             serialization.PrivateFormat.PKCS8, serialization.NoEncryption()))
        with self.assertRaisesRegex(ValueError, "encrypted"):
            feeds.load_signing_keys(*paths, passwords)

    def test_outputs_appear_together_and_existing_directory_is_never_replaced(self):
        outputs = self.build()
        output = self.root / "output"
        feeds.write_outputs(outputs, output)
        self.assertEqual({item.name for item in output.iterdir()}, set(outputs))
        with self.assertRaisesRegex(ValueError, "already exists"):
            feeds.write_outputs(outputs, output)
        self.assertEqual((output / "android.json").read_bytes(), outputs["android.json"])

    def test_explicit_partial_releases_emit_only_the_present_platform(self):
        outputs = self.build(mapping={"windows-amd64": self.mapping["windows-amd64"]}, require_all=False)
        self.assertEqual(set(outputs), {"windows-amd64.xml", "inventory.json"})

    def test_required_matrix_does_not_require_the_currently_unsupported_windows_arm64_target(self):
        mapping = {key: value for key, value in self.mapping.items() if key != "windows-arm64"}
        outputs = self.build(mapping=mapping)
        self.assertEqual(set(outputs), {"android.json", "windows-amd64.xml", "macos-amd64.xml",
                                       "macos-arm64.xml", "inventory.json"})

    def test_password_json_rejects_duplicates_extra_fields_and_invalid_values(self):
        for data in (b'{"update-ed25519":"a","update-ed25519":"b","update-rsa":"c"}',
                     b'{"update-ed25519":"a","update-rsa":"c","extra":"x"}',
                     b'{"update-ed25519":null,"update-rsa":"c"}', b"[]", b"not json"):
            with self.subTest(data=data), self.assertRaises(ValueError):
                feeds.read_passwords(io.BytesIO(data))
        with self.assertRaisesRegex(ValueError, "valid UTF-8"):
            feeds.read_passwords(io.BytesIO(b'{"update-ed25519":"\\ud800","update-rsa":"c"}'))

    def test_password_file_requires_owner_protection_and_rejects_shared_access(self):
        directory = self.root / "private-test-input"
        directory.mkdir(mode=0o700)
        if os.name == "nt":
            result = subprocess.run(["whoami.exe", "/user", "/fo", "csv", "/nh"],
                                    capture_output=True, text=True, check=True)
            owner = next(csv.reader([result.stdout.strip()]))[1]
            subprocess.run(["icacls.exe", str(directory), "/inheritance:r", "/grant:r", f"*{owner}:(OI)(CI)F"],
                           capture_output=True, check=True)
        password_file = directory / "synthetic-passwords.json"
        password_file.write_bytes(b"{}")
        password_file.chmod(0o600)
        self.assertEqual(feeds.require_protected_password_file(password_file), password_file.resolve())
        if os.name == "nt":
            subprocess.run(["icacls.exe", str(password_file), "/grant", "*S-1-1-0:R"],
                           capture_output=True, check=True)
        else:
            password_file.chmod(0o644)
        with self.assertRaisesRegex(ValueError, "owner|SYSTEM"):
            feeds.require_protected_password_file(password_file)

    def test_cli_uses_only_temporary_keys_and_repository_pins_and_writes_atomically(self):
        passwords = {name: "synthetic-test-password" for name in feeds.KEY_NAMES}
        (self.root / "release").mkdir()
        (self.root / "release/update-public-keys.json").write_bytes(feeds.compact_json(self.pinned))
        key_paths = []
        for name, key in (("update-ed25519", self.ed_key), ("update-rsa", self.rsa_key)):
            path = self.root / f"{name}.pem"
            path.write_bytes(key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8,
                             serialization.BestAvailableEncryption(passwords[name].encode())))
            key_paths.append(path)
        arguments = ["build_update_feeds.py", "--version-name", "0.1.1", "--version-code", "2",
                     "--previous-version-code", "1", "--channel", "stable", "--ed25519-key", str(key_paths[0]),
                     "--rsa-key", str(key_paths[1]), "--passwords-stdin", "--output", str(self.root / "output"),
                     "--require-all"]
        for name, path in self.mapping.items():
            arguments.extend(["--artifact", f"{name}={path}"])
        stdin = io.TextIOWrapper(io.BytesIO(feeds.compact_json(passwords)), encoding="utf-8")
        stdout, stderr = io.StringIO(), io.StringIO()
        with patch.object(feeds, "ROOT", self.root), patch.object(sys, "argv", arguments), \
                patch.object(sys, "stdin", stdin), redirect_stdout(stdout), redirect_stderr(stderr):
            self.assertEqual(feeds.main(), 0)
        self.assertTrue((self.root / "output/inventory.json").is_file())
        self.assertNotIn("synthetic-test-password", stdout.getvalue() + stderr.getvalue())


if __name__ == "__main__":
    unittest.main()

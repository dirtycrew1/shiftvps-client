# Publish client updates

`build_update_feeds.py` produces metadata locally. It performs no network requests,
uploads, git changes, installer execution, or VPN operations. Python 3.10+ and
`cryptography` are required. Run the offline checks with:

```powershell
python release/test_update_feeds.py
```

Those tests use temporary synthetic artifacts and temporary signing keys. They
never open the real production signing files.

## Prepare the release

1. Increase `VERSION_CODE` in `husi.properties`; use the same code in every binary.
   The producer requires an explicit `--previous-version-code` and rejects a release
   whose code does not increase. Use `0` only for the first publication.
2. Build, inspect, and test the release artifacts. Verify their embedded versions,
   package identifiers, existing APK signing certificate, CPU architectures, and
   recovery behavior. Metadata generation does not execute or inspect native
   installers and does not replace those checks.
3. Choose exact input paths. Wildcards, selecting the newest file automatically,
   duplicate map keys, reused input paths, and duplicate asset filenames are not
   accepted. Android takes `.apk`, Windows the bundled-runtime installer `.exe`,
   and macOS `.dmg`.
4. Supply encrypted PKCS#8 PEM files for the offline Ed25519 and RSA keys. The
   producer checks both public halves against `release/update-public-keys.json`
   in this repository. It accepts no public-key override. Keep private keys and
   passwords outside the checkout, CI, download service, and output directory.

Supported mapping keys:

| Platform | Keys |
| --- | --- |
| Android | `android-arm64-v8a`, `android-armeabi-v7a`, `android-x86_64`, `android-x86` |
| Windows | `windows-amd64` |
| macOS | `macos-amd64`, `macos-arm64` |

At least one mapping is required. Partial releases emit feeds only for their
explicitly mapped targets. `--require-all` requires the complete seven-artifact
matrix. A `windows-arm64` mapping is reserved for a future supported ARM release;
the current application deliberately rejects that target because the upstream
SQLite JNI library is unavailable. Do not publish an ARM Windows feed until the
native application is actually supported and tested.
All inputs must validate before any feed becomes visible in the chosen
new output directory. Existing output directories are never replaced.

## Supply passwords without exposing them

The password JSON contains exactly two fields, `update-ed25519` and `update-rsa`,
with the corresponding passwords as strings. Do not place password values in
command arguments, environment variables, logs, a terminal transcript, or this
document. Keep the JSON owner-protected alongside the offline keys.

`--passwords-file` checks the file and its immediate directory. On POSIX both must
be owned by the current user and grant no group/other access. On Windows their
ACLs must grant access and ownership only to the current user, SYSTEM, or local
Administrators. ACL inspection runs the system PowerShell with only the file path
on its input; the password contents never enter another process's arguments.
Alternatively, `--passwords-stdin` reads the same JSON from standard input. Do not
type an unmasked password into an interactive terminal as stdin.

Example using existing protected files; the placeholders below are paths, not
passwords. Adjust the version and previous code to the actual release:

```powershell
python release/build_update_feeds.py `
  --version-name 0.1.1 --version-code 2 --previous-version-code 1 --channel stable `
  --ed25519-key C:/private/offline/update-ed25519.pem `
  --rsa-key C:/private/offline/update-rsa.pem `
  --passwords-file C:/private/offline/passwords.json `
  --artifact android-arm64-v8a=C:/release/ShiftVPS-0.1.1-android-arm64-v8a.apk `
  --artifact android-armeabi-v7a=C:/release/ShiftVPS-0.1.1-android-armeabi-v7a.apk `
  --artifact android-x86_64=C:/release/ShiftVPS-0.1.1-android-x86_64.apk `
  --artifact android-x86=C:/release/ShiftVPS-0.1.1-android-x86.apk `
  --artifact windows-amd64=C:/release/ShiftVPS-0.1.1-windows-amd64-jbr.exe `
  --artifact macos-amd64=C:/release/ShiftVPS-0.1.1-macos-amd64.dmg `
  --artifact macos-arm64=C:/release/ShiftVPS-0.1.1-macos-arm64.dmg `
  --require-all --output C:/release/metadata-0.1.1
```

Use quotes around the entire `KEY=PATH` argument when its path contains spaces.
The producer never copies or writes private keys. Python retains decrypted key
objects in process memory until exit; use a trusted offline workstation.

## Inspect and publish

The producer creates:

- `android.json`: Base64 payload/signature envelope. The exact decoded JSON payload
  uses schema `1`, channel `stable`, version code/name, and Android artifact URL,
  SHA-256, and byte size. Its signature is RSA PKCS#1 v1.5 with SHA-256.
- `windows-ARCH.xml`: HTTPS RSS appcast with numeric `sparkle:version` and displayed
  `sparkle:shortVersionString`. Each EXE has an Ed25519 enclosure signature over
  its raw bytes. WinSparkle does not authenticate the appcast with a feed signature.
- `macos-ARCH.xml`: RSS appcast with the same version fields and raw-DMG Ed25519
  signatures and `sparkle:minimumSystemVersion` set to `13.0` for both Apple Silicon
  and Intel, matching the native libraries. The feed itself has Sparkle's Ed25519 footer over the exact preceding
  XML bytes. Never reformat a feed after generation. See [macOS OTA](macos/OTA.md).
- `inventory.json`: SHA-256 and byte counts for artifacts and generated metadata,
  excluding the inventory itself. It contains public release URLs and filenames,
  never local paths, private key contents, or passwords. The inventory is a review
  record; clients authenticate their own feeds/artifacts rather than this file.

All artifact URLs are fixed to:
`https://github.com/dirtycrew1/shiftvps-client/releases/download/vVERSION/FILENAME`.
The producer rejects arbitrary origins, credentials, ports, queries, fragments,
path traversal, unsafe filenames, and mismatched release versions.

Before publishing, compare the inventory against the exact built files. Upload
those unchanged assets to the matching `vVERSION` GitHub release first. Only after
all asset URLs work, publish the generated feed files together in one commit on
the repository's `updates` branch. Preserve older architecture feeds when making
an explicitly partial release; do not replace an unbuilt target with another
architecture's payload. Keep the inventory as release evidence. These are manual
publication steps; this script cannot upload or modify remote state.

After publication, independently fetch the public feeds and release assets,
recheck byte counts, digests, and signatures, then perform real update tests on
each supported operating system. A successful metadata test alone does not prove
that installation, user authorization, or macOS Gatekeeper acceptance succeeds.

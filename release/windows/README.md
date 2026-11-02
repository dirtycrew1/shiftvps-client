# Windows release signing

`husi-signing-cert.pem` is the certificate the Windows releases are signed with. It is published
here so anyone can check that a downloaded build really came from this project.

It is a **self-signed** certificate, not one issued by a commercial CA. Windows will still warn
about an unknown publisher, and that is expected — SmartScreen was never the point.

## Fingerprints

```
SHA-256  E0:10:8A:03:F1:81:C1:D7:E1:61:C6:E8:B9:94:BC:46:B3:EC:BA:44:54:D8:70:BD:A9:A7:A7:7E:B7:5F:05:E2
SHA-1    B4:1F:B3:8C:6B:08:4A:52:88:07:21:B9:CF:79:B6:94:E0:2F:17:CC
Subject  CN=husi
Valid    2026-08-14 .. 2036-08-11
```

Windows shows the SHA-1 value: it is the "Thumbprint" in the file properties Digital Signatures
tab, and what PowerShell reports.

## Checking a download

PowerShell:

```powershell
(Get-AuthenticodeSignature .\husi-core.exe).SignerCertificate.Thumbprint
```

Linux or macOS, with `osslsigncode`:

```
osslsigncode verify -CAfile release/windows/husi-signing-cert.pem husi-core.exe
```

Every signed payload in a release — the launcher, `husi-core.exe`, the `libcronet.dll` beside it and
the installer — carries this same certificate. `libcronet.dll` is the prebuilt Cronet that SagerNet's cronet-go
publishes unsigned; the signature only says this project shipped it.

## What this does and does not prove

A matching fingerprint means the file was signed with the private key behind this certificate. A
mismatch means it was not, and the build should not be trusted.

Nothing in husi checks this certificate at runtime. Comparing against the fingerprints above is
what ties a build to this project, and that comparison is yours to make.

## Signing a build

See the Windows signing entry in [AGENTS.md](../../AGENTS.md). The private key lives outside this
repository; `release/windows/codesign.sh` reads it from `WINDOWS_SIGNING_P12`.

## In-app updates

Installed ShiftVPS builds use [WinSparkle 0.9.4](https://github.com/vslavik/winsparkle/releases/tag/v0.9.4)
through the existing JNA dependency. The native updater checks once per day and presents its own
download/install dialog. Installation requires the user's approval. Connecting, connected, and
stopping VPN sessions prevent the updater from closing the application; disconnect before installing.
Portable archives and development runs do not update the registered installation.

The application requires the compiled `UPDATE_ED25519_PUBLIC_KEY` and the HTTPS
`UPDATE_WINDOWS_APPCAST_URL`. WinSparkle verifies every downloaded installer against the Ed25519 key
before the application's installer callback can run. An absent or invalid signature stops the update.
The appcast itself is HTTPS metadata; Ed25519 authenticates the installer bytes. Use `VERSION_CODE`
as `sparkle:version` and `VERSION_NAME` as `sparkle:shortVersionString`. Publish the installer for the
matching CPU architecture; the bundled-runtime installer is the normal OTA payload.

Remote installer arguments are ignored. The verified EXE receives only `/S /UPDATE` and the current
GUI's process ID. The installer waits up to 60 seconds for that process to exit, checks the registered
installation directory, rejects older versions/builds, stages the new files and makes a backup under
`.shiftvps-update-backup` in that directory. Profiles, account data and user configuration are outside
the payload replacement list. Existing shortcuts are preserved.

When a system service already exists, this explicit update mode may show a UAC prompt to replace it.
Declining the prompt, a nonzero service exit code, or a file replacement error fails the update and
attempts recovery of application files. A failed update retains its backup and shows the recovery
location. This is not a transactional rollback of the privileged service, a profile database migration,
or an interrupted installation; test those recovery cases in a disposable VM before publication.
Ordinary unattended `/S` installation retains its existing behavior and does not request elevation.

`fetch-winsparkle.py` downloads only the official release URL and checks both the GitHub release
archive digest and architecture-specific DLL digest before extracting fixed entries. Packaging puts
the original DLL beside the launcher and distributes its `COPYING`, `COPYING.expat`, and `AUTHORS`
notices under `licenses/WinSparkle`. The application checks that DLL's pinned digest again and loads
its absolute path. Do not re-sign or modify WinSparkle.dll without deliberately reviewing and updating
the pins. An unrecognized or missing DLL disables the updater.

Compiler-only release checks (never execute the generated installer on a development machine):

```powershell
python release/windows/test-updater.py --winsparkle-archive C:/cache/WinSparkle-0.9.4.zip --makensis C:/tools/nsis/makensis.exe
```

This checks archive/DLL pins, license notices, rejection of altered downloads and native compilation
of both installer variants. Application safety tests are in `DesktopWindowsUpdaterTest` and run with
`make test_gradle`. Before publishing, a disposable VM must also verify accepted/declined elevation,
VPN-active deferral, process waiting, failed replacement recovery and successful version/readback.

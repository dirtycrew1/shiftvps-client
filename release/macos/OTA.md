# macOS updates

Both Apple Silicon and Intel packages require macOS 13.0 or newer, matching the
bundled Cronet native library. The application, updater helper, Go deployment target,
and signed appcast declare that same minimum. Older deployment-target overrides
are rejected rather than producing a package that advertises unsupported systems.

The packaged application runs the official Sparkle CLI in the background after
startup and every six hours. It always supplies `--defer-install`: Sparkle downloads
and verifies the release, then waits for ShiftVPS to quit naturally. It never forces
an active VPN session to restart. A manual check may request the normal system
authorization prompt if the installation directory is not writable by the user.
The updater is disabled in development runs without the packaged helper.

`DesktopMacUpdater.initialize()`, `check()` and `close()` are the desktop entry points.
`close()` cancels future checks but deliberately does not terminate an installer that
is waiting for app exit. Closing the last window can leave a tray application running;
the user must quit the application to finish a staged update.

## Dependencies and signing

`sparkle.sh` pins Sparkle 2.10.1's official release framework by SHA-256. The five
official CLI source/header files are pinned to upstream commit
`ab7d71291238df6e0492143b120c9b696a35f603`, each with its own SHA-256. They are compiled
against that framework with Apple's clang SDK for the package's target architecture.
Only the application's `update-ed25519` public key is consumed during packaging.
The upstream MIT license is included in the helper's Resources directory.

Both the archive and appcast must have valid Ed25519 signatures. The bundle enables
`SURequireSignedFeed` and `SUVerifyUpdateBeforeExtraction`, and disables signed-feed
failure expiration with `SUSignedFeedFailureExpirationInterval=0`. Do not remove these
settings to work around publication failures. No updater private key belongs in the
repository, CI, build cache, or download server.

Architecture-specific feeds are:

- `https://raw.githubusercontent.com/dirtycrew1/shiftvps-client/updates/macos-arm64.xml`
- `https://raw.githubusercontent.com/dirtycrew1/shiftvps-client/updates/macos-amd64.xml`

`sparkle:version` must equal the integer `VERSION_CODE`/`CFBundleVersion`, and
`sparkle:shortVersionString` is the displayed `VERSION_NAME`. Increase `VERSION_CODE`
on every published release. The enclosure URL must use HTTPS, its `length` is the
exact DMG byte length, and `sparkle:edSignature` is Base64 Ed25519 over the raw DMG.

To sign the feed, first serialize its final UTF-8 XML bytes, preserving whitespace
and any final newline. Sign those bytes with Ed25519 and append exactly:

```text
<!-- sparkle-signatures:
edSignature: <Base64 signature of preceding XML bytes>
length: <decimal length of preceding XML bytes>
-->
```

The signature block ends with a newline and is not part of the signed content.
Never reformat XML after signing. This is Sparkle's format from
`common_cli/Signing.swift` and `Sparkle/SPUExtractSignedFeed.m` at the pinned commit.

The current application is ad-hoc signed. That is not a Developer ID signature or
notarization: initial installation and some updates can still be refused by macOS
policy. The updater reports failure and never disables Gatekeeper, clears quarantine,
or circumvents certificate/signature validation. Real installation and replacement
must be tested on both supported Mac architectures; compiling on Windows is not
evidence that a native macOS installation succeeded.

Official references: [Sparkle CLI](https://sparkle-project.org/documentation/sparkle-cli/),
[setup and signed feeds](https://sparkle-project.org/documentation/),
[customization](https://sparkle-project.org/documentation/customization/).

#!/usr/bin/env bash

# Sourced by package.sh. Only public verification keys belong in the build.
SPARKLE_VERSION="2.10.1"
SPARKLE_COMMIT="ab7d71291238df6e0492143b120c9b696a35f603"
SPARKLE_ARCHIVE_SHA256="1a856d2a9edb8e628de88121d7be04cf0e2167f8641650b78fca00b14244cf27"

sparkle_verify_download() {
    local path="$1" expected="$2"
    [[ -f "$path" && "$(shasum -a 256 "$path" | awk '{print $1}')" == "$expected" ]]
}

sparkle_download() {
    local url="$1" destination="$2" expected="$3"
    if sparkle_verify_download "$destination" "$expected"; then return; fi
    local temporary
    temporary="$(mktemp "${destination}.XXXXXX")"
    if ! curl --fail --silent --show-error --location --retry 3 \
        --connect-timeout 20 --max-time 180 --proto '=https' --proto-redir '=https' \
        "$url" -o "$temporary" || ! sparkle_verify_download "$temporary" "$expected"; then
        rm -f "$temporary"
        error "Sparkle dependency download failed or its SHA-256 did not match."
        return 1
    fi
    mv "$temporary" "$destination"
}

prepare_sparkle_metadata() {
    SPARKLE_PUBLIC_KEY="$(python3 - "$ROOT_DIR/release/update-public-keys.json" <<'PY'
import base64
import json
import sys

with open(sys.argv[1], encoding="utf-8") as stream:
    key = json.load(stream)["update-ed25519"]
if len(base64.b64decode(key, validate=True)) != 32:
    raise SystemExit("Invalid Ed25519 update verification key")
print(key)
PY
    )"
    case "$TARGET_ARCH" in
        arm64|amd64)
            SPARKLE_FEED_URL="https://raw.githubusercontent.com/dirtycrew1/shiftvps-client/updates/macos-${TARGET_ARCH}.xml"
            ;;
        *) error "Unsupported Sparkle target architecture: $TARGET_ARCH"; return 1 ;;
    esac
}

install_sparkle_updater() {
    local contents_dir="$1"
    if [[ "$HOST_OS" != "darwin" ]]; then
        error "macOS OTA packaging requires macOS and the Apple command-line SDK."
        return 1
    fi
    local dependency_dir="$ROOT_DIR/build/third-party/sparkle/$SPARKLE_VERSION"
    local source_dir="$dependency_dir/source"
    local archive="$dependency_dir/Sparkle-$SPARKLE_VERSION.tar.xz"
    mkdir -p "$source_dir"
    sparkle_download \
        "https://github.com/sparkle-project/Sparkle/releases/download/$SPARKLE_VERSION/Sparkle-$SPARKLE_VERSION.tar.xz" \
        "$archive" "$SPARKLE_ARCHIVE_SHA256"

    # Build the official CLI with our bundle identity, as Sparkle recommends.
    # A commit plus a digest pins every source file; tags alone are insufficient.
    local name digest
    while read -r digest name; do
        sparkle_download \
            "https://raw.githubusercontent.com/sparkle-project/Sparkle/$SPARKLE_COMMIT/sparkle-cli/$name" \
            "$source_dir/$name" "$digest"
    done <<'SOURCES'
6df90a46bc481a76261f1b5c1a3123572f316fa3dc4463ab4269e2f5ecd0051e main.m
0924b26df77f726afafed2bb149998c4c27813574c0a9e67ddf69cc0419490b1 SPUCommandLineDriver.m
3f7d8efdd5b1be0e150ecb75795e70b0cd4fadbd1b18818b2dcf7c26f0dd6171 SPUCommandLineDriver.h
7e23c34763c74afbdbb7095cdd02d23343e9d0836e358e9da1099ff198e51600 SPUCommandLineUserDriver.m
525cc91b76b7c88d3f0bc476cfdf0df72c50739c31d3ff71e9da9a6bb4773c42 SPUCommandLineUserDriver.h
SOURCES

    local helper_contents="$contents_dir/Helpers/ShiftVPSUpdater.app/Contents"
    local frameworks_dir="$helper_contents/Frameworks"
    local native_arch="arm64"
    if [[ "$TARGET_ARCH" == "amd64" ]]; then native_arch="x86_64"; fi
    mkdir -p "$helper_contents/MacOS" "$helper_contents/Resources" "$frameworks_dir"
    # Extract anew from the verified archive, preserving framework symlinks and modes.
    tar -xJf "$archive" -C "$frameworks_dir" ./Sparkle.framework ./LICENSE
    mv "$frameworks_dir/LICENSE" "$helper_contents/Resources/Sparkle-LICENSE.txt"
    lipo -verify_arch "$native_arch" "$frameworks_dir/Sparkle.framework/Sparkle"

    xcrun --sdk macosx clang -arch "$native_arch" -mmacosx-version-min=11.0 \
        -fobjc-arc -O2 -F "$frameworks_dir" -framework Cocoa -framework Sparkle \
        -Wl,-rpath,@executable_path/../Frameworks \
        "$source_dir/main.m" "$source_dir/SPUCommandLineDriver.m" \
        "$source_dir/SPUCommandLineUserDriver.m" \
        -o "$helper_contents/MacOS/ShiftVPSUpdater"
    lipo -verify_arch "$native_arch" "$helper_contents/MacOS/ShiftVPSUpdater"
    /usr/libexec/PlistBuddy -c 'Print :CFBundleIdentifier' \
        "$frameworks_dir/Sparkle.framework/Resources/Info.plist" >/dev/null

    cat >"$helper_contents/Info.plist" <<'PLIST'
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "https://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
    <key>CFBundleIdentifier</key><string>ru.shiftvps.client.updater</string>
    <key>CFBundleExecutable</key><string>ShiftVPSUpdater</string>
    <key>CFBundleName</key><string>ShiftVPS Updater</string>
    <key>CFBundlePackageType</key><string>APPL</string>
    <key>CFBundleShortVersionString</key><string>2.10.1</string>
    <key>CFBundleVersion</key><string>2.10.1</string>
    <key>LSUIElement</key><true/>
    <key>LSMinimumSystemVersion</key><string>11.0</string>
</dict></plist>
PLIST
    plutil -lint "$helper_contents/Info.plist"
    log "Bundled official Sparkle $SPARKLE_VERSION updater for $TARGET_ARCH."
}

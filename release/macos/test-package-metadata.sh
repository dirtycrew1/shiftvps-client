#!/usr/bin/env bash

# Run with /bin/bash on macOS so this also covers Apple's Bash 3.2.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/../desktop/package-metadata.sh"

[[ -z "$(desktop_url_scheme_entries_plist)" ]]
[[ -z "$(desktop_url_scheme_mime_types)" ]]

DESKTOP_URL_SCHEMES=(example-one example-two)
[[ "$(desktop_url_scheme_entries_plist)" == '<string>example-one</string><string>example-two</string>' ]]
[[ "$(desktop_url_scheme_mime_types)" == 'x-scheme-handler/example-one;x-scheme-handler/example-two;' ]]

DESKTOP_URL_SCHEMES=("" example-one "")
[[ "$(desktop_url_scheme_entries_plist)" == '<string>example-one</string>' ]]
[[ "$(desktop_url_scheme_mime_types)" == 'x-scheme-handler/example-one;' ]]

unset DESKTOP_URL_SCHEMES
[[ -z "$(desktop_url_scheme_entries_plist)" ]]
[[ -z "$(desktop_url_scheme_mime_types)" ]]

# Exercise the real command lines against lipo's input-before-option contract.
(
    frameworks_dir='/fixture path/Frameworks'
    helper_contents='/fixture path/Contents'
    lipo() {
        if [[ "$#" -ne 3 || "$2" != '-verify_arch' || "$3" != "$native_arch" ]]; then
            printf 'lipo requires the input file before -verify_arch.\n' >&2
            return 1
        fi
        [[ "$1" == "$frameworks_dir/Sparkle.framework/Sparkle" ||
           "$1" == "$helper_contents/MacOS/ShiftVPSUpdater" ]]
        lipo_calls=$((lipo_calls + 1))
    }
    for native_arch in arm64 x86_64; do
        lipo_calls=0
        while IFS= read -r invocation; do
            [[ "$invocation" == '    lipo '* ]] || continue
            eval "$invocation"
        done <"$SCRIPT_DIR/sparkle.sh"
        [[ "$lipo_calls" -eq 2 ]]
    done
)

# Exercise the real compiler argument vector without compiling or running a helper.
(
    frameworks_dir='/fixture path/Frameworks'
    helper_contents='/fixture path/Contents'
    source_dir='/fixture path/source'
    xcrun() {
        [[ "$#" -ge 6 && "$1" == '--sdk' && "$2" == macosx && "$3" == clang ]]
        [[ "$4" == '-arch' && "$5" == "$native_arch" && "$6" == '-mmacosx-version-min=13.0' ]]
        local argument direct_count=0 members_count=0
        for argument in "$@"; do
            case "$argument" in
                -DSPU_OBJC_DIRECT=*)
                    [[ "$argument" == '-DSPU_OBJC_DIRECT=__attribute__((objc_direct))' ]]
                    direct_count=$((direct_count + 1))
                    ;;
                -DSPU_OBJC_DIRECT_MEMBERS=*)
                    [[ "$argument" == '-DSPU_OBJC_DIRECT_MEMBERS=__attribute__((objc_direct_members))' ]]
                    members_count=$((members_count + 1))
                    ;;
            esac
        done
        if [[ "$direct_count" -ne 1 || "$members_count" -ne 1 ]]; then
            printf 'Sparkle CLI needs both upstream Objective-C direct attributes.\n' >&2
            return 1
        fi
        compiler_calls=$((compiler_calls + 1))
    }
    compiler_invocation="$(awk '
        /^    xcrun / { capture = 1 }
        capture { print; if ($0 !~ /\\$/) exit }
    ' "$SCRIPT_DIR/sparkle.sh")"
    [[ -n "$compiler_invocation" ]]
    for native_arch in arm64 x86_64; do
        compiler_calls=0
        eval "$compiler_invocation"
        [[ "$compiler_calls" -eq 1 ]]
    done
)

# Check the compiler target emitted by the same Make target used in CI.
(
    root_dir="$(cd "$SCRIPT_DIR/../.." && pwd)"
    for target in darwin/arm64 darwin/amd64; do
        expected_target='aarch64-macos.13.0'
        if [[ "$target" == darwin/amd64 ]]; then expected_target='x86_64-macos.13.0'; fi
        invocation="$(make --no-print-directory -n -C "$root_dir" launcher DESKTOP_TARGET="$target")"
        if [[ "$invocation" != *" -Dtarget=$expected_target" ]]; then
            printf 'Launcher compiler target must pin macOS 13.0: %s\n' "$target" >&2
            exit 1
        fi
    done
)

# Exercise the actual packaging gate against otool fixtures; never run a binary.
(
    HOST_OS=darwin
    binary_path='/fixture path/launcher'
    file() {
        # Framework entry points can be links; inspect their actual Mach-O target.
        [[ "$#" -eq 2 && "$1" == '-bL' && "$2" == "$binary_path" ]] || return 1
        printf 'Mach-O 64-bit executable %s\n' "$expected_arch"
    }
    error() { printf '%s\n' "$*" >&2; }
    log() { :; }
    otool() {
        [[ "$#" -eq 4 && "$1" == '-arch' && "$2" == "$expected_arch" && "$3" == '-l' && "$4" == "$binary_path" ]] || return 1
        printf '%s\n' "$otool_fixture"
    }
    verifier="$(awk '
        /^verify_native_binary\(\) \{/ { capture = 1 }
        capture { print; if ($0 == "}") exit }
    ' "$SCRIPT_DIR/package.sh")"
    [[ -n "$verifier" ]]
    eval "$verifier"
    for TARGET_ARCH in arm64 amd64; do
        expected_arch=arm64
        if [[ "$TARGET_ARCH" == amd64 ]]; then expected_arch=x86_64; fi
        otool_fixture=$'cmd LC_BUILD_VERSION\nplatform 1\nminos 13.0\nsdk 27.0\nntools 1\ntool LD\nversion 1234.0'
        (verify_native_binary "$binary_path") || exit 1
        otool_fixture=$'cmd LC_VERSION_MIN_MACOSX\nversion 11.0\nsdk 27.0'
        (verify_native_binary "$binary_path") || exit 1
        for otool_fixture in \
            $'cmd LC_BUILD_VERSION\nplatform 1\nminos 15.0\nsdk 27.0' \
            $'cmd LC_VERSION_MIN_MACOSX\nversion 13.0.1\nsdk 27.0' \
            $'cmd LC_BUILD_VERSION\nplatform 2\nminos 13.0\nsdk 27.0' \
            $'cmd LC_BUILD_VERSION\nplatform 1\nsdk 27.0'; do
            if (verify_native_binary "$binary_path") >/dev/null 2>&1; then
                printf 'Packaging accepted a newer, foreign, or missing macOS minimum.\n' >&2
                exit 1
            fi
        done
    done
)

printf 'Package metadata checks passed with Bash %s.\n' "$BASH_VERSION"

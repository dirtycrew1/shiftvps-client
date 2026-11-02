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

printf 'Package metadata checks passed with Bash %s.\n' "$BASH_VERSION"

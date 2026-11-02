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

printf 'Package metadata checks passed with Bash %s.\n' "$BASH_VERSION"

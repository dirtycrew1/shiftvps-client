# Sourced by the packaging scripts, never executed, so it carries a shell
# directive instead of a shebang.
# shellcheck shell=bash

APP_NAME="ShiftVPS"
APP_NAME_ZH_CN="ShiftVPS"
APP_NAME_ZH_TW="ShiftVPS"
APP_DESCRIPTION="ShiftVPS managed VPN client."
APP_DESCRIPTION_ZH_CN="$APP_DESCRIPTION"
APP_DESCRIPTION_ZH_TW="$APP_DESCRIPTION"
APP_FULL_DESCRIPTION="Sign in to ShiftVPS, choose a server, and connect. Server profiles and network policy are managed by ShiftVPS."
APP_FULL_DESCRIPTION_ZH_CN="$APP_FULL_DESCRIPTION"
APP_FULL_DESCRIPTION_ZH_TW="$APP_FULL_DESCRIPTION"
APP_URL="https://shiftvps.ru"
MAINTAINER="ShiftVPS"
DESKTOP_URL_TYPE_NAME="Import URL"
DESKTOP_URL_SCHEMES=()

# "owner/repo" of the GitHub project APP_URL points at, empty for a fork that
# publishes somewhere else. The single source of truth for the repository slug:
# whatever rename.sh writes into APP_URL is what the packaging follows.
github_repository_slug() {
    local github_prefix="https://github.com/"
    local path="${APP_URL#"$github_prefix"}"

    if [[ "$path" == "$APP_URL" ]]; then
        return
    fi

    # Nothing but "<owner>/<repo>" qualifies: a deeper path is not a project page.
    if [[ "$path" =~ ^[^/]+/[^/]+$ ]]; then
        printf '%s' "${path%.git}"
    fi
}

desktop_url_scheme_mime_types() {
    local scheme
    for scheme in "${DESKTOP_URL_SCHEMES[@]}"; do
        printf 'x-scheme-handler/%s;' "$scheme"
    done
}

desktop_url_scheme_entries_plist() {
    local scheme
    for scheme in "${DESKTOP_URL_SCHEMES[@]}"; do
        printf '<string>%s</string>' "$scheme"
    done
}

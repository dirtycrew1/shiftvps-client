#!/usr/bin/env bash
#
# Download the JetBrains Runtime SDK pinned by JBR_RELEASE for one target.
#
# JBR is the only JDK this project uses: it builds everything, and its modules
# are what jlink links into the Linux, Windows and macOS bundled runtimes.
# Keeping one vendor means the runtime users get is the one CI built
# and tested with.
#
# By default only the jmods directory is unpacked: it is all jlink reads, and
# the rest of a foreign-platform SDK is of no use on the build host. --sdk
# unpacks the whole SDK instead, for use as the build JDK, and prints its
# JAVA_HOME. The resolved path is printed to stdout so callers can capture it;
# everything else goes to stderr.
#
# Usage: ./run lib jbr <platform/arch> [--sdk]

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd -P)"

# shellcheck source=../init/version.sh
source "$ROOT_DIR/buildScript/init/version.sh"

readonly JBR_BASE_URL="https://cache-redirector.jetbrains.com/intellij-jbr"
# Versioned so that bumping JBR_RELEASE never reuses an older unpacked copy.
readonly INSTALL_ROOT="$ROOT_DIR/build/jbr/$JBR_RELEASE"
readonly DOWNLOAD_ATTEMPTS=3

log() {
    echo "[jbr] $*" >&2
}

error() {
    echo "[jbr] $*" >&2
}

usage() {
    cat >&2 <<USAGE
Usage:
  $(basename "$0") <platform/arch> [--sdk]

Targets: linux/amd64, linux/arm64, windows/amd64, windows/arm64,
         darwin/amd64 and darwin/arm64

Without --sdk, prints the jmods directory; with --sdk, prints JAVA_HOME.
USAGE
}

want_sdk=false
target=""
for argument in "$@"; do
    case "$argument" in
        --sdk)
            want_sdk=true
            ;;
        -h | --help)
            usage
            exit 0
            ;;
        *)
            if [[ -n "$target" ]]; then
                usage
                exit 1
            fi
            target="$argument"
            ;;
    esac
done

if [[ -z "$target" ]]; then
    usage
    exit 1
fi

platform="${target%%/*}"
arch="${target#*/}"
jmods_strip_components=1

# java_home_subdir: where JAVA_HOME sits inside the unpacked archive.
case "$platform" in
    linux | windows)
        jbr_platform="$platform"
        java_home_subdir=""
        ;;
    darwin | macos | osx)
        platform="darwin"
        jbr_platform="osx"
        java_home_subdir="Contents/Home"
        jmods_strip_components=3
        ;;
    *)
        error "Unsupported platform '$platform'. Use linux, windows or darwin."
        exit 1
        ;;
esac

case "$arch" in
    amd64 | x86_64 | x64)
        arch="amd64"
        jbr_arch="x64"
        ;;
    arm64 | aarch64)
        arch="arm64"
        jbr_arch="aarch64"
        ;;
    *)
        error "Unsupported arch '$arch'. Use amd64 or arm64."
        exit 1
        ;;
esac

archive_name="jbrsdk-${JBR_VERSION}-${jbr_platform}-${jbr_arch}-b${JBR_BUILD}.tar.gz"
sdk_install_dir="$INSTALL_ROOT/sdk/${platform}_${arch}"
sdk_jmods_dir="$sdk_install_dir${java_home_subdir:+/$java_home_subdir}/jmods"

check_cache_path() {
    local directory="$1" parent
    for parent in "$ROOT_DIR/build" "$ROOT_DIR/build/jbr" "$INSTALL_ROOT" "${directory%/*}" "$directory"; do
        if [[ -L "$parent" ]]; then
            error "Refusing to use a cache symlink: $parent"
            exit 1
        fi
    done
}

# Check the cache boundary before following any files inside a warm SDK.
check_cache_path "$sdk_install_dir"
if [[ "$want_sdk" != true && -f "$sdk_jmods_dir/java.base.jmod" ]]; then
    log "Reusing pinned SDK modules: $sdk_jmods_dir"
    echo "$sdk_jmods_dir"
    exit 0
fi
if [[ "$want_sdk" == true ]]; then
    install_dir="$sdk_install_dir"
    result_dir="$install_dir${java_home_subdir:+/$java_home_subdir}"
    home_prefix="${java_home_subdir:+$java_home_subdir/}"
    executable=java
    if [[ "$platform" == windows ]]; then executable=java.exe; fi
    required_files=("${home_prefix}release" "${home_prefix}jmods/java.base.jmod" "${home_prefix}bin/$executable")
else
    install_dir="$INSTALL_ROOT/jmods/${platform}_${arch}"
    result_dir="$install_dir/jmods"
    required_files=("jmods/java.base.jmod")
fi

cache_ready() {
    local directory="$1" file
    for file in "${required_files[@]}"; do
        [[ -f "$directory/$file" ]] || return 1
    done
}

check_cache_path "$install_dir"
if cache_ready "$install_dir"; then
    log "Already present: $result_dir"
    echo "$result_dir"
    exit 0
fi

if [[ -e "$install_dir" || -L "$install_dir" ]]; then
    error "Existing cache is incomplete; refusing to overwrite it: $install_dir"
    exit 1
fi

parent_dir="${install_dir%/*}"
mkdir -p "$parent_dir"
stage_parent="$(cd "$parent_dir" && pwd -P)"
if [[ "$stage_parent" != "$parent_dir" ]]; then
    error "Cache staging parent resolved outside the pinned directory."
    exit 1
fi

stage_dir=''
cleanup_stage() {
    [[ -n "$stage_dir" ]] || return 0
    local suffix="${stage_dir#"$stage_parent"/.jbr-stage.}"
    if [[ -z "$suffix" || "$suffix" == "$stage_dir" || "$suffix" == */* || -L "$stage_dir" ||
          "$(cd "$stage_parent" && pwd -P)" != "$stage_parent" ]]; then
        error "Refusing to clean an unexpected staging path."
        return 1
    fi
    rm -rf -- "$stage_dir"
    stage_dir=''
}
trap cleanup_stage EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

for ((attempt = 1; attempt <= DOWNLOAD_ATTEMPTS; attempt++)); do
    stage_dir="$(mktemp -d "$stage_parent/.jbr-stage.XXXXXX")"
    log "Fetching $archive_name (attempt $attempt/$DOWNLOAD_ATTEMPTS)"
    if [[ "$want_sdk" == true ]]; then
        extract=(tar -C "$stage_dir" --strip-components=1 -xzf -)
    else
        extract=(tar -C "$stage_dir" --strip-components="$jmods_strip_components" -xzf -)
        # GNU tar needs the flag; macOS bsdtar matches these patterns by default.
        if tar --version 2>/dev/null | grep -q "GNU tar"; then extract+=(--wildcards); fi
        extract+=('*/jmods/*')
    fi
    # Retry the whole stream into fresh staging; curl-level retries would append
    # a second archive after partial bytes already consumed by tar.
    if curl -sSfL --connect-timeout 20 --max-time 300 "$JBR_BASE_URL/$archive_name" | "${extract[@]}" && cache_ready "$stage_dir"; then
        check_cache_path "$install_dir"
        if cache_ready "$install_dir"; then
            cleanup_stage
        elif [[ -e "$install_dir" || -L "$install_dir" ]]; then
            error "Cache appeared while downloading; refusing to overwrite it: $install_dir"
            exit 1
        else
            mv "$stage_dir" "$install_dir"
            stage_dir=''
        fi
        log "Unpacked: $result_dir"
        echo "$result_dir"
        exit 0
    fi
    cleanup_stage
    log "Download or extraction attempt $attempt/$DOWNLOAD_ATTEMPTS failed."
    if [[ "$attempt" -lt "$DOWNLOAD_ATTEMPTS" ]]; then sleep 2; fi
done

error "Failed to fetch a complete $archive_name after $DOWNLOAD_ATTEMPTS attempts."
exit 1

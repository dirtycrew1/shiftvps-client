#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
METADATA_FILE="$ROOT_DIR/husi.properties"
DESKTOP_METADATA_FILE="$ROOT_DIR/release/desktop/package-metadata.sh"
DESKTOP_JRE_MODULES_FILE="$ROOT_DIR/release/desktop/jre-modules.sh"
JAR_DIR_DEFAULT="$ROOT_DIR/composeApp/build/compose/jars"
OUTPUT_DIR_DEFAULT="$ROOT_DIR/composeApp/build/compose/packages/macos"
PREBUILT_ICON_DEFAULT="$ROOT_DIR/release/macos/desktop/icon.icns"
JBR_JMODS_DIR=""
PACKAGE_NAME_PLACEHOLDER="__HUSI_PACKAGE_NAME__"
APP_NAME_PLACEHOLDER="__HUSI_APP_NAME__"
APP_DESCRIPTION_PLACEHOLDER="__HUSI_APP_DESCRIPTION__"
EXECUTABLE_NAME_PLACEHOLDER="__HUSI_EXECUTABLE_NAME__"
ICON_FILE_PLACEHOLDER="__HUSI_ICON_FILE__"
MACOS_BUNDLE_VERSION_PLACEHOLDER="__HUSI_MACOS_BUNDLE_VERSION__"
URL_TYPE_NAME_PLACEHOLDER="__HUSI_URL_TYPE_NAME__"
URL_SCHEME_ENTRIES_PLACEHOLDER="__HUSI_URL_SCHEME_ENTRIES__"
TAG_NAME=""
TAG_EPOCH=""
TOUCH_TIMESTAMP=""
IMAGE_MODIFICATION_DATE=""

source "$SCRIPT_DIR/sparkle.sh"

log() {
    echo "[package] $*"
}

error() {
    echo "[package] $*" >&2
}

usage() {
    cat <<EOF
Usage:
  $(basename "$0") [--target <platform/arch>] [--input-jar <file>] [--launcher-bin <file>] [--core-bin <file>] [--jbr-jmods <dir>] [--output-dir <dir>]
  $(basename "$0") --check-tools [--target <platform/arch>]

Description:
  Build a macOS dmg with a pinned bundled JetBrains Runtime; no system Java is needed.

Defaults:
  --target       host darwin/<arch>
  --input-jar    newest matching jar under $JAR_DIR_DEFAULT
  --launcher-bin $ROOT_DIR/launcher/zig-out/bin/launcher-macos-aarch64
  --core-bin     $ROOT_DIR/libcore/build/darwin_arm64/husi-core
  --output-dir   $OUTPUT_DIR_DEFAULT
  --jbr-jmods    auto-fetch pinned target modules (env: JBR_JMODS)
  icon asset     $PREBUILT_ICON_DEFAULT

Host backends:
  macOS host     hdiutil
  Linux host     xorrisofs (ISO 9660 fallback)
EOF
}

require_arg() {
    local option="$1"
    local value="${2:-}"
    if [[ -z "$value" ]]; then
        error "Missing value for $option."
        usage
        exit 1
    fi
}

escape_for_sed() {
    printf '%s' "$1" | sed -e 's/[\\&#]/\\&/g'
}

render_template() {
    local template_file="$1"
    local output_file="$2"
    shift 2

    if [[ ! -f "$template_file" ]]; then
        error "Template file not found: $template_file"
        exit 1
    fi

    if [[ $(( $# % 2 )) -ne 0 ]]; then
        error "render_template requires placeholder/value pairs."
        exit 1
    fi

    local -a sed_args=()
    local placeholder
    local value
    local escaped_value
    while [[ $# -gt 0 ]]; do
        placeholder="$1"
        value="$2"
        escaped_value="$(escape_for_sed "$value")"
        sed_args+=(-e "s#${placeholder}#${escaped_value}#g")
        shift 2
    done

    sed "${sed_args[@]}" "$template_file" >"$output_file"
}

source_desktop_metadata() {
    if [[ ! -f "$DESKTOP_METADATA_FILE" ]]; then
        error "Desktop metadata file not found: $DESKTOP_METADATA_FILE"
        exit 1
    fi

    # Named so that shellcheck can follow it; the path is only dynamic because
    # it is anchored at the repository root.
    # shellcheck source=../desktop/package-metadata.sh
    source "$DESKTOP_METADATA_FILE"
    if [[ ! -f "$DESKTOP_JRE_MODULES_FILE" ]]; then
        error "Desktop JRE module list not found: $DESKTOP_JRE_MODULES_FILE"
        exit 1
    fi
    # shellcheck source=../desktop/jre-modules.sh
    source "$DESKTOP_JRE_MODULES_FILE"
}

load_metadata() {
    if [[ ! -f "$METADATA_FILE" ]]; then
        error "Metadata file not found: $METADATA_FILE"
        exit 1
    fi

    PACKAGE_NAME="$(awk -F= '$1=="PACKAGE_NAME"{print $2; exit}' "$METADATA_FILE")"
    VERSION_NAME="$(awk -F= '$1=="VERSION_NAME"{print $2; exit}' "$METADATA_FILE")"
    VERSION_CODE="$(awk -F= '$1=="VERSION_CODE"{print $2; exit}' "$METADATA_FILE")"

    if [[ -z "$PACKAGE_NAME" || -z "$VERSION_NAME" ]]; then
        error "Failed to parse PACKAGE_NAME or VERSION_NAME from $METADATA_FILE"
        exit 1
    fi
    if [[ ! "$VERSION_CODE" =~ ^[1-9][0-9]*$ ]]; then
        error "VERSION_CODE must be a positive integer for signed appcast version ordering."
        exit 1
    fi

    source_desktop_metadata
    URL_SCHEME_ENTRIES="$(desktop_url_scheme_entries_plist)"
}

resolve_host_os() {
    case "$(uname -s)" in
        Darwin)
            HOST_OS="darwin"
            ;;
        Linux)
            HOST_OS="linux"
            ;;
        *)
            error "Unsupported host OS '$(uname -s)'. Use Linux or macOS."
            exit 1
            ;;
    esac
}

format_timestamp_from_epoch() {
    local epoch="$1"
    local format="$2"

    case "$HOST_OS" in
        darwin)
            LC_ALL=C date -u -r "$epoch" "$format"
            ;;
        linux)
            LC_ALL=C date -u -d "@$epoch" "$format"
            ;;
        *)
            error "Unsupported host OS '$HOST_OS'."
            exit 1
            ;;
    esac
}

resolve_tag_epoch() {
    local candidate="v$VERSION_NAME"

    if git rev-parse -q --verify "refs/tags/$candidate" >/dev/null 2>&1; then
        TAG_NAME="$candidate"
        TAG_EPOCH="$(git log -1 --format=%ct "refs/tags/$candidate" | tr -d '\r\n')"
    fi

    if [[ -z "$TAG_NAME" || -z "$TAG_EPOCH" ]]; then
        error "No matching tag found for VERSION_NAME=$VERSION_NAME (required: v$VERSION_NAME)."
        exit 1
    fi

    if [[ ! "$TAG_EPOCH" =~ ^[0-9]+$ ]]; then
        error "Invalid tag epoch '$TAG_EPOCH' from tag '$TAG_NAME'."
        exit 1
    fi

    TOUCH_TIMESTAMP="$(format_timestamp_from_epoch "$TAG_EPOCH" "+%Y%m%d%H%M.%S")"
    IMAGE_MODIFICATION_DATE="$(format_timestamp_from_epoch "$TAG_EPOCH" "+%Y%m%d%H%M%S00")"
}

normalize_platform() {
    local value
    value="$(echo "$1" | tr '[:upper:]' '[:lower:]')"
    case "$value" in
        darwin|macos|mac|osx)
            echo "darwin"
            ;;
        *)
            error "Unsupported platform '$1'. Use darwin."
            exit 1
            ;;
    esac
}

normalize_arch() {
    local value
    value="$(echo "$1" | tr '[:upper:]' '[:lower:]')"
    case "$value" in
        amd64|x86_64)
            echo "amd64"
            ;;
        arm64|aarch64)
            echo "arm64"
            ;;
        *)
            error "Unsupported arch '$1'. Use amd64 or arm64."
            exit 1
            ;;
    esac
}

resolve_target() {
    if [[ -n "$TARGET" ]]; then
        local raw_platform="${TARGET%%/*}"
        local raw_arch="${TARGET#*/}"
        if [[ "$raw_platform" == "$raw_arch" ]]; then
            error "Invalid --target '$TARGET'. Use <platform>/<arch>, e.g. darwin/arm64."
            exit 1
        fi
        TARGET_PLATFORM="$(normalize_platform "$raw_platform")"
        TARGET_ARCH="$(normalize_arch "$raw_arch")"
    else
        TARGET_PLATFORM="darwin"
        TARGET_ARCH="$(normalize_arch "$(uname -m)")"
    fi
}

resolve_arch() {
    case "$TARGET_ARCH" in
        amd64)
            JAR_ARCH="x64"
            LAUNCHER_MACHINE="x86_64"
            ;;
        arm64)
            JAR_ARCH="arm64"
            LAUNCHER_MACHINE="aarch64"
            ;;
        *)
            error "Unsupported architecture '$TARGET_ARCH'."
            exit 1
            ;;
    esac
}

resolve_dmg_backend() {
    case "$HOST_OS" in
        darwin)
            DMG_BACKEND="hdiutil"
            DMG_COMMAND="hdiutil"
            ;;
        linux)
            if command -v xorrisofs >/dev/null 2>&1; then
                DMG_BACKEND="xorrisofs"
                DMG_COMMAND="xorrisofs"
            else
                error "Linux fallback requires xorrisofs."
                exit 2
            fi
            ;;
        *)
            error "Unsupported host OS '$HOST_OS'."
            exit 1
            ;;
    esac
}

require_tools() {
    local -a tools=(awk sed grep find cp chmod ln ls mkdir mktemp date git sort head rm touch bash curl tar jar jlink file)
    local -a missing=()
    local tool

    case "$DMG_BACKEND" in
        hdiutil)
            tools+=(hdiutil codesign xcrun lipo otool shasum python3 plutil)
            ;;
        xorrisofs)
            tools+=("$DMG_COMMAND")
            ;;
    esac

    for tool in "${tools[@]}"; do
        if ! command -v "$tool" >/dev/null 2>&1; then
            missing+=("$tool")
        fi
    done

    if [[ -n "${missing[*]-}" ]]; then
        error "Missing required tools: ${missing[*]}"
        exit 2
    fi
}

resolve_icon_asset() {
    ICON_ICNS="$PREBUILT_ICON_DEFAULT"

    if [[ ! -f "$ICON_ICNS" ]]; then
        error "Missing bundled icon asset: $ICON_ICNS"
        exit 1
    fi
}

touch_path_tree() {
    local root="$1"
    while IFS= read -r -d '' path; do
        if [[ -L "$path" ]]; then
            touch -h -t "$TOUCH_TIMESTAMP" "$path"
        else
            touch -t "$TOUCH_TIMESTAMP" "$path"
        fi
    done < <(find "$root" -print0)
}

resolve_input_jar() {
    local requested="$1"
    if [[ -n "$requested" ]]; then
        if [[ ! -f "$requested" ]]; then
            error "Input jar not found: $requested"
            exit 1
        fi
        INPUT_JAR="$requested"
        return
    fi

    local exact="$JAR_DIR_DEFAULT/${PACKAGE_NAME}-darwin-${JAR_ARCH}-${VERSION_NAME}.jar"
    if [[ -f "$exact" ]]; then
        INPUT_JAR="$exact"
        return
    fi

    local latest=""
    local candidate=""
    local -a matches=()
    shopt -s nullglob
    # Everything but the wildcard stays quoted, so only the glob expands.
    matches=("$JAR_DIR_DEFAULT/${PACKAGE_NAME}-darwin-${JAR_ARCH}-"*.jar)
    shopt -u nullglob
    for candidate in "${matches[@]-}"; do
        [[ -n "$candidate" ]] || continue
        if [[ -z "$latest" || "$candidate" -nt "$latest" ]]; then
            latest="$candidate"
        fi
    done
    if [[ -n "$latest" ]]; then
        INPUT_JAR="$latest"
        return
    fi

    error "No matching desktop jar found in $JAR_DIR_DEFAULT"
    error "Build one first: ./gradlew -p composeApp packageReleaseUberJarForCurrentOS -PdesktopTarget=darwin/$TARGET_ARCH"
    exit 1
}

resolve_launcher_bin() {
    local requested="$1"
    local default_path="$ROOT_DIR/launcher/zig-out/bin/launcher-macos-${LAUNCHER_MACHINE}"

    if [[ -n "$requested" ]]; then
        if [[ ! -f "$requested" ]]; then
            error "Launcher binary not found: $requested"
            exit 1
        fi
        INPUT_LAUNCHER_BIN="$requested"
        return
    fi

    if [[ -f "$default_path" ]]; then
        INPUT_LAUNCHER_BIN="$default_path"
        return
    fi

    error "Launcher binary not found: $default_path"
    error "Build one first: cd launcher && zig build -Doptimize=ReleaseSmall -Dtarget=${LAUNCHER_MACHINE}-macos"
    exit 1
}

resolve_core_bin() {
    local requested="$1"
    local default_path="$ROOT_DIR/libcore/build/${TARGET_PLATFORM}_${TARGET_ARCH}/husi-core"

    if [[ -n "$requested" ]]; then
        if [[ ! -f "$requested" ]]; then
            error "Core host binary not found: $requested"
            exit 1
        fi
        INPUT_CORE_BIN="$requested"
        return
    fi

    if [[ -f "$default_path" ]]; then
        INPUT_CORE_BIN="$default_path"
        return
    fi

    error "Core host binary not found: $default_path"
    error "Build one first: make core_desktop DESKTOP_TARGETS=${TARGET_PLATFORM}/${TARGET_ARCH}"
    exit 1
}

resolve_jbr_jmods() {
    local requested="$1"
    if [[ -n "$requested" ]]; then
        JBR_JMODS_DIR="$requested"
    elif [[ -n "${JBR_JMODS:-}" ]]; then
        JBR_JMODS_DIR="$JBR_JMODS"
    else
        JBR_JMODS_DIR="$(bash "$ROOT_DIR/buildScript/lib/jbr.sh" "darwin/$TARGET_ARCH")"
    fi
    if [[ ! -f "$JBR_JMODS_DIR/java.base.jmod" ]]; then
        error "Missing target JetBrains Runtime modules: $JBR_JMODS_DIR"
        exit 1
    fi
}

verify_sqlite_native() {
    local native_arch="x64"
    if [[ "$TARGET_ARCH" == "arm64" ]]; then native_arch="arm64"; fi
    local resource="natives/osx_${native_arch}/libsqliteJni.dylib"
    if ! jar tf "$INPUT_JAR" | grep -Fx "$resource" >/dev/null; then
        error "Desktop jar is missing the exact target SQLite native library: $resource"
        exit 1
    fi
}

verify_native_binary() {
    local binary="$1"
    local expected_machine="arm64"
    if [[ "$TARGET_ARCH" == "amd64" ]]; then expected_machine="x86_64"; fi
    if ! file -bL "$binary" | grep "Mach-O.*$expected_machine" >/dev/null; then
        error "Binary does not match darwin/$TARGET_ARCH: $binary"
        exit 1
    fi
    if [[ "$HOST_OS" == darwin ]]; then
        local minimum_os
        if ! minimum_os="$(otool -arch "$expected_machine" -l "$binary" | awk '
            function check(version, parts) {
                if (version !~ /^[0-9]+([.][0-9]+)?([.][0-9]+)?$/) exit 1
                split(version, parts, ".")
                if (parts[1] > 13 || (parts[1] == 13 && (parts[2] > 0 || parts[3] > 0))) exit 1
                count++
                print version
            }
            $1 == "cmd" { command = $2; platform = "" }
            command == "LC_BUILD_VERSION" && $1 == "platform" { platform = $2 }
            command == "LC_BUILD_VERSION" && $1 == "minos" {
                if (platform != "1" && platform != "macos") exit 1
                check($2)
            }
            command == "LC_VERSION_MIN_MACOSX" && $1 == "version" { check($2) }
            END { if (!count) exit 1 }
        ')"; then
            error "Binary has a missing, invalid, or newer-than-13.0 macOS minimum: $binary"
            exit 1
        fi
        log "Verified darwin/$TARGET_ARCH minimum macOS $minimum_os <= 13.0: $binary"
    fi
}

build_bundled_runtime() {
    local runtime_dir="$1"
    # Target jmods determine the output OS/architecture; never execute a foreign image.
    jlink \
        --module-path "$JBR_JMODS_DIR" \
        --add-modules "$DESKTOP_JRE_MODULES_COMMON" \
        --strip-java-debug-attributes \
        --no-header-files \
        --no-man-pages \
        --compress=zip-6 \
        --output "$runtime_dir"
    local binary
    for binary in "$runtime_dir/bin/java" "$runtime_dir/lib/server/libjvm.dylib"; do
        verify_native_binary "$binary"
    done
    log "Linked bundled runtime for darwin/$TARGET_ARCH: $runtime_dir"
}

sign_test_bundle() {
    local bundle_root="$1"
    if [[ "$HOST_OS" == "darwin" ]]; then
        codesign --force --deep --sign - "$bundle_root"
        codesign --verify --deep --strict "$bundle_root"
        log "Ad-hoc signed test bundle; Developer ID notarization is not configured."
    else
        log "Cross-built bundle is unsigned; validate it on macOS before distribution."
    fi
}

normalize_macos_bundle_version() {
    local version="$1"
    local numbers=()
    local number
    while IFS= read -r number; do
        numbers+=("$number")
    done < <(printf '%s' "$version" | grep -o '[0-9]\+')

    local major="${numbers[0]:-1}"
    local minor="${numbers[1]:-0}"
    local patch="${numbers[2]:-0}"
    echo "$major.$minor.$patch"
}

render_info_plist_strings() {
    local locale_dir="$1"
    # Named for the locale they belong to, so they do not read as stray
    # lowercase spellings of the APP_NAME / APP_DESCRIPTION defaults.
    local localized_name="$2"
    local localized_description="$3"
    local template_file="$ROOT_DIR/release/macos/desktop/InfoPlist.strings"
    local output_file="$locale_dir/InfoPlist.strings"

    mkdir -p "$locale_dir"
    render_template \
        "$template_file" \
        "$output_file" \
        "$APP_NAME_PLACEHOLDER" "$localized_name" \
        "$APP_DESCRIPTION_PLACEHOLDER" "$localized_description"
}

prepare_app_bundle() {
    local bundle_root="$1"
    local contents_dir="$bundle_root/Contents"
    local macos_dir="$contents_dir/MacOS"
    local resources_dir="$contents_dir/Resources"
    local app_dir="$contents_dir/app"
    local plist_template="$ROOT_DIR/release/macos/desktop/Info.plist"
    local plist_file="$contents_dir/Info.plist"
    local executable_name="$PACKAGE_NAME"
    local icon_name="${PACKAGE_NAME}.icns"
    local bundle_version
    bundle_version="$(normalize_macos_bundle_version "$VERSION_NAME")"
    prepare_sparkle_metadata

    mkdir -p "$macos_dir" "$resources_dir" "$app_dir"
    cp "$INPUT_JAR" "$app_dir/$PACKAGE_NAME.jar"
    cp "$INPUT_LAUNCHER_BIN" "$macos_dir/$executable_name"
    chmod 755 "$macos_dir/$executable_name"
    cp "$INPUT_CORE_BIN" "$macos_dir/husi-core"
    chmod 755 "$macos_dir/husi-core"
    cp "$ROOT_DIR/release/linux/desktop/desktop-java-opts.conf" "$macos_dir/desktop-java-opts.conf.template"
    cp "$ROOT_DIR/release/linux/desktop/desktop-app-args.conf" "$macos_dir/desktop-app-args.conf.template"
    cp "$ICON_ICNS" "$resources_dir/$icon_name"
    cp "$ROOT_DIR/LICENSE" "$resources_dir/LICENSE.txt"

    render_template \
        "$plist_template" \
        "$plist_file" \
        "$PACKAGE_NAME_PLACEHOLDER" "$PACKAGE_NAME" \
        "$APP_NAME_PLACEHOLDER" "$APP_NAME" \
        "$APP_DESCRIPTION_PLACEHOLDER" "$APP_DESCRIPTION" \
        "$EXECUTABLE_NAME_PLACEHOLDER" "$executable_name" \
        "$ICON_FILE_PLACEHOLDER" "$icon_name" \
        "$URL_TYPE_NAME_PLACEHOLDER" "$DESKTOP_URL_TYPE_NAME" \
        "$URL_SCHEME_ENTRIES_PLACEHOLDER" "$URL_SCHEME_ENTRIES" \
        "$MACOS_BUNDLE_VERSION_PLACEHOLDER" "$VERSION_CODE" \
        "__HUSI_MACOS_SHORT_VERSION__" "$bundle_version" \
        "__SHIFT_UPDATE_FEED__" "$SPARKLE_FEED_URL" \
        "__SHIFT_UPDATE_PUBLIC_KEY__" "$SPARKLE_PUBLIC_KEY"

    render_info_plist_strings "$resources_dir/en.lproj" "$APP_NAME" "$APP_DESCRIPTION"
    render_info_plist_strings "$resources_dir/zh_CN.lproj" "$APP_NAME_ZH_CN" "$APP_DESCRIPTION_ZH_CN"
    render_info_plist_strings "$resources_dir/zh_TW.lproj" "$APP_NAME_ZH_TW" "$APP_DESCRIPTION_ZH_TW"

    build_bundled_runtime "$contents_dir/runtime"
    install_sparkle_updater "$contents_dir"
    verify_native_binary "$contents_dir/Helpers/ShiftVPSUpdater.app/Contents/MacOS/ShiftVPSUpdater"
    verify_native_binary "$contents_dir/Helpers/ShiftVPSUpdater.app/Contents/Frameworks/Sparkle.framework/Sparkle"
    touch_path_tree "$bundle_root"
    sign_test_bundle "$bundle_root"
}

build_dmg_with_hdiutil() {
    local dmg_root="$1"
    local output_path="$2"

    hdiutil create \
        -quiet \
        -format UDZO \
        -imagekey zlib-level=9 \
        -volname "$APP_NAME $VERSION_NAME" \
        -srcfolder "$dmg_root" \
        "$output_path"
}

build_dmg_with_xorrisofs() {
    local dmg_root="$1"
    local output_path="$2"

    "$DMG_COMMAND" \
        -quiet \
        -o "$output_path" \
        -V "$APP_NAME $VERSION_NAME" \
        -D \
        -r \
        --modification-date="$IMAGE_MODIFICATION_DATE" \
        "$dmg_root"
}

build_dmg() {
    local app_bundle="$1"
    local work_dir="$2"
    local dmg_root="$work_dir/dmg-root"
    local output_path="$OUTPUT_DIR/${PACKAGE_NAME}-${VERSION_NAME}-${TARGET_ARCH}.dmg"

    rm -rf "$dmg_root"
    mkdir -p "$dmg_root"
    cp -R "$app_bundle" "$dmg_root/"
    ln -s /Applications "$dmg_root/Applications"
    touch_path_tree "$dmg_root"

    rm -f "$output_path"
    case "$DMG_BACKEND" in
        hdiutil)
            build_dmg_with_hdiutil "$dmg_root" "$output_path"
            ;;
        xorrisofs)
            build_dmg_with_xorrisofs "$dmg_root" "$output_path"
            ;;
        *)
            error "Unsupported dmg backend '$DMG_BACKEND'."
            exit 1
            ;;
    esac

    touch -t "$TOUCH_TIMESTAMP" "$output_path"
    if [[ "$DMG_BACKEND" == "hdiutil" ]]; then
        log "Built dmg: $output_path"
    else
        log "Built dmg (ISO 9660) via $DMG_COMMAND: $output_path"
    fi
}

TARGET=""
TARGET_PLATFORM=""
TARGET_ARCH=""
INPUT_JAR=""
INPUT_LAUNCHER_BIN=""
INPUT_CORE_BIN=""
OUTPUT_DIR="$OUTPUT_DIR_DEFAULT"
CHECK_TOOLS=0
HOST_OS=""
DMG_BACKEND=""
DMG_COMMAND=""
ICON_ICNS=""
JBR_JMODS_ARG=""

while [[ $# -gt 0 ]]; do
    case "$1" in
        --target)
            require_arg "$1" "${2:-}"
            TARGET="$2"
            shift 2
            ;;
        -i|--input-jar)
            require_arg "$1" "${2:-}"
            INPUT_JAR="$2"
            shift 2
            ;;
        --launcher-bin)
            require_arg "$1" "${2:-}"
            INPUT_LAUNCHER_BIN="$2"
            shift 2
            ;;
        --core-bin)
            require_arg "$1" "${2:-}"
            INPUT_CORE_BIN="$2"
            shift 2
            ;;
        --jbr-jmods)
            require_arg "$1" "${2:-}"
            JBR_JMODS_ARG="$2"
            shift 2
            ;;
        -o|--output-dir)
            require_arg "$1" "${2:-}"
            OUTPUT_DIR="$2"
            shift 2
            ;;
        --check-tools)
            CHECK_TOOLS=1
            shift
            ;;
        -h|--help)
            usage
            exit 0
            ;;
        *)
            error "Unknown argument: $1"
            usage
            exit 1
            ;;
    esac
done

load_metadata
resolve_host_os
resolve_target
resolve_arch
resolve_dmg_backend
resolve_tag_epoch
require_tools
resolve_icon_asset

if [[ "$CHECK_TOOLS" -eq 1 ]]; then
    log "All required tools are available for target: darwin/$TARGET_ARCH (backend: $DMG_COMMAND)"
    exit 0
fi

resolve_input_jar "$INPUT_JAR"
resolve_launcher_bin "$INPUT_LAUNCHER_BIN"
resolve_core_bin "$INPUT_CORE_BIN"
verify_native_binary "$INPUT_LAUNCHER_BIN"
verify_native_binary "$INPUT_CORE_BIN"
verify_sqlite_native
resolve_jbr_jmods "$JBR_JMODS_ARG"
mkdir -p "$OUTPUT_DIR"

work_dir="$(mktemp -d)"
cleanup() {
    rm -rf "$work_dir"
}
trap cleanup EXIT

app_bundle="$work_dir/$APP_NAME.app"
prepare_app_bundle "$app_bundle"
build_dmg "$app_bundle" "$work_dir"

log "Done. Output directory: $OUTPUT_DIR"

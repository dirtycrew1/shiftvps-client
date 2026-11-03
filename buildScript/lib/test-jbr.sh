#!/usr/bin/env bash

# Offline downloader checks: tiny archives and a curl fake; no SDK is executed.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
ROOT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd -P)"
source "$ROOT_DIR/buildScript/init/version.sh"
TEMP_PARENT="$(cd "${TMPDIR:-/tmp}" && pwd -P)"
TEST_ROOT="$(mktemp -d "$TEMP_PARENT/shiftvps-jbr-test.XXXXXX")"

cleanup() {
    local suffix="${TEST_ROOT#"$TEMP_PARENT"/shiftvps-jbr-test.}"
    [[ -n "$suffix" && "$suffix" != "$TEST_ROOT" && "$suffix" != */* && ! -L "$TEST_ROOT" ]]
    [[ "$(cd "$TEST_ROOT" && pwd -P)" == "$TEST_ROOT" ]]
    rm -rf -- "$TEST_ROOT"
}
trap cleanup EXIT

fail() { printf 'JBR offline check failed: %s\n' "$*" >&2; exit 1; }

directory_link() {
    case "$(uname -s)" in
        MINGW* | MSYS*)
            # Windows junctions need no symlink privilege and Bash recognizes -L.
            TEST_LINK_TARGET="$(cygpath -w "$1")" TEST_LINK_PATH="$(cygpath -w "$2")" \
                powershell.exe -NoProfile -NonInteractive -Command \
                'New-Item -ItemType Junction -Path $env:TEST_LINK_PATH -Target $env:TEST_LINK_TARGET -ErrorAction Stop | Out-Null'
            ;;
        *) ln -s "$1" "$2" ;;
    esac
    [[ -L "$2" ]] || fail 'test directory link was not created'
}

mkdir -p "$TEST_ROOT/bin"
cat >"$TEST_ROOT/bin/curl" <<'CURL'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$*" >>"$TEST_STATE/arguments"
count="$(cat "$TEST_STATE/count")"
count=$((count + 1))
printf '%s' "$count" >"$TEST_STATE/count"
[[ "${!#}" == "$TEST_EXPECTED_URL" ]] || exit 90
if [[ -e "$TEST_INSTALL_DIR" ]]; then printf 'early\n' >>"$TEST_STATE/early-cache"; fi
if [[ "$TEST_SCENARIO" == all-fail || ( "$TEST_SCENARIO" == partial-first && "$count" -eq 1 ) ]]; then
    cat "$TEST_STATE/partial.tar.gz"
    exit 28
fi
if [[ "$TEST_SCENARIO" == incomplete-archive ]]; then
    cat "$TEST_STATE/partial.tar.gz"
    exit 0
fi
cat "$TEST_STATE/complete.tar.gz"
CURL
cat >"$TEST_ROOT/bin/sleep" <<'SLEEP'
#!/usr/bin/env bash
exit 0
SLEEP
chmod +x "$TEST_ROOT/bin/curl" "$TEST_ROOT/bin/sleep"
export PATH="$TEST_ROOT/bin:$PATH"

prepare_case() {
    local name="$1" target="$2" sdk="$3"
    local platform="${target%%/*}" arch="${target#*/}" vendor_arch=x64
    local vendor_platform="$platform"
    CASE_ROOT="$TEST_ROOT/$name"
    TEST_REPO="$CASE_ROOT/repository with spaces"
    TEST_STATE="$CASE_ROOT/state"
    mkdir -p "$TEST_REPO/buildScript/lib" "$TEST_REPO/buildScript/init" "$TEST_STATE"
    cp "$SCRIPT_DIR/jbr.sh" "$TEST_REPO/buildScript/lib/jbr.sh"
    cp "$ROOT_DIR/buildScript/init/version.sh" "$TEST_REPO/buildScript/init/version.sh"
    printf '0' >"$TEST_STATE/count"
    local home=''
    if [[ "$platform" == darwin ]]; then vendor_platform=osx; home=Contents/Home; fi
    if [[ "$arch" == arm64 ]]; then vendor_arch=aarch64; fi
    TEST_EXPECTED_URL="https://cache-redirector.jetbrains.com/intellij-jbr/jbrsdk-${JBR_VERSION}-${vendor_platform}-${vendor_arch}-b${JBR_BUILD}.tar.gz"
    local payload="$CASE_ROOT/payload/jbr${home:+/$home}"
    mkdir -p "$payload/bin" "$payload/jmods"
    printf 'JAVA_VERSION="fixture"\n' >"$payload/release"
    printf 'fixture-module\n' >"$payload/jmods/java.base.jmod"
    local executable=java
    if [[ "$platform" == windows ]]; then executable=java.exe; fi
    printf 'fixture, never executed\n' >"$payload/bin/$executable"
    tar -czf "$TEST_STATE/complete.tar.gz" -C "$CASE_ROOT/payload" jbr
    local partial="$CASE_ROOT/partial/jbr${home:+/$home}"
    mkdir -p "$partial"
    printf 'partial-marker\n' >"$partial/release"
    printf 'stale first attempt\n' >"$partial/first-attempt-only"
    tar -czf "$TEST_STATE/partial.tar.gz" -C "$CASE_ROOT/partial" jbr
    local kind=jmods
    if [[ "$sdk" == true ]]; then kind=sdk; fi
    TEST_INSTALL_DIR="$TEST_REPO/build/jbr/$JBR_RELEASE/$kind/${platform}_${arch}"
    TEST_RESULT_DIR="$TEST_INSTALL_DIR/jmods"
    if [[ "$sdk" == true ]]; then TEST_RESULT_DIR="$TEST_INSTALL_DIR${home:+/$home}"; fi
    export TEST_STATE TEST_EXPECTED_URL TEST_INSTALL_DIR
}

run_helper() {
    TEST_SCENARIO="$1" bash "$TEST_REPO/buildScript/lib/jbr.sh" "${@:2}" >"$TEST_STATE/output" 2>"$TEST_STATE/errors"
}

assert_ready() {
    [[ "$(cat "$TEST_STATE/output")" == "$TEST_RESULT_DIR" ]] || fail 'wrong result directory'
    [[ ! -e "$TEST_STATE/early-cache" ]] || fail 'cache published before download completed'
    [[ -z "$(find "$TEST_REPO/build/jbr" -name '.jbr-stage.*' -print -quit)" ]] || fail 'staging directory leaked'
    [[ -z "$(find "$TEST_INSTALL_DIR" -name first-attempt-only -print -quit)" ]] || fail 'partial attempt leaked into ready cache'
    while IFS= read -r invocation; do
        [[ "$invocation" == *'--connect-timeout 20'* && "$invocation" == *'--max-time 300'* ]] || fail 'download timeout missing'
        [[ "$invocation" != *'--retry'* ]] || fail 'curl-level retry would corrupt the streamed archive'
    done <"$TEST_STATE/arguments"
}

prepare_case retry-sdk darwin/amd64 true
run_helper partial-first darwin/amd64 --sdk || fail 'partial first attempt did not recover'
[[ "$(cat "$TEST_STATE/count")" == 2 ]] || fail 'retry count is not two'
assert_ready
[[ -f "$TEST_RESULT_DIR/release" && -f "$TEST_RESULT_DIR/jmods/java.base.jmod" && -f "$TEST_RESULT_DIR/bin/java" ]] || fail 'SDK is incomplete'

prepare_case all-fail darwin/arm64 false
if run_helper all-fail darwin/arm64; then fail 'all-fail case reported success'; fi
[[ "$(cat "$TEST_STATE/count")" == 3 ]] || fail 'failure retries were not bounded to three'
[[ ! -e "$TEST_INSTALL_DIR" ]] || fail 'failed download left a ready cache'
[[ -z "$(find "$TEST_REPO/build/jbr" -name '.jbr-stage.*' -print -quit)" ]] || fail 'failed stage leaked'

prepare_case first-success windows/amd64 true
run_helper success windows/amd64 --sdk || fail 'first download failed'
[[ "$(cat "$TEST_STATE/count")" == 1 ]] || fail 'successful download was retried'
assert_ready
[[ -f "$TEST_RESULT_DIR/bin/java.exe" ]] || fail 'Windows SDK layout changed'

prepare_case cold-jmods darwin/arm64 false
run_helper success darwin/arm64 || fail 'cold Mac modules download failed'
[[ "$(cat "$TEST_STATE/count")" == 1 && -f "$TEST_RESULT_DIR/java.base.jmod" ]] || fail 'Mac module layout changed'
assert_ready

prepare_case incomplete-archive linux/amd64 true
if run_helper incomplete-archive linux/amd64 --sdk; then fail 'incomplete SDK archive reported success'; fi
[[ "$(cat "$TEST_STATE/count")" == 3 && ! -e "$TEST_INSTALL_DIR" ]] || fail 'incomplete archive became a ready SDK'
[[ -z "$(find "$TEST_REPO/build/jbr" -name '.jbr-stage.*' -print -quit)" ]] || fail 'incomplete archive staging leaked'

prepare_case existing-incomplete linux/amd64 true
mkdir -p "$TEST_INSTALL_DIR"
printf 'preserve incomplete cache\n' >"$TEST_INSTALL_DIR/release"
if run_helper all-fail linux/amd64 --sdk; then fail 'incomplete existing SDK reported ready'; fi
[[ "$(cat "$TEST_STATE/count")" == 0 && "$(cat "$TEST_INSTALL_DIR/release")" == 'preserve incomplete cache' ]] || fail 'existing incomplete cache was overwritten'

prepare_case symlink-cache linux/amd64 true
link_target="$CASE_ROOT/existing SDK"
mkdir -p "$link_target" "${TEST_INSTALL_DIR%/*}"
cp -R "$CASE_ROOT/payload/jbr/." "$link_target/"
printf 'preserve linked target\n' >"$link_target/sentinel"
directory_link "$link_target" "$TEST_INSTALL_DIR"
if run_helper all-fail linux/amd64 --sdk; then fail 'linked SDK cache reported ready'; fi
if run_helper all-fail linux/amd64; then fail 'linked SDK modules reported ready'; fi
[[ "$(cat "$TEST_STATE/count")" == 0 && -L "$TEST_INSTALL_DIR" ]] || fail 'linked SDK was changed or downloaded'
[[ "$(cat "$link_target/sentinel")" == 'preserve linked target' && "$(cat "$link_target/release")" == 'JAVA_VERSION="fixture"' && -f "$link_target/jmods/java.base.jmod" && -f "$link_target/bin/java" ]] || fail 'linked target was changed'
[[ ! -s "$TEST_STATE/output" ]] || fail 'linked SDK printed a ready-cache path'
rm "$TEST_INSTALL_DIR"

prepare_case warm-cache linux/amd64 true
mkdir -p "$TEST_INSTALL_DIR"
cp -R "$CASE_ROOT/payload/jbr/." "$TEST_INSTALL_DIR/"
printf 'preserve\n' >"$TEST_INSTALL_DIR/sentinel"
run_helper all-fail linux/amd64 --sdk || fail 'valid warm SDK not reused'
[[ "$(cat "$TEST_STATE/count")" == 0 && "$(cat "$TEST_INSTALL_DIR/sentinel")" == preserve ]] || fail 'warm SDK changed or downloaded'
[[ "$(cat "$TEST_STATE/output")" == "$TEST_RESULT_DIR" ]] || fail 'wrong warm SDK path'
run_helper all-fail linux/amd64 || fail 'warm SDK modules not reused'
[[ "$(cat "$TEST_STATE/count")" == 0 && "$(cat "$TEST_STATE/output")" == "$TEST_RESULT_DIR/jmods" ]] || fail 'warm modules changed or downloaded'

# Internal JDK links are legal; only the cache directory and parents are guarded.
mv "$TEST_INSTALL_DIR/jmods" "$CASE_ROOT/internal modules"
directory_link "$CASE_ROOT/internal modules" "$TEST_INSTALL_DIR/jmods"
run_helper all-fail linux/amd64 --sdk || fail 'internal JDK link was rejected'
[[ "$(cat "$TEST_STATE/count")" == 0 && -f "$CASE_ROOT/internal modules/java.base.jmod" ]] || fail 'internal JDK link was changed or downloaded'
rm "$TEST_INSTALL_DIR/jmods"

printf 'JBR offline download checks passed (retry, all-fail, cold SDK/modules, incomplete archives, linked cache, warm cache).\n'

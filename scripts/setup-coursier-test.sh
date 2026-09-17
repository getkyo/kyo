#!/usr/bin/env bash
set -euo pipefail

# Exercise setup retries without downloads, installation, or real backoff delays.
script_dir=$(cd "$(dirname "$0")" && pwd)
test_dir=$(mktemp -d)
trap 'rm -rf "$test_dir"' EXIT
mkdir "$test_dir/bin"

cat > "$test_dir/bin/curl" <<'STUB'
#!/usr/bin/env bash
set -euo pipefail
count=$(cat "$STUB_STATE/downloads" 2>/dev/null || echo 0)
count=$((count + 1))
echo "$count" > "$STUB_STATE/downloads"
output=
while [ "$#" -gt 0 ]; do
    case "$1" in
        --output) output=$2; shift ;;
        https://*) printf '%s\n' "$1" >> "$STUB_STATE/urls" ;;
    esac
    shift
done
printf 'partial' > "$output"
[ "$count" -gt "$DOWNLOAD_FAILURES" ] || exit 22
printf 'bundle' > "$output"
STUB

cat > "$test_dir/bin/node" <<'STUB'
#!/usr/bin/env bash
set -euo pipefail
[ "$(cat "$1")" = bundle ]
[ "$INPUT_JVM" = corretto:25 ]
[ "$INPUT_APPS" = sbt ]
[ "$INPUT_USECONTAINERIMAGE" = false ]
[ "$INPUT_DISABLEDEFAULTREPOS" = false ]
count=$(cat "$STUB_STATE/setups" 2>/dev/null || echo 0)
count=$((count + 1))
echo "$count" > "$STUB_STATE/setups"
[ "$count" -gt "$SETUP_FAILURES" ] || exit 42
printf 'JAVA_HOME=/jdk with spaces\n' >> "$GITHUB_ENV"
printf '/coursier with spaces/bin\n' >> "$GITHUB_PATH"
printf 'cs-version=2.1.25-M19\n' >> "$GITHUB_OUTPUT"
STUB

cat > "$test_dir/bin/sleep" <<'STUB'
#!/usr/bin/env bash
set -euo pipefail
[ "$1" = 30 ]
printf 'backoff\n' >> "$STUB_STATE/sleeps"
STUB
chmod +x "$test_dir/bin/"*

run_case() {
    local name=$1 download_failures=$2 setup_failures=$3 expected_status=$4
    local downloads=$5 setups=$6 sleeps=$7 status=0
    local state="$test_dir/$name"
    mkdir -p "$state/runner temp"
    PATH="$test_dir/bin:$PATH" STUB_STATE="$state" \
        DOWNLOAD_FAILURES="$download_failures" SETUP_FAILURES="$setup_failures" \
        RUNNER_TEMP="$state/runner temp" GITHUB_ENV="$state/env" \
        GITHUB_PATH="$state/path" GITHUB_OUTPUT="$state/output" \
        bash "$script_dir/setup-coursier.sh" > "$state/log" 2>&1 || status=$?
    if [ "$status" -ne "$expected_status" ]; then
        cat "$state/log"
        echo "$name: expected exit $expected_status, got $status" >&2
        exit 1
    fi
    [ "$(cat "$state/downloads")" -eq "$downloads" ]
    [ "$(cat "$state/setups" 2>/dev/null || echo 0)" -eq "$setups" ]
    [ "$(wc -l < "$state/urls" | tr -d ' ')" -eq "$downloads" ]
    [ "$(sort -u "$state/urls")" = 'https://raw.githubusercontent.com/coursier/setup-action/fd1707a76b027efdfb66ca79318b4d29b72e5a02/dist/index.js' ]
    if [ "$sleeps" -eq 0 ]; then
        [ ! -e "$state/sleeps" ]
    else
        [ "$(wc -l < "$state/sleeps" | tr -d ' ')" -eq "$sleeps" ]
    fi
    [ -z "$(ls -A "$state/runner temp")" ]
    if [ "$expected_status" -eq 0 ]; then
        [ "$(cat "$state/env")" = 'JAVA_HOME=/jdk with spaces' ]
        [ "$(cat "$state/path")" = '/coursier with spaces/bin' ]
        [ "$(cat "$state/output")" = 'cs-version=2.1.25-M19' ]
    fi
    echo "PASS: $name"
}

run_case success 0 0 0 1 1 0
run_case download-recovery 2 0 0 3 1 2
run_case setup-recovery 0 2 0 3 3 2
run_case mixed-recovery 1 2 0 4 3 3
run_case download-exhaustion 4 0 22 4 0 3
run_case setup-exhaustion 0 4 42 4 4 3

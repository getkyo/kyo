#!/usr/bin/env bash
set -euo pipefail

# Exercise scripts/fetch-lib.sh without a network, real clones, or real backoff delays: curl, git
# and sleep are stubs on PATH that record what they were asked to do. Run under `sh`, because
# that is what the container preludes source the library from.
script_dir=$(cd "$(dirname "$0")" && pwd)
test_dir=$(mktemp -d)
trap 'rm -rf "$test_dir"' EXIT
mkdir "$test_dir/bin"

# Records its full argument list, one call per line, and writes a marker file for --output.
cat > "$test_dir/bin/curl" <<'STUB'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$*" >> "$STUB_STATE/curl-calls"
STUB

# Fails the first GIT_FAILURES attempts, leaving a partial directory behind as a real failed
# clone does; succeeds after that. The directory argument is last.
cat > "$test_dir/bin/git" <<'STUB'
#!/usr/bin/env bash
set -euo pipefail
[ "$1" = clone ]
printf '%s\n' "$*" >> "$STUB_STATE/git-calls"
count=$(cat "$STUB_STATE/clones" 2>/dev/null || echo 0)
count=$((count + 1))
echo "$count" > "$STUB_STATE/clones"
dir=${*: -1}
[ ! -e "$dir" ] || { echo "clone target already exists: $dir" >&2; exit 99; }
mkdir -p "$dir"
printf 'partial' > "$dir/partial"
[ "$count" -gt "$GIT_FAILURES" ] || exit 128
rm "$dir/partial"
printf 'ok' > "$dir/README"
STUB

cat > "$test_dir/bin/sleep" <<'STUB'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$1" >> "$STUB_STATE/sleeps"
STUB
chmod +x "$test_dir/bin/"*

run_sh() {
    local state=$1
    shift
    PATH="$test_dir/bin:$PATH" STUB_STATE="$state" GIT_FAILURES="${GIT_FAILURES:-0}" \
        sh -c ". '$script_dir/fetch-lib.sh'; $*"
}

lines() {
    if [ -e "$1" ]; then wc -l < "$1" | tr -d ' '; else echo 0; fi
}

# fetch_url: the whole retry policy is what curl is asked for, so the assertion is the exact
# argument list, including the environment overrides the self-test relies on.
state="$test_dir/url"; mkdir -p "$state"
run_sh "$state" 'fetch_url https://example.invalid/a.zip /tmp/a.zip'
[ "$(cat "$state/curl-calls")" = '--fail --location --silent --show-error --connect-timeout 30 --retry 5 --retry-delay 30 --retry-max-time 300 --output /tmp/a.zip https://example.invalid/a.zip' ]
echo "PASS: fetch_url passes the retry policy to curl"

state="$test_dir/url-override"; mkdir -p "$state"
FETCH_RETRY_DELAY=0 FETCH_RETRY_MAX_TIME=1 run_sh "$state" 'fetch_url https://example.invalid/a.zip /tmp/a.zip'
[ "$(cat "$state/curl-calls")" = '--fail --location --silent --show-error --connect-timeout 30 --retry 5 --retry-delay 0 --retry-max-time 1 --output /tmp/a.zip https://example.invalid/a.zip' ]
echo "PASS: fetch_url honors FETCH_RETRY_DELAY and FETCH_RETRY_MAX_TIME"

# fetch_git_clone: attempts, waits, and a fresh directory for every attempt.
state="$test_dir/clone-ok"; mkdir -p "$state"
run_sh "$state" "fetch_git_clone '$state/src' --depth 1 --branch v1 https://example.invalid/r.git"
[ "$(cat "$state/clones")" -eq 1 ]
[ "$(lines "$state/sleeps")" -eq 0 ]
[ "$(cat "$state/git-calls")" = "clone --depth 1 --branch v1 https://example.invalid/r.git $state/src" ]
[ "$(cat "$state/src/README")" = ok ]
echo "PASS: fetch_git_clone clones once when the first attempt succeeds"

state="$test_dir/clone-recovery"; mkdir -p "$state"
GIT_FAILURES=2 run_sh "$state" "fetch_git_clone '$state/src' https://example.invalid/r.git" 2>"$state/stderr"
[ "$(cat "$state/clones")" -eq 3 ]
[ "$(lines "$state/sleeps")" -eq 2 ]
[ "$(sort -u "$state/sleeps")" = 30 ]
[ "$(cat "$state/src/README")" = ok ]
[ ! -e "$state/src/partial" ]
grep -q 'attempt 1 of 6' "$state/stderr"
echo "PASS: fetch_git_clone retries with 30-second waits and a fresh directory"

state="$test_dir/clone-exhaustion"; mkdir -p "$state"
status=0
GIT_FAILURES=6 run_sh "$state" "fetch_git_clone '$state/src' https://example.invalid/r.git" 2>"$state/stderr" || status=$?
[ "$status" -eq 1 ]
[ "$(cat "$state/clones")" -eq 6 ]
[ "$(lines "$state/sleeps")" -eq 5 ]
grep -q 'failed after 6 attempts' "$state/stderr"
echo "PASS: fetch_git_clone gives up after six attempts"

state="$test_dir/clone-delay"; mkdir -p "$state"
GIT_FAILURES=1 FETCH_RETRY_DELAY=0 run_sh "$state" "fetch_git_clone '$state/src' https://example.invalid/r.git" 2>/dev/null
[ "$(cat "$state/sleeps")" = 0 ]
echo "PASS: fetch_git_clone honors FETCH_RETRY_DELAY"

# fetch_verify_sha256, against a digest computed independently of the library's tool cascade.
state="$test_dir/sha"; mkdir -p "$state"
printf 'abc' > "$state/abc"
abc_sha256=ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad
run_sh "$state" "fetch_verify_sha256 '$state/abc' $abc_sha256 abc"
echo "PASS: fetch_verify_sha256 accepts a matching digest"

status=0
run_sh "$state" "fetch_verify_sha256 '$state/abc' 0000000000000000000000000000000000000000000000000000000000000000 abc" 2>"$state/stderr" || status=$?
[ "$status" -eq 1 ]
grep -q 'checksum mismatch for abc' "$state/stderr"
grep -q "actual   $abc_sha256" "$state/stderr"
echo "PASS: fetch_verify_sha256 rejects a mismatch and names both digests"

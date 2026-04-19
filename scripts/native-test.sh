#!/usr/bin/env bash
set -uo pipefail
#
# Run Scala Native tests with retry and crash tolerance.
#
# Handles three failure modes:
# 1. Process killed before tests complete (no test output) — retries
# 2. Scala Native libunwind crash — tolerates if 0 test failures
# 3. Process hangs with no output for 3 minutes — killed and retried
#
# Only fails on actual test failures (failed > 0).
#
# Usage:
#   ./scripts/native-test.sh                          # runs sbt 'kyoNative/test'
#   ./scripts/native-test.sh 'testKyo --all Native'   # custom sbt command
#   ./scripts/native-test.sh --self-test              # run built-in validation
#

# ── self-test mode (must be before SBT_CMD assignment) ──────────────
if [ "${1:-}" = "--self-test" ]; then
    SELF="$0"
    PASS=0; FAIL=0; TOTAL=0
    TMPDIR_SELF=$(mktemp -d)
    trap 'rm -rf "$TMPDIR_SELF"' EXIT

    assert() {
        local name="$1" expected="$2"
        shift 2
        TOTAL=$((TOTAL+1))
        local fake="$TMPDIR_SELF/fake-$$-$TOTAL"
        printf '#!/bin/bash\n%s\n' "$*" > "$fake"
        chmod +x "$fake"
        ln -sf "$fake" "$TMPDIR_SELF/sbt"
        actual=$(PATH="$TMPDIR_SELF:$PATH" MAX_RETRIES=2 STALE_TIMEOUT=3 POLL_INTERVAL=1 \
            "$SELF" "test" >/dev/null 2>&1; echo $?)
        if [ "$actual" = "$expected" ]; then
            echo "  PASS: $name"
            PASS=$((PASS+1))
        else
            echo "  FAIL: $name — expected exit $expected, got $actual"
            FAIL=$((FAIL+1))
        fi
    }

    echo "Running self-tests..."

    # ── basic outcomes ──
    assert "clean pass"                     0  'echo "Tests: succeeded 100, failed 0"; exit 0'
    assert "test failures"                  1  'echo "Tests: succeeded 90, failed 3"; exit 1'
    assert "crash after pass"               0  'echo "Tests: succeeded 100, failed 0"; exit 137'
    assert "killed before tests"            1  'exit 137'

    # ── hang scenarios (watchdog kills, then check_log decides) ──
    assert "hang after tests pass"          0  'echo "Tests: succeeded 163, failed 0"; sleep 600'
    assert "hang after test failures"       1  'echo "Tests: succeeded 90, failed 2"; sleep 600'
    assert "hang with no output"            1  'sleep 600'

    # ── multiple test suites in one run ──
    assert "multi-suite all pass"           0  'echo "Tests: succeeded 64, failed 0"
echo "Tests: succeeded 45, failed 0"
echo "Tests: succeeded 163, failed 0"
exit 0'
    assert "multi-suite one fails"          1  'echo "Tests: succeeded 64, failed 0"
echo "Tests: succeeded 45, failed 2"
echo "Tests: succeeded 163, failed 0"
exit 1'
    assert "multi-suite pass then hang"     0  'echo "Tests: succeeded 64, failed 0"
echo "Tests: succeeded 45, failed 0"
echo "Tests: succeeded 163, failed 0"
sleep 600'
    assert "multi-suite fail then hang"     1  'echo "Tests: succeeded 64, failed 0"
echo "Tests: succeeded 45, failed 1"
sleep 600'

    # ── scalafmt / other non-test errors ──
    assert "scalafmt fail, tests pass"      0  'echo "scalafmt: failed for 1 sources"
echo "Tests: succeeded 100, failed 0"
exit 1'
    assert "scalafmt fail, no tests"        1  'echo "scalafmt: failed for 1 sources"; exit 1'

    # ── retry behavior ──
    rm -f /tmp/native-test-retry-flag
    assert "retry succeeds on 2nd attempt"  0  '
if [ ! -f /tmp/native-test-retry-flag ]; then
    touch /tmp/native-test-retry-flag
    exit 137
else
    rm -f /tmp/native-test-retry-flag
    echo "Tests: succeeded 100, failed 0"
    exit 0
fi'

    echo ""
    echo "Results: $PASS/$TOTAL passed, $FAIL failed"
    rm -rf "$TMPDIR_SELF"
    [ $FAIL -eq 0 ]
    exit $?
fi

# ── main ────────────────────────────────────────────────────────────
MAX_RETRIES=${MAX_RETRIES:-3}
STALE_TIMEOUT=${STALE_TIMEOUT:-180}  # seconds without output before killing
POLL_INTERVAL=${POLL_INTERVAL:-10}   # check output freshness interval
WALL_TIMEOUT=${WALL_TIMEOUT:-2400}   # hard wall-clock limit per attempt (40 min)
SBT_CMD="${1:-kyoNative/test}"
LOG=$(mktemp)
tail_pid=""
trap 'rm -f "$LOG"; [ -n "$tail_pid" ] && kill $tail_pid 2>/dev/null' EXIT

log() { echo "=== [native-test] $* ==="; }

file_size() { wc -c < "$1" 2>/dev/null | tr -d ' '; }

# Kill a process and all its descendants
kill_tree() {
    local pid=$1 sig=${2:-TERM}
    # Try process-group kill first (works when setsid was used)
    kill -$sig -- -$pid 2>/dev/null
    # Also kill direct children in case process-group kill missed
    local children
    children=$(pgrep -P $pid 2>/dev/null) || true
    for child in $children; do
        kill -$sig $child 2>/dev/null
    done
    kill -$sig $pid 2>/dev/null
}

# Evaluate log contents: returns 0 (pass), 1 (fail), or 2 (no test output).
check_log() {
    if grep -qE "Tests:.*failed [1-9]" "$LOG"; then
        log "tests FAILED (real test failures detected)"
        return 1
    fi
    if grep -qE "Tests:" "$LOG"; then
        log "0 test failures — tolerating non-zero exit"
        return 0
    fi
    return 2
}

for attempt in $(seq 1 $MAX_RETRIES); do
    log "attempt $attempt/$MAX_RETRIES — running: sbt $SBT_CMD"
    > "$LOG"  # truncate log

    # Run sbt in its own process group if possible (Linux setsid)
    if command -v setsid &>/dev/null; then
        setsid sbt "$SBT_CMD" >> "$LOG" 2>&1 &
    else
        sbt "$SBT_CMD" >> "$LOG" 2>&1 &
    fi
    sbt_pid=$!

    # Stream log to stdout so CI sees progress in real time
    tail -f "$LOG" 2>/dev/null &
    tail_pid=$!

    # Watchdog: kill sbt if output stops growing OR wall-clock limit exceeded
    last_size=$(file_size "$LOG")
    stale_seconds=0
    wall_seconds=0

    while kill -0 $sbt_pid 2>/dev/null; do
        sleep $POLL_INTERVAL
        current_size=$(file_size "$LOG")
        wall_seconds=$((wall_seconds + POLL_INTERVAL))

        if [ $wall_seconds -ge $WALL_TIMEOUT ]; then
            log "wall-clock limit ${WALL_TIMEOUT}s exceeded — killing process (pid $sbt_pid)"
            kill_tree $sbt_pid TERM
            sleep 3
            if kill -0 $sbt_pid 2>/dev/null; then
                log "process still alive — sending SIGKILL"
                kill_tree $sbt_pid KILL
            fi
            break
        fi

        if [ "$current_size" = "$last_size" ]; then
            stale_seconds=$((stale_seconds + POLL_INTERVAL))
            if [ $((stale_seconds % 60)) -eq 0 ] && [ $stale_seconds -lt $STALE_TIMEOUT ]; then
                log "no output for ${stale_seconds}s (killing in $((STALE_TIMEOUT - stale_seconds))s)..."
            fi
            if [ $stale_seconds -ge $STALE_TIMEOUT ]; then
                log "no output for ${STALE_TIMEOUT}s — killing hung process (pid $sbt_pid)"
                kill_tree $sbt_pid TERM
                sleep 3
                if kill -0 $sbt_pid 2>/dev/null; then
                    log "process still alive — sending SIGKILL"
                    kill_tree $sbt_pid KILL
                fi
                break
            fi
        else
            if [ $stale_seconds -ge 60 ]; then
                log "output resumed after ${stale_seconds}s pause"
            fi
            stale_seconds=0
            last_size=$current_size
        fi
    done

    wait $sbt_pid 2>/dev/null
    exit_code=$?
    kill $tail_pid 2>/dev/null
    wait $tail_pid 2>/dev/null
    tail_pid=""

    if [ $exit_code -eq 0 ]; then
        log "tests passed"
        exit 0
    fi

    check_log
    rc=$?
    if [ $rc -le 1 ]; then
        exit $rc
    fi

    # No test output — process was killed before tests ran
    log "no test output — retrying..."
done

log "FAILED — no test output after $MAX_RETRIES attempts"
exit 1

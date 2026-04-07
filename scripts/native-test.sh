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
#

MAX_RETRIES=3
STALE_TIMEOUT=180  # seconds (3 min) without output before killing
POLL_INTERVAL=10   # check output freshness every 10 seconds
SBT_CMD="${1:-kyoNative/test}"
LOG=$(mktemp)
trap "rm -f $LOG" EXIT

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

    # Watchdog: poll log file size, kill sbt if it stops growing
    last_size=$(file_size "$LOG")
    stale_seconds=0
    hung=false

    while kill -0 $sbt_pid 2>/dev/null; do
        sleep $POLL_INTERVAL
        current_size=$(file_size "$LOG")

        if [ "$current_size" = "$last_size" ]; then
            stale_seconds=$((stale_seconds + POLL_INTERVAL))
            if [ $((stale_seconds % 60)) -eq 0 ] && [ $stale_seconds -lt $STALE_TIMEOUT ]; then
                log "no output for ${stale_seconds}s (killing in $((STALE_TIMEOUT - stale_seconds))s)..."
            fi
            if [ $stale_seconds -ge $STALE_TIMEOUT ]; then
                log "no output for ${STALE_TIMEOUT}s — killing hung process (pid $sbt_pid)"
                kill_tree $sbt_pid TERM
                sleep 3
                # Force kill if still alive
                if kill -0 $sbt_pid 2>/dev/null; then
                    log "process still alive — sending SIGKILL"
                    kill_tree $sbt_pid KILL
                fi
                hung=true
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

    if $hung; then
        log "process was hung — retrying..."
        continue
    fi

    if [ $exit_code -eq 0 ]; then
        log "tests passed"
        exit 0
    fi

    # Check for actual test failures
    if grep -qE "Tests:.*failed [1-9]" "$LOG"; then
        log "tests FAILED (real test failures detected)"
        exit 1
    fi

    # Tests ran with 0 failures but sbt exited non-zero (crash, scalafmt, etc.)
    if grep -qE "Tests:" "$LOG"; then
        log "0 test failures — tolerating non-zero exit (crash/scalafmt)"
        exit 0
    fi

    # No test output at all — process was killed before tests ran
    log "no test output — retrying..."
done

log "FAILED — no test output after $MAX_RETRIES attempts"
exit 1

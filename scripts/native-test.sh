#!/usr/bin/env bash
set -uo pipefail
#
# Run Scala Native tests with retry and crash tolerance.
#
# Handles two failure modes:
# 1. Process killed before tests complete (no test output) — retries
# 2. Scala Native libunwind crash — tolerates if 0 test failures
#
# Only fails on actual test failures (failed > 0).
#
# Usage:
#   ./scripts/native-test.sh                        # runs sbt '+kyoNative/test'
#   ./scripts/native-test.sh '+kyoNative/testQuick' # custom sbt command
#

MAX_RETRIES=3
SBT_CMD="${1:-+kyoNative/test}"
LOG=$(mktemp)
trap "rm -f $LOG" EXIT

for attempt in $(seq 1 $MAX_RETRIES); do
    echo "=== Native test attempt $attempt/$MAX_RETRIES ==="
    sbt "$SBT_CMD" 2>&1 | tee "$LOG"
    exit_code=${PIPESTATUS[0]}

    if [ $exit_code -eq 0 ]; then
        echo "=== Native tests passed ==="
        exit 0
    fi

    # Check for actual test failures
    if grep -qE "Tests:.*failed [1-9]" "$LOG"; then
        echo "=== Native tests FAILED ==="
        exit 1
    fi

    # If test output exists (Tests: line present), tolerate crash with 0 failures
    if grep -qE "Tests:" "$LOG"; then
        echo "=== Native tests: 0 failures (process crash tolerated) ==="
        exit 0
    fi

    # No test output at all — process was killed before tests ran, retry
    echo "=== No test output, retrying... ==="
done

echo "=== Native tests: no test output after $MAX_RETRIES attempts ==="
exit 1

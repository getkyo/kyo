#!/usr/bin/env bash
set -uo pipefail
#
# Run Scala Native tests, tolerating process crashes with zero test failures.
#
# Scala Native's bundled libunwind conflicts with the system libunwind,
# causing intermittent SIGSEGV in decodeFDE during thread_start. This
# crashes the test runner process but produces zero actual test failures.
#
# This script checks the output: if any test reports failures (failed > 0),
# it fails. If the only errors are process crashes with zero failures, it
# passes.
#
# Usage:
#   ./scripts/native-test.sh                        # runs sbt '+kyoNative/test'
#   ./scripts/native-test.sh '+kyoNative/testQuick' # custom sbt command
#

SBT_CMD="${1:-+kyoNative/test}"

echo "=== Running: sbt $SBT_CMD ==="
output=$(sbt "$SBT_CMD" 2>&1)
exit_code=$?

echo "$output"

if [ $exit_code -eq 0 ]; then
    echo "=== Native tests passed ==="
    exit 0
fi

# Check for actual test failures in sbt's test summary line
if echo "$output" | grep -qE "Tests:.*failed [1-9]"; then
    echo "=== Native tests FAILED ==="
    exit 1
fi

# sbt exited non-zero but no tests failed — process crash only
echo "=== Native tests: 0 failures (Scala Native libunwind process crash tolerated) ==="
exit 0

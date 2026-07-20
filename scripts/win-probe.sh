#!/usr/bin/env bash
# Windows compiler-diagnosis probe (dev artifact, probe branch only).
set -x
sbt 'kyo-compilerJVM/testOnly kyo.internal.WinProbeTest' 2>&1 | grep -E "\[probe\]|PASS|FAIL|Tests:|\[error\]"
sbt 'kyo-compilerJVM/testOnly kyo.internal.CompilerPoolTest kyo.internal.WinProbeTest' 2>&1 | grep -E "\[probe\]|PASS|FAIL|Tests:|=== |\[error\]"
timeout -k 20 900 sbt 'kyo-compilerJVM/test' 2>&1 | grep -E "PASS|FAIL|Tests:|=== |\[error\]"
echo "full test exit=$?"
jps -l || true
for p in $(jps -l | grep -Ei 'ForkMain' | awk '{print $1}'); do
    echo "=== jstack $p ==="
    jstack "$p" || true
    taskkill //F //PID "$p" || true
done

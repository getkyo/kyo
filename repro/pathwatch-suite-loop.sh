#!/usr/bin/env bash
# Usage: repro/pathwatch-suite-loop.sh <iterations>; runs the whole kyo-systemJVM test suite repeatedly (leaves in parallel, as CI does)
# with the ancestor-move leaf instrumented to list handle holders on a denied move. Stops at the first failing run.
set +e +o pipefail
curl -sSL -o /tmp/handle.zip https://download.sysinternals.com/files/Handle.zip && unzip -o -q /tmp/handle.zip -d repro && ls repro
powershell -NoProfile -Command "Get-Service WSearch | Format-List Name,Status,StartType" 2>&1 | head -4
for i in $(seq 1 "$1"); do
    ./scripts/sbt.sh test-jvm "kyo-systemJVM/test" > /tmp/suite-$i.log 2>&1
    code=$?
    pass=$(sed 's/\x1b\[[0-9;]*m//g' /tmp/suite-$i.log | grep -ac "PASS\] watcher invalidates when an ancestor")
    echo "=== iteration $i exit $code ancestor-leaf passes $pass"
    sed 's/\x1b\[[0-9;]*m//g' /tmp/suite-$i.log | grep -aE "^\s*\[FAIL\]|kyo-test: [0-9]+ tests" | head -8
    if sed 's/\x1b\[[0-9;]*m//g' /tmp/suite-$i.log | grep -aq "SCRATCH-HANDLES"; then
        sed 's/\x1b\[[0-9;]*m//g' /tmp/suite-$i.log | grep -a -A40 "SCRATCH-HANDLES" | head -60
        exit 1
    fi
done
echo "=== no denied move in $1 suite runs"

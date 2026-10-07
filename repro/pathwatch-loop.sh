#!/usr/bin/env bash
# Usage: repro/pathwatch-loop.sh <probe-iterations> <test-iterations>; runs the move probe in three modes, records Defender's state, then
# repeats kyo-systemJVM testOnly kyo.PathWatchTest and tallies the ancestor-move leaf.
set +e +o pipefail
powershell -NoProfile -Command "Get-MpComputerStatus | Select-Object RealTimeProtectionEnabled,AntivirusEnabled,IoavProtectionEnabled | Format-List" 2>&1 | head -6
echo "TEMP=$TEMP"
for mode in none scan settle; do java repro/MoveProbe.java "$1" "$mode"; done
cmds=()
for _ in $(seq 1 "$2"); do cmds+=("kyo-systemJVM/testOnly kyo.PathWatchTest"); done
./scripts/sbt.sh test-jvm "${cmds[@]}" > /tmp/pathwatch.log 2>&1
echo "sbt exit $?"
sed 's/\x1b\[[0-9;]*m//g' /tmp/pathwatch.log | grep -aE "\[(PASS|FAIL)\] watcher invalidates when an ancestor" | sed 's/(.*//' | sort | uniq -c
sed 's/\x1b\[[0-9;]*m//g' /tmp/pathwatch.log | grep -aE "^--- PathWatchTest" | sed 's/(.*//' | sort | uniq -c
sed 's/\x1b\[[0-9;]*m//g' /tmp/pathwatch.log | grep -a -A3 "FAIL\] watcher invalidates when an ancestor" | head -12

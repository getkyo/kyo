#!/usr/bin/env bash
# Usage: repro/watch-loop.sh <iterations> <sbt command>; repeats one sbt test command with the event-loop watchdog in the runner and
# prints every watchdog report (a leaf that blocked the event loop) with its main-thread stack. Stops after the first report.
set +e +o pipefail
iters=$1
shift
echo kyo-podWasm > /tmp/pod-plan
scripts/fixture-images.sh /tmp/pod-plan
for i in $(seq 1 "$iters"); do
    log=/tmp/watch-$i.log
    echo "=== iteration $i start $(date -u +%T): $*"
    ./scripts/sbt.sh run "$@" > "$log" 2>&1
    echo "=== iteration $i exit $? end $(date -u +%T)"
    sed 's/\x1b\[[0-9;]*m//g' "$log" | grep -aE "kyo-test: [0-9]+ tests|^\s*\[(FAIL|TIMEOUT)\]|RunTerminated" | head -10
    if sed 's/\x1b\[[0-9;]*m//g' "$log" | grep -aq "event loop has been blocked"; then
        echo "=== WATCHDOG REPORT in iteration $i"
        sed 's/\x1b\[[0-9;]*m//g' "$log" | grep -a -B3 -A140 "event loop has been blocked" | head -300
        exit 1
    fi
done
echo "=== no watchdog report in $iters iterations"

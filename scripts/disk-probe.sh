#!/usr/bin/env bash
set -uo pipefail
#
# disk-probe.sh - instrumented Native testDiff run for the runner disk-exhaustion
# investigation. Prints the runner's starting disk, samples free disk with a du
# attribution every 60s while the row runs, and dumps a final per-module breakdown.
# Dispatch via ci.yml custom mode: bash scripts/disk-probe.sh

echo "=== STARTING DISK ==="
df -h /
df -Pm .

(
    while :; do
        free_mb=$(df -Pm . | awk 'NR==2{print $4}')
        tgt=$(du -scm ./*/native/target 2>/dev/null | tail -1 | cut -f1)
        tmpd=$(du -sm /tmp 2>/dev/null | cut -f1)
        cour=$(du -sm "$HOME/.cache/coursier" 2>/dev/null | cut -f1)
        echo "[disk-probe $(date -u +%H:%M:%S)] freeMB=$free_mb nativeTargetsMB=${tgt:-0} tmpMB=${tmpd:-0} coursierMB=${cour:-0}"
        sleep 60
    done
) &
probe=$!

./scripts/ci-test.sh Native testDiff
rc=$?
kill "$probe" 2>/dev/null

echo "=== FINAL BREAKDOWN: per-module native-test workspaces ==="
du -sm ./*/native/target/scala-*/native-test ./*/*/native/target/scala-*/native-test 2>/dev/null | sort -rn | head -40
echo "=== per-target totals ==="
du -scm ./*/native/target 2>/dev/null | sort -rn | head -15
echo "=== caches and misc ==="
du -sm "$HOME/.cache/coursier" "$HOME/.sbt" "$HOME/.cache/kyo-browser" /tmp kyo-net/build/boringssl 2>/dev/null
echo "=== ENDING DISK ==="
df -h /
exit "$rc"

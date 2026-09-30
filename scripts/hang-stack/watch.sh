#!/usr/bin/env bash
# One-off stack capture for a Node test process whose event loop stops returning.
#
# Runs one sbt command the way ci-test.sh's run phase does (scheduler status file, ci-monitor, the out-of-JVM
# driver heap cap) with every Node process preloading heartbeat.cjs. When a live process's heartbeat is older
# than STALE_SECS, it is captured, then killed so the run can end:
#   1. SIGUSR1 opens the inspector; capture.cjs takes a 5 s CPU profile and five Debugger.pause stacks.
#   2. The process's native thread stacks from /proc, when readable.
# Node's signal-triggered diagnostic report is not used: its signal is handled on the event loop, so it never fires for a stuck one.
#
# Usage: scripts/hang-stack/watch.sh [sbt command]   (default: kyo-podWasm/testOnly kyo.ContainerItTest)
# Env: STALE_SECS (120), POLL_SECS (10), RUN_HEAP_CAP (6G), KYO_HANG_DIR (under RUNNER_TEMP or /tmp), RUN_CMD (a shell
# command run in place of sbt).
set -uo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
repo="$(cd "$here/../.." && pwd)"
cmd="${1:-kyo-podWasm/testOnly kyo.ContainerItTest}"
stale="${STALE_SECS:-120}"
poll="${POLL_SECS:-10}"
dir="${KYO_HANG_DIR:-${RUNNER_TEMP:-/tmp}/kyo-hang}"
rm -rf "$dir"
mkdir -p "$dir"

log() { echo "[hang-stack $(date -u +%H:%M:%S)] $*"; }

sched_file="${RUNNER_TEMP:-/tmp}/kyo-sched-Wasm.status"
rm -f "$sched_file"
export KYO_SCHEDULER_TOPSTATUSFILE="$sched_file"
export KYO_SCHEDULER_TOPSTATUSFILEMS=5000
KYO_SCHED_FILE="$sched_file" bash "$repo/scripts/ci-monitor.sh" &
monitor_pid=$!

export KYO_HANG_DIR="$dir"
export NODE_OPTIONS="--require $here/heartbeat.cjs"

native_stacks() {
    local pid="$1" t
    [ -d "/proc/$pid/task" ] || return
    for t in /proc/"$pid"/task/*; do
        log "pid $pid thread $(basename "$t") $(cat "$t/comm" 2>/dev/null) state=$(awk '{print $3}' "$t/stat" 2>/dev/null)"
        sed 's/^/[hang-stack native]   /' "$t/stack" 2>/dev/null || true
    done
}

capture() {
    local pid="$1" age="$2"
    log "pid $pid: heartbeat ${age}s old; $(cut -d' ' -f2- "$dir/hb.$pid" | head -c 300)"
    ps -o pid,ppid,pcpu,rss,etime,args -p "$pid" 2>/dev/null | sed 's/^/[hang-stack ps] /'
    # capture.cjs bounds each of its own inspector calls, so it ends without an outer timeout (macOS has none).
    kill -USR1 "$pid" 2>/dev/null && NODE_OPTIONS= node "$here/capture.cjs" 9229 "pid=$pid"
    native_stacks "$pid"
    log "pid $pid: killing after capture"
    kill -9 "$pid" 2>/dev/null
}

if [ -n "${RUN_CMD:-}" ]; then
    log "running: $RUN_CMD (stale after ${stale}s, heartbeats in $dir)"
    bash -c "$RUN_CMD" &
else
    log "running: sbt -J-Xmx${RUN_HEAP_CAP:-6G} '$cmd' (stale after ${stale}s, heartbeats in $dir)"
    if command -v setsid >/dev/null 2>&1; then
        setsid sbt "-J-Xmx${RUN_HEAP_CAP:-6G}" "$cmd" &
    else
        sbt "-J-Xmx${RUN_HEAP_CAP:-6G}" "$cmd" &
    fi
fi
sbt_pid=$!

captured=""
while kill -0 "$sbt_pid" 2>/dev/null; do
    sleep "$poll"
    now=$(date +%s)
    for hb in "$dir"/hb.*; do
        [ -e "$hb" ] || continue
        pid="${hb##*.}"
        case " $captured " in *" $pid "*) continue ;; esac
        kill -0 "$pid" 2>/dev/null || continue
        ts=$(cut -d' ' -f1 "$hb")
        age=$((now - ts))
        if [ "$age" -gt "$stale" ]; then
            capture "$pid" "$age"
            captured="$captured $pid"
        fi
    done
done
wait "$sbt_pid"
rc=$?

kill "$monitor_pid" 2>/dev/null || true
wait "$monitor_pid" 2>/dev/null || true
# Zero heartbeats means NODE_OPTIONS never reached the test's Node process, so a clean exit proves nothing about stalls.
log "node processes that wrote a heartbeat: $(ls "$dir"/hb.* 2>/dev/null | wc -l | tr -d ' ')"
if [ -n "$captured" ]; then
    log "captured stalled pids:$captured (sbt exit $rc)"
    exit 1
fi
log "no stall; sbt exit $rc"
exit "$rc"

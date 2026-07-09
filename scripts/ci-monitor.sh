#!/usr/bin/env bash
set -uo pipefail
#
# ci-monitor.sh - resource monitor for CI and local dev runs. Runs until signalled (TERM/INT).
#
# Pure logging: one "[ci-mon] ..." line per interval to stdout (the job log). No files, no artifacts.
# Grep it back with `ci-logs.sh --metrics`. Two best-effort layers per line:
#
#   1) kyo scheduler snapshot - always, cross-platform. The compact line the scheduler's topStatusFile
#      sink writes to $KYO_SCHED_FILE (workers/blocked/stalled/load/exec/done/...).
#   2) OS headline - /proc on Linux (MemAvailable, SwapFree, disk, load, PSI memory some-avg10,
#      cumulative CPU steal ticks); vm_stat/sysctl on macOS (avail, swap, disk, load; PSI and steal are
#      Linux-only so they read `na`); nothing where neither is available.
#
# On stop it prints any kernel OOM verdict from dmesg (Linux). Disabled with CI_MON=0. Never disrupts
# or fails the run.
#
# Env: CI_MON (set 0 to disable), KYO_SCHED_FILE (scheduler snapshot path), CI_MON_INTERVAL (seconds,
#      default 20).

[ "${CI_MON:-1}" != "0" ] || exit 0

INTERVAL="${CI_MON_INTERVAL:-20}"
SCHED_FILE="${KYO_SCHED_FILE:-}"
OS="$(uname -s 2>/dev/null || echo unknown)"
if [ -r /proc/meminfo ]; then MON_SRC=proc
elif [ "$OS" = "Darwin" ]; then MON_SRC=darwin
else MON_SRC=none
fi

log() { echo "=== [ci-mon] $(date -u +%H:%M:%S) $* ==="; }

report() {
    [ "$MON_SRC" = "proc" ] || return 0
    local oom
    oom=$(sudo -n dmesg 2>/dev/null | grep -iE 'out of memory|oom-kill|killed process' | tail -20) || true
    [ -n "$oom" ] || oom=$(dmesg 2>/dev/null | grep -iE 'out of memory|oom-kill|killed process' | tail -20) || true
    if [ -n "$oom" ]; then log "kernel OOM detected:"; echo "$oom"; else log "no kernel OOM lines in dmesg"; fi
}
trap 'report; exit 0' TERM INT

os_headline() {
    case "$MON_SRC" in
        proc)
            local avail swap disk load psi steal
            avail=$(awk '/^MemAvailable:/{printf "%d", $2/1024}' /proc/meminfo 2>/dev/null)
            swap=$(awk '/^SwapFree:/{printf "%d", $2/1024}' /proc/meminfo 2>/dev/null)
            disk=$(df -Pm . 2>/dev/null | awk 'NR==2{print $4}')
            load=$(cut -d' ' -f1 /proc/loadavg 2>/dev/null)
            psi=$(awk -F'avg10=' '/^some/{split($2, a, " "); print a[1]}' /proc/pressure/memory 2>/dev/null)
            steal=$(awk '/^cpu /{print $9}' /proc/stat 2>/dev/null)
            printf 'availMB=%s swapFreeMB=%s diskFreeMB=%s load=%s psiMem10=%s stealTicks=%s' \
                "${avail:-?}" "${swap:-?}" "${disk:-?}" "${load:-?}" "${psi:-?}" "${steal:-?}"
            ;;
        darwin)
            local pagesize vm free inactive spec avail swap load disk
            pagesize=$(sysctl -n hw.pagesize 2>/dev/null || echo 4096)
            vm=$(vm_stat 2>/dev/null)
            free=$(printf '%s\n' "$vm" | awk '/Pages free/{gsub(/\./,"",$NF); print $NF}')
            inactive=$(printf '%s\n' "$vm" | awk '/Pages inactive/{gsub(/\./,"",$NF); print $NF}')
            spec=$(printf '%s\n' "$vm" | awk '/Pages speculative/{gsub(/\./,"",$NF); print $NF}')
            avail=$(( (${free:-0} + ${inactive:-0} + ${spec:-0}) * pagesize / 1048576 ))
            swap=$(sysctl -n vm.swapusage 2>/dev/null | awk '{for (i=1;i<=NF;i++) if ($i=="free") v=$(i+2); gsub(/[^0-9.]/,"",v); printf "%d", v}')
            load=$(sysctl -n vm.loadavg 2>/dev/null | awk '{print $2}')
            disk=$(df -Pm . 2>/dev/null | awk 'NR==2{print $4}')
            printf 'availMB=%s swapFreeMB=%s diskFreeMB=%s load=%s psiMem10=%s stealTicks=%s' \
                "${avail:-?}" "${swap:-?}" "${disk:-?}" "${load:-?}" "na" "na"
            ;;
        *) return 0 ;;
    esac
}

sched_snapshot() {
    { [ -n "$SCHED_FILE" ] && [ -r "$SCHED_FILE" ]; } && cat "$SCHED_FILE" 2>/dev/null
}

log "started interval=${INTERVAL}s src=$MON_SRC sched=${SCHED_FILE:-none}"
while true; do
    os="$(os_headline)"
    sc="$(sched_snapshot)"
    echo "[ci-mon $(date -u +%H:%M:%S)]${os:+ $os}${sc:+ $sc}"
    sleep "$INTERVAL"
done

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
# Disk watch: the per-interval line always carries diskFreeMB. When free disk first drops below
# CI_MON_DISK_WARN_MB (and again below CI_MON_DISK_CRIT_MB) the monitor prints a one-shot
# "[ci-mon-disk]" attribution dump (du of the workspace targets, the dependency caches, and /tmp), and
# below the crit threshold every line carries a DISK-CRIT marker. The healthy path stays cost-free: no
# du runs unless a threshold is crossed. Runner background: a Native row consumes tens of GB of link
# workspace; a runner that starts small exhausts its disk and dies WITHOUT uploading logs, which is
# unattributable. These dumps are the flight recorder for that failure mode.
#
# On stop it prints any kernel OOM verdict from dmesg (Linux), plus the disk attribution when a
# threshold was crossed during the run. Disabled with CI_MON=0. Never disrupts or fails the run,
# with one opt-in exception: when CI_MON_DISK_ABORT_MB is set and free disk drops below it, the
# monitor prints the attribution and TERM-kills its process group (the whole ci-test.sh tree runs in
# one group, so the build dies with the evidence in the log instead of the runner dying with none).
#
# Env: CI_MON (set 0 to disable), KYO_SCHED_FILE (scheduler snapshot path), CI_MON_INTERVAL (seconds,
#      default 20), CI_MON_DISK_WARN_MB (default 8192), CI_MON_DISK_CRIT_MB (default 2048),
#      CI_MON_DISK_ABORT_MB (default unset: never abort).

[ "${CI_MON:-1}" != "0" ] || exit 0

INTERVAL="${CI_MON_INTERVAL:-20}"
SCHED_FILE="${KYO_SCHED_FILE:-}"
DISK_WARN_MB="${CI_MON_DISK_WARN_MB:-8192}"
DISK_CRIT_MB="${CI_MON_DISK_CRIT_MB:-2048}"
DISK_ABORT_MB="${CI_MON_DISK_ABORT_MB:-}"
OS="$(uname -s 2>/dev/null || echo unknown)"
if [ -r /proc/meminfo ]; then MON_SRC=proc
elif [ "$OS" = "Darwin" ]; then MON_SRC=darwin
else MON_SRC=none
fi

log() { echo "=== [ci-mon] $(date -u +%H:%M:%S) $* ==="; }

# One-shot disk attribution: where the space went, biggest first. Runs only on a threshold crossing
# (or in the exit report after one), never on the healthy path: du over a large build tree costs
# real IO, and by the time this fires the run is already degraded.
disk_attribution() {
    log "disk attribution (free=${1:-?}MB):"
    {
        du -scm ./*/target ./*/*/target 2>/dev/null | sort -rn | head -12
        du -sm "$HOME/.cache/coursier" "$HOME/.sbt" "$HOME/.cache/kyo-browser" /tmp 2>/dev/null
    } | while IFS= read -r line; do echo "[ci-mon-disk] $line"; done
    echo "[ci-mon-disk] $(df -Pm . 2>/dev/null | awk 'NR==2{print "fs="$1" totalMB="$2" usedMB="$3" freeMB="$4}')"
}

disk_warned=0
disk_critted=0

report() {
    [ "$disk_warned" = "1" ] && disk_attribution "$(df -Pm . 2>/dev/null | awk 'NR==2{print $4}')"
    [ "$MON_SRC" = "proc" ] || return 0
    local oom
    oom=$(sudo -n dmesg 2>/dev/null | grep -iE 'out of memory|oom-kill|killed process' | tail -20) || true
    [ -n "$oom" ] || oom=$(dmesg 2>/dev/null | grep -iE 'out of memory|oom-kill|killed process' | tail -20) || true
    if [ -n "$oom" ]; then log "kernel OOM detected:"; echo "$oom"; else log "no kernel OOM lines in dmesg"; fi
}
trap 'report; exit 0' TERM INT

# Threshold ladder for one disk sample. WARN and CRIT entries each dump the attribution once;
# below-crit samples are additionally marked on the periodic line by the caller. The abort rung is
# opt-in (CI_MON_DISK_ABORT_MB): it prints the evidence and TERM-kills the process group, so the
# whole ci-test.sh tree (sbt, clang, this monitor) dies with the log intact rather than the runner
# dying with no log at all.
disk_check() {
    local free="$1"
    case "$free" in '' | *[!0-9]*) return 0 ;; esac
    if [ -n "$DISK_ABORT_MB" ] && [ "$free" -lt "$DISK_ABORT_MB" ]; then
        log "DISK-ABORT free=${free}MB < abort=${DISK_ABORT_MB}MB: killing the build to preserve the log"
        disk_attribution "$free"
        trap - TERM INT
        [ "$MON_SRC" = "proc" ] && report
        kill -TERM 0
        exit 1
    fi
    # Flags are set BEFORE the (slow) attribution dump: a TERM landing mid-dump still leaves the
    # exit report knowing a threshold was crossed, so it re-dumps the final state.
    if [ "$disk_critted" = "0" ] && [ "$free" -lt "$DISK_CRIT_MB" ]; then
        disk_critted=1
        disk_warned=1
        log "DISK-CRIT free=${free}MB < ${DISK_CRIT_MB}MB"
        disk_attribution "$free"
    elif [ "$disk_warned" = "0" ] && [ "$free" -lt "$DISK_WARN_MB" ]; then
        disk_warned=1
        log "DISK-WARN free=${free}MB < ${DISK_WARN_MB}MB"
        disk_attribution "$free"
    fi
    return 0
}

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

# Per-process attribution: total RSS and process count aggregated by command name, top 3 by RSS,
# e.g. "top=[java:11216M/2 clang:1834M/4 node:912M/1]". Host-level numbers alone cannot answer
# "whose memory is it" when a link or test phase overcommits the box. Best-effort: skipped where
# ps is unavailable (minimal containers).
proc_top() {
    command -v ps >/dev/null 2>&1 || return 0
    local rows
    rows=$(ps axo rss=,comm= 2>/dev/null | awk '
        {
            rss = $1; $1 = ""; cmd = substr($0, 2)
            sub(/.*\//, "", cmd); gsub(/ /, "_", cmd)
            if (cmd != "" && rss + 0 > 0) { r[cmd] += rss; n[cmd]++ }
        }
        END { for (c in r) printf "%d %s %d\n", r[c], c, n[c] }' \
        | sort -rn | head -3 \
        | awk '{ printf "%s%s:%dM/%d", sep, $2, $1 / 1024, $3; sep = " " }')
    [ -n "$rows" ] && printf 'top=[%s]' "$rows"
}

ncpu=$( (command -v nproc >/dev/null 2>&1 && nproc) || sysctl -n hw.ncpu 2>/dev/null || echo '?')
log "started interval=${INTERVAL}s src=$MON_SRC cores=$ncpu sched=${SCHED_FILE:-none} diskWarnMB=$DISK_WARN_MB diskCritMB=$DISK_CRIT_MB diskAbortMB=${DISK_ABORT_MB:-off}"
while true; do
    os="$(os_headline)"
    top="$(proc_top)"
    sc="$(sched_snapshot)"
    free_mb=$(df -Pm . 2>/dev/null | awk 'NR==2{print $4}')
    disk_check "$free_mb"
    crit=""
    [ "$disk_critted" = "1" ] && crit=" DISK-CRIT"
    echo "[ci-mon $(date -u +%H:%M:%S)]${os:+ $os}${top:+ $top}${sc:+ $sc}${crit}"
    sleep "$INTERVAL"
done

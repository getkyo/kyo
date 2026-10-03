#!/usr/bin/env bash
set -uo pipefail
#
# ci-monitor.sh - resource monitor for CI and local dev runs. Runs until signalled (TERM/INT).
#
# Pure logging: one "[ci-mon] ..." line per interval to stdout (the job log). No files, no artifacts.
# Grep it back with `ci-logs.sh --metrics`. Best-effort layers per line:
#
#   1) kyo scheduler snapshot - always, cross-platform. The compact line the scheduler's topStatusFile
#      sink writes to $KYO_SCHED_FILE (workers/blocked/stalled/load/exec/done/...).
#   2) OS headline - /proc on Linux (MemAvailable, SwapFree, disk, load, PSI memory some-avg10,
#      cumulative CPU steal ticks); vm_stat/sysctl on macOS (avail, swap, disk, load; PSI and steal are
#      Linux-only so they read `na`); nothing where neither is available.
#   3) Fork-pressure headline - live task (thread) count against the RLIMIT_NPROC ceiling (tasks=N/M),
#      process count, and conmon count. Diagnoses in-container `sh: Cannot fork` (EAGAIN) and the
#      reap/cleanup backlog a container-heavy suite exhausts a rootless runner with. Task count is
#      Linux-only (macOS reads `na`).
#   4) Socket-pressure headline - live TCP entries, the TIME_WAIT subset, and the dynamic port range
#      (tcp/timeWait/ephemeral). Diagnoses a WSAENOBUFS (error 10055) connect failure, which no memory
#      or CPU field predicts. Windows-only: the other poles have ranges too large to exhaust.
#   5) proc_top - top 3 commands by aggregate RSS, for "whose memory is it" when the box overcommits.
#   6) Runner liveness - the GitHub runner's own processes with their state (GitHub Actions, not Windows).
#
# Kernel log: on Linux, kernel messages logged after the monitor starts stream into the log as
# "[ci-mon-kern]" lines the moment the kernel emits them, filtered to the ones that explain a dying
# machine (hung tasks, lockups, OOM kills, io_uring, BUG/WARNING/Oops and their call traces). A runner
# that loses contact with GitHub leaves a log that just stops, and the kernel's last word comes
# seconds before; a per-interval sample would miss it.
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

kern_pid=""
kern_reader=""
kern_sudo=""

report() {
    [ -n "$kern_pid" ] && kill "$kern_pid" 2>/dev/null
    [ -n "$kern_reader" ] && $kern_sudo kill "$kern_reader" 2>/dev/null
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
            # Windows reaches this branch through Git Bash, whose emulated /proc/meminfo publishes MemFree but
            # not MemAvailable. Without the fallback every Windows sample reports `?` and a run that dies under
            # memory pressure carries no memory evidence at all.
            [ -n "$avail" ] || avail=$(awk '/^MemFree:/{printf "%d", $2/1024}' /proc/meminfo 2>/dev/null)
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

# The snapshot's ts= (epoch millis) is when its writer last ran; age= is how long ago that was. On JS the
# writer is a timer on the process's single event loop, so a slice that never returns stops the rewrites
# and the age keeps growing: the only outside sign of a blocked loop, whose own timeouts cannot fire.
sched_snapshot() {
    { [ -n "$SCHED_FILE" ] && [ -r "$SCHED_FILE" ]; } || return 0
    local line ts
    line=$(cat "$SCHED_FILE" 2>/dev/null) || return 0
    ts=$(printf '%s' "$line" | sed -n 's/.*ts=\([0-9][0-9]*\).*/\1/p')
    if [ -n "$ts" ]; then
        printf '%s age=%ss' "$line" "$(( $(date +%s) - ts / 1000 ))"
    else
        printf '%s' "$line"
    fi
}

# Per-process attribution: total RSS and process count aggregated by command name, top 3 by RSS,
# e.g. "top=[java:11216M/2 clang:1834M/4 node:912M/1]". Host-level numbers alone cannot answer
# "whose memory is it" when a link or test phase overcommits the box. Best-effort: skipped where
# ps is unavailable (minimal containers).
proc_top() {
    local rows
    # On Windows, `ps` is the MSYS build and reports its own accounting rather than the Windows working set,
    # so a JVM holding gigabytes prints as single-digit MB and the attribution is worse than absent. `tasklist`
    # reports the real figure. Anything unexpected in its output yields no rows, which prints nothing at all.
    case "$OS" in
        MINGW* | MSYS* | CYGWIN*)
            command -v tasklist >/dev/null 2>&1 || return 0
            rows=$(MSYS2_ARG_CONV_EXCL='*' tasklist /FO CSV /NH 2>/dev/null | tr -d '\r' | awk -F'","' '
                {
                    # Windows image names carry spaces ("Memory Compression", "System Idle Process"), which the
                    # field-split output below would read as separate columns, so they join like the posix branch.
                    name = $1; gsub(/"/, "", name); sub(/\.[Ee][Xx][Ee]$/, "", name); gsub(/ /, "_", name)
                    mem = $5; gsub(/[^0-9]/, "", mem)
                    if (name != "" && mem + 0 > 0) { r[name] += mem; n[name]++ }
                }
                END { for (c in r) printf "%d %s %d\n", r[c], c, n[c] }' \
                | sort -rn | head -3 \
                | awk '{ printf "%s%s:%dM/%d", sep, $2, $1 / 1024, $3; sep = " " }')
            [ -n "$rows" ] && printf 'top=[%s]' "$rows"
            return 0
            ;;
    esac
    command -v ps >/dev/null 2>&1 || return 0
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

# Fork-pressure headline: the numbers that explain an in-container `sh: Cannot fork` (EAGAIN) or a host
# fork failure. `tasks=N/M` is the user's live task (thread) count N against the RLIMIT_NPROC ceiling M
# (`ulimit -u`) - a rising N nearing M is the fork wall, whether it is a genuine peak or a thread leak.
# `conmon=C` is the container/exec monitor count (podman leaves ~2 conmon per live exec/container): C
# climbing while the container count is flat is a reap or cleanup backlog, the shape a container-heavy
# suite exhausts a runner with. Best-effort: three cheap `ps`/`pgrep` summaries per interval; degrades
# to `na` where a field cannot be sampled (macOS has no cross-process thread count; minimal containers
# lack ps).
tasks_headline() {
    command -v ps >/dev/null 2>&1 || return 0
    local tasks procs nprocmax conmon
    nprocmax=$(ulimit -u 2>/dev/null || echo '?')
    procs=$(ps -e -o pid= 2>/dev/null | wc -l | tr -d ' ')
    conmon=$( { pgrep -x conmon 2>/dev/null || true; } | wc -l | tr -d ' ')
    case "$MON_SRC" in
        proc)
            tasks=$(ps -eL -o pid= 2>/dev/null | wc -l | tr -d ' ')
            printf 'tasks=%s/%s procs=%s conmon=%s' "${tasks:-?}" "$nprocmax" "${procs:-?}" "${conmon:-?}"
            ;;
        *)
            printf 'tasks=na/%s procs=%s conmon=%s' "$nprocmax" "${procs:-?}" "${conmon:-?}"
            ;;
    esac
}

# Socket-pressure headline: the numbers that explain a WSAENOBUFS (Windows error 10055) or an EADDRNOTAVAIL.
# `tcp=N` is the live TCP entry count and `timeWait=T` the subset sitting in TIME_WAIT, against `ephemeral=R`,
# the size of the dynamic port range. A browser suite that opens a connection per test leaf walks T up toward R
# (Windows holds TIME_WAIT for 120s since Windows 8; the NT-era default was 240s), and past it a connect fails
# with no memory or CPU pressure to show for it, which is invisible in every other field here. Windows only: it
# is the pole where the range is small enough to exhaust and the only one that has produced the failure.
# Best-effort; a field that cannot be sampled prints `?`.
#
# Both address families are counted. A JVM on Windows opens dual-stack sockets and localhost resolves to ::1, so
# those rows appear only under `-p tcpv6`: counting IPv4 alone would under-report the churn this exists to show,
# and the ranges are configured separately.
sockets_headline() {
    case "$OS" in
        MINGW* | MSYS* | CYGWIN*) ;;
        *) return 0 ;;
    esac
    command -v netstat >/dev/null 2>&1 || return 0
    local counts tcp timewait v4range v6range range
    # Emit nothing when no TCP row was seen, so a netstat that errored or printed nothing reads as `?`
    # below rather than as `tcp=0 timeWait=0`. A zero is worse than a blank here: it says the sockets are
    # fine. A live Windows box always has at least one LISTENING row, so no-rows means no sample.
    counts=$(
        {
            MSYS2_ARG_CONV_EXCL='*' netstat -ano -p tcp 2>/dev/null
            MSYS2_ARG_CONV_EXCL='*' netstat -ano -p tcpv6 2>/dev/null
        } | tr -d '\r' |
            awk '/^ +TCP/ { total++; if ($4 == "TIME_WAIT") tw++ } END { if (total) printf "%d %d", total, tw }'
    )
    tcp=${counts%% *}
    timewait=${counts##* }
    v4range=$(MSYS2_ARG_CONV_EXCL='*' netsh int ipv4 show dynamicport tcp 2>/dev/null | tr -d '\r' |
        awk '/Number of Ports/ { print $NF }')
    v6range=$(MSYS2_ARG_CONV_EXCL='*' netsh int ipv6 show dynamicport tcp 2>/dev/null | tr -d '\r' |
        awk '/Number of Ports/ { print $NF }')
    # One figure when the two families share a range, which is the default, and both when they diverge.
    if [ -n "$v4range" ] && [ "$v4range" = "$v6range" ]; then range="$v4range"
    elif [ -n "$v4range" ] || [ -n "$v6range" ]; then range="${v4range:-?}/${v6range:-?}"
    fi
    printf 'tcp=%s timeWait=%s ephemeral=%s' "${tcp:-?}" "${timewait:-?}" "${range:-?}"
}

# Commit-pressure headline: what a WSAENOBUFS (Windows error 10055) reads as when the socket headline is healthy.
# AFD takes a socket's buffers from non-paged pool under the system commit limit, so a connect fails with 10055
# when either runs out while `tcp` and `timeWait` sit far below the port range: measured on windows-x64 runners
# with a few hundred TCP entries against a 16384-port range and the pagefile down to single-digit megabytes.
# `commitMB` is the committed charge, `commitLimitMB` the limit (physical memory plus pagefile), `nonpagedMB` the
# pool. Windows only, through `typeperf`, the counter reader every runner image ships. Best-effort; a field that
# cannot be sampled prints `?`.
commit_headline() {
    case "$OS" in
        MINGW* | MSYS* | CYGWIN*) ;;
        *) return 0 ;;
    esac
    command -v typeperf >/dev/null 2>&1 || return 0
    local sample committed limit pool
    # typeperf prints a CSV header naming the counters, then one row per sample: a quoted timestamp and the
    # values in bytes. The header starts with a quote too, so the row that counts is the last one whose values
    # are numeric; a typeperf that printed only its header reads as unsampled rather than as zero.
    sample=$(MSYS2_ARG_CONV_EXCL='*' typeperf '\Memory\Committed Bytes' '\Memory\Commit Limit' '\Memory\Pool Nonpaged Bytes' -sc 1 2>/dev/null |
        tr -d '\r' | awk -F'","' '/^"/ && NF == 4 && $2 ~ /^[0-9.]+$/ { row = $0 } END { if (row) print row }')
    if [ -z "$sample" ]; then
        printf 'commitMB=? commitLimitMB=? nonpagedMB=?'
        return 0
    fi
    committed=$(printf '%s' "$sample" | awk -F'","' '{ printf "%d", $2 / 1048576 }')
    limit=$(printf '%s' "$sample" | awk -F'","' '{ printf "%d", $3 / 1048576 }')
    pool=$(printf '%s' "$sample" | awk -F'","' '{ gsub(/"/, "", $4); printf "%d", $4 / 1048576 }')
    printf 'commitMB=%s commitLimitMB=%s nonpagedMB=%s' "${committed:-?}" "${limit:-?}" "${pool:-?}"
}

# Runner liveness: the GitHub runner's processes with pid and state, e.g. "runner=[Runner.Listener:812/Ssl Runner.Worker:2290/Sl]".
# When the runner loses contact with GitHub the log just stops, and the last samples are the only record of whether these were
# alive, gone (`runner=[none]`), or in uninterruptible sleep (state D) on the kernel. Not on Windows, where MSYS `ps` does not
# list native processes.
runner_headline() {
    [ "${GITHUB_ACTIONS:-}" = "true" ] || return 0
    case "$OS" in
        MINGW* | MSYS* | CYGWIN*) return 0 ;;
    esac
    command -v ps >/dev/null 2>&1 || return 0
    local rows
    rows=$(ps -eo pid=,stat=,comm= 2>/dev/null | awk '
        { cmd = $3; sub(/.*\//, "", cmd) }
        cmd == "Runner.Listener" || cmd == "Runner.Worker" { printf "%s%s:%s/%s", sep, cmd, $1, $2; sep = " " }')
    printf 'runner=[%s]' "${rows:-none}"
}

# Kernel log filter: keeps the lines that explain a dying machine, plus the 40 lines after each, which carry its call trace, and
# drops the rest (on a runner, mostly container network churn). Capped so a flood cannot bury the build output. `exec` makes the
# awk itself the process the monitor kills on stop. The match is on a lowercased copy because mawk, Ubuntu's awk, has no
# case-insensitive mode.
kern_filter() {
    exec awk '
        {
            l = tolower($0)
            hit = l ~ /blocked for more than|hung_task|soft lockup|hard lockup|rcu.*stall|out of memory|oom-kill|killed process|io_uring|bug:|warning: cpu|oops|kernel panic|call trace|segfault|general protection/
            if (hit) after = 40
            else if (after > 0) after--
            else next
            if (shown < 500) { print "[ci-mon-kern] " $0; shown++ }
            else if (!capped) { print "[ci-mon-kern] 500-line cap reached, later kernel lines dropped"; capped = 1 }
            fflush()
        }'
}

# Starts the kernel log stream. `--follow-new` (util-linux 2.36) prints only what is logged from now on, so nothing re-reads the
# buffer. Where the kernel restricts its log (Ubuntu runners), the reader runs under passwordless sudo. The stop kills the reader
# as well as the filter: a reader left alone waits for its next write to fail, and a quiet kernel never gives it one.
kern_follow() {
    [ "$MON_SRC" = "proc" ] || return 0
    command -v dmesg >/dev/null 2>&1 || return 0
    if ! dmesg --help 2>&1 | grep -q -- '--follow-new'; then
        log "kernel log off: dmesg has no --follow-new"
        return 0
    fi
    if ! dmesg >/dev/null 2>&1; then
        if sudo -n dmesg >/dev/null 2>&1; then
            kern_sudo="sudo -n"
        else
            log "kernel log off: not readable"
            return 0
        fi
    fi
    $kern_sudo dmesg --follow-new --time-format iso 2>/dev/null | kern_filter &
    kern_pid=$!
    # The pipeline's first process: dmesg, or the sudo that relays the stop signal to it.
    kern_reader=$(jobs -p %%)
    log "kernel log on${kern_sudo:+ (sudo)}"
}

ncpu=$( (command -v nproc >/dev/null 2>&1 && nproc) || sysctl -n hw.ncpu 2>/dev/null || echo '?')
log "started interval=${INTERVAL}s src=$MON_SRC cores=$ncpu sched=${SCHED_FILE:-none} diskWarnMB=$DISK_WARN_MB diskCritMB=$DISK_CRIT_MB diskAbortMB=${DISK_ABORT_MB:-off}"
kern_follow
while true; do
    os="$(os_headline)"
    tasks="$(tasks_headline)"
    sock="$(sockets_headline)"
    commit="$(commit_headline)"
    top="$(proc_top)"
    runner="$(runner_headline)"
    sc="$(sched_snapshot)"
    free_mb=$(df -Pm . 2>/dev/null | awk 'NR==2{print $4}')
    disk_check "$free_mb"
    crit=""
    [ "$disk_critted" = "1" ] && crit=" DISK-CRIT"
    echo "[ci-mon $(date -u +%H:%M:%S)]${os:+ $os}${tasks:+ $tasks}${sock:+ $sock}${commit:+ $commit}${top:+ $top}${runner:+ $runner}${sc:+ $sc}${crit}"
    sleep "$INTERVAL"
done

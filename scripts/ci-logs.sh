#!/usr/bin/env bash
set -uo pipefail
#
# ci-logs.sh - inspect kyo CI logs: pick WHICH jobs, then choose WHAT to show.
#
# The primitive is "fetch and clean one job's log" (ANSI stripped, timestamps
# removed). Everything else is two orthogonal choices:
#
#   SELECTOR (which jobs)            VIEW (what to show per job)
#   ----------------------          --------------------------------------
#   run    <id|url>                  --failures  (default) reliable failures
#   job    <id|url>                  --grep <re>           lines matching re
#   runs   <branch> [n]             --full                entire cleaned log
#   running [branch]                --tail <n>            last n lines (n=40)
#   pr     <number>                  --steps               per-job step status
#                                    --totals              per-leg suite totals, every job
#   open-prs                         --metrics             ci-monitor.sh resource
#                                                          lines (implies all-jobs)
#                                    --all-jobs            apply to ALL jobs,
#                                                          not just failed ones
#
# Examples:
#   ci-logs.sh run 281...948                       # reliable failures of a run
#   ci-logs.sh runs main 10                        # ... across last 10 main runs
#   ci-logs.sh pr 1707                             # ... for a PR's failing checks
#   ci-logs.sh job 836...195 --full                # dump one job's whole log
#   ci-logs.sh run 281...948 --grep 'OutOfMemory'  # grep failed jobs of a run
#   ci-logs.sh run 281...948 --grep Timeout --all-jobs   # grep every job
#   ci-logs.sh run 281...948 --steps               # which step failed, timings
#   ci-logs.sh run 281...948 --totals              # read a GREEN run: per-leg suite totals and
#                                                  # whether the two arch legs of each target agree
#
# WHY a dedicated "failures" view: both test frameworks here (ScalaTest and
# kyo-test) print " *** FAILED ***" on every failing leaf, and kyo-test's runner
# self-tests run CHILD suites that fail ON PURPOSE and echo their whole report.
# So grepping for "*** FAILED ***" is unreliable. The --failures view reads only
# sbt's authoritative "[error]" summary (TestsFailedException / "Failed tests:" /
# "Failed: Total N, Failed M" / compile errors / the Native "Tests:" line),
# which the in-process self-test children never reach. Use --grep/--full when you
# want the raw picture instead.
#
# Reads per-JOB logs via the REST jobs/<id>/logs endpoint, so it works on a
# running run too (a job's log is available as soon as that job finishes).
#
# Repo: -R owner/name (or REPO env, default gh-detected) targets a fork's runs, not just the
# working directory's repo. Env: CI_WORKFLOW (default ci.yml).

REPO="${REPO:-$(gh repo view --json nameWithOwner -q .nameWithOwner 2>/dev/null)}"
CI_WORKFLOW="${CI_WORKFLOW:-ci.yml}"

# ---- view options (parsed out of the arg list) ----
VIEW=failures; GREP_RE=""; TAIL_N=40; ALL_JOBS=0
POS=()
while [ $# -gt 0 ]; do
    case "$1" in
        -R|--repo)  REPO="${2:?-R needs owner/name}"; shift ;;
        --failures) VIEW=failures ;;
        --grep)     VIEW=grep; GREP_RE="${2:?--grep needs a pattern}"; shift ;;
        --metrics)  VIEW=grep; GREP_RE='\[ci-mon\]'; ALL_JOBS=1 ;;
        --full|--dump|--raw) VIEW=full ;;
        --tail)     VIEW=tail; TAIL_N="${2:?--tail needs a count}"; shift ;;
        --steps)    VIEW=steps ;;
        --all-jobs) ALL_JOBS=1 ;;
        --totals)   VIEW=totals; ALL_JOBS=1 ;;
        *)          POS+=("$1") ;;
    esac
    shift
done
set -- "${POS[@]:-}"

# Every gh subcommand (run view/list, pr view/list) honors GH_REPO, unlike the bare REPO the gh api calls
# below already use; export it so a fork's runs are reachable via -R owner/name, not only the working
# directory's repo.
[ -n "${REPO:-}" ] || { echo "error: set -R owner/name or REPO=owner/name" >&2; exit 2; }
export GH_REPO="$REPO"

# Strip ANSI, CR, and the leading ISO-8601 job-log timestamp prefix.
# The BOM strip comes first and is not optional: the log API emits a UTF-8 byte order mark at chunk
# boundaries, mid-file, in front of a timestamp. Every pattern below anchors on the start of the line, so a
# line that keeps its timestamp because the BOM blocked the strip matches nothing, and a genuine failure on
# such a line is reported as no failure at all.
clean() { perl -pe 's/^\x{EF}\x{BB}\x{BF}//; s/\e\[[0-9;]*m//g; s/\r$//; s/^\d{4}-\d{2}-\d{2}T[\d:.]+Z[ \t]*//'; }

# --failures: print only sbt's authoritative real-failure lines. Exit 9 if none.
extract() {
    awk '
        /^\[error\] \(.* \/ Test \/ test\) / && /sbt\.TestsFailedException/ {
            l=$0; sub(/^\[error\] /,"",l); print "  task-failed: " l; found=1; next }
        /^\[error\] (Failed|Error): Total [0-9]+, Failed [0-9]+/ {
            l=$0; sub(/^\[error\] /,"",l); print "  summary:     " l; found=1; next }
        /^\[error\].*\.scala:[0-9]+:[0-9]+/ { print "  compile:     " $0; found=1; next }
        /^\[error\].*[Cc]ompilation failed/ { print "  compile:     " $0; found=1; next }
        /Tests: succeeded [0-9]+, failed [1-9]/ { print "  native:      " $0; found=1; next }
        /^\[error\] Failed tests:/ { cap=1; next }
        cap==1 {
            if ($0 ~ /^\[error\][ \t]+[A-Za-z][A-Za-z0-9_.]*$/) {
                n=$0; sub(/^\[error\][ \t]+/,"",n); print "  test-class:  " n; found=1; next }
            else cap=0 }
        END { if (!found) exit 9 }
    '
}

# Fallback for a failed job with no parsed test/compile failure: infra cause.
infra_tail() {
    grep -aE '##\[error\]|Cannot allocate|OutOfMemory|Killed|No space|Connection|timed out|Timeout|exit code|received a shutdown' \
        | grep -avE 'WARNING|sun\.misc|jna|launcher|enable-native' \
        | tail -6 | sed 's/^/  infra:       /'
}

# Per-suite totals for one job's cleaned log.
#
# The mandate this drive works under requires a green run to be read on its suite totals rather than its
# conclusion field, and doing that by hand is how a UTF-8 BOM once turned one leg's count into a phantom
# arch discrepancy. The match is deliberately NOT anchored to the start of the line, so a stray prefix
# costs nothing even if the cleaner ever misses one.
totals_of() {
    perl -ne '
        if (/(?:^|\s)--- \S+: (\d+) passed, (\d+) failed(?:, (\d+) cancelled)?/) {
            $n++; $p += $1; $f += $2; $c += (defined $3 ? $3 : 0);
        }
        END { printf("  suites=%d passed=%d failed=%d cancelled=%d passed+cancelled=%d\n",
                     $n, $p, $f, $c, $p + $c); }
    '
}

# Apply the selected view to a job's cleaned log (read from a variable).
show_log() {
    local log="$1"
    [ -z "$log" ] && { echo "  (no log available yet)"; return; }
    case "$VIEW" in
        failures) printf '%s\n' "$log" | extract || printf '%s\n' "$log" | infra_tail ;;
        grep)     printf '%s\n' "$log" | grep -aiE "$GREP_RE" | sed 's/^/  /' || echo "  (no matches)" ;;
        full)     printf '%s\n' "$log" ;;
        tail)     printf '%s\n' "$log" | tail -n "$TAIL_N" | sed 's/^/  /' ;;
        totals)
            local tline
            tline="$(printf '%s\n' "$log" | totals_of)"
            printf '%s\n' "$tline"
            if [ -n "${TOTALS_FILE:-}" ] && [ -n "${TOTALS_JOB:-}" ]; then
                # "build (linux-x64) / build (Native)" reduces to "linux-x64/Native" so the pair is groupable.
                printf '%s %s\n' \
                    "$(printf '%s' "$TOTALS_JOB" | sed -E 's/build \(([^)]*)\) \/ build \(([^)]*)\)/\1\/\2/')" \
                    "$(printf '%s' "$tline" | sed 's/^ *//')" >> "$TOTALS_FILE"
            fi
            ;;
    esac
}

inspect_job() {
    local jobid="$1" raw
    raw="$(gh api "repos/$REPO/actions/jobs/$jobid/logs" 2>/dev/null)"; local rc=$?
    # A cancelled/killed step or an expired run leaves no log blob (HTTP 404 with
    # an <Error> XML body). Report it instead of silently showing nothing.
    if [ $rc -ne 0 ] || [ -z "$raw" ] || printf '%s' "$raw" | head -c 200 | grep -q '<Error>'; then
        echo "  (log unavailable: job cancelled/expired or blob not found)"
        return
    fi
    show_log "$(printf '%s' "$raw" | clean)"
}

# The --steps view is per-run metadata, not log content.
run_steps() {
    gh run view "$1" --json jobs --jq '
        .jobs[] | "JOB: \(.name)  [\(.status)/\(.conclusion // "running")]",
        (.steps[] | select(.conclusion=="failure" or .status=="in_progress")
            | "    step \(.number) \(.name): \(.status)/\(.conclusion // "running")")'
}

# The two arch legs of one target must agree on suites and on passed+cancelled. Their CANCELLED splits
# differ legitimately (a leg that cannot host a browser cancels those leaves instead of running them), so
# neither figure alone is comparable; the sum is. A disagreement means one arch silently skipped work that
# the other ran, which no conclusion field ever reports.
arch_check() {
    local f="$1"
    [ -s "$f" ] || return 0
    echo "------------------------------------------------------------------"
    echo "ARCH AGREEMENT (linux-x64 vs linux-arm64, per target)"
    perl -ne '
        next unless /^(\S+)\s+suites=(\d+).*passed\+cancelled=(\d+)/;
        my ($job, $su, $pc) = ($1, $2, $3);
        next unless $job =~ /^(linux-x64|linux-arm64)\/(\S+)$/;
        $seen{$2}{$1} = "$su/$pc";
        END {
            my $bad = 0;
            for my $t (sort keys %seen) {
                my $x = $seen{$t}{"linux-x64"}; my $a = $seen{$t}{"linux-arm64"};
                next unless defined $x && defined $a;
                my $ok = ($x eq $a);
                $bad++ unless $ok;
                printf("  %-8s x64=%-14s arm64=%-14s %s\n", $t, $x, $a, $ok ? "AGREE" : "DISAGREE");
            }
            print "  (no arch pair present in this run)\n" unless keys %seen;
            print "  a DISAGREE means one arch skipped suites the other ran; read that leg before trusting the run\n" if $bad;
        }
    ' "$f"
}

inspect_run() {
    local runid="$1" meta
    meta="$(gh run view "$runid" --json status,conclusion,displayTitle,headSha,workflowName 2>/dev/null)"
    [ -n "$meta" ] || { echo "run $runid: not found" >&2; return; }
    echo "=================================================================="
    printf 'RUN %s  [%s]  %s  status=%s conclusion=%s\n' "$runid" \
        "$(jq -r .workflowName <<<"$meta")" "$(jq -r '.headSha[0:8]' <<<"$meta")" \
        "$(jq -r .status <<<"$meta")" "$(jq -r '.conclusion // "<running>"' <<<"$meta")"
    echo "  $(jq -r .displayTitle <<<"$meta")"

    if [ "$VIEW" = steps ]; then run_steps "$runid" | sed 's/^/  /'; return; fi

    local sel='select(.conclusion=="failure")'
    [ "$ALL_JOBS" = 1 ] && sel='select(.name)'
    local jobs
    jobs=$(gh run view "$runid" --json jobs --jq ".jobs[] | $sel | \"\(.databaseId)\t\(.name)\"" 2>/dev/null)
    local running
    running=$(gh run view "$runid" --json jobs \
        --jq '.jobs[] | select(.status=="in_progress" or .status=="queued") | .name' 2>/dev/null)

    if [ "$VIEW" = totals ]; then
        TOTALS_FILE="$(mktemp)"; export TOTALS_FILE
        trap 'rm -f "$TOTALS_FILE"' RETURN
    fi

    if [ -z "$jobs" ] && [ -z "$running" ]; then echo "  no failed jobs."; return; fi
    if [ -n "$jobs" ]; then
        while IFS=$'\t' read -r jid jname; do
            [ -z "$jid" ] && continue
            echo "------------------------------------------------------------------"
            echo "JOB: $jname  (db=$jid)"
            TOTALS_JOB="$jname" inspect_job "$jid"
        done <<< "$jobs"
    fi
    [ -n "$running" ] && { echo "------------------------------------------------------------------";
        echo "STILL RUNNING:"; printf '%s\n' "$running" | sed 's/^/  ... /'; }
    # Only meaningful once every leg has finished: legs mid-run are simply at different points, and comparing
    # them reports a disagreement that says nothing about what either leg will end up running.
    if [ "$VIEW" = totals ]; then
        if [ "$(jq -r .status <<<"$meta")" = completed ]; then
            arch_check "$TOTALS_FILE"
        else
            echo "------------------------------------------------------------------"
            echo "ARCH AGREEMENT: skipped, run still in progress (legs are at different points)"
        fi
    fi
    return 0
}

as_id() { printf '%s' "$1" | grep -oE '[0-9]+' | tail -1; }

cmd="${1:-}"; shift || true
case "$cmd" in
    run)  inspect_run "$(as_id "${1:?run id or url required}")" ;;
    job)  jid="$(as_id "${1:?job id or url required}")"; echo "JOB: $jid"; inspect_job "$jid" ;;
    runs)
        branch="${1:?branch required}"; n="${2:-10}"
        for id in $(gh run list --branch "$branch" --workflow "$CI_WORKFLOW" --limit "$n" \
                    --json databaseId --jq '.[].databaseId'); do inspect_run "$id"; done ;;
    running)
        branch="${1:-}"
        f='.[] | select(.status=="in_progress" or .status=="queued") | .databaseId'
        if [ -n "$branch" ]; then
            ids=$(gh run list --branch "$branch" --workflow "$CI_WORKFLOW" --limit 30 --json databaseId,status --jq "$f")
        else
            ids=$(gh run list --workflow "$CI_WORKFLOW" --limit 30 --json databaseId,status --jq "$f")
        fi
        [ -z "$ids" ] && echo "no in-progress ci runs."
        for id in $ids; do inspect_run "$id"; done ;;
    pr)
        num="${1:?pr number required}"
        echo "=================================================================="; echo "PR #$num"
        gh pr view "$num" --json statusCheckRollup \
            --jq '.statusCheckRollup[] | select((.conclusion // .state)=="FAILURE") | "\(.name)\t\(.detailsUrl // "")"' \
        | while IFS=$'\t' read -r cname curl; do
            jid=$(printf '%s' "$curl" | grep -oE '/job/[0-9]+' | grep -oE '[0-9]+' || true)
            echo "------------------------------------------------------------------"; echo "CHECK: $cname"
            if [ -n "$jid" ]; then inspect_job "$jid"; else echo "  (non-Actions check; $curl)"; fi
        done ;;
    open-prs)
        for num in $(gh pr list --state open --limit 100 --json number --jq '.[].number'); do
            "$0" pr "$num" ${GREP_RE:+--grep "$GREP_RE"} $([ "$VIEW" = full ] && echo --full)
        done ;;
    *)  sed -n '2,46p' "$0" | sed 's/^#\{0,1\} \{0,1\}//'; exit 1 ;;
esac

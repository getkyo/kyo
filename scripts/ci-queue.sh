#!/usr/bin/env bash
set -uo pipefail
#
# ci-queue.sh - inspect the kyo CI queue: what holds runners, what waits, and why.
#
# Companion to ci-logs.sh. That one answers "why did this job fail"; this one answers
# "why has nothing started yet".
#
#   VIEW                     SHOWS
#   ----------------------   --------------------------------------------------------
#   --active   (default)     every queued/in_progress run, jobs grouped by runner pool
#   --waits [n]              queue wait per pool over the last n runs (p50/p90/max)
#   --groups                 the concurrency group EXPRESSION behind each active run
#
# Examples:
#   ci-queue.sh                        # what is running right now, by pool
#   ci-queue.sh --waits 30             # where the queue time actually goes
#   ci-queue.sh --groups               # why did run B not cancel run A
#
# WHY pools rather than a total: GitHub bills a single concurrency cap, but runners are
# provisioned per image, so a job waits on its own pool's capacity and not on the plan
# limit. A total of "18 jobs running" hides the fact that 12 sit on one image with a p90
# wait of over an hour while another idles. Every view here splits by pool for that
# reason, reading each job's `labels` rather than guessing from job names.
#
# WHY --groups exists: GitHub matches concurrency by the EVALUATED group string, taken
# from the workflow file at the ref being run. Two runs of the same workflow therefore
# fail to cancel each other whenever the group expression itself changed between their
# commits, which looks identical to a broken concurrency config. The API never exposes
# the evaluated value, so this reads the expression from each run's own commit; two rows
# differing for one workflow is the explanation.
#
# Env: REPO (owner/repo, default gh-detected).

REPO="${REPO:-$(gh repo view --json nameWithOwner -q .nameWithOwner 2>/dev/null)}"
[ -n "${REPO:-}" ] || { echo "error: set REPO=owner/name" >&2; exit 2; }

VIEW=active
WAITS_N=20
while [ $# -gt 0 ]; do
    case "$1" in
        --active) VIEW=active ;;
        --waits)  VIEW=waits
                  case "${2:-}" in [0-9]*) WAITS_N="$2"; shift ;; esac ;;
        --groups) VIEW=groups ;;
        -h|--help) sed -n '3,32p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *) echo "ci-queue.sh: unknown argument '$1'" >&2; exit 2 ;;
    esac
    shift
done

# Runner image -> pool. Capacity is provisioned per image, so this is the unit that
# actually queues. Unrecognized labels fall through to the raw label rather than being
# bucketed as linux, so a self-hosted or new image stays visible instead of silently
# inflating a pool it does not belong to.
pool_of() {
    case "$1" in
        *windows*)          echo "windows" ;;
        *macos*)            echo "macos" ;;
        *ubuntu*arm*|*arm*) echo "linux-arm64" ;;
        *ubuntu*|*linux*)   echo "linux-x64" ;;
        *)                  echo "$1" ;;
    esac
}

active_runs() {
    gh run list --limit 50 \
        --json databaseId,name,status,event,headBranch,headSha,createdAt,workflowName \
        --jq '.[] | select(.status=="queued" or .status=="in_progress")
              | [.databaseId, .status, .workflowName, .headBranch, .headSha, .createdAt] | @tsv'
}

# Null and empty fields become "-" rather than an empty column: a queued job has no
# started_at and a skipped one may carry no labels, and because tab is whitespace, bash
# collapses consecutive tabs and silently shifts every later field left.
jobs_of() {
    gh api --paginate "repos/$REPO/actions/runs/$1/jobs?per_page=100" \
        --jq '.jobs[]
              | [.status, (.labels|join(",")), .created_at, .started_at, .completed_at, .name]
              | map(if . == null or . == "" then "-" else . end)
              | @tsv' 2>/dev/null
}

# Peak simultaneous jobs on one pool, by sweeping start/end events. This is the number that
# distinguishes "the plan's concurrency cap is binding" from "this image's fleet is binding":
# observing more concurrent jobs than the documented account-wide cap proves the cap is not
# what a job waits on, and that the wait belongs to the pool instead.
peak_concurrency() {
    awk '{ print $1, "+1"; print $2, "-1" }' \
        | sort -n -k1,1 -k2,2r \
        | awk '{ cur += $2; if (cur > mx) mx = cur } END { print mx + 0 }'
}

# p50/p90/max over stdin (one integer per line). Sorting is the whole implementation;
# the counts are small enough that a streaming estimate would only add error.
percentiles() {
    local vals n p50 p90 mx
    vals=$(sort -n)
    n=$(printf '%s\n' "$vals" | grep -c .)
    [ "$n" -eq 0 ] && { printf '%8s %8s %8s %6s' - - - 0; return; }
    p50=$(printf '%s\n' "$vals" | sed -n "$(( (n + 1) / 2 ))p")
    p90=$(printf '%s\n' "$vals" | sed -n "$(( (n * 9 + 9) / 10 ))p")
    mx=$(printf '%s\n' "$vals" | tail -1)
    printf '%8s %8s %8s %6s' "$(fmt_min "$p50")" "$(fmt_min "$p90")" "$(fmt_min "$mx")" "$n"
}

fmt_min() { awk -v s="$1" 'BEGIN { printf "%.1fm", s/60 }'; }

epoch() { date -j -f '%Y-%m-%dT%H:%M:%SZ' "$1" '+%s' 2>/dev/null || date -d "$1" '+%s' 2>/dev/null; }

view_active() {
    printf '%s\n' "=================================================================="
    printf 'ACTIVE RUNS on %s\n' "$REPO"
    while IFS=$'\t' read -r id status wf branch sha created; do
        [ -z "${id:-}" ] && continue
        printf '\n  %-11s %-14s %s [%s]  %s\n' "$status" "$wf" "$id" "$branch" "$created"
        jobs_of "$id" | awk -F'\t' -v OFS='' '
            { split($2, l, ","); print $1 "\t" l[1] }
        ' | while IFS=$'\t' read -r jstatus label; do
            printf '%s\t%s\n' "$jstatus" "$(pool_of "$label")"
        done | sort | uniq -c | sort -rn | while read -r n rest; do
            jstatus=$(printf '%s' "$rest" | cut -f1)
            pool=$(printf '%s' "$rest" | cut -f2)
            case "$jstatus" in
                queued|in_progress) printf '      %2s %-12s %s\n' "$n" "$jstatus" "$pool" ;;
            esac
        done
    done < <(active_runs)

    printf '\n  ---- jobs holding or waiting on each pool ----\n'
    while IFS=$'\t' read -r id _ _ _ _ _; do
        [ -z "${id:-}" ] && continue
        jobs_of "$id"
    done < <(active_runs) | while IFS=$'\t' read -r jstatus labels _ _ _ _; do
        [ "$labels" = "-" ] && continue
        case "$jstatus" in
            queued|in_progress) printf '%s\t%s\n' "$(pool_of "${labels%%,*}")" "$jstatus" ;;
        esac
    done | sort | uniq -c | sort -k2 | awk '{ printf "      %2s  %-13s %s\n", $1, $2, $3 }'

    # Anything still queued, with how long it has been waiting. A run sitting at "queued" for
    # two minutes and one sitting there for forty look identical in every other view, and only
    # the second is a problem.
    local now stuck
    now=$(date '+%s')
    stuck=$(while IFS=$'\t' read -r id _ wf branch _ _; do
        [ -z "${id:-}" ] && continue
        jobs_of "$id" | while IFS=$'\t' read -r jstatus labels created _ _ name; do
            [ "$jstatus" = "queued" ] || continue
            c=$(epoch "$created"); [ -z "${c:-}" ] && continue
            printf '%s\t%s\t%s\t%s\n' "$(( now - c ))" "$(pool_of "${labels%%,*}")" "$wf" "$name"
        done
    done < <(active_runs) | sort -rn)
    if [ -n "$stuck" ]; then
        printf '\n  ---- queued, longest wait first ----\n'
        printf '%s\n' "$stuck" | while IFS=$'\t' read -r secs pool wf name; do
            printf '      %8s  %-13s %-14s %s\n' "$(fmt_min "$secs")" "$pool" "$wf" "$name"
        done
    fi
    printf '\n'
}

view_waits() {
    printf '%s\n' "=================================================================="
    printf 'QUEUE WAIT by pool, last %s runs on %s\n' "$WAITS_N" "$REPO"
    printf '  (time from job created to job started; the run-level wait a PR actually feels)\n\n'
    local tmp span; tmp=$(mktemp); span=$(mktemp)
    gh run list --limit "$WAITS_N" --json databaseId --jq '.[].databaseId' | while read -r id; do
        jobs_of "$id" | while IFS=$'\t' read -r _ labels created started completed _; do
            # A skipped job carries no labels and never occupied a runner, so it is not part of
            # any pool's contention and must not dilute that pool's percentiles.
            [ "$started" = "-" ] || [ "$created" = "-" ] || [ "$labels" = "-" ] && continue
            c=$(epoch "$created"); s=$(epoch "$started")
            [ -z "${c:-}" ] || [ -z "${s:-}" ] && continue
            pool=$(pool_of "${labels%%,*}")
            d=$(( s - c )); [ "$d" -lt 0 ] && d=0
            printf '%s\t%s\n' "$pool" "$d" >> "$tmp"
            # Running span, for the peak-concurrency sweep. A job with no completed_at is still
            # running, so it is counted to now.
            if [ "$completed" = "-" ]; then e=$(date '+%s'); else e=$(epoch "$completed"); fi
            [ -n "${e:-}" ] && printf '%s\t%s\t%s\n' "$pool" "$s" "$e" >> "$span"
        done
    done
    printf '  %-13s %8s %8s %8s %6s %6s\n' pool p50 p90 max jobs peak
    cut -f1 "$tmp" | sort -u | while read -r pool; do
        [ -z "$pool" ] && continue
        printf '  %-13s ' "$pool"
        awk -F'\t' -v p="$pool" '$1==p {print $2}' "$tmp" | percentiles
        printf ' %6s\n' "$(awk -F'\t' -v p="$pool" '$1==p {print $2, $3}' "$span" | peak_concurrency)"
    done
    rm -f "$tmp" "$span"
    printf '\n  peak = most jobs this pool ran at once in the sampled window, i.e. the capacity\n'
    printf '  actually reached. Compare against waits: a pool that never exceeds a low peak while\n'
    printf '  its p90 climbs is fleet-bound, not cap-bound.\n\n'
}

view_groups() {
    printf '%s\n' "=================================================================="
    printf 'CONCURRENCY GROUPS behind active runs on %s\n' "$REPO"
    printf '  Two runs of one workflow can only cancel each other when these match.\n\n'
    while IFS=$'\t' read -r id status wf branch sha created; do
        [ -z "${id:-}" ] && continue
        file=$(gh api "repos/$REPO/actions/runs/$id" --jq '.path' 2>/dev/null)
        grp=$(gh api "repos/$REPO/contents/${file}?ref=${sha}" \
                --jq '.content' 2>/dev/null | base64 -d 2>/dev/null \
              | awk '/^concurrency:/ {inblock=1; next}
                     inblock && /^[^[:space:]]/ {inblock=0}
                     inblock && /group:/ { sub(/^[[:space:]]*group:[[:space:]]*/, ""); print; exit }')
        printf '  %-14s %s [%s] %s\n' "$wf" "$id" "$branch" "${sha:0:8}"
        printf '      %s\n' "${grp:-<none declared>}"
    done < <(active_runs)
    printf '\n'
}

case "$VIEW" in
    active) view_active ;;
    waits)  view_waits ;;
    groups) view_groups ;;
esac

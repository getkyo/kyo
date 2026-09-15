#!/usr/bin/env bash
#
# Preflight for the CI stabilization drive: answers "where am I, and does the remote agree" from the
# repository rather than from memory. Run it before dispatching; a non-zero exit means an assumption the
# run rests on is wrong, and the safe next step is to report that rather than spend runner hours on it.
#
# It exists because the answers are cheap to check and expensive to assume. A run dispatched from a
# tree whose remote is behind tests a revision nobody is looking at, and a run dispatched from the
# wrong branch tests the wrong work entirely; both look identical to a green result until someone
# reads the sha.
#
# THE DRIVE BRANCH IS A CONSTANT, DELIBERATELY. An earlier form of this script took the expected branch
# as an argument supplied by the caller. That cannot work: the caller's copy of the branch name is the
# value most likely to be stale, so the check validated against the stale name and reported success. A
# guard whose input is the failing value launders the failure instead of catching it. The name lives
# here and nowhere else; an argument is still accepted, reported when it disagrees, and ignored.
#
# The branch is permanent. Ship a PR from a disposable branch cut off it, never by renaming it, so a
# merge that deletes the PR branch upstream cannot take the drive's identity with it.
#
# Usage: scripts/ci-stabilization.sh
# Exit:  0 all checks pass; 1 a check failed; 2 not a git worktree.

set -uo pipefail

# The single source of truth for the drive's identity.
DRIVE_BRANCH="ci-stabilization"

supplied="${1:-}"

fail=0
ok()   { printf '  \033[32mok\033[0m    %s\n' "$1"; }
bad()  { printf '  \033[31mFAIL\033[0m  %s\n' "$1"; fail=1; }
note() { printf '  %-24s %s\n' "$1" "$2"; }

git rev-parse --is-inside-work-tree >/dev/null 2>&1 || {
    echo "ci-stabilization: not inside a git worktree" >&2
    exit 2
}

root=$(git rev-parse --show-toplevel)
cd "$root" || exit 2

echo "== ci-stabilization preflight =="
note "worktree" "$root"
note "drive branch" "$DRIVE_BRANCH (constant)"

if [ -n "$supplied" ] && [ "$supplied" != "$DRIVE_BRANCH" ]; then
    printf '  \033[33mstale\033[0m %s\n' "argument '$supplied' ignored: the drive branch is '$DRIVE_BRANCH'"
fi

branch=$(git rev-parse --abbrev-ref HEAD)
note "branch" "$branch"
if [ "$branch" = "HEAD" ]; then
    bad "detached HEAD: a dispatch cannot name this revision by branch"
elif [ "$branch" != "$DRIVE_BRANCH" ]; then
    bad "wrong branch: on '$branch', the drive works only on '$DRIVE_BRANCH'"
    where=$(git worktree list | awk -v b="[$DRIVE_BRANCH]" '$NF == b {print $1}')
    if [ -n "$where" ]; then
        note "it is checked out at" "$where"
    else
        note "not checked out" "no worktree holds '$DRIVE_BRANCH'"
    fi
else
    ok "on the drive branch"
fi

head=$(git rev-parse HEAD)
note "HEAD" "$(git rev-parse --short HEAD) $(git log -1 --format=%s | cut -c1-56)"

# Every remote carrying this branch must carry this commit. A remote left behind means the dispatch
# runs an older tree, which is invisible in the result until the sha is compared by hand.
seen_remote=0
for remote in $(git remote); do
    ref="$remote/$branch"
    git rev-parse --verify --quiet "$ref" >/dev/null || continue
    seen_remote=1
    if [ "$(git rev-parse "$ref")" = "$head" ]; then
        ok "$ref matches HEAD"
    else
        ahead=$(git rev-list --count "$ref..HEAD" 2>/dev/null || echo '?')
        behind=$(git rev-list --count "HEAD..$ref" 2>/dev/null || echo '?')
        # Only unpushed work is a problem. A local ref that is merely BEHIND its remote means the remote already
        # carries everything here, so nothing is at risk and nothing is blocked; that is the normal state for a
        # checkout that publishes from elsewhere. Failing on it made the preflight permanently red and turned a
        # real signal into noise. Ahead, or diverged, means commits exist only here and a teardown would take them.
        if [ "$ahead" = "0" ]; then
            printf '  \033[33mnote\033[0m  %s\n' "$ref is $behind ahead of this checkout: it already carries this work, nothing at risk"
        else
            bad "$ref differs from HEAD (local ahead $ahead, behind $behind): push or reconcile first"
        fi
    fi
done
[ "$seen_remote" -eq 1 ] || bad "no remote carries '$branch': nothing to dispatch against"

# Uncommitted work is invisible to CI, and a checkout or reset would destroy it.
dirty=$(git status --porcelain | grep -vc '^??' || true)
untracked=$(git status --porcelain | grep -c '^??' || true)
note "uncommitted" "$dirty tracked, $untracked untracked"
if [ "$dirty" -eq 0 ]; then
    ok "no uncommitted changes to tracked files"
else
    bad "$dirty uncommitted change(s): CI would test a different tree"
fi

if git rev-parse --verify --quiet origin/main >/dev/null; then
    behind_main=$(git rev-list --count "HEAD..origin/main")
    note "behind origin/main" "$behind_main"
    if [ "$behind_main" -eq 0 ]; then
        ok "current with main"
    else
        printf '  \033[33mnote\033[0m  %s\n' "$behind_main commit(s) behind main: merge before relying on a green"
    fi
fi

# How long since this branch was last exercised on CI. A preflight that only reports the tree's state cannot
# notice that its own refusals have stopped all validation: the checks kept passing or failing on the same
# facts while nothing was being verified anywhere. This is the one number that makes that visible, and it is
# deliberately reported whether the rest passes or not, because a failing preflight is exactly the situation
# in which validation quietly stops.
# Deliberately NOT filtered by branch. A workflow_dispatch run reports the branch its WORKFLOW FILE was
# taken from, not the commit it tested, so a dispatch that tests this branch is commonly recorded against
# another one. Filtering on that field hides exactly the runs this check exists to see, and the check then
# reports staleness while validation is in fact happening. The fork carries nothing but this work, so its
# most recent run is the honest answer to when anything here was last exercised.
last_run=$(gh run list -R fwbrasil/kyo-ci-test --limit 1 \
    --json createdAt,conclusion -q '.[0] | "\(.createdAt) \(.conclusion)"' 2>/dev/null || echo "")
if [ -z "$last_run" ]; then
    printf '  \033[31mSTALE\033[0m %s\n' "no CI run has ever been dispatched for '$DRIVE_BRANCH': nothing here is validated"
else
    # The timestamp is UTC; parsing it in local time silently shifts the age by the offset and can even
    # report a negative age, which reads as nonsense rather than as a bug in the check.
    run_epoch=$(TZ=UTC date -j -f "%Y-%m-%dT%H:%M:%SZ" "${last_run%% *}" +%s 2>/dev/null || echo 0)
    now_epoch=$(date +%s)
    if [ "$run_epoch" -gt 0 ]; then
        hours=$(( (now_epoch - run_epoch) / 3600 ))
        outcome="${last_run##* }"
        [ -z "$outcome" ] && outcome="in progress"
        note "last CI run" "${hours}h ago ($outcome)"
        if [ "$hours" -ge 6 ]; then
            printf '  \033[31mSTALE\033[0m %s\n' "no CI run for ${hours}h: if a check here is what stopped it, that check is the bug"
        fi
    fi
fi

echo
if [ "$fail" -eq 0 ]; then
    echo "preflight OK"
    exit 0
fi
echo "preflight FAILED. Do not dispatch; report this."
exit 1

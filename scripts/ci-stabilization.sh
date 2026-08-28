#!/usr/bin/env bash
#
# Preflight for a branch that dispatches CI runs to a fork: answers "where am I, and does the remote
# agree" from the repository rather than from memory. Run it before dispatching; a non-zero exit means
# an assumption the run rests on is wrong, and the safe next step is to report that rather than spend
# runner hours on it.
#
# It exists because the answers are cheap to check and expensive to assume. A run dispatched from a
# tree whose remote is behind tests a revision nobody is looking at, and a run dispatched from the
# wrong branch tests the wrong work entirely; both look identical to a green result until someone
# reads the sha.
#
# Usage: scripts/ci-stabilization.sh [expected-branch]
#   With an argument, the checkout must be on that branch. Without one, the branch is reported and the
#   remaining checks still run.
# Exit:  0 all checks pass; 1 a check failed; 2 not a git worktree.

set -uo pipefail

expected="${1:-}"

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

branch=$(git rev-parse --abbrev-ref HEAD)
note "branch" "$branch"
if [ "$branch" = "HEAD" ]; then
    bad "detached HEAD: a dispatch cannot name this revision by branch"
elif [ -n "$expected" ] && [ "$branch" != "$expected" ]; then
    bad "branch mismatch: on '$branch', expected '$expected'"
elif [ -n "$expected" ]; then
    ok "on the expected branch"
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
        bad "$ref differs from HEAD (local ahead $ahead, behind $behind): push or reconcile first"
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

echo
if [ "$fail" -eq 0 ]; then
    echo "preflight OK"
    exit 0
fi
echo "preflight FAILED. Do not dispatch; report this."
exit 1

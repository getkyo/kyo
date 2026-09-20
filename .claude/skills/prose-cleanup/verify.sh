#!/usr/bin/env bash
# Guard for a prose pass: prove the working tree changed nothing but prose the branch itself wrote.
#
#   verify.sh [base-ref]
#
# Two failures matter, and neither is visible by reading an agent's report:
#   CODE   a changed line is not a comment
#   STOLEN a removed comment line was pre-existing, not added by this branch
#
# Code lines are compared with whitespace collapsed. Removing a comment can move an alignment
# boundary, and scalafmt (align.preset = more) then re-spaces the code around it; that is a
# formatting consequence of a legitimate edit, while any change to what a code line SAYS still fails.
#
#   verify.sh [-b BASE] [file ...]     no files: every changed .scala/.sbt in the tree
#
# Pass your own paths when other agents are editing in parallel; the tree-wide form is the
# coordinator's check after a wave.
set -u
BASE="origin/main"
if [ "${1:-}" = "-b" ]; then BASE="${2:-origin/main}"; shift 2; fi
fail=0

if [ "$#" -gt 0 ]; then
    changed="$*"
else
    changed=$(git diff --name-only -- '*.scala' '*.sbt')
fi
[ -z "$changed" ] && { echo "no files changed"; exit 0; }

# Private scratch: a wave runs several of these at once, and a shared path would have one agent
# verifying against another's data, which is the one wrong answer this script must never give.
BRANCH=$(mktemp); OLD=$(mktemp); NEW=$(mktemp)
trap 'rm -f "$BRANCH" "$OLD" "$NEW"' EXIT

for f in $changed; do
    # Lines this branch contributed: the legitimate pool a pass may remove from.
    git diff "$BASE"..HEAD -- "$f" | grep -E '^\+' | sed 's/^+//' | sed 's/^[[:space:]]*//' | sort -u > "$BRANCH"

    # Code lines with whitespace collapsed, each side of the change, to tell a re-spacing from an edit.
    git diff -U0 -- "$f" | grep -E '^-' | grep -vE '^---' | sed 's/^-//' | sed 's/^[[:space:]]*//' \
        | grep -vE '^(//|\*|/\*)' | tr -s ' \t' ' ' | sed 's/[[:space:]]*$//' | sort > "$OLD"
    git diff -U0 -- "$f" | grep -E '^\+' | grep -vE '^\+\+\+' | sed 's/^+//' | sed 's/^[[:space:]]*//' \
        | grep -vE '^(//|\*|/\*)' | tr -s ' \t' ' ' | sed 's/[[:space:]]*$//' | sort > "$NEW"
    reflowed=$(comm -3 "$OLD" "$NEW" | grep -c . || true)

    bad_code=0; stolen=0
    while IFS= read -r line; do
        body="${line:1}"
        trimmed=$(printf '%s' "$body" | sed 's/^[[:space:]]*//')
        [ -z "$trimmed" ] && continue
        case "$trimmed" in
            //*|\**|/\**) ;;                       # a comment line: allowed to change
            *) continue ;;                         # code: judged by the whitespace-insensitive compare above
        esac
        # A removed line must have been this branch's prose.
        if [ "${line:0:1}" = "-" ]; then
            grep -qxF "$trimmed" "$BRANCH" || stolen=$((stolen+1))
        fi
    done < <(git diff -U0 -- "$f" | grep -E '^[+-]' | grep -vE '^(\+\+\+|---)')

    if [ "$reflowed" -gt 0 ] || [ "$stolen" -gt 0 ]; then
        fail=1
        printf 'FAIL  %s\n' "$f"
        [ "$reflowed" -gt 0 ] && printf '        CODE   %d code line(s) changed beyond whitespace\n' "$reflowed"
        [ "$stolen" -gt 0 ]   && printf '        STOLEN %d removed comment line(s) were pre-existing\n' "$stolen"
    else
        printf 'ok    %s\n' "$f"
    fi
done

echo
if [ "$fail" -eq 0 ]; then
    echo "PASS: every change is prose this branch wrote"
else
    echo "FAILED: restore the listed files with 'git checkout -- <file>' and re-run them alone"
    exit 1
fi

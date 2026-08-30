#!/usr/bin/env bash
#
# Re-derives every mechanical claim a live-review package makes and compares it against what the
# package says (see SKILL.md, "Preparing a live review").
#
# It exists because the same escape happened in four consecutive rounds: prose asserting a fact
# about the tree, written once and not re-derived when the tree moved. Stale tip shas, a flag
# count that no longer matches the table, a surface the range disagrees with, and, worst, a
# benchmark class that does not exercise the package under review at all.
#
# Usage:
#   package-check.sh <base>..<tip> <review-dir> <package-path>
#
#   package-check.sh 31a7b4bde9..HEAD reviews/proto-region-stack kyo/proto/kernel
#
# Every line is CHECK (informational), OK, or STALE. A STALE line is a defect in the package, not
# a suggestion. Judgment-bearing claims (what a number means, whether a concession is justified)
# are not checked here; those are the review lenses' job.

set -euo pipefail

range="${1:?usage: package-check.sh <base>..<tip> <review-dir> <package-path>}"
dir="${2:?usage: package-check.sh <base>..<tip> <review-dir> <package-path>}"
pkg="${3:?usage: package-check.sh <base>..<tip> <review-dir> <package-path>}"

base="${range%%..*}"
tip="${range##*..}"
base_sha=$(git rev-parse --short=10 "$base")
tip_sha=$(git rev-parse --short=10 "$tip")
commits=$(git rev-list --count "$range")

status=0
stale() { echo "STALE  $*"; status=1; }

echo "CHECK  range $range  base $base_sha  tip $tip_sha  commits $commits"

# 1. Shas named in the package that are neither the base nor the tip. A superseded sha in the prose
#    is how three rounds of numbers came to describe a tree that was not shipping.
while read -r sha file; do
    [ -z "$sha" ] && continue
    if git cat-file -e "$sha^{commit}" 2>/dev/null; then
        full=$(git rev-parse --short=10 "$sha")
        if [ "$full" != "$base_sha" ] && [ "$full" != "$tip_sha" ]; then
            stale "$file names commit $sha, which is neither the base nor the tip"
        fi
    fi
done < <(grep -rhoE '\b[0-9a-f]{8,40}\b' "$dir" --include='*.md' -H 2>/dev/null |
    sed 's/^\([^:]*\):\(.*\)$/\2 \1/' | sort -u)

# 2. The surface: what the range actually touches, so a file changed but never presented, or
#    presented but never changed, is visible rather than argued about.
echo "CHECK  the range touches:"
git diff --name-only "$range" | sed 's/^/       /'

# 3. Working tree clean against the tip. A comment or a fix living only in the working tree does
#    not survive an A/B leg, and its line numbers shift every citation below it.
if [ -n "$(git status --porcelain)" ]; then
    stale "the working tree is dirty; every line citation and every A/B leg is against the tip, not this"
else
    echo "OK     working tree clean against the tip"
fi

# 4. The flag table: the script's row count against the table's, both re-derived now.
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
if [ -x "$here/flags.sh" ] && [ -f "$dir/flags.md" ]; then
    emitted=$("$here/flags.sh" "$range" | grep -c . || true)
    tabled=$(grep -cE '^\| F[0-9]+ \|' "$dir/flags.md" || true)
    if [ "$emitted" = "$tabled" ]; then
        echo "OK     flags: $emitted emitted, $tabled adjudicated"
    else
        stale "flags: the script emits $emitted rows, flags.md adjudicates $tabled"
    fi
fi

# 5. Benchmark coverage. The one that matters most: a benchmark class named in the package must
#    actually exercise the package under review. A class named for what it used to measure will
#    otherwise supply numbers about code the change does not touch, and they will read as evidence.
for cls in $(grep -rhoE '\b[A-Z][A-Za-z0-9]*Bench\b' "$dir" --include='*.md' 2>/dev/null | sort -u); do
    src=$(git ls-files "*/$cls.scala")
    if [ -z "$src" ]; then
        stale "the package names benchmark class $cls, which no source file defines"
    elif grep -qE "(^|[^A-Za-z0-9.])${pkg//\//\\.}" $src; then
        echo "OK     $cls exercises $pkg"
    else
        stale "$cls does not reference $pkg, so its rows are not evidence about this change"
    fi
done

# 6. The edit sequence, if the package recorded one: applying it to the base must reproduce the
#    tip byte for byte. A described sequence that was never built is why one walk reached for a
#    bulk replace.
if [ -f "$dir/sequence.py" ]; then
    if python3 "$dir/sequence.py" --verify >/dev/null 2>&1; then
        echo "OK     the recorded edit sequence reproduces the tip"
    else
        stale "the recorded edit sequence does not reproduce the tip; run $dir/sequence.py --verify"
    fi
else
    echo "CHECK  no sequence.py in $dir: the walk cannot be performed from this package"
fi

exit $status

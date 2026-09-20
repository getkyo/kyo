#!/usr/bin/env bash
# Split the files a branch added comments to into N batches of equal comment volume.
#
#   batch.sh <n> [base-ref] [out-dir]
#
# Balances on comment lines, not file count: one 180-line file outweighs twenty 5-line files.
# Writes <out-dir>/batch-<i>.txt, one path per line, and prints the resulting loads.
set -u
N="${1:-8}"
BASE="${2:-origin/main}"
OUT="${3:-/tmp/prose-batches}"

rm -rf "$OUT"; mkdir -p "$OUT"
TALLY=$(mktemp)

for f in $(git diff --name-only "$BASE"..HEAD -- '*.scala' '*.sbt'); do
    [ -f "$f" ] || continue
    n=$(git diff "$BASE"..HEAD -- "$f" | grep -E '^\+' | grep -cE '^\+[[:space:]]*(//|\*|/\*)')
    [ "$n" -gt 0 ] && printf "%5d %s\n" "$n" "$f" >> "$TALLY"
done

sort -rn "$TALLY" -o "$TALLY"
echo "files with branch-added comments: $(wc -l < "$TALLY")"
echo "total branch-added comment lines: $(awk '{s+=$1} END{print s+0}' "$TALLY")"
echo

# Largest-first packing into the currently-lightest bin.
awk -v n="$N" -v out="$OUT" '
    { cnt[NR] = $1; path[NR] = $2; total = NR }
    END {
        for (b = 1; b <= n; b++) load[b] = 0
        for (i = 1; i <= total; i++) {
            best = 1
            for (b = 2; b <= n; b++) if (load[b] < load[best]) best = b
            load[best] += cnt[i]
            print path[i] >> (out "/batch-" best ".txt")
            files[best]++
        }
        for (b = 1; b <= n; b++) printf "batch-%d: %3d files, %5d comment lines\n", b, files[b], load[b]
    }
' "$TALLY"
rm -f "$TALLY"
echo
echo "batch lists in $OUT"

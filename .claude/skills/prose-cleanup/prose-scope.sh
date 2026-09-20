#!/usr/bin/env bash
# Print the comment blocks a branch added or modified in one file: the editable set for a prose pass.
#
#   prose-scope.sh <file> [base-ref]        one file, blocks with line numbers
#   prose-scope.sh --list <file-of-paths>   line counts per path, for batching
#
# A block is a run of consecutive comment lines. A block is in scope when at least one of its lines
# was added or modified against the base. A block with no added line is pre-existing and must not be
# touched. Line numbers refer to the working tree at the time this runs; edits shift them, so anchor
# edits on the text, not the number.
set -u
BASE="${2:-origin/main}"

if [ "${1:-}" = "--list" ]; then
    while IFS= read -r f; do
        [ -f "$f" ] || continue
        n=$(git diff "${3:-origin/main}"..HEAD -- "$f" | grep -E '^\+' | grep -cE '^\+[[:space:]]*(//|\*|/\*)')
        [ "$n" -gt 0 ] && printf "%5d %s\n" "$n" "$f"
    done < "$2"
    exit 0
fi

FILE="$1"
[ -f "$FILE" ] || { echo "no such file: $FILE" >&2; exit 1; }

# Private scratch: several of these run concurrently in a wave, and a shared path would have them
# reading each other's line numbers.
ADDED=$(mktemp); trap 'rm -f "$ADDED"' EXIT

# New-file line numbers of lines this branch added, from the hunk headers.
git diff -U0 "$BASE"..HEAD -- "$FILE" | awk '
    /^@@/ {
        match($0, /\+[0-9]+/); start = substr($0, RSTART+1, RLENGTH-1)
        cnt = 1
        if (match($0, /\+[0-9]+,[0-9]+/)) { split(substr($0, RSTART+1, RLENGTH-1), a, ","); start = a[1]; cnt = a[2] }
        for (i = 0; i < cnt; i++) print start + i
        next
    }
' | sort -n -u > "$ADDED"

if [ ! -s "$ADDED" ]; then
    echo "NO BRANCH-ADDED LINES in $FILE"
    exit 0
fi

awk -v addedfile="$ADDED" '
    BEGIN { while ((getline l < addedfile) > 0) added[l] = 1 }
    {
        line[NR] = $0
        t = $0; sub(/^[[:space:]]+/, "", t)
        iscomment[NR] = (t ~ /^(\/\/|\*|\/\*)/) ? 1 : 0
    }
    END {
        n = NR; i = 1
        while (i <= n) {
            if (iscomment[i]) {
                s = i
                while (i <= n && iscomment[i]) i++
                e = i - 1
                touched = 0
                for (j = s; j <= e; j++) if (added[j]) touched = 1
                if (touched) {
                    ed = ""; ro = ""
                    for (j = s; j <= e; j++) {
                        if (added[j]) ed = ed (ed == "" ? "" : ",") j
                        else          ro = ro (ro == "" ? "" : ",") j
                    }
                    printf "\n--- BLOCK %d-%d ---\n", s, e
                    printf "    EDITABLE : %s\n", (ed == "" ? "(none)" : ed)
                    printf "    READ-ONLY: %s   <- changing any of these fails verification\n", (ro == "" ? "(none)" : ro)
                    for (j = s; j <= e; j++)
                        printf "%s %5d | %s\n", (added[j] ? "EDIT    " : "READ-ONLY"), j, line[j]
                }
            } else i++
        }
    }
' "$FILE"

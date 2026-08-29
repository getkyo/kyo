#!/usr/bin/env bash
#
# Emits one flag per construct of concern found on the ADDED lines of a diff, as the skeleton of
# the adjudication table the REVIEW phase fills in (see SKILL.md, "Preparing a live review").
#
# Recall-tuned and false-positive tolerant on purpose: a flag is a row that must be given a
# verdict, never an accusation. The value is completeness, so that no cast and no `Any` can reach
# a review without its author having written down why it is there.
#
# Usage:
#   flags.sh [<git-diff-range>] [-- <paths>...]
#
#   flags.sh                        # working tree against HEAD
#   flags.sh HEAD~1                 # against a commit
#   flags.sh main -- kyo-kernel     # a range, narrowed to paths
#
# Classes that need judgment rather than a pattern (surface, allocation intent, unbacked claims)
# are not emitted here; they are catalog entries the review lenses apply. See SKILL.md.

set -euo pipefail

range=""
paths=()
while [ $# -gt 0 ]; do
    case "$1" in
        --) shift; paths=("$@"); break ;;
        *) range="$1"; shift ;;
    esac
done

if [ -n "$range" ]; then
    set -- diff -U0 "$range"
else
    set -- diff -U0 HEAD
fi
if [ ${#paths[@]} -gt 0 ]; then
    set -- "$@" -- "${paths[@]}"
fi

git "$@" | awk '
function flag(cls,   n) {
    n = ++count
    printf "| F%d | %s:%d | `%s` | %s |  |\n", n, file, lineno, esc, cls
}
/^\+\+\+ / { file = substr($0, 7); next }
/^--- /    { next }
/^@@ /     {
    # @@ -a,b +c,d @@  : c is the first added line number in the new file
    match($0, /\+[0-9]+/)
    lineno = substr($0, RSTART + 1, RLENGTH - 1) - 1
    next
}
/^\+/ {
    lineno++
    line = substr($0, 2)
    # the verbatim line, trimmed and made safe for a markdown table cell
    esc = line
    gsub(/^[ \t]+/, "", esc)
    gsub(/\|/, "\\|", esc)
    if (length(esc) > 110) esc = substr(esc, 1, 107) "..."
    if (esc == "") next

    if (line ~ /asInstanceOf|@unchecked|\.erased/)                                  flag("cast")
    if (line ~ /(^|[^A-Za-z])(Any|Nothing|Null|null)([^A-Za-z]|$)/)                  flag("carrier")
    if (line ~ /(^|[^A-Za-z])(var|while)([^A-Za-z]|$)/)                              flag("mutability")
    if (line ~ /(class|trait|object|enum|type)[ \t]+[A-Z]/)                          flag("new-type")
    if (line ~ /(^|[^A-Za-z])([Dd]rive|drives|driving|after)([^A-Za-z]|$)/)          flag("terminology")
    if (line ~ /TODO|FIXME|\?\?\?/)                                                  flag("placeholder")
    if (line ~ /(^|[^A-Za-z])new[ \t]+[A-Z]/)                                        flag("allocation")
}
BEGIN {
    print "| id | site | added line | class | verdict |"
    print "|----|------|------------|-------|---------|"
}
END {
    if (count == 0) print "| |  | no flagged construct on added lines |  |  |"
}
'

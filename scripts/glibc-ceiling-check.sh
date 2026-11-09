#!/usr/bin/env bash
set -uo pipefail
#
# glibc-ceiling-check.sh - fail when a published glibc native needs a newer glibc than kyo supports.
#
# Usage: glibc-ceiling-check.sh <file>...
#
# A shared object records the newest glibc symbol version it was linked against (GLIBC_2.38 for a
# call to __isoc23_strtol, say), and the dynamic loader refuses to load it on any older glibc. That
# version comes from the build host, so a release built on a newer runner image silently raises the
# glibc every user needs. This reads each file's dynamic symbol table and fails when any symbol needs
# a version above the ceiling. musl objects (named *-linux-musl-*) carry no glibc versions and are
# skipped, as is any file that is not an ELF object.
#
# The ceiling is the glibc of Ubuntu 24.04, the image the release's glibc producers build on, and is
# defined here only: release.yml's producers and release-probe.yml both call this script. Raising it
# raises the glibc a kyo user needs, so it moves only by decision, together with those runners.
# GLIBC_CEILING overrides it for a one-off check.

ceiling="${GLIBC_CEILING:-2.39}"
[ $# -ge 1 ] || { echo "usage: $0 <file>..." >&2; exit 2; }

command -v objdump >/dev/null 2>&1 || { echo "glibc-ceiling-check: objdump is required (binutils)" >&2; exit 2; }

# 0 when version $1 is greater than version $2, comparing numerically per dot-separated part.
newer() {
    [ "$1" != "$2" ] && [ "$(printf '%s\n%s\n' "$1" "$2" | sort -t. -k1,1n -k2,2n -k3,3n | tail -1)" = "$1" ]
}

failed=""
checked=0
for f in "$@"; do
    case "$f" in *-linux-musl-*) continue ;; esac
    if ! head -c 4 "$f" 2>/dev/null | grep -q 'ELF'; then
        continue
    fi
    needed="$(objdump -T "$f" | grep -o 'GLIBC_[0-9][0-9.]*' | sed 's/^GLIBC_//' | sort -t. -k1,1n -k2,2n -k3,3n -u | tail -1)"
    checked=$((checked + 1))
    if [ -z "$needed" ]; then
        echo "$f: needs no versioned glibc symbol"
    elif newer "$needed" "$ceiling"; then
        echo "$f: needs GLIBC_$needed, above the supported ceiling GLIBC_$ceiling. Symbols:" >&2
        objdump -T "$f" | awk '/GLIBC_[0-9]/ { match($0, /GLIBC_[0-9.]+/); print substr($0, RSTART + 6, RLENGTH - 6), $NF }' \
            | sort -u | while read -r version symbol; do
                if newer "$version" "$ceiling"; then echo "  $symbol@GLIBC_$version" >&2; fi
            done
        failed=1
    else
        echo "$f: needs GLIBC_$needed (ceiling GLIBC_$ceiling)"
    fi
done

[ "$checked" -gt 0 ] || { echo "glibc-ceiling-check: no glibc ELF object among the arguments" >&2; exit 1; }
[ -z "$failed" ] || exit 1

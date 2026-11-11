#!/usr/bin/env bash
# Exercise the release guard with real ELF binaries, including a malformed input.
set -euo pipefail
export LC_ALL=C

here="$(cd "$(dirname "$0")" && pwd)"
fixture="$(mktemp -d)"
trap 'rm -rf "$fixture"' EXIT

cat > "$fixture/program.c" <<'C'
#include <stdio.h>
int main(void) { return puts("glibc guard") < 0; }
C
cc -static "$fixture/program.c" -o "$fixture/static"
cc -shared -fPIC "$fixture/program.c" -o "$fixture/dynamic.so"

# A static executable has no requirement on the consumer's installed glibc. A shared
# object must still pass the configured ceiling; accepting static files must not bypass it.
GLIBC_CEILING=0 bash "$here/glibc-ceiling-check.sh" "$fixture/static"
GLIBC_CEILING=999 bash "$here/glibc-ceiling-check.sh" "$fixture/dynamic.so"
if GLIBC_CEILING=0 bash "$here/glibc-ceiling-check.sh" "$fixture/dynamic.so" > "$fixture/rejection" 2>&1; then
    echo "the guard accepted a shared object's glibc requirement above the ceiling" >&2
    exit 1
fi
grep -q 'above the supported ceiling' "$fixture/rejection"

# An ELF signature is not evidence that the symbol table was successfully inspected.
printf '\177ELF' > "$fixture/malformed"
if bash "$here/glibc-ceiling-check.sh" "$fixture/malformed" > "$fixture/rejection" 2>&1; then
    echo "the guard accepted a malformed ELF file" >&2
    exit 1
fi
echo "glibc guard accepts static binaries and rejects excessive requirements and malformed ELF"

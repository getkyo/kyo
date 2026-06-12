#!/usr/bin/env bash
# Build a static BoringSSL (CMake + Go) at the pinned commit and stage it per os-arch for kyo-net
# (RI-006 / AD-007 / D6). Run once per runner os-arch before the kyo-net package/test step; the
# produced libssl.a + libcrypto.a + headers are build artifacts (never committed), consumed by the
# kyonet_boringssl FfiLibrary (the kyo_net_boringssl.c shim links them into a loadable lib) and by Scala
# Native's archive link.
#
# Usage: build-boringssl.sh <os-arch>   e.g. linux-x86_64 | linux-aarch64 | darwin-aarch64
# Requires cmake + go + a C/C++ toolchain on PATH (apt: cmake golang build-essential; brew: cmake go).
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
osArch="${1:?os-arch required, e.g. linux-x86_64 / linux-aarch64 / darwin-aarch64}"
commit="$(grep -vE '^[[:space:]]*#' "$here/BORINGSSL_COMMIT" | head -n1 | tr -d '[:space:]')"
[ "${#commit}" -eq 40 ] || { echo "BORINGSSL_COMMIT is not a 40-char commit: '$commit'" >&2; exit 1; }

src="${BORINGSSL_SRC:-${TMPDIR:-/tmp}/kyonet-boringssl-src}"
if [ ! -d "$src/.git" ]; then
    rm -rf "$src"
    git clone https://boringssl.googlesource.com/boringssl "$src"
fi
# Full clone has every commit; check out the exact pin (fetch first if a fresh bump is not present).
git -C "$src" checkout -q "$commit" 2>/dev/null || { git -C "$src" fetch --all --quiet; git -C "$src" checkout -q "$commit"; }

# Position-independent code is REQUIRED: on JVM the kyonet_boringssl shim links these static archives
# into a loadable shared object (cc -shared), and GNU ld on aarch64 rejects non-PIC relocations from a
# .a folded into a .so ("relocation R_AARCH64_ADR_PREL_PG_HI21 ... can not be used when making a shared
# object; recompile with -fPIC"). -DCMAKE_POSITION_INDEPENDENT_CODE=ON makes every object PIC, which the
# shared-object link needs and the Native archive link tolerates. Harmless on darwin (already PIC there).
cmake -S "$src" -B "$src/build" -DCMAKE_BUILD_TYPE=Release -DBUILD_SHARED_LIBS=OFF \
    -DCMAKE_POSITION_INDEPENDENT_CODE=ON >/dev/null
cmake --build "$src/build" --target ssl crypto -j"$(getconf _NPROCESSORS_ONLN 2>/dev/null || echo 4)"

dest="$here/staged/$osArch"
rm -rf "$dest"
mkdir -p "$dest/lib"
cp "$src/build/libssl.a" "$src/build/libcrypto.a" "$dest/lib/"
cp -R "$src/include" "$dest/include"
echo "staged BoringSSL $commit for $osArch -> $dest (libssl.a $(wc -c <"$dest/lib/libssl.a") bytes, libcrypto.a $(wc -c <"$dest/lib/libcrypto.a") bytes)"

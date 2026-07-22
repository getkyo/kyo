#!/usr/bin/env bash
# Build the static Aeron C library (libaeron_driver_static.a, which embeds the full client) at the
# pinned 1.50.2 tag and stage it per os-arch for kyo-aeron.
# Run once per runner os-arch before the kyo-aeron compile step; produced archives are
# build artifacts (gitignored), consumed by the kyo_aeron FfiLibrary.
#
# Usage: kyo-aeron/scripts/build-aeron.sh <os-arch>   e.g. linux-x86_64 | linux-aarch64 | darwin-aarch64
# Requires cmake + a C toolchain on PATH (apt: cmake build-essential; brew: cmake).
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
osArch="${1:?os-arch required, e.g. linux-x86_64 / linux-aarch64 / darwin-aarch64}"
AERON_VERSION="1.50.2"
AERON_TAG="$AERON_VERSION"

src="${AERON_SRC:-${TMPDIR:-/tmp}/kyo-aeron-src}"
if [ ! -d "$src/.git" ]; then
    rm -rf "$src"
    git clone --depth 1 --branch "$AERON_TAG" https://github.com/real-logic/aeron.git "$src"
fi
# Re-point a reused cache to the pinned tag: a $src left over from a different AERON_TAG would
# otherwise silently build the wrong Aeron version (then statically linked with no further check).
# Mirrors kyo-net/build/boringssl/build-boringssl.sh's re-checkout-on-every-run.
git -C "$src" fetch --depth 1 origin tag "$AERON_TAG"
git -C "$src" checkout -q "$AERON_TAG"

dest="$here/../build/aeron/staged/$osArch"
rm -rf "$dest"
mkdir -p "$dest/lib" "$dest/include/aeron" "$dest/include/aeronmd"

# -DCMAKE_POSITION_INDEPENDENT_CODE=ON: required on Linux aarch64 (non-PIC .a folded
# into -shared .so fails with R_AARCH64_ADR_PREL_PG_HI21 relocation error).
# Harmless on darwin (already PIC). Mirrors kyo-net/build/boringssl/build-boringssl.sh.
cmake -S "$src" -B "$src/build" \
    -DCMAKE_BUILD_TYPE=Release \
    -DBUILD_SHARED_LIBS=OFF \
    -DCMAKE_POSITION_INDEPENDENT_CODE=ON \
    >/dev/null

cmake --build "$src/build" \
    --target aeron_driver_static \
    -j"$(getconf _NPROCESSORS_ONLN 2>/dev/null || echo 4)"

# Copy the static archive to staged lib/. Only aeron_driver_static is built and linked: it
# already embeds the full client, and adding aeron_static would cause duplicate-symbol link
# errors (see kyo-aeron/CONTRIBUTING.md "The link is driver-only").
cp "$src/build/lib/libaeron_driver_static.a" "$dest/lib/"

# Copy Aeron C client headers (aeronc.h + all public client headers) to staged include/aeron/.
cp "$src/aeron-client/src/main/c/aeronc.h" "$dest/include/aeron/"
# Copy remaining public client headers (collections, concurrent, util, etc.) used by aeronc.h.
find "$src/aeron-client/src/main/c" -name "*.h" ! -name "aeronc.h" \
    -exec cp {} "$dest/include/aeron/" \;

# Copy Aeron media driver header (aeronmd.h) to staged include/aeronmd/.
cp "$src/aeron-driver/src/main/c/aeronmd.h" "$dest/include/aeronmd/"
# Copy remaining driver headers used by aeronmd.h.
find "$src/aeron-driver/src/main/c" -name "*.h" ! -name "aeronmd.h" \
    -exec cp {} "$dest/include/aeronmd/" \;

echo "staged Aeron $AERON_VERSION for $osArch -> $dest"
echo "  lib/libaeron_driver_static.a $(wc -c <"$dest/lib/libaeron_driver_static.a") bytes"
echo "  include/aeron/aeronc.h       $(test -f "$dest/include/aeron/aeronc.h" && echo present || echo MISSING)"
echo "  include/aeronmd/aeronmd.h    $(test -f "$dest/include/aeronmd/aeronmd.h" && echo present || echo MISSING)"

#!/usr/bin/env bash
# Build the static Aeron C library (libaeron_driver_static.a, which embeds the full client) at the
# pinned 1.51.1 tag and stage it per os-arch for kyo-aeron.
# Run once per runner os-arch before the kyo-aeron compile step; produced archives are
# build artifacts (gitignored), consumed by the kyo_aeron FfiLibrary.
#
# Usage: kyo-aeron/scripts/build-aeron.sh [<os-arch>]
#   os-arch is one of linux-x86_64 | linux-aarch64 | linux-musl-x86_64 | linux-musl-aarch64 |
#   darwin-x86_64 | darwin-aarch64 | windows-x86_64 | windows-aarch64, and defaults to this host's.
#   It is not just a directory label: it selects the cross flags and is asserted against the archive
#   actually produced, so `staged/<os-arch>/` always contains code for <os-arch>.
#
#   The only cross-build supported is darwin arch-to-arch (one arm64 Mac covers both Mac arches via
#   -DCMAKE_OSX_ARCHITECTURES). Cross-OS is rejected. Windows cross-arch is rejected too, even
#   though MSVC itself could do it: the kyo-ffi plugin's shim compile has no per-invocation
#   architecture flag for `cl`, so it targets whatever the ambient vcvars environment selected, and
#   a cross-staged archive would pair with a host-arch shim. On Windows the request is therefore
#   checked against the architecture that `cl` targets, not against the machine's.
#
# Requires cmake + a C toolchain on PATH (apt: cmake build-essential uuid-dev;
# apk: cmake build-base util-linux-dev; brew: cmake; Windows: MSVC + cmake).
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
# Host detection, target validation, cross rejection and the arch assertion are shared with
# kyo-net's build-boringssl.sh; see scripts/native-build-lib.sh for why they live in one place.
. "$(cd "$here/../.." && pwd)/scripts/native-build-lib.sh"

native_resolve_target "${1:-}" \
    "linux-x86_64 linux-aarch64 linux-musl-x86_64 linux-musl-aarch64 darwin-x86_64 darwin-aarch64 windows-x86_64 windows-aarch64"

AERON_VERSION="1.51.1"
AERON_TAG="$AERON_VERSION"

src="${AERON_SRC:-${TMPDIR:-/tmp}/kyo-aeron-src}"
if [ ! -d "$src/.git" ]; then
    rm -rf "$src"
    git clone --depth 1 --branch "$AERON_TAG" https://github.com/real-logic/aeron.git "$src"
fi
# Re-point a reused cache to the pinned tag: a $src left over from a different AERON_TAG would
# otherwise silently build the wrong Aeron version (then statically linked with no further check).
git -C "$src" fetch --depth 1 origin tag "$AERON_TAG"
git -C "$src" checkout -q "$AERON_TAG"

dest="$here/../build/aeron/staged/$osArch"
rm -rf "$dest"
mkdir -p "$dest/lib" "$dest/include/aeron" "$dest/include/aeronmd"

# Per-target build dir: CMakeCache.txt pins the generator's architecture, so reusing one dir across
# `darwin-aarch64` then `darwin-x86_64` would rebuild with the FIRST target's arch and stage it under
# the second's name. Separate dirs also let both Mac arches be built on one runner without a wipe.
build="$src/build-$osArch"

# Aeron only supports Windows under MSVC: its sources gate Windows compatibility on `_MSC_VER`
# (e.g. the sys/uio.h shim), so MinGW cannot build it. On Windows the build therefore uses cmake's
# default Visual Studio generator and a multi-config Release build; every other host keeps the
# single-config Unix Makefiles build with PIC (required on Linux aarch64, harmless elsewhere).
# The MSVC environment must already be initialized on Windows (vcvars64.bat / vcvarsarm64.bat):
# the target architecture is read from the `cl` on PATH, and the archive check needs `dumpbin`.
case "$os" in
    windows)
        # -A pins the target platform. Without it the generator picks its own default, which is the
        # host's, and the requested arch would only have renamed the staging directory.
        msvcPlatform="$(native_msvc_platform "$arch")"
        cmake -S "$src" -B "$build" \
            -A "$msvcPlatform" \
            -DBUILD_SHARED_LIBS=OFF \
            >/dev/null
        cmake --build "$build" --target aeron_driver_static --config Release
        # The Visual Studio generator writes the archive under a per-config subdir; find it rather
        # than hard-coding the layout, which varies by cmake version. `sed -n 1p` rather than
        # `head -1`: head closes the pipe and the still-writing find takes SIGPIPE, which under
        # `set -o pipefail` fails the build with 141.
        archive="$(find "$build" -name "aeron_driver_static.lib" | sed -n '1p')"
        [ -n "$archive" ] || { echo "aeron_driver_static.lib not produced" >&2; exit 1; }
        cp "$archive" "$dest/lib/aeron_driver_static.lib"
        staged_lib="$dest/lib/aeron_driver_static.lib"
        ;;
    *)
        # -DCMAKE_POSITION_INDEPENDENT_CODE=ON: required on Linux aarch64 (non-PIC .a folded
        # into -shared .so fails with R_AARCH64_ADR_PREL_PG_HI21 relocation error).
        # Harmless on darwin (already PIC).
        cmakeArgs=(-DCMAKE_BUILD_TYPE=Release -DBUILD_SHARED_LIBS=OFF -DCMAKE_POSITION_INDEPENDENT_CODE=ON)
        if [ "$os" = darwin ]; then
            # This is the flag that makes the cross-build real: without it the argument only renamed
            # the staging directory and arm64 archives shipped as darwin-x86_64.
            cmakeArgs+=(-DCMAKE_OSX_ARCHITECTURES="$(native_osx_architecture "$arch")")
        fi
        cmake -S "$src" -B "$build" "${cmakeArgs[@]}" >/dev/null
        cmake --build "$build" \
            --target aeron_driver_static \
            -j"$(getconf _NPROCESSORS_ONLN 2>/dev/null || echo 4)"
        # Copy the static archive to staged lib/. Only aeron_driver_static is built and linked: it
        # already embeds the full client, and adding aeron_static would cause duplicate-symbol link
        # errors (see kyo-aeron/CONTRIBUTING.md "The link is driver-only").
        cp "$build/lib/libaeron_driver_static.a" "$dest/lib/"
        staged_lib="$dest/lib/libaeron_driver_static.a"
        ;;
esac

# The staged name is a promise every consumer trusts (the sbt build links it, the plugin packages the
# resulting shim as <os-arch>). Assert the archive really is the requested arch, so a cross flag that
# was ignored fails here loudly instead of at some consumer's link, or at their runtime.
native_assert_arch "$staged_lib" "$os" "$arch"

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
echo "  lib archive               $(basename "$staged_lib") ($(wc -c <"$staged_lib") bytes)"
echo "  include/aeron/aeronc.h       $(test -f "$dest/include/aeron/aeronc.h" && echo present || echo MISSING)"
echo "  include/aeronmd/aeronmd.h    $(test -f "$dest/include/aeronmd/aeronmd.h" && echo present || echo MISSING)"

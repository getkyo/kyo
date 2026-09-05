#!/usr/bin/env bash
# Build a static BoringSSL (CMake + Go) at the pinned commit and stage it per os-arch for kyo-net
# (RI-006 / AD-007 / D6). Run once per runner os-arch before the kyo-net package/test step; the
# produced libssl.a + libcrypto.a + headers are build artifacts (never committed), consumed by the
# kyonet_boringssl FfiLibrary (the kyo_net_boringssl.c shim links them into a loadable lib) and by Scala
# Native's archive link.
#
# Usage: build-boringssl.sh [<os-arch>]
#   os-arch is one of linux-x86_64 | linux-aarch64 | linux-musl-x86_64 | linux-musl-aarch64 |
#   darwin-x86_64 | darwin-aarch64, and defaults to this host's. It is not just a directory label:
#   it selects the cross flags and is asserted against the archives actually produced, so
#   `staged/<os-arch>/` always contains code for <os-arch>.
#
#   The only cross-build supported is darwin arch-to-arch (one arm64 Mac covers both Mac arches via
#   -DCMAKE_OSX_ARCHITECTURES). A cross-OS build would need a CMake toolchain file and is rejected
#   rather than built as the host's, which is what would silently ship the wrong platform.
#
# Host detection, target validation, cross rejection and the arch assertion are shared with
# kyo-aeron's build-aeron.sh; see scripts/native-build-lib.sh for why they live in one place.
#
# Requires cmake + go + a C/C++ toolchain on PATH (apt: cmake golang build-essential; brew: cmake go).
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"

. "$(cd "$here/../../.." && pwd)/scripts/native-build-lib.sh"

native_resolve_target "${1:-}" \
    "linux-x86_64 linux-aarch64 linux-musl-x86_64 linux-musl-aarch64 darwin-x86_64 darwin-aarch64"

commit="$(grep -vE '^[[:space:]]*#' "$here/BORINGSSL_COMMIT" | head -n1 | tr -d '[:space:]')"
[ "${#commit}" -eq 40 ] || { echo "BORINGSSL_COMMIT is not a 40-char commit: '$commit'" >&2; exit 1; }

src="${BORINGSSL_SRC:-${TMPDIR:-/tmp}/kyonet-boringssl-src}"
# Clone from the GitHub mirror rather than boringssl.googlesource.com: googlesource rate-limits
# anonymous clones from CI/datacenter IP ranges and returns intermittent 503s on GitHub-hosted
# runners. The mirror carries the same history, so the pinned commit below resolves identically.
# Override with BORINGSSL_GIT_URL if a different source is ever needed.
boringssl_url="${BORINGSSL_GIT_URL:-https://github.com/google/boringssl}"
clone_src() {
    rm -rf "$src"
    attempt=1
    until git clone "$boringssl_url" "$src"; do
        [ "$attempt" -ge 3 ] && { echo "git clone $boringssl_url failed after $attempt attempts" >&2; exit 1; }
        echo "git clone $boringssl_url failed (attempt $attempt); retrying in $((attempt * 5))s" >&2
        sleep $((attempt * 5))
        attempt=$((attempt + 1))
    done
}
# `[ -d "$src/.git" ]` is not a usable-clone check: the default $src lives under $TMPDIR, which the OS
# reaps, and it reaps it to a skeleton (.git/ survives with HEAD, config and index gone). That skeleton
# passes a directory test, and every git command below then fails with no way back. Probe the clone
# instead, and treat a failing checkout (a wiped object store, an interrupted clone) as re-clone too,
# so the script always recovers on its own.
git -C "$src" rev-parse --git-dir >/dev/null 2>&1 || clone_src
# Full clone has every commit; check out the exact pin (fetch first if a fresh bump is not present).
git -C "$src" checkout -q "$commit" 2>/dev/null \
    || { git -C "$src" fetch --all --quiet >/dev/null 2>&1 && git -C "$src" checkout -q "$commit" 2>/dev/null; } \
    || { clone_src; git -C "$src" checkout -q "$commit"; }

# Per-target build dir: CMakeCache.txt pins CMAKE_OSX_ARCHITECTURES, so reusing one dir across
# `darwin-aarch64` then `darwin-x86_64` would rebuild with the FIRST target's arch and stage it under
# the second's name. Separate dirs also let both Mac arches be built on one runner without a wipe.
build="$src/build-$osArch"

# Position-independent code is REQUIRED: on JVM the kyonet_boringssl shim links these static archives
# into a loadable shared object (cc -shared), and GNU ld on aarch64 rejects non-PIC relocations from a
# .a folded into a .so ("relocation R_AARCH64_ADR_PREL_PG_HI21 ... can not be used when making a shared
# object; recompile with -fPIC"). -DCMAKE_POSITION_INDEPENDENT_CODE=ON makes every object PIC, which the
# shared-object link needs and the Native archive link tolerates. Harmless on darwin (already PIC there).
cmakeArgs=(-DCMAKE_BUILD_TYPE=Release -DBUILD_SHARED_LIBS=OFF -DCMAKE_POSITION_INDEPENDENT_CODE=ON)
if [ "$os" = darwin ]; then
    # This is the flag that makes the cross-build real: without it the argument only renamed the
    # staging directory and arm64 archives shipped as darwin-x86_64.
    cmakeArgs+=(-DCMAKE_OSX_ARCHITECTURES="$(native_osx_architecture "$arch")")
fi

# BoringSSL's Go tooling: no GOARCH is set, and none is needed. The pinned commit ships checked-in
# pregenerated per-arch assembly under gen/, so the Go step is host-only; a real darwin-x86_64
# cross-build on an arm64 Mac with only -DCMAKE_OSX_ARCHITECTURES=x86_64 produced x86_64 archives
# (lipo-verified), which the assert below confirms every run. Should a future BORINGSSL_COMMIT bump
# reintroduce a target-arch Go build, the assert will fail here; the fix is to export the matching
# GOARCH (amd64 for x86_64, arm64 for aarch64) before the cmake build.
cmake -S "$src" -B "$build" "${cmakeArgs[@]}" >/dev/null
cmake --build "$build" --target ssl crypto -j"$(getconf _NPROCESSORS_ONLN 2>/dev/null || echo 4)"

# The staged name is a promise every consumer trusts (the sbt build links it, the plugin packages the
# resulting shim as <os-arch>). Assert the archives really are the requested arch, so a cross flag that
# was ignored fails here loudly instead of at some consumer's link, or at their runtime.
native_assert_arch "$build/libssl.a" "$os" "$arch"
native_assert_arch "$build/libcrypto.a" "$os" "$arch"

dest="$here/staged/$osArch"
rm -rf "$dest"
mkdir -p "$dest/lib"
cp "$build/libssl.a" "$build/libcrypto.a" "$dest/lib/"
cp -R "$src/include" "$dest/include"
echo "staged BoringSSL $commit for $osArch -> $dest (libssl.a $(wc -c <"$dest/lib/libssl.a") bytes, libcrypto.a $(wc -c <"$dest/lib/libcrypto.a") bytes)"

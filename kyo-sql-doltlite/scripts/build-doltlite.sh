#!/usr/bin/env bash
# Fetch DoltLite's prebuilt library at the pinned version and stage it for kyo-sql-doltlite.
# Run once before the kyo-sql-doltlite compile step; the staged tree is a build artifact (gitignored),
# consumed by the kyo_doltlite FfiLibrary, which compiles this module's shim against it.
#
# Usage: kyo-sql-doltlite/scripts/build-doltlite.sh [os-arch]
#
# Stages PER OS-ARCH: DoltLite ships a compiled library per platform, so the artifact itself varies.
# Upstream publishes one for osx-arm64, linux-x64, linux-arm64 and win-x64; every other platform builds
# the same pinned tag from source, which produces exactly what the published zip contains.
#
# The target defaults to the host. Name it where one job stages more than one, the way
# build-boringssl.sh and build-aeron.sh are called: a macOS runner produces both Mac targets from one
# checkout, and staging only the host leaves the cross compile with nothing to link.
#
# Requires curl, plus any one of shasum/sha256sum/openssl for the checksum and any one of
# unzip/bsdtar/7z to extract, because the Windows runners carry neither shasum nor unzip reliably;
# every other platform has the first of each. Additionally git + cc + make + tcl on a platform with
# no published library.
set -euo pipefail

DOLTLITE_VERSION="0.50.10"

here="$(cd "$(dirname "$0")" && pwd)"
module="$(cd "$here/.." && pwd)"

# The staged path uses the FFI PLUGIN's os-arch tag, because that is what build.sbt looks under:
# CCompiler.detectOs answers darwin/linux/linux-musl and detectArch answers aarch64/x86_64. DoltLite
# names its published assets differently, so the two are mapped below.
osarch="${1:-}"
if [[ -z "$osarch" ]]; then
    uname_s="$(uname -s)"
    uname_m="$(uname -m)"
    case "$uname_s" in
        Darwin) os="darwin" ;;
        Linux)
            if [[ -f /lib/ld-musl-x86_64.so.1 || -f /lib/ld-musl-aarch64.so.1 ]]; then os="linux-musl"; else os="linux"; fi
            ;;
        # Git Bash and MSYS on a Windows runner.
        MINGW*|MSYS*|CYGWIN*) os="windows" ;;
        *) echo "[kyo-sql-doltlite] unsupported OS: $uname_s" >&2; exit 1 ;;
    esac
    case "$uname_m" in
        arm64|aarch64) arch="aarch64" ;;
        x86_64|amd64)  arch="x86_64" ;;
        *) echo "[kyo-sql-doltlite] unsupported architecture: $uname_m" >&2; exit 1 ;;
    esac
    osarch="$os-$arch"
fi

# Checked rather than trusted: a misspelled target would stage into a directory the build never reads,
# and the failure would surface as a missing library at link time instead of here.
case "$osarch" in
    darwin-aarch64|darwin-x86_64) ;;
    linux-x86_64|linux-aarch64) ;;
    linux-musl-x86_64|linux-musl-aarch64) ;;
    windows-x86_64|windows-aarch64)
        echo "[kyo-sql-doltlite] DoltLite is not staged on Windows." >&2
        echo "  The win-x64 release carries doltlite.h and libdoltlite.dll only, with no static archive" >&2
        echo "  and no import library, and the shim links the archive rather than the DLL. The module" >&2
        echo "  declares itself absent here (kyoSqlDoltLiteOsArchTargets), so nothing needs staging." >&2
        exit 0
        ;;
    *) echo "[kyo-sql-doltlite] unsupported target: $osarch" >&2; exit 1 ;;
esac

# DoltLite's own asset naming, and the sha256 of each. Update the version and every checksum together.
# There is no published osx-x64, musl or win-arm64 build; those fall through to the source build.
case "$osarch" in
    darwin-aarch64)
        asset="osx-arm64"
        sha256="914aa79c6f5af1c1315ab0f86e717a9ada268202ee96a7f278ba2ba9eacc8d89"
        ;;
    linux-x86_64)
        asset="linux-x64"
        sha256="b9abb9f2da834dd622844aea506aee665602206f7c804edc1c22b727b9767e49"
        ;;
    linux-aarch64)
        asset="linux-arm64"
        sha256="5dd0d54584b80eef2fc8dbc78f1545b56d8a7930c7e5752cceca0385ef767309"
        ;;
    windows-x86_64)
        asset="win-x64"
        sha256="b5255f516da383f8f5c288c4d8c9b98d1ff84fdc50f1214133c4632daa834702"
        ;;
    *)
        # No published library for this platform, so build the same pinned tag from source below.
        asset=""
        sha256=""
        ;;
esac

staged="$module/build/doltlite/staged/$osarch"
work="$module/build/doltlite/work"

if [[ -f "$staged/doltlite.h" ]]; then
    echo "[kyo-sql-doltlite] DoltLite $DOLTLITE_VERSION already staged at $staged"
    exit 0
fi

mkdir -p "$work" "$staged"

# --- No published library for this platform: build the pinned tag from source ---
#
# Produces the same artifacts the published zip carries, through DoltLite's own `doltlite-lib` target.
if [[ -z "$asset" ]]; then
    for tool in git cc make tclsh; do
        command -v "$tool" >/dev/null 2>&1 || {
            echo "[kyo-sql-doltlite] $osarch has no published library, so it is built from source," >&2
            echo "which needs $tool on PATH." >&2
            exit 1
        }
    done

    src="$module/build/doltlite/src"
    if [[ ! -d "$src/.git" ]]; then
        echo "[kyo-sql-doltlite] cloning DoltLite v$DOLTLITE_VERSION for a source build ($osarch)"
        rm -rf "$src"
        git clone --depth 1 --branch "v$DOLTLITE_VERSION" https://github.com/dolthub/doltlite.git "$src"
    fi

    # Cross-building on macOS is an -arch flag rather than a compiler prefix: passing --host makes
    # configure hunt for x86_64-apple-darwin-cc and fail.
    conf_env=()
    case "$osarch" in
        darwin-x86_64) conf_env=(CC="cc -arch x86_64" CFLAGS="-arch x86_64" LDFLAGS="-arch x86_64") ;;
        darwin-aarch64) conf_env=(CC="cc -arch arm64" CFLAGS="-arch arm64" LDFLAGS="-arch arm64") ;;
        *) : ;; # every other producer builds natively for its own host
    esac

    build="$src/build-$osarch"
    rm -rf "$build"; mkdir -p "$build"
    (
        cd "$build"
        env "${conf_env[@]}" ../configure >configure.log 2>&1 || { tail -20 configure.log >&2; exit 1; }
        make doltlite-lib >make.log 2>&1 || { tail -30 make.log >&2; exit 1; }
    ) || { echo "[kyo-sql-doltlite] source build failed for $osarch" >&2; exit 1; }

    cp "$build/doltlite.h" "$staged/doltlite.h"
    cp "$build/libdoltlite.a" "$staged/libdoltlite.a"
    cp "$build"/libdoltlite.dylib "$staged/" 2>/dev/null || true
    cp "$build"/libdoltlite.so* "$staged/" 2>/dev/null || true
    cp "$build"/doltlite.dll "$staged/" 2>/dev/null || true
    echo "[kyo-sql-doltlite] built DoltLite $DOLTLITE_VERSION from source at $staged"
    exit 0
fi

# --- Published library: download and verify ---
dist="doltlite-lib-$asset-$DOLTLITE_VERSION"
zip="$work/$dist.zip"
url="https://github.com/dolthub/doltlite/releases/download/v$DOLTLITE_VERSION/$dist.zip"

echo "[kyo-sql-doltlite] fetching $url"
curl -fsSL --retry 3 -o "$zip" "$url"

# Whichever of the three this platform has. Checked rather than assumed: a missing tool would
# otherwise make `actual` empty, which compares unequal and reports a checksum mismatch, sending a
# reader after a corrupt download that never happened. The Windows runners carry none of shasum.
if command -v shasum >/dev/null 2>&1; then
    actual="$(shasum -a 256 "$zip" | awk '{print $1}')"
elif command -v sha256sum >/dev/null 2>&1; then
    actual="$(sha256sum "$zip" | awk '{print $1}')"
elif command -v openssl >/dev/null 2>&1; then
    actual="$(openssl dgst -sha256 "$zip" | awk '{print $NF}')"
else
    echo "[kyo-sql-doltlite] no sha256 tool found (tried shasum, sha256sum, openssl)." >&2
    echo "Install one, or stage $staged by hand from $url." >&2
    exit 1
fi
if [[ "$actual" != "$sha256" ]]; then
    echo "[kyo-sql-doltlite] checksum mismatch for $dist.zip" >&2
    echo "  expected $sha256" >&2
    echo "  actual   $actual" >&2
    echo "Refusing to stage. If the version was bumped, update the checksum in this script together with it." >&2
    exit 1
fi

# bsdtar (the `tar` Windows 10+ and macOS ship) extracts zip, which is what makes the Windows
# runners work without unzip. 7z is the last resort, preinstalled on GitHub's Windows images.
if command -v unzip >/dev/null 2>&1; then
    unzip -o -q "$zip" -d "$work"
elif tar --version 2>/dev/null | grep -qi bsdtar; then
    (cd "$work" && tar -xf "$zip")
elif command -v 7z >/dev/null 2>&1; then
    7z x -y -o"$work" "$zip" >/dev/null
else
    echo "[kyo-sql-doltlite] no zip extractor found (tried unzip, bsdtar, 7z)." >&2
    exit 1
fi
cp "$work/$dist/doltlite.h" "$staged/doltlite.h"
cp "$work/$dist/libdoltlite.a" "$staged/libdoltlite.a"
# The shared library is staged too: the JVM and Node load it at run time rather than linking it.
cp "$work/$dist/libdoltlite.dylib" "$staged/" 2>/dev/null || true
cp "$work/$dist/libdoltlite.so" "$staged/" 2>/dev/null || true
cp "$work/$dist/doltlite.dll" "$staged/" 2>/dev/null || true
cp "$work/$dist/libdoltlite.dll" "$staged/" 2>/dev/null || true
rm -rf "$work/$dist" "$zip"

echo "[kyo-sql-doltlite] staged DoltLite $DOLTLITE_VERSION at $staged"

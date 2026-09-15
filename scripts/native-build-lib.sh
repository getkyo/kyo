#!/usr/bin/env bash
# Shared machinery for the vendored-native build scripts (kyo-net's BoringSSL, kyo-aeron's libaeron).
#
# Both answer the same question: produce this third-party static library for <os-arch> and stage it
# where the sbt build expects it. Everything around that question lives here, so the two cannot
# disagree about which host they run on, which targets are buildable from it, or whether the archive
# produced is really for the architecture it is staged as.
#
# Usage: source this file, then call `native_resolve_target` and `native_assert_arch`.
#
#   . "$(cd "$(dirname "$0")/../.." && pwd)/scripts/native-build-lib.sh"   # path varies per caller
#   native_resolve_target "${1:-}" "linux-x86_64 linux-aarch64 darwin-aarch64"
#   # -> sets osArch, os, arch, hostOs, hostArch
#
# Callers keep their own source acquisition (a tag-pinned shallow clone and a commit-pinned full
# clone are genuinely different) and their own cmake invocation.

# --- host identification -------------------------------------------------------------------------

# The OS this script is running on. The musl probe mirrors CCompiler.detectOsWith (kyo-ffi plugin)
# and NativeLoader.detectOs (kyo-ffi/jvm): on Alpine the staged tree must be named
# linux-musl-<arch>, because that is where both the sbt build and the runtime look for it.
native_host_os() {
    case "$(uname -s)" in
        Darwin) echo darwin ;;
        Linux)
            if [ -e /lib/ld-musl-x86_64.so.1 ] || [ -e /lib/ld-musl-aarch64.so.1 ]; then
                echo linux-musl
            else
                echo linux
            fi
            ;;
        # Git Bash / MSYS2 identify as MINGW64_NT-* or MSYS_NT-*; both are Windows hosts running a
        # POSIX shell, and the toolchain the callers drive there is MSVC.
        MINGW*|MSYS*|CYGWIN*) echo windows ;;
        *) echo "unsupported build host: $(uname -s)" >&2; return 1 ;;
    esac
}

# The architecture this script builds for by default, and the one a cross-arch request is checked
# against. Off Windows this is the machine's, matching CCompiler.detectArch.
#
# On Windows this is deliberately NOT the machine's architecture. The kyo-ffi plugin invokes `cl`
# with no target flag (CCompiler.scala), so the shim is built for whatever the ambient MSVC
# environment selected, and what a staged archive has to match is that selection. The MSYS bash the
# Windows runners provide answers x86_64 to `uname -m` even where vcvars has selected the ARM64
# tools, so asking the shell produces a cross-arch rejection on the machine's own native build.
# The MSVC toolset lays its binaries out as bin/Host<host>/<target>/cl.exe, so the `cl` that will do
# the compiling names its own target.
native_host_arch() {
    local raw
    case "$(uname -s)" in
        MINGW*|MSYS*|CYGWIN*)
            local cl
            cl="$(command -v cl.exe 2>/dev/null || command -v cl 2>/dev/null)"
            if [ -z "$cl" ]; then
                echo "MSVC environment not initialized: 'cl' is not on PATH." >&2
                echo "Run vcvars64.bat or vcvarsarm64.bat first; this script needs cl and dumpbin, and takes its target from them." >&2
                return 1
            fi
            local hostDir; hostDir="$(basename "$(dirname "$(dirname "$cl")")")"
            case "$hostDir" in
                Host*) ;;
                *)
                    echo "unexpected MSVC toolset layout for '$cl': expected .../bin/Host<host>/<target>/cl.exe" >&2
                    return 1
                    ;;
            esac
            raw="$(basename "$(dirname "$cl")")"
            ;;
        *) raw="$(uname -m)" ;;
    esac
    case "$raw" in
        x86_64|amd64|AMD64|x64) echo x86_64 ;;
        aarch64|arm64|ARM64)    echo aarch64 ;;
        *) echo "unsupported build host architecture: $raw" >&2; return 1 ;;
    esac
}

# --- target resolution ---------------------------------------------------------------------------

# Resolve and validate the requested `<os-arch>`, defaulting to this host's.
#
# $1  the requested os-arch, or empty for the host's
# $2  space-separated list of os-arch tags this caller supports
#
# Sets: osArch, os, arch, hostOs, hostArch.
#
# The argument is NOT a directory label. It selects the cross flags the caller applies and is
# asserted against the archives actually produced, so `staged/<os-arch>/` always holds code for
# <os-arch>. The only cross-build permitted is darwin arch-to-arch, where one arm64 Mac covers both
# Mac targets via -DCMAKE_OSX_ARCHITECTURES. A cross-OS build would need a CMake toolchain file, and
# a cross-arch build anywhere else would need a full cross toolchain; both are rejected rather than
# silently built as the host's, which is what ships the wrong platform.
native_resolve_target() {
    local requested="${1:-}" supported="${2:?supported os-arch list required}"

    hostOs="$(native_host_os)" || return 1
    hostArch="$(native_host_arch)" || return 1

    osArch="${requested:-$hostOs-$hostArch}"
    # Split at the LAST hyphen: the os itself carries one (linux-musl-x86_64 is linux-musl + x86_64).
    arch="${osArch##*-}"
    os="${osArch%-*}"

    local ok=0 candidate
    for candidate in $supported; do
        [ "$candidate" = "$osArch" ] && ok=1
    done
    if [ "$ok" != 1 ]; then
        echo "unsupported os-arch '$osArch' (supported: $supported)" >&2
        return 1
    fi

    if [ "$os" != "$hostOs" ]; then
        echo "cannot build '$osArch' on a '$hostOs-$hostArch' host: only a darwin arch cross-build is supported." >&2
        echo "Run this on a '$os' runner/container, or omit the argument to build for the host ($hostOs-$hostArch)." >&2
        return 1
    fi
    if [ "$os" != darwin ] && [ "$arch" != "$hostArch" ]; then
        echo "cannot build '$osArch' on a '$hostOs-$hostArch' host: cross-arch is supported on darwin only." >&2
        return 1
    fi
}

# Apple spells aarch64 "arm64". Echoes the -DCMAKE_OSX_ARCHITECTURES value for $arch.
native_osx_architecture() {
    case "$1" in
        x86_64)  echo x86_64 ;;
        aarch64) echo arm64 ;;
        *) echo "no darwin architecture mapping for '$1'" >&2; return 1 ;;
    esac
}

# The cmake `-A` platform name for a Windows Visual Studio generator. Without an explicit -A the
# generator picks its own default, which is the host's and silently ignores the requested target.
native_msvc_platform() {
    case "$1" in
        x86_64)  echo x64 ;;
        aarch64) echo ARM64 ;;
        *) echo "no MSVC platform mapping for '$1'" >&2; return 1 ;;
    esac
}

# --- architecture assertion ----------------------------------------------------------------------

# Assert that a produced static archive really is for `$2-$3`, so a cross flag that was ignored
# fails here loudly instead of at a consumer's link, or at their runtime.
#
# $1  path to the archive
# $2  os      (darwin | linux | linux-musl | windows)
# $3  arch    (x86_64 | aarch64)
#
# The publish-time guard (NativeArtifactFormat) checks the shipped shared libraries; this checks the
# third-party ARCHIVE they are linked from, which never reaches that guard. A cross flag the build
# ignored is caught here or not at all.
native_assert_arch() {
    local archive_in="$1" os="$2" arch="$3"
    # Absolute, because the linux branch inspects an extracted member from a temp cwd.
    local archive; archive="$(cd "$(dirname "$archive_in")" && pwd)/$(basename "$archive_in")"
    case "$os" in
        darwin)
            local want; want="$(native_osx_architecture "$arch")" || return 1
            local got; got="$(lipo -archs "$archive")"
            case " $got " in
                *" $want "*) ;;
                *)
                    echo "arch mismatch: $archive contains '$got', expected '$want' for $os-$arch" >&2
                    return 1
                    ;;
            esac
            ;;
        linux|linux-musl)
            # `file` on an ar archive reports the container, not the ISA, so inspect a member.
            if ! command -v ar >/dev/null 2>&1 || ! command -v file >/dev/null 2>&1; then
                echo "cannot verify $archive: this check needs 'ar' and 'file' (apt: binutils file; apk: binutils file)." >&2
                return 1
            fi
            # head closes the pipe after line 1; under `set -o pipefail` the still-writing ar takes
            # SIGPIPE (busybox head on the Alpine/musl leg) and fails the build with 141. sed -n reads to EOF.
            local member; member="$(ar t "$archive" | sed -n '1p')"
            local tmp; tmp="$(mktemp -d)"
            ( cd "$tmp" && ar x "$archive" "$member" )
            local desc; desc="$(file -b "$tmp/$member")"
            rm -rf "$tmp"
            local pattern
            case "$arch" in
                x86_64)  pattern='x86-64' ;;
                aarch64) pattern='aarch64|ARM aarch64' ;;
            esac
            echo "$desc" | grep -qE "$pattern" || {
                echo "arch mismatch: $archive member '$member' is '$desc', expected $arch for $os-$arch" >&2
                return 1
            }
            ;;
        windows)
            # dumpbin, not ar. MSVC's lib.exe records member names as the paths cmake passed
            # (`aeron_driver_static.dir/Release/foo.obj`), which GNU ar lists but cannot extract:
            # `ar x` reports "no entry in archive" and writes nothing. dumpbin reads the archive its
            # own toolchain produced, and vcvars has already put it on PATH wherever this runs.
            if ! command -v dumpbin >/dev/null 2>&1; then
                echo "cannot verify $archive: dumpbin is not on PATH (initialize the MSVC environment)." >&2
                return 1
            fi
            local want
            case "$arch" in
                x86_64)  want="x64" ;;
                aarch64) want="ARM64" ;;
            esac
            # One "machine (<name>)" line per member; they agree, so the first speaks for the archive.
            local got
            got="$(dumpbin //headers "$archive" | sed -n 's/.*machine (\([^)]*\)).*/\1/p' | sed -n '1p')"
            [ -n "$got" ] || {
                echo "cannot verify $archive: dumpbin reported no machine field for it." >&2
                return 1
            }
            [ "$got" = "$want" ] || {
                echo "arch mismatch: $archive is machine '$got', expected '$want' for $os-$arch" >&2
                return 1
            }
            ;;
    esac
}

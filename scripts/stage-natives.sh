#!/usr/bin/env bash
# Stages the vendored native trees the FFI modules link. The one list of those trees: the stage-natives action and
# scripts/build.sh both run this, so a tree added here is staged by CI and by a CI-faithful local run alike.
#
#   stage-natives.sh            stage every tree whose STAGE_<TREE> is not off
#   stage-natives.sh --trees    print the tree names
#
# STAGE_<TREE> takes true/1 or false/0 (the action passes its input strings, build.sh its flags); unset is on.
# OS_ARCHS is a space-separated list of kyo os-arch tags; empty stages the host's own.
set -euo pipefail

# Per-os-arch trees first, in staging order, then SQLite, which is C source the FFI plugin compiles per platform.
ARCH_TREES="boringssl aeron doltlite"
SHARED_TREES="sqlite"

if [ "${1:-}" = --trees ]; then
    echo "$ARCH_TREES $SHARED_TREES"
    exit 0
fi
[ $# -eq 0 ] || { echo "usage: stage-natives.sh [--trees]" >&2; exit 2; }

cd "$(dirname "$0")/.."

enabled() {
    local var
    var="STAGE_$(printf '%s' "$1" | tr '[:lower:]' '[:upper:]')"
    case "${!var:-true}" in
        true | 1) return 0 ;;
        false | 0) return 1 ;;
        *) echo "stage-natives: $var='${!var}' is not true/1 or false/0" >&2; exit 2 ;;
    esac
}

windows=0
case "${RUNNER_OS:-$(uname -s)}" in Windows | MINGW* | MSYS* | CYGWIN*) windows=1 ;; esac

# An empty list iterates once with no argument, which every script takes as its host.
for arch in ${OS_ARCHS:-""}; do
    if enabled boringssl; then
        # An explicit skip rather than a silent one: Windows ships no BoringSSL native by ruling (NIO + the JDK's TLS),
        # so a bare exit would ship the kyonet_boringssl stub and the TLS tests would cancel with no signal.
        if [ "$windows" = 1 ]; then
            echo "Windows ships no BoringSSL native (NIO + JDK TLS by ruling); skipping."
        else
            bash kyo-net/build/boringssl/build-boringssl.sh $arch
        fi
    fi
    if enabled aeron; then bash kyo-aeron/scripts/build-aeron.sh $arch; fi
    # build-doltlite.sh declines Windows itself.
    if enabled doltlite; then bash kyo-sql-doltlite/scripts/build-doltlite.sh $arch; fi
done
if enabled sqlite; then bash kyo-sql-sqlite/scripts/build-sqlite.sh; fi

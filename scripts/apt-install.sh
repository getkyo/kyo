#!/usr/bin/env bash
set -uo pipefail
#
# apt-install.sh - install apt packages with bounded retries that survive mirror hangs.
#
# Usage: apt-install.sh [--if-missing] <package>...
#
# With --if-missing, each argument may be "pkg" or "cmd:pkg". A package is skipped when dpkg
# already has it installed, or when the command (the part before ":", defaulting to the package
# name) is already on PATH. The PATH check covers tools the runner image ships outside dpkg, such
# as the /usr/local podman on the 20260810.x images (#1881) or the tarball-installed go, where a
# plain apt install would break or duplicate the image's stack. When nothing is missing the
# script exits 0 without touching apt.
#
# Each attempt runs apt-get update + install under `sudo timeout`: timeout must run as root so it
# can signal the root-owned apt-get when the deadline expires. Wrapping `sudo apt-get` in a
# non-root supervisor instead (e.g. nick-fields/retry) fails with EPERM at kill time, because the
# runner user cannot signal a root process, turning an apt mirror hang into a hard job failure.
# apt-level Acquire::Retries covers transient fetch errors within an attempt.

if_missing=""
if [ "${1:-}" = "--if-missing" ]; then
    if_missing=1
    shift
fi

[ $# -ge 1 ] || { echo "usage: $0 [--if-missing] <package>..." >&2; exit 2; }

if [ -n "$if_missing" ]; then
    missing=()
    for arg in "$@"; do
        cmd="${arg%%:*}"
        pkg="${arg#*:}"
        if dpkg -s "$pkg" >/dev/null 2>&1; then
            echo "$pkg already installed (dpkg)"
        elif command -v "$cmd" >/dev/null 2>&1; then
            echo "$pkg already present: $cmd at $(command -v "$cmd")"
        else
            missing+=("$pkg")
        fi
    done
    if [ ${#missing[@]} -eq 0 ]; then
        echo "nothing to install"
        exit 0
    fi
    set -- "${missing[@]}"
fi

for attempt in 1 2 3; do
    if sudo timeout -k 30 300 apt-get update &&
        sudo timeout -k 30 300 apt-get install -y -o Acquire::Retries=3 "$@"; then
        exit 0
    fi
    echo "apt-get attempt $attempt failed; retrying in 15s" >&2
    sleep 15
done
echo "apt-get failed after 3 attempts: $*" >&2
exit 1

#!/usr/bin/env bash
set -uo pipefail
#
# apt-install.sh - install apt packages with bounded retries that survive mirror hangs.
#
# Usage: apt-install.sh <package>...
#
# Each attempt runs apt-get update + install under `sudo timeout`: timeout must run as root so it
# can signal the root-owned apt-get when the deadline expires. Wrapping `sudo apt-get` in a
# non-root supervisor instead (e.g. nick-fields/retry) fails with EPERM at kill time, because the
# runner user cannot signal a root process, turning an apt mirror hang into a hard job failure.
# apt-level Acquire::Retries covers transient fetch errors within an attempt.

[ $# -ge 1 ] || { echo "usage: $0 <package>..." >&2; exit 2; }

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

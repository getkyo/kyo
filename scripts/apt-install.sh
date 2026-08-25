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
# apt-level Acquire::Retries covers transient fetch errors within an attempt; the attempt loop uses
# exponential backoff to ride out a longer outage. GitHub's runners resolve the archive through a
# mirrorlist (/etc/apt/apt-mirrors.txt: azure.archive.ubuntu.com primary, archive.ubuntu.com
# fallback); azure intermittently 5xx or hangs, and apt's built-in fallback is only partial (under
# an outage it refetches the Release files from archive but can leave a pocket's Packages index
# Ign'd from azure, which then fails the install). So after the first failed attempt the loop
# rewrites the azure host to archive.ubuntu.com wherever it appears (the mirrorlist, or a directly
# pinned sources.list on older images), forcing every index off the failing mirror. It is a
# host-only substitution that cannot restructure the file, and it runs only on the failure path, so
# a healthy first-attempt install is untouched.

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

max_attempts=5
backoff=15
mirror_swapped=""
for attempt in $(seq 1 "$max_attempts"); do
    if sudo timeout -k 30 300 apt-get update &&
        sudo timeout -k 30 300 apt-get install -y -o Acquire::Retries=3 "$@"; then
        exit 0
    fi
    [ "$attempt" -ge "$max_attempts" ] && break
    # One-time, failure-path only: force the canonical Ubuntu archive over the failing azure mirror,
    # in the mirrorlist (current images) or a directly pinned sources.list (older images).
    if [ -z "$mirror_swapped" ]; then
        mirror_swapped=1
        azure_files=$(grep -rl 'azure.archive.ubuntu.com' \
            /etc/apt/apt-mirrors.txt /etc/apt/sources.list /etc/apt/sources.list.d 2>/dev/null || true)
        if [ -n "$azure_files" ]; then
            echo "apt-install: azure mirror failing, forcing archive.ubuntu.com" >&2
            printf '%s\n' "$azure_files" | sudo xargs -r sed -i 's|azure.archive.ubuntu.com|archive.ubuntu.com|g' 2>/dev/null || true
        fi
    fi
    echo "apt-get attempt $attempt failed; retrying in ${backoff}s" >&2
    sleep "$backoff"
    backoff=$((backoff * 2))
done
echo "apt-get failed after $max_attempts attempts: $*" >&2
exit 1

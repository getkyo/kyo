#!/usr/bin/env bash
set -uo pipefail
#
# apt-install.sh - install apt packages from a .deb cache when one holds them, else from the mirrors with bounded retries
# that survive mirror hangs.
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
# With KYO_APT_CACHE set, each package list (its exact arguments, version pins included) has a directory under it holding the
# .debs a mirror install of that list downloaded. A complete directory installs from those files, contacting no mirror, and
# counts only when every package is then installed at its pinned version; anything else falls back to the mirrors.
# A mirror install fills the directory, and .github/actions/caches carries it between jobs, so a mirror outage fails a job
# only when the cache has never held its list.
#
# Each attempt runs apt-get update + install under `sudo timeout`: timeout must run as root so it
# can signal the root-owned apt-get when the deadline expires. Wrapping `sudo apt-get` in a
# non-root supervisor instead (e.g. nick-fields/retry) fails with EPERM at kill time, because the
# runner user cannot signal a root process, turning an apt mirror hang into a hard job failure.
# apt-level Acquire::Retries covers transient fetch errors within an attempt; the attempt loop uses
# exponential backoff to ride out a longer outage. GitHub's runners resolve the archive through a
# mirrorlist (/etc/apt/apt-mirrors.txt: http://azure.archive.ubuntu.com primary, https://archive.ubuntu.com
# fallback); azure intermittently 5xx or hangs, and apt's built-in fallback is only partial (under
# an outage it refetches the Release files from archive but can leave a pocket's Packages index
# Ign'd from azure, which then fails the install). So after the first failed attempt the loop
# replaces the azure host with the canonical host over https wherever it appears (the mirrorlist, or a
# directly pinned sources.list on older images), and moves any remaining plain-http Ubuntu host to
# https. The azure mirror serves no https, and an outage has taken plain http down on archive.ubuntu.com
# too while its https answered, so a swap that kept http left the Packages index Ign'd on every attempt.
# It is a URL-only substitution that cannot restructure the file, and it runs only on the failure path,
# so a healthy first-attempt install is untouched.

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

# Every requested package is installed, and a pinned one ("pkg=version") at that version.
installed_as_asked() {
    local arg name want got
    for arg in "$@"; do
        name="${arg%%=*}"
        want=""
        [ "$name" != "$arg" ] && want="${arg#*=}"
        got=$(dpkg-query -W -f='${db:Status-Status} ${Version}' "$name" 2>/dev/null) || return 1
        [ "${got%% *}" = installed ] || return 1
        [ -z "$want" ] || [ "${got#* }" = "$want" ] || return 1
    done
}

set_dir=""
if [ -n "${KYO_APT_CACHE:-}" ]; then
    set_dir="$KYO_APT_CACHE/$(printf '%s\n' "$@" | sha256sum | cut -c1-16)"
    if [ -f "$set_dir/complete" ]; then
        debs=()
        for deb in "$set_dir"/*.deb; do [ -e "$deb" ] && debs+=("$deb"); done
        # An empty index directory: apt resolves against the cached files and the installed packages alone, so it can neither
        # prefer a newer version from the image's indexes nor reach for a mirror. `--no-download` is not used because apt then
        # resolves a file pulled in only as a dependency by a relative name and fails ("Pathname to install is not absolute").
        no_lists=$(mktemp -d)
        mkdir -p "$no_lists/partial"
        if { [ ${#debs[@]} -eq 0 ] || sudo timeout -k 30 300 apt-get install -y -o "Dir::State::Lists=$no_lists" "${debs[@]}"; } &&
            installed_as_asked "$@"; then
            echo "apt-install: installed $* from the .deb cache (${#debs[@]} files), no mirror contacted"
            exit 0
        fi
        echo "apt-install: the .deb cache did not satisfy $*; installing from the mirrors" >&2
    fi
fi

# A fresh archives directory per run, so what it holds after the install is exactly the set this list downloaded.
apt_cache_opts=()
stage=""
if [ -n "$set_dir" ]; then
    stage=$(mktemp -d)
    sudo mkdir -p "$stage/partial"
    sudo chown _apt "$stage/partial" 2>/dev/null || true
    apt_cache_opts=(-o "Dir::Cache::archives=$stage" -o APT::Keep-Downloaded-Packages=true)
fi

max_attempts=5
backoff=15
mirror_swapped=""
for attempt in $(seq 1 "$max_attempts"); do
    if sudo timeout -k 30 300 apt-get update &&
        sudo timeout -k 30 300 apt-get install -y -o Acquire::Retries=3 "${apt_cache_opts[@]}" "$@"; then
        if [ -n "$set_dir" ]; then
            rm -rf "$set_dir"
            mkdir -p "$set_dir"
            for deb in "$stage"/*.deb; do [ -e "$deb" ] && cp "$deb" "$set_dir/"; done
            touch "$set_dir/complete"
            echo "apt-install: cached $(find "$set_dir" -name '*.deb' | wc -l | tr -d ' ') .deb files for $* in $set_dir"
        fi
        exit 0
    fi
    [ "$attempt" -ge "$max_attempts" ] && break
    # One-time, failure-path only: force every Ubuntu index off the failing azure mirror and onto https, in the mirrorlist
    # (current images) or a directly pinned sources.list (older images).
    if [ -z "$mirror_swapped" ]; then
        mirror_swapped=1
        apt_files=$(grep -rlE 'http://([a-z]+\.)?(archive|ports|security)\.ubuntu\.com' \
            /etc/apt/apt-mirrors.txt /etc/apt/sources.list /etc/apt/sources.list.d 2>/dev/null || true)
        if [ -n "$apt_files" ]; then
            echo "apt-install: azure mirror failing, forcing the canonical Ubuntu hosts over https" >&2
            printf '%s\n' "$apt_files" | sudo xargs -r sed -i -E \
                -e 's#http://azure\.(archive|ports)\.ubuntu\.com#https://\1.ubuntu.com#g' \
                -e 's#http://(archive|ports|security)\.ubuntu\.com#https://\1.ubuntu.com#g' 2>/dev/null || true
        fi
    fi
    echo "apt-get attempt $attempt failed; retrying in ${backoff}s" >&2
    sleep "$backoff"
    backoff=$((backoff * 2))
done
echo "apt-get failed after $max_attempts attempts: $*" >&2
exit 1

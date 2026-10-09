#!/usr/bin/env bash
# Retrying a dependency resolution through a Maven Central outage. Sourced by scripts/ci-test.sh and by the
# deps-cache workflow, so a build leg and the cache writer retry the same failures the same way:
#
#   . "$repo/scripts/resolve-retry-lib.sh"
#   resolve_retry 5 "dependency resolution" resolve_transient_failure resolve_tee scripts/sbt.sh tool update
#
# Central has served 404 for files it holds for at least 2.5 minutes at a stretch, so retries are spaced
# exponentially: RESOLVE_BACKOFF seconds, then double each time. Five attempts from 30s wait 30+60+120+240 =
# 450s in total, which spans such a burst with each sbt start on top, and bounds what a genuinely missing
# artifact costs.

RESOLVE_BACKOFF=${RESOLVE_BACKOFF:-30}

resolve_log() { echo "=== [resolve-retry] $(date '+%H:%M:%S') $* ==="; }

# The Coursier cache a resolution on this machine writes to: COURSIER_CACHE when set, else each OS's default
# location that exists. .github/actions/caches saves these same default directories; a directory one list
# names and the other does not either keeps markers a saved entry ships or saves a cache nothing purges.
coursier_cache_dirs() {
    local dir
    if [ -n "${COURSIER_CACHE:-}" ]; then
        [ -d "$COURSIER_CACHE" ] && printf '%s\n' "$COURSIER_CACHE"
        return 0
    fi
    for dir in "$HOME/.cache/coursier" "$HOME/Library/Caches/Coursier" "$HOME/AppData/Local/Coursier/Cache"; do
        [ -d "$dir" ] && printf '%s\n' "$dir"
    done
    return 0
}

# Coursier records a 404 as a `.<file>.error` marker beside where the file would be, and from then on answers
# "not found" for that URL from the marker without a request, with no expiry. A retry after a transient 404 that
# leaves the markers in place fails the same way without touching the network.
purge_coursier_error_markers() {
    local dir
    while IFS= read -r dir; do
        find "$dir" -type f -name '.*.error' -delete
    done < <(coursier_cache_dirs)
}

# A transient repository error in a log with no test output: a 403, 429 or 5xx, and a 404 for a file the
# repository holds, which surfaces as "Error downloading" for a pom and as FetchError "not found" for a jar.
# A real compile error carries none of these; a genuinely missing artifact reproduces on every attempt. The
# FetchError token is package-qualified because scalac echoes the source line of an error, and a README
# doctest that declares its own `FetchError` would otherwise turn a compile error into a retry.
resolve_transient_failure() {
    grep -qE 'Error downloading|coursier\.error\.FetchError|[Nn]ot found: https?://|[Ff]orbidden: https?://|Server returned HTTP response code: (403|429|50[0-9])|download error' "$1"
}

# The same signature in a log that also carries test output. A test can print a 429, a 404 URL or "download
# error" on its own (a Chrome download, a container image pull, an HTTP test), so the log must also carry one of
# sbt's or Coursier's resolution-failure classes, which test output never emits.
resolve_run_transient_failure() {
    grep -qE 'sbt\.librarymanagement\.ResolveException|coursier\.error\.(FetchError|ResolutionError)' "$1" &&
        grep -qE 'Server returned HTTP response code: (403|429|50[0-9])|Error downloading|Error fetching artifacts|[Nn]ot found: https?://|download error' "$1"
}

# resolve_tee <attempt> <log> <cmd...>: a runner for resolve_retry that streams the command and copies its
# output into <log>; returns the command's exit code.
resolve_tee() {
    local file="$2"
    shift 2
    "$@" 2>&1 | tee "$file"
    return "${PIPESTATUS[0]}"
}

# resolve_retry <attempts> <label> <matcher> <runner> <args...>: runs `<runner> <attempt> <log> <args...>` until
# it succeeds, its log fails `<matcher> <log>`, or <attempts> runs have failed. Before each retry the Coursier
# error markers are purged and the attempt's backoff is slept. Returns the last run's exit code.
resolve_retry() {
    local attempts="$1" label="$2" matcher="$3" runner="$4" attempt=1 rc wait tmp
    shift 4
    tmp="$(mktemp)"
    while :; do
        # Tested in an if so a caller under `set -e` (a workflow step) reaches the retry instead of exiting.
        if "$runner" "$attempt" "$tmp" "$@"; then rm -f "$tmp"; return 0; else rc=$?; fi
        if [ "$attempt" -lt "$attempts" ] && "$matcher" "$tmp"; then
            wait=$((RESOLVE_BACKOFF * (1 << (attempt - 1))))
            resolve_log "transient $label failure (attempt $attempt/$attempts): purging Coursier error markers, retrying in ${wait}s"
            purge_coursier_error_markers
            sleep "$wait"
            attempt=$((attempt + 1))
            continue
        fi
        rm -f "$tmp"; return "$rc"
    done
}

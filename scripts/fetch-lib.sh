#!/usr/bin/env sh
# Network acquisition for the vendored-native staging scripts: one retry policy, one checksum
# check, one extractor cascade. Sourced by every script that fetches a pinned upstream
# (BoringSSL, Aeron, SQLite, DoltLite) and by the container preludes that fetch a toolchain
# (scripts/build.sh, the musl release producers).
#
# POSIX sh, not bash: the container preludes run under busybox sh before bash is installed, and
# function variables are global in POSIX sh, so every name this file sets is fetch_-prefixed.
#
#   . "$repo/scripts/fetch-lib.sh"
#   fetch_url "$url" "$zip"
#   fetch_verify_sha256 "$zip" "$sha256" "$dist.zip"
#   fetch_unzip "$zip" "$work"
#   fetch_git_clone "$src" --depth 1 --branch "$tag" "$url"
#
# The policy: a failure a retry cannot change (a 404, a checksum mismatch) fails once. A
# transient failure (a 5xx, a reset, a timeout) is retried with fixed 30-second waits, up to six
# attempts and at most five minutes of retrying. GitHub Releases has answered 504 for minutes at
# a stretch, which is longer than curl's default backoff of 1s, 2s, 4s covers; the ceiling keeps
# a real outage from becoming an unbounded wait. FETCH_RETRY_DELAY and FETCH_RETRY_MAX_TIME are
# read from the environment so the self-test runs the same code without waiting.

fetch_retries=5
fetch_retry_delay="${FETCH_RETRY_DELAY:-30}"
fetch_retry_max_time="${FETCH_RETRY_MAX_TIME:-300}"
fetch_connect_timeout=30

# Download $1 to $2. curl retries the transient statuses (408, 429, 5xx), connection errors and
# timeouts on its own, and treats every other failure as final.
fetch_url() {
    curl --fail --location --silent --show-error \
        --connect-timeout "$fetch_connect_timeout" \
        --retry "$fetch_retries" \
        --retry-delay "$fetch_retry_delay" \
        --retry-max-time "$fetch_retry_max_time" \
        --output "$2" "$1"
}

# Print the sha256 of $1 with whichever tool this platform has. Checked rather than assumed: a
# missing tool would otherwise print nothing, which compares unequal and reports a checksum
# mismatch, sending a reader after a corrupt download that never happened. The Windows runners
# carry none of shasum.
fetch_sha256() {
    if command -v shasum >/dev/null 2>&1; then
        shasum -a 256 "$1" | awk '{print $1}'
    elif command -v sha256sum >/dev/null 2>&1; then
        sha256sum "$1" | awk '{print $1}'
    elif command -v openssl >/dev/null 2>&1; then
        openssl dgst -sha256 "$1" | awk '{print $NF}'
    else
        echo "no sha256 tool found (tried shasum, sha256sum, openssl)" >&2
        return 1
    fi
}

# Fail unless $1 has the sha256 $2; $3 names the file in the message.
fetch_verify_sha256() {
    fetch_actual="$(fetch_sha256 "$1")" || return 1
    if [ "$fetch_actual" != "$2" ]; then
        echo "checksum mismatch for $3" >&2
        echo "  expected $2" >&2
        echo "  actual   $fetch_actual" >&2
        echo "Refusing to stage. If the version was bumped, update the checksum together with it." >&2
        return 1
    fi
}

# Extract the zip $1 (an absolute path) into $2. bsdtar (the `tar` Windows 10+ and macOS ship)
# extracts zip, which is what makes the Windows runners work without unzip. 7z is the last
# resort, preinstalled on GitHub's Windows images.
fetch_unzip() {
    if command -v unzip >/dev/null 2>&1; then
        unzip -o -q "$1" -d "$2"
    elif tar --version 2>/dev/null | grep -qi bsdtar; then
        (cd "$2" && tar -xf "$1")
    elif command -v 7z >/dev/null 2>&1; then
        7z x -y -o"$2" "$1" >/dev/null
    else
        echo "no zip extractor found (tried unzip, bsdtar, 7z)" >&2
        return 1
    fi
}

# Clone into $1 with the remaining arguments passed to `git clone`, the URL last. A failed
# attempt leaves a partial directory that git refuses to clone over, so every attempt starts
# from an empty one.
fetch_git_clone() {
    fetch_dir="$1"
    shift
    fetch_attempt=1
    while :; do
        rm -rf "$fetch_dir"
        if git clone "$@" "$fetch_dir"; then
            return 0
        fi
        if [ "$fetch_attempt" -gt "$fetch_retries" ]; then
            echo "git clone failed after $fetch_attempt attempts: $*" >&2
            return 1
        fi
        echo "git clone failed (attempt $fetch_attempt of $((fetch_retries + 1))); retrying in ${fetch_retry_delay}s: $*" >&2
        sleep "$fetch_retry_delay"
        fetch_attempt=$((fetch_attempt + 1))
    done
}

#!/usr/bin/env bash
set -euo pipefail

# Run the unmodified coursier/setup-action v3.0.0 bundle under setup-node's Node 24.
# Download inside the retry loop: runner-level `uses:` downloads cannot ride out a
# longer GitHub rate-limit window. Coursier has no pre/post hooks to reproduce.
setup_dir=$(mktemp -d "${RUNNER_TEMP:?}/kyo-coursier.XXXXXX")
trap 'rm -rf "$setup_dir"' EXIT
bundle="$setup_dir/index.js"
url=https://raw.githubusercontent.com/coursier/setup-action/fd1707a76b027efdfb66ca79318b4d29b72e5a02/dist/index.js

for attempt in 1 2 3 4; do
    if curl --fail --location --silent --show-error --connect-timeout 30 --max-time 300 \
        --output "$bundle" "$url"; then
        # GitHub's environment, PATH, and output files pass through unchanged.
        # Explicit inputs include the boolean defaults normally supplied by action.yml.
        if env INPUT_JVM=corretto:25 INPUT_APPS=sbt \
            INPUT_USECONTAINERIMAGE=false INPUT_DISABLEDEFAULTREPOS=false node "$bundle"; then
            exit 0
        else
            status=$?
        fi
    else
        status=$?
    fi
    echo "Coursier setup attempt $attempt/4 failed (exit $status)." >&2
    if [ "$attempt" -lt 4 ]; then
        sleep 30
    else
        exit "$status"
    fi
done

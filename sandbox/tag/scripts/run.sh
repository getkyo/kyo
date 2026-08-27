#!/usr/bin/env bash
# Always a clean build: an incremental one reuses classes compiled before a macro change and
# reports green with sites still broken. The negative project must fail to compile with the
# exact code its row expects.
set -euo pipefail
cd "$(dirname "$0")/.."
sbt -batch clean core/compile lib/compile probes/compile tests/test "$@"
if out=$(sbt -batch negative/clean negative/compile 2>&1); then
    echo "negative project compiled: the cache-poisoning row is green when it must be red" >&2
    exit 1
fi
if ! grep -q '\[Tag.opaque.collapsed\]' <<< "$out"; then
    echo "negative project failed for a reason other than [Tag.opaque.collapsed]:" >&2
    grep -E '^\[error\]' <<< "$out" | head -20 >&2
    exit 1
fi
echo "negative project refused as expected"

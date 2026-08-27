#!/usr/bin/env bash
# Always a clean build: an incremental one reuses classes compiled before a macro change and
# reports green with sites still broken.
set -euo pipefail
cd "$(dirname "$0")/.."
exec sbt -batch clean core/compile lib/compile probes/compile tests/test "$@"

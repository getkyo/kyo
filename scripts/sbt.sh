#!/usr/bin/env bash
# sbt with the driver heap of a role from scripts/sbt-heap-lib.sh. Every workflow starts sbt through
# this, and it is how a developer runs sbt with a CI-sized heap:
#
#   scripts/sbt.sh compile 'kyo-coreJVM/test:compile'
#   scripts/sbt.sh publish -Dplatform=JVM +publishLocal
#
# A -J-Xmx among the arguments comes after the role's on the launcher's command line, so it wins.
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
. "$here/sbt-heap-lib.sh"

[ $# -ge 1 ] || { echo "usage: scripts/sbt.sh <role> [sbt args...]" >&2; exit 2; }
role="$1"; shift
heap=$(sbt_heap "$role")
mem=$(sbt_heap_memory_mb)
echo "sbt.sh: role $role, $heap (runner memory ${mem:-unknown}MB)" >&2
exec sbt "$heap" "$@"

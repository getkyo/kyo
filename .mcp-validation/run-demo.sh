#!/usr/bin/env bash
# Launcher for kyo-mcp / kyo-lsp demo servers used by Claude Code MCP integration.
# Usage: run-demo.sh <module> <fqcn> [demo-args...]
#   module: kyo-mcp | kyo-lsp
#   fqcn:   fully-qualified main class (e.g. demo.Filesystem)
# Caches the test classpath in .mcp-validation/<module>.classpath so subsequent
# launches skip the sbt export. Refresh by removing the cache file.

set -euo pipefail

MODULE="$1"
FQCN="$2"
shift 2

WORKTREE="$(cd "$(dirname "$0")/.." && pwd)"
CACHE_DIR="$WORKTREE/.mcp-validation"
CP_FILE="$CACHE_DIR/${MODULE}.classpath"

# Refresh classpath if missing or older than the module's compile output.
TARGET_DIR="$WORKTREE/${MODULE}/jvm/target/scala-3.8.3/test-classes"
if [[ ! -f "$CP_FILE" ]] || [[ "$TARGET_DIR" -nt "$CP_FILE" ]]; then
    cd "$WORKTREE"
    export JAVA_OPTS="-Xms3G -Xmx4G -Xss10M -XX:MaxMetaspaceSize=512M -XX:ReservedCodeCacheSize=128M -Dfile.encoding=UTF-8"
    export JVM_OPTS="$JAVA_OPTS"
    sbt -error "export ${MODULE}/Test/fullClasspath" 2>/dev/null | tail -1 > "$CP_FILE"
fi

CP="$(cat "$CP_FILE")"
exec java -cp "$CP" "$FQCN" "$@"

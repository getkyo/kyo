#!/usr/bin/env bash
# Launcher for kyo-mcp / kyo-lsp demo servers used by Claude Code MCP / LSP integration.
# Usage: run-demo.sh <module> <fqcn> [demo-args...]
#
# Do NOT use `set -e` here ; MCP hosts inspect stderr and exit codes, and any subshell
# hiccup would silently kill the server. We fail explicitly with status messages to the
# launch log so failures are diagnosable.

MODULE="$1"
FQCN="$2"
shift 2

LAUNCH_LOG="/tmp/mcp-validation/launch.log"
mkdir -p "$(dirname "$LAUNCH_LOG")"
{
    echo "==== $(date '+%Y-%m-%d %H:%M:%S') launching $MODULE/$FQCN ===="
    echo "  args: $*"
    echo "  pwd : $PWD"
    echo "  ppid: $PPID"
    echo "  env : SHELL=$SHELL PATH=$PATH"
} >> "$LAUNCH_LOG"

WORKTREE="$(cd "$(dirname "$0")/.." && pwd)"
CACHE_DIR="$WORKTREE/.mcp-validation"
CP_FILE="$CACHE_DIR/${MODULE}.classpath"

if [[ ! -s "$CP_FILE" ]]; then
    {
        echo "  classpath cache missing or empty, exporting via sbt"
        echo "  cache : $CP_FILE"
    } >> "$LAUNCH_LOG"
    cd "$WORKTREE"
    export JAVA_OPTS="-Xms3G -Xmx4G -Xss10M -XX:MaxMetaspaceSize=512M -XX:ReservedCodeCacheSize=128M -Dfile.encoding=UTF-8"
    sbt -error "export ${MODULE}/Test/fullClasspath" 2>>"$LAUNCH_LOG" | tail -1 > "$CP_FILE"
fi

CP="$(cat "$CP_FILE" 2>/dev/null || true)"
if [[ -z "$CP" ]]; then
    echo "  FATAL: empty classpath after attempted export" >> "$LAUNCH_LOG"
    exit 2
fi

TRACE_DIR="/tmp/mcp-validation/trace"
mkdir -p "$TRACE_DIR"
STDIN_LOG="$TRACE_DIR/${MODULE}-${FQCN##*.}-stdin.log"
echo "  cp_chars=${#CP}, tee stdin->$STDIN_LOG (no stdout tee — would buffer)" >> "$LAUNCH_LOG"
# Tee stdin only. A stdout-side tee would fully-buffer when its own stdout is a pipe
# (MCP host case), so the demo's response never reaches the host and the handshake
# times out. Java's stderr still folds into LAUNCH_LOG.
exec tee -a "$STDIN_LOG" \
  | java --enable-native-access=ALL-UNNAMED -cp "$CP" "$FQCN" "$@" 2>>"$LAUNCH_LOG"

#!/usr/bin/env bash
# Hot-reload kyo demo servers without restarting Claude Code.
#
# Usage:
#   .mcp-validation/reload.sh                          # reload all (kyo-mcp + kyo-lsp)
#   .mcp-validation/reload.sh mcp                      # only kyo-mcp demos
#   .mcp-validation/reload.sh lsp                      # only kyo-lsp demos
#   .mcp-validation/reload.sh demo.Filesystem          # only one fqcn
#
# Flow: compile the relevant module(s), then `pkill` the running demo JVM(s) by
# main-class FQCN. Claude Code's MCP / LSP host auto-reconnects by re-spawning
# via the configured launcher, and the fresh JVM picks up the just-compiled
# `.class` files since the cached classpath points at the project target dirs.
#
# Prereqs: a previous Claude Code restart must have read `.mcp.json` /
# `.lsp-plugin/.claude-plugin/marketplace.json`, so the host already knows the
# server commands. After that, this script is the only reload primitive needed.

set -u
arg="${1:-all}"

WORKTREE="$(cd "$(dirname "$0")/.." && pwd)"
export JAVA_OPTS="${JAVA_OPTS:--Xms3G -Xmx4G -Xss10M -XX:MaxMetaspaceSize=512M -XX:ReservedCodeCacheSize=128M -Dfile.encoding=UTF-8}"
export JVM_OPTS="$JAVA_OPTS"

compile_module() {
    local module="$1"
    echo ">> recompiling $module"
    (cd "$WORKTREE" && sbt -batch -error "$module/Test/compile") || {
        echo "!! $module compile failed; not killing running JVMs (they'd respawn against the old code)"
        exit 1
    }
}

kill_fqcn() {
    local pat="$1"
    if pgrep -f "$pat" >/dev/null 2>&1; then
        echo ">> killing matches for /$pat/"
        pkill -f "$pat"
    fi
}

case "$arg" in
    all)
        compile_module kyo-mcp
        compile_module kyo-lsp
        kill_fqcn 'demo\.Filesystem'
        kill_fqcn 'demo\.Notes'
        kill_fqcn 'demo\.GitInsight'
        kill_fqcn 'demo\.HttpFetch'
        kill_fqcn 'demo\.TodoLsp'
        kill_fqcn 'demo\.TodoDiagnostics'
        ;;
    mcp)
        compile_module kyo-mcp
        kill_fqcn 'demo\.Filesystem'
        kill_fqcn 'demo\.Notes'
        kill_fqcn 'demo\.GitInsight'
        kill_fqcn 'demo\.HttpFetch'
        ;;
    lsp)
        compile_module kyo-lsp
        kill_fqcn 'demo\.TodoLsp'
        kill_fqcn 'demo\.TodoDiagnostics'
        ;;
    demo.*)
        # Single FQCN: figure out which module and reload only it.
        case "$arg" in
            demo.TodoLsp|demo.TodoDiagnostics) compile_module kyo-lsp ;;
            *)                                 compile_module kyo-mcp ;;
        esac
        kill_fqcn "${arg//./\\.}"
        ;;
    *)
        echo "usage: $0 [all|mcp|lsp|demo.<Fqcn>]" >&2
        exit 2
        ;;
esac

echo ">> done — Claude Code will respawn the killed servers on the next tool call"

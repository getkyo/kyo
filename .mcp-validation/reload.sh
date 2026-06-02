#!/usr/bin/env bash
# Hot-reload all kyo demo servers without restarting Claude Code.
#
# After the initial Claude Code restart (which spawns the proxies via .mcp.json /
# .lsp-plugin marketplace.json), this script is the only reload primitive needed:
# it bumps the sentinel mtime; each running proxy.py notices via its watcher
# thread, recompiles the relevant module, replaces its JVM child, replays the
# captured initialize handshake, and (for MCP) emits notifications/tools/list_changed
# so Claude Code refreshes its tool catalog. Claude Code never sees a disconnect.
#
# Per-proxy reload (with a one-second granularity from the proxy poll loop) is the
# only behaviour ; there's no opt-in/opt-out for individual demos because the proxy
# pool watches the same sentinel file.

set -u

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
SENTINEL="$SCRIPT_DIR/reload.sentinel"

touch "$SENTINEL"
echo ">> bumped $SENTINEL ; running proxies will reload within ~1s"

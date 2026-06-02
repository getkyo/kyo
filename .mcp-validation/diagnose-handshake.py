#!/usr/bin/env python3
"""Reproduce Claude Code's full MCP handshake against the launcher.

Spawns the launcher with PIPE-attached stdin/stdout/stderr (the same way Claude Code
does), sends the exact `initialize` payload Claude Code emits, then the standard
`notifications/initialized` follow-up and a `tools/list` request, and reports the
response for each step. The first step that hangs or returns an unexpected shape
is the actual cause of the TERM-after-handshake pattern.
"""

import json
import select
import subprocess
import sys
import time
from pathlib import Path

LAUNCHER = Path(__file__).parent / "run-demo.sh"

# Verbatim from /tmp/mcp-validation/trace/*-stdin.log Claude Code captures.
INITIALIZE = {
    "method": "initialize",
    "params": {
        "protocolVersion": "2025-11-25",
        "capabilities": {"roots": {}, "elicitation": {}},
        "clientInfo": {
            "name": "claude-code",
            "title": "Claude Code",
            "version": "2.1.159",
            "description": "Anthropic's agentic coding tool",
            "websiteUrl": "https://claude.com/claude-code",
        },
    },
    "jsonrpc": "2.0",
    "id": 0,
}
INITIALIZED = {"jsonrpc": "2.0", "method": "notifications/initialized"}
TOOLS_LIST = {"jsonrpc": "2.0", "id": 1, "method": "tools/list"}


def read_until_newline(proc, timeout_s: float) -> tuple[str | None, str]:
    """Read one NDJSON line from proc.stdout within timeout. Returns (line | None, error)."""
    deadline = time.monotonic() + timeout_s
    buf = b""
    while time.monotonic() < deadline:
        if proc.poll() is not None:
            return None, f"process exited prematurely code={proc.returncode}"
        rlist, _, _ = select.select([proc.stdout], [], [], 0.2)
        if proc.stdout in rlist:
            chunk = proc.stdout.read1(4096)
            if not chunk:
                continue
            buf += chunk
            if b"\n" in buf:
                line, _ = buf.split(b"\n", 1)
                return line.decode("utf-8", errors="replace"), ""
    return None, f"timeout after {timeout_s}s; buffered={buf!r}"


def write_msg(proc, msg: dict) -> None:
    data = (json.dumps(msg) + "\n").encode("utf-8")
    proc.stdin.write(data)
    proc.stdin.flush()


def diagnose(module: str, fqcn: str, *args: str) -> int:
    label = f"{module}/{fqcn}"
    print(f"\n=== {label} ===")
    proc = subprocess.Popen(
        [str(LAUNCHER), module, fqcn, *args],
        stdin=subprocess.PIPE,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        bufsize=0,
    )
    rc = 1
    try:
        # Step 1: initialize
        print("→ initialize")
        write_msg(proc, INITIALIZE)
        line, err = read_until_newline(proc, 15)
        if line is None:
            print(f"  FAIL initialize: {err}")
            return 1
        try:
            resp = json.loads(line)
        except json.JSONDecodeError as e:
            print(f"  FAIL parse: {e} raw={line[:200]!r}")
            return 1
        if "result" not in resp:
            print(f"  FAIL no result: {line[:300]}")
            return 1
        print(f"  ✓ initialize result: protocolVersion={resp['result'].get('protocolVersion')}")
        print(f"    capabilities keys={list(resp['result'].get('capabilities', {}).keys())}")

        # Step 2: initialized notification (no response expected)
        print("→ notifications/initialized")
        write_msg(proc, INITIALIZED)
        print("  ✓ sent (notification, no response expected)")

        # Step 3: tools/list — the first real probe Claude Code makes
        print("→ tools/list")
        write_msg(proc, TOOLS_LIST)
        line, err = read_until_newline(proc, 10)
        if line is None:
            print(f"  FAIL tools/list: {err}")
            # Drain stderr to see if anything was logged
            stderr_data = b""
            try:
                while True:
                    r, _, _ = select.select([proc.stderr], [], [], 0.5)
                    if proc.stderr in r:
                        c = proc.stderr.read1(4096)
                        if not c:
                            break
                        stderr_data += c
                    else:
                        break
            except Exception:
                pass
            if stderr_data:
                print(f"  stderr: {stderr_data.decode('utf-8', errors='replace')[:500]}")
            return 1
        try:
            resp = json.loads(line)
        except json.JSONDecodeError as e:
            print(f"  FAIL parse tools/list: {e} raw={line[:200]!r}")
            return 1
        tools = resp.get("result", {}).get("tools", [])
        print(f"  ✓ tools/list returned {len(tools)} tool(s): {[t.get('name') for t in tools]}")
        rc = 0
    finally:
        proc.terminate()
        try:
            proc.wait(timeout=3)
        except subprocess.TimeoutExpired:
            proc.kill()
    return rc


def main() -> int:
    cases = [
        ("kyo-mcp", "demo.Filesystem", "/tmp/mcp-validation/root"),
        ("kyo-mcp", "demo.Notes"),
    ]
    all_ok = True
    for module, fqcn, *args in cases:
        ok = diagnose(module, fqcn, *args) == 0
        all_ok = all_ok and ok
    return 0 if all_ok else 1


if __name__ == "__main__":
    sys.exit(main())

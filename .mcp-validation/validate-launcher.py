#!/usr/bin/env python3
"""Simulate exactly how Claude Code's MCP host invokes a launcher.

Spawns the launcher with stdin/stdout/stderr as PIPES (not TTYs), feeds
Claude Code's actual initialize payload, reads the response with a hard
timeout, and reports pass/fail. Run this BEFORE restarting Claude Code
to validate the launcher under the same conditions.
"""

import json
import subprocess
import sys
import time
from pathlib import Path

LAUNCHER = Path(__file__).parent / "run-demo.sh"

# Exact payload Claude Code sent (captured from prior stdin trace).
CLAUDE_INIT = {
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


def run_one(module: str, fqcn: str, *args: str, timeout: float = 15.0) -> tuple[bool, str]:
    """Spawn the launcher with pipe-attached stdio and verify initialize round-trips."""
    proc = subprocess.Popen(
        [str(LAUNCHER), module, fqcn, *args],
        stdin=subprocess.PIPE,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        bufsize=0,
    )
    try:
        req = (json.dumps(CLAUDE_INIT) + "\n").encode("utf-8")
        proc.stdin.write(req)
        proc.stdin.flush()

        # Read one NDJSON line back with a hard wall-clock deadline.
        deadline = time.monotonic() + timeout
        line = b""
        while time.monotonic() < deadline:
            chunk = proc.stdout.read1(4096) if hasattr(proc.stdout, "read1") else proc.stdout.read(4096)
            if not chunk:
                if proc.poll() is not None:
                    return False, f"process exited code={proc.returncode} before responding"
                time.sleep(0.05)
                continue
            line += chunk
            if b"\n" in line:
                break
        else:
            return False, f"timed out after {timeout}s; got {len(line)} bytes: {line!r}"

        first_line = line.split(b"\n", 1)[0].decode("utf-8", errors="replace")
        try:
            resp = json.loads(first_line)
        except json.JSONDecodeError as e:
            return False, f"invalid JSON response: {e}; raw: {first_line!r}"

        if resp.get("id") != 0:
            return False, f"wrong id {resp.get('id')}; resp: {first_line}"
        if "result" not in resp:
            return False, f"no result; resp: {first_line}"
        return True, first_line
    finally:
        proc.terminate()
        try:
            proc.wait(timeout=3)
        except subprocess.TimeoutExpired:
            proc.kill()


def main() -> int:
    cases = [
        ("kyo-mcp", "demo.Filesystem", "/tmp/mcp-validation/root"),
        ("kyo-mcp", "demo.Notes"),
        ("kyo-lsp", "demo.TodoLsp"),
    ]
    all_ok = True
    for module, fqcn, *args in cases:
        label = f"{module}/{fqcn}"
        ok, msg = run_one(module, fqcn, *args)
        status = "PASS" if ok else "FAIL"
        print(f"[{status}] {label}: {msg[:200]}")
        all_ok = all_ok and ok
    return 0 if all_ok else 1


if __name__ == "__main__":
    sys.exit(main())

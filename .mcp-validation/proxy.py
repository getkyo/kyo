#!/usr/bin/env python3
"""Long-lived stdio proxy that wraps a kyo demo JVM behind one stable session with
Claude Code's MCP / LSP host. Lets the developer hot-reload the child JVM (after
`sbt compile`) without ever disconnecting from Claude Code.

Wire: proxy <-> Claude Code on its own stdio; proxy <-> JVM child on the child's
stdio. The proxy is the .mcp.json / lsp-plugin launcher target; the JVM is what
the proxy spawns internally (via .mcp-validation/run-demo.sh).

Reload trigger: touch `.mcp-validation/reload.sentinel`. The proxy notices the
mtime change, kills the JVM child, recompiles via `sbt <module>/Test/compile`,
spawns a fresh JVM, replays the captured `initialize` request to it (so the new
child has the same protocol state Claude Code thinks it has), discards the
synthetic initialize response, and (for MCP) injects `notifications/tools/list_changed`
to the upstream so Claude Code re-fetches its tool catalog.

Args:
  proxy.py <framing> <module> <fqcn> [child-args...]
    framing: "ndjson" (MCP)  or  "content-length" (LSP)
    module : "kyo-mcp" or "kyo-lsp" (passed to run-demo.sh for classpath cache)
    fqcn   : main class FQCN, e.g. "demo.Filesystem"
    rest   : forwarded to the demo's `args`
"""
import json
import os
import signal
import subprocess
import sys
import threading
import time
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parent
LAUNCHER = str(SCRIPT_DIR / "run-demo.sh")
SENTINEL = SCRIPT_DIR / "reload.sentinel"
LOG = SCRIPT_DIR / "proxy.log"

# Synthetic id used when the proxy replays initialize to a fresh JVM.
REPLAY_INIT_ID = "__proxy_replay_init__"


def log(msg: str) -> None:
    try:
        with open(LOG, "a") as f:
            f.write(f"[{time.strftime('%H:%M:%S')}] [{os.getpid()}] {msg}\n")
    except Exception:
        pass


def main() -> int:
    framing = sys.argv[1]
    module = sys.argv[2]
    fqcn = sys.argv[3]
    child_args = sys.argv[4:]

    if framing not in ("ndjson", "content-length"):
        sys.stderr.write(f"proxy: unknown framing {framing!r}\n")
        return 2

    log(f"start framing={framing} module={module} fqcn={fqcn} args={child_args}")
    SENTINEL.touch(exist_ok=True)

    # Captured initialize bytes (raw JSON), replayed to each new JVM child.
    state = {
        "init_bytes": None,        # bytes of the initialize request from Claude Code
        "child": None,             # current subprocess.Popen for the JVM child
        "sentinel_mtime": SENTINEL.stat().st_mtime,
        "lock": threading.Lock(),  # serialises child swap
        "shutdown": False,
        # The reader threads exit if `child` becomes None or changes; the main loop
        # re-spawns them after each swap.
    }

    def spawn_child() -> subprocess.Popen:
        log(f"spawn child: {LAUNCHER} {module} {fqcn} {' '.join(child_args)}")
        return subprocess.Popen(
            [LAUNCHER, module, fqcn, *child_args],
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=open(SCRIPT_DIR / f"proxy-child.{fqcn}.log", "ab", buffering=0),
            bufsize=0,
        )

    # --- Framing helpers (NDJSON vs Content-Length) ---

    def read_one_message(stream) -> bytes | None:
        """Read one framed message from `stream`, returning the raw JSON body bytes."""
        if framing == "ndjson":
            line = stream.readline()
            if not line:
                return None
            return line.rstrip(b"\n").rstrip(b"\r")
        else:
            # Content-Length\r\n\r\nBODY
            headers = b""
            while b"\r\n\r\n" not in headers:
                chunk = stream.read(1)
                if not chunk:
                    return None
                headers += chunk
            cl = None
            for line in headers.split(b"\r\n"):
                if line.lower().startswith(b"content-length:"):
                    cl = int(line.split(b":", 1)[1].strip())
            if cl is None or cl < 0:
                return None
            body = b""
            while len(body) < cl:
                chunk = stream.read(cl - len(body))
                if not chunk:
                    return None
                body += chunk
            return body

    def frame_outbound(body: bytes) -> bytes:
        if framing == "ndjson":
            return body + b"\n"
        return f"Content-Length: {len(body)}\r\n\r\n".encode("utf-8") + body

    # --- Forwarders ---

    def upstream_to_child():
        """stdin (Claude Code) -> JVM child stdin. Captures initialize once."""
        while not state["shutdown"]:
            body = read_one_message(sys.stdin.buffer)
            if body is None:
                log("upstream: EOF / stdin closed")
                state["shutdown"] = True
                with state["lock"]:
                    c = state["child"]
                    if c and c.poll() is None:
                        try: c.stdin.close()
                        except Exception: pass
                return
            # Capture initialize the first time we see it.
            if state["init_bytes"] is None:
                try:
                    msg = json.loads(body)
                    if msg.get("method") == "initialize":
                        state["init_bytes"] = body
                        log("captured initialize")
                except Exception:
                    pass
            with state["lock"]:
                c = state["child"]
                if c and c.poll() is None:
                    try:
                        c.stdin.write(frame_outbound(body))
                        c.stdin.flush()
                    except (BrokenPipeError, OSError) as e:
                        log(f"upstream write failed: {e}")

    def child_to_upstream(c: subprocess.Popen):
        """One thread per child generation. Reads child stdout -> Claude Code stdout."""
        while not state["shutdown"]:
            body = read_one_message(c.stdout)
            if body is None:
                log(f"child {c.pid} stdout EOF")
                return
            # If this is the synthetic-init response we injected, swallow it.
            try:
                msg = json.loads(body)
                if msg.get("id") == REPLAY_INIT_ID:
                    log("swallowed synthetic init response")
                    continue
            except Exception:
                pass
            try:
                sys.stdout.buffer.write(frame_outbound(body))
                sys.stdout.buffer.flush()
            except (BrokenPipeError, OSError) as e:
                log(f"upstream write failed: {e}")
                state["shutdown"] = True
                return

    def emit_to_upstream(message: dict) -> None:
        body = json.dumps(message).encode("utf-8")
        try:
            sys.stdout.buffer.write(frame_outbound(body))
            sys.stdout.buffer.flush()
        except (BrokenPipeError, OSError) as e:
            log(f"emit_to_upstream failed: {e}")

    def replay_initialize(c: subprocess.Popen) -> None:
        """Send the captured initialize to a fresh child + the followup initialized notif."""
        if state["init_bytes"] is None:
            log("replay_initialize: no captured init; skipping")
            return
        try:
            init = json.loads(state["init_bytes"])
        except Exception as e:
            log(f"replay_initialize: parse failed {e}")
            return
        init["id"] = REPLAY_INIT_ID
        log("replay initialize -> new child")
        try:
            c.stdin.write(frame_outbound(json.dumps(init).encode("utf-8")))
            c.stdin.flush()
            # initialized notification (MCP: notifications/initialized; LSP: initialized)
            initialized = {
                "jsonrpc": "2.0",
                "method": "notifications/initialized" if framing == "ndjson" else "initialized",
                "params": {},
            }
            c.stdin.write(frame_outbound(json.dumps(initialized).encode("utf-8")))
            c.stdin.flush()
        except (BrokenPipeError, OSError) as e:
            log(f"replay write failed: {e}")

    def trigger_reload() -> None:
        """Recompile + swap child."""
        log("reload triggered")
        # Recompile
        try:
            subprocess.run(
                ["sbt", "-batch", "-error", f"{module}/Test/compile"],
                cwd=str(SCRIPT_DIR.parent),
                check=False,
                stdout=subprocess.DEVNULL,
                stderr=subprocess.STDOUT,
                timeout=300,
                env={**os.environ, "JAVA_OPTS": os.environ.get("JAVA_OPTS",
                    "-Xms3G -Xmx4G -Xss10M -XX:MaxMetaspaceSize=512M "
                    "-XX:ReservedCodeCacheSize=128M -Dfile.encoding=UTF-8")}
            )
        except Exception as e:
            log(f"reload compile failed: {e}")

        with state["lock"]:
            old = state["child"]
            if old and old.poll() is None:
                try: old.stdin.close()
                except Exception: pass
                try: old.terminate()
                except Exception: pass
                try: old.wait(timeout=3)
                except subprocess.TimeoutExpired:
                    try: old.kill()
                    except Exception: pass
            new = spawn_child()
            state["child"] = new
            replay_initialize(new)
            # Start a fresh reader thread for the new child
            threading.Thread(target=child_to_upstream, args=(new,), daemon=True).start()
        # MCP: nudge Claude Code to re-fetch tools. LSP doesn't have an analogue.
        if framing == "ndjson":
            emit_to_upstream({
                "jsonrpc": "2.0",
                "method": "notifications/tools/list_changed",
            })
        log("reload complete")

    def sentinel_watcher():
        while not state["shutdown"]:
            try:
                m = SENTINEL.stat().st_mtime
                if m != state["sentinel_mtime"]:
                    state["sentinel_mtime"] = m
                    trigger_reload()
            except FileNotFoundError:
                # Sentinel deleted; recreate so future touches reload.
                SENTINEL.touch()
                state["sentinel_mtime"] = SENTINEL.stat().st_mtime
            time.sleep(1.0)

    # --- Bootstrap ---
    with state["lock"]:
        state["child"] = spawn_child()
    threading.Thread(target=child_to_upstream, args=(state["child"],), daemon=True).start()
    threading.Thread(target=sentinel_watcher, daemon=True).start()
    upstream_to_child()  # blocks until stdin EOF or shutdown
    log("proxy exiting")
    with state["lock"]:
        c = state["child"]
        if c and c.poll() is None:
            try: c.terminate()
            except Exception: pass
            try: c.wait(timeout=3)
            except subprocess.TimeoutExpired:
                try: c.kill()
                except Exception: pass
    return 0


if __name__ == "__main__":
    sys.exit(main())

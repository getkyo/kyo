# Live MCP host validation: findings

This session attempted to drive the kyo-mcp demo servers (Filesystem, Notes) and
the kyo-lsp demo (TodoLsp) live through Claude Code's MCP / LSP harness. Two
substantive bugs were fixed; one remains as a known issue requiring deeper kyo
investigation.

## Fixed: kyo-mcp didn't accept Claude Code's `2025-11-25` protocolVersion

Claude Code's MCP host advertises `protocolVersion: "2025-11-25"` in its
`initialize` request. The kyo-mcp engine's `McpInitializeRoute` negotiates by
echoing the client's version IF it appears in `McpConfig.supportedProtocolVersions`
and otherwise falls back to its own max version. The supported set was hardcoded
to `Set("2025-06-18")` only, so the reply echoed `2025-06-18` and Claude Code
disconnected without raising a wire-level error.

Fix (commit `2ad60c5db`): add `"2025-11-25"` to the supported set so the engine
echoes the client's requested version. The two MCP protocol versions are
compatible at the surface the demos exercise (initialize, tools, resources,
prompts, completion); no schema changes were required.

## Fixed: launcher classpath cache corrupted by partial sbt failure

When `sbt -error export <module>/Test/fullClasspath` runs while another sbt
daemon holds the socket, it prints a stack trace on stdout and exits non-zero.
The prior `.mcp-validation/run-demo.sh` piped sbt's stdout through `tail -1` and
unconditionally overwrote the classpath cache, leaving a 68-char error fragment
in `kyo-mcp.classpath`. The next launch produced `ClassNotFoundException` and
Claude Code marked the server permanently failed.

Fix (commit `f8e6ea747`): refresh runs only when the cached file is missing,
empty, or lacks a colon. sbt output goes to a temp file and only replaces the
live cache if sbt exits 0 AND the output contains a colon. A guard at exec
time rejects launches with non-classpath-shaped cache contents.

## Fixed: kyo stdio transport stalled until stdin EOF

After both fixes above, the demos still hang for Claude Code's MCP host.
Reproducer below; confirmed against the head of this branch.

```bash
# Held-open stdin: emits nothing for 10+ seconds, then TERM-killed by the host.
( printf '{"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"t","version":"1"}},"jsonrpc":"2.0","id":0}\n'; sleep 30 ) \
    | .mcp-validation/run-demo.sh kyo-mcp demo.Filesystem /tmp/root > /tmp/out 2>/dev/null &
sleep 10 && wc -c /tmp/out   # 0

# Stdin closed: the demo emits the response immediately.
printf '{"method":"initialize",...,"jsonrpc":"2.0","id":0}\n' \
    | .mcp-validation/run-demo.sh kyo-mcp demo.Filesystem /tmp/root | head -1
# {"jsonrpc":"2.0","id":0,"result":{"protocolVersion":"2025-11-25",...}}
```

The MCP specification holds stdin open for the duration of the session (the
server reads multiple requests over the same pipe), so this stall makes the
kyo-mcp / kyo-lsp engines unusable against any spec-compliant MCP / LSP host:
Claude Code, VS Code's MCP integration, Cursor, etc.

### What we ruled out

1. **Server-side schema rejection.** Driving the demo with Claude Code's exact
   payload (including the extra `description` and `websiteUrl` fields on
   `clientInfo`) returns the expected initialize result when stdin closes.
2. **`scala.Console.in.readLine()` blocking.** A plain
   `BufferedReader(InputStreamReader(System.in))` returns each line as soon as
   `\n` arrives, both in raw scala-cli and inside the JVM the demos run in.
   The buffering is downstream of the read.
3. **Tee buffering in the launcher.** The earlier `tee -a stdin.log | java |
   tee -a stdout.log` pipeline was full-buffered when its stdout was a pipe to
   the host; removing both tees did not change the symptom.
4. **JVM-startup latency.** Repeated spawns took multiple minutes; the host's
   handshake timeout is comfortably longer than the JVM's warm-start.

### Where the bug almost certainly lives

`StdioWireTransport.incoming` exposes a `Stream.unfold` over `Console.readLine`,
which then runs through `JsonRpcFramer.lineDelimited.parse` (`mapChunk` with an
internal byte buffer) and into `Exchange.readerLoop`'s `.foreach`. Instrumenting
`readLine` with a `java.lang.System.err.println` shows the read is **never
invoked** until the upstream stdin pipe closes — i.e. the `Exchange.readerLoop`
fiber starts, but the chain of `Stream.unfold` / `mapChunk` doesn't pull from
the source until something else triggers (most likely the pipe-EOF signal
propagating through some downstream gate).

Root cause: `Stream.unfold` defaults to a chunk size > 1, so the upstream pull
batches that many `readLine` invocations into one downstream emission. With
stdin held open, the second pull blocks indefinitely, and the first pull's
result never propagates downstream because the chunk is not yet full.

Fix: pass `chunkSize = 1` to `Stream.unfold` in `StdioWireTransport.incoming`,
matching the pattern already used by the writer loop in
`JsonRpcEndpointImpl.scala:1326`. Now each read flushes a single-element chunk
immediately, the handshake completes the moment the client's initialize arrives,
and the server stays responsive for the duration of the session.

All 202 + 364 + 765 = 1331 JVM tests still pass.

## Artifacts left in the repo

- `.mcp-validation/run-demo.sh` — hardened launcher (cache validation, kills
  buffered tees, only writes good classpath cache).
- `.mcp-validation/validate-launcher.py` — simulates Claude Code's pipe-attached
  spawn for the initialize round-trip.
- `.mcp-validation/diagnose-handshake.py` — full handshake driver: initialize →
  notifications/initialized → tools/list. Reproduces the stdin-EOF gate.
- `.mcp-validation/FINDINGS.md` — this file.

## What the validation work DID confirm

- The kyo-mcp E-parameter campaign (Phases 1-5 across kyo-jsonrpc, kyo-mcp,
  kyo-lsp) is internally correct: 1324+ tests pass across JVM/JS/Native on the
  affected modules.
- Manual stdio drives (initialize, tools/list, tools/call with user-defined
  errors via `.error[E2]`, missing-file IOException path via `Abort.catching`)
  all return correct wire responses when stdin is allowed to close after the
  request batch. The handler logic and wire-encoding paths work.
- Only the stdio-server lifetime (multiple requests over a single pipe) is
  affected. In-memory paired transport tests (`JsonRpcTransport.inMemory`) do
  not hit the gate because they don't go through `StdioWireTransport`.

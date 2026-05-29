# steering.md — kyo-jsonrpc protocol-coverage campaign

## NEVER STOP

Once `flow-validate` exits 0, drive every phase through commit and
immediately launch the next. Stop only when:
1. Plan exhausted (all phases committed + strip-dev commit green).
2. Genuinely blocked after 3 retries AND concrete repro recorded.
3. User explicitly says stop.

## No idle-wait, no intermediate reports

- Between phases, the ONLY user-facing output is one terse line:
  "Phase NN committed: <sha>".
- NO checkpoints, progress tables, "remaining work" summaries,
  status re-summaries. Those are stops.
- After dispatching a background phase agent, IMMEDIATELY begin the
  next independent unit of work in the SAME turn (next prep, next
  agent prompt draft, read-only verify on prior phase). Idle-waiting
  for completion notification = stop.
- Substantive prose reserved for: plan-approval summary, genuine
  block/escalation, ALL-PHASES-COMPLETE.

## Pre-phase baseline

Before dispatching `flow-phase-impl` for phase N, capture:
```
git -C /Users/fwbrasil/workspace/kyo/.claude/worktrees/crispy-swinging-lemur \
  status --porcelain kyo-jsonrpc/shared/src \
  > .flow/protocol-coverage/phase-N-baseline.txt
```

## Verify FAIL response protocol

Per /flow command: read diffs, cross-reference baseline + audit/*, decide
ADOPT vs STASH vs REVERT. Never blind-revert against uncommitted prior work.

## Scope — net adjusted MUST + SHOULD fix list

From the 6 audit artifacts in `kyo-jsonrpc/research/`:

### MUST-FIX engine bugs (3)
1. `Config.cdp` preset — current `Config()` default ships LSP
   cancellation on CDP wires (`JsonRpcEndpoint.scala:64`).
2. LSP `partialResultToken` stamping — `ProgressPolicy.scala:41-42`
   reads/writes only `workDoneToken`. Mis-labels token field on
   partial-result calls.
3. Progress token uniqueness MUST — upgraded from SHOULD per live
   MCP spec; needs `putIfAbsent`-and-regenerate in ProgressEngine.

### SHOULD-FIX module-level seams (10)
4. `JsonRpcTransport.webSocket` adapter (absorbs kyo-browser relay-fiber).
5. `JsonRpcTransport.stdio` adapter (line-framed for MCP/LSP).
6. `JsonRpcTransport.unixDomain(sockPath)` (matches HookSocketServer).
7. Byte-stream seam UNDER envelope codec (lets LSP Content-Length
   framing layer cleanly).
8. Tolerant fallback id extraction on malformed responses.
9. Two-phase `close(gracePeriod)` on JsonRpcEndpoint.
10. `CancellationPolicy` owns its decoder (move from
    `CancellationEngine.scala:19`).
11. Per-sessionId notification dispatch seam (replaces hand-rolled
    dispatcher tables).
12. Public `JsonRpcMethod.dispatch(name, methods, params, ctx)`.
13. `endpoint.sendUnmatched(method, params, id)` for fire-and-forget /
    dialog-drainer patterns.

### Audit-missed module-level concerns (4)
14. MCP malformed-id null-reply semantic (engine emits `Absent`).
15. MCP `_meta` reserved-prefix MUST enforcement.
16. MCP JSON Schema 2020-12 support (tools/elicitation surface).
17. LSP `window/workDoneProgress/create` server-initiated token seam.

### Out of scope (consumer modules)
- Streamable HTTP transport, MCP initialize/initialized lifecycle,
  capability negotiation, typed MCP/LSP/CDP method libraries — these
  belong in future `kyo-mcp` / `kyo-lsp` / `kyo-cdp` modules.

## Conventions

- All Rule 8c HARD: source + matching test in same phase commit.
- `// flow-allow:` rationales for any exception, traced to
  VALIDATED_EXCEPTION verdict.
- No scope cuts, no deferrals, no "tested transitively" excuses.
- Phase todos zero-padded (`Phase 01`, `Phase 02`, ... `Phase 10`).

## Design v1 -> v2 directive (after plan v1 protocol-bleed review)

Plan v1 PASSED flow-validate but the supervisor + user review found
7 of 17 items were partial protocol implementations bleeding into the
engine layer. The engine must know JSON-RPC 2.0 + extensibility seams
only. Protocol-specific behavior moves to future consumer modules.

### REMOVE from engine scope

These 7 items no longer ship in kyo-jsonrpc; they are first work in
the future kyo-mcp / kyo-lsp / kyo-cdp consumer modules:

- Item 1 `Config.cdp` preset (CDP-specific bundle); just verify
  `Config()` no-arg default is protocol-neutral (cancellation=Absent,
  no LSP/MCP knowledge).
- Item 2 `partialResultToken` stamping (LSP wire field name); the
  existing `ProgressPolicy` callback shape already supports this; the
  LSP preset lives in `kyo-lsp`.
- Item 11 per-sessionId dispatch (CDP wire feature); `ExtrasEncoder`
  + `endpoint.notify` already give per-session routing.
- Item 14 null-id response semantic (MCP/CDP behavior); generic
  malformed-envelope handling stays in Item 8, but the `Maybe[Id]`
  on Response and `respondToMalformed` are MCP-bundle concerns.
- Item 15 `MetaPolicy` for `_meta` (MCP wire field); `ExtrasEncoder`
  already provides arbitrary extras.
- Item 16 `JsonSchema2020_12.encode[A]` (MCP tools surface);
  kyo-schema already ships the encoder.
- Item 17 `emitProgress` ($/progress / notifications/progress shape);
  engine already has `endpoint.notify(method, params, extras)`.

### KEEP in engine scope (10 items)

These remain genuinely engine-level (protocol-agnostic):
- Item 3 generic putIfAbsent token-allocation primitive
- Items 4, 5, 6, 7 transport seams (WireTransport + Framer + stdio +
  unixDomain + webSocket)
- Item 8 Malformed envelope handling
- Item 9 close(gracePeriod)
- Item 10 CancellationPolicy.decodeParams refactor (decoder out of
  CancellationEngine)
- Item 12 JsonRpcMethod.dispatch public surface
- Item 13 endpoint.sendUnmatched

### Design v2 + Plan v2

Design v2 rewrites the 17-item table to a 10-item table; drops the
protocol-named types (MetaPolicy, JsonSchema2020_12); reframes
Items 2 and 17 as "already covered by existing ProgressPolicy
callbacks and endpoint.notify, no new API needed"; reverifies that
Config()'s default cancellation/progress/codec are all protocol-
neutral. Cross-phase invariants shrink accordingly.

Plan v2 regenerates phases from the narrower scope. Expect 4-5
phases instead of 7.

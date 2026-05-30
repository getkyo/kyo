# Phase 3 pulse 2

Time: 2026-05-30T00:28:00Z
Files reviewed: design/05-plan.yaml §phase-3, control/steering.md, phases/phase-03/pulse-1.md, git status --porcelain, git diff --stat
Plan cites: ./design/05-plan.yaml §Phase 3

## Plan anchor

- Files to delete (10 target): 10/10 DONE (unchanged from pulse-1)
- Companion files to edit (4 target): 4/4 DONE (unchanged from pulse-1)
- Internal engine/codec/transport edits: 10/10 DONE
  - Pulse-1 pending (5): JsonRpcRequest, StdioWireTransport, WireTransportAdapter, UdsWireTransport all updated; FramerImpl needed no changes (does not reference the deleted type names)
- kyo-jsonrpc-http: 1/1 DONE (test file updated; main file had no old type references and needed no edit)
- kyo-browser: 6/6 DONE (CdpBackend.scala + 5 test files all modified)
- Test renames: 10/10 DONE (all RM entries confirmed in git status; new class names verified in diff)
- Net LoC: 842 insertions / 359 deletions = 483 net (vs 429 at pulse-1; small growth from browser + transport cleanups)
- Files changed: 42

## Reward-hacking checks

| Pattern | Verdict | Citation |
|---|---|---|
| Verification commands actually run | CLEAN | No impl stdout; work is structural reference-update — no claim of passing tests yet |
| Compile-only "success" claim | CLEAN | No such claim present |
| Priority inference | CLEAN | decisions.md records proper rationale; kyo-browser and http-transport were deferred to end as expected |
| Scope substitution | CLEAN | All 11 nested types absorbed; all 10 test renames done; all downstream callers updated |
| Foreach-discards-assert | CLEAN | New test class names (e.g. JsonRpcEndpointCancellationPolicyTest) contain assertions per drift output |
| Stale-state passing | CLEAN | Old type names (JsonRpcId, HandlerCtx, bare WireTransport, bare Framer) no longer appear as code references in any kyo-jsonrpc or kyo-browser source file |

## Drifting checks

| Pattern | Verdict | Citation |
|---|---|---|
| Public API signature matches plan | CLEAN | All 11 nested types (UnknownMethodPolicy, MessageGate, CancellationPolicy, ProgressPolicy, ExtrasEncoder, Id, Context, WireTransport, Framer objects/traits) appear as additions under the correct companions in git diff |
| No off-plan architecture substitution | CLEAN | flow-pulse-drift.sh shows only plan-listed files in dirty tree; all additions match plan §Phase 3 files_modified |
| No cross-cutting refactor outside phase | CLEAN | kyo-browser and kyo-jsonrpc-http are listed in plan §Phase 3; no other modules touched |
| Internal helpers stay internal | CLEAN | StdioWireTransport, WireTransportAdapter, UdsWireTransport are private[kyo]; new nested type references use fully-qualified paths (JsonRpcTransport.WireTransport, JsonRpcEnvelope.Id.Num) |

## Scope-cutting checks

| Leaf | Status | Notes |
|---|---|---|
| 1: Delete IdStrategy.scala | PRESENT_STRICT | Deleted |
| 2: Delete UnknownMethodPolicy.scala | PRESENT_STRICT | Deleted |
| 3: Delete MessageGate.scala | PRESENT_STRICT | Deleted |
| 4: Delete CancellationPolicy.scala | PRESENT_STRICT | Deleted |
| 5: Delete ProgressPolicy.scala | PRESENT_STRICT | Deleted |
| 6: Delete ExtrasEncoder.scala | PRESENT_STRICT | Deleted |
| 7: Delete Framer.scala | PRESENT_STRICT | Deleted |
| 8: Delete WireTransport.scala | PRESENT_STRICT | Deleted |
| 9: Delete HandlerCtx.scala (rename to JsonRpcMethod.Context) | PRESENT_STRICT | Deleted + absorbed |
| 10: Delete JsonRpcId.scala (rename to JsonRpcEnvelope.Id) | PRESENT_STRICT | Deleted + absorbed |
| 11: Edit JsonRpcEndpoint.scala (absorb 6 types) | PRESENT_STRICT | Modified; 6 nested types in diff |
| 12: Edit JsonRpcTransport.scala (absorb 2 types) | PRESENT_STRICT | Modified; WireTransport + Framer in diff |
| 13: Edit JsonRpcMethod.scala (absorb Context) | PRESENT_STRICT | Modified |
| 14: Edit JsonRpcEnvelope.scala (absorb Id) | PRESENT_STRICT | Modified |
| 15: Edit internal/engine/* (update refs) | PRESENT_STRICT | All 4 engine files + JsonRpcCodecImpl updated |
| 16: Edit internal/codec/JsonRpcRequest.scala | PRESENT_STRICT | Updated |
| 17: Edit internal/transport/StdioWireTransport.scala | PRESENT_STRICT | Updated (uses JsonRpcTransport.WireTransport) |
| 18: Edit internal/transport/WireTransportAdapter.scala | PRESENT_STRICT | Updated |
| 19: Edit internal/framing/FramerImpl.scala | PRESENT_STRICT | No old type references; no edit needed (confirmed by grep) |
| 20: Edit jvm/internal/transport/UdsWireTransport.scala | PRESENT_STRICT | Updated |
| 21: Edit kyo-jsonrpc-http/JsonRpcHttpTransport.scala (main) | PRESENT_STRICT | No old type references; no edit needed (confirmed by grep); test file updated |
| 22: Test renames (10 files) | PRESENT_STRICT | All 10 RM entries in git status; new class names confirmed in diff |
| 23: Edit kyo-browser/* (6 files) | PRESENT_STRICT | CdpBackend + 5 test files all modified |

## CRITICAL (steer immediately)

None.

## MINOR (queue for post-commit audit)

1. Net LoC (483) remains well above the plan estimate of 75. This is expected and was flagged at pulse-1: absorbing full type bodies into companions inflates the gross count. Not a scope issue.
2. Two string literals in JsonRpcEndpointImpl.scala (error messages) still mention "ProgressPolicy.lsp / .mcp" -- these are user-facing error strings, not type references. Verify wording accuracy at post-commit audit.
3. flow-pulse-drift.sh false-positive UNEXPECTED pattern for delete-action entries persists (same as pulse-1). No corrective action needed.

## Recommendation: CONTINUE

All pulse-1 pending items are resolved: internals 10/10, test renames 10/10, kyo-jsonrpc-http 1/1, kyo-browser 6/6. The entire scope of Phase 3 is now represented in the dirty tree. No old top-level type names remain as code references. Impl is ready for the verification command (`sbt 'kyo-jsonrpcJVM/Test/compile' 'kyo-jsonrpc-httpJVM/Test/compile' 'kyo-browserJVM/Test/compile' 'kyo-jsonrpcJVM/test'`).

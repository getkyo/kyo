# Phase 3 pulse 1

Time: 2026-05-30T00:08:00Z
Files reviewed: design/05-plan.yaml §phase-3, control/steering.md, phases/phase-03/decisions.md, git status --porcelain, git diff --stat
Plan cites: ./design/05-plan.yaml §Phase 3

## Plan anchor

- Files to delete (10 target): 10/10 DONE (all standalone policy/seam files deleted)
- Companion files to edit (4 target): 4/4 DONE (JsonRpcEndpoint, JsonRpcTransport, JsonRpcMethod, JsonRpcEnvelope all modified)
- Internal engine/codec edits: 4/10 done (CancellationEngine, IdStrategyEngine, JsonRpcEndpointImpl, ProgressEngine modified; JsonRpcCodecImpl modified; 5 internal files still unmodified: JsonRpcRequest.scala, StdioWireTransport.scala, WireTransportAdapter.scala, FramerImpl.scala, UdsWireTransport.scala)
- kyo-jsonrpc-http edit: 0/1 done (JsonRpcHttpTransport.scala unmodified)
- kyo-browser edits: 0/6 done (CdpBackend.scala + 5 test files unmodified)
- Test renames (10): 0/10 done (all 10 old test names still present; none of the new JsonRpcEndpoint*/JsonRpcTransport*/JsonRpcMethod*/JsonRpcEnvelope* test names exist)
- Net LOC change: ~494 insertions / ~65 deletions = ~559 gross, ~429 net (vs estimated 75 net). Gross is high due to absorbing full type bodies into companions.
- Public API additions: all 11 nested types now visible inside companions (UnknownMethodPolicy, MessageGate, CancellationPolicy, ProgressPolicy, ExtrasEncoder, Id, Context, WireTransport, Framer objects/traits)

## Reward-hacking checks

| Pattern | Verdict | Citation |
|---|---|---|
| Verification commands actually run | CLEAN | No impl stdout; decisions.md records convention sweep results but no compile gate run yet |
| Compile-only "success" claim | CLEAN | No claim of passing; work in progress |
| Priority inference | CLEAN | decisions.md shows proper rationale for all 8 decisions |
| Scope substitution | CLEAN | All 11 nesting moves present and aligned with plan §Phase 3 |
| Foreach-discards-assert | N/A | No test code in dirty tree yet |
| Stale-state passing | CLEAN | Deletions are staged; dirty tree reflects actual filesystem state |

## Drifting checks

| Pattern | Verdict | Citation |
|---|---|---|
| Public API signature matches plan | CLEAN | All 11 nested types absorbed under correct owning companion per plan table |
| No off-plan architecture substitution | CLEAN | All dirty-tree files are listed in plan §Phase 3 files_modified |
| No cross-cutting refactor outside phase | CLEAN | No files outside kyo-jsonrpc/* in dirty tree (kyo-browser unmodified so far, which is expected mid-impl) |
| Internal helpers stay internal | CLEAN | Private aliases kept private per Decision 3 |

Note: `flow-pulse-drift.sh` reports all dirty-tree files as UNEXPECTED because the script does exact path matching against the plan yaml and does not handle `action: delete` entries correctly (deleted files appear as unexpected). All flagged paths are listed in plan §Phase 3. Not a real drift finding.

Note: `flow-verify-grep.sh --catalog reward-hacking` reports 60 hits all in `kyo-test/procedures/` -- a different campaign's plan files. 0 hits in kyo-jsonrpc sources.

## Scope-cutting checks

| Leaf | Status | Notes |
|---|---|---|
| 1: Delete IdStrategy.scala | PRESENT_STRICT | Deleted in dirty tree |
| 2: Delete UnknownMethodPolicy.scala | PRESENT_STRICT | Deleted in dirty tree |
| 3: Delete MessageGate.scala | PRESENT_STRICT | Deleted in dirty tree |
| 4: Delete CancellationPolicy.scala | PRESENT_STRICT | Deleted in dirty tree |
| 5: Delete ProgressPolicy.scala | PRESENT_STRICT | Deleted in dirty tree |
| 6: Delete ExtrasEncoder.scala | PRESENT_STRICT | Deleted in dirty tree |
| 7: Delete Framer.scala | PRESENT_STRICT | Deleted in dirty tree |
| 8: Delete WireTransport.scala | PRESENT_STRICT | Deleted in dirty tree |
| 9: Delete HandlerCtx.scala (rename to JsonRpcMethod.Context) | PRESENT_STRICT | Deleted + absorbed under JsonRpcMethod |
| 10: Delete JsonRpcId.scala (rename to JsonRpcEnvelope.Id) | PRESENT_STRICT | Deleted + absorbed under JsonRpcEnvelope |
| 11: Edit JsonRpcEndpoint.scala (absorb 6 types) | PRESENT_STRICT | Modified with all 6 nested types |
| 12: Edit JsonRpcTransport.scala (absorb 2 types) | PRESENT_STRICT | Modified with WireTransport + Framer |
| 13: Edit JsonRpcMethod.scala (absorb Context) | PRESENT_STRICT | Modified |
| 14: Edit JsonRpcEnvelope.scala (absorb Id) | PRESENT_STRICT | Modified |
| 15: Edit internal/engine/* (update refs) | PARTIAL | 4/4 engine files modified; codec/transport internals pending |
| 16: Test renames (10 files) | MISSING | 0/10 renamed; old names still present |
| 17: Edit kyo-jsonrpc-http/JsonRpcHttpTransport.scala | MISSING | Unmodified |
| 18: Edit kyo-browser/* (6 files) | MISSING | All 6 unmodified |

## CRITICAL (steer immediately)

None. The impl is mid-flight; deletes and companion absorptions are complete. Remaining work (test renames, 5 internal file updates, http/browser caller updates) is expected to be in progress.

## MINOR (queue for post-commit audit)

1. `flow-pulse-drift.sh` false-positive UNEXPECTED reports for all phase-3 plan files: the script does not recognize `action: delete` entries. No corrective action needed for impl; note for script calibration.
2. Net LOC delta is ~429 (well above the 75 estimated). The estimate was "net" but absorbing full type bodies into companions is mechanically correct -- the high line count is expected for this move pattern. Not a scope issue.
3. 10 test files still carry old names (e.g. `IdStrategyTest.scala`). The rename step (plan Decision 5) has not started yet.

## Recommendation: CONTINUE

Deletes: 10/10 complete. Companions: 4/4 modified. Engine internals: 4+1/10 done. Remaining: test renames (10), internal transport/codec/framing/uds (5 files), kyo-jsonrpc-http (1 file), kyo-browser (6 files). Impl is on-plan and converging. No drift, no reward-hacking. No steer needed.

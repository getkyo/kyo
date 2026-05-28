# Phase 5 In-Flight Review (pulse 2)

Pulse 2: 2026-05-28T06:16Z

## Steer verification
| # | Steer | PASS/FAIL | Evidence |
|---|-------|-----------|----------|
| 1 | Build no longer broken | FAIL | E175 on `JsonRpcEndpointImpl.scala:447`: discarded non-Unit from `pendingInbound.remove(id)` |
| 2 | CancellationPolicy 5 fields | FAIL | Case class has **6 fields**: cancelMethod, encodeParams, **extractId**, expectReplyForCancelledRequest, cancelledError, protectedMethods — `extractId` was NOT removed |
| 3 | 14 tests present | PASS | `CancellationPolicyTest.scala` exists with exactly 14 `" in` labels |

## Compile state
```
[error] -- [E175] Potential Issue Error: kyo-jsonrpc/shared/src/main/scala/kyo/internal/JsonRpcEndpointImpl.scala:447:97
[error] 447 |                                                                            pendingInbound.remove(id)
[error]     |                                                                            ^^^^^^^^^^^^^^^^^^^^^^^^^
[error]     |discarded non-Unit value of type kyo.internal.InboundEntry. Add `: Unit` to discard silently.
[error] one error found
[error] (kyo-jsonrpc / Compile / compileIncremental) Compilation failed
```

## Test count
14 test labels confirmed in `CancellationPolicyTest.scala`:
1. LSP inbound cancel: handler observes cancelled and caller gets -32800
2. LSP inbound cancel: a reply IS still sent on the transport
3. MCP inbound cancel: no reply is sent on the transport
4. MCP inbound cancel race: cancel while reply queued in writer channel suppresses the reply
5. LSP outbound cancel: sends $/cancelRequest notification and call fails with -32800
6. MCP outbound cancel: sends notifications/cancelled with requestId and reason, call fails
7. cancel for protected method (MCP initialize) sends no notification and does not abort call
8. cancel for already-completed call returns unit without sending a cancel notification
9. inbound cancel for absent handler id is silently dropped without error
10. timeout with LSP policy sends $/cancelRequest and caller fails with -32800
11. timeout with cancellation=Absent sends no cancel notification; call fails locally
12. handler aborts with ContentModified on cancel: wire response carries -32801 verbatim
13. cancel notification carries extras from original call (C1 extras propagation)
14. cancel-during-encode race: encoded request still sent but caller observes abort immediately

## CRITICAL
- **Build is still broken**: `JsonRpcEndpointImpl.scala:447` discards the return value of `pendingInbound.remove(id)` (type `InboundEntry`). Fix: wrap with `discard(pendingInbound.remove(id))`.
- **`extractId` field NOT removed from `CancellationPolicy`**: The case class still has 6 fields. Pulse-1 steer required removing `extractId` and scoping it as a private companion helper. This was not done. IMPLEMENTATION.md line 316 specifies exactly 5 fields (no `extractId`).
- Both pulse-1 steers for structural issues (steer 1 and steer 2) are outstanding. Steer 3 (test count) is the only one that landed.

## Recommendation: STEER

Two of three pulse-1 steers are unresolved. Build is broken (E175). `CancellationPolicy` still carries the `extractId` public field that was to be moved into companion scope. Agent must:
1. Fix `JsonRpcEndpointImpl.scala:447`: `discard(pendingInbound.remove(id))`.
2. Remove `extractId: CancellationPolicy.IdExtractor` from the `CancellationPolicy` case class; move the field's usage into private companion logic per IMPLEMENTATION.md §316.
3. Re-run `kyo-jsonrpc/Test/compile` to confirm clean build before any further test work.

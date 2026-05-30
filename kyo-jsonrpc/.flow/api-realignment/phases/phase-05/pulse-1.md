# Phase 05 pulse 1

Time: 2026-05-30T00:00:00Z
Files reviewed: 5 modified source files + 2 modified control files
Plan cites: ./design/realignment-plan.md §Phase E

## Plan anchor
- Files to produce: `JsonRpcHandler.Unsafe` (as abstract class inside companion) — PRESENT (within JsonRpcHandler.scala diff)
- Files to modify: `JsonRpcHandler.scala`, `JsonRpcRoute.scala`, `JsonRpcEndpointImpl.scala`, `JsonRpcHandlerTest.scala`, `JsonRpcHandlerUnknownMethodPolicyTest.scala` — ALL 5 MODIFIED
- Tests: existing tests updated for opaque-type `.unsafe` accessor pattern — PRESENT
- Public API additions expected: `opaque type JsonRpcHandler`, `extension (self: JsonRpcHandler)`, `JsonRpcHandler.Unsafe` abstract class, `Unsafe.dispatch` — PRESENT

## Reward-hacking checks
| Pattern | Verdict | Citation |
|---|---|---|
| Verification commands actually run | UNKNOWN — no impl stdout provided; no runs/ artifacts observed | runs/ dir exists but empty |
| Compile-only "success" claim | CLEAN — impl is mid-flight; no false success claimed | dirty tree has no fabricated test output |
| Priority inference | CLEAN — opaque-type conversion is Phase E's primary deliverable and is present | JsonRpcHandler.scala diff line 44: `opaque type JsonRpcHandler = JsonRpcHandler.Unsafe` |
| Scope substitution | FLAG: STEER items (errorMappings wiring + Halt routing) not yet addressed — see CRITICAL | JsonRpcEndpointImpl.scala: no `errorMappings` or `Halt` catch in inbound dispatch path |
| Foreach-discards-assert | CLEAN — test changes tighten assertions (`.unsafe.asInstanceOf[...]`) not loosen them | JsonRpcHandlerTest.scala diff |
| Stale-state passing | CLEAN — no stale state pattern; opaque type properly delegates to Unsafe methods | JsonRpcHandler.scala extension block delegates to `self.callUnsafe`, etc. |

## Drifting checks
| Pattern | Verdict | Citation |
|---|---|---|
| Public API signature matches plan | CLEAN — opaque type + extension block + abstract Unsafe class mirrors plan exactly | JsonRpcHandler.scala: `opaque type JsonRpcHandler = JsonRpcHandler.Unsafe`; `abstract class Unsafe` at line 100 |
| No off-plan architecture substitution | CLEAN — `abstract class Unsafe` (not `sealed abstract`) matches plan phrasing "introduce `JsonRpcHandler.Unsafe`"; kyo-http HttpServer.Unsafe precedent followed | HttpServer.scala:145 cited in impl comment |
| No cross-cutting refactor outside phase | CLEAN — only Phase E files modified; no Phase F/G type hoists touched | 5 files, all within phase scope |
| Internal helpers stay internal | CLEAN — `JsonRpcRoute.dispatch` moved to `private[kyo]`; `JsonRpcEndpointImpl.initEngine` moved to `private[kyo]`; `dispatch` method on `Unsafe` added as planned (audit A1) | JsonRpcRoute.scala diff; JsonRpcEndpointImpl.scala diff |

## Scope-cutting checks
| Leaf | Status | Notes |
|---|---|---|
| 1: `opaque type JsonRpcHandler = JsonRpcHandler.Unsafe` | PRESENT_STRICT | JsonRpcHandler.scala diff line 44 |
| 2: `extension (self: JsonRpcHandler)` block in companion | PRESENT_STRICT | All 10 methods present (call, notify, sendUnmatched, callWithProgress, callPartialResults, subscribeProgress, unsubscribeProgress, cancel, awaitDrain, close, closeNow, unsafe accessor) |
| 3: `JsonRpcHandler.Unsafe` abstract class | PRESENT_STRICT | JsonRpcHandler.scala line 100; JsonRpcEndpointImpl extends it |
| 4: `closeNow` overload ambiguity scaladoc dropped | PRESENT_STRICT | New scaladoc omits the workaround note (diff confirms) |
| 5: `JsonRpcRoute.dispatch` moved to internal (audit A1) | PRESENT_STRICT | `private[kyo] def dispatch` in JsonRpcRoute.scala; `Unsafe.dispatch` wrapper added to JsonRpcHandler.Unsafe |
| 6: STEER-1 — wire `errorMappings` in engine dispatch loop | MISSING | Zero `errorMappings` / `ErrorMapping` references in JsonRpcEndpointImpl.scala; `Result.Failure(e)` path emits raw error without consulting mappings |
| 7: STEER-2 — Halt-before-panic catch in inbound dispatch | MISSING | `fiber.unsafe.onComplete` at line ~1059 handles `Result.Failure(e)` as `JsonRpcResponse(id, Absent, Present(e), extras)` with no `Halt` intercept; `Result.Panic` goes to `JsonRpcHandlerPanicError`; `Halt` lands as `Panic` |
| 8: Halt integration test | MISSING | No new test asserting wrapped Halt response reaches wire |
| 9: ErrorMapping integration test | MISSING | No new test asserting `.error[E2]` mapping produces correct wire code |

## CRITICAL (steer immediately)

None — the impl is on-plan for the primary deliverable (opaque type + Unsafe tier). The STEER items are in-progress and legitimately unfinished at pulse 1 (~8 min). No silent dropping: the steering note in control/steering.md is explicit that these can defer to Phase 06 if too large. No reward-hacking pattern detected.

## MINOR (queue for post-commit audit)

1. `JsonRpcHandlerTest.scala` test accesses internals via `.unsafe.asInstanceOf[internal.engine.JsonRpcEndpointImpl]` — brittle for callers outside `kyo` package; consider exposing typed test helpers in the Unsafe tier if tests need `callerRegistry` / `inFlight` inspection (post-commit audit item).
2. `notify` `end` tag not updated after rename — `end notify` at JsonRpcEndpointImpl.scala line 353 references old name (should be `end notifyUnsafe`); confirm fix is in the full diff or flag for audit.
3. `sendUnmatched` `end` tag similarly: `end sendUnmatched` should be `end sendUnmatchedUnsafe` — verify.
4. STEER-1 and STEER-2 must be complete before commit. Steering note allows deferral to Phase 06 IF they prove too large — impl agent must make the explicit defer decision, not silently omit.

## Recommendation: CONTINUE

On-plan at pulse 1. The opaque-type conversion, extension block, Unsafe abstract class, and dispatch migration are all PRESENT_STRICT. The two STEER items (errorMappings wiring, Halt routing) are legitimately not yet addressed at the 8-minute mark and the steering note already provides a Phase 06 escape hatch. No em-dash drift detected (rg found nothing). No reward-hacking pattern detected. No LoC blow-up (316 changed lines vs 250 estimated — within 1.3x, well under 2x kill threshold). Next pulse at ~20-minute mark should verify STEER items progressing or the explicit Phase 06 defer decision logged.

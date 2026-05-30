# Phase 05 audit

Time: 2026-05-30T00:00:00Z
HEAD: c28e2dd6b
Phase commit: c28e2dd6b
Plan cites: ./design/realignment-plan.md §Phase E (lines 364-376)
Design cites: ./design/realignment-plan.md (treated as design source); kyo-http/shared/src/main/scala/kyo/HttpServer.scala:37 precedent

## Test count
| Leaf | Status | Notes |
|---|---|---|
| 1: `opaque type JsonRpcHandler = JsonRpcHandler.Unsafe` | PRESENT_STRICT | JsonRpcHandler.scala:30 — identical shape to HttpServer.scala:37 |
| 2: `extension (self: JsonRpcHandler)` block with all former instance methods | PRESENT_STRICT | JsonRpcHandler.scala:34-97 — 12 extension methods: call, notify, sendUnmatched, callWithProgress, callPartialResults, subscribeProgress, unsubscribeProgress, cancel, awaitDrain, close(0-arg), close(gracePeriod), closeNow, plus `unsafe` accessor (= 13 total inc. accessor). Matches decisions.md count of 12 instance-method migrations |
| 3: `JsonRpcHandler.Unsafe` abstract class with all method signatures | PRESENT_STRICT | JsonRpcHandler.scala:100-156 — 10 abstract `*Unsafe` methods + `dispatch` + `final def safe`. Mirrors HttpServer.Unsafe:145-163 shape |
| 4: Concrete impl in JsonRpcEndpointImpl extends Unsafe | PRESENT_STRICT | JsonRpcEndpointImpl.scala:47-67 — `final class JsonRpcEndpointImpl private[kyo] (...) extends JsonRpcHandler.Unsafe` |
| 5: Extension methods cover all 12 former instance methods | PRESENT_STRICT | Decisions table at decisions.md:20-33 enumerates all 12; verified in source |
| 6: `JsonRpcRoute.dispatch` demoted to `private[kyo]` | PRESENT_STRICT | JsonRpcRoute.scala:140 — `private[kyo] def dispatch(` |
| 7: `Unsafe.dispatch` wrapper present | PRESENT_STRICT | JsonRpcHandler.scala:148-152 (abstract); JsonRpcEndpointImpl.scala:664-676 (concrete impl using local methodMap, per audit A1) |
| 8: close-overload ambiguity scaladoc removed | PRESENT_STRICT | JsonRpcHandler.scala:83-93 — only single-line scaladoc per close variant; no Scala 3 overload-resolution warning text present |
| 9: closeFiber omission (declared deviation) | WEAKENED | Acceptable per decisions.md §Deviations item 1 — `closeUnsafe(gracePeriod): Unit < Async` is functionally equivalent without `Fiber.Unsafe` wrap. Diverges from HttpServer.Unsafe:156 but justified |

## CONTRIBUTING.md violations
- None observed in changed files. Public APIs carry explicit return types, scaladoc present on opaque type and Unsafe class, `using Frame` propagated on all suspended methods.

## Unsafe markers
- The Unsafe tier is intentional API surface (mirrors HttpServer.Unsafe). All `*Unsafe` methods carry the required `(using Frame)` and abort/effect rows. No bare `AllowUnsafe.embrace.danger` blocks introduced in this phase.
- Note: HttpServer.Unsafe.closeFiber uses `(using AllowUnsafe, Frame)`; JsonRpcHandler.Unsafe.closeUnsafe uses only `(using Frame)`. Consistent with the "returns a properly suspended effect, not a raw Fiber.Unsafe" deviation.

## Cross-platform consistency
- Phase 05 modifies only `shared/` sources in kyo-jsonrpc. No jvm/js/native-specific divergence introduced.
- decisions.md reports "Compile-green on all 3 modules"; no per-platform deltas observed in the diff.

## Naming convention compliance
- `*Unsafe` suffix consistently applied across abstract methods (callUnsafe, notifyUnsafe, sendUnmatchedUnsafe, callWithProgressUnsafe, callPartialResultsUnsafe, subscribeProgressUnsafe, unsubscribeProgressUnsafe, cancelUnsafe, awaitDrainUnsafe, closeUnsafe). `dispatch` is the one exception (kept as plain name on Unsafe tier per the audit A1 contract).
- `end notifyUnsafe` / `end sendUnmatchedUnsafe` tags updated correctly at JsonRpcEndpointImpl.scala:355 and :369 (pulse-1 MINOR items resolved).

## Steering deviation
- `git diff --name-only HEAD~1 HEAD` for Phase 05: 11 files (5 source + 2 test + 4 flow-artifact).
- Source files match plan §Phase E scope: JsonRpcHandler.scala, JsonRpcRoute.scala, JsonRpcEndpointImpl.scala, JsonRpcHandlerTest.scala, JsonRpcHandlerUnknownMethodPolicyTest.scala. No off-scope source touched.
- 316 LoC changed vs ~250 LoC estimate — within 1.3x, well under 2x kill threshold.

## Anti-flakiness measures
- Tests updated to use `.unsafe.asInstanceOf[internal.engine.JsonRpcEndpointImpl]` for internal-state assertions. Brittle (NOTE-1 below) but not flaky — deterministic cast against the only concrete Unsafe impl.
- 186/186 test pass per decisions.md.

## Architecture substitution check
- Design intent: opaque type with abstract Unsafe class, mirroring HttpServer.scala:37 verbatim.
- HEAD reality: `opaque type JsonRpcHandler = JsonRpcHandler.Unsafe` + `abstract class Unsafe` + `final def safe` + extension block + JsonRpcEndpointImpl extends Unsafe.
- Verdict: MATCH. The three documented deviations (closeFiber omission, no Sync.Unsafe.defer bridge, abstract not sealed) are all justified by HttpServer's own pattern (HttpServer.Unsafe is also `abstract class` not `sealed`, per decisions.md cite of HttpServer.scala:145) or by the legitimate observation that the underlying impl already returns suspended effects.

## Documentation drift
- Scaladoc additions: opaque-type top doc (29 lines), Unsafe class banner, dispatch method doc, safe accessor doc, close variants. All within plan intent (documenting the new opaque-type shape and Unsafe tier).
- No off-plan API documented. No spec-level prose added beyond what HttpServer.scala carries.
- Beyond plan intent: no.

## Held-out check: STEER items from Phase 04 audit
Per the steering directive at control/steering.md:43-51, Phase 05 was originally scoped to fold in:
  1. `ErrorMapping[E].matches` engine wiring (so `.error[E2]` is functional, not dead API).
  2. Halt-in-request-handler routing fix (so `Abort.fail(JsonRpcResponse.Halt(resp))` lands as the wrapped response, not `JsonRpcHandlerPanicError`).

Verified at HEAD: zero `errorMappings` / `ErrorMapping` / `Halt` references in JsonRpcEndpointImpl.scala. Both STEER items are **NOT addressed** in Phase 05.

Deferral documented in:
- decisions.md `Deviations` section (implicitly via commit message "deferred to Phase 06's scope").
- Phase 05 commit message body: "The two STEER items from Phase 04 audit (ErrorMapping engine wiring and Halt routing fix) are deferred to Phase 06's scope. Documented in control/steering.md and control/progress.md."
- control/progress.md: Phase 04 entry records both as WARN-1 / WARN-2; Phase 05 entry must roll them forward.

Status: KNOWN GAP, ROLLED FORWARD. Not a BLOCKER for Phase 05 (which has its own legitimate primary scope of opaque-type conversion), but MUST be in Phase 06 prep input.

## Em-dash status
- `git show c28e2dd6b -- '*.scala' | grep -E '^\+' | grep -E '—|–'` — zero hits on added lines in source files.
- pulse-1.md decisions.md also clean (none on added lines).
- Verdict: PASS.

## Findings (categorized)

### BLOCKER
- None.

### WARN
- **WARN-1**: STEER-1 (ErrorMapping engine wiring) deferred from Phase 05 to Phase 06. `.error[E2]` remains dead API at HEAD. Must be Phase 06 prep input.
- **WARN-2**: STEER-2 (Halt-in-request-handler routing) deferred from Phase 05 to Phase 06. `Abort.fail(JsonRpcResponse.Halt(resp))` still lands as `JsonRpcHandlerPanicError`. Must be Phase 06 prep input alongside the integration test that asserts the wrapped response reaches the wire.

### NOTE
- **NOTE-1**: Tests access internals via `handler.unsafe.asInstanceOf[internal.engine.JsonRpcEndpointImpl].callerRegistry` (decisions.md:74-76). Acceptable for in-package tests but brittle for any cross-package test that needs the same state. Consider exposing typed test helpers (e.g., `Unsafe.callerRegistryView`) if cross-package tests need this in a later phase. End-of-project cleanup candidate.
- **NOTE-2**: `closeFiber` omission is a deliberate divergence from HttpServer.Unsafe:156 (decisions.md §Deviations item 1). If a future requirement emerges for callers to obtain a `Fiber.Unsafe[Unit, Any]` handle on close (e.g., to compose with other unsafe-tier fibers), the shape will need to be added. Tracked as a design decision, not a defect.
- **NOTE-3**: The dispatch wrapper on Unsafe (JsonRpcHandler.scala:148-152, JsonRpcEndpointImpl.scala:664-676) reaches into the local `methodMap` rather than calling `JsonRpcRoute.dispatch` directly. Audit A1 contract is satisfied (public API no longer leaks `JsonRpcRoute.dispatch`), but the duplicated lookup-and-handle logic could be unified via a shared internal helper in a follow-up. Cosmetic.

## Routing
- BLOCKER findings: 0 — no halt of SLOT-A launch for Phase 07.
- WARN findings (2): TaskCreate for Phase 06 prep input. WARN-1 + WARN-2 are the explicit Phase 06 scope additions per control/steering.md:43-51.
- NOTE findings (3): TaskCreate for end-of-project cleanup queue.

## Summary

Phase 05 executes the opaque-type conversion cleanly. Shape mirrors HttpServer.scala:37 verbatim. All 12 instance-method migrations present. Audit A1 (`JsonRpcRoute.dispatch` demotion) satisfied. Audit A4 (close-overload scaladoc) satisfied. The three deviations (closeFiber omission, no Sync.Unsafe.defer bridge, abstract not sealed) are justified and documented. The two STEER items from Phase 04 audit (ErrorMapping wiring, Halt routing) are explicitly deferred to Phase 06 with rationale in steering.md and the commit message — not silent omission. Em-dashes clean. No BLOCKER findings.

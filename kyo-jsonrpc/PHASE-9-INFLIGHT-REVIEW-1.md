# Phase 9 In-Flight Review (pulse 1)

## Test count per file

| File | Expected | Actual |
|------|----------|--------|
| ScenarioHttpStyleTest | 3 | 3 |
| ScenarioWsStyleTest | 3 | 3 |
| ScenarioBidiTest | 4 | 4 |
| **Total** | **10** | **10** |

All counts match the plan.

## Compile state

Classes present in `jvm/target/scala-3.8.3/test-classes/kyo/`. Cached test reports confirm last compile was clean (0 errors, 0 failures). No recompile was triggered during this review.

## Test state

From cached JUnit XML reports (latest run: 2026-05-28):

| Suite | tests | errors | failures | skipped |
|-------|-------|--------|----------|---------|
| ScenarioHttpStyleTest | 3 | 0 | 0 | 0 |
| ScenarioWsStyleTest | 3 | 0 | 0 | 0 |
| ScenarioBidiTest | 4 | 0 | 0 | 0 |

All 10 tests PASS.

## Weak assertions

None found. Checked for: `assert(true)`, `case _ => succeed`, `_.isSuccess`, `Result.Failure(_)` (unbound wildcard catch-all).

All failure branches use `fail(...)` with a diagnostic message. LSP cancel test asserts exact error code `-32800`; MCP cancel test asserts zero Response frames; gate test asserts exact code `-32002`.

## Wallclock-timing usage

Two `Async.sleep` uses found — both are **problematic parking witnesses**, not deterministic handoffs:

1. **ScenarioWsStyleTest.scala:105** — `Async.sleep(30.millis)` in Test 99 (CDP maxInFlight). Used after firing the 9th call to assert that `entered.get() == 8` (i.e., 9th has not entered yet). This is a timing probe, not a Latch handoff. The plan specification (IMPLEMENTATION.md line 538) explicitly requires verification "via Latch handoff, not wallclock."

2. **ScenarioBidiTest.scala:130** — `Async.sleep(100.millis)` in Test 103 (MCP no-reply). Used after `callFib.get` to wait for any stray response frame before asserting absence. Deterministic alternative: the plan's supervision note (line 557) says verify by counting frames on the transport after cancellation, but the current implementation sleeps rather than waiting on a deterministic signal (e.g., a Latch released by the transport after the handler fiber terminates).

## Convention sweep

| Check | Result |
|-------|--------|
| Em-dashes (`—`) | 0 — CLEAN |
| `asInstanceOf` | 0 — CLEAN |
| `: Option[` (should be Maybe) | 0 — CLEAN |
| Trailing semicolons | 0 — CLEAN |
| `AllowUnsafe` without `// Unsafe:` comment | 2 occurrences (ScenarioWsStyleTest.scala lines 110, 117) — `p.unsafe.completeUnitDiscard()(using AllowUnsafe.embrace.danger)` present with no `// Unsafe:` block comment on either line |

The `AllowUnsafe` calls in Test 99 are bridging-justified (resolving a `Fiber.Promise` from outside the fiber), but the `// Unsafe:` annotation convention required by MEMORY.md is missing on both lines.

## Per-test plan match

| Test # | Label | Present | Strong assertion | Wallclock-free | Plan match |
|--------|-------|---------|-----------------|----------------|-----------|
| 95 | single server endpoint, two sequential calls return typed results | YES | YES — asserts `addResult == AddResp(10)` and `greetResult == GreetResp("Hello, World!")` | YES | YES |
| 96 | notification triggers handler; no reply frame arrives | YES | YES — asserts `responses.isEmpty` and `handlerRan.get() == 1` | YES | YES |
| 97 | LSP pre-init gate: -32002 before init, success after | YES | YES — asserts exact code `-32002` and both response values | YES | YES |
| 98 | B interleaves notifications to A; no cross-wiring | YES | YES — asserts `events == List("alpha","beta")` and both call responses | YES | YES |
| 99 | CDP maxInFlight=8: 9th parks until slot freed | YES | YES for slot count — but uses `Async.sleep(30.millis)` as parking witness instead of Latch handoff | **NO** — wallclock sleep violates plan spec | PARTIAL |
| 100 | CDP extras: sessionId visible in B's ctx.extras | YES | YES — asserts `sessionId == Some(Str("s1"))` | YES | YES |
| 101 | simultaneous A.call(B) and B.call(A) without id collision | YES | YES — asserts `AddResp(8)` and `EchoResp("HELLO")` | YES | YES |
| 102 | LSP cancel: B responds with -32800; Response IS on transport | YES | YES — asserts `e.code == -32800` and transport contains Response frame | YES | YES |
| 103 | MCP cancel: NO response frame on transport | YES | YES — asserts `noReply` over all frames | **NO** — `Async.sleep(100.millis)` used as drain delay instead of deterministic handoff | PARTIAL |
| 104 | LSP progress: 3 values collected; final result arrives | YES | YES — asserts `collected.size == 3` and `finalResp == WorkResp(true)` | YES | YES |

## CRITICAL

1. **Test 99 wallclock parking witness** (ScenarioWsStyleTest.scala:105): `Async.sleep(30.millis)` is used to assert the 9th call has not yet entered. The plan (IMPLEMENTATION.md line 538) explicitly forbids this: "verify via Latch handoff, not wallclock." The 9th call's submission future parking must be witnessed deterministically — a `Latch` or a second `AtomicInteger` check via `untilTrue` on the scheduler queue depth, not a time delay. This is a spec violation.

2. **Test 103 wallclock drain delay** (ScenarioBidiTest.scala:130): `Async.sleep(100.millis)` is used after `callFib.get` to allow any stray Response frame to arrive before asserting absence. A deterministic witness (e.g., waiting for the handler fiber to terminate via a Latch set in the cancel path, or waiting for the transport's sent-count to stabilize) is needed per the supervision plan.

3. **Missing `// Unsafe:` comments** (ScenarioWsStyleTest.scala lines 110, 117): Both `AllowUnsafe.embrace.danger` sites lack the required annotation comment per MEMORY.md convention.

## Recommendation: STEER

Two tests have wallclock-timing issues that violate the plan's explicit Latch-handoff requirement (Tests 99 and 103). Missing `// Unsafe:` annotations on two lines in Test 99. All other tests are correct, complete, and strongly asserted. Fix the two parking witnesses before Phase 10.

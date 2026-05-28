# Phase 4 In-Flight Review (pulse 4)

Pulse 4: 2026-05-28T03:28Z

## Pulse-3 steer verification

| # | Steer | Status | Evidence |
|---|-------|--------|----------|
| 1 | Test 44 present (late-reply dropped) | FAIL | Test file has 17 tests; none matches "late reply", "dropped", or any variant. Expected 18 tests per steer. The test is missing. |
| 2 | Test 38/39 tightened assertions | FAIL | Tests 38/39 as named in the steer do not exist under those names. The endpoint test file has "Scope exit closes Exchange..." (line 89) and "callerRegistry drain on close..." (line 120). Both use `Result.Failure(_)` wildcard — neither checks `c: Closed` vs `JsonRpcError` specifically. Assertions remain broad. |
| 3 | Test 49 counting transport | FAIL | No test matching "exit-after-shutdown", "counting", `sendCount`, or `CountingTransport` found anywhere in the test suite. The steer-required counting transport wrapper does not exist. |
| 4 | line 342 `// Unsafe:` comment | FAIL | Line 342 is `discard(writerChannel.unsafe.offer(...)...)`. No `// Unsafe:` comment precedes it. The comment at line 337 covers `AtomicBoolean.Unsafe.init`, not the `writerChannel.unsafe.offer`. The exact steer ("immediately before that line") is not satisfied. |
| 5 | Writer fiber loop complete | PARTIAL | Both `SendEnvelope` and `SuppressIfCancelled` cases are present and correct. The `suppress` flag is checked via `.map` on `r.suppress.get`, and `Sync.Unsafe.defer(pendingInbound.remove(id))` runs in both branches. However: `Abort.run[Closed](transport.send(...)).unit` silently swallows Closed — it does NOT propagate to an endpoint.close path. The steer's fourth bullet ("On Closed during transport.send, propagate to endpoint.close path") is not satisfied. |

## Compile state

`sbt 'kyo-jsonrpc/Test/compile'` tail-5 output:

```
[info] loading settings for project crispy-swinging-lemur-build from build.sbt, plugins.sbt...
[info] loading project definition from ...
[info] loading settings for project kyoJVM from build.sbt...
[info] set current project to kyoJVM ...
[success] Total time: 2 s, completed May 28, 2026, 3:28:29 AM
```

Compiles clean. No errors, no warnings surfaced in tail output.

## Test assertions weakness scan

No `assert(true)`, `assert(_.isSuccess)`, or `case _ => succeed` patterns found in any test file. The one concern is the _broad_ `Result.Failure(_)` patterns in the close/drain tests (lines 109, 139) — these accept any failure type, which is the pulse-3 steer item 2, not yet fixed.

## CRITICAL (steer immediately)

1. **Test 44 still missing.** Write test: cancel an in-flight outbound call via `endpoint.cancel(id, Absent)`, have the slow handler on the peer eventually reply, assert the already-resolved call fiber is unaffected and no exception is raised. This is the only test covering "late reply silently dropped."

2. **Tests 38/39 assertions still broad.** `Result.Failure(_)` must become:
   - "Scope exit" test (line 109): `Result.Failure(c: Closed)` — Exchange's pending map resolves with Closed, not JsonRpcError.
   - "callerRegistry drain" test (line 139): `Result.Failure(e: JsonRpcError)` with an internalError code check (e.g. `e.code == -32603`), per DESIGN §6.4 step 6.

3. **Test 49 counting transport missing.** Implement a `CountingTransport` wrapper (private, in test scope) that delegates to InMemoryTransport and increments an `AtomicInt` on each `send`. Write a test that calls `endpoint.close` then asserts `sendCount` did not increase after close.

4. **`// Unsafe:` comment missing at line 342.** Add `// Unsafe: writer-channel offer from Sync-only onComplete callback` on the line immediately before `discard(writerChannel.unsafe.offer(...))`.

5. **Writer fiber does not propagate transport Closed.** `Abort.run[Closed](transport.send(...)).unit` silently discards Closed. Per IMPLEMENTATION.md the writer fiber should call `endpoint.close` (or trigger the finalizer) when transport.send returns Closed, so the endpoint tears down rather than silently continuing. Add a `case Result.Failure(_) => Abort.fail(Closed(...))` (or similar) to propagate the closed signal out of the `Abort.run[Closed]` wrapper.

## Recommendation: STEER

All 5 pulse-3 steers remain OPEN (items 1, 2, 3 fully missing; item 4 comment placement wrong; item 5 partial — writer fiber handles both cases but does not propagate Closed on transport.send). The compile is clean. Phase 4 is NOT ready to proceed to verification.

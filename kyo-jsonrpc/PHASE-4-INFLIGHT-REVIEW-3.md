# Phase 4 In-Flight Review (pulse 3)

Pulse 3: 2026-05-28T00:00Z

## Test file
- LOC: 321
- Labeled tests found: **17** (one `" in run {` block per test label)
- Missing test numbers (33-50): **Test 44** (late-reply-silently-dropped)
- Test labels vs IMPLEMENTATION.md:
  - Test 33: present ("call add handler returns correct result")
  - Test 34: present ("notify sends one frame and handler runs without reply")
  - Test 35: present ("bidirectional simultaneous calls resolve without cross-wiring")
  - Test 36: present ("multiple concurrent calls resolve independently")
  - Test 37: present ("unknown method request fails with MethodNotFound code -32601")
  - Test 38: present ("Scope exit closes Exchange and fails in-flight calls") — label diverges from IMPLEMENTATION.md which requires "Scope.run cleanup closes Exchange and fails in-flight calls with Closed"; also assertion is weak (see below)
  - Test 39: present ("callerRegistry drain on close fails pending calls") — label truncated vs spec; assertion weak (see below)
  - Test 40: present ("callerRegistry is empty after a call completes normally")
  - Test 41: present ("callerRegistry is empty after call is interrupted externally")
  - Test 42: present ("call returns Abort[Closed] when transport closes mid-call") — assertion weak (see below)
  - Test 43: present ("awaitDrain returns after all pending calls resolve")
  - Test 44: **MISSING** — "Late reply for an already-cancelled outbound call is silently dropped"
  - Test 45: present ("cancel with no CancellationPolicy fails call locally without sending cancel notification")
  - Test 46: present ("ExtrasEncoder.const causes extras to appear in outbound envelope")
  - Test 47: present ("IdStrategy.SequentialLong produces ids Num(1), Num(2), Num(3)")
  - Test 48: present ("IdStrategy.SequentialInt produces ids Num(1), Num(2), Num(3)")
  - Test 49: present ("close prevents further outbound writes") — assertion weak (see below)
  - Test 50: present ("IdStrategy.Custom with concurrent calls produces distinct ids")

- Weak assertions:
  - **Test 38** (line 106): `case Result.Failure(_) => succeed` — accepts any failure type; spec requires `Abort[Closed]` specifically. CRITICAL.
  - **Test 39** (line 133): `case Result.Failure(_) => succeed` — accepts any failure; spec requires `internalError` from callerRegistry drain. CRITICAL.
  - **Test 42** (line 184): `case Result.Failure(_) => succeed` — accepts any failure; spec requires `Abort[Closed]` or `JsonRpcError` but the match arm accepts both without distinguishing the `Closed` case the spec calls out. Minor (the test does not accept success).
  - **Test 49** (line 307): `case Result.Failure(_) => succeed` — spec says "verify with a counting test transport that the write count does not increase after close." The test does not use a counting transport and does not verify frame count. CRITICAL.

## Unsafe comment audit (7 specific lines)

| impl line | Has // Unsafe: comment? | Content |
|-----------|--------------------------|---------|
| 172 | PASS | line 171: `// Unsafe: bulk-complete abortSignals from outside their originating fibers` |
| 222 | PASS | line 221: `// Unsafe: init AtomicInt/AtomicRef/Promise.Unsafe for inFlight and drainSignal counters` |
| 236 | PASS | line 236: `// Unsafe: register in callerRegistry and complete idSignal inside Exchange encode callback` (comment is on the same line as the block opener) |
| 342 | FAIL | nearest comment is line 336 (`// Unsafe: AtomicBoolean.Unsafe.init for suppress flag`) for the CAS init; the `writerChannel.unsafe.offer` on line 342 has no dedicated `// Unsafe:` comment for the channel.unsafe use |
| 346 | N/A | `case _ => ()` is a pure match arm with no AllowUnsafe site; STEERING.md listed it as a site but it holds no unsafe call — closing brace of the `onComplete` is on line 348, which carries `(using AllowUnsafe.embrace.danger)` covered by the line 305 block comment |
| 359 | PASS | line 359: `// Unsafe: offer to writerChannel inside Exchange decode callback; channel.unsafe used here` |
| 386 | PASS | line 384: `// Unsafe: complete abortSignal inside Exchange decode callback so Async.race selects the abort arm` |

Summary: 5 PASS, 1 FAIL (line 342), 1 N/A (line 346 is not an unsafe site).

## Convention sweep
- em-dashes: **0**
- asInstanceOf: **0**
- Option: **0**
- semicolons: **0**

All four sweeps clean.

## JsonRpcEndpointImpl.scala size: 27,974 bytes, **459 LOC**

459 LOC is at the low end of the expected 600-1000 LOC range (IMPLEMENTATION.md supervision plan implies a complete engine). The file ends at line 459 including the companion object. This may be acceptable if logic is spread across helper files, but warrants a sanity check that all 8 finalizer steps and the writer fiber loop are fully implemented (not stubs).

## CRITICAL (steer immediately)

1. **Test 44 missing.** "Late reply for an already-cancelled outbound call is silently dropped by Exchange; pendingInbound is not consulted for outbound drops." No test exists. Must be added before Phase 4 closes.

2. **Test 38 weak assertion (line 106).** `Result.Failure(_)` accepts any error type. Spec requires `Abort[Closed]` (Exchange pending-map drain, §6.4 step 5). Tighten to `case Result.Failure(_: Closed) => succeed` or `case Result.Failure(e: JsonRpcError) if e.code == -32603 => succeed` (whichever the impl actually emits) — but the test label says "Closed" so it must match `_: Closed`.

3. **Test 39 weak assertion (line 133).** Same pattern: `Result.Failure(_)` is undiscriminating. Spec says callerRegistry drain emits `internalError("endpoint closed", ...)` (code -32603). Should assert `e.code == -32603`.

4. **Test 49 weak assertion (lines 307-309).** The spec says "verify with a counting test transport that the write count does not increase after close." The current test uses a plain `inMemory` transport and only checks that a call fails after close — it does NOT count frames. This is a substantive coverage gap: the counting-transport check is the unique value of Test 49 vs Test 38/42/45.

5. **`// Unsafe:` missing at line 342.** `writerChannel.unsafe.offer` inside the `onComplete` callback at line 342 has no direct `// Unsafe:` comment. The block-level comment at line 305 (`// Unsafe: register pendingInbound entry and attach onComplete hook`) covers the outer `Sync.Unsafe.defer` but not the inner `channel.unsafe.offer` at line 342. Add `// Unsafe: writerChannel.unsafe.offer inside onComplete callback; safe channel.put would park` on line 341.

## MINOR (queue for post-commit audit)

1. Test 38 label: "Scope exit closes Exchange and fails in-flight calls" — IMPLEMENTATION.md specifies "Scope.run cleanup closes Exchange and fails in-flight calls with Closed". Minor wording drift; not blocking but label should include "with Closed" to match the spec's emphasis.

2. Test 39 label: "callerRegistry drain on close fails pending calls" — spec says "fails pending calls with internalError". Label omits the error type. Update to "callerRegistry drain on close fails pending calls with internalError".

3. Test 42 assertion (line 184): accepts both `Closed` and `JsonRpcError` with a single `succeed`. Acceptable because the spec itself says "Closed, not a hang" and the test label says "Abort[Closed]"; tighten to `case Result.Failure(_: Closed) => succeed` to make the discriminator explicit.

4. LOC at 459 is low relative to the 600-1000 expectation. Verify that the writer fiber loop (processes `WriterMsg.SendEnvelope` and `WriterMsg.SuppressIfCancelled`) is fully implemented in this file and not deferred to a Phase 5 stub.

## Recommendation: STEER — add Test 44, fix assertions in Tests 38/39/49, add `// Unsafe:` at line 342

# Phase 4 In-Flight Review (pulse 2)

Pulse 2: 2026-05-28T06:04:01Z
Files reviewed:
- `kyo-jsonrpc/shared/src/main/scala/kyo/internal/JsonRpcEndpointImpl.scala`
- `kyo-jsonrpc/shared/src/main/scala/kyo/IdStrategy.scala`
- `kyo-jsonrpc/shared/src/main/scala/kyo/HandlerCtx.scala`
- `kyo-jsonrpc/shared/src/main/scala/kyo/CancellationPolicy.scala`
- `kyo-jsonrpc/shared/src/main/scala/kyo/MessageGate.scala`
- `kyo-jsonrpc/shared/src/main/scala/kyo/ProgressPolicy.scala`
- `kyo-jsonrpc/shared/src/test/scala/kyo/` (all test files)

## Steer-fix verification (pulse 1's 5 critical items)

| # | Steer | Status | Evidence (file:line) |
|---|-------|--------|---------------------|
| 1 | asInstanceOf removed | PASS | `grep -rn 'asInstanceOf' kyo-jsonrpc/shared/src` returns 0 lines |
| 2 | suppress.get typing fixed | PARTIAL | impl:422 `r.suppress.get.map { shouldSuppress => ... }` — now uses `.get` returning `Boolean < Sync` and chains via `.map`, NOT raw Boolean. The `suppress` field is `AtomicBoolean` (safe), so `.get` returns `Boolean < Sync` and `.map` is correct. Pattern is fine. However this is inside the writer fiber body (Async context), NOT inside `Sync.Unsafe.defer`, so the safe `.get.map` path is the right one here. PASS on the typing; the prior `r.suppress.get` as raw Boolean is gone. |
| 3 | `// Unsafe:` comments | FAIL | 7 AllowUnsafe sites still lack a `// Unsafe:` comment within 2 lines above. Detail: **MISSING** at impl:172 (inside `Sync.Unsafe.defer` block — no comment above `info.abortSignal.unsafe.completeDiscard`); impl:222 (`initPromise.completeUnitDiscard()` — comment on line 220 covers 223 but not 222); impl:236 (`req.idSignal.completeDiscard` — comment on 235 is `// Signal id…` not `// Unsafe:`); impl:342 (`writerChannel.unsafe.offer` closing paren — comment at 334 is 8 lines up, out of window); impl:346 (`}(using AllowUnsafe…)` for `fiber.unsafe.onComplete` — no `// Unsafe:` comment nearby); impl:359 (`AllowUnsafe.embrace.danger` arg for `writerChannel.unsafe.offer` MethodNotFound path — no `// Unsafe:` comment); impl:386 (`AllowUnsafe.embrace.danger` for `abortSignal.unsafe.completeDiscard` on error response — comment at 381-382 is explanatory, not a `// Unsafe:` tag). **7 sites FAIL**. |
| 4 | nextId wiring | FAIL | `IdStrategy.mkNextId` returns `() => JsonRpcId < Sync` (a thunk). Exchange's `nextId` parameter is `nextId: => Id < Sync` (by-name). At impl:403, the call is `nextId = nextIdFn()`. This evaluates `nextIdFn()` ONCE at the call site and passes the resulting `JsonRpcId < Sync` effect value as the by-name argument. Because `nextIdFn()` produces a new Kyo effect each time it is called, and the by-name `nextId` re-evaluates its body for each request, the actual sequence is: Exchange captures the body `nextIdFn()`, and re-runs the thunk call each time it needs a new id. This is CORRECT: `nextIdFn` is a `() => JsonRpcId < Sync`, so `nextIdFn()` produces a fresh `Sync.Unsafe.defer(...)` effect object each invocation, and Exchange's by-name re-evaluation triggers `nextIdFn()` anew each time. Wiring is sound. **PASS**. |
| 5 | 18 test leaves present | FAIL | `JsonRpcEndpointTest.scala` does NOT EXIST. `ls kyo-jsonrpc/shared/src/test/scala/kyo/` shows only `JsonRpcCodecTest.scala`, `JsonRpcMethodTest.scala`, `JsonRpcTransportTest.scala`, `Test.scala`. Tests 33-50 (per IMPLEMENTATION.md lines 270-289) are entirely missing. **FAIL**. |

## New drift detected

**New public type: `HandlerCtx`** — appears in `kyo/HandlerCtx.scala` as a `final class` with public constructor (private[kyo]) and public fields `cancelled`, `requestId`, `extras`, and `progress` method. This type was listed in IMPLEMENTATION.md Phase 2 deliverables, so its presence is expected. Not drift.

**Skeleton stubs for future phases** — `CancellationPolicy.scala`, `MessageGate.scala`, `ProgressPolicy.scala` each contain only a comment `"Phase N skeleton"` and a `sealed trait` with no body. These are intentional Phase 4 skeletons per IMPLEMENTATION.md Phase 7/6/5 notes. Not drift, but these 3 sealed traits are public types in `kyo.*` with no ops exposed yet.

**`inFlight.getAndIncrement` at impl:69 called in Sync.Unsafe.defer context but with no `// Unsafe:` comment** — this is `AtomicInt.safe.getAndIncrement` which returns `Int < Sync`, so it is a safe call, not an Unsafe site. Not a violation.

**impl:222 `initPromise.completeUnitDiscard()` uses `AllowUnsafe` but `initPromise` is `Promise.Unsafe`** — the comment on line 220 reads `// Unsafe: AtomicRef for drainSignal` which pertains to line 223, not line 222. Line 222 (`initPromise.completeUnitDiscard()`) is a distinct `AllowUnsafe` use and needs its own `// Unsafe: seed the drain-signal promise before any fiber can race it` comment.

**`r.suppress.get` at impl:422** — uses safe `AtomicBoolean.get` (returns `Boolean < Sync`) chained with `.map`. This is in the async writer fiber body, which is correct. The unsafe-get fix from pulse 1 was NOT applied (the pulse 1 steer said "use `.unsafe.get()` inside `Sync.Unsafe.defer`"), but the current approach is actually better: the safe `.get.map` path is correct for an Async context. Call this resolved correctly, not as a steer violation. PASS on substance.

**No cross-cutting Phase 0-3 file modifications detected** — Phase 0-3 files (`JsonRpcCodec.scala`, `JsonRpcEnvelope.scala`, `JsonRpcId.scala`, `JsonRpcError.scala`, `JsonRpcMethod.scala`, `JsonRpcTransport.scala`) are present with no evidence of agent modification in this session.

## CRITICAL (steer immediately)

1. **JsonRpcEndpointTest.scala is entirely missing** — 18 test leaves required (Tests 33-50 per IMPLEMENTATION.md §Phase 4); Phase 4 cannot be called complete without them. Write the file now before any further impl changes.

2. **7 AllowUnsafe sites lack `// Unsafe:` comments** — add the tag immediately above each violating line: impl:168-172 (inside `Sync.Unsafe.defer` for `callerRegistry.forEach`), impl:222 (`initPromise.completeUnitDiscard()`), impl:235-236 (`req.idSignal.completeDiscard`), impl:340-342 (`writerChannel.unsafe.offer` in `onComplete`), impl:346 (`fiber.unsafe.onComplete` closing brace), impl:357-359 (`writerChannel.unsafe.offer` for MethodNotFound), impl:383-386 (`abortSignal.unsafe.completeDiscard` for error response).

## MINOR (queue for post-commit audit)

- `CancellationPolicy`, `MessageGate`, `ProgressPolicy` are sealed with no members, leaking empty public types into `kyo.*` until Phases 5/6/7. Consider whether they should carry `private[kyo]` until the phase that fills them lands.
- `JsonRpcEndpoint.callWithProgress` and `callPartialResults` are stubbed with `Abort.fail(JsonRpcError.internalError(...))` — acceptable for Phase 4, but the stub bodies must be replaced in Phase 6; note in the phase plan.

## Recommendation: STEER

Write `JsonRpcEndpointTest.scala` with all 18 required test leaves and add missing `// Unsafe:` comments at the 7 identified sites before declaring Phase 4 complete.

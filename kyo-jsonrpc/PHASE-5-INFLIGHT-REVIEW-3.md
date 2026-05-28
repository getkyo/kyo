# Phase 5 In-Flight Review (pulse 3)

## Test 60-61 (timeout)

**Tests**: "timeout with LSP policy sends $/cancelRequest and caller fails with -32800" and "timeout with cancellation=Absent sends no cancel notification; call fails locally"

**Root cause**: The timeout path IS implemented in `JsonRpcEndpointImpl.scala` lines 99-122.
When `config.requestTimeout != Duration.Infinity`, the call is wrapped with `Async.timeout` and
on `Result.Failure(_: Timeout)` the code calls `CancellationEngine.handleTimeout`, which enqueues
the cancel notification and completes `abortSignal`. Then it calls `abortSignal.get` to propagate
the abort error.

The problem: `handleTimeout` in `CancellationEngine.scala` lines 103-129 completes `abortSignal`
with `Sync.Unsafe.defer` (fire-and-forget) and then the caller immediately calls
`abortSignal.get` — but because `abortSignal` is already racing with `raceFirst` in the **outer**
computation (line 91: `Async.raceFirst(abortSignal.get ..., exchange(req) ...)`), the `raceFirst`
race has already been cancelled by `Async.timeout`. There is nobody left listening on
`abortSignal.get` at the outer race level; the timeout path then tries to call
`abortSignal.get` again on line 117 AFTER the `Async.timeout` timed out and returned, which means
the raceFirst is gone. The `abortSignal.get` in the timeout handler block (line 117) WILL park
indefinitely because `handleTimeout` fires the `abortSignal.completeDiscard` asynchronously from
`Sync.Unsafe.defer` — there is a race window where `abortSignal.get` on line 117 is called before
the `Sync.Unsafe.defer` inside `handleTimeout` executes the `completeDiscard`.

More precisely: `handleTimeout` is called with `Sync.Unsafe.defer { ... CancellationEngine.handleTimeout(...).andThen(abortSignal.get ...) }`. The outer `Sync.Unsafe.defer` defers the whole block, including the `abortSignal.get`. Inside `handleTimeout`, the `abortSignal` is completed via another nested `Sync.Unsafe.defer`. This creates a double-deferred sequencing issue: the completion and the `.get` are both inside the same sequential chain so the completion happens first — HOWEVER the real issue is that for `cancellation = Absent` (test 61), `handleTimeout` calls `Sync.Unsafe.defer { info.abortSignal.unsafe.completeDiscard(...) }`, then on line 119 the outer code does `abortSignal.get`. But `Sync.Unsafe.defer` is lazy; the inner `.completeDiscard` does not run until the returned effect is executed. If `handleTimeout` returns an effect (not `Unit`) and the caller chains `.andThen(abortSignal.get)`, the sequencing is correct — BUT the `Sync.Unsafe.defer` on line 107 wraps the ENTIRE `idSignal.poll() match` block. This turns a `Unit < (Async & ...)` into a double-wrapped computation that may not type-check correctly or may execute the wrong branches.

**Simpler diagnosis**: The tests use `Config(requestTimeout = 150.millis)`. Test 61 uses `cancellation = Absent`. The endpoint is `JsonRpcEndpoint.init(capA, Seq.empty, timeoutNoPolicy)`, and the server endpoint uses `JsonRpcEndpoint.init(tb, Seq(neverReturns))` (no config arg — default). Check: does `JsonRpcEndpoint.init` have a default config? If there is no overload accepting only `(transport, methods)`, these tests will fail to compile, not fail at runtime. This is the actual root cause for test 61 — line 473:
```scala
JsonRpcEndpoint.init(tb, Seq(neverReturns))   // no config arg
```
If the signature is `init(transport, methods, config)` with no default, this line would not compile. If it does compile via a default, the test may work or reveal the runtime issue above.

**Fix**: (a) Wire `Async.timeout` in Phase 5 — it is already wired (lines 99-122). The real fix needed is to ensure the `JsonRpcEndpoint.init` overload without a `config` arg compiles (add a default-config overload or default parameter), AND verify the `Sync.Unsafe.defer` double-nesting in the timeout Failure branch does not lose the sequencing of `.completeDiscard` before `.get`. Specifically, flatten the `Sync.Unsafe.defer` on line 107 so `handleTimeout(...)` and `abortSignal.get` are chained at the same effect level rather than nested inside an outer `Sync.Unsafe.defer`.

**Recommendation**: (a) Fix in Phase 5. Two concrete changes:
1. Add a two-arg overload `JsonRpcEndpoint.init(transport, methods)` using default config, so test 61 line 473 compiles.
2. In the `Result.Failure(_: Timeout)` branch (lines 104-120), remove the outer `Sync.Unsafe.defer` wrapper and let `handleTimeout` return its own `< Async` effect that is chained directly, so `.completeDiscard` is guaranteed to execute before `.get`.

## Test 62 (ContentModified)

**Test**: "handler aborts with ContentModified on cancel: wire response carries -32801 verbatim"

**Root cause**: `JsonRpcEndpointImpl.scala` line 435-448. When cancel wins the CAS and moves the entry to `InboundEntry.Cancelled`, the `onComplete` hook sees `case _: InboundEntry.Cancelled`. At this point `mustReply = true` for LSP (`expectReplyForCancelledRequest = true`). The `responseEnvelope` built at lines 398-419 uses the fiber's `result`:

- When the handler does `Abort.fail(JsonRpcError.ContentModified)`, the fiber result is `Result.Failure(JsonRpcError.ContentModified)`.
- Line 407: `case Result.Failure(e) => JsonRpcEnvelope.Response(id, Absent, Present(e), extras)` — this builds the envelope correctly with `-32801`.

So the envelope IS built with `-32801`. But the caller on side A reads the response via `decodeCallback` lines 470-484. On the caller side: when the response envelope arrives with `error = Present(e)`, line 475-480 completes `abortSignal` with `Result.succeed(e)`. The `raceFirst` arm on line 91 (`abortSignal.get.map(e => Abort.fail[JsonRpcError](e))`) fires and delivers `e` to the caller.

However: `cancel(id, Absent)` was called on endpoint A BEFORE the handler on B produced the ContentModified error. `cancel` on A (line 188) completes `abortSignal` with `policy.cancelledError.getOrElse(...)` = `JsonRpcError.RequestCancelled` (-32800) immediately via `Sync.Unsafe.defer`. The `abortSignal` is a `Fiber.Promise` — it can only be completed once. The first completion wins. So `cancel` fires `-32800` into the signal, and when the ContentModified response arrives later and tries to complete the same `abortSignal` again (line 477), it is a no-op because the promise is already done.

The caller therefore sees `-32800` (from the local cancel), not `-32801` (from the wire response).

**Root cause file:line**: `JsonRpcEndpointImpl.scala` line 195-201: `cancel()` immediately completes `abortSignal` with `-32800` before the handler on the remote side has had a chance to produce and return its ContentModified error.

**Fix**: In the `decodeCallback` response-error path (lines 473-481), the completion of `abortSignal` should use `tryComplete` semantics that prefer the wire error over the local cancel error. Since `Fiber.Promise` completes-once, the fix is to NOT complete `abortSignal` from `cancel()` immediately, but instead have the `raceFirst` wait for either the wire response error OR a separate local-cancel signal. Alternatively, use a two-stage approach: keep the local cancel signal separate from the wire-error signal and let the caller prefer the wire error when it arrives. The minimal one-line fix is not straightforward because it requires changing the signal architecture.

**Practical minimal fix**: In the LSP path (`expectReplyForCancelledRequest = true`), do NOT complete `abortSignal` in `cancel()`. Instead, let the wire response drive the `abortSignal`. The local `cancel()` only sends the `$/cancelRequest` notification; it does not abort the caller locally. The caller waits for the actual response (which will be an error). This matches LSP semantics: the server MUST reply, so the caller waits for that reply.

One-line change in `JsonRpcEndpointImpl.scala` cancel() method: wrap the `abortSignal.unsafe.completeDiscard(...)` call at line 197-200 in a condition — only complete it if `!policy.expectReplyForCancelledRequest`. For LSP (`expectReplyForCancelledRequest = true`), skip the local completion and let the decode path handle it.

```scala
// In cancel(), lines 182-202, change:
if policy.expectReplyForCancelledRequest then
    // LSP: server will reply; do not abort caller locally — the wire response drives abortSignal
    CancellationEngine.buildAndEnqueueOutboundCancel(id, reason, info, policy, writerChannel)
else
    val abortError = policy.cancelledError.getOrElse(JsonRpcError.cancelled(reason))
    CancellationEngine.buildAndEnqueueOutboundCancel(...).andThen {
        Sync.Unsafe.defer { info.abortSignal.unsafe.completeDiscard(Result.succeed(abortError)) }
    }
```

## Other findings

- Test 60 (`timeout with LSP policy`) relies on the same `requestTimeout` path. The handler on B observes `ctx.cancelled.get` (which parks until cancelled). The timeout fires and `handleTimeout` enqueues `$/cancelRequest` on A's transport AND completes `abortSignal`. This should work if the sequencing issue in the `Sync.Unsafe.defer` nesting (test 61 concern) is resolved. The LSP test additionally needs `$/cancelRequest` to appear in `capA.sent` — that requires `handleTimeout` to actually enqueue the notification before `callFib.get` returns. The current `handleTimeout` does call `buildAndEnqueueOutboundCancel` before completing `abortSignal`, so ordering is correct once the double-nesting is fixed.

- The `JsonRpcEndpoint.init(tb, Seq(neverReturns))` call in test 61 (no config arg) must compile. Check whether a two-arg overload or default config parameter exists; if not, add it.

## Recommendation: STEER

- **Test 61**: Add a two-arg `JsonRpcEndpoint.init(transport, methods)` overload with default config. Fix the `Sync.Unsafe.defer` double-nesting in the timeout `Failure` branch so `handleTimeout` effect and `abortSignal.get` chain at the same level.
- **Test 60**: Same double-nesting fix unblocks this as well.
- **Test 62**: In `cancel()`, do not complete `abortSignal` immediately for LSP (`expectReplyForCancelledRequest = true`); let the wire response drive it. This is a one-method change in `JsonRpcEndpointImpl.scala`.

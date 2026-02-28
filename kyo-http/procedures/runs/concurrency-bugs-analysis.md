# Concurrency Bugs Analysis — kyo-http2

## Bugs Found

4 failing tests across 3 platforms. All tests use `Choice.eval` for parameterized pool sizes, `Latch` for synchronized fiber start, and `Loop.repeat` for repetition.

### E1: `closeNow` does not interrupt in-flight requests (ALL platforms)

**Test**: Fire requests through a client, call `closeNow`, expect in-flight requests to complete or fail promptly.

**Root cause**: `HttpClient.close` (line 163) only calls `pool.closeAll()`, which drains idle connections from the ring buffer. There is no mechanism to track or interrupt in-flight requests. The `closeAll` method in `ConnectionPool` iterates the ring buffer and discards idle connections — it has no visibility into active `sendWith` calls.

**Fix direction**: Track in-flight fibers (e.g. via `IOPromise` or a concurrent set) so `close`/`closeNow` can interrupt them.

### A2: ~~Pool slot leak after concurrent fiber cancellation~~ — NOT A BUG (test timing issue)

**Root cause**: `Fiber.interrupt` is non-blocking. The test fired follow-up requests immediately after interrupt, before finalizers had run. Debug logging confirmed all 4 finalizers (unreserve + release for both fibers) fire correctly. **Fixed** by adding `untilTrue` to poll until the pool is usable.

### A3: Dirty connection returned to pool after partial streaming response consumption (JVM)

**Test**: Start a streaming response, partially consume it (`take(1).run`), cancel the fiber, then verify the pool slot is freed.

**Debug findings**: All finalizers fire correctly. The connection is returned to the pool. But the connection is mid-stream — the server is still sending chunks while the client abandoned the stream.

**Root cause**: This is a **different issue** from A2/A4. The `sendWith(f)` call completes **successfully** — `f` takes 1 chunk and returns normally. `Sync.ensure` sees `Absent` (success) and returns the connection to pool. But the Netty channel is still receiving streaming response chunks from the server, making the connection dirty.

The error-aware finalizer (Option A fix) cannot catch this case because there is no error. The fix requires wrapping the streaming response body so that if the stream is not fully consumed, the connection is discarded instead of returned to pool. This is a deeper architectural change in how `sendStreamingWith` integrates with `poolWith`.

### A4: Dirty connection returned to pool after streaming request cancellation (JVM + Native)

Same root cause as A3 but for streaming uploads. The connection has partially-written HTTP chunks and the server is expecting more data. Returning this connection to the pool poisons it for the next user.

## Architecture: poolWith and Sync.ensure

```
poolWith flow (HttpClient.scala:115-160):

Case 1 — Reuse idle connection:
  pool.poll(key) → Present(conn)
  Sync.ensure(pool.release(key, conn)) {
    backend.sendWith(conn, route, req)(f)
  }

Case 2 — New connection:
  pool.tryReserve(key) → true         // inFlight++
  Sync.ensure(pool.unreserve(key)) {  // outer: inFlight-- on any exit
    backend.connectWith(...) { conn =>
      Sync.ensure(pool.release(key, conn)) {  // inner: return conn to ring
        backend.sendWith(conn, route, req)(f)
      }
    }
  }

Case 3 — Pool exhausted:
  Abort.fail(ConnectionPoolExhausted)
```

The finalizer chain for a new connection is:
1. **Outer** `Sync.ensure` → `pool.unreserve(key)` (decrements `inFlight`)
2. **Inner** `Sync.ensure` → `pool.release(key, conn)` (returns connection to ring buffer)

## Key Insight: Finalizers DO Run

`Sync.ensure` delegates to `Safepoint.ensure`, which registers a finalizer on the `IOTask`. When the task completes (`!isPending()` in IOTask.scala:91-96), ALL finalizers are run:

```scala
// IOTask.scala lines 91-96
if !isPending() then
    ...
    if !finalizers.isEmpty then
        finalizers.run(pollError())
        finalizers = Finalizers.empty
```

This runs regardless of whether the task succeeded, failed, or was interrupted. The `Ensure` class in `Safepoint` uses `AtomicBoolean` to guarantee one-shot execution.

**Therefore: the issue is likely NOT that `Sync.ensure` doesn't fire.** The finalizers should execute even on fiber interrupt.

## Nested Sync.ensure Execution Order

### Finalizer storage: FIFO (not LIFO)

`Finalizers` uses `ArrayDeque` with `addLast` for registration and `poll` (remove from head) for execution. This means finalizers run in **FIFO order** — first registered, first executed.

### Registration order in poolWith (new connection path)

```
1. outer Sync.ensure(unreserve) registered → addFinalizer(unreserve)
2. connectWith suspends at Promise (async boundary — Netty connect callback)
3. ... Netty connects ...
4. f(conn) runs → inner Sync.ensure(release) registered → addFinalizer(release)
5. sendWith suspends at Promise (async boundary — Netty response callback)
```

### On normal completion (ensureLoop reaches non-Kyo value)

Inner `release` completes first (its `ensureLoop` resolves first):
1. `removeFinalizer(release)` from IOTask
2. `release(Absent)` → returns connection to ring
3. Then outer `unreserve` completes:
4. `removeFinalizer(unreserve)` from IOTask
5. `unreserve(Absent)` → decrements inFlight

**Order: release THEN unreserve — CORRECT.**

### On interrupt (IOTask.run finalizers)

`finalizers.run(ex)` polls ArrayDeque from head (FIFO):
1. `unreserve(ex)` → decrements inFlight
2. `release(ex)` → returns connection to ring

**Order: unreserve THEN release — REVERSED but runs synchronously, so final state is consistent.** No other fiber can interleave between steps 1 and 2.

### The real problem: release returns a dirty connection

When interrupted mid-request, the `release` finalizer returns the connection to the pool ring buffer. But this connection has:
- A partially-written request on the Netty channel
- A response handler still attached to the pipeline
- Potentially in-flight bytes from the server

The `isAlive` check (`NettyConnection.isAlive`) only checks `channel.isActive` — it does NOT check whether the connection is in a clean, reusable state. So the next `poll` retrieves this dirty connection, and `sendWith` either:
- Fails because the pipeline has stale handlers
- Gets corrupted response data from the previous request
- Hangs waiting for a response that never comes (wrong handler state)

### Why A2 fails (pool exhaustion after cancel)

The `release` finalizer calls `pool.release(key, conn)` which calls `HostPool.release(conn, discardConn)`. This puts the dirty connection back in the ring. But the connection's Netty channel may close asynchronously (the server sees a half-request and closes). By the time the next request tries to `poll`, the connection may be:
- Still in the ring but dead → `isAlive` returns false → evicted → no idle conn
- The `inFlight` was already decremented by `unreserve`
- `tryReserve` checks `inFlight + idleSize >= capacity` → `0 + 0 >= 1` → false → reserves OK

So actually pool exhaustion **shouldn't** happen from this path alone. This suggests the issue may be that **the inner `Sync.ensure(release)` never gets registered** because the interrupt arrives during the async suspension inside `connectWith` or between `connectWith` completing and the inner ensure being set up.

### Critical window: interrupt between connectWith and inner ensure

```
poolWith:
  Sync.ensure(unreserve) {          // outer registered
    connectWith(host, port, ssl) {  // suspends at Promise
      conn =>                       // ← interrupt can arrive HERE
        Sync.ensure(release(conn)) { // inner NOT YET registered
          sendWith(conn, ...)
        }
    }
  }
```

If the interrupt fires after `connectWith` resolves (connection created) but before the continuation registers the inner `Sync.ensure(release)`:
- Only `unreserve` finalizer runs → `inFlight--`
- Connection is leaked (not in ring, not tracked, not closed)
- The Netty channel remains open, consuming resources
- Pool accounting: `inFlight=0`, `idleSize=0` → `tryReserve` succeeds → new connection created
- This leaks connections but does NOT cause pool exhaustion

**For pool exhaustion**, the issue must be that `unreserve` itself doesn't run, OR `inFlight` gets incremented without a matching decrement.

### Alternative theory: interrupt before outer ensure is fully set up

`Safepoint.ensure` calls `ensuring(ensure)(ensureLoop(v))`. The `ensuring` function (line 152-161) does:
```scala
val interceptor = safepoint.interceptor
if !isNull(interceptor) then interceptor.addFinalizer(ensure)
try thunk
```

If `thunk` (the body) evaluates to a `KyoSuspend`, execution continues via the scheduler. The ensure is registered via `addFinalizer`. But `ensureLoop` wraps every continuation with `ensuring(ensure)(...)` — re-registering the same ensure on each resume. `Finalizers.add` deduplicates (line 36: `arr.contains(f)`).

The ensure IS registered before the thunk runs, so if the thunk suspends and the fiber is later interrupted, the finalizer should be in the IOTask's finalizer list.

### Most likely root cause for A2/A3/A4

The `Sync.ensure(release)` finalizer returns the connection to the pool. But the pool's `release` method in `HostPool` (line 144-160) can **discard** the connection if the ring is full:
```scala
if seq < currentTail then
    // Ring is full — slot still holds an unread write
    discardConn(conn)
```

If multiple interrupted fibers all try to release at once, the ring (capacity = pool size = 1 in tests) fills up, and excess connections are discarded via `discardConn`. But `discardConn` closes the connection — it doesn't affect `inFlight`.

Wait — `release` does NOT decrement `inFlight`. Only `unreserve` does that. And `unreserve` only applies to the new-connection path (Case 2). For the reuse path (Case 1, via `poll`), there is no `inFlight` tracking at all.

**`poll` does NOT increment `inFlight`.** A polled connection is simply removed from the ring (head advances). The `release` finalizer puts it back. No inFlight accounting involved.

So for pool exhaustion to occur, it must be the new-connection path where `tryReserve` increments `inFlight` but `unreserve` never runs.

## Recommended Next Steps

1. **Add debug logging** to `unreserve`, `release`, `tryReserve`, and `poll` to trace exact call sequence during interrupt
2. **Verify the ensure registration timing** — add a test that interrupts a fiber at various points in the connectWith → sendWith chain and checks whether finalizers fire
3. **Check if Promise interrupt propagation** causes the IOTask to be abandoned before finalizers run (e.g., if interrupt causes the promise to complete with an error that bypasses the normal IOTask completion path)
4. **Investigate IOPromise.interrupt** — when a fiber is interrupted via `IOPromise.interrupt`, does the IOTask always reach the `!isPending()` check that triggers finalizer execution?

## Cross-Platform Results

| Test | JVM | JS | Native | Status |
|------|-----|-----|--------|--------|
| E1 (closeNow) | FAIL | FAIL | FAIL | Real bug: no in-flight tracking |
| A2 (cancel leak) | PASS | PASS | PASS | Fixed: was test timing issue, added untilTrue |
| A3 (stream resp cancel) | FAIL | pass | TBD | Real bug: dirty connection returned to pool |
| A4 (stream req cancel) | FAIL | pass | TBD | Real bug: dirty connection returned to pool |

A3/A4 root cause: `Sync.ensure(pool.release(key, conn))` returns the connection unconditionally. After streaming interruption, the connection's Netty pipeline is in a broken state but `isAlive` (only checks `channel.isActive`) doesn't detect it. The connection gets polled and reused, causing the next request to fail/hang.

**Fix direction for A3/A4**: The `release` finalizer should check whether the connection is in a clean state (no pending request/response in progress) before returning it to the pool. If dirty, it should be discarded (`discardConn`) instead.

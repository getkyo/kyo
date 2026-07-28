# Analysis: LLMStreamTest CI hang (one leaf timed out, its sibling hung 169 minutes)

## Incident recap

Windows CI run, `kyo.LLMStreamTest` (code identical on origin/main today). Two structurally
identical leaves went STUCK at the same wall-clock second:

- "stream[Answer] streams complete objects one by one, never a partial object": reported
  `[TIMEOUT] (limit: 2m) *** FAILED ***` at 2 minutes. The timeout machinery worked.
- "stream[Answer] assembles one element split across many deltas": never timed out; the
  kyo-test heartbeat kept firing every 60s for 169 minutes until CI killed the job.

At hang time the JVM dump showed no thread in test-body code: scheduler workers parked, one
thread in the heartbeat's dump call, one in `NioIoDriver.pollOnce` (a healthy selector park),
`main` WAITING. The stuck leaf's fiber was suspended with nothing pending to wake it.

## Verdict

Root cause (best-supported, deterministically demonstrated): a kyo-core liveness defect.
`IOPromise`'s completion flush isolates callbacks with a `NonFatal`-only guard, so a single
fatal throwable (`OutOfMemoryError`, `StackOverflowError`, `LinkageError`) thrown by one waiter
callback aborts the flush walk and silently strands every earlier-registered co-waiter forever.
When the stranded waiter is `Async.timeout`'s interrupt callback on the Clock sleep promise, the
leaf's timeout is permanently disarmed with zero diagnostics: the fatal escapes into the clock
executor's unread `Future`, so nothing is logged and no thread dies, while the heartbeat (a
fresh timer task each period) keeps ticking. This is a second real-world instance of the
invariant hole documented in `analysis-iopromise-fatal-flush.md` (arm64 HarnessCompletionTest
incident); the two analyses corroborate each other and share the fix.

It is NOT an `Async.timeout` semantic defect, NOT a kyo-http connection-pool defect, and NOT a
test defect.

Confidence: HIGH that the mechanism exists and produces exactly the observed signature
(code-verified and reproduced); MEDIUM-HIGH that this specific CI hang was an instance of it.
It is the only explanation left standing after falsifying every alternative below, and it
explains every observed detail including the total silence; but the initiating fatal itself was
unlogged (that silence is the bug), so the incident cannot be pinned beyond this post mortem.

## What the code guarantees (verified on the current origin/main merge)

The timeout topology: the kyo-test runner arms `Async.timeout(120s)` around the leaf body
(kyo-test/runner/shared/src/main/scala/kyo/test/runner/TestRunner.scala:442), injects the 120s
default into every leaf (TestRunner.scala:150-154), and discharges the leaf `Scope` OUTSIDE the
timeout (TestRunner.scala:452). Inside the leaf, kyo-ai's SSE bridge `Completion.sseFragments`
(kyo-ai/shared/src/main/scala/kyo/ai/completion/Completion.scala:249-374) runs the request in a
detached producer fiber bounded by `Async.timeoutWithError(config.timeout)` and feeds a bounded
Channel the consumer drains; `config.timeout` defaults to 2 minutes
(kyo-ai/shared/src/main/scala/kyo/ai/Config.scala:68), the same value as the leaf default
(kyo-test/api/shared/src/main/scala/kyo/test/internal/TestBase.scala:495).

Interrupt delivery is a CAS, not a request: `Async._timeout`
(kyo-core/shared/src/main/scala/kyo/Async.scala:186-204) registers
`sleepFiber.onComplete(_ => discard(task.unsafe.interrupt(error)))`, and `IOPromise.interrupt`
(kyo-core/shared/src/main/scala/kyo/scheduler/IOPromise.scala:111-122) CAS-completes the target
promise immediately; `task.get` then completes. Refusal exists only for masked promises
(`preInterrupt() = false`, IOPromise.scala:101-106) or already-completed ones (in which case
`task.get` is already done). The discarded Boolean cannot hide a lost interrupt at this promise.

## Hypotheses tested and falsified

- "The fiber was in `Async.mask` or an uninterruptible region, so the interrupt was ignored and
  `task.get` never completed." Falsified: no `Async.mask` / `.mask()` exists anywhere on the
  kyo-ai, kyo-http, or kyo-test paths (grepped all three), and interrupting an unmasked IOTask
  completes its promise regardless of what the fiber is executing.
- "The shared per-host connection pool wedged both leaves." Falsified: pool acquire never parks.
  `poolWithImpl` (kyo-http/shared/src/main/scala/kyo/internal/client/HttpClientBackend.scala:1138-1175)
  polls an idle connection, reserves-and-connects, or fails fast with
  `HttpPoolExhaustedException`. Pool keys are host:port; each leaf binds its own ephemeral port.
- "Leaf teardown parked forever after the timeout fired." `Scope.run`'s `finalizer.await`
  (kyo-core/shared/src/main/scala/kyo/Scope.scala:129-142) is indeed outside the leaf timeout
  and unbounded (a latent hazard worth noting), but falsified for THIS leaf's finalizer set:
  the leaf scope holds exactly a producer-fiber interrupt (a CAS), the carrier `Channel.close`
  (sync), and `HttpServer.closeNow` (fully synchronous,
  kyo-http/shared/src/main/scala/kyo/HttpServer.scala:282-304). Confirmed empirically: 31 probe
  leaves driven into the leaf timeout against a wedged SSE exchange all scored TimedOut; the
  suite ended in 4.1s.
- Every macro-level interleaving of the three racing 2-minute timers (leaf, kyo-ai producer,
  kyo-http request) resolves to either a TimedOut leaf or a typed `AICompletionTimeoutException`;
  walked case by case, no ordering deadlocks at this altitude. The eternal hang therefore
  requires a lost wakeup below the effect system: a completed promise whose registered waiter
  never ran.

## The mechanism in detail

`IOPromise.eval` (IOPromise.scala:414-422) catches `NonFatal` only; it isolates each callback in
`flush`/`flushInterrupt` (via `Pending.run`/`Pending.interrupt`). A fatal in any callback aborts
the walk mid-chain; the promise is already completed so nothing ever flushes again, and no layer
logs the escape (`IOTask.eval`'s completeDiscard on an already-completed promise is a no-op; the
scheduler worker catches NonFatal only).

The LLMStream twist: `Clock.Unsafe.sleep` schedules the sleep IOPromise itself as a `Callable`
on the 2-thread clock executor (kyo-core/shared/src/main/scala/kyo/Clock.scala:728-743). On
expiry, `call()` completes the promise and the flush runs `Async.timeout`'s entire interrupt
cascade inline on that thread. A fatal escaping there is buried by `ScheduledThreadPoolExecutor`
in the task's unread `Future`: no log, no uncaught-handler, no dead thread. The one-shot leaf
timer is permanently disarmed while the heartbeat keeps ticking.

Why leaf 1 timed out and leaf 2 did not: both wedged in minute one and both armed 120s timers at
start. Leaf 1's expiry cascade ran to completion (TimedOut reported, non-parking teardown
finished). Leaf 2's one-shot expiry chain lost a waiter to a fatal mid-flush (on the clock
thread, or in the downstream join flush), so the interrupt or the resume it should have caused
never happened; nothing else can ever complete a wedged SSE consumer. Heartbeats forever, no
thread in test code, fiber suspended with no waker: the dump exactly.

## Deterministic reproductions (run locally on macOS; scratch suite deleted per brief)

Probe suite `LLMStreamHangProbeTest` (kyo-ai/shared/src/test), four arms, against an in-process
SSE endpoint that sends headers then a body stream that never emits
(`Stream[HttpSseEvent[String], Async](Async.never[Unit])` behind
`HttpRoute.postRaw(...).response(_.bodySseText)`):

1. Producer deadline works: `config.timeout = 1s` yields the typed
   `AICompletionTimeoutException` in 1.2s. PASS.
2. Leaf timeout and teardown work: leaf `.timeout(2s)`, `config.timeout = 5min` yields a clean
   TimedOut; 30 equal-deadline race leaves all resolved. The CI hang is NOT reproducible at the
   effect-system level; teardown does not park.
3. The invariant break (unit shape): on a raw `kyo.scheduler.IOPromise`, register waiter A, then
   waiter B that throws `NoClassDefFoundError`; complete the promise. Result: the fatal escaped
   (NonFatal-only guard) and A never ran (assertion diagram `earlierRan == false`). FAILS today,
   deterministically; becomes the regression test for the fix (belongs in IOPromiseTest).
4. The leaf-2 signature (timeout disarm): build a `Clock` over a custom
   `ScheduledThreadPoolExecutor` whose `schedule` override captures the 100ms sleep promise
   `Async.timeout` creates (the scheduled `Callable` IS the IOPromise); inside the timed body,
   sleep 30ms then register a fatal-throwing waiter on the captured promise (LIFO flush walks it
   before the timeout's interrupt callback); body then `Async.never`, all wrapped in an outer 3s
   `Async.timeout` on the real clock as a guard. Result: the inner 100ms timeout NEVER fired;
   only the outer 3s guard ended the wait (leaf ran exactly 3.0s). Without the guard this leaf
   hangs forever with heartbeats: the CI behavior on demand, no Windows timing involved. The
   outcome discriminates cleanly: had the 30ms sleep or the capture failed, the inner timeout
   would have fired normally and the leaf would have failed on the other match arm.

A captured stack of the actually-hung CI leaf was not obtainable: the hang did not reproduce at
the effect-system level locally (arm 2 above), which is itself evidence that the CI event
required the below-effect-system waiter loss.

## The primal stall (what wedged both exchanges in minute one)

Least provable part; the CI log cannot discriminate between two candidate classes, both
consistent with the dump's healthy `pollOnce` park:

1. The same fatal-in-flush drop on a SHARED completion chain: the NIO driver completes read
   promises inline while dispatching ready keys, and one fatal in one walk strands every
   earlier co-waiter on that chain, i.e. several leaves at the same instant from one event.
   This uniquely explains the same-second pairing with a single cause and needs no new bug.
2. A residual of the transport's known lost-wakeup family: `NioIoDriver` carries multiple prior
   fixes for coalesced-wakeup stranding of one-shot read/connect arms (the `forceReadArmWakeup`
   comment at kyo-net/jvm/src/main/scala/kyo/net/internal/NioIoDriver.scala:308-316,
   `armConnectInterest` at :656-668, `reassertPendingInterest` backstop at :1144-1147); a
   Windows-selector variant slipping past the backstop would strand an exchange exactly as
   dumped.

## Fix locations

1. kyo-core/shared/src/main/scala/kyo/scheduler/IOPromise.scala, `eval` (:414-422), used by
   `flush`/`flushInterrupt`: total callback isolation (catch `Throwable`), log fatals loudly,
   keep walking the chain. Policy (rethrow the first fatal after the walk vs. logged panic) is a
   review decision; either preserves co-waiter liveness. Same fix as
   `analysis-iopromise-fatal-flush.md` specifies.
2. kyo-core/shared/src/main/scala/kyo/Clock.scala:740, `call()`: guard and log any throwable
   before it reaches the executor's unread-Future swallow; this is the quietest failure point in
   the system.
3. kyo-scheduler `Worker.runTask`: log `Throwable` at the thread boundary (also in the sibling
   analysis).
4. Secondary hardening (separate, optional): kyo-test's per-leaf deadline lives inside the
   leaf's own effect graph and is one lost flush away from unbounded; an out-of-graph watchdog
   would cap the damage. And kyo-ai test configs could set `config.timeout` below the leaf
   timeout so a wedged stream fails typed (`AICompletionTimeoutException`) instead of burning
   the whole leaf budget into an opaque `[TIMEOUT]` (today's equal 2m/2m defaults guarantee the
   leaf timer wins by the arming gap, making the producer deadline unreachable in tests).

Regression tests for the fix: reproduction arms 3 and 4 above (arm 3 in IOPromiseTest, arm 4 in
an AsyncTest-adjacent suite; arm 4's assertion flips to "inner timeout fires despite the
poisoned flush" once fixed).

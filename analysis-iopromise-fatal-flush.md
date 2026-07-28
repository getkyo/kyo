# Analysis: fatal throwables break IOPromise waiter flush (candidate root cause of the arm64 leaf hang)

## The invariant that breaks

When an `IOPromise` completes, every waiter registered at completion time must be notified.
Waiters are usually the resume callbacks of suspended fibers: a dropped notification is a fiber
that never runs again, with no thread attached, no log line, and no later event that can rescue
it (the promise is already completed, so nothing will flush again).

## The code path

`kyo-core/shared/src/main/scala/kyo/scheduler/IOPromise.scala`:

1. Completion publishes the result, then flushes the waiter chain (line 209):

```scala
final private def complete(p: Pending[E, A], v: Result[E, A]): Boolean =
    compareAndSet(p, v) && {
        onComplete()
        p.flush(v)
        true
    }
```

2. The waiter chain is an immutable LIFO stack of `Pending` nodes; flush walks it newest to
   oldest (line 393):

```scala
final def flush(v: Result[E, A]): Unit =
    @tailrec def flushLoop(p: Pending[E, A]): Unit =
        p match
            case _ if (p eq Pending.Empty) => ()
            case p                         => flushLoop(p.run(v))
    flushLoop(this)
```

3. Each `onComplete` node runs its callback through `eval` (line 317):

```scala
def run(v: Result[E, A]) =
    eval(discard(f(v.asInstanceOf[Result[E, A]])))
    self
```

4. `eval` guards with `NonFatal` only (end of file):

```scala
private inline def eval[A](inline f: => Unit): Unit =
    try f
    catch
        case ex if NonFatal(ex) =>
            Log.live.unsafe.error("uncaught exception", ex)
```

A fatal throwable (`StackOverflowError`, `OutOfMemoryError`, `LinkageError`) thrown by any
callback therefore escapes `eval`, aborts `flushLoop` mid-walk, and unwinds out of `complete`.
Every waiter registered EARLIER than the throwing one is silently dropped. The thrower's own
fiber is also typically lost (its resume died mid-flight). The promise state is already
completed, so late-arriving waiters still work, which makes the damage invisible: only the
flush-time cohort is gone.

## The compounding guards above it

The escaping fatal then crosses two more NonFatal-shaped boundaries, so nothing anywhere logs it:

- `IOTask.eval` (IOTask.scala:119-123) catches all, completes the task promise with `Panic`,
  and RE-THROWS fatals. When the fatal came from a flush, the promise is already completed, so
  the `completeDiscard` is a no-op and the leaf that owned the completing task has already
  reported success. The error has no owner.
- `Worker.runTask` (kyo-scheduler Worker.scala:369-370) catches `NonFatal` only, so the fatal
  reaches the worker thread itself. Whatever the thread-level outcome is (death and dynamic
  replacement, or a silent uncaught-handler), it is not logged by kyo.

Net effect: one fatal in one callback produces permanently hung fibers, zero diagnostics, and
no failed test.

## Fit to the incident (run 29617762796, arm64 JVM, 3h timeout)

Observed: three pure leaves of `HarnessCompletionTest` stuck simultaneously for 122 minutes
(heartbeats ticking throughout), 12 sibling leaves passed, no leaf failed, no error or fatal
anywhere in the log, no thread holding test frames, every later suite would have run fine had
sbt not been waiting on the suite future.

The dropped-flush mechanism reproduces this signature exactly IF the three leaves' resume
callbacks co-resided on one waiter chain at the moment a fatal fired: one throw strands the
thrower plus every earlier-registered co-waiter, three victims from one event.

- **Verified**: the guard hole (code above), the silence of all three layers, the purity of the
  three leaf bodies (they cannot self-hang), the leaves' lack of a timeout (no bound ever fires),
  and heartbeat independence (separate Clock fibers, unaffected by design).
- **Unverified link 1 (topology)**: which shared chain held all three. The leading candidate is
  the suite's leaf-parallelism gate (a slot release flushing the waiting leaves' resumes); this
  requires confirming the runner's gate mechanism flushes through `IOPromise` and that the leaf
  heartbeat starts before gate acquisition (the STUCK-at-1m readings then make sense). The
  alternative, three independent drops, would need three separate fatals and is implausible.
- **Unverified link 2 (the fatal itself)**: nothing was logged because every path is silent for
  fatals, so the initiating throwable is unidentified. `StackOverflowError` during an inline
  resume is the leading candidate on a deep completer stack; `OutOfMemoryError` under the fork's
  GC pressure is second.
- **Withdrawn earlier claims**: the scheduler-queue-stranding and dead-worker theories. Worker
  absence/presence in the dump is explained by the scheduler's ordinary dynamic sizing, and no
  scheduler defect is required for the observed signature.

## Why this is a real bug regardless of the incident

Even if the incident's topology turns out different, the invariant violation stands on its own:
callback isolation in a completion flush must be total. A framework promise that can lose
co-waiters on a fatal is a liveness landmine for every user of `Fiber#get`, `Async` joins,
gates, and interrupts. It is deterministic to demonstrate and cheap to fix.

## Reproduction plan (deterministic, no probability)

1. `IOPromiseTest`: register waiter A (callback throws a synthetic fatal, e.g. a
   `VirtualMachineError` subclass), register waiter B (records invocation), complete the
   promise, assert B ran. Fails today: B is dropped.
2. Same shape for `flushInterrupt` (the interrupt path shares the pattern).
3. After the fix, an integration-shaped guard: a suite of parallel leaves where one leaf's
   resume throws a fatal, asserting all other leaves still complete and the fatal is logged.

## Fix design

1. `IOPromise` flush paths: isolate every callback with a full `Throwable` guard; log fatals
   loudly; continue flushing the remaining chain. Policy question for review: rethrow the first
   fatal AFTER the chain is fully flushed, or convert to a logged panic; either preserves
   co-waiter liveness.
2. `Worker.runTask` (and any thread-boundary catch): catch `Throwable` or install loud logging
   at the thread boundary so a fatal can never be silent.
3. Diagnosability: the "no dumpers registered" gap seen in the incident's `Diagnostics.dumpAll()`
   output means forked test JVMs register no fiber dumpers; registering the kernel's dumper in
   the test runner would have named the stuck fibers' suspension points immediately.
4. Test-runner hardening (separate, already noted in the CI analysis): leaves without explicit
   timeouts should inherit a finite default in CI, and the stale-output watchdog should cover
   JVM phases so a future liveness bug costs minutes, not 3 runner-hours.

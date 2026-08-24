# `Finalizer ... is closed` under kernel2: investigation record

Status: **unresolved**. This is a record of what is measured, what is ruled out, and what is
still open, written so a reviewer can challenge the reasoning without rerunning everything.

Worktree: `.claude/worktrees/effervescent-painting-backus`, branch
`worktree-effervescent-painting-backus`, HEAD `0647b6bdaf`.

---

## 1. The failure

45 tests in `kyo-core` fail, and every one fails with the same single panic:

```
kyo.Closed:
    // ChannelTest.scala:614:51 kyo.ChannelTest $anonfun
613 │ size    <- Choice.eval(0, 1, 2, 10, 100)
614 │ channel <- Channel.init[Int](size)📍
    ⚠️ KyoException
    Finalizer created at <internal>:0:0 is closed.
    This finalizer is already closed. This may happen if a background fiber escapes
    the scope of a 'Scope.run' call.
```

Origin, `kyo-core/shared/src/main/scala/kyo/Scope.scala:165`:

```scala
def ensure(v: Maybe[Error[Any]] => Any < (Async & Abort[Throwable]))(using Frame): Unit < Sync =
    Sync.Unsafe.defer {
        if !queue.offer(v).contains(true) then
            Abort.panic(new Closed("Finalizer", frame, "..."))
```

`Scope` is a `ContextEffect[Scope.Finalizer]`. `Scope.run` builds an `Awaitable` backed by an
unbounded `Queue`, binds it, and closes the queue when its extent ends. The panic is a
`Scope.ensure` reaching an `Awaitable` whose queue is already closed. Any `Scope`-managed
init triggers it: `Channel.init`, `Meter.initSemaphore`, `Fiber.init`.

Counts in a full `kyo-coreJVM/test`: 45 failing tests, 132 occurrences of the message,
**0** occurrences of `Finalizer$Spent` and **0** of `Finalizer$Abandoned`.

Distribution: ChannelTest 22, MeterTest 10, QueueTest 8, HubTest 2, AsyncTest 1, LogTest 1,
ScopeTest 1.

---

## 2. Reproduction

Each suite is **green alone**. Any **two** suites reproduce it. Measured:

| command | `Closed` |
|---|---|
| `kyo.MeterTest` | 0 (42 passed, 5.2s) |
| `kyo.ChannelTest` | 0 |
| `kyo.QueueTest` | 0 |
| `kyo.HubTest` | 0 |
| `kyo.MeterTest kyo.ChannelTest` | 66 |
| `kyo.MeterTest kyo.QueueTest` | 24 |
| `kyo.MeterTest kyo.HubTest` | 6 |
| `kyo.ChannelTest kyo.QueueTest` | 24 |
| `kyo.ChannelTest kyo.HubTest` | 6 |
| `kyo.QueueTest kyo.HubTest` | 6 |
| all four | 96 |
| full `kyo-coreJVM/test` | 132 |

Smallest repro (~1s):

```
sbt --client 'kyo-coreJVM/testOnly kyo.MeterTest kyo.HubTest'
```

All 6 pairs of the four fail; all 4 singles are clean. No suite poisons a specific other one.

---

## 3. What is ruled out, with evidence

### 3.1 Stack pooling / stack leaking between threads — RULED OUT

`Stack.borrow`/`release` replaced with `new Stack` / no-op, so no stack is ever reused:

```
Closed count (pooling DISABLED): 6    [with pooling: 6]
```

Identical count, identical two failures.

### 3.2 IOTask scheduling races — NOT SUPPORTED

Two genuine scheduling bugs were found and fixed during this session (§5). Both are confirmed
fixes by their own measurements. The `Closed` count did not move: **132 before, 132 after**.

This is evidence, not proof: the fixes addressed two specific races, not all possible ones.

### 3.3 Choice — NOT REQUIRED

33 of 45 failing sites sit inside a `Choice.eval`; 12 do not, including
`AsyncTest.scala:1690` (`basic nesting and cancellation`) and `LogTest.scala:363`
(`child fiber inherits the ambient Log under let`, a bare `Fiber.init`).

### 3.4 Scheduler saturation — NOT SUPPORTED

Direction is backwards. The clean single-suite MeterTest run drives 20,000 acquisitions
across 20 fibers in 5.2s (genuine scheduler load) with **0** `Closed`. The failing two-suite
runs collapse in ~56-124ms having done far less work.

### 3.5 The `<internal>:0:0` frame — EXPLAINED, BENIGN

`createdAt` is the `Frame` passed to `Awaitable.Unsafe.init`, which comes from `Scope.run`'s
`using frame`. `kyo-test/runner/.../TestRunner.scala:263-264`:

```scala
// Frame.internal here is the single sanctioned CLI-edge boundary Frame; the Cli entry point has no caller Frame.
runToFuture(suite, config)(using Frame.internal)
```

The runner enters at the CLI edge with no caller frame and it propagates into `Scope.run`.
**Consequence: the frame cannot identify which scope closed.** It is a dead end for this hunt.

A separate, real frame bug was found and fixed while chasing this (§5.3), but it does not
change this message.

---

## 4. The leading hypothesis (UNCONFIRMED, and the weakest link in this document)

`kyo-test`'s `LeafPool` is process-global: one instance, `globalK` long-lived worker fibers,
every suite's leaves routed through it (`LeafPool.scala:129-132`). `TestRunner.scala:250`
wraps each *suite* in its own `Scope.run`; `:452` wraps each *leaf*.

Proposed mechanism: a `Scope` binding rides a pool worker fiber past the extent that installed
it, so a later leaf reads a scope that has already closed.

Why it would be invisible with one suite: every leaf of a suite sits under the same suite-level
`Scope.run`, so a binding leaking between leaves of one suite yields the same `Awaitable`. With
two suites it yields the *other* suite's, which closes when that suite finishes.

This predicts: green alone, fails for any pair, immediate rather than load-dependent, count
scaling with the number of sharing suites. All four match.

**It is not confirmed and no mechanism inside the binding machinery has been identified.**
Candidate sites, neither examined in depth:

- `Stack.restore` / `resolveFrom` (`Stack.scala:392-413`): restore writes parked entries above
  what the resuming eval already pushed, then re-resolves bindings against what is now below.
- `Isolate.Contextual` (`Isolate.scala:510-545`): `capture`/`isolate`/`restore` for a binding
  crossing a fork. Note `isolate` builds `new Park[...](body, state._1, state._2, Span.empty)`
  with an explicitly empty finalizer span.

### The decisive experiment, not yet run

Log `identityHashCode` of the `Awaitable` at install (`ContextEffect.handle` in `Scope.run`)
and at read (`ContextEffect.suspendWith` in `Scope.ensure`), then run
`testOnly kyo.LogTest kyo.HubTest`.

- A read returning an identity installed by a *different* `Scope.run` ⇒ binding leak; look at
  the two sites above.
- Every read matching its own leaf's install ⇒ no leak; the leaf's own scope closed early, a
  lifecycle bug in `Scope.run`/`Awaitable.close`.

This single datum splits the remaining space and has not been collected.

---

## 5. Fixes landed during the investigation (none resolve §1)

### 5.1 `5a8d737196` — IOTask armed a parked fiber's wakeup before publishing the remainder

Not the `Closed` bug. Found while chasing a different failure, `Finalizer$Spent` under
semaphore contention (~1 acquisition in 2000).

The boundary armed `promise.onComplete { schedule(this) }` inside the join clause, before the
eval had unwound into the park holding the remainder; `curr` is only stored once `run` regains
control. A completion landing in that window made the task runnable while `curr` still held the
*previous* slice, so a second worker restored the same `Park` — same regions, same outstanding
releases. One completed and ran them, the other re-entered a spent scope.

Confirmed by instrumenting `Park` with a CAS recording first resumption: exactly one
double-restore per failure, and its finalizer was the one that panicked. Zero after the fix.

Fix: the clause records the promise and stops; `run` stores the remainder, clears the status,
then arms the wakeup. Regression test added to MeterTest (20 callers, 2 permits, 1000
iterations); failed at ~2105 of 20000 before, passes after.

Also cleared `AsyncTest › interrupt of a running fiber is never lost` and
`ScopeTest › concurrent acquireRelease all cleaned up` in the full run (94 → 90 failure lines).

**The old CPS scheduler had no such window** because its clause held the whole remainder as a
value and the waker built `curr` itself before scheduling. The Arrow kernel keeps the remainder
in the eval's stack, so only `run` can publish it.

### 5.2 `14ff176c70` — IOTask status as a claimable state machine

`status` named ownership but was written with plain stores, so ownership could not be
arbitrated. A fiber interrupted while parked had no slice to stop and nothing to reschedule it,
so releasing its park depended on the interrupt cascade happening to complete the awaited
promise.

Four states, `Idle` / `Thread` / `IOPromise` / `Done`, one rule: **`Idle` is the only state
another thread may take a task out of; every other transition belongs to the owner.** Two
contended claims, both CAS: `run` claims to resume, `onInterrupted` makes the task runnable so
`run` claims to abandon. CAS via a platform handle over the field (`IOTaskPlatformSpecific`,
mirroring `IOPromise.StateHandle`), so no atomic wrapper is allocated per fiber.

Also fixed the same publish-ordering bug as §5.1 in the surviving-slice arm.

Effect on the suite: neutral, 90 → 90 failure lines. MeterTest 42/0.

Two implementation traps hit on the way, both worth knowing:
- The union erases to its least upper bound, not `Object`. `IOPromise.State` erases to `Object`
  only because it includes an opaque type over a type parameter. Field declared `AnyRef`.
- A field a **lambda** touches is promoted and renamed (`kyo$scheduler$IOTask$$status`), so
  `findVarHandle("status")` fails. Every access now lives in a method of the class.

An earlier revision had `onInterrupted` claim and abandon **inline**; when the claim failed
against a running slice the interrupt produced no wakeup at all, hanging
`MeterTest › a permit released while an interrupted waiter's teardown is pending`. Thread dump
showed every worker idle in `getTask` — a lost wakeup, not a deadlock. Reverted to scheduling.

### 5.3 `0647b6bdaf` — frames dropped on region re-entry, and the fatal path wedging a task

`Handler.resuspend` stamped `Frame.internal` on the suspension it rebuilt for a bailing answer
loop, and the eval's four clause-resumption sites did the same. A private
`given Frame = Frame.internal` in `object Eval` made every such loss silent. Removed; frames
are now threaded, and a missing one is a compile error.

**This does not change the §1 message** (whose `<internal>:0:0` comes from TestRunner, §3.5).
It is a real but separate defect.

Also, in `IOTask.run`, rethrowing a fatal exits without reaching the arms that clear `status`,
leaving the task `Running` forever. Harmless before §5.2, a permanent wedge after: the claim
would fail for good, and with it every later schedule and the interrupt path's claim.

---

## 6. Open items

1. **§1 is unresolved.** The decisive experiment in §4 has not been run.
2. `AsyncTest › memoize › handles interruption during initialization` timed out once in a full
   run at `14ff176c70`. AsyncTest passes 129/0 alone. Never determined whether this is a
   regression from §5.2 or suite contamination. **One run, no repeat.**
3. The per-slice CAS added in §5.2 is **unmeasured**. It sits on the scheduler's hottest path.
4. §5.2 and §5.3 are **JVM-only verified**. `IOTaskPlatformSpecific` was written for Native and
   JS/Wasm but neither has been compiled.
5. `Scope`'s release row is `Async & Abort[Throwable]` while `Sync.ensure`/
   `Sync.acquireReleaseWith` (already on `Effect.bracket`) use `Sync & Abort[Throwable]`,
   because a kernel release runs through `Eval` and cannot suspend. Relevant to any migration
   of `Scope` onto the kernel bracket.

## 7. What a reviewer should attack

- §4 is speculation dressed as a hypothesis. Is there a simpler explanation consistent with
  the pair table in §2?
- §3.2 infers "not IOTask" from an unmoved count. Is that inference sound, given the fixes
  targeted specific races?
- Is there a reading of the pair table that points at something other than process-global state?
- §5.2's state machine: are there transitions or interleavings not covered by the audit
  (fatal path, preempt/interrupt overlap, `Task.Preempted` re-enqueue racing a claim)?

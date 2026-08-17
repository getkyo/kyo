# IOTask on kernel2: current state, preemption analysis, and proposed integration

Status: analysis and design. No code was compiled, run, or benchmarked for this document.
Where something is undetermined it is marked as an explicit open question in section 7
rather than resolved by assertion.

Two code bases are cited, and every citation says which.

- **Baseline**, the shipped implementation this integration must not regress:
  `origin/main` at `2e9bb02d40`. Cited as `origin/main:<path>:<line>`, readable with
  `git show origin/main:<path>`.
- **Worktree**, the branch carrying kernel2 and the prior porting attempt, at `b9356dee49`.
  Cited as a bare `<path>:<line>`.

Relevant differences between the two, established by diff: `kyo-scheduler` is byte-identical
(`git diff origin/main HEAD -- kyo-scheduler` is empty), so every scheduler citation holds
for both. The old kernel's `kyo/kernel` main sources differ only in `Pending.scala`, so the
old `Safepoint` and `ArrowEffect` citations hold for both. `Finalizers.scala` is identical.
`IOTask.scala` and `IOPromise.scala` are the two files the port rewrote.

Companion documents already in the tree: `kernel2-preemption-design.md` (the design that
produced the now-deleted interim Safepoint), `kernel2-preemption-analysis.md`,
`kernel2-finalizer-design.md`, and `kyo-kernel2/CONTRIBUTING.md` (the doctrine this design
is judged against).

---

## 1. Current state

### 1.1 What the prior attempt did

Two commits ported kyo-core's scheduler integration onto the kernel that existed on
2026-08-09.

`77cedd3689` ("[kyo-core] port the scheduler integration and Sync to the new kernel")
rewrote `IOTask` on a new drive currency and moved `Sync` onto kernel brackets. Its own
message states the shape: "IOTask is rewritten on the drive currency:
handlePartial(Async.Join) with the re-entry park protocol ..., beginSlice deadlines with a
completion-time preempt push through the published running safepoint, boundary completion
of unhandled Aborts via dispatchFirst, and finalizeBracket on every discarded remainder."
It closes with "Compile-green on JVM for kernel2, prelude, and core, main and test; suites
not yet run."

`23cb1d551d` ("[kyo-core] fix the park protocol race and port gaps surfaced by the suite")
fixed a real race the suite found: the park registered its wakeup inside the slice before
`run` published the remainder into `curr`, so a completion landing in that window
rescheduled the task over the stale slice-start snapshot. The fix added a third mutable
field, `pendingJoin`, so the wakeup registration moves to `run` after the store
(`IOTask.scala:28-33`, `IOTask.scala:145-157`). It also restored the null-poll arm
(`IOTask.scala:81-85`). Its own message ends "Unverified: full kyo-core suite rerun in
flight; interrupt-path stucks and the semaphore leak from the previous run not yet
re-diagnosed against these fixes."

### 1.2 The decisive fact: today's IOTask is stranded, not merely suboptimal

`23cb1d551d` is 94 commits behind HEAD. In that window the kernel deleted every API the
port was written against. The file in the worktree cannot compile against `kyo-kernel2` at
HEAD. This is not a style critique; it is the state of the code.

| Reference in `IOTask.scala` | Line | Exists in kernel2 at HEAD? |
|---|---|---|
| `Context`, `Context.empty` | 16 | No. `kyo-kernel2/shared/src/main/scala/kyo/kernel/internal/` contains only `CanLift`, `Eval`, `Handler`, `Handlers`, `KyoInternal`, `LiftMacro`, `Safepoint`. `Context.scala` existed at `23cb1d551d` and is gone. |
| `Safepoint` as an instance with `preempt()` | 21, 44 | No. `class Safepoint` at HEAD is an empty declaration (`Safepoint.scala:9`); all state and operations are statics on the companion. |
| `Safepoint.beginSlice(deadline)` | 118 | No. Grep across the tree finds exactly one occurrence, this one. |
| `sp.endSlice()` | 124 | No. |
| `ArrowEffect.handlePartial(tag, curr, context)(...)` | 74 | Wrong arity. HEAD's signature is `handlePartial(_tag, v)(f)(using _frame)` (`ArrowEffect.scala:218-220`); there is no context parameter. |
| `ArrowEffect.dispatchFirst` | 170, 195 | No. Not present anywhere in kernel2. |
| `remainder.finalizeBracket(...)` | 185 | No. `Effect` at HEAD carries only `catching` and `defer` (`Effect.scala:11-83`). |
| `ContextEffect[IOPromise[?, ?]]` with `Context.set`/`get`/`contains` | 216-219, 237 | Partially. `ContextEffect` exists (`ContextEffect.scala:15`) but is now an `ArrowEffect[Const[Unit], Const[A]]` provisioned by a handler layer; there is no `Context` map to `set` or read. |

The damage is not confined to `IOTask`. `Sync.ensure` calls `Effect.bracket`
(`Sync.scala:115`), which does not exist at HEAD. `Isolate` is referenced across
`StreamCoreExtensions.scala`, `Clock.scala`, `KyoApp.scala`, and `Async.scala` and does not
exist in kernel2 at HEAD. `Fiber.scala` calls `ContextEffect.runDetached` at four sites
(`Fiber.scala:173, 749, 788, 896`) and `Context.empty` at `Fiber.scala:417`; neither
exists. So kyo-core as a whole is mid-migration against a moving kernel, and IOTask is one
file in that state.

### 1.3 Precise critique of the design the attempt left

Even read against the kernel it was written for, four things in the current file are wrong
to carry forward.

**(a) Three new mutable fields, all avoidable.** The baseline IOTask carries `curr`, `trace`,
`finalizers` (`origin/main:kyo-core/shared/src/main/scala/kyo/scheduler/IOTask.scala:11-15`).
The current one carries `curr`, `running` (volatile), `parked`, `pendingJoin`
(`IOTask.scala:11, 21, 26, 33`), and unconditionally allocates the anonymous subclass that
holds `context` (`IOTask.scala:236-237`), where the baseline factory allocates it only when
the context is non-empty (`origin/main:.../IOTask.scala:209-214`). Section 5.2 shows all
three of the new fields can go: `parked` and `pendingJoin` are slice-local and can be locals
in `run` if the boundary clause expands there, and `running` is only needed if IOTask
(rather than the scheduler) owns preemption delivery.

**(b) `running: Safepoint` was a mechanism the kernel has since deleted.** The interim
Safepoint at `23cb1d551d` was a two-state machine: `Active`/`Preempted` classes over a
`Home` indirection (`Slot` into an array, or a `Cell` in a ThreadLocal), with `depth`,
`deadline`, and `masked` fields, `openDrive`/`closeDrive` save-restore, `arm`/`expired`,
and `markMasked`/`takeMasked`
(`git show 23cb1d551d:kyo-kernel2/shared/src/main/scala/kyo/kernel/internal/Safepoint.scala`,
lines 28-57, 85-152). The published `running` reference was the channel by which a
requester reached that machine. Commit `2ccf3807e3` replaced all of it with a `Stop`
wrapper riding the existing `owners` array, and `770ee98b04` removed the last callback.
Publishing a per-slice reference so a remote thread can call a method on it is exactly the
"coordinating device" pattern `kyo-kernel2/CONTRIBUTING.md:172-181` tells you to check
before adding. It should not come back in any form; the current kernel's requester needs a
`Thread`, and the scheduler already has one (section 5.3).

**(c) The drive cannot make progress on a computation containing regions.**
`ArrowEffect.handlePartial` walks the head of a computation, answering suspensions whose
tag matches and stepping `Kyo.Defer` nodes; everything else falls to `case v => v`
(`ArrowEffect.scala:221-241`). A `Kyo.Handled` region node is "everything else". Any fiber
whose body contains a handler (`Abort.run`, `Env.run`, `Var.run`, any `handle*` call) has a
`Handled` node at or near its head, so `IOTask.eval` as written
(`IOTask.scala:72-107`) would answer nothing and return `curr` unchanged, forever. This is
precisely why `Eval.partial` exists and says so in its own comment: "ArrowEffect.handlePartial
cannot take this role: it parks at region nodes by design, so evaluating regions without
throwing on a miss needs this entry" (`Eval.scala:32-35`). The port used the wrong entry
point.

**(d) `Abort` handling by post-hoc head inspection does not survive the region model.**
`completeAbort` calls `dispatchFirst` on the returned remainder to detect an unhandled
`Abort` at the head (`IOTask.scala:168-177`), and `ensureInterrupt` does the same for a
`Join` (`IOTask.scala:194-198`). Both are head-only walks. In kernel2 an unhandled
suspension is reified by `rebuildFrom(0, v, hs, exits)` (`Eval.scala:85`), which wraps it
in one `Kyo.Handled` node per surviving layer (`Eval.scala:239-258`). The suspension is
therefore at the bottom of a stack of region nodes, not at the head, whenever the fiber has
any handler installed. Head inspection cannot see it. The correct kernel2 form is a handler
layer, not a walk (section 5).

**(e) The fatal-unwind guarantee was dropped silently.** The baseline wraps the slice's
`eval` in a `catch` that, on a `Throwable` escaping it, runs the finalizers with the
promise's error, releases the trace, clears `curr`, and only then re-propagates
(`origin/main:.../IOTask.scala:129-144`). Its comment states the stake: "The task's promise
is already completed with a Panic, but its finalizers would be skipped, stranding whatever
resource or awaited promise they release." The current file has no such arm: `run` wraps
`eval` in `try ... finally { running = null; sp.endSlice() }` (`IOTask.scala:120-124`), so
a fatal leaves `run` without `finish` ever being called. The port did not replace this
guarantee with anything, and neither commit message mentions removing it. Whatever the
finalizer ruling decides (section 4.3), the fatal path is one of the paths it must cover.

Two further items are honest deferrals rather than defects, but they are open:
`fiberTrace` now renders `curr.toString` (`IOTask.scala:53-63`) and its five tests are
`.ignore`d with the reason "fiberTrace needs a frame walk over the chain on the new kernel;
deferred" (`IOTaskTest.scala:12, 38, 57, 81, 106`); and `finish`/`finalizeBracket`
(`IOTask.scala:182-187`) is a call into a kernel bracket protocol that no longer exists.

---

## 2. Baseline: IOTask on `origin/main`

### 2.1 Field inventory and the footprint techniques

The shipped IOTask declares three fields and inherits two
(`origin/main:kyo-core/shared/src/main/scala/kyo/scheduler/IOTask.scala:11-15`):

| Field | Declared at | Size (compressed oops) |
|---|---|---|
| `state` | `origin/main:.../IOPromise.scala:15`, on a class that extends `Safepoint.Interceptor` (line 13) | 4 |
| `state: Task.State` | `Task` trait, `Task.scala:6` | 4 (Int) |
| `curr` | `origin/main:.../IOTask.scala:12` | 4 |
| `trace` | `origin/main:.../IOTask.scala:13` | 4 |
| `finalizers` | `origin/main:.../IOTask.scala:14` | 4 |

The build runs tests with `-XX:+UseCompactObjectHeaders` (`build.sbt:210`), so the header is
8 bytes. 8 + 20 = 28, aligned to **32**.

Five footprint techniques are visible, and they are the standard this design is held to:

1. **The context costs no field in the common case.** The factory branches on emptiness:
   `if ctx.isEmpty then new IOTask(curr, trace, finalizers) else new IOTask(...){ override def context = ctx }`
   (`origin/main:.../IOTask.scala:209-214`), with the base class exposing `context` as a
   plain `def` returning `Context.empty` (line 19). A context-free fiber has no context field
   at all, and a fiber that has one lands its captured reference in the four bytes of
   alignment padding the base layout already wastes, so the object stays at 32 either way.
   The current worktree file always allocates the subclass (`IOTask.scala:236-237`).
2. **Preemption and scheduling priority share one Int, without atomics.** `Task.State` packs
   runtime in bits 0-30 and the preempt flag in bit 31, and the scaladoc is explicit that
   the flag is a bit-set rather than a negation "so it works for every runtime including 0"
   (`Task.scala:62-105`). Lost updates from the three concurrent writers are tolerated by
   design, with each read-modify-write writer re-asserting the interrupt reset
   (`Task.scala:8-17`, `Task.scala:43-49`).
3. **`Finalizers` is an opaque union, not a collection.**
   `Absent.type | Finalizer | ArrayDeque[Finalizer]` (`Finalizers.scala:11-12`, identical on
   both sides): zero and one finalizer cost no wrapper at all, and the many case draws its
   `ArrayDeque` from a pooled `MpmcUnsafeQueue` and returns it after running
   (`Finalizers.scala:20-23, 54-67`).
4. **`IOPromise.state` is an opaque union too**: `Result | Pending | Linked`
   (`origin/main:.../IOPromise.scala:302`), so a promise's result, its waiter list, and its
   link all share one field.
5. **The trace is a pooled fixed-size ring, not a growing log.** `Trace` holds
   `Array[Frame]` of `maxTraceFrames = 16`
   (`kyo-kernel/shared/src/main/scala/kyo/kernel/internal/Trace.scala:22-29`,
   `kyo-kernel/.../internal/package.scala:8`), and IOTask returns it to the pool with
   `safepoint.releaseTrace(trace)` on every termination path
   (`origin/main:.../IOTask.scala:140-142, 156-158`).

The conclusion for section 5: the baseline's answer to "where does this state live" is never
"add a field". It is a union, a bit-pack, a pool, or a conditional subclass.

Two exit paths of the baseline `run` also belong in the inventory, because the port dropped
both and the new design has to decide about them explicitly. `run` drains the finalizers with
the promise's error and releases the trace on the normal termination path
(`origin/main:.../IOTask.scala:153-158`) and again on the fatal-unwind path
(`origin/main:.../IOTask.scala:129-144`). The third drain site is the interrupt-while-queued
case, which reaches the same normal-termination block through `!isPending()` at line 145.

### 2.2 The eval loop and how preemption reached it

`IOTask.eval` calls a two-tag `handlePartial`
(`kyo-kernel/shared/src/main/scala/kyo/kernel/ArrowEffect.scala:621-661`) with an explicit
`stop` thunk (`origin/main:.../IOTask.scala:70-76`):

```scala
stop = shouldPreempt() || (deadline != Long.MaxValue && clock.currentMillis() > deadline) || needsInterrupt()
```

`partialLoop` evaluates `if stop then v` at the top of every iteration
(`kyo-kernel/.../ArrowEffect.scala:636`), so all three conditions are re-read on every
dispatch and every `Defer` step. That is one coarse preemption check per operation, and the
comment at `origin/main:.../IOTask.scala:72-75` records the ordering rationale: the
authoritative interrupt read comes last so a step stopping for preemption or the deadline
skips it.

A second, finer check runs underneath it. `IOPromise` extends `Safepoint.Interceptor`
(`origin/main:.../IOPromise.scala:13`, with no-op defaults at lines 20-22), and IOTask
overrides `enter` (`origin/main:.../IOTask.scala:21-22`):

```scala
final override def enter(frame: Frame, value: Any) = !shouldPreempt()
```

The slice installs the task as the thread's interceptor for its duration:
`Isolate.internal.restoring(trace, this)` (`origin/main:.../IOTask.scala:69`) is
`Safepoint.immediate(interceptor)(safepoint.withTrace(trace)(v))`
(`kyo-kernel/shared/src/main/scala/kyo/kernel/Isolate.scala:234-240`), and `immediate`
saves and restores the previous interceptor around the block
(`kyo-kernel/.../internal/Safepoint.scala:107-123`). The kernel then consults it at every
guarded step through `Safepoint.enter` (`kyo-kernel/.../internal/Safepoint.scala:30-45`, the
`state.hasInterceptor && interceptor.enter(...)` conjunct) and, on refusal, rescues into
`Effect.defer(suspend)` (`kyo-kernel/.../internal/Safepoint.scala:206-215`). So the running
task polls its own preempt bit at frame granularity, with no cross-thread signal and no
per-frame extra load beyond the interceptor check the Safepoint already performs.

The same interceptor is how finalizers reach the task: `addFinalizer` and `removeFinalizer`
are `Interceptor` methods that IOTask overrides to push into its own `finalizers` field
(`origin/main:.../IOTask.scala:32-36`), fed by `Safepoint.ensure`
(`kyo-kernel/.../internal/Safepoint.scala:157-193`). One mechanism carries preemption
polling, trace ownership, and finalizer registration.

Four properties follow, and they are what the new design is measured against:

- **Delivery is never lost, because there is no delivery.** The coordinator sets a bit on
  the task (`Task.doPreempt`, `Task.scala:8-17`); the task reads the bit itself. No thread
  identity is involved, so worker migration is a non-question.
- **Time slicing works on every platform.** The JS and Wasm scheduler passes a real deadline
  (`kyo-scheduler/js-wasm/src/main/scala/kyo/scheduler/Scheduler.scala:12, 18`) and the
  `stop` thunk reads the clock. JVM and Native pass `Long.MaxValue` (`Worker.scala:382`) and
  rely on the coordinator's bit.
- **Preemption latency is bounded by one operation**, not by a budget period.
- **Finalizers run on every exit**, including the fatal one (2.1).

---

## 3. Preemption delivery analysis

### 3.1 The mechanism at HEAD, stated exactly

- A slot is an index into two parallel arrays sized `Slots + 1 = 8193`: `depths: Array[Long]`
  and `owners: AtomicReferenceArray[AnyRef]` (`Safepoint.scala:46-48`). The comment prices
  them at 128KB (`Safepoint.scala:22-25`).
- `Safepoint.get()` computes `(threadId * 8) & 8191` and returns that index if the entry is
  the current thread, else linear-probes and claims (`Safepoint.scala:61-97`).
- `Safepoint.stop(thread)` re-derives the index, probes for the thread's slot, and CASes the
  entry from the bare `Thread` to `new Stop(thread)`; it is idempotent (an already-wrapped
  entry returns true) and returns false if the thread owns no slot (`Safepoint.scala:139-162`).
- `Safepoint.stopped(slot)` is a one-shot consume: it plain-writes the bare thread back and
  returns true (`Safepoint.scala:166-173`).
- `Eval.partial` reads it at exactly two places: once at entry, before doing any work
  (`Eval.scala:37-38`), and in the `Kyo.Defer` arm of the loop (`Eval.scala:205-207`).
  `ArrowEffect.handlePartial` reads it in its own `Defer` arm (`ArrowEffect.scala:227-229`).
- `Eval.apply` never reads it (`Eval.scala:20-26`), so synchronous evaluation ignores stop
  requests and, critically, does not consume them.

### 3.2 Mapping the scheduler's preemption decision onto it

There are two producers of a preemption decision today, and one of them has no path to
`Safepoint.stop`.

**Time slicing on JVM and Native.** `Worker.checkStalling` runs on whichever thread calls
`checkAvailability` (the coordinator), reads `currentTask` and `taskStartMs`, and calls
`task.doPreempt()` when the task has been running longer than the time slice
(`Worker.scala:246-263`). The worker's own mounted thread is right there as
`Worker.mount` (`Worker.scala:269`). So the decision site already holds both the task and the
thread that is running it. Nothing needs to travel through IOTask for this case.

**Interrupts.** `IOTask.onComplete` fires on the interrupter's thread after
`IOPromise.interrupt` CASes the state (`IOPromise.scala:196-202`), and today it calls
`doPreempt()` plus `resetRuntime()` (`IOTask.scala:35-45`). The interrupter does not know
which worker thread, if any, is running the task. The scheduler does: `BlockingMonitor.collect`
snapshots `(worker.mountId, worker.currentTask)` per worker (`BlockingMonitor.scala:196-208`)
and `process` already dispatches `mount.interrupt()` under an `interruptLock` with a
`worker.currentTask eq task` re-check (`BlockingMonitor.scala:230-251`).
`Scheduler.notifyInterrupt` bumps the epoch and wakes the monitor for an immediate scan
(`Scheduler.scala:137-140`), and `IOTask.onInterrupted` already calls it
(`IOTask.scala:67-68`). The only gap is that the monitor's dispatch is gated on
`blocked` (`BlockingMonitor.scala:237`), so a CPU-bound interrupted fiber gets nothing.

**Time slicing on JS and Wasm.** There is no second thread. `Scheduler.schedule` runs the
task from a macrotask with `deadline = now + timeSlice` and reschedules on `Task.Preempted`
(`kyo-scheduler/js-wasm/.../Scheduler.scala:15-20`). Nobody can call `Safepoint.stop` while
the task runs, and a self-issued stop before entry would be consumed by
`Eval.partial`'s entry check without any work being done. **Under the kernel at HEAD there is
no mechanism by which a JS or Wasm fiber that does not suspend out of band can be
preempted.** The old kernel handled this through the `stop` thunk's clock read; the interim
kernel2 handled it through `Safepoint.beginSlice` arming a per-thread deadline that
`pollPreempt` converted into an ordinary request
(`kernel2-preemption-design.md:231-268`); HEAD has neither. This is the largest gap in the
integration and is open question Q1.

### 3.3 Latency bound

Detection happens only where `stopped` is read, so the bound is the maximum distance
between two such reads. Inside `Eval.partial` that is the distance between two `Kyo.Defer`
nodes.

`Kyo.Defer` nodes are minted in two places: by `Effect.defer` at every `Sync.defer` site
(`Effect.scala:68-81`), and by the budget when `<.map`'s strict arm exhausts it
(`Pending.scala:30-43`, specifically `if !Safepoint.enter(slot) then new Kyo.Defer(v, arrow)`
at lines 36-37). `Safepoint.enter` refuses past `Period = 512` (`Safepoint.scala:20, 114-121`).

So for a computation built out of `map`, the bound is **at most 512 strict steps plus the
current fused segment**, which is the property the design document asked to be pinned
(`kernel2-preemption-design.md:499-502`).

**There is a shape with no bound at all.** `ArrowEffect.suspendWith` carries an explicit
"no budget check" comment on its settled arm (`ArrowEffect.scala:48-54`), reasoning that
`f` either suspends and returns flat, or settles into chained arrows "whose strict segments
carry their own checks in map". The first disjunct is the hole. Trace it:

1. `walk(kyo.cont, answer)` is `cont.step.head(v, step.tail)` (`Eval.scala:225-228`).
2. For a `suspendWith` node `cont` is the node itself (`ArrowEffect.scala:41`), which extends
   `Arrow.Transform`, so `step` is the node, `head` is the node, and `tail` is `identity`
   (`Arrow.scala:48, 66-73`).
3. The node's settled arm computes `f(res)` and hands it to `identity`, which returns it
   unchanged when `next eq this` (`Arrow.scala:34-38`).
4. If `f(res)` is another `suspendWith` suspension, the loop takes another turn having
   touched neither `Safepoint.enter` nor `Kyo.Defer`.

`Var.get`, `Var.update`, and `Var.setDiscard` are all `suspendWith`
(`kyo-prelude/.../Var.scala:70, 97, 109, 129, 142`), as are `ContextEffect.suspendWith`
(`ContextEffect.scala:28-33`), `Async.useResult` (`Async.scala:818-823`), and `Abort.error`
(`kyo-prelude/.../Abort.scala:70`). A loop of the form "read or update a `Var`, recurse",
answered by the `LoopState` handler its `Var.run` installed, dispatches indefinitely with
zero budget consumption, so `Eval.partial` never re-reads the stop marker and the fiber is
not preemptible. `evalLoop` is `@tailrec`, so this is not a stack-safety problem; it is
purely a preemption-liveness one. This is the same class the interim design predicted and
resolved by polling at the top of the drive loop rather than in the `Defer` arm
(`kernel2-preemption-design.md:479-497`); HEAD polls only in the `Defer` arm. Open question Q2.

### 3.4 Worker migration between slices

A task's slices can run on different worker threads: `Worker.run` polls its own queue and
steals from others (`Worker.scala:286-296`), and a preempted task is requeued and may be
picked up elsewhere (`Worker.scala:303-330`).

This is benign under the HEAD mechanism, for a structural reason. The stop marker is per
thread, and it is consumed at `Eval.partial`'s entry before any work happens
(`Eval.scala:37-38`), returning the input value unchanged. So a marker that outlives its
intended victim can only cause a slice that does nothing and returns
`Task.Preempted`. Nothing is lost, because the residual returned is the input.

The liveness consequence is that the intended victim does not yield when it was supposed to.
That is bounded and self-correcting: `Worker.checkStalling` re-issues `doPreempt` on every
availability check while the task is past its slice (`Worker.scala:246-263`), so the request
is re-aimed at the thread the fiber is now on, one tick later.

### 3.5 The stale-stop question, argued

**Claim: a stop consumed by an unrelated later evaluation on the same thread is benign, not
a correctness bug.** The proof has three parts.

1. **The request carries no payload.** `Stop` holds only the thread
   (`Safepoint.scala:44`), and `stopped` returns a bare `Boolean` (`Safepoint.scala:166-173`).
   So consuming it cannot mis-deliver information; it can only cause a yield.
2. **A yield is always safe, because the residual is the standing computation.** In the
   entry case the residual is literally the input (`Eval.scala:38`). In the `Defer` case it
   is `rebuildFrom(0, v, hs, exits)` (`Eval.scala:207`), which the kernel's own comment
   describes as re-entering "the same layers with the same exits: a residual is ordinary data
   and resumes by evaluation alone" (`Eval.scala:229-238`), and which `EvalTest` pins by
   parking a 100000-step computation on a pending stop and finishing it with a second
   `Eval.partial` call (`EvalTest.scala:217-226`). No step executes twice and no step is
   skipped.
3. **Nothing depends on the stop for correctness.** Interruption is anchored on
   `IOPromise`'s CAS, not on the stop: `needsInterrupt()` is `!isPending()`
   (`IOTask.scala:50-51`), read from the promise state that `interrupt` CASes before firing
   any callback (`IOPromise.scala:196-202`). Time-slice preemption is a fairness mechanism
   whose only consumer is `Worker.run`'s requeue arm (`Worker.scala:303`). Losing either
   costs latency, never a wrong result.

The cost is therefore exactly one wasted schedule per stale request, which is what the
interim design already accepted and priced as "at most one wasted re-schedule per tick per
worker" (`kernel2-preemption-design.md:652-656`).

Two corollaries worth stating because they are non-obvious:

- **`Eval.apply` neither reads nor consumes the marker** (`Eval.scala:20-26`). A stop landing
  while a thread is inside a synchronous `eval` (for example `Abort.run(v).eval` inside a
  fiber, or `IOPromise.block`'s completion callbacks) survives that whole evaluation and is
  consumed by whatever `Eval.partial` runs next on that thread. This widens the stale window
  but does not change the argument above.
- **`stop` on a thread with no slot is an 8192-entry scan.** `probe` walks the whole array
  before returning -1 (`Safepoint.scala:153-162`), and `stop` returns false
  (`Safepoint.scala:150`). A thread that has never evaluated, or a dead one whose slot was
  reclaimed by `compact` (`Safepoint.scala:99-107`), costs the requester 8192 volatile reads.
  On the interrupt path this runs on an arbitrary caller's thread. `SafepointTest` pins the
  outcome ("stop misses a thread that never evaluated") but not the cost. Worth a cheap
  guard; noted as Q6.

### 3.6 Comparison with the baseline, in one table

| Property | `origin/main` | kernel2 at HEAD |
|---|---|---|
| Who observes the signal | the running task, reading its own bit | the running thread, reading its slot |
| Where | every guarded frame (`Safepoint.enter`) plus every `handlePartial` iteration | `Eval.partial` entry and every `Kyo.Defer` node |
| Latency bound | one operation | 512 strict `map` steps, and unbounded in `suspendWith` dispatch loops (3.3) |
| Cross-thread delivery | none needed | one CAS on `owners`, plus a probe |
| Wrong-victim possible | no | yes, benign (3.5) |
| Works on JS/Wasm | yes, via the deadline in the `stop` thunk | no (3.2) |
| Per-frame cost | one interceptor virtual call while an interceptor is installed | none |

The new mechanism is strictly better on the hot path and strictly worse on coverage. Both
halves are real.

---

## 4. Ensure, finalizers, and interrupts against a partial evaluation

### 4.1 Where the residual lives between slices, and what it costs

`curr` (`IOTask.scala:11`). One reference field, as in the baseline
(`origin/main:.../IOTask.scala:12`). What changed is what that reference points at.

Baseline: the remainder is the continuation chain, and a park clears `curr` and re-stores it
from the wakeup callback as `curr = Sync.defer(cont(r))`
(`origin/main:.../IOTask.scala:97-104`). One allocation, independent of handler nesting.

The protocol difference matters for the race in 1.1. In the baseline the clause owns `curr`:
it nulls it and the wakeup callback installs the resumption, so there is no slice-start
snapshot for a racing completion to reschedule over. kernel2's `handlePartial` returns the
remainder to the caller instead, so `run` owns the store, and the window between the
clause's poll and that store is new. That is the window `23cb1d551d` closed by moving the
wakeup registration after the store, and it is why section 5.4 keeps that ordering.

kernel2: a residual is `rebuildFrom(from, value, hs, exits)`, one `Kyo.Handled` node per
surviving layer, allocated innermost-first, with a `compact` of both chunks first when the
stack is deeper than 8 (`Eval.scala:239-258`). So the storage cost is O(open handler layers)
allocations per park or preempt, paid on the resume side for a clause park (the
`rebuildFrom` call sits inside the chained arrow at `Eval.scala:97` and runs when the answer
arrives) and on the yield side for a stop (`Eval.scala:207`). For a fiber with a handful of
handlers this is a few small objects per park, against a park that already costs a promise
callback and a reschedule. It is not a footprint concern for IOTask; it is a per-park
allocation the region model implies.

`EvalTest` pins that such a residual is resumable and that its unhandled suspension can be
answered by a handler installed later: `Eval.partial` parks, then
`ArrowEffect.handle(Tag[Say], residual)` finishes it to the right value
(`EvalTest.scala:245-252`). That pin is the foundation the design in section 5 rests on.

### 4.2 Interrupts interleaving with a partial evaluation

The invariant that makes this tractable is unchanged from the old kernel and is worth
restating: **the promise is the single source of truth**. `IOPromise.interrupt` CASes the
state to an `Error` and only then runs `onComplete()`, `onInterrupted()`, and the waiter
flush (`IOPromise.scala:196-202`). `needsInterrupt()` and `run`'s `isPending()` checks read
that state (`IOTask.scala:50-51`, `IOTask.scala:110, 125`). A stop request that is lost,
stale, or aimed at the wrong thread delays observation; it cannot lose the interrupt.

Three interleavings, with what each needs from the design:

1. **Interrupt lands while the task is queued.** `run` observes `!isPending()` at entry and
   must finalize the retained remainder without driving user code (`IOTask.scala:109-114`).
2. **Interrupt lands mid-slice.** The drive returns some residual. `run` re-checks
   `isPending()` and, on failure, must treat the residual (not the slice-start `curr`) as the
   accurate remainder, because the residual's head is the operation the drive stopped in
   front of (`IOTask.scala:125-130`, whose comment states exactly this).
3. **Interrupt lands while parked on a join.** The cascade link registered at park time
   propagates the interrupt to the awaited promise through `IOPromise.interrupts`
   (`IOPromise.scala:64-75`), whose completed-state arm interrupts the other promise
   immediately (`IOPromise.scala:72-73`).

The current file additionally runs `ensureInterrupt` to register a cascade link for a join
the drive stopped in front of but had not yet linked (`IOTask.scala:189-198`). Section 5.4
shows that under a boundary handler layer this race cannot arise, because the link is
registered at the operation rather than reconstructed afterwards, so `ensureInterrupt` and
its `dispatchFirst` disappear.

### 4.3 Ensure and finalizers: genuinely undetermined

This is the one part of the integration that cannot be designed today, and saying so is the
honest position rather than a deferral.

`Sync.ensure` currently lowers to `Effect.bracket` (`Sync.scala:115`), which does not exist
in kernel2 at HEAD. `kernel2-finalizer-design.md` records six structural encodings that were
built and failed (section 3 of that document, items a through f), states the root cause
("ctl-format rotation is monomorphic in the handler's result type ... Structure alone cannot
express 'after the region' on the discard path", lines 111-116), and proposes a different
direction (finalizers saved and executed by the eval loop, with the store as an explicit
parameter of the eval entry points, lines 118-156). That direction has one open ruling of
its own, the store's carrier at the seams (lines 245-260), and it directly names IOTask as
a participant: "the boundary owns the store (the fiber, via IOTask, exactly as the old
kernel's fiber-level list)" (line 129), later amended to "parks must keep remainders
self-contained ... the store is drive-local; the boundary owns only the remainder" (lines
206-212).

What this means concretely for IOTask:

- Whether the `finalizers` field comes back at all is a consequence of that ruling, not an
  IOTask decision. Under the original proposal it does (a field). Under adopted Amendment 1
  it does not (the remainder carries a sew node).
- `finish` (`IOTask.scala:182-187`) has no kernel counterpart to call today.
- A `done` answer from a boundary `Abort` handler truncates the layer stack and the discarded
  layers' exits never run (`kyo-kernel2/CONTRIBUTING.md:116`). Whether that is correct
  depends entirely on whether finalizers live in the exits or in a store.

The recommendation in section 5 is therefore written so that it is complete and correct for
everything except finalization, and so that both outcomes of the finalizer ruling drop into
it without changing its shape. That is stated as Q3, and it is a blocker for shipping a green
kyo-core, not for agreeing the integration design.

---

## 5. Proposed design

### 5.1 The core idea

Two facts from the kernel determine the whole shape.

- **Answering an effect at the boundary must be a handler layer, not a walk**, because a
  suspension is reified inside its surviving layers (`Eval.scala:85`, `Eval.scala:239-258`)
  and head inspection cannot reach it.
- **A clause whose outcome is pending runs outside its own layer** (`Eval.scala:91-101`,
  `hs.take(idx)`), and `kyo-kernel2/CONTRIBUTING.md:117` states the rule: "a clause runs
  outside its own region by construction. The layers the operation crossed are rebuilt around
  the resumption."

Put those together with the boundary layer installed outermost, so its index is 0. When the
clause parks by re-raising its own operation as a pending outcome, the loop continues under
`hs.take(0)`, which is empty, the re-raised suspension misses, and the residual is a **bare
`Kyo.Suspend` with no region nodes around it**, whose continuation already carries a
`rebuildFrom(0, ...)` of the entire layer stack including the boundary layer itself
(`Eval.scala:97`). That residual is exactly the one thing `ArrowEffect.handlePartial` can
answer, and it answers it **without installing a new layer** (`ArrowEffect.scala:223-226`).
Resuming therefore restores the original layer stack rather than nesting a second boundary
layer around it.

The park protocol the current file implements by hand ("the next slice re-enters this
handler with a fresh continuation and polls again", `IOTask.scala:92-96`) becomes a
consequence of the region semantics instead of a mechanism.

### 5.2 Proposed field layout

```scala
sealed private[kyo] class IOTask[Ctx, E, A] private (
    private var curr: A < (Ctx & Async & Abort[E])
) extends IOPromise[E, A] with Task
```

| Field | Kept? | Justification |
|---|---|---|
| `curr` | yes | the residual between slices; irreducible |
| `context` | conditional, as a `def` overridden by an anonymous subclass only when non-empty | restores the baseline factory's branch (`origin/main:.../IOTask.scala:209-214`); see 5.6 and Q4 |
| `trace` | **dropped** | kernel2 has no `Trace`; `fiberTrace` renders the chain (5.7) |
| `finalizers` | **dropped, pending Q3** | the finalizer ruling decides whether a store returns |
| `running` | **dropped**, first choice | delivery moves to the scheduler (5.3); fallback is one `@volatile var running: Thread` |
| `parked` | **dropped** | a local `Boolean` in `run` |
| `pendingJoin` | **dropped** | a local `IOPromise` in `run`, set by the inline boundary clause that expands into `run` |

Layout consequence under compact headers and compressed oops.
**Baseline** (`origin/main:.../IOTask.scala:11-15`): header 8 + `IOPromise.state` 4
+ `Task.state` 4 + `curr` 4 + `trace` 4 + `finalizers` 4 = 28, aligned to **32**, and 32
again with the conditional context field because it lands in existing padding.
**Current worktree file**: 8 + `IOPromise.state` 4 + `Task.state` 4 + `curr` 4 + `running` 4
+ `pendingJoin` 4 + `parked` 1 + the always-present `context` 4 = 33, aligned to **40**.
**Proposed, zero-field variant**: 8 + 4 + 4 + 4 = 20, aligned to **24**, with the conditional
context landing in padding at 24. **Proposed, `running: Thread` fallback**: 8 + 4 + 4 + 4 + 4
= 24, aligned to **24** as well, so the fallback is free relative to the zero-field variant
and both are smaller than the baseline's 32. That headroom is the budget a finalizer store
would spend if Q3 rules it back, which would return the proposal to the baseline's 32.

Note the fallback's parity: `running: Thread` is one word where the current file has
`running: Safepoint`, so the improvement over the current file is not the field itself but
dropping the interim Safepoint's machinery behind it, plus dropping `parked` and
`pendingJoin`.

### 5.3 Preemption wiring

Delivery is `Safepoint.stop(thread)` (`Safepoint.scala:139-151`), and the design question is
who holds the thread. Recommendation: the scheduler, because it already does.

kyo-scheduler cannot call into kyo-kernel2; the module is standalone and deliberately
kernel-free (`Task.scala:31-38` explains why `fiberTrace` returns a plain `String`). So the
thread has to reach kyo-core through a `Task` hook. Proposed hook, replacing nothing:

```scala
// kyo-scheduler, Task
/** Requests that the thread currently executing this task yield at its next safepoint.
  * Called only by the scheduler, which knows the mounted thread; a plain Task ignores it. */
private[scheduler] def preemptOn(thread: Thread): Unit = ()
```

with `IOTask` overriding it as `discard(Safepoint.stop(thread))`. Two call sites:

1. `Worker.checkStalling`, alongside the existing `task.doPreempt()`
   (`Worker.scala:246-263`), passing `mount` (`Worker.scala:269`). This is the time-slice
   path, and it needs no new gating: the method already establishes that this task has been
   running on this worker past its slice.
2. `BlockingMonitor.process`, alongside the existing `mount.interrupt()`
   (`BlockingMonitor.scala:230-251`), for tasks whose `needsInterrupt()` holds. The existing
   dispatch is gated on `blocked` (line 237); the stop dispatch must not be, because the
   case that matters is a CPU-bound interrupted fiber. Everything else about the guard is
   reusable as is: the `interruptLock` CAS and the `worker.currentTask eq task` re-check
   (lines 239-241) are exactly the identity guard a stop needs, and `Scheduler.notifyInterrupt`
   already wakes the monitor immediately on every real interrupt (`Scheduler.scala:137-140`),
   which `IOTask.onInterrupted` already calls (`IOTask.scala:67-68`).

`IOTask.onComplete` then reduces to what it does for the scheduler and nothing else:

```scala
final override def onComplete() =
    doPreempt()
    resetRuntime()
```

`Task.State`'s preempt bit stays as the scheduler-internal signal it already is
(`Task.scala:19-20, 86-91`); nothing in the kernel reads it any more, which the interim
design also concluded (`kernel2-preemption-design.md:779-784`).

If Q5 is ruled the other way, the fallback is `@volatile private var running: Thread`,
written at slice entry and nulled in the `finally`, with `onComplete` reading it and calling
`Safepoint.stop`. It is one field and it is correct; it is second choice only because the
scheduler already holds the same information with a better identity guard.

### 5.4 The run loop

Signatures used below, verbatim from HEAD:

- `def partial[A, S](v: A < S): A < S` (`Eval.scala:36`)
- `inline def handlePartial[I[_], O[_], E <: ArrowEffect[I, O], A, S](inline _tag: Tag[E], v: A < (E & S))(inline f: [X] => (I[X], O[X] => A < (E & S)) => Maybe[A < (E & S)])(using inline _frame: Frame): A < (E & S)` (`ArrowEffect.scala:218-220`)
- `inline def handleLoop[I[_], O[_], E <: ArrowEffect[I, O], A, S, S2](inline _tag: Tag[E], v: A < (E & S))(inline f: [X] => I[X] => Loop.Outcome[O[X] < (E & S & S2), A] < S2)(using inline _frame: Frame): A < (S & S2)` (`ArrowEffect.scala:109-111`)
- `private[kyo] inline def evalNow: Maybe[A]` (`Pending.scala:68`)

**At fiber creation**, wrap once, `Join` outermost:

```scala
// IOTask.apply
val boundary =
    ArrowEffect.handleLoop(Tag[Async.Join],
        ArrowEffect.handleLoop(erasedAbortTag, user)(
            [C] => error =>
                task.completeDiscard(error.asInstanceOf[Result[E, A]])
                Loop.done(nullResult)          // see Q3: what the truncation must not skip
        )
    )(
        [C] => joinInput =>
            val p = joinInput(task)
            p.poll() match
                case Absent    => reraise(joinInput)     // park, see below
                case Present(r) => task.removeInterrupt(p); Loop.continue(r)
                case null       => task.removeInterrupt(p); Loop.continue(null)
    )
```

`Join` must be outermost so its index is 0 and `hs.take(idx)` is empty on the park path;
that is what makes the park residual a bare suspension. `Abort` sits directly inside it and
`Handlers.indexOf` scans innermost-first (`Handlers.scala:22-28`), so any `Abort.run` the
user installed still wins. The erased tag works because both sides are erased to
`Abort[Any]`: `Abort.error` suspends with `erasedTag` (`kyo-prelude/.../Abort.scala:48, 70`)
and so does `Abort.run`. The existing `erasedAbortTag` in IOTask (`IOTask.scala:70`) is the
same trick.

`reraise` is the park, and it is one line of ordinary kernel usage:

```scala
ArrowEffect.suspend[C](Tag[Async.Join], joinInput).map(Loop.continue(_))
```

The clause returns a pending outcome, so `evalLoop` chains the decision and continues under
`hs.take(0)` (`Eval.scala:91-101`), the re-raised `Join` misses, and `Eval.partial` reifies
it as a bare suspension (`Eval.scala:79-85`).

**Per slice:**

```scala
final def run(startMillis: Long, clock: InternalClock, deadline: Long): Task.Result =
    if !isPending() then
        finish(curr)                       // Q3
        Task.Done
    else
        var joined: IOPromise[?, ?] = null
        val next =
            try
                val driven = Eval.partial(curr)
                // answer a park residual whose promise completed, or record the promise
                ArrowEffect.handlePartial(Tag[Async.Join], driven)(
                    [C] =>
                        (joinInput, cont) =>
                            val p = joinInput(this)
                            p.poll() match
                                case Absent => joined = p; Maybe.Absent
                                case r      => this.removeInterrupt(p); Maybe(cont(coerce(r)))
                )
            catch
                case ex =>
                    completeDiscard(new Result.Panic(ex))
                    if !NonFatal(ex) then
                        // the baseline's fatal-unwind guarantee (origin/main:.../IOTask.scala:129-144):
                        // the promise is already Panic-completed, but a fatal must not strand
                        // whatever the finalizers release. Q3 decides what this call becomes.
                        finish(curr)
                        throw ex
                    nullResult
        if !isPending() then
            finish(if !isNull(next) then next else curr)   // Q3
            Task.Done
        else
            next.evalNow match
                case Present(a) =>
                    completeDiscard(Result.succeed(a))
                    curr = nullResult
                    Task.Done
                case Absent =>
                    curr = next
                    if joined ne null then
                        // last task-state action of the slice: once the wakeup can fire,
                        // another worker may run this task concurrently with this return
                        joined.onComplete { _ =>
                            this.removeInterrupt(joined)
                            Scheduler.get.schedule(this)
                        }
                        Task.Done
                    else
                        Task.Preempted
```

Notes on why each piece is what it is:

- **`Eval.partial` is the driver**, not `handlePartial`. It evaluates regions
  (`EvalTest.scala:240-243` pins this) and yields on a stop or an unhandled suspension. This
  is the correction to critique 1.3(c).
- **`handlePartial` is the resumer**, and only that. It answers a bare `Join` suspension at
  the head with no new region node (`ArrowEffect.scala:223-226`), which is the only way to
  resume a park without nesting a second boundary layer per park. When `driven` is a stop
  residual its head is a `Kyo.Handled` stack or a `Kyo.Defer`, so `handlePartial` either
  returns immediately or steps a few defers, both harmless.
- **`parked` and `pendingJoin` become the local `joined`**, because `handlePartial` is
  `inline` and its clause expands into `run`. This is the whole reason the drive lives in
  `run` rather than in a separate `eval` method: the field in the current file exists only
  because the clause could not see `run`'s stack.
- **The race fix from `23cb1d551d` is preserved verbatim in shape**: `curr = next` happens
  before `joined.onComplete`, and nothing writes task state after the registration.
  `IOTaskTest`'s 50000-iteration hammer (`IOTaskTest.scala:120-146`) still adjudicates it.
- **`completeAbort` and `ensureInterrupt` are deleted.** The boundary `Abort` layer completes
  the promise at the operation, so there is nothing to detect afterwards. The `Join` cascade
  link is registered by `joinInput(this)` inside whichever clause sees the operation, so
  there is no window in which a park exists without its link and no walk to reconstruct one.
  Both deletions remove uses of `dispatchFirst`, which kernel2 does not have.

### 5.5 What maps, what changes, what is dropped

| Baseline behavior (`origin/main`) | Under this design |
|---|---|
| slice budget (stack safety), via the interceptor's depth accounting | `Safepoint` depth budget, `Period = 512` (`Safepoint.scala:20, 114-121`); IOTask does not participate |
| preemption honored, via `enter` reading `shouldPreempt()` (`origin/main:.../IOTask.scala:21-22`) | `Safepoint.stop` from the scheduler (5.3), observed by `Eval.partial` (3.1), with the coverage gaps of 3.3 |
| fair yielding and requeue | unchanged: `Task.Preempted` and `Worker.run`'s requeue arm (`Worker.scala:303-330`) |
| interrupt authority | unchanged: `IOPromise` state, `needsInterrupt()` (`origin/main:.../IOTask.scala:41-42`) |
| interrupt priority boost | unchanged: `resetRuntime()` in `onComplete` (`origin/main:.../IOTask.scala:24-30`) |
| unhandled `Abort` completes the promise (`origin/main:.../IOTask.scala:77-82`) | boundary `Abort` handler layer, replacing the two-tag `handlePartial` clause |
| join park and wakeup (`origin/main:.../IOTask.scala:83-106`) | boundary `Join` handler layer plus `handlePartial` resume; the store-then-register ordering of `23cb1d551d` is required here and kept (4.1) |
| interrupt-before-join cascade repair, `ensureInterrupt` via `dispatchFirst` (`origin/main:.../IOTask.scala:176-180`) | deleted: the link is registered at the operation, so the window does not exist (5.4) |
| finalizers drained on the normal exit (`origin/main:.../IOTask.scala:153-155`) | undetermined, Q3 |
| finalizers drained on the fatal unwind (`origin/main:.../IOTask.scala:129-144`) | preserved in shape in 5.4's `catch`; its body is Q3 |
| pooled trace, released on every exit (`origin/main:.../IOTask.scala:140-142, 156-158`) | dropped with `Trace`; `fiberTrace` renders the chain, see 5.7 |
| JS and Wasm time slicing via the deadline in the `stop` thunk (`origin/main:.../IOTask.scala:76`) | **not covered**, Q1 |

### 5.6 Context and the fiber identity

`IOTask.CurrentFiber` (`IOTask.scala:216`) and `parentIn` (`IOTask.scala:218-220`) are
written against a `Context` map that no longer exists. Under HEAD's `ContextEffect`,
provision is an answering handler layer (`ContextEffect.scala:55-59`) and reading the parent
means raising the effect. That is a different design with its own live track
(`kernel2-context-threading-design.md`, `kernel2-context-typemap-design.md`) and its own
agents. This report deliberately does not design it; it only records the interface IOTask
needs from it: at spawn time, read the current fiber (for interrupt linking) once and pass
it, and bind the new task as the current fiber for the computation it drives. The baseline
reads the parent from the Safepoint interceptor and passes it to the factory rather than
reading a thread local per child (`origin/main:.../IOTask.scala:194-198, 216-219`); that
call shape survives whatever replaces the interceptor. The proposed field layout keeps
`context` as a `def` so that whatever the context track lands can use the baseline factory's
conditional-subclass trick, which is why 5.2 marks it conditional.

### 5.7 fiberTrace

The baseline renders `Trace.render(snapshot)` off the task's pooled 16-frame ring, containing
every `Throwable` because the read is cross-thread from the leak probe
(`origin/main:.../IOTask.scala:44-53`). `Trace` does not exist in kernel2, so both the render
and the pool go; the current `fiberTrace` renders `curr.toString`
(`IOTask.scala:53-63`) with all five tests ignored (`IOTaskTest.scala:12, 38, 57, 81, 106`).
The node `toString`s are frame-aware (`KyoInternal.scala:56-57` renders
`frame.position.show` and `frame.snippetShort`; `Arrow.Step.toString` renders the first
transform's frame and deliberately does not walk, `Arrow.scala:52-53`), so a real frame walk
over the residual is buildable: the residual is ordinary data and `Kyo.Suspend.frame` and
`Arrow.Transform.frame` are both reachable. This is a separable work item, not a blocker,
but the five ignored tests are the acceptance criteria and should not stay ignored
indefinitely.

---

## 6. Rejected alternatives

**Publish a per-slice `Safepoint` (or any kernel object) on the task so a requester can call
a method on it.** This is what `running` does today (`IOTask.scala:21, 44`). Rejected on
three counts: the object it names no longer exists; the requester needs a `Thread`, which
`Safepoint.stop` takes directly (`Safepoint.scala:139`); and it is a coordination device
whose only job is to protect a property the scheduler already holds
(`kyo-kernel2/CONTRIBUTING.md:172-181`).

**Drive with `ArrowEffect.handlePartial` alone.** Rejected on correctness: it parks at every
`Kyo.Handled` node (`ArrowEffect.scala:237-238`), so a fiber containing any handler makes no
progress. The kernel says so in `Eval.scala:32-35`.

**Drive with `Eval.partial` alone, resuming a park by re-wrapping the residual in a fresh
boundary layer.** This is the shape `EvalTest.scala:245-252` demonstrates, and it works
once. It fails as a protocol: the park residual's continuation already rebuilds the previous
boundary layer (`Eval.scala:97` calls `rebuildFrom(idx, ...)`, inclusive of `idx`), so
re-wrapping nests a second `Join` layer per park. After the second park the innermost `Join`
handler is no longer at index 0, `hs.take(idx)` is non-empty, the residual head becomes a
`Kyo.Handled` node, and no entry point can answer it. Layers also accumulate one per park for
the fiber's lifetime, lengthening every `Handlers.indexOf` scan (`Handlers.scala:22-28`).

**Distinguish park from preempt by type-testing the residual head.** A stop residual heads
with a `Kyo.Defer` (`Eval.scala:207`) and a park residual with a `Kyo.Suspend`, so the test
is sound. Rejected as a design: it makes kyo-core depend on the internal node shapes, and the
`handlePartial` clause already answers the question as a side effect of the work it has to do
anyway, with no test at all.

**Keep a `stop: () => Boolean` callback on the partial drive** (the old kernel's shape,
`kyo-kernel/.../ArrowEffect.scala:627`). This would close both the JS deadline gap and the
`suspendWith` latency gap in one move. Rejected because it was already ruled against
(`kernel2-preemption-design.md:72-74`, ruling 2, and lines 805-811), it puts an indirect call
on every drive iteration, and it makes the kernel depend on scheduler state.

**Fold `running` and `pendingJoin` into one union-typed field**, in the style of
`IOPromise.state` (`IOPromise.scala:298`) and `Finalizers` (`Finalizers.scala:11-12`). The
precedent is real and the two are almost never live at once. Rejected because 5.2 removes
both fields outright, so the union would be optimizing a field that no longer exists.

**Add a `Maybe`-typed or reason-carrying return to the partial drive so IOTask learns why it
stopped.** Rejected for the reason the interim design gave (`kernel2-preemption-design.md:813-818`):
it allocates per slice, widens the API, and the caller does not need it. Section 5.4 gets the
distinction from `joined` for free.

**Have IOTask self-issue `Safepoint.stop(Thread.currentThread())` on JS when the deadline
passes.** Rejected on mechanism: IOTask only regains control at a park or at drive exit, and
at both of those points the slice is already over. A stop issued before entry is consumed by
`Eval.partial`'s entry check (`Eval.scala:37-38`) with no work done. It cannot bound a
CPU-bound fiber, which is the only case that needs bounding.

---

## 7. Open questions requiring a maintainer ruling

These are separated from the recommendations above deliberately. Everything in section 5 is
a settled recommendation except where it points here.

**Q1 (blocking, largest). JS and Wasm time slicing.** Under kernel2 at HEAD there is no way
to preempt a fiber that does not suspend out of band on a single-threaded platform (3.2).
The old kernel read the clock in the `stop` thunk; the interim kernel2 armed a deadline on
the Safepoint (`kernel2-preemption-design.md:231-268`); HEAD has neither, while the JS
scheduler still passes a real deadline and still expects `Task.Preempted`
(`kyo-scheduler/js-wasm/.../Scheduler.scala:15-20`). The minimal shape that fits the current
Safepoint is a parallel `Array[Long]` of per-slot deadlines read at the same two sites that
already read the stop marker, so the cost is one array read and one compare per budget
period and zero on the hot path, with `Long.MaxValue` meaning unarmed on JVM and Native. Is
that acceptable, or is losing JS fiber time slicing the intended trade?

**Q2 (blocking for latency claims). The `suspendWith` dispatch loop is not preemptible.**
Section 3.3 traces a shape that dispatches indefinitely without consuming budget or minting
a `Defer`, so `Eval.partial` never re-reads the stop marker. The interim design's answer was
to poll at the top of the drive loop rather than in the `Defer` arm
(`kernel2-preemption-design.md:479-497`), which costs one `Safepoint.get` plus one read per
loop iteration. Is that cost acceptable, is a cheaper site available (for example counting
answered operations against the same budget), or is this shape considered out of scope?

**Q3 (blocking for a green kyo-core, not for this design). Finalizers.** `Sync.ensure` has
no kernel counterpart at HEAD (4.3), and the finalizer track's own ruling on the store
carrier is open (`kernel2-finalizer-design.md:245-260`). Three sub-questions land on IOTask
directly: does the fiber own a finalizer store (a field) or does the remainder carry a sew
node (no field); what must a boundary `Abort` handler's `done` truncation not skip, given
that discarded layers' exits do not run (`kyo-kernel2/CONTRIBUTING.md:116`); and what covers
the baseline's fatal-unwind drain (`origin/main:.../IOTask.scala:129-144`), which the port
dropped without replacement (1.3(e))? The baseline drains on three paths (normal termination,
fatal unwind, and interrupt-while-queued); whatever lands must cover all three.

**Q4. Context and fiber identity.** Section 5.6 states the interface IOTask needs but does
not design it, since `kernel2-context-threading-design.md` owns it. Confirm that IOTask
should keep `context` as a `def` with the conditional-subclass factory, so the footprint
trick from 2.1 survives whatever that track lands.

**Q5. Who owns preemption delivery.** Section 5.3 recommends the scheduler, using
`Worker.mount` and `BlockingMonitor`'s existing task-identity guard, for zero IOTask fields.
The alternative is one `@volatile var running: Thread` on IOTask. The scheduler route needs a
new `Task` hook and un-gates one `BlockingMonitor` dispatch from `blocked`; the IOTask route
needs neither but costs a field and re-introduces a published per-slice reference. Which?

**Q6 (small, kernel-side). `Safepoint.stop` on a thread with no slot scans all 8192 entries**
(`Safepoint.scala:153-162`). On the interrupt path this runs on an arbitrary caller's thread.
Worth a fast negative (for example checking the strided home index before probing)?

**Q7 (small, kernel-side, likely a live defect). `Safepoint` uses `Thread.threadId()`**
(`Safepoint.scala:63, 140`). The old kernel deliberately used the deprecated `getId()` with a
comment recording why: "its replacement threadId() is absent from the Scala.js javalib and
fails JS and Wasm linking (it type-checks against the JDK, then breaks at link)"
(`kyo-kernel/.../Safepoint.scala:32-36`), and the interim kernel2 Safepoint carried the same
comment. kernel2's suites run on JVM only today, so nothing has caught it. This will break
kyo-core's JS and Wasm link, not compile.

---

## 8. What this design does not claim

- Nothing here has been compiled. In particular the exact type ascriptions in 5.4's clauses
  (the `Loop.Outcome[O[X] < (E & S & S2), A] < S2` shape of the re-raise, and the erased
  `Result` coercions) are asserted from the signatures at `ArrowEffect.scala:109-111` and
  `ArrowEffect.scala:218-220` and will need adjustment against the compiler.
- The claim that the boundary `Join` layer sits at index 0 depends on it being installed
  outermost and on `rebuildFrom(idx, ...)` being inclusive of `idx` (`Eval.scala:97, 246-257`).
  Both are read from the source; neither is covered by an existing test in the exact
  configuration this design uses. A red-first pin for "park, resume, park again, resume"
  asserting the layer count does not grow is the first test to write.
- The allocation cost of one `rebuildFrom` per park (4.1) is reasoned, not measured. The
  kernel's own gate requires a JMH A/B for changes to `Eval.scala` (`kyo-kernel2/CONTRIBUTING.md:200`);
  this design changes no kernel file except under Q1 and Q2, but the IOTask-side per-park cost
  should be measured against `origin/main` on the arena rows before the integration is called
  done.
- The baseline comparison is against `origin/main` at `2e9bb02d40` only. The branch's own
  pre-port IOTask (`77cedd3689^`, that is `3c055b91db`) is close but not identical: it lacks
  the fatal-unwind arm and the all-throwable containment in `IOPromise`'s callback loop that
  `origin/main` carries. Nothing in this report is based on that branch-local version.

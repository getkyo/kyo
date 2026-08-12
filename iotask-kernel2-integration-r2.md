# IOTask on kernel2: current state, preemption analysis, and proposed integration (r2)

Status: analysis and design. No code was compiled, run, or benchmarked for this document.
Every claim about existing code carries a file and line citation and names the codebase it
belongs to. Where something is genuinely undetermined it is stated as a ruling in section 9
rather than resolved by assertion.

This is a complete revision of `iotask-kernel2-integration.md` (r1, written against kernel
state `b9356dee49`). It is self-contained: nothing here requires reading r1 first. Every
substantive difference from r1 carries an inline **Changed from r1:** note, so a reader who
knows r1 can find the deltas without diffing.

Two code bases are cited, and every citation says which.

- **Baseline**, the shipped implementation this integration must not regress:
  `origin/main` at `2e9bb02d40`. Cited as `origin/main:<path>:<line>`, readable with
  `git show origin/main:<path>`.
- **Worktree**, the branch carrying kernel2 and the prior porting attempt, at `6ee19e2ebe`.
  Cited as a bare `<path>:<line>`.

Relevant differences between the two, established by diff: `kyo-scheduler` is byte-identical
(`git diff origin/main HEAD -- kyo-scheduler` produces no output), so every scheduler citation
holds for both. `IOTask.scala` and `IOPromise.scala` are the two files the port rewrote, and
the old kernel (`kyo-kernel`) is present on both sides while `kyo-kernel2` exists only in the
worktree.

**Changed from r1:** r1 pinned the worktree at `b9356dee49`. HEAD is now `6ee19e2ebe`, 40
commits later, and the kernel changed in ways that invalidate a large fraction of r1's
citations and two of its conclusions. Section 1.4 lists the kernel deltas that matter.

Companion documents already in the tree: `kernel2-preemption-design.md` (the design that
produced the now-deleted interim Safepoint), `kernel2-preemption-analysis.md`,
`kernel2-finalizer-design.md`, and `kyo-kernel2/CONTRIBUTING.md` (the doctrine this design is
judged against).

Ruled context taken as given by this revision, per the brief:

- The old kernel's `Safepoint.Interceptor` is dead and will not be ported.
- A bracket primitive is coming to the kernel as a new node type `Kyo.Bracket`, similar in
  shape to `Kyo.Handled`, holding acquire, use, and release computations. Section 4.3 designs
  against its existence and states the three requirements IOTask places on it.
- An exception-enrichment mechanism succeeding the old `EffectTrace` is being designed
  separately. Section 5.9 records the one point where IOTask touches it.
- An `Isolate` redesign over the reified handler stack is being explored separately. Section
  5.7 records the dependency and designs nothing.

---

## 1. Current state

### 1.1 What the prior attempt did

Two commits ported kyo-core's scheduler integration onto the kernel that existed on
2026-08-09.

`77cedd3689` ("[kyo-core] port the scheduler integration and Sync to the new kernel")
rewrote `IOTask` on a new drive currency and moved `Sync` onto kernel brackets. Its own
message states the shape: "IOTask is rewritten on the drive currency:
handlePartial(Async.Join) with the re-entry park protocol ..., beginSlice deadlines with a
completion-time preempt push through the published running safepoint, boundary completion of
unhandled Aborts via dispatchFirst, and finalizeBracket on every discarded remainder." It
closes with "Compile-green on JVM for kernel2, prelude, and core, main and test; suites not
yet run."

`23cb1d551d` ("[kyo-core] fix the park protocol race and port gaps surfaced by the suite")
fixed a real race the suite found: the park registered its wakeup inside the slice before
`run` published the remainder into `curr`, so a completion landing in that window rescheduled
the task over the stale slice-start snapshot. The fix added a third mutable field,
`pendingJoin`, so the wakeup registration moves to `run` after the store
(`kyo-core/shared/src/main/scala/kyo/scheduler/IOTask.scala:28-33`,
`kyo-core/shared/src/main/scala/kyo/scheduler/IOTask.scala:145-157`). It also restored the
null-poll arm (`kyo-core/shared/src/main/scala/kyo/scheduler/IOTask.scala:81-85`). Its own
message ends "Unverified: full kyo-core suite rerun in flight; interrupt-path stucks and the
semaphore leak from the previous run not yet re-diagnosed against these fixes."

`IOTask.scala` in the worktree has not changed since `23cb1d551d`: every line cited from it in
r1 is still at the same line at HEAD.

### 1.2 The decisive fact: today's IOTask is stranded, not merely suboptimal

The kernel deleted every API the port was written against, and the file cannot compile against
`kyo-kernel2` at HEAD. This is the state of the code, re-verified against HEAD rather than
carried over from r1.

| Reference in `IOTask.scala` | Line | Exists in kernel2 at HEAD? |
|---|---|---|
| `Context`, `Context.empty` | 16 | No. `kyo-kernel2/shared/src/main/scala/kyo/kernel/internal/` contains exactly `Eval.scala`, `Handler.scala`, `Handlers.scala`, `KyoInternal.scala`, `LiftMacro.scala`, `Safepoint.scala`. There is no `Context.scala`. |
| `Safepoint` as an instance with `preempt()` | 21, 44 | No. `class Safepoint` at HEAD is an empty declaration (`kyo-kernel2/shared/src/main/scala/kyo/kernel/internal/Safepoint.scala:9`); all state and operations are statics on the companion (`Safepoint.scala:11-182`). |
| `Safepoint.beginSlice(deadline)` | 118 | No. Grep across the tree finds this one occurrence and no definition. |
| `sp.endSlice()` | 124 | No. |
| `ArrowEffect.handlePartial(tag, curr, context)(...)` | 74 | Wrong arity. HEAD's signature is `handlePartial(_tag, v)(f)(using _frame)` (`kyo-kernel2/shared/src/main/scala/kyo/kernel/ArrowEffect.scala:223-225`); there is no context parameter and no `stop` thunk. |
| `ArrowEffect.dispatchFirst` | 170, 195 | No. Not present anywhere in kernel2. Section 5.5 rules on adding it. |
| `remainder.finalizeBracket(...)` | 185 | No. `Effect` at HEAD carries `catching`, the private `guarded`, and `defer` and nothing else (`kyo-kernel2/shared/src/main/scala/kyo/kernel/Effect.scala:13-93`). |
| `ContextEffect[IOPromise[?, ?]]` with `Context.set`/`get`/`contains` | 216-219, 237 | Partially. `ContextEffect` exists (`kyo-kernel2/shared/src/main/scala/kyo/kernel/ContextEffect.scala:17`) but is an `ArrowEffect[Const[Unit], Const[A]]` provisioned by a handler layer (`ContextEffect.scala:57-61`); there is no `Context` map to `set` or read. |

**Changed from r1:** the `internal` package listing lost `CanLift.scala`. The lift gate is now
the macro at `kyo-kernel2/shared/src/main/scala/kyo/kernel/internal/LiftMacro.scala` reached
from `Implicits.lift` (`kyo-kernel2/shared/src/main/scala/kyo/kernel/Implicits.scala:15-20`),
and there is no `CanLift` evidence type anywhere in kernel2's main sources.
`kyo-kernel2/CONTRIBUTING.md:26` still lists `CanLift.scala` as an internal file, so that
guide is itself stale on this point.

The damage is not confined to `IOTask`. `Sync.ensure` calls `Effect.bracket`
(`kyo-core/shared/src/main/scala/kyo/Sync.scala:115`), which does not exist at HEAD.
`Fiber.scala` calls `ContextEffect.runDetached` at four sites
(`kyo-core/shared/src/main/scala/kyo/Fiber.scala:173, 749, 788, 896`) and `Context.empty` at
`kyo-core/shared/src/main/scala/kyo/Fiber.scala:417`; neither exists. `Isolate` is referenced
at `kyo-core/shared/src/main/scala/kyo/Fiber.scala:129, 148, 165` and does not exist in
kernel2. So kyo-core as a whole is mid-migration against a moving kernel, and IOTask is one
file in that state.

One correction to the record that r1 got wrong. r1 wrote that "`Sync.ensure` currently lowers
to `Effect.bracket`", implying that is the baseline shape. It is not: on `origin/main`,
`Sync.ensure` lowers to `Safepoint.ensure`
(`origin/main:kyo-core/shared/src/main/scala/kyo/Sync.scala:111`), and `Effect.bracket` does
not exist in the old kernel either. `Effect.bracket` was introduced by the port itself and
never had a kernel counterpart. **Changed from r1:** the finalizer discussion in section 4.3 is
rewritten on that footing.

### 1.3 Precise critique of the design the attempt left

Read against the kernel it was written for, five things in the current file are wrong to carry
forward. All five still hold at HEAD; the citations behind (c) and (d) changed.

**(a) Three new mutable fields, two of them avoidable.** The baseline IOTask carries `curr`,
`trace`, `finalizers`
(`origin/main:kyo-core/shared/src/main/scala/kyo/scheduler/IOTask.scala:11-15`). The current
one carries `curr`, `running` (volatile), `parked`, `pendingJoin`
(`kyo-core/shared/src/main/scala/kyo/scheduler/IOTask.scala:11, 21, 26, 33`), and
unconditionally allocates the anonymous subclass that holds `context`
(`kyo-core/shared/src/main/scala/kyo/scheduler/IOTask.scala:236-237`), where the baseline
factory allocates it only when the context is non-empty
(`origin/main:kyo-core/shared/src/main/scala/kyo/scheduler/IOTask.scala:209-214`). Section 5.2
shows `parked` goes and `running` changes type, but `pendingJoin` stays.

**Changed from r1:** r1 asserted that all three new fields can go, and that `parked` and
`pendingJoin` "are slice-local and can be locals in `run` if the boundary clause expands
there". That is wrong for `pendingJoin`. Scala boxes a local `var` that a nested `def` reads
or writes, and `ArrowEffect.handlePartial` expands to a local `@tailrec def partialLoop`
(`kyo-kernel2/shared/src/main/scala/kyo/kernel/ArrowEffect.scala:226-239`) whose body is where
the inlined clause lands. A `var joined` declared in `run` and assigned by the clause is
therefore captured by `partialLoop` and heap-boxed into an `ObjectRef`, one allocation per
slice. A field costs four bytes once; the local costs an allocation on every slice. The field
wins, and r1's field-count claim was arithmetic done without the capture rule. Section 5.2
restates the layout honestly: parity with the baseline, not an improvement.

**(b) `running: Safepoint` was a mechanism the kernel has since deleted.** The interim
Safepoint at `23cb1d551d` was a two-state machine over a `Home` indirection with `depth`,
`deadline`, and `masked` fields and `openDrive`/`closeDrive` save-restore
(`git show 23cb1d551d:kyo-kernel2/shared/src/main/scala/kyo/kernel/internal/Safepoint.scala`).
The published `running` reference was the channel by which a requester reached that machine.
Commit `2ccf3807e3` replaced all of it with a `Stop` wrapper riding the slot array, and
`770ee98b04` removed the last callback. The object `running` names no longer exists. What
replaces it is one word holding a `Thread`, which is what `Safepoint.stop` takes
(`kyo-kernel2/shared/src/main/scala/kyo/kernel/internal/Safepoint.scala:158`); see 5.3.

**(c) The drive cannot make progress on a computation containing regions.**
`ArrowEffect.handlePartial` answers suspensions whose tag matches
(`kyo-kernel2/shared/src/main/scala/kyo/kernel/ArrowEffect.scala:228-231`) and steps
`Kyo.Defer` nodes (`ArrowEffect.scala:232-236`); everything else falls to `case v => v`
(`ArrowEffect.scala:237-238`). A `Kyo.Handled` region node
(`kyo-kernel2/shared/src/main/scala/kyo/kernel/internal/KyoInternal.scala:106-127`) is
"everything else". Any fiber whose body contains a handler (`Abort.run`, `Env.run`, `Var.run`,
any `handle*` call) has a `Handled` node at or near its head, so `IOTask.eval` as written
(`kyo-core/shared/src/main/scala/kyo/scheduler/IOTask.scala:72-107`) would answer nothing and
return `curr` unchanged, forever. `kyo-kernel2/CONTRIBUTING.md:96` states the same thing from
the other side: "`Eval.partial` stays alongside `handlePartial` because the eager driver parks
at region nodes by design, so evaluating regions without throwing on a miss needs the
evaluator entry." The port used the wrong entry point.

**Changed from r1:** r1 cited `ArrowEffect.scala:221-241` and `Eval.scala:32-35` for this.
Both line ranges are stale. `handlePartial` now lives at `ArrowEffect.scala:223-242` and no
longer carries an explicit region-node arm at all (it reaches `case v => v`), and the comment
r1 quoted from `Eval.scala` was moved into `kyo-kernel2/CONTRIBUTING.md:96` when `Eval.partial`
was rewritten.

**(d) `Abort` handling by post-hoc head inspection does not survive the region model.**
`completeAbort` calls `dispatchFirst` on the returned remainder to detect an unhandled `Abort`
at the head (`kyo-core/shared/src/main/scala/kyo/scheduler/IOTask.scala:168-177`), and
`ensureInterrupt` does the same for a `Join`
(`kyo-core/shared/src/main/scala/kyo/scheduler/IOTask.scala:194-198`). Both are head-only
walks. In kernel2 an unhandled suspension inside `Eval.partial` is reified by
`rebuild(hs, Empty, v)`
(`kyo-kernel2/shared/src/main/scala/kyo/kernel/internal/Eval.scala:49`), which wraps it in one
region node per surviving layer (`Eval.scala:241-251`). The suspension is therefore at the
bottom of a stack of region nodes, not at the head, whenever the fiber has any handler
installed. Head inspection cannot see it. The correct form for the `Abort` case is a handler
layer (section 5.1); the correct form for the `Join` case is a region-peeling walk, which is
the one kernel API this integration adds (section 5.5).

**Changed from r1:** r1 cited `rebuildFrom(0, v, hs, exits)` at `Eval.scala:85` and
`Eval.scala:239-258`. There is no `rebuildFrom` and no `exits` chunk at HEAD. The residual is
built by `rebuild(top, stop, acc)` (`Eval.scala:241-251`), which walks the immutable spine of
`Handlers` cells (`kyo-kernel2/shared/src/main/scala/kyo/kernel/internal/Handlers.scala:9-48`)
rather than indexing two parallel chunks. The conclusion is unchanged; the mechanism is not.

**(e) The fatal-unwind guarantee was dropped silently.** The baseline wraps the slice's `eval`
in a `catch` that, on a `Throwable` escaping it, runs the finalizers with the promise's error,
releases the trace, clears `curr`, and only then re-propagates
(`origin/main:kyo-core/shared/src/main/scala/kyo/scheduler/IOTask.scala:129-144`). Its comment
states the stake: "The task's promise is already completed with a Panic, but its finalizers
would be skipped, stranding whatever resource or awaited promise they release." The current
file has no such arm: `run` wraps `eval` in `try ... finally { running = null; sp.endSlice() }`
(`kyo-core/shared/src/main/scala/kyo/scheduler/IOTask.scala:120-124`), so a fatal leaves `run`
without `finish` ever being called. The port did not replace this guarantee with anything, and
neither commit message mentions removing it. Section 4.3 shows that under the `Kyo.Bracket`
model this path cannot be reconstructed by IOTask at all, and becomes a requirement on the
kernel.

Two further items are honest deferrals rather than defects, but they are open: `fiberTrace` now
renders `curr.toString` (`kyo-core/shared/src/main/scala/kyo/scheduler/IOTask.scala:53-63`) and
its five tests are `.ignore`d with the reason "fiberTrace needs a frame walk over the chain on
the new kernel; deferred"
(`kyo-core/shared/src/test/scala/kyo/scheduler/IOTaskTest.scala:12-13, 38, 57-59, 81-83,
106-108`); and `finish`/`finalizeBracket`
(`kyo-core/shared/src/main/scala/kyo/scheduler/IOTask.scala:182-187`) is a call into a kernel
bracket protocol that no longer exists.

### 1.4 What changed in the kernel since r1, and why each change matters here

This section exists because r1's analysis rested on kernel mechanisms that are gone. Each
entry names the change, the citation at HEAD, and the section of this document that reworks
around it.

1. **The `Safepoint` slot table is `Slots + 1` entries of `Thread | Stop` plus a parallel
   `Array[State]` of packed `Int` budgets** (`Safepoint.scala:21-29`), where `Slots` defaults
   to 65536 through a `StaticFlag` (`Safepoint.scala:33-38`), not 8192 as r1 stated and not the
   `Array[Long]` r1 cited. `stopped` is now `consumeStopped` (`Safepoint.scala:174-180`).
   Reworked in 3.1 and 3.5.
2. **A pending `Stop` now converts into a budget drain at the next slot resolution.**
   `resolve` drains an armed slot whose entry is a `Stop` (`Safepoint.scala:101-102`), and both
   `get()` (`Safepoint.scala:71-76`) and the fused `enter()` (`Safepoint.scala:124-130`) route
   through `resolve` whenever the fast-path identity test fails, which a `Stop` wrapper
   guarantees. This is the single largest change to the preemption story and it makes the
   latency bound one step rather than 512. Reworked in 3.3.
3. **`Safepoint` gained an `Armed` state bit** (`Safepoint.scala:42, 61-62`) set by
   `Eval.partial` (`Eval.scala:28`) and preserved across a drain (`Safepoint.scala:59`), which
   is what scopes the drain to fiber drives and leaves ordinary synchronous evaluation alone.
   Used in 3.1 and 3.3.
4. **`Safepoint.enter()` is fused**: it resolves the slot and consumes one budget step in one
   call, returning a negative sentinel when denied, tested by `slot.entered`
   (`Safepoint.scala:111-130`). The two-step `get()` plus `enter(slot)` API remains for the
   evaluator loop (`Safepoint.scala:132-133`, used at `Eval.scala:190`). Pinned by
   `kyo-kernel2/shared/src/test/scala/kyo/kernel/internal/SafepointTest.scala:51-66`. Used in
   3.3.
5. **`Pending.map` and `flatMap` were restructured**: the evaluation body is a local `run` def
   per site, the anonymous `Arrow.Transform` delegates to it, and the eager entry calls `run`
   directly with no `Transform` allocation
   (`kyo-kernel2/shared/src/main/scala/kyo/kernel/Pending.scala:26-52` and `54-80`). There is
   no `mapLoop`. The budget entry is `Safepoint.enter()` at `Pending.scala:40` and the rescue
   is `Kyo.Defer(v, arrow.chain(next))` at `Pending.scala:41-42`. Used in 3.3.
6. **`Eval` is a different evaluator.** `evalLoop(v0, slot, partial)` runs one flat `@tailrec`
   loop over an immutable spine of `Handlers` cells (`Eval.scala:35-119`), with
   `Kyo.Handled`/`Kyo.HandledState` region nodes (`Eval.scala:99-112`), a `Handler.Cont` and
   `Handler.Loop` split (`Eval.scala:70-92`), `rebuild` over cells (`Eval.scala:241-251`), and
   `RebuiltNode`/`RebuiltStateNode` cell reuse (`Eval.scala:101-104, 109-110, 224-239`). The
   `Handlers.indexOf` and `hs.take(idx)` operations r1 built its core idea on do not exist;
   the equivalent is `Handlers.find` (`Handlers.scala:37-46`) and `node.prev`. Reworked in 4.1
   and 5.1.
7. **`Eval.partial` reads the stop marker at exactly two places**: once at entry, before any
   work (`Eval.scala:25`), and in the `Kyo.Defer` arm of the loop (`Eval.scala:94`).
   `ArrowEffect.handlePartial` reads it in its own `Defer` arm (`ArrowEffect.scala:233`).
   Used in 3.1.
8. **An unhandled suspension whose root is `Kyo.Defaulted` resumes with its default instead of
   parking** (`Eval.scala:44-47`, `KyoInternal.scala:39-41`), which is how optional context is
   expressed (`ContextEffect.scala:37-47, 86-93`). It carries a maintainer TODO at
   `KyoInternal.scala:38` and a parked task. Noted in 5.7 because it changes what "unhandled"
   means at the boundary.
9. **New regression pins exist for deferring nested values at a denied safepoint**
   (`kyo-kernel2/shared/src/test/scala/kyo/kernel/PendingTest.scala:159-203`), whose helper
   drains the budget deterministically (`PendingTest.scala:166-171`). They pin that a denied
   safepoint defers the wrapped value rather than the unnested payload. Relevant to 5.4 only as
   the reason `run` must never unnest a remainder before storing it.

---

## 2. Baseline: IOTask on `origin/main`

### 2.1 Field inventory and the footprint techniques

The shipped IOTask declares three fields and inherits two
(`origin/main:kyo-core/shared/src/main/scala/kyo/scheduler/IOTask.scala:11-15`):

| Field | Declared at | Size (compressed oops) |
|---|---|---|
| `state` | `origin/main:kyo-core/shared/src/main/scala/kyo/scheduler/IOPromise.scala:15`, on a class that extends `Safepoint.Interceptor` (line 13) | 4 |
| `state: Task.State` | `kyo-scheduler/shared/src/main/scala/kyo/scheduler/Task.scala:6` | 4 (Int) |
| `curr` | `origin/main:kyo-core/shared/src/main/scala/kyo/scheduler/IOTask.scala:12` | 4 |
| `trace` | `origin/main:kyo-core/shared/src/main/scala/kyo/scheduler/IOTask.scala:13` | 4 |
| `finalizers` | `origin/main:kyo-core/shared/src/main/scala/kyo/scheduler/IOTask.scala:14` | 4 |

The build runs tests with `-XX:+UseCompactObjectHeaders` (`build.sbt:210`), so the header is 8
bytes. 8 + 20 = 28, aligned to **32**.

Five footprint techniques are visible, and they are the standard this design is held to:

1. **The context costs no field in the common case.** The factory branches on emptiness:
   `if ctx.isEmpty then new IOTask(curr, trace, finalizers) else new IOTask(...){ override def context = ctx }`
   (`origin/main:kyo-core/shared/src/main/scala/kyo/scheduler/IOTask.scala:209-214`), with the
   base class exposing `context` as a plain `def` returning `Context.empty` (line 19). A
   context-free fiber has no context field at all, and a fiber that has one lands its captured
   reference in the four bytes of alignment padding the base layout already wastes, so the
   object stays at 32 either way. The current worktree file always allocates the subclass
   (`kyo-core/shared/src/main/scala/kyo/scheduler/IOTask.scala:236-237`).
2. **Preemption and scheduling priority share one Int, without atomics.** `Task.State` packs
   runtime in bits 0-30 and the preempt flag in bit 31, and the scaladoc is explicit that the
   flag is a bit-set rather than a negation "so it works for every runtime including 0"
   (`kyo-scheduler/shared/src/main/scala/kyo/scheduler/Task.scala:86-91`). Lost updates from
   the three concurrent writers are tolerated by design, with each read-modify-write writer
   re-asserting the interrupt reset (`Task.scala:8-17`, `Task.scala:43-49`, and the scaladoc at
   `Task.scala:62-74`).
3. **`Finalizers` is an opaque union, not a collection.**
   `Absent.type | Finalizer | ArrayDeque[Finalizer]`
   (`origin/main:kyo-core/shared/src/main/scala/kyo/scheduler/Finalizers.scala:11-12`): zero and
   one finalizer cost no wrapper at all, and the many case draws its `ArrayDeque` from a pooled
   `MpmcUnsafeQueue` and returns it after running (`Finalizers.scala:20-23, 54-67`).
4. **`IOPromise.state` is an opaque union too**:
   `Result[E, A] | Pending[E, A] | Linked[E, A]`
   (`kyo-core/shared/src/main/scala/kyo/scheduler/IOPromise.scala:298`, identical on both
   sides), so a promise's result, its waiter list, and its link all share one field.
5. **The trace is a pooled fixed-size ring, not a growing log.** `Trace` holds `Array[Frame]`
   of `maxTraceFrames = 16`
   (`origin/main:kyo-kernel/shared/src/main/scala/kyo/kernel/internal/Trace.scala:22-29`), and
   IOTask returns it to the pool with `safepoint.releaseTrace(trace)` on every termination path
   (`origin/main:kyo-core/shared/src/main/scala/kyo/scheduler/IOTask.scala:140-142, 156-158`).

The conclusion for section 5: the baseline's answer to "where does this state live" is never
"add a field". It is a union, a bit-pack, a pool, or a conditional subclass. A design that adds
a field must say why the four alternatives do not apply.

Two exit paths of the baseline `run` also belong in the inventory, because the port dropped
both and the new design has to decide about them explicitly. `run` drains the finalizers with
the promise's error and releases the trace on the normal termination path
(`origin/main:kyo-core/shared/src/main/scala/kyo/scheduler/IOTask.scala:153-158`) and again on
the fatal-unwind path
(`origin/main:kyo-core/shared/src/main/scala/kyo/scheduler/IOTask.scala:129-144`). The third
drain site is the interrupt-while-queued case, which reaches the same normal-termination block
through `!isPending()` at line 145.

### 2.2 The eval loop and how preemption reached it

`IOTask.eval` calls a two-tag `handlePartial`
(`origin/main:kyo-kernel/shared/src/main/scala/kyo/kernel/ArrowEffect.scala:621-661`) with an
explicit `stop` thunk
(`origin/main:kyo-core/shared/src/main/scala/kyo/scheduler/IOTask.scala:70-76`):

```scala
stop = shouldPreempt() || (deadline != Long.MaxValue && clock.currentMillis() > deadline) || needsInterrupt()
```

`partialLoop` evaluates `if stop then v` at the top of every iteration
(`origin/main:kyo-kernel/shared/src/main/scala/kyo/kernel/ArrowEffect.scala:636`), so all three
conditions are re-read on every dispatch and every `Defer` step. That is one coarse preemption
check per operation, and the comment at
`origin/main:kyo-core/shared/src/main/scala/kyo/scheduler/IOTask.scala:72-75` records the
ordering rationale: the authoritative interrupt read comes last so a step stopping for
preemption or the deadline skips it.

A second, finer check runs underneath it. `IOPromise` extends `Safepoint.Interceptor`
(`origin/main:kyo-core/shared/src/main/scala/kyo/scheduler/IOPromise.scala:13`, with no-op
defaults at lines 20-22), and IOTask overrides `enter`
(`origin/main:kyo-core/shared/src/main/scala/kyo/scheduler/IOTask.scala:21-22`):

```scala
final override def enter(frame: Frame, value: Any) = !shouldPreempt()
```

The slice installs the task as the thread's interceptor for its duration:
`Isolate.internal.restoring(trace, this)`
(`origin/main:kyo-core/shared/src/main/scala/kyo/scheduler/IOTask.scala:69`) is
`Safepoint.immediate(interceptor)(safepoint.withTrace(trace)(v))`
(`origin/main:kyo-kernel/shared/src/main/scala/kyo/kernel/Isolate.scala:234-240`), and
`immediate` saves and restores the previous interceptor around the block
(`origin/main:kyo-kernel/shared/src/main/scala/kyo/kernel/internal/Safepoint.scala:107-123`).
The kernel then consults it at every guarded step through `Safepoint.enter`
(`origin/main:kyo-kernel/shared/src/main/scala/kyo/kernel/internal/Safepoint.scala:30-45`, the
`state.hasInterceptor && interceptor.enter(...)` conjunct) and, on refusal, rescues into
`Effect.defer(suspend)`
(`origin/main:kyo-kernel/shared/src/main/scala/kyo/kernel/internal/Safepoint.scala:206-215`).
So the running task polls its own preempt bit at frame granularity, with no cross-thread signal
and no per-frame extra load beyond the interceptor check the Safepoint already performs.

The same interceptor is how finalizers reach the task: `addFinalizer` and `removeFinalizer` are
`Interceptor` methods that IOTask overrides to push into its own `finalizers` field
(`origin/main:kyo-core/shared/src/main/scala/kyo/scheduler/IOTask.scala:32-36`), fed by
`Safepoint.ensure`
(`origin/main:kyo-kernel/shared/src/main/scala/kyo/kernel/internal/Safepoint.scala:168-193`).
One mechanism carries preemption polling, trace ownership, and finalizer registration. It is
ruled dead and will not be ported, so all three concerns need separate answers, which is what
sections 5.3, 5.8, and 4.3 give.

Four properties follow, and they are what the new design is measured against:

- **Delivery is never lost, because there is no delivery.** The coordinator sets a bit on the
  task (`Task.doPreempt`, `kyo-scheduler/shared/src/main/scala/kyo/scheduler/Task.scala:8-17`);
  the task reads the bit itself. No thread identity is involved, so worker migration is a
  non-question.
- **Time slicing works on every platform.** The JS and Wasm scheduler passes a real deadline
  (`kyo-scheduler/js-wasm/src/main/scala/kyo/scheduler/Scheduler.scala:12, 17-18`) and the
  `stop` thunk reads the clock. JVM and Native pass `Long.MaxValue`
  (`kyo-scheduler/jvm-native/src/main/scala/kyo/scheduler/Worker.scala:382`) and rely on the
  coordinator's bit.
- **Preemption latency is bounded by one operation**, not by a budget period.
- **Finalizers run on every exit**, including the fatal one (2.1).

---

## 3. Preemption delivery analysis

### 3.1 The mechanism at HEAD, stated exactly

**Changed from r1:** this whole subsection is rewritten. Every mechanism r1 described was
replaced.

- A slot is an index into two parallel arrays sized `Slots + 1`: `depths: Array[State]`, where
  `State` is a packed `Int`, and `slots: AtomicReferenceArray[Thread | Stop]`
  (`kyo-kernel2/shared/src/main/scala/kyo/kernel/internal/Safepoint.scala:23-28`). `Slots`
  comes from a `StaticFlag` defaulting to 65536 and constrained to a power of two
  (`Safepoint.scala:33-38`).
- `State` packs a depth countdown against a `DepthGuard` bit at 1 << 15 and an `Armed` bit at
  1 << 30, initialised to `DepthGuard | period()` with `period` a `StaticFlag` defaulting to
  512 (`Safepoint.scala:31, 40-46`). `enterInto` decrements and parks the budget when the guard
  bit clears (`Safepoint.scala:49-56`, `Safepoint.scala:135-138`).
- `Safepoint.get()` computes `home(thread) = (threadId * 8) & (Slots - 1)` and returns that
  index when the entry is the current thread, else calls `resolve`
  (`Safepoint.scala:68-76`). `resolve` prefers a `ThreadLocal` cache of the thread's slot and
  otherwise linear-probes and claims, degrading to the `Overflowed` sentinel slot after `Slots`
  probes (`Safepoint.scala:78-109`).
- **`resolve` drains an armed slot that has a `Stop` pending**: `if depths(slot).isArmed &&
  slots.get(slot).isInstanceOf[Stop] then depths(slot) = depths(slot).drained`
  (`Safepoint.scala:101-102`). `drained` keeps `Armed` and resets to bare `DepthGuard`
  (`Safepoint.scala:59`), which makes the next `enterInto` fail.
- `Safepoint.enter()` is the fused entry: resolve plus one budget step, returning `Denied = -1`
  when refused, tested by the `entered` extension (`Safepoint.scala:111-130`).
  `Safepoint.enter(slot)` is the two-step form used by the evaluator (`Safepoint.scala:132-133`,
  called at `Eval.scala:190`).
- `Safepoint.stop(thread)` guards on `thread.isAlive()`, re-derives `home`, probes for the
  thread's slot, and CASes the entry from the bare `Thread` to `new Stop(thread)`; an
  already-wrapped entry returns true, and a thread owning no slot returns false after `Slots`
  probes (`Safepoint.scala:158-172`).
- `Safepoint.consumeStopped(slot)` is a one-shot consume: it plain-writes the bare thread back
  and returns true (`Safepoint.scala:174-180`).
- `Eval.partial` arms the slot for the duration of the drive and restores the caller's state on
  exit (`Eval.scala:23-33`). It reads the stop marker at entry, before doing any work
  (`Eval.scala:25`), and in the `Kyo.Defer` arm of the loop (`Eval.scala:94`).
  `ArrowEffect.handlePartial` reads it in its own `Defer` arm (`ArrowEffect.scala:233`).
- `Eval.apply` never reads it (`Eval.scala:15-21`), so synchronous evaluation ignores stop
  requests and, critically, does not consume them. It does `save`/`restore` around the loop
  (`Eval.scala:16-19`), so the ambient budget state survives a nested synchronous evaluation.

`SafepointTest` pins the wrap-and-consume contract, its idempotence, that `get` resolves the
owning slot while a stop is pending, that a thread which never evaluated is missed, and that
the fused and two-step entries share one budget cell
(`kyo-kernel2/shared/src/test/scala/kyo/kernel/internal/SafepointTest.scala:10-66`).

### 3.2 Mapping the scheduler's preemption decision onto it

There are three producers of a preemption decision today.

**Time slicing on JVM and Native.** `Worker.checkStalling` runs on whichever thread calls
`checkAvailability` (the coordinator), reads `currentTask` and `taskStartMs`, and calls
`task.doPreempt()` when the task has been running longer than the time slice
(`kyo-scheduler/jvm-native/src/main/scala/kyo/scheduler/Worker.scala:246-263`). The worker's own
mounted thread is a field on the same object,
`kyo-scheduler/jvm-native/src/main/scala/kyo/scheduler/Worker.scala:102`. So the decision site
already holds both the task and the thread that is running it.

**Changed from r1:** r1 cited `Worker.mount` at `Worker.scala:269` and omitted the
`jvm-native/` path segment on every `Worker`, `BlockingMonitor`, and `Scheduler` citation. Line
269 is inside `run()`; `mount` is declared at line 102. Every scheduler citation in this
document carries the full path.

**Interrupts.** `IOTask.onComplete` fires on the interrupter's thread after
`IOPromise.interrupt` CASes the state
(`kyo-core/shared/src/main/scala/kyo/scheduler/IOPromise.scala:196-202`), and it calls
`doPreempt()` plus `resetRuntime()`
(`kyo-core/shared/src/main/scala/kyo/scheduler/IOTask.scala:35-45`). The interrupter does not
know which worker thread, if any, is running the task. The scheduler does:
`BlockingMonitor.collect` snapshots `(worker.mountId, worker.currentTask)` per worker
(`kyo-scheduler/jvm-native/src/main/scala/kyo/scheduler/BlockingMonitor.scala:196-208`) and
`process` dispatches `mount.interrupt()` under an `interruptLock` with a
`worker.currentTask eq task` re-check
(`kyo-scheduler/jvm-native/src/main/scala/kyo/scheduler/BlockingMonitor.scala:230-251`).
`Scheduler.notifyInterrupt` bumps the epoch and wakes the monitor for an immediate scan
(`kyo-scheduler/jvm-native/src/main/scala/kyo/scheduler/Scheduler.scala:134-140`), and
`IOTask.onInterrupted` already calls it
(`kyo-core/shared/src/main/scala/kyo/scheduler/IOTask.scala:67-68`). The monitor's dispatch is
gated on `blocked` (`BlockingMonitor.scala:237`), so a CPU-bound interrupted fiber gets nothing
from it.

The interrupt path is the one with a hard latency requirement, and it is the one r1 got wrong.
Under kernel2 nothing in the drive reads `isPending()`: `Eval.partial` is driven only by the
thread's stop marker. So for a CPU-bound interrupted fiber, the stop request is the *only*
thing that stops it, and its delivery latency *is* the interrupt latency. Routing it through
the BlockingMonitor makes that latency the monitor's scan period, and routing it through
`Worker.checkStalling` makes it a full time slice. The baseline's latency is one operation
(2.2). Section 5.3 resolves this.

**Time slicing on JS and Wasm.** There is no second thread. `Scheduler.schedule` runs the task
from a macrotask with `deadline = now + timeSlice` and reschedules on `Task.Preempted`
(`kyo-scheduler/js-wasm/src/main/scala/kyo/scheduler/Scheduler.scala:15-20`). Nobody can call
`Safepoint.stop` while the task runs, and a self-issued stop before entry would be consumed by
`Eval.partial`'s entry check (`Eval.scala:25`) without any work being done. **Under the kernel
at HEAD there is no mechanism by which a JS or Wasm fiber that does not suspend out of band can
be preempted.** This is ruling R1 in section 9, and section 3.7 gives a concrete proposal that
fits the current Safepoint.

### 3.3 Latency bound

**Changed from r1:** this subsection reverses r1's headline number. r1 concluded "at most 512
strict steps plus the current fused segment". Under the drain conversion added at
`Safepoint.scala:101-102`, the bound for ordinary map-driven code is **one settled step**.

Trace it. A stop lands: `Safepoint.stop` CASes `slots(i)` from the bare `Thread` to a `Stop`
(`Safepoint.scala:165`). The running thread is inside `Eval.partial`, so its slot is armed
(`Eval.scala:28`). The next settled `map` step calls `Safepoint.enter()`
(`Pending.scala:40`), whose fast-path test `slots.get(h) eq thread` now fails because the entry
is a `Stop` (`Safepoint.scala:127`), so it calls `resolve`, which finds the cached slot, sees
armed plus `Stop`, and drains the budget (`Safepoint.scala:98-103`). `enterInto` then fails
(`Safepoint.scala:49-56`), `enter()` returns `Denied`, `slot.entered` is false
(`Safepoint.scala:117`), and `map` mints `Kyo.Defer(v, arrow.chain(next))`
(`Pending.scala:41-42`) instead of executing. The evaluator's next iteration takes the `Defer`
arm, `Safepoint.consumeStopped(slot)` returns true, and the residual is `rebuild(hs, Empty, v)`
(`Eval.scala:93-95`). One settled step from the CAS to the park.

The same drain reaches `flatMap` (`Pending.scala:68`), `andThen` (`Pending.scala:95`), `unit`
(`Pending.scala:122`), `flatten` (`Pending.scala:283`), and `Eval.evalChain`, which calls
`Safepoint.get()` once per invocation (`Eval.scala:187`). It does not reach
`Safepoint.enter(slot)` (`Eval.scala:190`) because that form takes an already-resolved slot,
but `evalChain` has already paid the `get()` at entry, so the drain is observed there.

Exactly one `Kyo.Defer` is minted per stop, because the `Defer` arm calls `Safepoint.reset(slot)`
before continuing (`Eval.scala:97`), restoring a full period.

**There is one shape with no bound at all, and `Async.Join` is that shape.**
`ArrowEffect.suspendWith` carries an explicit "no budget check" comment on its settled arm
(`ArrowEffect.scala:49-51`), reasoning that `f` either suspends and returns flat, or settles
into chained arrows "whose strict segments carry their own checks in map". The first disjunct
is the hole. Trace it against the current sources:

1. A handler answers a suspension with a settled value, and the evaluator resumes through
   `resume(kyo.cont, answer)` (`Eval.scala:65` for the stateful arm, `Eval.scala:83` for the
   stateless one).
2. For a bare `suspendWith` node, `cont` is the node itself (`ArrowEffect.scala:41`), which
   extends `Arrow.Transform`, so it is not an `Arrow.AndThen` and `resume` takes its second
   branch: `val step = cont.step; step.head(v, step.tail)` (`Eval.scala:182-184`). For a
   `Transform`, `step` is the node, `head` is the node, and `tail` is `identity`
   (`kyo-kernel2/shared/src/main/scala/kyo/Arrow.scala:50, 68-72`).
3. The node's settled arm computes `f(res)` and hands it to `identity`, which returns it
   unchanged when `next eq this` (`Arrow.scala:36-40`, `ArrowEffect.scala:52-54`).
4. If `f(res)` is another `suspendWith` suspension, the loop takes another turn having touched
   neither `Safepoint.enter` nor `Safepoint.get` nor `Kyo.Defer`.

No Safepoint entry point runs, so the stop never converts into a drain and is never read. The
fiber is not preemptible for as long as the loop runs. `evalLoop` is `@tailrec`
(`Eval.scala:39`), so this is not a stack-safety problem; it is purely a preemption-liveness
one.

**Changed from r1:** r1 illustrated this with `Var` and listed `Async.useResult` among the
`suspendWith` users. The stronger statement is the one that matters here:
`Async.useResult` is `ArrowEffect.suspendWith[A](Tag[Join], input)(f)`
(`kyo-core/shared/src/main/scala/kyo/Async.scala:823`), and every `Async` join in the system
goes through it (`Async.scala:807-815`). The boundary handler this design installs answers
`Join` operations, so a fiber that repeatedly joins already-completed promises with no settled
`map` between the joins runs a dispatch loop that consumes no budget and reads no stop marker.
That is not a hypothetical shape; it is `Fiber.get` on a completed fiber, `Channel.take` on a
non-empty channel, and `Promise.get` on a completed promise, in a loop. The hole is directly on
IOTask's critical path, which is why section 3.7 proposes a resolution rather than leaving it
open.

`ContextEffect.suspendWith` (`ContextEffect.scala:30-35`) and `Abort.error`
(`kyo-prelude/shared/src/main/scala/kyo/Abort.scala:70-71`) are the other `suspendWith` users
in the stack.

### 3.4 Worker migration between slices

A task's slices can run on different worker threads: `Worker.run` polls its own queue and
steals from others
(`kyo-scheduler/jvm-native/src/main/scala/kyo/scheduler/Worker.scala:286-296`), and a preempted
task is requeued and may be picked up elsewhere
(`kyo-scheduler/jvm-native/src/main/scala/kyo/scheduler/Worker.scala:303-334`).

This is benign under the HEAD mechanism, for a structural reason. The stop marker is per
thread, and it is consumed at `Eval.partial`'s entry before any work happens
(`Eval.scala:25`), returning the input value unchanged. So a marker that outlives its intended
victim can only cause a slice that does nothing and returns `Task.Preempted`. Nothing is lost,
because the residual returned is the input.

The liveness consequence is that the intended victim does not yield when it was supposed to.
For time slicing that is bounded and self-correcting: `Worker.checkStalling` re-issues
`doPreempt` on every availability check while the task is past its slice
(`kyo-scheduler/jvm-native/src/main/scala/kyo/scheduler/Worker.scala:246-263`), so the request
is re-aimed at the thread the fiber is now on, one tick later. Under the design in 5.3 that
re-issue also re-delivers the stop, because the stop rides `doPreempt`.

### 3.5 The stale-stop question, argued

**Claim: a stop consumed by an unrelated later evaluation on the same thread is benign, not a
correctness bug.** The proof has three parts.

1. **The request carries no payload.** `Stop` holds only the thread (`Safepoint.scala:19`), and
   `consumeStopped` returns a bare `Boolean` (`Safepoint.scala:174-180`). So consuming it cannot
   mis-deliver information; it can only cause a yield.
2. **A yield is always safe, because the residual is the standing computation.** In the entry
   case the residual is literally the input (`Eval.scala:25`). In the `Defer` case it is
   `rebuild(hs, Empty, v)` (`Eval.scala:95`), which reconstructs one region node per open cell
   around the standing value (`Eval.scala:241-251`) and which the evaluator re-enters by
   identity on the next drive (`Eval.scala:101-104`, see 4.1). `EvalTest` pins this by parking a
   100000-step computation on a pending stop and finishing it with a second `Eval.partial` call
   (`kyo-kernel2/shared/src/test/scala/kyo/kernel/internal/EvalTest.scala:222-231`), and by
   parking mid-evaluation between defers (`EvalTest.scala:245-255`) and inside a stateful region
   with its advanced state (`EvalTest.scala:315-326`). No step executes twice and no step is
   skipped.
3. **Nothing depends on the stop for correctness.** Interruption is anchored on `IOPromise`'s
   CAS, not on the stop: `needsInterrupt()` is `!isPending()`
   (`kyo-core/shared/src/main/scala/kyo/scheduler/IOTask.scala:50-51`), read from the promise
   state that `interrupt` CASes before firing any callback
   (`kyo-core/shared/src/main/scala/kyo/scheduler/IOPromise.scala:196-202`). Time-slice
   preemption is a fairness mechanism whose only consumer is `Worker.run`'s requeue arm
   (`kyo-scheduler/jvm-native/src/main/scala/kyo/scheduler/Worker.scala:303`). Losing either
   costs latency, never a wrong result.

The cost is therefore exactly one wasted schedule per stale request, which is what the interim
design already accepted and priced as "at most one wasted re-schedule per tick per worker"
(`kernel2-preemption-design.md:651-656`).

Three corollaries worth stating because they are non-obvious:

- **`Eval.apply` neither reads nor consumes the marker** (`Eval.scala:15-21`). A stop landing
  while a thread is inside a synchronous `eval` (for example `Abort.run(v).eval` inside a fiber,
  or `IOPromise.block`'s completion callbacks) survives that whole evaluation and is consumed by
  whatever `Eval.partial` runs next on that thread. This widens the stale window but does not
  change the argument above.
- **A stop only drains a slot that is armed** (`Safepoint.scala:101`), and `arm` is called only
  by `Eval.partial` (`Eval.scala:28`) with the caller's state restored on exit
  (`Eval.scala:30`). So a stop cannot degrade ordinary synchronous evaluation into per-step
  `Defer` minting. This is the property that makes the drain conversion safe to have at all, and
  it is worth a test that does not exist today: a stop landing on a thread whose armed drive has
  already exited must leave the ambient budget intact.
- **`stop` on a live thread with no slot is a `Slots`-entry scan.** The probe walks the whole
  array before returning false (`Safepoint.scala:159-171`), and `Slots` defaults to 65536
  (`Safepoint.scala:33`). The `thread.isAlive()` guard at `Safepoint.scala:171` handles the dead
  case in one call, but a live thread that has never evaluated costs 65536 volatile reads on the
  requester's thread. **Changed from r1:** r1 priced this at 8192. Ruling R6 in section 9
  narrows it to a concrete fix.

### 3.6 Comparison with the baseline, in one table

| Property | `origin/main` | kernel2 at HEAD |
|---|---|---|
| Who observes the signal | the running task, reading its own bit | the running thread, reading its slot |
| Where | every guarded frame (`Safepoint.enter`) plus every `handlePartial` iteration | `Eval.partial` entry, every `Kyo.Defer` node, and implicitly at every `Safepoint.get`/`enter` through the drain |
| Latency bound, `map`-driven code | one operation | one settled step (3.3) |
| Latency bound, `suspendWith` dispatch loops | one operation | unbounded (3.3) |
| Cross-thread delivery | none needed | one CAS on the slot array, plus a probe |
| Wrong-victim possible | no | yes, benign (3.5) |
| Works on JS and Wasm | yes, via the deadline in the `stop` thunk | no (3.2) |
| Per-frame cost | one interceptor virtual call while an interceptor is installed | none |

**Changed from r1:** the latency row split in two and the ordinary-code half improved from 512
steps to one. The `suspendWith` half is unchanged and is now the only latency gap.

### 3.7 Proposals for the two coverage gaps

Both gaps are kernel-side changes. They are stated here as concrete proposals rather than open
questions, because research settles the mechanism; only the cost is a value judgement.

**Gap A, JS and Wasm time slicing.** The minimal shape that fits the current Safepoint is a
deadline armed alongside the `Armed` bit and checked at the point the budget is already
exhausted, `enterPark` (`Safepoint.scala:135-138`), which runs once per `period()` steps. On
expiry, `enterPark` self-installs the `Stop` for the current thread instead of merely draining,
so the already-minted `Kyo.Defer` is read by `Eval.partial`'s `Defer` arm
(`Eval.scala:93-95`) and parks the drive through the existing path. No new read site, no new
signal representation, and the mechanism the request travels on is the one that already exists,
which is what `kyo-kernel2/CONTRIBUTING.md:142-152` asks of any new device.

The deadline reaches the kernel as a parameter of the scheduler entry point:
`Eval.partial(v, deadlineMillis)`, with `Long.MaxValue` meaning unarmed. IOTask has the value
already: `Task.run(startMillis, clock, deadline)`
(`kyo-scheduler/shared/src/main/scala/kyo/scheduler/Task.scala:22`). The clock read must be
`java.lang.System.currentTimeMillis()` rather than the scheduler's `InternalClock`, because the
kernel cannot depend on kyo-scheduler; at one read per 512 steps that is cheaper than the
baseline, which reads the cached clock at every dispatch
(`origin/main:kyo-core/shared/src/main/scala/kyo/scheduler/IOTask.scala:76`). Cost on JVM and
Native: one `Long` compare against `Long.MaxValue` once per 512 steps, and one extra parameter
on `Eval.partial`.

**Changed from r1:** r1 proposed "a parallel `Array[Long]` of per-slot deadlines read at the
same two sites that already read the stop marker". Reading at the two stop sites does not work,
because the entry site runs once per slice and the `Defer` site only runs when a `Defer` exists,
which on JS is exactly what the deadline is supposed to cause. Arming at `enterPark` is the site
that actually fires.

**Gap B, the `suspendWith` dispatch loop.** The evaluator already holds the resolved slot as a
parameter (`Eval.scala:35`), and the arm that can loop without touching the budget is the
settled-answer resume (`Eval.scala:65` and `Eval.scala:83`). Charging that arm one budget step
against the slot it already holds closes the hole with no new mechanism: when the step is
refused, mint `Kyo.Defer(answer, kyo.cont)` and continue the loop with it, so the very next
iteration takes the existing `Defer` arm, reads the stop marker, and resets the budget
(`Eval.scala:93-98`). The result is one uniform property, "at most `period()` steps of any kind
between two stop reads", replacing today's two different bounds.

Cost: one `depths(slot)` read-modify-write per answered operation, in the arm
`kyo-kernel2/CONTRIBUTING.md:166` describes as the hot path that "allocates nothing beyond the
clause's outcome box", plus one `Kyo.Defer` allocation per 512 answered operations. This is a
change to `Eval.scala`, so `kyo-kernel2/CONTRIBUTING.md:168` requires a JMH A/B against a frozen
baseline with `-prof gc` before it can land. That measurement is the acceptance condition, and
it is the only reason this is presented as a proposal rather than a settled recommendation.

**Changed from r1:** r1 left this as open question Q2 with three unranked options, one of which
was "poll at the top of the drive loop" (`kernel2-preemption-design.md:493-496`). Polling at the
top of the loop costs a read on every iteration including the hot `map` and `Defer` arms;
charging the one arm that can loop without the budget costs the same read only where the loop is
possible.

---

## 4. Ensure, finalizers, and interrupts against a partial evaluation

### 4.1 Where the residual lives between slices, and what it costs

`curr` (`kyo-core/shared/src/main/scala/kyo/scheduler/IOTask.scala:11`). One reference field, as
in the baseline (`origin/main:kyo-core/shared/src/main/scala/kyo/scheduler/IOTask.scala:12`).
What changed is what that reference points at.

Baseline: the remainder is the continuation chain, and a park clears `curr` and re-stores it
from the wakeup callback as `curr = Sync.defer(cont(r))`
(`origin/main:kyo-core/shared/src/main/scala/kyo/scheduler/IOTask.scala:97-104`). One
allocation, independent of handler nesting.

The protocol difference matters for the race in 1.1. In the baseline the clause owns `curr`: it
nulls it and the wakeup callback installs the resumption, so there is no slice-start snapshot for
a racing completion to reschedule over. kernel2's `handlePartial` returns the remainder to the
caller instead, so `run` owns the store, and the window between the clause's poll and that store
is new. That is the window `23cb1d551d` closed by moving the wakeup registration after the store,
and it is why section 5.4 keeps that ordering.

kernel2: a residual is `rebuild(hs, Empty, v)`, one region node per open cell, allocated
innermost-first by walking the spine through `prev` (`Eval.scala:241-251`). So the storage cost is
O(open handler layers) allocations per park or per stop.

**Changed from r1:** r1 stopped there and priced the resume side as another O(layers). It is
cheaper than that. `rebuild` produces `RebuiltNode`/`RebuiltStateNode` wrappers that carry the
**original cell object** (`Eval.scala:224-239`), and the evaluator re-enters a rebuilt layer by
identity when it lands where it was built from:

```scala
case kyo: RebuiltNode if kyo.node.prev eq hs =>
    // the onion layer lands where it was built from, so
    // the original cell re-enters the stack as is
    loop(kyo.value, kyo.node)
```

(`Eval.scala:101-104`, and `Eval.scala:109-110` for the stateful form). Because the boundary
layer sits outermost (5.1), a residual is always rebuilt down to `Empty` and always re-entered
from `Empty`, so the outermost rebuilt node satisfies `kyo.node.prev eq hs` and every node below
it does too, by induction on the identity of the original `prev` chain. The whole spine re-enters
with zero cell allocations. The per-park cost is therefore O(layers) short wrapper objects
allocated once at the park, and nothing at the resume.

`EvalTest` pins that such a residual is resumable and that its unhandled suspension can be
answered by a handler installed later
(`kyo-kernel2/shared/src/test/scala/kyo/kernel/internal/EvalTest.scala:262-268`), that a
non-initial stateful state survives the park (`EvalTest.scala:275-283`), and that state advances
across repeated parks (`EvalTest.scala:422-430`). Those pins are the foundation section 5 rests
on.

**The residual trichotomy.** With the boundary `Join` layer installed outermost and its clause
producing either a settled `Loop.continue` or a re-raise of its own operation (5.1), the value
`Eval.partial` returns is always exactly one of three shapes:

1. **A settled value.** The fiber finished. `evalNow` reports it (`Pending.scala:146-149`).
2. **A region-node-headed residual.** The drive stopped. The head is the rebuilt boundary `Join`
   layer, because `hs` is non-empty for the whole drive: the first value the evaluator sees is
   the boundary `Kyo.Handled` node, which pushes a cell (`Eval.scala:99-106`), and the spine
   only returns to `Empty` when that cell pops on completion (`Eval.scala:113-117`).
3. **A bare `Kyo.Suspend[Async.Join]`.** The clause parked. The re-raise is a pending clause
   outcome, so the evaluator continues at `node.prev`, which is `Empty` for the outermost layer
   (`Eval.scala:74-75`, `Eval.scala:153-164`), the re-raised tag misses in an empty spine, and
   `rebuild(Empty, Empty, v)` returns `v` itself (`Eval.scala:49`, `Eval.scala:241-242`).

The trichotomy is what lets `run` distinguish park from stop with no type test on kernel
internals and no reason code from the drive: shape 3 is exactly what `handlePartial` answers, and
shapes 1 and 2 are exactly what it returns unchanged. A corollary worth stating: a `Kyo.Defer` can
never be at the head of a residual under this design, so `handlePartial`'s `Defer` arm
(`ArrowEffect.scala:232-236`) is unreachable from IOTask and never steps user code outside the
armed drive. Section 8 lists the test that must pin this, because no existing test covers the
configuration.

### 4.2 Interrupts interleaving with a partial evaluation

The invariant that makes this tractable is unchanged from the old kernel and is worth restating:
**the promise is the single source of truth**. `IOPromise.interrupt` CASes the state to an
`Error` and only then runs `onComplete()`, `onInterrupted()`, and the waiter flush
(`kyo-core/shared/src/main/scala/kyo/scheduler/IOPromise.scala:196-202`). `needsInterrupt()` and
`run`'s `isPending()` checks read that state
(`kyo-core/shared/src/main/scala/kyo/scheduler/IOTask.scala:50-51`,
`kyo-core/shared/src/main/scala/kyo/scheduler/IOTask.scala:110, 125`). A stop request that is
lost, stale, or aimed at the wrong thread delays observation; it cannot lose the interrupt.

Four interleavings, with what each needs from the design:

1. **Interrupt lands while the task is queued.** `run` observes `!isPending()` at entry and must
   settle the retained remainder without driving user code
   (`kyo-core/shared/src/main/scala/kyo/scheduler/IOTask.scala:109-114`).
2. **Interrupt lands mid-slice.** The drive returns some residual. `run` re-checks `isPending()`
   and, on failure, must treat the residual (not the slice-start `curr`) as the accurate
   remainder, because the residual's head is the operation the drive stopped in front of
   (`kyo-core/shared/src/main/scala/kyo/scheduler/IOTask.scala:125-130`, whose comment states
   exactly this, and the baseline's fuller version at
   `origin/main:kyo-core/shared/src/main/scala/kyo/scheduler/IOTask.scala:146-150`).
3. **Interrupt lands while parked on a join.** The cascade link registered at park time
   propagates the interrupt to the awaited promise through `IOPromise.interrupts`
   (`kyo-core/shared/src/main/scala/kyo/scheduler/IOPromise.scala:64-75`), whose completed-state
   arm interrupts the other promise immediately.
4. **Interrupt lands while the drive is stopped in front of a `Join` it has not yet reached.**
   The link does not exist, because `joinInput(this)` runs inside the clause and the clause has
   not run. The awaited promise is left without the cascade.

**Changed from r1:** r1 claimed case 4 "cannot arise" under a boundary handler layer, "because
the link is registered at the operation rather than reconstructed afterwards, so `ensureInterrupt`
and its `dispatchFirst` disappear". That is wrong, and it is the most consequential error in r1.
Registering at the operation closes case 3, not case 4. Case 4 is precisely the case the
baseline's `ensureInterrupt` exists for, named in its own comment: "Handle race when interrupted
before processing Async.Join and linking interrupts"
(`origin/main:kyo-core/shared/src/main/scala/kyo/scheduler/IOTask.scala:169`), and the baseline's
`run` names the same shape when it explains why it walks `next` rather than `curr`
(`origin/main:kyo-core/shared/src/main/scala/kyo/scheduler/IOTask.scala:146-150`).

Case 4 is not covered by creation-time linking either. `IOTask.apply` links a parent to a child
it creates (`origin/main:kyo-core/shared/src/main/scala/kyo/scheduler/IOTask.scala:219`,
`kyo-core/shared/src/main/scala/kyo/scheduler/IOTask.scala:242`), which covers a fiber awaiting
its own child. But `Async.useResult` links unconditionally, for any promise
(`kyo-core/shared/src/main/scala/kyo/Async.scala:819-822`), and it is used for `Fiber.get`,
`Promise.get`, `Channel.take`, and every other await of a promise this fiber did not create. For
those, the link exists only from the operation onward, and case 4 leaves it unregistered.

So the repair must survive, and under the region model it needs a walk that peels region nodes.
Section 5.5 specifies it.

### 4.3 Ensure, finalizers, and the ruled `Kyo.Bracket`

**Changed from r1:** r1 declared this "the one part of the integration that cannot be designed
today" and left it as Q3 with three sub-questions. With `Kyo.Bracket` ruled in as a node type
holding acquire, use, and release, it can be designed, and what remains are three precise
requirements on the bracket primitive plus one value fork. r1's framing was also built on
`kernel2-finalizer-design.md`'s proposal to thread a finalizer store through the eval entry
points (`kernel2-finalizer-design.md:118-156`, amended at `kernel2-finalizer-design.md:206-212`),
which the `Kyo.Bracket` ruling supersedes.

Under a `Kyo.Bracket` node the storage question answers itself: an outstanding release lives in
the remainder, exactly as an open region lives in the remainder, because `rebuild` reconstructs
every open cell around the standing value (`Eval.scala:241-251`). IOTask therefore carries **no
finalizer field**, and `Finalizers` (`kyo-core/shared/src/main/scala/kyo/scheduler/Finalizers.scala`)
loses its only consumer. That is the structural answer
`kyo-kernel2/CONTRIBUTING.md:31-33` asks for: the property "a fiber's outstanding releases are
known" is held by the shape of the residual, not by a list someone has to maintain.

What does not answer itself is the discard path, and it is the same problem
`kernel2-finalizer-design.md:111-116` identifies as the root cause of six failed encodings:
"Structure alone cannot express 'after the region' on the discard path." IOTask discards a
remainder on three paths (2.1), and on all three the evaluator will never reach the releases,
because the remainder is never evaluated again. So the kernel owes IOTask three things.

**R-B1. An abandonment entry.** Given a remainder that will never be evaluated and an outcome,
run its outstanding releases. IOTask calls it on the normal interrupt path, the
interrupt-while-queued path, and the fatal path. The releases must run innermost-first, exactly
once per bracket, and with the fiber's error as the outcome (`pollError()`,
`kyo-core/shared/src/main/scala/kyo/scheduler/IOPromise.scala:54`).

The one design choice inside R-B1 is whether an abandoned release may suspend. The baseline says
no: `Sync.ensure` lowers to `Safepoint.ensure(ex => Sync.Unsafe.evalOrThrow(f(ex)))(v)`
(`origin/main:kyo-core/shared/src/main/scala/kyo/Sync.scala:111`), so every finalizer is
evaluated synchronously through `evalOrThrow` on every path, and the fiber-level drain
(`origin/main:kyo-core/shared/src/main/scala/kyo/scheduler/IOTask.scala:153-155`) calls plain
`Maybe[Error[Any]] => Unit` functions. `kernel2-finalizer-design.md:233-234` reaches the same
restriction from the other direction: "parking releases on discard-walk paths remain
synchronous-eval only, stated as a restriction". Recommendation: keep the baseline's contract, so
the abandonment entry returns `Unit` and IOTask's shape stays the baseline's. The alternative,
returning a computation of the releases for the fiber to drive, needs either a second termination
mode on `run` or a throwaway task to drive it, and buys a capability the baseline never had. This
is ruling R3 in section 9.

**R-B2. A throw escaping `Eval.partial` must have already run the releases of what it unwound.**
This one is not optional and it is not IOTask's to implement. On the fatal path the exception
escapes the drive, so IOTask holds only the slice-start `curr`
(`origin/main:kyo-core/shared/src/main/scala/kyo/scheduler/IOTask.scala:129-144` shows the
baseline in the same position). Under the bracket-in-remainder model, `curr` is stale in both
directions: it does not contain brackets acquired during the slice, and it still contains
brackets whose releases already ran. IOTask cannot reconstruct the truth from it, so calling the
abandonment entry on `curr` would both miss releases and double-run others. The kernel must
rebalance at its own catch sites, which is what `kernel2-finalizer-design.md:145-146` already
specifies: "Throws rebalance at the existing catch sites (the catching guard and the drive's
boundary) with a `Panic` outcome." IOTask's fatal arm then reduces to completing the promise with
the `Panic` and re-propagating, with nothing to drain.

**R-B3. A `done` that truncates the spine must run the discarded cells' releases.** The boundary
`Abort` clause has to truncate: `Abort` is `ArrowEffect[Const[Error[E]], Const[Unit]]`
(`kyo-prelude/shared/src/main/scala/kyo/Abort.scala:41`) and `Abort.error` is
`ArrowEffect.suspendWith[Any](erasedTag[E], error)(_ => ???)`
(`kyo-prelude/shared/src/main/scala/kyo/Abort.scala:70-71`), so answering an `Abort` with `()` and
continuing runs `???`. The clause must issue `Loop.done`. And `done` "feeds its own cell's exit
and continues at `node.prev`. The discarded cells' exits never run"
(`kyo-kernel2/CONTRIBUTING.md:98`, implemented at `Eval.scala:84-85`). Using a `Handler.Cont`
instead does not help: its clause body runs at `node` (`Eval.scala:87-92`), so returning a value
without calling the continuation discards the same cells. If a `Kyo.Bracket` is a cell in the
spine, every unhandled abort in a fiber holding an open bracket would skip its release. This is
the hard face of the discard problem and it must be solved by the bracket primitive, not worked
around by IOTask.

Consequence for the design in section 5: the run loop is written so that it is complete and
correct given R-B1, R-B2, and R-B3, and so that ruling R3 either way changes only the body of one
private method.

---

## 5. Proposed design

### 5.1 The core idea

Two facts from the kernel determine the whole shape.

- **Answering an effect at the boundary must be a handler layer, not a walk**, because a
  suspension is reified inside its surviving layers (`Eval.scala:49`, `Eval.scala:241-251`) and
  head inspection cannot reach it.
- **A clause whose outcome is pending runs outside its own layer.** For a `Handler.Loop` the
  evaluator continues at `node.prev` and chains the decision after the pending outcome
  (`Eval.scala:74-75`, `Eval.scala:153-164`), and `kyo-kernel2/CONTRIBUTING.md:99` states the
  rule: "a clause runs outside its own scope, because a clause runs outside its own region by
  construction. ... the crossed cells are rebuilt around the resumption when the outcome settles."

Put those together with the boundary layer installed outermost, so its cell's `prev` is `Empty`.
When the clause parks by re-raising its own operation as a pending outcome, the loop continues
under `Empty`, the re-raised suspension misses, and the residual is a **bare `Kyo.Suspend` with no
region nodes around it**, whose continuation already carries `rebuild(hsAll, Empty, ...)` of the
entire spine including the boundary layer itself (`Eval.scala:161`). That residual is exactly the
one thing `ArrowEffect.handlePartial` can answer, and it answers it **without installing a new
layer** (`ArrowEffect.scala:228-231`). Resuming therefore restores the original spine, cell by
cell and by identity (4.1), rather than nesting a second boundary layer around it.

The park protocol the current file implements by hand ("the next slice re-enters this handler with
a fresh continuation and polls again",
`kyo-core/shared/src/main/scala/kyo/scheduler/IOTask.scala:92-96`) becomes a consequence of the
region semantics instead of a mechanism.

**Changed from r1:** r1 expressed this over `hs.take(idx)` and `rebuildFrom(idx, ...)` with the
boundary at index 0. Neither exists. The property survives with a different mechanism: the spine
is a linked list of cells, "index 0" becomes "`prev` is `Empty`", and `hs.take(idx)` becomes the
loop continuing at `node.prev`.

**The distinction the re-raise depends on, stated because getting it backwards is an infinite
loop.** The evaluator treats two kinds of pending differently:

- The clause's **outcome** is pending, that is `h(kyo.input)` returns a `Kyo`: the loop continues
  at `node.prev`, outside the region (`Eval.scala:74-75`).
- The clause returned a settled `Loop.continue` whose **answer** is pending: the loop continues at
  `node`, inside the region (`Eval.scala:80-81`), which is what lets a handler re-raise its own
  effect and answer it itself (`kyo-kernel2/CONTRIBUTING.md:100`).

So the park must be written `ArrowEffect.suspend(Tag[Async.Join], joinInput).map(Loop.continue(_))`,
which is a pending outcome. Written `Loop.continue(ArrowEffect.suspend(Tag[Async.Join], joinInput))`
it is a settled continue with a pending answer, the re-raised `Join` is answered by the same
boundary clause, and the fiber spins allocating. The two forms are one character apart in reading
and opposite in behavior, so section 8 names the test that pins it.

### 5.2 Proposed field layout

```scala
sealed private[kyo] class IOTask[Ctx, E, A] private (
    private var curr: A < (Ctx & Async & Abort[E])
) extends IOPromise[E, A] with Task
```

| Field | Kept? | Justification |
|---|---|---|
| `curr` | yes | the residual between slices; irreducible |
| `running: Thread` | yes, `@volatile` | the thread whose slot a completion must stop; see 5.3 for why the scheduler cannot own this |
| `pendingJoin: IOPromise[?, ?]` | yes | the promise the boundary clause parked on, handed to `run` after the store; a local would be heap-boxed by the clause's capture (1.3(a)) |
| `context` | conditional, as a `def` overridden by an anonymous subclass only when non-empty | restores the baseline factory's branch (`origin/main:kyo-core/shared/src/main/scala/kyo/scheduler/IOTask.scala:209-214`); see 5.7 and ruling R4 |
| `trace` | dropped | kernel2 has no `Trace`; `fiberTrace` renders the chain (5.8) |
| `finalizers` | dropped | outstanding releases live in the remainder under `Kyo.Bracket` (4.3) |
| `parked` | dropped | `pendingJoin ne null` is the same predicate |

Layout under compact headers and compressed oops. **Baseline**
(`origin/main:kyo-core/shared/src/main/scala/kyo/scheduler/IOTask.scala:11-15`): header 8 +
`IOPromise.state` 4 + `Task.state` 4 + `curr` 4 + `trace` 4 + `finalizers` 4 = 28, aligned to
**32**, and 32 again with the conditional context field because it lands in existing padding.
**Current worktree file**: 8 + 4 + 4 + `curr` 4 + `running` 4 + `pendingJoin` 4 + `parked` 1 +
the always-present `context` 4 = 33, aligned to **40**. **Proposed**: 8 + 4 + 4 + `curr` 4 +
`running` 4 + `pendingJoin` 4 = 28, aligned to **32**, with the conditional context landing in
padding at 32.

**Changed from r1:** r1 claimed a zero-field variant at 24 bytes and a one-field fallback also at
24, both "smaller than the baseline's 32". Both numbers were wrong, because they assumed
`pendingJoin` could be a local. The honest result is parity with the baseline at 32, achieved by
trading `trace` and `finalizers` for `running` and `pendingJoin`. Parity is the correct claim to
make, and it is worth stating that this design does not pay for the region model in footprint.

### 5.3 Preemption wiring

Delivery is `Safepoint.stop(thread)`
(`kyo-kernel2/shared/src/main/scala/kyo/kernel/internal/Safepoint.scala:158-172`), and the design
question is who holds the thread and who issues the call.

Both producers of a preemption decision already funnel through one method. `Worker.checkStalling`
calls `task.doPreempt()`
(`kyo-scheduler/jvm-native/src/main/scala/kyo/scheduler/Worker.scala:260`) for the time slice, and
`IOTask.onComplete` calls `doPreempt()`
(`kyo-core/shared/src/main/scala/kyo/scheduler/IOTask.scala:36`;
`origin/main:kyo-core/shared/src/main/scala/kyo/scheduler/IOTask.scala:25`) for a completion or an
interrupt. `Task.doPreempt` is a plain overridable method
(`kyo-scheduler/shared/src/main/scala/kyo/scheduler/Task.scala:8-17`). So the entire wiring is one
override:

```scala
// The slice's thread, published so a preemption decision reaching this task from any
// producer can stop the drive at its next step. Null between slices.
@volatile private var running: Thread = null

final override def doPreempt(): Unit =
    super.doPreempt()
    val thread = running
    if thread ne null then discard(Safepoint.stop(thread))
```

with `run` writing `running = Thread.currentThread()` before the drive and nulling it in a
`finally`. `onComplete` keeps the baseline's body unchanged
(`origin/main:kyo-core/shared/src/main/scala/kyo/scheduler/IOTask.scala:24-30`), because
`doPreempt()` now carries the stop.

**Changed from r1:** r1 recommended moving delivery to the scheduler with a new
`Task.preemptOn(thread)` hook, two new call sites, and un-gating one `BlockingMonitor` dispatch
from `blocked` (`kyo-scheduler/jvm-native/src/main/scala/kyo/scheduler/BlockingMonitor.scala:237`),
in order to save one field. That recommendation is withdrawn for three reasons.

1. **It does not meet the interrupt latency requirement.** Under kernel2 nothing in the drive
   reads the promise state, so the stop is the only thing that stops an interrupted CPU-bound
   fiber (3.2). The scheduler learns the interrupt through `Scheduler.notifyInterrupt`, which
   bumps an epoch and wakes the blocking monitor
   (`kyo-scheduler/jvm-native/src/main/scala/kyo/scheduler/Scheduler.scala:137-140`), so delivery
   waits for a monitor scan; via `checkStalling` it waits a full time slice. The baseline delivers
   in one operation (2.2). `onComplete` runs on the interrupter's thread the moment the CAS
   succeeds (`kyo-core/shared/src/main/scala/kyo/scheduler/IOPromise.scala:196-199`), which is the
   only site with that latency.
2. **It costs more machinery, not less.** The override adds nothing to kyo-scheduler, which is a
   published standalone contract; r1's route adds a method to `Task`, a call in `Worker`, a call in
   `BlockingMonitor`, and a change to a documented gating decision whose comment explains why
   preemption is withheld from blocked workers
   (`kyo-scheduler/jvm-native/src/main/scala/kyo/scheduler/Worker.scala:218-224`).
3. **The field is not the mechanism `kyo-kernel2/CONTRIBUTING.md:142` warns about.** That checklist
   governs kernel mechanisms. `running` publishes a fact the runtime genuinely owns, the thread its
   slice is on, to the one method that needs it, and it replaces a field of the same width
   (`running: Safepoint`) rather than adding one.

Two properties of this wiring worth stating.

- **A stop aimed at a thread that has moved on is benign** by 3.5, and for the time-slice path it
  is re-issued on every coordinator tick while the task is past its slice
  (`kyo-scheduler/jvm-native/src/main/scala/kyo/scheduler/Worker.scala:246-263`). For the interrupt
  path there is no explicit re-issue, but `checkStalling` will fire within one time slice for a
  task that keeps running, so the fallback exists.
- **A stop issued before the slice claims its slot is lost.** `run` publishes `running` before
  `Eval.partial` calls `Safepoint.get()` (`Eval.scala:24`), so a stop in that window returns false
  (`Safepoint.scala:171`). Worker threads claim a slot on their first drive and keep it through the
  `ThreadLocal` cache (`Safepoint.scala:29, 98-107`), so the window is one slice per worker thread
  over the process lifetime, and the re-issue above covers it.

`Task.State`'s preempt bit stays the scheduler-internal signal it already is
(`kyo-scheduler/shared/src/main/scala/kyo/scheduler/Task.scala:19-20, 86-91`); nothing in the
kernel reads it any more, which the interim design also concluded
(`kernel2-preemption-design.md:779-784`).

### 5.4 The run loop

Signatures used below, verbatim from HEAD:

- `def partial[A, S](v: A < S): A < S` (`kyo-kernel2/shared/src/main/scala/kyo/kernel/internal/Eval.scala:23`),
  becoming `partial[A, S](v: A < S, deadlineMillis: Long)` under ruling R1.
- `inline def handlePartial[I[_], O[_], E <: ArrowEffect[I, O], A, S](inline _tag: Tag[E], v: A < (E & S))(inline f: [X] => (I[X], O[X] => A < (E & S)) => Maybe[A < (E & S)])(using inline _frame: Frame): A < (E & S)`
  (`kyo-kernel2/shared/src/main/scala/kyo/kernel/ArrowEffect.scala:223-225`)
- `inline def handleLoop[I[_], O[_], E <: ArrowEffect[I, O], A, S, S2](inline _tag: Tag[E], v: A < (E & S))(inline f: [X] => I[X] => Loop.Outcome[O[X] < (E & S & S2), A] < S2)(using inline _frame: Frame): A < (S & S2)`
  (`kyo-kernel2/shared/src/main/scala/kyo/kernel/ArrowEffect.scala:110-113`)
- `private[kyo] inline def evalNow: Maybe[A]` (`kyo-kernel2/shared/src/main/scala/kyo/kernel/Pending.scala:146-149`)

**At fiber creation**, wrap once, `Join` outermost:

```scala
// IOTask.apply
val boundary =
    ArrowEffect.handleLoop(Tag[Async.Join],
        ArrowEffect.handleLoop(erasedAbortTag, user)(
            [C] =>
                error =>
                    task.completeDiscard(error.asInstanceOf[Result[E, A]])
                    Loop.done(nullValue)          // truncates the spine; see R-B3
        )
    )(
        [C] =>
            joinInput =>
                // invoking joinInput registers the interrupt cascade link on THIS task
                // before the promise state is read (Async.scala:819-822)
                val p = joinInput(task)
                p.poll() match
                    case Absent     => reraise(joinInput)            // park; see below
                    case Present(r) => task.removeInterrupt(p); Loop.continue(r)
                    case null       => task.removeInterrupt(p); Loop.continue(null)
    )
```

`Join` must be outermost so its cell's `prev` is `Empty` and the loop runs the parked clause under
an empty spine; that is what makes the park residual a bare suspension (5.1). `Abort` sits directly
inside it and `Handlers.find` walks `prev` innermost-first
(`kyo-kernel2/shared/src/main/scala/kyo/kernel/internal/Handlers.scala:37-45`), so any `Abort.run`
the user installed still wins. The erased tag works because both sides are erased to `Abort[Any]`:
`Abort.error` suspends with `erasedTag` (`kyo-prelude/shared/src/main/scala/kyo/Abort.scala:48,
70-71`) and so does `Abort.run`. The existing `erasedAbortTag` in IOTask
(`kyo-core/shared/src/main/scala/kyo/scheduler/IOTask.scala:70`) is the same trick.

`reraise` is the park, and it is one line of ordinary kernel usage:

```scala
ArrowEffect.suspend[C](Tag[Async.Join], joinInput).map(Loop.continue(_))
```

**Per slice:**

```scala
final def run(startMillis: Long, clock: InternalClock, deadline: Long): Task.Result =
    if !isPending() then
        // completed while queued: settle the retained remainder without driving user code
        abandon(curr)
        Task.Done
    else
        pendingJoin = null
        running     = Thread.currentThread()
        val next =
            try drive(curr, deadline)
            catch
                case ex =>
                    completeDiscard(Result.Panic(ex))
                    // R-B2: a throw escaping the drive has already had its releases run by the
                    // kernel's own catch sites. curr is the stale slice-start snapshot and must
                    // not be abandoned here: it would miss brackets acquired during the slice
                    // and double-run brackets already released.
                    if !NonFatal(ex) then throw ex
                    nullRemainder
            finally running = null
        if !isPending() then
            // On an interrupt that lands mid-slice, `next` is the accurate remainder whose head is
            // the operation the drive stopped in front of, while `curr` is the slice-start snapshot.
            abandon(if !isNull(next) then next else curr)
            Task.Done
        else if isNull(next) then
            Task.Done
        else
            next.evalNow match
                case Present(a) =>
                    completeDiscard(Result.succeed(a))
                    curr = nullRemainder
                    Task.Done
                case Absent =>
                    curr = next
                    val join = pendingJoin
                    if join ne null then
                        pendingJoin = null
                        // last task-state action of the slice: once the wakeup can fire, another
                        // worker may run this task concurrently with this return
                        join.onComplete { _ =>
                            this.removeInterrupt(join)
                            Scheduler.get.schedule(this)
                        }
                        Task.Done
                    else
                        Task.Preempted
end run

// Eval.partial drives; handlePartial resumes a park. A resume does no work of its own, so a
// resumed value re-enters the drive rather than ending the slice.
@tailrec private def drive(v: A < (Ctx & Async & Abort[E]), deadline: Long): A < (Ctx & Async & Abort[E]) =
    val driven = Eval.partial(v, deadline)
    val resumed =
        ArrowEffect.handlePartial(Tag[Async.Join], driven)(
            [C] =>
                (joinInput, cont) =>
                    val p = joinInput(this)
                    p.poll() match
                        case Absent =>
                            // Park. The wakeup is registered by run, after the remainder is
                            // stored; the link registered above stays for the cascade while parked.
                            pendingJoin = p
                            Maybe.Absent
                        case Present(r) =>
                            this.removeInterrupt(p)
                            Maybe(cont(r.asInstanceOf[Result[Nothing, C]]))
                        case null =>
                            // a promise completed with a null-valued result polls as a raw null
                            // through the opaque encodings: resume with it like any completion
                            this.removeInterrupt(p)
                            Maybe(cont(null.asInstanceOf[Result[Nothing, C]]))
        )
    if (pendingJoin ne null) || (resumed.asInstanceOf[AnyRef] eq driven.asInstanceOf[AnyRef]) then resumed
    else drive(resumed, deadline)
end drive
```

Notes on why each piece is what it is.

- **`Eval.partial` is the driver**, not `handlePartial`. It evaluates regions
  (`kyo-kernel2/shared/src/test/scala/kyo/kernel/internal/EvalTest.scala:257-260` pins this) and
  yields on a stop or an unhandled suspension. This is the correction to critique 1.3(c), and it is
  the ruling `kyo-kernel2/CONTRIBUTING.md:96` states from the kernel's side.
- **`handlePartial` is the resumer**, and only that. It answers a bare `Join` suspension at the head
  with no new region node (`ArrowEffect.scala:228-231`), which is the only way to resume a park
  without nesting a second boundary layer per park (section 6). Given the residual trichotomy of
  4.1, it either answers a park (shape 3), or returns the same reference (shapes 1 and 2). Its
  `Defer` arm (`ArrowEffect.scala:232-236`) is unreachable here.
- **`drive` loops, so a resume costs no reschedule.** Answering a park produces a region-node-headed
  value that `handlePartial` immediately returns; without the loop the slice would end there having
  done no work, and the scheduler would pay a full reschedule per wakeup. The loop's exit test is
  reference identity on `handlePartial`'s result, which is sound because `handlePartial` returns its
  input unchanged in exactly the two non-answering cases (`ArrowEffect.scala:231, 237-238`).
  **Changed from r1:** r1's run loop called `Eval.partial` and `handlePartial` once each per slice
  and therefore burned one reschedule on every park wakeup.
- **`pendingJoin` is a field, not a local.** See 1.3(a): the clause is inlined into `handlePartial`'s
  local `partialLoop` (`ArrowEffect.scala:226-239`), so a local `var` it writes is captured and
  boxed.
- **The race fix from `23cb1d551d` is preserved verbatim in shape**: `curr = next` happens before
  `join.onComplete`, and nothing writes task state after the registration. `IOTaskTest`'s
  50000-iteration hammer
  (`kyo-core/shared/src/test/scala/kyo/scheduler/IOTaskTest.scala:120-146`) still adjudicates it.
- **`completeAbort` is deleted.** The boundary `Abort` layer completes the promise at the operation,
  so there is nothing to detect afterwards, and the `dispatchFirst` head walk it used could not see
  a reified suspension anyway (1.3(d)).
- **`ensureInterrupt` survives, inside `abandon`.** See 4.2 case 4 and 5.5.

**One hazard this loop inherits and should not.** `isNull(next)` means "the promise is already
complete and there is no remainder", and it is distinguishable from "the fiber's value is `null`"
only by the promise state, because `evalNow` reports a settled `null` as `Maybe.Absent`
(`Pending.scala:146-149` composed with `kyo-data/shared/src/main/scala/kyo/Maybe.scala:39-41`,
where `Maybe(null)` is `Absent`). In the loop above a fiber whose result is `null` reaches the
`Absent` arm with a settled residual, stores it in `curr`, and returns `Task.Preempted` forever.
The baseline has the same shape and the same outcome
(`origin/main:kyo-core/shared/src/main/scala/kyo/scheduler/IOTask.scala:109-118, 161-165`), so this
is not a regression introduced here, but it is a live bug on both sides and this design should not
carry it forward. The fix is to stop using `null` for two meanings: a private sentinel object for
"no remainder", tested by reference, leaves `null` free to be a value. Section 8 names the test.

### 5.5 `dispatchFirst`: what it must become

The old kernel's `dispatchFirst` inspects the head suspension of a value, calls `f` with its input
when the tag matches, and does nothing otherwise; its scaladoc records that it "never enters the
Safepoint, never executes the continuation, and never schedules a continuation" and that its
consumer is `IOTask.ensureInterrupt`
(`origin/main:kyo-kernel/shared/src/main/scala/kyo/kernel/ArrowEffect.scala:401-419`). It has
exactly one call site on `origin/main`
(`origin/main:kyo-core/shared/src/main/scala/kyo/scheduler/IOTask.scala:177`); the second call site
in the worktree file (`completeAbort`,
`kyo-core/shared/src/main/scala/kyo/scheduler/IOTask.scala:170`) was added by the port.

**Decision: kernel2 needs it, for one call site, with one behavioral change.**

**Changed from r1:** r1 concluded that both uses "disappear" and that kernel2 needs no equivalent.
That follows from r1's incorrect claim in 4.2 that the interrupt-before-join race cannot arise. It
can (4.2 case 4), so the repair needs a walk, and under the region model a head-only walk cannot
find the operation.

Specification:

```scala
/** Inspects the standing operation of `v`, that is the first suspension reachable by peeling
  * region nodes, and invokes `f` with its input when the tag matches. Runs nothing: it does not
  * enter a handler, does not apply a region's exit, does not step a Defer, and does not read or
  * consume the Safepoint's stop marker. Intended for purely-inspecting consumers that read the
  * input as a value and produce side effects directly, on a remainder that will never be
  * evaluated.
  */
private[kyo] inline def dispatchFirst[I[_], O[_], E <: ArrowEffect[I, O], A, S](
    inline _tag: Tag[E],
    v: A < (E & S)
)(
    inline f: [C] => I[C] => Unit
): Unit
```

The one behavioral change from the old kernel's version is the peel. The implementation walks
`Kyo.Handled.value` (`kyo-kernel2/shared/src/main/scala/kyo/kernel/internal/KyoInternal.scala:107`)
and `Kyo.HandledState.value` (`KyoInternal.scala:147`) until it reaches something else, then tests
`Kyo.Suspend.tag` (`KyoInternal.scala:46`, which delegates through `root`, `KyoInternal.scala:51,
62-64`, so a mapped suspension answers correctly) and stops at a `Kyo.Defer` or a settled value.
Peeling `value` is what makes it run nothing: the handler and the exit arrow are the other two
fields of the node and are never touched. The walk is `@tailrec` over the residual's node chain,
whose depth is the number of open regions, so it needs no carrier beyond the loop
(`kyo-kernel2/CONTRIBUTING.md:115-122`).

Stopping at a `Kyo.Defer` means the walk can miss a `Join` that sits one step behind a deferred
body. The baseline accepts the same limitation for the same reason, stated in its comment: "no
Defer body is drained, so it runs no user code and cannot reintroduce the `Sync.ensure`
finalizer-drop reverted in `33bb29bd94`"
(`origin/main:kyo-core/shared/src/main/scala/kyo/scheduler/IOTask.scala:170-171`). Running user
code from a walk over an already-completed fiber's remainder is the failure that comment records,
so the limitation is deliberate on both sides.

IOTask's single consumer:

```scala
// Runs after the promise completed with the computation discarded: registers the interrupt
// cascade for a Join the drive stopped in front of but had not yet reached, then runs the
// outstanding releases the discarded remainder still carries.
private def abandon(remainder: A < (Ctx & Async & Abort[E])): Unit =
    if !isNull(remainder) && remainder.evalNow.isEmpty then
        ArrowEffect.dispatchFirst(Tag[Async.Join], remainder) {
            [C] => joinInput => discard(joinInput(this))
        }
        Eval.abandon(remainder, pollError())      // R-B1
    curr = nullRemainder
end abandon
```

The order matches the baseline: cascade repair first, releases second
(`origin/main:kyo-core/shared/src/main/scala/kyo/scheduler/IOTask.scala:151-155`).

### 5.6 What maps, what changes, what is dropped

| Baseline behavior (`origin/main`) | Under this design |
|---|---|
| slice budget for stack safety, via the interceptor's depth accounting | `Safepoint` depth budget, `period()` defaulting to 512 (`Safepoint.scala:31, 44-56`); IOTask does not participate |
| preemption honored, via `enter` reading `shouldPreempt()` (`origin/main:kyo-core/.../IOTask.scala:21-22`) | `Safepoint.stop` from the `doPreempt` override (5.3), observed by `Eval.partial` (3.1), with the coverage gaps of 3.3 and the proposals of 3.7 |
| fair yielding and requeue | unchanged: `Task.Preempted` and `Worker.run`'s requeue arm (`kyo-scheduler/jvm-native/.../Worker.scala:303-334`) |
| interrupt authority | unchanged: `IOPromise` state, `needsInterrupt()` (`origin/main:kyo-core/.../IOTask.scala:41-42`) |
| interrupt priority boost | unchanged: `resetRuntime()` in `onComplete` (`origin/main:kyo-core/.../IOTask.scala:24-30`) |
| unhandled `Abort` completes the promise (`origin/main:kyo-core/.../IOTask.scala:77-82`) | boundary `Abort` handler layer, replacing the two-tag `handlePartial` clause; needs R-B3 |
| join park and wakeup (`origin/main:kyo-core/.../IOTask.scala:83-106`) | boundary `Join` handler layer plus `handlePartial` resume inside the drive loop; the store-then-register ordering of `23cb1d551d` is required here and kept (4.1) |
| interrupt-before-join cascade repair via `dispatchFirst` (`origin/main:kyo-core/.../IOTask.scala:176-180`) | kept, over a region-peeling `dispatchFirst` (5.5) |
| finalizers drained on the normal exit (`origin/main:kyo-core/.../IOTask.scala:153-155`) | `Eval.abandon(remainder, pollError())` inside `abandon` (4.3, R-B1) |
| finalizers drained on the fatal unwind (`origin/main:kyo-core/.../IOTask.scala:129-144`) | moves into the kernel's own catch sites (4.3, R-B2); IOTask's fatal arm completes the promise and re-propagates |
| finalizers drained on interrupt-while-queued (`origin/main:kyo-core/.../IOTask.scala:145-160`) | same `abandon` call from `run`'s entry arm (5.4) |
| pooled trace, released on every exit (`origin/main:kyo-core/.../IOTask.scala:140-142, 156-158`) | dropped with `Trace`; `fiberTrace` renders the chain, see 5.8 |
| JS and Wasm time slicing via the deadline in the `stop` thunk (`origin/main:kyo-core/.../IOTask.scala:76`) | the deadline parameter on `Eval.partial` armed at `enterPark` (3.7, ruling R1) |

### 5.7 Context and the fiber identity

`IOTask.CurrentFiber` (`kyo-core/shared/src/main/scala/kyo/scheduler/IOTask.scala:216`) and
`parentIn` (`kyo-core/shared/src/main/scala/kyo/scheduler/IOTask.scala:218-220`) are written
against a `Context` map that no longer exists. Under HEAD's `ContextEffect`, provision is an
answering handler layer (`kyo-kernel2/shared/src/main/scala/kyo/kernel/ContextEffect.scala:57-61`)
and reading the parent means raising the effect. That is a different design with its own live
track (`kernel2-context-threading-design.md`, `kernel2-context-typemap-design.md`) and its own
agents. This report deliberately does not design it; it records the interface IOTask needs from
it: at spawn time, read the current fiber (for interrupt linking) once and pass it, and bind the
new task as the current fiber for the computation it drives. The baseline reads the parent from
the Safepoint interceptor and passes it to the factory rather than reading a thread local per
child (`origin/main:kyo-core/shared/src/main/scala/kyo/scheduler/IOTask.scala:194-198, 216-219`);
that call shape survives whatever replaces the interceptor. The proposed field layout keeps
`context` as a `def` so that whatever the context track lands can use the baseline factory's
conditional-subclass trick, which is why 5.2 marks it conditional. That is ruling R4.

One kernel fact the context track has to reconcile with the boundary design, recorded here
because it changes what "unhandled" means. An unhandled suspension whose `root` is a
`Kyo.Defaulted` does not park; the evaluator resumes it with the default
(`kyo-kernel2/shared/src/main/scala/kyo/kernel/internal/Eval.scala:44-47`,
`kyo-kernel2/shared/src/main/scala/kyo/kernel/internal/KyoInternal.scala:39-41`), which is how
optional context reads a value nobody provided (`ContextEffect.scala:37-47, 86-93`). The
mechanism carries a maintainer TODO at `KyoInternal.scala:38` ("no, this is not acceptable, we
need to fully review") and a parked task. If it survives, an optional-context read inside a fiber
resolves without reaching the boundary, which is correct and requires nothing from IOTask; if it
is replaced by something that parks, the boundary must be able to answer it or the fiber wedges.
This is an input to that track, not a question for this one.

There is also a dependency on the `Isolate` redesign over the reified handler stack, which is
being explored separately. IOTask does not use `Isolate` directly, but the baseline reaches the
per-slice trace and interceptor installation through `Isolate.internal.restoring`
(`origin/main:kyo-core/shared/src/main/scala/kyo/scheduler/IOTask.scala:69`), and
`kyo-core/shared/src/main/scala/kyo/Fiber.scala:129, 148, 165` requires `Isolate` for the fork
surface. This design assumes the boundary wrap in `IOTask.apply` is the only thing between the
user computation and the drive; if the `Isolate` redesign wants to install layers per fiber, it
composes inside the boundary, not outside it, because the boundary's `Join` layer must remain
outermost (5.1).

### 5.8 fiberTrace

The baseline renders `Trace.render(snapshot)` off the task's pooled 16-frame ring, containing
every `Throwable` because the read is cross-thread from the leak probe
(`origin/main:kyo-core/shared/src/main/scala/kyo/scheduler/IOTask.scala:44-53`). `Trace` does not
exist in kernel2, so both the render and the pool go; the current `fiberTrace` renders
`curr.toString` (`kyo-core/shared/src/main/scala/kyo/scheduler/IOTask.scala:53-63`) with all five
tests ignored (`kyo-core/shared/src/test/scala/kyo/scheduler/IOTaskTest.scala:12-13, 38, 57-59,
81-83, 106-108`).

The node `toString`s are frame-aware: `Kyo.Suspend.toString` renders `frame.position.show` and
`frame.snippetShort` (`kyo-kernel2/shared/src/main/scala/kyo/kernel/internal/KyoInternal.scala:53-54`),
and `Arrow.Step.toString` renders the first transform's frame and deliberately does not walk,
with its comment naming the reason: "composed chains can be arbitrarily large and walking them
from toString has broken tools that stringify values, like kyo-test"
(`kyo-kernel2/shared/src/main/scala/kyo/Arrow.scala:52-55`). So a real frame walk over the
residual is buildable, but it must be a separate depth-bounded walk rather than a `toString`:
the residual is ordinary data, `Kyo.Suspend.frame` (`KyoInternal.scala:48`) and
`Arrow.Transform.frame` (`Arrow.scala:70`) are both reachable, and the region nodes peel by
`value` exactly as in 5.5. `Task.fiberTrace` returns a plain `String` and is documented as
best-effort and never throwing
(`kyo-scheduler/shared/src/main/scala/kyo/scheduler/Task.scala:31-38`), so the containment of
every `Throwable` that the baseline has must be kept. This is a separable work item, not a
blocker, but the five ignored tests are the acceptance criteria and should not stay ignored
indefinitely.

### 5.9 Where IOTask touches exception enrichment

Recorded because the enrichment successor is being designed separately and this is the one seam.
The old kernel enriches at three sites in `ArrowEffect`
(`origin/main:kyo-kernel/shared/src/main/scala/kyo/kernel/ArrowEffect.scala:594, 604, 616`) and
two in `Effect` (`origin/main:kyo-kernel/shared/src/main/scala/kyo/kernel/Effect.scala:50, 59`),
all calling `Safepoint.enrich(ex)`
(`origin/main:kyo-kernel/shared/src/main/scala/kyo/kernel/internal/Safepoint.scala:231-232`),
which splices the pooled trace's frames into the throwable
(`origin/main:kyo-kernel/shared/src/main/scala/kyo/kernel/internal/Trace.scala:190-201`). kernel2
has no `enrich` anywhere in its main sources.

IOTask's contact with it is one line: `completeDiscard(Result.Panic(ex))` in the slice's `catch`
(`kyo-core/shared/src/main/scala/kyo/scheduler/IOTask.scala:104`;
`origin/main:kyo-core/shared/src/main/scala/kyo/scheduler/IOTask.scala:121`). Whatever enrichment
lands, that `Panic` is the value users see for a fiber that threw, and `FiberTest` asserts the
enriched frames are present in a fiber's stack trace
(`origin/main:kyo-core/shared/src/test/scala/kyo/FiberTest.scala:728-745`). So the enrichment
track owns the mechanism, and the only requirement from here is that the throwable reaching
IOTask's `catch` is already enriched, since IOTask has no trace to enrich it with.

---

## 6. Rejected alternatives

**Publish a per-slice `Safepoint` (or any kernel object) on the task so a requester can call a
method on it.** This is what `running` does today
(`kyo-core/shared/src/main/scala/kyo/scheduler/IOTask.scala:21, 44`). Rejected on two counts: the
object it names no longer exists, and the requester needs a `Thread`, which `Safepoint.stop` takes
directly (`kyo-kernel2/shared/src/main/scala/kyo/kernel/internal/Safepoint.scala:158`). The
replacement in 5.3 keeps a field of the same width and publishes a plain `Thread`.

**Move preemption delivery to the scheduler behind a new `Task` hook.** Rejected in 5.3, with the
argument reproduced there. This is a reversal of r1's recommendation.

**Drive with `ArrowEffect.handlePartial` alone.** Rejected on correctness: it returns unchanged at
every region node (`ArrowEffect.scala:237-238`), so a fiber containing any handler makes no
progress. `kyo-kernel2/CONTRIBUTING.md:96` says so from the kernel's side.

**Drive with `Eval.partial` alone, resuming a park by re-wrapping the residual in a fresh boundary
layer.** This is the shape `EvalTest.scala:262-268` demonstrates, and it works once. It fails as a
protocol. The park residual's continuation already rebuilds the previous boundary layer
(`Eval.scala:161` calls `rebuild(hsAll, node.prev, ...)`, which is inclusive of `node`), and the
rebuilt outermost cell has `prev eq Empty`. Wrapping the residual in a new `handleLoop` pushes a
new cell first, so when the rebuilt cells arrive the identity test `kyo.node.prev eq hs`
(`Eval.scala:101`) fails against the new cell, and each rebuilt layer allocates a fresh `Node`
rather than re-entering (`Eval.scala:106`). The spine grows by one dead boundary cell per park for
the fiber's lifetime, and every `Handlers.find` walk gets longer
(`kyo-kernel2/shared/src/main/scala/kyo/kernel/internal/Handlers.scala:37-45`). **Changed from
r1:** r1 rejected this on the grounds that "no entry point can answer" the residual after the
second park. That is not what happens, because the innermost matching cell is the original handler
and it still answers; the real defect is an unbounded spine and a lost cell-reuse fast path.

**Distinguish park from stop by type-testing the residual head.** A stop residual heads with a
region node and a park residual with a `Kyo.Suspend` (4.1), so the test is sound. Rejected as a
design: it makes kyo-core depend on the internal node shapes, and the `handlePartial` call already
answers the question as a side effect of the work it has to do anyway, through a reference
comparison on its own return value (5.4).

**Keep a `stop: () => Boolean` callback on the partial drive** (the old kernel's shape,
`origin/main:kyo-kernel/shared/src/main/scala/kyo/kernel/ArrowEffect.scala:627`). This would close
both the JS deadline gap and the `suspendWith` latency gap in one move. Rejected because it was
already ruled against (`kernel2-preemption-design.md:72-73`, ruling 2, and
`kernel2-preemption-design.md:805-811`), it puts an indirect call on every drive iteration, and it
makes the kernel depend on scheduler state. The proposals in 3.7 close both gaps without it.

**Return a stop reason from the partial drive so IOTask learns why it stopped.** Rejected for the
reason the interim design gave (`kernel2-preemption-design.md:813-818`): it allocates per slice, it
widens the API, and the caller does not need it. Section 5.4 gets the distinction from
`pendingJoin` and one reference comparison.

**Have IOTask self-issue `Safepoint.stop(Thread.currentThread())` on JS when the deadline passes.**
Rejected on mechanism: IOTask only regains control at a park or at drive exit, and at both of those
points the slice is already over. A stop issued before entry is consumed by `Eval.partial`'s entry
check (`Eval.scala:25`) with no work done. It cannot bound a CPU-bound fiber, which is the only
case that needs bounding. The proposal in 3.7 arms the deadline where the kernel can act on it.

**Deliver interruption to the fiber as an ordinary `Join` answer.** `Async.Join`'s output is
`Result[Nothing, *]` (`kyo-core/shared/src/main/scala/kyo/Async.scala:812`), so the boundary clause
could answer a pending join with the fiber's own error instead of parking, letting the interrupt
propagate as an `Abort` through the user's code, running `Sync.ensure` releases in band and
completing at the boundary `Abort` layer. It is a clean-looking design and it is wrong:
interruption would become catchable. Any `Abort.run[Throwable]` around the join would swallow it
and the fiber would keep running user code after being interrupted. The baseline stops the drive
dead and settles out of band precisely to prevent this, and that property must be preserved.

**Make `abandon` return a computation of the releases for the fiber to drive.** This is the (b)
option inside ruling R3. It buys suspending releases on the discard path, which the baseline never
had (`origin/main:kyo-core/shared/src/main/scala/kyo/Sync.scala:111` evaluates every finalizer with
`evalOrThrow`), and costs either a second termination mode on `run` or a throwaway task per
abandoned fiber. Recommended against, but it is a genuine value fork, so it is a ruling and not a
rejection.

---

## 7. Questions from r1, resolved or narrowed

r1's section 7 listed seven open questions. Their disposition:

| r1 | Subject | Disposition in r2 |
|---|---|---|
| Q1 | JS and Wasm time slicing | Narrowed to a concrete mechanism (3.7, gap A). Remains ruling R1 because the cost of an extra `Eval.partial` parameter is a judgement. |
| Q2 | `suspendWith` dispatch loops are not preemptible | Narrowed to a concrete mechanism (3.7, gap B) and strengthened: `Async.Join` is that shape (`Async.scala:823`), so the gap is on IOTask's own path. Remains ruling R2 because it changes `Eval.scala` and carries the JMH gate. |
| Q3 | Finalizers | Resolved into three requirements on the ruled `Kyo.Bracket` (4.3: R-B1, R-B2, R-B3) plus one value fork, ruling R3. IOTask carries no finalizer field either way. |
| Q4 | Context and fiber identity | Unchanged, ruling R4. |
| Q5 | Who owns preemption delivery | **Resolved, against r1's recommendation.** IOTask owns it through a `doPreempt` override and one `Thread` field (5.3). No ruling needed. |
| Q6 | `Safepoint.stop` scans the whole slot table on a miss | Narrowed to a concrete fix, ruling R6. The scan is 65536 entries, not 8192. |
| Q7 | `Safepoint` uses `Thread.threadId()` | Confirmed still live at `Safepoint.scala:69`, ruling R7. |

Two questions r1 did not have, both raised by this revision:

- The interrupt-before-join cascade repair needs a kernel API (5.5). Ruling R5.
- The `null`-as-two-meanings hazard in `run` (5.4) is a live bug on both sides.

---

## 8. What this design does not claim, and the tests it needs

- Nothing here has been compiled. In particular the exact type ascriptions in 5.4's clauses (the
  `Loop.Outcome[O[X] < (E & S & S2), A] < S2` shape of the re-raise, which leaves `Async.Join` in
  the handler's `S2` and therefore in the fiber's row, and the erased `Result` coercions) are
  asserted from the signatures at `ArrowEffect.scala:110-113` and `ArrowEffect.scala:223-225` and
  will need adjustment against the compiler.
- The residual trichotomy of 4.1 is argued from the sources and is not covered by any existing
  test in the configuration this design uses. It is the load-bearing claim, and these are the pins
  it needs, red-first:
  1. **Park, resume, park again, resume**, asserting the fiber completes and that the number of
     region cells does not grow across parks. This is what distinguishes the design from the
     rejected re-wrap alternative (section 6).
  2. **The clause's re-raise is a pending outcome, not a settled continue with a pending answer.**
     A test that builds the boundary with `Loop.continue(ArrowEffect.suspend(...))` must not
     terminate; the shipped form must. Without this pin the two forms are indistinguishable by
     reading (5.1).
  3. **A stop landing anywhere in the drive yields a region-node-headed residual, never a
     `Kyo.Defer`-headed one**, which is what keeps `handlePartial`'s `Defer` arm out of the
     integration.
  4. **A stop landing on a thread whose armed drive has already exited leaves the ambient budget
     intact** (3.5, second corollary). This is a kernel-side pin with no current coverage.
  5. **A fiber whose result is `null` completes** (5.4's hazard). This fails on `origin/main` and
     on the worktree today.
  6. **A fiber interrupted while stopped in front of an unreached `Join` interrupts the awaited
     promise** (4.2 case 4), with the awaited promise created outside the fiber so creation-time
     linking does not mask the case.
- The allocation cost of one `rebuild` per park (4.1) is reasoned, not measured. The kernel's own
  gate requires a JMH A/B for changes to `Eval.scala`
  (`kyo-kernel2/CONTRIBUTING.md:168, 190`); this design changes no kernel file except under rulings
  R1, R2, and R5, but the IOTask-side per-park cost should be measured against `origin/main` on the
  arena rows before the integration is called done.
- The claim in 5.3 that `doPreempt` reaches every preemption producer rests on there being exactly
  two producers at HEAD (`kyo-scheduler/jvm-native/src/main/scala/kyo/scheduler/Worker.scala:260`
  and `kyo-core/shared/src/main/scala/kyo/scheduler/IOTask.scala:36`). A third producer added later
  that bypasses `doPreempt` would silently lose delivery.
- The baseline comparison is against `origin/main` at `2e9bb02d40` only. The branch's own pre-port
  IOTask (`77cedd3689^`, that is `3c055b91db`) is close but not identical; nothing in this report is
  based on that branch-local version.
- Two documents cited here are themselves stale against HEAD and should be refreshed rather than
  trusted at the line level: `kyo-kernel2/CONTRIBUTING.md` still lists `CanLift.scala` as an
  internal file (line 26), describes the slot table as "8192 line-strided slots" and the wrapper as
  riding `owners` (line 25, where the count is 65536 and the array is `slots`), quotes
  `new Node(kyo.handler, kyo.cont, hs)` where the source has `kyo.exit` (lines 55 and 85, against
  `Eval.scala:106`), and quotes a `rebuildFrom(from, value, hs, exits)` signature that no longer
  exists (line 135). `kernel2-finalizer-design.md` predates the
  `Kyo.Bracket` ruling. The doctrine in both is intact; the citations are not.

---

## 9. Consolidated rulings needed

**R1 (blocking for JS and Wasm parity). Arm a slice deadline in the kernel, or accept losing JS
fiber time slicing.** Under kernel2 at HEAD there is no way to preempt a fiber that does not
suspend out of band on a single-threaded platform (3.2), while the JS scheduler still passes a real
deadline and still expects `Task.Preempted`
(`kyo-scheduler/js-wasm/src/main/scala/kyo/scheduler/Scheduler.scala:15-20`). The proposal (3.7,
gap A) adds a `deadlineMillis` parameter to `Eval.partial` and arms it at `enterPark`
(`Safepoint.scala:135-138`), which self-installs the `Stop` on expiry so the existing `Defer` read
site does the rest. Cost on JVM and Native: one parameter and one `Long` compare per 512 steps.
**The ruling decides**: whether `Eval.partial` gains a deadline parameter, or JS and Wasm fibers
lose time slicing.

**R2 (blocking for the latency claim). Charge the evaluator's settled-answer arm one budget step,
subject to the JMH gate.** A `suspendWith` dispatch loop consumes no budget and touches no Safepoint
entry point, so a stop never converts into a drain and is never read (3.3). `Async.Join` is exactly
that shape (`kyo-core/shared/src/main/scala/kyo/Async.scala:823`), so every fiber that joins
completed promises in a tight loop is unpreemptible. The proposal (3.7, gap B) charges the arm at
`Eval.scala:65` and `Eval.scala:83` one step against the slot the evaluator already holds and mints
a `Kyo.Defer` on refusal, reusing the existing park path with no new mechanism. It changes
`Eval.scala`, so `kyo-kernel2/CONTRIBUTING.md:168` requires a JMH A/B on a frozen baseline with
`-prof gc` before it lands. **The ruling decides**: whether to spend one `depths(slot)`
read-modify-write per answered operation on the kernel's hot arm to make the preemption bound
uniform, conditional on the A/B showing the answering rows stay allocation-flat.

**R3 (blocking for a green kyo-core). May an abandoned release suspend?** Under the ruled
`Kyo.Bracket`, IOTask calls one kernel entry to run a discarded remainder's outstanding releases
(4.3, R-B1). Option (a): releases on the discard path are evaluated synchronously, the entry returns
`Unit`, and IOTask's shape matches the baseline. This preserves the baseline's contract exactly,
since `Sync.ensure` already evaluates every finalizer through `evalOrThrow`
(`origin/main:kyo-core/shared/src/main/scala/kyo/Sync.scala:111`), and it is what
`kernel2-finalizer-design.md:233-234` contemplates. Option (b): the entry returns a computation the
fiber drives, which needs either a second termination mode on `run` or a throwaway task per
abandoned fiber. Recommendation: (a). **The ruling decides**: whether the bracket primitive's
abandonment entry is `Unit`-returning with synchronous releases, or computation-returning.

Two requirements ride on this ruling and are not themselves forks, but they must be accepted by the
bracket track for this design to be correct, so they are stated here for confirmation:

- **R-B2**: a throw escaping `Eval.partial` must already have run the releases of what it unwound,
  because IOTask holds only the stale slice-start `curr` on the fatal path and cannot reconstruct
  them (4.3).
- **R-B3**: a `Loop.done` that truncates the spine must run the discarded cells' releases. The
  boundary `Abort` clause must truncate, because `Abort.error` answers with `???`
  (`kyo-prelude/shared/src/main/scala/kyo/Abort.scala:70-71`), and today discarded cells' exits do
  not run (`kyo-kernel2/CONTRIBUTING.md:98`, `Eval.scala:84-85`).

**R4. Context and fiber identity.** Section 5.7 states the interface IOTask needs but does not
design it, since `kernel2-context-threading-design.md` owns it. **The ruling decides**: that IOTask
keeps `context` as a `def` with the conditional-subclass factory
(`origin/main:kyo-core/shared/src/main/scala/kyo/scheduler/IOTask.scala:209-214`), so the footprint
trick from 2.1 survives whatever that track lands.

**R5. Add `dispatchFirst` to kernel2, peeling region nodes.** The interrupt-before-join cascade
repair cannot be dropped (4.2 case 4), and under the region model the standing operation is not at
the head. Section 5.5 specifies a `private[kyo]` inline entry that peels `Kyo.Handled.value` and
`Kyo.HandledState.value`, tests the standing suspension's tag, runs nothing, and stops at a
`Kyo.Defer` or a settled value, with one call site in kyo-core. Note the sequencing dependency: an
in-flight task is adding `ArrowEffect.handleFirst` and `ArrowEffect.handleCatching` to kernel2, and
on `origin/main` `dispatchFirst` sits next to `handleFirst` and shares its scaladoc's framing
(`origin/main:kyo-kernel/shared/src/main/scala/kyo/kernel/ArrowEffect.scala:401-419`), so the two
should land together. **The ruling decides**: whether kernel2 gains this entry, or whether the
cascade repair is dropped and the leak it prevents is accepted.

**R6 (small, kernel-side). Give `Safepoint.stop` a cheap negative.** A live thread with no slot
costs `Slots` volatile reads, defaulting to 65536 (`Safepoint.scala:33, 159-171`), on the requester's
thread, which under 5.3 is the interrupter's thread. There is a correct early exit: entries are
never written back to `null` (`resolve` CASes to a `Thread` at `Safepoint.scala:91` and
`consumeStopped` writes the bare thread at `Safepoint.scala:177`), and `claim` takes the first free
index walking forward from `home` (`Safepoint.scala:79-96`), so a `null` entry reached while probing
forward from `home(thread)` proves the thread owns no slot at or before it, and `stop` may return
false there. A concurrent claim of that entry makes the stop a lost request, which 3.5 shows is
benign and which `Worker.checkStalling` re-issues. **The ruling decides**: whether to add the early
exit.

**R7 (small, kernel-side, likely a live defect). `Safepoint` uses `Thread.threadId()`.**
`Safepoint.scala:69` calls `thread.threadId()`. The old kernel deliberately used the deprecated
`getId()` with a comment recording why: "its replacement threadId() is absent from the Scala.js
javalib and fails JS and Wasm linking (it type-checks against the JDK, then breaks at link)"
(`origin/main:kyo-kernel/shared/src/main/scala/kyo/kernel/internal/Safepoint.scala:32-36`, with the
same note repeated at line 86). `kyo-kernel2` cross-builds JS, Native, and Wasm
(`build.sbt:772-773, 802-803`) and `kyo-prelude` depends on it (`build.sbt:808`), so this breaks the
downstream JS and Wasm link, not the compile. kernel2 has no platform-specific sources at all
(`kyo-kernel2/js`, `kyo-kernel2/native`, and `kyo-kernel2/wasm` contain only `target`), and its
suites run on JVM only, so nothing has caught it. **The ruling decides**: whether to restore the
`getId()` form with its comment, or to add a platform-specific `home` implementation.

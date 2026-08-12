# IOTask on kernel2

## Mechanism

Boundary installed once at fiber creation, `Join` outermost so its cell's `prev` is `Empty`:

```scala
ArrowEffect.handleLoop(Tag[Async.Join],
    ArrowEffect.handleLoop(erasedAbortTag, user)(
        [C] => error =>
            task.completeDiscard(error.asInstanceOf[Result[E, A]])
            Loop.done(nullValue)                      // must truncate: Abort.error answers with ??? (Abort.scala:71)
    )
)(
    [C] => joinInput =>
        val p = joinInput(task)                       // registers the cascade link before reading state
        p.poll() match
            case Absent => reraise(joinInput)
            case r      => task.removeInterrupt(p); Loop.continue(r)   // Present or a raw null result
)
```

The park, and the form one character apart from it that spins:

```scala
ArrowEffect.suspend[C](Tag[Async.Join], joinInput).map(Loop.continue(_))  // correct: pending OUTCOME
Loop.continue(ArrowEffect.suspend[C](Tag[Async.Join], joinInput))         // spins: settled continue, pending ANSWER
```

The first returns a `Kyo` from `h(kyo.input)`, so the loop continues at `node.prev` (`Eval.scala:74-75`), `Empty`
for the outermost cell; the re-raised tag misses, `rebuild(Empty, Empty, v)` returns `v`, and the residual is a bare
`Kyo.Suspend[Join]` whose cont already carries `rebuild(hsAll, Empty, ...)` of the whole spine (`Eval.scala:161`).
The second is a settled `Continue` with a pending answer, which runs at `node` (`Eval.scala:80-81`), so the same
clause answers its own re-raise, forever. Per slice, `Eval.partial` drives and `handlePartial` only resumes a park:

```scala
@tailrec private def drive(v: A < (Ctx & Async & Abort[E]), deadline: Long): A < (Ctx & Async & Abort[E]) =
    val driven  = Eval.partial(v, deadline)
    val resumed = ArrowEffect.handlePartial(Tag[Async.Join], driven)(
        [C] => (joinInput, cont) =>
            val p = joinInput(this)
            p.poll() match
                case Absent => pendingJoin = p; Maybe.Absent
                case r      => this.removeInterrupt(p); Maybe(cont(coerce(r)))
    )
    if (pendingJoin ne null) || (resumed.asInstanceOf[AnyRef] eq driven.asInstanceOf[AnyRef]) then resumed
    else drive(resumed, deadline)
```

`run` stores `curr = next` and only then registers `join.onComplete { ... schedule(this) }`, keeping the ordering
`23cb1d551d` established. The exit test is reference identity because `handlePartial` returns its input in exactly
the two non-answering cases (`ArrowEffect.scala:231, 237-238`); the loop exists so a resume costs no reschedule.

**Residual trichotomy** (the invariant everything above rests on). With `Join` outermost, `hs` is non-empty for the
whole drive, so `Eval.partial` returns exactly one of: a settled value; a region-node head (stop); a bare
`Kyo.Suspend[Join]` (park). No `Kyo.Defer` can head a residual, so `handlePartial`'s `Defer` arm is unreachable and
never steps user code outside the armed drive. Re-entry is free: a residual is rebuilt down to `Empty` and
re-entered from `Empty`, so every `RebuiltNode` satisfies `kyo.node.prev eq hs` (`Eval.scala:101-104`) by induction.
O(layers) wrappers at the park, zero cells at the resume.

**Fields.** `curr`, `@volatile running: Thread`, `pendingJoin: IOPromise[?, ?]`; `trace` and `finalizers` go, the
latter because outstanding releases live in the remainder. 8 + 4 (`IOPromise.state`) + 4 (`Task.state`) + 4 + 4 + 4
= 28, aligned to 32, parity with the baseline, conditional `context` in padding. `pendingJoin` cannot be a local:
the clause inlines into `handlePartial`'s local `partialLoop`, so a local `var` is captured and boxed per slice.

**Preemption wiring is one override**, since both producers funnel through the overridable `Task.doPreempt`
(`Worker.scala:260` for the time slice, `IOTask.onComplete` for completion and interrupt):

```scala
final override def doPreempt(): Unit =
    super.doPreempt()
    val thread = running
    if thread ne null then discard(Safepoint.stop(thread))
```

`run` publishes `running` before the drive and nulls it in a `finally`. Zero scheduler changes. A stop issued before
the slice claims its slot is lost, one slice per worker thread over the process lifetime, and `checkStalling`
re-issues within a time slice.

**`dispatchFirst`, region-peeling.** Same signature and "runs nothing" contract as the old kernel
(`origin/main:kyo-kernel/.../ArrowEffect.scala:409-419`), with one change: peel `Kyo.Handled.value` and
`Kyo.HandledState.value` before testing `Kyo.Suspend.tag`, then stop at a `Kyo.Defer` or a settled value. Peeling
`value` is what makes it run nothing, since handler and exit are the node's other two fields. One call site:
`abandon` runs it with `[C] => joinInput => discard(joinInput(this))` then the release drain (R-B1), the baseline's
cascade-then-finalizers order. `completeAbort` is deleted; the boundary `Abort` layer completes at the operation.

## Load-bearing findings

**Answering at the boundary must be a layer, not a walk.** `Eval.partial` reifies an unhandled suspension as
`rebuild(hs, Empty, v)` (`Eval.scala:49`), so every head-only walk in the current file is blind for a fiber
holding any handler.

**Pending outcome versus pending answer** is the whole park protocol, and the two forms differ by one character
(above). It needs a pin: the wrong form does not fail a type check, it fails to terminate.

**Gap 1: single-threaded platforms have no delivery.** Nothing can call `Safepoint.stop` while a JS task runs, and a
self-issued stop before entry is consumed by `Eval.partial`'s entry check. Fix: a `deadlineMillis` parameter checked
in `enterPark` (`Safepoint.scala:135-138`), already once per period, self-installing the `Stop` on expiry.

**Gap 2: `suspendWith` dispatch loops consume no budget.** The settled arm calls no Safepoint entry
(`ArrowEffect.scala:48-54`), so a stop never converts into a drain and is never read. `Async.useResult` is
`suspendWith` (`Async.scala:823`), so this is IOTask's own path: `Fiber.get`, `Channel.take`, `Promise.get` on
completed promises in a loop are unpreemptible. Fix: charge the settled-answer arm (`Eval.scala:65, 83`) one budget
step against the slot the evaluator already holds, minting `Kyo.Defer(answer, kyo.cont)` on refusal so the existing
`Defer` arm reads the stop and resets. No new mechanism, one uniform bound.

Everything else beats the baseline: a stop drains the budget at the next `resolve` (`Safepoint.scala:101-102`), and
`get()`/`enter()` route there once a `Stop` breaks the identity test, so map-driven code parks one step after the CAS.

**One live bug on both sides.** `evalNow` reports a settled `null` as `Absent` (`Maybe(null)` is `Absent`) and
`isNull(next)` means "no remainder", so a fiber whose result is `null` never completes. Fix: a private sentinel.

## Rulings

- **R1**: `deadlineMillis` on `Eval.partial`, or JS and Wasm fibers lose time slicing. Recommend the parameter.
- **R2**: spend one `depths(slot)` read-modify-write per answered operation to close gap 2, conditional on the JMH
  A/B the `Eval.scala` gate requires. Recommend yes if the answering rows stay allocation-flat.
- **R3**: whether the bracket abandonment entry is `Unit`-returning with synchronous releases, or returns a
  computation the fiber drives. Recommend synchronous, which is already the baseline contract: `Sync.ensure`
  evaluates every finalizer through `evalOrThrow` (`origin/main:kyo-core/.../Sync.scala:111`).
- **R4**: confirm `context` stays a `def` with the conditional-subclass factory, so the footprint trick survives
  whatever the context track lands.
- **R5**: kernel2 gains region-peeling `dispatchFirst`, or the child-fiber leak it prevents is accepted. Recommend
  adding it, alongside the in-flight `handleFirst`.
- **R6**: `Safepoint.stop` gets a cheap negative. A `null` entry probing forward from `home` proves the thread owns
  no slot (entries are never written back to `null`, `claim` takes the first free index); today a live thread with
  no slot costs 65536 volatile reads on the interrupter's thread. Recommend adding it.

Three requirements on the bracket primitive, shape-agnostic between the node and handler encodings. **R-B1**: an
abandonment entry running a never-to-be-evaluated remainder's outstanding releases innermost-first, exactly once,
with the fiber's error; shape decided by R3. **R-B2**: a throw escaping `Eval.partial` must already have run the
releases of what it unwound, since IOTask holds only the stale slice-start `curr` on the fatal path and can neither
find brackets acquired during the slice nor avoid double-running released ones. **R-B3**: a `Loop.done` that
truncates must run the discarded regions' releases; the boundary `Abort` clause has to truncate, and discarded
exits do not run today (`Eval.scala:84-85`). R-B2 and R-B3 are not forks, they need confirmation.

## Status

Design only, nothing implemented; IOTask at HEAD does not compile against kernel2. Full analysis, with the baseline
comparison and the six red-first pins, in `iotask-kernel2-integration-r2.md`.

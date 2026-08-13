# kernel2 backlog

Living technical brief; updated as agents finish and rulings land.
Last update: 2026-08-12, HEAD `b4f91612ba`. Suite: kyo-kernel2JVM 629 passed, 1 ignored
(the deliberately disabled map bytecode pin), 0 failed.

## 0. Session rulings (this conversation, binding)

1. **Bracket is not served via Isolate.** The Scope-as-stateful-effect-plus-isolate
   direction was explored end to end and dropped: the A-to-B hop (release on a
   short-circuit that truncates the scope's region) is the inexpressible core, and
   placement discipline cannot guarantee it. The bracket track returns to the three
   candidate docs (section 3), judgment pending.
2. **Isolate stays as-is**: the old kernel's design verbatim (`Isolate[Remove, Keep,
   Restore]`, `type State`, `type Transform[_]`, capture/isolate/restore/nest/run/use/
   andThen, per-effect policy instances, derive for intersections, no instances for
   Abort/Choice). The Join-effect substitution for `Transform[_]` was designed in full
   (Join[State] as the protocol-state carrier, region-with-detached-exit framing,
   structural emission, failure delivery) and set aside; the exploration is recorded in
   this conversation and in `isolate-kernel2-design.md` for whenever the port begins.
3. **The old Safepoint Interceptor stays dead** (ruled earlier; restated for the record).

## 1. Landed implementation

### 1.1 handleFirst and handleCatching (commits `90d1a4c285`, `d830408a48`, `38bc835fb8`, `d5e85efc4c`)

`handleFirst` is a fourth handler kind, not an encoding over the existing three. The
no-reinstall property is structural: the evaluator continues the clause's result at
`node.prev`, so the cell is off the spine before the continuation it handed out can
raise the effect again, and the continuation is `rebuild(hsAll, node, ...)`, which
excludes the cell by construction. One allocation per region: the object is both the
handler and the region node.

```scala
inline def handleFirst[I[_], O[_], E <: ArrowEffect[I, O], A, S, B, S2](inline _tag: Tag[E], v: A < (E & S))(
    inline f: [X] => (I[X], O[X] => A < (E & S)) => B < S2
)(inline done: A => B < S2)(using inline _frame: Frame): B < (S & S2)
// the continuation keeps E in its row; it is a value: resumable later, more
// than once, or not at all. done is the exit when no operation ever arrives.
```

`handleCatching` is composition, not machinery: `handle` over `Effect.catching(v)(recover)`
with every clause invocation also wrapped. Its boundary is pinned by test: a nested
region's interior is not covered (the same boundary `Effect.catching` draws; see the
guarded TODO in 4.1).

New machinery underneath: `Handler.First` kind, `Kyo.HandledFirst` node with the fused
map, `FirstNode` cell wired through find/rebuild/replace, bytecode pins for the cell,
node, and clause boundaries, and a module-guide section. Tests: 223 lines ported and
new; suite 617 then 629 green across the three commits.

### 1.2 Earlier this session (validated, boards in `compile-execution-analysis.md`)

The user's map/flatMap local-run shape (eager path allocates no Transform by
construction), the fused `Safepoint.enter()` with the unboxed denied sentinel, and the
nested-defer regression pins. Compile fixtures: new kernel beats old on all three
symptomatic fixtures (MapChainDeep100 0.39x, ForCompDeep25 0.85x, ForComprehensions
0.86x). Runtime: both candidate violations closed; deepRecursion 1.05x time watch item;
fusionAfterSuspension 1.84x standing structural position.

## 2. Landed designs, author-written sections

## Exception enrichment

Failures carry effect-level frames again, reconstructed from the live chain at the catch rather
than recorded as they run. No `Safepoint` field, no ring, no pool, no per-platform file.

### Mechanism

The walk, entered from a catch with the loop's own `v` and `hs`, emitting innermost first.

```scala
// role-tagged entry: Suspend.map's product IS an Arrow.AndThen (KyoInternal.scala:70),
// handleWith's IS a Transform + Handler.Cont + Handled (ArrowEffect.scala:89).
private def reconstruct(v: Any < Nothing, hs: Handlers, out: Builder): Unit =
    v match                                    // 1. the failing node's own frame
        case s: Kyo.Suspend[?,?,?,?,?,?] => emit(s.frame); walkArrow(s.cont, out)
        case d: Kyo.Defer[?,?,?]         => walkArrow(d.cont, out)   // carries none
        case _                           => ()
    cells(hs)  // 2. @tailrec outward: emit handler.tag.show, walkArrow(exit), prev

// AndThen -> push b, push a; Step -> emit head.frame, push tail; Transform -> emit frame.
// Worklist, never `a.step`: that clears the shared scratch (Arrow.scala:42-44, 113-114) and
// mints a Step per node. Frame.internal skipped by identity (Frame.scala:126-135).
private def walkArrow(a: Arrow[?,?,?], out: Builder): Unit
```

Frames out: `Arrow.Transform.frame` (`Arrow.scala:70`) per step, `Kyo.Suspend.frame`
(`KyoInternal.scala:48`, via `root` at `:51, 65`, so the operation's site not an intermediate
`map`'s), `Handler.tag` (`Handler.scala:10`) per region. The op label is `Frame.calleeName`
(`Frame.scala:67-80`), so its `(String, Frame)` pair goes. The cap is the walk's carrier.

The carrier keeps the deleted type and constructor form (`5d5d3e613f^:.../EffectTrace.scala:11`):

```scala
final private[kyo] class EffectTrace extends Exception(null, null, false, false):
    var elements: Chunk[StackTraceElement] = Chunk.empty   // already synthesized
    var dropped:  Int                      = 0
```

Three roles: idempotence marker (presence in `getSuppressed`), accumulation across nested
drives, programmatic read (`getMessage`, plus `fiberTrace`). No `installed` cursor: a
reconstruction is written once, never revised. Not per-step accumulation: `evalLoop` starts every
drive at `Empty` (`Eval.scala:118`), so an inner drive's `hs` excludes the outer's regions and
each crossed boundary appends outward, bounded by drive nesting plus guard count.

The splice, once, at the outermost kernel exit.

```scala
ex.setStackTrace(carrier.elements.toArray ++ ex.getStackTrace.filterNot(kernelPlumbing))
```

Order matters: the old kernel found its splice point by matching file name and line against the
first synthesized element (`origin/main:.../Trace.scala:194-196`), which is what fails on JS and
why three `TraceTest` cases are `pendingUntilFixed`. Leading with the synthesized frames leaves
no position to find, so the JS cases become real assertions. `kernelPlumbing` covers
`kyo.kernel.` and `kyo.Arrow` (the prototype's `:55` predicate is short now), never user anons.

Attach at every guarded arm, splice at the two exits: the drive boundary (`Eval.scala:15-33`) and
`catching`'s arms before `f(ex)` (`Effect.scala:20, 44`), where the old kernel put
`Safepoint.enrich` (old `Effect.scala:50, 59`). Skip fatal (tested inside `attach`, not as a catch
guard, so propagation is unchanged) and `NoStackTrace` (`KyoException.scala:26`); `Abort` failures
are values and never reach a catch site.

The `Eval` arms: per-arm `try` excluding the tail call, so `@tailrec` holds, no pair return.

```scala
case kyo: Kyo.Defer[Any, Any, Any] @unchecked =>
    val next =
        try walk(kyo.cont, kyo.value)
        catch case ex: Throwable => EffectTrace.attach(ex, kyo, hs); throw ex
    loop(next, hs)
```

Six regions cover the handler clauses (`Eval.scala:51, 73, 92`), the dispatches
(`:65, 83, 98, 116-117`), and the unhandled-suspension throw (`:48`). Nothing on the non-throwing
path; the cost is `kyo` and `hs` live into the handler. Allocation comes back byte-identical.

### Findings

**Accumulate-at-catch is dominated here.** Two `try/catch` sites exist module-wide
(`Effect.scala:19-20, 43-44`); the prototype's fidelity came from the per-transform catches in
`Arrow.Step.run` and `guardedRun` (`5d5d3e613f^:.../Arrow.scala:73, 123`), so restoring it puts a
`try/catch` in every inlined per-site expansion. At the sites that exist it yields one frame per
boundary, a strict subset of the walk there.

**The physical stack is complementary, not redundant.** `javap` on the tree's compiled tests
confirms the per-site Transform is an anon class in the user's own compilation unit
(`kyo.kernel.EffectTest$$anon$1`, `LineNumberTable line 54`), step body lifted onto the user's
class at the same line. Fused segments already read as user source; the walk covers only what
crosses a suspension, a `Defer` bounce, or region nesting.

**Honestly lost: pre-suspension history.** An applied continuation arrow is unreachable, so no
try/catch architecture recovers steps from before a suspension; the old ring did, at 16.

**`fiberTrace` unifies.** The same walker over `curr` replaces `curr.toString`
(`kyo-core/.../IOTask.scala:53-63`) and unignores `IOTaskTest.scala:12, 38, 57, 81, 106`. Keep the
throw containment: `curr` is mutable even though everything it points at is not.

### Rulings

1. **Name.** Reuse `EffectTrace` (ruled at `43d5c021a7`), or split a second name since the walker
   is also `fiberTrace`. Recommend reuse.
2. **Pre-suspension history.** Accept the loss, or the ring returns in some form. Recommend
   accept: a stack trace never shows returned calls, and the walk gives the active chain.
3. **`NoStackTrace`.** Skip splice and carrier both, or skip only the splice so the frames stay
   readable as data on a `KyoException`. Recommend skip only the splice.
4. **Cap.** One total cap, drop-newest (the walk just stops), or a reserve so region cells always
   emit when a deep chain fills the budget. Recommend drop-newest at 64.
5. **`addSuppressed` on JS and Wasm.** Proven on JVM and Native only; fallback is splice-only,
   costing idempotence detection and the programmatic read. Ruling needed only if the link fails.
6. **`Debug.trace`.** No drive hook needed, but `catching`'s one-layer rewrite
   (`Effect.scala:28-76`) must be exposed taking a per-step function, of which `catching` becomes
   an instance. Approve that one name, or drop `Debug.trace`; it survives suspensions, not forks.
7. **Arm scope.** Six regions, or three (`:51, 92, 48`) if the board moves. Confirm the set before
   measuring so the A/B answers the right question.
8. **Pre-existing, unrelated.** `Eval.apply` and `Eval.partial` (`Eval.scala:15-33`) call
   `Safepoint.save`/`restore` with no `try/finally` and `partial` arms the slot at `:28`, so an
   escape leaves the thread's budget and armed bit as the aborted drive left them. Own fix.

**Status:** design only, nothing implemented. Full argument, attach-point inventory, and test
plan in `exception-enrichment-design.md`.

## Isolate: fork as a transplant of the handler stack

The stack subsumes `capture` and `isolate` and deletes `Context`. It does not subsume `restore`
(joiner-side by nature) or the static witness (the row is not the stack).

### Mechanism

A fork is `rebuild` with the exits neutralised. Reaching `hs` from a user site needs one marker,
answered in the arm that already answers `Defaulted`: no hot-dispatch cost, `root` delegation free.

```scala
// KyoInternal.scala, beside Defaulted (:39-41)
private[kyo] trait Detached:
    self: Suspend[?, ?, ?, ?, ?, ?] =>
    def child: Any
// Eval.scala, the find-miss arm (:43-49), third case unchanged
case Empty =>
    kyo.root match
        case d: Kyo.Defaulted => loop(resume(kyo.cont, Nested.lift(d.default)), hs)
        case d: Kyo.Detached  => loop(resume(kyo.cont, Nested.lift(transplant(hs, d.child))), hs)
```

`Nested.lift` is the point: `Kyo <: Boxed` (`KyoInternal.scala:26`), so the fork site gets the
onion-wearing child as data, not as something this drive continues into.

```scala
// Eval.scala, beside rebuild (:241-251)
@tailrec private def transplant(top: Handlers, acc: Any < Nothing): Any < Nothing =
    top match
        case Empty => acc
        case n: Node[[X] =>> Any, [X] =>> Any, Nothing, Any, Any] @unchecked =>
            if n.handler.fork == Fork.Skip then transplant(n.prev, acc)
            else transplant(n.prev, Kyo.Handled(acc, n.handler, Arrow[Any]))
        case n: StateNode[[X] =>> Any, [X] =>> Any, Nothing, Any, Any, Any] @unchecked =>
            if n.handler.fork == Fork.Skip then transplant(n.prev, acc)
            else transplant(n.prev, Kyo.HandledState(acc, n.handler, Arrow[Any], n.handler.forkState(n.state)))
```

`Arrow[Any]` instead of `n.exit` is load-bearing: under the `*With` variants the exit **is** the parent's
post-region continuation (`ArrowEffect.scala:89-104, 139-155, 199-217`), so a verbatim copy has the child
running the rest of the parent's program on completion. Price: `RebuiltNode`'s identity re-entry
(`Eval.scala:99-112`) stops applying, one cell per inherited layer per fork. As a pair, **`rebuild`
preserves exits and bracket cells, `transplant` neutralises exits and drops brackets**, so double-release
is unrepresentable. Fiber-owned layers wrap inside the detach: inherited cells land outermost.

```scala
// Handler.scala
sealed trait Handler[I[_], O[_], E <: ArrowEffect[I, O], A, -S]:
    def tag: Tag[E]
    def fork: Handler.Fork = Handler.Fork.Inherit   // Inherit | Skip only
trait LoopState[...] extends Handler[...]:
    def forkState(state: State): State = state      // Emit/Check return their empty
```

Two cases, not three: "snapshot" is already the representation (immutable cells, `withState`
successors), and a "bar" is a compile error in the witness, not a runtime throw. `Noninheritable`
becomes `Skip` from one tag test at region construction, replacing both `Context.inherit`'s filter
scan and the `NoninheritableFlag` sentinel (origin/main `Context.scala:30-36, 45-51`).

```scala
abstract class Isolate[Remove, -Keep, -Restore]:
    type Transform[_]
    def isolate[A, S](v: A < (Remove & S))(using Frame): Transform[A] < (Remove & Keep & S)
    def restore[A, S](v: Transform[A] < S)(using Frame): A < (Restore & S)
def update[V](using Tag[Var[V]]): Isolate[Var[V], Any, Var[V]] =   // Var.isolate.update
    new Isolate[Var[V], Any, Var[V]]:
        type Transform[A] = (V, A)
        def isolate[A, S](v: A < (Var[V] & S))(using Frame) = Var.use[V](s => Var.runTuple(s)(v))
        def restore[A, S](v: (V, A) < S)(using Frame)       = v.map(Var.setWith(_)(_))
```

`State`, `capture`, and `andThen`'s state pairing are gone: the `Var.use` runs *in the child* against
the inherited cell, so the phase disappears rather than moves. Capture ordering becomes the runtime
nesting order, not `deriveImpl`'s intersection flattening order, which is why the Abort/Choice
ordering hazard (origin/main `Isolate.scala:70-71`) does not reproduce here.

### Load-bearing findings

**`done` gates `Fork.Inherit`.** A cell's `done` produces the *region's* result type (`Handler.scala:19,23`,
`Loop.scala:84,95`) and, with a neutral exit, becomes the child's result: a transplanted `Abort.run` cell
hands a `Result[E, A_parent]` where the child's `A2` was expected. The handlers that never `done` are
exactly the effects origin/main gave instances to; the ones that can are exactly the ones it refused.
Recommend an answering handler kind (no done branch in the clause type) over a declared `fork = Inherit`
that is a standing per-edit obligation. That kind needs the state-aware region exit kernel2 lacks:
origin/main's stateful `handleLoop` takes `done: (State, A) => B` (`ArrowEffect.scala:530-541`), worktree
exits with `Arrow[A]` (`ArrowEffect.scala:176`). That gap blocks `Var.runTuple`, `Emit.run`, and
`Check.runChunk` independently of this design and is not in the parity gaps.

**Restore must ship boxed.** A restore mapped into the fiber stays pending today only because the fiber
has no handler for it; under inheritance it has one, answers its own write-back, and drops it. Every
kyo-core consumer already restores joiner-side (`Async.scala:124-125, 226-227, 274-275, 384-386,
412-416`); the counterexample is `Fiber.initUnscoped` (`Fiber.scala:174-179`), reached with an identity
isolate by those consumers, so it has not bitten. Fix: `nest` semantics, box it (`EvalTest.scala:352-371`).

**Inheriting a cell shares the handler closure**, which origin/main never did (its instances always ran a
fresh handler in the child). Everything else shipped is immutable: kernel2 main has one `var`
(`Eval.Suspended.rest`, thread-confined) and `depths` is owner-thread-only.

**Context reads.** Every suspension pays `find` (`Eval.scala:42`), and a `Tag <:<` miss costs a
`Thread.currentThread()` plus a hashed cache probe per cell (`Tag.scala:173-184, 380-405`). Worse,
the layered `ContextEffect.handle` raises a `probe` per read (`ContextEffect.scala:74`), so N nested
`Local.let` cost N walks per read. Library-only fix: resolve the outer value once at region entry
and hold it as the cell's state, making a read one `find`. `ifDefined` is pure at every call site so
per-entry versus per-read is unobservable; parks and multi-shot are pinned already. No JMH run.

### Rulings

1. **Answering handler kind, or declared `fork` policy?** Structural soundness at the cost of two
   handler kinds plus rewriting Var/Emit/Check/Memo/ContextEffect, versus smaller now and the
   obligation forever. Recommend the kind.
2. **Does `Fiber.initUnscoped` keep an inward restore?** All-restores-joiner-side (one line) versus
   boxed tunnelling with the payload type documenting it. Both sound; recommend joiner-side.
3. **Inherit by default, or opt in per fork site?** Inherit makes the `discard` policies free
   instances; skip matches origin/main's arrow-effect behaviour. Recommend inherit.
4. **Naming** under the no-new-terminology rule: `detach`, `transplant`, `Fork.Inherit`/`Skip`,
   `forkState`, the answering kind, and whether `Isolate` keeps its name. No recommendation.
5. **State-value publication: documented or enforced?** No effectively-immutable evidence exists in
   the tree and origin/main documented it. Recommend documented, on the forkable kind.

Status: design only, nothing implemented, no benchmark run. Full analysis in `isolate-kernel2-design.md`.

**Status note on the isolate section above (post-ruling):** the fork-transplant
mechanics, the done-soundness finding, the restore-must-ship-boxed finding, and the
context-read cost analysis remain the reference for porting Isolate onto kernel2. Its
policy proposals (Handler.fork declarations, inherit-by-default) are superseded by
ruling 0.2: the old Isolate design is retained as-is; the session's soundness analysis
(implicit inheritance only for ContextEffect; explicit instances gate stateful
effects; control-flow effects excluded) stands as the reading of that design.


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

## 3. Bracket: three candidate designs, judgment pending (task #73)

All three docs at `db363ae396`; the contract they carry: use fully interruptible; no
interruption between acquire finishing and use starting (resource held implies
obligation reachable); release always executes on settle, unwind, and discard, failures
suppressed onto the primary; release runs to completion once started. The drive-era
prototype is failure-modes-only reference. IOTask r2 adds R-B1 (Unit-returning
synchronous discard entry), R-B2 (a throw escaping Eval.partial already ran the
releases it unwound), R-B3 (a Loop.done truncation runs the discarded cells'
releases).

### 3.1 `bracket-node-design.md`: Kyo.Bracket node

Core: **the node exists exactly when the resource exists.** Acquire is an ordinary
effectful computation chained into one strict `open` transform that reads the
resource, allocates the obligation (with a one-shot claim), and allocates the node
with no budget check between: there is no value in the currency meaning "acquired, no
obligation", so parks, rebuilds, and discards preserve the pairing by construction.
No mask, none proposed. Effectful acquire works gap-free (suspensions inside acquire
park safely; nothing exists yet). Release: one helper over spine segments covers
settle, done-truncation, Cont/First capture, and unwind; the discard entry folds the
node onion. Weakest point, stated by the doc: a bracket cell carried off the spine by
a captured Handler.Cont continuation; when it releases is a value fork.

### 3.2 `bracket-handler-design.md`: Handler.Bracket kind

Core: LoopState plus `release(state, outcome)`; obligations accumulate as region
state via a register operation; any effect can be a bracket effect, so kyo-core's
Scope is a direct instance. Two honest self-findings: the seed's drive-local mask
counter is broken (lost when an async acquire parks; nothing reinstates it), so the
mask must be a cell on the spine (survives parks structurally); and the stop check
must skip, never consume (consuming discards the scheduler's request). Release has
one implementation (the settle arm, release-before-exit); unwind is the per-arm try
regions shared with enrichment; discard is the evaluator driving the remainder with
exits neutralized; R-B3 falls out of the settle arms. Weakest points: every operation
inside a bracket region pays extra find iterations for the region's lifetime (for a
program-wide Scope, every suspension in the program), a registration round trip per
resource, and the most new shapes (two node classes, two cell classes, arms in five
places).

### 3.3 `bracket-effect-design.md`: expressivity boundary (the probe)

Verdict: **not expressible with the current algebra**, boundary stated exactly:
knowing which releases are outstanding at a discard is expressible (a registry region
below the bracket survives truncation with state intact); running them AT the discard
is not, because the only event the algebra gives a region after a truncation is its
own exit, strictly later than everything composed onto the truncating region. A
Handler.Cont clause that never invokes its continuation is undecidable in any design
(the continuation is a value, callable zero or n times later). Minimal kernel assist:
**a per-cell disposal hook run over the cells a truncation discards, plus one catch
site at the evaluator's boundary; that assist is the essential content of both
sibling designs, everything else is convenience.** Tier table rows: normal
completion, release-before-downstream, and nesting are a library today (a region
whose exit is the release); deferred release at a surviving registry is old-kernel
tier and needs the state-aware exit; in-band release on discard and throws inside
nested region bodies are kernel-tier.

### 3.4 Judgment inputs

Convergences across all three plus the TODO analysis: the shared walk (one mechanism,
four consumers: bracket unwind, Loop.done truncation per R-B3, catching-as-a-region,
enrichment frames); the state-aware region exit keeps surfacing as an unlisted parity
gap (Var.runTuple, Emit.run, Check.runChunk blocked on it); multi-shot capture across
a bracket needs a semantic ruling in every design. Decision axes: acquire-gap
mechanism (node existence, representation-held, vs mask-cell machinery) and
steady-state cost (node free on the hot walk vs handler taxing find for every open
region). Session lean (not ruled): the node for the primitive, Scope built above it;
the handler doc's fair counter is that the state-aware exit kind is coming anyway.

## 4. Kernel2 TODOs: designed solutions (`kernel2-todos-design.md`, task #78)

### 4.1 Effect.guarded: catching becomes a region

Diagnosis confirmed the maintainer's "expensive": per resumed step under catching,
one guard Transform allocation, one node re-allocation across a five-way match, one
cont.step flatten. And a semantic hole: the rewrite reaches exits but not region
interiors (pinned by test; a user cannot predict it from the API). A plain evaluator
try cannot replace it because a guarded residual must carry its scope across drives
(pinned: a park under Eval.partial recovered in a second drive), so the scope must be
a value; the only structure that parks, rebuilds, and re-enters is a region. Design:
`Kyo.Caught` region node plus a passive catch cell; catching allocates one object;
guarded and its per-node arms are deleted; the nested-interior hole closes (a nested
region sits inside the catch region). The failure path is the shared walk of 3.4.

### 4.2 Handlers: one cell layer, and the tag hoist is a hot-path win

The cell kinds are enumerated in seven live places (nine with in-flight tracks); the
FirstNode addition touched all seven. Design: `Cell(exit, prev)` base with
`withPrev`/`rebuilt`; `Answering(tag, ...)` vs `Passive(...)` under it; the generic
walks (find, rebuild, replace, pop) read the base and stop enumerating kinds; only
operation dispatch stays per-kind. The hoisted tag turns find's per-cell megamorphic
`handler.tag` call (fresh anonymous class per handle expansion) into a field read:
encapsulation and a find speedup in one change. JMH-gated; sharedHandlerPaysDispatch
is the row to watch. Passive waits for the bracket judgment.

### 4.3 Kyo.Defaulted: the row lies and the payload is untyped (task #31 input)

Defaulted's `default: Any` is a scar from deleting the Context map: definedness
became an untyped probe. Two needs separated: optional context (Local.get with
fallback) and definedness (Env.run union). Design: `ContextEffect[+A] extends
ArrowEffect[Const[Unit], Const[Maybe[A]]]` so the answer carries its own
definedness, plus a typed `Unhandled` marker (`unhandled: O[X]`) replacing Defaulted
in the find-miss arm. Sets the pattern the isolate exploration's Detached marker
assumed.

### 4.4 Loop.continue/done: bare Outcome, matching origin/main

The `Outcome[..] < S` return type was an inference workaround from the lower-bound
era; it broke signature parity (extra type parameter on every constructor) and forced
the explicit Nested.lift. Design: bare returns exactly as origin/main; the lift moves
to use sites where the conversion already fires; ten runner sites inside Loop.scala
adjust. Evidence it type-checks: origin/main has the identical currency, bare
returns, a more restrictive lift, and 1,750 call sites; must be settled by a compile
before landing. The one live parity break in the set; ordered first in the fix plan.

Fix plan order (dependency only): Loop constructors; the cell layer minus Passive;
then the bracket judgment rules the walk and Passive together (its outcome also
decides guarded-as-region's walk and the enrichment arms).

## 5. Implementation queue

1. Bracket judgment (task #73): rule over 3.1-3.3 with 3.4's axes; the ruling should
   decide the shared walk, since catching-as-region (4.1), enrichment, and R-B3 are
   its other consumers.
2. Loop bare-Outcome fix (4.4): independent, live parity break, compile-gated.
3. Handlers cell layer minus Passive (4.2): hot-path win, JMH-gated.
4. dispatchFirst (IOTask r2 R5): region-peeling private[kyo] entry, lands beside
   handleFirst.
5. Exception enrichment implementation per its rulings 9.1-9.7; un-ignores the five
   fiberTrace tests.
6. Isolate port (as-is per ruling 0.2) once its two kernel prerequisites exist: the
   state-aware region exit and, for parity of the old capture semantics, nothing else;
   the isolate-kernel2-design.md mechanics are the reference.
7. Eval.partial deadline (R1), settled-answer budget charge (R2, JMH-gated),
   Safepoint.stop cheap negative (R6).
8. IOTask port per iotask-kernel2-integration-r2.md section 5; consumes 1-7. Then
   tasks #9 and #11.

## 6. Standing open items

| item | pointer |
|---|---|
| kyo-bench arena rows old vs new kernel (task #8) | kyo-bench module; KernelBench boards both kernels |
| Safepoint overflow one-shot report decision (task #57) | internal/Safepoint.scala |
| Effect.guarded placement discussion (task #59) | resolved by 4.1's design pending the walk ruling |
| Fold Implicits back into Pending.scala for layout parity | kernel-parity-gaps.md section 2; user call |
| Remove stray empty dir kyo-kernel2/kyo-kernel2/ | hygiene |
| JS/Native/Wasm compile check of kernel2 | kernel-parity-gaps.md section 4 |
| Pre-existing: Eval.apply/partial save/restore without try/finally | enrichment section, finding 8: an escape leaves budget and armed bit as the aborted drive left them |

## 7. Accepted or watched performance positions

- Boards and method: `compile-execution-analysis.md` (in-session A/B tables),
  `node-fusion-report.md` (mandate and fusion-era boards).
- Compile time: new kernel beats old on all measured fixtures (MapChainDeep100 0.39x,
  ForCompDeep25 0.85x, ForComprehensions 0.86x).
- fusionAfterSuspension 1.84x time / 1.16x alloc: standing structural position; both
  kernels allocate two objects per map on an unanswered suspension; the delta is one
  reference per map plus resume-side costs the old kernel pays back. RunOnly exempt
  by ruling. JFR attribution in the session record.
- deepRecursionPaysRescuesOnly 1.05x time against 0.43x alloc: watch item; rescue-path
  time moved with the candidate design; next optimization target if the perf campaign
  reopens.

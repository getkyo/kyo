# kernel2: the fork-boundary API (item #13)

Scope: replace `Isolate.internal.runDetached`'s `(Trace, Context) => A < S` callback with an
API designed from what the real consumers (kyo-core's `Fiber` and `kyo.scheduler.IOTask`)
actually do at a fork. Analysis and design only; no code changed.

Two parallel tracks bind this design and are treated as fixed inputs:

- Track B (context threading, `kernel2-context-threading-design.md`): the drive knows the
  current `Context`, `Kyo.ContextSnapshot` and `Kyo.ContextRead` do not exist, and a context
  read is a `Defer` node the drive resumes with the context. This design states what it needs
  from that track as an interface (section 4.2) and does not design it.
- The `Trace` placeholder class is deleted (item #12). kernel2's trace mechanism is the
  `EffectTrace` suppressed-exception carrier, which attaches frames to a failure as it unwinds
  (`kyo-kernel2/shared/src/main/scala/kyo/kernel2/internal/KyoException.scala:31-44`), so
  there is no per-thread trace ring to save and restore. Section 8 says exactly where the
  per-fiber trace round plugs back in without changing any signature proposed here.

---

## 1. Recommendation in one page
wtf where's the context of this!?
```scala
// kyo-kernel2/shared/src/main/scala/kyo/kernel2/Isolate.scala, inside `private[kyo] object internal`

/** The ambient effect state a forked computation inherits from its creator.
  *
  * Produced by [[detach]] at the fork point and applied with [[attach]] to each computation that
  * will run detached. Holds the creator's context bindings, resolved to values and filtered of
  * the noninheritable ones, so a detached computation observes what its creator observed.
  */
final private[kyo] class Detached private[kernel2] (private val context: Context, private val frame: Frame):

    /** Transplants the captured bindings onto a computation that will run detached.
      *
      * The bindings are installed outermost, so the computation's own handlers shadow them, and
      * they travel with the computation: a slice that parks it keeps them in the remainder.
      */
    private[kyo] def attach[A, S](v: A < S): A < S =
        if context.isEmpty then v else Kyo.withContext(context, frame)(v)

private[kyo] object Detached:
    /** Inherits nothing. The entry point for a fork with no creator to inherit from. */
    private[kyo] val empty: Detached = new Detached(Context.empty, Frame.internal)

/** Captures the ambient effect state at this point, for transplanting onto forked computations. */
private[kyo] def detach(using frame: Frame): Detached < Any =
    Kyo.readContext(context => new Detached(context.inherit.resolve, frame))
```

The fork site maps over one value and can build any number of children from it:

```scala
Isolate.internal.detach.map { detached =>
    ...
    IOTask(detached.attach(child), parent)
}
```

Three properties are what make this the right shape rather than a smaller one:

1. It is a value, not a callback, because the only reason for the callback was that the
   snapshot could be produced solely at a drive. Once a context read is an ordinary node,
   "produced at a drive" is what `A < Any` already means, and `.map` is the composition.
2. It is a nominal type, not a bare `Context`, because the value carries an invariant (resolved
   and inherit-filtered) that a raw in-flight context does not have, because `attach` is the
   only correct way to consume it, and because the trace round adds a field to it without
   touching a single consumer signature.
3. `attach` transplants into the computation rather than into the task, so `IOTask` holds no
   context, the drive takes no context parameter, and a fork nested inside a fork inherits by
   construction (section 5.3).

---

## 2. Jobs to be done, from the old kernel's consumers

Every job below is read off the current code, with citations. J1 to J9 are what a fork boundary
must serve; J10 and J11 are jobs the old boundary served that kernel2 answers elsewhere, and they
are listed because leaving them implicit is how they get dropped at the swap.

### J1. Materialize the creator's visible context bindings at the fork point
I asked to design a better approach?
`kyo-kernel/shared/src/main/scala/kyo/kernel/Isolate.scala:227-232`:

```scala
private[kyo] inline def runDetached[A, S](inline f: (Trace, Context) => A < S)(using inline _frame: Frame): A < S =
    new KyoDefer[A, S]:
        def frame = _frame
        def apply(v: Unit, context: Context)(using safepoint: Safepoint) =
            f(safepoint.saveTrace(), context.inherit)
```

The boundary is a `KyoDefer` precisely so the context arrives as the drive's threaded parameter.
kernel2 reproduces the same late resolution through a suspension instead
(`kyo-kernel2/shared/src/main/scala/kyo/kernel2/Isolate.scala:107-109`, resolved at
`Pending.scala:575-576` only when `boundary` is true).

### J2. Filter noninheritable bindings at the fork, and only there
no, this will leak
`context.inherit` in the same expression; `Context.inherit` at
`kyo-kernel2/shared/src/main/scala/kyo/kernel2/internal/Context.scala:32-38`. The oracle is
`kyo-kernel2/shared/src/test/scala/kyo/kernel2/IsolateTest.scala:173-192` and, for the
downstream meaning, `kyo-prelude/shared/src/test/scala/kyo/LocalTest.scala:114-155`, which also
pins that a fork nested inside a fork filters the bindings the inner scope installed
(`LocalTest.scala:131-154`).

### J3. Resolve derived bindings to values before they cross
tha'ts not what I'm saying. Did you fucking see the context param threading in the old kernel!?
`snapshotContext` folds each tag's transforms outermost to innermost so the snapshot holds
values, not transforms (`Pending.scala:582-630`, in particular the fold at
`Pending.scala:617-628`). This is load bearing: a transform installed by
`ContextEffect.handle(tag, ifUndefined, ifDefined)` (`ContextEffect.scala:78-88`) derives its
value from the binding outside it, and outside the fork there is nothing for it to derive from.

### J4. Capture the creator's trace, and give each child its own copy
wtf is this. DO NOT TOUCH anything outside of kyo-kernel2. And fucking no, we delted Trace!
`safepoint.saveTrace()` at the boundary (`kernel/Isolate.scala:232`), then per child
`safepoint.copyTrace(trace)` (`kyo-core/shared/src/main/scala/kyo/Fiber.scala:768`, `:801`,
`:914`), released when the task finishes (`kyo-core/shared/src/main/scala/kyo/scheduler/IOTask.scala:141-143`).
The pooled ring buffer these operate on is `kyo-kernel/shared/src/main/scala/kyo/kernel/internal/Trace.scala:121-146`.

### J5. Seed the child's drive with the inherited context, on every slice
fuck man... DO NOT touch outside of kyo-kernel2. That's your only freedom
`IOTask` holds the context (`IOTask.scala:19`, with the subclass mint that avoids a field when
the context is empty at `IOTask.scala:193-199`) and passes it into the drive of every slice
(`IOTask.scala:70`: `ArrowEffect.handlePartial(erasedAbortTag, Tag[Async.Join], curr, context)`).
A fiber can migrate between workers between slices, so this has to be re-established per slice
from state the task owns.

### J6. Restore ambient state on the worker thread, per slice

`IOTask.scala:69` wraps each slice in `Isolate.internal.restoring(trace, this)`, which is
`Safepoint.immediate(interceptor)(safepoint.withTrace(trace)(v))` (`kernel/Isolate.scala:234-240`).
It does two unrelated things: aliases the thread's trace ring to the fiber's trace, and installs
the `IOTask` as the safepoint's interceptor.

### J7. Identify the forking fiber, so a child can be linked for interrupt cascade

`Fiber.scala:752-755`, `:796-799`, `:909-912`, all identical:

```scala
val parent: Maybe[IOPromise[?, ?]] =
    safepoint.getInterceptor() match
        case p: IOPromise[?, ?] => Present(p)
        case _                  => Absent
```

consumed at `IOTask.scala:204` (`parent.foreach(p => p.interrupts(task))`) before the task is
scheduled. This is a fork-time read of "which fiber is running on this thread", served today by
the interceptor that J6 installed.

### J8. Build many children from one capture

`Fiber.internal.foreachIndexed` mints `numWorkers` tasks inside one boundary
(`Fiber.scala:750-774`), `Race.apply` one per element (`Fiber.scala:793-806`), `gather` one per
element (`Fiber.scala:905-919`). One boundary, N children, each child a separate task that must
inherit the same state.

### J9. Let arbitrary parent-side code run between the capture and the fork

`Fiber.initUnscoped` (`Fiber.scala:165-179`) runs `isolate.capture` inside the boundary callback
and returns whatever the callback returns, keeping the caller's effect row:

```scala
Isolate.internal.runDetached((trace, context) =>
    isolate.capture { state =>
        val io = isolate.isolate(state, v).map(r => isolate.restore(r))
        IOTask(io, trace, context).asInstanceOf[Fiber[A, reduce.SReduced & S2]]
    }
)
```

Note that the same pairing appears in the other order elsewhere: `Async.race`, `Async.gather`,
`Async.foreach` capture at the `Async` layer and the boundary happens inside `Fiber.internal.*`
(`kyo-core/shared/src/main/scala/kyo/Async.scala:226-227`, `:384-386`, `:412-416`). Both orders
are correct because `Isolate.capture` installs no context binding, so it cannot change what the
snapshot sees. Practically this means the public `Async` surface is untouched by this item; only
`Fiber`'s four sites and `IOTask` change.

### J10. Register and run finalizers for an abandoned computation

`Sync.ensure` registers with the interceptor (`kyo-core/shared/src/main/scala/kyo/Sync.scala:111`
into `Safepoint.ensure`, `kernel/internal/Safepoint.scala:169-193`, reaching
`interceptor.addFinalizer`), `IOTask` implements the registry (`IOTask.scala:32-36`) and runs it
when a slice ends with the promise already completed (`IOTask.scala:138-140`).

This job does not belong to the boundary in kernel2. Finalizers are chain resident: a bracket
leaves a `Finalize` node in the continuation (`Pending.scala:429-434`) and abandoning a
remainder runs them (`Pending.scala:105-116`, `discardValue`/`discardArrow` at
`Pending.scala:389-418`). Section 9 routes the residual delta.

### J11. Gate preemption and stack depth on the worker thread

`IOTask.enter` (`IOTask.scala:21-22`) is the interceptor hook the old safepoint consults per
frame. In kernel2 this is the Safepoint slice protocol owned by track A
(`kernel2-preemption-design.md:739-773`): the task arms a deadline with
`Safepoint.beginSlice(deadline)`, publishes the instance, and delivers requests through
`doPreempt`. There is no interceptor and nothing for the boundary to install.

---

## 3. What the job list looks like after the two tracks land

| Job | Old mechanism | kernel2 |
|---|---|---|
| J1 materialize context | drive-threaded `Context` in `KyoDefer.apply` | `Kyo.readContext` node resolved by the drive |
| J2 inherit filter | `context.inherit` at the boundary | unchanged, inside `detach` |
| J3 resolve derived bindings | `snapshotContext` fold | `Context.resolve` inside `detach` |
| J4 trace capture and per-child copy | `saveTrace` / `copyTrace` / `releaseTrace` | nothing yet; section 8 |
| J5 per-slice context seeding | `IOTask.context` into `handlePartial` | gone: the bindings live in `curr` |
| J6 per-slice restore | `restoring(trace, interceptor)` | gone: trace is failure attached, interceptor replaced by the slice protocol |
| J7 forking fiber identity | `safepoint.getInterceptor()` | re-homed in kyo-core; section 9.1 |
| J8 many children per capture | one `runDetached`, N `IOTask`s | one `detach`, N `attach` |
| J9 parent-side code after capture | callback returning `A < S` | `.map` on `Detached < Any` |
| J10 finalizers | interceptor registry | chain resident brackets plus `discard` |
| J11 preemption gate | interceptor `enter` | Safepoint slice protocol (track A) |

The boundary loses its restore side entirely. In the old kernel it was bidirectional (capture on
the creator's thread, restore on the worker's, every slice); in kernel2 it is one directional
(capture once, transplant into the computation, nothing per slice). That is the single largest
simplification available here, and it is what makes a plain value the right shape.

---

## 4. The proposed API

### 4.1 Signatures

```scala
package kyo.kernel2

object Isolate:

    private[kyo] object internal:

        /** The ambient effect state a forked computation inherits from its creator.
          *
          * Produced by [[detach]] at a fork point and applied with [[attach]] to each computation
          * that runs detached. The bindings it holds are resolved to values and filtered of the
          * noninheritable ones, so a detached computation observes what its creator observed and
          * nothing its creator marked as not crossing a fork.
          */
        final private[kyo] class Detached private[kernel2] (
            private val context: Context,
            private val frame: Frame
        ):

            /** Transplants the captured bindings onto a computation that will run detached.
              *
              * Installed outermost, so the computation's own handlers shadow them, and carried in
              * the continuation, so a parked remainder keeps them.
              */
            private[kyo] def attach[A, S](v: A < S): A < S =
                if context.isEmpty then v
                else Kyo.withContext(context, frame)(v)

        end Detached

        private[kyo] object Detached:

            /** Inherits nothing: for a fork with no creator in scope. */
            private[kyo] val empty: Detached = new Detached(Context.empty, Frame.internal)

        end Detached

        /** Captures the ambient effect state at this point, for transplanting onto forked
          * computations.
          *
          * Resolves at a boundary drive, the same late resolution a context read gets, so
          * bindings installed between construction and the fork are visible.
          */
        private[kyo] def detach(using frame: Frame): Detached < Any =
            Kyo.readContext(context => new Detached(context.inherit.resolve, frame))
```

`Trace` does not appear. `Context` does not appear in any consumer's source. No `null`, no
`Maybe` needed (there is no absent case: a creator with no bindings produces a `Detached` whose
`attach` is the identity). Return types are explicit.

### 4.2 What this needs from the context-threading track

Stated as an interface, not designed here. All three already appear in that track's design.

- `Kyo.readContext[A, S](f: Context => A < S)(using Frame): A < S`, a node the drive resumes with
  the context in scope at that point, resolved at boundary drives and parked at non-boundary
  drives (`kernel2-context-threading-design.md:274-295`, drive arm at `:311-323`).
- `Kyo.withContext[A, S](context: Context, frame: Frame)(v: A < S): A < S`, installing a group of
  bindings around a computation without discharging anything from its effect row. That track
  already needs this operation for its bracket fix (`kernel2-context-threading-design.md:489-501`)
  and calls the body of `attach` by that name.
- `Context.resolve: Context`, folding derived bindings into values, plus the existing
  `Context.inherit` and `Context.isEmpty`.

If the context track chooses a representation in which `resolve` is a no-op (for example if
bindings are materialized at installation), `detach` drops the call and nothing else changes.

### 4.3 Semantics and invariants

1. **Capture point.** `detach` resolves at the first boundary drive that reaches it, which is
   what `ContextSnapshot` does today (`Pending.scala:563-576`). A fork written inside an
   `ArrowEffect.handle` install drive therefore happens when the computation reaches a boundary
   drive, not at the syntactic position. This is existing behavior, preserved deliberately: it is
   what makes a binding installed after composition still visible to the fork.

2. **Filter then resolve.** `inherit` before `resolve` is cheaper (fewer frames to fold) and
   equivalent: noninheritable is a property of the effect type, so filtering never removes a
   frame that a surviving frame's transform needed.

3. **Attach is a runtime seeding, not a handler.** `attach[A, S](v: A < S): A < S` keeps the
   effect row. A context effect that the child still declares stays in its row and is answered at
   runtime by the inherited binding, exactly as `IOTask[Ctx, E, A]` keeps `Ctx` today while
   answering it from `IOTask.context`.

4. **Shadowing.** Inherited bindings are installed outermost, so a `ContextEffect.handle` inside
   the child shadows them, and a derived handler inside the child sees the inherited value as its
   outer value. This is the same nesting order the creator had.

5. **Survival across slices and across out-of-band resumes.** The installed node sits at the tail
   of the child's chain, so it is reached only when the child completes. A slice that parks
   returns a remainder that still contains it. The out-of-band path
   (`IOTask.scala:99-103`, `input.onComplete { r => curr = Sync.defer(cont(r)); schedule }`)
   resumes from the continuation the boundary clause was handed, which is the full chain
   including every traveling delimiter (this is the stated reason the boundary clause cannot be
   an installed delimiter, `kernel2-todo-analysis.md:284-290`), so the bindings survive it too.

6. **Nested forks.** A `detach` inside a detached computation reads the chain, which contains the
   attached bindings plus anything the child installed, and filters again. `IsolateTest`'s nested
   case (`IsolateTest.scala:60-76`) and `LocalTest`'s nested case (`LocalTest.scala:131-154`)
   are the oracle and pass by construction, without the child's task holding any context.

7. **A read with no binding.** Under the context track a boundary drive reports
   `bug("Missing value for context effect ...")` at the read rather than parking forever
   (`kernel2-context-threading-design.md:341-352`). For a fiber this converts a silent hang into
   a `Result.Panic` through `IOTask.eval`'s catch (`IOTask.scala:119-123`), which is the correct
   outcome and worth stating because the fork boundary is where an unbound read is most likely.

---

## 5. Consumer call sites, before and after

The `after` sketches assume the swap round, so they also reflect track A's `IOTask`
(`kernel2-preemption-design.md:745-773`) and the removal of `Trace`.

### 5.1 `Fiber.initUnscoped` (`Fiber.scala:165-179`)

Before:

```scala
Isolate.internal.runDetached((trace, context) =>
    isolate.capture { state =>
        val io = isolate.isolate(state, v).map(r => isolate.restore(r))
        IOTask(io, trace, context).asInstanceOf[Fiber[A, reduce.SReduced & S2]]
    }
)
```

After:

```scala
Isolate.internal.detach.map { detached =>
    isolate.capture { state =>
        val io = isolate.isolate(state, v).map(r => isolate.restore(r))
        IOTask(io, detached).asInstanceOf[Fiber[A, reduce.SReduced & S2]]
    }
}
```

### 5.2 `Fiber.internal.foreachIndexed` (`Fiber.scala:750-774`)

Before:

```scala
Isolate.internal.runDetached { (trace, context) =>
    val safepoint = Safepoint.get
    val parent: Maybe[IOPromise[?, ?]] =
        safepoint.getInterceptor() match
            case p: IOPromise[?, ?] => Present(p)
            case _                  => Absent
    @tailrec def loop(i: Int): Unit =
        if i < numWorkers then
            def workerLoop(): Unit < (Abort[E] & Async) = ...
            val fiber = IOTask(workerLoop(), safepoint.copyTrace(trace), context, parent)
            state.interrupts(fiber)
            fiber.onComplete(state)
            loop(i + 1)
    loop(0)
    state
}
```

After:

```scala
Isolate.internal.detach.map { detached =>
    val parent = IOTask.current
    @tailrec def loop(i: Int): Unit =
        if i < numWorkers then
            def workerLoop(): Unit < (Abort[E] & Async) = ...
            val fiber = IOTask(workerLoop(), detached, parent)
            state.interrupts(fiber)
            fiber.onComplete(state)
            loop(i + 1)
    loop(0)
    state
}
```

`Safepoint.get` disappears from the site: it was there for the trace copy and the interceptor
read, and both are gone (section 9.1 covers `IOTask.current`). `Race.apply`
(`Fiber.scala:793-806`) and `gather` (`Fiber.scala:905-919`) change identically, each losing the
same four lines.

### 5.3 `Fiber.Unsafe.init` (`Fiber.scala:408-420`)

Before:

```scala
IOTask(Sync.defer(v), Trace.saved(), Context.empty)
    .asInstanceOf[Fiber.Unsafe[A, reduce.SReduced]]
```

After:

```scala
IOTask(Sync.defer(v), Isolate.internal.Detached.empty)
    .asInstanceOf[Fiber.Unsafe[A, reduce.SReduced]]
```

The documented behavior at `Fiber.scala:393-398` ("empty effect context ... capturing the live
context requires a suspension, which an unsafe entry has none of") stays true and is now stated
by the argument itself. The trace clause of that scaladoc becomes false at the swap and must be
updated with the trace round (section 8).

### 5.4 `IOTask`

Before (`IOTask.scala:11-19`, `:66-70`, `:185-199`):

```scala
sealed private[kyo] class IOTask[Ctx, E, A] private (
    private var curr: A < (Ctx & Async & Abort[E]),
    private var trace: Trace,
    private var finalizers: Finalizers
) extends IOPromise[E, A] with Task:

    def context: Context = Context.empty
    ...
    Isolate.internal.restoring(trace, this) {
        ArrowEffect.handlePartial(erasedAbortTag, Tag[Async.Join], curr, context)( ... )
    }
    ...
    def apply[Ctx, E, A](
        curr: A < (Ctx & Async & Abort[E]),
        trace: Trace,
        context: Context,
        parent: Maybe[IOPromise[?, ?]] = Absent,
        finalizers: Finalizers = Finalizers.empty,
        runtime: Int = 0
    ): IOTask[Ctx, E, A] =
        val ctx = context
        val task =
            if ctx.isEmpty then new IOTask(curr, trace, finalizers)
            else new IOTask(curr, trace, finalizers) { override def context = ctx }
```

After:

```scala
sealed private[kyo] class IOTask[Ctx, E, A] private (
    private var curr: A < (Ctx & Async & Abort[E])
) extends IOPromise[E, A] with Task:
    ...
    ArrowEffect.handlePartial(erasedAbortTag, Tag[Async.Join], curr)( ... )
    ...
    def apply[Ctx, E, A](
        curr: A < (Ctx & Async & Abort[E]),
        detached: Isolate.internal.Detached,
        parent: Maybe[IOPromise[?, ?]] = Absent,
        runtime: Int = 0
    ): IOTask[Ctx, E, A] =
        val task = new IOTask(detached.attach(curr))
```

What goes away: the `context` member and its empty-context subclass mint
(`IOTask.scala:19`, `:193-199`), the context argument to the drive (`IOTask.scala:70`), the
`trace` field and its lifecycle (`IOTask.scala:13`, `:141-143`), `restoring`
(`IOTask.scala:69`), and the `finalizers` field with its interceptor methods
(`IOTask.scala:14`, `:32-36`, `:138-140`, replaced per section 9.2). `Fiber.scala`'s
`import kyo.kernel.internal.Context` and `import kyo.kernel.internal.Trace`
(`Fiber.scala:7`, `:9`) both go.

### 5.5 Calling `attach` in `IOTask.apply` rather than at the fork site

The one ergonomic property the old API had by accident is that `context` was a required
positional parameter of `IOTask.apply`, so a fork site could not forget it. A `Detached` value
plus a separate `attach` call loses that: a site that forgets `attach` compiles and silently
gives the child default values for every `Local`, which is the exact failure mode the
`Fiber.Unsafe.init` scaladoc warns about.

Making `detached: Detached` a required parameter of `IOTask.apply` and calling `attach` inside it
(as sketched in 5.4) restores the property: every task carries a `Detached`, the unsafe entry
passes `Detached.empty` and says so at the call site, and no kyo-core file outside `IOTask` calls
`attach` at all. This is a kyo-core convention rather than a kernel constraint, which is where it
belongs: the kernel keeps a general `attach`, and the one consumer that mints tasks makes it
non-optional. Recorded as open question Q1 in case the reverse (explicit `attach` at each fork
site, `IOTask` staying ignorant) is preferred.

### 5.6 Tests

`kyo-kernel2/shared/src/test/scala/kyo/kernel2/IsolateTest.scala` is the oracle for items 2, 6
and the residual-effect rows. The rewrites are mechanical, `(trace, context) => body` becoming
`detached => body`, with two shape changes:

- The rows that assert on the captured context (`IsolateTest.scala:35-38`, `:41-48`, `:182-191`)
  read `detached.context`, so `context` needs to stay reachable from the test. Either
  `private[kernel2]` on the field (the test is in `kyo.kernel2`) or a `private[kernel2]` accessor.
  The `LocalTest` rows (`LocalTest.scala:114-155`) live in package `kyo` in kyo-prelude and need
  `private[kyo]`, which the field already has to be for `attach`'s empty check to be inlineable
  there; simplest is `private[kyo] val context`.
- New rows this design earns: `attach` transplants what `detach` captured (assert a read inside
  an attached computation observes the creator's value), a noninheritable binding does not
  survive `attach`, and a child's own handler shadows an inherited binding.

---

## 6. Why not keep a callback

`runDetached(f)` is `snapshot.map(f)` with the map spelled by hand. That indirection bought
something exactly once: the snapshot could not be named as a value because producing it required
being at a drive, and the callback was how the old kernel expressed "run this once the drive has
the context". kernel2 already has a first class way to say that, which is a pending computation.
Keeping the callback after the snapshot becomes a value would mean the fork site cannot hold the
state, cannot pass it to a helper, and cannot mint children in a nested scope without capturing
two loose parameters, and each of the three sites in `Fiber.internal` does exactly that today.

---

## 7. Alternatives considered and rejected

**A1. Direct value: `snapshot(using Frame): Context < Any`, with the caller installing.**
This is the brief's first candidate and it is close to the recommendation; the difference is the
nominal type. Rejected because: the resolved-and-filtered invariant becomes a comment rather than
a type (nothing stops a future caller from handing an unresolved in-flight context to a fork);
`Context` stays in kyo-core's imports and in `IOTask`'s vocabulary, where today it is one of the
things this item is trying to remove; the trace round would have to widen the signature to a pair
or a tuple, changing every consumer. The cost of the nominal type is one small object per fork,
against a fork that already allocates an `IOTask`, an `IOPromise` and several closures.

**A2. A macro or inline boundary in the old kernel's `Boundary.apply` style.**
Rejected because there is nothing left for a macro to do. The old inline existed to mint a
`KyoDefer` subclass at the call site so the boundary cost one allocation and no megamorphic call.
In kernel2 the equivalent node is minted by `Kyo.readContext`, which the context track already
defines as an inline that mints one anonymous transform, so the fork site gets the same code
shape through an ordinary call. A macro would add compile-time surface and a second place where
fork semantics live, for no runtime difference.

**A3. Model context inheritance as an `Isolate` instance and drop the second mechanism.**
Structurally this works: an `Isolate[Any, Any, Any]` with `State = Context`,
`capture = readContext(ctx => f(ctx.inherit.resolve))`, `isolate(ctx, v) = attach(ctx)(v)`,
`Transform[A] = A`, `restore = identity`, composed at fork sites as
`contextIsolate.andThen(isolate)`, whose `andThen` (`Isolate.scala:68-80`) already puts the
context capture outermost and the attach around the user isolate's handler installation, which
is the correct order. It is genuinely tempting: one protocol, and the derivation macro's
`filterNot(_ <:< TypeRepr.of[ContextEffect[Any]])` (`Isolate.scala:137`) stops being an
exclusion and becomes a base case.

Rejected for two reasons. First, if the context component is folded into the derived instance,
then every non-fork use of `Isolate.run` and `Isolate.nest` also snapshots, filters
noninheritable bindings and re-installs them, which changes the meaning of `Noninheritable` from
"does not cross a fork" to "does not cross an isolate", and those are different sets of
call sites. Keeping it out of the derivation and composing it explicitly at fork sites avoids
that, but then the site writes `boundary.andThen(isolate)` instead of `detach.map`, which is the
same number of concepts with a less direct name. Second, the three-phase protocol has no slot for
state that is not a computation transformation, and the trace round needs exactly that (a value
the task may hold, section 8). The two mechanisms are not redundant: `Isolate` is a bidirectional
round trip for state the fork must not share (capture, transform, restore), the boundary is a one
way transplant for state the fork must inherit as is. Different shapes because different jobs.

**A4. `detach[A, S](v: A < S)(using Frame): (A < S) < Any`, a single method that captures and
transplants in one step.** Attractive for `initUnscoped` (`detach(io).map(child => IOTask(child))`)
and it makes forgetting the transplant impossible. Rejected because three of the four consumer
sites build N children from one capture (J8), and this shape forces either N captures (N
suspensions, N snapshots, and N chances for them to differ) or a second `detachAll`-style method
for collections that does not fit `foreachIndexed`'s worker loop at all. `Detached` plus `attach`
is this shape factored so that the capture and the application are separately reusable.

**A5. Keep the context in `IOTask` and pass it to the drive per slice, as the old kernel does.**
Recorded in the context track as its section 7.4 and rejected there for the same reason it is
rejected here: it puts inherited bindings in a second place, which then has to be merged with the
chain-resident ones at every read and re-supplied at every drive entry, and it makes a fork nested
inside a fiber a special case (the nested boundary would have to consult both places). With
`attach` there is one place, and J5 and J6 disappear instead of being re-implemented.

**A6. A transplant function, `detach: ([A, S] => (A < S) => A < S) < Any`.** This is A2 of the
brief's shapes taken literally, and it collapses into the recommendation: a nominal class with an
`attach` method is a polymorphic function value with a name, minus the allocation and the
megamorphic call, plus room for the trace field. Rejected as strictly worse than the class.

**A7. Naming.** `detach` / `Detached` / `attach` was chosen over `boundary` / `Boundary` / `cross`
and `capture` / `Ambient` / `transplant`. `capture` is unavailable at these call sites: it is
already `Isolate.capture` with a different meaning, and both appear in the same expression.
`detach` keeps continuity with `runDetached`, and the context track independently proposed the
same three names, so the two designs converge without a rename.

---

## 8. Where the trace round plugs in

Nothing in the proposed signatures mentions a trace, and nothing needs to change when the trace
round lands. The two plausible shapes both fit:

- If the per-fiber trace stays failure attached (today's `EffectTrace` model,
  `internal/KyoException.scala:31-44`), then what a child needs is the creator's frame prefix, a
  value. `Detached` gains a field for it, and `attach` additionally installs a node that appends
  the prefix to any `KyoException` escaping the child, the way `Catching` re-arms itself
  (`kernel2-preemption-design.md:676-684` describes that re-arming). No consumer signature moves.
- If a thread-resident ring buffer returns (the old kernel's model,
  `kernel/internal/Trace.scala:121-179`), then `Detached` gains `trace: Trace`, `IOTask` gains a
  field for it and re-installs it per slice next to `Safepoint.beginSlice`, and per-child copies
  come from `detached.trace`. `detach`'s signature is still unchanged; only `IOTask` moves.

Two consequences of the interim state must be recorded rather than discovered at the swap:
`IOTask.fiberTrace()` (`IOTask.scala:44-53`), which the blocking monitor's leak probe renders,
returns the empty string until the trace round lands; and the `Fiber.Unsafe.init` scaladoc clause
"Trace captured. The current execution trace IS snapshotted for diagnostics"
(`Fiber.scala:397-398`) becomes false and must be edited in the same commit that removes the
`Trace.saved()` argument. Open question Q3 asks whether that interim is acceptable or whether the
trace round must precede the swap.

---

## 9. Adjacent obligations this design surfaces, routed

### 9.1 The forking fiber's identity (J7) needs a new home

`safepoint.getInterceptor()` has no kernel2 equivalent: track A's Safepoint has states and a
slice, not an interceptor stack, and the kernel must not learn about `IOPromise`. The read is a
"which fiber is running on this thread" question, and the thread is the right place to answer it.

Recommendation: kyo-core owns it. `IOTask` already stashes its safepoint per slice in track A's
sketch (`running` in `kernel2-preemption-design.md:747-756`); the reverse map is one more
assignment in the same try/finally:

```scala
// IOTask
private val local = new ThreadLocal[IOTask[?, ?, ?]]      // a plain var on JS and Wasm
private[kyo] def current: Maybe[IOPromise[?, ?]] = Maybe(local.get())
```

Two alternatives, both worse: `Worker.currentTask` already exists on JVM and Native
(`kyo-scheduler/jvm-native/src/main/scala/kyo/scheduler/Worker.scala:126`, set at `:378`, cleared
at `:390`) but has no counterpart in the JS and Wasm scheduler
(`kyo-scheduler/js-wasm/src/main/scala/kyo/scheduler/Scheduler.scala:16-20`), so it would split
the implementation by platform; or a context binding carrying the current fiber, which is more
principled (it travels with the computation rather than with the thread) but costs a binding
install per task and needs the task to bind itself to its own computation after construction.

One behavior delta to note either way: with the old composed interceptor, a fork made while an
`IOTask` runs nested inside another `IOTask` on the same thread reads a composite interceptor,
fails the `case p: IOPromise` match, and links no parent (`kernel/internal/Safepoint.scala:113-119`
builds the composite). A thread-local returns the innermost task instead, so such a child would be
linked where today it is orphaned. That is arguably a fix, but it is a change, and it is Q2.

### 9.2 Finalizers on an abandoned remainder (J10)

`IOTask.run`'s interrupted branch runs `finalizers.run(pollError())` (`IOTask.scala:138-140`).
The kernel2 equivalent is `remainder.discard` (`Pending.scala:105-116`), which walks the
remainder for `Finalize` nodes and runs the releases. Two deltas the swap round must resolve, in
kyo-core, not here: `discard` passes no error to the release, whereas the old finalizer receives
`Maybe[Error[Any]]` (which `Sync.ensure` exposes to user code,
`kyo-core/shared/src/main/scala/kyo/Sync.scala:111`); and `discard` throws the accumulated errors
rather than returning them, which a task-completion path cannot let escape. Flagged here because
removing `IOTask.finalizers` is part of the same edit as removing its `context` and `trace`.

### 9.3 `ensureInterrupt` (`IOTask.scala:161-165`)

Walks the interrupted remainder head-only to register the interrupt cascade on a pending
`Async.Join`, using `ArrowEffect.dispatchFirst`. kernel2 needs an equivalent head-only dispatch
that runs no user code. Not a boundary concern, but it is in the same method the swap rewrites and
it has a comment explaining a reverted bug (`IOTask.scala:154-160`), so it should be carried over
deliberately rather than re-derived.

---

## 10. Open questions that need the user

**Q1. Where `attach` is called.** Recommendation: `IOTask.apply` takes `detached: Detached` as a
required parameter and calls `attach` itself, so a fork site cannot silently lose inheritance
(section 5.5). The alternative is an explicit `detached.attach(child)` at each of the four fork
sites, keeping `IOTask` ignorant of the concept. This is a kyo-core convention decided at the swap
round, but it changes what the kernel's API is optimized for, so it is worth ruling now.

**Q2. The forking fiber's identity.** Recommendation: a kyo-core thread-local set by
`IOTask.run` (section 9.1). Confirm the behavior delta is acceptable: a fork made inside a nested
task on the same thread would link a parent where the old composed interceptor linked none.

**Q3. Interim trace gap.** The swap round removes `IOTask`'s trace field before the trace round
lands, so `fiberTrace()` renders nothing for the blocking monitor's leak probe and a failure
inside a fiber carries no frames from its creator. Acceptable as an interim, or must the trace
round precede the kyo-core swap?

**Q4. Test reachability of the captured context.** The oracle rows assert on the captured context
directly (`IsolateTest.scala:35-38`, `LocalTest.scala:114-155`). Recommendation is
`private[kyo] val context` on `Detached`, which keeps it out of user code while letting both test
suites read it. The stricter alternative is to rewrite those rows to assert behaviorally (attach
the context to a probe computation and read the value back), which tests the property rather than
the representation but makes the noninheritable rows longer.

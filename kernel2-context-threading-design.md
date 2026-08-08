# kernel2 design: the Context parameter threaded through execution

Replaces the rejected arrow-resident-summary design. Your ruling, stated in the
Kyo.scala TODO ("these should be handled with the context param threaded + Defer not
specific suspensions. See the old kernel"), the Isolate TODO ("ContextSnapshot should
not exist. Thread Context as parameter like the old kernel"), and in review: the
context is threaded through execution as a parameter, exactly like the old kernel.
This document maps that mechanism onto kernel2's chain execution, faithfully, and
names the few places where kernel2's structure forces a decision.

# 1. The old kernel's mechanism, precisely

Four facts, cited from the frozen tree, define the shape being ported:

1. **Every continuation application takes the context as a parameter.**
   `KyoSuspend.apply(v: O[A], context: Context)(using Safepoint): B < S`
   (`kyo-kernel/.../internal/KyoInternal.scala:59`). The context is never stored in
   a node; it exists only in flight, passed into each resumption.

2. **A read is a plain Defer that consumes the parameter.** `ContextEffect.suspendWith`
   mints a `KyoDefer` whose body is
   `f(context.getOrElse(effectTag, default))` (`ContextEffect.scala:103-109`). No
   dedicated read suspension exists; the no-default variant's default is
   `bug("Unexpected pending context effect: ...")` (`ContextEffect.scala:64`).

3. **A binding re-wraps every resumption into its region.** `ContextEffect.handle`'s
   `handleLoop` wraps each suspension in a `KyoContinue` whose apply computes
   `updated = context.set(tag, ...)` from the INCOMING (resume-time) context and
   resumes inward with it, then re-wraps the region's next state
   (`ContextEffect.scala:153-167`). One wrapper allocation per suspension crossing
   per enclosing binding. A pure value returns as-is; the transform never runs.

4. **Drives supply the initial context.** `eval` enters with
   `evalLoop(kyo((), Context.empty))` (`Pending.scala:411`); the `ArrowEffect.handle`
   family starts its loops at `Context.empty` and passes the loop's context into
   clause resumptions (`kyo(_, context)`); `handlePartial(tag1, tag2, v, context)`
   takes the context as an explicit parameter (`ArrowEffect.scala:621-661`), which is
   how IOTask hands the fiber's context in per slice, and its Defer arm answers reads
   with it (`k((), context)`).

# 2. The mapping onto kernel2

kernel2 executes fused chains (`Arrow.Transform.run` through `Offset.run`) under a
trampoline (`evalLoop`). The port threads the parameter through exactly those paths.

## 2.1 Signatures: the parameter rides every execution application

```scala
// Arrow.Transform
def run[C, S2](v: Any, context: Context, cont: Arrow[B, C, S2]): C < (S & S2)

// Arrow application: canonical form is internal; the public overload is the
// cold boundary and supplies empty, like the old kernel's bare eval
private[kyo] def apply[S2](v: A < S2, context: Context): B < (S & S2)
def apply[S2](v: A < S2): B < (S & S2) = apply(v, Context.empty)
```

`Offset.run`'s fused loop, `guardedRun`, `applySlow`, `empty`, and `segmentBoundary`
thread it mechanically. Minted transforms (map fragments, `defer` thunks, loop
steps) pass it through untouched: `cont(w, context)`. The cost on the pure hot path
is one extra parameter in registers, the same cost the old kernel carries in every
`apply(v, context)`; section 5 measures it.

The context is never stored in any node, matching the old kernel: it exists in
flight, and section 2.3's re-arm is what lets updates survive trampoline bounces.

## 2.2 Reads: plain Defers consuming the parameter

`Kyo.ContextRead` and `Kyo.ContextSnapshot` are deleted. `Kyo.Suspension` collapses
into `Kyo.Suspend`, the one remaining suspension kind. A read is a `Kyo.Defer` whose
transform consumes the threaded context:

```scala
// ContextEffect.suspend(tag): V < E
Kyo.Defer((), new Arrow.Transform[Unit, V, E]:
    def frame = _frame
    def run[C, S2](v: Any, context: Context, cont: Arrow[V, C, S2]) =
        cont(Kyo.lift(context.getOrElse(effectTag, bugMissing(effectTag))), context))
```

The with-default variant substitutes the fallback for the bug. The drive's Defer arm
becomes `defer.cont(defer.value, context)`: reads resolve in one map lookup wherever
they execute, in `eval`, inside `handlePartial` (with the caller-passed context, the
old kernel's `k((), context)` arm), or inside a resumed continuation.

`ContextEffect.runDetached` becomes a Defer reading the whole context:
`cont(Kyo.lift(context.inherit), context)`, then `map(f)`. The fork boundary sees
the context threaded to it at fork execution time; `ContextSnapshot` and the
`snapshotContext` chain walk are deleted, satisfying the Isolate TODO.

## 2.3 Bindings: a re-arming interceptor at region entry

The old kernel's `KyoContinue` re-wrap has an exact kernel2 counterpart already in
the tree: the `Arrow.Interceptor` re-arm pattern `Effect.Catching` uses
(`Effect.scala:64-67`: run the region step, and if the output parked, `prepend(this)`
so later steps stay intercepted). `ContextBinding` becomes such an interceptor and
stops being a `Handler`. It is fully typed, following the typed-handler rule already
in force: state stored at the public API's types, `V` and `E` bound as class
parameters, clauses kept at their real types, no erased `Tag[Any]` and no
`Maybe[Any] => Any` lambda. `Context`'s accessors are typed against `Tag[E]`, so the
body needs no casts:

```scala
final private[kyo] class ContextBinding[V, E <: ContextEffect[V]](
    effectTag: Tag[E],
    ifUndefined: () => V,
    ifDefined: V => V,
    _frame: Frame
) extends Arrow.Interceptor:
    def frame = _frame
    def run[C, S2](v: Any, context: Context, cont: Arrow[Any, C, S2]): C < (Any & S2) =
        val value =
            if context.contains(effectTag) then ifDefined(context.get(effectTag))
            else ifUndefined()
        cont(Kyo.lift(v), context.set(effectTag, value)) match
            case kyo: Kyo[?, ?] => kyo.prepend(this).asInstanceOf[C < (Any & S2)]
            case w              => w
end ContextBinding
```

The value positions (`v: Any`, the prepend result cast) are the `Interceptor`
pass-through contract, identical to `Catching`: on values the arrow is
identity-typed, which is what justifies `Interceptor.as`. Everything effect-typed
stays at its real type.

Installation stays pure and moves to the region's ENTRY:

```scala
// ContextEffect.handle(tag, ifUndefined, ifDefined)(v)
v match
    case kyo: Kyo[?, ?] => kyo.prepend(binding)   // one node, nothing evaluates
    case v              => v                       // pure value: transform never runs
// plus the one documented E-discharge cast, as Handler.install has today
```

Why this is the old kernel's semantics, point by point:

1. **Entry**: `prepend` puts the binding before the region's continuation. A
   suspension at the region's head dispatches outward with its identity intact and
   the binding applies to the resumed value flowing back in, exactly what the old
   `KyoContinue` wrapper (same tag and input, apply sets and resumes inward) does.
2. **Resume-time derivation**: `ifDefined` reads the INCOMING context on every entry,
   so the outer value it sees is the resumer's, recomputed per resumption, matching
   `handleLoop`'s per-step `context.set`.
3. **Parks**: every Kyo that escapes the region passes through `run` on the stack
   (chain applications return through it), so the parked remainder gets the binding
   prepended and the next resumption re-establishes it. This is also what makes
   trampoline bounces safe: a rescue or segment-boundary Defer bubbling out to
   `evalLoop` carries the binding at its front, so the drive's plain
   `defer.cont(value, context)` re-applies it. In-flight updates never need to
   survive a bounce because every bounced remainder re-arms.
4. **Nesting**: `handle(a)(handle(b)(v))` prepends b then a; execution runs a first,
   b derives from a's value, reads see b. Parks re-prepend in the same order (inner
   re-arms first, outer wraps it on the way out).
5. **Multi-shot**: a captured continuation contains the re-armed bindings among its
   prefix transforms; each clause invocation re-runs them.
6. **Purity**: install allocates one node; a pure region never runs the transform.

With bindings out of the `Handler` hierarchy, dispatch's delimiter search and
`hasHandler` narrow to operation handlers only, and `Handler.ContextBinding` is
deleted along with `evalSuspension`'s context arms and `resolveContext`.

## 2.4 Drives: who supplies the parameter

1. `evalLoop(v0, mode, context)`: the Defer arm passes it, dispatch closes it into
   clause resumptions (`resume = (x: o[Any]) => k(Kyo.lift(x), context)`, the old
   kernel's `kyo(_, context)`), and `eval`/`evalPartial` enter with `Context.empty`
   like the old `eval`.
2. `handlePartial(tag, v, context)(clause)` gains the explicit context parameter,
   conforming to the old kernel's signature; the future IOTask passes the fiber's
   context per slice and reads inside the slice resolve against it.
3. `evalSuspension` collapses into the operation dispatch: with one suspension kind
   left there is nothing else to distinguish.

## 2.5 Bracket: finalizers under the threaded context

The bracket arms (`acquire`, `use`, `release`, the discard-path finalizers) execute
under whatever context the drive threads at their execution site. `Bracket.prepend`
today pushes an interceptor into `acquire` and `cont` but not `release`; that is the
pre-existing defect the previous analysis found (finalizers escaping the region's
bindings). The fix is structural here and lands with this change: `Bracket.prepend`
also wraps `release`, so a bracket inside a binding region runs its release with the
binding re-established. The `reacquire`/`Finalize`/`constant` transforms thread the
parameter mechanically.

# 3. Semantic deltas against today's kernel2 (not against the old kernel)

1. **Resolution moves from chain structure to execution flow.** Today a read parks
   and a drive walks its continuation chain for the innermost binding delimiter;
   with threading it consumes the dynamic parameter at its execution point. For all
   standard compositions (bindings installed around the computation before a drive)
   the observable values are identical, and the ContextEffectTest nesting and
   late-binding cases keep their expected results.
2. **An unbound no-default read is a bug defect, not a park.** The old kernel's
   exact behavior (`bug("Unexpected pending context effect")`). The type system
   already guarantees `E` is discharged for well-typed programs, so this is only
   reachable through internal partial-drive paths. The tests that pinned parking
   behavior adapt to pin the defect.
3. **Reads get cheaper, forks get cheaper.** A read is one map lookup instead of an
   O(chain) walk with erased tag comparisons; a fork boundary reads the threaded
   context instead of materializing a snapshot by walking the chain.

# 4. What is deleted

1. `Kyo.ContextRead`, `Kyo.ContextSnapshot`, and the `Kyo.Suspension` layer
   (collapses into `Kyo.Suspend`).
2. `Handler.ContextBinding` (replaced by the interceptor in 2.3).
3. `evalSuspension`'s context arms, `resolveContext`, `snapshotContext`.
4. The subtype-tolerant chain-walk read resolution as a mechanism (binding matching
   now happens in `Context`'s map, keyed as the old kernel keyed it).

# 5. Costs, measured before anything merges

The plan is benchmarks-first: implement the threading on the branch, run the full
KernelBench board plus one new row, and put the numbers next to the current ledger
before the suite adaptations are finished.

| path | today (kernel2) | with threading | old kernel reference |
|------|-----------------|----------------|----------------------|
| pure map chain (eagerMap5, loopPure10k) | no param | +1 register parameter per run | carries the same parameter |
| context read | O(chain) walk per read | O(1) lookup + the Defer node | identical Defer |
| binding install | 1 delimiter node | 1 interceptor node | 1 wrapper node |
| park crossing a binding | 0 | +1 prepend node per binding | +1 KyoContinue per handle level |
| fork snapshot | O(chain) walk | field-free: reads the parameter | reads the parameter |

The new row exercises a read-heavy region (`contextRead` style: N reads under one
binding) so the walk-versus-lookup change is visible, not inferred. The guard rows
are eagerMap5 and resumeFused: both must stay allocation-free, and any delta on
eagerMap5 beyond noise gets diagnosed before proceeding.

# 6. Execution order (on approval)

1. `Arrow.scala`: signature threading (Transform.run, apply canonical+public,
   Offset.run, guardedRun, applySlow, empty, segmentBoundary).
2. `Pending.scala` + `internal/Kyo.scala`: evalLoop context parameter, Defer arm,
   dispatch closing the context into resumes, suspension collapse, deletion of the
   context arms and walks; minted transforms across Pending/Loop/Observe/Effect.
3. `internal/Handler.scala`: run signatures; ContextBinding deleted from the
   hierarchy.
4. `ContextEffect.scala`: reads as Defers, binding as interceptor, runDetached.
5. `ArrowEffect.scala`: handlePartial context parameter.
6. Bracket release prepend fix.
7. Benchmarks (section 5), then the test-suite adaptation, full suite green, commit.

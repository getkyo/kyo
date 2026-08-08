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

5. **The context is re-derived from the root on every bounce, never carried.**
   `eval`'s loop applies the parked computation with `Context.empty` on EVERY
   iteration (`Pending.scala:411`), not just the first: the correct context is
   reconstructed inward each time by the binding wrappers stacked inside the parked
   value (fact 3). Structural loops thread the received context untouched
   (`flatten`'s wrapper: `apply(v, context) = flattenLoop(kyo(v, context))`,
   `Pending.scala:387-388`), and `ArrowEffect.handle`'s unmatched-suspension wrapper
   refreshes its loop context from each incoming resumption
   (`apply(v, context) = handleLoop(kyo(v, context), context)`,
   `ArrowEffect.scala:140-141`). The `Context.empty` at a handle loop's start is
   therefore only the pre-first-resumption state of an eagerly evaluated handle,
   and it is safe for a structural reason: handles evaluate bottom-up at
   construction time, so no outer binding can exist yet, and a read that needs one
   parks and resolves later at a root drive, threaded through the wrappers
   installed by then. `Context.empty` originates ONLY at true roots: the bare
   `eval`, and the absence of a fiber. Everything else receives its context from
   its caller.

# 2. The mapping onto kernel2

kernel2 executes fused chains (`Arrow.Transform.run` through `Offset.run`) under a
trampoline (`evalLoop`). The port threads the parameter through exactly those paths.

## 2.1 Signatures: the parameter rides every execution application

```scala
// Arrow.Transform
def run[C, S2](v: Any, context: Context, cont: Arrow[B, C, S2]): C < (S & S2)

// Arrow application: one canonical internal form; the caller always supplies
// the context it is executing under
private[kyo] def apply[S2](v: A < S2, context: Context): B < (S & S2)

// The user-facing application defers: the arrow runs when a drive pops the
// node, under the ambient context of the site where the result is embedded
def apply[S2](v: A < S2): B < (S & S2) =
    Kyo.Defer[A, B, S & S2](v, self)   // fromKyo conversion; zero casts
```

This is cast-free because `Defer`'s value field becomes a pending value:

```scala
final class Defer[A, +B, -S](
    val value: A < S,
    val cont: Arrow[A, B, S]
) extends Kyo[B, S]
```

Variance carries the whole proof: `A < S2 <: A < (S & S2)` and
`Arrow[A, B, S] <: Arrow[A, B, S & S2]` by contravariance in `S`, and the ascribed
`Kyo.Defer[A, B, S & S2]` reaches `B < (S & S2)` through the `fromKyo` conversion,
the same zero-cast route `ArrowEffect.suspend` already uses. Typing the field also
deletes an existing cast: the drive's Defer arm applies
`defer.cont(defer.value, context)` directly, where today it needs
`defer.value.asInstanceOf[Any < Any]`.

The public form is the answer to "what context does `arrow(value)` run under": none
at application time, because application constructs a `Defer` and execution happens
where the result is embedded. If the caller embeds it inside a binding region, the
region's interceptors re-prepend themselves onto the node as it parks outward
(2.3), so the drive pops it with the bindings re-derived in front of the arrow: the
application sees exactly the ambient of its execution site. At a true root (a test
applying a captured continuation cold) the drive is `eval` and the ambient is
empty, which is the root's ambient. This is the old kernel's resume-time-context
semantics without ever asking the caller for a context.

`Offset.run`'s fused loop, `guardedRun`, `applySlow`, `empty`, and `segmentBoundary`
thread it mechanically. Minted transforms (map fragments, `defer` thunks, loop
steps) pass it through untouched: `cont(w, context)`. The cost on the pure hot path
is one extra parameter in registers, the same cost the old kernel carries in every
`apply(v, context)`; section 5 measures it.

The context is never stored in any node, matching the old kernel: it exists in
flight, and section 2.3's re-arm is what lets updates survive trampoline bounces.

## 2.1a Where the context originates, and why it is never lost

The old kernel's fact 5 becomes the binding rule for every kernel2 site:

1. **The consumers of the parameter are exactly three**: Defer reads (2.2), binding
   interceptors (2.3), and dispatch resume closures (2.4). All three execute only
   under a drive. Plain transforms are pure thread-through.
2. **Every internal execution site passes the context it received.** The fused loop,
   the trampoline arms, dispatch, and the bracket arms all thread the drive's
   context. No internal execution path may fabricate `Context.empty`; the greppable
   invariant is that `Context.empty` appears at the two root drives (`eval`,
   `evalPartial`) and nowhere else in execution code.
3. **A drive's context is a constant of the drive.** The old kernel's root loop
   applies with the same root context on every bounce and lets the in-computation
   wrappers re-derive the bindings inward; kernel2 is identical: a parked remainder
   carries its re-armed binding interceptors at its front (2.3), so the drive's
   plain re-application from its entry context reconstructs the in-scope bindings.
   Nothing needs the in-flight updated context to survive a bounce, because every
   bounced remainder re-derives it.
4. **`handlePartial` never fabricates a context**: it takes one from its caller (the
   old kernel's exact signature), and the future IOTask passes the fiber's context
   per slice. A caller with no ambient context passes `Context.empty` because empty
   IS its ambient, the same way the bare `eval` root does.
5. **Eager execution applies only kernel-minted, context-pure transforms.** The
   eager value fast path (the function overload of `map` on a pure value, the
   eagerMap5 path) executes only the transform it just minted, which threads the
   parameter blindly; its context argument is inert by construction, under the same
   structural justification as the old kernel's eager handle loops (construction is
   bottom-up, no outer binding can exist yet, and anything needing one parks).
   Application of an ARBITRARY arrow to a value is never eager: the public
   `arrow(v)` defers (2.1), and the Arrow overload of `map` on a pure value defers
   the same way, because an arbitrary arrow may contain binding interceptors from a
   captured continuation, and running those eagerly would fabricate an empty
   ambient at a site that has a real one. Deferring is what hands them the real
   one.

## 2.2 Reads: plain Defers consuming the parameter

`Kyo.ContextRead` and `Kyo.ContextSnapshot` are deleted. `Kyo.Suspension` collapses
into `Kyo.Suspend`, the one remaining suspension kind. A read is a `Kyo.Defer` whose
transform consumes the threaded context:

```scala
// ContextEffect.suspend(tag): V < E
Kyo.Defer((), new Arrow.Transform[Unit, V, E]:
    def frame = _frame
    def run[C, S2](v: Any, context: Context, cont: Arrow[V, C, S2]) =
        cont(context.getOrElse(effectTag, bugMissing(effectTag)), context))
```

The read value goes through the implicit lift conversion (the position is typed at
`V`, and for a generic `V` the macro emits the nesting check itself). Explicit
`Kyo.lift` appears nowhere in typed positions: it is reserved for where the
conversion cannot apply.

The with-default variant substitutes the fallback for the bug. The drive's Defer arm
becomes `defer.cont(defer.value, context)`: reads resolve in one map lookup wherever
they execute, in `eval`, inside `handlePartial` (with the caller-passed context, the
old kernel's `k((), context)` arm), or inside a resumed continuation.

`ContextEffect.runDetached` becomes a Defer reading the whole context:
`cont(context.inherit, context)` (the conversion applies: `Context` is a concrete
opaque type, provably not a computation), then `map(f)`. The fork boundary sees
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

## 2.3a OPEN FINDING from the validation round: the flat chain loses binding scope

One suite failure survives the implementation and it is structural, not a test
detail. The scenario: a binding installed OUTSIDE an operation handler whose
CLAUSE reads the context:

```scala
ContextEffect.handle(Tag[Env], 3) {
    ArrowEffect.handleResume(Tag[CtxOp], program)([C] => _ => env.map(_ * 2))
}
```

The old kernel resolves the clause's read to 3: the binding wrapper encloses the
whole handler, and the clause's parked read resolves through it at the root drive.
Under the interceptor-prepend encoding the read resolves against the drive's empty
context and dies as a defect, because the binding interceptor sits mid-chain,
never executed (the operation parks at the region head before any value flows
through it), and dispatch grafts the clause result in FRONT of it.

The deeper fact: no dispatch-side rule can repair this, because pure installation
makes the two scoping cases produce byte-identical chains:

```scala
handle(Env, 3)(handleResume(...)(program))   // binding outside: clause sees 3
handleResume(...)(handle(Env, 3)(program))   // binding inside: clause must not
// both encode as: Continue(op, [binding, steps..., delimiter])
```

The old kernel distinguishes them by wrapper NESTING, which the flat chain erased.
Value-flow semantics are correct in both cases; only clause scope needs the
distinction. Candidate fixes:

1. **Binding as a nesting-preserving node** (recommended): `Bound(inner, binding,
   cont)`, the old kernel's wrapper made explicit in the node algebra. The drive
   enters `inner` under the derived context (a nested drive scope with its own
   context), parks re-wrap the node, dispatch passes through it (an operation
   inside reaches delimiters in `cont`, and its clause runs under the context
   OUTSIDE the node: exact old-kernel scoping), and captured continuations
   re-enter through the node. Faithful; touches the drive and dispatch.
2. **Entry-interceptor plus exit-marker pairs** in the chain, with a scope-depth
   walk at dispatch. Keeps the flat chain but the pairing must survive every piece
   of chain surgery dispatch performs; fragile.
3. **Prefix-fold at dispatch** (fold ContextBindings found in the captured prefix
   into the clause context): smallest change, fixes the failing case, but leaks
   inside-installed bindings into clause scope, a documented divergence from the
   old kernel in exactly the case the encodings cannot distinguish.

### 2.3b Candidate 1 in concrete terms (for the ruling)

The node makes the old kernel's wrapper nesting first-class:

```scala
final private[kyo] class Bound[V, E <: ContextEffect[V], A, +B, -S](
    val inner: A < (E & S),                  // the delimited region
    val binding: ContextBinding[V, E],       // derives this scope's value per entry
    val cont: Arrow[A, B, S]                 // the continuation OUTSIDE the binding
) extends Kyo[B, S]
```

1. **Installation stays pure and now encodes scope**: `ContextEffect.handle(v)`
   wraps the node (`Bound(v, binding, Arrow[A])`) instead of prepending into the
   chain. `map` appends to `cont` (outside the region), so
   `handle(...)(x).map(f)` and `handle(...)(x.map(f))` produce DIFFERENT nodes,
   which is exactly the distinction the flat chain lost.
2. **The drive enters the region under the derived context**: evalLoop's Bound arm
   computes `updated = binding.derive(context)` and drives `inner` with `updated`
   (the nested-drive recursion carries its own context, the way `recur` already
   carries `depth`). A completed region's value flows into `cont` under the OUTER
   context.
3. **Parks re-wrap the node**: when `inner` parks, the remainder is
   `Bound(parkedInner, binding, cont)`, so every later resumption re-derives from
   the resume-time context, the old kernel's per-resumption re-wrap.
4. **Dispatch passes through**: an operation parking inside `inner` whose delimiter
   lives in `cont` (or further out) dispatches with the captured continuation
   re-entering THROUGH the node
   (`k = x => Bound(innerPrefix(x), binding, Arrow[A]).map(restOfCont)`), and the
   clause runs under the context OUTSIDE the binding: exact old-kernel clause
   scope, for both installation orders.
5. **Value-flow reads are unchanged**: inside the region they consume the threaded
   parameter the drive derived at entry; the interceptor-prepend machinery for
   Catching and Observe is untouched (those are value-flow interceptors with no
   scope question).

The cost profile matches the old kernel: one node per binding region, one re-wrap
per park crossing, and dispatch grows one transparent case. The interceptor-based
`ContextBinding.run` re-arm from 2.3 becomes internal to the node's entry step.

### 2.3c Candidate 4, from your gist: rotation as the arrow step

Your mini-kernel (gist 8c8b2b02) states the mechanism as a law:

```
Handler Rotation
handle(tag1, suspend(tag2, input, cont), f) ≡ suspend(tag2, input, x => handle(tag1, cont(x), f))   where tag1 ≠ tag2
```

An unmatched suspension crosses the handler outward, and the handler ROTATES into
the suspension's continuation, staying wrapped around the region's remainder. This
is the old kernel's unmatched-arm KyoContinue re-wrap as algebra, and it dissolves
the contradiction section 2.1a met head-on: a binding must be recorded at its
scope boundary (exit position, so installation order encodes scope) yet act at
every entry (so reads and resumptions see it). Rotation stores at the boundary and
re-wraps to the front on every crossing: scope-true at rest, entry-true in motion.

Two readings of "we need a rotate arrow step", both restoring bindings to
dispatch-visible delimiters at their APPENDED (scope-encoding) position, which
directly fixes the clause-scope red (outside-installed and inside-installed
bindings encode differently again):

1. **R1, rotation for scope with the threading kept at boundaries**: context reads
   go back to dispatching (an operation resolving against the innermost matching
   binding delimiter in its continuation, the rotated normal form the flat chain
   already is for value flow), and FALL BACK to the threaded context when no
   delimiter matches, which is how a fiber's inherited bindings reach reads across
   the boundary (`handlePartial`'s context parameter stays exactly for this). The
   clause context for dispatch is the fold of binding delimiters OUTSIDE the
   matched handler, now correct because position encodes scope. The threading
   parameter survives as the boundary carrier and root ambient; the per-read cost
   returns from O(1) to dispatch distance.
2. **R2, the gist's full position**: no ContextEffect kind at all: `Env`-style
   effects are ArrowEffects handled with `cont(value)` (your gist's Env), scope
   and multi-shot correctness fall out of dispatch structure plus the rotation
   law, and `Context` exists only as the materialized carrier at fork boundaries.
   The biggest unification; it subsumes candidate 1 (the Bound node is rotation
   frozen into a node) and deletes the ContextEffect/ArrowEffect split from the
   kernel.

Open for your ruling alongside the R1/R2 choice: whether the read-cost profile
(dispatch distance instead of the threaded O(1) lookup) is acceptable, since that
was the original motivation for threading the parameter; under R1 the hot path for
fiber-context reads (no local binding) is one failed chain search plus the map
lookup.

### 2.3d Rotation generalized: the operation that replaces prepend

Your framing is broader than a binding fix, and a probe just confirmed it
empirically. The prepend encoding loses every wrapper's region EXIT, not just
bindings':

```scala
// kernel2 today: the throwing map is OUTSIDE the catching region, yet f catches it
val guarded = Effect.catching(ask.map(_ + 1))(_ => -1)
val outside = guarded.map(v => (throw new RuntimeException("boom")): Int)
handle(...)(outside).eval   // kernel2: -1 (over-guard). old kernel: boom escapes.
```

Verified against both kernels: the old kernel scopes the guard correctly, kernel2
catches past the region end. `Observe.Step` over-observes by the identical shape.
Three defects, one cause: `prepend` re-arms a wrapper at the FRONT of whatever
remainder exists, erasing where its region ends.

The rotation kernel fixes the class, and makes the handlers real:

1. **Every wrapper is a boundary delimiter.** `Handler.Cont/Resume/Stop/First/
   Loop`, `Catching`, `Observe.Step`, and `ContextBinding` install by APPENDING at
   their region's end, purely. Chain position IS scope. `prepend`,
   `Arrow.Interceptor`, and the pass-through `as` cast are deleted.
2. **Rotation is the crossing operation.** When a suspension bubbles outward
   through a delimiter, the delimiter decides its crossing: an `ArrowHandler`
   with a matching tag dispatches (the captured continuation is exactly the
   region up to itself, by construction); any other delimiter rotates into the
   suspension's continuation, preserving nesting order (the gist's law). Today's
   dispatch search, prefix capture, and per-dispatch `optimize` re-walks collapse
   into this one structural step, and the delimiters stop being passive markers
   interpreted by a distant erased match: each carries its own crossing.
3. **Reads dispatch like operations** (R1): resolve against the innermost
   matching `ContextBinding` delimiter crossed on the way out, falling back to
   the threaded context at the boundary (`handlePartial`'s parameter, the fiber
   carrier). Clause scope is correct by position for every wrapper kind.
4. **Throws unwind to the nearest downstream Catching delimiter**, which is now
   the correctly-scoped generalization of dispatch's existing clause-throw
   unwind: the region end is marked, so the over-guard disappears.

What remains of the threading round: the run-signature context parameter and its
supply rules (roots, drives, handlePartial) stay as the boundary carrier; the
binding-as-interceptor and read-as-context-lookup pieces are replaced by
delimiter dispatch with context fallback.

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

# 5a. Measured results (implementation round)

The board, threading tip versus the pre-threading commit re-measured the same day
on the same machine:

| row | baseline | threading | delta |
|-----|----------|-----------|-------|
| eagerMap5 | 4.62 ns, 0 B | 4.62 ns, 0 B | none: the parameter is free on the pure path |
| deepBind10k | 66.6-68.8 us / 160,336 B | 68.8 us / 160,336 B | noise band, alloc identical |
| state10 | 828 ns / 4,560 B | 838 ns / 4,560 B | ~1% |
| loopPure10k | 18.9 us / 160,008 B | 19.0 us / 160,008 B | noise |
| loopSuspend1k | 71.4 us / 379,934 B | 70.4 us / 379,938 B | noise |
| suspension | 447.7 ns / 2,120 B | 489.2 ns / 2,120 B | +9.3% time, alloc identical |
| narrowIter | 2,490 ns / 12,024 B | 2,627 ns / 12,024 B | +5.5% time, alloc identical |
| stateMap10k | 2.05 ms / 7,832,021 B | 2.39 ms / 8,792,071 B | +16.7% time, +96 B/iter under JMH |
| resumeFused | 18.4 ns / 0 B | 28.4 ns / 16 B | the predicted public-defer price: one Defer, one pop |
| contextRead100 (new) | n/a | 56 ns and 208 B per read under a binding | the new baseline |

The stateMap10k allocation delta was diagnosed with a deterministic probe
(constructor counters on Offset/AndThen/Continue/Defer/Suspend plus
ThreadMXBean.getThreadAllocatedBytes around a single 10k-iteration run, at both
commits): node counts are IDENTICAL (140,002 / 50,000 / 70,001 / 0 / 20,001) and
the single-run allocation is IDENTICAL TO THE BYTE (7,671,984 B). The change
allocates nothing extra. The +96 B/iter and the time deltas on the dispatch-heavy
rows appear only under JMH's execution profile: the wider three-argument run
signatures shift JIT inlining and escape-analysis boundaries, materializing
allocations (resume closures, boxes) that the baseline profile scalar-replaces.
This is the measured price of the threading mechanism itself, the same parameter
the old kernel carries on every continuation application.

# 5. Costs, estimated at design time (kept for the record)

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
| public `arrow(v)` on a value | eager, 0 nodes | +1 Defer, +1 trampoline pop (resumeFused shows it) | no public form: `kyo(v, context)` demands a context |

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

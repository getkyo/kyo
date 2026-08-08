# kernel2 review issues: tracker

One section per issue from your review pass (30 code TODOs, the Handler margin note,
your inline rulings on the previous version of this doc, and the 4 follow-up Safepoint
TODOs). Every section is self-contained: files, problem, planned change, and current
status. Work proceeds one issue at a time, validated with you between issues.

## Status summary

| # | Issue | Status |
|---|-------|--------|
| 1 | `Kyo.lift` is a bare cast (nesting bug) | APPROVED (your revision), not started |
| 2 | Forwarding methods | DONE, committed `564dc4cc9f` |
| 3 | Erased handler machinery | APPROVED, not started |
| 4 | Handler kind naming | APPROVED, lands with #3 |
| 5 | Inconsistent type parameter names | APPROVED, next up |
| 6 | drive/dispatch/eval verb mix | APPROVED, not started |
| 7 | Uninformative toString | APPROVED, not started |
| 8 | `(x: Any) match` widenings | APPROVED, not started |
| 9 | Unexplained cast in `prepend` | DESIGN TO BE PRESENTED first |
| 10 | `discard` misleading name | DECIDED: `finalizeBracket` |
| 11 | `observe` placement + effectful observer | APPROVED, not started |
| 12 | `KyoException` name clash, `Trace` stub | APPROVED, not started |
| 13 | Fork-boundary API shape | IN DESIGN (track B agent) |
| 14 | `LastResort \| Null` carrier | APPROVED, lands with #3 |
| 15 | Preemption not wired into drives | IN DESIGN (track A agent) |
| 16 | `defer` allocates twice | AWAITING YOUR RULING |
| 17 | Loop drivers slower than old kernel | APPROVED, benchmark first |
| 18 | Context reads walk the chain | IN DESIGN (track B agent) |
| 19 | `Context` to `TypeMap` | APPROVED as direction, perf-gated |
| 20 | Overly public Arrow surface | AWAITING YOUR RULING |
| 21 | Safepoint follow-up TODOs (S1-S4) | IN DESIGN (track A agent) |

## 1. `Kyo.lift` is a bare cast, so explicit nesting is broken
when I say "fix" just go ahead and fix
fix :)
- Files: `kyo-kernel2/shared/src/main/scala/kyo/kernel2/Kyo.scala`, `Pending.scala`
- Status: APPROVED with your revision (keep the method, fix the body; superclass named
  `Implicits`). Not started.

Problem. `Kyo.lift` is the user-facing explicit lift, needed when the implicit
conversions confuse inference. Its current body does nothing:

```scala
private[kyo] inline def lift[A, S](inline v: A): A < S = v
```

If `v` is itself a suspended computation, the result `A < S < S2` holds the inner
suspension at the outer level: `evalNow` wrongly reports `Absent`, and evaluating the
outer computation runs the inner one. The correct dynamic-nesting logic already exists
as `liftSlow` (the implicit lift macro routes through it):

```scala
def liftSlow[A](v: A): A < Any =
    v match
        case _: Kyo[?, ?] | _: Kyo.Nested[?] => Kyo.Nested(v).asInstanceOf[A < Any]
        case _                               => v
```

Change.
- `Kyo.lift` keeps its name and place; its body becomes the `Nested`-boxing match
  above. `liftSlow` is removed; all 20 internal call sites use the canonical method.
- The implicit conversion family (`lift` macro, `liftAnyVal`, `liftUnit`,
  `abortCastUnit`, `liftPureFunction1-6`, the `Render` given) moves out of the `<`
  companion body into a superclass it extends, per your naming: `Implicits`
  (implicits inherited from a companion's superclass stay in implicit scope).
- `Kyo.unwrap` renamed `Kyo.unnest` (stays `@static` for the bytecode reasons noted at
  its definition), pairing with the nesting vocabulary.

## 2. Forwarding methods
no need to report done stuff. This is about what needs my attention. This sould still surface if there's a relevant decision/info to me
- Files: `Pending.scala`, `Effect.scala`, `Arrow.scala`, `ArrowEffect.scala`
- Status: DONE. Full kyo-kernel2 JVM suite green. Commit `564dc4cc9f`.

What was removed:
- `unsafeGet` (a proto1 leftover; its only test now asserts through `eval`).
- `driveInstalled` and `drivePartial` (ArrowEffect calls `driveLoop` directly).
- `Effect.deferInline` (only `defer` remains; old-kernel call sites migrate at swap,
  source-compatible since the parameter is by-name).
- `Arrow.of`, a pure upcast; all 12 sites construct the `Arrow.Transform` in place.
- `neverPreempt` is now the single val instead of a def forwarding a private val.

## 3. Erased handler machinery
fix
- Files: `internal/Handler.scala`, `ArrowEffect.scala`, `Pending.scala`
- Status: APPROVED. Not started. Scheduled after the naming sweeps (#5, #6).

Problem. Handler clauses are stored through erased aliases, discarding the types the
public API established:

```scala
type Clause      = [C] => (Any, Arrow[Any, Any, Any]) => Any < Any
type InputClause = [C] => Any => Any < Any

final class Cont(val effectTag: Tag[Any], val clause: Clause, val frame: Frame) ...
```

Your ruling: the original prototype (`kyo-kernel/shared/src/main/scala/kyo/proto*/`)
was properly typed; this must be too.

Change. Type the delimiter classes generically and store the user clause at its public
type, deleting the aliases:

```scala
final class Cont[I[_], O[_], E <: ArrowEffect[I, O], A, S, S2](
    val effectTag: Tag[E],
    val clause: [C] => (I[C], O[C] => A < (E & S & S2)) => A < (E & S & S2),
    val frame: Frame
) extends ArrowHandler
```

The ArrowEffect handle methods then stop minting an erased adapter closure per call.
The unavoidable erased boundary concentrates at the dispatch tag match in
`Pending.scala`, where each cast is justified by the matched tag. Side effect to
measure with JMH: removes a per-handle closure allocation.

## 4. Handler kind naming
fix
- Files: `internal/Handler.scala`
- Status: APPROVED. Lands together with #3.

Problem. `Handler.Operation` does not say which effect kind it interprets (your margin
note: "Is this ArrowHandler?"), and `Handler.Context` collides with `internal.Context`.

Change. `Operation` becomes `ArrowHandler`; `Handler.Context` becomes `ContextBinding`.

## 5. Inconsistent type parameter names and missing variance
fix
- Files: `Kyo.scala`, `Arrow.scala`
- Status: APPROVED. Next up.

Problem. Ad-hoc parameter names against the convention (values `A, B, C...`, effects
`S, S2, S3...`), and `Suspension` is invariant where it can be covariant:

```scala
sealed abstract class Suspension[X, -E] extends Kyo[X, E]      // current
final class Offset[-A, X0, +B, -S] private[kyo] (...)          // current
```

Change (module-wide sweep):

```scala
sealed abstract class Suspension[+A, -S] extends Kyo[A, S]
final private[kyo] class Continue[A, +B, -S](...)              // pivot A stays invariant
final class Offset[-A, B, +C, -S] private[kyo] (...)
private[kyo] def isEmpty[A, B, S](f: Arrow[A, B, S]): Boolean
```

`Step`'s abstract type member `X` (the intermediate type between head and rest) is
renamed too; proposal `Mid`.

## 6. drive / dispatch / eval verb mix
fix
- Files: `Pending.scala`
- Status: APPROVED. Not started.

Problem. One kind of thing carries three verbs. Change, consolidating to eval:

| current | new |
|---------|-----|
| `driveLoop` | `evalLoop` |
| `dispatch` | `evalSuspension` |
| `dispatchControl` | `evalOperation` |
| `dispatchLast` | `evalBoundary` |

`resolveContext`/`snapshotContext` keep the resolve prefix (they compute values, they
do not evaluate computations); both disappear anyway if #18's design lands.

## 7. Uninformative toString
fix
- Files: `Kyo.scala`
- Status: APPROVED. Not started.

Problem. Several nodes render as bare constants (`"Nested"`, `"Defer"`, `"Offset"`),
useless in test failures and debugging. Change: render shape plus content, matching the
existing style of `Suspend`/`ContextRead`/`Transform`, e.g. `Nested(<value>)`,
`Offset(<head>, <next>)`, `Continue` including its chain.

## 8. `(x: Any) match` widenings
fix
- Files: `Pending.scala` (map/flatMap/andThen/unit, Observe), `Effect.scala`
  (catching), `Loop.scala` (drivers), `Arrow.scala` (run sites)
- Status: APPROVED. Not started.

Problem and change:

```scala
(cont: Any) match                                       // current
    case o: Arrow.Offset[Any, Any, Any, Any] @unchecked ...

cont match                                              // new
    case o: Arrow.Offset[Any, Any, Any, Any] @unchecked ...
```

Outside `Pending.scala` the `<` opaque is not transparent (that was why the widening
crept in); direct matching against class patterns still compiles since the opaque bound
erases, with `@unchecked` carrying the justification. Each site verified individually.

## 9. Unexplained cast in `prepend`
ok
- Files: `Kyo.scala` (`Suspension.prepend` and siblings), `Effect.scala`, `Pending.scala`
- Status: You asked for an elaborated design for approval before implementation. I will
  present it when we reach this item (after #12 in the order).

Problem. `prepend(f: Arrow[Any, Any, Any])` is only ever called with pass-through
interceptors (`Catching`, `Observe`), which is why the cast in

```scala
final private[kyo] def prepend(f: Arrow[Any, Any, Any]): X < E =
    map(f.asInstanceOf[Arrow[X, X, Any]])
```

is sound but looks senseless. Design direction to elaborate: an `Arrow.Interceptor`
type that encodes the value-pass-through contract (`run` forwards the value unchanged
to its continuation), `prepend` takes it, and the cast disappears or becomes a typed
composition. The design will cover which classes implement it (`Catching`, `Observe`,
`Finalize`?) and every `prepend` signature (`Suspension`, `Continue`, `Bracket`,
`Defer`).

## 10. `discard` misleading name
fix
- Files: `Pending.scala`
- Status: DECIDED by you: `finalizeBracket`. Not started.

Problem. `discard` runs bracket finalizers when the scheduler drops a parked
computation; the name reads like harmless value-dropping (`kyo.discard`, `.unit`), and
someone could call it thinking that. Plain `finalize` is unusable: an extension method
named `finalize` is permanently shadowed by `java.lang.Object#finalize`.

## 11. `observe` placement, then an effectful observer
fix
- Files: `Pending.scala` -> new `Observe.scala` (+ `ObserveTest.scala`)
- Status: APPROVED, including the effectful follow-up. Not started.

Change, step 1: move `observe` and the `Observe` transform class to their own file,
entry point `Observe.apply`. Step 2 (your follow-up question, answered yes): make the
observer effectful. The observed loop already runs step-by-step (each transform
executes against the empty continuation so every intermediate value is visible), so:

```scala
def apply[A, S, S2](observer: (Frame, Any) => Any < S2)(v: A < S): A < (S & S2)
```

composes `observer(frame, value).map(_ => step)` per frame. Cost: one map node per
observed frame, only while observing (debug path). Care point: the observer's own
computation must sit outside the re-armed observed region so observation does not
observe itself.

## 12. `KyoException` name clash and the `Trace` stub
ok
- Files: `internal/KyoException.scala`, `internal/Trace.scala`, `Isolate.scala`
- Status: APPROVED. Not started.

Problem. kernel2's internal trace carrier (a suppressed exception accumulating effect
frames for stack enrichment) reuses the name of kyo-data's public `KyoException`, a
different thing. Separately, `internal/Trace.scala` is an empty placeholder class.

Change. Rename the carrier to `EffectTrace`; delete the `Trace` stub and drop the
`Trace` parameter from `Isolate.internal.runDetached`. Recorded obligation for the swap
round (your ruling): the mechanism must then also work with kyo-data's `KyoException`,
enriching those exceptions the same way.

## 13. Fork-boundary API shape
launch an opus agent to explore
- Files: `Isolate.scala` (`internal.runDetached`), future consumers kyo-core/IOTask
- Status: IN DESIGN. Merged into track B (see #18) per your instruction to explore
  with an opus agent.

Problem. The fork boundary hands the scheduler `(Trace, Context)` through a callback:

```scala
private[kyo] def runDetached[A, S](f: (Trace, Context) => A < S)(using Frame): A < S
```

You asked for something more elegant, informed by how kyo-core and IOTask actually
consume it. Leading candidate: a direct snapshot value the caller maps over. Since #18
eliminates `ContextSnapshot`, where the snapshot comes from is part of that design, so
one agent designs both.

## 14. The `LastResort | Null` boundary carrier
I'm not convinced of LastResort and you did not provide proper context.
- Files: `Pending.scala`, `ArrowEffect.scala`
- Status: APPROVED (after the why-it-exists explanation). Lands with #3.

Why a carrier must exist at all: `handlePartial`'s clause cannot be an installed chain
delimiter, because (a) it must not travel with a parked continuation: each scheduler
slice re-enters the drive with its own clause, so an installed one would stack
duplicates per slice, and (b) it needs the full continuation, delimiters included, so
an out-of-band resume re-installs every traveling handler; a delimiter would capture
only its own prefix. What is wrong is the shape:

```scala
final private[kyo] class LastResort(
    val effectTag: Tag[Any],
    val clause: [C] => (Any, Arrow[Any, Any, Any]) => Maybe[Any < Any]
)
// threaded as: last: LastResort | Null = null
```

Change: a typed node in the handler hierarchy (with #3's typing), threaded as
`Maybe[...]`, no `null` anywhere.

## 15. Preemption not wired into the drives
do you have a design for this yet? if needed launch an opus agent to work on it
- Files: `Pending.scala`, `Arrow.scala`, `ArrowEffect.scala`, `internal/Safepoint.scala`
- Status: IN DESIGN, track A opus agent running. Deliverable:
  `kernel2-preemption-design.md`, to be summarized for your review.

Problem. The Safepoint machinery (per-thread slots, CAS preemption delivery, depth
budget) is built and tested, but no drive consults it. The interim plumbing leaks into
the API you are unhappy with:

```scala
def eval(preempt: () => Boolean, period: Int): A < Any                 // goes away
def handlePartial[...](..., preempt: () => Boolean = ..., period: Int = ...)
```

Bindings for the design (your rulings): the period is ALWAYS a constant, never
overridable through any API; drives poll `Safepoint.preempted` and consume with
`clearPreempt`; the scheduler requests preemption via `Safepoint.preempt` on the
fiber's registered thread.

## 16. `defer` allocates twice
I don't think this is safe. How about Defer extends from suspension and arrow?
- Files: `Effect.scala`, `Kyo.scala`
- Status: AWAITING YOUR RULING.

Problem. `Effect.defer` allocates a `Kyo.Defer` plus a separate `Arrow.Transform`:

```scala
val thunk = new Arrow.Transform[Unit, A, S]:
    def frame = _frame
    def run[C, S2](v: Any, cont: Arrow[A, C, S2]): C < (S & S2) = cont(f)
Kyo.Defer((), thunk)
```

where the old kernel mints one anonymous class:

```scala
new KyoDefer[A, S]:
    def frame = _frame
    def apply(v: Unit, context: Context)(using Safepoint) = f
```

Proposed change: give `Defer` an abstract `run(): A < S`; `defer` mints one anonymous
`Defer`; the current `(value, cont)` form becomes a concrete subclass used by the
rescue and segment-boundary paths; composition (`map`/`prepend`) goes through a
`Chained` subclass. JMH after (deepBind and suspension rows are Defer-sensitive).

## 17. Loop drivers slower than the old kernel
ok
- Files: `Loop.scala`
- Status: APPROVED, benchmark first (your instruction). Not started.

Problem. kernel2's drivers allocate a transform per iteration even for immediate
outcomes, via `map`:

```scala
def loop(v: Outcome[A, O] < S): O < S =
    v.map { o => ... loop(run(next._1)) ... }        // kernel2: alloc per iteration
```

The old kernel is `@tailrec` on the immediate path and wraps a suspension exactly once:

```scala
@tailrec def loop(v: Outcome[A, O] < S)(using Safepoint): O < S =
    v match
        case next: Continue[A] @unchecked => loop(run(next._1))
        case kyo: KyoSuspend[...]         => new KyoContinue(kyo) { ... loop(kyo(v, context)) }
        case res                          => res.asInstanceOf[O]
```

Change: add loop JMH rows measuring old kernel vs kernel2 first, then port the old
driver shape to every arity (apply 1-4, indexed 1-4, and the rest of the surface).

## 18. Context reads walk the continuation chain
Confirm first: can we wire the context as a parameter instead of having these suspensions? see how the old kernel uses defer for this
- Files: `Pending.scala` (`resolveContext`, `snapshotContext`), `Kyo.scala`
  (`ContextRead`, `ContextSnapshot`), `internal/Handler.scala`, `Arrow.scala`
  (`hasHandler`)
- Status: IN DESIGN, track B opus agent running. Deliverable:
  `kernel2-context-threading-design.md`, to be summarized for your review.

Problem. Every context read walks the whole chain collecting matching delimiters, with
list allocations, and `ContextSnapshot` exists as a second walking suspension only for
fork boundaries. Your rulings: this is expensive (benchmarks required), the context
should be passed as a parameter like the old kernel, and `ContextSnapshot` should not
exist at all.

Design scope: where the context parameter lives without taxing the eager path
(eagerMap5 at 5.67 ns/op must not regress; kernel2 frames deliberately have no context
parameter, unlike old-kernel `KyoSuspend.apply(v, context)`), how bindings survive
park/resume, what `ContextEffect.handle` does, whether `hasHandler` still pays for
itself, and the fork-boundary snapshot (#13).

## 19. `Context` to `TypeMap`
launch an opus agent to explore the performance aspect. Assume we can make imporvements to TypeMap if justified or even create a new datastructure. Ideally, the conext should be of a type that is user-facing like TypeMap
- Files: `internal/Context.scala`, kyo-data `TypeMap`
- Status: APPROVED as direction; perf-gated (your ruling: the challenge is
  performance, not code change). Sequenced after #18 settles what Context must support.

Change: migrate the hand-rolled `Map[Tag[Any], AnyRef]` to kyo-data's `TypeMap`, adding
the `private[kyo]` operations it lacks (`inherit`-style filtering, flag-maintaining
`set`). Adoption gated on benchmarks of the context-heavy rows.

## 20. Overly public Arrow surface
fix
- Files: `Arrow.scala`
- Status: AWAITING YOUR RULING.

Problem. `Arrow.Step` is public without need; same audit question for `step`,
`stepSlow`, `optimize`, `isEmpty`. Proposal: all `private[kyo]`. `Arrow` itself stays
public: it is the continuation type in the public handler model (`handleFirst`,
`handlePartial` signatures).

## 21. Safepoint follow-up TODOs (S1-S4)
you must always expand properly as their own items
- Files: `internal/Safepoint.scala`
- Status: IN DESIGN, folded into track A (#15).

- S1: rename `Parked` ("it's odd to think a safepoint would be parked"); the design
  proposes Preempt-flavored naming and checks it reads correctly for Overflow and the
  resume field.
- S2: use `AtomicReferenceArray[Maybe[Safepoint]]` instead of null-empty slots if it
  costs nothing (needs pre-filling with `Absent`; benchmark `get`).
- S3: Overflow is too drastic. Confirmed real: with `enter()` always false, a lone
  transform rescues into a Defer whose re-execution rescues identically, a permanent
  livelock. Your ruling: a task must keep running without preemption/interruption
  rather than never progress; candidate fix is handing out an unregistered `Active`
  (keeps the depth guard and progress, loses only cross-thread preemption delivery).
- S4: analyze whether clearPreempt's consume-then-check is sufficient in all cases
  (re-preemption mid-slice, multiple requesters, the future interruption use).

## Execution order

#5 (next) -> #6 -> #7 -> #8 -> #10 -> #1 -> #11 -> #12 -> #3 + #4 + #14 -> #9 (design
first) -> #17 (benchmark first) -> #16, #20 (once ruled) -> track A landing -> track B
landing -> #19 (perf-gated). Full kernel2 suite green after each item; JMH after the
perf-relevant ones (#3, #15, #16, #17, #18).

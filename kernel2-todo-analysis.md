# kernel2 review issues: tracker

One section per open issue from your review passes. Every section is self-contained:
files, problem, planned change, current status. Issues that are done drop off the
tracker; they only resurface when they carry a decision or information you need.

Working rules for this tracker (your rulings):
- When you write "fix" on an issue, it is authorized: I implement it without asking
  again, one issue at a time, full kernel2 suite green and a commit per issue.
- The tracker carries only what needs your attention.
- Every issue is its own numbered item, always expanded, never bundled.

Off the list as done: #2 forwarding methods (commit `564dc4cc9f`).

## Status summary

| # | Issue | Status |
|---|-------|--------|
| 1 | `Kyo.lift` is a bare cast (nesting bug) | AUTHORIZED (fix) |
| 3 | Erased handler machinery | AUTHORIZED (fix) |
| 4 | Handler kind naming | AUTHORIZED (fix), lands with #3 |
| 5 | Inconsistent type parameter names | AUTHORIZED (fix), next up |
| 6 | drive/dispatch/eval verb mix | AUTHORIZED (fix) |
| 7 | Uninformative toString | AUTHORIZED (fix) |
| 8 | `(x: Any) match` widenings | AUTHORIZED (fix) |
| 9 | Unexplained cast in `prepend` | design first, then implement (your ok) |
| 10 | `discard` renamed `finalizeBracket` | AUTHORIZED (fix) |
| 11 | `observe` placement + effectful observer | AUTHORIZED (fix) |
| 12 | `KyoException` name clash, `Trace` stub | AUTHORIZED (your ok) |
| 13 | Fork-boundary API shape | IN DESIGN: dedicated opus agent (track C) |
| 14 | `LastResort` boundary carrier | OPEN DISCUSSION: context below for your read |
| 15 | Preemption not wired into drives | IN DESIGN: opus agent (track A) |
| 16 | `defer` allocates twice | YOUR COUNTER-PROPOSAL adopted for design; to present |
| 17 | Loop drivers slower than old kernel | AUTHORIZED, benchmark first |
| 18 | Context reads walk the chain | CONFIRMED feasible; IN DESIGN (track B) |
| 19 | `Context` to a user-facing type | IN DESIGN: dedicated opus agent (track D) |
| 20 | Overly public Arrow surface | AUTHORIZED (fix) |
| 21 | `Parked` naming in Safepoint | input to track A design |
| 22 | Null-based Safepoint slots | input to track A design |
| 23 | Overflow safepoint can livelock | input to track A design |
| 24 | clearPreempt sufficiency | input to track A design |

## 1. `Kyo.lift` is a bare cast, so explicit nesting is broken

- Files: `kyo-kernel2/shared/src/main/scala/kyo/kernel2/Kyo.scala`, `Pending.scala`
- Status: AUTHORIZED (fix).

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
  above. `liftSlow` is removed; all internal call sites use the canonical method.
- The implicit conversion family (`lift` macro, `liftAnyVal`, `liftUnit`,
  `abortCastUnit`, `liftPureFunction1-6`, the `Render` given) moves out of the `<`
  companion body into a superclass it extends, named `Implicits` per your ruling
  (implicits inherited from a companion's superclass stay in implicit scope).
- `Kyo.unwrap` renamed `Kyo.unnest` (stays `@static` for the bytecode reasons noted at
  its definition), pairing with the nesting vocabulary.

## 3. Erased handler machinery

- Files: `internal/Handler.scala`, `ArrowEffect.scala`, `Pending.scala`
- Status: AUTHORIZED (fix). Scheduled after the naming sweeps (#5, #6).

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

- Files: `internal/Handler.scala`
- Status: AUTHORIZED (fix). Lands together with #3.

Problem. `Handler.Operation` does not say which effect kind it interprets (your margin
note: "Is this ArrowHandler?"), and `Handler.Context` collides with `internal.Context`.

Change. `Operation` becomes `ArrowHandler`; `Handler.Context` becomes `ContextBinding`.

## 5. Inconsistent type parameter names and missing variance

- Files: `Kyo.scala`, `Arrow.scala`
- Status: AUTHORIZED (fix). Next up.

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

- Files: `Pending.scala`
- Status: AUTHORIZED (fix).

Problem. One kind of thing carries three verbs. Change, consolidating to eval:

| current | new |
|---------|-----|
| `driveLoop` | `evalLoop` |
| `dispatch` | `evalSuspension` |
| `dispatchControl` | `evalOperation` |
| `dispatchLast` | `evalBoundary` |

`resolveContext`/`snapshotContext` keep the resolve prefix (they compute values, they
do not evaluate computations); both disappear anyway when #18's design lands.

## 7. Uninformative toString

- Files: `Kyo.scala`
- Status: AUTHORIZED (fix).

Problem. Several nodes render as bare constants (`"Nested"`, `"Defer"`, `"Offset"`),
useless in test failures and debugging. Change: render shape plus content, matching the
existing style of `Suspend`/`ContextRead`/`Transform`, e.g. `Nested(<value>)`,
`Offset(<head>, <next>)`, `Continue` including its chain.

## 8. `(x: Any) match` widenings

- Files: `Pending.scala` (map/flatMap/andThen/unit, Observe), `Effect.scala`
  (catching), `Loop.scala` (drivers), `Arrow.scala` (run sites)
- Status: AUTHORIZED (fix).

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

- Files: `Kyo.scala` (`Suspension.prepend` and siblings), `Effect.scala`, `Pending.scala`
- Status: your ok on the plan: I present an elaborated design for your approval, then
  implement.

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

- Files: `Pending.scala`
- Status: AUTHORIZED (fix). Name you chose: `finalizeBracket`.

Problem. `discard` runs bracket finalizers when the scheduler drops a parked
computation; the name reads like harmless value-dropping (`kyo.discard`, `.unit`), and
someone could call it thinking that. Plain `finalize` is unusable: an extension method
named `finalize` is permanently shadowed by `java.lang.Object#finalize`.

## 11. `observe` placement, then an effectful observer

- Files: `Pending.scala` -> new `Observe.scala` (+ `ObserveTest.scala`)
- Status: AUTHORIZED (fix), including the effectful follow-up.

Change, step 1: move `observe` and the `Observe` transform class to their own file,
entry point `Observe.apply`. Step 2: make the observer effectful. The observed loop
already runs step-by-step (each transform executes against the empty continuation so
every intermediate value is visible), so:

```scala
def apply[A, S, S2](observer: (Frame, Any) => Any < S2)(v: A < S): A < (S & S2)
```

composes `observer(frame, value).map(_ => step)` per frame. Cost: one map node per
observed frame, only while observing (debug path). Care point: the observer's own
computation must sit outside the re-armed observed region so observation does not
observe itself.

## 12. `KyoException` name clash and the `Trace` stub

- Files: `internal/KyoException.scala`, `internal/Trace.scala`, `Isolate.scala`
- Status: AUTHORIZED (your ok).

Problem. kernel2's internal trace carrier (a suppressed exception accumulating effect
frames for stack enrichment) reuses the name of kyo-data's public `KyoException`, a
different thing. Separately, `internal/Trace.scala` is an empty placeholder class.

Change. Rename the carrier to `EffectTrace`; delete the `Trace` stub and drop the
`Trace` parameter from `Isolate.internal.runDetached`. Recorded obligation for the swap
round (your ruling): the mechanism must then also work with kyo-data's `KyoException`,
enriching those exceptions the same way.

## 13. Fork-boundary API shape

- Files: `Isolate.scala` (`internal.runDetached`), future consumers kyo-core/IOTask
- Status: IN DESIGN. Dedicated opus agent launched per your instruction (track C).
  Deliverable: `kernel2-boundary-api-design.md`, summarized for you when it lands.

Problem. The fork boundary hands the scheduler `(Trace, Context)` through a callback:

```scala
private[kyo] def runDetached[A, S](f: (Trace, Context) => A < S)(using Frame): A < S
```

You asked for something more elegant, informed by how kyo-core and IOTask actually
consume the boundary. The agent extracts the jobs-to-be-done from the old kernel's
Boundary consumers (Fiber, Async, IOTask), proposes exact signatures, and shows
before/after call sites. It is briefed to assume #18's outcome (context threaded by the
drive, no ContextSnapshot) as an interface, not to design it.

## 14. The `LastResort` boundary carrier
- Files: `Pending.scala`, `ArrowEffect.scala`
- Status: OPEN DISCUSSION. You are not convinced; the use is shown below with the
  real code. No implementation until you rule. Track A's design also validates or
  replaces this mechanism against the real IOTask consumer.

The use, concretely. `LastResort`'s only producer is `ArrowEffect.handlePartial`, and
`handlePartial`'s only real user is the scheduler: it is how a task drives one slice
of a fiber. The fiber's computation contains suspensions of the scheduler's own
runtime effect (the kernel2 stand-in for what IOTask interprets in kyo-core today,
like joining a promise). PendingSchedulerTest is the working example. Parking a fiber
and resuming it out of band:

```scala
var parked: Any = null
val remainder = ArrowEffect.handlePartial(Tag[SchedulerAsk], program)(
    [C] =>
        (input, cont) =>
            parked = cont          // stash the continuation for the completion callback
            Maybe.Absent           // park: this slice is over, the drive returns
)
// later, out of band (promise completed on another thread):
val resumed = parked.asInstanceOf[Arrow[Int, Int, SchedulerAsk]](100)
assert(resumed.asInstanceOf[Int < Any].eval == 142)   // bracket released in-band
```

Interrupting instead of resuming (finalizers still run):

```scala
val remainder = ArrowEffect.handlePartial(Tag[SchedulerAsk], program)(
    [C] => (input, cont) => Maybe.Absent
)
remainder.discard    // runs rel-inner, rel-outer: the brackets the park was holding
```

At the swap round, IOTask's slice loop is this pattern: drive the fiber's computation
with `handlePartial`, answer scheduler operations in-slice via `Present(next)`, park on
`Absent` after stashing the continuation, re-enter next slice with the same clause.

What `LastResort` itself is: the internal envelope carrying handlePartial's
(tag, clause) into `evalLoop`, consulted only for a suspension that NO installed
delimiter matched (hence the name, the handler of last resort):

```scala
final private[kyo] class LastResort(
    val effectTag: Tag[Any],
    val clause: [C] => (Any, Arrow[Any, Any, Any]) => Maybe[Any < Any]
)
// threaded through the drive as: last: LastResort | Null = null
```

Why the clause is a drive parameter and not an installed chain delimiter:
1. Delimiters travel with the continuation. A fiber parks, the promise completes on
   another thread, and a LATER slice re-enters handlePartial with its own clause. If
   the clause were installed in the chain, the resumed continuation would already
   contain the previous slice's copy, and each slice would add another.
2. Parking must return control to the scheduler, not stay inside the program. With the
   boundary clause, `Absent` makes the drive return with the suspension still pending,
   and the scheduler (the caller) decides what to do. An installed handler is part of
   the computation; it can only produce another computation.
3. The clause needs the FULL continuation, delimiters included, so an out-of-band
   resume re-installs every traveling handler. A chain delimiter captures only the
   prefix up to itself.

What I got wrong in the earlier writeup: I marked this APPROVED from your "fix" on the
null shape while you were actually questioning the mechanism itself. The shape fix
(typed node, threaded as `Maybe`, no null) is separate from the mechanism question.
If, after this context, you still want handlePartial's interpreter modeled differently,
that ruling reshapes track A's scheduler integration and I will hold #14 until then.

## 15. Preemption not wired into the drives

- Files: `Pending.scala`, `Arrow.scala`, `ArrowEffect.scala`, `internal/Safepoint.scala`
- Status: IN DESIGN. Your question "do you have a design for this yet?": not yet; the
  opus agent you asked for (track A) is running now. Deliverable:
  `kernel2-preemption-design.md`, summarized for you when it lands.

Problem. The Safepoint machinery (per-thread slots, CAS preemption delivery, depth
budget) is built and tested, but no drive consults it. The interim plumbing leaks into
the API:

```scala
def eval(preempt: () => Boolean, period: Int): A < Any                 // goes away
def handlePartial[...](..., preempt: () => Boolean = ..., period: Int = ...)
```

Bindings for the design (your rulings): the period is ALWAYS a constant, never
overridable through any API; drives poll `Safepoint.preempted` and consume with
`clearPreempt`; the scheduler requests preemption via `Safepoint.preempt` on the
fiber's registered thread.

## 16. `defer` allocates twice

- Files: `Effect.scala`, `Kyo.scala`
- Status: YOUR COUNTER-PROPOSAL adopted as the design direction. I will present the
  worked-out design for your approval before implementing (you flagged my earlier
  abstract-run() shape as possibly unsafe).

Problem. `Effect.defer` allocates a `Kyo.Defer` node plus a separate `Arrow.Transform`
holding the thunk, where the old kernel's `KyoDefer` was one anonymous class.

Your proposal: `Defer` extends from Suspension and Arrow. Initial analysis, to be
worked out fully: if `Defer` is a `Suspension`, chaining comes free through the
existing `Continue` machinery (`Suspension.map` already builds the chain), and if it is
also an `Arrow.Transform`, the deferred computation IS the node: `Effect.defer` mints
exactly one object, and the drive's Defer arm runs the node's own transform against the
chain. The rescue and segment-boundary producers become small concrete subclasses. This
also dovetails with #18: context reads become Defer-shaped suspensions the drive
answers from the threaded context, which is exactly the old kernel's pattern (KyoDefer
receiving the context parameter). Precedent for the double role in kernel2:
`Arrow.Offset` already extends both `Transform` and `Step`.

## 17. Loop drivers slower than the old kernel

- Files: `Loop.scala`
- Status: AUTHORIZED, benchmark first (your instruction).

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

- Files: `Pending.scala` (`resolveContext`, `snapshotContext`), `Kyo.scala`
  (`ContextRead`, `ContextSnapshot`), `internal/Handler.scala`, `Arrow.scala`
  (`hasHandler`)
- Status: your question "can we wire the context as a parameter instead of having
  these suspensions?": CONFIRMED, yes. The old kernel does exactly that: every
  continuation frame receives the context (`KyoSuspend.apply(v, context)`), and its
  defer (`KyoDefer`) is an ordinary suspension the eval loop answers while threading
  the context through. kernel2 can adopt the same shape at the drive level: the drive
  threads a Context register, reads become Defer-shaped nodes answered from it (no
  chain walk, no ContextRead/ContextSnapshot suspensions), bindings update the register
  when handlers install. The engineering question is only where the register lives so
  the eager fused path pays nothing (eagerMap5 at 5.67 ns/op must not regress). The
  track B opus agent is producing that concrete design with benchmarks. Deliverable:
  `kernel2-context-threading-design.md`, summarized for you when it lands.

## 19. `Context` should be a user-facing type, likely `TypeMap`

- Files: `internal/Context.scala`, kyo-data `TypeMap`
- Status: IN DESIGN. Dedicated opus agent launched per your instruction (track D),
  briefed with your rulings: the challenge is performance; TypeMap may be improved if
  justified, or a new datastructure created; ideally the context IS a user-facing type
  like TypeMap. Deliverable: `kernel2-context-typemap-design.md`, summarized for you
  when it lands.

## 20. Overly public Arrow surface

- Files: `Arrow.scala`
- Status: AUTHORIZED (fix).

Problem. `Arrow.Step` is public without need; same audit for `step`, `stepSlow`,
`optimize`, `isEmpty`: all become `private[kyo]`. `Arrow` itself stays public: it is
the continuation type in the public handler model (`handleFirst`, `handlePartial`).

## 21. `Parked` is the wrong name for a preempt-requested safepoint

- Files: `internal/Safepoint.scala`
- Status: input to track A's design (your TODO: "isn't a better name for this
  Preempt? it's odd to think a safepoint would be parked").

The design proposes Preempt-flavored naming and checks it still reads correctly for
the two other uses of the class: the shared Overflow instance and the `resume` field
that carries the previous Active state.

## 22. Null-based Safepoint slot array

- Files: `internal/Safepoint.scala`
- Status: input to track A's design (your TODO: use `Maybe[Safepoint]` if no perf
  overhead).

`slots` is an `AtomicReferenceArray[Safepoint]` with null empty slots. The change
requires pre-filling the array with `Absent` and confirming `get` cost is unchanged
(Maybe's Present is unboxed for references, so the comparison stays an identity check);
the design states what the benchmark must show.

## 23. The Overflow safepoint can livelock a task

- Files: `internal/Safepoint.scala`, `Arrow.scala`, `Pending.scala`
- Status: input to track A's design. Your ruling: it is better to let a task run
  without preemption/interruption/stack-depth services than to make it never progress.

Confirmed real, not just drastic: Overflow is permanently parked, so `enter()` is
always false; a lone transform then rescues into a Defer whose re-execution rescues
identically, forever. Candidate fix: hand overflow threads an unregistered `Active`
(keeps the depth guard and progress, loses only cross-thread preemption delivery); the
design weighs alternatives.

## 24. Is clearing the preempt flag enough?
- Files: `internal/Safepoint.scala`
- Status: answered directly below (no agent needed for this one); to be reconciled
  with track A's landed design when I summarize it for you.

Your question: do we have cases where preempted clearing wouldn't be enough? My
analysis, case by case against the actual protocol (requester publishes its condition,
then CASes the victim's slot from `Active` to `Parked`; the owner polls through `get`;
`clearPreempt` CASes the `Active` back and returns true):

1. A second request arriving after `clearPreempt` restored the `Active`: a new
   `Parked` lands via CAS; the very next poll observes it. Nothing lost.
2. Multiple requesters while already parked: `Parked.preempt()` is a no-op, so
   requests coalesce into one park. Correct, because parking is idempotent and each
   requester published its condition before calling `preempt()`; the boundary's
   authoritative check after `clearPreempt` therefore sees every condition behind any
   coalesced request (the CAS exchange orders the writes).
3. A request landing between `clearPreempt` and the drive returning: not lost. The
   slot is `Parked` again, so the next `enter()` refuses and the next poll observes
   it; the request is served one frame later.
4. Dead-thread reclamation dropping a pending `Parked`: benign. The request's
   condition lives outside the slot (promise state, interrupt flag), and a fiber that
   migrated off a dead thread gets re-preempted through the thread the scheduler
   currently has registered for it.
5. The one real gap: NESTED boundary drives. `clearPreempt` consumes for whoever
   calls it first, so a boundary drive nested inside the scheduler's slice (for
   example an `eval` performed synchronously inside a fiber) would swallow a request
   aimed at the outer slice, and the park would never reach the scheduler. Clearing
   is enough only with a rule about who consumes: exactly one boundary per thread
   (the scheduler's) consumes; anything nested must observe-and-cascade, or consume
   and re-arm before returning. This is the concrete requirement I will check track
   A's landed design against.

## Execution order

Authorized queue, one issue per green-suite commit: #5 -> #6 -> #7 -> #8 -> #10 ->
#1 -> #20 -> #11 -> #12 -> #3 + #4. Design-gated: #9 (present design), #16 (present
design), #14 (your ruling after the context above), #17 (benchmarks first), then
tracks A/B/C/D as they land, then #19 adoption (perf-gated). Full kernel2 suite green
after each item; JMH after the perf-relevant ones (#3, #15, #16, #17, #18).

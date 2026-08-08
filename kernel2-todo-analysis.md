# kernel2 review issues: tracker

The focus of this doc is what needs YOU. Items are grouped: first what needs your
attention, then designs in flight that I will bring to you, and the authorized fix
queue last (that part is mine to execute and only reports back through the done line).

Working rules (your rulings):
- When you write "fix" on an issue, it is authorized: I implement it without asking
  again, one issue at a time, full kernel2 suite green and a commit per issue.
- The tracker carries only what needs your attention; done items drop off.
- Every issue is its own numbered item, always expanded, never bundled.
- Attention items first; authorized fixes at the end.

Done so far: #2 forwarding methods (`564dc4cc9f`), #5 type parameter naming and
variance (`9b47ace3af`), #6 eval verb consolidation (`04adb36022`).

## Status summary

Needs your attention:

| # | Issue | What I need from you |
|---|-------|----------------------|
| 14 | `LastResort` boundary carrier | Ruling: keep the handlePartial mechanism (with typed `Maybe` shape) or direct a different one. Use shown below with real code. |
| 19 | `Context` as a user-facing type | Heads-up: the landed design recommends AGAINST `Context = TypeMap` on typing grounds. My verified summary comes next; expect a decision then. |
| 24 | clearPreempt sufficiency | Answered by me below (no agent was needed); read when convenient. The nested-boundary finding feeds the track A review. |

Designs in flight, mine to verify and bring to you (all four agent docs have landed):

| # | Issue | State |
|---|-------|-------|
| 15 (+21, 22, 23) | Preemption integration + Safepoint TODOs | `kernel2-preemption-design.md` landed; my critical read and summary pending |
| 18 | Context threaded as a parameter | `kernel2-context-threading-design.md` landed; my critical read and summary pending |
| 13 | Fork-boundary API | `kernel2-boundary-api-design.md` landed; my critical read and summary pending |
| 19 | Context datastructure performance | `kernel2-context-typemap-design.md` landed; my critical read and summary pending |
| 16 | Single-allocation defer (your shape: Defer extends Suspension and Arrow) | I present the worked-out design for your approval |
| 9 | `prepend` Interceptor typing | I present the design for your approval |

Authorized queue, mine to execute (details at the end of the doc):

| # | Issue | State |
|---|-------|-------|
| 7 | toString sweep | implemented, suite running |
| 8 | `(x: Any) match` widenings | queued |
| 10 | `discard` to `finalizeBracket` | queued |
| 1 | Fix `Kyo.lift`, `Implicits` superclass, `unnest` | queued |
| 20 | Arrow surface visibility | queued |
| 11 | `observe` extraction + effectful observer | queued |
| 12 | `EffectTrace` rename, `Trace` stub deletion | queued |
| 3 | Typed handler hierarchy | queued |
| 4 | Handler kind naming | queued, lands with #3 |
| 17 | Loop drivers | queued, benchmarks first |

# Needs your attention

## 14. The `LastResort` boundary carrier
Didn't we redesign handlePartial to return the computation? there should be no need for the var?
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
// threaded as: last: LastResort | Null = null
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

If you rule the mechanism stays, the shape fix applies (typed node, threaded as
`Maybe`, no null, lands with #3). If you want it modeled differently, that ruling
reshapes track A's scheduler integration and I hold this until then.

## 19. `Context` should be a user-facing type: heads-up on the landed design
- Files: `internal/Context.scala`, kyo-data `TypeMap`
- Status: `kernel2-context-typemap-design.md` landed (track D, briefed with your
  rulings: performance is the challenge; TypeMap may be improved or a new
  datastructure created; ideally the context is a user-facing type).

Heads-up before my full summary: the design's headline recommends AGAINST making
`Context` literally a `TypeMap`, on typing grounds independent of performance:
`Context` is keyed by effect type while `TypeMap` is keyed by value type, and two
context effects can carry the same value type (it cites `Local.internal.State` vs
`Local.internal.NoninheritableState`). I will verify that claim against the code,
read the full design critically, and bring you a summary with a concrete
recommendation; the decision is yours then.

## 24. Is clearing the preempt flag enough?
is an alternative making it a number instead of a flag?
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

# Designs in flight

## 15. Preemption not wired into the drives
- Files: `Pending.scala`, `Arrow.scala`, `ArrowEffect.scala`, `internal/Safepoint.scala`
- Status: `kernel2-preemption-design.md` landed (track A); my critical read and
  summary for you pending. Issues 21 to 23 below are inputs to it.

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

## 18. Context reads walk the continuation chain
- Files: `Pending.scala` (`resolveContext`, `snapshotContext`), `Kyo.scala`
  (`ContextRead`, `ContextSnapshot`), `internal/Handler.scala`, `Arrow.scala`
  (`hasHandler`)
- Status: your feasibility question was CONFIRMED (the old kernel passes the context
  as a parameter of every continuation frame and answers its defer suspensions while
  threading it; kernel2 can do the same at the drive level).
  `kernel2-context-threading-design.md` landed (track B); my critical read and
  summary for you pending. The design must show where the context register lives so
  the eager path pays nothing (eagerMap5 at 5.67 ns/op must not regress) and how
  bindings survive park/resume; `ContextSnapshot` must not exist per your ruling.

## 13. Fork-boundary API shape
- Files: `Isolate.scala` (`internal.runDetached`), future consumers kyo-core/IOTask
- Status: `kernel2-boundary-api-design.md` landed (track C); my critical read and
  summary for you pending.

Problem. The fork boundary hands the scheduler `(Trace, Context)` through a callback:

```scala
private[kyo] def runDetached[A, S](f: (Trace, Context) => A < S)(using Frame): A < S
```

You asked for something more elegant, informed by how kyo-core and IOTask actually
consume the boundary. The agent extracted the jobs-to-be-done from the old kernel's
Boundary consumers (Fiber, Async, IOTask) and proposes exact signatures with
before/after call sites, assuming #18's outcome as an interface.

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

## 9. Unexplained cast in `prepend`
- Files: `Kyo.scala` (`Suspension.prepend` and siblings), `Effect.scala`, `Pending.scala`
- Status: your ok on the plan: I present an elaborated design for your approval, then
  implement.

Problem. `prepend(f: Arrow[Any, Any, Any])` is only ever called with pass-through
interceptors (`Catching`, `Observe`), which is why the cast in

```scala
final private[kyo] def prepend(f: Arrow[Any, Any, Any]): A < S =
    map(f.asInstanceOf[Arrow[A, A, Any]])
```

is sound but looks senseless. Design direction to elaborate: an `Arrow.Interceptor`
type that encodes the value-pass-through contract (`run` forwards the value unchanged
to its continuation), `prepend` takes it, and the cast disappears or becomes a typed
composition. The design will cover which classes implement it (`Catching`, `Observe`,
`Finalize`?) and every `prepend` signature (`Suspension`, `Continue`, `Bracket`,
`Defer`).

# Authorized fix queue (mine to execute)

## 7. Uninformative toString
- Files: `Kyo.scala`, `Arrow.scala`
- Status: implemented; suite running. `Nested` now renders through its case class
  form, `Defer`, `Offset`, and `Continue` render shape plus content.

## 8. `(x: Any) match` widenings
- Files: `Pending.scala` (map/flatMap/andThen/unit, Observe), `Effect.scala`
  (catching), `Loop.scala` (drivers), `Arrow.scala` (run sites)
- Status: AUTHORIZED (fix), queued.

```scala
(cont: Any) match                                       // current
    case o: Arrow.Offset[Any, Any, Any, Any] @unchecked ...

cont match                                              // new
    case o: Arrow.Offset[Any, Any, Any, Any] @unchecked ...
```

Outside `Pending.scala` the `<` opaque is not transparent (that was why the widening
crept in); direct matching against class patterns still compiles since the opaque bound
erases, with `@unchecked` carrying the justification. Each site verified individually.

## 10. `discard` misleading name
- Files: `Pending.scala`
- Status: AUTHORIZED (fix), queued. Name you chose: `finalizeBracket`. Rationale
  recorded: `discard`/`abandon` read like benign value-dropping; plain `finalize` is
  unusable (shadowed by `java.lang.Object#finalize`).

## 1. `Kyo.lift` is a bare cast, so explicit nesting is broken
- Files: `Kyo.scala`, `Pending.scala`
- Status: AUTHORIZED (fix), queued.

`Kyo.lift` is the user-facing explicit lift, needed when the implicit conversions
confuse inference; its body is a bare cast today, so lifting a suspended computation
puts the inner suspension at the outer level (`evalNow` wrongly `Absent`, outer eval
runs the inner). Fix: its body becomes the `Nested`-boxing match that `liftSlow`
carries today; `liftSlow` is removed; the implicit conversion family moves to an
`Implicits` superclass of the `<` companion (your naming); `Kyo.unwrap` renamed
`Kyo.unnest` (stays `@static`).

## 20. Overly public Arrow surface
- Files: `Arrow.scala`
- Status: AUTHORIZED (fix), queued. `Step`, `step`, `stepSlow` become `private[kyo]`
  (`optimize`, `isEmpty` already are); `Arrow` itself stays public as the continuation
  type in the public handler model.

## 11. `observe` placement, then an effectful observer
- Files: `Pending.scala` -> new `Observe.scala` (+ `ObserveTest.scala`)
- Status: AUTHORIZED (fix), queued, after #1 (uses the fixed lift).

Step 1: move `observe` and the transform class to their own file, entry point
`Observe.apply`. Step 2: effectful observer,
`def apply[A, S, S2](observer: (Frame, Any) => Any < S2)(v: A < S): A < (S & S2)`,
composing `observer(frame, value).map(_ => step)` per observed frame; the observer's
own computation stays outside the re-armed region so observation does not observe
itself.

## 12. `KyoException` name clash and the `Trace` stub
- Files: `internal/KyoException.scala`, `internal/Trace.scala`, `Isolate.scala`
- Status: AUTHORIZED (your ok), queued. Rename the internal trace carrier to
  `EffectTrace`; delete the `Trace` stub and its `runDetached` parameter. Swap-round
  obligation recorded: the mechanism must then also enrich kyo-data `KyoException`s.

## 3. Erased handler machinery
- Files: `internal/Handler.scala`, `ArrowEffect.scala`, `Pending.scala`
- Status: AUTHORIZED (fix), queued. Your ruling: the original prototype
  (`kyo-kernel/shared/src/main/scala/kyo/proto*/`) was properly typed; this must be
  too. The delimiter classes become generically typed storing the user clause at its
  public type; the `Clause`/`InputClause`/`LoopClause` aliases die; casts concentrate
  at the dispatch tag match; JMH after (removes a per-handle closure allocation).
  The #14 shape fix (typed carrier, `Maybe`, no null) lands here if you keep the
  mechanism.

## 4. Handler kind naming
- Files: `internal/Handler.scala`
- Status: AUTHORIZED (fix), lands with #3. `Operation` becomes `ArrowHandler`;
  `Handler.Context` becomes `ContextBinding`.

## 17. Loop drivers slower than the old kernel
- Files: `Loop.scala`
- Status: AUTHORIZED, queued, benchmarks first (your instruction): add loop JMH rows
  measuring old kernel vs kernel2, then port the old `@tailrec` driver shape (alloc
  per iteration only on suspension, not per immediate outcome) to every arity.

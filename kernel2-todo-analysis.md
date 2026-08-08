# kernel2 review issues: tracker

The focus of this doc is what needs YOU: attention items first, designs in flight
second, the authorized fix queue last (mine to execute without stopping).

Working rules (your rulings):
- "fix" on an issue authorizes it: I implement without asking again, one issue at a
  time, full kernel2 suite green and a commit per issue.
- The tracker carries only what needs your attention; done items drop off.
- Every issue is its own numbered item, always expanded, never bundled.
- ALL changes are confined to kyo-kernel2. Nothing outside the module (kyo-data,
  kyo-kernel, kyo-core, build) is touched without your explicit instruction. Trace is
  deleted; no design or API resurrects it.

Done or closed so far: #2 forwarding methods (`564dc4cc9f`), #5 type parameter naming
and variance (`9b47ace3af`), #6 eval verb consolidation (`04adb36022`),
#13 fork-boundary API (your rulings, tracker and in-doc: the track C doc is rejected
and set aside; the boundary follows the old kernel's context-param threading, which is
exactly #18's design, confined to kyo-kernel2, with no Trace anywhere),
#22 Maybe-based Safepoint slots (your ruling: drop, keep the null-based array),
#24 clearPreempt sufficiency (you accepted the analysis; its one finding, the
consumption-ownership rule, is folded into #15's implementation notes).

## Status summary

Needs your attention:

| # | Issue | What I need from you |
|---|-------|----------------------|
| 21 | `Parked` rename | Proposal below: fix #23 first, then `Parked` becomes `Preempted` with a non-Maybe `resume: Active`. Confirm the name. |
| 23 | Overflow livelock | Full context below plus the proposed fix (ThreadLocal fallback Active). Confirm the direction. |
| 18 | Context threading design | I do the critical read and quality pass on `kernel2-context-threading-design.md`, then ask you to review, per your instruction. |
| 19 | Context datastructure | Verified summary of `kernel2-context-typemap-design.md` coming; decision then. |

Designs answered in place, now implementing (from your comments):

| # | Issue | Resolution |
|---|-------|-----------|
| 14 | `LastResort` | You are right: not needed. handlePartial becomes an outer slice loop around the plain drive; `LastResort`, `evalBoundary`, and the null threading are deleted. Design below. |
| 16 | Single-allocation defer | Class diamond confirmed (Kyo and Transform are both classes), so back to your original question: yes, `Defer` can simply have an abstract `run()`, and it is safe. Design below. |
| 9 | `prepend` Interceptor | Design below, as you asked ("where's the design?"). One documented cast in one place. |
| 15 | Preemption integration | Your "fix" recorded: authorized. I read track A's landed design critically first, then implement; the #24 consumption rule is a hard requirement on it. |

Authorized queue, in execution order, no stops: #7 (suite running) -> #8 -> #10 ->
#1 -> #20 -> #11 -> #12 -> #14 -> #16 -> #3+#4 -> #15 -> #17 (benchmarks first).

# Needs your attention

## 21. `Parked` rename: AUTHORIZED (your fix)

`Parked` becomes `Preempted` after #23 lands; the field becomes `restore: Active`
(the design's name: it is a state to put back, not an action), no `Maybe`. Lands
inside #15's implementation.

Original proposal for reference. Current shape: `Parked(resume: Maybe[Active])` covers two situations. A real
preemption request carries `Present(active)`, the state to restore. The shared
`Overflow` token is `Parked(Absent, Absent)`: permanently refusing, nothing to
restore, no request behind it. That second use is why any Preempt-flavored name reads
oddly today: Overflow was never preempted by anyone.

Proposal: fix #23 first. Its fix removes `Overflow` as a `Parked` instance entirely
(overflow threads get a fallback `Active` instead). After that, the class has exactly
one meaning, a preemption request in flight, and the rename lands clean:

```scala
final private[kernel2] class Preempted private[Safepoint] (
    private[Safepoint] val resume: Active,      // no Maybe: always present now
    thread: Maybe[Thread]
) extends Safepoint(thread)
```

`preempted`/`clearPreempt` match on `Preempted`, and `clearPreempt`'s restore CAS uses
`resume` directly. Confirm `Preempted` (alternatives considered: `PreemptRequested`,
verbose; `Yielding`, wrong actor).

## 23. The Overflow livelock: context again, from the top

How a thread gets its safepoint: `Safepoint.get` hashes the thread id into a 256-slot
array and linear-probes up to 8 slots, claiming a free or dead slot with a fresh
`Active`. If all 8 probed slots are held by other LIVE threads, `get` returns the
shared `Overflow` token instead. That can happen with several hundred threads running
kernel computations concurrently (virtual threads make it realistic).

What Overflow does today: it is permanently parked, so `enter()` is always false. The
livelock: a lone transform runs through `guardedRun`, which asks `enter()`; refused, it
reroutes through the rescue path, wrapping the step in a `Defer`; the drive pops the
`Defer` and re-runs the same transform; `enter()` refuses again; an identical `Defer`
is minted; forever. The thread spins making zero progress for as long as it stays
overflowed. (Fused chains still run, because `Offset.run`'s inner loop does not
consult the safepoint; it is the lone-transform step that loops.)

Your ruling: better to run without preemption/interruption/stack services than to
never progress. The adopted fix goes one better than the ruling requires, from track
A's design: the fallback `Active` is backed by its own `AtomicReference` cell (the
holder trait the design calls Home and you renamed `Current`), cached in a
`ThreadLocal` so the thread gets the same instance for life. That keeps progress AND
the depth guard AND preemption/interruption delivery (a requester CASes the cell
exactly like a slot; the memory-ordering argument transfers unchanged). Cost: only
detached threads pay the ThreadLocal plus cell read, roughly 4 to 6ns per frame; the
hot slotted path is untouched. A bare unregistered `Active` (my earlier, weaker
proposal) was rejected in the design because it silently makes detached fibers
uninterruptible; a fresh `Active` per `get` was rejected because it defeats the depth
guard. Overflow is deleted outright, which also makes `Safepoint.thread` and the
`Preempted` restore field total (no `Maybe`), removing one type test from the hot
`get` path. Lands inside #15's implementation (your #21 fix presupposes it).

## 18. Context threading design: next step per your instruction

You asked me to make sure `kernel2-context-threading-design.md` is a high-quality doc
and then ask you to review it. That is my next non-queue work item: critical read,
verify its claims against the code, tighten it, then hand it to you.

## 19. Context datastructure: verified summary coming
just drop this
The track D doc recommends against `Context = TypeMap` on typing grounds (effect-keyed
vs value-keyed; `Local`'s two internal state effects share a value type). You
acknowledged the heads-up; my verified summary with a concrete recommendation is
queued behind #18's review pass.

# Designs answered in place

## 14. `LastResort` removed: handlePartial as an outer slice loop
fix
You are right that it is not needed. The drive already returns the remainder whenever
it hits a suspension no installed delimiter matches. So handlePartial does not need to
push its clause INTO the drive at all; it can loop AROUND it:

```scala
def handlePartial[I[_], O[_], E <: ArrowEffect[I, O], A, S](effectTag: Tag[E], v: A < (E & S))(
    clause: [C] => (I[C], Arrow[O[C], A, E & S]) => Maybe[A < (E & S)]
)(using Frame): A < (E & S) =
    @tailrec def slice(cur: A < (E & S)): A < (E & S) =
        val r = `<`.evalLoop(cur, boundary = true)       // plain drive, no clause
        r match
            case c: Kyo.Continue[?, ?, ?] if matches(effectTag, c.suspend) =>
                clause(input(c), continuation(c)) match
                    case Present(next) => slice(next)     // answered: keep driving this slice
                    case Absent        => r               // park: remainder returns to the caller
            case _ => r                                   // done, or parked on a foreign effect
    slice(v)
```

Deleted: the `LastResort` class, `evalBoundary`, and the `last: LastResort | Null`
parameter. The clause is consulted in exactly the same situation as before (only for
suspensions no delimiter matched, since the drive exhausts delimiters before
returning), the continuation the clause receives is the full optimized chain as
before, and parking still returns the remainder. Cost: one extra drive entry per
answered operation, on the scheduler-operation path, which is cold relative to the
operation itself. Implementing in this pass; PendingSchedulerTest and the oracle
suite validate.

## 16. Single-allocation defer: abstract `run()`, and why it is safe
hmm but this will hgenerate complexity and more bytecode in other places. Drop but you can put the def input = () in the class at least?
Your diamond observation is confirmed: `Kyo` and `Arrow.Transform` are both abstract
classes, so `Defer` cannot extend both (that blocked the extends-Suspension-and-Arrow
shape; making `Transform` a trait would put interface dispatch on the hottest path).
And your question stands: `Defer` CAN simply have an abstract `run()`:

```scala
abstract class Defer[+A, -S] extends Kyo[A, S]:
    def frame: Frame
    private[kyo] def run(): A < S
```

`Effect.defer` then mints exactly one anonymous class (`run() = f`), like the old
kernel's `KyoDefer`. The drive's Defer arm calls `run()` instead of `cont(value)`.
Why it is safe: `run()` is evaluated once per drive pass, on the driver's stack,
exactly when `cont(value)` is evaluated today, so laziness and the runs-once-per-drive
semantics are unchanged (EffectTest pins them). The internal (value, cont) producers
(the rescue path and the segment boundary) become a small concrete subclass with
`run() = cont(value)`. Composition: `map(f)` builds a `Chained` node
(`run() = f(outer.run())`), same shape as today's `cont` growth. The one subtle case
is `prepend` (a `Catching` must intercept exceptions thrown by the thunk itself): the
prepended wrapper re-materializes a (value, cont) pair so the thunk runs UNDER the
interceptor; that allocation sits on the cold prepend path, not on defer creation.
Implementing in this pass; the defer/catching EffectTest block validates.

## 9. `prepend` Interceptor design, as requested
prepend sounds odd? install(interceptor)?
The contract: an interceptor is a transform that forwards the value it receives into
its continuation unchanged; it may wrap the EVALUATION (catch exceptions, observe,
schedule a release) but never alters or consumes the value. Exactly three classes
qualify and become its subclasses: `Effect.Catching`, `Observe`, `Finalize`.

```scala
object Arrow:
    /** A pass-through transform: run forwards the value into the continuation
      * unchanged. The only arrows prepend accepts, and the justification for the
      * single cast in [[as]]. */
    abstract class Interceptor extends Transform[Any, Any, Any]:
        // Cast justified by the pass-through contract: on values the arrow is
        // identity-typed, so Arrow[A, A, S] is its true shape at any A.
        final private[kyo] def as[A, S]: Arrow[A, A, S] =
            this.asInstanceOf[Arrow[A, A, S]]
```

Every `prepend` signature changes from `Arrow[Any, Any, Any]` to `Interceptor`:

```scala
// Suspension: the senseless-looking cast disappears
final private[kyo] def prepend(f: Arrow.Interceptor): A < S = map(f.as[A, Any])
// Continue: typed composition, no casts at all
private[kyo] def prepend(f: Arrow.Interceptor): B < S = Continue(suspend, f.as[A, S].map(cont))
```

`Bracket.prepend` and `Defer.prepend` compose the same way. The erased-cast surface
shrinks to the one documented `as`, whose soundness is the class contract, and the
compiler rejects prepending any non-interceptor arrow. If this reads right to you it
implements with the queue (it slots naturally before #16, which reworks
`Defer.prepend`).

# Authorized fix queue (mine to execute, no stops)

## 7. toString sweep: implemented, suite running.
## 8. `(x: Any) match` widenings: next after #7.
## 10. `discard` to `finalizeBracket`: queued.
## 1. Fix `Kyo.lift` body, `Implicits` superclass, `unwrap` to `unnest`: queued.
   (Includes the bench's `liftSlow` call site.)
## 20. `Step`/`step`/`stepSlow` to `private[kyo]`: queued. Also `handlePartial` (and
   the evalPartial the preemption round introduces) become `private[kyo]`: the current
   kernel exposes no public scheduler-drive API, so kernel2 conforms.
## 11. `Observe.scala` extraction + effectful observer: queued, after #1.
## 12. `EffectTrace` rename, `Trace` stub deletion: queued.
## 14. `LastResort` removal (design above): queued.
## 16. Abstract-run `Defer` (design above): queued.
## 3+4. Typed handler hierarchy + `ArrowHandler`/`ContextBinding` naming: queued.
   (Simpler now: no boundary carrier to type.)
## 15. Preemption integration: authorized by your "fix". Sequence: critical read of
   `kernel2-preemption-design.md`, reconcile with the #24 consumption rule (only
   handlePartial's drive consumes; plain eval never polls, which closes the nested
   boundary gap by construction), fold in #21/#23 once you confirm, implement, JMH.
   Your in-doc comment adopted: the design's `Home` holder trait is named `Current`.
## 17. Loop drivers: JMH rows old vs new first, then the tailrec driver port.

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
| 18 | Context threading design | I do the critical read and quality pass on `kernel2-context-threading-design.md`, then ask you to review, per your instruction. |
| 16 | defer's `()` at the call site | Dropped per your ruling; my answer to your "def input = () in the class" question is below. Correct me if I read it wrong. |

Track A design verdict (my critical read, done): sound, adopted with amendments:
the in-drive boundary clause is replaced by #14's outer slice loop; #22 stays
dropped per your ruling (no Maybe slots); the holder trait is named `Current` per
your in-doc comment; eval runs Masked (it must poll: a non-polling eval would
livelock on a pending request the same way Overflow does), and a Preemptible drive
nested in a slice is unsupported and documented, which is the #24 consumption rule.

Authorized queue, in execution order, no stops: #7 (suite running) -> #8 -> #10 ->
#1 -> #20 -> #11 -> #12 -> #14 (your fix) -> #3+#4 -> #15 (with #21 rename and #23
detached-cell fix inside) -> #17 (benchmarks first).
Closed this pass: #16 (dropped, nicety noted below), #19 (dropped entirely, your
"just drop this").

# Needs your attention

## 18. Context threading design: next step per your instruction

You asked me to make sure `kernel2-context-threading-design.md` is a high-quality doc
and then ask you to review it. That is my next non-queue work item: critical read,
verify its claims against the code, tighten it, then hand it to you.


# Designs answered in place

## 14. `LastResort` removed: handlePartial as an outer slice loop  [AUTHORIZED: your fix]

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

## 16. Single-allocation defer: DROPPED (your ruling), one nicety kept

Your ruling: the abstract-run reshape generates complexity and more bytecode
elsewhere; dropped. `Defer` keeps its (value, cont) shape and `Effect.defer` keeps
its two allocations.

Your follow-up question, "can you put the `def input = ()` in the class at least?":
the literal old-kernel move (a fixed unit member in the class) requires `value` to be
an abstract member instead of a constructor parameter, which is the reshape just
dropped. The zero-cost equivalent I am applying instead: an `inline` unit-fixed
constructor on the companion, so the one unit-valued producer reads
`Kyo.Defer(thunk)` with no `()` at the call site and no extra bytecode (inline
expands to `new Defer((), thunk)`). If you meant something else, say the word and I
adjust.

For reference, the dropped analysis: your diamond observation is confirmed: `Kyo` and `Arrow.Transform` are both abstract
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
## 15. Preemption integration: authorized (your fix), design read done, ready to
   implement after #3+#4. Includes, all authorized by your fixes: #23 (Overflow
   deleted; detached threads get a cell-backed fallback `Active` cached in a
   ThreadLocal, keeping progress, depth guard, and preemption/interruption delivery;
   only detached threads pay the ~4-6ns) and #21 (`Parked` becomes `Preempted`,
   field `restore: Active`, no `Maybe`; `Safepoint.thread` also becomes total).
   Amendments from my critical read: the in-drive boundary clause is superseded by
   #14's outer loop; eval runs Masked (a non-polling eval would livelock on a pending
   request); nested preemptible drives unsupported and documented (#24 rule); holder
   trait named `Current` per your in-doc comment; no Maybe slots (#22 dropped). JMH
   after.
## 17. Loop drivers: JMH rows old vs new first, then the tailrec driver port.

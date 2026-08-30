# `Eval.partial` for the proto

How partial evaluation lands in `kyo.proto.kernel`, checked against the reference's
`Eval.partial` (`kyo/kernel/internal/Eval.scala:112-123`) and the proto eval as it stands.

Ruled: parking builds a dedicated `Park` node rather than one `Handle` wrapper per open
region. The Handle equation below stays the law; `Park` is its fast-path carrier, and every
observable behavior of a park must match the wrapped reading.

## The scope, stated honestly

The proto already parks on suspension. A suspension foreign to every region exits the eval
with each region folded into its continuation by the crossing rebuild, and `Eval.scala:132`
returns it: that lane, the one the reference needed `Park` hardest for (`Isolate.scala:572`
constructs one to cross an Async boundary), ships today with no work.

`Eval.partial`'s domain is the lane where that trigger can never fire. Its row is `Any`:
every effect is handled by some region inside the eval, so no suspension is ever foreign to
the whole stack. A CPU-bound computation, a tight `Loop` or a big fold, does strict work and
defers and never crosses anything. Preempting it is the one thing this item adds.

## The equation first

The eval's standing invariant is that at any loop point the remainder of the computation is

```
handle(... handle(defer(v, contA, contB), h_inner, s_inner).cont ..., h_outer, s_outer).cont
```

one region per stack entry, innermost closest to the value, each carrying the continuation
stored when it was installed. A park denotes exactly that value. The `Park` node carries it
as one node and one packed array instead of N `Handle` wrappers; the equivalence is the
node's contract, pinned by tests (below), and any observable divergence between a `Park` and
its Handle-wrapped reading is a bug in `Park`.

## The surface

```scala
private[kyo] def partial[A](v: A < Any): A < Any
```

Same signature, same protocol as the reference:

1. `Safepoint.consumeStopped(slot)` before starting: a stop already delivered ends the slice
   before it begins, the input comes straight back, and the sentinel is taken so the next
   slice runs.
2. Otherwise run armed, and consume the stop at the boundary in the `finally`, so park,
   completion, and failure all satisfy it and no stale sentinel survives.

The row is `Any` for the same reason as the reference: every effect is already handled; a
slice cannot park on an unhandled operation and have it answered later.

## The node

In `KyoInternal.scala`, mirroring the reference's `val`-field shape (single construction
site, cold path) but with three slots per region, not four: contexts are deliberately not
captured, because re-installation derives them from where the resume stands.

```scala
/** A parked slice: the regions an eval had open, standing around the value they were installed
  * around. Semantically this is the value wrapped in one Handle per entry, and every observable
  * behavior must match that reading; one node and one array stand in for the N wrappers.
  */
final class Park[+A, -S](
    val value: Any < Any,
    // handler, state, continuation per region, outermost first. Contexts are not captured:
    // a re-installed region derives its context from where it stands, as the Handle arm does
    val entries: Array[AnyRef]
) extends Pending[A, S]:
    Debugger.onAlloc(this)

    def release(ex: Throwable): Any < Any =
        // the equation's order: the value's releases first, then each region's, innermost first
        var acc = value match
            case p: Pending[?, ?] => p.release(ex)
            case _                => ()
        var i = entries.length - 3
        while i >= 0 do
            val h  = entries(i).asInstanceOf[Handler[Nothing, Any, Any, Any, Any]]
            val st = entries(i + 1)
            acc = acc.andThen(h.release(st, ex))(using Frame.internal)
            i -= 3
        acc
end Park
```

## `Stack.snapshot()`

A move, not a copy: it empties the stack as it packs, so the pooled arrays go back clean and
the entries have exactly one owner.

```scala
def snapshot(): Array[AnyRef] =
    val out = new Array[AnyRef](size * 3)
    var i   = 0
    while i < size do
        out(i * 3) = handlers(i)
        out(i * 3 + 1) = states(i).asInstanceOf[AnyRef]
        out(i * 3 + 2) = continuations(i)
        handlers(i) = null; states(i) = null
        contexts(i) = Context.empty; continuations(i) = null
        i += 1
    size = 0
    out
```

## The eval changes

`Eval.apply` splits into the public `apply(v)` and a private `apply(v, armed: Boolean)`.
`armed` is a val of `apply`, closed over by `loop`, never a loop parameter: it does not
change during the eval, and the poll is gated on it so an unarmed eval pays one predictable
branch and nothing else (the reference makes the same trade at `Eval.scala:453`).

After `Safepoint.save(slot)`, which installs a fresh budget and clears the armed bit as it
reads: `if armed then Safepoint.arm(slot)`. The existing `restore` in the finally already
hands a nested eval's caller back its state, armed bit included, so a slice nested in another
eval leaves no trace, and a plain `Eval.apply` nested inside a slice runs unarmed and cannot
park. Both properties come from code that is already there.

The poll sits on the Defer arm, mirroring the reference's placement (`:524`), and builds the
park in place: two allocations per park regardless of depth (the `Effect.defer` may be a
third when the pending conts are not identity; its free-slot laws fold them when they are).

```scala
case kyo: Kyo.Defer[AX, Y, T, S2] @unchecked =>
    if armed && Safepoint.stopped(slot) then
        val curr = Effect.defer(kyo, contA, contB)
        if stack.isEmpty then curr.asInstanceOf[A < S]
        else new Kyo.Park(curr, stack.snapshot()).asInstanceOf[A < S] // row widens: the entries answer its effects
    else
        loop(kyo.value, kyo.contA, kyo.contB.chain(contA.chain(contB)), ctx)
```

One poll point suffices, for the same reason it does in the reference: the budget makes the
Defer arm inevitable. Every strict application gates on `Safepoint.enter`; a drained budget
turns applications into deferrals, and every explicit `flatMap`/by-name builds a Defer
outright, so a pending stop is observed within one budget period of pure strict work and
usually much sooner. The proto needs no answers-loop bail (the reference's `armed` threading
into `dispatchContFast`/`dispatchLoopFast`): the handleLoop continue lane routes through the
main match every iteration, so it passes the poll's arm on the same cadence as everything
else.

## The resume arm

Placed with the rare shapes, before the settled arm. This is re-installation, not
restoration, and it is the one subtle piece: each entry is pushed the way the Handle arm
pushes it, against the ambient context, so a `HandlerContext` binding re-resolves from the
resume point and region exits restore resume-time contexts, never park-time ones. Restoring
snapshot contexts verbatim would drop every binding installed around the resumed value;
that is why contexts are not in the array at all.

```scala
case kyo: Kyo.Park[T, S2] @unchecked =>
    val entries = kyo.entries
    var c       = ctx
    var i       = 0
    while i < entries.length do
        val h    = entries(i).asInstanceOf[Handler[EX, AX, Y, Any, VX]]
        var st   = entries(i + 1).asInstanceOf[VX]
        var cont = entries(i + 2).asInstanceOf[Arrow[Y, Any, Any]]
        if i == 0 then cont = cont.chain(contA.chain(contB)) // the pending continuation follows the outermost region
        val bound = h match
            case hc: Handler.HandlerContext[VX, ?, AX, Y, ?] @unchecked =>
                st = hc.resolve(Maybe.when(c.contains(hc.tag))(c(hc.tag)))
                c.update(hc.tag, st)
            case _ => c
        stack.push(h, st, c, cont)
        c = bound
        i += 3
    end while
    loop(kyo.value, Arrow.id, Arrow.id, c)
```

Since this replays the Handle arm's install logic, the two arms share a small `install`
helper so they cannot drift.

## Why fresh state, and why not the crossing

The eval consumes a `Handle` at installation, reading its four fields into the stack and
looping on the interior, so by park time the original node is the install-time snapshot: its
`value` points at where the region started, and a `handleLoop`'s state has advanced past it.
The stack entries are the live region, and the snapshot packs them.

The crossing's automatic rebuild is the wrong shape to borrow: it builds rotated transforms
because a foreign suspension must travel out, be answered beyond this eval, and only then
have the region re-install around the answer. A park has nothing to answer, so the regions
stand around the remainder directly.

## What needs nothing

- `<.chain` onto a `Park` lands on the default arm and defers, the correct reading: a park
  has no free slot.
- `Eval.release` already dispatches to the node's own `release`.
- No `parkable` guard (`:490-500` in the reference) and no second poll at region entry
  (`:661`): the proto has no `Binding` node and no finalizer registry, releases are
  structural through the nodes' own `release`, so a park cannot orphan anything.
- No snapshot of contexts, marks, or finalizers; the reference's four spans reduce to one
  packed array.

## Consequences to record

- Walkers that enumerate node kinds gain a `Park` arm when they land: B5's `dispatchFirst`
  (`case p: Park => loop(p.value)`, one line, same as the reference's `:568`) and B3's trace
  splice. Recorded here so neither discovers it as a surprise.
- The settled lane pays one more instanceof test in the match. After implementing, the whole
  bench class runs on both variants, per the measurement rules.

The entry contexts are dropped and a re-installed region re-derives its binding, the Handle
arm's documented semantics. A non-identity context update does not survive, which is the
caveat already on file for the foreign-crossing path; the one ruling on context-update
scoping covers crossing, park, and B1 together.

## What resumption means

The parked value is an ordinary computation: resumable by any `Eval.apply` or a later
`partial`, on any thread, because it is immutable data referencing immutable data (the
snapshot array is written once at park time and only read at resume; the mutable stack goes
back to the pool clean). Cross-thread resume needs no handoff protocol beyond ordinary safe
publication of the value itself. Multi-shot holds: resuming the same park twice re-installs
the regions twice from the same immutable entries.

## Work items

1. The `Park` node in `KyoInternal.scala` and `Stack.snapshot()`.
2. `Eval.partial`, the `armed` split, the Defer-arm poll, the resume arm, and the shared
   `install` helper between the Handle arm and the resume arm.
3. Tests, reproduction-first, in `EvalTest`:
   - park mid-computation, resume, same answer as the unparked run;
   - Park-vs-Handle-wrap observational equivalence: same answer and same release order as
     the hand-wrapped equivalent of the same entries;
   - a `handleLoop` with real state parked mid-loop resumes at the parked state, not the
     initial one;
   - resume under a new context binding sees the new binding (the re-derivation semantics);
   - `chain` onto a parked value composes;
   - stop pending before the slice returns the input and consumes the sentinel;
   - a settled position with an empty stack parks to itself;
   - a nested unarmed eval inside a slice does not park;
   - a throw during a slice still consumes the stop at the boundary;
   - resume on a different thread (jvm).
   Open item: the trigger differs per platform (`stop(thread)` on jvm, `deadline` on
   js-wasm), so the shared tests need a platform-neutral way to make a stop pending; read
   how the reference's partial tests do it before inventing one.
4. Benchmarks after: the whole `ProtoBench` class on both variants, per the measurement
   rules; the poll touches the Defer arm and the new arm touches the settled lane, which
   every row crosses.

## What this deliberately does not include

No caller. The reference's `partial` is driven by the scheduler in kyo-core; wiring that up
is that layer's migration, not the kernel's. The kernel deliverable is the capability, its
tests, and the measurement.

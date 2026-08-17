# DIS-3: the design, and the cast that needs sign-off before it is measured

Proceeding under DIS-3's recorded default. The rulings document says *"I will surface the exact
spelling for sign-off before it is measured"*, and this is that. **No kernel source has been changed
for DIS-3.** This is the design and the sign-off request; the edit follows a ruling on the cast.

## Why this candidate and not another

DIS-1 is refuted in all three constructible tier splits, and what its refutation left open is whether
*some other shape* recovers the win without enlarging the compilation unit. The refuted fix forced the
607-byte `dispatch$1` inline, which won -6.8% on `continuationBodiesFuse` and destroyed
`trailingMapsStayLinear`'s scalar replacement, +240,000 B/op, reproduced to one byte across two
independent five-leg measurements.

DIS-3 does not force that body inline. It **deletes it**, by collapsing the two suspension node kinds
into one so the drive has a single suspension arm. That is the only proposed shape that could take the
win without paying the allocation, because it removes the second arm rather than growing the first.

## What the two kinds actually are

```scala
abstract class Suspend[I[_], O[_], E <: ArrowEffect[I, O], A, B, S] extends Defer[Any, B, E & S]:
    def frame: Frame
    def tag: Tag[E]
    def input: I[A]
    def cont(v: O[A]): B < S
    final override def apply(v: Any) = cont(v.asInstanceOf[O[A]])
    override def chain[C, S2](f: Arrow[B, C, S2]): Arrow[Any, C, E & S & S2] =
        if f eq Identity then this.asInstanceOf[Arrow[Any, C, E & S & S2]]
        else SuspendWith(this, f)

final private[proto] class SuspendWith[I[_], O[_], E <: ArrowEffect[I, O], A, X, B, S, S2](
    val susp: Suspend[I, O, E, A, X, S],
    val cont: Arrow[X, B, S2]
) extends Defer[Any, B, E & S & S2]
```

`SuspendWith` is exactly a `Suspend` plus a pending continuation, produced by `Suspend.chain` whenever
the chained arrow is not `Identity`. The drive then tests for both and dispatches them through two
paths, and the second path is `dispatch$1`, the 607-byte method refused as `hot method too big`.

Note `Suspend.chain` **already carries a cast** on its `Identity` fast path,
`this.asInstanceOf[Arrow[Any, C, E & S & S2]]`, justified by reference identity: `f eq Identity`
implies `C = B`. That is the skill's *reference-identity knowledge* category and it is precedent for
the shape below, not a new kind of concession.

## The collapsed shape

One node carrying an optional continuation, so `chain` composes rather than switching kind:

```scala
abstract class Suspend[I[_], O[_], E <: ArrowEffect[I, O], A, B, S] extends Defer[Any, B, E & S]:
    def frame: Frame
    def tag: Tag[E]
    def input: I[A]
    def cont(v: O[A]): B < S
```

with the pending arrow folded into a single field rather than a distinct class. The drive then has one
suspension arm and `dispatch$1` has no reason to exist.

## The cast, stated exactly, for sign-off

Collapsing the kinds means the delivery arm holds a continuation whose input type is the *previous*
arm's output type, and that equality is not expressible once the two type parameters `X` and `B` are
carried by one node instead of two. The spelling I would use is the ladder's **step 2, a typed pattern
with `@unchecked`**, not a bare cast:

```scala
case s: Suspend[[X] =>> Any, [X] =>> Any, Nothing, Any, Any, Any] @unchecked =>
```

which is the spelling the drive **already uses today** for exactly this reason, at the existing
`Suspend` and `SuspendWith` arms in `Eval.loop`. So the request is narrower than the plan implies: the
candidate is not introducing a new category of cast, it is keeping the one already there while
removing one of the two arms that use it.

Category, per the skill's closed set: **erasure-forced**, since `O[A]` is erased at the drive and the
node's own type parameters are gone by the time the arm runs.

**What I need from you:** confirmation that keeping this `@unchecked` typed pattern, on one arm instead
of two, is signed off. If you would rather the collapsed arm carry evidence instead of `@unchecked`, say
so, because that changes the design rather than the spelling.

## What must be true before it is measured, and what would falsify it

- The proto suite must be green, all 128, including the nesting tests: the representation contract
  says a value handed out is complete and valid in any context, and collapsing node kinds is exactly
  the sort of change that breaks replay.
- `javap` must show `dispatch$1` **gone**, not merely smaller. If it survives, the candidate did not do
  what it claims and the measurement is pointless.
- **It must not share a bracket with DIS-1**, which subsumes the byte saving; crediting this for that
  would repeat the attribution error the skill's worked example is about.
- **Falsifier**: `dispatch$1` disappears and `continuationBodiesFuse` does not move. Then the frame was
  never the mechanism and DIS-1's measured -6.8% needs re-explaining.
- The row to watch for the cost side is `trailingMapsStayLinear`'s **allocation**, which is exact and
  has reproduced to one byte twice. If it moves, this shape pays the same price the forced inline did
  and is no better.

# The Arrow mechanism

This document explains how `Arrow` works in kyo-kernel2: what the node kinds
are, how composition builds trees, how `optimize` turns trees into executable
chains, how application dispatches, and where `SmallLimit` and the segment
boundaries fit. It ends with the three questions that were annotated in the
source and how each was resolved (the `respine` rename, the `optimize`
strategy shape, and `Maybe(self)` in `step`).

## 1. The four shapes of an arrow

`Arrow[A, B, S]` is a function from `A` to `B < S` that exists as data. There
are exactly four shapes:

```scala
// the identity: one shared instance, recognized by reference
private val empty = new Transform[Any, Any, Any]: ...

// one step: the only shape with behavior
abstract class Transform[-A, +B, -S] extends Arrow[A, B, S]:
    def frame: Frame
    def run[C, S2](v: A, context: Context, handlers: Handlers, cont: Arrow[B, C, S2]): C < (S & S2)

// composition: a tree, built by map, never executed directly
final private[kyo] class AndThen[-A, B, +C, -S](
    val a: Arrow[A, B, S],
    val b: Arrow[B, C, S]
) extends Arrow[A, C, S]

// the pre-linked chain node and the decomposition handle in one
final class Step[-A, B, +C, -S] private[kyo] (
    val head: Transform[A, B, S],
    val next: Arrow[B, C, S]
) extends Transform[A, C, S]
```

`Transform.run` takes the continuation as a parameter (`cont`) instead of
returning a value to be post-processed: a transform finishes by handing its
result forward, which is what lets a chain of transforms execute as a loop
instead of a nest of returns.

## 2. Composition is lazy

`map` builds structure and nothing else:

```scala
def map[C, S2](f: Arrow[B, C, S2]): Arrow[A, C, S & S2] =
    if isEmpty(self) then f.asInstanceOf[Arrow[A, C, S & S2]]
    else if isEmpty(f) then self.asInstanceOf[Arrow[A, C, S & S2]]
    else new AndThen(self, f)
```

So `t1.map(t2).map(t3)` is `AndThen(AndThen(t1, t2), t3)`: a tree whose shape
records the order maps happened in, at one allocation per map. The tree is
not executable in that form; it is normalized on demand by `optimize`.
Composing with the identity is free in both positions, which is why a bare
operation's continuation (the shared `empty`) costs nothing until something
real is appended.

## 3. optimize: from tree to chain

Execution wants a linked list of transforms, head first: `Step(t1,
Step(t2, Step(t3, empty)))`. `optimize` converts the `AndThen` tree into
exactly that, choosing between two strategies by size:

```scala
self match
    case at: AndThen[?, ?, ?, ?] =>
        if fits(at, 2 * internal.SmallLimit) >= 0 then linearize(at, empty)
        else unfold(at)
    case _ =>
        self
```

`fits` is a bounded scan with one number: the remaining node budget, or `-1`
once exhausted. It decrements on every node, interior or leaf, so the walk
and its recursion depth are bounded even on a deep left spine, where the
first leaf appears only after the full descent. A chain of n transforms has
2n-1 nodes, so the doubled limit keeps the leaf capacity at `SmallLimit`
(32), and the scan's cost is capped no matter how large the tree is.

**Small trees** go through `linearize`: a direct recursion that rebuilds the
tree as a right-leaning chain.

```scala
def linearize(node: Arrow[?, ?, ?], rest: Arrow[Any, Any, Any]): Arrow[Any, Any, Any] =
    node match
        case at: AndThen[?, ?, ?, ?] =>
            linearize(at.a, linearize(at.b, rest))
        case t =>
            new Step(t.asInstanceOf[Transform[Any, Any, Any]], rest)
```

Recursion depth is bounded by the tree size, which `fits` just proved is at
most 32 transforms, so the stack is safe and no auxiliary structure is
needed.

**Large trees** go through `unfold`: an explicit thread-local buffer flattens
the tree without recursion (the tree can be arbitrarily deep, so recursing
over it could overflow), and the chain is relinked back to front with a
`segmentBoundary` spliced every `Safepoint.Period` elements:

```scala
@tailrec def link(acc: Arrow[Any, Any, Any], n: Int): Arrow[Any, Any, Any] =
    if !it.hasNext then acc
    else if n == Safepoint.Period then
        link(new Step(segmentBoundary, acc), 0)
    else link(new Step(it.next().asInstanceOf[Transform[Any, Any, Any]], acc), n + 1)
```

The boundary is the stack-safety valve for long chains:

```scala
private val segmentBoundary = new Transform[Any, Any, Any]:
    def frame = Frame.internal
    def run[C, S2](v: Any, context: Context, handlers: Handlers, cont: Arrow[Any, C, S2]): C < (Any & S2) =
        Kyo.Defer(defaultLift(v), cont.asInstanceOf[Arrow[Any, Any, Any]]).asInstanceOf[C < (Any & S2)]
```

Running a boundary does not call forward: it returns a `Defer` carrying the
rest of the chain, unwinding the stack, and the drive's trampoline pops the
Defer and continues. So a resumed chain's stack depth is bounded by the
segment size regardless of total chain length, and the interior of a segment
carries no per-step check at all: the cadence lives in the chain structure.

## 4. Application

There are two application forms. The deferred form embeds an arrow at a call
site without running it (it builds a `Defer` the drive will pop, or fuses
onto a pending computation). The execution form runs now, under the ambient
parameters, and is the one every internal execution site uses:

```scala
def apply[S2](v: A < S2, context: Context, handlers: Handlers): B < (S & S2) =
    if isEmpty(self) then
        v.asInstanceOf[B < (S & S2)]
    else if v.isInstanceOf[Kyo[?, ?]] then
        // the one place suspensions bubble: consult handlers, else push self onto the suspension
        ...
    else
        self match
            case o: Step[Any, Any, Any, Any] @unchecked =>
                o.head.run(Kyo.unnest(v), context, handlers, o.next).asInstanceOf[B < (S & S2)]
            case t: Transform[A, B, S] @unchecked =>
                guardedRun(t, v, context, handlers)
            case _ =>
                applySlow(self, v, context, handlers)
```

In order: identity returns the value; a pending value either gets answered in
place (a registered handler matches) or the arrow is pushed onto the
suspension and bubbles; a chain enters its fused interior; a lone transform
runs under the guard; an `AndThen` is optimized first (`applySlow`) and then
applied, so trees normalize exactly once, at first execution.

## 5. The fused interior

`Step.run` is where a chain executes as a loop. One stack frame drives the
whole segment:

```scala
@tailrec def loop(o: Step[Any, Any, Any, Any], cur: Any): Any =
    o.head match
        case jump: Step[Any, Any, Any, Any] @unchecked if isEmpty(o.next) =>
            loop(jump, cur)
        case t =>
            val w =
                try t.run(cur, context, handlers, empty)
                catch ...
            if w.isInstanceOf[Kyo[?, ?]] then
                o.next.map(k)(w.asInstanceOf[Any < Any], context, handlers)
            else
                o.next match
                    case n: Step[Any, Any, Any, Any] @unchecked => loop(n, Kyo.unnest(w))
                    case _ => k(Kyo.unnest(w).asInstanceOf[Any < Any], context, handlers)
```

Each head runs with `empty` as its continuation, so its raw result comes back
to the loop, which feeds it to the next head directly: no intermediate nodes,
no stack growth. If a head produces a pending computation, the remaining
chain is mapped onto it and the loop's frame returns: the pending value
carries the rest. The `jump` arm skips through a chain stored as another
chain's head. The currency between heads is unnested raw values; a value that
is itself a computation re-enters as data through the central lift when it is
handed back to a pending position.

## 6. Step: decomposing instead of running

Some callers do not want the arrow to run itself; they want to execute the
first step in their own bytecode and keep the rest as a value. The `Step`
node is that view of itself: `head` is the first executable transform,
`next` the rest of the chain, and the middle type connecting them is the
class's second type parameter, existential to the caller
(`Maybe[Step[A, ?, B, S]]`).

`step` produces it: a `Step` is its own decomposition, an `AndThen`
optimizes first, a lone transform gets wrapped once, and the identity has no
step (`Absent`). This is the resume protocol used at the scheduler boundary:
a parked continuation is re-entered by running
`s.head.run(input, ..., s.next)`.

## 7. The guard

`guardedRun` wraps a lone transform's execution with the safepoint depth
budget:

```scala
private def guardedRun[A, B, S, S2](t: Transform[A, B, S], v: A < S2, context: Context, handlers: Handlers): B < (S & S2) =
    val safepoint = Safepoint.get
    if !safepoint.enter() then rescue(t, v)
    else
        try
            val r = t.run(Kyo.unnest(v).asInstanceOf[A], context, handlers, Arrow[B]).asInstanceOf[B < (S & S2)]
            safepoint.exit()
            r
        catch ...
```

If the budget refuses (the stack is already deep), `rescue` returns
`Kyo.Defer(v, self)` instead of running: the work parks and the trampoline
resumes it on a fresh stack. This is the depth bound for the eager paths that
run outside a chain's boundary cadence: construction-time maps and one-off
transform applications.

## 8. The two constants

- `SmallLimit = 32`: the threshold between the recursive and the buffered
  normalization strategies in `optimize` (`fits` receives it doubled, since
  its budget counts every node and a chain of n transforms has 2n-1). A
  chain at most this long cannot overflow the stack during `linearize` and
  is short enough that boundary splicing is unnecessary: depth is bounded by
  the chain's own length.
- `Safepoint.Period`: the boundary cadence in long chains. Every `Period`
  transforms, one boundary node returns to the trampoline. Between
  boundaries, zero checks.

## 9. Answers to the annotated questions

**`respine` naming (`optimize`, first inner method).** Resolved: renamed to
`linearize`, per the in-code request for a more intuitive name. It converts
the composition tree into the linear executable chain, which is the standard
word for exactly that.

**"This logic seems quite complex, is it well optimized? should the count be
discarded after the check? can't it optimize something later?" (`optimize`,
strategy choice).** Three parts:

- The complexity is real but each piece is load-bearing: the bounded scan
  exists so `linearize` can recurse safely (its depth is proven bounded
  before it runs), and `linearize` exists because for the common case (short
  chains) it beats `unfold`, which pays for a thread-local lookup, buffer
  traffic, and the boundary bookkeeping that short chains do not need.
- Resolved: the old `count` computed a full size and used only its sign; it
  is now `fits`, a single remaining-budget number that is the bound itself,
  so nothing is computed and discarded. One caution learned landing it: the
  budget must decrement on every node, not only at leaves, because a deep
  left spine descends fully before its first leaf; a leaf-only budget hung
  the deep-resumed-continuation test until the walk itself was bounded.
  Merging the scan into `linearize` as a bail-to-`unfold` mid-recursion
  remains a possible micro-optimization, unimplemented.
- "Can't it optimize something later": normalization is already deferred to
  first execution (`applySlow` calls `optimize` when an `AndThen` is first
  applied), and the result replaces nothing in place: an arrow held by two
  sites is optimized by whichever executes first, and the other still holds
  the tree. Caching the optimized form in the `AndThen` node (a lazily filled
  field) would make repeated application of the same held arrow pay
  normalization once. Today that case is rare (chains are usually applied
  once, or pre-optimized at the park boundary), so the field is not there.

**`Maybe(self)` in `step`.** Yes: in that arm `o` and `self` are the same
reference, so `Maybe(o.asInstanceOf[Step[A, B, S]])` and
`Maybe(self.asInstanceOf[Step[A, B, S]])` are identical in behavior and cost;
the pattern binding is only serving as the evidence that the cast is
justified. Writing `Maybe(self)` directly needs the same cast, so the change
is purely cosmetic; keeping the binding makes the justification visible.

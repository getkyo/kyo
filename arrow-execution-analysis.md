# Arrow chain execution: why the fusionAfterSuspension family loses to the old kernel

## Measured facts

Step-call counts per AndThen instance (temporary counter on AndThen.step, since removed):

| shape | step firsts | step repeats | reading |
|---|---|---|---|
| fusionAfterSuspensionRunOnly, 1000 ops over one stored chain | 1 | 999 | the stored chain re-flattens per op, but only because the bench stores and reuses one value |
| fusionAfterSuspension, 10 ops x 1000 levels | 10,010 | 0 | one flatten per level, every instance stepped exactly once |
| trailingMapsStayLinear, 10 ops x 10,000 levels | 100,000 | 0 | one flatten per level, single-use |

Real shapes never re-step an instance, so memoizing the flatten only repairs the run-only
microbenchmark's artificial reuse. Probed and discarded.

Board numbers this analysis has to explain (like-for-like mirrored boards, 3 forks):

| row | old time (us/op) | new time (us/op) | old alloc (B/op) | new alloc (B/op) |
|---|---|---|---|---|
| fusionAfterSuspension | 87.98 | 271.49 | 408,437 | 1,073,154 |
| fusionAfterSuspensionRunOnly | 0.283 | 0.829 | 0.002 | 1,288 |

## The mechanism: three representations per map over a suspension

Per `.map` on a pending suspension, the new kernel materializes:

1. the Suspend wrapper (`Suspend.map`, KyoInternal.scala:56: a new anonymous Suspend with root
   delegation, holding `root` and the new cont), roughly 24-32 bytes. The old kernel pays the
   equivalent wrapper and nothing else (about 37 B/map measured on the whole-cycle row).
2. the chain node from `cont.chain(f)` (Arrow.scala:16): a `Step` for the second transform,
   an `AndThen` for every one after, roughly 24 bytes each. O(1) append is the point.
3. at answer time, `AndThen.step` (Arrow.scala:91) unrolls the accumulated tree through the
   thread-local scratch deque and links a fresh chain of `Step` objects, one per transform,
   roughly 24 bytes each, once per use.

The old kernel has only representation 1: its continuation wrapper is itself the executable
node, built at map time in final form. That is exactly why it is quadratic on trailing maps
(re-attaching an accumulated remainder rebuilds wrappers level by level), and why kernel2's
deferred-flatten representation wins that row by 700x. The constant-factor price is
representations 2 and 3.

## Fix directions

### B. Execute the spine from the scratch region, materialize only on suspension

`step`'s linked-Step output exists so every transform can receive its remainder as a value
(`apply(v, next)`), because a transform that suspends must capture `next` into the new
suspension. But the remainder only needs to exist as a value at suspension points. Execution
can instead:

- unroll the tree into the scratch buffer exactly as today (no Step minting),
- drive the transforms iteratively with the single-argument `apply(v)` (identity next; the
  stored transforms' captured downstream is always identity, verified on the map path),
- when a transform returns pure, continue with the next buffer index: zero allocation,
- when a transform returns a Kyo, materialize the remaining suffix of the buffer into linked
  Steps and attach with `kyo.map(remainder)`: allocation proportional to what is left, only on
  suspension. In fusionAfterSuspension the suspending transform is the last one, so the
  remainder is empty and the row's answer path allocates nothing.

Reentrancy is the sharp edge: the driven transforms run user code that may itself flatten
other chains on the same thread-local scratch. The current code is safe only because step()
fully drains the buffer before returning. The fix is region discipline: replace the deque with
an indexable growable array plus a top index; each execution brackets its region
[mark, top), nested uses stack above and restore their own marks, so an exception unwinding
past a bracket is corrected by the enclosing bracket's restore.

Expected effect: kills representation 3 entirely (about 24 B and one linked-node build per
transform per use), fixes run-only structurally (steady-state zero allocation, no memo), and
removes the flatten-link pass from the whole-cycle row.

### C. Fuse the chain node into the Suspend wrapper

`Suspend.map` allocates the wrapper and the AndThen separately. The anonymous wrapper can
itself carry the two fields (previous cont, appended transform) and serve as the chain node,
collapsing representations 1 and 2 into one object, the same trick suspendWith uses for the
suspension-as-continuation. Saves about 24 B and one indirection per map at build time,
bringing build-side allocation to parity with the old kernel's single wrapper.

### Memoize AndThen.step: yes, and it is footprint-free

AndThen today is a 12-byte header plus two compressed refs, 20 bytes padded to 24. A third
ref field lands exactly on the 24-byte boundary: zero growth on the default object layout the
canonical bench numbers run on (under UseCompactObjectHeaders it would cost one 8-byte
alignment step; benches deliberately run without that flag). The race is benign (idempotent
computation, worst case a duplicate flatten).

The split that keeps single-use chains allocation-free: `step()` populates the cache, because
its callers need the chain as an Arrow value and the linked Steps must be minted anyway
(handlePartial's `kyo.cont.step`, identity's two-arg apply, rebuild sites). The execution path
(B below) reads the cache when present but never populates it, because minting on first
execution would re-introduce the per-use allocation on every single-use chain, which the
counts show is the dominant shape.

### Rejected: pre-linked construction

Appending to a right-linked list is O(n) per map, O(n^2) per chain: this is the old kernel's
trailing-maps quadratic, the thing the AndThen representation exists to fix.

## The proposed change, concretely

### 1. The scratch becomes a region: ordered array plus mark/top discipline

The tree unroll runs no user code, so its pending-node stack can stay a plain reused deque.
Only the ordered transform list must survive while user code (the driven transforms) runs, so
it moves to an indexable growable array bracketed by mark/top. Nested executions stack above
the caller's region; every bracket restores its own mark, so an exception unwinding past one
bracket is corrected by the enclosing bracket's restore.

```scala
final private class Scratch:
    val pending          = new ArrayDeque[Arrow[?, ?, ?]] // unroll only, drained before user code runs
    var region           = new Array[Transform[Any, Any, Any]](256)
    var top              = 0
    def push(t: Transform[Any, Any, Any]): Unit =
        if top == region.length then region = java.util.Arrays.copyOf(region, top * 2)
        region(top) = t
        top += 1
end Scratch

@static private val scratch: ThreadLocal[Scratch] =
    new ThreadLocal[Scratch]:
        override def initialValue() = new Scratch
```

### 2. Execution drives the region directly; the remainder is minted only on suspension

A single internal entry replaces the step-then-apply dance at the evaluator's walk sites. The
default implementation is today's behavior; AndThen overrides it with region execution. (Name
provisional.)

```scala
// Arrow
private[kyo] def applyTo(v: Any < Nothing): Any < Nothing =
    val s = step
    s.head(v, s.tail)
```

```scala
// AndThen
private var flattened: Step[Any, Any, Any] = null

def apply(v: A) = applyTo(v).asInstanceOf[C < S]

private[kyo] override def applyTo(v0: Any < Nothing): Any < Nothing =
    val cached = flattened
    if cached ne null then cached.head(v0, cached.tail)
    else
        val s    = scratch.get
        val mark = s.top
        unroll(s)         // today's tree walk, pushing transforms in order; no user code
        val end  = s.top
        @tailrec def run(i: Int, v: Any < Nothing): Any < Nothing =
            if i == end then
                s.top = mark
                v
            else
                s.region(i)(v, identity) match
                    case kyo: Kyo[Any, Any] @unchecked =>
                        val rest = remainder(s, i + 1, end)
                        s.top = mark
                        if rest eq identity then kyo else kyo.map(rest)
                    case v2 =>
                        run(i + 1, v2)
        run(mark, v0)
end applyTo

private def remainder(s: Scratch, from: Int, end: Int): Arrow[Any, Any, Any] =
    @tailrec def link(j: Int, acc: Arrow[Any, Any, Any]): Arrow[Any, Any, Any] =
        if j < from then acc
        else link(j - 1, Step(s.region(j), acc))
    link(end - 1, identity)

def step =
    val cached = flattened
    if cached ne null then cached
    else
        val res = flatten   // today's unroll + link, via the region with the same bracket
        flattened = res
        res
```

```scala
// Eval
private def walk(cont: Arrow[Any, Any, Any], v: Any < Nothing): Any < Nothing =
    cont.applyTo(v)
```

Properties, each following from a specific line:

- Pure runs allocate nothing: the region is reused memory, transforms are applied with
  identity as their next (their captured downstream is always identity on this path), and the
  loop threads values through registers.
- A transform that suspends (including a Safepoint park: enter returning false produces a
  Defer, which is a Kyo) gets exactly the unfinished suffix as linked Steps. In
  fusionAfterSuspension the suspending transform is the last, so `rest eq identity` and the
  answer path attaches nothing.
- Budget and preemption are untouched: every driven transform still passes through mapLoop's
  enter/exit at its own application site.
- mapLoop itself is untouched: its `next` on region-driven paths is always identity or a
  linked Step (both have free `step`), never an AndThen.
- A Kyo arriving as the input value falls into the first iteration's Kyo arm and attaches the
  full chain, which is what happens today after the flatten.

### 3. Follow-up: fuse the chain node into the Suspend wrapper

`Suspend.map` allocates the wrapper and the chain node separately (KyoInternal.scala:56-66).
The wrapper can carry the two chain fields itself and serve as its own cont, the same move
suspendWith made for the suspension-as-continuation. Sketch, needs the Arrow/Kyo interplay
worked out:

```scala
final def map[B, S2](f: Arrow[A, B, S2]): B < (S & S2) =
    val r = root
    // one object: both the new suspension and the appended chain node
    new Suspend[I, O, E, X, B, S & S2] with ArrowChain(prev = cont, last = f):
        override val root = r
        def tag           = root.tag
        def input         = root.input
        def frame         = root.frame
        def cont          = this
```

This takes build-side allocation from wrapper+node (about 48 B/map) to one fused object,
parity with the old kernel's single wrapper.

## Measured variant history (3-fork gates; time us/op, alloc B/op)

Five execution designs were built and gated. The recursive on-stack walk (with and without a
Kyo-input bail) went quadratic on trailingMapsStayLinear because early-suspending walks
re-attach their pending right sides per frame, so every round leaves a deeper tree: the walk
tears continuations down and the suspension re-accumulates them. The full-unroll buffer drive
stayed quadratic for a subtler reason JFR exposed: its unroll expanded previously-minted
linked Step chains node by node and re-minted them on every suspension. The invariant that
restores linearity: a minted chain is never re-expanded or re-minted; pre-linked Steps stay
opaque units executing through their own fused head(v, tail) path.

| row | flatten baseline | recursive walk | full-unroll drive | units drive | adaptive (shipped) |
|---|---|---|---|---|---|
| fusionAfterSuspension | 271.5 / 1,073K | 166.8 / 545K | 167.9 / 569K | 195.1 / 569K | 196.0 / 561K |
| fusionAfterSuspensionRunOnly | 0.829 / 1,288 | 0.588 / 64 | 0.644 / 64 | 0.666 / 64 | 0.291 / 64 |
| trailingMapsStayLinear | 672 / 2.96M | 91,137 / 478M | 351,464 / 1.2G | 746 / 2.96M | 702 / 2.96M |
| foreignCrossingsPayRotation | 377.2 / 1.84M | 412.1 / 1.84M | 401.0 / 1.84M | 403.4 / 1.84M | 400.6 / 1.84M |
| suspensionBaseline | 82.9 / 640K | 80.7 / 640K | 80.2 / 640K | 81.0 / 640K | 81.3 / 640K |
| handleLoopAnswersInPlace | 82.0 / 640K | 81.0 / 640K | 79.2 / 640K | 79.9 / 640K | 80.0 / 640K |
| statefulAnswersPaySuccessor | 111.1 / 1.12M | 111.5 / 1.12M | 109.8 / 1.12M | 110.5 / 1.12M | 110.1 / 1.12M |
| deepRecursionPaysRescuesOnly | 52.4 / 912 | 50.9 / 912 | 65.8 / 912 | 50.4 / 912 | 51.4 / 912 |
| fusionAllocatesNothing | 0.578 / 0 | 0.578 / 0 | 0.574 / 0 | 0.585 / 0 | 0.581 / 0 |

The shipped design (commits 8fa613309a and 772d22ac52) combines the units drive with
adaptive materialization: fusion needs linked objects (the monomorphic call sites live inside
each transform's per-map-site mapLoop), and minting only pays when the same chain executes
repeatedly, so the flattened field doubles as a use detector. First execution drives units
from the region (single-use chains never mint); the DrivenOnce sentinel marks it; the second
execution materializes through step() and every later one runs fused. Run-only lands at old
kernel parity (0.291 vs 0.283) with 64 B/op. Remaining deltas vs the old kernel:
fusionAfterSuspension 2.2x time and 1.37x alloc (build-side wrapper plus chain node per map;
the wrapper-fusion follow-up below), foreignCrossings 1.23x time at alloc parity.

## Expected end state

With 1+2 (and 3 as a follow-up), per map over a suspension the new kernel allocates one
wrapper (fused with the chain node after 3) at build and nothing at answer time unless the
computation suspends mid-chain, matching the old kernel's allocation shape while keeping O(1)
append and the linear trailing-maps behavior. Bench gates: fusionAfterSuspension,
fusionAfterSuspensionRunOnly, trailingMapsStayLinear (asymptotics guard), suspensionBaseline,
foreignCrossingsPayRotation, and the fusion rows (no regression expected: pure fused chains
never reach AndThen).

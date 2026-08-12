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

### Rejected: memoize AndThen.step

Counts above: real shapes are single-use; the cache field would be dead weight plus a benign
data race on every AndThen. Only the artificial stored-reuse bench improves.

### Rejected: pre-linked construction

Appending to a right-linked list is O(n) per map, O(n^2) per chain: this is the old kernel's
trailing-maps quadratic, the thing the AndThen representation exists to fix.

## Expected end state

With B (and C as a follow-up), per map over a suspension the new kernel allocates one fused
wrapper node at build and nothing at answer time unless the computation suspends mid-chain,
matching the old kernel's allocation shape while keeping O(1) append and the linear
trailing-maps behavior. The hypotheses need bench confirmation on: fusionAfterSuspension,
fusionAfterSuspensionRunOnly, trailingMapsStayLinear (asymptotics guard), suspensionBaseline,
foreignCrossingsPayRotation, and the fusion rows (no regression expected: pure chains never
reach AndThen).

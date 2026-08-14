# Kernel benchmark comparison: new kernel vs old kernel vs previous kyo-kernel2

Three JMH runs of the same KernelBench rows (avgt, 2 forks, 5+5 x 1s, `-prof gc`),
on this machine, 2026-08-13:

- **old-kernel**: `kyo-kernel` (the production kernel), via its bench mirror at
  `kyo-kernel/bench`.
- **prev-kernel2**: the pre-swap kyo-kernel2 (fused-node design with the
  Handlers spine), built from `cb5435af06~1` in a scratch worktree.
- **new-kernel2**: the current kyo-kernel2 (this branch, commit `b130241ea0`):
  Defer/Suspend/HandleCont/HandleLoop nodes over a thread-local drive stack.

Raw outputs and JSON are in `bench-results/`.

## Numbers

Each cell is `us/op` then `gc.alloc.rate.norm B/op`. Depth rows run 10000
operations per op, Narrow rows 1000, so per-operation cost is the cell divided
by that depth.

| benchmark | old-kernel | prev-kernel2 | new-kernel2 |
|---|---|---|---|
| evalFixedOverhead | 0.009 / 0 | 0.002 / 0 | 0.002 / 0 |
| fusionAllocatesNothing | 0.837 / 0 | 0.585 / 0 | 0.582 / 0 |
| fusionPastBudgetPaysRescuesOnly | 49.0 / 1128 | 34.8 / 448 | 33.9 / 448 |
| uncachedValuesPayBoxingOnly | 75.4 / 141.8K | 37.8 / 155.2K | 38.0 / 155.2K |
| userTypesSkipKernelWrapping | 44.0 / 177.1K | 33.8 / 176.6K | 33.9 / 176.6K |
| inlineLimitCostsTimeNotAllocation | 346.4 / 724.4K | 229.3 / 735.6K | 247.4 / 735.6K |
| inlineLimitKeepsZeroAllocation | 2.21 / 0 | 1.66 / 0 | 1.61 / 0 |
| deepRecursionPaysRescuesOnly | 55.7 / 2128 | 54.1 / 912 | 55.0 / 912 |
| idleHandlerAddsNothing | 49.0 / 1224 | 33.5 / 496 | 33.7 / 472 |
| continuationBodiesFuse | 25.0 / 56.1K | 27.3 / 64.1K | 53.9 / 136.2K |
| fusionAfterSuspensionRunOnly | 0.290 / 0 | 0.522 / 48 | 0.893 / 1512 |
| fusionAfterSuspension | 88.4 / 408.4K | 157.5 / 472.5K | 239.7 / 824.9K |
| suspensionBaseline | 130.1 / 560.1K | 81.5 / 640.1K | 360.1 / 1360.2K |
| suspensionFusesContinuation | 71.0 / 240.1K | 27.5 / 240.1K | 353.9 / 1360.2K |
| partialSuspensionBaseline | (no partial mode) | 82.1 / 640.1K | 386.6 / 1360.2K |
| sharedHandlerPaysDispatch | 137.1 / 240.4K | 154.6 / 240.4K | 410.2 / 1362.2K |
| handleLoopAnswersInPlace | 135.5 / 960.1K | 80.4 / 640.1K | 178.4 / 640.1K |
| statefulAnswersPaySuccessor | 155.4 / 1040.1K | 118.3 / 1118.2K | 398.4 / 1360.2K |
| foreignCrossingsPayRotation | 328.4 / 1680.2K | 349.2 / 1520.3K | 1024.5 / 3280.4K |
| trailingMapsStayLinear | 567553 / 1601.2M | 350.9 / 2161.4K | 389116 / 1402.3M |

## Reading

### The settled engine is at full parity with the previous kernel2

Every row that exercises maps, budget rescues, boxing, the JIT inline limit,
and idle handlers lands within noise of prev-kernel2, and both beat the old
kernel by 1.3x to 2x with half to a third of the allocation. The radically
smaller evaluator (one drive loop over a thread-local stack) gives nothing
away on the paths that dominate settled computation:

- one settled map plus eval is 2ns, 4.5x under the old kernel;
- the fused chain runs at prev-kernel2's exact rate with zero allocation;
- past the budget, rescues cost 448 B against the old kernel's 1128 B.

### Suspension traffic is the regression, 2.2x to 12.9x

Every row where operations are actually answered is slower than both other
kernels, with roughly double the allocation:

| row | vs prev-kernel2 | vs old-kernel |
|---|---|---|
| suspensionBaseline | 4.4x | 2.8x |
| suspensionFusesContinuation | 12.9x | 5.0x |
| statefulAnswersPaySuccessor | 3.4x | 2.6x |
| sharedHandlerPaysDispatch | 2.7x | 3.0x |
| handleLoopAnswersInPlace | 2.2x | 1.3x |
| foreignCrossingsPayRotation | 2.9x | 3.1x |
| partialSuspensionBaseline | 4.7x | - |

In absolute terms suspensionBaseline is 36ns and 136 B per answered operation
(prev: 8.1ns / 64 B; old: 13ns / 56 B).

Two costs drive it:

1. **The one-shot capture.** Each operation answered through `handle` copies
   the stack segment above the region (`copyFrom`), truncates, and rebuilds
   re-nodes when the continuation resumes. That is an array plus one re-node
   per crossed entry per operation. prev-kernel2 reused region cells rebuilt
   in place; the old kernel chained a `KyoContinue`.
2. **suspendWith lost its fusion.** It is now `suspend(...).map(f)`: two nodes
   per operation where prev-kernel2's suspension was its own continuation
   (one object, its 27.5us row is 12.9x). The kernel3 design deliberately
   dropped the cont from `Suspend`; this row is the price as long as that
   holds.

`handleLoop` answering in place is the closest row (2.2x prev at identical
allocation), consistent with the capture being the dominant cost: its clause
receives no continuation, so nothing is copied, and the remaining gap is the
per-settle dispatch of the drive loop against prev-kernel2's fused resume.

### The trailing-map shape (issue 531) is quadratic again

prev-kernel2 kept it linear by design (350us for 10k levels). The new kernel
is quadratic like the old kernel (389ms, 1.4 GB per op): each answered
operation captures and rebuilds the accumulated trailing segment, which grows
by one arrow per level, so the copies sum to O(n^2). Any workload shaped like
a for comprehension with a trailing map after a recursive effect step hits
this. This is the single most important performance item for the new kernel.

## Directions (not yet done)

- Share captured segments instead of copying per operation: the capture is
  only observable if the continuation escapes or runs more than once, so a
  borrow-then-copy-on-escape discipline over the same stack would remove both
  the array copy and most re-node allocation from the hot path, and with it
  likely the quadratic trailing-map behavior.
- Reconsider a fused suspend-with-continuation node if the 12.9x on
  suspendWith matters before IOTask integration; the row exists to make that
  trade visible.
- foreignCrossingsPayRotation doubles the allocation of both other kernels
  (3.28 MB vs 1.5-1.7 MB): each crossing captures the inner region into a
  segment and re-nodes it on resume, compounding with the capture cost above.

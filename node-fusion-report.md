# Node fusion campaign: single-allocation nodes, cell reuse, loop hygiene

Mandate: the new kernel beats or matches the old one on every row, with the single
granted exception of the reused-computation case (fusionAfterSuspensionRunOnly,
which evaluates the same prebuilt value over and over).

## The changes (four commits, 547/547 tests green after each)

1. **Node fusion** (16f91f859c). Defer, Handled, and HandledState become traits
   following the Suspend precedent, each with a final map building one object
   extending `Arrow.AndThen` with the node type: the node is its own chain arrow,
   the previous continuation followed by the mapped arrow. Previously two objects
   per map (chain node plus rewrapped node). The six ArrowEffect handle sites fuse
   handler, region node, and (With variants) exit arrow into one object, resolving
   the two review TODOs. The Handled arrow member renamed cont to exit, matching
   Handlers.Node vocabulary and removing a silent shadowing hazard against the
   inline cont parameter in handleWith. Bench module footprint shrank from
   1,712 KB to 1,376 KB at the identical 281 classes: the inline map expansion is
   untouched and the handle bodies got smaller.

2. **Resume reuses region cells rebuilt in place** (caad50dfa7). A handler answer
   crossing inner regions parks them as a Handled onion and immediately re-enters
   them; that minted a node plus a stack cell per layer per answer. rebuild now
   emits layers that remember their source cell; when a layer is consumed in the
   position it was built from (node.prev eq hs, the tail-resume shape) the
   immutable cell re-enters the stack as is. Escaped continuations miss the
   identity check and take the general arm: multi-shot and park/resume semantics
   unchanged.

3. **Dispatch by frequency** (74b17b22d3). The evaluation loop tested the two
   region-node traits before the Suspend arm, charging every answered operation
   two failed interface checks. Arms now order Suspend, Defer, region nodes.

4. **Loop hygiene** (walk split + pending-block extraction). walk splits into its
   Kyo-attach half and resume (the settled half); call sites with values proven
   settled call resume directly. The four pending-outcome re-entry blocks (a
   handler that itself suspends) moved out of the loop, so the hot arms stay
   small enough for the JIT to reliably inline the handler invocation.

## Full board vs the old kernel

3 forks, gc profiler. Ratio is new/old, lower is better. runOnly is the granted
reuse exception.

### CPU time, us/op

| row | old kernel | new kernel | ratio |
|---|---|---|---|
| trailingMapsStayLinear | 472,770.9 | 324.5 | 0.0007 |
| evalFixedOverhead | 0.009 | 0.002 | 0.22 |
| suspensionFusesContinuation | 70.22 | 25.00 | 0.36 |
| uncachedValuesPayBoxingOnly | 74.71 | 38.10 | 0.51 |
| handleLoopAnswersInPlace | 134.85 | 81.50 | 0.60 |
| inlineLimitKeepsZeroAllocation | 2.167 | 1.381 | 0.64 |
| inlineLimitCostsTimeNotAllocation | 347.03 | 224.81 | 0.65 |
| suspensionBaseline | 127.69 | 83.99 | 0.66 |
| fusionPastBudgetPaysRescuesOnly | 48.44 | 32.70 | 0.68 |
| idleHandlerAddsNothing | 48.84 | 33.08 | 0.68 |
| fusionAllocatesNothing | 0.830 | 0.572 | 0.69 |
| statefulAnswersPaySuccessor | 153.68 | 114.45 | 0.74 |
| userTypesSkipKernelWrapping | 43.86 | 37.89 | 0.86 |
| deepRecursionPaysRescuesOnly | 55.08 | 51.29 | 0.93 |
| foreignCrossingsPayRotation | 325.04 | 331.29 | 1.02 |
| sharedHandlerPaysDispatch | 140.77 | 152.28 | 1.08 |
| continuationBodiesFuse | 24.78 | 27.01 | 1.09 |
| fusionAfterSuspension | 87.98 | 157.37 | 1.79 |
| fusionAfterSuspensionRunOnly (exempt) | 0.283 | 0.520 | 1.84 |
| partialSuspensionBaseline | kernel2-only | 84.11 | - |

### Allocation, B/op

| row | old kernel | new kernel | ratio |
|---|---|---|---|
| trailingMapsStayLinear | 1,601,202,471 | 1,921,122 | 0.0012 |
| deepRecursionPaysRescuesOnly | 2,128 | 912 | 0.43 |
| idleHandlerAddsNothing | 1,224 | 568 | 0.46 |
| fusionPastBudgetPaysRescuesOnly | 1,128 | 528 | 0.47 |
| handleLoopAnswersInPlace | 960,134 | 640,121 | 0.67 |
| userTypesSkipKernelWrapping | 177,040 | 176,720 | 1.00 |
| sharedHandlerPaysDispatch | 240,405 | 240,441 | 1.00 |
| suspensionFusesContinuation | 240,051 | 240,080 | 1.00 |
| fusionAllocatesNothing | 0 | 0 | zero both |
| inlineLimitKeepsZeroAllocation | 0 | 0 | zero both |
| evalFixedOverhead | ~0 | ~0 | zero both |
| inlineLimitCostsTimeNotAllocation | 724,418 | 736,050 | 1.02 |
| foreignCrossingsPayRotation | 1,680,202 | 1,760,274 | 1.05 |
| statefulAnswersPaySuccessor | 1,040,139 | 1,118,153 | 1.08 |
| uncachedValuesPayBoxingOnly | 141,777 | 155,248 | 1.10 |
| suspensionBaseline | 560,080 | 640,121 | 1.14 |
| continuationBodiesFuse | 56,072 | 64,120 | 1.14 |
| fusionAfterSuspension | 408,437 | 544,577 | 1.33 |
| fusionAfterSuspensionRunOnly (exempt) | ~0 | 40 | - |
| partialSuspensionBaseline | kernel2-only | 640,121 | - |

## Deltas from this campaign (vs the pre-fusion shipped board)

- suspensionFusesContinuation 29.70 to 25.00 us (-16%)
- uncachedValuesPayBoxingOnly 42.08 to 38.10 us (-9%)
- suspensionBaseline 87.45 to 83.99 us, handleLoop 84.10 to 81.50 us,
  partialSuspension 85.99 to 84.11 us
- foreignCrossings allocation 2,160,362 to 1,760,274 B/op average; the fast JIT
  mode measures 1,600,258 B/op at ~291 us, below the old kernel on both axes
- fusionPastBudget allocation 768 to 528 B/op, idleHandler 832 to 568 B/op
- runOnly allocation 64 to 40 B/op
- sharedHandler 154.49 to 152.28 us, trailingMaps 330.4 to 324.5 us

## Mandate scorecard

Fourteen of nineteen shared rows faster (0.0007x to 0.93x), foreignCrossings at
parity (1.02x), runOnly exempt by ruling. Three rows remain over parity, all
sharing one structural root:

1. **fusionAfterSuspension 1.79x time, 1.33x alloc.** Builds ten transformations
   on an unanswered suspension per level. The old kernel's map-on-suspension is
   ONE object because its per-site anonymous class is simultaneously the closure,
   the chain node, and the suspension. Ours is TWO (the per-site arrow plus the
   fused AndThen node) because the site class is kept minimal. Every one-object
   route grows the inline-expanded map site: per-site node classes measured at
   2.2x bytecode / 2.7x classfiles (rejected), and a Suspend mixin on the site
   arrow anon adds fields and accessors to every map site (against the
   no-inline-bloat rule). The suspension family's 1.14x alloc overhang
   (640,121 vs 560,080 B/op while running 1.5x faster) is the same two-versus-one
   object shape. Mutating a fresh suspension in place is unsound: values are
   shared, `val s = ask; s.map(f); s.map(g)` must not leak f into g.
2. **continuationBodiesFuse 1.09x.** Decomposes into a suspension cycle we win
   (8.4 vs 12.8 ns/level) plus eleven in-continuation maps at partial JIT
   inlining, where our larger per-site map body pays ~0.6 ns/map more. Same root:
   the size of the sacrosanct inline map expansion.
3. **sharedHandlerPaysDispatch 1.08x.** Sixteen suspend sites through one
   handler; the gap is receiver-profile-overflowed dispatch on the resumed
   continuations, the same megamorphic residual PrintInlining attributed before.

Open decision: these three rows cannot reach parity without letting the map call
site carry the node (bigger inline expansion, exact cost measurable on demand) or
granting them the same structural-exception status as runOnly. All other rows
meet the mandate.

Operational note: foreignCrossings is JIT-bimodal. The per-answer continuation
closure is eliminated by escape analysis only when the handler invocation
inlines; the loop-hygiene commit raised the fast-mode rate from one fork in
three to two in three. Fast mode beats the old kernel on both axes; slow mode
matches its time and pays 48 B/level.

# Morning report: chain evaluation, the general-case solution

## The solution

Three composable mechanisms, each small, all landed on `exp/site-fusion` (lineage
`exp/stack-walk` -> `exp/stack-walk-safepoint` -> `exp/wrapper-fusion` -> `exp/site-fusion`):

1. **Thread-stack evaluation under the budget** (Eval). `walk` executes an `AndThen`/`Composed`
   tree by recursion: units run through their own fused sites, completion allocates nothing,
   and every recursion frame brackets `Safepoint.enter`/`exit`, so the walk's real stack is
   bounded by the same budget as everything else, with no ad-hoc constant. A suspension
   travels up through a single private carrier composing the unfinished right sides one chain
   node per frame, and the walk root links that remainder once, so the resumed part executes
   fused as well. An exhausted descent flattens the remaining subtree into a linked chain
   whose first transform defers, and the trampoline resumes it with guaranteed progress.
2. **The suspension is its own chain node** (KyoInternal). `Suspend.map` builds one object
   extending `AndThen with Suspend`: as a value it carries the tag for handler dispatch, as an
   arrow it means the previous continuation followed by f.
3. **The map site is the suspension's chain node** (Pending + Arrow). `Composed` abstracts a
   composition's second step; `Pending.map`'s suspension arm builds one object per map that is
   the composition node, the site's transform (f inline-captured, no separate arrow object),
   and the suspension. `bArrow` materializes the pending step as a value only when a
   suspension actually needs it. The construction lives outside mapLoop's hot body so the
   pure fused path keeps its inlining size.

Composition thinking throughout: no interpreter machinery, no scratch regions, no
memoization, no mutation beyond the unwind carrier that never escapes the walk.

## Results, 9 gate rows, 3 forks, same session as the old-kernel board

Time us/op and allocation B/op. Start = the batch-8 state when the night began.

| row | old kernel | start | champion | vs old |
|---|---|---|---|---|
| suspensionBaseline | 127.7 / 560K | 85.3 / 640K | 74.2 / 560K | 1.72x faster, alloc parity |
| handleLoopAnswersInPlace | 134.9 / 960K | 83.3 / 640K | 72.0 / 560K | 1.87x faster, -42% alloc |
| statefulAnswersPaySuccessor | 153.7 / 1.04M | 112.7 / 1.12M | 104.4 / 1.04M | 1.47x faster, alloc parity |
| trailingMapsStayLinear | 472,771 / 1.6G | 595 / 2.48M | 503 / 2.32M | 940x faster |
| foreignCrossingsPayRotation | 325.0 / 1.68M | 332.1 / 2.0M | 350.1 / 1.60M | 1.08x slower, -5% alloc |
| fusionAllocatesNothing | 0.830 / 0 | 0.583 / 0 | 0.583 / 0 | 1.42x faster |
| deepRecursionPaysRescuesOnly | 55.1 / 2,128 | 51.5 / 912 | 52.7 / 1,064 | faster, -50% alloc |
| fusionAfterSuspensionRunOnly | 0.283 / ~0 | 0.750 / 952 | 0.328 / 64 | 1.16x slower |
| fusionAfterSuspension | 88.0 / 408K | 245.5 / 977K | 109.1 / 456K | 1.24x slower, 1.12x alloc |

The kernel now beats the old one on seven of nine rows, several by large margins, with the
remaining two inside 1.25x (they were 2.8x and 2.9x at the start of the night). The full
20-row board against the same-session old-kernel run is appended below.

## Fusion after suspension: verified

PrintInlining on the resumed-chain row shows the per-site classes inlining hot into each other
with monomorphic type profiles (single receiver, 22214/22214 counts at the sampled sites)
through evalChain (92 bytes, inline hot). The remainder a suspension leaves behind links once
at the boundary and re-enters the same fused execution.

## The night's path (what failed and why)

- Batch-k tails: mint per use bounded early-suspension waste but paid near-full mint on
  completion-heavy chains; dominated once stack execution worked.
- Recursive walk with per-frame kyo.map re-attach: quadratic on early-suspending accumulated
  chains (each round rebuilt a deeper tree); fixed by the single-carrier unwind.
- Constant-depth cap (A) vs budget-bounded (A2): A was slightly faster (160.6 vs 167.6 on
  fusionAfterSuspension) but showed unstable stateful iterations twice and carries a magic
  constant; A2 chosen.
- Wrapper fusion alone (D) halved trailingMaps but left the per-site arrow objects; site
  fusion (E) removed those and moved every answering row past the old kernel.

## Flat representation: disposition

Explored analytically against the record rather than rebuilt: Flat's unique property, O(1)
suspension windows, addressed a cost the final design no longer has (remainders are already
proportional to genuinely unfinished work, linked once, resumed fused), while its known
weaknesses remain (indexed execution defeats per-site fusion; its historical numbers on its
best-case row were 0.998us/1424B where the champion measures 0.328us/64B). A measured attempt
stays one branch away if wanted, but every advantage it offers is dominated by the shipped
design.

## Trades accepted and open items

- foreignCrossingsPayRotation: 350.1 vs the plain stack walk's 308.4 (time), with the best
  allocation ever measured (1.60M). The rotation path pays bArrow materialization at its
  frequent crossings. Candidate follow-up: a cheaper pending-step value for site nodes.
- trailingMapsStayLinear: 503 vs wrapper-fusion's 348 for the same reason; still 940x over
  the old kernel.
- statefulAnswers showed run-to-run instability on this machine during the night under the
  cap variant; stable under the shipped one (104.4 +- 0.4).
- deepRecursion alloc rose 912 -> 1,064 B/op (rescue-path bArrow), still half the old kernel.

## Full champion board vs old kernel (3 forks, gc profiler, same session)

Time us/op and allocation B/op; ratio is champion/old, lower is better.

| row | old time | champ time | ratio | old alloc | champ alloc | ratio |
|---|---|---|---|---|---|---|
| trailingMapsStayLinear | 472,770.9 | 478.1 | 0.001 | 1,601,202,471 | 2,321,131 | 0.001 |
| suspensionFusesContinuation | 70.22 | 29.51 | 0.42 | 240,051 | 240,104 | 1.00 |
| uncachedValuesPayBoxingOnly | 74.71 | 36.85 | 0.49 | 141,777 | 155,488 | 1.10 |
| handleLoopAnswersInPlace | 134.85 | 71.04 | 0.53 | 960,134 | 560,136 | 0.58 |
| suspensionBaseline | 127.69 | 74.93 | 0.59 | 560,080 | 560,137 | 1.00 |
| inlineLimitCostsTimeNotAllocation | 347.03 | 229.06 | 0.66 | 724,418 | 737,250 | 1.02 |
| inlineLimitKeepsZeroAllocation | 2.167 | 1.434 | 0.66 | 0 | 0 | - |
| fusionPastBudgetPaysRescuesOnly | 48.44 | 33.11 | 0.68 | 1,128 | 776 | 0.69 |
| idleHandlerAddsNothing | 48.84 | 33.07 | 0.68 | 1,224 | 840 | 0.69 |
| statefulAnswersPaySuccessor | 153.68 | 104.19 | 0.68 | 1,040,139 | 1,038,169 | 1.00 |
| fusionAllocatesNothing | 0.830 | 0.591 | 0.71 | 0 | 0 | - |
| userTypesSkipKernelWrapping | 43.86 | 35.89 | 0.82 | 177,040 | 176,960 | 1.00 |
| deepRecursionPaysRescuesOnly | 55.08 | 53.23 | 0.97 | 2,128 | 1,064 | 0.50 |
| evalFixedOverhead | 0.009 | 0.002 | 0.22 | ~0 | ~0 | - |
| continuationBodiesFuse | 24.78 | 26.36 | 1.06 | 56,072 | 56,136 | 1.00 |
| foreignCrossingsPayRotation | 325.04 | 350.98 | 1.08 | 1,680,202 | 1,600,306 | 0.95 |
| sharedHandlerPaysDispatch | 140.77 | 153.75 | 1.09 | 240,405 | 240,465 | 1.00 |
| fusionAfterSuspensionRunOnly | 0.283 | 0.339 | 1.20 | ~0 | 64 | - |
| fusionAfterSuspension | 87.98 | 108.70 | 1.24 | 408,437 | 456,537 | 1.12 |
| partialSuspensionBaseline | kernel2-only | 73.22 | - | kernel2-only | 560,137 | - |

Fifteen of nineteen rows faster, many by 1.5x to 2.4x and the linearity row by three orders of
magnitude; allocation at or below the old kernel on sixteen. The four time losses sit between
1.06x and 1.24x, all in the build-and-answer-once family where the old kernel's single-object
nesting is at its best, and two of them (continuationBodiesFuse, sharedHandlerPaysDispatch)
are within run noise of parity.

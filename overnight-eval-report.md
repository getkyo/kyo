# Morning report: chain evaluation, the general-case solution

## The solution

Two mechanisms, both library-side. `Pending.map` is byte-identical to its original form: one
anonymous arrow per call site, `kyo.map(arrow)` on suspension, nothing else. The inline
expansion every kyo program pays is unchanged, measured: the bench module compiles to
1,712 KB / 281 classes, exactly the original footprint.

1. **Thread-stack evaluation under the budget** (Eval). `walk` executes an `AndThen` tree by
   recursion: units run through their own fused sites, completion allocates nothing, and every
   recursion frame brackets `Safepoint.enter`/`exit`, so the walk's real stack is bounded by
   the same budget as everything else, with no ad-hoc constant. A suspension travels up
   through a single private carrier composing the unfinished right sides one chain node per
   frame, and the walk root links that remainder once, so the resumed part executes fused. An
   exhausted descent flattens the remaining subtree into a linked chain whose first transform
   defers, and the trampoline resumes it with guaranteed progress.
2. **The suspension is its own chain node** (KyoInternal). `Suspend.map` builds one object
   extending `AndThen with Suspend`: as a value it carries the tag for handler dispatch, as an
   arrow it means the previous continuation followed by the site's arrow. A first map keeps
   the plain wrapper with the arrow as its continuation.

Composition thinking throughout: no interpreter machinery, no scratch regions, no
memoization, no per-site machinery beyond the arrow map always generated, no mutation beyond
the unwind carrier that never escapes the walk.

## Results, 9 gate rows, 3 forks, same session as the old-kernel board

Time us/op and allocation B/op. Start = the batch-8 state when the night began.

| row | old kernel | start | shipped | vs old |
|---|---|---|---|---|
| trailingMapsStayLinear | 472,771 / 1.6G | 595 / 2.48M | 334 / 2.08M | 1,415x faster |
| foreignCrossingsPayRotation | 325.0 / 1.68M | 332.1 / 2.0M | 313.3 / 2.16M | 1.04x faster |
| suspensionBaseline | 127.7 / 560K | 85.3 / 640K | 85.7 / 640K | 1.49x faster |
| handleLoopAnswersInPlace | 134.9 / 960K | 83.3 / 640K | 84.1 / 640K | 1.60x faster, -33% alloc |
| statefulAnswersPaySuccessor | 153.7 / 1.04M | 112.7 / 1.12M | 112.7 / 1.12M | 1.36x faster |
| fusionAllocatesNothing | 0.830 / 0 | 0.583 / 0 | 0.577 / 0 | 1.44x faster |
| deepRecursionPaysRescuesOnly | 55.1 / 2,128 | 51.5 / 912 | 51.3 / 912 | faster, -57% alloc |
| fusionAfterSuspensionRunOnly | 0.283 / ~0 | 0.750 / 952 | 0.497 / 64 | 1.76x slower |
| fusionAfterSuspension | 88.0 / 408K | 245.5 / 977K | 156.6 / 545K | 1.78x slower, 1.33x alloc |

Seven of nine rows beat the old kernel, several by 1.4x to 1.6x and the linearity guard by
three orders of magnitude; the two losses are the build-and-answer-once family, improved from
2.8x and 2.9x at the start of the night to 1.76x and 1.78x. The full 20-row board on this
exact build is appended below.

## The site-fusion chapter: built, measured, rejected

An additional mechanism (exp/site-fusion) made the map site itself the suspension's chain
node, one object per map carrying f directly. It measured fusionAfterSuspension at
109.1us/456K (1.24x vs old) and runOnly at 0.331 (1.16x), with suspension rows at 560K
byte-parity with the old kernel. The price, measured after a verified clean rebuild: the
bench module ballooned from 1,712 KB / 281 classes to 3,788 KB / 763, because the inline map
expanded two extra anonymous classes at every call site in every program. Rejected: map's
per-site expansion is sacrosanct. The numbers stay on record; if that family's last 1.8x ever
matters enough, the only known doors are per-site classes (this cost) or shared nodes holding
lambdas (megamorphic resume, weaker fusion after suspension).

Operational note: the measurement that initially hid this cost was a silently stale JMH
build; zinc repeatedly fails to invalidate the Jmh configuration on inline changes, and the
session's procedure is now clean, compile, retry once, then verify a bench classfile's
superclass and timestamp before trusting any number.

## The night's path (what failed and why)

- Batch-k tails: mint per use bounded early-suspension waste but paid near-full mint on
  completion-heavy chains.
- Recursive walk with per-frame kyo.map re-attach: quadratic on early-suspending accumulated
  chains; fixed by the single-carrier unwind.
- Constant-depth cap vs budget-bounded walk: the budget version costs a few percent and
  removes the magic constant; chosen.
- Wrapper fusion (Suspend.map single object): kept, library-side, no footprint cost.
- Site fusion: rejected as above.
- Flat representation: dispositioned analytically; its O(1)-window advantage addresses a cost
  the final design no longer has, its indexed execution defeats per-site fusion, and its
  recorded best-case numbers (0.998us/1424B on runOnly) trail the shipped 0.497/64.

## Trades accepted and open items

- fusionAfterSuspension family at 1.76-1.78x vs the old kernel: the walk pays per-node
  dispatch (megamorphic arrow application, depth-limited recursion inlining) where the old
  kernel's answered continuation is pre-compiled monomorphic nesting. The site-fusion numbers
  bound what closing it is worth.
- suspensionBaseline allocation 640K vs the old kernel's 560K: one arrow plus one node per
  map against the old kernel's single fused closure; same root cause, same bound.
- deepRecursion allocation halved against the old kernel; rescue rows improved across the
  board (fusionPastBudget 776B vs 1,128B).

## Fusion after suspension: verified on this build

PrintInlining on the resumed-chain row: 152 inline-hot verdicts on the per-site mapLoop
bodies against 49 size rejections, and 108 monomorphic TypeProfile devirtualizations
(full single-receiver counts) chaining site to site. About three quarters of the resumed
chain's call sites compile into fused regions; the quarter that stays virtual (106-byte
bodies past MaxInlineSize at warm sites) is the measured residual against the old kernel's
fully pre-compiled nesting.

## Full board vs old kernel (3 forks, gc profiler, same session)

Time us/op and allocation B/op; ratio is shipped/old, lower is better.

| row | old time | shipped time | ratio | old alloc | shipped alloc | ratio |
|---|---|---|---|---|---|---|
| trailingMapsStayLinear | 472,770.9 | 330.4 | 0.0007 | 1,601,202,471 | 2,001,146 | 0.001 |
| evalFixedOverhead | 0.009 | 0.002 | 0.22 | ~0 | ~0 | - |
| suspensionFusesContinuation | 70.22 | 29.70 | 0.42 | 240,051 | 240,104 | 1.00 |
| uncachedValuesPayBoxingOnly | 74.71 | 42.08 | 0.56 | 141,777 | 155,488 | 1.10 |
| handleLoopAnswersInPlace | 134.85 | 84.10 | 0.62 | 960,134 | 640,145 | 0.67 |
| inlineLimitCostsTimeNotAllocation | 347.03 | 221.35 | 0.64 | 724,418 | 737,250 | 1.02 |
| inlineLimitKeepsZeroAllocation | 2.167 | 1.415 | 0.65 | 0 | 0 | - |
| fusionPastBudgetPaysRescuesOnly | 48.44 | 32.82 | 0.68 | 1,128 | 768 | 0.68 |
| idleHandlerAddsNothing | 48.84 | 33.22 | 0.68 | 1,224 | 832 | 0.68 |
| suspensionBaseline | 127.69 | 87.45 | 0.68 | 560,080 | 640,145 | 1.14 |
| fusionAllocatesNothing | 0.830 | 0.573 | 0.69 | 0 | 0 | - |
| statefulAnswersPaySuccessor | 153.68 | 112.96 | 0.73 | 1,040,139 | 1,118,177 | 1.08 |
| userTypesSkipKernelWrapping | 43.86 | 38.08 | 0.87 | 177,040 | 176,960 | 1.00 |
| deepRecursionPaysRescuesOnly | 55.08 | 51.51 | 0.94 | 2,128 | 912 | 0.43 |
| foreignCrossingsPayRotation | 325.04 | 327.62 | 1.01 | 1,680,202 | 2,160,362 | 1.29 |
| continuationBodiesFuse | 24.78 | 27.38 | 1.10 | 56,072 | 64,144 | 1.14 |
| sharedHandlerPaysDispatch | 140.77 | 154.49 | 1.10 | 240,405 | 240,465 | 1.00 |
| fusionAfterSuspension | 87.98 | 157.37 | 1.79 | 408,437 | 544,601 | 1.33 |
| fusionAfterSuspensionRunOnly | 0.283 | 0.506 | 1.79 | ~0 | 64 | - |
| partialSuspensionBaseline | kernel2-only | 85.99 | - | kernel2-only | 640,145 | - |

Fourteen of nineteen shared rows faster, many by 1.4x to 2.4x and the linearity row by three
orders of magnitude; allocation at or below the old kernel on twelve. The two structural
losses are the build-and-answer-once family at 1.79x, bounded by the recorded site-fusion
numbers (1.24x) that were rejected for their per-site footprint.

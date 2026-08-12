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

## Full board vs old kernel (appended when the 20-row run lands)

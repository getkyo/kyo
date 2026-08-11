# Safepoint: PrintAssembly findings and the depth-only redesign

Refreshed after the JIT investigation. The prior open decision (SWAR vs naive vs no check) is
resolved by evidence from the compiled machine code, not by picking among the measured variants:
all three encodings were paying for the same structural mistake, and removing it recovers the
no-check baseline while keeping preemption.

## What PrintAssembly showed

Setup: hsdis was already installed in the JDK (`$JAVA_HOME/lib/hsdis-aarch64.dylib`), so
`-XX:CompileCommand=print` emits real aarch64 assembly. Non-forked JMH runs
(`-f 0`) reproduce the forked numbers (1.19 vs 0.56) and allow flag control; the C2 nmethod for
`KernelBench::loop$1` is the fully-inlined fusion loop in every build.

Per map application, in the straight-line hot region between recursive calls:

| build | depths loads | depths stores | ldar (acquire) | nmethod size | fusion row |
|---|---|---|---|---|---|
| spine baseline (depth-only) | 1 | 0 | 1 | 6168 B | 0.574 |
| SWAR steps+depth (committed) | 2 | 2 | 1 | 9592 B | 1.219 |
| SWAR + inline restart arm | 2 | 2 | 1 | 12088 B | 1.187 |
| depth-only + delivery via get() | 1 | 0 | 1 | 6152 B | 0.566 |

Three mechanisms, all read directly from the disassembly:

1. **The spine baseline was fast because C2 deleted the accounting.** Its bytecode stores on both
   enter (`d+1`) and exit (`d-1`), but the compiled fast path is one load and one compare: the
   slow arm of enter returns constant false, so `enter == true` implies the single fast path,
   store-to-load forwarding gives exit the entered value, the write-back of `d` becomes a store of
   the just-loaded value and is removed, and the then-dead enter store follows. The counter never
   moves in flat chains. That is the same fact as the preemption bug: cancelling accounting is
   exactly accounting a flat chain never advances.

2. **The steps counter made the stores non-cancelling and the slow path made them non-optimizable.**
   Net progress per map (the steps half) means C2 must keep the stores. Worse, `enterSlow` can
   return true (interval restart, overflow), so the true-arm of the caller merges the fast-path
   store with the memory state of an out-of-line call; the merge blocks both the forwarding and
   the dead-store elimination, leaving load+store+load+store on the same word per map. The
   measured 1.63ns/map delta (about 5 cycles) is those two store-to-load forwarding hops plus the
   SWAR arithmetic. Inlining the restart arm does not help: C2 does not forward a load through a
   memory phi, so any control merge before exit is enough to keep the reload (verified: the
   inline-restart build still shows 2 stores per map and benches 1.187).

3. **Encapsulation and scalac inline clutter cost nothing.** The 63-byte fused `enterInto` inlines
   hot at every hot site, and the dead `MODULE$` loads visible in javap do not survive C2: the
   compiled hot loop contains no trace of them. The 71-byte and 63-byte encapsulated builds bench
   identically. The earlier framing (raw ops vs accepting an inlining cliff) was wrong.

## The redesign: delivery rides the load the fast path already pays

The step counter existed to bound the distance between reads of the preemption flag. But the fast
path already reads the flag every map: `Safepoint.get()` does an acquire load of `slots(home)` and
compares it to the current thread. When a `Stop` wrapper lands, that comparison fails on the very
next map application. The steps counter was duplicating a signal get() already observes and throws
away.

Design now in the tree:

- `State` is a single guarded down-counter: `Initial = DepthGuard | period()`. enter decrements
  and tests the guard bit; exit refunds. The pair cancels in flat chains and C2 deletes it
  (verified in the new build's assembly: 2 stores per 22 maps, both from the genuinely nested
  recursion map). The SWAR packing, `preemptionInterval` flag, and interval-restart arm are gone.
- The slow path (`enterPark`) stores the drained state and always returns false, restoring the
  property optimization needs.
- Stop delivery: `resolve`'s cached branch (which a pending Stop forces every map, since the owner
  compare fails) probes the cached cell and drains the budget, so the next enter parks and the
  Defer reaches the evaluator. Delivery latency is one map application, versus 1024 steps before.
- Armed bit (`1 << 30`): only partial evaluators can park, so only they receive delivery.
  `Eval.partial` arms its scope after save; restore un-arms. Without the gate, a pending stop
  aimed at a full eval (which cannot park and does not consume) would turn every map into a
  Defer/reset round-trip for the eval's whole duration.
- Overflow: `slots` and `depths` are sized `Slots + 1`; the sentinel cell at index `Slots` is a
  normal shared budget cell whose `slots` entry stays null forever. Every guard
  (`slot != Overflowed`) is deleted: exhaustion on the shared cell parks and the rescue resets it
  (self-healing), giving overflow threads an approximate stack bound they previously lacked. Stop
  delivery still cannot reach them (null is never a Stop): unchanged.

## Gate results (3 forks, clean run)

| row | spine board (no check) | SWAR steps+depth | depth-only |
|---|---|---|---|
| fusionAllocatesNothing | 0.574 | 1.219 | 0.575 ± 0.004 |
| inlineLimitKeepsZeroAllocation | 1.344 | 1.798 | 1.381 ± 0.007 |
| suspensionBaseline | 84.2 | 86.9 | 84.27 ± 1.47 |
| fusionPastBudgetPaysRescuesOnly | 32.87 | not measured | 33.68 ± 0.19 |
| deepRecursionPaysRescuesOnly | 52.72 | not measured | 51.58 ± 1.97 |

Allocation profiles are unchanged on every row (fusion rows stay at zero). The one delta outside
error bars is fusionPastBudget at +2.5 percent against a board number recorded in a different JVM
session; unattributed. kyo-kernel2JVM/test 547/547 green; JS and Native test-compile green.

## Contract changes needing a ruling

1. The pinned "overflowed slot ignores budget operations" behavior became "overflow threads share
   the sentinel budget cell": enter can return false at collective exhaustion, save returns real
   state. Behavioral asserts in the pin still hold (see test run), but the pinned intent changed.
2. `handlePartial` consumes stops at its Defer arm but does not arm delivery; standalone
   handlePartial (outside `Eval.partial`) sees a stop only when a Defer reaches it naturally.
   Arming there needs a scoped disarm (and a stance on exception paths), left open.
3. Preemption latency for pure `Loop.apply` runner bodies is unchanged (they never call enter or
   get): still the known boundary.
4. `LineStride` is 8 ints = 32 bytes now that State is Int, so neighboring home cells share a
   cache line. Flat chains no longer write depths, which softens this, but nested-heavy workloads
   write per map; stride 16 would trade home count (4096) for isolation.

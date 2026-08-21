# kernel2 performance: compile time, runtime, allocation

Measured at `340adb262a`, on this machine, with nothing else running but the build. Every number below comes
from a run in this session; nothing is carried over from an earlier one.

---

## Method

**Compile time.** `kyo-compile-bench`: an in-process dotc compiles one fixture file per invocation against one
kernel's classes directory, with the rest of the classpath held identical, so only the kernel varies. 13
fixtures, each isolating one compile-cost driver. JMH average time, 8 warmup and 5 measured iterations, one
fork per (fixture, kernel). A compile that emits diagnostics fails the run, so a broken fixture cannot
masquerade as a fast one. `HandleSites` is the one fixture with a kernel2-specific override, since the two
kernels spell a region differently; every other fixture is byte-identical text compiled twice.

**Runtime and allocation.** `KernelBench` on kernel2 and its mirror compiled against the old kernel, 20 rows
shared by both boards. JMH average time in µs/op, 5 warmup and 5 measured iterations, 2 forks, with `-prof gc`
so `gc.alloc.rate.norm` gives exact bytes per operation rather than an inference from wall clock. Both boards
ran back to back in one sbt session.

**Reading the ratios.** 🟢 is kernel2 faster by more than 5%, ⚪ is within 5% either way, 🔴 is kernel2 slower
by more than 5%. A ratio is only worth acting on when it clears the row's own error bars, which are quoted.

---

## 1. Compile time, 13 fixtures

kernel2 is faster on 12 of 13, and the one red row is inside its own noise.

| fixture | old kernel | kernel2 | |
|---|---:|---:|---|
| **HandleSites** | 642.9 ms | **320.5 ms** | 🟢 0.50x |
| **SuspendSites** | 408.8 | **241.6** | 🟢 0.59x |
| ForComprehensions | 688.7 | 501.9 | 🟢 0.73x |
| **MapChainDeep100** | 15941.5 | **11676.8** | 🟢 0.73x |
| TagDerivation | 146.0 | 111.0 | 🟢 0.76x |
| ForCompDeep25 | 1424.2 | 1100.2 | 🟢 0.77x |
| ForCompShallow | 272.3 | 210.4 | 🟢 0.77x |
| NestedMaps | 669.9 | 537.4 | 🟢 0.80x |
| MapChainWide100 | 559.3 | 457.0 | 🟢 0.82x |
| FlatMapChains | 437.7 | 365.7 | 🟢 0.84x |
| MapChain10 | 175.9 | 150.3 | 🟢 0.85x |
| Baseline | 123.0 | 122.4 | ⚪ 1.00x |
| EffectRowGenerics | 232.9 | 246.9 | 🔴 1.06x |

**Median ratio 0.77x. Total across the board 0.74x**, though the total is dominated by `MapChainDeep100`,
which alone is 12 of the 16 seconds, so the median is the more honest summary.

Three things worth reading out of this table:

- **`HandleSites` at 0.50x** is the fixture that expands a region at 26 sites, so it is the one that responds
  to the size of what `handleCont` emits. It was 1.49x (slower) before `Eval.apply` stopped being inline,
  about 0.63x after, and 0.50x now. Part of the remaining move is tonight's change to how a region matches its
  body, which took `handleCont`'s expansion from 44 to 37 bytes and is pinned by
  `ArrowEffectBytecodeTest`.
- **`SuspendSites` at 0.59x** is the same story for suspensions: kernel2 fuses a suspension and its
  continuation into one node, so each site emits one anonymous class rather than a node plus a continuation.
- **`EffectRowGenerics` is the only red row, and it is noise**: 246.9 ± 69.8 against 232.9 ± 4.8. The error bar
  on the kernel2 side is 28% of its own score, so the interval covers the old kernel's number comfortably.
  Worth one confirming run at higher fork count if it ever matters; it contains no kernel dispatch, only row
  generics, so there is no mechanism for a real regression there.

---

## 2. Runtime and allocation, 19 shared rows

Sorted worst to best for kernel2. Both boards ran back to back in one session, `-f 2 -wi 5 -i 5 -prof gc`.

| | row | old µs | kernel2 µs | | old B/op | kernel2 B/op | |
|---|---|---:|---:|---|---:|---:|---|
| 🔴 | `fusionAfterSuspensionRunOnly` | 0.261 | 2.515 | 9.63x | 0 | 4792 | — |
| 🔴 | `fusionAfterSuspension` | 82.6 | 565.7 | 6.85x | 408437 | 1385460 | 3.39x |
| 🔴 | `statefulAnswersPaySuccessor` | 144.2 | 580.9 | 4.03x | 1040140 | 798124 | 🟢 0.77x |
| 🔴 | `foreignCrossingsPayRotation` | 310.2 | 881.4 | 2.84x | 1680202 | 1760310 | 1.05x |
| 🔴 | `suspensionBaseline` | 122.0 | 183.4 | 1.50x | 560081 | 640137 | 1.14x |
| 🔴 | `sharedHandlerPaysDispatch` | 128.3 | 190.7 | 1.49x | 240407 | 240457 | ⚪ 1.00x |
| 🔴 | `continuationBodiesFuse` | 23.4 | 33.1 | 1.41x | 56072 | 64136 | 1.14x |
| 🔴 | `suspensionFusesContinuation` | 68.0 | 96.1 | 1.41x | 240050 | 240097 | ⚪ 1.00x |
| 🔴 | `handleLoopAnswersInPlace` | 128.3 | 163.8 | 1.28x | 960136 | 640137 | 🟢 0.67x |
| 🔴 | `evalFixedOverhead` | 0.009 | 0.011 | 1.26x | 0 | 0 | ⚪ |
| 🔴 | `userTypesSkipKernelWrapping` | 41.4 | 47.0 | 1.14x | 177056 | 176848 | ⚪ 1.00x |
| ⚪ | `deepRecursionPaysRescuesOnly` | 52.6 | 51.2 | 0.97x | 2128 | 912 | 🟢 0.43x |
| ⚪ | `fusionPastBudgetPaysRescuesOnly` | 45.9 | 44.0 | 0.96x | 1128 | 664 | 🟢 0.59x |
| ⚪ | `idleHandlerAddsNothing` | 46.1 | 44.0 | 0.95x | 1224 | 704 | 🟢 0.58x |
| 🟢 | `inlineLimitCostsTimeNotAllocation` | 324.2 | 284.3 | 0.88x | 724434 | 740034 | 1.02x |
| 🟢 | `fusionAllocatesNothing` | 0.791 | 0.553 | 0.70x | 0 | 0 | ⚪ |
| 🟢 | `uncachedValuesPayBoxingOnly` | 70.8 | 46.6 | 0.66x | 141776 | 155376 | 1.10x |
| 🟢 | `inlineLimitKeepsZeroAllocation` | 2.07 | 1.33 | 0.64x | 0 | 0 | ⚪ |
| 🟢 | **`trailingMapsStayLinear`** | **622010** | **1554** | **0.0025x** | 1601251641 | 3840147 | **0.0024x** |

### Confirmation at `-f 3 -wi 10 -i 10`

The four largest deltas, rerun on both kernels with three forks and ten iterations:

| row | old µs | kernel2 µs | screen | confirmed |
|---|---:|---:|---|---|
| `fusionAfterSuspensionRunOnly` | 0.269 ± 0.001 | 2.481 ± 0.019 | 9.63x | **9.21x** |
| `fusionAfterSuspension` | 82.95 ± 0.28 | 565.4 ± 2.3 | 6.85x | **6.82x** |
| `statefulAnswersPaySuccessor` | 123.4 ± 6.2 | 238.6 ± 1.5 | 4.03x | **1.93x** |
| `sharedHandlerPaysDispatch` | 128.0 ± 0.9 | 194.8 ± 1.4 | 1.49x | **1.52x** |

**`statefulAnswersPaySuccessor` was overstated by the screen.** Five iterations had not settled; at ten it is
1.93x, not 4.03x. The other three hold within a few percent, with error bars under 1%.

### The mechanism, and the evidence for it

**Answering an operation costs a round trip that the previous kernel does not pay.** When a suspension is
dispatched, kernel2 folds every entry standing above the handler into a chain (`dump`), hands that to the
clause as the continuation, and then takes it apart again when the clause resumes (`push` decomposes chains).
A CPS kernel has no such step: the continuation is already a closure on the suspension node.

The board shows a dose-response against the number of pending steps above the handler:

| pending steps above the handler when the operation is answered | row | kernel2 / old |
|---:|---|---|
| 1 | `sharedHandlerPaysDispatch` | 1.52x |
| 1 | `suspensionBaseline` | 1.50x |
| 10 | `fusionAfterSuspension` | 6.82x |
| 53 | `fusionAfterSuspensionRunOnly` | 9.21x |

Allocation corroborates it from the other side. `sharedHandlerPaysDispatch` and `suspensionFusesContinuation`
are 1.5x and 1.4x slower with **identical** bytes per operation, so those two are pure path length.
`fusionAfterSuspensionRunOnly` allocates 4792 B/op where the old kernel allocates **nothing**, and it has 53
entries to fold, which is about 90 bytes per pending step.

This is a design cost of the stack evaluator rather than a defect in any one commit. The Aug 18 cross-kernel
screen (`reviews/bench/screen-0818-f1-head-report.md`, commit `35d4cbdba0`) already shows the same family
slower, so it predates all of this month's work, including tonight's.

### The other side of the same design

`trailingMapsStayLinear` is the reason the design exists: **622 ms/op to 1.55 ms/op, and 1.6 GB/op to 3.8
MB/op.** The old kernel is quadratic in trailing maps; kernel2 is linear. Same for the rescue rows, where
kernel2 allocates 40 to 60 percent less.

So the trade, stated plainly: kernel2 pays per answered operation, proportional to how much work is stacked
above the handler, and is paid back on map-heavy and deeply-composed work, spectacularly so where the old
kernel degenerates.

### Where I would look next

Not implemented, and not measured, so this is a direction rather than a claim:

**Most clauses resume immediately.** `cont(v)` as the clause's whole body is the common case across the
prelude's handlers, and for those the reification is pure waste: the entries are folded into a chain and
immediately pushed back. A capture that references the stack region and materializes only when a clause
actually stores it would erase the cost for the common shape and keep the semantics for the rest. The
continuation-as-value tests in `EvalTest` (`:746-876`) are the corpus that would keep it honest, since they
pin exactly the cases that must still materialize.

---

## 3. What changed tonight that could move these numbers

Recorded so the next run can be read against it:

- `Effect.bracket` is now a binding rather than an arrow. For a **pending** acquire this allocates the map's
  deferral and arrow on top of the binding, where the fused node was one object; for a **settled** acquire,
  which is what `Sync.ensure` uses, it is unchanged. No bracket row exists on the board, so this is unmeasured
  and is the first gap to close if brackets matter on a hot path.
- The preemption poll now reads a deferral's payload once before deciding, and a park carries what was read.
  This costs one type test, and only when a slice is armed, which a full evaluation never is.
- A binding ending its extent gets its own arm in the settled path instead of the generic arrow case, which
  skips a `dump` per binding exit.
- `handleCont`'s expansion is 7 bytes smaller, which is what the compile board's `HandleSites` row reflects.

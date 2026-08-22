# Cross-library benchmark results

How the kernel2 evaluator fares against ZIO, cats-effect, zio-blocks Async, and Turbolift on the
KernelBench row shapes, with the old kyo-kernel beside it. Produced from one measurement session on
the quiet machine, 2026-08-21 22:47 to 23:37, all seven boards back to back under the bench mutex.

## Provenance

- Protocol: `-f 2 -wi 5 -i 5 -prof gc -foe true`, AverageTime, microseconds, JMH 1.37, JDK 25.0.3.
- Versions: kernel2 and old kernel at HEAD (`f1848bec6d`); zio 2.1.26; cats-effect 3.7.0;
  zio-blocks-async 0.0.51; turbolift-core 0.126.0 (requirements said 0.114.0; that was stale, the
  plan pinned latest). All resolve natively for Scala 3.8.4.
- Raw JSONs: `reviews/bench/crosslib-0821-*.json` (committed). Boards: `kernel2`, `oldkernel` from
  the two kyo bench projects; `zio`, `ce`, `zioblocks`, `turbolift` from `kyo-kernel2-bench-cross`;
  `alt-ce-notrace` is the tracing-off sensitivity run.
- The cross project runs with `-Xss32m` (zio-blocks has no trampoline; its depth-10000 rows recurse
  on the JVM stack). The kernel boards run under their canonical flags; the kernel rows are
  trampolined and stack-insensitive.
- Drift check: the fresh kernel2 board matches the gate2 record within 0.97x to 1.03x on every
  shared row, inside the known 3 to 4 percent drift band, so this session is continuous with the
  campaign record. The two shape changes made for the cross comparison (loop starts read through
  the mutable `seed` field; the new `entryFloorBatch` and dynamic-chain rows) did not move any
  kernel row.
- Anti-folding fix, applied uniformly to all six boards: loop rows previously started from literal
  `0`; zio-blocks' fully inline ready path let C2 constant-fold entire rows built from literals (a
  depth-10000 recursion reported one nanosecond). Loop starts now come through the `seed` field.

## Fidelity legend (read before the tables)

| label | meaning |
|---|---|
| exact | same semantics: an effect operation resolved by an installed handler |
| substituted | the library's idiomatic ambient-value mechanism stands in for the handler; noted per row |
| LOW | no effect system to measure; a pre-completed value read stands in |
| JIT-eliminated | the row's result is seed-independent and the library has no machinery, so C2 deleted the program; the cell measures nothing and is excluded from the verdict |
| - | skipped; reason in the notes table |

Per-column fidelity:

- **zio**: Tier B rows read a `FiberRef` (the ambient substrate; `ZIO.service` is that read plus a
  ZEnvironment dictionary lookup, measured as an alternative below). The FiberRef default is the
  installed answer, so no per-run install is paid (alternative measured). Stateful row:
  `FiberRef.modify`.
- **ce**: Tier B rows read an `IOLocal`; stateful row `IOLocal.modify` (fiber-local state
  threading, matching what kyo's stateful region does; the requirements' `Ref[IO]` measured as an
  alternative below). Fiber tracing at its default (`cached`); tracing-off measured as an
  alternative. The entry (`unsafeRunSync()`) is a thread handoff per operation: an
  ArrayBlockingQueue, a fiber scheduled onto the work-stealing pool, the caller parked.
- **zioblocks**: no effect system; every Tier B row is LOW fidelity by construction. On rows whose
  result is seed-independent, C2 deletes the fully inlined program: those cells are labelled
  JIT-eliminated. That is the honest zio-blocks answer: no machinery, so dead work costs nothing,
  and only rows with live work (deep frames, allocation, dynamic structure) measure anything.
- **turbolift**: exact on every effect row (true algebraic effects: Reader and State operations
  under installed handlers; `asksEff` is the exact analogue of kyo's `askWith`). Entry is `runST`
  (calling-thread executor; `.run` defaults to a thread pool and would measure a handoff).

Emoji verdict: 🟢 = kernel2 at or within 5 percent of the best **external** library on the row;
🔴 = some external library beats kernel2 by more than 5 percent. The oldkernel column is the
sibling reference (the standing campaign scoreboard tracks that comparison) and does not decide
the emoji; JIT-eliminated cells do not either.

## Time (us/op)

| row | kernel2 | oldkernel | zio | ce | zioblocks | turbolift |
|---|---|---|---|---|---|---|
| continuationBodiesFuse | 🟢 32.60 | 23.68 (0.73x) | 178 (5.47x) | 251 (7.72x) | 0.0025 (JIT-eliminated) | 103 (3.17x) |
| deepRecursionPaysRescuesOnly | 🔴 50.18 | 52.84 (1.05x) | 41.18 (0.82x) | 111 (2.21x) | 3.05 (0.06x) | 29.57 (0.59x) |
| dynamicChainOfBindsStaysLinear | 🔴 3.23 | 4.66 (1.44x) | 13.98 (4.33x) | 28.24 (8.75x) | 0.6585 (0.20x) | 8.34 (2.58x) |
| dynamicChainOfMapsStaysLinear | 🔴 3.21 | 4.65 (1.45x) | 7.87 (2.45x) | 23.68 (7.37x) | 0.6553 (0.20x) | 6.63 (2.06x) |
| entryFloorBatch | 🟢 0.0003 | 0.0067 | 0.0577 | 7.63 | 0.0003 | 0.0439 |
| evalFixedOverhead | 0.0016 | 0.0091 | - | - | - | - |
| evalFixedOverheadBatch | 🔴 0.0016 | 0.0093 | 0.0629 | 7.62 | 0.0003 | 0.0473 |
| foreignCrossingsPayRotation | 🔴 868 | 310 (0.36x) | 482 (0.56x) | 852 (0.98x) | - | 116 (0.13x) |
| fusionAfterSuspension | 🔴 196 | 81.70 (0.42x) | 176 (0.90x) | 215 (1.10x) | 0.0025 (JIT-eliminated) | 99.13 (0.51x) |
| fusionAfterSuspensionRunOnly | 🔴 0.8071 | 0.2707 (0.34x) | 0.5649 (0.70x) | 9.37 (11.61x) | 0.0006 (JIT-eliminated) | 0.6243 (0.77x) |
| fusionAllocatesNothing | 🟢 0.5543 | 0.8036 (1.45x) | 4.63 (8.35x) | 14.02 (25.29x) | 0.0015 (JIT-eliminated) | 3.91 (7.05x) |
| fusionPastBudgetPaysRescuesOnly | 🟢 43.83 | 46.50 (1.06x) | 156 (3.55x) | 190 (4.33x) | 0.0015 (JIT-eliminated) | 106 (2.41x) |
| handleLoopAnswersInPlace | 89.01 | 129 (1.45x) | - | - | - | - |
| idleHandlerAddsNothing | 🟢 43.78 | 46.84 (1.07x) | 148 (3.37x) | 191 (4.35x) | - | 96.99 (2.22x) |
| inlineLimitCostsTimeNotAllocation | 280 | 331 (1.18x) | - | - | - | - |
| inlineLimitKeepsZeroAllocation | 1.29 | 2.09 (1.62x) | - | - | - | - |
| sharedHandlerPaysDispatch | 🔴 161 | 132 (0.82x) | 84.66 (0.53x) | 583 (3.62x) | 8.49 (0.05x) | 65.34 (0.41x) |
| statefulAnswersPaySuccessor | 🟢 91.22 | 146 (1.60x) | 388 (4.26x) | 279 (3.06x) | 0.0031 (JIT-eliminated) | 122 (1.34x) |
| suspensionBaseline | 🔴 186 | 123 (0.66x) | 234 (1.26x) | 456 (2.45x) | 0.0031 (JIT-eliminated) | 42.17 (0.23x) |
| suspensionFusesContinuation | 🔴 99.78 | 69.12 (0.69x) | 60.35 (0.60x) | 467 (4.68x) | 0.0031 (JIT-eliminated) | 49.57 (0.50x) |
| trailingMapsStayLinear | 🔴 639 | 463,894 (726.01x) | 334 (0.52x) | 801 (1.25x) | 44.98 (0.07x) | 121 (0.19x) |
| uncachedValuesPayBoxingOnly | 🟢 46.81 | 71.38 (1.53x) | 154 (3.29x) | 193 (4.13x) | 0.0024 (JIT-eliminated) | 113 (2.41x) |
| userTypesSkipKernelWrapping | 🔴 47.47 | 41.98 (0.88x) | 138 (2.91x) | 175 (3.69x) | 1.12 (0.02x) | 109 (2.30x) |

## Allocation (gc.alloc.rate.norm, B/op)

| row | kernel2 | oldkernel | zio | ce | zioblocks | turbolift |
|---|---|---|---|---|---|---|
| continuationBodiesFuse | 64,136 | 56,072 (0.87x) | 425,342 (6.63x) | 369,398 (5.76x) | 16 | 561,407 (8.75x) |
| deepRecursionPaysRescuesOnly | 912 | 2,128 (2.33x) | 400,520 (439.00x) | 401,073 (439.61x) | 0 (0.00x) | 320,360 (351.14x) |
| dynamicChainOfBindsStaysLinear | 13,984 | 13,984 (1.00x) | 86,600 (6.19x) | 64,764 (4.63x) | 0 (0.00x) | 86,256 (6.17x) |
| dynamicChainOfMapsStaysLinear | 13,984 | 13,984 (1.00x) | 46,600 (3.33x) | 48,800 (3.49x) | 0 (0.00x) | 62,256 (4.45x) |
| entryFloorBatch | 0 | 0 | 462 | 1,050 | 0 | 310 |
| evalFixedOverhead | 0 | 0 | - | - | - | - |
| evalFixedOverheadBatch | 0 | 0 | 508 | 1,088 | 0 | 348 |
| foreignCrossingsPayRotation | 1,760,310 | 1,680,202 (0.95x) | 2,001,378 (1.14x) | 1,521,194 (0.86x) | - | 641,465 (0.36x) |
| fusionAfterSuspension | 712,785 | 408,437 (0.57x) | 353,238 (0.50x) | 329,361 (0.46x) | 16 | 513,358 (0.72x) |
| fusionAfterSuspensionRunOnly | 1,240 | 0 (0.00x) | 860 (0.69x) | 1,572 (1.27x) | 0 | 2,008 (1.62x) |
| fusionAllocatesNothing | 0 | 0 | 10,992 | 10,800 | 0 | 17,984 |
| fusionPastBudgetPaysRescuesOnly | 664 | 1,128 (1.70x) | 321,195 (483.51x) | 297,290 (447.52x) | 0 | 536,844 (808.13x) |
| handleLoopAnswersInPlace | 642,009 | 960,135 (1.50x) | - | - | - | - |
| idleHandlerAddsNothing | 704 | 1,224 (1.74x) | 321,730 (456.81x) | 297,449 (422.33x) | - | 537,365 (762.98x) |
| inlineLimitCostsTimeNotAllocation | 740,034 | 724,410 (0.98x) | - | - | - | - |
| inlineLimitKeepsZeroAllocation | 0 | 0 | - | - | - | - |
| sharedHandlerPaysDispatch | 240,457 | 240,407 (1.00x) | 641,502 (2.67x) | 722,184 (3.00x) | 16 (0.00x) | 401,473 (1.67x) |
| statefulAnswersPaySuccessor | 643,273 | 1,040,140 (1.62x) | 2,719,210 (4.23x) | 1,359,154 (2.11x) | 16 | 1,038,950 (1.62x) |
| suspensionBaseline | 640,137 | 560,081 (0.87x) | 961,031 (1.50x) | 721,123 (1.13x) | 16 | 320,856 (0.50x) |
| suspensionFusesContinuation | 240,097 | 240,050 (1.00x) | 640,536 (2.67x) | 721,103 (3.00x) | 16 | 400,867 (1.67x) |
| trailingMapsStayLinear | 2,320,956 | 1,601,202,469 (689.89x) | 1,492,358 (0.64x) | 1,264,982 (0.55x) | 160,032 (0.07x) | 960,947 (0.41x) |
| uncachedValuesPayBoxingOnly | 155,376 | 141,776 (0.91x) | 459,894 (2.96x) | 436,013 (2.81x) | 16 | 683,558 (4.40x) |
| userTypesSkipKernelWrapping | 176,848 | 177,056 (1.00x) | 481,381 (2.72x) | 457,485 (2.59x) | 16,032 (0.09x) | 705,029 (3.99x) |

## Skips and per-row notes

| row | note |
|---|---|
| evalFixedOverhead | kyo boards only; the single-shot row sits inside harness resolution, superseded by the batch row |
| handleLoopAnswersInPlace | kyo boards only: it differs from suspensionBaseline solely in which kernel handler shape resolves the operation; other libraries have no such distinction, and a port would duplicate suspensionBaseline byte for byte |
| inlineLimit rows | kyo boards only: they pin kyo inline mechanics, not evaluator work |
| suspensionFusesContinuation | ZIO spells it `FiberRef.getWith` (defined as `get.flatMap`); CE and zio-blocks have no distinct spelling, and their cells were expected to equal suspensionBaseline. ZIO's measured 60 vs 234: `getWith` avoids the intermediate `map` node of the baseline spelling, a real finding. Turbolift's `asksEff` is exact |
| foreignCrossingsPayRotation | ZIO and CE cells are two fiber-local map reads per level, not two nested regions crossed; only Turbolift (two Reader handlers) measures what the kyo row measures. zio-blocks skipped |
| fusionAfterSuspensionRunOnly | zio-blocks NONE fidelity: map over a ready value evaluates at field initialization, so the cell measures `.block` on a settled Int |
| statefulAnswersPaySuccessor | zio-blocks LOW: method-local var loop, no state effect exists to pay for |
| idleHandlerAddsNothing | the one row where the per-run install is the substance: ZIO `locally`, CE `set`; zio-blocks skipped (no handler concept) |
| dynamicChain rows | adopted from zio-blocks' own AsyncChainBench (build N single links at runtime, run once); the one genuinely new shape their suite carries against KernelBench |

## Recorded alternatives (excluded from the headline tables)

| variant | time us/op | B/op | headline it varies |
|---|---|---|---|
| ZIO environment read (`ZIO.service` under `provideEnvironment`) | 501.2 | 2,881,877 | suspensionBaseline 233.9 / 961,031: the environment is the same FiberRef read plus a ZEnvironment dictionary lookup, 2.1x slower, 3x the allocation |
| ZIO per-run install (`locally`, an uninterruptible bracket) | 261.5 | 1,201,596 | suspensionBaseline 233.9: +12% |
| CE per-run install (`set` before the loop) | 544.2 | 721,232 | suspensionBaseline 456.2: +19% |
| CE `Ref[IO]` stateful (the requirements' spelling) | 196.4 | 1,041,113 | statefulAnswersPaySuccessor 279.3 / 1,359,154: the shared atomic is measurably faster than IOLocal here; the headline keeps IOLocal because it is the mechanism-faithful substitution (fiber-local state threading), with this number recorded beside it |
| CE tracing off (`-Dcats.effect.tracing.mode=none`, full board in `crosslib-0821-alt-ce-notrace.json`) | | | construction-time tracing is large on deep-bind rows: deepRecursion 111 to 49.4 (0.44x), suspensionBaseline 456 to 308 (0.68x), fusionPastBudget 190 to 144 (0.76x), stateful 279 to 234 (0.84x). The headline keeps the default because it is what an idiomatic CE application runs |

## Findings

1. **kernel2 wins the eager and fused families outright against every external library.** Fusion
   (8.4x/25x/7.1x vs zio/ce/turbolift), the trampoline row (3.6x/4.3x/2.4x), boxing and user-type
   chains (2.9x to 4.1x), the settled loop under a handler (3.4x to 4.4x), the stateful family
   after F1 (3.1x to 4.3x, and 1.3x vs turbolift's exact State effect), the fixed entry (0.3ns; 36x
   under zio, 29x under turbolift, three orders under CE), and the batch eval floor. Allocation
   tells the same story more sharply: the fusion family runs at 0 B/op where zio/ce/turbolift pay
   10 to 18 KB, and the rescue-only rows carry hundreds of bytes against their hundreds of
   kilobytes.
2. **The suspension and handler-crossing cluster is where externals win, and it is the same
   cluster that is red against the old kernel.** Turbolift, the only true algebraic-effects peer,
   is the strongest: suspensionBaseline 42 vs 186 (4.4x), foreignCrossings 116 vs 868 (7.5x),
   trailingMaps 121 vs 639 (5.3x), sharedHandler 65 vs 161 (2.5x), at half the allocation on the
   baseline row. ZIO also beats kernel2 on suspensionFuses (0.60x), sharedHandler (0.53x),
   trailingMaps (0.52x), deepRecursion (0.82x). This is independent confirmation that the round
   three optimization targets (HandlerCont answer-in-class, Safepoint plain-read, foreign's
   per-crossing fold) are the right ones: the gap to the old kernel and the gap to the best
   externals sit on the same rows.
3. **cats-effect is entry-dominated and construction-heavy on this workload.** Its 7.6 us entry
   floor (a thread handoff per `unsafeRunSync`) swamps the fast rows, and default fiber tracing
   costs up to 2.3x on deep-bind rows (the notrace board is committed). CE beats kernel2 nowhere.
4. **zio-blocks is not an evaluator on these rows and the comparison says so.** Rows whose result
   is seed-independent are deleted whole by C2 (labelled JIT-eliminated); the rows that survive
   measure direct unprotected recursion (deepRecursion 3.05 at 10,000 real stack frames, needs
   -Xss32m; 0 B/op) or eager inline loops (dynamic chains 0.66, 0 B/op: it skips even the Integer
   boxing that kyo's pending union pays). It has no effects, no trampolining, no interruption; its
   numbers are the floor of not having a kernel, not a competing kernel.
5. **The adopted dynamic-chain rows land a kernel2 win over the old kernel** (3.2 vs 4.7, 1.45x,
   identical 14 KB boxing-only allocation), and beat every effect-system peer (zio 2.5x, turbolift
   2.1x, ce 7.4x). Only zio-blocks' eager non-representation is faster.
6. **Anti-folding matters for cross-library boards.** Literal-seeded loops survive every
   interpreter but not a zero-machinery inline path; benchmark inputs must flow through mutable
   state for a comparison to mean anything. Fixed uniformly; kernel rows measurably unaffected
   (drift check above).

## Reproduction

Boards were produced by one sbt invocation (session log preserved during the run):

```sh
sbt \
  'kyo-kernel2JVM/Jmh/run -f 2 -wi 5 -i 5 -prof gc -foe true -rf json -rff reviews/bench/crosslib-0821-kernel2.json kyo.kernel.bench.KernelBench' \
  'kyo-kernel-bench/Jmh/run -f 2 -wi 5 -i 5 -prof gc -foe true -rf json -rff reviews/bench/crosslib-0821-oldkernel.json kyo.kernel.bench.KernelBench' \
  'kyo-kernel2-bench-cross/Jmh/run -f 2 -wi 5 -i 5 -prof gc -foe true -rf json -rff reviews/bench/crosslib-0821-zio.json kyo.kernel.bench.cross.ZioBench' \
  'kyo-kernel2-bench-cross/Jmh/run -f 2 -wi 5 -i 5 -prof gc -foe true -rf json -rff reviews/bench/crosslib-0821-ce.json kyo.kernel.bench.cross.CatsEffectBench' \
  'kyo-kernel2-bench-cross/Jmh/run -f 2 -wi 5 -i 5 -prof gc -foe true -rf json -rff reviews/bench/crosslib-0821-zioblocks.json kyo.kernel.bench.cross.ZioBlocksBench' \
  'kyo-kernel2-bench-cross/Jmh/run -f 2 -wi 5 -i 5 -prof gc -foe true -rf json -rff reviews/bench/crosslib-0821-turbolift.json kyo.kernel.bench.cross.TurboliftBench' \
  'kyo-kernel2-bench-cross/Jmh/run -f 2 -wi 5 -i 5 -prof gc -foe true -jvmArgsAppend -Dcats.effect.tracing.mode=none -rf json -rff reviews/bench/crosslib-0821-alt-ce-notrace.json kyo.kernel.bench.cross.CatsEffectBench'
```

Tables are generated by `reviews/crosslib_join.py`:

```sh
cd reviews/bench && python3 ../crosslib_join.py \
  kernel2=crosslib-0821-kernel2.json oldkernel=crosslib-0821-oldkernel.json \
  zio=crosslib-0821-zio.json ce=crosslib-0821-ce.json \
  zioblocks=crosslib-0821-zioblocks.json turbolift=crosslib-0821-turbolift.json
```

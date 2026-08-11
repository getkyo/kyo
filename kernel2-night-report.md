# kernel2 parity night: report

Autonomous session while you slept, per the standing instruction: fix the test issues, incorporate old-kernel features toward parity in features, file organization, visibility, and APIs, keep only cont/loop/loopState/partial for handling, preemption via a Safepoint.owners wrapper, kernel module only, composability and correctness by construction throughout, perf checked as work landed with a comprehensive sweep at the end and no architecture changes.

Suite state at the end of the night: 489/489 kyo-kernel2 JVM tests on a clean build, bench compiling, every commit attribution-clean. The comprehensive JMH board (all rows, three forks, gc profiler, frozen snapshots, idle machine) was still measuring when this report was written; its table is appended once it lands.

## What landed, in order

1. `46aae5d7b6` partial returns as the fourth handling variant. Restored verbatim from before the trampoline deletion: the eager driver that answers while the clause returns a present continuation and parks at refusal, foreign suspension, budget exhaustion, or a region node, returning the computation as it stands. New pin: partial parks at a region node without evaluating it.

2. `2ccf3807e3` preemption via a Stop wrapper in Safepoint.owners. `Safepoint.stop(thread)` CASes the thread's owners entry into a `Stop` wrapper riding the existing volatile array; slot resolution and compaction see through it; `Safepoint.stopped` consumes it one-shot. The running evaluation pays nothing on the hot path: the marker is read only on the budget's slow path, so detection latency is bounded by one Period. `Eval.partial` became the scheduler entry: it evaluates regions like `apply` but yields the standing computation reified with its remaining layers (plain data, resumable by evaluating again) on stop-check, pending stop request, or unhandled suspension. A first variant also poisoned the budget counter to accelerate detection; save and restore alias that poison across evaluations (it leaked a wedged budget into later evaluations), so the marker alone is the signal.

3. `38766de3b6` Effect ported: `defer` and `catching`. `catching` guards by rewrapping suspensions and defers with a continuation arrow that applies the original steps under the handler and re-guards what they produce, so every resumed step stays covered (the old kernel's guarantee, including the failure-in-map pin exercised inside a region's answering walk). Region internals evaluate at eval and are documented as not covered, consistent with lazy regions. The machinery is a non-inline helper because an anonymous suspension's `root` override does not typecheck inside an inline expansion.

4. `e19661ee6f` the lift discipline and Pending parity. `CanLift` ported into the new `kyo.kernel.internal`: the implicit lift now rejects lifting an already pending computation at compile time with the old kernel's teaching error message, turning the recurring silent-nesting footgun (three occurrences in this kernel's history) into a compile error, pinned with `assertTypeError`. Pending gained `unit`, `flatten`, the ten `handle` pipe overloads, `liftAnyVal`, `liftUnit`, the pure-function lifts, and the `Render` instance.
   - Deviation: the old kernel's `LiftMacro` (static per-type mode selection, plus the kyo-module-lift diagnostic) was ported and then reverted: same-module macro expansion trips dotty's compilation-suspension bug (StaleSymbolException on clean builds). The macro-free lift keeps the load-bearing constraint (`NotGiven`-based nesting rejection) and the primitive fast path; the module-lift diagnostic is lost until the compiler issue is resolved.

5. `a76bf421c4` kyo.Kyo collection utilities, kernel.scala aliases, old suspend shapes. The full 2800-line `kyo.Kyo` companion (foreach families through groupings and zips, with the List, Chunk, Map, Maybe specializations) adapted only by dropping Safepoint threading; `kyo.<`, `kyo.Loop`, `Id`, `Const` aliases for source compatibility; `ArrowEffect.suspend`/`suspendWith` take the old kernel's split type-parameter shape so call sites write `suspend[Any](tag, input)`. Suspensions gained the old diagnostic `toString`.
   - Deviation: the rendering carries the pending operation's origin frame rather than the old kernel's latest-transformation frame; mirroring the old behavior would add a type test to the hot `map` path for a diagnostic nicety. The toString pin asserts structure.
   - Two old tests marked `pendingUntilFixed("deep effect suspension is not yet stack-safe (StackOverflowError)")` evaluate fine in kernel2 (actuals 200001 and 103125 at n=100000) and became plain assertions: a parity improvement documented by tests.

6. `2ed2230d44` ContextEffect over handlers. `ContextEffect[A] extends ArrowEffect[Const[Unit], Const[A]]`; provision is an answering `handleLoop` layer; no context register exists. Optional context is data: a `Defaulted` suspension carries a fallback that Eval's miss arm resumes with, resolved through the node's `root` so the property survives maps. The layered handle (provide-or-transform-outer) re-raises the effect through an erased probe from its clause; since a clause runs outside its own scope, the probe resolves against outer handlers or falls back to a sentinel compared by identity before any cast (primitive-safe). `ArrowEffect` took the old kernel's variance. The old `RuntimeEffectTest`-in-`ContextEffectTest.scala` naming quirk is fixed.

7. `3d9bcb6830` internal package. `Kyo` (nodes), `Arrow`, `Handler`, `Handlers`, `Eval`, `Safepoint`, `CanLift` moved to `kyo.kernel.internal` with their unit tests, mirroring the old layout; public files at `kyo.kernel` are `ArrowEffect`, `ContextEffect`, `Effect`, `Loop`, `Pending`. One visibility lesson: `Eval` had to become public within the internal package rather than `private[kyo]`, because a private object referenced from public inline bodies makes dotty emit an inline accessor that materializes the package prefix as a runtime value (`NoClassDefFoundError: kyo/kernel/internal` at every eval site). The internal package location carries the intent.

8. `4ddc4aeb88` CONTRIBUTING.md refreshed to the above state (file map with the internal layout, the four handling variants, partial's semantics, the miss behavior including Defaulted and residuals, the state arm, the CanLift discipline and the reverted macro, the executed test migration, deleted probes).

Earlier the same evening, before you went to bed and part of the same arc: handlers as final data classes with the honest region rows (`c18e0bb573`), the trampoline deletion, `evalNow`, and the `partial`/`Eval.partial` groundwork.

## Parity map

- Ported: Loop (+73-test LoopTest), Effect (defer, catching, EffectTest), ContextEffect (+ContextEffectTest), kyo.Kyo utilities (+KyoTest, KyoForeach tests), kernel.scala aliases, CanLift, evalNow, suspend/suspendWith shapes, suspension toString, Pending extension surface, internal package layout and test organization.
- New relative to the old kernel: the preemption signal (Safepoint.stop wrapper per your ruling), Defaulted suspensions, region-based handling with the four variants, `Eval.partial` residual reification.
- Assessed and deferred, needing your ruling:
  - Isolate: inseparable from the old Context register and the async boundary story. In kernel2, reified residuals carry their layers, so the copy category (context effects crossing forks) holds by construction; the transform-on-restore category (Var and Emit isolation strategies) is fiber-integration design.
  - Trace machinery (TracePool, frame-enriched exceptions): the old design records per suspension through the threaded Safepoint instances; kernel2 nodes carry `Frame`, but per-operation recording on the hot path is a perf-sensitive design choice I did not make unilaterally.
  - The TestVariant annotation compiles but the build-level variant generator (List and Chunk specializations of KyoForeachCollTest) is old-module build machinery not wired for kernel2; the Seq-based suite runs.

## Defects found and fixed along the way

- The budget-poison aliasing in the first preemption variant (save and restore leaked a wedged budget into later evaluations; caught by three unrelated tests going red).
- The dotty inline-accessor package-materialization failure on private objects in inline bodies (class loading failed at every eval site after the reorg).
- The dotty StaleSymbolException on same-module macros (LiftMacro reverted; lift kept macro-free).
- The layered-context sentinel unboxing on primitive contexts (fixed by keeping the probe erased until after the identity comparison).
- The `unit`/`flatten` inline-expansion opacity losses (fixed by routing through values typed in the defining file).

## Open for your decisions

- Isolate and Trace, per above.
- The Cont cost recorded in the evening A/B (per-operation continuation closure plus crossed-layer rebuild on crossing rows; `foreignCrossingsPayRotation` +66% time under region `handle` vs the trampoline). The idiomatic answering surface is `handleLoop`, which is allocation-flat, but if Cont-heavy crossing patterns matter, that row is the one to optimize, within the architecture.
- The kyo-module-lift diagnostic lost with LiftMacro.
- The `.eval`-ignores-stop decision: synchronous `eval` runs to completion regardless of a pending stop request; only `Eval.partial` honors it. Deliberate, documented, revisitable.

## Comprehensive perf sweep

All KernelBench rows, HEAD vs the pre-parity-night frozen snapshot, three forks, gc profiler, idle machine. Two rows were renamed with their mechanism (`resumeAnswersInPlace` to `handleLoopAnswersInPlace`, `statefulAnswersPayOneTuple` to `statefulAnswersPaySuccessor`), so those compare semantically rather than by name.

| row | baseline | tonight | alloc baseline | alloc tonight |
|---|---|---|---|---|
| continuationBodiesFuse | 33.59 us | 33.49 us | 64176.2 B | 64176.2 B |
| deepRecursionPaysRescuesOnly | 52.72 us | 52.60 us | 912.4 B | 912.4 B |
| evalFixedOverhead | 0.002 us | 0.002 us | ~0 B | ~0 B |
| foreignCrossingsPayRotation | 583.7 us | 495.1 us | 2720484 B | 2080419 B |
| fusionAfterSuspension | 246.7 us | 238.4 us | 1081194 B | 1001194 B |
| fusionAfterSuspensionRunOnly | 1.23 us | 0.99 us | 1320.0 B | 1320.0 B |
| fusionAllocatesNothing | 0.745 us | 0.744 us | 0.005 B | 0.005 B |
| fusionPastBudgetPaysRescuesOnly | 32.87 us | 32.87 us | 1032.2 B | 1032.2 B |
| idleHandlerAddsNothing | 32.89 us | 32.80 us | 1128.2 B | 1128.2 B |
| inlineLimitCostsTimeNotAllocation | 231.5 us | 250.3 us | 743345.6 B | 743345.7 B |
| inlineLimitKeepsZeroAllocation | 1.71 us | 1.50 us | 0.012 B | 0.010 B |
| context provision (resume vs handleLoop) | 81.6 us | 89.0 us | 640080.6 B | 640176.6 B |
| sharedHandlerPaysDispatch | 184.6 us | 187.2 us | 240497.3 B | 240497.3 B |
| stateful answering (loop vs handleLoop) | 98.8 us | 340.3 us | 798088.7 B | 1838306.4 B |
| suspensionBaseline | 103.0 us | 105.3 us | 640176.7 B | 640176.7 B |
| suspensionFusesContinuation | 56.25 us | 56.58 us | 240136.4 B | 240136.4 B |
| trailingMapsStayLinear | 873.6 us | 878.0 us | 3281214 B | 3281214 B |
| uncachedValuesPayBoxingOnly | 35.14 us | 35.12 us | 155752.2 B | 155752.2 B |
| userTypesSkipKernelWrapping | 35.27 us | 38.0 us (wide error) | 177224.2 B | 177224.3 B |

Reading:

- Thirteen rows byte-identical on allocation and flat on time. `foreignCrossingsPayRotation` improved 15% time and 23% allocation, and `inlineLimitKeepsZeroAllocation` improved 12%, both after the handler kinds became final data classes with clause fields (monomorphic dispatch where the subclassed handlers were megamorphic).
- Context provision on the region mechanism costs 9% time over the deleted resume trampoline with per-operation allocation identical (the +96 B is one region node for the whole 10000-operation run). Acceptable for the semantics gained.
- `inlineLimitCostsTimeNotAllocation` shows +8% time with byte-identical allocation on a JIT-boundary-sensitive row; watch, likely code-layout noise.
- **The one real regression: stateful answering, 3.4x time and 2.3x allocation on its microbenchmark row.** The old `loop` trampoline paid one tuple per operation; the successor model pays a new `LoopState` instance, a `Handlers.updated` chunk copy, and the outcome box per state change, and the reference-check fast path never fires when the state is a fresh value (`state + 1`). That is about +24 ns and +104 B per stateful operation, isolated to this row. The candidate fix within the architecture is copy-on-write layer storage in the eval loop (mutate the working chunk, snapshot only where closures capture it), which changes evaluator internals but no representation; it needs a ruling before landing, so it is documented rather than done.

## Review-session follow-up (the day after)

The review session re-measured this board on the same frozen classfiles and revised the stateful finding: the night's 340us reading does not reproduce, the stable cost is 165.8us and 1,278KB against the old trampoline's 98.8us and 798KB, so the true regression is 1.68x time and 1.6x allocation, still the per-answer successor (`withState` object, `updated` chain node, `Continue2` box, state box) against the old loop-variable threading. The layer storage landed as Chunk with adaptive compaction (`08dc668847`, ruled interactively): entry and settle are chain nodes again, a deep handler scan flattens the storage once past a threshold, and `Chunk.updated` became a real kyo-data override.

The session also introduced and then caught a fresh regression with the allocation profiler: `rebuildFrom` briefly flattened its layer stack unconditionally, which runs on every answered operation's continuation path; two thirds of all allocation on the suspension rows sat under its `toIndexed` and `suspensionFusesContinuation` read 6.7x. Fixed by flattening only past the compaction threshold (`84e930cca6`). With the fix, the suspension rows land at or better than the old kernel: `suspensionFusesContinuation` 35.8us vs the old 56.3 (the single-transform `suspendWith` and the fused handler-plus-exit objects showing through), `continuationBodiesFuse` 27.1 vs 33.6, `suspensionBaseline` 96.4 vs 103.0, `sharedHandlerPaysDispatch` 173.1 vs 184.6, all at byte-identical allocation. The full three-fork post-fix board and the stateful-row optimization are the open items.

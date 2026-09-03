# ProtoBench: tip 1c2d8dcbe7 against control 28d1dcb43f

Control `28d1dcb43f` is the commit before the soundness fixes (S1 to S8), the Context rewrite and the
TypeMap revert; tip `1c2d8dcbe7` is the branch head at the time of the run. Both were measured from
detached worktrees (`bench-sweep-soundness`, `bench-sweep-tip`) with the same `ProtoBench` source, which
is byte-identical between the two commits, plus two context rows added for this run (`8c1e339c75`).

## Method

- Bracket C V C V C at `-f 1`, 10 warmup and 5 measured iterations of 1 s, `-prof gc -prof comp`,
  each leg gated on the machine's one-minute load average dropping below 5 on 12 cores. Raw legs:
  `control-{1,2,3}.json`, `tip-{1,2}.json`; pooled table in `table.txt`.
- Rows outside or near their control band were re-measured at `-f 3`, control then tip then control
  (`confirm/suspects-*.json`, six control forks against three tip forks). The two context rows were
  measured the same way (`confirm/context-*.json`).
- Evidence for the movers: `gc.alloc.rate.norm` on every leg, allocation-site and CPU profiles through
  async-profiler, `PrintInlining` and `PrintCompilation` logs, one-variable experiments on the tip
  worktree, and a `git bisect` over the kernel commits with the regressed row as oracle (`evidence/`).
- Noise: the harness's stored leg of the same control (`control-28d1dcb43f-f91ac432`) has byte-identical
  allocation on every row and timings that differ from today's by -35% to +17%, so a single leg on
  either side is not evidence. Control leg 3 was disturbed (single iterations at 1.5x to 2x on six rows),
  which is where the wide control bands come from; the `-f 3` confirmation replaces those rows' numbers.

## Time, ProtoBench, C V C V C at -f 1

Control band is the spread between the three control legs of the same code; a delta inside it is noise. Rows marked confirmed were re-measured at -f 3 (six control forks against three tip forks), and the confirmed numbers are shown.

| row | control us/op | tip us/op | delta | control band | status |
|---|---:|---:|---:|---:|:---:|
| `continuationBodiesFuse` | 18.358 | 9.832 | -46.4% | 7.1% | 🟢 improvement (confirmed at -f 3) |
| `deepRecursionNoRescue` | 1.531 | 1.512 | -1.3% | 1.0% | ✅ flat |
| `deepRecursionOneRescue` | 2.779 | 2.768 | -0.4% | 0.7% | ✅ flat |
| `deepRecursionPaysRescuesOnly` | 49.088 | 48.625 | -0.9% | 4.2% | ✅ flat |
| `deferBindPerStep` | 12.955 | 12.837 | -0.9% | 5.1% | ✅ flat |
| `deferBindUnderIdleHandler` | 17.234 | 16.954 | -1.6% | 8.4% | ✅ flat |
| `deferBindUnderTrailingMap` | 29.297 | 29.210 | -0.3% | 2.1% | ✅ flat |
| `dynamicChainOfBindsStaysLinear` | 4.050 | 4.046 | -0.1% | 1.0% | ✅ flat |
| `dynamicChainOfMapsStaysLinear` | 4.071 | 4.039 | -0.8% | 1.4% | ✅ flat |
| `effectfulIterationViaArrow` | 78.845 | 77.121 | -2.2% | 3.8% | ✅ flat |
| `effectfulIterationViaLoop` | 227.069 | 220.732 | -2.8% | 5.3% | ✅ flat |
| `emittingClausesPayRegionRebuild` | 78.655 | 75.536 | -4.0% | 6.3% | ✅ flat |
| `evalFixedOverhead` | 0.002 | 0.002 | -1.1% | 2.3% | ✅ flat |
| `foreignCrossingsAnsweredInPlace` | 668.890 | 632.651 | -5.4% | 5.0% | 🟢 improvement |
| `foreignCrossingsPayRotation` | 1089.642 | 1104.318 | +1.3% | 3.0% | ✅ flat (confirmed at -f 3) |
| `fusionAfterSuspension` | 129.870 | 130.295 | +0.3% | 4.6% | ✅ flat (confirmed at -f 3) |
| `fusionAfterSuspensionRunOnly` | 0.529 | 0.527 | -0.4% | 2.9% | ✅ flat |
| `fusionAllocatesNothing` | 0.081 | 0.078 | -3.5% | 9.3% | ✅ flat |
| `fusionPastBudgetPaysRescuesOnly` | 46.237 | 45.619 | -1.3% | 3.7% | ✅ flat |
| `handleLoopAnswersInPlace` | 43.310 | 42.847 | -1.1% | 1.4% | ✅ flat |
| `handleLoopFusesContinuation` | 42.981 | 42.796 | -0.4% | 0.4% | ✅ flat |
| `idleHandlerAddsNothing` | 45.478 | 45.330 | -0.3% | 0.9% | ✅ flat |
| `inlineLimitCostsTimeNotAllocation` | 281.957 | 280.085 | -0.7% | 1.4% | ✅ flat |
| `inlineLimitKeepsZeroAllocation` | 1.121 | 1.122 | +0.1% | 0.8% | ✅ flat |
| `nestedPayloadsUnwrapInMaps` | 6.006 | 6.001 | -0.1% | 0.6% | ✅ flat |
| `partialSuspensionBaseline` | 115.200 | 107.542 | -6.6% | 21.6% | ✅ flat |
| `pureIterationViaArrow` | 93.595 | 91.071 | -2.7% | 42.9% | ✅ flat |
| `pureIterationViaLoop` | 15.862 | 15.891 | +0.2% | 0.6% | ✅ flat |
| `pureIterationViaMethod` | 65.180 | 64.360 | -1.3% | 8.1% | ✅ flat (confirmed at -f 3) |
| `sharedHandlerPaysDispatch` | 166.151 | 165.677 | -0.3% | 3.7% | ✅ flat |
| `statefulAnswersPaySuccessor` | 40.772 | 40.971 | +0.5% | 1.2% | ✅ flat |
| `suspensionBaseline` | 76.366 | 76.563 | +0.3% | 4.1% | ✅ flat |
| `suspensionFusesContinuation` | 40.881 | 35.829 | -12.4% | 45.9% | ✅ flat |
| `trailingMapsStayLinear` | 298.644 | 293.021 | -1.9% | 5.3% | ✅ flat (confirmed at -f 3) |
| `uncachedValuesPayBoxingOnly` | 49.705 | 49.589 | -0.2% | 3.9% | ✅ flat |
| `userTypesSkipKernelWrapping` | 50.152 | 50.265 | +0.2% | 1.5% | ✅ flat |
| `contextReadsUnderBindings` | 41.524 | 39.133 | -5.8% | 1.7% | 🟢 improvement (new row, -f 3 only) |
| `contextRegionsPayEntryExit` | 31.297 | 73.161 | +133.8% | 1.7% | 🔴 regression (new row, -f 3 only) |

## Allocation, gc.alloc.rate.norm

| row | control B/op | tip B/op | delta B/op | status |
|---|---:|---:|---:|:---:|
| `continuationBodiesFuse` | 48,120.2 | 48,120.1 | -0.1 | ✅ identical |
| `deepRecursionNoRescue` | 0.0 | 0.0 | -0.0 | ✅ identical |
| `deepRecursionOneRescue` | 32.0 | 32.0 | -0.0 | ✅ identical |
| `deepRecursionPaysRescuesOnly` | 608.4 | 608.4 | -0.0 | ✅ identical |
| `deferBindPerStep` | 64,080.1 | 64,080.1 | -0.0 | ✅ identical |
| `deferBindUnderIdleHandler` | 64,136.2 | 64,136.2 | -0.0 | ✅ identical |
| `deferBindUnderTrailingMap` | 112,144.3 | 112,144.3 | -0.0 | ✅ identical |
| `dynamicChainOfBindsStaysLinear` | 13,984.0 | 13,984.0 | -0.0 | ✅ identical |
| `dynamicChainOfMapsStaysLinear` | 13,984.0 | 13,984.0 | -0.0 | ✅ identical |
| `effectfulIterationViaArrow` | 480,136.7 | 480,136.7 | -0.0 | ✅ identical |
| `effectfulIterationViaLoop` | 1,120,226.0 | 1,120,226.0 | -0.1 | ✅ identical |
| `emittingClausesPayRegionRebuild` | 176,280.7 | 176,280.7 | -0.0 | ✅ identical |
| `evalFixedOverhead` | 0.0 | 0.0 | -0.0 | ✅ identical |
| `foreignCrossingsAnsweredInPlace` | 1,520,286.0 | 1,520,285.7 | -0.3 | ✅ identical |
| `foreignCrossingsPayRotation` | 2,240,361.8 | 2,480,385.5 | +240,023.7 | 🔴 more |
| `fusionAfterSuspension` | 536,609.2 | 536,609.2 | +0.0 | ✅ identical |
| `fusionAfterSuspensionRunOnly` | 1,240.0 | 1,240.0 | -0.0 | ✅ identical |
| `fusionAllocatesNothing` | 0.0 | 0.0 | -0.0 | ✅ identical |
| `fusionPastBudgetPaysRescuesOnly` | 488.4 | 488.4 | -0.0 | ✅ identical |
| `handleLoopAnswersInPlace` | 480,136.4 | 480,136.4 | -0.0 | ✅ identical |
| `handleLoopFusesContinuation` | 480,152.4 | 480,152.4 | -0.0 | ✅ identical |
| `idleHandlerAddsNothing` | 528.4 | 528.4 | -0.0 | ✅ identical |
| `inlineLimitCostsTimeNotAllocation` | 738,402.5 | 738,402.5 | -0.0 | ✅ identical |
| `inlineLimitKeepsZeroAllocation` | 0.0 | 0.0 | +0.0 | ✅ identical |
| `nestedPayloadsUnwrapInMaps` | 32,064.1 | 32,064.1 | -0.0 | ✅ identical |
| `partialSuspensionBaseline` | 480,121.0 | 480,121.0 | -0.1 | ✅ identical |
| `pureIterationViaArrow` | 158,624.8 | 158,624.8 | -0.0 | ✅ identical |
| `pureIterationViaLoop` | 160,048.1 | 160,048.1 | +0.0 | ✅ identical |
| `pureIterationViaMethod` | 158,456.6 | 158,456.6 | -0.0 | ✅ identical |
| `sharedHandlerPaysDispatch` | 240,457.5 | 240,457.5 | -0.0 | ✅ identical |
| `statefulAnswersPaySuccessor` | 480,160.4 | 480,160.4 | +0.0 | ✅ identical |
| `suspensionBaseline` | 480,120.7 | 480,120.7 | +0.0 | ✅ identical |
| `suspensionFusesContinuation` | 240,096.4 | 240,096.3 | -0.0 | ✅ identical |
| `trailingMapsStayLinear` | 1,600,642.8 | 1,600,642.6 | -0.1 | ✅ identical |
| `uncachedValuesPayBoxingOnly` | 155,200.4 | 155,200.4 | -0.0 | ✅ identical |
| `userTypesSkipKernelWrapping` | 176,672.5 | 176,672.5 | +0.0 | ✅ identical |
| `contextReadsUnderBindings` | 48,336.3 | 48,312.3 | -24.0 | 🟢 less (new row) |
| `contextRegionsPayEntryExit` | 120,136.2 | 112,128.5 | -8,007.7 | 🟢 less (new row) |

## Findings

### No timing regression on the 36 existing rows

Every existing row is flat or better once the noisy ones are confirmed at `-f 3`. Two groups moved:

- `continuationBodiesFuse` is 46% faster on every fork (control 17.5 to 18.8 us/op over six forks, tip
  9.7 to 10.0) with identical allocation. The mechanism was not investigated; it is an improvement, and
  the compilation logs for the row are stored under `evidence/` for a later look.
- The crossing and region-rebuild rows (`foreignCrossingsAnsweredInPlace`, `foreignCrossingsPayRotation`,
  `emittingClausesPayRegionRebuild`) improved 3% to 5% in the bracket. Consistent with `rebound` no longer
  calling `stack.find` per dumped entry after the Context rewrite; not isolated.

### Allocation regression: `foreignCrossingsPayRotation`, +24 B per crossing

2,240,362 to 2,480,386 B/op over 10,000 crossings. The allocation-site profile confirms the class:
`Object[]` goes from 13% to 23% of allocated bytes, every other class keeps its share. Mechanism, read
from the source and matching the size: on reinstall, `installed` settles the region's owed snapshot. The
control settled one lane by identity:

```scala
def settle(i: Int, snapshot: Stack.Snapshot): Unit =
    val lane = owed(i)
    if !lane.isEmpty && (lane.last.asInstanceOf[AnyRef] eq snapshot.asInstanceOf[AnyRef]) then
        owed(i) = if lane.length == 1 then Chunk.empty else lane.dropRight(1)
```

The tip's `settle` (S4) scans every lane from the top, and `settleIn` starts each non-empty lane with
`lane.toIndexed`. The lane is what `dump` built, `owed(from - 1).append(snapshot)`, an `Append` node, so
`toIndexed` copies it into a fresh one-element array: 16 bytes of header plus one compressed reference.
The row's time still improved 2.8% in the bracket and is flat at `-f 3`, so this is an allocation cost,
not a time cost, on this row. It is a representation cost: the lane can be walked by identity without
materializing it, the way `last` already is.

### Time regression: `contextRegionsPayEntryExit`, 31.3 to 73.2 us/op (+134%)

A new row: 1,000 iterations of enter a context region, read it, exit it. Bands under 2% on both sides.
Allocation is 8 B per iteration lower on the tip (a `Context.Bound` of three references for a
`TypeMap.Node` of four), and the allocation-site profile shows the same five objects per iteration on
both sides, so the cost is not allocation.

Evidence, in the order it was gathered:

- CPU profile: on the control 29% of samples land in `Eval.loop` and 8% in the row's own `loop` method;
  on the tip `Eval.loop` drops to 6% and the row's `loop` rises to 60%, under the map continuation. The
  extra time sits in the code the JIT compiles for that frame, which on the tip is 75 bytes of bytecode
  (the control's 200 include the settled fast path S5 removed). Bytecode and JIT decisions for that
  method and its four constructors are the same on both sides.
- `lazy val state` on the `Handle` node (S5) adds a volatile field and a `VarHandle` to every `Handle`
  class, but it is not the mechanism: with `def state` instead, clean-compiled, the row measures 70.9
  (`E1c-lazy-to-def.json`).
- Bisect over the kernel and data commits, oracle "under 50 us/op" with a clean compile per step:
  `1ede100a1b` (S3) 31.2, `164eb68f47` (S8) 31.1, `b6b1334eab` (the S9 revert) 31.2, `6b1ca5cc98`
  (the Context rewrite) 71.7. First bad commit: the Context rewrite. On this row's path that commit
  changes exactly two things: region entry binds through `ctx.bind` instead of `ctx.update`, region
  exit unbinds through `ctx.unbind` instead of `stack.findExact` plus `ctx.remove` or `ctx.update`; and
  the representation behind them (a region-ordered cons list in place of the branch's linked-list
  `TypeMap`). The read path is not the cost: `contextReadsUnderBindings` (1,000 reads under three
  bindings) is 5.8% faster on the tip.
- Constant pool check: the two `Tag[Cfg]` sites share one string constant, so the tag comparison in
  `get` takes the `eq` fast path; the compare is not the cost.

- Handle node state: `lazy val state` (S5) to `def` measures 70.9 clean-compiled (`E1c`), to `val` 73.3
  (`E33`); neither is the mechanism. The lazy form is gone anyway (`2897efc642`), by ruling.
- One-variable experiments on the tip worktree, each clean-compiled, none recovers the row: tag compare by
  reference only 70.2 (`E11`), exit returning the empty context 68.4 (`E5`), `get` as a plain loop 70.5
  (`E4`), `next` not loop-carried 72.9 (`E26`); no entry-time read and exit returning the empty context
  63.0 (`E27`, about 10 of the 42 ns); a cached node in place of the per-entry allocation 36.5 with 24 B
  less per iteration (`E28`). ParallelGC in place of G1: 33.7 against 69.9, the collector is not involved.
- Context operations alone (bind, read, unbind in a loop outside the evaluator, `ctxbench-*`): 0.30 us per
  1,000 on both revisions with zero allocation, so the Context is not intrinsically slow.
- JIT: the C2 inlining trees of `Eval.loop` are equivalent on both sides (everything on the path inlined,
  no failures); the C2 nmethods are the same size and shape (about 1,300 instructions, same call and
  barrier counts); both allocation sites are the TLAB fast path followed by the same release barrier.
- The one instruction-level difference: on the tip C2 emits the initializing stores of `Bound.value` and
  `Bound.next` as one paired 8-byte store (`stp`), while the control's `Node` fields are stored one by
  one. The reads of those fields that follow (`get`, `unbind`) are 4-byte loads that cannot forward from
  a wider in-flight store on this CPU and wait for it to drain behind the release barrier. This fits
  `E28` (no fresh node, no stall) and `E27` (fewer such loads, part of the cost), and it is a hypothesis:
  `-XX:-MergeStores` leaves the tip at 72.3 because the pairing comes from the aarch64 peephole, which
  this JDK exposes no switch for, and `xctrace` is not installed, so no per-instruction sample confirms it.

**Decision (2026-09-02, "ok, accept"):** accepted as measured. One operation, context-region entry and
exit, 42 ns per pair; every other row flat or better. The row stays in `ProtoBench` as the guard, and the
fix belongs with the region-entry work gated on this sweep (`atTop`, TODO 4): either no node per entry,
reading bindings through the region entries the stack already holds, or a node layout chosen against the
stall. No optimization applied, by instruction.

### Improvement, not investigated

`continuationBodiesFuse` -46% on every fork with identical allocation. Its compilation and inlining logs
are stored under `evidence/` for a later look.

## Files

- `control-{1,2,3}.json`, `tip-{1,2}.json`, `*.log`: the bracket legs; `table.txt`, `table.md`, `tables.md`.
- `confirm/`: the `-f 3` runs (`suspects-*`, `context-*`) and their tables; `context-prep*.log`.
- `evidence/`: allocation-site and CPU profiles (`alloc-*`, `cpu-*`, `lines-*`), inlining and compilation
  logs (`inline-*`, `inl2-*`, `inl3-*`, `comp-*`, `asm-*`), the experiments (`E*.json`, `experiments.log`),
  the bisect (`bisect/`), the Context micro-row (`ctxbench-*`), the ParallelGC and store-merging runs.

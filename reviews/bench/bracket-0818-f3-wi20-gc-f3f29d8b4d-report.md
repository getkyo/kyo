Replicated over 3 control and 3 variant leg(s): the threshold below is estimated from the spread between them.

A/A null: clean, no control row classified against another control leg (15 rows).

Control `f3f29d8b4d` (kernel-f1) against variant `f3f29d8b4d` (proto-f1).
JMH -f 1, all 15 rows, timing only, so no movement here is attributed, drift 4.0% assumed, not measured.
Markers control  | variant 

| | row | mode | cnt | control | variant | delta | resolves | B/op delta | mechanism |
|---|---|---|---|---|---|---|---|---|---|
| 🟢 | `evalFixedOverhead` | avgt | 5 | 0.007533 ± 0.000356 | 0.004000 ± 0.000356 | -46.9% | ±27.8% | - | **none found** |
| ⚪ | `emittingClausesPayRegionRebuild` | avgt | 5 | 82.23 ± 0.8506 | 70.03 ± 0.8506 | -14.8% | ±25.7% | -64064 | - |
| 🔴 | `fusionAllocatesNothing` | avgt | 5 | 0.5610 ± 0.000887 | 0.5889 ± 0.000887 | +5.0% | ±1.6% | +0 | **none found** |
| 🔴 | `continuationBodiesFuse` | avgt | 5 | 28.91 ± 0.5035 | 32.20 ± 0.5035 | +11.4% | ±10.7% | +0 | **none found** |
| ⚪ | `deepRecursionPaysRescuesOnly` | avgt | 5 | 51.25 ± 0.5534 | 57.93 ± 0.5534 | +13.0% | ±17.8% | +239592 | - |
| 🔴 | `fusionPastBudgetPaysRescuesOnly` | avgt | 5 | 34.65 ± 0.2682 | 44.76 ± 0.2682 | +29.2% | ±4.0% | +184024 | allocation +184024 B/op |
| 🔴 | `idleHandlerAddsNothing` | avgt | 5 | 34.56 ± 0.5822 | 44.71 ± 0.5822 | +29.4% | ±8.6% | +184024 | allocation +184024 B/op |
| 🔴 | `uncachedValuesPayBoxingOnly` | avgt | 5 | 34.77 ± 0.6981 | 47.77 ± 0.6981 | +37.4% | ±11.1% | +184032 | allocation +184032 B/op |
| 🔴 | `suspensionFusesContinuation` | avgt | 5 | 40.72 ± 0.2280 | 56.49 ± 0.2280 | +38.7% | ±2.9% | +240024 | allocation +240024 B/op |
| 🔴 | `nestedPayloadsUnwrapInMaps` | avgt | 5 | 5.99 ± 0.0520 | 8.36 ± 0.0520 | +39.6% | ±8.9% | +24000 | allocation +24000 B/op |
| 🔴 | `statefulAnswersPaySuccessor` | avgt | 5 | 116.3 ± 0.3661 | 211.8 ± 0.3661 | +82.2% | ±7.5% | +1 | **none found** |
| 🔴 | `suspensionBaseline` | avgt | 5 | 88.91 ± 0.5975 | 196.1 ± 0.5975 | +120.5% | ±12.6% | +1 | **none found** |
| 🔴 | `handleLoopAnswersInPlace` | avgt | 5 | 88.63 ± 18.67 | 216.2 ± 18.67 | +143.9% | ±107.6% | +1 | **none found** |
| 🔴 | `trailingMapsStayLinear` | avgt | 5 | 362.9 ± 29466.0 | 778494.2 ± 29466.0 | +214400.1% | ±41458.2% | +2399362298 | allocation +2399362298 B/op |
Every flat row below is flat to within its own resolution, at worst +-41458.21% (alpha 0.00333 after correcting for 14 rows, df 4).
ℹ️  Allocation moved on rows whose timing did not resolve. Allocation is exact and per-operation, so this is a real change regardless of what the timing could or could not show:
  - emittingClausesPayRegionRebuild -64064 B/op, timing -14.8% (Flat)
  - deepRecursionPaysRescuesOnly +239592 B/op, timing +13.0% (Flat)
⚠️  Same sha and no recorded JVM arguments on either leg, so nothing here says what was varied. Either this is an A/A, or it was measured before the harness recorded configuration and the difference is unrecoverable.
🔴 Regressed, so the work is unfinished until each is diagnosed or ruled on:
  - fusionAllocatesNothing +5.0%
  - continuationBodiesFuse +11.4%
  - fusionPastBudgetPaysRescuesOnly +29.2%
  - idleHandlerAddsNothing +29.4%
  - uncachedValuesPayBoxingOnly +37.4%
  - suspensionFusesContinuation +38.7%
  - nestedPayloadsUnwrapInMaps +39.6%
  - statefulAnswersPaySuccessor +82.2%
  - suspensionBaseline +120.5%
  - handleLoopAnswersInPlace +143.9%
  - trailingMapsStayLinear +214400.1%
⚠️  Moved with nothing in the evidence behind it, so the cause is not known yet:
  - evalFixedOverhead: check allocation sites and the inlining log before proposing a mechanism
  - fusionAllocatesNothing: check allocation sites and the inlining log before proposing a mechanism
  - continuationBodiesFuse: check allocation sites and the inlining log before proposing a mechanism
  - statefulAnswersPaySuccessor: check allocation sites and the inlining log before proposing a mechanism
  - suspensionBaseline: check allocation sites and the inlining log before proposing a mechanism
  - handleLoopAnswersInPlace: check allocation sites and the inlining log before proposing a mechanism
⚠️  This change both wins and loses. Those are two diagnoses, not one tradeoff: the loss usually turns out removable, and accepting it early ships a defect the same afternoon's work would have deleted.
ℹ️  74% of sampled time is outside kyo.kernel. or kyo.proto, so kernel-attributable movement is a fraction of each delta above. Largest contributors:
       40.9%  scala.runtime.BoxesRunTime.boxToInteger
        2.7%  kyo.kernel.bench.YetAnotherProtoBench$.ask
        1.8%  java.lang.Integer.valueOf

🔥 CPU by row (sampled time, top frames each side):
  🟢 `evalFixedOverhead` -46.9%
    control  kernel  29.0%  benchmark   1.1%  other  69.9%
         32.3% java.lang.invoke.VarHandleReferences$Array.getVolatile
         23.7% scala.runtime.BoxesRunTime.boxToInteger
         12.9% kyo.kernel.internal.Eval$.loop
          7.5% java.lang.ThreadLocal$ThreadLocalMap.getEntry
          4.3% kyo.kernel.internal.Nested.unnest
          4.3% kyo.kernel.internal.Safepoint.exit
    variant  kernel  27.8%  benchmark   3.3%  other  68.9%
         52.2% scala.runtime.BoxesRunTime.boxToInteger
         15.6% kyo.proto.Safepoint.save
         10.0% java.lang.ThreadLocal$ThreadLocalMap.getEntry
         10.0% kyo.proto.Safepoint.exit
          4.4% java.lang.invoke.VarHandleReferences$Array.getVolatile
          2.2% kyo.kernel.bench.jmh_generated.YetAnotherProtoBench_evalFixedOverhead_jmhTest.evalFixedOverhead_avgt_jmhStub
  ⚪ `emittingClausesPayRegionRebuild` -14.8%
    control  kernel  28.6%  benchmark   7.7%  other  63.7%
         53.8% scala.runtime.BoxesRunTime.boxToInteger
          7.7% kyo.kernel.internal.Eval$.loop
          3.3% kyo.kernel.internal.Stack.truncate
          3.3% kyo.kernel.bench.ProtoKernelBench$.tick
          3.3% kyo.kernel.internal.Eval$$anon$1.handler
          2.2% kyo.kernel.internal.Eval$.kyo$kernel$internal$Eval$$$_$region$1
    variant  kernel  53.3%  benchmark  19.6%  other  27.2%
         23.9% scala.runtime.BoxesRunTime.boxToInteger
         10.9% kyo.proto.Stack.push
          9.8% kyo.proto.Eval$.step
          7.6% kyo.proto.Eval$.go$1
          6.5% kyo.proto.Eval$.dispatch
          5.4% kyo.proto.Stack.loop$1
  🔴 `fusionAllocatesNothing` +5.0%
    control  kernel   7.9%  benchmark   3.4%  other  88.8%
         73.0% scala.runtime.BoxesRunTime.boxToInteger
         15.7% java.lang.invoke.VarHandleReferences$Array.getVolatile
          4.5% kyo.kernel.internal.Safepoint.enter
          3.4% kyo.kernel.bench.ProtoKernelBench.loop$1
          2.2% kyo.kernel.internal.Safepoint.home
          1.1% kyo.kernel.internal.Eval$.loop
    variant  kernel   9.8%  benchmark   5.4%  other  84.8%
         60.9% scala.runtime.BoxesRunTime.boxToInteger
         14.1% java.lang.invoke.VarHandleReferences$Array.getVolatile
          9.8% kyo.proto.Safepoint.home
          6.5% java.lang.invoke.DirectMethodHandle$Holder.newInvokeSpecial
          5.4% kyo.kernel.bench.YetAnotherProtoBench.kyo$kernel$bench$YetAnotherProtoBench$$_$loop$1
          2.2% java.util.concurrent.atomic.AtomicReferenceArray.get
  🔴 `continuationBodiesFuse` +11.4%
    control  kernel  18.7%  benchmark   8.8%  other  72.5%
         49.5% scala.runtime.BoxesRunTime.boxToInteger
         14.3% java.lang.invoke.VarHandleReferences$Array.getVolatile
          7.7% kyo.kernel.internal.Eval$.dispatch$1
          6.6% kyo.kernel.bench.ProtoKernelBench.kyo$kernel$bench$ProtoKernelBench$$_$run$56
          6.6% kyo.kernel.internal.Safepoint.get
          3.3% kyo.kernel.internal.Eval$.loop
    variant  kernel  27.6%  benchmark  10.3%  other  62.1%
         52.9% scala.runtime.BoxesRunTime.boxToInteger
          8.0% kyo.proto.Safepoint.home
          5.7% java.lang.invoke.DirectMethodHandle$Holder.newInvokeSpecial
          5.7% kyo.proto.Eval$.dispatch
          4.6% kyo.kernel.bench.YetAnotherProtoBench$.ask
          4.6% kyo.proto.Eval$.step
  ⚪ `deepRecursionPaysRescuesOnly` +13.0%
    control  kernel   8.3%  benchmark  83.3%  other   8.3%
         45.8% kyo.kernel.bench.ProtoKernelBench.arrow$35
         31.3% kyo.kernel.bench.ProtoKernelBench.kyo$kernel$bench$ProtoKernelBench$$_$run$35
          6.3% kyo.kernel.bench.ProtoKernelBench.loop$4
          5.2% kyo.kernel.internal.Safepoint.home
          4.2% scala.runtime.BoxesRunTime.boxToInteger
          3.1% java.lang.invoke.VarHandleReferences$Array.getVolatile
    variant  kernel   8.2%  benchmark  51.8%  other  40.0%
         35.3% scala.runtime.BoxesRunTime.boxToInteger
         17.6% kyo.kernel.bench.YetAnotherProtoBench$$anon$35.apply
         12.9% kyo.kernel.bench.YetAnotherProtoBench$$anon$35.<init>
         11.8% kyo.kernel.bench.YetAnotherProtoBench$$anon$35.apply$$anonfun$70
          9.4% kyo.kernel.bench.YetAnotherProtoBench.kyo$kernel$bench$YetAnotherProtoBench$$_$loop$4
          4.7% kyo.proto.Safepoint.enter
  🔴 `fusionPastBudgetPaysRescuesOnly` +29.2%
    control  kernel   1.0%  benchmark  30.2%  other  68.8%
         26.0% kyo.Arrow$Transform.chain
         21.9% kyo.Arrow.chain
         18.8% scala.runtime.BoxesRunTime.boxToInteger
          4.2% kyo.kernel.bench.ProtoKernelBench.kyo$kernel$bench$ProtoKernelBench$$_$_$_$_$_$_$_$_$_$_$_$run$13
          4.2% kyo.kernel.bench.ProtoKernelBench.kyo$kernel$bench$ProtoKernelBench$$_$run$23
          3.1% kyo.kernel.bench.ProtoKernelBench.arrow$18
    variant  kernel   8.2%  benchmark  34.1%  other  57.6%
         45.9% scala.runtime.BoxesRunTime.boxToInteger
         15.3% kyo.kernel.bench.YetAnotherProtoBench.kyo$kernel$bench$YetAnotherProtoBench$$_$loop$2
          3.5% kyo.kernel.bench.YetAnotherProtoBench$$anon$20.<init>
          3.5% kyo.proto.Safepoint.enter
          3.5% kyo.kernel.bench.YetAnotherProtoBench$$anon$23.apply
          2.4% semaphore_wait_trap
  🔴 `idleHandlerAddsNothing` +29.4%
    control  kernel   6.3%  benchmark  33.7%  other  60.0%
         20.0% kyo.Arrow$Transform.chain
         18.9% scala.runtime.BoxesRunTime.boxToInteger
         15.8% kyo.Arrow.chain
          6.3% kyo.kernel.bench.ProtoKernelBench.kyo$kernel$bench$ProtoKernelBench$$_$run$51
          3.2% kyo.kernel.bench.ProtoKernelBench.arrow$52
          3.2% kyo.kernel.bench.ProtoKernelBench.arrow$47
    variant  kernel   5.5%  benchmark  56.0%  other  38.5%
         29.7% scala.runtime.BoxesRunTime.boxToInteger
         23.1% kyo.kernel.bench.YetAnotherProtoBench.kyo$kernel$bench$YetAnotherProtoBench$$_$loop$10
          4.4% java.lang.invoke.DirectMethodHandle$Holder.newInvokeSpecial
          4.4% kyo.kernel.bench.YetAnotherProtoBench$$anon$54.<init>
          4.4% kyo.kernel.bench.YetAnotherProtoBench$$anon$57.<init>
          4.4% kyo.kernel.bench.YetAnotherProtoBench$$anon$60.<init>
  🔴 `uncachedValuesPayBoxingOnly` +37.4%
    control  kernel   8.4%  benchmark  11.6%  other  80.0%
         46.3% scala.runtime.BoxesRunTime.boxToInteger
         27.4% java.lang.Integer.valueOf
          5.3% kyo.kernel.internal.Safepoint.exit
          4.2% kyo.kernel.bench.ProtoKernelBench.kyo$kernel$bench$ProtoKernelBench$$_$run$34
          3.2% kyo.kernel.bench.ProtoKernelBench.loop$3
          2.1% kyo.kernel.bench.ProtoKernelBench.kyo$kernel$bench$ProtoKernelBench$$_$_$_$_$_$_$_$run$28
    variant  kernel   3.2%  benchmark  44.7%  other  52.1%
         29.8% scala.runtime.BoxesRunTime.boxToInteger
         18.1% kyo.kernel.bench.YetAnotherProtoBench.kyo$kernel$bench$YetAnotherProtoBench$$_$loop$3
         12.8% java.lang.Integer.valueOf
          4.3% kyo.kernel.bench.YetAnotherProtoBench$$anon$34.apply
          3.2% kyo.proto.Safepoint.enter
          3.2% kyo.kernel.bench.YetAnotherProtoBench$$anon$30.<init>
  🔴 `suspensionFusesContinuation` +38.7%
    control  kernel  12.9%  benchmark  17.2%  other  69.9%
         65.6% scala.runtime.BoxesRunTime.boxToInteger
         17.2% kyo.kernel.bench.ProtoKernelBench$$anon$39.cont
          7.5% kyo.kernel.internal.Eval$.loop
          3.2% kyo.kernel.internal.Stack.find
          2.2% __psynch_cvwait
          2.2% semaphore_wait_trap
    variant  kernel  35.6%  benchmark  23.0%  other  41.4%
         36.8% scala.runtime.BoxesRunTime.boxToInteger
         18.4% kyo.proto.Eval$.dispatch
         17.2% kyo.kernel.bench.YetAnotherProtoBench.kyo$kernel$bench$YetAnotherProtoBench$$_$loop$6
         10.3% kyo.proto.Eval$.go$1
          6.9% kyo.proto.Eval$.step
          3.4% kyo.kernel.bench.YetAnotherProtoBench$$anon$39.<init>
  🔴 `nestedPayloadsUnwrapInMaps` +39.6%
    control  kernel  15.2%  benchmark  57.6%  other  27.2%
         26.1% kyo.kernel.bench.ProtoKernelBench.loop$9
         18.5% scala.runtime.BoxesRunTime.boxToInteger
         15.2% kyo.kernel.bench.ProtoKernelBench.kyo$kernel$bench$ProtoKernelBench$$_$run$39
         10.9% kyo.kernel.bench.ProtoKernelBench$.ask
          6.5% kyo.kernel.internal.Safepoint.home
          5.4% kyo.kernel.bench.ProtoKernelBench$$anon$95.<init>
    variant  kernel  12.5%  benchmark  46.9%  other  40.6%
         32.3% scala.runtime.BoxesRunTime.boxToInteger
         16.7% kyo.kernel.bench.YetAnotherProtoBench.kyo$kernel$bench$YetAnotherProtoBench$$_$loop$8
         13.5% kyo.kernel.bench.YetAnotherProtoBench$.ask
          5.2% kyo.kernel.bench.YetAnotherProtoBench$$anon$47.<init>
          5.2% kyo.proto.Pending$package$$less$.lift
          4.2% kyo.kernel.bench.YetAnotherProtoBench$$anon$47.apply$$anonfun$78
  🔴 `statefulAnswersPaySuccessor` +82.2%
    control  kernel  37.0%  benchmark  13.0%  other  50.0%
         40.2% scala.runtime.BoxesRunTime.boxToInteger
         31.5% kyo.kernel.internal.Eval$.dispatch$1
          7.6% kyo.kernel.bench.ProtoKernelBench.arrow$41
          3.3% kyo.kernel.bench.ProtoKernelBench$.ask
          2.2% java.lang.Integer.valueOf
          2.2% kyo.kernel.bench.ProtoKernelBench$$anon$53.handler
    variant  kernel  37.4%  benchmark   5.5%  other  57.1%
         35.2% scala.runtime.BoxesRunTime.boxToInteger
         13.2% java.lang.Integer.valueOf
         11.0% kyo.proto.Stack.push
          6.6% kyo.proto.Eval$.dispatch
          5.5% kyo.proto.Eval$.go$1
          4.4% kyo.proto.Eval$.step
  🔴 `suspensionBaseline` +120.5%
    control  kernel  29.5%  benchmark  13.7%  other  56.8%
         35.8% scala.runtime.BoxesRunTime.boxToInteger
         10.5% kyo.kernel.internal.Eval$.loop
          9.5% kyo.kernel.internal.Eval$.dispatch$1
          6.3% kyo.kernel.internal.Stack.loop$1
          5.3% kyo.Arrow$Suspend.chain
          4.2% kyo.kernel.bench.ProtoKernelBench$$anon$95.<init>
    variant  kernel  27.9%  benchmark  20.9%  other  51.2%
         51.2% scala.runtime.BoxesRunTime.boxToInteger
         15.1% kyo.kernel.bench.YetAnotherProtoBench$.ask
          8.1% kyo.proto.Eval$.step
          7.0% kyo.proto.Eval$.dispatch
          4.7% kyo.proto.Eval$.go$1
          2.3% kyo.kernel.bench.YetAnotherProtoBench$$anon$36.<init>
  🔴 `handleLoopAnswersInPlace` +143.9%
    control  kernel  40.7%  benchmark  12.1%  other  47.3%
         35.2% scala.runtime.BoxesRunTime.boxToInteger
         19.8% kyo.kernel.internal.Eval$.dispatch$1
          9.9% kyo.kernel.internal.Eval$.loop
          5.5% kyo.kernel.bench.ProtoKernelBench.arrow$37
          5.5% kyo.kernel.internal.Stack.loop$1
          4.4% kyo.kernel.bench.ProtoKernelBench$$anon$42.<init>
    variant  kernel  18.4%  benchmark   6.9%  other  74.7%
         71.3% scala.runtime.BoxesRunTime.boxToInteger
          5.7% kyo.proto.Eval$.go$1
          4.6% kyo.proto.Eval$.dispatch
          4.6% kyo.proto.Stack.loop$2
          2.3% java.lang.invoke.DirectMethodHandle$Holder.newInvokeSpecial
          2.3% kyo.kernel.bench.YetAnotherProtoBench$$anon$43.<init>
  🔴 `trailingMapsStayLinear` +214400.1%
    control  kernel  13.7%  benchmark  32.6%  other  53.7%
         22.1% scala.runtime.BoxesRunTime.boxToInteger
         10.5% kyo.kernel.internal.Eval$.dispatch$1
         10.5% kyo.kernel.bench.ProtoKernelBench.kyo$kernel$bench$ProtoKernelBench$$_$run$53
          8.4% kyo.kernel.bench.ProtoKernelBench.arrow$54
          7.4% kyo.Arrow$SuspendWith.chain
          5.3% kyo.Arrow$Transform.chain
    variant  kernel  63.8%  benchmark   2.2%  other  34.1%
         26.1% scala.runtime.BoxesRunTime.boxToInteger
         19.6% kyo.proto.Arrow$Chain$.apply
          9.4% kyo.proto.Arrow$Chain.b
          7.2% kyo.proto.Arrow$Chain.apply
          5.1% kyo.proto.Kyo$Defer$.apply
          5.1% kyo.proto.Stack.push

Next experiments, each one run of a configuration this harness already issues:
  1. emittingClausesPayRegionRebuild rides scalar replacement
      re-run control with -XX:-EliminateAllocations
      if disabling escape analysis on the control reproduces the variant's -64064 B/op, the variant destroyed a scalar replacement rather than adding an allocation
  2. deepRecursionPaysRescuesOnly rides scalar replacement
      re-run control with -XX:-EliminateAllocations
      if disabling escape analysis on the control reproduces the variant's +239592 B/op, the variant destroyed a scalar replacement rather than adding an allocation
  3. fusionPastBudgetPaysRescuesOnly rides scalar replacement
      re-run control with -XX:-EliminateAllocations
      if disabling escape analysis on the control reproduces the variant's +184024 B/op, the variant destroyed a scalar replacement rather than adding an allocation
  4. idleHandlerAddsNothing rides scalar replacement
      re-run control with -XX:-EliminateAllocations
      if disabling escape analysis on the control reproduces the variant's +184024 B/op, the variant destroyed a scalar replacement rather than adding an allocation
  5. uncachedValuesPayBoxingOnly rides scalar replacement
      re-run control with -XX:-EliminateAllocations
      if disabling escape analysis on the control reproduces the variant's +184032 B/op, the variant destroyed a scalar replacement rather than adding an allocation
  6. suspensionFusesContinuation rides scalar replacement
      re-run control with -XX:-EliminateAllocations
      if disabling escape analysis on the control reproduces the variant's +240024 B/op, the variant destroyed a scalar replacement rather than adding an allocation
  7. nestedPayloadsUnwrapInMaps rides scalar replacement
      re-run control with -XX:-EliminateAllocations
      if disabling escape analysis on the control reproduces the variant's +24000 B/op, the variant destroyed a scalar replacement rather than adding an allocation
  8. trailingMapsStayLinear rides scalar replacement
      re-run control with -XX:-EliminateAllocations
      if disabling escape analysis on the control reproduces the variant's +2399362298 B/op, the variant destroyed a scalar replacement rather than adding an allocation
  A falsifier whose flag did not take refutes nothing; the run reports that as inconclusive rather than as evidence against the hypothesis.

package kyo.kernel.bench.cross

import java.util.concurrent.TimeUnit
import org.openjdk.jmh.annotations.*
import scala.util.control.NoStackTrace
import zio.{Scope as _, *}

/** ZIO port of the KernelBench rows for the cross-library boards. Row names match KernelBench's so
  * result tables join by name.
  *
  * Run entry: `runtime.unsafe.run` on a shared runtime allocates a FiberRuntime and its FiberRefs and
  * runs the computation on the calling thread. The runtime has cooperative yielding disabled: with it
  * on, a run longer than 10240 operations is preempted, resumed on the ZIO executor and joined by the
  * parked caller, a thread handoff kyo's `eval` never pays. The one row that measures the preemptible
  * run, partialSuspensionBaseline, uses `Runtime.default` with the flag on. The entry cost is
  * measured, not factored out; entryFloorBatch isolates it.
  *
  * Tier B substitution: kyo's Ask suspension becomes a `FiberRef.get`, ZIO's ambient-value
  * substrate, with the answer as the FiberRef's initial value so no per-run install is paid; the
  * stateful row uses `FiberRef.modify`. A kyo region installed for an extent becomes `locally`, the
  * scoped install with its restore. Rows suffixed `Alt` record the other spellings (`ZIO.service`
  * over a ZEnvironment, the per-run `locally` install, a shared `Ref`) and stay out of the headline
  * tables.
  *
  * Rows with no ZIO counterpart are absent rather than approximated: a continuation captured and
  * resumed by a handler (foreignCrossingsPayRotation, handleContResumesOnce, handleContResumesTwice,
  * handleFirstPeelsRemainder), a handler clause that performs another effect
  * (emittingClausesPayRegionRebuild), a suspension with its continuation fused into the node
  * (suspensionFusesContinuation: `FiberRef.getWith` is `get.flatMap`), a region with the following
  * map fused into it (handleLoopFusesContinuation), masking (maskTunnelsPastInnerHandler) and the
  * isolate state crossing without a fiber (isolateCrossingPerRound).
  */
@State(org.openjdk.jmh.annotations.Scope.Benchmark)
@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 2)
class ZioBench:

    import ZioBench.*

    private var seed = 1

    /** Built once so fusionAfterSuspensionRunOnly times only the answer, not the chain. */
    private val accumulatedChain: UIO[Int] =
        ask.get.map(a => a & 63)
            .map(v => (v + 1) & 63).map(v => (v + 1) & 63).map(v => (v + 1) & 63)
            .map(v => (v + 1) & 63).map(v => (v + 1) & 63).map(v => (v + 1) & 63)
            .map(v => (v + 1) & 63).map(v => (v + 1) & 63).map(v => (v + 1) & 63)
            .map(v => (v + 1) & 63).map(v => (v + 1) & 63).map(v => (v + 1) & 63)
            .map(v => (v + 1) & 63).map(v => (v + 1) & 63).map(v => (v + 1) & 63)
            .map(v => (v + 1) & 63).map(v => (v + 1) & 63).map(v => (v + 1) & 63)
            .map(v => (v + 1) & 63).map(v => (v + 1) & 63).map(v => (v + 1) & 63)
            .map(v => (v + 1) & 63).map(v => (v + 1) & 63).map(v => (v + 1) & 63)
            .map(v => (v + 1) & 63).map(v => (v + 1) & 63).map(v => (v + 1) & 63)
            .map(v => (v + 1) & 63).map(v => (v + 1) & 63).map(v => (v + 1) & 63)
            .map(v => (v + 1) & 63).map(v => (v + 1) & 63).map(v => (v + 1) & 63)
            .map(v => (v + 1) & 63).map(v => (v + 1) & 63).map(v => (v + 1) & 63)
            .map(v => (v + 1) & 63).map(v => (v + 1) & 63).map(v => (v + 1) & 63)
            .map(v => (v + 1) & 63).map(v => (v + 1) & 63).map(v => (v + 1) & 63)
            .map(v => (v + 1) & 63).map(v => (v + 1) & 63).map(v => (v + 1) & 63)
            .map(v => (v + 1) & 63).map(v => (v + 1) & 63).map(v => (v + 1) & 63)
            .map(v => (v + 1) & 63).map(v => (v + 1) & 63)

    @Benchmark
    @OperationsPerInvocation(1000)
    def evalFixedOverheadBatch: Int =
        var acc = 0
        var i   = 0
        while i < 1000 do
            acc += runSync(ZIO.succeed(seed + i).map(_ + 1))
            i += 1
        acc
    end evalFixedOverheadBatch

    /** A settled value through the run entry with no transformation, so the fixed per-run cost is
      * visible in the same units as every other row.
      */
    @Benchmark
    @OperationsPerInvocation(1000)
    def entryFloorBatch: Int =
        var acc = 0
        var i   = 0
        while i < 1000 do
            acc += runSync(ZIO.succeed(seed + i))
            i += 1
        acc
    end entryFloorBatch

    @Benchmark
    def fusionAllocatesNothing: Int =
        def loop(i: Int, acc: Int): UIO[Int] =
            if i > FusedDepth then ZIO.succeed(acc)
            else
                ZIO.succeed(acc & 63)
                    .map(v => (v + 1) & 63).map(v => (v + 1) & 63).map(v => (v + 1) & 63)
                    .map(v => (v + 1) & 63).map(v => (v + 1) & 63).map(v => (v + 1) & 63)
                    .map(v => (v + 1) & 63).map(v => (v + 1) & 63).map(v => (v + 1) & 63)
                    .map(v => (v + 1) & 63)
                    .flatMap(v => loop(i + 1, v))
        runSync(loop(0, seed))
    end fusionAllocatesNothing

    @Benchmark
    def fusionPastBudgetPaysRescuesOnly: Int =
        def loop(i: Int, acc: Int): UIO[Int] =
            if i > NarrowDepth then ZIO.succeed(acc)
            else
                ZIO.succeed(acc & 63)
                    .map(v => (v + 1) & 63).map(v => (v + 1) & 63).map(v => (v + 1) & 63)
                    .map(v => (v + 1) & 63).map(v => (v + 1) & 63).map(v => (v + 1) & 63)
                    .map(v => (v + 1) & 63).map(v => (v + 1) & 63).map(v => (v + 1) & 63)
                    .map(v => (v + 1) & 63)
                    .flatMap(v => loop(i + 1, v))
        runSync(loop(0, seed))
    end fusionPastBudgetPaysRescuesOnly

    @Benchmark
    def uncachedValuesPayBoxingOnly: Int =
        def loop(i: Int): UIO[Int] =
            if i > NarrowDepth then ZIO.succeed(i)
            else
                ZIO.succeed(i + 11)
                    .map(_ - 1).map(_ - 1).map(_ - 1).map(_ - 1).map(_ - 1)
                    .map(_ - 1).map(_ - 1).map(_ - 1).map(_ - 1).map(_ - 1)
                    .flatMap(loop)
        runSync(loop(seed - 1))
    end uncachedValuesPayBoxingOnly

    @Benchmark
    def userTypesSkipKernelWrapping: Int =
        def loop(b: Box): UIO[Box] =
            if b.value > NarrowDepth then ZIO.succeed(b)
            else
                ZIO.succeed(Box(b.value + 11))
                    .map(b => Box(b.value - 1)).map(b => Box(b.value - 1)).map(b => Box(b.value - 1))
                    .map(b => Box(b.value - 1)).map(b => Box(b.value - 1)).map(b => Box(b.value - 1))
                    .map(b => Box(b.value - 1)).map(b => Box(b.value - 1)).map(b => Box(b.value - 1))
                    .map(b => Box(b.value - 1))
                    .flatMap(loop)
        runSync(loop(Box(seed - 1))).value
    end userTypesSkipKernelWrapping

    @Benchmark
    def deepRecursionPaysRescuesOnly: Int =
        def loop(i: Int): UIO[Int] =
            ZIO.unit.flatMap { _ =>
                if i > Depth then ZIO.succeed(i) else loop(i + 1)
            }
        runSync(loop(seed - 1))
    end deepRecursionPaysRescuesOnly

    @Benchmark
    def suspensionBaseline: Int =
        def loop(i: Int): UIO[Int] =
            if i > Depth then ZIO.succeed(i)
            else ask.get.flatMap(a => loop(i + a))
        runSync(loop(seed - 1))
    end suspensionBaseline

    /** Recorded alternative: `ZIO.service` under `provideEnvironment`, the same FiberRef read plus
      * a ZEnvironment dictionary lookup.
      */
    @Benchmark
    def suspensionBaselineAltEnv: Int =
        def loop(i: Int): ZIO[Int, Nothing, Int] =
            if i > Depth then ZIO.succeed(i)
            else ZIO.service[Int].flatMap(a => loop(i + a))
        runSync(loop(seed - 1).provideEnvironment(env))
    end suspensionBaselineAltEnv

    /** Recorded alternative: the per-run `locally` install, a full uninterruptible bracket. */
    @Benchmark
    def suspensionBaselineAltInstall: Int =
        def loop(i: Int): UIO[Int] =
            if i > Depth then ZIO.succeed(i)
            else ask.get.flatMap(a => loop(i + a))
        runSync(ask.locally(1)(loop(seed - 1)))
    end suspensionBaselineAltInstall

    /** Sixteen call sites, so the read's continuation is a different class at each. */
    @Benchmark
    def sharedHandlerPaysDispatch: Int =
        def s0(i: Int): UIO[Int]  = if i > Depth then ZIO.succeed(i) else ask.get.flatMap(a => s1(i + a))
        def s1(i: Int): UIO[Int]  = ask.get.flatMap(a => s2(i + a))
        def s2(i: Int): UIO[Int]  = ask.get.flatMap(a => s3(i + a))
        def s3(i: Int): UIO[Int]  = ask.get.flatMap(a => s4(i + a))
        def s4(i: Int): UIO[Int]  = ask.get.flatMap(a => s5(i + a))
        def s5(i: Int): UIO[Int]  = ask.get.flatMap(a => s6(i + a))
        def s6(i: Int): UIO[Int]  = ask.get.flatMap(a => s7(i + a))
        def s7(i: Int): UIO[Int]  = ask.get.flatMap(a => s8(i + a))
        def s8(i: Int): UIO[Int]  = ask.get.flatMap(a => s9(i + a))
        def s9(i: Int): UIO[Int]  = ask.get.flatMap(a => s10(i + a))
        def s10(i: Int): UIO[Int] = ask.get.flatMap(a => s11(i + a))
        def s11(i: Int): UIO[Int] = ask.get.flatMap(a => s12(i + a))
        def s12(i: Int): UIO[Int] = ask.get.flatMap(a => s13(i + a))
        def s13(i: Int): UIO[Int] = ask.get.flatMap(a => s14(i + a))
        def s14(i: Int): UIO[Int] = ask.get.flatMap(a => s15(i + a))
        def s15(i: Int): UIO[Int] = ask.get.flatMap(a => s0(i + a))
        runSync(s0(seed - 1))
    end sharedHandlerPaysDispatch

    @Benchmark
    def continuationBodiesFuse: Int =
        def loop(i: Int, acc: Int): UIO[Int] =
            if i > NarrowDepth then ZIO.succeed(acc)
            else
                ask.get.flatMap { a =>
                    ZIO.succeed((acc + a) & 63)
                        .map(v => (v + 1) & 63).map(v => (v + 1) & 63).map(v => (v + 1) & 63)
                        .map(v => (v + 1) & 63).map(v => (v + 1) & 63).map(v => (v + 1) & 63)
                        .map(v => (v + 1) & 63).map(v => (v + 1) & 63).map(v => (v + 1) & 63)
                        .map(v => (v + 1) & 63)
                        .flatMap(v => loop(i + 1, v))
                }
        runSync(loop(0, seed))
    end continuationBodiesFuse

    @Benchmark
    def fusionAfterSuspensionRunOnly: Int =
        runSync(accumulatedChain)

    @Benchmark
    def fusionAfterSuspension: Int =
        def loop(i: Int, acc: Int): UIO[Int] =
            if i > NarrowDepth then ZIO.succeed(acc)
            else
                ask.get
                    .map(a => (acc + a) & 63)
                    .map(v => (v + 1) & 63).map(v => (v + 1) & 63).map(v => (v + 1) & 63)
                    .map(v => (v + 1) & 63).map(v => (v + 1) & 63).map(v => (v + 1) & 63)
                    .map(v => (v + 1) & 63).map(v => (v + 1) & 63).map(v => (v + 1) & 63)
                    .flatMap(v => loop(i + 1, v))
        runSync(loop(0, seed))
    end fusionAfterSuspension

    /** The one row where the per-run install is the substance: the ambient is installed for the
      * extent but never read.
      */
    @Benchmark
    def idleHandlerAddsNothing: Int =
        def loop(i: Int, acc: Int): UIO[Int] =
            if i > NarrowDepth then ZIO.succeed(acc)
            else
                ZIO.succeed(acc & 63)
                    .map(v => (v + 1) & 63).map(v => (v + 1) & 63).map(v => (v + 1) & 63)
                    .map(v => (v + 1) & 63).map(v => (v + 1) & 63).map(v => (v + 1) & 63)
                    .map(v => (v + 1) & 63).map(v => (v + 1) & 63).map(v => (v + 1) & 63)
                    .map(v => (v + 1) & 63)
                    .flatMap(v => loop(i + 1, v))
        runSync(ask.locally(1)(loop(0, seed)))
    end idleHandlerAddsNothing

    @Benchmark
    def statefulAnswersPaySuccessor: Int =
        def loop(i: Int): UIO[Int] =
            if i > Depth then ZIO.succeed(i)
            else st.modify(s => (1, s + 1)).flatMap(a => loop(i + a))
        runSync(loop(seed - 1))
    end statefulAnswersPaySuccessor

    @Benchmark
    def trailingMapsStayLinear: Int =
        def loop(i: Int): UIO[Int] =
            if i > Depth then ZIO.succeed(i)
            else ask.get.flatMap(a => loop(i + a)).map(x => x)
        runSync(loop(seed - 1))
    end trailingMapsStayLinear

    /** NarrowDepth map links attached in a runtime loop, then one run. ZIO reifies a Mapped node
      * per link, so the row measures node build plus the interpreter over those nodes. Shape from
      * zio-blocks' AsyncChainBench.
      */
    @Benchmark
    def dynamicChainOfMapsStaysLinear: Int =
        var fa: UIO[Int] = ZIO.succeed(seed)
        var i            = 0
        while i < NarrowDepth do
            fa = fa.map(_ + 1)
            i += 1
        runSync(fa)
    end dynamicChainOfMapsStaysLinear

    /** The bind spelling of dynamicChainOfMapsStaysLinear: a FlatMap node per link. */
    @Benchmark
    def dynamicChainOfBindsStaysLinear: Int =
        var fa: UIO[Int] = ZIO.succeed(seed)
        var i            = 0
        while i < NarrowDepth do
            fa = fa.flatMap(v => ZIO.succeed(v + 1))
            i += 1
        runSync(fa)
    end dynamicChainOfBindsStaysLinear

    /** Recursion shallow enough to stay within one of kyo's safepoint periods. */
    @Benchmark
    def deepRecursionNoRescue: Int =
        def loop(i: Int): UIO[Int] =
            ZIO.unit.flatMap(_ => if i > ShallowDepth then ZIO.succeed(i) else loop(i + 1))
        runSync(loop(seed - 1))
    end deepRecursionNoRescue

    /** Recursion deep enough to cross one of kyo's safepoint periods once. */
    @Benchmark
    def deepRecursionOneRescue: Int =
        def loop(i: Int): UIO[Int] =
            ZIO.unit.flatMap(_ => if i > OneRescueDepth then ZIO.succeed(i) else loop(i + 1))
        runSync(loop(seed - 1))
    end deepRecursionOneRescue

    /** Iteration driven by open recursion through a method. */
    @Benchmark
    def pureIterationViaMethod: Int =
        def loop(i: Int): UIO[Int] =
            if i > Depth then ZIO.succeed(i) else ZIO.succeed(i + 1).flatMap(loop)
        runSync(loop(seed - 1))
    end pureIterationViaMethod

    /** A deferral reified per step, so each round costs one suspension node. */
    @Benchmark
    def deferBindPerStep: Int =
        def loop(i: Int): UIO[Int] =
            if i > NarrowDepth then ZIO.succeed(i) else ZIO.suspendSucceed(ZIO.succeed(i + 1)).flatMap(loop)
        runSync(loop(seed - 1))
    end deferBindPerStep

    /** The deferral loop with one transformation composed after it, so the tail is rebuilt. */
    @Benchmark
    def deferBindUnderTrailingMap: Int =
        def loop(i: Int): UIO[Int] =
            if i > NarrowDepth then ZIO.succeed(i) else ZIO.suspendSucceed(ZIO.succeed(i + 1)).flatMap(loop)
        runSync(loop(seed - 1).map(x => x))
    end deferBindUnderTrailingMap

    /** The deferral loop under a binding nothing reads, isolating the cost of the region itself. */
    @Benchmark
    def deferBindUnderIdleHandler: Int =
        def loop(i: Int): UIO[Int] =
            if i > NarrowDepth then ZIO.succeed(i) else ZIO.suspendSucceed(ZIO.succeed(i + 1)).flatMap(loop)
        runSync(ask.locally(1)(loop(seed - 1)))
    end deferBindUnderIdleHandler

    /** Iteration expressed through the library's loop combinator rather than open recursion. */
    @Benchmark
    def pureIterationViaLoop: Int =
        runSync(ZIO.iterate(seed - 1)(_ <= Depth)(i => ZIO.succeed(i + 1)))
    end pureIterationViaLoop

    /** Iteration through a step held as a value, so the loop body outlives the expression that built it. */
    @Benchmark
    def pureIterationViaArrow: Int =
        lazy val step: Int => UIO[Int] = i =>
            if i > Depth then ZIO.succeed(i) else ZIO.succeed(i + 1).flatMap(v => step(v))
        runSync(step(seed - 1))
    end pureIterationViaArrow

    /** Iteration whose every round performs an operation, through the library's loop combinator. */
    @Benchmark
    def effectfulIterationViaLoop: Int =
        runSync(ZIO.iterate(seed - 1)(_ <= Depth)(i => ask.get.map(a => i + a)))
    end effectfulIterationViaLoop

    /** Iteration whose every round performs an operation, driven by a step held as a value. */
    @Benchmark
    def effectfulIterationViaArrow: Int =
        lazy val step: Int => UIO[Int] = i =>
            if i > Depth then ZIO.succeed(i) else ask.get.flatMap(a => step(i + a))
        runSync(step(seed - 1))
    end effectfulIterationViaArrow

    /** A transformation chain wide enough to reach the compiler's expansion limit, run shallow. */
    @Benchmark
    def inlineLimitKeepsZeroAllocation: Int =
        def loop(i: Int, acc: Int): UIO[Int] =
            if i > FusedWideDepth then ZIO.succeed(acc)
            else
                ZIO.succeed(acc & 63)
                    .map(v => (v + 1) & 63).map(v => (v + 1) & 63).map(v => (v + 1) & 63).map(v => (v + 1) & 63).map(v => (v + 1) & 63)
                    .map(v => (v + 1) & 63).map(v => (v + 1) & 63).map(v => (v + 1) & 63).map(v => (v + 1) & 63).map(v => (v + 1) & 63)
                    .map(v => (v + 1) & 63).map(v => (v + 1) & 63).map(v => (v + 1) & 63).map(v => (v + 1) & 63).map(v => (v + 1) & 63)
                    .map(v => (v + 1) & 63).map(v => (v + 1) & 63).map(v => (v + 1) & 63).map(v => (v + 1) & 63).map(v => (v + 1) & 63)
                    .map(v => (v + 1) & 63).map(v => (v + 1) & 63).map(v => (v + 1) & 63).map(v => (v + 1) & 63).map(v => (v + 1) & 63)
                    .map(v => (v + 1) & 63).map(v => (v + 1) & 63).map(v => (v + 1) & 63).map(v => (v + 1) & 63).map(v => (v + 1) & 63)
                    .map(v => (v + 1) & 63).map(v => (v + 1) & 63).map(v => (v + 1) & 63).map(v => (v + 1) & 63).map(v => (v + 1) & 63)
                    .map(v => (v + 1) & 63).map(v => (v + 1) & 63).map(v => (v + 1) & 63).map(v => (v + 1) & 63).map(v => (v + 1) & 63)
                    .map(v => (v + 1) & 63).map(v => (v + 1) & 63).map(v => (v + 1) & 63).map(v => (v + 1) & 63).map(v => (v + 1) & 63)
                    .map(v => (v + 1) & 63).map(v => (v + 1) & 63).map(v => (v + 1) & 63).map(v => (v + 1) & 63).map(v => (v + 1) & 63)
                    .flatMap(v => loop(i + 1, v))
        runSync(loop(0, seed))
    end inlineLimitKeepsZeroAllocation

    /** A narrower chain run deep, so the cost shows in time rather than in expansion. */
    @Benchmark
    def inlineLimitCostsTimeNotAllocation: Int =
        def loop(i: Int): UIO[Int] =
            if i > NarrowDepth then ZIO.succeed(i)
            else
                ZIO.succeed(i + 51)
                    .map(_ - 1).map(_ - 1).map(_ - 1).map(_ - 1).map(_ - 1)
                    .map(_ - 1).map(_ - 1).map(_ - 1).map(_ - 1).map(_ - 1)
                    .map(_ - 1).map(_ - 1).map(_ - 1).map(_ - 1).map(_ - 1)
                    .map(_ - 1).map(_ - 1).map(_ - 1).map(_ - 1).map(_ - 1)
                    .map(_ - 1).map(_ - 1).map(_ - 1).map(_ - 1).map(_ - 1)
                    .map(_ - 1).map(_ - 1).map(_ - 1).map(_ - 1).map(_ - 1)
                    .map(_ - 1).map(_ - 1).map(_ - 1).map(_ - 1).map(_ - 1)
                    .map(_ - 1).map(_ - 1).map(_ - 1).map(_ - 1).map(_ - 1)
                    .map(_ - 1).map(_ - 1).map(_ - 1).map(_ - 1).map(_ - 1)
                    .map(_ - 1).map(_ - 1).map(_ - 1).map(_ - 1).map(_ - 1)
                    .flatMap(loop)
        runSync(loop(seed - 1))
    end inlineLimitCostsTimeNotAllocation

    /** A suspension carried as a payload through a bind and never run, as kyo boxes a pending value
      * it carries as data; the payload is a fresh read node per round, as kyo's is a fresh suspension.
      */
    @Benchmark
    def nestedPayloadsUnwrapInMaps: Int =
        def loop(i: Int, acc: Int): UIO[Int] =
            if i > NarrowDepth then ZIO.succeed(acc)
            else ZIO.succeed(ask.get).flatMap(_ => loop(i + 1, acc + i))
        runSync(loop(0, seed))
    end nestedPayloadsUnwrapInMaps

    /** The cost of one entry, too small to resolve alone; the batch row measures it. */
    @Benchmark
    def evalFixedOverhead: Int =
        runSync(ZIO.succeed(seed).map(_ + 1))
    end evalFixedOverhead

    /** A read of the outermost of three nested bindings, under an idle binding innermost, as in the
      * kyo row; a FiberRef read is a map lookup, so the nesting is carried for shape, not cost.
      */
    @Benchmark
    def contextReadsUnderBindings: Int =
        def loop(i: Int): UIO[Int] =
            if i > NarrowDepth then ZIO.succeed(i) else cfg3.get.flatMap(c => loop(i + c))
        runSync(cfg3.locally(1)(cfg2.locally(2)(cfg.locally(3)(ask.locally(1)(loop(seed - 1))))))
    end contextReadsUnderBindings

    /** A binding installed and torn down once per round, so the round pays entry and exit. */
    @Benchmark
    def contextRegionsPayEntryExit: Int =
        def loop(i: Int): UIO[Int] =
            if i > NarrowDepth then ZIO.succeed(i)
            else cfg.locally(1)(cfg.get).flatMap(c => loop(i + c))
        runSync(loop(seed - 1))
    end contextRegionsPayEntryExit

    /** A binding installed per round that derives its value from the enclosing one. */
    @Benchmark
    def contextRegionsDeriveFromOuter: Int =
        def loop(i: Int): UIO[Int] =
            if i > NarrowDepth then ZIO.succeed(i)
            else cfg.locallyWith(_ + 1)(cfg.get).flatMap(c => loop(i + c))
        runSync(cfg.locally(0)(loop(seed - 1)))
    end contextRegionsDeriveFromOuter

    /** Every occurrence answered where it stands, without the remainder being handed over. */
    @Benchmark
    def handleLoopAnswersInPlace: Int =
        def loop(i: Int): UIO[Int] =
            if i > Depth then ZIO.succeed(i) else ask.get.flatMap(a => loop(i + a))
        runSync(loop(seed - 1))
    end handleLoopAnswersInPlace

    /** Two bindings of one ref nested, the inner shadowing the outer for every read. */
    @Benchmark
    def sameTagInnerHandlerAnswers: Int =
        def loop(i: Int): UIO[Int] =
            if i > Depth then ZIO.succeed(i) else ask.get.flatMap(a => loop(i + a))
        runSync(ask.locally(0)(ask.locally(1)(loop(seed - 1))))
    end sameTagInnerHandlerAnswers

    /** Two operations interleaved, the inner one answered without displacing the outer. */
    @Benchmark
    def foreignCrossingsAnsweredInPlace: Int =
        def loop(i: Int): UIO[Int] =
            if i > Depth then ZIO.succeed(i)
            else ask.get.flatMap(a => ask2.get.flatMap(t => loop(i + a + t)))
        runSync(loop(seed - 1))
    end foreignCrossingsAnsweredInPlace

    /** The Depth loop on the default runtime, whose cooperative yielding preempts the fiber every
      * 10240 operations and resumes it on the ZIO executor while the caller parks: ZIO's preemptible
      * run, against the non-yielding runtime every other row uses. Kyo's armed slice polls and, with
      * no stop requested, never parks, so this row carries the handoffs kyo's does not.
      */
    @Benchmark
    def partialSuspensionBaseline: Int =
        def loop(i: Int): UIO[Int] =
            if i > Depth then ZIO.succeed(i) else ask.get.flatMap(a => loop(i + a))
        runYielding(loop(seed - 1))
    end partialSuspensionBaseline

    /** A typed failure raised under a bracket and a binding, unwinding both to the catch outside. */
    @Benchmark
    def abortUnwindsThroughRegions: Int =
        def loop(i: Int, acc: Int): UIO[Int] =
            if i > NarrowDepth then ZIO.succeed(acc)
            else
                ZIO.acquireReleaseWith(ZIO.unit)(_ => ZIO.unit)(_ => cfg.locally(acc)(ZIO.fail(acc + 1)))
                    .catchAll(e => ZIO.succeed(e))
                    .flatMap(v => loop(i + 1, v))
        runSync(loop(0, seed))
    end abortUnwindsThroughRegions

    /** A throw raised after a read and recovered by the catch around it. */
    @Benchmark
    def recoverAnswersThrow: Int =
        def loop(i: Int, acc: Int): UIO[Int] =
            if i > NarrowDepth then ZIO.succeed(acc)
            else
                ask.get.flatMap(a => ZIO.attempt(if a > 0 then throw Boom else a))
                    .catchAll(_ => ZIO.succeed(acc + 1))
                    .flatMap(v => loop(i + 1, v))
        runSync(loop(0, seed))
    end recoverAnswersThrow

    /** Recorded alternative: the successor kept in a shared `Ref`, an atomic CAS per answer. */
    @Benchmark
    def statefulAnswersPaySuccessorAltRef: Int =
        def loop(i: Int): UIO[Int] =
            if i > Depth then ZIO.succeed(i) else stRef.modify(s => (1, s + 1)).flatMap(a => loop(i + a))
        runSync(loop(seed - 1))
    end statefulAnswersPaySuccessorAltRef

    /** A resource bound and released once per round, so the round pays a region install and discharge. */
    @Benchmark
    def bracketPerRound: Int =
        def loop(i: Int, acc: Int): UIO[Int] =
            if i > NarrowDepth then ZIO.succeed(acc)
            else
                ZIO.acquireReleaseWith(ZIO.succeed(acc))(_ => ZIO.unit)(a => ZIO.succeed(a + 1))
                    .flatMap(v => loop(i + 1, v))
        runSync(loop(0, seed))
    end bracketPerRound

    /** One resource held across the whole loop, so every step carries an outstanding region. */
    @Benchmark
    def bracketAroundLoop: Int =
        def loop(i: Int): UIO[Int] =
            if i > Depth then ZIO.succeed(i) else ZIO.succeed(i + 1).flatMap(loop)
        runSync(ZIO.acquireReleaseWith(ZIO.succeed(seed))(_ => ZIO.unit)(a => loop(a - 1)))
    end bracketAroundLoop

    /** The release-only form, which installs its region before the body is built. */
    @Benchmark
    def bracketEnsuringOnly: Int =
        def loop(i: Int): UIO[Int] =
            if i > Depth then ZIO.succeed(i) else ZIO.succeed(i + 1).flatMap(loop)
        runSync(loop(seed - 1).ensuring(ZIO.unit))
    end bracketEnsuringOnly

    /** A transformation applied to every element of a collection. */
    @Benchmark
    def foreachOverCollection: Int =
        runSync(ZIO.foreach(elements)(a => ZIO.succeed(a + seed)).map(_.sum))
    end foreachOverCollection

    /** A fold threading an accumulator through a collection. */
    @Benchmark
    def foldOverCollection: Int =
        runSync(ZIO.foldLeft(elements)(seed)((acc, a) => ZIO.succeed(acc + a)))
    end foldOverCollection

    /** A fold that keeps only part of the collection, so each element decides whether it contributes.
      * ZIO's own `collect` routes the dropped case through the failure channel, so the option-valued
      * traversal plus a flatten is the spelling that keeps the per-element decision a value; the
      * flatten is a second pure pass kyo's single-pass collect does not make.
      */
    @Benchmark
    def collectOverCollection: Int =
        runSync(ZIO.foreach(elements)(a => ZIO.succeed(if (a & 1) == 0 then Some(a) else None)).map(_.flatten.sum + seed))
    end collectOverCollection

end ZioBench

object ZioBench:

    val elements: List[Int] = (0 until NarrowDepth).toList

    inline def Depth          = 10000
    inline def NarrowDepth    = 1000
    inline def FusedDepth     = 32
    inline def FusedWideDepth = 8
    inline def ShallowDepth   = 200
    inline def OneRescueDepth = 400

    final case class Box(value: Int)

    /** One runtime for the whole class, cooperative yielding off: `unsafe.run` then runs the whole
      * computation on the calling thread, as kyo's `eval` does. Building one per operation would
      * measure runtime construction, not evaluation.
      */
    val runtime: Runtime[Any] =
        Runtime(ZEnvironment.empty, FiberRefs.empty, RuntimeFlags.disable(RuntimeFlags.default)(RuntimeFlag.CooperativeYielding))

    /** The default runtime, cooperative yielding on, for the one row that measures the preemptible run. */
    val yieldingRuntime: Runtime[Any] = Runtime.default

    /** The ambient answer: a fresh fiber reads a FiberRef's initial value, so constructing it with
      * the answer is the install.
      */
    val ask: FiberRef[Int]  = Unsafe.unsafe(implicit u => FiberRef.unsafe.make(1))
    val ask2: FiberRef[Int] = Unsafe.unsafe(implicit u => FiberRef.unsafe.make(0))
    val st: FiberRef[Int]   = Unsafe.unsafe(implicit u => FiberRef.unsafe.make(0))
    val cfg: FiberRef[Int]  = Unsafe.unsafe(implicit u => FiberRef.unsafe.make(0))
    val cfg2: FiberRef[Int] = Unsafe.unsafe(implicit u => FiberRef.unsafe.make(0))
    val cfg3: FiberRef[Int] = Unsafe.unsafe(implicit u => FiberRef.unsafe.make(0))

    /** The shared cell of statefulAnswersPaySuccessorAltRef; it accumulates across runs. */
    val stRef: Ref[Int] = Unsafe.unsafe(implicit u => Ref.unsafe.make(0))

    val env: ZEnvironment[Int] = ZEnvironment(1)

    /** The failure recoverAnswersThrow raises: allocated once and without a stack trace. */
    object Boom extends Exception with NoStackTrace

    def runSync[A](z: ZIO[Any, Nothing, A]): A =
        Unsafe.unsafe(implicit u => runtime.unsafe.run(z).getOrThrowFiberFailure())

    def runYielding[A](z: ZIO[Any, Nothing, A]): A =
        Unsafe.unsafe(implicit u => yieldingRuntime.unsafe.run(z).getOrThrowFiberFailure())

end ZioBench

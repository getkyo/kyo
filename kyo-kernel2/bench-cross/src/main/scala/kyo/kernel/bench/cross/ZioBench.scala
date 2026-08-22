package kyo.kernel.bench.cross

import java.util.concurrent.TimeUnit
import org.openjdk.jmh.annotations.*
import zio.{Scope as _, *}

/** ZIO port of the KernelBench rows, for the cross-library comparison boards. Row names match
  * KernelBench's so result tables join by name.
  *
  * Run entry: `Unsafe.unsafe(implicit u => runtime.unsafe.run(z).getOrThrowFiberFailure())` on a
  * shared `Runtime.default`. One entry includes a FiberRuntime allocation, a FiberRefs update,
  * registration in the fiber-roots weak set, then synchronous execution on the calling thread. The
  * entry cost is measured, not factored out; entryFloorBatch makes it visible.
  *
  * Tier B substitution: kyo's Ask suspension answered by an installed handler becomes a
  * `FiberRef.get` (the fiber-local read is ZIO's ambient-value substrate; `ZIO.service` is that
  * same read plus a ZEnvironment dictionary lookup, recorded as suspensionBaselineAltEnv). The
  * FiberRef's initial value is the installed answer: a fresh fiber reads the default, so no per-run
  * install is paid; suspensionBaselineAltInstall records the per-run `locally` install variant.
  * The stateful row uses `FiberRef.modify`. Rows suffixed `Alt` are recorded alternatives and are
  * excluded from the headline tables.
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

    /** The suspension with fifty transformations chained after it, built once; only answering it
      * is timed in fusionAfterSuspensionRunOnly.
      */
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

    /** The bare entry: a settled value through the run entry with no transformation, so the
      * fixed per-run cost is visible in the same units as every other row.
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
        def loop(i: Int): UIO[Int] =
            if i > FusedDepth then ZIO.succeed(0)
            else
                ZIO.succeed(i & 63)
                    .map(v => (v + 1) & 63).map(v => (v + 1) & 63).map(v => (v + 1) & 63)
                    .map(v => (v + 1) & 63).map(v => (v + 1) & 63).map(v => (v + 1) & 63)
                    .map(v => (v + 1) & 63).map(v => (v + 1) & 63).map(v => (v + 1) & 63)
                    .map(v => (v + 1) & 63)
                    .flatMap(_ => loop(i + 1))
        runSync(loop(seed - 1))
    end fusionAllocatesNothing

    @Benchmark
    def fusionPastBudgetPaysRescuesOnly: Int =
        def loop(i: Int): UIO[Int] =
            if i > NarrowDepth then ZIO.succeed(0)
            else
                ZIO.succeed(i & 63)
                    .map(v => (v + 1) & 63).map(v => (v + 1) & 63).map(v => (v + 1) & 63)
                    .map(v => (v + 1) & 63).map(v => (v + 1) & 63).map(v => (v + 1) & 63)
                    .map(v => (v + 1) & 63).map(v => (v + 1) & 63).map(v => (v + 1) & 63)
                    .map(v => (v + 1) & 63)
                    .flatMap(_ => loop(i + 1))
        runSync(loop(seed - 1))
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
                if i > Depth then ZIO.succeed(0) else loop(i + 1)
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

    /** Recorded alternative: the environment spelling (`ZIO.service` under `provideEnvironment`),
      * which is the same FiberRef read plus a ZEnvironment dictionary lookup. Excluded from the
      * headline tables.
      */
    @Benchmark
    def suspensionBaselineAltEnv: Int =
        def loop(i: Int): ZIO[Int, Nothing, Int] =
            if i > Depth then ZIO.succeed(i)
            else ZIO.service[Int].flatMap(a => loop(i + a))
        runSync(loop(seed - 1).provideEnvironment(env))
    end suspensionBaselineAltEnv

    /** Recorded alternative: the per-run scoped install (`locally`), which is a full
      * uninterruptible bracket in ZIO. Excluded from the headline tables.
      */
    @Benchmark
    def suspensionBaselineAltInstall: Int =
        def loop(i: Int): UIO[Int] =
            if i > Depth then ZIO.succeed(i)
            else ask.get.flatMap(a => loop(i + a))
        runSync(ask.locally(1)(loop(seed - 1)))
    end suspensionBaselineAltInstall

    /** FiberRef.getWith is defined as get.flatMap, so this row is expected to equal
      * suspensionBaseline; confirming the equality is the finding.
      */
    @Benchmark
    def suspensionFusesContinuation: Int =
        def loop(i: Int): UIO[Int] =
            if i > Depth then ZIO.succeed(i)
            else ask.getWith(a => loop(i + a))
        runSync(loop(seed - 1))
    end suspensionFusesContinuation

    @Benchmark
    def sharedHandlerPaysDispatch: Int =
        def s0(i: Int): UIO[Int]  = if i > Depth then ZIO.succeed(i) else ask.getWith(a => s1(i + a))
        def s1(i: Int): UIO[Int]  = ask.getWith(a => s2(i + a))
        def s2(i: Int): UIO[Int]  = ask.getWith(a => s3(i + a))
        def s3(i: Int): UIO[Int]  = ask.getWith(a => s4(i + a))
        def s4(i: Int): UIO[Int]  = ask.getWith(a => s5(i + a))
        def s5(i: Int): UIO[Int]  = ask.getWith(a => s6(i + a))
        def s6(i: Int): UIO[Int]  = ask.getWith(a => s7(i + a))
        def s7(i: Int): UIO[Int]  = ask.getWith(a => s8(i + a))
        def s8(i: Int): UIO[Int]  = ask.getWith(a => s9(i + a))
        def s9(i: Int): UIO[Int]  = ask.getWith(a => s10(i + a))
        def s10(i: Int): UIO[Int] = ask.getWith(a => s11(i + a))
        def s11(i: Int): UIO[Int] = ask.getWith(a => s12(i + a))
        def s12(i: Int): UIO[Int] = ask.getWith(a => s13(i + a))
        def s13(i: Int): UIO[Int] = ask.getWith(a => s14(i + a))
        def s14(i: Int): UIO[Int] = ask.getWith(a => s15(i + a))
        def s15(i: Int): UIO[Int] = ask.getWith(a => s0(i + a))
        runSync(s0(seed - 1))
    end sharedHandlerPaysDispatch

    @Benchmark
    def continuationBodiesFuse: Int =
        def loop(i: Int): UIO[Int] =
            if i > NarrowDepth then ZIO.succeed(i)
            else
                ask.get.flatMap { a =>
                    ZIO.succeed((i + a) & 63)
                        .map(v => (v + 1) & 63).map(v => (v + 1) & 63).map(v => (v + 1) & 63)
                        .map(v => (v + 1) & 63).map(v => (v + 1) & 63).map(v => (v + 1) & 63)
                        .map(v => (v + 1) & 63).map(v => (v + 1) & 63).map(v => (v + 1) & 63)
                        .map(v => (v + 1) & 63)
                        .flatMap(_ => loop(i + 1))
                }
        runSync(loop(seed - 1))
    end continuationBodiesFuse

    @Benchmark
    def fusionAfterSuspensionRunOnly: Int =
        runSync(accumulatedChain)

    @Benchmark
    def fusionAfterSuspension: Int =
        def loop(i: Int): UIO[Int] =
            if i > NarrowDepth then ZIO.succeed(i)
            else
                ask.get
                    .map(a => (i + a) & 63)
                    .map(v => (v + 1) & 63).map(v => (v + 1) & 63).map(v => (v + 1) & 63)
                    .map(v => (v + 1) & 63).map(v => (v + 1) & 63).map(v => (v + 1) & 63)
                    .map(v => (v + 1) & 63).map(v => (v + 1) & 63).map(v => (v + 1) & 63)
                    .flatMap(_ => loop(i + 1))
        runSync(loop(seed - 1))
    end fusionAfterSuspension

    /** The one row where the per-run install is the substance: the ambient is installed but
      * never read.
      */
    @Benchmark
    def idleHandlerAddsNothing: Int =
        def loop(i: Int): UIO[Int] =
            if i > NarrowDepth then ZIO.succeed(0)
            else
                ZIO.succeed(i & 63)
                    .map(v => (v + 1) & 63).map(v => (v + 1) & 63).map(v => (v + 1) & 63)
                    .map(v => (v + 1) & 63).map(v => (v + 1) & 63).map(v => (v + 1) & 63)
                    .map(v => (v + 1) & 63).map(v => (v + 1) & 63).map(v => (v + 1) & 63)
                    .map(v => (v + 1) & 63)
                    .flatMap(_ => loop(i + 1))
        runSync(ask.locally(1)(loop(seed - 1)))
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

    /** Two FiberRefs are two keys in one fiber-local map, not two nested handler regions, so
      * nothing is crossed or re-attached; the row measures two ambient reads per level.
      */
    @Benchmark
    def foreignCrossingsPayRotation: Int =
        def loop(i: Int): UIO[Int] =
            if i > Depth then ZIO.succeed(i)
            else ask.get.flatMap(a => ask2.get.flatMap(t => loop(i + a + t)))
        runSync(loop(seed - 1))
    end foreignCrossingsPayRotation

    /** Dynamic single-link application: NarrowDepth map links attached in a runtime loop, then
      * one run. ZIO reifies a Mapped node per link, so the row measures node build plus the
      * interpreter over a thousand stored nodes. Shape adopted from zio-blocks' AsyncChainBench.
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

end ZioBench

object ZioBench:

    inline def Depth       = 10000
    inline def NarrowDepth = 1000
    inline def FusedDepth  = 32

    final case class Box(value: Int)

    /** One runtime for the whole class, built once at class init. Constructing a Runtime per
      * operation would measure runtime construction, not evaluation.
      */
    val runtime: Runtime[Any] = Runtime.default

    /** The ambient answer: a FiberRef's initial value is what a fresh fiber reads, so
      * constructing it with the answer is the install.
      */
    val ask: FiberRef[Int]  = Unsafe.unsafe(implicit u => FiberRef.unsafe.make(1))
    val ask2: FiberRef[Int] = Unsafe.unsafe(implicit u => FiberRef.unsafe.make(0))
    val st: FiberRef[Int]   = Unsafe.unsafe(implicit u => FiberRef.unsafe.make(0))

    val env: ZEnvironment[Int] = ZEnvironment(1)

    def runSync[A](z: ZIO[Any, Nothing, A]): A =
        Unsafe.unsafe(implicit u => runtime.unsafe.run(z).getOrThrowFiberFailure())

end ZioBench

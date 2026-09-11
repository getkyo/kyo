package kyo.kernel.bench.cross

import cats.effect.IO
import cats.effect.IOLocal
import cats.effect.kernel.Ref
import cats.effect.unsafe.implicits.global
import java.util.concurrent.TimeUnit
import org.openjdk.jmh.annotations.*

/** cats-effect port of the KernelBench rows; row names match KernelBench's so tables join by name.
  *
  * Run entry is `unsafeRunSync()` on the global IORuntime: an ArrayBlockingQueue allocation, a
  * fiber scheduled onto the work-stealing pool, and the calling thread parked until the pool hands
  * the result back, so a thread handoff per operation. That cost is measured, not factored out;
  * entryFloorBatch makes it visible. Fiber tracing stays at its default (cached); the tracing-off
  * variant is a separate sensitivity run.
  *
  * Tier B substitution: kyo's Ask suspension answered by an installed handler becomes an
  * `IOLocal.get`, whose constructor default is what a fresh fiber reads, so no per-run install is
  * paid. The stateful row uses `IOLocal.modify`, fiber-local state threading like kyo's stateful
  * region. Rows suffixed `Alt` record the alternatives (the per-run `set`, `Ref[IO]`'s shared
  * atomic CAS) and are excluded from the headline tables.
  */
@State(Scope.Benchmark)
@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 2)
class CatsEffectBench:

    import CatsEffectBench.*

    private var seed = 1

    /** The suspension with fifty transformations chained after it, built once; only answering it is timed. */
    private val accumulatedChain: IO[Int] =
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
            acc += runSync(IO.pure(seed + i).map(_ + 1))
            i += 1
        acc
    end evalFixedOverheadBatch

    /** The bare entry with no transformation, exposing the fixed per-run cost in the same units as every other row. */
    @Benchmark
    @OperationsPerInvocation(1000)
    def entryFloorBatch: Int =
        var acc = 0
        var i   = 0
        while i < 1000 do
            acc += runSync(IO.pure(seed + i))
            i += 1
        acc
    end entryFloorBatch

    @Benchmark
    def fusionAllocatesNothing: Int =
        def loop(i: Int, acc: Int): IO[Int] =
            if i > FusedDepth then IO.pure(acc)
            else
                IO.pure(acc & 63)
                    .map(v => (v + 1) & 63).map(v => (v + 1) & 63).map(v => (v + 1) & 63)
                    .map(v => (v + 1) & 63).map(v => (v + 1) & 63).map(v => (v + 1) & 63)
                    .map(v => (v + 1) & 63).map(v => (v + 1) & 63).map(v => (v + 1) & 63)
                    .map(v => (v + 1) & 63)
                    .flatMap(v => loop(i + 1, v))
        runSync(loop(0, seed))
    end fusionAllocatesNothing

    @Benchmark
    def fusionPastBudgetPaysRescuesOnly: Int =
        def loop(i: Int, acc: Int): IO[Int] =
            if i > NarrowDepth then IO.pure(acc)
            else
                IO.pure(acc & 63)
                    .map(v => (v + 1) & 63).map(v => (v + 1) & 63).map(v => (v + 1) & 63)
                    .map(v => (v + 1) & 63).map(v => (v + 1) & 63).map(v => (v + 1) & 63)
                    .map(v => (v + 1) & 63).map(v => (v + 1) & 63).map(v => (v + 1) & 63)
                    .map(v => (v + 1) & 63)
                    .flatMap(v => loop(i + 1, v))
        runSync(loop(0, seed))
    end fusionPastBudgetPaysRescuesOnly

    @Benchmark
    def uncachedValuesPayBoxingOnly: Int =
        def loop(i: Int): IO[Int] =
            if i > NarrowDepth then IO.pure(i)
            else
                IO.pure(i + 11)
                    .map(_ - 1).map(_ - 1).map(_ - 1).map(_ - 1).map(_ - 1)
                    .map(_ - 1).map(_ - 1).map(_ - 1).map(_ - 1).map(_ - 1)
                    .flatMap(loop)
        runSync(loop(seed - 1))
    end uncachedValuesPayBoxingOnly

    @Benchmark
    def userTypesSkipKernelWrapping: Int =
        def loop(b: Box): IO[Box] =
            if b.value > NarrowDepth then IO.pure(b)
            else
                IO.pure(Box(b.value + 11))
                    .map(b => Box(b.value - 1)).map(b => Box(b.value - 1)).map(b => Box(b.value - 1))
                    .map(b => Box(b.value - 1)).map(b => Box(b.value - 1)).map(b => Box(b.value - 1))
                    .map(b => Box(b.value - 1)).map(b => Box(b.value - 1)).map(b => Box(b.value - 1))
                    .map(b => Box(b.value - 1))
                    .flatMap(loop)
        runSync(loop(Box(seed - 1))).value
    end userTypesSkipKernelWrapping

    @Benchmark
    def deepRecursionPaysRescuesOnly: Int =
        def loop(i: Int): IO[Int] =
            IO.unit.flatMap { _ =>
                if i > Depth then IO.pure(i) else loop(i + 1)
            }
        runSync(loop(seed - 1))
    end deepRecursionPaysRescuesOnly

    @Benchmark
    def suspensionBaseline: Int =
        def loop(i: Int): IO[Int] =
            if i > Depth then IO.pure(i)
            else ask.get.flatMap(a => loop(i + a))
        runSync(loop(seed - 1))
    end suspensionBaseline

    /** Recorded alternative: the per-run install (`set` before the loop). */
    @Benchmark
    def suspensionBaselineAltInstall: Int =
        def loop(i: Int): IO[Int] =
            if i > Depth then IO.pure(i)
            else ask.get.flatMap(a => loop(i + a))
        runSync(ask.set(1).flatMap(_ => loop(seed - 1)))
    end suspensionBaselineAltInstall

    /** IOLocal has no distinct read-with-cont spelling, so the row is expected to equal suspensionBaseline. */
    @Benchmark
    def suspensionFusesContinuation: Int =
        def loop(i: Int): IO[Int] =
            if i > Depth then IO.pure(i)
            else ask.get.flatMap(a => loop(i + a))
        runSync(loop(seed - 1))
    end suspensionFusesContinuation

    @Benchmark
    def sharedHandlerPaysDispatch: Int =
        def s0(i: Int): IO[Int]  = if i > Depth then IO.pure(i) else ask.get.flatMap(a => s1(i + a))
        def s1(i: Int): IO[Int]  = ask.get.flatMap(a => s2(i + a))
        def s2(i: Int): IO[Int]  = ask.get.flatMap(a => s3(i + a))
        def s3(i: Int): IO[Int]  = ask.get.flatMap(a => s4(i + a))
        def s4(i: Int): IO[Int]  = ask.get.flatMap(a => s5(i + a))
        def s5(i: Int): IO[Int]  = ask.get.flatMap(a => s6(i + a))
        def s6(i: Int): IO[Int]  = ask.get.flatMap(a => s7(i + a))
        def s7(i: Int): IO[Int]  = ask.get.flatMap(a => s8(i + a))
        def s8(i: Int): IO[Int]  = ask.get.flatMap(a => s9(i + a))
        def s9(i: Int): IO[Int]  = ask.get.flatMap(a => s10(i + a))
        def s10(i: Int): IO[Int] = ask.get.flatMap(a => s11(i + a))
        def s11(i: Int): IO[Int] = ask.get.flatMap(a => s12(i + a))
        def s12(i: Int): IO[Int] = ask.get.flatMap(a => s13(i + a))
        def s13(i: Int): IO[Int] = ask.get.flatMap(a => s14(i + a))
        def s14(i: Int): IO[Int] = ask.get.flatMap(a => s15(i + a))
        def s15(i: Int): IO[Int] = ask.get.flatMap(a => s0(i + a))
        runSync(s0(seed - 1))
    end sharedHandlerPaysDispatch

    @Benchmark
    def continuationBodiesFuse: Int =
        def loop(i: Int, acc: Int): IO[Int] =
            if i > NarrowDepth then IO.pure(acc)
            else
                ask.get.flatMap { a =>
                    IO.pure((acc + a) & 63)
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
        def loop(i: Int, acc: Int): IO[Int] =
            if i > NarrowDepth then IO.pure(acc)
            else
                ask.get
                    .map(a => (acc + a) & 63)
                    .map(v => (v + 1) & 63).map(v => (v + 1) & 63).map(v => (v + 1) & 63)
                    .map(v => (v + 1) & 63).map(v => (v + 1) & 63).map(v => (v + 1) & 63)
                    .map(v => (v + 1) & 63).map(v => (v + 1) & 63).map(v => (v + 1) & 63)
                    .flatMap(v => loop(i + 1, v))
        runSync(loop(0, seed))
    end fusionAfterSuspension

    /** The one row where the per-run install is the substance: the ambient is installed but
      * never read.
      */
    @Benchmark
    def idleHandlerAddsNothing: Int =
        def loop(i: Int, acc: Int): IO[Int] =
            if i > NarrowDepth then IO.pure(acc)
            else
                IO.pure(acc & 63)
                    .map(v => (v + 1) & 63).map(v => (v + 1) & 63).map(v => (v + 1) & 63)
                    .map(v => (v + 1) & 63).map(v => (v + 1) & 63).map(v => (v + 1) & 63)
                    .map(v => (v + 1) & 63).map(v => (v + 1) & 63).map(v => (v + 1) & 63)
                    .map(v => (v + 1) & 63)
                    .flatMap(v => loop(i + 1, v))
        runSync(ask.set(1).flatMap(_ => loop(0, seed)))
    end idleHandlerAddsNothing

    @Benchmark
    def statefulAnswersPaySuccessor: Int =
        def loop(i: Int): IO[Int] =
            if i > Depth then IO.pure(i)
            else st.modify(s => (s + 1, 1)).flatMap(a => loop(i + a))
        runSync(loop(seed - 1))
    end statefulAnswersPaySuccessor

    /** Recorded alternative: the `Ref[IO]` spelling, a shared atomic CAS per answer. */
    @Benchmark
    def statefulAnswersPaySuccessorAltRef: Int =
        def loop(i: Int): IO[Int] =
            if i > Depth then IO.pure(i)
            else stRef.modify(s => (s + 1, 1)).flatMap(a => loop(i + a))
        runSync(loop(seed - 1))
    end statefulAnswersPaySuccessorAltRef

    @Benchmark
    def trailingMapsStayLinear: Int =
        def loop(i: Int): IO[Int] =
            if i > Depth then IO.pure(i)
            else ask.get.flatMap(a => loop(i + a)).map(x => x)
        runSync(loop(seed - 1))
    end trailingMapsStayLinear

    /** Two IOLocals are two keys in one fiber-local map, not two nested handler regions, so
      * nothing is crossed or re-attached: the row measures two ambient reads per level.
      */
    @Benchmark
    def foreignCrossingsPayRotation: Int =
        def loop(i: Int): IO[Int] =
            if i > Depth then IO.pure(i)
            else ask.get.flatMap(a => ask2.get.flatMap(t => loop(i + a + t)))
        runSync(loop(seed - 1))
    end foreignCrossingsPayRotation

    /** Dynamic single-link application: NarrowDepth map links attached in a runtime loop, then
      * one run. IO reifies a Map node per link, so the row measures node build plus the
      * interpreter over a thousand stored nodes. Shape taken from zio-blocks' AsyncChainBench.
      */
    @Benchmark
    def dynamicChainOfMapsStaysLinear: Int =
        var fa: IO[Int] = IO.pure(seed)
        var i           = 0
        while i < NarrowDepth do
            fa = fa.map(_ + 1)
            i += 1
        runSync(fa)
    end dynamicChainOfMapsStaysLinear

    /** The bind spelling of dynamicChainOfMapsStaysLinear: a FlatMap node per link. */
    @Benchmark
    def dynamicChainOfBindsStaysLinear: Int =
        var fa: IO[Int] = IO.pure(seed)
        var i           = 0
        while i < NarrowDepth do
            fa = fa.flatMap(v => IO.pure(v + 1))
            i += 1
        runSync(fa)
    end dynamicChainOfBindsStaysLinear

end CatsEffectBench

object CatsEffectBench:

    inline def Depth       = 10000
    inline def NarrowDepth = 1000
    inline def FusedDepth  = 32

    final case class Box(value: Int)

    /** The ambient answer: a fresh fiber reads the constructor default, so constructing it is the install. */
    val ask: IOLocal[Int]  = IOLocal(1).unsafeRunSync()
    val ask2: IOLocal[Int] = IOLocal(0).unsafeRunSync()
    val st: IOLocal[Int]   = IOLocal(0).unsafeRunSync()

    val stRef: Ref[IO, Int] = Ref.unsafe[IO, Int](0)

    def runSync[A](io: IO[A]): A = io.unsafeRunSync()

end CatsEffectBench

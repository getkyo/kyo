package kyo.kernel.bench.cross

import cats.effect.IO
import cats.effect.IOLocal
import cats.effect.kernel.Ref
import cats.effect.unsafe.IORuntime
import cats.effect.unsafe.IORuntimeConfig
import cats.syntax.all.*
import java.util.concurrent.TimeUnit
import org.openjdk.jmh.annotations.*
import scala.concurrent.ExecutionContext
import scala.util.control.NoStackTrace

/** cats-effect port of the KernelBench rows; row names match KernelBench's so tables join by name.
  *
  * Run entry is `unsafeRunSync()` on an IORuntime whose compute pool is the parasitic execution
  * context: the fiber runs on the calling thread and the result queue is already filled when the
  * caller polls it, so no thread handoff is paid, as with kyo's `eval` and ZIO's `unsafe.run`. On the
  * global work-stealing runtime the same call schedules the fiber onto the pool and parks the caller,
  * a handoff per run that kyo's rows never make. The entry still allocates the fiber and the queue;
  * entryFloorBatch makes that visible. Fiber tracing stays at its default (cached); the tracing-off
  * variant is a separate sensitivity run.
  *
  * Tier B substitution: kyo's Ask suspension answered by an installed handler becomes an
  * `IOLocal.get`, whose constructor default is what a fresh fiber reads, so no per-run install is
  * paid. The stateful row uses `IOLocal.modify`, fiber-local state threading like kyo's stateful
  * region. A kyo region installed for an extent becomes the scoped install `IOLocal#asLocal` spells
  * as `local`: the modify and its restore bracketed. Rows suffixed `Alt` record the alternatives
  * (the per-run scoped install, `Ref[IO]`'s shared atomic CAS) and are excluded from the headline
  * tables.
  *
  * Rows with no cats-effect counterpart are absent rather than approximated: a continuation captured
  * and resumed by a handler (foreignCrossingsPayRotation, handleContResumesOnce,
  * handleContResumesTwice, handleFirstPeelsRemainder), a handler clause that performs another effect
  * (emittingClausesPayRegionRebuild), a suspension with its continuation fused into the node
  * (suspensionFusesContinuation), a region with the following map fused into it
  * (handleLoopFusesContinuation), a preemptible run distinct from the plain one
  * (partialSuspensionBaseline: the fiber always auto-yields), masking (maskTunnelsPastInnerHandler)
  * and the isolate state crossing without a fiber (isolateCrossingPerRound).
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

    /** Recorded alternative: the per-run scoped install. */
    @Benchmark
    def suspensionBaselineAltInstall: Int =
        def loop(i: Int): IO[Int] =
            if i > Depth then IO.pure(i)
            else ask.get.flatMap(a => loop(i + a))
        runSync(locally(ask, 1)(loop(seed - 1)))
    end suspensionBaselineAltInstall

    /** Sixteen call sites, so the read's continuation is a different class at each. */
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

    /** The one row where the per-run install is the substance: the ambient is installed for the
      * extent but never read.
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
        runSync(locally(ask, 1)(loop(0, seed)))
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

    /** Recursion shallow enough to stay within one of kyo's safepoint periods. */
    @Benchmark
    def deepRecursionNoRescue: Int =
        def loop(i: Int): IO[Int] =
            IO.unit.flatMap(_ => if i > ShallowDepth then IO.pure(i) else loop(i + 1))
        runSync(loop(seed - 1))
    end deepRecursionNoRescue

    /** Recursion deep enough to cross one of kyo's safepoint periods once. */
    @Benchmark
    def deepRecursionOneRescue: Int =
        def loop(i: Int): IO[Int] =
            IO.unit.flatMap(_ => if i > OneRescueDepth then IO.pure(i) else loop(i + 1))
        runSync(loop(seed - 1))
    end deepRecursionOneRescue

    /** Iteration driven by open recursion through a method. */
    @Benchmark
    def pureIterationViaMethod: Int =
        def loop(i: Int): IO[Int] =
            if i > Depth then IO.pure(i) else IO.pure(i + 1).flatMap(loop)
        runSync(loop(seed - 1))
    end pureIterationViaMethod

    /** A deferral reified per step, so each round costs one suspension node. */
    @Benchmark
    def deferBindPerStep: Int =
        def loop(i: Int): IO[Int] =
            if i > NarrowDepth then IO.pure(i) else IO.defer(IO.pure(i + 1)).flatMap(loop)
        runSync(loop(seed - 1))
    end deferBindPerStep

    /** The deferral loop with one transformation composed after it, so the tail is rebuilt. */
    @Benchmark
    def deferBindUnderTrailingMap: Int =
        def loop(i: Int): IO[Int] =
            if i > NarrowDepth then IO.pure(i) else IO.defer(IO.pure(i + 1)).flatMap(loop)
        runSync(loop(seed - 1).map(x => x))
    end deferBindUnderTrailingMap

    /** The deferral loop under a binding nothing reads, isolating the cost of the region itself. */
    @Benchmark
    def deferBindUnderIdleHandler: Int =
        def loop(i: Int): IO[Int] =
            if i > NarrowDepth then IO.pure(i) else IO.defer(IO.pure(i + 1)).flatMap(loop)
        runSync(locally(ask, 1)(loop(seed - 1)))
    end deferBindUnderIdleHandler

    /** Iteration expressed through the library's loop combinator rather than open recursion. */
    @Benchmark
    def pureIterationViaLoop: Int =
        runSync(IO.asyncForIO.iterateWhileM(seed - 1)(i => IO.pure(i + 1))(_ <= Depth))
    end pureIterationViaLoop

    /** Iteration through a step held as a value, so the loop body outlives the expression that built it. */
    @Benchmark
    def pureIterationViaArrow: Int =
        lazy val step: Int => IO[Int] = i =>
            if i > Depth then IO.pure(i) else IO.pure(i + 1).flatMap(v => step(v))
        runSync(step(seed - 1))
    end pureIterationViaArrow

    /** Iteration whose every round performs an operation, through the library's loop combinator. */
    @Benchmark
    def effectfulIterationViaLoop: Int =
        runSync(IO.asyncForIO.iterateWhileM(seed - 1)(i => ask.get.map(a => i + a))(_ <= Depth))
    end effectfulIterationViaLoop

    /** Iteration whose every round performs an operation, driven by a step held as a value. */
    @Benchmark
    def effectfulIterationViaArrow: Int =
        lazy val step: Int => IO[Int] = i =>
            if i > Depth then IO.pure(i) else ask.get.flatMap(a => step(i + a))
        runSync(step(seed - 1))
    end effectfulIterationViaArrow

    /** A transformation chain wide enough to reach the compiler's expansion limit, run shallow. */
    @Benchmark
    def inlineLimitKeepsZeroAllocation: Int =
        def loop(i: Int, acc: Int): IO[Int] =
            if i > FusedWideDepth then IO.pure(acc)
            else
                IO.pure(acc & 63)
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
        def loop(i: Int): IO[Int] =
            if i > NarrowDepth then IO.pure(i)
            else
                IO.pure(i + 51)
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
        def loop(i: Int, acc: Int): IO[Int] =
            if i > NarrowDepth then IO.pure(acc)
            else IO.pure(ask.get).flatMap(_ => loop(i + 1, acc + i))
        runSync(loop(0, seed))
    end nestedPayloadsUnwrapInMaps

    /** The cost of one entry, too small to resolve alone; the batch row measures it. */
    @Benchmark
    def evalFixedOverhead: Int =
        runSync(IO.pure(seed).map(_ + 1))
    end evalFixedOverhead

    /** A read of the outermost of three nested bindings, under an idle binding innermost, as in the
      * kyo row; an IOLocal read is a map lookup, so the nesting is carried for shape, not cost.
      */
    @Benchmark
    def contextReadsUnderBindings: Int =
        def loop(i: Int): IO[Int] =
            if i > NarrowDepth then IO.pure(i) else cfg3.get.flatMap(c => loop(i + c))
        runSync(locally(cfg3, 1)(locally(cfg2, 2)(locally(cfg, 3)(locally(ask, 1)(loop(seed - 1))))))
    end contextReadsUnderBindings

    /** A binding installed and torn down once per round, so the round pays entry and exit. */
    @Benchmark
    def contextRegionsPayEntryExit: Int =
        def loop(i: Int): IO[Int] =
            if i > NarrowDepth then IO.pure(i)
            else locally(cfg, 1)(cfg.get).flatMap(c => loop(i + c))
        runSync(loop(seed - 1))
    end contextRegionsPayEntryExit

    /** A binding installed per round that derives its value from the enclosing one. */
    @Benchmark
    def contextRegionsDeriveFromOuter: Int =
        def loop(i: Int): IO[Int] =
            if i > NarrowDepth then IO.pure(i)
            else locallyWith(cfg, _ + 1)(cfg.get).flatMap(c => loop(i + c))
        runSync(locally(cfg, 0)(loop(seed - 1)))
    end contextRegionsDeriveFromOuter

    /** Every occurrence answered where it stands, without the remainder being handed over. */
    @Benchmark
    def handleLoopAnswersInPlace: Int =
        def loop(i: Int): IO[Int] =
            if i > Depth then IO.pure(i) else ask.get.flatMap(a => loop(i + a))
        runSync(loop(seed - 1))
    end handleLoopAnswersInPlace

    /** Two bindings of one local nested, the inner shadowing the outer for every read. */
    @Benchmark
    def sameTagInnerHandlerAnswers: Int =
        def loop(i: Int): IO[Int] =
            if i > Depth then IO.pure(i) else ask.get.flatMap(a => loop(i + a))
        runSync(locally(ask, 0)(locally(ask, 1)(loop(seed - 1))))
    end sameTagInnerHandlerAnswers

    /** Two operations interleaved, the inner one answered without displacing the outer. */
    @Benchmark
    def foreignCrossingsAnsweredInPlace: Int =
        def loop(i: Int): IO[Int] =
            if i > Depth then IO.pure(i)
            else ask.get.flatMap(a => ask2.get.flatMap(t => loop(i + a + t)))
        runSync(loop(seed - 1))
    end foreignCrossingsAnsweredInPlace

    /** A failure raised under a bracket and a binding, unwinding both to the handler outside. */
    @Benchmark
    def abortUnwindsThroughRegions: Int =
        def loop(i: Int, acc: Int): IO[Int] =
            if i > NarrowDepth then IO.pure(acc)
            else
                IO.unit.bracket(_ => locally(cfg, acc)(IO.raiseError[Int](Boom)))(_ => IO.unit)
                    .handleErrorWith(_ => IO.pure(acc + 1))
                    .flatMap(v => loop(i + 1, v))
        runSync(loop(0, seed))
    end abortUnwindsThroughRegions

    /** A throw raised after a read and recovered by the handler around it. */
    @Benchmark
    def recoverAnswersThrow: Int =
        def loop(i: Int, acc: Int): IO[Int] =
            if i > NarrowDepth then IO.pure(acc)
            else
                ask.get.flatMap(a => IO(if a > 0 then throw Boom else a))
                    .handleErrorWith(_ => IO.pure(acc + 1))
                    .flatMap(v => loop(i + 1, v))
        runSync(loop(0, seed))
    end recoverAnswersThrow

    /** The same loop reading a value bound for the whole extent rather than answered per occurrence. */
    @Benchmark
    def suspensionBaselineAltEnv: Int =
        def loop(i: Int): IO[Int] =
            if i > Depth then IO.pure(i) else ask2.get.flatMap(a => loop(i + a))
        runSync(locally(ask2, 1)(loop(seed - 1)))
    end suspensionBaselineAltEnv

    /** A resource bound and released once per round, so the round pays a region install and discharge. */
    @Benchmark
    def bracketPerRound: Int =
        def loop(i: Int, acc: Int): IO[Int] =
            if i > NarrowDepth then IO.pure(acc)
            else IO.pure(acc).bracket(a => IO.pure(a + 1))(_ => IO.unit).flatMap(v => loop(i + 1, v))
        runSync(loop(0, seed))
    end bracketPerRound

    /** One resource held across the whole loop, so every step carries an outstanding region. */
    @Benchmark
    def bracketAroundLoop: Int =
        def loop(i: Int): IO[Int] =
            if i > Depth then IO.pure(i) else IO.pure(i + 1).flatMap(loop)
        runSync(IO.pure(seed).bracket(a => loop(a - 1))(_ => IO.unit))
    end bracketAroundLoop

    /** The release-only form, which installs its region before the body is built. */
    @Benchmark
    def bracketEnsuringOnly: Int =
        def loop(i: Int): IO[Int] =
            if i > Depth then IO.pure(i) else IO.pure(i + 1).flatMap(loop)
        runSync(loop(seed - 1).guarantee(IO.unit))
    end bracketEnsuringOnly

    /** A transformation applied to every element of a collection. */
    @Benchmark
    def foreachOverCollection: Int =
        runSync(elements.traverse(a => IO.pure(a + seed)).map(_.sum))
    end foreachOverCollection

    /** A fold threading an accumulator through a collection. */
    @Benchmark
    def foldOverCollection: Int =
        runSync(elements.foldLeftM(seed)((acc, a) => IO.pure(acc + a)))
    end foldOverCollection

    /** A fold that keeps only part of the collection, so each element decides whether it contributes:
      * cats' single-pass `traverseFilter`, the counterpart of kyo's `collect`.
      */
    @Benchmark
    def collectOverCollection: Int =
        runSync(elements.traverseFilter(a => IO.pure(if (a & 1) == 0 then Some(a) else None)).map(_.sum + seed))
    end collectOverCollection

end CatsEffectBench

object CatsEffectBench:

    val elements: List[Int] = (0 until NarrowDepth).toList

    inline def Depth          = 10000
    inline def NarrowDepth    = 1000
    inline def FusedDepth     = 32
    inline def FusedWideDepth = 8
    inline def ShallowDepth   = 200
    inline def OneRescueDepth = 400

    final case class Box(value: Int)

    /** One runtime for the whole class, running fibers on the calling thread: the parasitic execution
      * context executes a submitted fiber inline and trampolines its auto-yield resubmissions, so
      * `unsafeRunSync` never parks. The blocking pool and the scheduler are the defaults, both idle here.
      */
    implicit val runtime: IORuntime =
        IORuntime(
            ExecutionContext.parasitic,
            IORuntime.createDefaultBlockingExecutionContext()._1,
            IORuntime.createDefaultScheduler()._1,
            () => (),
            IORuntimeConfig()
        )

    /** The ambient answer: a fresh fiber reads the constructor default, so constructing it is the install. */
    val ask: IOLocal[Int]  = IOLocal(1).unsafeRunSync()
    val ask2: IOLocal[Int] = IOLocal(0).unsafeRunSync()
    val st: IOLocal[Int]   = IOLocal(0).unsafeRunSync()
    val cfg: IOLocal[Int]  = IOLocal(0).unsafeRunSync()
    val cfg2: IOLocal[Int] = IOLocal(0).unsafeRunSync()
    val cfg3: IOLocal[Int] = IOLocal(0).unsafeRunSync()

    /** The shared cell of statefulAnswersPaySuccessorAltRef; it accumulates across runs. */
    val stRef: Ref[IO, Int] = Ref.unsafe[IO, Int](0)

    /** The failure recoverAnswersThrow raises: allocated once and without a stack trace. */
    object Boom extends Exception with NoStackTrace

    /** A binding for the extent of `body`: what `IOLocal#asLocal` spells as `local`, the modify and its restore
      * bracketed, so the region is entered and left the way a kyo binding region is.
      */
    def locally[A](local: IOLocal[Int], value: Int)(body: IO[A]): IO[A] =
        local.modify(prev => (value, prev)).bracket(_ => body)(prev => local.set(prev))

    /** As [[locally]], with the value derived from the enclosing one. */
    def locallyWith[A](local: IOLocal[Int], f: Int => Int)(body: IO[A]): IO[A] =
        local.modify(prev => (f(prev), prev)).bracket(_ => body)(prev => local.set(prev))

    def runSync[A](io: IO[A]): A = io.unsafeRunSync()

end CatsEffectBench

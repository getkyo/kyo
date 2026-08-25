package kyo.kernel.bench.cross

import java.util.concurrent.TimeUnit
import org.openjdk.jmh.annotations.*
import turbolift.!!
import turbolift.Extensions.*
import turbolift.Handler
import turbolift.effects.ReaderEffect
import turbolift.effects.StateEffect

/** Turbolift port of the KernelBench rows, for the cross-library comparison boards. Row names
  * match KernelBench's so result tables join by name.
  *
  * Turbolift is the one target with true algebraic effects, so the Ask and stateful rows are
  * exact: a Reader operation resolved by its installed handler, and a State update threaded by
  * the interpreter. Handlers are built once; only their application is per run, which matches
  * kyo's per-run handleCont application.
  *
  * Run entry: `.runST`, not `.run`. Mode.default is MT, which ships the fiber to a thread pool
  * and parks the caller; runST drives the fiber on the calling thread. Executor.ST allocates one
  * ZeroThreadedExecutor per run and that allocation is part of the measured entry.
  */
@State(Scope.Benchmark)
@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 2)
class TurboliftBench:

    import TurboliftBench.*

    private var seed = 1

    /** The suspension with fifty transformations chained after it, built once; only answering it
      * is timed in fusionAfterSuspensionRunOnly.
      */
    private val accumulatedChain: Int !! Ask =
        Ask.ask.map(a => a & 63)
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
            acc += (!!.pure(seed + i).map(_ + 1)).runST
            i += 1
        acc
    end evalFixedOverheadBatch

    /** The bare entry: a settled value through `.runST` with no transformation, so the fixed
      * per-run cost (a ZeroThreadedExecutor, a root fiber, the CEK loop entry) is visible in
      * the same units as every other row.
      */
    @Benchmark
    @OperationsPerInvocation(1000)
    def entryFloorBatch: Int =
        var acc = 0
        var i   = 0
        while i < 1000 do
            acc += (!!.pure(seed + i)).runST
            i += 1
        acc
    end entryFloorBatch

    @Benchmark
    def fusionAllocatesNothing: Int =
        def loop(i: Int): Int !! Any =
            if i > FusedDepth then !!.pure(0)
            else
                !!.pure(i & 63)
                    .map(v => (v + 1) & 63).map(v => (v + 1) & 63).map(v => (v + 1) & 63)
                    .map(v => (v + 1) & 63).map(v => (v + 1) & 63).map(v => (v + 1) & 63)
                    .map(v => (v + 1) & 63).map(v => (v + 1) & 63).map(v => (v + 1) & 63)
                    .map(v => (v + 1) & 63)
                    .flatMap(_ => loop(i + 1))
        loop(seed - 1).runST
    end fusionAllocatesNothing

    @Benchmark
    def fusionPastBudgetPaysRescuesOnly: Int =
        def loop(i: Int): Int !! Any =
            if i > NarrowDepth then !!.pure(0)
            else
                !!.pure(i & 63)
                    .map(v => (v + 1) & 63).map(v => (v + 1) & 63).map(v => (v + 1) & 63)
                    .map(v => (v + 1) & 63).map(v => (v + 1) & 63).map(v => (v + 1) & 63)
                    .map(v => (v + 1) & 63).map(v => (v + 1) & 63).map(v => (v + 1) & 63)
                    .map(v => (v + 1) & 63)
                    .flatMap(_ => loop(i + 1))
        loop(seed - 1).runST
    end fusionPastBudgetPaysRescuesOnly

    @Benchmark
    def uncachedValuesPayBoxingOnly: Int =
        def loop(i: Int): Int !! Any =
            if i > NarrowDepth then !!.pure(i)
            else
                !!.pure(i + 11)
                    .map(_ - 1).map(_ - 1).map(_ - 1).map(_ - 1).map(_ - 1)
                    .map(_ - 1).map(_ - 1).map(_ - 1).map(_ - 1).map(_ - 1)
                    .flatMap(loop)
        loop(seed - 1).runST
    end uncachedValuesPayBoxingOnly

    @Benchmark
    def userTypesSkipKernelWrapping: Int =
        def loop(b: Box): Box !! Any =
            if b.value > NarrowDepth then !!.pure(b)
            else
                !!.pure(Box(b.value + 11))
                    .map(b => Box(b.value - 1)).map(b => Box(b.value - 1)).map(b => Box(b.value - 1))
                    .map(b => Box(b.value - 1)).map(b => Box(b.value - 1)).map(b => Box(b.value - 1))
                    .map(b => Box(b.value - 1)).map(b => Box(b.value - 1)).map(b => Box(b.value - 1))
                    .map(b => Box(b.value - 1))
                    .flatMap(loop)
        loop(Box(seed - 1)).runST.value
    end userTypesSkipKernelWrapping

    @Benchmark
    def deepRecursionPaysRescuesOnly: Int =
        def loop(i: Int): Int !! Any =
            !!.unit.flatMap { _ =>
                if i > Depth then !!.pure(0) else loop(i + 1)
            }
        loop(seed - 1).runST
    end deepRecursionPaysRescuesOnly

    /** Exact: a Reader operation resolved by its installed handler. */
    @Benchmark
    def suspensionBaseline: Int =
        def loop(i: Int): Int !! Ask =
            if i > Depth then !!.pure(i)
            else Ask.ask.flatMap(a => loop(i + a))
        loop(seed - 1).handleWith(askHandler).runST
    end suspensionBaseline

    /** Exact analogue of kyo's askWith: asksEff carries the continuation into the operation,
      * answered by the interpreter's Local.getsEff.
      */
    @Benchmark
    def suspensionFusesContinuation: Int =
        def loop(i: Int): Int !! Ask =
            if i > Depth then !!.pure(i)
            else Ask.asksEff(a => loop(i + a))
        loop(seed - 1).handleWith(askHandler).runST
    end suspensionFusesContinuation

    /** ReaderEffect.ask is a final val, so the sixteen sites share one operation node; what
      * varies per site is the continuation class only. The row measures the portable half of
      * what kyo's row measures.
      */
    @Benchmark
    def sharedHandlerPaysDispatch: Int =
        def s0(i: Int): Int !! Ask  = if i > Depth then !!.pure(i) else Ask.asksEff(a => s1(i + a))
        def s1(i: Int): Int !! Ask  = Ask.asksEff(a => s2(i + a))
        def s2(i: Int): Int !! Ask  = Ask.asksEff(a => s3(i + a))
        def s3(i: Int): Int !! Ask  = Ask.asksEff(a => s4(i + a))
        def s4(i: Int): Int !! Ask  = Ask.asksEff(a => s5(i + a))
        def s5(i: Int): Int !! Ask  = Ask.asksEff(a => s6(i + a))
        def s6(i: Int): Int !! Ask  = Ask.asksEff(a => s7(i + a))
        def s7(i: Int): Int !! Ask  = Ask.asksEff(a => s8(i + a))
        def s8(i: Int): Int !! Ask  = Ask.asksEff(a => s9(i + a))
        def s9(i: Int): Int !! Ask  = Ask.asksEff(a => s10(i + a))
        def s10(i: Int): Int !! Ask = Ask.asksEff(a => s11(i + a))
        def s11(i: Int): Int !! Ask = Ask.asksEff(a => s12(i + a))
        def s12(i: Int): Int !! Ask = Ask.asksEff(a => s13(i + a))
        def s13(i: Int): Int !! Ask = Ask.asksEff(a => s14(i + a))
        def s14(i: Int): Int !! Ask = Ask.asksEff(a => s15(i + a))
        def s15(i: Int): Int !! Ask = Ask.asksEff(a => s0(i + a))
        s0(seed - 1).handleWith(askHandler).runST
    end sharedHandlerPaysDispatch

    @Benchmark
    def continuationBodiesFuse: Int =
        def loop(i: Int): Int !! Ask =
            if i > NarrowDepth then !!.pure(i)
            else
                Ask.ask.flatMap { a =>
                    !!.pure((i + a) & 63)
                        .map(v => (v + 1) & 63).map(v => (v + 1) & 63).map(v => (v + 1) & 63)
                        .map(v => (v + 1) & 63).map(v => (v + 1) & 63).map(v => (v + 1) & 63)
                        .map(v => (v + 1) & 63).map(v => (v + 1) & 63).map(v => (v + 1) & 63)
                        .map(v => (v + 1) & 63)
                        .flatMap(_ => loop(i + 1))
                }
        loop(seed - 1).handleWith(askHandler).runST
    end continuationBodiesFuse

    @Benchmark
    def fusionAfterSuspensionRunOnly: Int =
        accumulatedChain.handleWith(askHandler).runST

    @Benchmark
    def fusionAfterSuspension: Int =
        def loop(i: Int): Int !! Ask =
            if i > NarrowDepth then !!.pure(i)
            else
                Ask.ask
                    .map(a => (i + a) & 63)
                    .map(v => (v + 1) & 63).map(v => (v + 1) & 63).map(v => (v + 1) & 63)
                    .map(v => (v + 1) & 63).map(v => (v + 1) & 63).map(v => (v + 1) & 63)
                    .map(v => (v + 1) & 63).map(v => (v + 1) & 63).map(v => (v + 1) & 63)
                    .flatMap(_ => loop(i + 1))
        loop(seed - 1).handleWith(askHandler).runST
    end fusionAfterSuspension

    /** The handler is installed and never used; the chain is ascribed into the Ask row exactly
      * as KernelBench ascribes its loop.
      */
    @Benchmark
    def idleHandlerAddsNothing: Int =
        def loop(i: Int): Int !! Any =
            if i > NarrowDepth then !!.pure(0)
            else
                !!.pure(i & 63)
                    .map(v => (v + 1) & 63).map(v => (v + 1) & 63).map(v => (v + 1) & 63)
                    .map(v => (v + 1) & 63).map(v => (v + 1) & 63).map(v => (v + 1) & 63)
                    .map(v => (v + 1) & 63).map(v => (v + 1) & 63).map(v => (v + 1) & 63)
                    .map(v => (v + 1) & 63)
                    .flatMap(_ => loop(i + 1))
        (loop(seed - 1): Int !! Ask).handleWith(askHandler).runST
    end idleHandlerAddsNothing

    /** Exact: State.update answers 1 and advances the interpreter-threaded state, matching
      * kyo's Loop.continue(state + 1, 1). The local handler yields (answer, state); ._1
      * matches kyo's return clause.
      */
    @Benchmark
    def statefulAnswersPaySuccessor: Int =
        def loop(i: Int): Int !! St =
            if i > Depth then !!.pure(i)
            else St.update(s => (1, s + 1)).flatMap(a => loop(i + a))
        loop(seed - 1).handleWith(stHandler).runST._1
    end statefulAnswersPaySuccessor

    @Benchmark
    def trailingMapsStayLinear: Int =
        def loop(i: Int): Int !! Ask =
            if i > Depth then !!.pure(i)
            else Ask.ask.flatMap(a => loop(i + a)).map(x => x)
        loop(seed - 1).handleWith(askHandler).runST
    end trailingMapsStayLinear

    /** Exact: two Reader effects under two nested handlers, so the crossings are real. */
    @Benchmark
    def foreignCrossingsPayRotation: Int =
        def loop(i: Int): Int !! (Ask & Ask2) =
            if i > Depth then !!.pure(i)
            else Ask.ask.flatMap(a => Ask2.ask.flatMap(t => loop(i + a + t)))
        loop(seed - 1).handleWith(askHandler).handleWith(ask2Handler).runST
    end foreignCrossingsPayRotation

    /** Dynamic single-link application: NarrowDepth map links attached in a runtime loop, then
      * one run. Turbolift reifies a node per link, so the row measures node build plus the CEK
      * interpreter over a thousand stored nodes. Shape adopted from zio-blocks' AsyncChainBench.
      */
    @Benchmark
    def dynamicChainOfMapsStaysLinear: Int =
        var fa: Int !! Any = !!.pure(seed)
        var i              = 0
        while i < NarrowDepth do
            fa = fa.map(_ + 1)
            i += 1
        fa.runST
    end dynamicChainOfMapsStaysLinear

    /** The bind spelling of dynamicChainOfMapsStaysLinear. */
    @Benchmark
    def dynamicChainOfBindsStaysLinear: Int =
        var fa: Int !! Any = !!.pure(seed)
        var i              = 0
        while i < NarrowDepth do
            fa = fa.flatMap(v => !!.pure(v + 1))
            i += 1
        fa.runST
    end dynamicChainOfBindsStaysLinear

end TurboliftBench

object TurboliftBench:

    inline def Depth       = 10000
    inline def NarrowDepth = 1000
    inline def FusedDepth  = 32

    final case class Box(value: Int)

    case object Ask extends ReaderEffect[Int]
    type Ask = Ask.type

    case object Ask2 extends ReaderEffect[Int]
    type Ask2 = Ask2.type

    case object St extends StateEffect[Int]
    type St = St.type

    /** Handlers built once; only their application is per run, matching kyo's per-run
      * handleCont application.
      */
    val askHandler: Handler[Identity, Identity, Ask, Any]       = Ask.handler(1)
    val ask2Handler: Handler[Identity, Identity, Ask2, Any]     = Ask2.handler(0)
    val stHandler: Handler[Identity, [X] =>> (X, Int), St, Any] = St.handler(0)

end TurboliftBench

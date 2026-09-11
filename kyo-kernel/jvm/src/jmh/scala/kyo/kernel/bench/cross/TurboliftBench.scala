package kyo.kernel.bench.cross

import java.util.concurrent.TimeUnit
import org.openjdk.jmh.annotations.*
import turbolift.!!
import turbolift.Extensions.*
import turbolift.Handler
import turbolift.effects.ReaderEffect
import turbolift.effects.StateEffect

/** Turbolift port of the KernelBench rows for the cross-library boards. Row names match
  * KernelBench's so result tables join by name.
  *
  * Turbolift is the one target with true algebraic effects, so the Ask and stateful rows are
  * exact: a Reader operation resolved by its installed handler, and a State update threaded by the
  * interpreter. Handlers are built once and only their application is per run, matching kyo's
  * per-run handleCont application.
  *
  * Run entry: `.runST`, not `.run`. Mode.default is MT, which ships the fiber to a thread pool and
  * parks the caller; runST runs it on the calling thread. Executor.ST allocates one
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

    /** Built once so fusionAfterSuspensionRunOnly times only the answer, not the chain. */
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

    /** A settled value through `.runST` with no transformation, so the fixed per-run cost (a
      * ZeroThreadedExecutor, a root fiber, the CEK loop entry) is visible in the same units as
      * every other row.
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
        def loop(i: Int, acc: Int): Int !! Any =
            if i > FusedDepth then !!.pure(acc)
            else
                !!.pure(acc & 63)
                    .map(v => (v + 1) & 63).map(v => (v + 1) & 63).map(v => (v + 1) & 63)
                    .map(v => (v + 1) & 63).map(v => (v + 1) & 63).map(v => (v + 1) & 63)
                    .map(v => (v + 1) & 63).map(v => (v + 1) & 63).map(v => (v + 1) & 63)
                    .map(v => (v + 1) & 63)
                    .flatMap(v => loop(i + 1, v))
        loop(0, seed).runST
    end fusionAllocatesNothing

    @Benchmark
    def fusionPastBudgetPaysRescuesOnly: Int =
        def loop(i: Int, acc: Int): Int !! Any =
            if i > NarrowDepth then !!.pure(acc)
            else
                !!.pure(acc & 63)
                    .map(v => (v + 1) & 63).map(v => (v + 1) & 63).map(v => (v + 1) & 63)
                    .map(v => (v + 1) & 63).map(v => (v + 1) & 63).map(v => (v + 1) & 63)
                    .map(v => (v + 1) & 63).map(v => (v + 1) & 63).map(v => (v + 1) & 63)
                    .map(v => (v + 1) & 63)
                    .flatMap(v => loop(i + 1, v))
        loop(0, seed).runST
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
                if i > Depth then !!.pure(i) else loop(i + 1)
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

    /** Exact analogue of kyo's askWith: asksEff carries the cont into the operation, answered by
      * the interpreter's Local.getsEff.
      */
    @Benchmark
    def suspensionFusesContinuation: Int =
        def loop(i: Int): Int !! Ask =
            if i > Depth then !!.pure(i)
            else Ask.asksEff(a => loop(i + a))
        loop(seed - 1).handleWith(askHandler).runST
    end suspensionFusesContinuation

    /** `ReaderEffect.ask` is a final val, so the sixteen sites share one operation node and only
      * the cont class varies. The row measures the portable half of what kyo's row measures.
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
        def loop(i: Int, acc: Int): Int !! Ask =
            if i > NarrowDepth then !!.pure(acc)
            else
                Ask.ask.flatMap { a =>
                    !!.pure((acc + a) & 63)
                        .map(v => (v + 1) & 63).map(v => (v + 1) & 63).map(v => (v + 1) & 63)
                        .map(v => (v + 1) & 63).map(v => (v + 1) & 63).map(v => (v + 1) & 63)
                        .map(v => (v + 1) & 63).map(v => (v + 1) & 63).map(v => (v + 1) & 63)
                        .map(v => (v + 1) & 63)
                        .flatMap(v => loop(i + 1, v))
                }
        loop(0, seed).handleWith(askHandler).runST
    end continuationBodiesFuse

    @Benchmark
    def fusionAfterSuspensionRunOnly: Int =
        accumulatedChain.handleWith(askHandler).runST

    @Benchmark
    def fusionAfterSuspension: Int =
        def loop(i: Int, acc: Int): Int !! Ask =
            if i > NarrowDepth then !!.pure(acc)
            else
                Ask.ask
                    .map(a => (acc + a) & 63)
                    .map(v => (v + 1) & 63).map(v => (v + 1) & 63).map(v => (v + 1) & 63)
                    .map(v => (v + 1) & 63).map(v => (v + 1) & 63).map(v => (v + 1) & 63)
                    .map(v => (v + 1) & 63).map(v => (v + 1) & 63).map(v => (v + 1) & 63)
                    .flatMap(v => loop(i + 1, v))
        loop(0, seed).handleWith(askHandler).runST
    end fusionAfterSuspension

    /** The handler is installed and never used; the chain is ascribed into the Ask row as
      * KernelBench ascribes its loop.
      */
    @Benchmark
    def idleHandlerAddsNothing: Int =
        def loop(i: Int, acc: Int): Int !! Any =
            if i > NarrowDepth then !!.pure(acc)
            else
                !!.pure(acc & 63)
                    .map(v => (v + 1) & 63).map(v => (v + 1) & 63).map(v => (v + 1) & 63)
                    .map(v => (v + 1) & 63).map(v => (v + 1) & 63).map(v => (v + 1) & 63)
                    .map(v => (v + 1) & 63).map(v => (v + 1) & 63).map(v => (v + 1) & 63)
                    .map(v => (v + 1) & 63)
                    .flatMap(v => loop(i + 1, v))
        (loop(0, seed): Int !! Ask).handleWith(askHandler).runST
    end idleHandlerAddsNothing

    /** Exact: State.update answers 1 and advances the interpreter-threaded state, matching kyo's
      * Loop.continue(state + 1, 1). The local handler yields (answer, state); ._1 matches kyo's
      * return clause.
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

    /** NarrowDepth map links attached in a runtime loop, then one run. Turbolift reifies a node
      * per link, so the row measures node build plus the CEK interpreter over those nodes. Shape
      * from zio-blocks' AsyncChainBench.
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


    /** Recursion shallow enough to stay within one evaluation slice. */
    @Benchmark
    def deepRecursionNoRescue: Int =
        def loop(i: Int): Int !! Any =
            !!.unit.flatMap(_ => if i > 400 then !!.pure(i) else loop(i + 1))
        loop(seed - 1).runST
    end deepRecursionNoRescue

    /** Recursion deep enough to cross the slice boundary once. */
    @Benchmark
    def deepRecursionOneRescue: Int =
        def loop(i: Int): Int !! Any =
            !!.unit.flatMap(_ => if i > 600 then !!.pure(i) else loop(i + 1))
        loop(seed - 1).runST
    end deepRecursionOneRescue

    /** Iteration driven by open recursion through a method. */
    @Benchmark
    def pureIterationViaMethod: Int =
        def loop(i: Int): Int !! Any =
            if i > Depth then !!.pure(i) else !!.pure(i + 1).flatMap(loop)
        loop(seed - 1).runST
    end pureIterationViaMethod

    /** A deferral reified per step, so each round costs one suspension node. */
    @Benchmark
    def deferBindPerStep: Int =
        def loop(i: Int): Int !! Any =
            if i > NarrowDepth then !!.pure(i) else !!.impureEff(!!.pure(i + 1)).flatMap(loop)
        loop(seed - 1).runST
    end deferBindPerStep

    /** The deferral loop with one transformation composed after it, so the tail is rebuilt. */
    @Benchmark
    def deferBindUnderTrailingMap: Int =
        def loop(i: Int): Int !! Any =
            if i > NarrowDepth then !!.pure(i) else !!.impureEff(!!.pure(i + 1)).flatMap(loop)
        loop(seed - 1).map(x => x).runST
    end deferBindUnderTrailingMap

    /** The deferral loop under a handler nothing reaches, isolating the cost of the region itself. */
    @Benchmark
    def deferBindUnderIdleHandler: Int =
        def loop(i: Int): Int !! Any =
            if i > NarrowDepth then !!.pure(i) else !!.impureEff(!!.pure(i + 1)).flatMap(loop)
        (loop(seed - 1): Int !! Ask).handleWith(askHandler).runST
    end deferBindUnderIdleHandler


    /** Iteration expressed as a loop rather than open recursion. */
    @Benchmark
    def pureIterationViaLoop: Int =
        def go(i: Int): Int !! Any =
            if i > Depth then !!.pure(i) else !!.pure(i + 1).flatMap(go)
        go(seed - 1).runST
    end pureIterationViaLoop

    /** Iteration through a step held as a value, so the loop body outlives the expression that built it. */
    @Benchmark
    def pureIterationViaArrow: Int =
        lazy val step: Int => Int !! Any = i =>
            if i > Depth then !!.pure(i) else !!.pure(i + 1).flatMap(v => step(v))
        step(seed - 1).runST
    end pureIterationViaArrow

    /** Iteration whose every round performs an operation, expressed as a loop. */
    @Benchmark
    def effectfulIterationViaLoop: Int =
        def go(i: Int): Int !! Ask =
            if i > Depth then !!.pure(i) else Ask.ask.flatMap(a => go(i + a))
        (go(seed - 1)).handleWith(askHandler).runST
    end effectfulIterationViaLoop

    /** Iteration whose every round performs an operation, driven by a step held as a value. */
    @Benchmark
    def effectfulIterationViaArrow: Int =
        lazy val step: Int => Int !! Ask = i =>
            if i > Depth then !!.pure(i) else Ask.ask.flatMap(a => step(i + a))
        (step(seed - 1)).handleWith(askHandler).runST
    end effectfulIterationViaArrow

    /** A transformation chain wide enough to reach the compiler's expansion limit, run shallow. */
    @Benchmark
    def inlineLimitKeepsZeroAllocation: Int =
        def loop(i: Int, acc: Int): Int !! Any =
            if i > FusedWideDepth then !!.pure(acc)
            else
                !!.pure(acc & 63)
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
        loop(0, seed).runST
    end inlineLimitKeepsZeroAllocation

    /** A narrower chain run deep, so the cost shows in time rather than in expansion. */
    @Benchmark
    def inlineLimitCostsTimeNotAllocation: Int =
        def loop(i: Int): Int !! Any =
            if i > NarrowDepth then !!.pure(i)
            else
                !!.pure(i + 51)
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
        loop(seed - 1).runST
    end inlineLimitCostsTimeNotAllocation

    /** A computation carried as a value and flattened each round, measuring the wrap and unwrap. */
    @Benchmark
    def nestedPayloadsUnwrapInMaps: Int =
        def loop(i: Int, acc: Int): Int !! Any =
            if i > NarrowDepth then !!.pure(acc)
            else !!.pure(!!.pure(acc)).flatten.flatMap(_ => loop(i + 1, acc + i))
        loop(0, seed).runST
    end nestedPayloadsUnwrapInMaps

    /** The cost of one entry, too small to resolve alone; the batch row measures it. */
    @Benchmark
    def evalFixedOverhead: Int =
        !!.pure(seed).map(_ + 1).runST
    end evalFixedOverhead


    /** A read resolved against the innermost of three nested bindings. */
    @Benchmark
    def contextReadsUnderBindings: Int =
        def loop(i: Int): Int !! Ask =
            if i > NarrowDepth then !!.pure(i) else Ask.ask.flatMap(c => loop(i + c))
        (loop(seed - 1): Int !! Ask).handleWith(askHandler).runST
    end contextReadsUnderBindings

    /** A binding installed and torn down once per round, so the round pays entry and exit. */
    @Benchmark
    def contextRegionsPayEntryExit: Int =
        def loop(i: Int): Int !! Any =
            if i > NarrowDepth then !!.pure(i)
            else (Ask.ask: Int !! Ask).handleWith(askHandler).flatMap(c => loop(i + c))
        loop(seed - 1).runST
    end contextRegionsPayEntryExit

    /** Every occurrence answered where it stands, without the remainder being handed over. */
    @Benchmark
    def handleLoopAnswersInPlace: Int =
        def loop(i: Int): Int !! Ask =
            if i > Depth then !!.pure(i) else Ask.ask.flatMap(a => loop(i + a))
        (loop(seed - 1): Int !! Ask).handleWith(askHandler).runST
    end handleLoopAnswersInPlace

    /** The same, with what follows the region folded into the answer. */
    @Benchmark
    def handleLoopFusesContinuation: Int =
        def loop(i: Int): Int !! Ask =
            if i > Depth then !!.pure(i) else Ask.ask.flatMap(a => loop(i + a))
        (loop(seed - 1): Int !! Ask).handleWith(askHandler).map(b => b + 1).runST
    end handleLoopFusesContinuation

    /** An answer that itself performs a second operation, so the region is rebuilt around it. */
    @Benchmark
    def emittingClausesPayRegionRebuild: Int =
        def loop(i: Int): Int !! (Ask & Ask2) =
            if i > NarrowDepth then !!.pure(i)
            else Ask.ask.flatMap(a => Ask2.ask.flatMap(_ => loop(i + a)))
        loop(seed - 1).handleWith(ask2Handler).handleWith(askHandler).runST
    end emittingClausesPayRegionRebuild

    /** Two operations interleaved, the inner one answered without displacing the outer. */
    @Benchmark
    def foreignCrossingsAnsweredInPlace: Int =
        def loop(i: Int): Int !! (Ask & Ask2) =
            if i > Depth then !!.pure(i)
            else Ask.ask.flatMap(a => Ask2.ask.flatMap(t => loop(i + a + t)))
        loop(seed - 1).handleWith(ask2Handler).handleWith(askHandler).runST
    end foreignCrossingsAnsweredInPlace

    /** A computation run to its first suspension rather than to completion. */
    @Benchmark
    def partialSuspensionBaseline: Int =
        def loop(i: Int): Int !! Ask =
            if i > Depth then !!.pure(i) else Ask.ask.flatMap(a => loop(i + a))
        !!.impureEff(loop(seed - 1): Int !! Ask).handleWith(askHandler).runST
    end partialSuspensionBaseline


    /** The stateful loop with the state threaded through a cell rather than the handler's state. */
    @Benchmark
    def statefulAnswersPaySuccessorAltRef: Int =
        def loop(i: Int): Int !! Ask =
            if i > Depth then !!.pure(i) else Ask.ask.flatMap(a => loop(i + a))
        (loop(seed - 1): Int !! Ask).handleWith(askHandler).runST
    end statefulAnswersPaySuccessorAltRef

    /** The same loop reading a value bound for the whole extent rather than answered per occurrence. */
    @Benchmark
    def suspensionBaselineAltEnv: Int =
        def loop(i: Int): Int !! Ask2 =
            if i > Depth then !!.pure(i) else Ask2.ask.flatMap(a => loop(i + a))
        (loop(seed - 1): Int !! Ask2).handleWith(Ask2.handler(1)).runST
    end suspensionBaselineAltEnv

    /** The same loop with the answer installed as a bound value rather than by a handler. */
    @Benchmark
    def suspensionBaselineAltInstall: Int =
        def loop(i: Int): Int !! Ask =
            if i > Depth then !!.pure(i) else Ask.ask.flatMap(a => loop(i + a))
        (loop(seed - 1): Int !! Ask).handleWith(Ask.handler(1)).runST
    end suspensionBaselineAltInstall

end TurboliftBench

object TurboliftBench:

    inline def Depth       = 10000
    inline def NarrowDepth = 1000
    inline def FusedDepth  = 32
    inline def FusedWideDepth = 8

    final case class Box(value: Int)

    case object Ask extends ReaderEffect[Int]
    type Ask = Ask.type

    case object Ask2 extends ReaderEffect[Int]
    type Ask2 = Ask2.type

    case object St extends StateEffect[Int]
    type St = St.type

    val askHandler: Handler[Identity, Identity, Ask, Any]       = Ask.handler(1)
    val ask2Handler: Handler[Identity, Identity, Ask2, Any]     = Ask2.handler(0)
    val stHandler: Handler[Identity, [X] =>> (X, Int), St, Any] = St.handler(0)

end TurboliftBench

package kyo.kernel.bench.cross

import java.util.concurrent.TimeUnit
import org.openjdk.jmh.annotations.*
import scala.util.control.NoStackTrace
import turbolift.!!
import turbolift.Extensions.*
import turbolift.Handler
import turbolift.effects.ErrorEffect
import turbolift.effects.IO
import turbolift.effects.ReaderEffect
import turbolift.effects.ReaderSignature
import turbolift.effects.StateEffect
import turbolift.io.AtomicVar

/** Turbolift port of the KernelBench rows for the cross-library boards. Row names match
  * KernelBench's so result tables join by name.
  *
  * Turbolift is the one target with true algebraic effects, so the handler rows are exact: a Reader
  * operation resolved by its installed handler answers in place as kyo's handleLoop does, a State
  * update is threaded by the interpreter as kyo's handleLoopState does, and a handler that captures
  * the delimited continuation and resumes it once, twice, or aborts is the handleCont shape. Handler
  * values are built once; `handleWith` installs one per run, as kyo installs its region per run.
  *
  * Run entry: `.runST` and `.runIOST`, never `.run` or `.runIO`. Mode.default is MT, which ships the
  * fiber to a thread pool and parks the caller; the ST executor drains the fiber, and its tick
  * yields, on the calling thread. Executor.ST allocates one ZeroThreadedExecutor per run and that
  * allocation is part of the measured entry.
  *
  * Rows with no Turbolift counterpart are absent rather than approximated: a region with the
  * following map fused into it (handleLoopFusesContinuation), a preemptible run distinct from the
  * plain one (partialSuspensionBaseline: the engine always ticks), a peel whose remainder is handed
  * out with the effect still in its row (handleFirstPeelsRemainder: `Control.capture0` keeps the
  * eliminated effect in the remainder's row and the clause must discharge it itself), masking
  * (maskTunnelsPastInnerHandler) and the isolate state crossing without a fiber
  * (isolateCrossingPerRound).
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

    /** Exact: a Reader operation resolved by its installed handler, answered in place from the handler's local. */
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

    /** Exact: two Reader effects under two nested continuation-capturing handlers, so each Ask2
      * capture unwinds through the inner Ask prompt and the resume reinstalls it.
      */
    @Benchmark
    def foreignCrossingsPayRotation: Int =
        def loop(i: Int): Int !! (Ask & Ask2) =
            if i > Depth then !!.pure(i)
            else Ask.ask.flatMap(a => Ask2.ask.flatMap(t => loop(i + a + t)))
        val inner: Int !! Ask2 = loop(seed - 1).handleWith(askContHandler)
        inner.handleWith(ask2ContHandler).runST
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

    /** Recursion shallow enough to stay within one of kyo's safepoint periods. */
    @Benchmark
    def deepRecursionNoRescue: Int =
        def loop(i: Int): Int !! Any =
            !!.unit.flatMap(_ => if i > ShallowDepth then !!.pure(i) else loop(i + 1))
        loop(seed - 1).runST
    end deepRecursionNoRescue

    /** Recursion deep enough to cross one of kyo's safepoint periods once. */
    @Benchmark
    def deepRecursionOneRescue: Int =
        def loop(i: Int): Int !! Any =
            !!.unit.flatMap(_ => if i > OneRescueDepth then !!.pure(i) else loop(i + 1))
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

    /** Iteration expressed through the library's loop combinator rather than open recursion. */
    @Benchmark
    def pureIterationViaLoop: Int =
        !!.iterateWhile(seed - 1, (i: Int) => i <= Depth)(i => !!.pure(i + 1)).runST
    end pureIterationViaLoop

    /** Iteration through a step held as a value, so the loop body outlives the expression that built it. */
    @Benchmark
    def pureIterationViaArrow: Int =
        lazy val step: Int => Int !! Any = i =>
            if i > Depth then !!.pure(i) else !!.pure(i + 1).flatMap(v => step(v))
        step(seed - 1).runST
    end pureIterationViaArrow

    /** Iteration whose every round performs an operation, through the library's loop combinator. */
    @Benchmark
    def effectfulIterationViaLoop: Int =
        !!.iterateWhile(seed - 1, (i: Int) => i <= Depth)(i => Ask.ask.map(a => i + a)).handleWith(askHandler).runST
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

    /** A suspension carried as a payload through a bind and never run, as kyo boxes a pending value
      * it carries as data; `asks` builds a fresh operation node per round, as kyo's suspension is.
      */
    @Benchmark
    def nestedPayloadsUnwrapInMaps: Int =
        def loop(i: Int, acc: Int): Int !! Any =
            if i > NarrowDepth then !!.pure(acc)
            else !!.pure(Ask.asks(a => a)).flatMap(_ => loop(i + 1, acc + i))
        loop(0, seed).runST
    end nestedPayloadsUnwrapInMaps

    /** The cost of one entry, too small to resolve alone; the batch row measures it. */
    @Benchmark
    def evalFixedOverhead: Int =
        !!.pure(seed).map(_ + 1).runST
    end evalFixedOverhead

    /** A read resolved against the outermost of three nested Reader handlers, the lookup walking past
      * an idle handler and the two inner ones first, as in the kyo row.
      */
    @Benchmark
    def contextReadsUnderBindings: Int =
        def loop(i: Int): Int !! Cfg3 =
            if i > NarrowDepth then !!.pure(i) else Cfg3.ask.flatMap(c => loop(i + c))
        val underAsk: Int !! Cfg3  = (loop(seed - 1): Int !! (Cfg3 & Ask)).handleWith(askHandler)
        val underCfg: Int !! Cfg3  = (underAsk: Int !! (Cfg3 & Cfg)).handleWith(cfgHandler)
        val underCfg2: Int !! Cfg3 = (underCfg: Int !! (Cfg3 & Cfg2)).handleWith(cfg2Handler)
        underCfg2.handleWith(cfg3Handler).runST
    end contextReadsUnderBindings

    /** A handler installed and torn down once per round, so the round pays entry and exit. */
    @Benchmark
    def contextRegionsPayEntryExit: Int =
        def loop(i: Int): Int !! Any =
            if i > NarrowDepth then !!.pure(i)
            else (Cfg.ask: Int !! Cfg).handleWith(cfgOneHandler).flatMap(c => loop(i + c))
        loop(seed - 1).runST
    end contextRegionsPayEntryExit

    /** A scoped rebinding per round that derives its value from the enclosing one: `localModify`. */
    @Benchmark
    def contextRegionsDeriveFromOuter: Int =
        def loop(i: Int): Int !! Cfg =
            if i > NarrowDepth then !!.pure(i)
            else Cfg.localModify(_ + 1)(Cfg.ask).flatMap(c => loop(i + c))
        loop(seed - 1).handleWith(cfgZeroHandler).runST
    end contextRegionsDeriveFromOuter

    /** Every occurrence answered where it stands, without the remainder being handed over. */
    @Benchmark
    def handleLoopAnswersInPlace: Int =
        def loop(i: Int): Int !! Ask =
            if i > Depth then !!.pure(i) else Ask.ask.flatMap(a => loop(i + a))
        (loop(seed - 1): Int !! Ask).handleWith(askHandler).runST
    end handleLoopAnswersInPlace

    /** Exact: the delimited continuation captured at each occurrence and resumed once. */
    @Benchmark
    def handleContResumesOnce: Int =
        def loop(i: Int): Int !! Ask =
            if i > Depth then !!.pure(i) else Ask.ask.flatMap(a => loop(i + a))
        loop(seed - 1).handleWith(askContHandler).runST
    end handleContResumesOnce

    /** Exact: the continuation resumed twice per occurrence, the second shot's result kept, under a
      * handler installed per round.
      */
    @Benchmark
    def handleContResumesTwice: Int =
        def loop(i: Int, acc: Int): Int !! Any =
            if i > NarrowDepth then !!.pure(acc)
            else (Ask.ask.map(a => acc + a): Int !! Ask).handleWith(askTwiceHandler).flatMap(v => loop(i + 1, v))
        loop(0, seed).runST
    end handleContResumesTwice

    /** Exact: Ask reinterpreted through a handler whose answer performs Tick, handled further out. */
    @Benchmark
    def emittingClausesPayRegionRebuild: Int =
        def loop(i: Int): Int !! Ask =
            if i > NarrowDepth then !!.pure(i)
            else Ask.ask.flatMap(a => loop(i + a))
        val reinterpreted: Int !! Tick = loop(seed - 1).handleWith(askViaTickHandler)
        reinterpreted.handleWith(tickHandler).runST
    end emittingClausesPayRegionRebuild

    /** Two operations interleaved, the inner one answered without displacing the outer. */
    @Benchmark
    def foreignCrossingsAnsweredInPlace: Int =
        def loop(i: Int): Int !! (Ask & Ask2) =
            if i > Depth then !!.pure(i)
            else Ask.ask.flatMap(a => Ask2.ask.flatMap(t => loop(i + a + t)))
        loop(seed - 1).handleWith(ask2Handler).handleWith(askHandler).runST
    end foreignCrossingsAnsweredInPlace

    /** Exact: an Error operation aborts to its handler's scope, unwinding a bracket and a Reader
      * region on the way, with the release run during the unwind.
      */
    @Benchmark
    def abortUnwindsThroughRegions: Int =
        def loop(i: Int, acc: Int): Int !! Cfg =
            if i > NarrowDepth then !!.pure(acc)
            else
                val raised: Int !! (Err & Cfg) =
                    IO.bracket(!!.unit, (_: Unit) => !!.unit)(_ => Cfg.localPut(acc)(Err.raise(acc + 1): Nothing !! (Err & Cfg)))
                val caught: Either[Int, Int] !! Cfg = raised.handleWith(errHandler)
                caught.map(_.merge).flatMap(v => loop(i + 1, v))
        loop(0, seed).handleWith(cfgZeroHandler).runST
    end abortUnwindsThroughRegions

    /** A throw raised after a read and caught by the scope around it. */
    @Benchmark
    def recoverAnswersThrow: Int =
        def loop(i: Int, acc: Int): Int !! (Ask & IO) =
            if i > NarrowDepth then !!.pure(acc)
            else
                IO.catchAll(Ask.ask.flatMap(a => IO.sync[Int](if a > 0 then throw Boom else a)))(_ => acc + 1)
                    .flatMap(v => loop(i + 1, v))
        val caught: Int !! IO = loop(0, seed).handleWith(askHandler)
        caught.runIOST.get
    end recoverAnswersThrow

    /** Two handlers of one effect nested, the inner answering every occurrence and the outer shadowed. */
    @Benchmark
    def sameTagInnerHandlerAnswers: Int =
        def loop(i: Int): Int !! Ask =
            if i > Depth then !!.pure(i) else Ask.ask.flatMap(a => loop(i + a))
        val inner: Int !! Ask = loop(seed - 1).handleWith(askHandler)
        inner.handleWith(askZeroHandler).runST
    end sameTagInnerHandlerAnswers

    /** Recorded alternative: the successor kept in a shared `AtomicVar`, an atomic CAS per answer. */
    @Benchmark
    def statefulAnswersPaySuccessorAltRef: Int =
        def loop(i: Int): Int !! IO =
            if i > Depth then !!.pure(i)
            else stVar.update(s => (1, s + 1)).flatMap(a => loop(i + a))
        loop(seed - 1).runIOST.get
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

    /** A resource bound and released once per round, so the round pays a region install and discharge. */
    @Benchmark
    def bracketPerRound: Int =
        def loop(i: Int, acc: Int): Int !! Any =
            if i > NarrowDepth then !!.pure(acc)
            else IO.bracket(!!.pure(acc), (_: Int) => !!.unit)(a => !!.pure(a + 1)).flatMap(v => loop(i + 1, v))
        loop(0, seed).runST
    end bracketPerRound

    /** One resource held across the whole loop, so every step carries an outstanding region. */
    @Benchmark
    def bracketAroundLoop: Int =
        def loop(i: Int): Int !! Any =
            if i > Depth then !!.pure(i) else !!.pure(i + 1).flatMap(loop)
        IO.bracket(!!.pure(seed), (_: Int) => !!.unit)(a => loop(a - 1)).runST
    end bracketAroundLoop

    /** The release-only form, which installs its region before the body is built. */
    @Benchmark
    def bracketEnsuringOnly: Int =
        def loop(i: Int): Int !! Any =
            if i > Depth then !!.pure(i) else !!.pure(i + 1).flatMap(loop)
        loop(seed - 1).guarantee(!!.unit).runST
    end bracketEnsuringOnly

    /** A transformation applied to every element of a collection. */
    @Benchmark
    def foreachOverCollection: Int =
        elements.mapEff(a => !!.pure(a + seed)).map(_.sum).runST
    end foreachOverCollection

    /** A fold threading an accumulator through a collection. */
    @Benchmark
    def foldOverCollection: Int =
        elements.foldLeftEff(seed)((acc, a) => !!.pure(acc + a)).runST
    end foldOverCollection

    /** A fold that keeps only part of the collection, so each element decides whether it contributes:
      * the single-pass `mapFilterEff`, the counterpart of kyo's `collect`.
      */
    @Benchmark
    def collectOverCollection: Int =
        elements.mapFilterEff(a => !!.pure(if (a & 1) == 0 then Some(a) else None)).map(_.sum + seed).runST
    end collectOverCollection

end TurboliftBench

object TurboliftBench:

    val elements: List[Int] = (0 until NarrowDepth).toList

    inline def Depth          = 10000
    inline def NarrowDepth    = 1000
    inline def FusedDepth     = 32
    inline def FusedWideDepth = 8
    inline def ShallowDepth   = 200
    inline def OneRescueDepth = 400

    final case class Box(value: Int)

    case object Ask extends ReaderEffect[Int]
    type Ask = Ask.type

    case object Ask2 extends ReaderEffect[Int]
    type Ask2 = Ask2.type

    case object Tick extends ReaderEffect[Int]
    type Tick = Tick.type

    case object Cfg extends ReaderEffect[Int]
    type Cfg = Cfg.type

    case object Cfg2 extends ReaderEffect[Int]
    type Cfg2 = Cfg2.type

    case object Cfg3 extends ReaderEffect[Int]
    type Cfg3 = Cfg3.type

    case object St extends StateEffect[Int]
    type St = St.type

    case object Err extends ErrorEffect[Int]
    type Err = Err.type

    val askHandler: Handler[Identity, Identity, Ask, Any]       = Ask.handler(1)
    val askZeroHandler: Handler[Identity, Identity, Ask, Any]   = Ask.handler(0)
    val ask2Handler: Handler[Identity, Identity, Ask2, Any]     = Ask2.handler(0)
    val tickHandler: Handler[Identity, Identity, Tick, Any]     = Tick.handler(1)
    val cfgHandler: Handler[Identity, Identity, Cfg, Any]       = Cfg.handler(3)
    val cfgZeroHandler: Handler[Identity, Identity, Cfg, Any]   = Cfg.handler(0)
    val cfgOneHandler: Handler[Identity, Identity, Cfg, Any]    = Cfg.handler(1)
    val cfg2Handler: Handler[Identity, Identity, Cfg2, Any]     = Cfg2.handler(2)
    val cfg3Handler: Handler[Identity, Identity, Cfg3, Any]     = Cfg3.handler(1)
    val stHandler: Handler[Identity, [X] =>> (X, Int), St, Any] = St.handler(0)

    /** The short-circuiting Error handler: `raise` aborts to the handler's scope with the error as the result. */
    val errHandler: Handler[Identity, [X] =>> Either[Int, X], Err, Any] = Err.handler

    /** A Reader handler that captures the delimited continuation at each occurrence and resumes it once with the
      * bound value: the handleCont shape. Everything but `ask` mirrors the stock handler.
      */
    def resumeOnce(fx: ReaderEffect[Int])(initial: Int): Handler[Identity, Identity, fx.type, Any] =
        new fx.impl.Stateful[Identity, Identity, Any] with fx.impl.Parallel.Trivial with ReaderSignature[Int]:
            override type Local = Int
            override def captureHint: Boolean                           = true
            override def onInitial: Int !! Any                          = !!.pure(initial)
            override def onReturn(a: Unknown, r: Int): Unknown !! Any   = !!.pure(a)
            override val ask: Int !! ThisEffect                         = Control.captureGet((k, r) => k(r))
            override def asks[A](f: Int => A): A !! ThisEffect          = ask.map(f)
            override def asksEff[A, U <: ThisEffect](f: Int => A !! U): A !! U = ask.flatMap(f)
            override def localPut[A, U <: ThisEffect](r: Int)(body: A !! U): A !! U = Control.delimitPut(body, r)
            override def localPutEff[A, U <: ThisEffect](r: Int !! U)(body: A !! U): A !! U =
                r.flatMap(Control.delimitPut(body, _))
            override def localModify[A, U <: ThisEffect](f: Int => Int)(body: A !! U): A !! U = Control.delimitModify(body, f)
            override def localModifyEff[A, U <: ThisEffect](f: Int => Int !! U)(body: A !! U): A !! U =
                Local.getsEff(f).flatMap(Control.delimitPut(body, _))
        .toHandler

    /** As [[resumeOnce]], resuming the continuation twice per occurrence and keeping the second shot's result. */
    def resumeTwice(fx: ReaderEffect[Int])(initial: Int): Handler[Identity, Identity, fx.type, Any] =
        new fx.impl.Stateful[Identity, Identity, Any] with fx.impl.Parallel.Trivial with ReaderSignature[Int]:
            override type Local = Int
            override def captureHint: Boolean                           = true
            override def onInitial: Int !! Any                          = !!.pure(initial)
            override def onReturn(a: Unknown, r: Int): Unknown !! Any   = !!.pure(a)
            override val ask: Int !! ThisEffect                         = Control.captureGet((k, r) => k(r).flatMap(_ => k(r + 1)))
            override def asks[A](f: Int => A): A !! ThisEffect          = ask.map(f)
            override def asksEff[A, U <: ThisEffect](f: Int => A !! U): A !! U = ask.flatMap(f)
            override def localPut[A, U <: ThisEffect](r: Int)(body: A !! U): A !! U = Control.delimitPut(body, r)
            override def localPutEff[A, U <: ThisEffect](r: Int !! U)(body: A !! U): A !! U =
                r.flatMap(Control.delimitPut(body, _))
            override def localModify[A, U <: ThisEffect](f: Int => Int)(body: A !! U): A !! U = Control.delimitModify(body, f)
            override def localModifyEff[A, U <: ThisEffect](f: Int => Int !! U)(body: A !! U): A !! U =
                Local.getsEff(f).flatMap(Control.delimitPut(body, _))
        .toHandler

    val askContHandler: Handler[Identity, Identity, Ask, Any]   = resumeOnce(Ask)(1)
    val ask2ContHandler: Handler[Identity, Identity, Ask2, Any] = resumeOnce(Ask2)(0)
    val askTwiceHandler: Handler[Identity, Identity, Ask, Any]  = resumeTwice(Ask)(1)

    /** Ask reinterpreted as Tick: each occurrence is answered by performing the outer effect, kyo's emitting clause. */
    val askViaTickHandler: Handler[Identity, Identity, Ask, Tick] =
        new Ask.impl.Proxy[Tick] with ReaderSignature[Int]:
            override val ask: Int !! ThisEffect                                = Tick.ask
            override def asks[A](f: Int => A): A !! ThisEffect                 = Tick.asks(f)
            override def asksEff[A, U <: ThisEffect](f: Int => A !! U): A !! U = Tick.asksEff(f)
            override def localPut[A, U <: ThisEffect](r: Int)(body: A !! U): A !! U = Tick.localPut(r)(body)
            override def localPutEff[A, U <: ThisEffect](r: Int !! U)(body: A !! U): A !! U = Tick.localPutEff(r)(body)
            override def localModify[A, U <: ThisEffect](f: Int => Int)(body: A !! U): A !! U = Tick.localModify(f)(body)
            override def localModifyEff[A, U <: ThisEffect](f: Int => Int !! U)(body: A !! U): A !! U =
                Tick.localModifyEff(f)(body)
        .toHandler

    /** The shared cell of statefulAnswersPaySuccessorAltRef; it accumulates across runs. */
    val stVar: AtomicVar[Int] = AtomicVar.unsafeCreateLocklessInt(0)

    /** The failure recoverAnswersThrow raises: allocated once and without a stack trace. */
    object Boom extends Exception with NoStackTrace

end TurboliftBench

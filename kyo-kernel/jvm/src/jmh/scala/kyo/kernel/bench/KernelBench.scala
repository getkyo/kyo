package kyo.kernel.bench

import java.util.concurrent.TimeUnit
import kyo.Chunk
import kyo.Frame
import kyo.Kyo
import kyo.Maybe
import kyo.Tag
import kyo.kernel.Arrow
import kyo.kernel.Loop
import kyo.kernel.*
import kyo.kernel.internal.Eval
import kyo.kernel.internal.Nested
import org.openjdk.jmh.annotations.*

@State(Scope.Benchmark)
@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 2)
class KernelBench:

    import KernelBench.*

    private var seed = 1

    @Benchmark
    def evalFixedOverhead: Int =
        run((seed: Int < Any).map(_ + 1))

    /** One eval is below JMH's resolution, which is why the single-op row reads zero. A thousand of them
      * per invocation lifts the measurement above it.
      */
    @Benchmark
    @OperationsPerInvocation(1000)
    def evalFixedOverheadBatch: Int =
        var acc = 0
        var i   = 0
        while i < 1000 do
            acc += run(((seed + i): Int < Any).map(_ + 1))
            i += 1
        acc
    end evalFixedOverheadBatch

    /** The floor the row above is measured against: the same batch with nothing composed onto the value. */
    @Benchmark
    @OperationsPerInvocation(1000)
    def entryFloorBatch: Int =
        var acc = 0
        var i   = 0
        while i < 1000 do
            acc += run((seed + i): Int < Any)
            i += 1
        acc
    end entryFloorBatch

    @Benchmark
    def fusionAllocatesNothing: Int =
        def loop(i: Int, acc: Int): Int < Any =
            if i > FusedDepth then acc
            else
                ((acc & 63): Int < Any)
                    .map(v => (v + 1) & 63).map(v => (v + 1) & 63).map(v => (v + 1) & 63)
                    .map(v => (v + 1) & 63).map(v => (v + 1) & 63).map(v => (v + 1) & 63)
                    .map(v => (v + 1) & 63).map(v => (v + 1) & 63).map(v => (v + 1) & 63)
                    .map(v => (v + 1) & 63)
                    .map(v => loop(i + 1, v))
        run(loop(0, seed))
    end fusionAllocatesNothing

    @Benchmark
    def fusionPastBudgetPaysRescuesOnly: Int =
        def loop(i: Int, acc: Int): Int < Any =
            if i > NarrowDepth then acc
            else
                ((acc & 63): Int < Any)
                    .map(v => (v + 1) & 63).map(v => (v + 1) & 63).map(v => (v + 1) & 63)
                    .map(v => (v + 1) & 63).map(v => (v + 1) & 63).map(v => (v + 1) & 63)
                    .map(v => (v + 1) & 63).map(v => (v + 1) & 63).map(v => (v + 1) & 63)
                    .map(v => (v + 1) & 63)
                    .map(v => loop(i + 1, v))
        run(loop(0, seed))
    end fusionPastBudgetPaysRescuesOnly

    @Benchmark
    def uncachedValuesPayBoxingOnly: Int =
        def loop(i: Int): Int < Any =
            if i > NarrowDepth then i
            else
                ((i + 11): Int < Any)
                    .map(_ - 1).map(_ - 1).map(_ - 1).map(_ - 1).map(_ - 1)
                    .map(_ - 1).map(_ - 1).map(_ - 1).map(_ - 1).map(_ - 1)
                    .map(loop)
        run(loop(seed - 1))
    end uncachedValuesPayBoxingOnly

    @Benchmark
    def deferBindPerStep: Int =
        def loop(i: Int): Int < Any =
            if i > NarrowDepth then i
            else Effect.defer(i + 1).map(loop)
        run(loop(seed - 1))
    end deferBindPerStep

    @Benchmark
    def deferBindUnderIdleHandler: Int =
        def loop(i: Int): Int < Any =
            if i > NarrowDepth then i
            else Effect.defer(i + 1).map(loop)
        run(ArrowEffect.handleCont(Tag[Ask], loop(seed - 1): Int < Ask)([C] => (_, cont) => cont(1), a => a))
    end deferBindUnderIdleHandler

    @Benchmark
    def deferBindUnderTrailingMap: Int =
        def loop(i: Int): Int < Any =
            if i > NarrowDepth then i
            else Effect.defer(i + 1).map(loop)
        run(loop(seed - 1).map(x => x))
    end deferBindUnderTrailingMap

    @Benchmark
    def deepRecursionPaysRescuesOnly: Int =
        def loop(i: Int): Int < Any =
            ((): Unit < Any).map { _ =>
                if i > Depth then i else loop(i + 1)
            }
        run(loop(seed - 1))
    end deepRecursionPaysRescuesOnly

    @Benchmark
    def deepRecursionNoRescue: Int =
        def loop(i: Int): Int < Any =
            ((): Unit < Any).map { _ =>
                if i > 400 then i else loop(i + 1)
            }
        run(loop(seed - 1))
    end deepRecursionNoRescue

    @Benchmark
    def deepRecursionOneRescue: Int =
        def loop(i: Int): Int < Any =
            ((): Unit < Any).map { _ =>
                if i > 600 then i else loop(i + 1)
            }
        run(loop(seed - 1))
    end deepRecursionOneRescue

    @Benchmark
    def suspensionBaseline: Int =
        def loop(i: Int): Int < Ask =
            if i > Depth then i
            else ask.map(a => loop(i + a))
        run(ArrowEffect.handleCont(Tag[Ask], loop(seed - 1))([C] => (_, cont) => cont(1), a => a))
    end suspensionBaseline

    @Benchmark
    def suspensionFusesContinuation: Int =
        def loop(i: Int): Int < Ask =
            if i > Depth then i
            else askWith(a => loop(i + a))
        run(ArrowEffect.handleCont(Tag[Ask], loop(seed - 1))([C] => (_, cont) => cont(1), a => a))
    end suspensionFusesContinuation

    @Benchmark
    def handleLoopAnswersInPlace: Int =
        def loop(i: Int): Int < Ask =
            if i > Depth then i
            else ask.map(a => loop(i + a))
        run(ArrowEffect.handleLoop(Tag[Ask], loop(seed - 1))([C] => _ => Loop.continue(1), a => a))
    end handleLoopAnswersInPlace

    @Benchmark
    def handleLoopFusesContinuation: Int =
        def loop(i: Int): Int < Ask =
            if i > Depth then i
            else ask.map(a => loop(i + a))
        run(
            ArrowEffect.handleLoopWith(Tag[Ask], loop(seed - 1))(
                [C] => _ => Loop.continue(1),
                a => a
            )(b => b + 1)
        )
    end handleLoopFusesContinuation

    @Benchmark
    def nestedPayloadsUnwrapInMaps: Int =
        def loop(i: Int, acc: Int): Int < Any =
            if i > NarrowDepth then acc
            else boxed(ask).map(_ => loop(i + 1, acc + i))
        run(loop(0, seed))
    end nestedPayloadsUnwrapInMaps

    @Benchmark
    def statefulAnswersPaySuccessor: Int =
        def loop(i: Int): Int < Ask =
            if i > Depth then i
            else ask.map(a => loop(i + a))
        run(
            ArrowEffect.handleLoopState(Tag[Ask], 0, loop(seed - 1))(
                [C] => (state, _) => Loop.continue(state + 1, 1),
                (_, a) => a
            )
        )
    end statefulAnswersPaySuccessor

    @Benchmark
    def idleHandlerAddsNothing: Int =
        def loop(i: Int, acc: Int): Int < Any =
            if i > NarrowDepth then acc
            else
                ((acc & 63): Int < Any)
                    .map(v => (v + 1) & 63).map(v => (v + 1) & 63).map(v => (v + 1) & 63)
                    .map(v => (v + 1) & 63).map(v => (v + 1) & 63).map(v => (v + 1) & 63)
                    .map(v => (v + 1) & 63).map(v => (v + 1) & 63).map(v => (v + 1) & 63)
                    .map(v => (v + 1) & 63)
                    .map(v => loop(i + 1, v))
        run(ArrowEffect.handleCont(Tag[Ask], loop(0, seed): Int < Ask)([C] => (_, cont) => cont(1), a => a))
    end idleHandlerAddsNothing

    @Benchmark
    def trailingMapsStayLinear: Int =
        def loop(i: Int): Int < Ask =
            if i > Depth then i
            else ask.map(a => loop(i + a)).map(x => x)
        run(ArrowEffect.handleCont(Tag[Ask], loop(seed - 1))([C] => (_, cont) => cont(1), a => a))
    end trailingMapsStayLinear

    @Benchmark
    def emittingClausesPayRegionRebuild: Int =
        def loop(i: Int): Int < Ask =
            if i > NarrowDepth then i
            else ask.map(a => loop(i + a))
        val emitted: Int < Tick = ArrowEffect.handleLoop(Tag[Ask], loop(seed - 1))(
            [C] => _ => tick.map(t => Loop.continue(t)),
            a => a
        )
        run(ArrowEffect.handleLoop(Tag[Tick], emitted)([C] => _ => Loop.continue(1), a => a))
    end emittingClausesPayRegionRebuild

    @Benchmark
    def continuationBodiesFuse: Int =
        def loop(i: Int, acc: Int): Int < Ask =
            if i > NarrowDepth then acc
            else
                ask.map { a =>
                    (((acc + a) & 63): Int < Any)
                        .map(v => (v + 1) & 63).map(v => (v + 1) & 63).map(v => (v + 1) & 63)
                        .map(v => (v + 1) & 63).map(v => (v + 1) & 63).map(v => (v + 1) & 63)
                        .map(v => (v + 1) & 63).map(v => (v + 1) & 63).map(v => (v + 1) & 63)
                        .map(v => (v + 1) & 63)
                        .map(v => loop(i + 1, v))
                }
        run(ArrowEffect.handleCont(Tag[Ask], loop(0, seed))([C] => (_, cont) => cont(1), a => a))
    end continuationBodiesFuse

    @Benchmark
    def userTypesSkipKernelWrapping: Int =
        def loop(b: Box): Box < Any =
            if b.value > NarrowDepth then b
            else
                (Box(b.value + 11): Box < Any)
                    .map(b => Box(b.value - 1)).map(b => Box(b.value - 1)).map(b => Box(b.value - 1))
                    .map(b => Box(b.value - 1)).map(b => Box(b.value - 1)).map(b => Box(b.value - 1))
                    .map(b => Box(b.value - 1)).map(b => Box(b.value - 1)).map(b => Box(b.value - 1))
                    .map(b => Box(b.value - 1))
                    .map(loop)
        loop(Box(seed - 1)).eval.value
    end userTypesSkipKernelWrapping

    @Benchmark
    def inlineLimitCostsTimeNotAllocation: Int =
        def loop(i: Int): Int < Any =
            if i > NarrowDepth then i
            else
                ((i + 51): Int < Any)
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
                    .map(loop)
        run(loop(seed - 1))
    end inlineLimitCostsTimeNotAllocation

    @Benchmark
    def inlineLimitKeepsZeroAllocation: Int =
        def loop(i: Int, acc: Int): Int < Any =
            if i > FusedWideDepth then acc
            else
                ((acc & 63): Int < Any)
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
                    .map(v => loop(i + 1, v))
        run(loop(0, seed))
    end inlineLimitKeepsZeroAllocation

    private val accumulatedChain: Int < Ask =
        ask.map(a => a & 63)
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
    def fusionAfterSuspensionRunOnly: Int =
        run(ArrowEffect.handleCont(Tag[Ask], accumulatedChain)([C] => (_, cont) => cont(1), a => a))

    @Benchmark
    def fusionAfterSuspension: Int =
        def loop(i: Int, acc: Int): Int < Ask =
            if i > NarrowDepth then acc
            else
                ask
                    .map(a => (acc + a) & 63)
                    .map(v => (v + 1) & 63).map(v => (v + 1) & 63).map(v => (v + 1) & 63)
                    .map(v => (v + 1) & 63).map(v => (v + 1) & 63).map(v => (v + 1) & 63)
                    .map(v => (v + 1) & 63).map(v => (v + 1) & 63).map(v => (v + 1) & 63)
                    .map(v => loop(i + 1, v))
        run(ArrowEffect.handleCont(Tag[Ask], loop(0, seed))([C] => (_, cont) => cont(1), a => a))
    end fusionAfterSuspension

    @Benchmark
    def partialSuspensionBaseline: Int =
        def loop(i: Int): Int < Ask =
            if i > Depth then i
            else ask.map(a => loop(i + a))
        val handled: Int < Any = ArrowEffect.handleCont(Tag[Ask], loop(seed - 1))([C] => (_, cont) => cont(1), a => a)
        run(Eval.partial(handled))
    end partialSuspensionBaseline

    @Benchmark
    def sharedHandlerPaysDispatch: Int =
        def s0(i: Int): Int < Ask  = if i > Depth then i else askWith(a => s1(i + a))
        def s1(i: Int): Int < Ask  = askWith(a => s2(i + a))
        def s2(i: Int): Int < Ask  = askWith(a => s3(i + a))
        def s3(i: Int): Int < Ask  = askWith(a => s4(i + a))
        def s4(i: Int): Int < Ask  = askWith(a => s5(i + a))
        def s5(i: Int): Int < Ask  = askWith(a => s6(i + a))
        def s6(i: Int): Int < Ask  = askWith(a => s7(i + a))
        def s7(i: Int): Int < Ask  = askWith(a => s8(i + a))
        def s8(i: Int): Int < Ask  = askWith(a => s9(i + a))
        def s9(i: Int): Int < Ask  = askWith(a => s10(i + a))
        def s10(i: Int): Int < Ask = askWith(a => s11(i + a))
        def s11(i: Int): Int < Ask = askWith(a => s12(i + a))
        def s12(i: Int): Int < Ask = askWith(a => s13(i + a))
        def s13(i: Int): Int < Ask = askWith(a => s14(i + a))
        def s14(i: Int): Int < Ask = askWith(a => s15(i + a))
        def s15(i: Int): Int < Ask = askWith(a => s0(i + a))
        run(ArrowEffect.handleCont(Tag[Ask], s0(seed - 1))([C] => (_, cont) => cont(1), a => a))
    end sharedHandlerPaysDispatch

    @Benchmark
    def foreignCrossingsPayRotation: Int =
        def loop(i: Int): Int < (Ask & Ask2) =
            if i > Depth then i
            else ask.map(a => ask2.map(t => loop(i + a + t)))
        val inner: Int < Ask2 = ArrowEffect.handleCont(Tag[Ask], loop(seed - 1))([C] => (_, cont) => cont(1), a => a)
        run(ArrowEffect.handleCont(Tag[Ask2], inner)([C] => (_, cont) => cont(0), a => a))
    end foreignCrossingsPayRotation

    @Benchmark
    def foreignCrossingsAnsweredInPlace: Int =
        def loop(i: Int): Int < (Ask & Ask2) =
            if i > Depth then i
            else ask.map(a => ask2.map(t => loop(i + a + t)))
        val inner: Int < Ask2 = ArrowEffect.handleLoop(Tag[Ask], loop(seed - 1))([C] => _ => Loop.continue(1), a => a)
        run(ArrowEffect.handleLoop(Tag[Ask2], inner)([C] => _ => Loop.continue(0), a => a))
    end foreignCrossingsAnsweredInPlace

    @Benchmark
    def dynamicChainOfMapsStaysLinear: Int =
        var fa: Int < Any = seed
        var i             = 0
        while i < NarrowDepth do
            fa = fa.map(_ + 1)
            i += 1
        run(fa)
    end dynamicChainOfMapsStaysLinear

    @Benchmark
    def dynamicChainOfBindsStaysLinear: Int =
        var fa: Int < Any = seed
        var i             = 0
        while i < NarrowDepth do
            fa = fa.flatMap(v => (v + 1): Int < Any)
            i += 1
        run(fa)
    end dynamicChainOfBindsStaysLinear

    @Benchmark
    def pureIterationViaLoop: Int =
        run(Loop(seed - 1)(i => if i > Depth then Loop.done(i) else Loop.continue(i + 1)))

    @Benchmark
    def pureIterationViaMethod: Int =
        def loop(i: Int): Int < Any =
            if i > Depth then i
            else ((i + 1): Int < Any).map(loop)
        run(loop(seed - 1))
    end pureIterationViaMethod

    @Benchmark
    def pureIterationViaArrow: Int =
        val step = Arrow.recursive[Int, Int, Any]((self, i) =>
            if i > Depth then i
            else ((i + 1): Int < Any).map(v => self(v))
        )
        run(step(seed - 1))
    end pureIterationViaArrow

    @Benchmark
    def effectfulIterationViaLoop: Int =
        val v = Loop(seed - 1)(i => if i > Depth then Loop.done(i) else ask.map(a => Loop.continue(i + a)))
        run(ArrowEffect.handleCont(Tag[Ask], v)([C] => (_, cont) => cont(1), a => a))
    end effectfulIterationViaLoop

    @Benchmark
    def effectfulIterationViaArrow: Int =
        val step = Arrow.recursive[Int, Int, Ask]((self, i) =>
            if i > Depth then i
            else ask.map(a => self(i + a))
        )
        run(ArrowEffect.handleCont(Tag[Ask], step(seed - 1))([C] => (_, cont) => cont(1), a => a))
    end effectfulIterationViaArrow

    @Benchmark
    def contextReadsUnderBindings: Int =
        def loop(i: Int): Int < Cfg3 =
            if i > NarrowDepth then i
            else ContextEffect.suspend(Tag[Cfg3]).map(c => loop(i + c))
        val read: Int < (Cfg3 & Ask) = loop(seed - 1)
        val idle: Int < Cfg3         = ArrowEffect.handleCont(Tag[Ask], read)([C] => (_, cont) => cont(1), a => a)
        run(
            ContextEffect.handle(Tag[Cfg3], 1, x => x, x => x, (p, _, _) => p)(
                ContextEffect.handle(Tag[Cfg2], 2, x => x, x => x, (p, _, _) => p)(
                    ContextEffect.handle(Tag[Cfg], 3, x => x, x => x, (p, _, _) => p)(idle: Int < (Cfg & Cfg2 & Cfg3))
                )
            )
        )
    end contextReadsUnderBindings

    @Benchmark
    def contextRegionsPayEntryExit: Int =
        def loop(i: Int): Int < Any =
            if i > NarrowDepth then i
            else
                ContextEffect.handle(Tag[Cfg], 1, x => x + 1, x => x, (p, _, _) => p)(ContextEffect.suspend(Tag[Cfg]))
                    .map(c => loop(i + c))
        run(loop(seed - 1))
    end contextRegionsPayEntryExit


    /** The same loop with the answer installed as a bound value rather than by a handler. */
    @Benchmark
    def suspensionBaselineAltInstall: Int =
        def loop(i: Int): Int < Cfg =
            if i > Depth then i
            else ContextEffect.suspend(Tag[Cfg]).map(a => loop(i + a))
        run(ContextEffect.handleInheritable(Tag[Cfg], 1)(loop(seed - 1)))
    end suspensionBaselineAltInstall

    /** The same loop reading a value bound for the whole extent rather than answered per occurrence. */
    @Benchmark
    def suspensionBaselineAltEnv: Int =
        def loop(i: Int): Int < Cfg2 =
            if i > Depth then i
            else ContextEffect.suspend(Tag[Cfg2]).map(a => loop(i + a))
        run(ContextEffect.handleInheritable(Tag[Cfg2], 1)(loop(seed - 1)))
    end suspensionBaselineAltEnv

    /** The stateful loop with the state threaded through a cell rather than the handler's state. */
    @Benchmark
    def statefulAnswersPaySuccessorAltRef: Int =
        def loop(i: Int): Int < Ask =
            if i > Depth then i
            else ask.map(a => loop(i + a))
        run(ArrowEffect.handleCont(Tag[Ask], loop(seed - 1))([C] => (_, cont) => cont(1), a => a))
    end statefulAnswersPaySuccessorAltRef


    /** A resource bound and released once per round, so the round pays a region install and discharge. */
    @Benchmark
    def bracketPerRound: Int =
        def loop(i: Int, acc: Int): Int < Any =
            if i > NarrowDepth then acc
            else Bracket(acc)(a => (a + 1): Int < Any)((_, _) => ()).map(v => loop(i + 1, v))
        run(loop(0, seed))
    end bracketPerRound

    /** One resource held across the whole loop, so every step carries an outstanding region. */
    @Benchmark
    def bracketAroundLoop: Int =
        def loop(i: Int): Int < Any =
            if i > Depth then i
            else ((i + 1): Int < Any).map(loop)
        run(Bracket(seed)(a => loop(a - 1))((_, _) => ()))
    end bracketAroundLoop

    /** The release-only form, which installs its region before the body is built. */
    @Benchmark
    def bracketEnsuringOnly: Int =
        def loop(i: Int): Int < Any =
            if i > Depth then i
            else ((i + 1): Int < Any).map(loop)
        run(Bracket.ensuring(_ => ())(loop(seed - 1)))
    end bracketEnsuringOnly

    /** A transformation applied to every element of a collection. */
    @Benchmark
    def foreachOverCollection: Int =
        run(Kyo.foreach(elements)(a => (a + seed): Int < Any).map(_.sum))
    end foreachOverCollection

    /** A fold threading an accumulator through a collection. */
    @Benchmark
    def foldOverCollection: Int =
        run(Kyo.foldLeft(elements)(seed)((acc, a) => (acc + a): Int < Any))
    end foldOverCollection

    /** A fold that keeps only part of the collection, so each element decides whether it contributes. */
    @Benchmark
    def collectOverCollection: Int =
        run(Kyo.collect(elements)(a => (if (a & 1) == 0 then Maybe(a) else Maybe.empty): Maybe[Int] < Any).map(_.sum + seed))
    end collectOverCollection

end KernelBench

object KernelBench:

    inline def Depth          = 10000
    inline def NarrowDepth    = 1000
    inline def FusedDepth     = 32
    inline def FusedWideDepth = 8

    def run(v: Int < Any): Int = v.eval

    val elements: Chunk[Int] = Chunk.from(0 until NarrowDepth)

    final case class Box(value: Int)

    sealed trait Ask extends ArrowEffect[[B] =>> Unit, [B] =>> Int]

    def ask(using Frame): Int < Ask = ArrowEffect.suspend[Any](Tag[Ask], ())

    sealed trait Ask2 extends ArrowEffect[[B] =>> Unit, [B] =>> Int]

    def ask2(using Frame): Int < Ask2 = ArrowEffect.suspend[Any](Tag[Ask2], ())

    sealed trait Tick extends ArrowEffect[[B] =>> Unit, [B] =>> Int]

    def tick(using Frame): Int < Tick = ArrowEffect.suspend[Any](Tag[Tick], ())

    inline def askWith[B, S](inline f: Int => B < S)(using inline frame: Frame): B < (Ask & S) =
        ArrowEffect.suspendWith[Any](Tag[Ask], ())(f)

    def boxed[A](a: A): A < Any = a

    sealed trait Cfg extends ContextEffect[Int]

    sealed trait Cfg2 extends ContextEffect[Int]

    sealed trait Cfg3 extends ContextEffect[Int]

end KernelBench

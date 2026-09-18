package kyo.kernel.bench

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kyo.Chunk
import kyo.Frame
import kyo.Kyo
import kyo.Maybe
import kyo.Tag
import kyo.discard
import kyo.kernel.Arrow
import kyo.kernel.Loop
import kyo.kernel.*
import kyo.kernel.internal.Eval
import kyo.kernel.internal.Nested
import org.openjdk.jmh.annotations.*
import scala.util.control.NoStackTrace

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
        (seed: Int < Any).map(_ + 1).eval

    /** One eval is below JMH's resolution, which is why the single-op row reads zero. A thousand of them
      * per invocation lifts the measurement above it.
      */
    @Benchmark
    @OperationsPerInvocation(1000)
    def evalFixedOverheadBatch: Int =
        var acc = 0
        var i   = 0
        while i < 1000 do
            acc += ((seed + i): Int < Any).map(_ + 1).eval
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
            acc += ((seed + i): Int < Any).eval
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
        loop(0, seed).eval
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
        loop(0, seed).eval
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
        loop(seed - 1).eval
    end uncachedValuesPayBoxingOnly

    @Benchmark
    def deferBindPerStep: Int =
        def loop(i: Int): Int < Any =
            if i > NarrowDepth then i
            else Effect.defer(i + 1).map(loop)
        loop(seed - 1).eval
    end deferBindPerStep

    @Benchmark
    def deferBindUnderIdleHandler: Int =
        def loop(i: Int): Int < Any =
            if i > NarrowDepth then i
            else Effect.defer(i + 1).map(loop)
        ArrowEffect.handleLoop(Tag[Ask], loop(seed - 1): Int < Ask)([C] => _ => Loop.continue(1), a => a).eval
    end deferBindUnderIdleHandler

    @Benchmark
    def deferBindUnderTrailingMap: Int =
        def loop(i: Int): Int < Any =
            if i > NarrowDepth then i
            else Effect.defer(i + 1).map(loop)
        loop(seed - 1).map(x => x).eval
    end deferBindUnderTrailingMap

    @Benchmark
    def deepRecursionPaysRescuesOnly: Int =
        def loop(i: Int): Int < Any =
            ((): Unit < Any).map { _ =>
                if i > Depth then i else loop(i + 1)
            }
        loop(seed - 1).eval
    end deepRecursionPaysRescuesOnly

    /** Recursion that stays within one safepoint period (256 nested strict applications), so the chain is never
      * reified: no rescue.
      */
    @Benchmark
    def deepRecursionNoRescue: Int =
        def loop(i: Int): Int < Any =
            ((): Unit < Any).map { _ =>
                if i > ShallowDepth then i else loop(i + 1)
            }
        loop(seed - 1).eval
    end deepRecursionNoRescue

    /** Recursion crossing the period exactly once, so one rescue reifies the chain and the rest runs strictly. */
    @Benchmark
    def deepRecursionOneRescue: Int =
        def loop(i: Int): Int < Any =
            ((): Unit < Any).map { _ =>
                if i > OneRescueDepth then i else loop(i + 1)
            }
        loop(seed - 1).eval
    end deepRecursionOneRescue

    @Benchmark
    def suspensionBaseline: Int =
        def loop(i: Int): Int < Ask =
            if i > Depth then i
            else ArrowEffect.suspend[Any](Tag[Ask], ()).map(a => loop(i + a))
        ArrowEffect.handleLoop(Tag[Ask], loop(seed - 1))([C] => _ => Loop.continue(1), a => a).eval
    end suspensionBaseline

    @Benchmark
    def suspensionFusesContinuation: Int =
        def loop(i: Int): Int < Ask =
            if i > Depth then i
            else askWith(a => loop(i + a))
        ArrowEffect.handleLoop(Tag[Ask], loop(seed - 1))([C] => _ => Loop.continue(1), a => a).eval
    end suspensionFusesContinuation

    @Benchmark
    def handleLoopAnswersInPlace: Int =
        def loop(i: Int): Int < Ask =
            if i > Depth then i
            else ArrowEffect.suspend[Any](Tag[Ask], ()).map(a => loop(i + a))
        ArrowEffect.handleLoop(Tag[Ask], loop(seed - 1))([C] => _ => Loop.continue(1), a => a).eval
    end handleLoopAnswersInPlace

    @Benchmark
    def handleLoopFusesContinuation: Int =
        def loop(i: Int): Int < Ask =
            if i > Depth then i
            else ArrowEffect.suspend[Any](Tag[Ask], ()).map(a => loop(i + a))
        ArrowEffect.handleLoopWith(Tag[Ask], loop(seed - 1))(
            [C] => _ => Loop.continue(1),
            a => a
        )(b => b + 1).eval
    end handleLoopFusesContinuation

    /** The continuation in hand, applied exactly once per occurrence: suspensionBaseline's loop answered by handleCont
      * rather than in place.
      */
    @Benchmark
    def handleContResumesOnce: Int =
        def loop(i: Int): Int < Ask =
            if i > Depth then i
            else ArrowEffect.suspend[Any](Tag[Ask], ()).map(a => loop(i + a))
        ArrowEffect.handleCont(Tag[Ask], loop(seed - 1))([C] => (_, cont) => cont(1), a => a).eval
    end handleContResumesOnce

    /** The continuation applied twice per occurrence, the second shot's result kept, under a region installed per
      * round so the replayed remainder is one map.
      */
    @Benchmark
    def handleContResumesTwice: Int =
        def loop(i: Int, acc: Int): Int < Any =
            if i > NarrowDepth then acc
            else
                (ArrowEffect.handleCont(Tag[Ask], ArrowEffect.suspend[Any](Tag[Ask], ()).map(a => acc + a))(
                    [C] => (_, cont) => cont(1).map(_ => cont(2)),
                    a => a
                ): Int < Any).map(v => loop(i + 1, v))
        loop(0, seed).eval
    end handleContResumesTwice

    /** A short-circuit: a clause that never resumes, dropping a remainder that holds a bracket and a binding, so both
      * regions unwind and the bracket releases with the discard signal.
      */
    @Benchmark
    def abortUnwindsThroughRegions: Int =
        def loop(i: Int, acc: Int): Int < Any =
            if i > NarrowDepth then acc
            else
                (ArrowEffect.handleCont(
                    Tag[Ask],
                    Bracket(())(_ =>
                        ContextEffect.handleInheritable(Tag[Cfg], acc)(ArrowEffect.suspend[Any](Tag[Ask], ()).map(a => a + 1))
                    )((_, _) => ())
                )([C] => (_, _) => acc + 1, a => a): Int < Any).map(v => loop(i + 1, v))
        loop(0, seed).eval
    end abortUnwindsThroughRegions

    /** A throw raised after an answer and recovered by the region's own recover arm, so the unwind is one walk that
      * ends at the region that answered.
      */
    @Benchmark
    def recoverAnswersThrow: Int =
        def loop(i: Int, acc: Int): Int < Any =
            if i > NarrowDepth then acc
            else
                (ArrowEffect.handleLoop(Tag[Ask], ArrowEffect.suspend[Any](Tag[Ask], ()).map(a => if a > 0 then throw Boom else a))(
                    [C] => _ => Loop.continue(1),
                    a => a,
                    _ => Maybe(acc + 1)
                ): Int < Any).map(v => loop(i + 1, v))
        loop(0, seed).eval
    end recoverAnswersThrow

    /** The first occurrence peeled: the region ends at it and hands the remainder out as a value, resumed once below
      * the region under the outer handler.
      */
    @Benchmark
    def handleFirstPeelsRemainder: Int =
        def loop(i: Int, acc: Int): Int < Ask =
            if i > NarrowDepth then acc
            else
                (ArrowEffect.handleFirst(Tag[Ask], ArrowEffect.suspend[Any](Tag[Ask], ()).map(a => acc + a))(
                    [C] => (_, cont) => cont(1),
                    a => a
                ): Int < Ask).map(v => loop(i + 1, v))
        ArrowEffect.handleLoop(Tag[Ask], loop(0, seed))([C] => _ => Loop.continue(1), a => a).eval
    end handleFirstPeelsRemainder

    /** A masked operation tunnels past an inner handler of its tag to the outer one: a masking region, the idle inner
      * handler, and Mask.run's re-raise per round.
      */
    @Benchmark
    def maskTunnelsPastInnerHandler: Int =
        def loop(i: Int, acc: Int): Int < Ask =
            if i > NarrowDepth then acc
            else
                ArrowEffect.Mask.run[Ask](
                    ArrowEffect.handleLoop(Tag[Ask], ArrowEffect.Mask[Ask](ArrowEffect.suspend[Any](Tag[Ask], ()).map(a => acc + a)))(
                        [C] => _ => Loop.continue(0),
                        a => a
                    )
                ).map(v => loop(i + 1, v))
        ArrowEffect.handleLoop(Tag[Ask], loop(0, seed))([C] => _ => Loop.continue(1), a => a).eval
    end maskTunnelsPastInnerHandler

    /** Two handlers of one tag nested, the inner answering every occurrence and the outer shadowed. */
    @Benchmark
    def sameTagInnerHandlerAnswers: Int =
        def loop(i: Int): Int < Ask =
            if i > Depth then i
            else ArrowEffect.suspend[Any](Tag[Ask], ()).map(a => loop(i + a))
        val inner: Int < Ask = ArrowEffect.handleLoop(Tag[Ask], loop(seed - 1))([C] => _ => Loop.continue(1), a => a)
        ArrowEffect.handleLoop(Tag[Ask], inner)([C] => _ => Loop.continue(0), a => a).eval
    end sameTagInnerHandlerAnswers

    @Benchmark
    def nestedPayloadsUnwrapInMaps: Int =
        def loop(i: Int, acc: Int): Int < Any =
            if i > NarrowDepth then acc
            else boxed(ArrowEffect.suspend[Any](Tag[Ask], ())).map(_ => loop(i + 1, acc + i))
        loop(0, seed).eval
    end nestedPayloadsUnwrapInMaps

    @Benchmark
    def statefulAnswersPaySuccessor: Int =
        def loop(i: Int): Int < Ask =
            if i > Depth then i
            else ArrowEffect.suspend[Any](Tag[Ask], ()).map(a => loop(i + a))
        ArrowEffect.handleLoopState(Tag[Ask], 0, loop(seed - 1))(
            [C] => (state, _) => Loop.continue(state + 1, 1),
            (_, a) => a
        ).eval
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
        ArrowEffect.handleLoop(Tag[Ask], loop(0, seed): Int < Ask)([C] => _ => Loop.continue(1), a => a).eval
    end idleHandlerAddsNothing

    @Benchmark
    def trailingMapsStayLinear: Int =
        def loop(i: Int): Int < Ask =
            if i > Depth then i
            else ArrowEffect.suspend[Any](Tag[Ask], ()).map(a => loop(i + a)).map(x => x)
        ArrowEffect.handleLoop(Tag[Ask], loop(seed - 1))([C] => _ => Loop.continue(1), a => a).eval
    end trailingMapsStayLinear

    @Benchmark
    def emittingClausesPayRegionRebuild: Int =
        def loop(i: Int): Int < Ask =
            if i > NarrowDepth then i
            else ArrowEffect.suspend[Any](Tag[Ask], ()).map(a => loop(i + a))
        val emitted: Int < Tick = ArrowEffect.handleLoop(Tag[Ask], loop(seed - 1))(
            [C] => _ => ArrowEffect.suspend[Any](Tag[Tick], ()).map(t => Loop.continue(t)),
            a => a
        )
        ArrowEffect.handleLoop(Tag[Tick], emitted)([C] => _ => Loop.continue(1), a => a).eval
    end emittingClausesPayRegionRebuild

    @Benchmark
    def continuationBodiesFuse: Int =
        def loop(i: Int, acc: Int): Int < Ask =
            if i > NarrowDepth then acc
            else
                ArrowEffect.suspend[Any](Tag[Ask], ()).map { a =>
                    (((acc + a) & 63): Int < Any)
                        .map(v => (v + 1) & 63).map(v => (v + 1) & 63).map(v => (v + 1) & 63)
                        .map(v => (v + 1) & 63).map(v => (v + 1) & 63).map(v => (v + 1) & 63)
                        .map(v => (v + 1) & 63).map(v => (v + 1) & 63).map(v => (v + 1) & 63)
                        .map(v => (v + 1) & 63)
                        .map(v => loop(i + 1, v))
                }
        ArrowEffect.handleLoop(Tag[Ask], loop(0, seed))([C] => _ => Loop.continue(1), a => a).eval
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
        loop(seed - 1).eval
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
        loop(0, seed).eval
    end inlineLimitKeepsZeroAllocation

    private val accumulatedChain: Int < Ask =
        ArrowEffect.suspend[Any](Tag[Ask], ()).map(a => a & 63)
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
        ArrowEffect.handleLoop(Tag[Ask], accumulatedChain)([C] => _ => Loop.continue(1), a => a).eval

    @Benchmark
    def fusionAfterSuspension: Int =
        def loop(i: Int, acc: Int): Int < Ask =
            if i > NarrowDepth then acc
            else
                ArrowEffect.suspend[Any](Tag[Ask], ())
                    .map(a => (acc + a) & 63)
                    .map(v => (v + 1) & 63).map(v => (v + 1) & 63).map(v => (v + 1) & 63)
                    .map(v => (v + 1) & 63).map(v => (v + 1) & 63).map(v => (v + 1) & 63)
                    .map(v => (v + 1) & 63).map(v => (v + 1) & 63).map(v => (v + 1) & 63)
                    .map(v => loop(i + 1, v))
        ArrowEffect.handleLoop(Tag[Ask], loop(0, seed))([C] => _ => Loop.continue(1), a => a).eval
    end fusionAfterSuspension

    @Benchmark
    def partialSuspensionBaseline: Int =
        def loop(i: Int): Int < Ask =
            if i > Depth then i
            else ArrowEffect.suspend[Any](Tag[Ask], ()).map(a => loop(i + a))
        val handled: Int < Any = ArrowEffect.handleLoop(Tag[Ask], loop(seed - 1))([C] => _ => Loop.continue(1), a => a)
        Eval.partial(handled).eval
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
        ArrowEffect.handleLoop(Tag[Ask], s0(seed - 1))([C] => _ => Loop.continue(1), a => a).eval
    end sharedHandlerPaysDispatch

    @Benchmark
    def foreignCrossingsPayRotation: Int =
        def loop(i: Int): Int < (Ask & Ask2) =
            if i > Depth then i
            else ArrowEffect.suspend[Any](Tag[Ask], ()).map(a => ArrowEffect.suspend[Any](Tag[Ask2], ()).map(t => loop(i + a + t)))
        val inner: Int < Ask2 = ArrowEffect.handleCont(Tag[Ask], loop(seed - 1))([C] => (_, cont) => cont(1), a => a)
        ArrowEffect.handleCont(Tag[Ask2], inner)([C] => (_, cont) => cont(0), a => a).eval
    end foreignCrossingsPayRotation

    @Benchmark
    def foreignCrossingsAnsweredInPlace: Int =
        def loop(i: Int): Int < (Ask & Ask2) =
            if i > Depth then i
            else ArrowEffect.suspend[Any](Tag[Ask], ()).map(a => ArrowEffect.suspend[Any](Tag[Ask2], ()).map(t => loop(i + a + t)))
        val inner: Int < Ask2 = ArrowEffect.handleLoop(Tag[Ask], loop(seed - 1))([C] => _ => Loop.continue(1), a => a)
        ArrowEffect.handleLoop(Tag[Ask2], inner)([C] => _ => Loop.continue(0), a => a).eval
    end foreignCrossingsAnsweredInPlace

    @Benchmark
    def dynamicChainOfMapsStaysLinear: Int =
        var fa: Int < Any = seed
        var i             = 0
        while i < NarrowDepth do
            fa = fa.map(_ + 1)
            i += 1
        fa.eval
    end dynamicChainOfMapsStaysLinear

    @Benchmark
    def dynamicChainOfBindsStaysLinear: Int =
        var fa: Int < Any = seed
        var i             = 0
        while i < NarrowDepth do
            fa = fa.flatMap(v => (v + 1): Int < Any)
            i += 1
        fa.eval
    end dynamicChainOfBindsStaysLinear

    @Benchmark
    def pureIterationViaLoop: Int =
        Loop(seed - 1)(i => if i > Depth then Loop.done(i) else Loop.continue(i + 1)).eval

    @Benchmark
    def pureIterationViaMethod: Int =
        def loop(i: Int): Int < Any =
            if i > Depth then i
            else ((i + 1): Int < Any).map(loop)
        loop(seed - 1).eval
    end pureIterationViaMethod

    @Benchmark
    def pureIterationViaArrow: Int =
        val step = Arrow.recursive[Int, Int, Any]((self, i) =>
            if i > Depth then i
            else ((i + 1): Int < Any).map(v => self(v))
        )
        step(seed - 1).eval
    end pureIterationViaArrow

    @Benchmark
    def effectfulIterationViaLoop: Int =
        val v = Loop(seed - 1)(i => if i > Depth then Loop.done(i) else ArrowEffect.suspend[Any](Tag[Ask], ()).map(a => Loop.continue(i + a)))
        ArrowEffect.handleLoop(Tag[Ask], v)([C] => _ => Loop.continue(1), a => a).eval
    end effectfulIterationViaLoop

    @Benchmark
    def effectfulIterationViaArrow: Int =
        val step = Arrow.recursive[Int, Int, Ask]((self, i) =>
            if i > Depth then i
            else ArrowEffect.suspend[Any](Tag[Ask], ()).map(a => self(i + a))
        )
        ArrowEffect.handleLoop(Tag[Ask], step(seed - 1))([C] => _ => Loop.continue(1), a => a).eval
    end effectfulIterationViaArrow

    /** A read resolved against the outermost of three nested bindings, the scan walking past an idle handler and the
      * two inner bindings first.
      */
    @Benchmark
    def contextReadsUnderBindings: Int =
        def loop(i: Int): Int < Cfg3 =
            if i > NarrowDepth then i
            else ContextEffect.suspend(Tag[Cfg3]).map(c => loop(i + c))
        val read: Int < (Cfg3 & Ask) = loop(seed - 1)
        val idle: Int < Cfg3         = ArrowEffect.handleLoop(Tag[Ask], read)([C] => _ => Loop.continue(1), a => a)
        ContextEffect.handle(Tag[Cfg3], 1, x => x, x => x, (p, _, _) => p)(
            ContextEffect.handle(Tag[Cfg2], 2, x => x, x => x, (p, _, _) => p)(
                ContextEffect.handle(Tag[Cfg], 3, x => x, x => x, (p, _, _) => p)(idle: Int < (Cfg & Cfg2 & Cfg3))
            )
        ).eval
    end contextReadsUnderBindings

    @Benchmark
    def contextRegionsPayEntryExit: Int =
        def loop(i: Int): Int < Any =
            if i > NarrowDepth then i
            else
                ContextEffect.handle(Tag[Cfg], 1, x => x + 1, x => x, (p, _, _) => p)(ContextEffect.suspend(Tag[Cfg]))
                    .map(c => loop(i + c))
        loop(seed - 1).eval
    end contextRegionsPayEntryExit

    /** A binding installed per round that derives its value from the enclosing one, the layering `Local.let` and
      * `Env.run` are built on.
      */
    @Benchmark
    def contextRegionsDeriveFromOuter: Int =
        def loop(i: Int): Int < Cfg =
            if i > NarrowDepth then i
            else
                ContextEffect.handle(Tag[Cfg], 0, x => x + 1, x => x, (p, _, _) => p)(ContextEffect.suspend(Tag[Cfg]))
                    .map(c => loop(i + c))
        ContextEffect.handleInheritable(Tag[Cfg], 0)(loop(seed - 1)).eval
    end contextRegionsDeriveFromOuter

    /** The state half of an execution boundary without the boundary: the context regions captured, forked into a
      * parked run, and joined back, once per round.
      */
    @Benchmark
    def isolateCrossingPerRound: Int =
        def loop(i: Int): Int < Cfg =
            if i > NarrowDepth then i
            else cfgCrossing.run(ContextEffect.suspend(Tag[Cfg])).map(c => loop(i + c))
        ContextEffect.handleInheritable(Tag[Cfg], 1)(loop(seed - 1)).eval
    end isolateCrossingPerRound

    /** The same loop with the answer installed as a bound value rather than by a handler. */
    @Benchmark
    def suspensionBaselineAltInstall: Int =
        def loop(i: Int): Int < Cfg =
            if i > Depth then i
            else ContextEffect.suspend(Tag[Cfg]).map(a => loop(i + a))
        ContextEffect.handleInheritable(Tag[Cfg], 1)(loop(seed - 1)).eval
    end suspensionBaselineAltInstall

    /** The same loop reading a value bound for the whole extent rather than answered per occurrence. */
    @Benchmark
    def suspensionBaselineAltEnv: Int =
        def loop(i: Int): Int < Cfg2 =
            if i > Depth then i
            else ContextEffect.suspend(Tag[Cfg2]).map(a => loop(i + a))
        ContextEffect.handleInheritable(Tag[Cfg2], 1)(loop(seed - 1)).eval
    end suspensionBaselineAltEnv

    /** The stateful loop with the successor kept in a shared atomic cell rather than in the handler's state: the
      * clause answers 1 and advances the cell.
      */
    @Benchmark
    def statefulAnswersPaySuccessorAltRef: Int =
        def loop(i: Int): Int < Ask =
            if i > Depth then i
            else ArrowEffect.suspend[Any](Tag[Ask], ()).map(a => loop(i + a))
        ArrowEffect.handleLoop(Tag[Ask], loop(seed - 1))(
            [C] => _ => { discard(cell.incrementAndGet()); Loop.continue(1) },
            a => a
        ).eval
    end statefulAnswersPaySuccessorAltRef

    /** A resource bound and released once per round, so the round pays a region install and discharge. */
    @Benchmark
    def bracketPerRound: Int =
        def loop(i: Int, acc: Int): Int < Any =
            if i > NarrowDepth then acc
            else Bracket(acc)(a => (a + 1): Int < Any)((_, _) => ()).map(v => loop(i + 1, v))
        loop(0, seed).eval
    end bracketPerRound

    /** One resource held across the whole loop, so every step carries an outstanding region. */
    @Benchmark
    def bracketAroundLoop: Int =
        def loop(i: Int): Int < Any =
            if i > Depth then i
            else ((i + 1): Int < Any).map(loop)
        Bracket(seed)(a => loop(a - 1))((_, _) => ()).eval
    end bracketAroundLoop

    /** The release-only form, which installs its region before the body is built. */
    @Benchmark
    def bracketEnsuringOnly: Int =
        def loop(i: Int): Int < Any =
            if i > Depth then i
            else ((i + 1): Int < Any).map(loop)
        Bracket.ensuring(_ => ())(loop(seed - 1)).eval
    end bracketEnsuringOnly

    /** A transformation applied to every element of a collection. */
    @Benchmark
    def foreachOverCollection: Int =
        Kyo.foreach(elements)(a => (a + seed): Int < Any).map(_.sum).eval
    end foreachOverCollection

    /** A fold threading an accumulator through a collection. */
    @Benchmark
    def foldOverCollection: Int =
        Kyo.foldLeft(elements)(seed)((acc, a) => (acc + a): Int < Any).eval
    end foldOverCollection

    /** A fold that keeps only part of the collection, so each element decides whether it contributes. */
    @Benchmark
    def collectOverCollection: Int =
        Kyo.collect(elements)(a => (if (a & 1) == 0 then Maybe(a) else Maybe.empty): Maybe[Int] < Any).map(_.sum + seed).eval
    end collectOverCollection

end KernelBench

object KernelBench:

    inline def Depth          = 10000
    inline def NarrowDepth    = 1000
    inline def FusedDepth     = 32
    inline def FusedWideDepth = 8

    /** Below the safepoint period of 256 nested strict applications, so deepRecursionNoRescue never reifies. */
    inline def ShallowDepth = 200

    /** Between one and two periods, so deepRecursionOneRescue reifies exactly once. */
    inline def OneRescueDepth = 400

    val elements: Chunk[Int] = Chunk.from(0 until NarrowDepth)

    final case class Box(value: Int)

    sealed trait Ask extends ArrowEffect[[B] =>> Unit, [B] =>> Int]

    sealed trait Ask2 extends ArrowEffect[[B] =>> Unit, [B] =>> Int]

    sealed trait Tick extends ArrowEffect[[B] =>> Unit, [B] =>> Int]

    inline def askWith[B, S](inline f: Int => B < S)(using inline frame: Frame): B < (Ask & S) =
        ArrowEffect.suspendWith[Any](Tag[Ask], ())(f)

    def boxed[A](a: A): A < Any = a

    sealed trait Cfg extends ContextEffect[Int]

    sealed trait Cfg2 extends ContextEffect[Int]

    sealed trait Cfg3 extends ContextEffect[Int]

    /** The shared cell of statefulAnswersPaySuccessorAltRef; it accumulates across runs, as the other ports' cells do. */
    val cell: AtomicInteger = new AtomicInteger(0)

    /** The failure recoverAnswersThrow raises: allocated once and without a stack trace, so the row measures the unwind. */
    object Boom extends Exception with NoStackTrace

    /** The crossing a spawn composes in front of its isolate: `derive` yields the pass-through instance for a lone context
      * effect, and `crossing` adds the context half that forks and joins the bindings.
      */
    val cfgCrossing: Isolate[Cfg, Any, Cfg] = Isolate.derive[Cfg, Any, Cfg].crossing

end KernelBench

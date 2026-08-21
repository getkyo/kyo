package kyo.kernel.bench

import java.util.concurrent.TimeUnit
import kyo.Frame
import kyo.Tag
import kyo.kernel.*
import org.openjdk.jmh.annotations.*

/** Mirror of kyo-kernel2's KernelBench for cross-kernel comparison. Row bodies stay identical
  * to the kernel2 suite; the only divergences are the handleLoop handler shapes (this kernel's
  * handlers receive the continuation explicitly, so answering in place is
  * Loop.continue(cont(1))) and the absence of partialSuspensionBaseline (partial evaluation is
  * not a kernel-level operation here). Expectations and row documentation live in the kernel2
  * file.
  */
@State(Scope.Benchmark)
@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 2)
class KernelBench:

    given Frame = Frame.internal

    import KernelBench.*

    private var seed = 1

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
    def evalFixedOverhead: Int =
        ((seed: Int < Any).map(_ + 1)).eval

    /** The fixed floor made readable: the single-shot row sits at ~10ns, inside the harness's own
      * resolution, so its cross-kernel ratio carries no signal. This runs the same path a thousand
      * times per invocation and reports per eval; the seed varies per step and the results
      * accumulate, so no iteration can be hoisted or folded away.
      */
    @Benchmark
    @OperationsPerInvocation(1000)
    def evalFixedOverheadBatch: Int =
        var acc = 0
        var i   = 0
        while i < 1000 do
            acc += (((seed + i): Int < Any).map(_ + 1)).eval
            i += 1
        acc
    end evalFixedOverheadBatch

    @Benchmark
    def fusionAllocatesNothing: Int =
        def loop(i: Int): Int < Any =
            if i > FusedDepth then 0
            else
                ((i & 63): Int < Any)
                    .map(v => (v + 1) & 63).map(v => (v + 1) & 63).map(v => (v + 1) & 63)
                    .map(v => (v + 1) & 63).map(v => (v + 1) & 63).map(v => (v + 1) & 63)
                    .map(v => (v + 1) & 63).map(v => (v + 1) & 63).map(v => (v + 1) & 63)
                    .map(v => (v + 1) & 63)
                    .map(_ => loop(i + 1))
        loop(0).eval
    end fusionAllocatesNothing

    @Benchmark
    def fusionPastBudgetPaysRescuesOnly: Int =
        def loop(i: Int): Int < Any =
            if i > NarrowDepth then 0
            else
                ((i & 63): Int < Any)
                    .map(v => (v + 1) & 63).map(v => (v + 1) & 63).map(v => (v + 1) & 63)
                    .map(v => (v + 1) & 63).map(v => (v + 1) & 63).map(v => (v + 1) & 63)
                    .map(v => (v + 1) & 63).map(v => (v + 1) & 63).map(v => (v + 1) & 63)
                    .map(v => (v + 1) & 63)
                    .map(_ => loop(i + 1))
        loop(0).eval
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
        loop(0).eval
    end uncachedValuesPayBoxingOnly

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
        loop(Box(0)).eval.value
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
        loop(0).eval
    end inlineLimitCostsTimeNotAllocation

    @Benchmark
    def inlineLimitKeepsZeroAllocation: Int =
        def loop(i: Int): Int < Any =
            if i > FusedWideDepth then 0
            else
                ((i & 63): Int < Any)
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
                    .map(_ => loop(i + 1))
        loop(0).eval
    end inlineLimitKeepsZeroAllocation

    @Benchmark
    def continuationBodiesFuse: Int =
        def loop(i: Int): Int < Ask =
            if i > NarrowDepth then i
            else
                ask.map { a =>
                    (((i + a) & 63): Int < Any)
                        .map(v => (v + 1) & 63).map(v => (v + 1) & 63).map(v => (v + 1) & 63)
                        .map(v => (v + 1) & 63).map(v => (v + 1) & 63).map(v => (v + 1) & 63)
                        .map(v => (v + 1) & 63).map(v => (v + 1) & 63).map(v => (v + 1) & 63)
                        .map(v => (v + 1) & 63)
                        .map(_ => loop(i + 1))
                }
        ArrowEffect.handle(Tag[Ask], loop(0))([X] => (_, cont) => cont(1)).eval
    end continuationBodiesFuse

    @Benchmark
    def fusionAfterSuspensionRunOnly: Int =
        ArrowEffect.handle(Tag[Ask], accumulatedChain)([X] => (_, cont) => cont(1)).eval

    @Benchmark
    def fusionAfterSuspension: Int =
        def loop(i: Int): Int < Ask =
            if i > NarrowDepth then i
            else
                ask
                    .map(a => (i + a) & 63)
                    .map(v => (v + 1) & 63).map(v => (v + 1) & 63).map(v => (v + 1) & 63)
                    .map(v => (v + 1) & 63).map(v => (v + 1) & 63).map(v => (v + 1) & 63)
                    .map(v => (v + 1) & 63).map(v => (v + 1) & 63).map(v => (v + 1) & 63)
                    .map(_ => loop(i + 1))
        ArrowEffect.handle(Tag[Ask], loop(0))([X] => (_, cont) => cont(1)).eval
    end fusionAfterSuspension

    @Benchmark
    def deepRecursionPaysRescuesOnly: Int =
        def loop(i: Int): Int < Any =
            ((): Unit < Any).map { _ =>
                if i > Depth then 0 else loop(i + 1)
            }
        loop(0).eval
    end deepRecursionPaysRescuesOnly

    @Benchmark
    def suspensionBaseline: Int =
        def loop(i: Int): Int < Ask =
            if i > Depth then i
            else ask.map(a => loop(i + a))
        ArrowEffect.handle(Tag[Ask], loop(0))([X] => (_, cont) => cont(1)).eval
    end suspensionBaseline

    @Benchmark
    def suspensionFusesContinuation: Int =
        def loop(i: Int): Int < Ask =
            if i > Depth then i
            else askWith(a => loop(i + a))
        ArrowEffect.handle(Tag[Ask], loop(0))([X] => (_, cont) => cont(1)).eval
    end suspensionFusesContinuation

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
        ArrowEffect.handle(Tag[Ask], s0(0))([X] => (_, cont) => cont(1)).eval
    end sharedHandlerPaysDispatch

    @Benchmark
    def idleHandlerAddsNothing: Int =
        def loop(i: Int): Int < Any =
            if i > NarrowDepth then 0
            else
                ((i & 63): Int < Any)
                    .map(v => (v + 1) & 63).map(v => (v + 1) & 63).map(v => (v + 1) & 63)
                    .map(v => (v + 1) & 63).map(v => (v + 1) & 63).map(v => (v + 1) & 63)
                    .map(v => (v + 1) & 63).map(v => (v + 1) & 63).map(v => (v + 1) & 63)
                    .map(v => (v + 1) & 63)
                    .map(_ => loop(i + 1))
        ArrowEffect.handle(Tag[Ask], loop(0): Int < Ask)([X] => (_, cont) => cont(1)).eval
    end idleHandlerAddsNothing

    @Benchmark
    def handleLoopAnswersInPlace: Int =
        def loop(i: Int): Int < Ask =
            if i > Depth then i
            else ask.map(a => loop(i + a))
        ArrowEffect.handleLoop(Tag[Ask], loop(0))([X] => (_, cont) => Loop.continue(cont(1))).eval
    end handleLoopAnswersInPlace

    @Benchmark
    def statefulAnswersPaySuccessor: Int =
        def loop0(i: Int): Int < Ask =
            if i > Depth then i
            else ask.map(a => loop0(i + a))
        ArrowEffect.handleLoop(Tag[Ask], 0, loop0(0))([X] => (_, state, cont) => Loop.continue(state + 1, cont(1))).eval
    end statefulAnswersPaySuccessor

    @Benchmark
    def trailingMapsStayLinear: Int =
        def loop(i: Int): Int < Ask =
            if i > Depth then i
            else ask.map(a => loop(i + a)).map(x => x)
        ArrowEffect.handle(Tag[Ask], loop(0))([X] => (_, cont) => cont(1)).eval
    end trailingMapsStayLinear

    @Benchmark
    def foreignCrossingsPayRotation: Int =
        def loop(i: Int): Int < (Ask & Ask2) =
            if i > Depth then i
            else ask.map(a => ask2.map(t => loop(i + a + t)))
        val inner = ArrowEffect.handle(Tag[Ask], loop(0))([X] => (_, cont) => cont(1))
        ArrowEffect.handle(Tag[Ask2], inner)([X] => (_, cont) => cont(0)).eval
    end foreignCrossingsPayRotation

end KernelBench

object KernelBench:

    inline def Depth          = 10000
    inline def NarrowDepth    = 1000
    inline def FusedDepth     = 32
    inline def FusedWideDepth = 8

    final case class Box(value: Int)

    sealed trait Ask extends ArrowEffect[[B] =>> Unit, [B] =>> Int]

    sealed trait Ask2 extends ArrowEffect[[B] =>> Unit, [B] =>> Int]

    def ask(using Frame): Int < Ask = ArrowEffect.suspend[Any](Tag[Ask], ())

    inline def askWith[B](inline f: Int => B < Ask)(using inline frame: Frame): B < Ask =
        ArrowEffect.suspendWith[Any](Tag[Ask], ())(f)

    def ask2(using Frame): Int < Ask2 = ArrowEffect.suspend[Any](Tag[Ask2], ())

end KernelBench

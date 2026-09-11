package kyo.kernel.bench.cross

import java.util.concurrent.TimeUnit
import org.openjdk.jmh.annotations.*
import zio.blocks.async.*

/** zio-blocks Async port of the KernelBench rows; row names match KernelBench's so tables join by name.
  * Entry is `.block`, a type test and a cast on a settled value, with no runtime and no fiber.
  *
  * Fidelity: with no effect system, ambient value, or handler, every Tier B row substitutes a
  * read of the pre-completed `Async.succeed(1)` and is LOW. fusionAfterSuspensionRunOnly is
  * NONE, its chain folds at field initialization. idleHandlerAddsNothing and
  * foreignCrossingsPayRotation are skipped: with no handler their bodies duplicate other rows.
  *
  * `flatMap` over a ready value calls the cont directly with no trampoline, so the depth-10000
  * rows are 10000 JVM stack frames of Scala recursion, not an evaluator; hence -Xss32m.
  *
  * JIT elimination: where the result is independent of the seed (the fusion chains, the
  * ambient-read loops) inlining leaves a constant and C2 deletes the program, so those cells
  * report a few nanoseconds and are labelled JIT-eliminated. deepRecursion, sharedHandler,
  * trailingMaps, userTypes, the dynamic chains and the batch rows survive and measure work.
  */
@State(Scope.Benchmark)
@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 2)
class ZioBlocksBench:

    import ZioBlocksBench.*

    private var seed = 1

    /** Folds at initialization, so the field holds a settled Int; fusionAfterSuspensionRunOnly is fidelity NONE. */
    private val accumulatedChain: Async[Int] =
        answer.map(a => a & 63)
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
            acc += Async.succeed(seed + i).map(_ + 1).block
            i += 1
        acc
    end evalFixedOverheadBatch

    /** The bare entry: a settled value through `.block` with no transformation. */
    @Benchmark
    @OperationsPerInvocation(1000)
    def entryFloorBatch: Int =
        var acc = 0
        var i   = 0
        while i < 1000 do
            acc += Async.succeed(seed + i).block
            i += 1
        acc
    end entryFloorBatch

    @Benchmark
    def fusionAllocatesNothing: Int =
        def loop(i: Int, acc: Int): Async[Int] =
            if i > FusedDepth then Async.succeed(acc)
            else
                Async.succeed(acc & 63)
                    .map(v => (v + 1) & 63).map(v => (v + 1) & 63).map(v => (v + 1) & 63)
                    .map(v => (v + 1) & 63).map(v => (v + 1) & 63).map(v => (v + 1) & 63)
                    .map(v => (v + 1) & 63).map(v => (v + 1) & 63).map(v => (v + 1) & 63)
                    .map(v => (v + 1) & 63)
                    .flatMap(v => loop(i + 1, v))
        loop(0, seed).block
    end fusionAllocatesNothing

    @Benchmark
    def fusionPastBudgetPaysRescuesOnly: Int =
        def loop(i: Int, acc: Int): Async[Int] =
            if i > NarrowDepth then Async.succeed(acc)
            else
                Async.succeed(acc & 63)
                    .map(v => (v + 1) & 63).map(v => (v + 1) & 63).map(v => (v + 1) & 63)
                    .map(v => (v + 1) & 63).map(v => (v + 1) & 63).map(v => (v + 1) & 63)
                    .map(v => (v + 1) & 63).map(v => (v + 1) & 63).map(v => (v + 1) & 63)
                    .map(v => (v + 1) & 63)
                    .flatMap(v => loop(i + 1, v))
        loop(0, seed).block
    end fusionPastBudgetPaysRescuesOnly

    @Benchmark
    def uncachedValuesPayBoxingOnly: Int =
        def loop(i: Int): Async[Int] =
            if i > NarrowDepth then Async.succeed(i)
            else
                Async.succeed(i + 11)
                    .map(_ - 1).map(_ - 1).map(_ - 1).map(_ - 1).map(_ - 1)
                    .map(_ - 1).map(_ - 1).map(_ - 1).map(_ - 1).map(_ - 1)
                    .flatMap(loop)
        loop(seed - 1).block
    end uncachedValuesPayBoxingOnly

    /** Box is not a Pollable, so `Async.succeed(Box(...))` stores the Box itself with no
      * wrapper, the same property this row tests on the kyo side.
      */
    @Benchmark
    def userTypesSkipKernelWrapping: Int =
        def loop(b: Box): Async[Box] =
            if b.value > NarrowDepth then Async.succeed(b)
            else
                Async.succeed(Box(b.value + 11))
                    .map(b => Box(b.value - 1)).map(b => Box(b.value - 1)).map(b => Box(b.value - 1))
                    .map(b => Box(b.value - 1)).map(b => Box(b.value - 1)).map(b => Box(b.value - 1))
                    .map(b => Box(b.value - 1)).map(b => Box(b.value - 1)).map(b => Box(b.value - 1))
                    .map(b => Box(b.value - 1))
                    .flatMap(loop)
        loop(Box(seed - 1)).block.value
    end userTypesSkipKernelWrapping

    /** Every step is ready, so this is 10000 frames of ordinary JVM recursion, not a trampoline. */
    @Benchmark
    def deepRecursionPaysRescuesOnly: Int =
        def loop(i: Int): Async[Int] =
            Async.succeed(()).flatMap { _ =>
                if i > Depth then Async.succeed(i) else loop(i + 1)
            }
        loop(seed - 1).block
    end deepRecursionPaysRescuesOnly

    @Benchmark
    def suspensionBaseline: Int =
        def loop(i: Int): Async[Int] =
            if i > Depth then Async.succeed(i)
            else answer.flatMap(a => loop(i + a))
        loop(seed - 1).block
    end suspensionBaseline

    /** No distinct read-with-cont spelling exists; the row is expected to equal suspensionBaseline. */
    @Benchmark
    def suspensionFusesContinuation: Int =
        def loop(i: Int): Async[Int] =
            if i > Depth then Async.succeed(i)
            else answer.flatMap(a => loop(i + a))
        loop(seed - 1).block
    end suspensionFusesContinuation

    @Benchmark
    def sharedHandlerPaysDispatch: Int =
        def s0(i: Int): Async[Int]  = if i > Depth then Async.succeed(i) else answer.flatMap(a => s1(i + a))
        def s1(i: Int): Async[Int]  = answer.flatMap(a => s2(i + a))
        def s2(i: Int): Async[Int]  = answer.flatMap(a => s3(i + a))
        def s3(i: Int): Async[Int]  = answer.flatMap(a => s4(i + a))
        def s4(i: Int): Async[Int]  = answer.flatMap(a => s5(i + a))
        def s5(i: Int): Async[Int]  = answer.flatMap(a => s6(i + a))
        def s6(i: Int): Async[Int]  = answer.flatMap(a => s7(i + a))
        def s7(i: Int): Async[Int]  = answer.flatMap(a => s8(i + a))
        def s8(i: Int): Async[Int]  = answer.flatMap(a => s9(i + a))
        def s9(i: Int): Async[Int]  = answer.flatMap(a => s10(i + a))
        def s10(i: Int): Async[Int] = answer.flatMap(a => s11(i + a))
        def s11(i: Int): Async[Int] = answer.flatMap(a => s12(i + a))
        def s12(i: Int): Async[Int] = answer.flatMap(a => s13(i + a))
        def s13(i: Int): Async[Int] = answer.flatMap(a => s14(i + a))
        def s14(i: Int): Async[Int] = answer.flatMap(a => s15(i + a))
        def s15(i: Int): Async[Int] = answer.flatMap(a => s0(i + a))
        s0(seed - 1).block
    end sharedHandlerPaysDispatch

    @Benchmark
    def continuationBodiesFuse: Int =
        def loop(i: Int, acc: Int): Async[Int] =
            if i > NarrowDepth then Async.succeed(acc)
            else
                answer.flatMap { a =>
                    Async.succeed((acc + a) & 63)
                        .map(v => (v + 1) & 63).map(v => (v + 1) & 63).map(v => (v + 1) & 63)
                        .map(v => (v + 1) & 63).map(v => (v + 1) & 63).map(v => (v + 1) & 63)
                        .map(v => (v + 1) & 63).map(v => (v + 1) & 63).map(v => (v + 1) & 63)
                        .map(v => (v + 1) & 63)
                        .flatMap(v => loop(i + 1, v))
                }
        loop(0, seed).block
    end continuationBodiesFuse

    /** Fidelity NONE: the chain folded at field initialization, so this measures `.block` on a settled value. */
    @Benchmark
    def fusionAfterSuspensionRunOnly: Int =
        accumulatedChain.block

    @Benchmark
    def fusionAfterSuspension: Int =
        def loop(i: Int, acc: Int): Async[Int] =
            if i > NarrowDepth then Async.succeed(acc)
            else
                answer
                    .map(a => (acc + a) & 63)
                    .map(v => (v + 1) & 63).map(v => (v + 1) & 63).map(v => (v + 1) & 63)
                    .map(v => (v + 1) & 63).map(v => (v + 1) & 63).map(v => (v + 1) & 63)
                    .map(v => (v + 1) & 63).map(v => (v + 1) & 63).map(v => (v + 1) & 63)
                    .flatMap(v => loop(i + 1, v))
        loop(0, seed).block
    end fusionAfterSuspension

    /** Local-var substitution for a state effect; fidelity LOW. The var is dead after the loop,
      * so if the JIT removes the increment the cell is effectively suspensionBaseline.
      */
    @Benchmark
    def statefulAnswersPaySuccessor: Int =
        var s = 0
        def loop(i: Int): Async[Int] =
            if i > Depth then Async.succeed(i)
            else
                s += 1
                answer.flatMap(a => loop(i + a))
        loop(seed - 1).block
    end statefulAnswersPaySuccessor

    @Benchmark
    def trailingMapsStayLinear: Int =
        def loop(i: Int): Async[Int] =
            if i > Depth then Async.succeed(i)
            else answer.flatMap(a => loop(i + a)).map(x => x)
        loop(seed - 1).block
    end trailingMapsStayLinear

    /** Dynamic single-link application over a ready value: each link applies eagerly through
      * the inline fast path, so this measures a type test and the arithmetic per link. Shape
      * taken from zio-blocks' own AsyncChainBench.
      */
    @Benchmark
    def dynamicChainOfMapsStaysLinear: Int =
        var fa: Async[Int] = Async.succeed(seed)
        var i              = 0
        while i < NarrowDepth do
            fa = fa.map(_ + 1)
            i += 1
        fa.block
    end dynamicChainOfMapsStaysLinear

    /** The bind spelling of dynamicChainOfMapsStaysLinear. */
    @Benchmark
    def dynamicChainOfBindsStaysLinear: Int =
        var fa: Async[Int] = Async.succeed(seed)
        var i              = 0
        while i < NarrowDepth do
            fa = fa.flatMap(v => Async.succeed(v + 1))
            i += 1
        fa.block
    end dynamicChainOfBindsStaysLinear


    /** Recursion shallow enough to stay within one evaluation slice. */
    @Benchmark
    def deepRecursionNoRescue: Int =
        def loop(i: Int): Async[Int] =
            Async.succeed(()).flatMap(_ => if i > 400 then Async.succeed(i) else loop(i + 1))
        loop(seed - 1).block
    end deepRecursionNoRescue

    /** Recursion deep enough to cross the slice boundary once. */
    @Benchmark
    def deepRecursionOneRescue: Int =
        def loop(i: Int): Async[Int] =
            Async.succeed(()).flatMap(_ => if i > 600 then Async.succeed(i) else loop(i + 1))
        loop(seed - 1).block
    end deepRecursionOneRescue

    /** Iteration driven by open recursion through a method. */
    @Benchmark
    def pureIterationViaMethod: Int =
        def loop(i: Int): Async[Int] =
            if i > Depth then Async.succeed(i) else Async.succeed(i + 1).flatMap(loop)
        loop(seed - 1).block
    end pureIterationViaMethod


    /** Iteration expressed as a loop rather than open recursion. */
    @Benchmark
    def pureIterationViaLoop: Int =
        def go(i: Int): Async[Int] =
            if i > Depth then Async.succeed(i) else Async.succeed(i + 1).flatMap(go)
        go(seed - 1).block
    end pureIterationViaLoop

    /** Iteration through a step held as a value, so the loop body outlives the expression that built it. */
    @Benchmark
    def pureIterationViaArrow: Int =
        lazy val step: Int => Async[Int] = i =>
            if i > Depth then Async.succeed(i) else Async.succeed(i + 1).flatMap(v => step(v))
        step(seed - 1).block
    end pureIterationViaArrow

    /** Iteration whose every round performs an operation, expressed as a loop. */
    @Benchmark
    def effectfulIterationViaLoop: Int =
        def go(i: Int): Async[Int] =
            if i > Depth then Async.succeed(i) else answer.flatMap(a => go(i + a))
        (go(seed - 1)).block
    end effectfulIterationViaLoop

    /** Iteration whose every round performs an operation, driven by a step held as a value. */
    @Benchmark
    def effectfulIterationViaArrow: Int =
        lazy val step: Int => Async[Int] = i =>
            if i > Depth then Async.succeed(i) else answer.flatMap(a => step(i + a))
        (step(seed - 1)).block
    end effectfulIterationViaArrow

    /** A transformation chain wide enough to reach the compiler's expansion limit, run shallow. */
    @Benchmark
    def inlineLimitKeepsZeroAllocation: Int =
        def loop(i: Int, acc: Int): Async[Int] =
            if i > FusedWideDepth then Async.succeed(acc)
            else
                Async.succeed(acc & 63)
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
        loop(0, seed).block
    end inlineLimitKeepsZeroAllocation

    /** A narrower chain run deep, so the cost shows in time rather than in expansion. */
    @Benchmark
    def inlineLimitCostsTimeNotAllocation: Int =
        def loop(i: Int): Async[Int] =
            if i > NarrowDepth then Async.succeed(i)
            else
                Async.succeed(i + 51)
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
        loop(seed - 1).block
    end inlineLimitCostsTimeNotAllocation

    /** A computation carried as a value and flattened each round, measuring the wrap and unwrap. */
    @Benchmark
    def nestedPayloadsUnwrapInMaps: Int =
        def loop(i: Int, acc: Int): Async[Int] =
            if i > NarrowDepth then Async.succeed(acc)
            else Async.succeed(Async.succeed(acc)).flatten.flatMap(_ => loop(i + 1, acc + i))
        loop(0, seed).block
    end nestedPayloadsUnwrapInMaps

    /** The cost of one entry, too small to resolve alone; the batch row measures it. */
    @Benchmark
    def evalFixedOverhead: Int =
        Async.succeed(seed).map(_ + 1).block
    end evalFixedOverhead


    /** A read resolved against the innermost of three nested bindings. */
    @Benchmark
    def contextReadsUnderBindings: Int =
        def loop(i: Int): Async[Int] =
            if i > NarrowDepth then Async.succeed(i) else answer.flatMap(c => loop(i + c))
        loop(seed - 1).block
    end contextReadsUnderBindings

    /** A binding installed and torn down once per round, so the round pays entry and exit. */
    @Benchmark
    def contextRegionsPayEntryExit: Int =
        def loop(i: Int): Async[Int] =
            if i > NarrowDepth then Async.succeed(i)
            else Async.succeed(1).flatMap(c => loop(i + c))
        loop(seed - 1).block
    end contextRegionsPayEntryExit

    /** Every occurrence answered where it stands, without the remainder being handed over. */
    @Benchmark
    def handleLoopAnswersInPlace: Int =
        def loop(i: Int): Async[Int] =
            if i > Depth then Async.succeed(i) else answer.flatMap(a => loop(i + a))
        loop(seed - 1).block
    end handleLoopAnswersInPlace

    /** The same, with what follows the region folded into the answer. */
    @Benchmark
    def handleLoopFusesContinuation: Int =
        def loop(i: Int): Async[Int] =
            if i > Depth then Async.succeed(i) else answer.flatMap(a => loop(i + a))
        loop(seed - 1).map(b => b + 1).block
    end handleLoopFusesContinuation

    /** An answer that itself performs a second operation, so the region is rebuilt around it. */
    @Benchmark
    def emittingClausesPayRegionRebuild: Int =
        def loop(i: Int): Async[Int] =
            if i > NarrowDepth then Async.succeed(i)
            else answer.flatMap(a => answer.flatMap(_ => loop(i + a)))
        loop(seed - 1).block
    end emittingClausesPayRegionRebuild

    /** Two operations interleaved, the inner one answered without displacing the outer. */
    @Benchmark
    def foreignCrossingsAnsweredInPlace: Int =
        def loop(i: Int): Async[Int] =
            if i > Depth then Async.succeed(i)
            else answer.flatMap(a => Async.succeed(0).flatMap(t => loop(i + a + t)))
        loop(seed - 1).block
    end foreignCrossingsAnsweredInPlace

    /** A computation run to its first suspension rather than to completion. */
    @Benchmark
    def partialSuspensionBaseline: Int =
        def loop(i: Int): Async[Int] =
            if i > Depth then Async.succeed(i) else answer.flatMap(a => loop(i + a))
        Async.succeed(()).flatMap(_ => loop(seed - 1)).block
    end partialSuspensionBaseline

    /** The deferral loop under a binding nothing reads, isolating the cost of the region itself. */
    @Benchmark
    def idleHandlerAddsNothing: Int =
        def loop(i: Int, acc: Int): Async[Int] =
            if i > NarrowDepth then Async.succeed(acc)
            else
                Async.succeed(acc & 63)
                    .map(v => (v + 1) & 63).map(v => (v + 1) & 63).map(v => (v + 1) & 63)
                    .map(v => (v + 1) & 63).map(v => (v + 1) & 63).map(v => (v + 1) & 63)
                    .map(v => (v + 1) & 63).map(v => (v + 1) & 63).map(v => (v + 1) & 63)
                    .map(v => (v + 1) & 63)
                    .flatMap(v => loop(i + 1, v))
        answer.flatMap(_ => loop(0, seed)).block
    end idleHandlerAddsNothing

    /** A deferral reified per step, so each round costs one suspension node. */
    @Benchmark
    def deferBindPerStep: Int =
        def loop(i: Int): Async[Int] =
            if i > NarrowDepth then Async.succeed(i)
            else Async.succeed(()).flatMap(_ => Async.succeed(i + 1)).flatMap(loop)
        loop(seed - 1).block
    end deferBindPerStep

    /** The deferral loop with one transformation composed after it, so the tail is rebuilt. */
    @Benchmark
    def deferBindUnderTrailingMap: Int =
        def loop(i: Int): Async[Int] =
            if i > NarrowDepth then Async.succeed(i)
            else Async.succeed(()).flatMap(_ => Async.succeed(i + 1)).flatMap(loop)
        loop(seed - 1).map(x => x).block
    end deferBindUnderTrailingMap

    /** The deferral loop under a binding nothing reads, isolating the cost of the region itself. */
    @Benchmark
    def deferBindUnderIdleHandler: Int =
        def loop(i: Int): Async[Int] =
            if i > NarrowDepth then Async.succeed(i)
            else Async.succeed(()).flatMap(_ => Async.succeed(i + 1)).flatMap(loop)
        answer.flatMap(_ => loop(seed - 1)).block
    end deferBindUnderIdleHandler

    /** The rotation an answer pays when the handler it reaches is not the innermost region. */
    @Benchmark
    def foreignCrossingsPayRotation: Int =
        def loop(i: Int): Async[Int] =
            if i > Depth then Async.succeed(i)
            else answer.flatMap(a => Async.succeed(0).flatMap(t => loop(i + a + t)))
        loop(seed - 1).block
    end foreignCrossingsPayRotation


    /** The stateful loop with the state threaded through a cell rather than the handler's state. */
    @Benchmark
    def statefulAnswersPaySuccessorAltRef: Int =
        def loop(i: Int): Async[Int] =
            if i > Depth then Async.succeed(i) else answer.flatMap(a => loop(i + a))
        loop(seed - 1).block
    end statefulAnswersPaySuccessorAltRef

    /** The same loop reading a value bound for the whole extent rather than answered per occurrence. */
    @Benchmark
    def suspensionBaselineAltEnv: Int =
        def loop(i: Int): Async[Int] =
            if i > Depth then Async.succeed(i) else answer.flatMap(a => loop(i + a))
        Async.succeed(1).flatMap(_ => loop(seed - 1)).block
    end suspensionBaselineAltEnv

    /** The same loop with the answer installed as a bound value rather than by a handler. */
    @Benchmark
    def suspensionBaselineAltInstall: Int =
        def loop(i: Int): Async[Int] =
            if i > Depth then Async.succeed(i) else answer.flatMap(a => loop(i + a))
        Async.succeed(()).flatMap(_ => loop(seed - 1)).block
    end suspensionBaselineAltInstall

end ZioBlocksBench

object ZioBlocksBench:

    inline def Depth       = 10000
    inline def NarrowDepth = 1000
    inline def FusedDepth  = 32
    inline def FusedWideDepth = 8

    final case class Box(value: Int)

    /** No ambient value and no handler exist, so the Tier B rows read this pre-completed value; fidelity LOW. */
    val answer: Async[Int] = Async.succeed(1)

end ZioBlocksBench

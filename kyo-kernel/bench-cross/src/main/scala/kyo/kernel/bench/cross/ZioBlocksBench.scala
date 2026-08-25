package kyo.kernel.bench.cross

import java.util.concurrent.TimeUnit
import org.openjdk.jmh.annotations.*
import zio.blocks.async.*

/** zio-blocks Async port of the KernelBench rows, for the cross-library comparison boards. Row
  * names match KernelBench's so result tables join by name.
  *
  * Run entry: `.block`. For a settled value that is a type-test chain and a cast, effectively
  * free; there is no runtime and no fiber.
  *
  * Fidelity: zio-blocks has no effect system, no ambient value, and no handler, so every Tier B
  * row substitutes a pre-completed `Async.succeed(1)` read and is LOW fidelity by construction.
  * fusionAfterSuspensionRunOnly is NONE: `map` over a ready value evaluates at field
  * initialization, so the field holds a settled Int and the row measures `.block` on an
  * already-computed value. idleHandlerAddsNothing and foreignCrossingsPayRotation are skipped
  * (no handler concept; the bodies would duplicate other rows byte for byte).
  *
  * Cost model note: `flatMap` over a ready value calls the continuation directly, with no
  * trampoline, so the depth-10000 rows recurse on the JVM stack, one frame per level. That is
  * why this project runs with -Xss32m, and it is a finding the results tables report rather
  * than hide: those rows measure direct Scala recursion with a type test per step, not an
  * evaluator.
  *
  * JIT elimination note: on the rows whose result is independent of the seed (the fusion
  * chains and the ambient-read loops, where each level's computed value is discarded or
  * increments by a constant), full inlining leaves pure arithmetic with a constant result and
  * C2 deletes the entire program; those cells report a few nanoseconds and are labelled
  * JIT-eliminated in the results tables. The rows that survive (deepRecursion, sharedHandler,
  * trailingMaps, userTypes, the dynamic chains, the batch rows) are the zio-blocks cells that
  * measure real work. Every other library's machinery blocks this elimination; having no
  * machinery to measure is the honest zio-blocks answer to these rows.
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

    /** Folds at initialization (map over a ready value evaluates eagerly), so this field holds
      * a settled Int; fusionAfterSuspensionRunOnly's fidelity is NONE and the table says so.
      */
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
        def loop(i: Int): Async[Int] =
            if i > FusedDepth then Async.succeed(0)
            else
                Async.succeed(i & 63)
                    .map(v => (v + 1) & 63).map(v => (v + 1) & 63).map(v => (v + 1) & 63)
                    .map(v => (v + 1) & 63).map(v => (v + 1) & 63).map(v => (v + 1) & 63)
                    .map(v => (v + 1) & 63).map(v => (v + 1) & 63).map(v => (v + 1) & 63)
                    .map(v => (v + 1) & 63)
                    .flatMap(_ => loop(i + 1))
        loop(seed - 1).block
    end fusionAllocatesNothing

    @Benchmark
    def fusionPastBudgetPaysRescuesOnly: Int =
        def loop(i: Int): Async[Int] =
            if i > NarrowDepth then Async.succeed(0)
            else
                Async.succeed(i & 63)
                    .map(v => (v + 1) & 63).map(v => (v + 1) & 63).map(v => (v + 1) & 63)
                    .map(v => (v + 1) & 63).map(v => (v + 1) & 63).map(v => (v + 1) & 63)
                    .map(v => (v + 1) & 63).map(v => (v + 1) & 63).map(v => (v + 1) & 63)
                    .map(v => (v + 1) & 63)
                    .flatMap(_ => loop(i + 1))
        loop(seed - 1).block
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
      * wrapper, which is the property this row tests on the kyo side too.
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

    /** Every step is ready, so this is 10000 frames of ordinary JVM recursion, not a
      * trampoline; the row is why the project sets -Xss32m.
      */
    @Benchmark
    def deepRecursionPaysRescuesOnly: Int =
        def loop(i: Int): Async[Int] =
            Async.succeed(()).flatMap { _ =>
                if i > Depth then Async.succeed(0) else loop(i + 1)
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

    /** No distinct read-with-continuation spelling exists; expected to equal
      * suspensionBaseline, and confirming the equality is the finding.
      */
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
        def loop(i: Int): Async[Int] =
            if i > NarrowDepth then Async.succeed(i)
            else
                answer.flatMap { a =>
                    Async.succeed((i + a) & 63)
                        .map(v => (v + 1) & 63).map(v => (v + 1) & 63).map(v => (v + 1) & 63)
                        .map(v => (v + 1) & 63).map(v => (v + 1) & 63).map(v => (v + 1) & 63)
                        .map(v => (v + 1) & 63).map(v => (v + 1) & 63).map(v => (v + 1) & 63)
                        .map(v => (v + 1) & 63)
                        .flatMap(_ => loop(i + 1))
                }
        loop(seed - 1).block
    end continuationBodiesFuse

    /** Fidelity NONE: the chain folded at field initialization, so this measures `.block` on a
      * settled value. Reported with that label because "the chain folded at construction" is
      * the honest answer to what this row asks of zio-blocks.
      */
    @Benchmark
    def fusionAfterSuspensionRunOnly: Int =
        accumulatedChain.block

    @Benchmark
    def fusionAfterSuspension: Int =
        def loop(i: Int): Async[Int] =
            if i > NarrowDepth then Async.succeed(i)
            else
                answer
                    .map(a => (i + a) & 63)
                    .map(v => (v + 1) & 63).map(v => (v + 1) & 63).map(v => (v + 1) & 63)
                    .map(v => (v + 1) & 63).map(v => (v + 1) & 63).map(v => (v + 1) & 63)
                    .map(v => (v + 1) & 63).map(v => (v + 1) & 63).map(v => (v + 1) & 63)
                    .flatMap(_ => loop(i + 1))
        loop(seed - 1).block
    end fusionAfterSuspension

    /** The requirements' local-var substitution; fidelity LOW. The var is method-local and dead
      * after the loop, which is honest since there is no state effect to pay for; if the JIT
      * removes the increment the cell is effectively suspensionBaseline, and the note says so.
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
      * the inline fast path, so this measures a type test and the arithmetic per link. The
      * original shape of zio-blocks' own AsyncChainBench.
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

end ZioBlocksBench

object ZioBlocksBench:

    inline def Depth       = 10000
    inline def NarrowDepth = 1000
    inline def FusedDepth  = 32

    final case class Box(value: Int)

    /** No effect system, so no ambient value and no handler: the Tier B rows read this
      * pre-completed value; fidelity LOW, recorded per row.
      */
    val answer: Async[Int] = Async.succeed(1)

end ZioBlocksBench

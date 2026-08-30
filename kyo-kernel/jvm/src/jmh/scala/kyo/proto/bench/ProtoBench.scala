package kyo.proto.bench

import java.util.concurrent.TimeUnit
import kyo.Frame
import kyo.Tag
import kyo.proto.Arrow
import kyo.proto.Loop
import kyo.proto.kernel.*
import kyo.proto.kernel.internal.Eval
import kyo.proto.kernel.internal.Nested
import org.openjdk.jmh.annotations.*

/** The proto kernel on the workloads `kyo.kernel.bench.ProtoKernelBench` runs, row for row.
  *
  * That class measures `kyo.kernel`, not the proto: it was named when the proto was `kyo-kernel2`, and the rename that made kernel2 the
  * kernel left the name behind. Nothing under `src/jmh` referenced `kyo.proto` before this file, so the proto had no benchmark at all.
  *
  * Every row here is its counterpart's body with the imports changed, so a row-to-row ratio is the two evaluators on identical work. The one
  * deliberate difference is the entry point: `kyo.kernel`'s `Eval.apply` returns the raw payload and strips the representation itself, while
  * the proto's returns the union, so the strip is written out here to keep the two doing the same work.
  */
@State(Scope.Benchmark)
@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 2)
class ProtoBench:

    import ProtoBench.*

    private var seed = 1

    @Benchmark
    def evalFixedOverhead: Int =
        run((seed: Int < Any).map(_ + 1))

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
        run(loop(0))
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
        run(loop(0))
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
        run(loop(0))
    end uncachedValuesPayBoxingOnly

    // the NarrowBind shape from kyo-bench's arena: a deferred suspension then a bind, every step.
    // The rows above recurse over settled values, which fusion keeps allocation-free; this one pays
    // the deferral per step, which is what everyday Sync.defer code does
    @Benchmark
    def deferBindPerStep: Int =
        def loop(i: Int): Int < Any =
            if i > NarrowDepth then i
            else Effect.defer(i + 1).map(loop)
        run(loop(0))
    end deferBindPerStep

    // a region below the defer loop is free: the fold boundary stops at it, so the steady state folds
    // nothing. Pinned against deferBindUnderTrailingMap, where a standing transform is not free
    @Benchmark
    def deferBindUnderIdleHandler: Int =
        def loop(i: Int): Int < Any =
            if i > NarrowDepth then i
            else Effect.defer(i + 1).map(loop)
        run(ArrowEffect.handleCont(Tag[Ask], loop(0): Int < Ask)([C] => (_, cont) => cont(1), a => a))
    end deferBindUnderIdleHandler

    // a transform standing below the defer loop pays per step: every settled delivery folds it into a
    // chain the next push takes apart. This is Abort.run's overhead on defer chains, whose runWith
    // stands map(Result.succeed) under the body; the handler itself is free
    @Benchmark
    def deferBindUnderTrailingMap: Int =
        def loop(i: Int): Int < Any =
            if i > NarrowDepth then i
            else Effect.defer(i + 1).map(loop)
        run(loop(0).map(x => x))
    end deferBindUnderTrailingMap

    @Benchmark
    def deepRecursionPaysRescuesOnly: Int =
        def loop(i: Int): Int < Any =
            ((): Unit < Any).map { _ =>
                if i > Depth then 0 else loop(i + 1)
            }
        run(loop(0))
    end deepRecursionPaysRescuesOnly

    // the two sides of the rescue boundary: 400 stays inside one budget, 600 crosses it once
    @Benchmark
    def deepRecursionNoRescue: Int =
        def loop(i: Int): Int < Any =
            ((): Unit < Any).map { _ =>
                if i > 400 then 0 else loop(i + 1)
            }
        run(loop(0))
    end deepRecursionNoRescue

    @Benchmark
    def deepRecursionOneRescue: Int =
        def loop(i: Int): Int < Any =
            ((): Unit < Any).map { _ =>
                if i > 600 then 0 else loop(i + 1)
            }
        run(loop(0))
    end deepRecursionOneRescue

    @Benchmark
    def suspensionBaseline: Int =
        def loop(i: Int): Int < Ask =
            if i > Depth then i
            else ask.map(a => loop(i + a))
        run(ArrowEffect.handleCont(Tag[Ask], loop(0))([C] => (_, cont) => cont(1), a => a))
    end suspensionBaseline

    @Benchmark
    def suspensionFusesContinuation: Int =
        def loop(i: Int): Int < Ask =
            if i > Depth then i
            else askWith(a => loop(i + a))
        run(ArrowEffect.handleCont(Tag[Ask], loop(0))([C] => (_, cont) => cont(1), a => a))
    end suspensionFusesContinuation

    @Benchmark
    def handleLoopAnswersInPlace: Int =
        def loop(i: Int): Int < Ask =
            if i > Depth then i
            else ask.map(a => loop(i + a))
        run(ArrowEffect.handleLoop(Tag[Ask], loop(0))([C] => _ => Loop.continue((), 1: Int < Any), a => a))
    end handleLoopAnswersInPlace

    @Benchmark
    def handleLoopFusesContinuation: Int =
        def loop(i: Int): Int < Ask =
            if i > Depth then i
            else ask.map(a => loop(i + a))
        run(
            ArrowEffect.handleLoopWith(Tag[Ask], loop(0))(
                [C] => _ => Loop.continue((), 1: Int < Any),
                a => a
            )(b => b + 1)
        )
    end handleLoopFusesContinuation

    @Benchmark
    def nestedPayloadsUnwrapInMaps: Int =
        def loop(i: Int): Int < Any =
            if i > NarrowDepth then 0
            else boxed(ask).map(_ => loop(i + 1))
        run(loop(0))
    end nestedPayloadsUnwrapInMaps

    @Benchmark
    def statefulAnswersPaySuccessor: Int =
        def loop(i: Int): Int < Ask =
            if i > Depth then i
            else ask.map(a => loop(i + a))
        run(
            ArrowEffect.handleLoopState(Tag[Ask], 0, loop(0))(
                [C] => (state, _) => Loop.continue(state + 1, 1: Int < Any),
                (_, a) => a
            )
        )
    end statefulAnswersPaySuccessor

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
        run(ArrowEffect.handleCont(Tag[Ask], loop(0): Int < Ask)([C] => (_, cont) => cont(1), a => a))
    end idleHandlerAddsNothing

    @Benchmark
    def trailingMapsStayLinear: Int =
        def loop(i: Int): Int < Ask =
            if i > Depth then i
            else ask.map(a => loop(i + a)).map(x => x)
        run(ArrowEffect.handleCont(Tag[Ask], loop(0))([C] => (_, cont) => cont(1), a => a))
    end trailingMapsStayLinear

    @Benchmark
    def emittingClausesPayRegionRebuild: Int =
        def loop(i: Int): Int < Ask =
            if i > NarrowDepth then i
            else ask.map(a => loop(i + a))
        val emitted: Int < Tick = ArrowEffect.handleLoop(Tag[Ask], loop(0))(
            [C] => _ => tick.map(t => Loop.continue((), t: Int < Any)),
            a => a
        )
        run(ArrowEffect.handleLoop(Tag[Tick], emitted)([C] => _ => Loop.continue((), 1: Int < Any), a => a))
    end emittingClausesPayRegionRebuild

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
        run(ArrowEffect.handleCont(Tag[Ask], loop(0))([C] => (_, cont) => cont(1), a => a))
    end continuationBodiesFuse

end ProtoBench

object ProtoBench:

    inline def Depth       = 10000
    inline def NarrowDepth = 1000
    inline def FusedDepth  = 32

    /** The eval plus the strip its counterpart's eval performs internally, so a row measures the same work on both sides rather than one
      * side's missing step.
      */
    def run(v: Int < Any): Int = Nested.unnest[Int](Eval(v))

    sealed trait Ask extends ArrowEffect[[B] =>> Unit, [B] =>> Int]

    def ask(using Frame): Int < Ask = ArrowEffect.suspend[Any](Tag[Ask], ())

    sealed trait Tick extends ArrowEffect[[B] =>> Unit, [B] =>> Int]

    def tick(using Frame): Int < Tick = ArrowEffect.suspend[Any](Tag[Tick], ())

    inline def askWith[B, S](inline f: Int => B < S)(using inline frame: Frame): B < (Ask & S) =
        ArrowEffect.suspendWith[Any](Tag[Ask], ())(f)

    def boxed[A](a: A): A < Any = a

end ProtoBench

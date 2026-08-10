package kyo.kernel.bench

import java.util.concurrent.TimeUnit
import kyo.Frame
import kyo.Tag
import kyo.kernel.*
import org.openjdk.jmh.annotations.*

/** Fusion ladder: each benchmark isolates one property or one limit of fused execution. Run
  * with `-prof gc`; the expectations below are in gc.alloc.rate.norm terms.
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

    /** The fixed floor: one settled map and one eval. Expect a few nanoseconds (slot lookup,
      * save, restore, one fused step) and zero allocation.
      */
    @Benchmark
    def singleMap: Int =
        ((seed: Int < Any).map(_ + 1)).eval

    /** Pure fusion: 396 cache-resident steps inside one safepoint window. Expect zero
      * allocation and every mapLoop site inlined hot into one C2 region.
      */
    @Benchmark
    def fusedBindMap: Int =
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
    end fusedBindMap

    /** The trampoline: the same chain past the budget (12012 steps). Expect rescue allocation
      * only, one Defer plus its arrow per Period steps, about 736 bytes per op.
      */
    @Benchmark
    def cachedBindMap: Int =
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
    end cachedBindMap

    /** Value boxing: values outside the Integer cache. Expect the delta against cachedBindMap
      * to be Integer.valueOf traffic only, about one 14-byte box per map.
      */
    @Benchmark
    def narrowBindMap: Int =
        def loop(i: Int): Int < Any =
            if i > NarrowDepth then i
            else
                ((i + 11): Int < Any)
                    .map(_ - 1).map(_ - 1).map(_ - 1).map(_ - 1).map(_ - 1)
                    .map(_ - 1).map(_ - 1).map(_ - 1).map(_ - 1).map(_ - 1)
                    .map(loop)
        loop(0).eval
    end narrowBindMap

    /** Generic lift and unnest arms: a case class flows through the chain, nothing wraps.
      * Expect single-digit percent over narrowBindMap, allocation the Box instances.
      */
    @Benchmark
    def boxedBindMap: Int =
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
    end boxedBindMap

    /** JIT region limit: 51 sites per iteration overflow C2's caller inline budget. Expect
      * higher per-map time than narrowBindMap at the same per-map allocation.
      */
    @Benchmark
    def wideBindMap: Int =
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
    end wideBindMap

    /** JIT region limit without boxing: expect zero allocation at a higher per-step time than
      * fusedBindMap; fusion as no-reification survives where JIT inlining stops.
      */
    @Benchmark
    def fusedWideBindMap: Int =
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
    end fusedWideBindMap

    /** Fusion inside continuations: the chain runs settled after each answer. Expect
      * suspension-machinery allocation only; the inner ten maps contribute nothing.
      */
    @Benchmark
    def resumedBindMap: Int =
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
    end resumedBindMap

    /** Reification limit: maps attached to a pending suspension cannot fuse. Expect a Suspend
      * wrapper, a chain node, and the arrow per attach, and step dispatch on resume.
      */
    @Benchmark
    def attachedBindMap: Int =
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
    end attachedBindMap

    /** Trampolined recursion through settled unit maps. Expect rescue pairs only, 760 bytes
      * per ten thousand binds.
      */
    @Benchmark
    def deepBind: Int =
        def loop(i: Int): Int < Any =
            ((): Unit < Any).map { _ =>
                if i > Depth then 0 else loop(i + 1)
            }
        loop(0).eval
    end deepBind

    /** Suspension baseline: one suspend, answer, resume cycle per step. Expect about 48 bytes
      * of real suspension data per operation.
      */
    @Benchmark
    def effectOps: Int =
        def loop(i: Int): Int < Ask =
            if i > Depth then i
            else ask.map(a => loop(i + a))
        ArrowEffect.handle(Tag[Ask], loop(0))([X] => (_, cont) => cont(1)).eval
    end effectOps

    /** suspendWith: the suspension is its own continuation, one object per operation. Expect
      * effectOps semantics at roughly a third of the allocation.
      */
    @Benchmark
    def suspendWithOps: Int =
        def loop(i: Int): Int < Ask =
            if i > Depth then i
            else askWith(a => loop(i + a))
        ArrowEffect.handle(Tag[Ask], loop(0))([X] => (_, cont) => cont(1)).eval
    end suspendWithOps

    /** Idle handler: the cachedBindMap chain under a handler whose effect never occurs. Expect
      * cachedBindMap numbers; the handler only relays the budget rescues.
      */
    @Benchmark
    def handledBindMap: Int =
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
    end handledBindMap

    /** Context provision: resume answers every operation in place. Expect effectOps numbers;
      * the answer closure handle builds does not survive escape analysis, so the two match.
      */
    @Benchmark
    def contextOps: Int =
        def loop(i: Int): Int < Ask =
            if i > Depth then i
            else ask.map(a => loop(i + a))
        ArrowEffect.resume(Tag[Ask], loop(0))([X] => _ => 1).eval
    end contextOps

    /** State threading: loop carries state through every answer. Expect effectOps plus a tuple
      * per operation.
      */
    @Benchmark
    def stateOps: Int =
        def loop0(i: Int): Int < Ask =
            if i > Depth then i
            else ask.map(a => loop0(i + a))
        ArrowEffect.loop(Tag[Ask], 0, loop0(0))([X] => (_, state, cont) => (state + 1, cont(1))).eval._2
    end stateOps

    /** Issue 531's shape: a for comprehension leaves a trailing map after each recursive effect
      * step, so the pending chain grows by one transform per level and re-attaches on every
      * answer. Expect linear cost, about 75ns and 250 bytes per level (the level's suspension,
      * the trailing attach, the answer-time re-attach of the accumulated remainder, and the
      * one-time end flatten); any superlinear re-walk of the chain blows this row up.
      */
    @Benchmark
    def trailingMapOps: Int =
        def loop(i: Int): Int < Ask =
            if i > Depth then i
            else ask.map(a => loop(i + a)).map(x => x)
        ArrowEffect.handle(Tag[Ask], loop(0))([X] => (_, cont) => cont(1)).eval
    end trailingMapOps

    /** Rotation: two effects alternate, so every outer operation crosses the inner handler and
      * re-attaches it. Expect effectOps rate for the handled operations plus a Suspend wrapper,
      * chain node, and arrow per crossing.
      */
    @Benchmark
    def crossedOps: Int =
        def loop(i: Int): Int < (Ask & Ask2) =
            if i > Depth then i
            else ask.map(a => ask2.map(t => loop(i + a + t)))
        val inner = ArrowEffect.handle(Tag[Ask], loop(0))([X] => (_, cont) => cont(1))
        ArrowEffect.handle(Tag[Ask2], inner)([X] => (_, cont) => cont(0)).eval
    end crossedOps

end KernelBench

object KernelBench:

    inline def Depth          = 10000
    inline def NarrowDepth    = 1000
    inline def FusedDepth     = 32
    inline def FusedWideDepth = 8

    final case class Box(value: Int)

    sealed trait Ask extends ArrowEffect[[B] =>> Unit, [B] =>> Int]

    sealed trait Ask2 extends ArrowEffect[[B] =>> Unit, [B] =>> Int]

    def ask(using Frame): Int < Ask = ArrowEffect.suspend[[B] =>> Unit, [B] =>> Int, Ask, Any](Tag[Ask], ())

    inline def askWith[B](inline f: Int => B < Ask)(using inline frame: Frame): B < Ask =
        ArrowEffect.suspendWith[[B2] =>> Unit, [B2] =>> Int, Ask, Any, B, Ask](Tag[Ask], ())(f)

    def ask2(using Frame): Int < Ask2 = ArrowEffect.suspend[[B] =>> Unit, [B] =>> Int, Ask2, Any](Tag[Ask2], ())

end KernelBench

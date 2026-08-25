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

    /** The fixed floor: one settled map and one eval. Expect a few nanoseconds (slot lookup,
      * save, restore, one fused step) and zero allocation.
      */
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

    /** The bare entry: a settled value through eval with no transformation, so the fixed
      * per-run cost is visible in the same units as every other row. The cross-library boards
      * carry the same row over their own entries.
      */
    @Benchmark
    @OperationsPerInvocation(1000)
    def entryFloorBatch: Int =
        var acc = 0
        var i   = 0
        while i < 1000 do
            acc += ((seed + i): Int < Any).eval
            i += 1
        acc

    /** Pure fusion: 396 cache-resident steps inside one safepoint window. Expect zero
      * allocation and every mapLoop site inlined hot into one C2 region.
      */
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
        loop(seed - 1).eval
    end fusionAllocatesNothing

    /** The trampoline: the same chain past the budget (12012 steps). Expect rescue allocation
      * only, one Defer plus its arrow per Period steps, about 736 bytes per op.
      */
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
        loop(seed - 1).eval
    end fusionPastBudgetPaysRescuesOnly

    /** Value boxing: values outside the Integer cache. Expect the delta against fusionPastBudgetPaysRescuesOnly
      * to be Integer.valueOf traffic only, about one 14-byte box per map.
      */
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

    /** Generic lift and unnest arms: a case class flows through the chain, nothing wraps.
      * Expect single-digit percent over uncachedValuesPayBoxingOnly, allocation the Box instances.
      */
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

    /** JIT region limit: 51 sites per iteration overflow C2's caller inline budget. Expect
      * higher per-map time than uncachedValuesPayBoxingOnly at the same per-map allocation.
      */
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

    /** JIT region limit without boxing: expect zero allocation at a higher per-step time than
      * fusionAllocatesNothing; fusion as no-reification survives where JIT inlining stops.
      */
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
        loop(seed - 1).eval
    end inlineLimitKeepsZeroAllocation

    /** Fusion inside continuations: the chain runs settled after each answer. Expect
      * suspension-machinery allocation only; the inner ten maps contribute nothing.
      */
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
        ArrowEffect.handleCont(Tag[Ask], loop(seed - 1))([X] => (_, cont) => cont(1), a => a).eval
    end continuationBodiesFuse

    /** Fusion after suspension, execution only: a suspension with fifty transformations chained
      * after it is built once outside the timed region, so the op measures answering the
      * suspension and running the stored chain, nothing else. Expect zero allocation and a
      * per-step time near the fused rate: each stored transformation calls the next through a
      * call site that only ever sees one target, so the JIT can fuse the answered chain the
      * same way it fuses eager execution.
      */
    @Benchmark
    def fusionAfterSuspensionRunOnly: Int =
        ArrowEffect.handleCont(Tag[Ask], accumulatedChain)([X] => (_, cont) => cont(1), a => a).eval

    /** Fusion after suspension, whole cycle: a suspension with ten transformations chained
      * after it, answered by the handler, built and run per iteration. Storing each
      * transformation on the unanswered suspension allocates (there is no value to run against
      * yet); once answered, the chain's execution is expected to fuse. The run-only row
      * isolates that execution.
      */
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
        ArrowEffect.handleCont(Tag[Ask], loop(seed - 1))([X] => (_, cont) => cont(1), a => a).eval
    end fusionAfterSuspension

    /** Trampolined recursion through settled unit maps. Expect rescue pairs only, 760 bytes
      * per ten thousand binds.
      */
    @Benchmark
    def deepRecursionPaysRescuesOnly: Int =
        def loop(i: Int): Int < Any =
            ((): Unit < Any).map { _ =>
                if i > Depth then 0 else loop(i + 1)
            }
        loop(seed - 1).eval
    end deepRecursionPaysRescuesOnly

    /** Suspension baseline: one suspend, answer, resume cycle per step. Expect about 48 bytes
      * of real suspension data per operation.
      */
    @Benchmark
    def suspensionBaseline: Int =
        def loop(i: Int): Int < Ask =
            if i > Depth then i
            else ask.map(a => loop(i + a))
        ArrowEffect.handleCont(Tag[Ask], loop(seed - 1))([X] => (_, cont) => cont(1), a => a).eval
    end suspensionBaseline

    /** suspendWith: suspend followed by map, so the operation carries its continuation as a
      * deferred step. Expect suspensionBaseline semantics.
      */
    @Benchmark
    def suspensionFusesContinuation: Int =
        def loop(i: Int): Int < Ask =
            if i > Depth then i
            else askWith(a => loop(i + a))
        ArrowEffect.handleCont(Tag[Ask], loop(seed - 1))([X] => (_, cont) => cont(1), a => a).eval
    end suspensionFusesContinuation

    /** suspensionBaseline evaluated through Eval.partial, the scheduler entry: measures the partial
      * mode's cost including the periodic preemption check on the budget path.
      */
    // Waits on Eval.partial, which this kernel does not have yet. Kept rather than deleted so the
    // row returns with the surface instead of being rediscovered.
    // @Benchmark
    // def partialSuspensionBaseline: Int =
    //     def loop(i: Int): Int < Ask =
    //         if i > Depth then i
    //         else ask.map(a => loop(i + a))
    //     val handled = ArrowEffect.handleCont(Tag[Ask], loop(seed - 1))([X] => (_, cont) => cont(1), a => a)
    //     kyo.kernel.internal.Eval.partial(handled).eval
    // end partialSuspensionBaseline

    /** Shared handler limit: 16 distinct askWith sites resolve through one handler loop, so its
      * tag, input, and cont sites overflow the receiver profile and stay virtual calls. Expect
      * suspensionFusesContinuation allocation at roughly 2.8x the time, all of it dispatch.
      */
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
        ArrowEffect.handleCont(Tag[Ask], s0(seed - 1))([X] => (_, cont) => cont(1), a => a).eval
    end sharedHandlerPaysDispatch

    /** Idle handler: the fusionPastBudgetPaysRescuesOnly chain under a handler whose effect never occurs. Expect
      * fusionPastBudgetPaysRescuesOnly numbers; the handler only relays the budget rescues.
      */
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
        ArrowEffect.handleCont(Tag[Ask], loop(seed - 1): Int < Ask)([X] => (_, cont) => cont(1), a => a).eval
    end idleHandlerAddsNothing

    /** Context provision: an answering handler resolves every operation in place. Expect
      * suspensionBaseline numbers plus the outcome box per answer.
      */
    @Benchmark
    def handleLoopAnswersInPlace: Int =
        def loop(i: Int): Int < Ask =
            if i > Depth then i
            else ask.map(a => loop(i + a))
        // the clause answers at the region's row, so the resumed value is ascribed pending
        ArrowEffect.handleLoop(Tag[Ask], loop(seed - 1))([X] => _ => Loop.continue(1: Int < Any), a => a).eval
    end handleLoopAnswersInPlace

    /** State threading: the handler advances state through every answer. Expect
      * handleLoopAnswersInPlace plus a successor handler per state change.
      */
    @Benchmark
    def statefulAnswersPaySuccessor: Int =
        def loop0(i: Int): Int < Ask =
            if i > Depth then i
            else ask.map(a => loop0(i + a))
        // the stateful region is handleLoopState here, and the clause hands the answer back in the
        // outcome rather than applying a continuation it was passed. Same state per answer
        ArrowEffect.handleLoopState(Tag[Ask], 0, loop0(0))(
            [X] => (state, _) => Loop.continue(state + 1, 1: Int < Any),
            (_, a) => a
        ).eval
    end statefulAnswersPaySuccessor

    /** Issue 531's shape: a for comprehension leaves a trailing map after each recursive effect
      * step, so the pending chain grows by one transform per level and re-attaches on every
      * answer. Expect linear cost, about 75ns and 250 bytes per level (the level's suspension,
      * the trailing attach, the answer-time re-attach of the accumulated remainder, and the
      * one-time end flatten); any superlinear re-walk of the chain blows this row up.
      */
    @Benchmark
    def trailingMapsStayLinear: Int =
        def loop(i: Int): Int < Ask =
            if i > Depth then i
            else ask.map(a => loop(i + a)).map(x => x)
        ArrowEffect.handleCont(Tag[Ask], loop(seed - 1))([X] => (_, cont) => cont(1), a => a).eval
    end trailingMapsStayLinear

    /** Rotation: two effects alternate, so every outer operation crosses the inner handler and
      * re-attaches it. Expect suspensionBaseline rate for the handled operations plus a Suspend wrapper,
      * chain node, and arrow per crossing.
      */
    @Benchmark
    def foreignCrossingsPayRotation: Int =
        def loop(i: Int): Int < (Ask & Ask2) =
            if i > Depth then i
            else ask.map(a => ask2.map(t => loop(i + a + t)))
        val inner = ArrowEffect.handleCont(Tag[Ask], loop(seed - 1))([X] => (_, cont) => cont(1), a => a)
        ArrowEffect.handleCont(Tag[Ask2], inner)([X] => (_, cont) => cont(0), a => a).eval
    end foreignCrossingsPayRotation

    /** Dynamic single-link application: NarrowDepth map links attached to a settled carrier in
      * a runtime loop, one link per call site visit, then one eval. Nothing accumulates here:
      * the settled fast arm applies each link eagerly, so expect Integer boxing only (the
      * values leave the cache; about 14 bytes per link) and arithmetic cost per link, with no
      * kernel allocation. Libraries that reify a node per link pay their interpreter on this
      * row instead. Shape adopted from zio-blocks' AsyncChainBench.
      */
    @Benchmark
    def dynamicChainOfMapsStaysLinear: Int =
        var fa: Int < Any = seed
        var i             = 0
        while i < NarrowDepth do
            fa = fa.map(_ + 1)
            i += 1
        fa.eval
    end dynamicChainOfMapsStaysLinear

    /** The bind spelling of dynamicChainOfMapsStaysLinear: each link lifts its result, so the
      * settled fast arm must see through the lifted carrier as well. Same expectation.
      */
    @Benchmark
    def dynamicChainOfBindsStaysLinear: Int =
        var fa: Int < Any = seed
        var i             = 0
        while i < NarrowDepth do
            fa = fa.flatMap(v => (v + 1): Int < Any)
            i += 1
        fa.eval
    end dynamicChainOfBindsStaysLinear

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

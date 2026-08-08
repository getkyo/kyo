package kyo.kernel2.proto

// TEMPORARY experiment E3 for the rotation/handlers design: runs 11 reference
// programs through the proto implementation (this package) and through the OLD
// kernel in the same process, comparing results program by program. Deleted once
// the design round closes; results recorded in kernel2-rotation-handlers-design.md.

import kyo.Tag
import kyo.kernel.ArrowEffect as OldArrow
import kyo.kernel.ContextEffect as OldContext
import kyo.kernel.Effect as OldEffect
import kyo.kernel2.Const
import scala.language.implicitConversions

object RotationProbeMain:

    // ==== the proto effects the programs use ====

    sealed trait PEnv extends ContextEffect[Int]
    sealed trait PCtx extends ArrowEffect[Const[Unit], Const[Int]]
    sealed trait PT   extends ArrowEffect[Const[Int], Const[Int]]
    sealed trait PT2  extends ArrowEffect[Const[Int], Const[Int]]
    sealed trait PS   extends ArrowEffect[Const[Int], Const[Int]]

    def penv: Int < PEnv       = ContextEffect.suspend(Tag[PEnv])
    def pctx: Int < PCtx       = ArrowEffect.suspend(Tag[PCtx], ())
    def pt(i: Int): Int < PT   = ArrowEffect.suspend(Tag[PT], i)
    def pt2(i: Int): Int < PT2 = ArrowEffect.suspend(Tag[PT2], i)
    def psop(i: Int): Int < PS = ArrowEffect.suspend(Tag[PS], i)

    /** The old kernel, same process, as the reference semantics. */
    object oldref:

        given kyo.Frame = kyo.Frame.internal

        sealed trait OEnv extends OldContext[Int]
        sealed trait OCtx extends OldArrow[kyo.Const[Unit], kyo.Const[Int]]
        sealed trait OT   extends OldArrow[kyo.Const[Int], kyo.Const[Int]]
        sealed trait OT2  extends OldArrow[kyo.Const[Int], kyo.Const[Int]]
        sealed trait OS   extends OldArrow[kyo.Const[Int], kyo.Const[Int]]

        def oenv: kyo.kernel.<[Int, OEnv]       = OldContext.suspend(Tag[OEnv])
        def octx: kyo.kernel.<[Int, OCtx]       = OldArrow.suspend[Any](Tag[OCtx], ())
        def ot(i: Int): kyo.kernel.<[Int, OT]   = OldArrow.suspend[Any](Tag[OT], i)
        def ot2(i: Int): kyo.kernel.<[Int, OT2] = OldArrow.suspend[Any](Tag[OT2], i)
        def osop(i: Int): kyo.kernel.<[Int, OS] = OldArrow.suspend[Any](Tag[OS], i)

        def pureOld[A2](a: A2): kyo.kernel.<[A2, Any] = a

    end oldref

    var failures = 0

    def check(name: String)(m: => Any, r: => Any): Unit =
        def capture(body: => Any): Any =
            try body
            catch case ex: Throwable => s"thrown ${ex.getClass.getSimpleName}: ${ex.getMessage}"
        val mv = capture(m)
        val rv = capture(r)
        val ok = mv.equals(rv) // universal equals: the build compiles with strict equality
        if !ok then failures += 1
        println(f"$name%-28s proto=$mv%-34s ref=$rv%-34s ${if ok then "OK" else "MISMATCH"}")
    end check

    def main(args: Array[String]): Unit =
        import oldref.*
        import oldref.given

        // p1: THE red clause-scope test: a clause's env read resolves OUTSIDE its own region
        check("p1-clause-scope-outer")(
            {
                val program: Int < (PCtx & PEnv) = pctx.map(_ + 1)
                ContextEffect.handle(Tag[PEnv], 3)(
                    ArrowEffect.handleResume(Tag[PCtx], program)([C] => (_: Unit) => penv.map(_ * 2))
                ).eval
            },
            OldContext.handle(Tag[OEnv], 3)(
                OldArrow.handle(Tag[OCtx], octx.map(_ + 1))(
                    [C] => (_, cont) => oenv.map(v => cont(v * 2))
                )
            ).eval
        )

        // p2: a rebind INSIDE the region must not leak into the clause; reads inside see it
        check("p2-clause-scope-rebind")(
            {
                val inner: Int < (PCtx & PEnv) =
                    ContextEffect.handle(Tag[PEnv], 42)(pctx.flatMap(x => penv.map(e => x * 1000 + e)))
                ContextEffect.handle(Tag[PEnv], 100)(
                    ArrowEffect.handleResume(Tag[PCtx], inner)([C] => (_: Unit) => penv.map(_ * 2))
                ).eval
            },
            OldContext.handle(Tag[OEnv], 100)(
                OldArrow.handle(Tag[OCtx], OldContext.handle(Tag[OEnv], 42)(octx.flatMap(x => oenv.map(e => x * 1000 + e))))(
                    [C] => (_, cont) => oenv.map(v => cont(v * 2))
                )
            ).eval
        )

        // p3: a throw inside the guarded region is caught
        check("p3-catch-inside")(
            Effect.catching(pure(1).map(x => (throw new RuntimeException("boom")): Int))(_ => pure(-1)).eval,
            OldEffect.catching(pureOld(1).map(x => (throw new RuntimeException("boom")): Int))(_ => -1).eval
        )

        // p4: a throw OUTSIDE the guarded region escapes (kernel2's flat chain wrongly catches this today)
        check("p4-catch-outside-escapes")(
            Effect.catching(pure(1))(_ => pure(-1)).map(_ => (throw new RuntimeException("boom")): Int).eval,
            OldEffect.catching(pureOld(1))(_ => -1).map(_ => (throw new RuntimeException("boom")): Int).eval
        )

        // p5: a throw after a park and resume inside the guarded region is still caught (guard rotation)
        check("p5-catch-after-resume")(
            {
                val guarded: Int < PT =
                    Effect.catching(pt(1).map(x => if x > 0 then throw new RuntimeException("boom2") else x))(_ => pure(-7))
                ArrowEffect.handle(Tag[PT], guarded)([C] => (in, cont) => cont(in + 4)).eval
            },
            OldArrow.handle(
                Tag[OT],
                OldEffect.catching(ot(1).map(x => if x > 0 then throw new RuntimeException("boom2") else x))(_ => -7)
            )([C] => (in, cont) => cont(in + 4)).eval
        )

        // p6: multi-shot: the captured continuation replays with consistent reads; the proto
        // capture is TYPED (handleFirst returns the continuation as a first-class value)
        check("p6-multi-shot")(
            {
                val program: Int < (PCtx & PEnv) = pctx.flatMap(a => penv.map(b => a + b))
                val bound: Int < PCtx            = ContextEffect.handle(Tag[PEnv], 5)(program)
                val k = ArrowEffect.handleFirst(Tag[PCtx], bound)(
                    [C] => (_, cont) => pure(cont),
                    a => pure((_: Int) => pure(a)) // unreachable: the program always suspends
                ).eval
                val results = (
                    k(1).asInstanceOf[Int < Any].eval,
                    k(10).asInstanceOf[Int < Any].eval,
                    k(20).asInstanceOf[Int < Any].eval
                )
                results
            }, {
                val program       = octx.flatMap(a => oenv.map(b => a + b))
                val bound         = OldContext.handle(Tag[OEnv], 5)(program)
                var captured: Any = null
                val first = OldArrow.handle(Tag[OCtx], bound)(
                    [C] =>
                        (in, cont) =>
                            captured = (o: Int) => cont(o)
                            cont(1)
                ).eval
                val k = captured.asInstanceOf[Int => kyo.kernel.<[Int, Any]]
                (first, k(10).eval, k(20).eval)
            }
        )

        // p7: rotation across a foreign handler: an op parks past an unrelated region, which
        // re-wraps; after resumption the crossed region still handles its own ops
        check("p7-rotation-foreign")(
            {
                val inner: Int < (PT & PT2) = pt2(5).flatMap(x => pt(x + 1).map(_ * 10))
                val t1: Int < PT2           = ArrowEffect.handle(Tag[PT], inner)([C] => (in, cont) => cont(in + 100))
                ArrowEffect.handle(Tag[PT2], t1)([C] => (in, cont) => cont(in * 2)).eval
            },
            OldArrow.handle(
                Tag[OT2],
                OldArrow.handle(Tag[OT], ot2(5).flatMap(x => ot(x + 1).map(_ * 10)))(
                    [C] => (in, cont) => cont(in + 100)
                )
            )([C] => (in, cont) => cont(in * 2)).eval
        )

        // p8: Stop discards the region remainder without capture, crossing (and dropping) an
        // inner Catching region; the region's outside continuation still runs
        check("p8-stop-discards")(
            {
                val region: Int < PS = Effect.catching(psop(5).map(_ + 999))(_ => pure(-1))
                ArrowEffect.handleStop(Tag[PS], region)([C] => (in: Int) => pure(in * 3)).map(_ + 1).eval
            },
            OldArrow.handle(
                Tag[OS],
                OldEffect.catching(osop(5).map(_ + 999))(_ => -1)
            )([C] => (in, _) => pureOld(in * 3)).map(_ + 1).eval
        )

        // p9: the crown case: a Resume clause parks mid-clause on an outer handler, and its
        // post-resume env read still resolves at the clause's scope, while an inner rebind
        // stays invisible throughout
        check("p9-clause-park-resume")(
            {
                val inner: Int < (PCtx & PEnv & PT) = ContextEffect.handle(Tag[PEnv], 42)(pctx)
                val handled: Int < (PEnv & PT) = ArrowEffect.handleResume(Tag[PCtx], inner)(
                    [C] => (_: Unit) => pt(7).flatMap(x => penv.map(e => x * 1000 + e))
                )
                val bound: Int < PT = ContextEffect.handle(Tag[PEnv], 3)(handled)
                ArrowEffect.handle(Tag[PT], bound)([C] => (in, cont) => cont(in + 1)).eval
            },
            OldArrow.handle(
                Tag[OT],
                OldContext.handle(Tag[OEnv], 3)(
                    OldArrow.handle(Tag[OCtx], OldContext.handle(Tag[OEnv], 42)(octx))(
                        [C] => (_, cont) => ot(7).flatMap(x => oenv.map(e => cont(x * 1000 + e)))
                    )
                )
            )([C] => (in, cont) => cont(in + 1)).eval
        )

        // p10: the boundary park: no entry in scope, the walk reaches the root and hands the
        // caller the operation plus the captured remainder (what handlePartial gives the
        // runtime); out-of-band resumption re-enters the re-wrapped regions. 105 is the
        // passing handlePartial suite expectation for this exact program shape.
        check("p10-boundary-partial")(
            {
                val program: Int < (PCtx & PEnv) = pctx.flatMap(a => penv.map(b => a + b))
                val bound: Int < PCtx            = ContextEffect.handle(Tag[PEnv], 5)(program)
                Eval.evalPartial(bound, Context.empty) match
                    case Left(p)  => p.cont(100).eval
                    case Right(_) => "expected a park"
            },
            105
        )

        // p11: Loop format: state evolves per operation without mutating any node, done sees
        // the final state
        check("p11-loop-state")(
            {
                val prog: Int < PT = pt(1).flatMap(a => pt(2).flatMap(b => pt(3).map(c => a * 100 + b * 10 + c)))
                ArrowEffect.handleLoop(Tag[PT], 5, prog)(
                    [C] => (in, st, cont) => pure(Handler.Loop.Continue(st + 1, cont(in * st))),
                    (st, a) => pure((st, a))
                ).eval
            }, {
                val prog = ot(1).flatMap(a => ot(2).flatMap(b => ot(3).map(c => a * 100 + b * 10 + c)))
                OldArrow.handleLoop(Tag[OT], 5, prog)(
                    [C] => (in, st, cont) => kyo.kernel.Loop.continue(st + 1, cont(in * st)),
                    (st: Int, a: Int) => pureOld((st, a))
                ).eval
            }
        )

        println(if failures == 0 then "ALL PROGRAMS AGREE" else s"$failures MISMATCH(ES)")
        if failures > 0 then throw new IllegalStateException(s"$failures mismatches against the old kernel")
    end main
end RotationProbeMain

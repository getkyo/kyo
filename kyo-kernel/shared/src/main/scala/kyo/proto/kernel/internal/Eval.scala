package kyo.proto.kernel.internal

import kyo.Frame
import kyo.bug
import kyo.proto.Arrow
import kyo.proto.Loop
import kyo.proto.Loop.Outcome2
import kyo.proto.kernel.<
import kyo.proto.kernel.ArrowEffect
import kyo.proto.kernel.ContextEffect
import kyo.proto.kernel.Effect
import language.implicitConversions

object Eval:

    type AX
    type Y
    type IX[_]
    type OX[_]
    type EX <: ArrowEffect[IX, OX]
    type VX
    type CX <: ContextEffect[VX]

    def apply[A, S](v: A < S): A < S =
        val dbg = Debugger.get
        def loop[A, B, C, S](v: A < S, contA: Arrow[A, B, S], contB: Arrow[B, C, S], ctx: Context): C < S =
            dbg.onLoop(v, contA, contB)
            v match
                case kyo: Kyo.Defer[AX, Y, A, S] @unchecked =>
                    loop(kyo.value, kyo.contA, kyo.contB.chain(contA.chain(contB)), ctx)
                case kyo: Kyo.SuspendContext[VX, CX, A, S] @unchecked if ctx.contains(kyo.tag) =>
                    val nv = kyo.update(ctx.apply[VX, CX](kyo.tag))
                    dbg.onContext(kyo, nv)
                    val k = kyo.cont
                    loop(k.head(nv, k.tail), contA, contB, ctx.update[VX, CX](kyo.tag, nv))
                case kyo: Kyo.SuspendContextDefault[VX, CX, A, S] @unchecked if ctx.contains(kyo.tag) =>
                    val nv = kyo.update(ctx.apply[VX, CX](kyo.tag))
                    dbg.onContext(kyo, nv)
                    val k = kyo.cont
                    loop(k.head(nv, k.tail), contA, contB, ctx.update[VX, CX](kyo.tag, nv))
                case kyo: Kyo.Suspend[EX, A, S] @unchecked =>
                    val k = contA.chain(contB)
                    if k.isInstanceOf[Arrow.Id[?]] then kyo.asInstanceOf[C < S]
                    else kyo.withCont(kyo.cont.chain(k))
                case kyo: Kyo.Handle[EX, AX, A, S, VX] @unchecked =>
                    def region[T](st: VX, v: T < (EX & S), cont: Arrow[T, AX, EX & S], ctx: Context): A < S =
                        loop(v, cont, Arrow.id, ctx) match
                            case res: Kyo.Suspend[EX, AX, EX & S] @unchecked if !(res.tag.erased <:< kyo.handler.tag.erased) =>
                                dbg.onForeign(res, kyo.handler)
                                // TODO it seems we can allocate Kyo.Suspend with Arrow.Transform to avoid an allocation
                                res.withCont(
                                    new Arrow.Transform[res.Op, A, S]:
                                        def frame             = Frame.internal
                                        override def toString = "Transform"
                                        def apply[D, S2](x: res.Op < S2, cont: Arrow[A, D, S2]) =
                                            val k = res.cont
                                            Kyo.handle[EX, AX, A, S & S2, VX](k.head(x, k.tail), kyo.handler, st).chain(cont)
                                        end apply
                                )
                            case suspend: Kyo.SuspendArrow[IX, OX, EX, VX, AX, EX & S] @unchecked =>
                                dbg.onHandle(suspend, kyo.handler, st)
                                val next = suspend.cont
                                kyo.handler match
                                    case handler: Handler.HandlerCont[IX, OX, EX, AX, A, S] @unchecked =>
                                        val r = handler.answer(suspend.input, next)
                                        dbg.onResult(r)
                                        region(st, r, Arrow.id, ctx)
                                    case handler: Handler.HandlerLoop[IX, OX, EX, AX, A, S, VX] @unchecked =>
                                        val o = handler.answer(st, suspend.input, next)
                                        dbg.onResult(o)
                                        o match
                                            case o: Loop.Continue2[VX, OX[VX] < (EX & S)] @unchecked =>
                                                region(o._1, o._2, next, ctx)
                                            case o =>
                                                // a done outcome is its payload in the union representation
                                                Nested.unnest[A < S](o)
                                        end match
                                    case _ =>
                                        bug(s"unhandled: ${kyo.handler}")
                                end match
                            case res: Arrow[Any, AX, S] @unchecked =>
                                bug(s"unhandled: $res")
                            case res: AX @unchecked =>
                                kyo.handler.done(st, res)
                    end region
                    dbg.onRegionEnter(kyo.handler, kyo.state)
                    val bound = kyo.handler match
                        case h: Handler.HandlerContext[VX, CX, AX, A, S] @unchecked =>
                            val hc: Handler.HandlerContext[VX, CX, AX, A, S] = h
                            ctx.update[VX, CX](hc.tag, kyo.state)
                        case _ => ctx
                    val res = region(kyo.state, kyo.value, Arrow.id, bound)
                    dbg.onRegionExit(kyo.handler, res)
                    loop(res, contA, contB, ctx)
                case arrow: Arrow.Transform[Any, A, S] @unchecked =>
                    loop(arrow((), contA.chain(contB)), Arrow.id, Arrow.id, ctx)
                case arrow: Arrow.Chain[Any, Any, A, S] @unchecked =>
                    loop(arrow.a, arrow.b, contA.chain(contB), ctx)
                case res =>
                    if contA.isInstanceOf[Arrow.Id[?]] && contB.isInstanceOf[Arrow.Id[?]] then
                        res.asInstanceOf[C < S]
                    else
                        contA match
                            case contA: Arrow.Chain[A, Any, B, S] @unchecked =>
                                loop(res, contA.a, contA.b.chain(contB), ctx)
                            case _ =>
                                loop(contA(res.asInstanceOf[A], contB), Arrow.id, Arrow.id, ctx)
            end match
        end loop
        def run(v: A < S): A < S =
            loop(v, Arrow.id, Arrow.id, Context.empty) match
                case suspend: Kyo.SuspendContextDefault[VX, CX, A, S] @unchecked =>
                    val nv = suspend.update(suspend.default)
                    dbg.onContextDefault(suspend, nv)
                    val k = suspend.cont
                    run(k.head(nv, k.tail))
                case res =>
                    res
        run(v)
    end apply

    def answerLoop[I[_], O[_], E <: ArrowEffect[I, O], A, B, S, State, W](
        handler: Handler.HandlerLoop[I, O, E, A, B, S, State],
        r: Outcome2[State, O[W] < (E & S), B < S] < S,
        next: Arrow[O[W], A, E & S]
    ): Outcome2[State, O[W] < (E & S), B < S] =
        r match
            case r: Arrow[Any, Outcome2[State, O[W] < (E & S), B < S], S] @unchecked =>
                val transform =
                    new Arrow.Transform[Outcome2[State, O[W] < (E & S), B < S], B, S]:
                        def frame             = Frame.internal
                        override def toString = "Transform"
                        def apply[D, S2](
                            out: Outcome2[State, O[W] < (E & S), B < S] < S2,
                            cont: Arrow[B, D, S2]
                        ) =
                            out match
                                case kyo: Arrow[Any, Outcome2[State, O[W] < (E & S), B < S], S2] @unchecked =>
                                    Effect.defer(kyo, this, cont)
                                case out: Loop.Continue2[State, O[W] < (E & S)] @unchecked =>
                                    Kyo.handle[E, A, B, S, State](
                                        out._2.chain(next),
                                        handler,
                                        out._1
                                    ).chain(cont)
                                case out =>
                                    // a done outcome is its payload in the union representation
                                    Nested.unnest[B < S](out).chain(cont)
                val out: B < S =
                    r match
                        case r: Kyo.Suspend[E, Outcome2[State, O[W] < (E & S), B < S], S] @unchecked =>
                            r.withCont(r.cont.chain(transform))
                        case _ =>
                            r.chain(transform)
                // a done outcome is its payload in the union representation
                Nested.unnest[Outcome2[State, O[W] < (E & S), B < S]](out)
            case r =>
                Nested.unnest[Outcome2[State, O[W] < (E & S), B < S]](r)
    end answerLoop
end Eval

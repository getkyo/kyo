package kyo.proto.kernel.internal

import kyo.Frame
import kyo.Maybe
import kyo.Maybe.Absent
import kyo.Maybe.Present
import kyo.bug
import kyo.discard
import kyo.proto.Arrow
import kyo.proto.Loop
import kyo.proto.Loop.Outcome2
import kyo.proto.kernel.<
import kyo.proto.kernel.ArrowEffect
import kyo.proto.kernel.ContextEffect
import kyo.proto.kernel.Effect
import language.implicitConversions
import scala.annotation.publicInBinary
import scala.annotation.tailrec
import scala.util.control.NonFatal

@publicInBinary private[kyo] object Eval:

    def release[A, S](v: A < S): Any < Any =
        v match
            case p: Pending[?, ?] => p.release(Discarded)
            case _                => ()

    def apply[A, S](v: A < S): A < S = apply(v, armed = false)

    def partial[A](v: A < Any): A < Any =
        val slot = Safepoint.get()
        if Safepoint.consumeStopped(slot) then v
        else

            try apply(v, armed = true)
            finally discard(Safepoint.consumeStopped(slot))
        end if
    end partial

    private def apply[A, S](v: A < S, armed: Boolean): A < S =

        val stack = Stack.borrow()

        val slot  = Safepoint.get()
        val saved = Safepoint.save(slot)
        if armed then Safepoint.arm(slot)

        def park[T, B, C, S2](curr: T < S2, contA: Arrow[T, B, S2], contB: Arrow[B, C, S2]): A < S =
            val v: Any < Any =
                if contA.isInstanceOf[Arrow.Id[?]] && contB.isInstanceOf[Arrow.Id[?]] then curr.asInstanceOf[Any < Any]
                else Effect.defer(curr, contA, contB).asInstanceOf[Any < Any]
            if stack.isEmpty then v.asInstanceOf[A < S]
            else new Kyo.Park[A, S](v, stack.snapshot())
        end park

        def exitContext(interior: Context): Context =
            stack.handler match
                case hc: Handler.HandlerContext[VX, CX, ?, ?, ?] @unchecked =>
                    val installed = stack.ctx
                    if installed.contains(hc.tag) then interior.updateErased(hc.tag, installed.apply[VX, CX](hc.tag))
                    else interior.remove(hc.tag)
                case _ => interior
        end exitContext

        @tailrec def loop[T, B, C, S2](v: T < S2, contA: Arrow[T, B, S2], contB: Arrow[B, C, S2], ctx: Context): A < S =
            Debugger.onLoop(v, contA, contB)
            v match
                case kyo: Kyo.Defer[AX, Y, T, S2] @unchecked =>

                    if armed && Safepoint.stopped(slot) then park(v, contA, contB)
                    else loop(kyo.value, kyo.contA, kyo.contB.chain(contA.chain(contB)), ctx)
                case kyo: Kyo.SuspendContext[VX, CX, T, S2] @unchecked if kyo.answers(ctx) =>

                    val nctx = kyo.update(ctx)
                    Debugger.onContext(kyo, nctx)
                    val k = kyo.cont
                    loop(k.head(nctx, k.tail), contA, contB, nctx)
                case kyo: Kyo.Suspend[EX, T, S2] @unchecked =>

                    val susp: Kyo.Suspend[?, ?, ?] =
                        if contA.isInstanceOf[Arrow.Id[?]] && contB.isInstanceOf[Arrow.Id[?]] then kyo
                        else if kyo.cont.isInstanceOf[Arrow.Id[?]] && (contA.isInstanceOf[Arrow.Id[?]] || contB.isInstanceOf[Arrow.Id[?]])
                        then

                            kyo.withCont(kyo.cont.chain(contA.chain(contB)))
                        else

                            kyo match

                                case sa: Kyo.SuspendArrow[IX, OX, EX, VX, T, S2] @unchecked =>
                                    val sax: Kyo.SuspendArrow[IX, OX, EX, VX, T, S2] = sa
                                    val k0                                           = sax.cont
                                    val cA                                           = contA
                                    val cB                                           = contB
                                    new Kyo.SuspendArrowTransform[IX, OX, EX, VX, C, S2]:
                                        def tag   = sax.tag
                                        def input = sax.input
                                        def cont  = this
                                        override def apply[D, S3](x: OX[VX] < S3, c2: Arrow[C, D, S3]) =
                                            x match
                                                case p: Pending[OX[VX], S3] @unchecked => Effect.defer(p, this, c2)
                                                case _                                 => cA(k0(x, Arrow.id), cB.chain(c2))
                                    end new
                                case sc: Kyo.SuspendContext[VX, CX, T, S2] @unchecked =>
                                    val scx: Kyo.SuspendContext[VX, CX, T, S2] = sc
                                    val k0                                     = scx.cont
                                    val cA                                     = contA
                                    val cB                                     = contB
                                    new Kyo.SuspendContextTransform[VX, CX, C, S2]:
                                        def tag                   = scx.tag
                                        def answers(ctx: Context) = scx.answers(ctx)
                                        def update(ctx: Context)  = scx.update(ctx)
                                        def cont                  = this
                                        override def apply[D, S3](x: Context < S3, c2: Arrow[C, D, S3]) =
                                            x match
                                                case p: Pending[Context, S3] @unchecked => Effect.defer(p, this, c2)
                                                case _                                  => cA(k0(x, Arrow.id), cB.chain(c2))
                                    end new
                            end match
                    if stack.isEmpty then susp.asInstanceOf[A < S]
                    else
                        val handler = stack.handler.asInstanceOf[Handler[EX, AX, Y, Any, VX]]
                        val state   = stack.state.asInstanceOf[VX]

                        if !(handler.tag.erased <:< susp.tag.erased) then
                            Debugger.onForeign(susp, handler)

                            def reenter[P, D, S3](k0: Arrow[P, AX, EX], x: P < S3, cont2: Arrow[Y, D, S3]): D < S3 =

                                Kyo.handle[EX, AX, Y, S3, VX](Effect.defer(x, k0), handler, state).chain(cont2)

                            val rebuilt: Y < Any =
                                susp match
                                    case sa: Kyo.SuspendArrow[IY, OY, EY, VY, AX, EX] @unchecked =>

                                        val sax: Kyo.SuspendArrow[IY, OY, EY, VY, AX, EX] = sa

                                        new Kyo.SuspendArrowTransform[IY, OY, EY, VY, Y, Any]:
                                            def tag   = sax.tag
                                            def input = sax.input
                                            def cont  = this
                                            override def release(ex: Throwable): Any < Any =
                                                Debugger.onRelease(handler, ex)
                                                sax.release(ex).andThen(handler.release(state, ex))(using Frame.internal)
                                            override def apply[D, S3](x: OY[VY] < S3, cont2: Arrow[Y, D, S3]) =
                                                x match
                                                    case p: Pending[OY[VY], S3] @unchecked =>
                                                        Effect.defer(p, this, cont2)
                                                    case _ =>
                                                        reenter(sax.cont, Nested.unnest[OY[VY]](x), cont2)
                                        end new
                                    case sc: Kyo.SuspendContext[VX, CX, AX, EX] @unchecked =>
                                        val scx: Kyo.SuspendContext[VX, CX, AX, EX] = sc

                                        new Kyo.SuspendContextTransform[VX, CX, Y, Any]:
                                            def tag                   = scx.tag
                                            def answers(ctx: Context) = scx.answers(ctx)
                                            def update(ctx: Context)  = scx.update(ctx)
                                            def cont                  = this
                                            override def release(ex: Throwable): Any < Any =
                                                Debugger.onRelease(handler, ex)
                                                scx.release(ex).andThen(handler.release(state, ex))(using Frame.internal)
                                            override def apply[D, S3](x: Context < S3, cont2: Arrow[Y, D, S3]) =
                                                x match
                                                    case p: Pending[Context, S3] @unchecked =>
                                                        Effect.defer(p, this, cont2)
                                                    case _ =>
                                                        reenter(scx.cont, Nested.unnest[Context](x), cont2)
                                        end new
                                end match
                            end rebuilt
                            Debugger.onRegionExit(handler, rebuilt)
                            val cont  = stack.cont.asInstanceOf[Arrow[Y, Any, Any]]
                            val outer = exitContext(ctx)
                            stack.pop()
                            loop(rebuilt, cont, Arrow.id, outer)
                        else
                            susp match
                                case suspend: Kyo.SuspendArrow[IX, OX, EX, VX, AX, EX] @unchecked =>
                                    Debugger.onHandle(suspend, handler, state)
                                    val next = suspend.cont
                                    handler match
                                        case handler: Handler.HandlerCont[IX, OX, EX, AX, Y, Any] @unchecked =>
                                            val r = handler.answer(suspend.input, next)
                                            Debugger.onResult(r)

                                            loop(r, Arrow.id, Arrow.id, ctx)
                                        case handler: Handler.HandlerContOperation[EX, AX, Y, Any] @unchecked =>

                                            val operation: OX[VX] < EX =
                                                Kyo.SuspendArrow(suspend.tag, suspend.input, Arrow.id[OX[VX]])
                                            val r = handler.answer(operation, next)
                                            Debugger.onResult(r)

                                            loop(r, Arrow.id, Arrow.id, ctx)
                                        case handler: Handler.HandlerLoop[IX, OX, EX, AX, Y, Any, VX] @unchecked =>
                                            val o = handler.answer(state, suspend.input, next)
                                            Debugger.onResult(o)
                                            o match
                                                case o: Loop.Continue2[VX, OX[VX] < EX] @unchecked =>

                                                    stack.state = o._1
                                                    loop(o._2, next, Arrow.id, ctx)
                                                case o =>

                                                    val r = o.asInstanceOf[Y < Any]
                                                    Debugger.onRegionExit(handler, r)
                                                    val cont  = stack.cont.asInstanceOf[Arrow[Y, Any, Any]]
                                                    val outer = exitContext(ctx)
                                                    stack.pop()
                                                    loop(r, cont, Arrow.id, outer)
                                            end match
                                        case _ =>
                                            bug(s"unhandled: $handler")
                                    end match
                                case _ =>
                                    bug(s"unhandled: $susp")
                            end match
                        end if
                    end if
                case kyo: Kyo.Handle[EX, AX, Y, T, S2, VX] @unchecked =>

                    var st0 = kyo.state
                    val bound = kyo.handler match
                        case h: Handler.HandlerContext[VX, CX, AX, Y, S2] @unchecked =>
                            val hc: Handler.HandlerContext[VX, CX, AX, Y, S2] = h
                            st0 = hc.resolve(Maybe.when(ctx.contains(hc.tag))(ctx[VX, CX](hc.tag)))
                            ctx.update(hc.tag, st0)
                        case _ => ctx
                    Debugger.onRegionEnter(kyo.handler, st0)

                    stack.push(kyo.handler, st0, ctx, kyo.cont.chain(contA.chain(contB)))
                    loop(kyo.value, Arrow.id, Arrow.id, bound)
                case kyo: Kyo.Park[?, ?] =>

                    val entries = kyo.entries
                    @tailrec def install(i: Int, c: Context): Context =
                        if i == entries.length then c
                        else
                            val handler = entries(i).asInstanceOf[Handler[EX, AX, Y, Any, VX]]
                            val stored  = entries(i + 2).asInstanceOf[Arrow[Y, Any, Any]]

                            val cont =
                                if i == 0 then stored.chain(contA.chain(contB).asInstanceOf[Arrow[Any, Any, Any]])
                                else stored
                            handler match
                                case h: Handler.HandlerContext[VX, CX, AX, Y, Any] @unchecked =>
                                    val hc: Handler.HandlerContext[VX, CX, AX, Y, Any] = h
                                    val st = hc.resolve(Maybe.when(c.contains(hc.tag))(c[VX, CX](hc.tag)))
                                    Debugger.onRegionEnter(handler, st)
                                    stack.push(handler, st, c, cont)
                                    install(i + 3, c.update(hc.tag, st))
                                case _ =>
                                    val st = entries(i + 1).asInstanceOf[VX]
                                    Debugger.onRegionEnter(handler, st)
                                    stack.push(handler, st, c, cont)
                                    install(i + 3, c)
                            end match
                    loop(kyo.value, Arrow.id, Arrow.id, install(0, ctx))
                case res =>
                    if contA.isInstanceOf[Arrow.Id[?]] && contB.isInstanceOf[Arrow.Id[?]] then
                        if stack.isEmpty then res.asInstanceOf[A < S]
                        else

                            val handler = stack.handler.asInstanceOf[Handler[EX, AX, Y, Any, VX]]
                            val r       = handler.done(stack.state.asInstanceOf[VX], Nested.unnest[AX](res))
                            Debugger.onRegionExit(handler, r)
                            val cont  = stack.cont.asInstanceOf[Arrow[Y, Any, Any]]
                            val outer = exitContext(ctx)
                            stack.pop()
                            loop(r, cont, Arrow.id, outer)
                    else
                        contA match
                            case contA: Arrow.Chain[T, Any, B, S2] @unchecked =>
                                loop(res, contA.a, contA.b.chain(contB), ctx)
                            case _ =>

                                loop(contA(res, contB), Arrow.id, Arrow.id, ctx)
            end match
        end loop

        @tailrec def recovered(ex: Throwable): A < S =
            if stack.isEmpty then

                EffectTrace.splice(ex)
                throw ex
            else
                val handler = stack.handler.asInstanceOf[Handler[EX, AX, Y, Any, VX]]
                val state   = stack.state.asInstanceOf[VX]
                val outcome =
                    try handler.recover(state, ex)
                    catch
                        case ex2 if NonFatal(ex2) =>
                            stack.pop()

                            EffectTrace.attach(ex2, stack)
                            EffectTrace.splice(ex2)
                            return recovered(ex2)
                outcome match
                    case Present(r) =>
                        Debugger.onRecover(handler, ex)
                        Debugger.onRegionExit(handler, r)
                        r.chain(stack.cont.asInstanceOf[Arrow[Y, A, S]])
                    case Absent =>
                        stack.pop()
                        recovered(ex)
                end match

        @tailrec def guarded(curr: A < S, ctx: Context): A < S =
            val res =
                try loop(curr, Arrow.id, Arrow.id, ctx)
                catch
                    case failure if NonFatal(failure) =>

                        Safepoint.reset(slot)

                        EffectTrace.attach(failure, stack)
                        EffectTrace.splice(failure)
                        val resumed = recovered(failure)

                        val outer = stack.ctx
                        stack.pop()
                        return guarded(resumed, outer)
            res match
                case susp: Kyo.Suspend[?, ?, ?] =>

                    bug(s"unhandled suspension: $susp")
                case res => res
            end match
        end guarded

        try guarded(v, Context.empty)
        finally
            Safepoint.restore(slot, saved)
            Stack.release(stack)
        end try
    end apply

    def answerLoop[I[_], O[_], E <: ArrowEffect[I, O], A, B, S, State, W](
        handler: Handler.HandlerLoop[I, O, E, A, B, S, State],
        r: Outcome2[State, O[W] < (E & S), B < S] < S,
        next: Arrow[O[W], A, E & S]
    ): Outcome2[State, O[W] < (E & S), B < S] =
        r match
            case r: Pending[Outcome2[State, O[W] < (E & S), B < S], S] @unchecked =>
                type Out = Outcome2[State, O[W] < (E & S), B < S]
                def outcome[D, S2](
                    self: Arrow[Out, B, S],
                    out: Out < S2,
                    cont: Arrow[B, D, S2]
                ): D < (S & S2) =
                    out match
                        case kyo: Pending[Out, S2] @unchecked =>
                            Effect.defer(kyo, self, cont)
                        case out: Loop.Continue2[State, O[W] < (E & S)] @unchecked =>
                            Kyo.handle[E, A, B, S, State](
                                out._2.chain(next),
                                handler,
                                out._1
                            ).chain(cont)
                        case out =>

                            Nested.unnest[B < S](out).chain(cont)
                val out: B < S =
                    r match
                        case rs: Kyo.SuspendArrow[IY, OY, EY, VY, Out, S] @unchecked
                            if rs.cont.isInstanceOf[Arrow.Id[?]] =>
                            val rsx: Kyo.SuspendArrow[IY, OY, EY, VY, Out, S] = rs

                            new Kyo.SuspendArrowTransform[IY, OY, EY, VY, B, S]:
                                def tag   = rsx.tag
                                def input = rsx.input
                                def cont  = this
                                override def apply[D, S2](out0: OY[VY] < S2, cont2: Arrow[B, D, S2]) =

                                    outcome(this.asInstanceOf[Arrow[Out, B, S]], out0.asInstanceOf[Out < S2], cont2)
                            end new
                        case _ =>

                            new Kyo.DeferTransform[Out, B, S]:
                                def value = r
                                def contA = this
                                def contB = Arrow.id
                                override def apply[D, S2](out0: Out < S2, cont2: Arrow[B, D, S2]) =
                                    outcome(this, out0, cont2)
                            end new

                Nested.unnest[Out](out)
            case r =>
                Nested.unnest[Outcome2[State, O[W] < (E & S), B < S]](r)
    end answerLoop

    type AX
    type Y
    type IX[_]
    type OX[_]
    type EX <: ArrowEffect[IX, OX]
    type VX
    type CX <: ContextEffect[VX]
    type IY[_]
    type OY[_]
    type EY <: ArrowEffect[IY, OY]
    type VY
end Eval

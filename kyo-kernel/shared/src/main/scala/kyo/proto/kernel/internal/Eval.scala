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

    def release[A, S](v: A < S, ex: Throwable): Any < Any =
        v match
            case p: Pending[?, ?] => p.release(ex)
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

        @tailrec def loop[T, B, C, S2](v: T < S2, contA: Arrow[T, B, S2], contB: Arrow[B, C, S2], ctx: Context): A < S =
            Debugger.onLoop(v, contA, contB)
            v match
                case kyo: Kyo.Defer[?, ?, T, S2] @unchecked =>
                    if armed && Safepoint.stopped(slot) then
                        val parked: Any < Any =
                            if contA.isInstanceOf[Arrow.Id[?]] && contB.isInstanceOf[Arrow.Id[?]] then v.asInstanceOf[Any < Any]
                            else Effect.defer(v, contA, contB).asInstanceOf[Any < Any]
                        if stack.isEmpty then parked.asInstanceOf[A < S]
                        else
                            Debugger.whenEnabled {
                                var j = stack.depth - 1
                                while j >= 0 do
                                    Debugger.onRegionExit(stack.handlerAt(j), parked)
                                    j -= 1
                            }
                            Kyo.Park[A, S](parked, stack.snapshot())
                        end if
                    else
                        loop(kyo.value, kyo.contA, kyo.contB.chain(contA.chain(contB)), ctx)

                case kyo: Kyo.Suspend[?, ?, T, S2] @unchecked =>
                    kyo match
                        case kyo: Kyo.SuspendContext[VX, CX, T, S2] @unchecked =>
                            val state = ctx.get(kyo.tag).orElse(kyo.default).getOrElse(bug(s"unhandled suspension: $kyo"))
                            Debugger.onContext(kyo, ctx)
                            loop(kyo.cont(state, contA.chain(contB)), Arrow.id, Arrow.id, ctx)

                        case kyo: Kyo.SuspendArrow[IX, OX, EX, VX, T, EX & S2] @unchecked =>
                            val idx = stack.find(kyo.tag)
                            if idx < 0 then bug(s"unhandled suspension: $kyo")
                            else
                                Debugger.onHandle(kyo, stack.handlerAt(idx), stack.stateAt(idx))
                                if idx < stack.depth - 1 then
                                    Debugger.onForeign(kyo, stack.handler)
                                def continuation =
                                    if idx == stack.depth - 1 then
                                        kyo.cont.chain(contA.chain(contB))
                                    else
                                        val entries = stack.dump(idx + 1)
                                        Debugger.whenEnabled {
                                            var i = entries.length - 3
                                            while i >= 0 do
                                                Debugger.onRegionExit(entries(i), kyo)
                                                i -= 3
                                        }
                                        val inner = kyo.cont.chain(contA.chain(contB))
                                        new Arrow.Step[OX[VX], C, EX & S2]:
                                            def frame = Frame.internal
                                            override def apply[D, S3](v: OX[VX] < S3, cont2: Arrow[C, D, S3]) =
                                                v match
                                                    case p: Pending[OX[VX], S3] @unchecked => Effect.defer(p, this, cont2)
                                                    case _ =>
                                                        cont2(
                                                            Kyo.Park(
                                                                Effect.defer(v, inner, Arrow.id).asInstanceOf[Any < Any],
                                                                entries
                                                            ),
                                                            Arrow.id
                                                        )
                                        end new
                                stack.handlerAt(idx) match
                                    case handler: Handler.HandlerCont[IX, OX, EX, C, Y, S2] @unchecked =>
                                        val result = handler.run(kyo.input, continuation)
                                        Debugger.onResult(result)
                                        loop(result, Arrow.id, Arrow.id, ctx)
                                    case handler: Handler.HandlerContOp[EX, C, Y, S2] @unchecked =>
                                        val operation: OX[VX] < EX =
                                            new Kyo.SuspendArrow[IX, OX, EX, VX, OX[VX], EX]:
                                                def tag   = kyo.tag
                                                def input = kyo.input
                                                def cont  = Arrow.id
                                        val result = handler.run(operation, continuation)
                                        Debugger.onResult(result)
                                        loop(result, Arrow.id, Arrow.id, ctx)
                                    case handler: Handler.HandlerLoop[IX, OX, EX, C, Y, S2, VX] @unchecked =>
                                        val outcome0 = handler.run(stack.stateAt(idx).asInstanceOf[VX], kyo.input)
                                        stack.scratch = outcome0
                                        Debugger.onResult(outcome0)
                                        outcome0 match
                                            case outcome: Loop.Continue2[VX, OX[VX] < (EX & S2)] @unchecked =>
                                                stack.updateState(idx, outcome._1)
                                                if idx == stack.depth - 1 then loop(outcome._2, kyo.cont, contA.chain(contB), ctx)
                                                else loop(outcome._2, continuation, Arrow.id, ctx)
                                            case pending: Pending[Outcome2[VX, OX[VX] < (EX & S2), Y < S2], S2] @unchecked =>
                                                type Out = Outcome2[VX, OX[VX] < (EX & S2), Y < S2]
                                                val reentry = continuation
                                                val dispatch =
                                                    new Arrow.Step[Out, Y, S2]:
                                                        def frame = Frame.internal
                                                        override def apply[D, S3](out: Out < S3, cont2: Arrow[Y, D, S3]) =
                                                            out match
                                                                case kyo: Pending[Out, S3] @unchecked =>
                                                                    Effect.defer(kyo, this, cont2)
                                                                case out: Loop.Continue2[VX, OX[VX] < (EX & S2)] @unchecked =>
                                                                    Kyo.handle[EX, C, Y, S2, VX](
                                                                        out._2.chain(reentry),
                                                                        handler,
                                                                        out._1
                                                                    ).chain(cont2)
                                                                case out =>
                                                                    Nested.unnest[Y < S2](out).chain(cont2)
                                                val next = stack.continuationAt(idx).asInstanceOf[Arrow[Y, Any, S2]]
                                                Debugger.onRegionExit(handler, pending)
                                                stack.pop()
                                                loop(pending, dispatch, next, ctx)
                                            case outcome =>
                                                val result = Nested.unnest[Y < S2](outcome)
                                                Debugger.whenEnabled {
                                                    var j = stack.depth - 1
                                                    while j > idx do
                                                        Debugger.onRegionExit(stack.handlerAt(j), outcome)
                                                        j -= 1
                                                }
                                                Debugger.onRegionExit(handler, result)
                                                val next = stack.continuationAt(idx).asInstanceOf[Arrow[Y, Any, Any]]
                                                stack.truncate(idx)
                                                loop(result, next, Arrow.id, ctx)
                                        end match
                                    case handler =>
                                        bug(s"unhandled: $handler")
                                end match
                            end if

                case kyo: Kyo.Handle[CX, ?, ?, T, S2, ?] @unchecked =>
                    kyo.handler match
                        case handler: Handler.HandlerContext[VX, CX, Any, T, S2] @unchecked =>
                            val newState   = handler.derive(ctx.get(handler.tag))
                            val newContext = ctx.update(handler.tag, newState)
                            Debugger.onContext(kyo, newContext)
                            Debugger.onRegionEnter(kyo.handler, newState)
                            stack.push(kyo.handler, newState, kyo.cont.chain(contA.chain(contB)))
                            loop(kyo.value, Arrow.id, Arrow.id, newContext)
                        case _ =>
                            Debugger.onRegionEnter(kyo.handler, kyo.state)
                            stack.push(kyo.handler, kyo.state, kyo.cont.chain(contA.chain(contB)))
                            loop(kyo.value, Arrow.id, Arrow.id, ctx)
                    end match

                case kyo: Kyo.Park[?, ?] =>
                    val entries = kyo.entries
                    @tailrec def install(i: Int, c: Context): Context =
                        if i == entries.length then c
                        else
                            val stored = entries(i + 2).asInstanceOf[Arrow[Y, Any, Any]]
                            val cont =
                                if i == 0 then stored.chain(contA.chain(contB).asInstanceOf[Arrow[Any, Any, Any]])
                                else stored
                            entries(i) match
                                case hc: Handler.HandlerContext[VX, CX, AX, Y, Any] @unchecked =>
                                    val st = entries(i + 1).asInstanceOf[VX]
                                    Debugger.onRegionEnter(hc, st)
                                    stack.push(hc, st, cont)
                                    install(i + 3, c.update(hc.tag, st))
                                case _ =>
                                    val handler = entries(i).asInstanceOf[Handler[EX, AX, Y, Any, VX]]
                                    val st      = entries(i + 1).asInstanceOf[VX]
                                    Debugger.onRegionEnter(handler, st)
                                    stack.push(handler, st, cont)
                                    install(i + 3, c)
                            end match
                    loop(kyo.value, Arrow.id, Arrow.id, install(0, ctx))

                case res =>
                    if contA.isInstanceOf[Arrow.Id[?]] && contB.isInstanceOf[Arrow.Id[?]] then
                        if stack.isEmpty then res.asInstanceOf[A < S]
                        else
                            val exiting = stack.handler
                            val handler = exiting.asInstanceOf[Handler[EX, AX, Y, Any, VX]]
                            val result  = handler.done(stack.state.asInstanceOf[VX], Nested.unnest[AX](res))
                            Debugger.onRegionExit(handler, result)
                            val next = stack.cont.asInstanceOf[Arrow[Y, Any, Any]]
                            stack.pop()
                            val outer =
                                exiting match
                                    case hc: Handler.HandlerContext[VX, CX, AX, Y, Any] @unchecked =>
                                        val j = stack.find(hc.tag)
                                        if j < 0 then ctx.remove(hc.tag)
                                        else ctx.update(hc.tag, stack.stateAt(j).asInstanceOf[VX])
                                    case _ => ctx
                            loop(result, next, Arrow.id, outer)
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
                            Debugger.onRegionExit(handler, ex2)
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
                        Debugger.onRegionExit(handler, ex)
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
                        stack.pop()
                        @tailrec def rebuild(i: Int, rebuilt: Context): Context =
                            if i == stack.depth then rebuilt
                            else
                                stack.handlerAt(i) match
                                    case handler: Handler.HandlerContext[VX, CX, ?, ?, ?] @unchecked =>
                                        rebuild(i + 1, rebuilt.update(handler.tag, stack.stateAt(i).asInstanceOf[VX]))
                                    case _ => rebuild(i + 1, rebuilt)
                        return guarded(resumed, rebuild(0, Context.empty))
            res match
                case susp: Kyo.Suspend[?, ?, ?, ?] =>

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

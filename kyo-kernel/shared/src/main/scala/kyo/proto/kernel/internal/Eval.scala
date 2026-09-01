package kyo.proto.kernel.internal

import kyo.Chunk
import kyo.Frame
import kyo.KyoException
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
import scala.collection.mutable.ArrayBuffer
import scala.util.control.NonFatal

@publicInBinary private[kyo] object Eval:

    def release[A, S](v: A < S, ex: Throwable): Unit =
        val collected = ArrayBuffer.empty[AnyRef]
        @tailrec def collect(v: Any): Unit =
            v match
                case p: Pending[?, ?] =>
                    p match
                        case kyo: Kyo.Defer[?, ?, ?, ?] =>
                            collect(kyo.value)
                        case kyo: Kyo.Handle[?, ?, ?, ?, ?, ?] =>
                            kyo.handler match
                                case hc: Handler.ContextHandler[?, ?, ?, ?] =>
                                    collected += hc
                                    collected += kyo.state.asInstanceOf[AnyRef]
                                case _ => ()
                            end match
                            collect(kyo.value)
                        case kyo: Kyo.Park[?, ?] =>
                            expandOwed(collected, kyo.owed)
                            val entries = kyo.entries
                            var i       = 0
                            while i < entries.regions do
                                entries.handler(i) match
                                    case hc: Handler.ContextHandler[?, ?, ?, ?] =>
                                        collected += hc
                                        collected += entries.state(i).asInstanceOf[AnyRef]
                                    case _ => ()
                                end match
                                expandOwed(collected, entries.owed(i))
                                i += 1
                            end while
                            collect(kyo.value)
                        case _: Kyo.Suspend[?, ?, ?, ?] => ()
                        case _: Kyo.Snapshot[?, ?]      => ()
                case _ => ()
        collect(v)
        releaseCollected(collected, ex)
    end release

    private def expandOwed(collected: ArrayBuffer[AnyRef], owed: Chunk[Stack.Snapshot]): Unit =
        if !owed.isEmpty then
            val snapshots = owed.toIndexed
            var j         = 0
            while j < snapshots.length do
                val snapshot = snapshots(j)
                var i        = 0
                while i < snapshot.regions do
                    snapshot.handler(i) match
                        case hc: Handler.ContextHandler[?, ?, ?, ?] =>
                            collected += hc
                            collected += snapshot.state(i).asInstanceOf[AnyRef]
                        case _ => ()
                    end match
                    expandOwed(collected, snapshot.owed(i))
                    i += 1
                end while
                j += 1
            end while
    end expandOwed

    private def releaseCollected(collected: ArrayBuffer[AnyRef], ex: Throwable): Unit =
        var i = collected.length - 2
        while i >= 0 do
            released(collected(i).asInstanceOf[Handler.ContextHandler[?, ?, ?, ?]], collected(i + 1), ex)
            i -= 2
        end while
    end releaseCollected

    private def drainOwed(owed: Chunk[Stack.Snapshot], ex: Throwable): Unit =
        val collected = ArrayBuffer.empty[AnyRef]
        expandOwed(collected, owed)
        releaseCollected(collected, ex)
    end drainOwed

    private def unhandled(kyo: Any): Nothing = bug(s"unhandled suspension: $kyo")

    private def unanswerable(handler: Handler[?, ?, ?]): Nothing = bug(s"unhandled: $handler")

    private def dumped(stack: Stack, idx: Int, kyo: Kyo.Suspend[?, ?, ?, ?]): Stack.Snapshot =
        val entries = stack.dump(idx + 1)
        Debugger.whenEnabled {
            var i = entries.regions - 1
            while i >= 0 do
                Debugger.onRegionExit(entries.handler(i), kyo)
                i -= 1
        }
        entries
    end dumped

    private def drainDiscarded(owed: Chunk[Stack.Snapshot]): Unit =
        if !owed.isEmpty then
            val signal = new KyoException("remainder discarded")(using Frame.internal)
            drainOwed(owed, signal)
            if signal.getSuppressed.length != 0 then Report.unhandled(signal)

    private def rebound(stack: Stack, entries: Stack.Snapshot, ctx: Context): Context =
        var c = ctx
        var i = 0
        while i < entries.regions do
            entries.handler(i) match
                case hc: Handler.ContextHandler[VX, CX, ?, ?] @unchecked =>
                    val j = stack.find(hc.tag)
                    c = if j < 0 then c.remove(hc.tag) else c.update(hc.tag, stack.state(j).asInstanceOf[VX])
                case _ => ()
            end match
            i += 1
        end while
        c
    end rebound

    private def released(handler: Handler.ContextHandler[?, ?, ?, ?], state: Any, ex: Throwable): Unit =
        Debugger.onRelease(handler, ex)
        try handler.asInstanceOf[Handler.ContextHandler[Any, ContextEffect[Any], Any, Any]].release(state, ex)
        catch
            case t if NonFatal(t) && (t ne ex) => ex.addSuppressed(t)
            case t if NonFatal(t)              => ()
        end try
    end released

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

        def park[T, B, C, S2](v: T < S2, contA: Arrow[T, B, S2], contB: Arrow[B, C, S2]): A < S =
            val parked: Any < Any =
                if contA.isInstanceOf[Arrow.Id[?]] && contB.isInstanceOf[Arrow.Id[?]] then v.asInstanceOf[Any < Any]
                else Effect.defer(v, contA, contB).asInstanceOf[Any < Any]
            val owedNow = stack.takeEvalOwed()
            if stack.isEmpty then
                if owedNow.isEmpty then parked.asInstanceOf[A < S]
                else Kyo.Park[A, S](parked, Stack.Snapshot.empty, owedNow)
            else
                Debugger.whenEnabled {
                    var j = stack.depth - 1
                    while j >= 0 do
                        Debugger.onRegionExit(stack.handler(j), parked)
                        j -= 1
                }
                Kyo.Park[A, S](parked, stack.snapshot(), owedNow)
            end if
        end park

        def installed(kyo: Kyo.Park[?, ?], resume: Arrow[Any, Any, Any], ctx: Context): Context =
            val entries = kyo.entries
            var ri      = 0
            while ri < entries.regions do
                entries.handler(ri) match
                    case hc: Handler.ContextHandler[VX, CX, ?, ?] @unchecked =>
                        try hc.reenter(entries.state(ri).asInstanceOf[VX])
                        catch
                            case ex if NonFatal(ex) =>
                                release(kyo, ex)
                                throw ex
                    case _ => ()
                end match
                ri += 1
            end while
            stack.oweBelow(stack.depth, kyo.owed)
            @tailrec def install(i: Int, c: Context): Context =
                if i == entries.regions then c
                else
                    val stored = entries.continuation(i).asInstanceOf[Arrow[Y, Any, Any]]
                    val cont =
                        if i == 0 then stored.chain(resume)
                        else stored
                    entries.handler(i) match
                        case hc: Handler.ContextHandler[VX, CX, Y, Any] @unchecked =>
                            val st = entries.state(i).asInstanceOf[VX]
                            Debugger.onRegionEnter(hc, st)
                            stack.push(hc, st, cont)
                            stack.owe(stack.depth - 1, entries.owed(i))
                            install(i + 1, c.update(hc.tag, st))
                        case handler0 =>
                            val handler = handler0.asInstanceOf[Handler[EX, Y, Any]]
                            val st      = entries.state(i).asInstanceOf[VX]
                            Debugger.onRegionEnter(handler, st)
                            stack.push(handler, st, cont)
                            stack.owe(stack.depth - 1, entries.owed(i))
                            install(i + 1, c)
                    end match
            install(0, ctx)
        end installed

        def contextExit(hc: Handler.ContextHandler[VX, CX, ?, ?], top: Int, ctx: Context): Context =
            hc.done(stack.state(top).asInstanceOf[VX])
            stack.pop()
            if stack.owesAny then drainDiscarded(stack.takePopped())
            val j = stack.find(hc.tag)
            if j < 0 then ctx.remove(hc.tag)
            else ctx.update(hc.tag, stack.state(j).asInstanceOf[VX])
        end contextExit

        def arrowExit(): Unit =
            stack.pop()
            if stack.owesAny then drainDiscarded(stack.takePopped())

        @tailrec def loop[T, B, C, S2](v: T < S2, contA: Arrow[T, B, S2], contB: Arrow[B, C, S2], ctx: Context): A < S =
            Debugger.onLoop(v, contA, contB)
            v match
                case kyo: Kyo.Defer[?, ?, T, S2] @unchecked =>
                    if armed && Safepoint.stopped(slot) then park(v, contA, contB)
                    else
                        loop(kyo.value, kyo.contA, kyo.contB.chain(contA.chain(contB)), ctx)

                case kyo: Kyo.Suspend[?, ?, T, S2] @unchecked =>
                    kyo match
                        case kyo: Kyo.SuspendContext[VX, CX, T, S2] @unchecked =>
                            val state = ctx.get(kyo.tag).orElse(kyo.default).getOrElse(unhandled(kyo))
                            Debugger.onContext(kyo, ctx)
                            loop(kyo.cont(state, contA.chain(contB)), Arrow.id, Arrow.id, ctx)

                        case kyo: Kyo.SuspendArrow[IX, OX, EX, VX, T, EX & S2] @unchecked =>
                            val idx = stack.find(kyo.tag)
                            if idx < 0 then unhandled(kyo)
                            else
                                Debugger.onHandle(kyo, stack.handler(idx), stack.state(idx))
                                val atTop = idx == stack.depth - 1
                                if !atTop then Debugger.onForeign(kyo, stack.handler(stack.depth - 1))
                                val entries = if atTop then Stack.Snapshot.empty else dumped(stack, idx, kyo)
                                val ctx2    = if atTop then ctx else rebound(stack, entries, ctx)
                                def continuation =
                                    if atTop then kyo.cont.chain(contA.chain(contB))
                                    else kyo.crossing(entries, contA.chain(contB))
                                stack.handler(idx) match
                                    case handler: Handler.ContHandler[IX, OX, EX, C, Y, S2] @unchecked =>
                                        val result = handler.run(kyo.input, continuation)
                                        Debugger.onResult(result)
                                        loop(result, Arrow.id, Arrow.id, ctx2)
                                    case handler: Handler.ContOpHandler[EX, C, Y, S2] @unchecked =>
                                        val operation: OX[VX] < EX =
                                            new Kyo.SuspendArrow[IX, OX, EX, VX, OX[VX], EX]:
                                                def tag   = kyo.tag
                                                def input = kyo.input
                                                def cont  = Arrow.id
                                        val result = handler.run(operation, continuation)
                                        Debugger.onResult(result)
                                        loop(result, Arrow.id, Arrow.id, ctx2)
                                    case handler: Handler.LoopHandler[VX, IX, OX, EX, C, Y, S2] @unchecked if atTop =>
                                        val k    = kyo.cont.chain(contA.chain(contB)).asInstanceOf[Arrow[Any, Any, Any]]
                                        val exit = handler.answers(stack.state(idx).asInstanceOf[VX], kyo.input, k, armed, slot)
                                        Debugger.onResult(exit)
                                        exit match
                                            case e: Loop.Continue2[VX, Any] @unchecked =>
                                                stack.setState(idx, e._1)
                                                loop(e._2.asInstanceOf[Any < S2], Arrow.id, Arrow.id, ctx)
                                            case pending: Pending[Outcome2[VX, OX[VX] < (EX & S2), Y < S2], S2] @unchecked =>
                                                val reentry = k.asInstanceOf[Arrow[OX[VX], C, EX & S2]]
                                                val next    = stack.continuation(idx).asInstanceOf[Arrow[Y, Any, S2]]
                                                Debugger.onRegionExit(handler, pending)
                                                stack.pop()
                                                if stack.owesAny then stack.oweBelow(idx, stack.takePopped())
                                                type OutT = Outcome2[VX, OX[VX] < (EX & S2), Y < S2]
                                                loop[OutT, Y, Any, S2](pending, handler.clauseDispatch(reentry), next, ctx)
                                            case done =>
                                                val result =
                                                    Nested.unnest[Y < S2](Loop.unnest(done.asInstanceOf[Outcome2[VX, Any, Y < S2]]))
                                                Debugger.onRegionExit(handler, result)
                                                val next = stack.continuation(idx).asInstanceOf[Arrow[Y, Any, Any]]
                                                if stack.owesAny then drainDiscarded(stack.takeOwed(idx))
                                                stack.truncate(idx)
                                                loop(result, next, Arrow.id, ctx)
                                        end match
                                    case handler: Handler.LoopHandler[VX, IX, OX, EX, C, Y, S2] @unchecked =>
                                        val outcome0 = handler.run(stack.state(idx).asInstanceOf[VX], kyo.input)
                                        stack.scratch = outcome0
                                        Debugger.onResult(outcome0)
                                        outcome0 match
                                            case outcome: Loop.Continue2[VX, OX[VX] < (EX & S2)] @unchecked =>
                                                stack.setState(idx, outcome._1)
                                                loop(outcome._2, continuation, Arrow.id, ctx2)
                                            case pending: Pending[Outcome2[VX, OX[VX] < (EX & S2), Y < S2], S2] @unchecked =>
                                                val next = stack.continuation(idx).asInstanceOf[Arrow[Y, Any, S2]]
                                                Debugger.onRegionExit(handler, pending)
                                                stack.pop()
                                                if stack.owesAny then stack.oweBelow(idx, stack.takePopped())
                                                type OutT = Outcome2[VX, OX[VX] < (EX & S2), Y < S2]
                                                val reentry2 = continuation.asInstanceOf[Arrow[OX[VX], C, EX & S2]]
                                                loop[OutT, Y, Any, S2](pending, handler.clauseDispatch(reentry2), next, ctx2)
                                            case outcome =>
                                                val result =
                                                    Nested.unnest[Y < S2](Loop.unnest(outcome.asInstanceOf[Outcome2[
                                                        VX,
                                                        OX[VX] < (EX & S2),
                                                        Y < S2
                                                    ]]))
                                                Debugger.onRegionExit(handler, result)
                                                val next = stack.continuation(idx).asInstanceOf[Arrow[Y, Any, Any]]
                                                if stack.owesAny then drainDiscarded(stack.takeOwed(idx))
                                                stack.truncate(idx)
                                                loop(result, next, Arrow.id, ctx2)
                                        end match
                                    case handler =>
                                        unanswerable(handler)
                                end match
                            end if

                case kyo: Kyo.Handle[?, CX, ?, ?, T, S2] @unchecked =>
                    kyo.handler match
                        case handler: Handler.ContextHandler[VX, CX, ?, ?] @unchecked =>
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

                case kyo: Kyo.Park[?, ?] if kyo.entries.isEmpty =>
                    stack.oweBelow(stack.depth, kyo.owed)
                    loop(kyo.value.asInstanceOf[T < S2], contA, contB, ctx)

                case kyo: Kyo.Park[?, ?] =>
                    loop(kyo.value, Arrow.id, Arrow.id, installed(kyo, contA.chain(contB).asInstanceOf[Arrow[Any, Any, Any]], ctx))

                case kyo: Kyo.Snapshot[T, S2] @unchecked =>
                    loop(kyo.cont(stack.contextual(), contA.chain(contB)), Arrow.id, Arrow.id, ctx)

                case res =>
                    if contA.isInstanceOf[Arrow.Id[?]] && contB.isInstanceOf[Arrow.Id[?]] then
                        if stack.isEmpty then res.asInstanceOf[A < S]
                        else
                            val top  = stack.depth - 1
                            val next = stack.continuation(top).asInstanceOf[Arrow[Y, Any, Any]]
                            stack.handler(top) match
                                case hc: Handler.ContextHandler[VX, CX, ?, ?] @unchecked =>
                                    Debugger.onRegionExit(hc, res)
                                    loop(res.asInstanceOf[Y < Any], next, Arrow.id, contextExit(hc, top, ctx))
                                case handler0 =>
                                    val handler = handler0.asInstanceOf[Handler.ArrowHandler[VX, EX, AX, Y, Any]]
                                    val result  = handler.done(stack.state(top).asInstanceOf[VX], Nested.unnest[AX](res))
                                    Debugger.onRegionExit(handler, result)
                                    arrowExit()
                                    loop(result, next, Arrow.id, ctx)
                            end match
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
                val owedNow = stack.takeEvalOwed()
                if !owedNow.isEmpty then drainOwed(owedNow, ex)
                EffectTrace.splice(ex)
                throw ex
            else
                val top   = stack.depth - 1
                val state = stack.state(top)
                stack.handler(top) match
                    case hc: Handler.ContextHandler[VX, CX, ?, ?] @unchecked =>
                        Debugger.onRegionExit(hc, ex)
                        stack.pop()
                        val owedHere = stack.takePopped()
                        if !owedHere.isEmpty then drainOwed(owedHere, ex)
                        released(hc, state, ex)
                        recovered(ex)
                    case handler0 =>
                        val handler = handler0.asInstanceOf[Handler.ArrowHandler[VX, EX, AX, Y, Any]]
                        val outcome =
                            try handler.recover(state.asInstanceOf[VX], ex)
                            catch
                                case ex2 if NonFatal(ex2) =>
                                    Debugger.onRegionExit(handler, ex2)
                                    stack.pop()
                                    val owedHere = stack.takePopped()
                                    if !owedHere.isEmpty then drainOwed(owedHere, ex2)
                                    EffectTrace.attach(ex2, stack)
                                    EffectTrace.splice(ex2)
                                    return recovered(ex2)
                        outcome match
                            case Present(r) =>
                                Debugger.onRecover(handler, ex)
                                Debugger.onRegionExit(handler, r)
                                r.chain(stack.continuation(top).asInstanceOf[Arrow[Y, A, S]])
                            case Absent =>
                                Debugger.onRegionExit(handler, ex)
                                stack.pop()
                                val owedHere = stack.takePopped()
                                if !owedHere.isEmpty then drainOwed(owedHere, ex)
                                recovered(ex)
                        end match
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
                        val owedHere = stack.takePopped()
                        if !owedHere.isEmpty then drainOwed(owedHere, failure)
                        @tailrec def rebuild(i: Int, rebuilt: Context): Context =
                            if i == stack.depth then rebuilt
                            else
                                stack.handler(i) match
                                    case handler: Handler.ContextHandler[VX, CX, ?, ?] @unchecked =>
                                        rebuild(i + 1, rebuilt.update(handler.tag, stack.state(i).asInstanceOf[VX]))
                                    case _ => rebuild(i + 1, rebuilt)
                        return guarded(resumed, rebuild(0, Context.empty))
            res match
                case susp: Kyo.Suspend[?, ?, ?, ?] =>

                    bug(s"unhandled suspension: $susp")
                case res => res
            end match
        end guarded

        try
            val out = guarded(v, Context.empty)
            drainDiscarded(stack.takeEvalOwed())
            out
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

package kyo.kernel.internal

import kyo.Chunk
import kyo.Frame
import kyo.KyoException
import kyo.Maybe
import kyo.Maybe.Absent
import kyo.Maybe.Present
import kyo.bug
import kyo.discard
import kyo.kernel.<
import kyo.kernel.Arrow
import kyo.kernel.ArrowEffect
import kyo.kernel.ContextEffect
import kyo.kernel.Effect
import kyo.kernel.Loop
import kyo.kernel.Loop.Outcome
import kyo.kernel.Loop.Outcome2
import language.implicitConversions
import scala.annotation.publicInBinary
import scala.annotation.tailrec
import scala.collection.mutable.ArrayBuffer
import scala.util.control.NonFatal

@publicInBinary private[kyo] object Eval:

    def apply[A, S](v: A < S): A < S =
        apply(v, armed = false)

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
                case kyo: Pending.Defer[?, ?, T, S2] @unchecked =>
                    if armed && Safepoint.stopped(slot) then
                        park(v, contA, contB)
                    else
                        loop(kyo.value, kyo.contA, kyo.contB.chain(contA.chain(contB)), ctx)

                case kyo: Pending.Suspend[?, ?, T, S2] @unchecked =>
                    kyo match
                        case kyo: Pending.SuspendContext[VX, CX, T, S2] @unchecked =>
                            val state = ctx.get(kyo.tag).orElse(kyo.default).getOrElse(unhandled(kyo, stack))
                            Debugger.onContext(kyo, ctx)
                            loop(kyo.cont(state, contA.chain(contB)), Arrow.id, Arrow.id, ctx)

                        case kyo: Pending.SuspendArrow[IX, OX, EX, VX, T, EX & S2] @unchecked =>
                            val idx = stack.find(kyo.tag)
                            if idx < 0 then
                                unhandled(kyo, stack)
                            else
                                Debugger.onHandle(kyo, stack.handler(idx), stack.state(idx))
                                val atTop = idx == stack.depth - 1
                                if !atTop then Debugger.onForeign(kyo, stack.handler(stack.depth - 1))
                                stack.handler(idx) match
                                    case handler: Handler.ContHandler[IX, OX, EX, C, Y, S2] @unchecked =>
                                        // TODO how about we move the atTop branching to the called methods?
                                        val entries = if atTop then Stack.Snapshot.empty else dumped(stack, idx, kyo)
                                        val ctx2    = if atTop then ctx else rebound(entries, ctx)
                                        val continuation =
                                            if atTop then kyo.cont.chain(contA.chain(contB))
                                            else kyo.crossing(entries, contA.chain(contB))
                                        val result = handler.answering(kyo.input, continuation, kyo, stack)
                                        Debugger.onResult(result)
                                        // the stop is honored on the clause's answer, as the loop walks do on
                                        // theirs: an answer that re-raises the operation would otherwise be
                                        // dispatched straight back to this clause with no deferral to park at
                                        if armed && Safepoint.stopped(slot) then park(result, Arrow.id, Arrow.id)
                                        else loop(result, Arrow.id, Arrow.id, ctx2)
                                    case handler: Handler.ContOpHandler[EX, C, Y, S2] @unchecked =>
                                        val entries = if atTop then Stack.Snapshot.empty else dumped(stack, idx, kyo)
                                        val ctx2    = if atTop then ctx else rebound(entries, ctx)
                                        val continuation =
                                            if atTop then kyo.cont.chain(contA.chain(contB))
                                            else kyo.crossing(entries, contA.chain(contB))
                                        val operation: OX[VX] < EX =
                                            new Pending.SuspendArrow[IX, OX, EX, VX, OX[VX], EX]:
                                                def tag   = kyo.tag
                                                def input = kyo.input
                                                def cont  = Arrow.id
                                        val result = handler.answering(operation, continuation, kyo, stack)
                                        Debugger.onResult(result)
                                        if armed && Safepoint.stopped(slot) then park(result, Arrow.id, Arrow.id)
                                        else loop(result, Arrow.id, Arrow.id, ctx2)
                                    // TODO are you sure the repeated code for the special atTop case is worth it? size of the loop mehtod is critical for performance
                                    case handler: Handler.LoopHandler[IX, OX, EX, C, Y, S2] @unchecked if atTop =>
                                        val k    = kyo.cont.chain(contA.chain(contB))
                                        val exit = handler.answers(kyo.input, k, armed, slot, kyo.frame)
                                        Debugger.onResult(exit)
                                        exit match
                                            case e: Loop.Continue[C < (EX & S2)] @unchecked =>
                                                loop(e._1, Arrow.id, Arrow.id, ctx)
                                            case pending: Pending[Outcome[OX[VX] < (EX & S2), Y < S2], S2] @unchecked =>
                                                val next = stack.continuation(idx).asInstanceOf[Arrow[Y, Any, S2]]
                                                Debugger.onRegionExit(handler, pending)
                                                stack.pop()
                                                if stack.owesAny then stack.oweBelow(idx, stack.takePopped())
                                                type OutT = Outcome[OX[VX] < (EX & S2), Y < S2]
                                                loop[OutT, Y, Any, S2](pending, handler.clauseDispatch(k), next, ctx)
                                            case done =>
                                                val result =
                                                    Nested.unnest[Y < S2](Loop.unnest(done.asInstanceOf[Outcome[Any, Y < S2]]))
                                                Debugger.onRegionExit(handler, result)
                                                val next = stack.continuation(idx).asInstanceOf[Arrow[Y, Any, Any]]
                                                if stack.owesAny then drainDiscarded(stack.takeOwed(idx))
                                                stack.truncate(idx)
                                                loop(result, next, Arrow.id, ctx)
                                        end match
                                    case handler: Handler.LoopHandler[IX, OX, EX, C, Y, S2] @unchecked =>
                                        val outcome0 = handler.running(kyo.input, kyo, stack, idx)
                                        stack.sink = outcome0
                                        Debugger.onResult(outcome0)
                                        outcome0 match
                                            case outcome: Loop.Continue[OX[VX] < (EX & S2)] @unchecked =>
                                                val ans = outcome._1
                                                if !ans.isInstanceOf[Pending[?, ?]] then
                                                    loop(ans, kyo.cont, contA.chain(contB), ctx)
                                                else
                                                    val entries = dumped(stack, idx, kyo)
                                                    val ctx2    = rebound(entries, ctx)
                                                    loop(ans, kyo.crossing(entries, contA.chain(contB)), Arrow.id, ctx2)
                                                end if
                                            case pending: Pending[Outcome[OX[VX] < (EX & S2), Y < S2], S2] @unchecked =>
                                                val entries = dumped(stack, idx, kyo)
                                                val ctx2    = rebound(entries, ctx)
                                                val next    = stack.continuation(idx).asInstanceOf[Arrow[Y, Any, S2]]
                                                Debugger.onRegionExit(handler, pending)
                                                stack.pop()
                                                if stack.owesAny then stack.oweBelow(idx, stack.takePopped())
                                                type OutT = Outcome[OX[VX] < (EX & S2), Y < S2]
                                                val reentry2 = kyo.crossing(entries, contA.chain(contB))
                                                loop[OutT, Y, Any, S2](pending, handler.clauseDispatch(reentry2), next, ctx2)
                                            case outcome =>
                                                val entries = dumped(stack, idx, kyo)
                                                val ctx2    = rebound(entries, ctx)
                                                val result =
                                                    Nested.unnest[Y < S2](Loop.unnest(outcome.asInstanceOf[Outcome[
                                                        OX[VX] < (EX & S2),
                                                        Y < S2
                                                    ]]))
                                                Debugger.onRegionExit(handler, result)
                                                val next = stack.continuation(idx).asInstanceOf[Arrow[Y, Any, Any]]
                                                if stack.owesAny then drainDiscarded(stack.takeOwed(idx))
                                                stack.truncate(idx)
                                                loop(result, next, Arrow.id, ctx2)
                                        end match
                                    case handler: Handler.LoopStateHandler[VX, IX, OX, EX, C, Y, S2] @unchecked if atTop =>
                                        val k    = kyo.cont.chain(contA.chain(contB))
                                        val exit = handler.answers(stack.state(idx).asInstanceOf[VX], kyo.input, k, armed, slot, kyo.frame)
                                        Debugger.onResult(exit)
                                        exit match
                                            case e: Loop.Continue2[VX, C < (EX & S2)] @unchecked =>
                                                stack.setState(idx, e._1)
                                                loop(e._2, Arrow.id, Arrow.id, ctx)
                                            case pending: Pending[Outcome2[VX, OX[VX] < (EX & S2), Y < S2], S2] @unchecked =>
                                                val next = stack.continuation(idx).asInstanceOf[Arrow[Y, Any, S2]]
                                                Debugger.onRegionExit(handler, pending)
                                                stack.pop()
                                                if stack.owesAny then stack.oweBelow(idx, stack.takePopped())
                                                type OutT = Outcome2[VX, OX[VX] < (EX & S2), Y < S2]
                                                loop[OutT, Y, Any, S2](pending, handler.clauseDispatch(k), next, ctx)
                                            case done =>
                                                val result =
                                                    Nested.unnest[Y < S2](Loop.unnest(done.asInstanceOf[Outcome2[VX, Any, Y < S2]]))
                                                Debugger.onRegionExit(handler, result)
                                                val next = stack.continuation(idx).asInstanceOf[Arrow[Y, Any, Any]]
                                                if stack.owesAny then drainDiscarded(stack.takeOwed(idx))
                                                stack.truncate(idx)
                                                loop(result, next, Arrow.id, ctx)
                                        end match
                                    case handler: Handler.LoopStateHandler[VX, IX, OX, EX, C, Y, S2] @unchecked =>
                                        val outcome0 = handler.running(stack.state(idx).asInstanceOf[VX], kyo.input, kyo, stack, idx)
                                        stack.sink = outcome0
                                        Debugger.onResult(outcome0)
                                        outcome0 match
                                            case outcome: Loop.Continue2[VX, OX[VX] < (EX & S2)] @unchecked =>
                                                stack.setState(idx, outcome._1)
                                                val ans = outcome._2
                                                if !ans.isInstanceOf[Pending[?, ?]] then
                                                    loop(ans, kyo.cont, contA.chain(contB), ctx)
                                                else
                                                    val entries = dumped(stack, idx, kyo)
                                                    val ctx2    = rebound(entries, ctx)
                                                    loop(ans, kyo.crossing(entries, contA.chain(contB)), Arrow.id, ctx2)
                                                end if
                                            case pending: Pending[Outcome2[VX, OX[VX] < (EX & S2), Y < S2], S2] @unchecked =>
                                                val entries = dumped(stack, idx, kyo)
                                                val ctx2    = rebound(entries, ctx)
                                                val next    = stack.continuation(idx).asInstanceOf[Arrow[Y, Any, S2]]
                                                Debugger.onRegionExit(handler, pending)
                                                stack.pop()
                                                if stack.owesAny then stack.oweBelow(idx, stack.takePopped())
                                                type OutT = Outcome2[VX, OX[VX] < (EX & S2), Y < S2]
                                                val reentry2 = kyo.crossing(entries, contA.chain(contB))
                                                loop[OutT, Y, Any, S2](pending, handler.clauseDispatch(reentry2), next, ctx2)
                                            case outcome =>
                                                val entries = dumped(stack, idx, kyo)
                                                val ctx2    = rebound(entries, ctx)
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

                case kyo: Pending.HandleArrow[?, ?, ?, ?, T, S2] @unchecked =>
                    Debugger.onRegionEnter(kyo.handler, kyo.state)
                    stack.push(kyo.handler, kyo.state, kyo.cont.chain(contA.chain(contB)))
                    loop(kyo.value, Arrow.id, Arrow.id, ctx)

                case kyo: Pending.HandleContext[VX, CX, T, S2] @unchecked =>
                    val handler    = kyo.handler
                    val newState   = handler.derive(ctx.get(handler.tag))
                    val newContext = ctx.bind(handler.tag, newState)
                    Debugger.onContext(kyo, newContext)
                    Debugger.onRegionEnter(handler, newState)
                    stack.push(handler, newState, contA.chain(contB))
                    loop(kyo.value, Arrow.id, Arrow.id, newContext)

                case kyo: Pending.Park[?, ?] if kyo.entries.isEmpty =>
                    stack.oweBelow(stack.depth, kyo.owed)
                    loop(kyo.value.asInstanceOf[T < S2], contA, contB, ctx)

                case kyo: Pending.Park[?, ?] =>
                    loop(kyo.value, Arrow.id, Arrow.id, installed(kyo, contA.chain(contB).asInstanceOf[Arrow[Any, Any, Any]], ctx))

                case kyo: Pending.Snapshot[T, S2] @unchecked =>
                    loop(kyo.cont(stack, contA.chain(contB)), Arrow.id, Arrow.id, rebuilt())

                case res =>
                    if contA.isInstanceOf[Arrow.Id[?]] && contB.isInstanceOf[Arrow.Id[?]] then
                        if stack.isEmpty then
                            res.asInstanceOf[A < S]
                        else
                            val top  = stack.depth - 1
                            val next = stack.continuation(top).asInstanceOf[Arrow[Y, Any, Any]]
                            stack.handler(top) match
                                case hc: Handler.ContextHandler[VX, CX, AX, ?] @unchecked =>
                                    Debugger.onRegionExit(hc, res)
                                    loop(res.asInstanceOf[Y < Any], next, Arrow.id, contextExit(hc, top, Nested.unnest[AX](res), ctx))
                                case handler0 =>
                                    val handler = handler0.asInstanceOf[Handler.ArrowHandler[VX, EX, AX, Y, Any]]
                                    val result  = handler.done(stack.state(top).asInstanceOf[VX], Nested.unnest[AX](res))
                                    Debugger.onRegionExit(handler, result)
                                    arrowExit(handler)
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

        def park[T, B, C, S2](v: T < S2, contA: Arrow[T, B, S2], contB: Arrow[B, C, S2]): A < S =
            val parked: Any < Any =
                if contA.isInstanceOf[Arrow.Id[?]] && contB.isInstanceOf[Arrow.Id[?]] then v.asInstanceOf[Any < Any]
                else Effect.defer(v, contA, contB).asInstanceOf[Any < Any]
            val owedNow = stack.takeEvalOwed()
            if stack.isEmpty then
                if owedNow.isEmpty then parked.asInstanceOf[A < S]
                else Pending.Park[A, S](parked, Stack.Snapshot.empty, owedNow)
            else
                Debugger.whenEnabled {
                    var j = stack.depth - 1
                    while j >= 0 do
                        Debugger.onRegionExit(stack.handler(j), parked)
                        j -= 1
                }
                Pending.Park[A, S](parked, stack.takeAll(), owedNow)
            end if
        end park

        def installed(kyo: Pending.Park[?, ?], resume: Arrow[Any, Any, Any], ctx: Context): Context =
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

            stack.settle(entries)
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
                            install(i + 1, c.bind(hc.tag, st))
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

        def contextExit(hc: Handler.ContextHandler[VX, CX, AX, ?], top: Int, value: AX, ctx: Context): Context =
            hc.done(stack.state(top).asInstanceOf[VX], value)
            stack.pop()
            if stack.owesAny then
                drainDiscarded(stack.takePopped())
            ctx.unbind
        end contextExit

        // a region that hands its continuation out has not discarded what it owes: the debt moves to
        // the scope below, as it does for a region exiting with a pending outcome, and is settled by
        // identity when the remainder resumes or drained where that scope ends
        def arrowExit(handler: Handler.ArrowHandler[?, ?, ?, ?, ?]): Unit =
            stack.pop()
            if stack.owesAny then
                if handler.handsOut then stack.oweBelow(stack.depth, stack.takePopped())
                else drainDiscarded(stack.takePopped())
        end arrowExit

        def rebuilt(): Context =
            @tailrec def rebuild(i: Int, c: Context): Context =
                if i == stack.depth then c
                else
                    stack.handler(i) match
                        case handler: Handler.ContextHandler[VX, CX, ?, ?] @unchecked =>
                            rebuild(i + 1, c.bind(handler.tag, stack.state(i).asInstanceOf[VX]))
                        case _ => rebuild(i + 1, c)
            rebuild(0, Context.empty)
        end rebuilt

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
                            try if NonFatal(ex) then handler.recover(state.asInstanceOf[VX], ex) else Absent
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
                    case failure =>

                        Safepoint.reset(slot)

                        EffectTrace.attach(failure, stack)
                        EffectTrace.splice(failure)
                        val resumed = recovered(failure)
                        stack.pop()
                        val owedHere = stack.takePopped()
                        if !owedHere.isEmpty then drainOwed(owedHere, failure)
                        return guarded(resumed, rebuilt())
            res match
                case susp: Pending.Suspend[?, ?, ?, ?] =>

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

    private def unhandled(kyo: Pending[?, ?], stack: Stack): Nothing =
        try bug(s"unhandled suspension: $kyo")
        catch
            case ex =>
                EffectTrace.attach(ex, kyo, stack)
                throw ex

    private def unanswerable(handler: Handler[?, ?, ?]): Nothing = bug(s"unhandled: $handler")

    private[kernel] def dumped(stack: Stack, idx: Int, kyo: Pending.Suspend[?, ?, ?, ?]): Stack.Snapshot =
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

    private def rebound(entries: Stack.Snapshot, ctx: Context): Context =
        var c = ctx
        var i = 0
        while i < entries.regions do
            if entries.handler(i).isInstanceOf[Handler.ContextHandler[?, ?, ?, ?]] then c = c.unbind
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

    def release[A, S](v: A < S, ex: Throwable): Unit =
        val collected = ArrayBuffer.empty[AnyRef]
        @tailrec def collect(v: Any): Unit =
            v match
                case p: Pending[?, ?] =>
                    p match
                        case kyo: Pending.Defer[?, ?, ?, ?] =>
                            collect(kyo.value)
                        case kyo: Pending.HandleContext[VX, CX, ?, ?] @unchecked =>
                            val hc = kyo.handler
                            collected += hc
                            collected += hc.derive(Maybe.empty).asInstanceOf[AnyRef]
                            collect(kyo.value)
                        case kyo: Pending.Handle[?, ?, ?, ?] =>
                            collect(kyo.value)
                        case kyo: Pending.Park[?, ?] =>
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
                        case _: Pending.Suspend[?, ?, ?, ?] => ()
                        case _: Pending.Snapshot[?, ?]      => ()
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

    type IX[_]
    type OX[_]
    type EX <: ArrowEffect[IX, OX]
    type VX
    type CX <: ContextEffect[VX]
    type AX
    type Y
    type IY[_]
    type OY[_]
    type EY <: ArrowEffect[IY, OY]
    type VY
end Eval

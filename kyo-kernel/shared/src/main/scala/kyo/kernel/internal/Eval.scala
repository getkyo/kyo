package kyo.kernel.internal

import kyo.Chunk
import kyo.Frame
import kyo.KyoException
import kyo.Maybe
import kyo.Maybe.Absent
import kyo.Maybe.Present
import kyo.Tag
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
            var defers  = false
            while ri < entries.regions do
                entries.handler(ri) match
                    case hc: Handler.ContextHandler[VX, CX, ?, ?] @unchecked =>
                        if hc.defers(entries.state(ri).asInstanceOf[VX]) then defers = true
                        try hc.reenter(entries.state(ri).asInstanceOf[VX])
                        catch
                            case ex if NonFatal(ex) =>
                                release(kyo, ex)
                                throw ex
                        end try
                    case _ => ()
                end match
                ri += 1
            end while

            // Settling hands each region back its own answerability, which a region that discharges exactly once
            // cannot take while the continuation can be resumed again. Leaving the debt where it is keeps the
            // obligation with the handler that dumped it, to be discharged where that handler ends.
            if !defers then stack.settle(entries)
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
                if handler.escaping then stack.oweBelow(stack.depth, stack.takePopped())
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
        // This continuation can be resumed more than once, either here or wherever it is handed to, so the regions
        // going into it must not discharge themselves when the first resumption ends their extents. Held out of
        // line: every answer passes through here, and only the declaring handlers walk the regions.
        if stack.handler(idx).repeated then held(entries)
        Debugger.whenEnabled {
            var i = entries.regions - 1
            while i >= 0 do
                Debugger.onRegionExit(entries.handler(i), kyo)
                i -= 1
        }
        entries
    end dumped

    // The regions a held continuation carries, marked so that the first resumption to end their extents records
    // its outcome rather than discharging them; whoever owes them discharges them when it ends.
    private def held(entries: Stack.Snapshot): Unit =
        var i = 0
        while i < entries.regions do
            entries.handler(i) match
                case hc: Handler.ContextHandler[Any, ?, ?, ?] @unchecked => hc.borrow(entries.state(i))
                case _                                                   => ()
            i += 1
        end while
    end held

    private def drainDiscarded(owed: Chunk[Stack.Snapshot]): Unit =
        if !owed.isEmpty then
            val signal = new KyoException("remainder discarded")(using Frame.internal)
            // the owner is ending normally, so a held region's recorded outcome is what its release is owed; the
            // signal only stands in for a region that never ran to an ending
            drainOwed(owed, signal, discharging = true)
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

    private def released(
        handler: Handler.ContextHandler[?, ?, ?, ?],
        state: Any,
        ex: Throwable,
        discharging: Boolean = false
    ): Unit =
        Debugger.onRelease(handler, ex)
        val hc = handler.asInstanceOf[Handler.ContextHandler[Any, ContextEffect[Any], Any, Any]]
        try if discharging then hc.discharge(state, ex) else hc.release(state, ex)
        catch
            case t if NonFatal(t) && (t ne ex) => ex.addSuppressed(t)
            case t if NonFatal(t)              => ()
        end try
    end released

    /** Releases the regions `v` still holds, running nothing.
      *
      * A deferral is walked rather than evaluated, so what it holds stays unreached. That is what a caller
      * releasing a continuation it has just refused needs: the refusal exists to stop that continuation
      * from running, and a walk that evaluated it would run the very thing being refused.
      */
    def release[A, S](v: A < S, ex: Throwable): Unit =
        release(v, ex, Absent, _ => (), 0)

    /** Releases the regions `v` still holds, and hands `f` the input of the first operation under them that
      * `effectTag` answers, so a caller that owes something to an operation the computation never reached
      * can settle it without walking the computation a second time. `f` runs before anything is released.
      *
      * An operation under a deferral exists only once the deferral has run, so this evaluates them to reach
      * it, bounded. That is for a caller abandoning a computation whole, where running a little of what was
      * about to run is the price of not stranding what it was about to wait on. A caller releasing a
      * continuation it refused wants [[release]] above, which evaluates nothing.
      */
    def release[I[_], O[_], E <: ArrowEffect[I, O], A, S](v: A < S, ex: Throwable, effectTag: Tag[E])(
        f: [C] => I[C] => Unit
    ): Unit =
        // Erasure-forced: the operation's state type is existential here, and `f` is the polymorphic
        // function that takes it back at that type.
        release(v, ex, Present(effectTag.erased), input => f[Any](input.asInstanceOf[I[Any]]), 16)

    private def release[A, S](v: A < S, ex: Throwable, effectTag: Maybe[Tag[Any]], f: Any => Unit, fuel: Int): Unit =
        val collected = ArrayBuffer.empty[AnyRef]
        @tailrec def collect(v: Any, fuel: Int): Unit =
            v match
                case p: Pending[?, ?] =>
                    p match
                        case kyo: Pending.Defer[a, b, c, s] @unchecked =>
                            if fuel > 0 then collect(kyo.contA(kyo.value, kyo.contB), fuel - 1)
                            else collect(kyo.value, fuel)
                        case kyo: Pending.HandleContext[VX, CX, ?, ?] @unchecked =>
                            val hc = kyo.handler
                            collected += hc
                            collected += hc.derive(Maybe.empty).asInstanceOf[AnyRef]
                            collect(kyo.value, fuel)
                        case kyo: Pending.Handle[?, ?, ?, ?] =>
                            collect(kyo.value, fuel)
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
                            collect(kyo.value, fuel)
                        case kyo: Pending.SuspendArrow[?, ?, ?, ?, ?, ?] @unchecked =>
                            effectTag.foreach(t => if t <:< kyo.tag.erased then f(kyo.input))
                        case _: Pending.Suspend[?, ?, ?, ?] => ()
                        case _: Pending.Snapshot[?, ?]      => ()
                case _ => ()
        collect(v, fuel)
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

    private def releaseCollected(collected: ArrayBuffer[AnyRef], ex: Throwable, discharging: Boolean = false): Unit =
        var i = collected.length - 2
        while i >= 0 do
            released(collected(i).asInstanceOf[Handler.ContextHandler[?, ?, ?, ?]], collected(i + 1), ex, discharging)
            i -= 2
        end while
    end releaseCollected

    private def drainOwed(owed: Chunk[Stack.Snapshot], ex: Throwable, discharging: Boolean = false): Unit =
        val collected = ArrayBuffer.empty[AnyRef]
        expandOwed(collected, owed)
        releaseCollected(collected, ex, discharging)
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

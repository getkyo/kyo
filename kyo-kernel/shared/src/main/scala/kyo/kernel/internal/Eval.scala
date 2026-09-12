package kyo.kernel.internal

import kyo.Chunk
import kyo.Frame
import kyo.IsFatal
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

/** The evaluator: unfolds a computation's nodes until it produces a value, or until there is nothing further it can do without an answer.
  *
  * A computation is a value built by composition, and this is an accelerator for running such values, not the definition of what they mean.
  * Every branch below is the operational reading of an equation expressible in the public combinators, which is why a gap here is a missing
  * value rather than a missing instruction.
  *
  * `loop` is the whole machine. It carries the node in hand and two continuations, and each arm either reduces the node and loops, or pushes
  * a region, or hands an answer to a handler. It is a tail-recursive loop rather than a recursive walk, which is where stack safety comes
  * from: depth in the computation costs heap, not call frames.
  *
  * Two things live beside it. [[Stack]] holds the regions installed around the node in hand, and [[Context]] holds the values bound by
  * context regions; the loop threads the context and mutates the stack, and both are borrowed for one evaluation.
  *
  * The loop is far too large to inline and every effect in the program passes through it, so its dispatch is megamorphic. That is the reason
  * the combinators fuse at their own call sites and reach the loop only when they must, and the reason cold work here is kept out of line
  * rather than written into the arms.
  */
@publicInBinary private[kyo] object Eval:

    /** Evaluates until a value is produced, with no preemption. */
    def apply[A, S](v: A < S): A < S =
        apply(v, armed = false)

    /** Evaluates until a value is produced or the safepoint says to stop, answering what is left as a computation to resume later.
      *
      * This is what a scheduler runs a fiber with: the stop flag is what turns a run into a slice, and the remainder that comes back is a
      * complete value, valid anywhere, so another thread may pick it up.
      */
    def partial[A](v: A < Any): A < Any =
        val slot = Safepoint.get()
        if Safepoint.consumeStopped(slot) then v
        else
            try apply(v, armed = true)
            finally discard(Safepoint.consumeStopped(slot))
        end if
    end partial

    // `armed` is a parameter rather than a test inside the loop: it is constant for the whole evaluation, so the
    // stop check folds away entirely for a run that cannot be preempted.
    private def apply[A, S](v: A < S, armed: Boolean): A < S =
        // one stack and one safepoint slot per evaluation
        val stack = Stack.borrow()
        val slot  = Safepoint.get()
        val saved = Safepoint.save(slot)
        if armed then Safepoint.arm(slot)

        @tailrec def loop[T, B, C, S2](v: T < S2, contA: Arrow[T, B, S2], contB: Arrow[B, C, S2], ctx: Context): A < S =
            Debugger.onLoop(v, contA, contB)
            v match
                // a deferral: unfold it, its two continuations going in front of ours
                case kyo: Pending.Defer[?, ?, T, S2] @unchecked =>
                    if armed && Safepoint.stopped(slot) then
                        park(v, contA, contB)
                    else
                        loop(kyo.value, kyo.contA, kyo.contB.chain(contA.chain(contB)), ctx)

                case kyo: Pending.Suspend[?, ?, T, S2] @unchecked =>
                    kyo match
                        // a context read: answered from the context, no region involved
                        case kyo: Pending.SuspendContext[VX, CX, T, CX & S2] @unchecked =>
                            val state = ctx.get(kyo.tag).orElse(kyo.default).getOrElse(unhandled(kyo, stack))
                            if state.asInstanceOf[AnyRef] ne Context.Masked then
                                Debugger.onContext(kyo, ctx)
                                loop(kyo.cont(state, contA.chain(contB)), Arrow.id, Arrow.id, ctx)
                            else
                                // A masking region shadows the binding this read would have answered from, so it
                                // dispatches there by the route an arrow operation takes.
                                val entries = maskedEntries(kyo)
                                val result  = maskedRead(kyo, entries, contA.chain(contB))
                                if armed && Safepoint.stopped(slot) then park(result, Arrow.id, Arrow.id)
                                else loop(result, Arrow.id, Arrow.id, rebound(entries, ctx))
                            end if

                        // an operation: the innermost region for its tag answers, by the shape of its handler
                        case kyo: Pending.SuspendArrow[IX, OX, EX, VX, T, EX & S2] @unchecked =>
                            val idx = stack.find(kyo.tag)
                            if idx < 0 then
                                unhandled(kyo, stack)
                            else
                                Debugger.onHandle(kyo, stack.handler(idx), stack.state(idx))
                                val atTop = idx == stack.depth - 1
                                if !atTop then Debugger.onForeign(kyo, stack.handler(stack.depth - 1))
                                stack.handler(idx) match
                                    // a cont clause: handed the continuation, with the regions above dumped into it
                                    case handler: Handler.ContHandler[IX, OX, EX, C, Y, S2] @unchecked =>
                                        val entries = if atTop then Stack.Snapshot.empty else dumped(stack, idx, kyo)
                                        val ctx2    = if atTop then ctx else rebound(entries, ctx)
                                        val continuation =
                                            if atTop then kyo.cont.chain(contA.chain(contB))
                                            else kyo.crossing(entries, contA.chain(contB))
                                        val result = handler.answering(kyo.input, continuation, kyo, stack)
                                        Debugger.onResult(result)
                                        // The stop is honored on the clause's answer: one that re-raises the
                                        // operation would otherwise dispatch straight back here with no deferral to park at.
                                        if armed && Safepoint.stopped(slot) then park(result, Arrow.id, Arrow.id)
                                        else loop(result, Arrow.id, Arrow.id, ctx2)
                                    // a masking clause: the same, handed the operation re-raised instead of its input
                                    case handler: Handler.MaskingHandler[EX, C, Y, S2] @unchecked =>
                                        val entries = if atTop then Stack.Snapshot.empty else dumped(stack, idx, kyo)
                                        val ctx2    = if atTop then ctx else rebound(entries, ctx)
                                        val continuation =
                                            if atTop then kyo.cont.chain(contA.chain(contB))
                                            else kyo.crossing(entries, contA.chain(contB))
                                        val result = handler.answering(kyo.reraise, continuation, kyo, stack)
                                        Debugger.onResult(result)
                                        if armed && Safepoint.stopped(slot) then park(result, Arrow.id, Arrow.id)
                                        else loop(result, Arrow.id, Arrow.id, ctx2)
                                    // a loop clause at the top: answered in place, the region staying installed
                                    case handler: Handler.LoopHandler[IX, OX, EX, C, Y, S2] @unchecked if atTop =>
                                        val k    = kyo.cont.chain(contA.chain(contB))
                                        val exit = handler.answers(kyo.input, k, armed, slot, kyo.frame)
                                        Debugger.onResult(exit)
                                        exit match
                                            // continued: the answer carries on inside the region
                                            case e: Loop.Continue[C < (EX & S2)] @unchecked =>
                                                loop(e._1, Arrow.id, Arrow.id, ctx)
                                            // suspended: the region ends, its outcome dispatched as the region's own continuation
                                            case pending: Pending[Outcome[C < (EX & S2), Y < S2], S2] @unchecked =>
                                                val next = stack.continuation(idx).asInstanceOf[Arrow[Y, Any, S2]]
                                                Debugger.onRegionExit(handler, pending)
                                                stack.pop()
                                                if stack.owesAny then stack.oweBelow(idx, stack.takePopped())
                                                type OutT = Outcome[C < (EX & S2), Y < S2]
                                                loop[OutT, Y, Any, S2](pending, handler.clauseDispatch, next, ctx)
                                            // done: the region ends with its value
                                            case done =>
                                                val result =
                                                    Nested.unnest[Y < S2](Loop.unnest(done.asInstanceOf[Outcome[Any, Y < S2]]))
                                                Debugger.onRegionExit(handler, result)
                                                val next = stack.continuation(idx).asInstanceOf[Arrow[Y, Any, Any]]
                                                if stack.owesAny then drainDiscarded(stack.takeOwed(idx))
                                                stack.truncate(idx)
                                                loop(result, next, Arrow.id, ctx)
                                        end match
                                    // a loop clause with regions above it: answered outside them, the same three outcomes as
                                    // above with the answer crossing back into the regions it left
                                    case handler: Handler.LoopHandler[IX, OX, EX, C, Y, S2] @unchecked =>
                                        val outcome0 = handler.running(kyo.input, kyo, stack, idx)
                                        // Load-bearing despite nothing reading it: see `Stack.sink`.
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
                                                stack.pop()
                                                if stack.owesAny then stack.oweBelow(idx, stack.takePopped())
                                                type OutT = Outcome[C < (EX & S2), Y < S2]
                                                val reentry2 = kyo.crossing(entries, contA.chain(contB))
                                                val answered = Handler.attachReentry[IX, OX, EX, C, Y, S2, VX](reentry2)(pending)
                                                Debugger.onRegionExit(handler, answered)
                                                loop[OutT, Y, Any, S2](answered, handler.clauseDispatch, next, ctx2)
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
                                    // the stateful loop clause at the top: as the loop clause, with the state in the region's slot
                                    case handler: Handler.LoopStateHandler[VX, IX, OX, EX, C, Y, S2] @unchecked if atTop =>
                                        val k    = kyo.cont.chain(contA.chain(contB))
                                        val exit = handler.answers(stack.state(idx).asInstanceOf[VX], kyo.input, k, armed, slot, kyo.frame)
                                        Debugger.onResult(exit)
                                        exit match
                                            case e: Loop.Continue2[VX, C < (EX & S2)] @unchecked =>
                                                stack.setState(idx, e._1)
                                                loop(e._2, Arrow.id, Arrow.id, ctx)
                                            case pending: Pending[Outcome2[VX, C < (EX & S2), Y < S2], S2] @unchecked =>
                                                val next = stack.continuation(idx).asInstanceOf[Arrow[Y, Any, S2]]
                                                Debugger.onRegionExit(handler, pending)
                                                stack.pop()
                                                if stack.owesAny then stack.oweBelow(idx, stack.takePopped())
                                                type OutT = Outcome2[VX, C < (EX & S2), Y < S2]
                                                loop[OutT, Y, Any, S2](pending, handler.clauseDispatch, next, ctx)
                                            case done =>
                                                val result =
                                                    Nested.unnest[Y < S2](Loop.unnest(done.asInstanceOf[Outcome2[VX, Any, Y < S2]]))
                                                Debugger.onRegionExit(handler, result)
                                                val next = stack.continuation(idx).asInstanceOf[Arrow[Y, Any, Any]]
                                                if stack.owesAny then drainDiscarded(stack.takeOwed(idx))
                                                stack.truncate(idx)
                                                loop(result, next, Arrow.id, ctx)
                                        end match
                                    // the stateful loop clause with regions above it: as the loop clause, with the state
                                    case handler: Handler.LoopStateHandler[VX, IX, OX, EX, C, Y, S2] @unchecked =>
                                        val outcome0 = handler.running(stack.state(idx).asInstanceOf[VX], kyo.input, kyo, stack, idx)
                                        // Load-bearing despite nothing reading it: see `Stack.sink`.
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
                                                stack.pop()
                                                if stack.owesAny then stack.oweBelow(idx, stack.takePopped())
                                                type OutT = Outcome2[VX, C < (EX & S2), Y < S2]
                                                val reentry2 = kyo.crossing(entries, contA.chain(contB))
                                                val answered =
                                                    Handler.attachReentry2[VX, IX, OX, EX, C, Y, S2, VX](reentry2)(pending)
                                                Debugger.onRegionExit(handler, answered)
                                                loop[OutT, Y, Any, S2](answered, handler.clauseDispatch, next, ctx2)
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

                // entering a region: push it, then run what is inside
                case kyo: Pending.HandleArrow[?, ?, ?, ?, T, S2] @unchecked =>
                    Debugger.onRegionEnter(kyo.handler, kyo.state)
                    stack.push(kyo.handler, kyo.state, kyo.cont.chain(contA.chain(contB)))
                    loop(kyo.value, Arrow.id, Arrow.id, kyo.handler.bound(ctx, kyo.state))

                // entering a binding: derive its value from the one outside, bind it, push it
                case kyo: Pending.HandleContext[VX, CX, T, S2] @unchecked =>
                    val handler    = kyo.handler
                    val newState   = handler.derive(ctx.get(handler.tag))
                    val newContext = handler.bound(ctx, newState)
                    Debugger.onContext(kyo, newContext)
                    Debugger.onRegionEnter(handler, newState)
                    stack.push(handler, newState, contA.chain(contB))
                    loop(kyo.value, Arrow.id, Arrow.id, newContext)

                // a parked slice with nothing to reinstall: take on its debt and continue in place
                case kyo: Pending.Park[?, ?] if kyo.entries.isEmpty =>
                    stack.oweBelow(stack.depth, kyo.owed)
                    loop(kyo.value.asInstanceOf[T < S2], contA, contB, ctx)

                // a parked slice: reinstall the regions it carries, then continue inside them
                case kyo: Pending.Park[?, ?] =>
                    loop(kyo.value, Arrow.id, Arrow.id, installed(kyo, contA.chain(contB).asInstanceOf[Arrow[Any, Any, Any]], ctx))

                case kyo: Pending.Snapshot[T, S2] @unchecked =>
                    loop(kyo.cont(stack, contA.chain(contB)), Arrow.id, Arrow.id, rebuilt())

                // a settled value
                case res =>
                    // nothing composed after it: it is the result of the region on top, or of the whole evaluation
                    if contA.isInstanceOf[Arrow.Id[?]] && contB.isInstanceOf[Arrow.Id[?]] then
                        if stack.isEmpty then
                            res.asInstanceOf[A < S]
                        else
                            val top  = stack.depth - 1
                            val next = stack.continuation(top).asInstanceOf[Arrow[Y, Any, Any]]
                            stack.handler(top) match
                                case hc: Handler.ContextHandler[VX, CX, AX, ?] @unchecked =>
                                    Debugger.onRegionExit(hc, res)
                                    loop(res.asInstanceOf[Y < Any], next, Arrow.id, contextExit(hc, top, ctx))
                                case handler0 =>
                                    val handler = handler0.asInstanceOf[Handler.ArrowHandler[VX, EX, AX, Y, Any]]
                                    val result  = handler.done(stack.state(top).asInstanceOf[VX], Nested.unnest[AX](res))
                                    Debugger.onRegionExit(handler, result)
                                    loop(result, next, Arrow.id, arrowExit(handler, ctx))
                            end match
                    else
                        // something composed after it: apply the head, keep the tail
                        contA match
                            case contA: Arrow.Chain[T, Any, B, S2] @unchecked =>
                                loop(res, contA.a, contA.b.chain(contB), ctx)
                            case _ =>
                                loop(contA(res, contB), Arrow.id, Arrow.id, ctx)
            end match
        end loop

        /** Stops the evaluation and answers what is left as a value that can be resumed anywhere.
          *
          * The node in hand and its two continuations fold back into one computation, and every region still installed comes with it as a
          * snapshot, so resuming reinstalls exactly what was here. An empty stack with no debt is the cheap case: the remainder is the
          * computation itself, with no `Park` node built for it.
          */
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

        // Out of line to keep `loop` small. Dumping the regions between pops the stack down to the masking
        // region, so it is at the top by the time `maskedRead` looks for it.
        def maskedEntries(kyo: Pending.Suspend[?, ?, ?, ?]): Stack.Snapshot =
            val idx = stack.find(kyo.tag)
            if idx == stack.depth - 1 then Stack.Snapshot.empty else dumped(stack, idx, kyo)

        def maskedRead[VX2, CX2 <: ContextEffect[VX2], T2, Y, S3](
            kyo: Pending.SuspendContext[VX2, CX2, T2, CX2 & S3],
            entries: Stack.Snapshot,
            resume: Arrow[T2, Y, S3]
        ): Y < (CX2 & S3) =
            val handler = stack.handler(stack.depth - 1).asInstanceOf[Handler.MaskingHandler[CX2, Y, Any, S3]]
            val continuation =
                if entries.isEmpty then kyo.cont.chain(resume)
                else kyo.crossing(entries, resume)
            val result = handler.answering(kyo.reraise, continuation, kyo, stack)
            Debugger.onResult(result)
            result
        end maskedRead

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
                            case ex if !IsFatal(ex) =>
                                release(kyo, ex)
                                throw ex
                        end try
                    case _ => ()
                end match
                ri += 1
            end while

            // A region that discharges exactly once cannot take its answerability back while the cont can be
            // resumed again, so the debt stays with the handler that dumped it.
            if !defers then stack.settle(entries)
            stack.oweBelow(stack.depth, kyo.owed)

            @tailrec def install(i: Int, c: Context): Context =
                if i == entries.regions then c
                else
                    val stored = entries.continuation(i).asInstanceOf[Arrow[Y, Any, Any]]
                    val cont =
                        if i == 0 then stored.chain(resume)
                        else stored
                    val handler = entries.handler(i).asInstanceOf[Handler[EX, Y, Any]]
                    val st      = entries.state(i)
                    Debugger.onRegionEnter(handler, st)
                    stack.push(handler, st, cont)
                    stack.owe(stack.depth - 1, entries.owed(i))
                    // each region puts back what `rebound` took off on the way out
                    install(i + 1, handler.bound(c, st))
            install(0, ctx)
        end installed

        def contextExit(hc: Handler.ContextHandler[VX, CX, AX, ?], top: Int, ctx: Context): Context =
            hc.done(stack.state(top).asInstanceOf[VX])
            stack.pop()
            if stack.owesAny then
                drainDiscarded(stack.takePopped())
            hc.unbound(ctx)
        end contextExit

        // An escaping region has not discarded what it owes, so the debt moves to the scope below.
        def arrowExit(handler: Handler.ArrowHandler[?, ?, ?, ?, ?], ctx: Context): Context =
            stack.pop()
            if stack.owesAny then
                if handler.escaping then stack.oweBelow(stack.depth, stack.takePopped())
                else drainDiscarded(stack.takePopped())
            handler.unbound(ctx)
        end arrowExit

        def rebuilt(): Context =
            @tailrec def rebuild(i: Int, c: Context): Context =
                if i == stack.depth then c
                else rebuild(i + 1, stack.handler(i).bound(c, stack.state(i)))
            rebuild(0, Context.empty)
        end rebuilt

        /** Unwinds the stack for a throwable, offering it to each region's recover arm from the innermost outward.
          *
          * A region that answers stops the unwind and the evaluation continues from there. One that declines is popped, discharging what it
          * owes and releasing its state, and the throwable carries on outward. Reaching an empty stack re-raises it with the effect trace
          * spliced in.
          *
          * A context region has no recover arm, so it is always popped. A fatal throwable is offered to nothing: every region is unwound and
          * released, and it propagates.
          */
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
                            try if IsFatal(ex) then Absent else handler.recover(state.asInstanceOf[VX], ex)
                            catch
                                case ex2 if !IsFatal(ex2) =>
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
                                stack.continuation(top).asInstanceOf[Arrow[Y, A, S]](r)
                            case Absent =>
                                Debugger.onRegionExit(handler, ex)
                                stack.pop()
                                val owedHere = stack.takePopped()
                                if !owedHere.isEmpty then drainOwed(owedHere, ex)
                                recovered(ex)
                        end match
                end match

        /** Runs the loop and catches what it throws, so a recover arm can resume the evaluation rather than only observe the failure.
          *
          * A region that recovers answers with a computation, which has to be evaluated from a loop that is itself still guarded, hence the
          * re-entry here rather than a return into the loop that just unwound. The context is rebuilt from the stack that survived, since the
          * one the throwing loop carried described regions that are no longer installed.
          */
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
        // The cont can be resumed more than once, so the regions going into it must not discharge themselves
        // when the first resumption ends their extents.
        if stack.handler(idx).repeated then held(entries)
        Debugger.whenEnabled {
            var i = entries.regions - 1
            while i >= 0 do
                Debugger.onRegionExit(entries.handler(i), kyo)
                i -= 1
        }
        entries
    end dumped

    // The regions a held cont carries: the first resumption to end their extents records its outcome rather
    // than discharging them, and whoever owes them discharges when it ends.
    private def held(entries: Stack.Snapshot): Unit =
        var i = 0
        while i < entries.regions do
            entries.handler(i) match
                case hc: Handler.ContextHandler[Any, ?, ?, ?] @unchecked => hc.borrow(entries.state(i))
                case _                                                   => ()
            i += 1
        end while
    end held

    /** Discharges obligations nobody will resume, because whoever held the continuation carrying them is done with it. */
    private def drainDiscarded(owed: Chunk[Stack.Snapshot]): Unit =
        if !owed.isEmpty then
            val signal = new KyoException("remainder discarded")(using Frame.internal)
            // The owner is ending normally, so a held region's recorded outcome is what its release is owed;
            // the signal only stands in for one that never ran to an ending.
            drainOwed(owed, signal, discharging = true)
            if signal.getSuppressed.length != 0 then Report.unhandled(signal)

    private def rebound(entries: Stack.Snapshot, ctx: Context): Context =
        var c = ctx
        var i = 0
        while i < entries.regions do
            c = entries.handler(i).unbound(c)
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
            case t if !IsFatal(t) && (t ne ex) => ex.addSuppressed(t)
            case t if !IsFatal(t)              => ()
        end try
    end released

    /** Releases the regions `v` still holds, running nothing.
      *
      * A deferral is walked rather than evaluated, so what it holds stays unreached: a caller releasing a cont
      * it has just refused would otherwise run the very thing the refusal exists to stop.
      */
    def release[A, S](v: A < S, ex: Throwable): Unit =
        release(v, ex, Absent, _ => (), 0)

    /** Releases the regions `v` still holds, and hands `f` the input of the first operation under them that
      * `effectTag` answers, so a caller that owes something to an operation the computation never reached
      * can settle it without walking the computation a second time. `f` runs before anything is released.
      *
      * An operation under a deferral exists only once the deferral has run, so this evaluates them to reach it,
      * bounded: for a caller abandoning a computation whole, running a little of what was about to run is the
      * price of not stranding what it was about to wait on. A caller releasing a cont it refused wants
      * [[release]] above, which evaluates nothing.
      */
    def release[I[_], O[_], E <: ArrowEffect[I, O], A, S](v: A < S, ex: Throwable, effectTag: Tag[E])(
        f: [C] => I[C] => Unit
    ): Unit =
        // Erasure-forced: the operation's state type is existential here, and `f` takes it back at that type.
        release(v, ex, Present(effectTag.erased), input => f[Any](input.asInstanceOf[I[Any]]), 16)

    private def release[A, S](v: A < S, ex: Throwable, effectTag: Maybe[Tag[Any]], f: Any => Unit, fuel: Int): Unit =
        val collected = ArrayBuffer.empty[AnyRef]

        // An `Arrow.Ensure` waiting on an already-settled value is a release nobody will run, so the cont is
        // carried down and offered the value when one is reached. Only an `Ensure` may run here. A chain's head
        // is its left arrow, itself a chain when one was built onto another, so walking `head` down finds the
        // step that would have received the value.
        @tailrec def leftmost(cont: Arrow[Any, Any, Any]): Arrow[Any, Any, Any] =
            val h = cont.head
            // Erasure-forced: the type joining a chain's links is existential from out here.
            if h eq cont then cont else leftmost(h.asInstanceOf[Arrow[Any, Any, Any]])
        end leftmost

        // Applying the `Ensure` is not always the whole debt. One that registers its release elsewhere, as
        // `Scope.acquireRelease` does, is done once applied; one that installs a region to own it, as
        // `Bracket` does, has only just created what owes it. So the result is walked too, at no budget.
        def ensuring(v: Any, cont: Arrow[Any, Any, Any]): Unit =
            leftmost(cont) match
                case step: Arrow.Ensure[Any, Any, Any] @unchecked => collect(step(v), Arrow.id, 0)
                case _                                            => ()

        @tailrec def collect(v: Any, cont: Arrow[Any, Any, Any], fuel: Int): Unit =
            v match
                case p: Pending[?, ?] =>
                    p match
                        // A deferral whose value is still a computation is walked, which reaches what is under
                        // it for free. One whose value is settled holds its body in the cont, so reaching it
                        // means running a step, and only that spends the budget.
                        case kyo: Pending.Defer[a, b, c, s] @unchecked =>
                            // Erasure-forced: the types joining a chain's links are existential from out here.
                            val after = kyo.contB.chain(cont).asInstanceOf[Arrow[Any, Any, Any]]
                            val below = kyo.contA.chain(after).asInstanceOf[Arrow[Any, Any, Any]]
                            kyo.value match
                                case _: Pending[?, ?] => collect(kyo.value, below, fuel)
                                // `Arrow.id` rather than the node's own cont, so a unit of budget buys one
                                // step: an arrow is free to run as many as it likes once handed one. What
                                // follows that step is carried, since a release waiting on this value sits there.
                                case _ if fuel > 0 =>
                                    ensuring(kyo.value, below)
                                    collect(kyo.contA(kyo.value, Arrow.id), after, fuel - 1)
                                case _ => ensuring(kyo.value, below)
                            end match
                        case kyo: Pending.HandleContext[VX, CX, ?, ?] @unchecked =>
                            val hc = kyo.handler
                            collected += hc
                            collected += hc.derive(Maybe.empty).asInstanceOf[AnyRef]
                            collect(kyo.value, cont, fuel)
                        case kyo: Pending.Handle[?, ?, ?, ?] =>
                            collect(kyo.value, cont, fuel)
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
                            collect(kyo.value, cont, fuel)
                        case kyo: Pending.SuspendArrow[?, ?, ?, ?, ?, ?] @unchecked =>
                            effectTag.foreach(t => if t <:< kyo.tag.erased then f(kyo.input))
                        case _: Pending.Suspend[?, ?, ?, ?] => ()
                        case _: Pending.Snapshot[?, ?]      => ()
                case settled => ensuring(settled, cont)
        // A deferral declines to run while the Safepoint is stopped, and it always is here: this walks a
        // computation whose fiber has just been interrupted. The walk gets its own state so stepping
        // reaches what a deferral holds, and the caller's is put back.
        if fuel > 0 then
            val slot  = Safepoint.get()
            val saved = Safepoint.save(slot)
            try collect(v, Arrow.id, fuel)
            finally Safepoint.restore(slot, saved)
        else collect(v, Arrow.id, fuel)
        end if
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

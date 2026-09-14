package kyo.kernel.internal

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
  * [[Stack]] lives beside it, holding the regions installed around the node in hand. There is no separate context: a context read resolves
  * from the stack the way an operation does, and a region's release lives in its own stack entry. The stack is borrowed for one evaluation.
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

        @tailrec def loop[T, B, C, S2](v: T < S2, contA: Arrow[T, B, S2], contB: Arrow[B, C, S2]): A < S =
            Debugger.onLoop(v, contA, contB)
            v match
                // a deferral: unfold it, its two continuations going in front of ours
                case kyo: Pending.Defer[?, ?, T, S2] @unchecked =>
                    if armed && Safepoint.stopped(slot) then
                        park(v, contA, contB)
                    else
                        loop(kyo.value, kyo.contA, kyo.contB.chain(contA.chain(contB)))

                case kyo: Pending.Suspend[?, ?, T, S2] @unchecked =>
                    kyo match
                        // a context read: resolve the binding from the stack the way an operation resolves its handler
                        case kyo: Pending.SuspendContext[VX, CX, T, CX & S2] @unchecked =>
                            val idx = stack.find(kyo.tag)
                            if idx < 0 then
                                // no binding: the defaulted form answers itself, the required form is unreachable
                                val state = kyo.default.getOrElse(unhandled(kyo, stack))
                                loop(kyo.cont(state, contA.chain(contB)), Arrow.id, Arrow.id)
                            else
                                stack.handler(idx) match
                                    case _: Handler.ContextHandler[VX, CX, ?, ?] @unchecked =>
                                        val state = stack.state(idx).asInstanceOf[VX]
                                        Debugger.onContext(kyo, state)
                                        loop(kyo.cont(state, contA.chain(contB)), Arrow.id, Arrow.id)
                                    case _ =>
                                        // a masking region shadows the binding, so the read dispatches to it the
                                        // route an arrow operation takes, re-raised rather than answered from a value
                                        val entries = if idx == stack.depth - 1 then Stack.Snapshot.empty else dumped(stack, idx, kyo)
                                        val result  = maskedRead(kyo, entries, contA.chain(contB))
                                        if armed && Safepoint.stopped(slot) then park(result, Arrow.id, Arrow.id)
                                        else loop(result, Arrow.id, Arrow.id)
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
                                        val continuation =
                                            if atTop then kyo.cont.chain(contA.chain(contB))
                                            else kyo.crossing(entries, contA.chain(contB))
                                        val result = handler.answering(kyo.input, continuation, kyo, stack)
                                        Debugger.onResult(result)
                                        // The stop is honored on the clause's answer: one that re-raises the
                                        // operation would otherwise dispatch straight back here with no deferral to park at.
                                        if armed && Safepoint.stopped(slot) then park(result, Arrow.id, Arrow.id)
                                        else loop(result, Arrow.id, Arrow.id)
                                    // a masking clause: the same, handed the operation re-raised instead of its input
                                    case handler: Handler.MaskingHandler[EX, C, Y, S2] @unchecked =>
                                        val entries = if atTop then Stack.Snapshot.empty else dumped(stack, idx, kyo)
                                        val continuation =
                                            if atTop then kyo.cont.chain(contA.chain(contB))
                                            else kyo.crossing(entries, contA.chain(contB))
                                        val result = handler.answering(kyo.reraise, continuation, kyo, stack)
                                        Debugger.onResult(result)
                                        if armed && Safepoint.stopped(slot) then park(result, Arrow.id, Arrow.id)
                                        else loop(result, Arrow.id, Arrow.id)
                                    // a first clause: answers the operation and carries its continuation out as the
                                    // region's result, so the region exits with the peeled value; it is escaping,
                                    // so what it holds moves to the scope below
                                    case handler: Handler.FirstHandler[IX, OX, EX, C, Y, S2] @unchecked =>
                                        val entries = if atTop then Stack.Snapshot.empty else dumped(stack, idx, kyo)
                                        val continuation =
                                            if atTop then kyo.cont.chain(contA.chain(contB))
                                            else kyo.crossing(entries, contA.chain(contB))
                                        val result = handler.answering(kyo.input, continuation, kyo, stack)
                                        Debugger.onRegionExit(handler, result)
                                        val next = stack.continuation(idx).asInstanceOf[Arrow[Y, Any, Any]]
                                        stack.pop()
                                        stack.oweBelow(stack.depth, stack.takePopped())
                                        if armed && Safepoint.stopped(slot) then park(result, next, Arrow.id)
                                        else loop(result, next, Arrow.id)
                                    // a loop clause at the top: answered in place, the region staying installed
                                    case handler: Handler.LoopHandler[IX, OX, EX, C, Y, S2] @unchecked if atTop =>
                                        val k    = kyo.cont.chain(contA.chain(contB))
                                        val exit = handler.answers(kyo.input, k, armed, slot, kyo.frame)
                                        Debugger.onResult(exit)
                                        exit match
                                            // continued: the answer carries on inside the region
                                            case e: Loop.Continue[C < (EX & S2)] @unchecked =>
                                                loop(e._1, Arrow.id, Arrow.id)
                                            // suspended: the region ends, its outcome dispatched as the region's own continuation
                                            case pending: Pending[Outcome[C < (EX & S2), Y < S2], S2] @unchecked =>
                                                val next = stack.continuation(idx).asInstanceOf[Arrow[Y, Any, S2]]
                                                Debugger.onRegionExit(handler, pending)
                                                stack.pop()
                                                if stack.owesAny then stack.oweBelow(idx, stack.takePopped())
                                                type OutT = Outcome[C < (EX & S2), Y < S2]
                                                loop[OutT, Y, Any, S2](pending, handler.clauseDispatch, next)
                                            // done: the region ends with its value
                                            case done =>
                                                val result =
                                                    Nested.unnest[Y < S2](Loop.unnest(done.asInstanceOf[Outcome[Any, Y < S2]]))
                                                Debugger.onRegionExit(handler, result)
                                                val next = stack.continuation(idx).asInstanceOf[Arrow[Y, Any, Any]]
                                                if stack.owesAny then drainClean(stack.takeReleases(idx))
                                                stack.truncate(idx)
                                                loop(result, next, Arrow.id)
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
                                                    loop(ans, kyo.cont, contA.chain(contB))
                                                else
                                                    val entries = dumped(stack, idx, kyo)
                                                    loop(ans, kyo.crossing(entries, contA.chain(contB)), Arrow.id)
                                                end if
                                            case pending: Pending[Outcome[OX[VX] < (EX & S2), Y < S2], S2] @unchecked =>
                                                val entries = dumped(stack, idx, kyo)
                                                val next    = stack.continuation(idx).asInstanceOf[Arrow[Y, Any, S2]]
                                                stack.pop()
                                                if stack.owesAny then stack.oweBelow(idx, stack.takePopped())
                                                type OutT = Outcome[C < (EX & S2), Y < S2]
                                                val reentry2 = kyo.crossing(entries, contA.chain(contB))
                                                val answered = Handler.attachReentry[IX, OX, EX, C, Y, S2, VX](reentry2)(pending)
                                                Debugger.onRegionExit(handler, answered)
                                                loop[OutT, Y, Any, S2](answered, handler.clauseDispatch, next)
                                            case outcome =>
                                                val entries = dumped(stack, idx, kyo)
                                                val result =
                                                    Nested.unnest[Y < S2](Loop.unnest(outcome.asInstanceOf[Outcome[
                                                        OX[VX] < (EX & S2),
                                                        Y < S2
                                                    ]]))
                                                Debugger.onRegionExit(handler, result)
                                                val next = stack.continuation(idx).asInstanceOf[Arrow[Y, Any, Any]]
                                                if stack.owesAny then drainClean(stack.takeReleases(idx))
                                                stack.truncate(idx)
                                                loop(result, next, Arrow.id)
                                        end match
                                    // the stateful loop clause at the top: as the loop clause, with the state in the region's slot
                                    case handler: Handler.LoopStateHandler[VX, IX, OX, EX, C, Y, S2] @unchecked if atTop =>
                                        val k    = kyo.cont.chain(contA.chain(contB))
                                        val exit = handler.answers(stack.state(idx).asInstanceOf[VX], kyo.input, k, armed, slot, kyo.frame)
                                        Debugger.onResult(exit)
                                        exit match
                                            case e: Loop.Continue2[VX, C < (EX & S2)] @unchecked =>
                                                stack.setState(idx, e._1)
                                                loop(e._2, Arrow.id, Arrow.id)
                                            case pending: Pending[Outcome2[VX, C < (EX & S2), Y < S2], S2] @unchecked =>
                                                val next = stack.continuation(idx).asInstanceOf[Arrow[Y, Any, S2]]
                                                Debugger.onRegionExit(handler, pending)
                                                stack.pop()
                                                if stack.owesAny then stack.oweBelow(idx, stack.takePopped())
                                                type OutT = Outcome2[VX, C < (EX & S2), Y < S2]
                                                loop[OutT, Y, Any, S2](pending, handler.clauseDispatch, next)
                                            case done =>
                                                val result =
                                                    Nested.unnest[Y < S2](Loop.unnest(done.asInstanceOf[Outcome2[VX, Any, Y < S2]]))
                                                Debugger.onRegionExit(handler, result)
                                                val next = stack.continuation(idx).asInstanceOf[Arrow[Y, Any, Any]]
                                                if stack.owesAny then drainClean(stack.takeReleases(idx))
                                                stack.truncate(idx)
                                                loop(result, next, Arrow.id)
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
                                                    loop(ans, kyo.cont, contA.chain(contB))
                                                else
                                                    val entries = dumped(stack, idx, kyo)
                                                    loop(ans, kyo.crossing(entries, contA.chain(contB)), Arrow.id)
                                                end if
                                            case pending: Pending[Outcome2[VX, OX[VX] < (EX & S2), Y < S2], S2] @unchecked =>
                                                val entries = dumped(stack, idx, kyo)
                                                val next    = stack.continuation(idx).asInstanceOf[Arrow[Y, Any, S2]]
                                                stack.pop()
                                                if stack.owesAny then stack.oweBelow(idx, stack.takePopped())
                                                type OutT = Outcome2[VX, C < (EX & S2), Y < S2]
                                                val reentry2 = kyo.crossing(entries, contA.chain(contB))
                                                val answered =
                                                    Handler.attachReentry2[VX, IX, OX, EX, C, Y, S2, VX](reentry2)(pending)
                                                Debugger.onRegionExit(handler, answered)
                                                loop[OutT, Y, Any, S2](answered, handler.clauseDispatch, next)
                                            case outcome =>
                                                val entries = dumped(stack, idx, kyo)
                                                val result =
                                                    Nested.unnest[Y < S2](Loop.unnest(outcome.asInstanceOf[Outcome2[
                                                        VX,
                                                        OX[VX] < (EX & S2),
                                                        Y < S2
                                                    ]]))
                                                Debugger.onRegionExit(handler, result)
                                                val next = stack.continuation(idx).asInstanceOf[Arrow[Y, Any, Any]]
                                                if stack.owesAny then drainClean(stack.takeReleases(idx))
                                                stack.truncate(idx)
                                                loop(result, next, Arrow.id)
                                        end match
                                    case handler =>
                                        unanswerable(handler)
                                end match
                            end if

                // entering a region: push it, then run what is inside
                case kyo: Pending.HandleArrow[?, ?, ?, ?, T, S2] @unchecked =>
                    Debugger.onRegionEnter(kyo.handler, kyo.state)
                    stack.push(kyo.handler, kyo.state, kyo.cont.chain(contA.chain(contB)))
                    loop(kyo.value, Arrow.id, Arrow.id)

                // entering a binding: derive its value from the one bound outside, push it, and install its release
                case kyo: Pending.HandleContext[VX, CX, T, S2] @unchecked =>
                    val handler = kyo.handler
                    // the value an outer region of the same tag bound, for the derive, resolved from the stack the
                    // way a read resolves it: Absent when nothing binds it or a mask shadows it
                    val outerIdx = stack.find(handler.tag)
                    val outer =
                        if outerIdx < 0 then Absent
                        else
                            stack.handler(outerIdx) match
                                case _: Handler.ContextHandler[VX, CX, ?, ?] @unchecked => Maybe(stack.state(outerIdx).asInstanceOf[VX])
                                case _                                                  => Absent
                    val newState = handler.derive(outer)
                    Debugger.onContext(kyo, newState)
                    Debugger.onRegionEnter(handler, newState)
                    stack.push(handler, newState, contA.chain(contB))
                    stack.oweRelease(stack.depth - 1, (failure: Maybe[Throwable]) => handler.release(newState, failure))
                    loop(kyo.value, Arrow.id, Arrow.id)

                // a parked slice with nothing to reinstall: take on its releases and continue in place
                case kyo: Pending.Park[?, ?] if kyo.entries.isEmpty =>
                    stack.oweBelow(stack.depth, kyo.releases)
                    loop(kyo.value.asInstanceOf[T < S2], contA, contB)

                // a parked slice: reinstall the regions it carries, then continue inside them
                case kyo: Pending.Park[?, ?] =>
                    installed(kyo, contA.chain(contB).asInstanceOf[Arrow[Any, Any, Any]])
                    loop(kyo.value, Arrow.id, Arrow.id)

                case kyo: Pending.Snapshot[T, S2] @unchecked =>
                    loop(kyo.cont(stack, contA.chain(contB)), Arrow.id, Arrow.id)

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
                                    // the region's own release, in its entry, runs its clean end; anything held for
                                    // it runs here too, as a discarded remainder the holder never resumed
                                    stack.pop()
                                    if stack.owesAny then drainClean(stack.takePopped())
                                    loop(res.asInstanceOf[Y < Any], next, Arrow.id)
                                case handler0 =>
                                    val handler = handler0.asInstanceOf[Handler.ArrowHandler[VX, EX, AX, Y, Any]]
                                    val result  = handler.done(stack.state(top).asInstanceOf[VX], Nested.unnest[AX](res))
                                    Debugger.onRegionExit(handler, result)
                                    arrowExit(handler, top)
                                    loop(result, next, Arrow.id)
                            end match
                    else
                        // something composed after it: apply the head, keep the tail
                        contA match
                            case contA: Arrow.Chain[T, Any, B, S2] @unchecked =>
                                loop(res, contA.a, contA.b.chain(contB))
                            case _ =>
                                loop(contA(res, contB), Arrow.id, Arrow.id)
            end match
        end loop

        /** Stops the evaluation and answers what is left as a value that can be resumed anywhere.
          *
          * The node in hand and its two continuations fold back into one computation, and every region still installed comes with it as a
          * snapshot, so resuming reinstalls exactly what was here. An empty stack with no releases is the cheap case: the remainder is the
          * computation itself, with no `Park` node built for it.
          */
        def park[T, B, C, S2](v: T < S2, contA: Arrow[T, B, S2], contB: Arrow[B, C, S2]): A < S =
            val parked: Any < Any =
                if contA.isInstanceOf[Arrow.Id[?]] && contB.isInstanceOf[Arrow.Id[?]] then v.asInstanceOf[Any < Any]
                else Effect.defer(v, contA, contB).asInstanceOf[Any < Any]
            val evalRs = stack.takeEvalReleases()
            if stack.isEmpty then
                if evalRs.isEmpty then parked.asInstanceOf[A < S]
                else Pending.Park[A, S](parked, Stack.Snapshot.empty, evalRs)
            else
                Debugger.whenEnabled {
                    var j = stack.depth - 1
                    while j >= 0 do
                        Debugger.onRegionExit(stack.handler(j), parked)
                        j -= 1
                }
                Pending.Park[A, S](parked, stack.takeAll(), evalRs)
            end if
        end park

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

        /** Reinstalls the regions a parked slice carries, restoring the releases each owed.
          *
          * A dump snapshot carries no releases, because a dumping handler moved them to its own entry, so a reinstalled region resumes with
          * none and the holder runs them once at its end. A park snapshot carries them, because a parked computation is itself resuming and
          * runs its regions' extents to an end where it resumes.
          */
        def installed(kyo: Pending.Park[?, ?], resume: Arrow[Any, Any, Any]): Unit =
            val entries = kyo.entries
            stack.oweBelow(stack.depth, kyo.releases)
            @tailrec def install(i: Int): Unit =
                if i < entries.regions then
                    val stored = entries.continuation(i).asInstanceOf[Arrow[Any, Any, Any]]
                    val cont =
                        if i == 0 then stored.chain(resume)
                        else stored
                    val handler = entries.handler(i).asInstanceOf[Handler[Effect, Any, Any]]
                    val st      = entries.state(i)
                    Debugger.onRegionEnter(handler, st)
                    stack.push(handler, st, cont)
                    stack.owe(stack.depth - 1, entries.releases(i))
                    install(i + 1)
            install(0)
        end installed

        // The clean end of a region runs what it still owed, told the extent ended without a failure. What it owed
        // is a mix of its own release and remainders held for it that nobody resumed; a throw from one goes to the
        // report, since nothing is unwinding to carry it.
        def drainClean(releases: Stack.Releases): Unit =
            releases.run(Absent)(t => Report.unhandled(t))

        // An escaping region moves what it owes to the scope below rather than running it, because it handed its
        // continuation out; every other region runs it, as a discarded remainder.
        def arrowExit(handler: Handler.ArrowHandler[?, ?, ?, ?, ?], top: Int): Unit =
            stack.pop()
            if stack.owesAny then
                val held = stack.takePopped()
                if handler.isInstanceOf[Handler.FirstHandler[?, ?, ?, ?, ?, ?]] then stack.oweBelow(stack.depth, held)
                else drainClean(held)
            end if
        end arrowExit

        /** Unwinds the stack for a throwable, offering it to each region's recover arm from the innermost outward.
          *
          * A region that answers stops the unwind and the evaluation continues from there. One that declines is popped, running what it owed
          * with the failure. Reaching an empty stack re-raises it with the effect trace spliced in.
          *
          * A context region has no recover arm, so it is always popped, and its own release, in its entry, runs with the failure. A fatal
          * throwable is offered to nothing: every region is unwound and released, and it propagates.
          */
        @tailrec def recovered(ex: Throwable): A < S =
            if stack.isEmpty then
                drainFailed(stack.takeEvalReleases(), ex)
                EffectTrace.splice(ex)
                throw ex
            else
                val top = stack.depth - 1
                stack.handler(top) match
                    case hc: Handler.ContextHandler[VX, CX, ?, ?] @unchecked =>
                        Debugger.onRegionExit(hc, ex)
                        stack.pop()
                        drainFailed(stack.takePopped(), ex)
                        recovered(ex)
                    case handler0 =>
                        val handler = handler0.asInstanceOf[Handler.ArrowHandler[VX, EX, AX, Y, Any]]
                        val state   = stack.state(top)
                        val outcome =
                            try if IsFatal(ex) then Absent else handler.recover(state.asInstanceOf[VX], ex)
                            catch
                                case ex2 if !IsFatal(ex2) =>
                                    Debugger.onRegionExit(handler, ex2)
                                    stack.pop()
                                    drainFailed(stack.takePopped(), ex2)
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
                                drainFailed(stack.takePopped(), ex)
                                recovered(ex)
                        end match
                end match

        /** Runs the loop and catches what it throws, so a recover arm can resume the evaluation rather than only observe the failure.
          *
          * A region that recovers answers with a computation, which has to be evaluated from a loop that is itself still guarded, hence the
          * re-entry here rather than a return into the loop that just unwound.
          */
        @tailrec def guarded(curr: A < S): A < S =
            val res =
                try loop(curr, Arrow.id, Arrow.id)
                catch
                    case failure =>
                        Safepoint.reset(slot)
                        EffectTrace.attach(failure, stack)
                        EffectTrace.splice(failure)
                        val resumed = recovered(failure)
                        stack.pop()
                        drainFailed(stack.takePopped(), failure)
                        return guarded(resumed)
            res match
                case susp: Pending.Suspend[?, ?, ?, ?] =>
                    bug(s"unhandled suspension: $susp")
                case res => res
            end match
        end guarded

        try
            val out = guarded(v)
            drainClean(stack.takeEvalReleases())
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

    // The unwind of a failed region runs what it owed with the failure, attaching a throw from a release as
    // suppressed rather than letting it replace the failure being carried.
    private def drainFailed(releases: Stack.Releases, ex: Throwable): Unit =
        releases.run(Maybe(ex))(t => if t ne ex then ex.addSuppressed(t))

    /** Releases the regions `v` still holds, running nothing else.
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
        var collected: Stack.Releases = Stack.Releases.empty

        def collectContext(hc: Handler.ContextHandler[?, ?, ?, ?], state: Any): Unit =
            val h = hc.asInstanceOf[Handler.ContextHandler[Any, ContextEffect[Any], Any, Any]]
            collected = collected.add(failure => h.release(state, failure))

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
                            collectContext(hc, hc.derive(Maybe.empty))
                            collect(kyo.value, cont, fuel)
                        case kyo: Pending.Handle[?, ?, ?, ?] =>
                            collect(kyo.value, cont, fuel)
                        case kyo: Pending.Park[?, ?] =>
                            collected = collected.concat(kyo.releases)
                            val entries = kyo.entries
                            var i       = 0
                            while i < entries.regions do
                                collected = collected.concat(entries.releases(i))
                                i += 1
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
        collected.run(Maybe(ex))(t => if t ne ex then ex.addSuppressed(t))
    end release

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

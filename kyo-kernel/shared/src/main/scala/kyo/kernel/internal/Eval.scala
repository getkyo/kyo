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

/** The evaluator: unfolds a computation's nodes until it produces a value, or until there is nothing further it can do without an answer.
  *
  * A computation is a value built by composition; this runs such values, it does not define what they mean. Every branch below is the
  * operational reading of an equation expressible in the public combinators, so a gap here is a missing value, not a missing instruction.
  *
  * `loop` is the whole machine: it carries the node in hand and two continuations, and each arm reduces the node and loops, pushes a region,
  * or hands an answer to a handler. Being tail-recursive rather than a recursive walk is where stack safety comes from: depth costs heap, not
  * call frames.
  *
  * [[Stack]] lives beside it, holding the regions installed around the node. There is no separate context: a context read resolves from the
  * stack the way an operation does, and a region's release lives in its own stack entry. The stack is borrowed for one evaluation.
  *
  * The loop is far too large to inline and every effect passes through it, so its dispatch is megamorphic. That is why the combinators fuse
  * at their own call sites and reach the loop only when they must, and why cold work here is kept out of line rather than in the arms.
  */
@publicInBinary private[kyo] object Eval:

    /** Evaluates until a value is produced, with no preemption. */
    def apply[A, S](v: A < S): A < S =
        apply(v, armed = false)

    /** Evaluates until a value is produced or the safepoint says to stop, answering what is left as a computation to resume later.
      *
      * What a scheduler runs a fiber with: the stop flag turns a run into a slice, and the remainder is a complete value, valid anywhere, so
      * another thread may pick it up.
      */
    def partial[A](v: A < Any): A < Any =
        val slot = Safepoint.get()
        if Safepoint.consumeStopped(slot) then v
        else
            try apply(v, armed = true)
            finally discard(Safepoint.consumeStopped(slot))
        end if
    end partial

    // `armed` is a constant parameter, not a per-loop test, so the stop check folds away for a run that cannot be preempted.
    private def apply[A, S](v: A < S, armed: Boolean): A < S =
        // one stack and one safepoint slot per evaluation
        val stack = Stack.borrow()
        val slot  = Safepoint.get()
        val saved = Safepoint.save(slot)
        if armed then Safepoint.arm(slot)

        // A stop is honored only on a still-pending answer, never on a settled value: the value reaches its
        // continuation and stays paired with the `Ensure` that owes its release, which a park in front of it would
        // strand. The settled arm parks the refusal-deferral holding it instead.
        def shouldPark[A, S](v: A < S): Boolean = armed && Safepoint.stopped(slot) && v.isInstanceOf[Pending[?, ?]]

        @tailrec def loop[T, B, C, S2](v: T < S2, contA: Arrow[T, B, S2], contB: Arrow[B, C, S2]): A < S =
            Debugger.onLoop(v, contA, contB)
            v match
                // a deferral: its continuations go in front of ours.
                case kyo: Pending.Defer[?, ?, T, S2] @unchecked =>
                    if shouldPark(kyo.value) then
                        park(v, contA, contB)
                    else
                        loop(kyo.value, kyo.contA, kyo.contB.chain(contA.chain(contB)))

                case kyo: Pending.Suspend[?, ?, T, S2] @unchecked =>
                    kyo match
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
                                        // a masking region shadows the binding: the read dispatches to it the way an
                                        // arrow operation does, re-raised rather than answered from a value
                                        val entries = if idx == stack.depth - 1 then Stack.Snapshot.empty else dumped(stack, idx, kyo)
                                        val result  = maskedRead(kyo, entries, contA.chain(contB))
                                        if shouldPark(result) then park(result, Arrow.id, Arrow.id)
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
                                        val entries      = if atTop then Stack.Snapshot.empty else dumped(stack, idx, kyo)
                                        val continuation =
                                            if atTop then kyo.cont.chain(contA.chain(contB))
                                            else kyo.crossing(entries, contA.chain(contB))
                                        val result = handler.answering(kyo.input, continuation, kyo, stack)
                                        Debugger.onResult(result)
                                        // Stop honored on the clause's answer: one that re-raises the operation would
                                        // otherwise dispatch straight back here with no deferral to park at.
                                        if shouldPark(result) then park(result, Arrow.id, Arrow.id)
                                        else loop(result, Arrow.id, Arrow.id)
                                    // a masking clause: the same, handed the operation re-raised instead of its input
                                    case handler: Handler.MaskingHandler[EX, C, Y, S2] @unchecked =>
                                        val entries      = if atTop then Stack.Snapshot.empty else dumped(stack, idx, kyo)
                                        val continuation =
                                            if atTop then kyo.cont.chain(contA.chain(contB))
                                            else kyo.crossing(entries, contA.chain(contB))
                                        val result = handler.answering(kyo.reraise, continuation, kyo, stack)
                                        Debugger.onResult(result)
                                        if shouldPark(result) then park(result, Arrow.id, Arrow.id)
                                        else loop(result, Arrow.id, Arrow.id)
                                    // a first clause: answers the operation and carries its continuation out as the region's result, so the
                                    // region exits with the peeled value. Single-shot (the default): each dumped region closes at its own end
                                    // (settle pulls it from the owed lane, else it is drained). Repeated: the dumped regions are held and
                                    // released once after every resumption, so a resource shared across a streamed choice's branches stays live.
                                    case handler: Handler.FirstHandler[IX, OX, EX, C, Y, S2] @unchecked =>
                                        val repeated = handler.repeated
                                        val entries  = if atTop then Stack.Snapshot.empty else dumped(stack, idx, kyo, escaping = !repeated)
                                        val continuation =
                                            if atTop then kyo.cont.chain(contA.chain(contB))
                                            else kyo.crossing(entries, contA.chain(contB))
                                        val result = handler.answering(kyo.input, continuation, kyo, stack)
                                        Debugger.onRegionExit(handler, result)
                                        val next = stack.continuation(idx).asInstanceOf[Arrow[Y, Any, Any]]
                                        // remainders owed to this peel (from inner peels it dumped over) pass down with it,
                                        // read before the pop clears the entry
                                        val owedThrough = if stack.owesAny then stack.takeOwedRemainders(idx) else Chunk.empty
                                        stack.pop()
                                        stack.oweBelow(stack.depth, stack.takePopped())
                                        stack.oweRemaindersBelow(stack.depth, owedThrough)
                                        if !repeated && !entries.isEmpty then stack.oweRemainderBelow(stack.depth, entries)
                                        if shouldPark(result) then park(result, next, Arrow.id)
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
                                                val owed = if stack.owesAny then stack.takeOwedRemainders(idx) else Chunk.empty
                                                stack.pop()
                                                if stack.owesAny then stack.oweBelow(idx, stack.takePopped())
                                                stack.oweRemaindersBelow(idx, owed)
                                                type OutT = Outcome[C < (EX & S2), Y < S2]
                                                loop[OutT, Y, Any, S2](pending, handler.clauseDispatch, next)
                                            // done: the region ends with its value
                                            case done =>
                                                val result =
                                                    Nested.unnest[Y < S2](Loop.unnest(done.asInstanceOf[Outcome[Any, Y < S2]]))
                                                Debugger.onRegionExit(handler, result)
                                                val next = stack.continuation(idx).asInstanceOf[Arrow[Y, Any, Any]]
                                                if stack.owesAny then
                                                    drainClean(stack.takeReleases(idx))
                                                    drainRemainders(stack.takeOwedRemainders(idx), Absent)
                                                stack.truncate(idx)
                                                loop(result, next, Arrow.id)
                                        end match
                                    // a loop clause with regions above it: answered outside them, same three outcomes as above,
                                    // the answer crossing back into the regions it left
                                    case handler: Handler.LoopHandler[IX, OX, EX, C, Y, S2] @unchecked =>
                                        val outcome0 = handler.running(kyo.input, kyo, stack, idx)
                                        // Load-bearing despite nothing reading it: see `Stack.sink`.
                                        stack.sink = outcome0
                                        Debugger.onResult(outcome0)
                                        outcome0 match
                                            case outcome: Loop.Continue[OX[VX] < (EX & S2)] @unchecked =>
                                                val ans = outcome._1
                                                ans match
                                                    case _ if !ans.isInstanceOf[Pending[?, ?]] =>
                                                        loop(ans, kyo.cont, contA.chain(contB))
                                                    // the operation raised again, bare: this region answers it in place, regions live. A stop
                                                    // re-raise (clause cannot answer yet) parks at the operation with every region carried by
                                                    // takeAll, so a bracket around it keeps its release and closes at its own end on resume.
                                                    case again: Pending.SuspendArrow[IX, OX, EX, VX, ?, ?] @unchecked
                                                        if again.tag.erased =:= kyo.tag.erased && again.cont.isInstanceOf[Arrow.Id[?]] =>
                                                        if armed && Safepoint.stopped(slot) then park(again, kyo.cont, contA.chain(contB))
                                                        else loop(again, kyo.cont, contA.chain(contB))
                                                    case _ =>
                                                        val entries = dumped(stack, idx, kyo)
                                                        loop(ans, kyo.crossing(entries, contA.chain(contB)), Arrow.id)
                                                end match
                                            case pending: Pending[Outcome[OX[VX] < (EX & S2), Y < S2], S2] @unchecked =>
                                                val entries = dumped(stack, idx, kyo)
                                                val next    = stack.continuation(idx).asInstanceOf[Arrow[Y, Any, S2]]
                                                val owed    = if stack.owesAny then stack.takeOwedRemainders(idx) else Chunk.empty
                                                stack.pop()
                                                if stack.owesAny then stack.oweBelow(idx, stack.takePopped())
                                                stack.oweRemaindersBelow(idx, owed)
                                                type OutT = Outcome[C < (EX & S2), Y < S2]
                                                val reentry2 = kyo.crossing(entries, contA.chain(contB))
                                                val answered = Handler.attachReentry[IX, OX, EX, C, Y, S2, VX](reentry2)(pending)
                                                Debugger.onRegionExit(handler, answered)
                                                loop[OutT, Y, Any, S2](answered, handler.clauseDispatch, next)
                                            case outcome =>
                                                val entries = dumped(stack, idx, kyo)
                                                val result  =
                                                    Nested.unnest[Y < S2](Loop.unnest(outcome.asInstanceOf[Outcome[
                                                        OX[VX] < (EX & S2),
                                                        Y < S2
                                                    ]]))
                                                Debugger.onRegionExit(handler, result)
                                                val next = stack.continuation(idx).asInstanceOf[Arrow[Y, Any, Any]]
                                                if stack.owesAny then
                                                    drainClean(stack.takeReleases(idx))
                                                    drainRemainders(stack.takeOwedRemainders(idx), Absent)
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
                                                val owed = if stack.owesAny then stack.takeOwedRemainders(idx) else Chunk.empty
                                                stack.pop()
                                                if stack.owesAny then stack.oweBelow(idx, stack.takePopped())
                                                stack.oweRemaindersBelow(idx, owed)
                                                type OutT = Outcome2[VX, C < (EX & S2), Y < S2]
                                                loop[OutT, Y, Any, S2](pending, handler.clauseDispatch, next)
                                            case done =>
                                                val result =
                                                    Nested.unnest[Y < S2](Loop.unnest(done.asInstanceOf[Outcome2[VX, Any, Y < S2]]))
                                                Debugger.onRegionExit(handler, result)
                                                val next = stack.continuation(idx).asInstanceOf[Arrow[Y, Any, Any]]
                                                if stack.owesAny then
                                                    drainClean(stack.takeReleases(idx))
                                                    drainRemainders(stack.takeOwedRemainders(idx), Absent)
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
                                                ans match
                                                    case _ if !ans.isInstanceOf[Pending[?, ?]] =>
                                                        loop(ans, kyo.cont, contA.chain(contB))
                                                    // the operation raised again, bare, dispatches in place; a stop the clause
                                                    // requested parks it here with every region carried (see the stateless arm).
                                                    case again: Pending.SuspendArrow[IX, OX, EX, VX, ?, ?] @unchecked
                                                        if again.tag.erased =:= kyo.tag.erased && again.cont.isInstanceOf[Arrow.Id[?]] =>
                                                        if armed && Safepoint.stopped(slot) then park(again, kyo.cont, contA.chain(contB))
                                                        else loop(again, kyo.cont, contA.chain(contB))
                                                    case _ =>
                                                        val entries = dumped(stack, idx, kyo)
                                                        loop(ans, kyo.crossing(entries, contA.chain(contB)), Arrow.id)
                                                end match
                                            case pending: Pending[Outcome2[VX, OX[VX] < (EX & S2), Y < S2], S2] @unchecked =>
                                                val entries = dumped(stack, idx, kyo)
                                                val next    = stack.continuation(idx).asInstanceOf[Arrow[Y, Any, S2]]
                                                val owed    = if stack.owesAny then stack.takeOwedRemainders(idx) else Chunk.empty
                                                stack.pop()
                                                if stack.owesAny then stack.oweBelow(idx, stack.takePopped())
                                                stack.oweRemaindersBelow(idx, owed)
                                                type OutT = Outcome2[VX, C < (EX & S2), Y < S2]
                                                val reentry2 = kyo.crossing(entries, contA.chain(contB))
                                                val answered =
                                                    Handler.attachReentry2[VX, IX, OX, EX, C, Y, S2, VX](reentry2)(pending)
                                                Debugger.onRegionExit(handler, answered)
                                                loop[OutT, Y, Any, S2](answered, handler.clauseDispatch, next)
                                            case outcome =>
                                                val entries = dumped(stack, idx, kyo)
                                                val result  =
                                                    Nested.unnest[Y < S2](Loop.unnest(outcome.asInstanceOf[Outcome2[
                                                        VX,
                                                        OX[VX] < (EX & S2),
                                                        Y < S2
                                                    ]]))
                                                Debugger.onRegionExit(handler, result)
                                                val next = stack.continuation(idx).asInstanceOf[Arrow[Y, Any, Any]]
                                                if stack.owesAny then
                                                    drainClean(stack.takeReleases(idx))
                                                    drainRemainders(stack.takeOwedRemainders(idx), Absent)
                                                stack.truncate(idx)
                                                loop(result, next, Arrow.id)
                                        end match
                                    case handler =>
                                        unanswerable(handler)
                                end match
                            end if

                case kyo: Pending.HandleArrow[?, ?, ?, ?, T, S2] @unchecked =>
                    Debugger.onRegionEnter(kyo.handler, kyo.state)
                    stack.push(kyo.handler, kyo.state, kyo.cont.chain(contA.chain(contB)))
                    loop(kyo.value, Arrow.id, Arrow.id)

                // entering a binding: derive its value from the one bound outside, push it, and install its release
                case kyo: Pending.HandleContext[VX, CX, T, S2] @unchecked =>
                    val handler = kyo.handler
                    // the value an outer region of the same tag bound, for the derive, resolved from the stack like a
                    // read: Absent when nothing binds it or a mask shadows it
                    val outerIdx = stack.find(handler.tag)
                    val outer    =
                        if outerIdx < 0 then Absent
                        else
                            stack.handler(outerIdx) match
                                case _: Handler.ContextHandler[VX, CX, ?, ?] @unchecked => Maybe(stack.state(outerIdx).asInstanceOf[VX])
                                case _                                                  => Absent
                    val newState = handler.derive(outer)
                    Debugger.onContext(kyo, newState)
                    Debugger.onRegionEnter(handler, newState)
                    stack.push(handler, newState, contA.chain(contB))
                    stack.oweOwn(stack.depth - 1, handler)
                    loop(kyo.value, Arrow.id, Arrow.id)

                // a parked slice with nothing to reinstall: take on its releases and owed remainders, continue in place
                case kyo: Pending.Park[?, ?] if kyo.entries.isEmpty =>
                    stack.oweBelow(stack.depth, kyo.releases)
                    stack.oweRemaindersBelow(stack.depth, kyo.owedRemainders)
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
                                    // the extent ran to a clean end: record it on the region, whether the region is here in
                                    // place or was reinstalled by a resumed remainder, so its release (run here or by the
                                    // scope that holds it) tells a clean ending rather than the discard signal a dropped
                                    // remainder gets.
                                    hc.complete(stack.state(top).asInstanceOf[VX])
                                    // the region's own release runs its clean end against the entry's live state (a fork's
                                    // join may have written it), before the pop while the entry is live. A remainder still
                                    // owed to it was never resumed, so its regions drain as discarded.
                                    if stack.owesAny then
                                        drainCleanOwn(stack.takeReleases(top), stack.state(top))
                                        val owed = stack.takeOwedRemainders(top)
                                        if !owed.isEmpty then drainRemainders(owed, Absent)
                                    end if
                                    stack.pop()
                                    loop(res.asInstanceOf[Y < Any], next, Arrow.id)
                                case handler0 =>
                                    val handler = handler0.asInstanceOf[Handler.ArrowHandler[VX, EX, AX, Y, Any]]
                                    val result  = handler.onDone(stack.state(top).asInstanceOf[VX], Nested.unnest[AX](res))
                                    Debugger.onRegionExit(handler, result)
                                    arrowExit(handler, top)
                                    loop(result, next, Arrow.id)
                            end match
                    else
                        contA match
                            case contA: Arrow.Chain[T, Any, B, S2] @unchecked =>
                                loop(res, contA.a, contA.b.chain(contB))
                            case _ =>
                                contA(res, contB) match
                                    // a step refused the value under a stop: the deferral it hands back holds the value
                                    // settled, in front of the step that did not run, and this is where the stop parks
                                    case next: Pending.Defer[?, ?, C, S2] @unchecked
                                        if armed && !next.value.isInstanceOf[Pending[?, ?]] && Safepoint.stopped(slot) =>
                                        park(next, Arrow.id, Arrow.id)
                                    case next =>
                                        loop(next, Arrow.id, Arrow.id)
            end match
        end loop

        /** Stops the evaluation and answers what is left as a value that can be resumed anywhere.
          *
          * The node in hand and its two continuations fold into one computation, and every region still installed comes with it as a
          * snapshot, so resuming reinstalls exactly what was here. Cheap case, an empty stack with no releases: the remainder is the
          * computation itself, no `Park` built.
          */
        def park[T, B, C, S2](v: T < S2, contA: Arrow[T, B, S2], contB: Arrow[B, C, S2]): A < S =
            val parked: Any < Any =
                if contA.isInstanceOf[Arrow.Id[?]] && contB.isInstanceOf[Arrow.Id[?]] then v.asInstanceOf[Any < Any]
                else Effect.defer(v, contA, contB).asInstanceOf[Any < Any]
            val evalRs = stack.takeEvalReleases()
            // The eval's own owed lane travels in the Park, symmetric with `evalRs`; each region's lane travels beside
            // its snapshot in `entryOwed` (captured before `takeAll` empties the stack) and re-owes to the reinstalled
            // region like its release, so a lane owed to a peel scope drains there, not at eval end.
            val owed      = stack.takeEvalOwedRemainders()
            val entryOwed = if stack.owesAny then stack.takeEntryOwed() else Chunk.empty
            if stack.isEmpty then
                if evalRs.isEmpty && owed.isEmpty then parked.asInstanceOf[A < S]
                else Pending.Park[A, S](parked, Stack.Snapshot.empty, evalRs, owed)
            else
                Debugger.whenEnabled {
                    var j = stack.depth - 1
                    while j >= 0 do
                        Debugger.onRegionExit(stack.handler(j), parked)
                        j -= 1
                }
                Pending.Park[A, S](parked, stack.takeAll(), evalRs, owed, entryOwed)
            end if
        end park

        def maskedRead[VX2, CX2 <: ContextEffect[VX2], T2, Y, S3](
            kyo: Pending.SuspendContext[VX2, CX2, T2, CX2 & S3],
            entries: Stack.Snapshot,
            resume: Arrow[T2, Y, S3]
        ): Y < (CX2 & S3) =
            val handler      = stack.handler(stack.depth - 1).asInstanceOf[Handler.MaskingHandler[CX2, Y, Any, S3]]
            val continuation =
                if entries.isEmpty then kyo.cont.chain(resume)
                else kyo.crossing(entries, resume)
            val result = handler.answering(kyo.reraise, continuation, kyo, stack)
            Debugger.onResult(result)
            result
        end maskedRead

        /** Reinstalls the regions a parked slice carries, restoring the releases each owed.
          *
          * A dump snapshot carries no releases (the dumping handler moved them to its own entry), so a reinstalled region resumes with none
          * and the holder runs them once at its end. A park snapshot carries them, because a parked computation is itself resuming and runs
          * its regions' extents to an end where it resumes.
          */
        def installed(kyo: Pending.Park[?, ?], resume: Arrow[Any, Any, Any]): Unit =
            val entries   = kyo.entries
            val entryOwed = kyo.entryOwed
            // a region whose release already ran refuses to be reinstalled (a bracket resumed after its resource was
            // released is a use-after-release); checked before any state changes, so a refusal leaves the stack untouched
            var ri = 0
            while ri < entries.regions do
                entries.handler(ri) match
                    case hc: Handler.ContextHandler[Any, ?, ?, ?] @unchecked => hc.reenter(entries.state(ri))
                    case _                                                   => ()
                ri += 1
            end while
            // the remainder is being consumed here, so the scope that owed it no longer drains it: its regions run
            // their own ends instead, reinstalled just below with their releases restored
            stack.settle(entries)
            stack.oweBelow(stack.depth, kyo.releases)
            stack.oweRemaindersBelow(stack.depth, kyo.owedRemainders)
            @tailrec def install(i: Int): Unit =
                if i < entries.regions then
                    val stored = entries.continuation(i).asInstanceOf[Arrow[Any, Any, Any]]
                    val cont   =
                        if i == 0 then stored.chain(resume)
                        else stored
                    val handler = entries.handler(i).asInstanceOf[Handler[Effect, Any, Any]]
                    val st      = entries.state(i)
                    Debugger.onRegionEnter(handler, st)
                    stack.push(handler, st, cont)
                    stack.owe(stack.depth - 1, entries.releases(i))
                    // the lane this entry owed travels beside the snapshot and re-owes to the reinstalled entry
                    if i < entryOwed.size then stack.oweRemainders(stack.depth - 1, entryOwed(i))
                    install(i + 1)
            install(0)
        end installed

        // A clean end runs what it owed, told the extent ended without a failure. `drainClean` is for a holder's or the
        // evaluation's own end, whose releases are captured closures moved onto it: a held/discarded release's region did
        // not complete in place and the holder already has, so a throw is reported, not propagated past a done computation.
        def drainClean(releases: Stack.Releases): Unit =
            releases.run(Absent)(t => Report.unhandled(t))

        // a context region's clean completion: the extent ran to an end (recorded above), so the release runs told the
        // clean ending, and a finalizer that throws here fails the computation, so the throw propagates.
        def drainCleanOwn(releases: Stack.Releases, state: Any): Unit =
            releases.runOwn(state, Absent)(t => throw t)

        // A dropped remainder (its region ended without resuming it) is drained here, each release resolved against the
        // state its snapshot carried since no entry is live; a throw is reported on a clean end, suppressed on an unwind.
        def drainRemainders(snapshots: Chunk[Stack.Snapshot], failure: Maybe[Throwable]): Unit =
            var s = 0
            while s < snapshots.size do
                val entries = snapshots(s)
                var i       = 0
                while i < entries.regions do
                    entries.releases(i).capture(entries.state(i)).run(failure) { t =>
                        failure match
                            case Present(ex) => if t ne ex then ex.addSuppressed(t)
                            case Absent      => Report.unhandled(t)
                    }
                    i += 1
                end while
                s += 1
            end while
        end drainRemainders

        // An escaping region moves what it owes to the scope below rather than running it (it handed its continuation
        // out); every other region runs it, as a discarded remainder. Remainders owed to the region move the same way.
        def arrowExit(handler: Handler.ArrowHandler[?, ?, ?, ?, ?], top: Int): Unit =
            val escaping = handler.isInstanceOf[Handler.FirstHandler[?, ?, ?, ?, ?, ?]]
            val owed     = if stack.owesAny then stack.takeOwedRemainders(top) else Chunk.empty
            stack.pop()
            if stack.owesAny then
                val held = stack.takePopped()
                if escaping then stack.oweBelow(stack.depth, held)
                else drainClean(held)
            end if
            if !owed.isEmpty then
                if escaping then stack.oweRemaindersBelow(stack.depth, owed)
                else drainRemainders(owed, Absent)
            end if
        end arrowExit

        /** Unwinds the stack for a throwable, offering it to each region's recover arm from the innermost outward.
          *
          * A region that answers stops the unwind and evaluation continues from there; one that declines is popped, running what it owed with
          * the failure. An empty stack re-raises with the effect trace spliced in. A context region has no recover arm, so it is always
          * popped, its own release running with the failure. A fatal throwable is offered to nothing: every region is unwound and released,
          * and it propagates.
          */
        @tailrec def recovered(ex: Throwable): A < S =
            if stack.isEmpty then
                drainFailed(stack.takeEvalReleases(), ex)
                drainRemainders(stack.takeEvalOwedRemainders(), Maybe(ex))
                EffectTrace.splice(ex)
                throw ex
            else
                val top = stack.depth - 1
                stack.handler(top) match
                    case hc: Handler.ContextHandler[VX, CX, ?, ?] @unchecked =>
                        Debugger.onRegionExit(hc, ex)
                        drainFailedOwn(stack.takeReleases(top), stack.state(top), ex)
                        if stack.owesAny then drainRemainders(stack.takeOwedRemainders(top), Maybe(ex))
                        stack.pop()
                        recovered(ex)
                    case handler0 =>
                        val handler = handler0.asInstanceOf[Handler.ArrowHandler[VX, EX, AX, Y, Any]]
                        val state   = stack.state(top)
                        // the regions this handler held are abandoned by the failure whether it recovers or not, so
                        // their releases, and any remainders owed to it, run with the failure before the recover arm decides
                        if stack.owesAny then
                            drainFailed(stack.takeReleases(top), ex)
                            drainRemainders(stack.takeOwedRemainders(top), Maybe(ex))
                        val outcome =
                            try if IsFatal(ex) then Absent else handler.onRecover(state.asInstanceOf[VX], ex)
                            catch
                                case ex2 if !IsFatal(ex2) =>
                                    Debugger.onRegionExit(handler, ex2)
                                    stack.pop()
                                    EffectTrace.attach(ex2, stack)
                                    EffectTrace.splice(ex2)
                                    return recovered(ex2)
                        outcome match
                            case Present(r) =>
                                Debugger.onRecover(handler, ex)
                                Debugger.onRegionExit(handler, r)
                                // Deferred, not applied: run inside the guard's catch with the region still on the stack,
                                // so the continuation's first link runs after the pop, where a throw from it unwinds
                                // through the regions below instead of escaping the eval.
                                Effect.defer(r, stack.continuation(top).asInstanceOf[Arrow[Y, A, S]])
                            case Absent =>
                                Debugger.onRegionExit(handler, ex)
                                stack.pop()
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
            drainRemainders(stack.takeEvalOwedRemainders(), Absent)
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

    private[kernel] def dumped(stack: Stack, idx: Int, kyo: Pending.Suspend[?, ?, ?, ?], escaping: Boolean = false): Stack.Snapshot =
        val entries = stack.dump(idx + 1, escaping)
        Debugger.whenEnabled {
            var i = entries.regions - 1
            while i >= 0 do
                Debugger.onRegionExit(entries.handler(i), kyo)
                i -= 1
        }
        entries
    end dumped

    // The unwind of a failed region runs what it owed with the failure, a throw from a release attached as suppressed
    // rather than replacing it. `drainFailedOwn` resolves a context region's own release against the entry's live `state`.
    private def drainFailed(releases: Stack.Releases, ex: Throwable): Unit =
        releases.run(Maybe(ex))(t => if t ne ex then ex.addSuppressed(t))

    private def drainFailedOwn(releases: Stack.Releases, state: Any, ex: Throwable): Unit =
        releases.runOwn(state, Maybe(ex))(t => if t ne ex then ex.addSuppressed(t))

    /** Releases the regions `v` still holds, running nothing else.
      *
      * A deferral is walked rather than evaluated, so what it holds stays unreached: a caller releasing a cont
      * it has just refused would otherwise run the very thing the refusal exists to stop.
      */
    def release[A, S](v: A < S, ex: Throwable): Unit =
        release(v, ex, Absent, _ => ())

    /** Releases the regions `v` still holds, and hands `f` the input of the first operation under them that `effectTag` answers, so a caller
      * owing something to an operation the computation stands at can settle it without a second walk. `f` runs before anything is released.
      *
      * An operation under a deferral does not exist yet, and neither does anything it would have waited on, so it is not reported.
      */
    def release[I[_], O[_], E <: ArrowEffect[I, O], A, S](v: A < S, ex: Throwable, effectTag: Tag[E])(
        f: [C] => I[C] => Unit
    ): Unit =
        // Erasure-forced: the operation's state type is existential here, and `f` takes it back at that type.
        release(v, ex, Present(effectTag.erased), input => f[Any](input.asInstanceOf[I[Any]]))

    private def release[A, S](v: A < S, ex: Throwable, effectTag: Maybe[Tag[Any]], f: Any => Unit): Unit =
        var collected: Stack.Releases = Stack.Releases.empty

        def collectContext(hc: Handler.ContextHandler[?, ?, ?, ?], state: Any): Unit =
            val h = hc.asInstanceOf[Handler.ContextHandler[Any, ContextEffect[Any], Any, Any]]
            collected = collected.add(failure => h.release(state, failure))

        @tailrec def collect(v: Any, cont: Arrow[Any, Any, Any]): Unit =
            v match
                case p: Pending[?, ?] =>
                    p match
                        // A deferral is walked, not run: running the body under it would be the caller's code, which after
                        // an interrupt would acquire what nothing then releases, and an operation that never ran is not
                        // waited on yet. A settled value owns no region, so the walk stops there.
                        case kyo: Pending.Defer[a, b, c, s] @unchecked =>
                            // Erasure-forced: the types joining a chain's links are existential from out here.
                            val after = kyo.contB.chain(cont).asInstanceOf[Arrow[Any, Any, Any]]
                            val below = kyo.contA.chain(after).asInstanceOf[Arrow[Any, Any, Any]]
                            kyo.value match
                                case _: Pending[?, ?] => collect(kyo.value, below)
                                case _                => ()
                            end match
                        case kyo: Pending.HandleContext[VX, CX, ?, ?] @unchecked =>
                            val hc = kyo.handler
                            collectContext(hc, hc.derive(Maybe.empty))
                            collect(kyo.value, cont)
                        case kyo: Pending.Handle[?, ?, ?, ?] =>
                            collect(kyo.value, cont)
                        case kyo: Pending.Park[?, ?] =>
                            // remainders a park owed are abandoned too: each carries its regions' releases, resolved
                            // against the state each snapshot carried
                            def collectOwed(owed: Chunk[Stack.Snapshot]): Unit =
                                var s = 0
                                while s < owed.size do
                                    val rem = owed(s)
                                    var ri  = 0
                                    while ri < rem.regions do
                                        collected = collected.concat(rem.releases(ri).capture(rem.state(ri)))
                                        ri += 1
                                    s += 1
                                end while
                            end collectOwed
                            collected = collected.concat(kyo.releases)
                            val entries   = kyo.entries
                            val entryOwed = kyo.entryOwed
                            var i         = 0
                            while i < entries.regions do
                                // resolve each region's own release against the state it carried, since the walk runs
                                // outside the evaluator with no live entry to read, and the lane it owed the same way
                                collected = collected.concat(entries.releases(i).capture(entries.state(i)))
                                if i < entryOwed.size then collectOwed(entryOwed(i))
                                i += 1
                            end while
                            collectOwed(kyo.owedRemainders)
                            collect(kyo.value, cont)
                        case kyo: Pending.SuspendArrow[?, ?, ?, ?, ?, ?] @unchecked =>
                            effectTag.foreach(t => if t <:< kyo.tag.erased then f(kyo.input))
                        case _: Pending.Suspend[?, ?, ?, ?] => ()
                        case _: Pending.Snapshot[?, ?]      => ()
                case _ => ()
        // The tagged walk (a fiber abandonment) runs on the just-interrupted fiber's stopped Safepoint. Reaching the
        // abandoned regions across a stopped Safepoint needs a live state, so the walk takes its own and restores the
        // caller's after; without it the abandoned regions are not reached and their releases are lost (#1735, and
        // #1928's drain never ends). The untagged walk (releasing a refused cont) runs where nothing is stopped, so
        // it uses the caller's state.
        if effectTag.isDefined then
            val slot  = Safepoint.get()
            val saved = Safepoint.save(slot)
            try collect(v, Arrow.id)
            finally Safepoint.restore(slot, saved)
        else collect(v, Arrow.id)
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

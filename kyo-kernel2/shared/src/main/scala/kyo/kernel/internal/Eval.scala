package kyo.kernel.internal

import kyo.Arrow
// unqualified so the inlined eval does not select these from Arrow.type at an expansion site
// outside package kyo, where they are not accessible. See the note in Pending.scala
import kyo.Arrow.Chain
import kyo.Arrow.Step
import kyo.Arrow.Transform
import kyo.Frame
import kyo.Maybe
import kyo.Maybe.*
import kyo.Result
import kyo.Tag
import kyo.bug
import kyo.discard
import kyo.kernel.*
import kyo.kernel.Loop.Outcome
import kyo.kernel.internal.Handler.HandlerCont
import kyo.kernel.internal.Handler.HandlerLoop
import kyo.kernel.internal.Handler.HandlerLoopState
import kyo.kernel.internal.Kyo.Binding
import kyo.kernel.internal.Kyo.Catching
import kyo.kernel.internal.Kyo.Defer
import kyo.kernel.internal.Kyo.Handle
import kyo.kernel.internal.Kyo.Park
import kyo.kernel.internal.Kyo.Suspend
import scala.annotation.nowarn
import scala.annotation.static
import scala.annotation.tailrec
import scala.util.control.NonFatal

/** A scope that answers its own failure.
  *
  * A trait, so an object that is already an entry can be a scope as well: a recovering region's handler
  * carries this and is pushed as any other handler is, and `Effect.catching`'s node carries it and is pushed
  * as itself. Nothing is allocated when the eval enters a scope.
  *
  * `Finalizer`'s sibling, and deliberately stateless where that one is not. A finalizer needs a flag because
  * two mechanisms reach it, the arrow on the completing path and the drain on the abandoning one, and running
  * a release twice is a double free. Only the unwind reaches this, and being applied is what takes it off the
  * stack, so stack presence is the scope and there is nothing to guard. That also keeps a resumed
  * continuation honest: re-entering the scope re-pushes its entry, and each entry can fail and recover.
  *
  * It carries no backup in the stack's side collection either. A continuation a clause drops takes its scope
  * with it, and a scope that never ran owes no recovery, where a resource already acquired still owes its
  * release.
  */
private[kyo] trait Recover[+A, -S]:

    /** The answer to a failure, or absent for one this must not answer.
      *
      * Fatal errors pass every recovery untouched, which is the previous kernel's rule: both of its arms
      * guard on `NonFatal`. The recovery itself never declines, and never had to: the consumers pass total
      * functions, `Abort.catching` with an explicit catch-all and `Debug` with a lambda that rethrows.
      *
      * The frames are reconstructed before the recovery runs. It is a second place a failure is observed, and
      * the boundary is no longer the only one, so a handler that reads the carrier has to see what a handler
      * at the boundary would. The previous kernel owed the same and paid it the same way.
      */
    final def panic(ex: Throwable): Maybe[A < S] =
        if !NonFatal(ex) then Maybe.empty
        else
            // spliced, not attached. The frames were reconstructed where the failure happened, by the arm
            // that caught it, and the unwind has since popped every entry above this one: attaching here
            // would describe the continuation below the scope, which is what happens next rather than what
            // went wrong. What a recovery needs is those frames written into the exception it inspects,
            // which is the obligation the boundary used to be the only one to meet
            EffectTrace.splice(ex)
            Maybe(recover(ex))
    end panic

    /** The computation the eval carries on from, at the type the scope ends at. */
    def recover(ex: Throwable): A < S

end Recover

object Eval:

    type IX[_]
    type OX[_]
    type EX <: ArrowEffect[IX, OX]
    type CX
    type AX
    type BX
    type StateX
    type SX

    private inline given Frame = Frame.internal

    // not inline: the eval is ~555 instructions and HotSpot refuses to inline it at any call site, so an
    // inline definition bought nothing at runtime and emitted a private copy of the whole interpreter per
    // call site. PendingTest alone carried 132 of them.
    //
    // not @static either, though the rest of the boundary primitives are. Scala.js cannot emit a static
    // method that contains a lambda: genSJSIR fails with "Cannot resolve delambdafy target method $anonfun"
    // on the eta-expansion below. The other @static methods in this package hold local defs and anonymous
    // classes, never lambdas, which is why they compile. The module load this costs is one getstatic
    def apply[A, S](v: A < S): A =
        apply(v, armed = false, neverStop).asInstanceOf[A]

    /** Evaluates until the computation parks, handing back a value that resumes on a later slice.
      *
      * A slice ends on a preemption stop or on the caller's own stop function, and the value returned carries
      * the regions above the park intact, with their state.
      *
      * The row is `Any`, the same as a full evaluation: every effect must already be handled. An operation
      * with no handler is a bug here too, not something a slice can park on and have answered later.
      *
      * A stop already delivered before the slice begins ends it before it starts: the input comes straight
      * back, and the sentinel is taken so the slice after this one runs.
      */
    private[kyo] def partial[A](v: A < Any, stop: () => Boolean = neverStop): A < Any =
        val slot = Safepoint.get()
        if Safepoint.consumeStopped(slot) then v
        else apply(v, armed = true, () => Safepoint.consumeStopped(slot) || stop()).asInstanceOf[A < Any]
    end partial

    // shared so a full evaluation and a partial one cannot drift apart. `armed` gates the poll rather than
    // the poll gating itself: a call per step costs 3 to 5 percent on the hot rows because the JIT will not
    // fold it away, and a full evaluation must not pay for a slice mechanism it cannot use
    private val neverStop: () => Boolean = () => false

    // The loop is compiled as one unit, and that unit's inlining budget is what everything reached from it
    // inlines out of, boxing helpers included: at 1.4KB the budget starved and Integer.valueOf stopped
    // inlining on the hot paths, which the stateful rows paid four times over. So the bulky and the cold
    // dispatches live out of line, each compiled with its own budget, and the loop keeps only the hot arms.

    private def attachThrow(ex: Throwable, node: Kyo[?, ?], next: Arrow[?, ?, ?], stack: Stack): Nothing =
        EffectTrace.attach(ex, node, next, stack)
        throw ex

    private def attachThrow(ex: Throwable, entry: Arrow[?, ?, ?], next: Arrow[?, ?, ?], stack: Stack): Nothing =
        EffectTrace.attach(ex, entry, next, stack)
        throw ex

    private def unhandled(kyo: Suspend[IX, OX, EX, CX, AX, SX], stack: Stack): Nothing =
        // the row rules this out for both entry points: a slice takes `A < Any` as well, so an operation
        // reaching here has no handler anywhere and never will
        attachThrow(bug.exception(s"unhandled suspension: ${kyo.tag}"), kyo, Arrow.id[Any], stack)

    /** The looping region's dispatch, returning what the eval runs next. Every branch of the arm it came
      * from ended in the loop, so the extraction is the arm with each of those calls replaced by its
      * argument; the dumps, pops and truncations stay inside.
      */
    @nowarn("msg=anonymous")
    private def dispatchLoop(
        stack: Stack,
        h: HandlerLoop[IX, OX, EX, AX, BX, SX],
        kyo: Suspend[IX, OX, EX, CX, AX, SX],
        pos: Int
    ): Any < Nothing =
        val out = stack.out
        val ran =
            try h.answer(kyo.input, out)
            catch
                case ex: Throwable =>
                    attachThrow(ex, kyo, Arrow.id[Any], stack)
        out.kind match
            case 1 =>
                val r = ran.asInstanceOf[OX[CX] < (EX & SX)]
                r match
                    case _: Kyo[OX[CX], EX & SX] @unchecked =>
                        val k = stack.dump[OX[CX], AX, EX & SX](pos)
                        r.map(a => k(a))
                    case _ => r
                end match
            case 2 =>
                clauseSuspendedLoop(stack, h, ran.asInstanceOf[Kyo[Loop.Outcome[OX[CX] < (EX & SX), BX], SX]], pos)
            case _ =>
                stack.truncate(pos + 1)
                ran.asInstanceOf[Any < Nothing]
        end match
    end dispatchLoop

    /** The stateless fast path, [[dispatchLoopStateFast]] without the state lane. */
    private def dispatchLoopFast(
        stack: Stack,
        h: HandlerLoop[IX, OX, EX, AX, BX, SX],
        kyo: Suspend[IX, OX, EX, CX, AX, SX],
        k: Arrow[Any, Any, Any],
        armed: Boolean,
        stop: () => Boolean
    ): Any < Nothing =
        val out = stack.out
        val ran =
            try h.answers(kyo.input, k, armed, stop, out)
            catch
                case ex: Throwable =>
                    if (out.cont ne null) && !(out.cont eq Arrow.Id) then stack.push(out.cont)
                    attachThrow(ex, kyo, Arrow.id[Any], stack)
        out.kind match
            case 1 =>
                if out.cont eq null then ran.asInstanceOf[Any < Nothing]
                else
                    stack.push(out.cont)
                    val r = ran.asInstanceOf[OX[CX] < (EX & SX)]
                    r match
                        case _: Kyo[OX[CX], EX & SX] @unchecked =>
                            val k2 = stack.dump[OX[CX], AX, EX & SX](if out.cont eq Arrow.Id then 0 else 1)
                            r.map(a => k2(a))
                        case _ => r
                    end match
            case 2 =>
                val pos2 = if (out.cont ne null) && !(out.cont eq Arrow.Id) then
                    stack.push(out.cont)
                    1
                else 0
                clauseSuspendedLoop(stack, h, ran.asInstanceOf[Kyo[Loop.Outcome[OX[CX] < (EX & SX), BX], SX]], pos2)
            case _ =>
                stack.truncate(1)
                ran.asInstanceOf[Any < Nothing]
        end match
    end dispatchLoopFast

    /** The stateless sibling of [[clauseSuspended]]. */
    @nowarn("msg=anonymous")
    private def clauseSuspendedLoop(
        stack: Stack,
        h: HandlerLoop[IX, OX, EX, AX, BX, SX],
        clause: Kyo[Loop.Outcome[OX[CX] < (EX & SX), BX], SX],
        pos: Int
    ): Any < Nothing =
        val k = stack.dump[OX[CX], AX, EX & SX](pos)
        discard(stack.pop())
        new Defer[Loop.Outcome[OX[CX] < (EX & SX), BX], BX, BX, EX & SX]
            with Step[Loop.Outcome[OX[CX] < (EX & SX), BX], BX, EX & SX]:
            def frame = Frame.internal
            def value = clause
            def contA = this
            def contB = Arrow.id[BX]
            override def apply(o: Loop.Outcome[OX[CX] < (EX & SX), BX]) =
                o match
                    case r: Loop.Continue[OX[CX] < (EX & SX)] @unchecked =>
                        Effect.defer(r._1.map(a => k(a)), h)
                    case v => v.asInstanceOf[BX]
            def apply[D, S2](o: Loop.Outcome[OX[CX] < (EX & SX), BX] < S2, next: Arrow[BX, D, S2])
                : D < (EX & SX & S2) =
                o match
                    case kyo: Kyo[Loop.Outcome[OX[CX] < (EX & SX), BX], S2] @unchecked =>
                        Effect.defer(kyo, this, next)
                    case _ => next(apply(Nested.unnest(o)), Arrow.id)
        end new
    end clauseSuspendedLoop

    /** The stateful sibling of [[dispatchLoop]]; the state slot updates stay inside.
      *
      * The clause call and the outcome destructuring live in the handler's own `answer` method, which the
      * call site generated with the clause statically bound: the outcome is consumed where it is born, so
      * no allocation crosses the virtual call. What crosses instead is the answer as the return value and
      * the state and branch through the stack's out-cell, which this dispatch reads back.
      */
    @nowarn("msg=anonymous")
    private def dispatchLoopState(
        stack: Stack,
        h: HandlerLoopState[IX, OX, EX, AX, BX, SX, StateX],
        kyo: Suspend[IX, OX, EX, CX, AX, SX],
        pos: Int
    ): Any < Nothing =
        val s   = stack.state(pos).getOrElse(h.initialState)
        val out = stack.out
        val ran =
            try h.answer(s, kyo.input, out)
            catch
                case ex: Throwable =>
                    attachThrow(ex, kyo, Arrow.id[Any], stack)
        out.kind match
            case 1 => // answered: the region continues with the returned value
                stack.putState(pos, out.state.asInstanceOf[StateX])
                val r = ran.asInstanceOf[OX[CX] < (EX & SX)]
                r match
                    case _: Kyo[OX[CX], EX & SX] @unchecked =>
                        val k = stack.dump[OX[CX], AX, EX & SX](pos)
                        r.map(a => k(a))
                    case _ => r
                end match
            case 2 => // the clause itself suspended: dispatch it outside the region and re-enter on its outcome
                clauseSuspended(stack, h, ran.asInstanceOf[Kyo[Loop.Outcome2[StateX, OX[CX] < (EX & SX), BX], SX]], pos)
            case _ => // finished: the clause completed the region
                stack.truncate(pos + 1)
                ran.asInstanceOf[Any < Nothing]
        end match
    end dispatchLoopState

    /** The stateful fast path: the handler at the top of the stack with its continuation in hand, so the
      * generated answer loop can run with the state in a local. Every bail arrives here with the state
      * committed to the cell; the slot and, where owed, the continuation entry are restored before the
      * eval carries on, so a capture, a park or a failure observes exactly what the general path leaves.
      */
    private def dispatchLoopStateFast(
        stack: Stack,
        h: HandlerLoopState[IX, OX, EX, AX, BX, SX, StateX],
        kyo: Suspend[IX, OX, EX, CX, AX, SX],
        k: Arrow[Any, Any, Any],
        armed: Boolean,
        stop: () => Boolean
    ): Any < Nothing =
        val s   = stack.state(0).getOrElse(h.initialState)
        val out = stack.out
        val ran =
            try h.answers(s, kyo.input, k, armed, stop, out)
            catch
                case ex: Throwable =>
                    // the loop committed before rethrowing; make the slot and the entry current
                    stack.putState(0, out.state.asInstanceOf[StateX])
                    if (out.cont ne null) && !(out.cont eq Arrow.Id) then stack.push(out.cont)
                    attachThrow(ex, kyo, Arrow.id[Any], stack)
        out.kind match
            case 1 =>
                stack.putState(0, out.state.asInstanceOf[StateX])
                if out.cont eq null then ran.asInstanceOf[Any < Nothing]
                else
                    // the generic body answered once and handed the continuation back: reattach it, then
                    // deliver the way the general path would
                    stack.push(out.cont)
                    val r = ran.asInstanceOf[OX[CX] < (EX & SX)]
                    r match
                        case _: Kyo[OX[CX], EX & SX] @unchecked =>
                            val k2 = stack.dump[OX[CX], AX, EX & SX](if out.cont eq Arrow.Id then 0 else 1)
                            r.map(a => k2(a))
                        case _ => r
                    end match
                end if
            case 2 =>
                val pos2 = if (out.cont ne null) && !(out.cont eq Arrow.Id) then
                    stack.push(out.cont)
                    1
                else 0
                clauseSuspended(stack, h, ran.asInstanceOf[Kyo[Loop.Outcome2[StateX, OX[CX] < (EX & SX), BX], SX]], pos2)
            case _ =>
                stack.truncate(1)
                ran.asInstanceOf[Any < Nothing]
        end match
    end dispatchLoopStateFast

    /** The re-entry a suspended clause owes: its outcome dispatched outside the region, resuming through
      * the folded continuation on a continue and completing past it on a done.
      */
    @nowarn("msg=anonymous")
    private def clauseSuspended(
        stack: Stack,
        h: HandlerLoopState[IX, OX, EX, AX, BX, SX, StateX],
        clause: Kyo[Loop.Outcome2[StateX, OX[CX] < (EX & SX), BX], SX],
        pos: Int
    ): Any < Nothing =
        val k = stack.dump[OX[CX], AX, EX & SX](pos)
        discard(stack.pop())
        new Defer[Loop.Outcome2[StateX, OX[CX] < (EX & SX), BX], BX, BX, EX & SX]
            with Step[Loop.Outcome2[StateX, OX[CX] < (EX & SX), BX], BX, EX & SX]:
            def frame = Frame.internal
            def value = clause
            def contA = this
            def contB = Arrow.id[BX]
            override def apply(o: Loop.Outcome2[StateX, OX[CX] < (EX & SX), BX]) =
                o match
                    case r: Loop.Continue2[StateX, OX[CX] < (EX & SX)] @unchecked =>
                        Effect.defer(r._2.map(a => k(a)), HandlerLoopState(h, r._1))
                    case v => v.asInstanceOf[BX]
            def apply[D, S2](
                o: Loop.Outcome2[StateX, OX[CX] < (EX & SX), BX] < S2,
                next: Arrow[BX, D, S2]
            ): D < (EX & SX & S2) =
                o match
                    case kyo: Kyo[Loop.Outcome2[StateX, OX[CX] < (EX & SX), BX], S2] @unchecked =>
                        Effect.defer(kyo, this, next)
                    case _ => next(apply(Nested.unnest(o)), Arrow.id)
        end new
    end clauseSuspended

    @nowarn("msg=anonymous")
    private def apply[A, S](v: A < S, armed: Boolean, stop: () => Boolean): Any =
        val stack = Stack.borrow()

        /** The slice, as a value that resumes it.
          *
          * An empty stack with nothing owed means the value alone is the whole remainder, so no node is built
          * for it. Otherwise the three arrays move into the park and the stack is left clean: the releases
          * belong to the computation, and this eval is ending without finishing it, so its drain must not
          * run them.
          */
        def park(curr: Any < Nothing): Any =
            if stack.isEmpty && stack.outstanding == 0 then curr
            else
                val es   = stack.snapshotEntries()
                val sts  = stack.snapshotStates()
                val fins = stack.snapshotFinalizers()
                stack.clear()
                // the row widens from Nothing to Any: what the loop carries is a value whose effects the
                // regions in the snapshot answer, and those travel with it
                new Park[Any, Any](curr.asInstanceOf[Any < Any], es, sts, fins)
        end park

        @tailrec def loop(curr: Any < Nothing): Any =
            curr match
                case kyo: Defer[a, b, A, S] @unchecked =>
                    // the poll lives here rather than at the top of the loop because a stop drains the budget,
                    // so every combinator starts deferring and this arm is reached on the next operation. The
                    // stop function is polled at the same cadence, which is often enough: nothing runs long
                    // without deferring, since a fused chain is bounded by the depth guard and hitting it is
                    // itself a deferral
                    //
                    // never in front of a binding: a resource whose scope has not been installed yet is owed
                    // by nobody, so a slice that ended here would be holding one that no drain can find. The
                    // payload is read once and the park carries what was read, so a by-name payload does not
                    // run a second time on the way back
                    val v = kyo.value
                    if armed && !v.isInstanceOf[Binding[?, ?, ?, ?]] && stop() then
                        park(Effect.defer[a, b, A, S](v, kyo.contA, kyo.contB))
                    else
                        stack.push(kyo.contB)
                        stack.push(kyo.contA)
                        loop(v)
                    end if
                case kyo: Suspend[IX, OX, EX, CX, AX, SX] @unchecked =>
                    stack.push(kyo.cont)
                    val pos = stack.find(kyo.tag)
                    if pos < 0 then unhandled(kyo, stack)
                    else
                        stack.handler(pos) match
                            case h: HandlerCont[IX, OX, EX, AX, ?, SX] @unchecked =>
                                val k = stack.dump[OX[CX], AX, EX & SX](pos)
                                val next =
                                    try h.run(kyo.input, k)
                                    catch
                                        case ex: Throwable => attachThrow(ex, kyo, k, stack)
                                loop(next)
                            case h: HandlerLoop[IX, OX, EX, AX, BX, SX] @unchecked =>
                                // same gate as the stateful case below
                                if pos == 0 then
                                    loop(dispatchLoopFast(stack, h, kyo, Arrow.id[Any].asInstanceOf[Arrow[Any, Any, Any]], armed, stop))
                                else if pos == 1 && stack.entry(0).isInstanceOf[Arrow.Step[
                                        ?,
                                        ?,
                                        ?
                                    ]] && !stack.entry(0).isInstanceOf[Arrow.Region[?, ?, ?]]
                                then
                                    val k = stack.pop().asInstanceOf[Arrow[Any, Any, Any]]
                                    loop(dispatchLoopFast(stack, h, kyo, k, armed, stop))
                                else
                                    loop(dispatchLoop(stack, h, kyo, pos))
                            case h: HandlerLoopState[IX, OX, EX, AX, BX, SX, StateX] @unchecked =>
                                // the answer loop needs the continuation in hand: a run of zero or one
                                // plain entries above the handler is exactly the entry just pushed for
                                // this suspension, or nothing when the suspension was bare. A region
                                // entry stays where the scans need it, so it takes the general path
                                if pos == 0 then
                                    loop(dispatchLoopStateFast(
                                        stack,
                                        h,
                                        kyo,
                                        Arrow.id[Any].asInstanceOf[Arrow[Any, Any, Any]],
                                        armed,
                                        stop
                                    ))
                                else if pos == 1 && stack.entry(0).isInstanceOf[Arrow.Step[
                                        ?,
                                        ?,
                                        ?
                                    ]] && !stack.entry(0).isInstanceOf[Arrow.Region[?, ?, ?]]
                                then
                                    val k = stack.pop().asInstanceOf[Arrow[Any, Any, Any]]
                                    loop(dispatchLoopStateFast(stack, h, kyo, k, armed, stop))
                                else
                                    loop(dispatchLoopState(stack, h, kyo, pos))
                        end match
                    end if
                case kyo: Handle[EX, ?, ?, A, S] @unchecked =>
                    stack.push(kyo.cont)
                    stack.push(kyo.handler)
                    loop(kyo.value)
                // the three arms below are last on purpose: a match tests in order, so every deferral,
                // operation and region above would pay a failed test for each of them. These are the rare
                // shapes, and a computation that never parks, recovers or binds must not pay for them
                case kyo: Park[?, ?] =>
                    // resuming is putting the parked stack back and carrying on from the value it held. The
                    // entries go above whatever this eval already pushed, so a handler installed around the
                    // parked computation sits below its regions and answers what they do not
                    stack.restore(kyo.entries, kyo.states, kyo.finalizers)
                    loop(kyo.value)
                case kyo: Catching[?, ?] =>
                    // the entry marks where the scope ends, and the body runs above it. A value flowing back
                    // through pops it, which is what makes the scope end; a failure finds it on the way down
                    stack.push(kyo)
                    loop(kyo.value)
                case kyo: Binding[v, ?, ?, ?] @unchecked =>
                    // a bind marks its own extent by going on the stack, and installing it is what resolves
                    // what it holds against what is bound below. A read marks nothing and only needs the
                    // innermost binding of its tag, or absent where none binds it
                    if kyo.bound.isDefined then
                        stack.push(kyo)
                        // the slot is where the install left it, read back at the binding's own value type:
                        // the array holds every binding's value, so its element type is the erasure, not this
                        val held = stack.state[v](0)
                        // a binding that owes something on the way out owes it exactly as a bracket does, so
                        // it is a `Finalizer`: above the binding, so it runs where the extent ends, and in
                        // the drain, so it still runs when the extent is abandoned rather than left
                        kyo.release.foreach { release =>
                            val fin = new Finalizer(release, held.getOrElse(bug("bound value missing")))
                            stack.pushFinalizer(fin)
                            stack.push(fin)
                        }
                        loop(kyo.resume(held))
                    else
                        // what a lookup returns is typed by the slots it walked, which hold every binding's
                        // value on this stack, so the read's own value type is asserted here
                        loop(kyo.resume(kyo.tag.fold(Maybe.empty)(stack.lookup).asInstanceOf[Maybe[v]]))
                case _ =>
                    val r = Nested.unnest[Any](curr)
                    if !stack.isEmpty then
                        val s = stack.state[StateX](0)
                        stack.pop() match
                            case h: HandlerLoopState[IX, OX, EX, AX, BX, S, StateX] @unchecked =>
                                val next =
                                    try h.apply(s.getOrElse(h.initialState), r.asInstanceOf[AX])
                                    catch
                                        case ex: Throwable => attachThrow(ex, h, Arrow.id[Any], stack)
                                loop(next)
                            case h: Handler[EX, AX, BX, S] @unchecked =>
                                val tail = stack.dump[BX, Any, EX & S]()
                                val next =
                                    try h(curr.asInstanceOf[AX < (EX & S)], tail)
                                    catch
                                        case ex: Throwable => attachThrow(ex, h, tail, stack)
                                loop(next)
                            // after the common entries, for the reason the node match orders its own arms:
                            // a value leaving a computation with no bindings in it must not pay a test for one
                            case _: Binding[?, ?, ?, ?] =>
                                // an extent ending: the entry is identity, so the value carries on to
                                // whatever stands below it, and there is nothing to fold a continuation for
                                loop(curr)
                            case head =>
                                val tail = stack.dump[Any, Any, EX & S]()
                                val next =
                                    try head.asInstanceOf[Arrow[Any, ?, EX & S]](curr, tail)
                                    catch
                                        case ex: Throwable => attachThrow(ex, head, tail, stack)
                                loop(next)
                        end match
                    else r
                    end if
        end loop

        val slot  = Safepoint.get()
        val saved = Safepoint.save(slot)
        // after `save`, which installs a fresh budget and clears the armed bit as it reads. Arming makes a
        // stop drain that budget, so the next operation defers and the poll above is reached at once rather
        // than up to a depth guard's worth of fused steps later. `restore` in the finally puts the caller's
        // state back, armed bit included, so a slice nested in another eval leaves no trace
        if armed then Safepoint.arm(slot)
        // recorded so the drain below can tell an eval that is leaving on an exception from one that is
        // completing, and attach a failing release to the former rather than replacing it
        var failure: Throwable | Null = null
        try
            // a recovery answers a failure with the value the computation carries on from, so a throw is not
            // always the end: the unwind either produces that value, and this goes round again, or it does
            // not and the failure leaves.
            //
            // The shape is forced from both sides. It cannot be a `try` around `loop`'s body, because a tail
            // call may not sit inside one and `loop` is what keeps a computation of any depth flat. And it
            // cannot recurse, which would read better, because the recursive call would sit inside the catch
            // and cost a frame per recovery: ten thousand scopes that fail and recover in sequence would
            // overflow. A loop out here costs neither.
            var curr = v.asInstanceOf[Any < Nothing]
            var out  = null.asInstanceOf[Any]
            var done = false
            while !done do
                try
                    out = loop(curr)
                    done = true
                catch
                    case ex: Throwable =>
                        stack.unwind(ex) match
                            case Present(next) => curr = next.asInstanceOf[Any < Nothing]
                            case Absent =>
                                failure = ex
                                // every throw that carries frames has already had them reconstructed at the
                                // site that ran the user code, so this only rewrites the exception's own trace
                                EffectTrace.splice(ex)
                                throw ex
            end while
            out
        finally
            // before the stack is pooled, and outside the catch above, so a release still runs when the
            // eval is leaving on an exception. One whose use completed already ran through its arrow and
            // is a no-op here
            stack.drainFinalizers(failure)
            Stack.release(stack)
            Safepoint.restore(slot, saved)
        end try
    end apply

    /** Runs the releases a parked computation still owes, for a holder that has decided not to resume it.
      *
      * A park carries its outstanding releases rather than running them, because the computation may carry on
      * and use those resources. That leaves whoever holds the park with the choice, and this is the half of
      * it that gives up. Resuming afterwards is harmless but pointless: the releases have run, and each is a
      * no-op the second time.
      *
      * Only the park itself is inspected. A parked value that has been composed since holds its park inside a
      * deferral where this cannot see it, so a holder that intends to finalize must keep what it was handed.
      */
    private[kyo] def finalizeResources(v: Any < Any): Unit =
        v match
            case p: Park[?, ?] =>
                // backwards, since index zero is the outermost: a resource acquired inside another is
                // released before it
                var i = p.finalizers.size
                while i > 0 do
                    i -= 1
                    p.finalizers(i).run(Result.panic(Finalizer.Abandoned))
            case _ => ()

end Eval

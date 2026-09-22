package kyo.kernel.internal

import kyo.Frame
import kyo.Maybe
import kyo.Maybe.Absent
import kyo.Tag
import kyo.discard
import kyo.kernel.<
import kyo.kernel.Arrow
import kyo.kernel.ArrowEffect
import kyo.kernel.ContextEffect
import kyo.kernel.Effect
import kyo.kernel.Loop
import kyo.kernel.Loop.Continue
import kyo.kernel.Loop.Continue2
import kyo.kernel.Loop.Outcome
import kyo.kernel.Loop.Outcome2
import scala.annotation.publicInBinary

/** What a region installs: the clause that answers an effect, plus what the evaluator has to know to run the region around it.
  *
  * One instance per region entry, pushed onto the [[Stack]] and matched by [[tag]] when a suspension looks for who answers it. Common to the
  * answering shapes below, all of it about the region rather than the effect: whether the clause may resume more than once, whether it hands
  * the continuation out, and what the region contributes to the context.
  *
  * Those three are declared rather than inferred because the evaluator has to decide what to do with a region's obligations before the clause
  * runs, and by then it is too late to observe what the clause does.
  */
sealed abstract private[kernel] class Handler[E <: Effect, A, -S]:
    def tag: Tag[E]

@publicInBinary private[kernel] object Handler:

    sealed abstract class ArrowHandler[State, E <: Effect, A, B, -S] extends Handler[E, B, S]:
        def onDone(state: State, v: A): B < S
        def onRecover(state: State, ex: Throwable): Maybe[B < S] = Absent

    /** The region behind [[kyo.kernel.ArrowEffect.handleCont]]: the clause is handed the continuation and resumes it inside the region.
      *
      * Single-shot (the default): the clause resumes at most once, so the dumped regions travel with the continuation and close at their
      * own end where it resumes (this region drains them if the clause drops it). [[repeated]] (`handleContRepeated`): the clause may resume
      * more than once, so the dumped regions are held and their releases run once, at this region's end, keeping a shared resource live
      * across every resumption.
      */
    abstract class ContHandler[I[_], O[_], E <: ArrowEffect[I, O], A, B, S] extends ArrowHandler[Unit, E, A, B, S]:
        def run[X](input: I[X], cont: Arrow[O[X], A, E & S]): A < (E & S)

        def repeated: Boolean = false

        /** The catch is here, not around the evaluator's call, so the suspension and stack are still in hand: by the time a throwable reaches
          * the loop, its region may already be off the stack.
          */
        def answering[X](input: I[X], cont: Arrow[O[X], A, E & S], kyo: Pending[?, ?], stack: Stack): A < (E & S) =
            try run(input, cont)
            catch
                case ex =>
                    EffectTrace.attach(ex, kyo, cont, stack)
                    throw ex
    end ContHandler

    abstract class MaskingHandler[E <: Effect, A, B, S] extends ArrowHandler[Unit, E, A, B, S]:
        def run[X](operation: X < E, next: Arrow[X, A, E & S]): A < (E & S)

        def answering[X](operation: X < E, next: Arrow[X, A, E & S], kyo: Pending[?, ?], stack: Stack): A < (E & S) =
            try run(operation, next)
            catch
                case ex =>
                    EffectTrace.attach(ex, kyo, next, stack)
                    throw ex
    end MaskingHandler

    /** The peel behind [[kyo.kernel.ArrowEffect.handleFirst]]: the clause answers the first operation and carries its continuation out as the
      * region's own result, so the remainder leaves the region as a value. It is the only escaping handler.
      *
      * Single-shot (the default): the remainder is consumed once, each dumped region closing at its own end (the scope below drains it if the
      * remainder is dropped). [[repeated]]: the remainder is resumed more than once (a streamed choice's branches), so the dumped regions are
      * held and their releases run once after every resumption, keeping a shared resource live across all of them.
      */
    abstract class FirstHandler[I[_], O[_], E <: ArrowEffect[I, O], A, B, S] extends ArrowHandler[Unit, E, A, B, S]:
        def run[X](input: I[X], cont: Arrow[O[X], A, E & S]): B < (E & S)

        def repeated: Boolean = false

        def answering[X](input: I[X], cont: Arrow[O[X], A, E & S], kyo: Pending[?, ?], stack: Stack): B < (E & S) =
            try run(input, cont)
            catch
                case ex =>
                    EffectTrace.attach(ex, kyo, cont, stack)
                    throw ex
    end FirstHandler

    abstract class LoopHandler[I[_], O[_], E <: ArrowEffect[I, O], A, B, S] extends ArrowHandler[Unit, E, A, B, S]:
        def run[X](input: I[X]): Outcome[O[X] < (E & S), B < S] < S

        def running[X](
            input: I[X],
            kyo: Pending.Suspend[?, ?, ?, ?],
            stack: Stack,
            idx: Int
        ): Outcome[O[X] < (E & S), B < S] < S =
            // The clause runs outside the regions between this one and the suspension, so a throw from it leaves them
            // for this region: dumping takes them off the stack and records the debt here, discharged when it unwinds.
            try run(input)
            catch
                case ex =>
                    discard(Eval.dumped(stack, idx, kyo))
                    EffectTrace.attach(ex, kyo, stack)
                    throw ex

        def clauseDispatch: Arrow[Outcome[A < (E & S), B < S], B, S] =
            type OutT = Outcome[A < (E & S), B < S]
            new Arrow.Step[OutT, B, S]:
                def frame                                                         = Frame.internal
                override def apply[D, S3](out: OutT < S3, cont2: Arrow[B, D, S3]) =
                    out match
                        case p: Pending[OutT, S3] @unchecked =>
                            Effect.defer(p, this, cont2)
                        case out: Continue[A < (E & S)] @unchecked =>
                            cont2(Pending.handle[Unit, E, A, B, S](
                                out._1,
                                LoopHandler.this,
                                ()
                            ))
                        case out =>
                            cont2(Nested.unnest[B < S](Loop.unnest(out.asInstanceOf[OutT])))
            end new
        end clauseDispatch

        /** Answers one occurrence for a region at the top of the stack, applying the continuation to the answer without leaving.
          *
          * The fused path, separate from [[running]]: a region need not be exited and re-entered for an occurrence it can answer in place, so
          * the answer goes straight into `k`. A throwable becomes a deferred re-raise carried by `Loop.continue` rather than thrown here, so it
          * reaches the evaluator as an ordinary computation and unwinds through the regions the continuation reinstalls, not from wherever this
          * clause happened to run.
          */
        def answers[X](
            input: I[X],
            k: Arrow[O[X], A, E & S],
            armed: Boolean,
            slot: Safepoint.Slot,
            frame: Frame
        ): Outcome[A < (E & S), B < S] < S =
            try
                run(input) match
                    case c: Continue[O[X] < (E & S)] @unchecked =>
                        val ans = c._1
                        ans match
                            case _: Pending[?, ?] =>
                                // Through the representation, not `ans.map(a => k(a))`: a map interposes a poll, so a
                                // stop as `ans` settles parks it in front of `k`'s first link (a bracket's `Ensure`),
                                // never reached. The deferral reaches that link with no poll.
                                Loop.continue(Effect.defer(ans, k))
                            case _ =>
                                Loop.continue(k(Nested.unnest[O[X]](ans)))
                        end match
                    case o =>
                        if o.isInstanceOf[Pending[?, ?]] then attachReentry[I, O, E, A, B, S, X](k)(o)
                        else o.asInstanceOf[Outcome[A < (E & S), B < S] < S]
                end match
            catch
                case ex: Throwable =>
                    EffectTrace.attach(ex, k, frame)
                    Loop.continue(Effect.deferInline[A, E & S](throw ex)(using frame))
            end try
        end answers
    end LoopHandler

    abstract class LoopStateHandler[State, I[_], O[_], E <: ArrowEffect[I, O], A, B, S] extends ArrowHandler[State, E, A, B, S]:
        def run[X](state: State, input: I[X]): Outcome2[State, O[X] < (E & S), B < S] < S

        def running[X](
            state: State,
            input: I[X],
            kyo: Pending.Suspend[?, ?, ?, ?],
            stack: Stack,
            idx: Int
        ): Outcome2[State, O[X] < (E & S), B < S] < S =
            try run(state, input)
            catch
                case ex =>
                    discard(Eval.dumped(stack, idx, kyo))
                    EffectTrace.attach(ex, kyo, stack)
                    throw ex

        def clauseDispatch: Arrow[Outcome2[State, A < (E & S), B < S], B, S] =
            type OutT = Outcome2[State, A < (E & S), B < S]
            new Arrow.Step[OutT, B, S]:
                def frame                                                         = Frame.internal
                override def apply[D, S3](out: OutT < S3, cont2: Arrow[B, D, S3]) =
                    out match
                        case p: Pending[OutT, S3] @unchecked =>
                            Effect.defer(p, this, cont2)
                        case out: Continue2[State, A < (E & S)] @unchecked =>
                            cont2(Pending.handle[State, E, A, B, S](
                                out._2,
                                LoopStateHandler.this,
                                out._1
                            ))
                        case out =>
                            cont2(Nested.unnest[B < S](Loop.unnest(out.asInstanceOf[OutT])))
            end new
        end clauseDispatch

        def answers[X](
            state: State,
            input: I[X],
            k: Arrow[O[X], A, E & S],
            armed: Boolean,
            slot: Safepoint.Slot,
            frame: Frame
        ): Outcome2[State, A < (E & S), B < S] < S =
            var st = state
            try
                run(state, input) match
                    case c: Continue2[State, O[X] < (E & S)] @unchecked =>
                        st = c._1
                        val ans = c._2
                        ans match
                            case _: Pending[?, ?] =>
                                Loop.continue(st, Effect.defer(ans, k))
                            case _ =>
                                Loop.continue(st, k(Nested.unnest[O[X]](ans)))
                        end match
                    case o2 =>
                        if o2.isInstanceOf[Pending[?, ?]] then attachReentry2[State, I, O, E, A, B, S, X](k)(o2)
                        else o2.asInstanceOf[Outcome2[State, A < (E & S), B < S] < S]
                end match
            catch
                case ex: Throwable =>
                    val at = st
                    EffectTrace.attach(ex, k, frame)
                    Loop.continue(at, Effect.deferInline[A, E & S](throw ex)(using frame))
            end try
        end answers
    end LoopStateHandler

    abstract class ContextHandler[State, E <: ContextEffect[State], A, -S] extends Handler[E, A, S]:
        def derive(outer: Maybe[State]): State
        def fork(parent: State): State
        def join(parent: State, forked: State, child: State): State
        def release(state: State, failure: Maybe[Throwable]): Unit

        /** Called when the region's extent runs to a clean end, whether in place or under a handler that resumed the remainder holding it.
          * It records the clean end but does not fire; [[release]], run here or by the scope that holds the region, fires and reads what was
          * recorded to tell a clean ending apart from a drop.
          */
        def complete(state: State): Unit = ()

        /** Called when a region is reinstalled by a resumed remainder. A region whose release has already run refuses here (a bracket
          * resumed after its resource was released is a use-after-release), by throwing.
          */
        def reenter(state: State): Unit = ()
    end ContextHandler

    /** The handler a re-entered region runs under: `outer` with `onDone` as identity, so the region a resumption re-enters yields the body's
      * value and `outer`'s `onDone` still runs once, at the outer region's end.
      *
      * It repeats, as `outer` does: a continuation captured inside a re-entered region is resumed by the same clause, more than once, so what
      * that region owes (a bracket captured in the continuation, say) must be held across every application and released when the
      * re-entered region ends, once the last of them has run.
      */
    def reentered[I[_], O[_], E <: ArrowEffect[I, O], A, B, S](
        outer: ContHandler[I, O, E, A, B, S]
    ): ContHandler[I, O, E, A, A, S] =
        new ContHandler[I, O, E, A, A, S]:
            def tag                                              = outer.tag
            def run[X](input: I[X], next: Arrow[O[X], A, E & S]) = outer.run(input, next)
            def onDone(state: Unit, v: A)                        = v
            override def repeated                                = true

    /** Wraps the continuation a clause may resume more than once, so that each application re-enters the region, through [[reentered]].
      *
      * Entering a region stores the loop's registers as that region's continuation, which keeps the clause's own pending work out of what a
      * later occurrence captures. Without that, the continuation captured at a later occurrence carries the enclosing clause's next
      * resumption, and every inner resumption re-triggers it, without bound. A computation handed to the wrapped continuation runs at the
      * clause's level first, as it does for a crossing; only the settled answer re-enters.
      */
    def reentering[I[_], O[_], E <: ArrowEffect[I, O], A, S, X0](
        k: Arrow[O[X0], A, E & S],
        reentered: ContHandler[I, O, E, A, A, S]
    ): Arrow[O[X0], A, E & S] =
        new Arrow.Step[O[X0], A, E & S]:
            def frame                                                        = Frame.internal
            override def apply[D, S3](v: O[X0] < S3, cont2: Arrow[A, D, S3]) =
                v match
                    case p: Pending[O[X0], S3] @unchecked => Effect.defer(p, this, cont2)
                    case _ => cont2(Pending.handle[Unit, E, A, A, S](k(Nested.unnest[O[X0]](v)), reentered, ()), Arrow.id)
        end new
    end reentering

    /** The caller must pass the cont of the operation whose answer this outcome carries. That obligation is why the attachment is here rather
      * than where the region is rebuilt: a walk that fuses across a run of operations answers a different one each turn, and only the walk
      * knows which. Applying it to an outcome that already carries a cont would apply two.
      */
    def attachReentry[I[_], O[_], E <: ArrowEffect[I, O], A, B, S, X0](
        reentry: Arrow[O[X0], A, E & S]
    ): Arrow[Outcome[O[X0] < (E & S), B < S], Outcome[A < (E & S), B < S], S] =
        type In  = Outcome[O[X0] < (E & S), B < S]
        type Out = Outcome[A < (E & S), B < S]
        new Arrow.Step[In, Out, S]:
            def frame                                                         = Frame.internal
            override def apply[D, S3](out: In < S3, cont2: Arrow[Out, D, S3]) =
                out match
                    case p: Pending[In, S3] @unchecked =>
                        Effect.defer(p, this, cont2)
                    case out: Continue[O[X0] < (E & S)] @unchecked =>
                        cont2(Loop.continue[A < (E & S), B < S, S](reentry(out._1)))
                    case out =>
                        cont2(out.asInstanceOf[Out < S])
        end new
    end attachReentry

    def attachReentry2[State, I[_], O[_], E <: ArrowEffect[I, O], A, B, S, X0](
        reentry: Arrow[O[X0], A, E & S]
    ): Arrow[Outcome2[State, O[X0] < (E & S), B < S], Outcome2[State, A < (E & S), B < S], S] =
        type In  = Outcome2[State, O[X0] < (E & S), B < S]
        type Out = Outcome2[State, A < (E & S), B < S]
        new Arrow.Step[In, Out, S]:
            def frame                                                         = Frame.internal
            override def apply[D, S3](out: In < S3, cont2: Arrow[Out, D, S3]) =
                out match
                    case p: Pending[In, S3] @unchecked =>
                        Effect.defer(p, this, cont2)
                    case out: Continue2[State, O[X0] < (E & S)] @unchecked =>
                        cont2(Loop.continue[State, A < (E & S), B < S](out._1, reentry(out._2)))
                    case out =>
                        cont2(out.asInstanceOf[Out < S])
        end new
    end attachReentry2

    inline def answersLoop[I[_], O[_], E <: ArrowEffect[I, O], A, B, S, C](
        inline effectTag: Tag[E],
        inline handle: [X] => I[X] => Outcome[O[X] < (E & S), B < S] < S,
        _frame: Frame,
        input0: I[C],
        k0: Arrow[O[C], A, E & S],
        armed: Boolean,
        slot: Safepoint.Slot
    ): Outcome[A < (E & S), B < S] < S =
        var in: Any                                 = input0
        var k: Arrow[Any, Any, Any]                 = k0.asInstanceOf[Arrow[Any, Any, Any]]
        var result: Outcome[A < (E & S), B < S] < S = null.asInstanceOf[Outcome[A < (E & S), B < S] < S]
        var running                                 = true
        while running do
            try
                handle[C](in.asInstanceOf[I[C]]) match
                    case c: Continue[O[C] < (E & S)] @unchecked =>
                        val ans = c._1
                        ans match
                            case _: Pending[?, ?] =>
                                result = Loop.continue(Effect.defer(ans, k).asInstanceOf[A < (E & S)])
                                running = false
                            case _ =>
                                val next = k(Nested.unnest[Any](ans))
                                if armed && Safepoint.stopped(slot) then
                                    result = Loop.continue(Effect.defer(next, Arrow.id[Any]).asInstanceOf[A < (E & S)])
                                    running = false
                                else
                                    next match
                                        case sN: Pending.SuspendArrow[?, ?, ?, ?, ?, ?] @unchecked
                                            if sN.tag.erased =:= effectTag.erased =>
                                            in = sN.input
                                            k = sN.cont.asInstanceOf[Arrow[Any, Any, Any]]
                                        case dN: Pending.Defer[Any, Any, Any, Any] @unchecked =>
                                            val v0      = dN.value
                                            var matched = false
                                            v0 match
                                                case sN: Pending.SuspendArrow[?, ?, ?, ?, ?, ?] @unchecked
                                                    if sN.tag.erased =:= effectTag.erased && dN.contB.isInstanceOf[Arrow.Id[?]] =>
                                                    val sc = sN.cont.asInstanceOf[Arrow[Any, Any, Any]]
                                                    val ca = dN.contA.asInstanceOf[Arrow[Any, Any, Any]]
                                                    if sc.isInstanceOf[Arrow.Id[?]] then
                                                        in = sN.input
                                                        k = ca
                                                        matched = true
                                                    else if ca.isInstanceOf[Arrow.Id[?]] then
                                                        in = sN.input
                                                        k = sc
                                                        matched = true
                                                    end if
                                                case _ => ()
                                            end match
                                            if !matched then
                                                result = Loop.continue(Effect.defer(v0, dN.contA, dN.contB).asInstanceOf[A < (E & S)])
                                                running = false
                                        case _ =>
                                            result = Loop.continue(next.asInstanceOf[A < (E & S)])
                                            running = false
                                    end match
                                end if
                        end match
                    case o =>
                        result =
                            if o.isInstanceOf[Pending[?, ?]] then
                                attachReentry[I, O, E, A, B, S, C](k.asInstanceOf[Arrow[O[C], A, E & S]])(o)
                            else o.asInstanceOf[Outcome[A < (E & S), B < S] < S]
                        running = false
                end match
            catch
                case ex: Throwable =>
                    EffectTrace.attach(ex, k, _frame)
                    result = Loop.continue(Effect.deferInline[A, E & S](throw ex)(using _frame))
                    running = false
        end while
        result
    end answersLoop

    inline def answersLoopState[State, I[_], O[_], E <: ArrowEffect[I, O], A, B, S, C](
        inline effectTag: Tag[E],
        inline handle: [X] => (State, I[X]) => Outcome2[State, O[X] < (E & S), B < (S)] < S,
        _frame: Frame,
        state0: State,
        input0: I[C],
        k0: Arrow[O[C], A, E & S],
        armed: Boolean,
        slot: Safepoint.Slot
    ): Outcome2[State, A < (E & S), B < S] < S =
        var st: State                                       = state0
        var in: Any                                         = input0
        var k: Arrow[Any, Any, Any]                         = k0.asInstanceOf[Arrow[Any, Any, Any]]
        var result: Outcome2[State, A < (E & S), B < S] < S = null.asInstanceOf[Outcome2[State, A < (E & S), B < S] < S]
        var running                                         = true
        while running do
            try
                handle[C](st, in.asInstanceOf[I[C]]) match
                    case c: Continue2[State, O[C] < (E & S)] @unchecked =>
                        st = c._1
                        val ans = c._2
                        ans match
                            case _: Pending[?, ?] =>
                                result = Loop.continue(st, Effect.defer(ans, k).asInstanceOf[A < (E & S)])
                                running = false
                            case _ =>
                                val next = k(Nested.unnest[Any](ans))
                                if armed && Safepoint.stopped(slot) then
                                    result = Loop.continue(st, Effect.defer(next, Arrow.id[Any]).asInstanceOf[A < (E & S)])
                                    running = false
                                else
                                    next match
                                        case sN: Pending.SuspendArrow[?, ?, ?, ?, ?, ?] @unchecked
                                            if sN.tag.erased =:= effectTag.erased =>
                                            in = sN.input
                                            k = sN.cont.asInstanceOf[Arrow[Any, Any, Any]]
                                        case dN: Pending.Defer[Any, Any, Any, Any] @unchecked =>
                                            val v0      = dN.value
                                            var matched = false
                                            v0 match
                                                case sN: Pending.SuspendArrow[?, ?, ?, ?, ?, ?] @unchecked
                                                    if sN.tag.erased =:= effectTag.erased && dN.contB.isInstanceOf[Arrow.Id[?]] =>
                                                    val sc = sN.cont.asInstanceOf[Arrow[Any, Any, Any]]
                                                    val ca = dN.contA.asInstanceOf[Arrow[Any, Any, Any]]
                                                    if sc.isInstanceOf[Arrow.Id[?]] then
                                                        in = sN.input
                                                        k = ca
                                                        matched = true
                                                    else if ca.isInstanceOf[Arrow.Id[?]] then
                                                        in = sN.input
                                                        k = sc
                                                        matched = true
                                                    end if
                                                case _ => ()
                                            end match
                                            if !matched then
                                                result = Loop.continue(st, Effect.defer(v0, dN.contA, dN.contB).asInstanceOf[A < (E & S)])
                                                running = false
                                        case _ =>
                                            result = Loop.continue(st, next.asInstanceOf[A < (E & S)])
                                            running = false
                                    end match
                                end if
                        end match
                    case o2 =>
                        result =
                            if o2.isInstanceOf[Pending[?, ?]] then
                                attachReentry2[State, I, O, E, A, B, S, C](k.asInstanceOf[Arrow[O[C], A, E & S]])(o2)
                            else o2.asInstanceOf[Outcome2[State, A < (E & S), B < S] < S]
                        running = false
                end match
            catch
                case ex: Throwable =>
                    val at = st
                    EffectTrace.attach(ex, k, _frame)
                    result = Loop.continue(at, Effect.deferInline[A, E & S](throw ex)(using _frame))
                    running = false
        end while
        result
    end answersLoopState

end Handler

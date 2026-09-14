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
  * One instance per region entry, pushed onto the [[Stack]] and matched by [[tag]] when a suspension looks for who answers it. The subclasses
  * below are the answering shapes the public API offers; everything they have in common is here, and it is all information the evaluator
  * needs about a region rather than about the effect: whether the clause may resume more than once, whether it hands the continuation out,
  * and what the region contributes to the context.
  *
  * Those three are declared rather than inferred because the evaluator has to decide what to do with a region's obligations before the clause
  * has run, and by then it is too late to observe what the clause actually does.
  */
sealed abstract private[kernel] class Handler[E <: Effect, A, -S]:
    def tag: Tag[E]

@publicInBinary private[kernel] object Handler:

    sealed abstract class ArrowHandler[State, E <: Effect, A, B, -S] extends Handler[E, B, S]:
        def done(state: State, v: A): B < S
        def recover(state: State, ex: Throwable): Maybe[B < S] = Absent

    abstract class ContHandler[I[_], O[_], E <: ArrowEffect[I, O], A, B, S] extends ArrowHandler[Unit, E, A, B, S]:
        def run[X](input: I[X], cont: Arrow[O[X], A, E & S]): A < (E & S)

        /** Runs the clause, attaching the effect trace to anything it throws.
          *
          * The catch is here rather than around the evaluator's call so the suspension and the stack are still in hand: by the time a
          * throwable reaches the loop, the region it came from may already be off the stack.
          */
        private[kyo] def answering[X](input: I[X], cont: Arrow[O[X], A, E & S], kyo: Pending[?, ?], stack: Stack): A < (E & S) =
            try run(input, cont)
            catch
                case ex =>
                    EffectTrace.attach(ex, kyo, cont, stack)
                    throw ex
    end ContHandler

    abstract class MaskingHandler[E <: Effect, A, B, S] extends ArrowHandler[Unit, E, A, B, S]:
        def run[X](operation: X < E, next: Arrow[X, A, E & S]): A < (E & S)

        private[kyo] def answering[X](operation: X < E, next: Arrow[X, A, E & S], kyo: Pending[?, ?], stack: Stack): A < (E & S) =
            try run(operation, next)
            catch
                case ex =>
                    EffectTrace.attach(ex, kyo, next, stack)
                    throw ex
    end MaskingHandler

    /** The peel behind [[kyo.kernel.ArrowEffect.handleFirst]]: the clause answers the first operation and carries its continuation out as
      * the region's own result, so the remainder leaves the region as a value. It is the only escaping handler; the regions it dumped move
      * their releases to the scope below when it exits, and back to a holder that resumes the remainder.
      *
      * `run` answers at the region result `B`, unlike [[ContHandler]] whose answer re-enters the region: the peel does not continue inside
      * the region, it leaves it.
      */
    abstract class FirstHandler[I[_], O[_], E <: ArrowEffect[I, O], A, B, S] extends ArrowHandler[Unit, E, A, B, S]:
        def run[X](input: I[X], cont: Arrow[O[X], A, E & S]): B < (E & S)

        private[kyo] def answering[X](input: I[X], cont: Arrow[O[X], A, E & S], kyo: Pending[?, ?], stack: Stack): B < (E & S) =
            try run(input, cont)
            catch
                case ex =>
                    EffectTrace.attach(ex, kyo, cont, stack)
                    throw ex
    end FirstHandler

    abstract class LoopHandler[I[_], O[_], E <: ArrowEffect[I, O], A, B, S] extends ArrowHandler[Unit, E, A, B, S]:
        def run[X](input: I[X]): Outcome[O[X] < (E & S), B < S] < S

        private[kyo] def running[X](
            input: I[X],
            kyo: Pending.Suspend[?, ?, ?, ?],
            stack: Stack,
            idx: Int
        ): Outcome[O[X] < (E & S), B < S] < S =
            // The clause runs outside the regions between this one and the suspension, so a throw from it leaves
            // them for this region to answer for: dumping takes them off the stack and records the debt here,
            // discharged when this region itself unwinds.
            try run(input)
            catch
                case ex =>
                    discard(Eval.dumped(stack, idx, kyo))
                    EffectTrace.attach(ex, kyo, stack)
                    throw ex

        /** The arrow that reads a settled outcome and either re-enters the region with the answer or leaves with the result.
          *
          * A `Continue` rebuilds the region as a fresh `Handle` value over the answer, which is what makes resumption the same operation as
          * entry rather than a separate path through the evaluator. Anything else is the loop's result, already at the row outside, so it is
          * handed straight to the caller's continuation. An outcome that has not settled defers and comes back here.
          */
        private[kyo] def clauseDispatch: Arrow[Outcome[A < (E & S), B < S], B, S] =
            type OutT = Outcome[A < (E & S), B < S]
            new Arrow.Step[OutT, B, S]:
                def frame = Frame.internal
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

        /** Answers one occurrence for a region that is at the top of the stack, applying the continuation to the answer without leaving.
          *
          * This is the fused path, which is why it is separate from [[running]]: the region does not have to be exited and re-entered for an
          * occurrence it can answer in place, so the answer goes straight into `k`.
          *
          * A throwable is turned into a deferred re-raise carried by `Loop.continue` rather than thrown from here, so it reaches the
          * evaluator as an ordinary computation and unwinds through the regions the continuation reinstalls, rather than from wherever this
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
                                Loop.continue(ans.map(a => k(a))(using Frame.internal))
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

        private[kyo] def running[X](
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

        private[kyo] def clauseDispatch: Arrow[Outcome2[State, A < (E & S), B < S], B, S] =
            type OutT = Outcome2[State, A < (E & S), B < S]
            new Arrow.Step[OutT, B, S]:
                def frame = Frame.internal
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
                                Loop.continue(st, ans.map(a => k(a))(using Frame.internal))
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
    end ContextHandler

    /** Attaches a cont to an outcome whose clause has not settled yet, turning the clause's answer into the
      * region's remaining computation.
      *
      * The caller must pass the cont of the operation whose answer this outcome carries. That is the whole
      * obligation, and it is why the attachment happens here rather than where the region is rebuilt: a walk
      * that fuses across a run of operations answers a different one on each turn, and only the walk knows
      * which. Applying it to an outcome that already carries a cont would apply two.
      */
    private[kyo] def attachReentry[I[_], O[_], E <: ArrowEffect[I, O], A, B, S, X0](
        reentry: Arrow[O[X0], A, E & S]
    ): Arrow[Outcome[O[X0] < (E & S), B < S], Outcome[A < (E & S), B < S], S] =
        type In  = Outcome[O[X0] < (E & S), B < S]
        type Out = Outcome[A < (E & S), B < S]
        new Arrow.Step[In, Out, S]:
            def frame = Frame.internal
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

    /** [[attachReentry]] for a region that carries its state through the outcome. */
    private[kyo] def attachReentry2[State, I[_], O[_], E <: ArrowEffect[I, O], A, B, S, X0](
        reentry: Arrow[O[X0], A, E & S]
    ): Arrow[Outcome2[State, O[X0] < (E & S), B < S], Outcome2[State, A < (E & S), B < S], S] =
        type In  = Outcome2[State, O[X0] < (E & S), B < S]
        type Out = Outcome2[State, A < (E & S), B < S]
        new Arrow.Step[In, Out, S]:
            def frame = Frame.internal
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

    private[kyo] inline def answersLoop[I[_], O[_], E <: ArrowEffect[I, O], A, B, S, C](
        inline effectTag: Tag[E],
        inline handle: [X] => I[X] => Outcome[O[X] < (E & S), B < S] < S,
        _frame: Frame,
        input0: I[C],
        k0: Arrow[O[C], A, E & S],
        armed: Boolean,
        slot: Safepoint.Slot
    ): Outcome[A < (E & S), B < S] < S =
        // The walk fuses across operations of different types, so the input and cont in flight are erased.
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
                                result = Loop.continue(ans.map(a => k(a))(using _frame).asInstanceOf[A < (E & S)])
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

    private[kyo] inline def answersLoopState[State, I[_], O[_], E <: ArrowEffect[I, O], A, B, S, C](
        inline effectTag: Tag[E],
        inline handle: [X] => (State, I[X]) => Outcome2[State, O[X] < (E & S), B < (S)] < S,
        _frame: Frame,
        state0: State,
        input0: I[C],
        k0: Arrow[O[C], A, E & S],
        armed: Boolean,
        slot: Safepoint.Slot
    ): Outcome2[State, A < (E & S), B < S] < S =
        // The walk fuses across operations of different types, so the input and cont in flight are erased.
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
                                result = Loop.continue(st, ans.map(a => k(a))(using _frame).asInstanceOf[A < (E & S)])
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

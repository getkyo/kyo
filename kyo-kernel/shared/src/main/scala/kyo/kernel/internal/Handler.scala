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

sealed abstract private[kernel] class Handler[E <: Effect, A, -S]:
    def tag: Tag[E]

    /** Whether this handler's clause resumes the continuation it is given more than once.
      *
      * Answering a suspension dumps the regions between this handler and that suspension into the continuation. A
      * region that discharges exactly once, a bracket's release, cannot be left to answer for itself when the same
      * continuation can be resumed again: whichever resumption ends its extent first would fire the release under the
      * rest. Declaring this holds those regions, so an ending records its outcome and the debt is discharged once,
      * where this handler ends.
      */
    def repeated: Boolean = false

    /** Whether this handler's clause hands the continuation out of the clause as a value.
      *
      * Such a clause has not finished with what it owes when its answer settles: the remainder is still live in
      * whoever holds it. So the debt moves to the scope below rather than being discharged here, to be settled when
      * the remainder resumes and drained at that scope's exit if it never does.
      */
    def escaping: Boolean = false

    /** What entering this region contributes to the context, and what leaving it takes back.
      *
      * A context region binds its value. A masking region binds the marker that sends a read to the region
      * instead of answering it. Every other region is transparent to reads, which is the default here.
      *
      * The evaluator maps a region to a binding in five places (entry, exit, and the three that rebuild a
      * context from the stack), and each has to agree with the others or the two structures drift. Asking the
      * handler keeps that one answer in one place.
      */
    private[kernel] def bound(ctx: Context, state: Any): Context = ctx

    private[kernel] def unbound(ctx: Context): Context = ctx

    override def toString = s"Handler(${tag.show})"
end Handler

@publicInBinary private[kernel] object Handler:

    sealed abstract class ArrowHandler[State, E <: Effect, A, B, -S] extends Handler[E, B, S]:
        def done(state: State, v: A): B < S

        def recover(state: State, ex: Throwable): Maybe[B < S] = Absent
    end ArrowHandler

    abstract class ContHandler[I[_], O[_], E <: ArrowEffect[I, O], A, B, S] extends ArrowHandler[Unit, E, A, B, S]:
        def run[X](input: I[X], cont: Arrow[O[X], A, E & S]): A < (E & S)

        private[kyo] def answering[X](input: I[X], cont: Arrow[O[X], A, E & S], kyo: Pending[?, ?], stack: Stack): A < (E & S) =
            try run(input, cont)
            catch
                case ex =>
                    EffectTrace.attach(ex, kyo, cont, stack)
                    throw ex
    end ContHandler

    // A region that masks its tag: every request for it, of either kind, reaches this clause as the request
    // itself re-raised rather than as an input, and no handler or binding it shadows sees it. An arrow
    // operation arrives by the stack lookup; a context read arrives because entering this region masks the tag
    // in the context, so the read dispatches here instead of answering from the binding outside.
    abstract class MaskingHandler[E <: Effect, A, B, S] extends ArrowHandler[Unit, E, A, B, S]:
        def run[X](operation: X < E, next: Arrow[X, A, E & S]): A < (E & S)

        override private[kernel] def bound(ctx: Context, state: Any): Context = ctx.mask(tag)
        override private[kernel] def unbound(ctx: Context): Context           = ctx.unbind

        // TODO why isn't this in Eval?
        private[kyo] def answering[X](operation: X < E, next: Arrow[X, A, E & S], kyo: Pending[?, ?], stack: Stack): A < (E & S) =
            try run(operation, next)
            catch
                case ex =>
                    EffectTrace.attach(ex, kyo, next, stack)
                    throw ex
    end MaskingHandler

    // Not on main: a LoopHandler's clause answers with a single-state Outcome and the region carries no
    // state; a LoopStateHandler's answers with an Outcome2 carrying its state.
    abstract class LoopHandler[I[_], O[_], E <: ArrowEffect[I, O], A, B, S] extends ArrowHandler[Unit, E, A, B, S]:
        def run[X](input: I[X]): Outcome[O[X] < (E & S), B < S] < S

        // TODO why aren't these methods in Eval?
        private[kyo] def running[X](
            input: I[X],
            kyo: Pending.Suspend[?, ?, ?, ?],
            stack: Stack,
            idx: Int
        ): Outcome[O[X] < (E & S), B < S] < S =
            try run(input)
            catch
                case ex =>
                    discard(Eval.dumped(stack, idx, kyo))
                    EffectTrace.attach(ex, kyo, stack)
                    throw ex

        private[kyo] def clauseDispatch[X0](reentry: Arrow[O[X0], A, E & S]): Arrow[Outcome[O[X0] < (E & S), B < S], B, S] =
            type OutT = Outcome[O[X0] < (E & S), B < S]
            new Arrow.Step[OutT, B, S]:
                def frame = Frame.internal
                override def apply[D, S3](out: OutT < S3, cont2: Arrow[B, D, S3]) =
                    out match
                        case p: Pending[OutT, S3] @unchecked =>
                            Effect.defer(p, this, cont2)
                        case out: Continue[O[X0] < (E & S)] @unchecked =>
                            Pending.handle[Unit, E, A, B, S](
                                out._1.chain(reentry),
                                LoopHandler.this,
                                ()
                            ).chain(cont2)
                        case out =>
                            Nested.unnest[B < S](Loop.unnest(out.asInstanceOf[OutT])).chain(cont2)
            end new
        end clauseDispatch

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
                        o.asInstanceOf[Outcome[A < (E & S), B < S] < S]
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

        private[kyo] def clauseDispatch[X0](reentry: Arrow[O[X0], A, E & S]): Arrow[Outcome2[State, O[X0] < (E & S), B < S], B, S] =
            type OutT = Outcome2[State, O[X0] < (E & S), B < S]
            new Arrow.Step[OutT, B, S]:
                def frame = Frame.internal
                override def apply[D, S3](out: OutT < S3, cont2: Arrow[B, D, S3]) =
                    out match
                        case p: Pending[OutT, S3] @unchecked =>
                            Effect.defer(p, this, cont2)
                        case out: Continue2[State, O[X0] < (E & S)] @unchecked =>
                            Pending.handle[State, E, A, B, S](
                                out._2.chain(reentry),
                                LoopStateHandler.this,
                                out._1
                            ).chain(cont2)
                        case out =>
                            Nested.unnest[B < S](Loop.unnest(out.asInstanceOf[OutT])).chain(cont2)
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
                        o2.asInstanceOf[Outcome2[State, A < (E & S), B < S] < S]
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

        override private[kernel] def bound(ctx: Context, state: Any): Context = ctx.bind(tag, state.asInstanceOf[State])
        override private[kernel] def unbound(ctx: Context): Context           = ctx.unbind

        /** This region's extent ran to an end.
          *
          * No value, unlike [[Handler.ArrowHandler.done]]: the evaluator reaches this with whatever the stack it is
          * running in produced, and a crossing wraps a forked result, so the origin's `A` is not what arrives. Nothing
          * ever read it.
          */
        private[kyo] def done(state: State): Unit = ()

        /** Takes custody of this region because the handler that dumped it will resume the continuation again. */
        private[kyo] def borrow(state: State): Unit = ()

        /** Whether this region is under custody and must not be handed back its own answerability on resumption. */
        private[kyo] def defers(state: State): Boolean = false

        private[kyo] def reenter(state: State): Unit = ()

        private[kyo] def release(state: State, ex: Throwable): Unit = ()

        /** Discharges this region where its owner ends normally, as opposed to [[release]], which reports a failure that
          * unwound it. A held region records the outcome of each ending, so this is where that record is finally acted
          * on; `ex` is the discard signal, used only when nothing was ever recorded.
          */
        private[kyo] def discharge(state: State, ex: Throwable): Unit = release(state, ex)
    end ContextHandler

    // TODO can we move this to Eval?
    private[kyo] inline def answersLoop[I[_], O[_], E <: ArrowEffect[I, O], A, B, S, C](
        inline effectTag: Tag[E],
        inline handle: [X] => I[X] => Outcome[O[X] < (E & S), B < S] < S,
        _frame: Frame,
        input0: I[C],
        k0: Arrow[O[C], A, E & S],
        armed: Boolean,
        slot: Safepoint.Slot
    ): Outcome[A < (E & S), B < S] < S =
        // The walk fuses across operations of different types, so the input and the continuation
        // in flight are erased; the answer handed back is the region's A.
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
                        result = o.asInstanceOf[Outcome[A < (E & S), B < S] < S]
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
        // The walk fuses across operations of different types, so the input and the continuation
        // in flight are erased; the answer handed back is the region's A.
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
                        result = o2.asInstanceOf[Outcome2[State, A < (E & S), B < S] < S]
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

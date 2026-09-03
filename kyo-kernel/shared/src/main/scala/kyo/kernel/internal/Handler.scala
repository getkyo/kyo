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
import kyo.kernel.Loop.Continue2
import kyo.kernel.Loop.Outcome2
import scala.annotation.publicInBinary

sealed abstract private[kernel] class Handler[E <: Effect, A, -S]:
    def tag: Tag[E]

    override def toString = s"Handler(${tag.show})"
end Handler

@publicInBinary private[kernel] object Handler:

    sealed abstract class ArrowHandler[State, E <: Effect, A, B, -S] extends Handler[E, B, S]:
        def done(state: State, v: A): B < S

        def recover(state: State, ex: Throwable): Maybe[B < S] = Absent
    end ArrowHandler

    abstract class ContHandler[I[_], O[_], E <: ArrowEffect[I, O], A, B, S] extends ArrowHandler[Unit, E, A, B, S]:
        def run[X](input: I[X], cont: Arrow[O[X], A, E & S]): A < (E & S)

        // TODO why isn't this in Eval?
        private[kyo] def answering[X](input: I[X], cont: Arrow[O[X], A, E & S], kyo: Pending[?, ?], stack: Stack): A < (E & S) =
            try run(input, cont)
            catch
                case ex =>
                    EffectTrace.attach(ex, kyo, cont, stack)
                    throw ex
    end ContHandler

    abstract class ContOpHandler[E <: Effect, A, B, S] extends ArrowHandler[Unit, E, A, B, S]:
        def run[X](operation: X < E, next: Arrow[X, A, E & S]): A < (E & S)

        // TODO why isn't this in Eval?
        private[kyo] def answering[X](operation: X < E, next: Arrow[X, A, E & S], kyo: Pending[?, ?], stack: Stack): A < (E & S) =
            try run(operation, next)
            catch
                case ex =>
                    EffectTrace.attach(ex, kyo, next, stack)
                    throw ex
    end ContOpHandler

    abstract class LoopHandler[State, I[_], O[_], E <: ArrowEffect[I, O], A, B, S] extends ArrowHandler[State, E, A, B, S]:
        def run[X](state: State, input: I[X]): Outcome2[State, O[X] < (E & S), B < S] < S

        // TODO why aren't these methods in Eval?
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

        private[kyo] def clauseDispatch[X0](
            reentry: Arrow[O[X0], A, E & S]
        ): Arrow[Outcome2[State, O[X0] < (E & S), B < S], B, S] =
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
                                LoopHandler.this,
                                out._1
                            ).chain(cont2)
                        case out =>
                            Nested.unnest[B < S](Loop.unnest(out.asInstanceOf[OutT])).chain(cont2)
            end new
        end clauseDispatch

        def answers[X](
            state: State,
            input: I[X],
            k: Arrow[Any, Any, Any],
            armed: Boolean,
            slot: Safepoint.Slot,
            frame: Frame
        ): Outcome2[State, Any, B < S] < S =
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
                                Loop.continue(st, k(Nested.unnest[Any](ans)))
                        end match
                    case o2 =>
                        o2.asInstanceOf[Outcome2[State, Any, B < S] < S]
                end match
            catch
                case ex: Throwable =>
                    val at = st
                    EffectTrace.attach(ex, k, frame)
                    Loop.continue(at, Effect.deferInline(throw ex)(using frame))
            end try
        end answers
    end LoopHandler

    abstract class ContextHandler[State, E <: ContextEffect[State], A, -S] extends Handler[E, A, S]:
        def derive(outer: Maybe[State]): State
        def fork(parent: State): State
        def join(parent: State, forked: State, child: State): State

        private[kyo] def done(state: State): Unit = ()

        private[kyo] def reenter(state: State): Unit = ()

        private[kyo] def release(state: State, ex: Throwable): Unit = ()
    end ContextHandler

    // TODO can we move this to Eval?
    private[kyo] inline def answersLoopState[State, I[_], O[_], E <: ArrowEffect[I, O], A, B, S, C](
        inline effectTag: Tag[E],
        inline handle: [X] => (State, I[X]) => Outcome2[State, O[X] < (E & S), B < (S)] < S,
        _frame: Frame,
        state0: State,
        input0: I[C],
        k0: Arrow[Any, Any, Any],
        armed: Boolean,
        slot: Safepoint.Slot
    ): Outcome2[State, Any, B < S] < S =
        var st: State                               = state0
        var in: Any                                 = input0
        var k: Arrow[Any, Any, Any]                 = k0
        var result: Outcome2[State, Any, B < S] < S = null.asInstanceOf[Outcome2[State, Any, B < S] < S]
        var running                                 = true
        while running do
            try
                handle[C](st, in.asInstanceOf[I[C]]) match
                    case c: Continue2[State, O[C] < (E & S)] @unchecked =>
                        st = c._1
                        val ans = c._2
                        ans match
                            case _: Pending[?, ?] =>
                                result = Loop.continue(st, ans.map(a => k(a))(using _frame))
                                running = false
                            case _ =>
                                val next = k(Nested.unnest[Any](ans))
                                if armed && Safepoint.stopped(slot) then
                                    result = Loop.continue(st, Effect.defer(next, Arrow.id[Any]))
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
                                                result = Loop.continue(st, Effect.defer(v0, dN.contA, dN.contB))
                                                running = false
                                        case _ =>
                                            result = Loop.continue(st, next)
                                            running = false
                                    end match
                                end if
                        end match
                    case o2 =>
                        result = o2.asInstanceOf[Outcome2[State, Any, B < S] < S]
                        running = false
                end match
            catch
                case ex: Throwable =>
                    val at = st
                    EffectTrace.attach(ex, k, _frame)
                    result = Loop.continue(at, Effect.deferInline(throw ex)(using _frame))
                    running = false
        end while
        result
    end answersLoopState

end Handler

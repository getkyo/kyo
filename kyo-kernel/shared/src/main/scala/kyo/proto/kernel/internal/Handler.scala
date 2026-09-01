package kyo.proto.kernel.internal

import kyo.Frame
import kyo.Loop
import kyo.Maybe
import kyo.Maybe.Absent
import kyo.Result
import kyo.Tag
import kyo.proto.Arrow
import kyo.proto.Loop.Continue2
import kyo.proto.Loop.Outcome2
import kyo.proto.kernel.<
import kyo.proto.kernel.ArrowEffect
import kyo.proto.kernel.ContextEffect
import kyo.proto.kernel.Effect
import scala.annotation.publicInBinary

abstract private[kernel] class Handler[E <: Effect, A, B, -S, State]:
    def tag: Tag[E]

    def recover(state: State, ex: Throwable): Maybe[B < S] = Absent

    private[kyo] def release(state: State, ex: Throwable): Any < Any = ()
    def done(state: State, v: A): B < S
    override def toString = s"Handler(${tag.show})"
end Handler

@publicInBinary private[kernel] object Handler:

    abstract class HandlerCont[I[_], O[_], E <: ArrowEffect[I, O], A, B, S] extends Handler[E, A, B, S, Unit]:
        def run[X](input: I[X], cont: Arrow[O[X], A, E & S]): A < (E & S)

    abstract class HandlerContOp[E <: Effect, A, B, S] extends Handler[E, A, B, S, Unit]:
        def run[X](operation: X < E, next: Arrow[X, A, E & S]): A < (E & S)

    abstract class HandlerLoop[I[_], O[_], E <: ArrowEffect[I, O], A, B, S, State] extends Handler[E, A, B, S, State]:
        def run[X](state: State, input: I[X]): Outcome2[State, O[X] < (E & S), B < S] < S

        def answers[X](
            state: State,
            input: I[X],
            k: Arrow[Any, Any, Any],
            armed: Boolean,
            slot: Safepoint.Slot
        ): Outcome2[State, Any, B < S] < S =
            var st = state
            try
                run(state, input) match
                    case c: Continue2[State, O[X] < (E & S)] @unchecked =>
                        st = c._1
                        val ans = c._2
                        ans match
                            case _: Pending[?, ?] =>
                                kyo.proto.Loop.continue(st, ans.map(a => k(a))(using Frame.internal))
                            case _ =>
                                kyo.proto.Loop.continue(st, k(Nested.unnest[Any](ans)))
                        end match
                    case o2 =>
                        o2.asInstanceOf[Outcome2[State, Any, B < S] < S]
                end match
            catch
                case ex: Throwable =>
                    val at = st
                    kyo.proto.Loop.continue(at, Effect.deferInline(throw ex)(using Frame.internal))
            end try
        end answers
    end HandlerLoop

    abstract class HandlerContext[State, E <: ContextEffect[State], A, B, S] extends Handler[E, A, B, S, State]:
        def derive(current: Maybe[State]): State
        def fork(current: State): State < S
        def join(current: State, forked: State, result: Result[Nothing, State]): Result[Nothing, State] < S
    end HandlerContext

    private[kyo] type AnyK[X] = Any

    private[kyo] inline def answersLoopState[I[_], O[_], E <: ArrowEffect[I, O], A, B, S, State, C](
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
                                result = kyo.proto.Loop.continue(st, ans.map(a => k(a))(using _frame))
                                running = false
                            case _ =>
                                val next = k(Nested.unnest[Any](ans))
                                if armed && Safepoint.stopped(slot) then
                                    result = kyo.proto.Loop.continue(st, Effect.defer(next, Arrow.id[Any]))
                                    running = false
                                else
                                    next match
                                        case sN: Kyo.SuspendArrow[AnyK, AnyK, Nothing, Any, Any, Any] @unchecked
                                            if sN.tag.erased =:= effectTag.erased =>
                                            in = sN.input
                                            k = sN.cont.asInstanceOf[Arrow[Any, Any, Any]]
                                        case dN: Kyo.Defer[Any, Any, Any, Any] @unchecked =>
                                            val v0      = dN.value
                                            var matched = false
                                            v0 match
                                                case sN: Kyo.SuspendArrow[AnyK, AnyK, Nothing, Any, Any, Any] @unchecked
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
                                                result = kyo.proto.Loop.continue(st, Effect.defer(v0, dN.contA, dN.contB))
                                                running = false
                                        case _ =>
                                            result = kyo.proto.Loop.continue(st, next)
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
                    result = kyo.proto.Loop.continue(at, Effect.deferInline(throw ex)(using _frame))
                    running = false
        end while
        result
    end answersLoopState

end Handler

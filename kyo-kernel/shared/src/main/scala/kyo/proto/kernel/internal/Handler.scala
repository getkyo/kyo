package kyo.proto.kernel.internal

import kyo.Frame
import kyo.Loop
import kyo.Maybe
import kyo.Maybe.Absent
import kyo.Result
import kyo.Tag
import kyo.proto.Arrow
import kyo.proto.Loop.Outcome2
import kyo.proto.kernel.<
import kyo.proto.kernel.ArrowEffect
import kyo.proto.kernel.ContextEffect
import kyo.proto.kernel.Effect

abstract private[kernel] class Handler[E <: Effect, A, B, -S, State]:
    def tag: Tag[E]

    def recover(state: State, ex: Throwable): Maybe[B < S] = Absent

    private[kyo] def release(state: State, ex: Throwable): Any < Any = ()
    def done(state: State, v: A): B < S
    override def toString = s"Handler(${tag.show})"
end Handler

private[kernel] object Handler:

    abstract class HandlerCont[I[_], O[_], E <: ArrowEffect[I, O], A, B, S] extends Handler[E, A, B, S, Unit]:
        def run[X](input: I[X], cont: Arrow[O[X], A, E & S]): A < (E & S)

    abstract class HandlerContOp[E <: Effect, A, B, S] extends Handler[E, A, B, S, Unit]:
        def run[X](operation: X < E, next: Arrow[X, A, E & S]): A < (E & S)

    abstract class HandlerLoop[I[_], O[_], E <: ArrowEffect[I, O], A, B, S, State] extends Handler[E, A, B, S, State]:
        def run[X](state: State, input: I[X]): Outcome2[State, O[X] < (E & S), B < S] < S

    abstract class HandlerContext[State, E <: ContextEffect[State], A, B, S] extends Handler[E, A, B, S, State]:
        def derive(current: Maybe[State]): State
        def fork(current: State): State < S
        def join(current: State, forked: State, result: Result[Nothing, State]): Result[Nothing, State] < S
    end HandlerContext

end Handler

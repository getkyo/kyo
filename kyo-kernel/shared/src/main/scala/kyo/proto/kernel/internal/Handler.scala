package kyo.proto.kernel.internal

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

abstract class Handler[E <: Effect, A, B, -S, State]:
    def tag: Tag[E]

    /** Consulted when a NonFatal throw unwinds the region's extent, with the state the region was installed with. A Present computation
      * replaces the region's outcome, and what follows the extent still follows; Absent declines, and the failure keeps unwinding through
      * the enclosing regions.
      */
    def recover(state: State, ex: Throwable): Maybe[B < S] = Absent
    def done(state: State, v: A): B < S
    override def toString = s"Handler(${tag.show})"
end Handler

object Handler:

    abstract class HandlerCont[I[_], O[_], E <: ArrowEffect[I, O], A, B, S] extends Handler[E, A, B, S, Unit]:
        def run[X, C, S2](input: I[X], cont: Arrow[O[X], A, E & S], k: Arrow[A, C, S2]): C < (E & S & S2)
        def answer[X](input: I[X], next: Arrow[O[X], A, E & S]): A < (E & S) =
            run(input, next, Arrow.id)
    end HandlerCont

    abstract class HandlerLoop[I[_], O[_], E <: ArrowEffect[I, O], A, B, S, State] extends Handler[E, A, B, S, State]:
        def run[X](state: State, input: I[X]): Outcome2[State, O[X] < (E & S), B < S] < S
        def answer[X](state: State, input: I[X], next: Arrow[O[X], A, E & S]): Outcome2[State, O[X] < (E & S), B < S] =
            Eval.answerLoop(this, run(state, input), next)
    end HandlerLoop

    abstract class HandlerContext[State, E <: ContextEffect[State], A, B, S] extends Handler[E, A, B, S, State]:
        /** What this binding installs, given what the enclosing scope binds for its tag, Absent when nothing is bound. A binding resolves
          * at installation, so a re-installed region resolves again from wherever it stands.
          */
        def resolve(outer: Maybe[State]): State
        def fork(current: State): State < S
        def join(current: State, forked: State, result: Result[Nothing, State]): Result[Nothing, State] < S
    end HandlerContext

end Handler

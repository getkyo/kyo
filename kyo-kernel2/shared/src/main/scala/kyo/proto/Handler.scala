package kyo.proto

import Loop.Outcome
import Loop.Outcome2
import kyo.Tag

sealed abstract class Handler[E <: ArrowEffect[?, ?], A, B, -S] extends Arrow.Transform[A, B, S]:
    def tag: Tag[E]
    def apply[C, S2](v: A < S2, next: Arrow[B, C, S2]): C < (S & S2) =
        v.lower(
            pending = Kyo.Defer(_, this, next),
            done = b =>
                val slot = Safepoint.get()
                if !Safepoint.enter(slot) then
                    Kyo.Defer(v, this, next)
                else
                    val out = next.head(apply(b), next.tail)
                    Safepoint.exit(slot)
                    out
                end if
        )
end Handler

object Handler:

    abstract class HandlerCont[I[_], O[_], E <: ArrowEffect[I, O], A, B, S] extends Handler[E, A, B, S]:
        def run[X](input: I[X], cont: O[X] => A < (E & S)): A < (E & S)
        def apply(v: A): B < S

    abstract class HandlerLoop[I[_], O[_], E <: ArrowEffect[I, O], A, B, S] extends Handler[E, A, B, S]:
        def run[X](input: I[X]): Outcome[O[X] < (E & S), B] < S
        def apply(v: A): B < S

    abstract class HandlerLoopState[I[_], O[_], E <: ArrowEffect[I, O], A, B, S, State] extends Handler[E, A, B, S]:
        def initialState: State
        def run[X](state: State, input: I[X]): Outcome2[State, O[X] < (E & S), B] < S
        def apply(state: State, v: A): B < S
    end HandlerLoopState

end Handler

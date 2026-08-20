package kyo.kernel.internal

import kyo.Arrow
import kyo.Loop.Outcome
import kyo.Loop.Outcome2
import kyo.Tag
import kyo.kernel.*

sealed abstract private[kyo] class Handler[E <: ArrowEffect[?, ?], A, B, -S] extends Arrow.Transform[A, B, S]:
    def tag: Tag[E]
    def apply[C, S2](v: A < S2, next: Arrow[B, C, S2]): C < (S & S2) =
        v match
            case kyo: Kyo[A, S2] @unchecked =>
                Effect.defer(kyo, this, next)
            case _ =>
                val slot = Safepoint.get()
                if !Safepoint.enter(slot) then
                    Effect.defer(v, this, next)
                else
                    val out = next.head(apply(v.unsafeGet), next.tail)
                    Safepoint.exit(slot)
                    out
                end if
end Handler

private[kyo] object Handler:

    abstract class HandlerCont[I[_], O[_], E <: ArrowEffect[I, O], A, B, S] extends Handler[E, A, B, S]:
        def run[X](input: I[X], cont: O[X] => A < (E & S)): A < (E & S)

    abstract class HandlerLoop[I[_], O[_], E <: ArrowEffect[I, O], A, B, S] extends Handler[E, A, B, S]:
        def run[X](input: I[X]): Outcome[O[X] < (E & S), B] < S

    abstract class HandlerLoopState[I[_], O[_], E <: ArrowEffect[I, O], A, B, S, State] extends Handler[E, A, B, S]:
        def initialState: State
        def run[X](state: State, input: I[X]): Outcome2[State, O[X] < (E & S), B] < S
        def apply(state: State, v: A): B < S
        final override def apply(v: A): B < S = apply(initialState, v)
    end HandlerLoopState

    object HandlerLoopState:
        def apply[I[_], O[_], E <: ArrowEffect[I, O], A, B, S, State](
            h: HandlerLoopState[I, O, E, A, B, S, State],
            state: State
        ): HandlerLoopState[I, O, E, A, B, S, State] =
            new HandlerLoopState[I, O, E, A, B, S, State]:
                def frame                             = h.frame
                def tag                               = h.tag
                def initialState                      = state
                def run[X](state: State, input: I[X]) = h.run(state, input)
                def apply(state: State, v: A)         = h.apply(state, v)
    end HandlerLoopState

end Handler

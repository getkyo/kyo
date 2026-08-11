package kyo.kernel.internal

import kyo.Tag
import kyo.kernel.*
import kyo.kernel.Loop.Outcome
import kyo.kernel.Loop.Outcome2

sealed abstract class Handler[I[_], O[_], E <: ArrowEffect[I, O], A, -S](val tag: Tag[E])

object Handler:

    abstract class Cont[I[_], O[_], E <: ArrowEffect[I, O], A, S](tag: Tag[E]) extends Handler[I, O, E, A, S](tag):
        def apply[X](input: I[X], cont: O[X] => A < (E & S)): A < (E & S)

    abstract class Loop[I[_], O[_], E <: ArrowEffect[I, O], A, S](tag: Tag[E]) extends Handler[I, O, E, A, S](tag):
        def apply[X](input: I[X]): Outcome[O[X] < (E & S), A] < S

    abstract class LoopState[I[_], O[_], E <: ArrowEffect[I, O], A, S, State](
        tag: Tag[E],
        val state: State
    ) extends Handler[I, O, E, A, S](tag):

        def apply[X](input: I[X], state: State): Outcome2[State, O[X] < (E & S), A] < S

        // the handling logic lives in the original instance and successors
        // delegate to it directly, so state updates never stack delegation
        private[kyo] def origin: LoopState[I, O, E, A, S, State] = this

        final def withState(state: State): LoopState[I, O, E, A, S, State] =
            val o = origin
            new LoopState[I, O, E, A, S, State](tag, state):
                override private[kyo] def origin        = o
                def apply[X](input: I[X], state: State) = o(input, state)
        end withState

    end LoopState

end Handler

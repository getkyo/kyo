package kyo.kernel

import kyo.Tag

sealed abstract class Handler[I[_], O[_], E <: ArrowEffect[I, O], A, -S](val tag: Tag[E])

object Handler:

    final class Cont[I[_], O[_], E <: ArrowEffect[I, O], A, S](
        tag: Tag[E],
        val clause: [X] => (I[X], O[X] => A < (E & S)) => A < (E & S)
    ) extends Handler[I, O, E, A, S](tag)

    final class Loop[I[_], O[_], E <: ArrowEffect[I, O], A, S](
        tag: Tag[E],
        val clause: [X] => I[X] => kyo.kernel.Loop.Outcome[O[X] < (E & S), A] < S
    ) extends Handler[I, O, E, A, S](tag)

    final class LoopState[I[_], O[_], E <: ArrowEffect[I, O], A, S, State](
        tag: Tag[E],
        val state: State,
        val clause: [X] => (I[X], State) => kyo.kernel.Loop.Outcome2[State, O[X] < (E & S), A] < S
    ) extends Handler[I, O, E, A, S](tag)

end Handler

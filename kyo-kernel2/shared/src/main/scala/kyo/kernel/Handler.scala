package kyo.kernel

import kyo.Tag

sealed abstract class Handler[I[_], O[_], E <: ArrowEffect[I, O]](val tag: Tag[E])

object Handler:

    abstract class Cont[I[_], O[_], E <: ArrowEffect[I, O], A, S](tag: Tag[E]) extends Handler[I, O, E](tag):
        def apply[X](input: I[X], cont: O[X] => A < (E & S)): A < (E & S)

    abstract class Loop[I[_], O[_], E <: ArrowEffect[I, O], A, S](tag: Tag[E]) extends Handler[I, O, E](tag):
        def apply[X](input: I[X]): kyo.kernel.Loop.Outcome[O[X] < (E & S), A] < S

    abstract class LoopState[I[_], O[_], E <: ArrowEffect[I, O], A, S](tag: Tag[E]) extends Handler[I, O, E](tag):
        def apply[X](input: I[X]): kyo.kernel.Loop.Outcome2[LoopState[I, O, E, A, S], O[X] < (E & S), A] < S

end Handler

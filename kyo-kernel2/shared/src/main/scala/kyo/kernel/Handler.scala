package kyo.kernel

import kyo.Tag

sealed abstract class Handler[I[_], O[_], E <: ArrowEffect[I, O]](val tag: Tag[E])

object Handler:

    abstract class Cont[I[_], O[_], E <: ArrowEffect[I, O], A, S](tag: Tag[E]) extends Handler[I, O, E](tag):
        def apply[X](input: I[X], cont: O[X] => A < (E & S)): A < (E & S)

    abstract class Resume[I[_], O[_], E <: ArrowEffect[I, O], S](tag: Tag[E]) extends Handler[I, O, E](tag):
        def apply[X](input: I[X]): O[X] < S

    abstract class Stop[I[_], O[_], E <: ArrowEffect[I, O], A, S](tag: Tag[E]) extends Handler[I, O, E](tag):
        def apply[X](input: I[X]): A < (E & S)

    abstract class Loop[I[_], O[_], E <: ArrowEffect[I, O], A, S, State](tag: Tag[E]) extends Handler[I, O, E](tag):
        def apply[X](input: I[X], state: State, cont: O[X] => A < (E & S)): (State, A < (E & S))

end Handler

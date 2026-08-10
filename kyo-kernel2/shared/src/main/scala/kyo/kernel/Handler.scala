package kyo.kernel

import kyo.Tag

sealed abstract class Handler[I[_], O[_], E <: ArrowEffect[I, O]](val tag: Tag[E])

object Handler:

    abstract class Resume[I[_], O[_], E <: ArrowEffect[I, O], S](tag: Tag[E]) extends Handler[I, O, E](tag):
        def apply[X](input: I[X]): O[X] < S

    final class Stop[I[_], O[_], E <: ArrowEffect[I, O]](tag: Tag[E]) extends Handler[I, O, E](tag)

end Handler

package kyo.kernel.internal

import kyo.Arrow
import kyo.Frame
import kyo.Loop.Outcome
import kyo.Loop.Outcome2
import kyo.Span
import kyo.Tag
import kyo.kernel.*

sealed abstract class Kyo[+A, -S]

object Kyo:

    abstract class Continue[A, +B, -S] extends Kyo[B, S]:
        def value: A < S
        def cont: Arrow[A, B, S]

    class Park[+A, +B, -S](
        val entries: Span[Arrow[?, ?, ?]],
        val tags: Span[AnyRef],
        val states: Span[AnyRef],
        val value: A < S
    ) extends Kyo[B, S]

    abstract class Suspend[I[_], O[_], E <: ArrowEffect[I, O], A, B, S] extends Kyo[B, E & S]:
        def frame: Frame
        def tag: Tag[E]
        def input: I[A]
        def cont: Arrow[O[A], B, S]
    end Suspend

    abstract class Handle[E <: ArrowEffect[?, ?], A, B, +C, -S] extends Kyo[C, S]:
        def v: Arrow[Any, A, E & S]
        def handler: Handler[E, A, B, S]
        def cont: Arrow[B, C, S]
    end Handle

    sealed abstract class Handler[E <: ArrowEffect[?, ?], A, +B, -S]:
        def tag: Tag[E]

    object Handler:

        abstract class HandleCont[I[_], O[_], E <: ArrowEffect[I, O], A, B, S] extends Handler[E, A, B, S]:
            def run[X](input: I[X], cont: O[X] => A < (E & S)): A < (E & S)
            def complete(v: A): B < S

        abstract class HandleLoop[I[_], O[_], E <: ArrowEffect[I, O], A, B, S] extends Handler[E, A, B, S]:
            def run[X](input: I[X]): Outcome[O[X] < (E & S), B] < S
            def complete(v: A): B < S

        abstract class HandleLoopState[I[_], O[_], E <: ArrowEffect[I, O], A, B, S, State] extends Handler[E, A, B, S]:
            def initialState: State
            def run[X](state: State, input: I[X]): Outcome2[State, O[X] < (E & S), B] < S
            def complete(state: State, v: A): B < S
        end HandleLoopState

    end Handler

end Kyo

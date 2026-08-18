package kyo.proto

import kyo.Frame
import kyo.Loop.Outcome
import kyo.Loop.Outcome2
import kyo.Span
import kyo.Tag

sealed abstract class Kyo[+A, -S]

object Kyo:

    /** `value` then `contA` then `contB`. The value is currency, pending or settled: a pending input deferred behind a transform, and a
      * strict step past the safepoint budget, are the same node.
      */
    abstract class Defer[A, B, +C, -S] extends Kyo[C, S]:
        def value: A < S
        def contA: Arrow[A, B, S]
        def contB: Arrow[B, C, S]
    end Defer

    object Defer:

        def apply[A, B, S](
            _value: A < S,
            _contA: Arrow[A, B, S]
        ): Kyo[B, S] =
            new Defer[A, B, B, S]:
                def value = _value
                def contA = _contA
                def contB = Arrow.id[B]

        def apply[A, B, C, S](
            _value: A < S,
            _contA: Arrow[A, B, S],
            _contB: Arrow[B, C, S]
        ): Kyo[C, S] =
            new Defer[A, B, C, S]:
                def value = _value
                def contA = _contA
                def contB = _contB
    end Defer

    /** A parked stack segment, in stack order, and the computation to run once it is back: beside a region's continuation its handler,
      * and beside that its state; both null for a plain continuation.
      */
    class Park[+A, +B, -S](
        val entries: Span[Arrow[?, ?, ?]],
        val handlers: Span[Handler[?, ?, ?, ?]],
        val states: Span[Any],
        val value: A < S
    ) extends Kyo[B, S]

    abstract class Suspend[I[_], O[_], E <: ArrowEffect[I, O], A, B, S] extends Kyo[B, E & S]:
        def frame: Frame
        def tag: Tag[E]
        def input: I[A]
        def cont: Arrow[O[A], B, S]
    end Suspend

    abstract class Handle[E <: ArrowEffect[?, ?], A, B, +C, -S] extends Kyo[C, S]:
        def v: Kyo[A, E & S]
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

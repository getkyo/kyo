package kyo.proto

import kyo.Frame
import kyo.Loop
import kyo.Tag
import scala.annotation.nowarn

abstract class ArrowEffect[I[_], O[_]]

object ArrowEffect:

    @nowarn("msg=anonymous")
    inline def suspend[C](
        using inline _frame: Frame
    )[I[_], O[_], E <: ArrowEffect[I, O]](
        inline effectTag: Tag[E],
        inline effectInput: I[C]
    ): O[C] < E =
        new Kyo.Suspend[I, O, E, C, O[C], Any]:
            def frame = _frame
            def tag   = effectTag
            def input = effectInput
            def cont  = Arrow.id[O[C]]

    @nowarn("msg=anonymous")
    inline def suspendWith[C](
        using inline _frame: Frame
    )[I[_], O[_], E <: ArrowEffect[I, O], B, S](
        inline effectTag: Tag[E],
        inline effectInput: I[C]
    )(
        inline f: O[C] => B < S
    ): B < (E & S) =
        new Kyo.Suspend[I, O, E, C, B, S]:
            def frame = _frame
            def tag   = effectTag
            def input = effectInput
            val cont  = Arrow.Transform(f)
    end suspendWith

    @nowarn("msg=anonymous")
    inline def handleCont[I[_], O[_], E <: ArrowEffect[I, O], A, B, S](
        inline effectTag: Tag[E],
        v: A < (E & S)
    )(
        inline handle: [C] => (I[C], O[C] => A < (E & S)) => A < (E & S),
        inline done: A => B < S
    ): B < S =
        v.lower[B < S](
            pending = body =>
                new Kyo.Handle[E, A, B, B, S]:
                    def v = body
                    val handler =
                        new Kyo.Handler.HandleCont[I, O, E, A, B, S]:
                            def tag                                            = effectTag
                            def run[C](input: I[C], cont: O[C] => A < (E & S)) = handle[C](input, cont)
                            def complete(a: A)                                 = done(a)
                    def cont = Arrow.id[B]
            ,
            done = a => done(a)
        )

    @nowarn("msg=anonymous")
    inline def handleLoop[I[_], O[_], E <: ArrowEffect[I, O], A, B, S](
        inline effectTag: Tag[E],
        v: A < (E & S)
    )(
        inline handle: [C] => I[C] => Loop.Outcome[O[C] < (E & S), B] < S,
        inline done: A => B < S
    ): B < S =
        v.lower[B < S](
            pending = body =>
                new Kyo.Handle[E, A, B, B, S]:
                    def v = body
                    val handler =
                        new Kyo.Handler.HandleLoop[I, O, E, A, B, S]:
                            def tag                 = effectTag
                            def run[C](input: I[C]) = handle[C](input)
                            def complete(a: A)      = done(a)
                    def cont = Arrow.id[B]
            ,
            done = a => done(a)
        )

    @nowarn("msg=anonymous")
    inline def handleLoopState[I[_], O[_], E <: ArrowEffect[I, O], A, B, S, State](
        inline effectTag: Tag[E],
        inline state: State,
        v: A < (E & S)
    )(
        inline handle: [C] => (State, I[C]) => Loop.Outcome2[State, O[C] < (E & S), B] < S,
        inline done: (State, A) => B < S
    ): B < S =
        v.lower[B < S](
            pending = body =>
                new Kyo.Handle[E, A, B, B, S]:
                    def v = body
                    val handler =
                        new Kyo.Handler.HandleLoopState[I, O, E, A, B, S, State]:
                            def tag                             = effectTag
                            def initialState                    = state
                            def run[C](st: State, input: I[C])  = handle[C](st, input)
                            def complete(st: State, a: A)       = done(st, a)
                    def cont = Arrow.id[B]
            ,
            done = a => done(state, a)
        )

end ArrowEffect

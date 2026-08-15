package kyo.kernel.proto

import kyo.Frame
import kyo.Loop.Outcome
import kyo.Loop.Outcome2
import kyo.Tag
import kyo.kernel.proto.Arrow.*
import scala.annotation.nowarn

abstract class ArrowEffect[-I[_], +O[_]]

object ArrowEffect:

    @nowarn("msg=anonymous")
    inline def suspend[C](
        using inline _frame: Frame
    )[I[_], O[_], E <: ArrowEffect[I, O]](
        inline effectTag: Tag[E],
        inline input0: I[C]
    ): O[C] < E =
        new Suspend[I, O, E, C, O[C], Any]:
            def tag   = effectTag
            def input = input0
            def cont  = Arrow[O[C]]

    @nowarn("msg=anonymous")
    inline def suspendWith[C](
        using inline _frame: Frame
    )[I[_], O[_], E <: ArrowEffect[I, O], B, S](
        inline effectTag: Tag[E],
        inline input0: I[C]
    )(
        inline f: O[C] => B < S
    ): B < (E & S) =
        def arrow =
            new Transform[O[C], B, S]:
                def frame = _frame
                def apply[C2, S2](v: O[C] < S2, next: Arrow[B, C2, S2]): C2 < (S & S2) =
                    run(v, next)
        def run[C2, S2](v: O[C] < S2, next: Arrow[B, C2, S2]): C2 < (S & S2) =
            v match
                case p: Arrow[Any, O[C], S2] @unchecked =>
                    p.chain(arrow.chain(next))
                case o: O[C] @unchecked =>
                    val slot = Safepoint.get()
                    if !Safepoint.enter(slot) then
                        Bind(o, arrow.chain(next))
                    else
                        val step = next.step
                        val out  = step.head(f(o), step.tail)
                        Safepoint.exit(slot)
                        out
                    end if
        end run
        new Suspend[I, O, E, C, B, S]:
            def tag   = effectTag
            def input = input0
            val cont  = arrow
        end new
    end suspendWith

    @nowarn("msg=anonymous")
    inline def handle[I[_], O[_], E <: ArrowEffect[I, O], A, B, S](
        inline effectTag: Tag[E],
        v: A < (E & S)
    )(
        inline f: [C] => (I[C], O[C] => A < (E & S)) => A < (E & S),
        inline done: A => B < S
    ): B < S =
        v match
            case body: Arrow[Any, A, E & S] @unchecked =>
                new Handle[E, A, B, B, S]:
                    def v = body
                    val handler =
                        new Handler.HandleCont[I, O, E, A, B, S]:
                            def tag                                            = effectTag
                            def run[C](input: I[C], cont: O[C] => A < (E & S)) = f[C](input, cont)
                            def complete(a: A)                                 = done(a)
                    def cont = Arrow[B]
            case a =>
                done(a.asInstanceOf[A])
    end handle

    @nowarn("msg=anonymous")
    inline def handleLoop[I[_], O[_], E <: ArrowEffect[I, O], A, B, S](
        inline effectTag: Tag[E],
        v: A < (E & S)
    )(
        inline f: [C] => I[C] => Outcome[O[C] < (E & S), B] < S,
        inline done: A => B < S
    ): B < S =
        v match
            case body: Arrow[Any, A, E & S] @unchecked =>
                new Handle[E, A, B, B, S]:
                    def v = body
                    val handler =
                        new Handler.HandleLoop[I, O, E, A, B, S]:
                            def tag                 = effectTag
                            def run[C](input: I[C]) = f[C](input)
                            def complete(a: A)      = done(a)
                    def cont = Arrow[B]
            case a =>
                done(a.asInstanceOf[A])
    end handleLoop

    @nowarn("msg=anonymous")
    inline def handleLoopState[I[_], O[_], E <: ArrowEffect[I, O], A, B, S, State](
        inline effectTag: Tag[E],
        state: State,
        v: A < (E & S)
    )(
        inline f: [C] => (State, I[C]) => Outcome2[State, O[C] < (E & S), B] < S,
        inline done: (State, A) => B < S
    ): B < S =
        v match
            case body: Arrow[Any, A, E & S] @unchecked =>
                new Handle[E, A, B, B, S]:
                    def v = body
                    val handler =
                        new Handler.HandleLoopState[I, O, E, A, B, S, State]:
                            def tag                            = effectTag
                            def initialState                   = state
                            def run[C](st: State, input: I[C]) = f[C](st, input)
                            def complete(st: State, a: A)      = done(st, a)
                    def cont = Arrow[B]
            case a =>
                done(state, a.asInstanceOf[A])
    end handleLoopState

end ArrowEffect

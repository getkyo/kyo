package kyo.kernel

import kyo.Arrow
import kyo.Arrow.*
import kyo.Frame
import kyo.Tag
import kyo.kernel.Loop.Outcome
import kyo.kernel.Loop.Outcome2
import kyo.kernel.internal.Safepoint
import scala.annotation.nowarn

// TODO we also need to add ContextEffect. I'm considering making it an ArrowEffect that keeps a map with all the contextual values

abstract class ArrowEffect[-I[_], +O[_]]

object ArrowEffect:

    @nowarn("msg=anonymous")
    inline def suspend[C](
        using inline _frame: Frame
    )[I[_], O[_], E <: ArrowEffect[I, O]](
        inline effectTag: Tag[E],
        inline effectInput: I[C]
    ): O[C] < E =
        new Suspend[I, O, E, C, O[C], Any]:
            def frame         = _frame
            def tag           = effectTag
            def input         = effectInput
            def cont(v: O[C]) = v

    @nowarn("msg=anonymous")
    inline def suspendWith[C](
        using inline _frame: Frame
    )[I[_], O[_], E <: ArrowEffect[I, O], B, S](
        inline effectTag: Tag[E],
        inline effectInput: I[C]
    )(
        inline f: O[C] => B < S
    ): B < (E & S) =
        new Suspend[I, O, E, C, B, S]:
            def frame         = _frame
            def tag           = effectTag
            def input         = effectInput
            def cont(v: O[C]) = f(v)
    end suspendWith

    @nowarn("msg=anonymous")
    inline def handleCont[I[_], O[_], E <: ArrowEffect[I, O], A, B, S](
        inline effectTag: Tag[E],
        v: A < (E & S)
    )(
        inline handle: [C] => (I[C], O[C] => A < (E & S)) => A < (E & S),
        inline done: A => B < S
    ): B < S =
        def onDone(v: A) = done(v)
        v match
            case body: Arrow[Any, A, E & S] @unchecked =>
                new Handle[E, A, B, B, S]:
                    def v = body
                    val handler =
                        new Handler.HandleCont[I, O, E, A, B, S]:
                            def tag                                            = effectTag
                            def run[C](input: I[C], cont: O[C] => A < (E & S)) = handle[C](input, cont)
                            def complete(a: A)                                 = onDone(Nested.unnest[A](a))
                    def cont = Arrow[B]
            case a =>
                onDone(Nested.unnest[A](a))
        end match
    end handleCont

    @nowarn("msg=anonymous")
    inline def handleContWith[I[_], O[_], E <: ArrowEffect[I, O], A, B, S](
        inline effectTag: Tag[E],
        v: A < (E & S)
    )(
        inline handle: [C] => (I[C], O[C] => A < (E & S)) => A < (E & S),
        inline done: A => B < S
    )[C2, S2](
        inline cont: B => C2 < S2
    )(using inline _frame: Frame): C2 < (S & S2) =
        def onDone(v: A) = done(v)
        def arrow =
            new Transform[B, C2, S2]:
                def frame = _frame
                def apply[C3, S3](v: B < S3, next: Arrow[C2, C3, S3]) =
                    v match
                        case v: Arrow[Any, B, S3] @unchecked =>
                            v.chain(this.chain(next))
                        case v =>
                            val res  = Nested.unnest[B](v)
                            val slot = Safepoint.get()
                            if !Safepoint.enter(slot) then
                                Bind(v, this.chain(next))
                            else
                                val step = next.step
                                val out  = step.head(cont(res), step.tail)
                                Safepoint.exit(slot)
                                out
                            end if
                    end match
                end apply
        v match
            case body: Arrow[Any, A, E & S] @unchecked =>
                new Handle[E, A, B, C2, S & S2]:
                    def v = body
                    val handler =
                        new Handler.HandleCont[I, O, E, A, B, S]:
                            def tag                                            = effectTag
                            def run[C](input: I[C], cont: O[C] => A < (E & S)) = handle[C](input, cont)
                            def complete(a: A)                                 = onDone(Nested.unnest[A](a))
                    def cont = arrow
            case a =>
                arrow(onDone(Nested.unnest[A](a)), Arrow[C2])
        end match
    end handleContWith

    @nowarn("msg=anonymous")
    inline def handleLoop[I[_], O[_], E <: ArrowEffect[I, O], A, B, S](
        inline effectTag: Tag[E],
        v: A < (E & S)
    )(
        inline handle: [C] => I[C] => Outcome[O[C] < (E & S), B] < S,
        inline done: A => B < S
    ): B < S =
        def onDone(v: A) = done(v)
        v match
            case body: Arrow[Any, A, E & S] @unchecked =>
                new Handle[E, A, B, B, S]:
                    def v = body
                    val handler =
                        new Handler.HandleLoop[I, O, E, A, B, S]:
                            def tag                 = effectTag
                            def run[C](input: I[C]) = handle[C](input)
                            def complete(a: A)      = onDone(Nested.unnest[A](a))
                    def cont = Arrow[B]
            case a =>
                onDone(Nested.unnest[A](a))
        end match
    end handleLoop

    @nowarn("msg=anonymous")
    inline def handleLoopWith[I[_], O[_], E <: ArrowEffect[I, O], A, B, S](
        inline effectTag: Tag[E],
        v: A < (E & S)
    )(
        inline f: [C] => I[C] => Outcome[O[C] < (E & S), B] < S,
        inline done: A => B < S
    )[C2, S2](
        inline cont: B => C2 < S2
    )(using inline _frame: Frame): C2 < (S & S2) =
        def onDone(v: A) = done(v)
        def arrow =
            new Transform[B, C2, S2]:
                def frame = _frame
                def apply[C3, S3](v: B < S3, next: Arrow[C2, C3, S3]): C3 < (S2 & S3) =
                    v match
                        case v: Arrow[Any, B, S3] @unchecked =>
                            v.chain(this.chain(next))
                        case v =>
                            val res  = Nested.unnest[B](v)
                            val slot = Safepoint.get()
                            if !Safepoint.enter(slot) then
                                Bind(v, this.chain(next))
                            else
                                val step = next.step
                                val out  = step.head(cont(res), step.tail)
                                Safepoint.exit(slot)
                                out
                            end if
                    end match
                end apply
        v match
            case body: Arrow[Any, A, E & S] @unchecked =>
                new Handle[E, A, B, C2, S & S2]:
                    def v = body
                    val handler =
                        new Handler.HandleLoop[I, O, E, A, B, S]:
                            def tag                 = effectTag
                            def run[C](input: I[C]) = f[C](input)
                            def complete(a: A)      = onDone(Nested.unnest[A](a))
                    def cont = arrow
            case a =>
                arrow(onDone(Nested.unnest[A](a)), Arrow[C2])
        end match
    end handleLoopWith

    @nowarn("msg=anonymous")
    inline def handleLoopState[I[_], O[_], E <: ArrowEffect[I, O], A, B, S, State](
        inline effectTag: Tag[E],
        state: State,
        v: A < (E & S)
    )(
        inline handle: [C] => (State, I[C]) => Outcome2[State, O[C] < (E & S), B] < S,
        inline done: (State, A) => B < S
    ): B < S =
        def onDone(s: State, v: A) = done(s, v)
        v match
            case body: Arrow[Any, A, E & S] @unchecked =>
                new Handle[E, A, B, B, S]:
                    def v = body
                    val handler =
                        new Handler.HandleLoopState[I, O, E, A, B, S, State]:
                            def tag                            = effectTag
                            def initialState                   = state
                            def run[C](st: State, input: I[C]) = handle[C](st, input)
                            def complete(st: State, a: A)      = onDone(st, Nested.unnest[A](a))
                    def cont = Arrow[B]
            case a =>
                onDone(state, Nested.unnest[A](a))
        end match
    end handleLoopState

    @nowarn("msg=anonymous")
    inline def handleLoopStateWith[I[_], O[_], E <: ArrowEffect[I, O], A, B, S, State](
        inline effectTag: Tag[E],
        state: State,
        v: A < (E & S)
    )(
        inline handle: [C] => (State, I[C]) => Outcome2[State, O[C] < (E & S), B] < S,
        inline done: (State, A) => B < S
    )[C2, S2](
        inline cont: B => C2 < S2
    )(using inline _frame: Frame): C2 < (S & S2) =
        def onDone(s: State, v: A) = done(s, v)
        def arrow =
            new Transform[B, C2, S2]:
                def frame = _frame
                def apply[C3, S3](v: B < S3, next: Arrow[C2, C3, S3]) =
                    v match
                        case v: Arrow[Any, B, S3] @unchecked =>
                            v.chain(this.chain(next))
                        case v =>
                            val res  = Nested.unnest[B](v)
                            val slot = Safepoint.get()
                            if !Safepoint.enter(slot) then
                                Bind(v, this.chain(next))
                            else
                                val step = next.step
                                val out  = step.head(cont(res), step.tail)
                                Safepoint.exit(slot)
                                out
                            end if
                    end match
                end apply
        v match
            case body: Arrow[Any, A, E & S] @unchecked =>
                new Handle[E, A, B, C2, S & S2]:
                    def v = body
                    val handler =
                        new Handler.HandleLoopState[I, O, E, A, B, S, State]:
                            def tag                            = effectTag
                            def initialState                   = state
                            def run[C](st: State, input: I[C]) = handle[C](st, input)
                            def complete(st: State, a: A)      = onDone(st, Nested.unnest[A](a))
                    def cont = arrow
            case a =>
                arrow(onDone(state, Nested.unnest[A](a)), Arrow[C2])
        end match
    end handleLoopStateWith

end ArrowEffect

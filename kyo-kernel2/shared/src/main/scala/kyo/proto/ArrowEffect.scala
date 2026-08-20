package kyo.proto

import kyo.Frame
import kyo.Tag
import scala.annotation.nowarn

abstract class ArrowEffect[I[_], O[_]] extends Effect

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
        new Kyo.Suspend[I, O, E, C, B, S] with Arrow.Transform[O[C], B, S]:
            def frame                   = _frame
            def tag                     = effectTag
            def input                   = effectInput
            def cont                    = this
            override def apply(v: O[C]) = f(v)
            def apply[D, S2](v: O[C] < S2, next: Arrow[B, D, S2]): D < (S & S2) =
                v.lower(
                    pending = Effect.defer(_, this, next),
                    done = v => next(apply(v), Arrow.id)
                )
    end suspendWith

    @nowarn("msg=anonymous")
    inline def handleCont[I[_], O[_], E <: ArrowEffect[I, O], A, B, S](
        inline effectTag: Tag[E],
        v: A < (E & S)
    )(
        inline handle: [C] => (I[C], O[C] => A < (E & S)) => A < (E & S),
        inline done: A => B < S
    )(using inline _frame: Frame): B < S =
        def onDone(v: A) = done(v)
        v.lower[B < S](
            pending = body =>
                new Kyo.Handle[E, A, B, B, S]:
                    def value = body
                    val handler =
                        new Handler.HandlerCont[I, O, E, A, B, S]:
                            def frame                                          = _frame
                            def tag                                            = effectTag
                            def run[C](input: I[C], cont: O[C] => A < (E & S)) = handle[C](input, cont)
                            override def apply(a: A)                           = onDone(a)
                    def cont = Arrow.id[B]
            ,
            done = a => onDone(a)
        )
    end handleCont

    @nowarn("msg=anonymous")
    inline def handleLoop[I[_], O[_], E <: ArrowEffect[I, O], A, B, S](
        inline effectTag: Tag[E],
        v: A < (E & S)
    )(
        inline handle: [C] => I[C] => Loop.Outcome[O[C] < (E & S), B] < S,
        inline done: A => B < S
    )(using inline _frame: Frame): B < S =
        def onDone(v: A) = done(v)
        v.lower[B < S](
            pending = body =>
                new Kyo.Handle[E, A, B, B, S]:
                    def value = body
                    val handler =
                        new Handler.HandlerLoop[I, O, E, A, B, S]:
                            def frame                = _frame
                            def tag                  = effectTag
                            def run[C](input: I[C])  = handle[C](input)
                            override def apply(a: A) = onDone(a)
                    def cont = Arrow.id[B]
            ,
            done = a => onDone(a)
        )
    end handleLoop

    @nowarn("msg=anonymous")
    inline def handleLoopState[I[_], O[_], E <: ArrowEffect[I, O], A, B, S, State](
        inline effectTag: Tag[E],
        state: State,
        v: A < (E & S)
    )(
        inline handle: [C] => (State, I[C]) => Loop.Outcome2[State, O[C] < (E & S), B] < S,
        inline done: (State, A) => B < S
    )(using inline _frame: Frame): B < S =
        def onDone(s: State, v: A) = done(s, v)
        v.lower[B < S](
            pending = body =>
                new Kyo.Handle[E, A, B, B, S]:
                    def value = body
                    val handler =
                        new Handler.HandlerLoopState[I, O, E, A, B, S, State]:
                            def frame                          = _frame
                            def tag                            = effectTag
                            def initialState                   = state
                            def run[C](st: State, input: I[C]) = handle[C](st, input)
                            def apply(st: State, a: A)         = onDone(st, a)
                    def cont = Arrow.id[B]
            ,
            done = a => onDone(state, a)
        )
    end handleLoopState

    // the *With variants take the region's continuation as a separate parameter group and fuse it
    // into the region node: the node is the arrow the region's result flows into, as suspendWith's
    // node is the arrow the operation's answer flows into. A settled input takes done and then the
    // continuation as a map

    @nowarn("msg=anonymous")
    inline def handleContWith[I[_], O[_], E <: ArrowEffect[I, O], A, B, S](
        using inline _frame: Frame
    )(
        inline effectTag: Tag[E],
        v: A < (E & S)
    )(
        inline handle: [X] => (I[X], O[X] => A < (E & S)) => A < (E & S),
        inline done: A => B < S
    )[C, S2](
        inline f: B => C < S2
    ): C < (S & S2) =
        def onDone(v: A) = done(v)
        v.lower[C < (S & S2)](
            pending = body =>
                new Kyo.Handle[E, A, B, C, S & S2] with Arrow.Transform[B, C, S & S2]:
                    def frame = _frame
                    def value = body
                    val handler =
                        new Handler.HandlerCont[I, O, E, A, B, S]:
                            def frame                                          = _frame
                            def tag                                            = effectTag
                            def run[X](input: I[X], cont: O[X] => A < (E & S)) = handle[X](input, cont)
                            override def apply(a: A)                           = onDone(a)
                    def cont                 = this
                    override def apply(b: B) = f(b)
                    def apply[D, S3](b: B < S3, next: Arrow[C, D, S3]): D < (S & S2 & S3) =
                        b.lower(
                            pending = Effect.defer(_, this, next),
                            done = b => next(apply(b), Arrow.id)
                        )
            ,
            done = a => onDone(a).map(f)
        )
    end handleContWith

    @nowarn("msg=anonymous")
    inline def handleLoopWith[I[_], O[_], E <: ArrowEffect[I, O], A, B, S](
        using inline _frame: Frame
    )(
        inline effectTag: Tag[E],
        v: A < (E & S)
    )(
        inline handle: [X] => I[X] => Loop.Outcome[O[X] < (E & S), B] < S,
        inline done: A => B < S
    )[C, S2](
        inline f: B => C < S2
    ): C < (S & S2) =
        def onDone(v: A) = done(v)
        v.lower[C < (S & S2)](
            pending = body =>
                new Kyo.Handle[E, A, B, C, S & S2] with Arrow.Transform[B, C, S & S2]:
                    def frame = _frame
                    def value = body
                    val handler =
                        new Handler.HandlerLoop[I, O, E, A, B, S]:
                            def frame                = _frame
                            def tag                  = effectTag
                            def run[X](input: I[X])  = handle[X](input)
                            override def apply(a: A) = onDone(a)
                    def cont                 = this
                    override def apply(b: B) = f(b)
                    def apply[D, S3](b: B < S3, next: Arrow[C, D, S3]): D < (S & S2 & S3) =
                        b.lower(
                            pending = Effect.defer(_, this, next),
                            done = b => next(apply(b), Arrow.id)
                        )
            ,
            done = a => onDone(a).map(f)
        )
    end handleLoopWith

    @nowarn("msg=anonymous")
    inline def handleLoopStateWith[I[_], O[_], E <: ArrowEffect[I, O], A, B, S, State](
        using inline _frame: Frame
    )(
        inline effectTag: Tag[E],
        state: State,
        v: A < (E & S)
    )(
        inline handle: [X] => (State, I[X]) => Loop.Outcome2[State, O[X] < (E & S), B] < S,
        inline done: (State, A) => B < S
    )[C, S2](
        inline f: B => C < S2
    ): C < (S & S2) =
        def onDone(s: State, v: A) = done(s, v)
        v.lower[C < (S & S2)](
            pending = body =>
                new Kyo.Handle[E, A, B, C, S & S2] with Arrow.Transform[B, C, S & S2]:
                    def frame = _frame
                    def value = body
                    val handler =
                        new Handler.HandlerLoopState[I, O, E, A, B, S, State]:
                            def frame                          = _frame
                            def tag                            = effectTag
                            def initialState                   = state
                            def run[X](st: State, input: I[X]) = handle[X](st, input)
                            def apply(st: State, a: A)         = onDone(st, a)
                    def cont                 = this
                    override def apply(b: B) = f(b)
                    def apply[D, S3](b: B < S3, next: Arrow[C, D, S3]): D < (S & S2 & S3) =
                        b.lower(
                            pending = Effect.defer(_, this, next),
                            done = b => next(apply(b), Arrow.id)
                        )
            ,
            done = a => onDone(state, a).map(f)
        )
    end handleLoopStateWith

end ArrowEffect

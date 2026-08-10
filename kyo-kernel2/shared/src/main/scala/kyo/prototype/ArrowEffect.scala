package kyo.prototype

import kyo.Frame
import kyo.Maybe
import kyo.Tag
import scala.annotation.nowarn
import scala.annotation.tailrec

abstract class ArrowEffect[I[_], O[_]]

object ArrowEffect:

    @nowarn("msg=anonymous")
    inline def suspend[I[_], O[_], E <: ArrowEffect[I, O], X](inline _tag: Tag[E], inline _input: I[X])(using
        inline _frame: Frame
    ): O[X] < E =
        new Kyo.Suspend[I, O, E, X, O[X], E]:
            def tag   = _tag
            def input = _input
            def frame = _frame
            def cont  = Arrow[O[X]]

    @nowarn("msg=anonymous")
    def handle[I[_], O[_], E <: ArrowEffect[I, O], A, S](tag: Tag[E], v: A < (E & S))(
        f: [X] => (I[X], O[X] => A < (E & S)) => A < (E & S)
    )(using _frame: Frame): A < S =
        def rotated[C, S2](next: Arrow[A, C, S2]): Arrow.Transform[A, C, S & S2] =
            new Arrow.Transform[A, C, S & S2]:
                def frame = _frame
                def apply[D, S3](v: A < S3, next2: Arrow[C, D, S3]) =
                    handleLoop(v.asInstanceOf[A < (E & S)], next.chain(next2)).asInstanceOf[D < (S & S2 & S3)]

        @tailrec def handleLoop[C, S2](v: A < (E & S), next: Arrow[A, C, S2]): C < (S & S2) =
            v match
                case kyo: Kyo.Suspend[?, ?, ?, ?, ?, ?] if kyo.tag.erased <:< tag.erased =>
                    val anchored = kyo.asInstanceOf[Kyo.Suspend[I, O, E, Any, A, E & S]]
                    handleLoop(f[Any](anchored.input, o => anchored.cont(o)), next)
                case kyo: Kyo.Suspend[?, ?, ?, ?, ?, ?] =>
                    kyo.asInstanceOf[Kyo[A, E & S]].map(rotated(next)).asInstanceOf[C < (S & S2)]
                case kyo: Kyo.Defer[?, ?, ?] =>
                    val slot = Safepoint.get()
                    if !Safepoint.enter(slot) then
                        kyo.asInstanceOf[Kyo[A, E & S]].map(rotated(next)).asInstanceOf[C < (S & S2)]
                    else
                        val defer = kyo.asInstanceOf[Kyo.Defer[Any, A, E & S]]
                        val w =
                            try
                                val step = defer.cont.step
                                step.head(defer.value, step.tail)
                            finally Safepoint.exit(slot)
                        handleLoop(w, next)
                    end if
                case v =>
                    val step = next.step
                    step.head(v.asInstanceOf[A < S2], step.tail).asInstanceOf[C < (S & S2)]
        end handleLoop

        handleLoop(v, Arrow[A])
    end handle

    @nowarn("msg=anonymous")
    def resume[I[_], O[_], E <: ArrowEffect[I, O], A, S, S2](tag: Tag[E], v: A < (E & S))(
        f: [X] => I[X] => O[X] < (S & S2)
    )(using _frame: Frame): A < (S & S2) =
        def rotated[C, S3](next: Arrow[A, C, S3]): Arrow.Transform[A, C, S & S2 & S3] =
            new Arrow.Transform[A, C, S & S2 & S3]:
                def frame = _frame
                def apply[D, S4](v: A < S4, next2: Arrow[C, D, S4]) =
                    resumeLoop(v.asInstanceOf[A < (E & S)], next.chain(next2)).asInstanceOf[D < (S & S2 & S3 & S4)]

        @tailrec def resumeLoop[C, S3](v: A < (E & S), next: Arrow[A, C, S3]): C < (S & S2 & S3) =
            v match
                case kyo: Kyo.Suspend[?, ?, ?, ?, ?, ?] if kyo.tag.erased <:< tag.erased =>
                    val anchored = kyo.asInstanceOf[Kyo.Suspend[I, O, E, Any, A, E & S]]
                    val step     = anchored.cont.step
                    resumeLoop(step.head(f[Any](anchored.input).asInstanceOf[O[Any] < (E & S)], step.tail), next)
                case kyo: Kyo.Suspend[?, ?, ?, ?, ?, ?] =>
                    kyo.asInstanceOf[Kyo[A, E & S]].map(rotated(next)).asInstanceOf[C < (S & S2 & S3)]
                case kyo: Kyo.Defer[?, ?, ?] =>
                    val slot = Safepoint.get()
                    if !Safepoint.enter(slot) then
                        kyo.asInstanceOf[Kyo[A, E & S]].map(rotated(next)).asInstanceOf[C < (S & S2 & S3)]
                    else
                        val defer = kyo.asInstanceOf[Kyo.Defer[Any, A, E & S]]
                        val w =
                            try
                                val step = defer.cont.step
                                step.head(defer.value, step.tail)
                            finally Safepoint.exit(slot)
                        resumeLoop(w, next)
                    end if
                case v =>
                    val step = next.step
                    step.head(v.asInstanceOf[A < S3], step.tail).asInstanceOf[C < (S & S2 & S3)]
        end resumeLoop

        resumeLoop(v, Arrow[A])
    end resume

    @nowarn("msg=anonymous")
    def stop[I[_], O[_], E <: ArrowEffect[I, O], A, S, B >: A](tag: Tag[E], v: A < (E & S))(
        f: [X] => I[X] => B < (E & S)
    )(using _frame: Frame): B < S =
        def rotated[C, S2](next: Arrow[B, C, S2]): Arrow.Transform[B, C, S & S2] =
            new Arrow.Transform[B, C, S & S2]:
                def frame = _frame
                def apply[D, S3](v: B < S3, next2: Arrow[C, D, S3]) =
                    stopLoop(v.asInstanceOf[B < (E & S)], next.chain(next2)).asInstanceOf[D < (S & S2 & S3)]

        @tailrec def stopLoop[C, S2](v: B < (E & S), next: Arrow[B, C, S2]): C < (S & S2) =
            v match
                case kyo: Kyo.Suspend[?, ?, ?, ?, ?, ?] if kyo.tag.erased <:< tag.erased =>
                    val anchored = kyo.asInstanceOf[Kyo.Suspend[I, O, E, Any, B, E & S]]
                    stopLoop(f[Any](anchored.input), next)
                case kyo: Kyo.Suspend[?, ?, ?, ?, ?, ?] =>
                    kyo.asInstanceOf[Kyo[B, E & S]].map(rotated(next)).asInstanceOf[C < (S & S2)]
                case kyo: Kyo.Defer[?, ?, ?] =>
                    val slot = Safepoint.get()
                    if !Safepoint.enter(slot) then
                        kyo.asInstanceOf[Kyo[B, E & S]].map(rotated(next)).asInstanceOf[C < (S & S2)]
                    else
                        val defer = kyo.asInstanceOf[Kyo.Defer[Any, B, E & S]]
                        val w =
                            try
                                val step = defer.cont.step
                                step.head(defer.value, step.tail)
                            finally Safepoint.exit(slot)
                        stopLoop(w, next)
                    end if
                case v =>
                    val step = next.step
                    step.head(v.asInstanceOf[B < S2], step.tail).asInstanceOf[C < (S & S2)]
        end stopLoop

        stopLoop(v, Arrow[B])
    end stop

    @nowarn("msg=anonymous")
    def loop[I[_], O[_], E <: ArrowEffect[I, O], A, S, State](tag: Tag[E], state: State, v: A < (E & S))(
        f: [X] => (I[X], State, O[X] => A < (E & S)) => (State, A < (E & S))
    )(using _frame: Frame): (State, A) < S =
        def rotated[C, S2](state: State, next: Arrow[(State, A), C, S2]): Arrow.Transform[A, C, S & S2] =
            new Arrow.Transform[A, C, S & S2]:
                def frame = _frame
                def apply[D, S3](v: A < S3, next2: Arrow[C, D, S3]) =
                    loopLoop(state, v.asInstanceOf[A < (E & S)], next.chain(next2)).asInstanceOf[D < (S & S2 & S3)]

        @tailrec def loopLoop[C, S2](state: State, v: A < (E & S), next: Arrow[(State, A), C, S2]): C < (S & S2) =
            v match
                case kyo: Kyo.Suspend[?, ?, ?, ?, ?, ?] if kyo.tag.erased <:< tag.erased =>
                    val anchored     = kyo.asInstanceOf[Kyo.Suspend[I, O, E, Any, A, E & S]]
                    val (state2, v2) = f[Any](anchored.input, state, o => anchored.cont(o))
                    loopLoop(state2, v2, next)
                case kyo: Kyo.Suspend[?, ?, ?, ?, ?, ?] =>
                    kyo.asInstanceOf[Kyo[A, E & S]].map(rotated(state, next)).asInstanceOf[C < (S & S2)]
                case kyo: Kyo.Defer[?, ?, ?] =>
                    val slot = Safepoint.get()
                    if !Safepoint.enter(slot) then
                        kyo.asInstanceOf[Kyo[A, E & S]].map(rotated(state, next)).asInstanceOf[C < (S & S2)]
                    else
                        val defer = kyo.asInstanceOf[Kyo.Defer[Any, A, E & S]]
                        val w =
                            try
                                val step = defer.cont.step
                                step.head(defer.value, step.tail)
                            finally Safepoint.exit(slot)
                        loopLoop(state, w, next)
                    end if
                case v =>
                    val step = next.step
                    val a    = Kyo.unnest(v.asInstanceOf[A < Any])
                    step.head(`<`.lift((state, a)), step.tail).asInstanceOf[C < (S & S2)]
        end loopLoop

        loopLoop(state, v, Arrow[(State, A)])
    end loop

    def partial[I[_], O[_], E <: ArrowEffect[I, O], A, S](tag: Tag[E], v: A < (E & S))(
        f: [X] => (I[X], O[X] => A < (E & S)) => Maybe[A < (E & S)]
    )(using _frame: Frame): A < (E & S) =
        @tailrec def partialLoop(v: A < (E & S)): A < (E & S) =
            v match
                case kyo: Kyo.Suspend[?, ?, ?, ?, ?, ?] if kyo.tag.erased <:< tag.erased =>
                    val anchored = kyo.asInstanceOf[Kyo.Suspend[I, O, E, Any, A, E & S]]
                    f[Any](anchored.input, o => anchored.cont(o)) match
                        case Maybe.Present(v2) => partialLoop(v2)
                        case Maybe.Absent      => v
                case kyo: Kyo.Defer[?, ?, ?] =>
                    val slot = Safepoint.get()
                    if !Safepoint.enter(slot) then v
                    else
                        val defer = kyo.asInstanceOf[Kyo.Defer[Any, A, E & S]]
                        val w =
                            try
                                val step = defer.cont.step
                                step.head(defer.value, step.tail)
                            finally Safepoint.exit(slot)
                        partialLoop(w)
                    end if
                case v =>
                    v
        end partialLoop

        partialLoop(v)
    end partial

end ArrowEffect

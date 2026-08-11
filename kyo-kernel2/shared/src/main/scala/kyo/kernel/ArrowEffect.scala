package kyo.kernel

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
    inline def suspendWith[I[_], O[_], E <: ArrowEffect[I, O], V, B, S](
        inline _tag: Tag[E],
        inline _input: I[V]
    )(inline f: O[V] => B < (E & S))(using inline _frame: Frame): B < (E & S) =
        @nowarn("msg=anonymous") def mapLoop[C, S3](v: O[V] < S3, next: Arrow[B, C, S3]): C < (E & S & S3) =
            def arrow =
                new Arrow.Transform[O[V], C, E & S & S3]:
                    def frame = _frame
                    def apply[D, S4](v: O[V] < S4, next2: Arrow[C, D, S4]) =
                        mapLoop(v, next.chain(next2))
            v match
                case kyo: Kyo[O[V], S3] @unchecked =>
                    kyo.map(arrow)
                case v =>
                    val res  = Kyo.unnest(v)
                    val slot = Safepoint.get()
                    if !Safepoint.enter(slot) then
                        new Kyo.Defer(v, arrow)
                    else
                        val step = next.step
                        val out  = step.head(f(res), step.tail)
                        Safepoint.exit(slot)
                        out
                    end if
            end match
        end mapLoop
        new Arrow.Transform[O[V], B, E & S] with Kyo.Suspend[I, O, E, V, B, E & S]:
            def tag   = _tag
            def input = _input
            def frame = _frame
            def cont  = this
            def apply[C, S2](v: O[V] < S2, next2: Arrow[B, C, S2]) =
                mapLoop(v, next2)
        end new
    end suspendWith

    @nowarn("msg=anonymous")
    def handle[I[_], O[_], E <: ArrowEffect[I, O], A, S](tag: Tag[E], v: A < (E & S))(
        f: [X] => (I[X], O[X] => A < (E & S)) => A < (E & S)
    )(using _frame: Frame): A < S =
        new Kyo.Handled[I, O, E, A, A, S](
            v,
            new Handler.Cont[I, O, E, A, S](tag):
                def apply[X](input: I[X], cont: O[X] => A < (E & S)) = f(input, cont)
            ,
            Arrow[A]
        )

    @nowarn("msg=anonymous")
    def handleLoop[I[_], O[_], E <: ArrowEffect[I, O], A, S, S2](tag: Tag[E], v: A < (E & S))(
        f: [X] => I[X] => Loop.Outcome[O[X] < (E & S & S2), A] < S2
    )(using _frame: Frame): A < (S & S2) =
        new Kyo.Handled[I, O, E, A, A, S & S2](
            v,
            new Handler.Loop[I, O, E, A, S & S2](tag):
                def apply[X](input: I[X]) = f(input)
            ,
            Arrow[A]
        )

    @nowarn("msg=anonymous")
    def handleLoop[I[_], O[_], E <: ArrowEffect[I, O], A, S, S2, State](tag: Tag[E], state: State, v: A < (E & S))(
        f: [X] => (I[X], State) => Loop.Outcome2[State, O[X] < (E & S & S2), A] < S2
    )(using _frame: Frame): A < (S & S2) =
        final class StateHandler(state: State) extends Handler.LoopState[I, O, E, A, S & S2](tag):
            def apply[X](input: I[X]) =
                f(input, state).map { out =>
                    (out: Any) match
                        case c: Loop.Continue2[State, O[X] < (E & S & S2)] @unchecked =>
                            Loop.continue(new StateHandler(c._1), c._2)
                        case done =>
                            // the unions share one encoding; only the successor slot's
                            // static type changes
                            done.asInstanceOf[Loop.Outcome2[Handler.LoopState[I, O, E, A, S & S2], O[X] < (E & S & S2), A] < (S & S2)]
                }
        end StateHandler
        new Kyo.Handled[I, O, E, A, A, S & S2](v, new StateHandler(state), Arrow[A])
    end handleLoop

    @nowarn("msg=anonymous")
    def resume[I[_], O[_], E <: ArrowEffect[I, O], A, S, S2](tag: Tag[E], v: A < (E & S))(
        f: [X] => I[X] => O[X] < (S & S2)
    )(using _frame: Frame): A < (S & S2) =
        def rotatedSuspend[C, S3](kyo: Kyo.Suspend[?, ?, ?, ?, ?, ?], next: Arrow[A, C, S3]): C < (S & S2 & S3) =
            val k = kyo.asInstanceOf[Kyo.Suspend[[B] =>> Any, [B] =>> Any, Nothing, Any, A, E & S]]
            new Kyo.Suspend[[B] =>> Any, [B] =>> Any, Nothing, Any, C, S & S2 & S3]:
                override val root = k.root
                def tag           = root.tag
                def input         = root.input
                def frame         = root.frame
                val cont = new Arrow.Transform[Any, C, S & S2 & S3]:
                    def frame = _frame
                    def apply[D, S4](v: Any < S4, next2: Arrow[C, D, S4]) =
                        val step = k.cont.step
                        resumeLoop(step.head(v.asInstanceOf[Any < (E & S)], step.tail), next.chain(next2))
                            .asInstanceOf[D < (S & S2 & S3 & S4)]
                    end apply
            end new
        end rotatedSuspend

        def rotatedDefer[C, S3](kyo: Kyo.Defer[?, ?, ?], next: Arrow[A, C, S3]): C < (S & S2 & S3) =
            val defer = kyo.asInstanceOf[Kyo.Defer[Any, A, E & S]]
            new Kyo.Defer[Any, C, S & S2 & S3](
                defer.value.asInstanceOf[Any < (S & S2 & S3)],
                new Arrow.Transform[Any, C, S & S2 & S3]:
                    def frame = _frame
                    def apply[D, S4](v: Any < S4, next2: Arrow[C, D, S4]) =
                        val step = defer.cont.step
                        resumeLoop(step.head(v.asInstanceOf[Any < (E & S)], step.tail), next.chain(next2))
                            .asInstanceOf[D < (S & S2 & S3 & S4)]
                    end apply
            )
        end rotatedDefer

        @tailrec def resumeLoop[C, S3](v: A < (E & S), next: Arrow[A, C, S3]): C < (S & S2 & S3) =
            v match
                case kyo: Kyo.Suspend[?, ?, ?, ?, ?, ?] if kyo.tag.erased <:< tag.erased =>
                    val anchored = kyo.asInstanceOf[Kyo.Suspend[I, O, E, Any, A, E & S]]
                    val step     = anchored.cont.step
                    resumeLoop(step.head(f[Any](anchored.input).asInstanceOf[O[Any] < (E & S)], step.tail), next)
                case kyo: Kyo.Suspend[?, ?, ?, ?, ?, ?] =>
                    rotatedSuspend(kyo, next)
                case kyo: Kyo.Defer[?, ?, ?] =>
                    val slot = Safepoint.get()
                    if !Safepoint.enter(slot) then
                        rotatedDefer(kyo, next)
                    else
                        val defer = kyo.asInstanceOf[Kyo.Defer[Any, A, E & S]]
                        val w =
                            val step = defer.cont.step
                            step.head(defer.value, step.tail)
                        Safepoint.exit(slot)
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
        def rotatedSuspend[C, S2](kyo: Kyo.Suspend[?, ?, ?, ?, ?, ?], next: Arrow[B, C, S2]): C < (S & S2) =
            val k = kyo.asInstanceOf[Kyo.Suspend[[B2] =>> Any, [B2] =>> Any, Nothing, Any, B, E & S]]
            new Kyo.Suspend[[B2] =>> Any, [B2] =>> Any, Nothing, Any, C, S & S2]:
                override val root = k.root
                def tag           = root.tag
                def input         = root.input
                def frame         = root.frame
                val cont = new Arrow.Transform[Any, C, S & S2]:
                    def frame = _frame
                    def apply[D, S3](v: Any < S3, next2: Arrow[C, D, S3]) =
                        val step = k.cont.step
                        stopLoop(step.head(v.asInstanceOf[Any < (E & S)], step.tail), next.chain(next2))
                            .asInstanceOf[D < (S & S2 & S3)]
                    end apply
            end new
        end rotatedSuspend

        def rotatedDefer[C, S2](kyo: Kyo.Defer[?, ?, ?], next: Arrow[B, C, S2]): C < (S & S2) =
            val defer = kyo.asInstanceOf[Kyo.Defer[Any, B, E & S]]
            new Kyo.Defer[Any, C, S & S2](
                defer.value.asInstanceOf[Any < (S & S2)],
                new Arrow.Transform[Any, C, S & S2]:
                    def frame = _frame
                    def apply[D, S3](v: Any < S3, next2: Arrow[C, D, S3]) =
                        val step = defer.cont.step
                        stopLoop(step.head(v.asInstanceOf[Any < (E & S)], step.tail), next.chain(next2))
                            .asInstanceOf[D < (S & S2 & S3)]
                    end apply
            )
        end rotatedDefer

        @tailrec def stopLoop[C, S2](v: B < (E & S), next: Arrow[B, C, S2]): C < (S & S2) =
            v match
                case kyo: Kyo.Suspend[?, ?, ?, ?, ?, ?] if kyo.tag.erased <:< tag.erased =>
                    val anchored = kyo.asInstanceOf[Kyo.Suspend[I, O, E, Any, B, E & S]]
                    stopLoop(f[Any](anchored.input), next)
                case kyo: Kyo.Suspend[?, ?, ?, ?, ?, ?] =>
                    rotatedSuspend(kyo, next)
                case kyo: Kyo.Defer[?, ?, ?] =>
                    val slot = Safepoint.get()
                    if !Safepoint.enter(slot) then
                        rotatedDefer(kyo, next)
                    else
                        val defer = kyo.asInstanceOf[Kyo.Defer[Any, B, E & S]]
                        val w =
                            val step = defer.cont.step
                            step.head(defer.value, step.tail)
                        Safepoint.exit(slot)
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
        def rotatedSuspend[C, S2](state: State, kyo: Kyo.Suspend[?, ?, ?, ?, ?, ?], next: Arrow[(State, A), C, S2]): C < (S & S2) =
            val k = kyo.asInstanceOf[Kyo.Suspend[[B] =>> Any, [B] =>> Any, Nothing, Any, A, E & S]]
            new Kyo.Suspend[[B] =>> Any, [B] =>> Any, Nothing, Any, C, S & S2]:
                override val root = k.root
                def tag           = root.tag
                def input         = root.input
                def frame         = root.frame
                val cont = new Arrow.Transform[Any, C, S & S2]:
                    def frame = _frame
                    def apply[D, S3](v: Any < S3, next2: Arrow[C, D, S3]) =
                        val step = k.cont.step
                        loopLoop(state, step.head(v.asInstanceOf[Any < (E & S)], step.tail), next.chain(next2))
                            .asInstanceOf[D < (S & S2 & S3)]
                    end apply
            end new
        end rotatedSuspend

        def rotatedDefer[C, S2](state: State, kyo: Kyo.Defer[?, ?, ?], next: Arrow[(State, A), C, S2]): C < (S & S2) =
            val defer = kyo.asInstanceOf[Kyo.Defer[Any, A, E & S]]
            new Kyo.Defer[Any, C, S & S2](
                defer.value.asInstanceOf[Any < (S & S2)],
                new Arrow.Transform[Any, C, S & S2]:
                    def frame = _frame
                    def apply[D, S3](v: Any < S3, next2: Arrow[C, D, S3]) =
                        val step = defer.cont.step
                        loopLoop(state, step.head(v.asInstanceOf[Any < (E & S)], step.tail), next.chain(next2))
                            .asInstanceOf[D < (S & S2 & S3)]
                    end apply
            )
        end rotatedDefer

        @tailrec def loopLoop[C, S2](state: State, v: A < (E & S), next: Arrow[(State, A), C, S2]): C < (S & S2) =
            v match
                case kyo: Kyo.Suspend[?, ?, ?, ?, ?, ?] if kyo.tag.erased <:< tag.erased =>
                    val anchored     = kyo.asInstanceOf[Kyo.Suspend[I, O, E, Any, A, E & S]]
                    val (state2, v2) = f[Any](anchored.input, state, o => anchored.cont(o))
                    loopLoop(state2, v2, next)
                case kyo: Kyo.Suspend[?, ?, ?, ?, ?, ?] =>
                    rotatedSuspend(state, kyo, next)
                case kyo: Kyo.Defer[?, ?, ?] =>
                    val slot = Safepoint.get()
                    if !Safepoint.enter(slot) then
                        rotatedDefer(state, kyo, next)
                    else
                        val defer = kyo.asInstanceOf[Kyo.Defer[Any, A, E & S]]
                        val w =
                            val step = defer.cont.step
                            step.head(defer.value, step.tail)
                        Safepoint.exit(slot)
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
                            val step = defer.cont.step
                            step.head(defer.value, step.tail)
                        Safepoint.exit(slot)
                        partialLoop(w)
                    end if
                case v =>
                    v
        end partialLoop

        partialLoop(v)
    end partial

end ArrowEffect

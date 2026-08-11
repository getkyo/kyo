package kyo.kernel

import kyo.Frame
import kyo.Maybe
import kyo.Tag
import scala.annotation.nowarn
import scala.annotation.tailrec

abstract class ArrowEffect[I[_], O[_]] extends Effect

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

    def handle[I[_], O[_], E <: ArrowEffect[I, O], A, S](tag: Tag[E], v: A < (E & S))(
        f: [X] => (I[X], O[X] => A < (E & S)) => A < (E & S)
    )(using _frame: Frame): A < S =
        v match
            case kyo: Kyo[?, ?] =>
                new Kyo.Handled[I, O, E, A, A, S](v, new Handler.Cont(tag, f), Arrow[A])
            case v =>
                // settled: the effect cannot occur, so the value passes through
                // strictly with no region node; the cast only shrinks the row
                v.asInstanceOf[A < S]

    def handleLoop[I[_], O[_], E <: ArrowEffect[I, O], A, S, S2](tag: Tag[E], v: A < (E & S))(
        f: [X] => I[X] => Loop.Outcome[O[X] < (E & S & S2), A] < S2
    )(using _frame: Frame): A < (S & S2) =
        v match
            case kyo: Kyo[?, ?] =>
                new Kyo.Handled[I, O, E, A, A, S & S2](v, new Handler.Loop(tag, f), Arrow[A])
            case v =>
                // settled: the effect cannot occur, so the value passes through
                // strictly with no region node; the cast only shrinks the row
                v.asInstanceOf[A < (S & S2)]

    def handleLoop[I[_], O[_], E <: ArrowEffect[I, O], A, S, S2, State](tag: Tag[E], state: State, v: A < (E & S))(
        f: [X] => (I[X], State) => Loop.Outcome2[State, O[X] < (E & S & S2), A] < S2
    )(using _frame: Frame): A < (S & S2) =
        v match
            case kyo: Kyo[?, ?] =>
                new Kyo.Handled[I, O, E, A, A, S & S2](v, new Handler.LoopState(tag, state, f), Arrow[A])
            case v =>
                // settled: the effect cannot occur, so the value passes through
                // strictly with no region node; the cast only shrinks the row
                v.asInstanceOf[A < (S & S2)]

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

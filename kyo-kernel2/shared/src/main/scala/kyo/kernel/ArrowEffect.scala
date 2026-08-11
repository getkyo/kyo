package kyo.kernel

import kyo.Arrow
import kyo.Frame
import kyo.Maybe
import kyo.Tag
import kyo.kernel.internal.*
import scala.annotation.nowarn
import scala.annotation.tailrec

abstract class ArrowEffect[-I[_], +O[_]] extends Effect

object ArrowEffect:

    @nowarn("msg=anonymous")
    inline def suspend[X](
        using inline _frame: Frame
    )[I[_], O[_], E <: ArrowEffect[I, O]](
        inline _tag: Tag[E],
        inline _input: I[X]
    ): O[X] < E =
        new Kyo.Suspend[I, O, E, X, O[X], E]:
            def tag   = _tag
            def input = _input
            def frame = _frame
            def cont  = Arrow[O[X]]

    @nowarn("msg=anonymous")
    inline def suspendWith[V](
        using inline _frame: Frame
    )[I[_], O[_], E <: ArrowEffect[I, O], B, S](
        inline _tag: Tag[E],
        inline _input: I[V]
    )(inline f: O[V] => B < (E & S)): B < (E & S) =
        @nowarn("msg=anonymous") def mapLoop[C, S3](v: O[V] < S3, next: Arrow[B, C, S3]): C < (E & S & S3) =
            def arrow =
                new Arrow.Transform[
                    O[V],
                    C,
                    E & S & S3
                ]: // TODO isn't this the same as the obhect we allocate at the end? can't we allocate it first and reuse?
                    def frame = _frame
                    def apply[D, S4](v: O[V] < S4, next2: Arrow[C, D, S4]) =
                        mapLoop(v, next.chain(next2))
            v match
                case kyo: Kyo[O[V], S3] @unchecked =>
                    kyo.map(arrow)
                case v =>
                    // no budget check: f either suspends, returning the node
                    // flat, or settles into the chained arrows, whose strict
                    // segments carry their own checks in map
                    val res  = Kyo.unnest(v)
                    val step = next.step
                    step.head(f(res), step.tail)
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
    inline def handle[I[_], O[_], E <: ArrowEffect[I, O], A, S](inline tag: Tag[E], v: A < (E & S))(
        inline f: [X] => (I[X], O[X] => A < (E & S)) => A < (E & S)
    )(using inline _frame: Frame): A < S =
        v match
            case kyo: Kyo[?, ?] =>
                val handler =
                    new Handler.Cont[I, O, E, A, S](tag):
                        def apply[X](input: I[X], cont: O[X] => A < (E & S)) = f(input, cont)
                new Kyo.Handled[I, O, E, A, A, S](v, handler, Arrow[A])
            case v =>
                // settled: the effect cannot occur, so the value passes through
                // strictly with no region node; the cast only shrinks the row
                v.asInstanceOf[A < S]
        end match
    end handle

    @nowarn("msg=anonymous")
    inline def handleWith[I[_], O[_], E <: ArrowEffect[I, O], A, S, B, S2](inline tag: Tag[E], v: A < (E & S))(
        inline f: [X] => (I[X], O[X] => A < (E & S)) => A < (E & S)
    )(inline cont: A => B < S2)(using inline _frame: Frame): B < (S & S2) =
        @nowarn("msg=anonymous") def mapLoop[C, S3](v: A < S3, next: Arrow[B, C, S3]): C < (S2 & S3) =
            def arrow =
                new Arrow.Transform[A, C, S2 & S3]:
                    def frame = _frame
                    def apply[D, S4](v2: A < S4, next2: Arrow[C, D, S4]) =
                        mapLoop(v2, next.chain(next2))
            v match
                case kyo: Kyo[A, S3] @unchecked =>
                    kyo.map(arrow)
                case v =>
                    val res  = Kyo.unnest(v)
                    val step = next.step
                    step.head(cont(res), step.tail)
            end match
        end mapLoop
        v match
            case kyo: Kyo[?, ?] =>
                val handler =
                    new Handler.Cont[I, O, E, A, S](tag):
                        def apply[X](input: I[X], cont: O[X] => A < (E & S)) = f(input, cont)
                val exit =
                    new Arrow.Transform[A, B, S2]:
                        def frame = _frame
                        def apply[C, S3](v2: A < S3, next2: Arrow[B, C, S3]) =
                            mapLoop(v2, next2)
                new Kyo.Handled[I, O, E, A, B, S & S2](v, handler, exit)
            case v =>
                // settled: the effect cannot occur, so the continuation
                // applies strictly with no region node
                cont(Kyo.unnest(v))
        end match
    end handleWith

    @nowarn("msg=anonymous")
    inline def handleLoop[I[_], O[_], E <: ArrowEffect[I, O], A, S, S2](inline tag: Tag[E], v: A < (E & S))(
        inline f: [X] => I[X] => Loop.Outcome[O[X] < (E & S & S2), A] < S2
    )(using inline _frame: Frame): A < (S & S2) =
        v match
            case kyo: Kyo[?, ?] =>
                val handler =
                    new Handler.Loop[I, O, E, A, S & S2](tag):
                        def apply[X](input: I[X]) = f(input)
                new Kyo.Handled[I, O, E, A, A, S & S2](v, handler, Arrow[A])
            case v =>
                // settled: the effect cannot occur, so the value passes through
                // strictly with no region node; the cast only shrinks the row
                v.asInstanceOf[A < (S & S2)]
        end match
    end handleLoop

    @nowarn("msg=anonymous")
    inline def handleLoopWith[I[_], O[_], E <: ArrowEffect[I, O], A, S, S2, B, S3](inline tag: Tag[E], v: A < (E & S))(
        inline f: [X] => I[X] => Loop.Outcome[O[X] < (E & S & S2), A] < S2
    )(inline cont: A => B < S3)(using inline _frame: Frame): B < (S & S2 & S3) =
        @nowarn("msg=anonymous") def mapLoop[C, S4](v: A < S4, next: Arrow[B, C, S4]): C < (S3 & S4) =
            def arrow =
                new Arrow.Transform[A, C, S3 & S4]:
                    def frame = _frame
                    def apply[D, S5](v2: A < S5, next2: Arrow[C, D, S5]) =
                        mapLoop(v2, next.chain(next2))
            v match
                case kyo: Kyo[A, S4] @unchecked =>
                    kyo.map(arrow)
                case v =>
                    val res  = Kyo.unnest(v)
                    val step = next.step
                    step.head(cont(res), step.tail)
            end match
        end mapLoop
        v match
            case kyo: Kyo[?, ?] =>
                val handler =
                    new Handler.Loop[I, O, E, A, S & S2](tag):
                        def apply[X](input: I[X]) = f(input)
                val exit =
                    new Arrow.Transform[A, B, S3]:
                        def frame = _frame
                        def apply[C, S4](v2: A < S4, next2: Arrow[B, C, S4]) =
                            mapLoop(v2, next2)
                new Kyo.Handled[I, O, E, A, B, S & S2 & S3](v, handler, exit)
            case v =>
                // settled: the effect cannot occur, so the continuation
                // applies strictly with no region node
                cont(Kyo.unnest(v))
        end match
    end handleLoopWith

    @nowarn("msg=anonymous")
    inline def handleLoop[I[_], O[_], E <: ArrowEffect[I, O], A, S, S2, State](inline tag: Tag[E], state: State, v: A < (E & S))(
        inline f: [X] => (I[X], State) => Loop.Outcome2[State, O[X] < (E & S & S2), A] < S2
    )(using inline _frame: Frame): A < (S & S2) =
        v match
            case kyo: Kyo[?, ?] =>
                val handler =
                    new Handler.LoopState[I, O, E, A, S & S2, State](tag, state):
                        def apply[X](input: I[X], state: State) = f(input, state)
                new Kyo.Handled[I, O, E, A, A, S & S2](v, handler, Arrow[A])
            case v =>
                // settled: the effect cannot occur, so the value passes through
                // strictly with no region node; the cast only shrinks the row
                v.asInstanceOf[A < (S & S2)]
        end match
    end handleLoop

    @nowarn("msg=anonymous")
    inline def handleLoopWith[I[_], O[_], E <: ArrowEffect[I, O], A, S, S2, State, B, S3](inline tag: Tag[E], state: State, v: A < (E & S))(
        inline f: [X] => (I[X], State) => Loop.Outcome2[State, O[X] < (E & S & S2), A] < S2
    )(inline cont: A => B < S3)(using inline _frame: Frame): B < (S & S2 & S3) =
        @nowarn("msg=anonymous") def mapLoop[C, S4](v: A < S4, next: Arrow[B, C, S4]): C < (S3 & S4) =
            def arrow =
                new Arrow.Transform[A, C, S3 & S4]:
                    def frame = _frame
                    def apply[D, S5](v2: A < S5, next2: Arrow[C, D, S5]) =
                        mapLoop(v2, next.chain(next2))
            v match
                case kyo: Kyo[A, S4] @unchecked =>
                    kyo.map(arrow)
                case v =>
                    val res  = Kyo.unnest(v)
                    val step = next.step
                    step.head(cont(res), step.tail)
            end match
        end mapLoop
        v match
            case kyo: Kyo[?, ?] =>
                val handler =
                    new Handler.LoopState[I, O, E, A, S & S2, State](tag, state):
                        def apply[X](input: I[X], state: State) = f(input, state)
                val exit =
                    new Arrow.Transform[A, B, S3]:
                        def frame = _frame
                        def apply[C, S4](v2: A < S4, next2: Arrow[B, C, S4]) =
                            mapLoop(v2, next2)
                new Kyo.Handled[I, O, E, A, B, S & S2 & S3](v, handler, exit)
            case v =>
                // settled: the effect cannot occur, so the continuation
                // applies strictly with no region node
                cont(Kyo.unnest(v))
        end match
    end handleLoopWith

    // the eager driver: answers operations of the tag while the clause
    // returns a present continuation and parks at a refusal, a foreign
    // suspension, a region node, a pending Safepoint.stop request, or
    // budget exhaustion, returning the computation as it stands
    inline def handlePartial[I[_], O[_], E <: ArrowEffect[I, O], A, S](inline tag: Tag[E], v: A < (E & S))(
        inline f: [X] => (I[X], O[X] => A < (E & S)) => Maybe[A < (E & S)]
    )(using inline _frame: Frame): A < (E & S) =
        @tailrec def partialLoop(v: A < (E & S)): A < (E & S) =
            v match
                case kyo: Kyo.Suspend[?, ?, ?, ?, ?, ?] if kyo.tag.erased <:< tag.erased =>
                    val anchored =
                        kyo.asInstanceOf[Kyo.Suspend[I, O, E, Any, A, E & S]] // TODO in the ENTIRE MODULE, prefer to simply declare the exected types in the matching and use @unchecked instead of using casts
                    f[Any](anchored.input, o => anchored.cont(o)) match
                        case Maybe.Present(v2) => partialLoop(v2)
                        case Maybe.Absent      => v
                case kyo: Kyo.Defer[?, ?, ?] =>
                    val slot = Safepoint.get()
                    if Safepoint.stopped(slot) || !Safepoint.enter(slot) then v
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
    end handlePartial

end ArrowEffect

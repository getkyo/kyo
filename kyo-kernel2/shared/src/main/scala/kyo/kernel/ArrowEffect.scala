package kyo.kernel

import kyo.Arrow
import kyo.Frame
import kyo.Maybe
import kyo.Tag
import kyo.kernel.internal.*
import scala.annotation.nowarn
import scala.annotation.tailrec
import scala.annotation.targetName

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
        new Arrow.Transform[O[V], B, E & S] with Kyo.Suspend[I, O, E, V, B, E & S]:
            self =>
            def tag   = _tag
            def input = _input
            def frame = _frame
            def cont  = this
            def apply[C, S2](v: O[V] < S2, next: Arrow[B, C, S2]) =
                v match
                    case kyo: Kyo[O[V], S2] @unchecked =>
                        // a pending input re-suspends through this same
                        // transform with the remaining steps chained after it
                        kyo.map(self.chain(next))
                    case v =>
                        // no budget check: f either suspends, returning the node
                        // flat, or settles into the chained arrows, whose strict
                        // segments carry their own checks in map
                        val res  = Kyo.unnest(v)
                        val step = next.step
                        step.head(f(res), step.tail)
                end match
            end apply
        end new
    end suspendWith

    @nowarn("msg=anonymous")
    inline def handle[I[_], O[_], E <: ArrowEffect[I, O], A, S](inline _tag: Tag[E], v: A < (E & S))(
        inline f: [X] => (I[X], O[X] => A < (E & S)) => A < (E & S)
    )(using inline _frame: Frame): A < S =
        v match
            case kyo: Kyo[?, ?] =>
                val handler =
                    new Handler.Cont[I, O, E, A, S]:
                        def tag                                              = _tag
                        def apply[X](input: I[X], cont: O[X] => A < (E & S)) = f(input, cont)
                new Kyo.Handled[I, O, E, A, A, S, Any](v, handler, Arrow[A])
            case v =>
                // settled: the effect cannot occur, so the value passes through
                // strictly with no region node; the cast only shrinks the row
                v.asInstanceOf[A < S]
        end match
    end handle

    @nowarn("msg=anonymous")
    inline def handleWith[I[_], O[_], E <: ArrowEffect[I, O], A, S, B, S2](inline _tag: Tag[E], v: A < (E & S))(
        inline f: [X] => (I[X], O[X] => A < (E & S)) => A < (E & S)
    )(inline cont: A => B < S2)(using inline _frame: Frame): B < (S & S2) =
        v match
            case kyo: Kyo[?, ?] =>
                // one allocation: the object is the handler and the region's
                // exit arrow
                val handler =
                    new Arrow.Transform[A, B, S2] with Handler.Cont[I, O, E, A, S]:
                        self =>
                        def tag                                              = _tag
                        def frame                                            = _frame
                        def apply[X](input: I[X], cont: O[X] => A < (E & S)) = f(input, cont)
                        def apply[C, S3](v2: A < S3, next: Arrow[B, C, S3]) =
                            v2 match
                                case kyo: Kyo[A, S3] @unchecked =>
                                    kyo.map(self.chain(next))
                                case v2 =>
                                    val res  = Kyo.unnest(v2)
                                    val step = next.step
                                    step.head(cont(res), step.tail)
                new Kyo.Handled[I, O, E, A, B, S, S2](v, handler, handler)
            case v =>
                // settled: the effect cannot occur, so the continuation
                // applies strictly with no region node
                cont(Kyo.unnest(v))
        end match
    end handleWith

    @nowarn("msg=anonymous")
    inline def handleLoop[I[_], O[_], E <: ArrowEffect[I, O], A, S, S2](inline _tag: Tag[E], v: A < (E & S))(
        inline f: [X] => I[X] => Loop.Outcome[O[X] < (E & S & S2), A] < S2
    )(using inline _frame: Frame): A < (S & S2) =
        v match
            case kyo: Kyo[?, ?] =>
                val handler =
                    new Handler.Loop[I, O, E, A, S & S2]:
                        def tag = _tag
                        @targetName("applyInput")
                        def apply[X](input: I[X]) = f(input)
                new Kyo.Handled[I, O, E, A, A, S & S2, Any](v, handler, Arrow[A])
            case v =>
                // settled: the effect cannot occur, so the value passes through
                // strictly with no region node; the cast only shrinks the row
                v.asInstanceOf[A < (S & S2)]
        end match
    end handleLoop

    @nowarn("msg=anonymous")
    inline def handleLoopWith[I[_], O[_], E <: ArrowEffect[I, O], A, S, S2, B, S3](inline _tag: Tag[E], v: A < (E & S))(
        inline f: [X] => I[X] => Loop.Outcome[O[X] < (E & S & S2), A] < S2
    )(inline cont: A => B < S3)(using inline _frame: Frame): B < (S & S2 & S3) =
        v match
            case kyo: Kyo[?, ?] =>
                // one allocation: the object is the handler and the region's
                // exit arrow
                val handler =
                    new Arrow.Transform[A, B, S3] with Handler.Loop[I, O, E, A, S & S2]:
                        self =>
                        def tag   = _tag
                        def frame = _frame
                        @targetName("applyInput")
                        def apply[X](input: I[X]) = f(input)
                        def apply[C, S4](v2: A < S4, next: Arrow[B, C, S4]) =
                            v2 match
                                case kyo: Kyo[A, S4] @unchecked =>
                                    kyo.map(self.chain(next))
                                case v2 =>
                                    val res  = Kyo.unnest(v2)
                                    val step = next.step
                                    step.head(cont(res), step.tail)
                new Kyo.Handled[I, O, E, A, B, S & S2, S3](v, handler, handler)
            case v =>
                // settled: the effect cannot occur, so the continuation
                // applies strictly with no region node
                cont(Kyo.unnest(v))
        end match
    end handleLoopWith

    @nowarn("msg=anonymous")
    inline def handleLoop[I[_], O[_], E <: ArrowEffect[I, O], A, S, S2, State](inline _tag: Tag[E], state: State, v: A < (E & S))(
        inline f: [X] => (I[X], State) => Loop.Outcome2[State, O[X] < (E & S & S2), A] < S2
    )(using inline _frame: Frame): A < (S & S2) =
        v match
            case kyo: Kyo[?, ?] =>
                val state0 = state
                val handler =
                    new Handler.LoopState[I, O, E, A, S & S2, State]:
                        def tag                                 = _tag
                        def apply[X](input: I[X], state: State) = f(input, state)
                new Kyo.HandledState[I, O, E, A, A, S & S2, Any, State](v, handler, Arrow[A], state0)
            case v =>
                // settled: the effect cannot occur, so the value passes through
                // strictly with no region node; the cast only shrinks the row
                v.asInstanceOf[A < (S & S2)]
        end match
    end handleLoop

    @nowarn("msg=anonymous")
    inline def handleLoopWith[I[_], O[_], E <: ArrowEffect[I, O], A, S, S2, State, B, S3](
        inline _tag: Tag[E],
        state: State,
        v: A < (E & S)
    )(
        inline f: [X] => (I[X], State) => Loop.Outcome2[State, O[X] < (E & S & S2), A] < S2
    )(inline cont: A => B < S3)(using inline _frame: Frame): B < (S & S2 & S3) =
        v match
            case kyo: Kyo[?, ?] =>
                val state0 = state
                // one allocation: the object is the handler and the region's
                // exit arrow
                val handler =
                    new Arrow.Transform[A, B, S3] with Handler.LoopState[I, O, E, A, S & S2, State]:
                        self =>
                        def tag                                 = _tag
                        def frame                               = _frame
                        def apply[X](input: I[X], state: State) = f(input, state)
                        def apply[C, S4](v2: A < S4, next: Arrow[B, C, S4]) =
                            v2 match
                                case kyo: Kyo[A, S4] @unchecked =>
                                    kyo.map(self.chain(next))
                                case v2 =>
                                    val res  = Kyo.unnest(v2)
                                    val step = next.step
                                    step.head(cont(res), step.tail)
                new Kyo.HandledState[I, O, E, A, B, S & S2, S3, State](v, handler, handler, state0)
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
    inline def handlePartial[I[_], O[_], E <: ArrowEffect[I, O], A, S](inline _tag: Tag[E], v: A < (E & S))(
        inline f: [X] => (I[X], O[X] => A < (E & S)) => Maybe[A < (E & S)]
    )(using inline _frame: Frame): A < (E & S) =
        @tailrec def partialLoop(v: A < (E & S)): A < (E & S) =
            v match
                case kyo: Kyo.Suspend[I, O, E, Any, A, E & S] @unchecked if kyo.tag <:< _tag =>
                    f[Any](kyo.input, o => kyo.cont(o)) match
                        case Maybe.Present(v2) => partialLoop(v2)
                        case Maybe.Absent      => v
                case kyo: Kyo.Defer[Any, A, E & S] @unchecked =>
                    val slot = Safepoint.get()
                    if Safepoint.stopped(slot) || !Safepoint.enter(slot) then v
                    else
                        val w =
                            val step = kyo.cont.step
                            step.head(kyo.value, step.tail)
                        Safepoint.exit(slot)
                        partialLoop(w)
                    end if
                case v =>
                    v
        end partialLoop

        partialLoop(v)
    end handlePartial

end ArrowEffect

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
                // one allocation: the object is the handler and the region node
                new Handler.Cont[I, O, E, A, S] with Kyo.Handled[I, O, E, A, A, S, Any]:
                    def tag                                              = _tag
                    def apply[X](input: I[X], cont: O[X] => A < (E & S)) = f(input, cont)
                    val value                                            = v
                    def handler                                          = this
                    def exit                                             = Arrow[A]
            case v =>
                // no unnest: the value stays inside the computation, so its
                // nesting box stays on; only crossing to a plain function
                // parameter unnests, as in handleWith
                v.asInstanceOf[A < S]
        end match
    end handle

    @nowarn("msg=anonymous")
    inline def handleWith[I[_], O[_], E <: ArrowEffect[I, O], A, S, B, S2](inline _tag: Tag[E], v: A < (E & S))(
        inline f: [X] => (I[X], O[X] => A < (E & S)) => A < (E & S)
    )(inline cont: A => B < S2)(using inline _frame: Frame): B < (S & S2) =
        v match
            case kyo: Kyo[?, ?] =>
                // one allocation: the object is the handler, the region node,
                // and the region's exit arrow
                new Arrow.Transform[A, B, S2] with Handler.Cont[I, O, E, A, S] with Kyo.Handled[I, O, E, A, B, S, S2]:
                    self =>
                    def tag                                              = _tag
                    def frame                                            = _frame
                    val value                                            = v
                    def handler                                          = this
                    def exit                                             = this
                    def apply[X](input: I[X], cont: O[X] => A < (E & S)) = f(input, cont)
                    def apply[C, S3](v2: A < S3, next: Arrow[B, C, S3]) =
                        v2 match
                            case kyo: Kyo[A, S3] @unchecked =>
                                kyo.map(self.chain(next))
                            case v2 =>
                                val res  = Kyo.unnest(v2)
                                val step = next.step
                                step.head(cont(res), step.tail)
            case v =>
                cont(Kyo.unnest(v))
        end match
    end handleWith

    @nowarn("msg=anonymous")
    inline def handleLoop[I[_], O[_], E <: ArrowEffect[I, O], A, S, S2](inline _tag: Tag[E], v: A < (E & S))(
        inline f: [X] => I[X] => Loop.Outcome[O[X] < (E & S & S2), A] < S2
    )(using inline _frame: Frame): A < (S & S2) =
        v match
            case kyo: Kyo[?, ?] =>
                // one allocation: the object is the handler and the region node
                new Handler.Loop[I, O, E, A, S & S2] with Kyo.Handled[I, O, E, A, A, S & S2, Any]:
                    def tag = _tag
                    @targetName("applyInput")
                    def apply[X](input: I[X]) = f(input)
                    val value                 = v
                    def handler               = this
                    def exit                  = Arrow[A]
            case v =>
                // no unnest: the value stays inside the computation, so its
                // nesting box stays on
                v.asInstanceOf[A < (S & S2)]
        end match
    end handleLoop

    @nowarn("msg=anonymous")
    inline def handleLoopWith[I[_], O[_], E <: ArrowEffect[I, O], A, S, S2, B, S3](inline _tag: Tag[E], v: A < (E & S))(
        inline f: [X] => I[X] => Loop.Outcome[O[X] < (E & S & S2), A] < S2
    )(inline cont: A => B < S3)(using inline _frame: Frame): B < (S & S2 & S3) =
        v match
            case kyo: Kyo[?, ?] =>
                // one allocation: the object is the handler, the region node,
                // and the region's exit arrow
                new Arrow.Transform[A, B, S3] with Handler.Loop[I, O, E, A, S & S2] with Kyo.Handled[I, O, E, A, B, S & S2, S3]:
                    self =>
                    def tag     = _tag
                    def frame   = _frame
                    val value   = v
                    def handler = this
                    def exit    = this
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
                // one allocation: the object is the handler and the region node
                new Handler.LoopState[I, O, E, A, S & S2, State] with Kyo.HandledState[I, O, E, A, A, S & S2, Any, State]:
                    def tag                                 = _tag
                    def apply[X](input: I[X], state: State) = f(input, state)
                    val value                               = v
                    def handler                             = this
                    def exit                                = Arrow[A]
                    val state                               = state0
                end new
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
                // one allocation: the object is the handler, the region node,
                // and the region's exit arrow
                new Arrow.Transform[A, B, S3] with Handler.LoopState[I, O, E, A, S & S2, State]
                    with Kyo.HandledState[I, O, E, A, B, S & S2, S3, State]:
                    self =>
                    def tag                                 = _tag
                    def frame                               = _frame
                    val value                               = v
                    def handler                             = this
                    def exit                                = this
                    val state                               = state0
                    def apply[X](input: I[X], state: State) = f(input, state)
                    def apply[C, S4](v2: A < S4, next: Arrow[B, C, S4]) =
                        v2 match
                            case kyo: Kyo[A, S4] @unchecked =>
                                kyo.map(self.chain(next))
                            case v2 =>
                                val res  = Kyo.unnest(v2)
                                val step = next.step
                                step.head(cont(res), step.tail)
                end new
            case v =>
                cont(Kyo.unnest(v))
        end match
    end handleLoopWith

    /** Answers the first operation of `E` and leaves.
      *
      * `f` receives the operation's input and its continuation, whose row still carries `E`: the operations after the first one are not
      * answered by this call, and the continuation is a value, so it can be resumed later, more than once, or not at all. `done` produces
      * the result when the computation settles without ever raising `E`.
      */
    @nowarn("msg=anonymous")
    inline def handleFirst[I[_], O[_], E <: ArrowEffect[I, O], A, S, B, S2](inline _tag: Tag[E], v: A < (E & S))(
        inline f: [X] => (I[X], O[X] => A < (E & S)) => B < S2
    )(inline done: A => B < S2)(using inline _frame: Frame): B < (S & S2) =
        v match
            case kyo: Kyo[?, ?] =>
                // one allocation: the object is the handler and the region node
                new Handler.First[I, O, E, A, B, S, S2] with Kyo.HandledFirst[I, O, E, A, B, B, S, S2, Any]:
                    def tag                                              = _tag
                    def apply[X](input: I[X], cont: O[X] => A < (E & S)) = f(input, cont)
                    @targetName("applyDone")
                    def apply(a: A) = done(a)
                    val value       = v
                    def handler     = this
                    def exit        = Arrow[B]
            case v =>
                // settled: the effect cannot occur, so the done clause applies
                // strictly with no region node
                done(Kyo.unnest(v))
        end match
    end handleFirst

    /** Handles `E` with `f` and routes the non-fatal failures of one pass to `recover`.
      *
      * The recovery covers the handled computation, including the steps resumed after a foreign operation, and every invocation of `f`.
      * A region nested inside the computation evaluates on its own, so its internals are not covered: that is the boundary
      * [[Effect.catching]] draws. The recovered value carries no `E`, so it does not reach `f`.
      */
    private[kyo] inline def handleCatching[I[_], O[_], E <: ArrowEffect[I, O], A, S, S2](
        inline _tag: Tag[E],
        inline v: => A < (E & S)
    )(
        inline f: [X] => (I[X], O[X] => A < (E & S & S2)) => A < (E & S & S2)
    )(inline recover: Throwable => A < (S & S2))(using inline _frame: Frame): A < (S & S2) =
        handle[I, O, E, A, S & S2](_tag, Effect.catching(v)(recover))(
            [X] => (input, cont) => Effect.catching(f(input, cont))(recover)
        )

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
                    if Safepoint.consumeStopped(Safepoint.get()) then v
                    else
                        val step = kyo.cont.step
                        partialLoop(step.head(kyo.value, step.tail))
                case v =>
                    v
        end partialLoop

        partialLoop(v)
    end handlePartial

end ArrowEffect

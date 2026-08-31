package kyo.proto.kernel

import kyo.Frame
import kyo.Id
import kyo.Maybe
import kyo.Tag
import kyo.proto.Arrow
import kyo.proto.Arrow.Transform
import kyo.proto.Loop
import kyo.proto.kernel.internal.Eval
import kyo.proto.kernel.internal.Handler.HandlerCont
import kyo.proto.kernel.internal.Handler.HandlerContOp
import kyo.proto.kernel.internal.Handler.HandlerLoop
import kyo.proto.kernel.internal.Kyo
import kyo.proto.kernel.internal.Nested
import kyo.proto.kernel.internal.Pending
import scala.annotation.nowarn
import scala.annotation.tailrec
import scala.util.control.NonFatal

abstract class ArrowEffect[-I[_], +O[_]] extends Effect

object ArrowEffect:

    @nowarn("msg=anonymous")
    inline def suspend[C](
        using inline _frame: Frame
    )[I[_], O[_], E <: ArrowEffect[I, O]](
        inline effectTag: Tag[E],
        inline effectInput: I[C]
    ): O[C] < E =

        new Kyo.SuspendArrow[I, O, E, C, O[C], E]:
            override def frame = _frame
            def tag            = effectTag
            def input          = effectInput
            def cont           = Arrow.id

    @nowarn("msg=anonymous")
    inline def suspendWith[C](
        using inline _frame: Frame
    )[I[_], O[_], E <: ArrowEffect[I, O], B, S](
        inline effectTag: Tag[E],
        inline effectInput: I[C]
    )(
        inline f: O[C] => B < S
    ): B < (E & S) =

        new Kyo.SuspendArrowWith[I, O, E, C, B, E & S]:
            override def frame = _frame
            def tag            = effectTag
            def input          = effectInput
            def cont           = this
            override def apply[D, S2](v: O[C] < S2, cont2: Arrow[B, D, S2]) =
                v match
                    case kyo: Pending[O[C], S2] @unchecked => Effect.defer(kyo, this, cont2)
                    case _                                 => cont2(f(Nested.unnest[O[C]](v)), Arrow.id)

    @nowarn("msg=anonymous")
    inline def handleCont[I[_], O[_], E <: ArrowEffect[I, O], A, B, S, S2](
        inline effectTag: Tag[E],
        v: A < (E & S)
    )(
        inline handle: [C] => (I[C], Arrow[O[C], A, E & S & S2]) => A < (E & S & S2),
        inline done: A => B < (S & S2)
    )(using inline _frame: Frame): B < (S & S2) =
        def onDone(v0: A): B < (S & S2) = done(v0)
        v match
            case _: Pending[?, ?] =>
                val h =
                    new HandlerCont[I, O, E, A, B, S & S2]:
                        def tag = effectTag
                        def run[X](input: I[X], next: Arrow[O[X], A, E & S & S2]) =
                            handle[X](input, next)
                        def done(state: Unit, v0: A) = onDone(v0)

                new Kyo.Handle[E, A, B, B, S & S2, Unit]:
                    override def frame = _frame
                    def value          = v
                    def handler        = h
                    def state          = ()
                    def cont           = Arrow.id
                end new
            case _ => onDone(Nested.unnest(v))
        end match
    end handleCont

    @nowarn("msg=anonymous")
    inline def handleCont[I[_], O[_], E <: ArrowEffect[I, O], A, B, S, S2](
        inline effectTag: Tag[E],
        v: A < (E & S)
    )(
        inline handle: [C] => (I[C], Arrow[O[C], A, E & S & S2]) => A < (E & S & S2),
        inline done: A => B < (S & S2),
        inline recover: Throwable => Maybe[B < (S & S2)]
    )(using inline _frame: Frame): B < (S & S2) =
        def onDone(v0: A): B < (S & S2)                   = done(v0)
        def onRecover(ex: Throwable): Maybe[B < (S & S2)] = recover(ex)
        v match
            case _: Pending[?, ?] =>
                val h =
                    new HandlerCont[I, O, E, A, B, S & S2]:
                        def tag = effectTag
                        def run[X](input: I[X], next: Arrow[O[X], A, E & S & S2]) =
                            handle[X](input, next)
                        def done(state: Unit, v0: A)                     = onDone(v0)
                        override def recover(state: Unit, ex: Throwable) = onRecover(ex)

                new Kyo.Handle[E, A, B, B, S & S2, Unit]:
                    override def frame = _frame
                    def value          = v
                    def handler        = h
                    def state          = ()
                    def cont           = Arrow.id
                end new
            case _ =>

                try onDone(Nested.unnest(v))
                catch
                    case ex if NonFatal(ex) => onRecover(ex).getOrElse(throw ex)
        end match
    end handleCont

    inline def handleCont[I[_], O[_], E <: ArrowEffect[I, O], A, S, S2](
        inline effectTag: Tag[E],
        v: A < (E & S)
    )(
        inline handle: [C] => (I[C], Arrow[O[C], A, E & S & S2]) => A < (E & S & S2)
    )(using inline _frame: Frame): A < (S & S2) =
        handleCont(effectTag, v)(handle, a => a)

    @nowarn("msg=anonymous")
    inline def handleContOperation[E <: ArrowEffect[?, ?], A, B, S, S2](
        inline effectTag: Tag[E],
        v: A < (E & S)
    )(
        inline handle: [X] => (X < E, Arrow[X, A, E & S & S2]) => A < (E & S & S2),
        inline done: A => B < (S & S2)
    )(using inline _frame: Frame): B < (S & S2) =
        def onDone(v0: A): B < (S & S2) = done(v0)
        v match
            case _: Pending[?, ?] =>
                val h =
                    new HandlerContOp[E, A, B, S & S2]:
                        def tag = effectTag
                        def run[X](operation: X < E, next: Arrow[X, A, E & S & S2]) =
                            handle[X](operation, next)
                        def done(state: Unit, v0: A) = onDone(v0)

                new Kyo.Handle[E, A, B, B, S & S2, Unit]:
                    override def frame = _frame
                    def value          = v
                    def handler        = h
                    def state          = ()
                    def cont           = Arrow.id
                end new
            case _ => onDone(Nested.unnest(v))
        end match
    end handleContOperation

    inline def handleContOperation[E <: ArrowEffect[?, ?], A, S, S2](
        inline effectTag: Tag[E],
        v: A < (E & S)
    )(
        inline handle: [X] => (X < E, Arrow[X, A, E & S & S2]) => A < (E & S & S2)
    )(using inline _frame: Frame): A < (S & S2) =
        handleContOperation(effectTag, v)(handle, a => a)

    abstract private[kyo] class FirstSuspended[I[_], O[_], E <: ArrowEffect[I, O], A, S]:
        type C
        def input: I[C]
        def cont: Arrow[O[C], A, E & S]
    end FirstSuspended

    @nowarn("msg=anonymous")
    private[kyo] inline def handleFirst[I[_], O[_], E <: ArrowEffect[I, O], A, B, S, S2](
        inline effectTag: Tag[E],
        v: A < (E & S)
    )(
        inline handle: [C] => (I[C], Arrow[O[C], A, E & S]) => B < (S & S2),
        inline done: A => B < (S & S2)
    )(using inline _frame: Frame): B < (S & S2) =
        handleCont[I, O, E, A | FirstSuspended[I, O, E, A, E & S], B, S, S2](effectTag, v)(
            [C0] =>
                (input0, cont0) =>
                    new FirstSuspended[I, O, E, A, E & S]:
                        type C = C0
                        def input = input0

                        def cont = cont0.asInstanceOf[Arrow[O[C0], A, E & S]]
            ,
            r =>
                r match
                    case first: FirstSuspended[I, O, E, A, E & S] @unchecked =>
                        handle[first.C](first.input, first.cont)
                    case a =>

                        done(a.asInstanceOf[A])
        )

    private[kyo] def dispatchFirst[I[_], O[_], E <: ArrowEffect[I, O], A, S](
        effectTag: Tag[E],
        v: A < (E & S)
    )(
        f: [C] => I[C] => Unit
    ): Unit =
        @tailrec def loop(x: Any): Unit =
            x match
                case kyo: Kyo.SuspendArrow[I, O, E, c, ?, ?] @unchecked =>

                    if effectTag.erased <:< kyo.tag.erased then f[c](kyo.input)
                case kyo: Kyo.Handle[?, ?, ?, ?, ?, ?] => loop(kyo.value)
                case kyo: Kyo.Park[?, ?]               => loop(kyo.value)
                case kyo: Kyo.Defer[?, ?, ?, ?]        => loop(kyo.value)
                case _                                 => ()
        loop(v)
    end dispatchFirst

    inline def handleLoop[I[_], O[_], E <: ArrowEffect[I, O], A, B, S, S2](
        inline effectTag: Tag[E],
        v: A < (E & S)
    )(
        inline handle: [C] => I[C] => Loop.Outcome2[Unit, O[C] < (E & S & S2), B < (S & S2)] < (S & S2),
        inline done: A => B < (S & S2)
    )(using inline _frame: Frame): B < (S & S2) =
        handleLoopState[I, O, E, A, B, S, S2, Unit](effectTag, (), v)(
            [C] => (st: Unit, input: I[C]) => handle[C](input),
            (st, v0) => done(v0)
        )

    inline def handleLoop[I[_], O[_], E <: ArrowEffect[I, O], A, B, S, S2](
        inline effectTag: Tag[E],
        v: A < (E & S)
    )(
        inline handle: [C] => I[C] => Loop.Outcome2[Unit, O[C] < (E & S & S2), B < (S & S2)] < (S & S2),
        inline done: A => B < (S & S2),
        inline recover: Throwable => Maybe[B < (S & S2)]
    )(using inline _frame: Frame): B < (S & S2) =
        handleLoopState[I, O, E, A, B, S, S2, Unit](effectTag, (), v)(
            [C] => (st: Unit, input: I[C]) => handle[C](input),
            (st, v0) => done(v0),
            (st, ex) => recover(ex)
        )

    inline def handleLoop[I[_], O[_], E <: ArrowEffect[I, O], A, S, S2](
        inline effectTag: Tag[E],
        v: A < (E & S)
    )(
        inline handle: [C] => I[C] => Loop.Outcome2[Unit, O[C] < (E & S & S2), A < (S & S2)] < (S & S2)
    )(using inline _frame: Frame): A < (S & S2) =
        handleLoop(effectTag, v)(handle, a => a)

    @nowarn("msg=anonymous")
    inline def handleLoopState[I[_], O[_], E <: ArrowEffect[I, O], A, B, S, S2, State](
        inline effectTag: Tag[E],
        state: State,
        v: A < (E & S)
    )(
        inline handle: [C] => (State, I[C]) => Loop.Outcome2[State, O[C] < (E & S & S2), B < (S & S2)] < (S & S2),
        inline done: (State, A) => B < (S & S2)
    )(using inline _frame: Frame): B < (S & S2) =
        def onDone(st: State, v0: A): B < (S & S2) = done(st, v0)
        v match
            case _: Pending[?, ?] =>
                val h =
                    new HandlerLoop[I, O, E, A, B, S & S2, State]:
                        def tag = effectTag
                        def run[X](st: State, input: I[X]) =
                            handle[X](st, input)
                        def done(st: State, v0: A) = onDone(st, v0)
                val state0 = state

                new Kyo.Handle[E, A, B, B, S & S2, State]:
                    override def frame = _frame
                    def value          = v
                    def handler        = h
                    def state          = state0
                    def cont           = Arrow.id
                end new
            case _ => onDone(state, Nested.unnest(v))
        end match
    end handleLoopState

    @nowarn("msg=anonymous")
    inline def handleLoopState[I[_], O[_], E <: ArrowEffect[I, O], A, B, S, S2, State](
        inline effectTag: Tag[E],
        state: State,
        v: A < (E & S)
    )(
        inline handle: [C] => (State, I[C]) => Loop.Outcome2[State, O[C] < (E & S & S2), B < (S & S2)] < (S & S2),
        inline done: (State, A) => B < (S & S2),
        inline recover: (State, Throwable) => Maybe[B < (S & S2)]
    )(using inline _frame: Frame): B < (S & S2) =
        def onDone(st: State, v0: A): B < (S & S2)                   = done(st, v0)
        def onRecover(st: State, ex: Throwable): Maybe[B < (S & S2)] = recover(st, ex)
        v match
            case _: Pending[?, ?] =>
                val h =
                    new HandlerLoop[I, O, E, A, B, S & S2, State]:
                        def tag = effectTag
                        def run[X](st: State, input: I[X]) =
                            handle[X](st, input)
                        def done(st: State, v0: A)                     = onDone(st, v0)
                        override def recover(st: State, ex: Throwable) = onRecover(st, ex)
                val state0 = state

                new Kyo.Handle[E, A, B, B, S & S2, State]:
                    override def frame = _frame
                    def value          = v
                    def handler        = h
                    def state          = state0
                    def cont           = Arrow.id
                end new
            case _ =>

                try onDone(state, Nested.unnest(v))
                catch
                    case ex if NonFatal(ex) => onRecover(state, ex).getOrElse(throw ex)
        end match
    end handleLoopState

    inline def handleLoopState[I[_], O[_], E <: ArrowEffect[I, O], A, S, S2, State](
        inline effectTag: Tag[E],
        state: State,
        v: A < (E & S)
    )(
        inline handle: [C] => (State, I[C]) => Loop.Outcome2[State, O[C] < (E & S & S2), A < (S & S2)] < (S & S2)
    )(using inline _frame: Frame): A < (S & S2) =
        handleLoopState(effectTag, state, v)(handle, (_, a) => a)

    @nowarn("msg=anonymous")
    inline def handleContWith[I[_], O[_], E <: ArrowEffect[I, O], A, B, S, S2](
        using inline _frame: Frame
    )(
        inline effectTag: Tag[E],
        v: A < (E & S)
    )(
        inline handle: [X] => (I[X], Arrow[O[X], A, E & S & S2]) => A < (E & S & S2),
        inline done: A => B < (S & S2)
    )[C, S3](
        inline f: B => C < S3
    ): C < (S & S2 & S3) =
        def onDone(v0: A): B < (S & S2) = done(v0)
        def onF(v0: B): C < S3          = f(v0)
        v match
            case _: Pending[?, ?] =>
                val h =
                    new HandlerCont[I, O, E, A, B, S & S2]:
                        def tag = effectTag
                        def run[X](input: I[X], next: Arrow[O[X], A, E & S & S2]) =
                            handle[X](input, next)
                        def done(state: Unit, v0: A) = onDone(v0)

                new Kyo.HandleWith[E, A, B, C, S & S2 & S3, Unit]:
                    override def frame = _frame
                    def value          = v
                    def handler        = h
                    def state          = ()
                    def cont           = this
                    override def apply[D, S4](b: B < S4, cont2: Arrow[C, D, S4]) =
                        b match
                            case kyo: Pending[B, S4] @unchecked => Effect.defer(kyo, this, cont2)
                            case _                              => cont2(onF(Nested.unnest[B](b)), Arrow.id)
                end new
            case _ => onDone(Nested.unnest(v)).map(onF)
        end match
    end handleContWith

    inline def handleLoopWith[I[_], O[_], E <: ArrowEffect[I, O], A, B, S, S2](
        using inline _frame: Frame
    )(
        inline effectTag: Tag[E],
        v: A < (E & S)
    )(
        inline handle: [X] => I[X] => Loop.Outcome2[Unit, O[X] < (E & S & S2), B < (S & S2)] < (S & S2),
        inline done: A => B < (S & S2)
    )[C, S3](
        inline f: B => C < S3
    ): C < (S & S2 & S3) =
        handleLoopStateWith[I, O, E, A, B, S, S2, Unit](effectTag, (), v)(
            [X] => (st: Unit, input: I[X]) => handle[X](input),
            (st, v0) => done(v0)
        )(f)

    @nowarn("msg=anonymous")
    inline def handleLoopStateWith[I[_], O[_], E <: ArrowEffect[I, O], A, B, S, S2, State](
        using inline _frame: Frame
    )(
        inline effectTag: Tag[E],
        state0: State,
        v: A < (E & S)
    )(
        inline handle: [X] => (State, I[X]) => Loop.Outcome2[State, O[X] < (E & S & S2), B < (S & S2)] < (S & S2),
        inline done: (State, A) => B < (S & S2)
    )[C, S3](
        inline f: B => C < S3
    ): C < (S & S2 & S3) =
        def onDone(st: State, v0: A): B < (S & S2) = done(st, v0)
        def onF(v0: B): C < S3                     = f(v0)
        v match
            case _: Pending[?, ?] =>
                val h =
                    new HandlerLoop[I, O, E, A, B, S & S2, State]:
                        def tag = effectTag
                        def run[X](st: State, input: I[X]) =
                            handle[X](st, input)
                        def done(st: State, v0: A) = onDone(st, v0)

                new Kyo.HandleWith[E, A, B, C, S & S2 & S3, State]:
                    override def frame = _frame
                    def value          = v
                    def handler        = h
                    def state          = state0
                    def cont           = this
                    override def apply[D, S4](b: B < S4, cont2: Arrow[C, D, S4]) =
                        b match
                            case kyo: Pending[B, S4] @unchecked => Effect.defer(kyo, this, cont2)
                            case _                              => cont2(onF(Nested.unnest[B](b)), Arrow.id)
                end new
            case _ => onDone(state0, Nested.unnest(v)).map(onF)
        end match
    end handleLoopStateWith

    sealed abstract class Mask[S] extends ArrowEffect[[A] =>> A < S, Id]

    object Mask:

        def apply[E](using
            Frame
        )[E2 >: E <: ArrowEffect[?, ?], A, S](v: A < (E2 & S))(
            using
            tag: Tag[E2],
            maskTag: Tag[Mask[E]]
        ): A < (Mask[E] & S) =
            handleContOperation(tag, v) {

                [X] => (operation, cont) => suspend[X](maskTag, operation).map(cont(_))
            }

        def run[S](using Frame)[A, S2](v: A < (Mask[S] & S2))(using tag: Tag[Mask[S]]): A < (S & S2) =
            handleCont(tag, v) {
                [C] => (input, cont) => input.map(cont(_))
            }
    end Mask

end ArrowEffect

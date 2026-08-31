package kyo.proto.kernel

import kyo.Frame
import kyo.Maybe
import kyo.Result
import kyo.Tag
import kyo.proto.Arrow
import kyo.proto.kernel.internal.Handler.HandlerContext
import kyo.proto.kernel.internal.Kyo
import kyo.proto.kernel.internal.Nested
import kyo.proto.kernel.internal.Pending
import scala.annotation.nowarn

abstract class ContextEffect[+A] extends Effect

object ContextEffect:

    @nowarn("msg=anonymous")
    inline def suspend[A, E <: ContextEffect[A]](inline effectTag: Tag[E])(using inline _frame: Frame): A < E =
        new Kyo.SuspendContext[A, E, A, E]:
            override def frame = _frame
            def tag            = effectTag
            def default        = Maybe.empty
            def cont           = Arrow.id

    @nowarn("msg=anonymous")
    inline def suspendWith[A, E <: ContextEffect[A], B, S](
        inline effectTag: Tag[E]
    )(
        inline f: A => B < S
    )(using inline _frame: Frame): B < (E & S) =
        new Kyo.SuspendContextWith[A, E, B, E & S]:
            override def frame = _frame
            def tag            = effectTag
            def default        = Maybe.empty
            def cont           = this
            override def apply[C, S2](v: A < S2, cont2: Arrow[B, C, S2]) =
                v match
                    case kyo: Pending[A, S2] @unchecked => Effect.defer(kyo, this, cont2)
                    case _                              => cont2(f(Nested.unnest[A](v)), Arrow.id)

    @nowarn("msg=anonymous")
    inline def suspend[A, E <: ContextEffect[A]](
        inline effectTag: Tag[E],
        inline defaultValue: => A
    )(using inline _frame: Frame): A < Any =
        new Kyo.SuspendContext[A, E, A, Any]:
            override def frame = _frame
            def tag            = effectTag
            def default        = Maybe(defaultValue)
            def cont           = Arrow.id

    @nowarn("msg=anonymous")
    inline def suspendWith[A, E <: ContextEffect[A], B, S](
        inline effectTag: Tag[E],
        inline defaultValue: => A
    )(
        inline f: A => B < S
    )(using inline _frame: Frame): B < S =
        new Kyo.SuspendContextWith[A, E, B, S]:
            override def frame = _frame
            def tag            = effectTag
            def default        = Maybe(defaultValue)
            def cont           = this
            override def apply[C, S2](v: A < S2, cont2: Arrow[B, C, S2]) =
                v match
                    case kyo: Pending[A, S2] @unchecked => Effect.defer(kyo, this, cont2)
                    case _                              => cont2(f(Nested.unnest[A](v)), Arrow.id)

    inline def handle[A, E <: ContextEffect[A], B, S](
        inline effectTag: Tag[E],
        inline value: A
    )(v: B < (E & S))(using inline _frame: Frame): B < S =
        handle(effectTag)((_: Maybe[A]) => value)(v)

    @nowarn("msg=anonymous")
    inline def handle[A, E <: ContextEffect[A], B, S](
        inline effectTag: Tag[E]
    )(
        inline derive: Maybe[A] => A,
        inline onFork: A => A < S = (current: A) => current,
        inline onJoin: (A, A, Result[Nothing, A]) => Result[Nothing, A] < S =
            (_: A, _: A, result: Result[Nothing, A]) => result
    )(v: B < (E & S))(using inline _frame: Frame): B < S =
        v match
            case _: Pending[?, ?] =>
                def derived(current: Maybe[A]): A = derive(current)
                val h =
                    new HandlerContext[A, E, B, B, S]:
                        def tag                                                     = effectTag
                        def derive(current: Maybe[A])                               = derived(current)
                        def fork(current: A)                                        = onFork(current)
                        def join(current: A, forked: A, result: Result[Nothing, A]) = onJoin(current, forked, result)
                        def done(state: A, v0: B)                                   = v0

                new Kyo.Handle[E, B, B, B, S, A]:
                    override def frame = _frame
                    def value          = v
                    def handler        = h
                    def state          = h.derive(Maybe.empty)
                    def cont           = Arrow.id
                end new
            case _ => Nested.unnest[B](v)
        end match
    end handle

end ContextEffect

package kyo.proto.kernel

import kyo.Frame
import kyo.Maybe
import kyo.Tag
import kyo.proto.Arrow
import kyo.proto.kernel.internal.Handler.ContextHandler
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

    // The child of an isolate inherits the binding and the merge keeps the parent's state.
    inline def handleInheritable[A, E <: ContextEffect[A], B, S](
        inline effectTag: Tag[E],
        inline value: A
    )(v: B < (E & S))(using inline _frame: Frame): B < S =
        handleInheritable(effectTag)((_: Maybe[A]) => value)(v)

    inline def handleInheritable[A, E <: ContextEffect[A], B, S](
        inline effectTag: Tag[E]
    )(
        inline derive: Maybe[A] => A
    )(v: B < (E & S))(using inline _frame: Frame): B < S =
        handle(effectTag)(derive, (parent: A) => parent, (parent: A, _: A, _: A) => parent)(v)

    @nowarn("msg=anonymous")
    inline def handle[A, E <: ContextEffect[A], B, S](
        inline effectTag: Tag[E]
    )(
        inline derive: Maybe[A] => A,
        inline fork: A => A,
        inline join: (A, A, A) => A,
        inline release: (A, Throwable) => Unit = (_: A, _: Throwable) => ()
    )(v: B < (E & S))(using inline _frame: Frame): B < S =
        v match
            case _: Pending[?, ?] =>
                def derived(outer: Maybe[A]): A             = derive(outer)
                def forked(parent: A): A                    = fork(parent)
                def joined(parent: A, fk: A, child: A): A   = join(parent, fk, child)
                def released(state: A, ex: Throwable): Unit = release(state, ex)
                val h =
                    new ContextHandler[A, E, B, B, S]:
                        def tag                                                    = effectTag
                        def derive(outer: Maybe[A])                                = derived(outer)
                        def fork(parent: A)                                        = forked(parent)
                        def join(parent: A, forked: A, child: A)                   = joined(parent, forked, child)
                        override private[kyo] def release(state: A, ex: Throwable) = released(state, ex)

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

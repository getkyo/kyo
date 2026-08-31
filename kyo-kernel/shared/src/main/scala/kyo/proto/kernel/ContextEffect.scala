package kyo.proto.kernel

import kyo.Frame
import kyo.Maybe
import kyo.Result
import kyo.Tag
import kyo.proto.Arrow
import kyo.proto.Arrow.Transform
import kyo.proto.kernel.internal.Context
import kyo.proto.kernel.internal.Handler.HandlerContext
import kyo.proto.kernel.internal.Kyo
import kyo.proto.kernel.internal.Nested
import kyo.proto.kernel.internal.Pending
import scala.annotation.nowarn

abstract class ContextEffect[+A] extends Effect

object ContextEffect:

    @nowarn("msg=anonymous")
    inline def suspend[A, E <: ContextEffect[A]](inline effectTag: Tag[E])(using inline _frame: Frame): A < E =

        new Kyo.SuspendContextTransform[A, E, A, E]:
            override def frame        = _frame
            def tag                   = effectTag
            def answers(ctx: Context) = ctx.contains(effectTag)
            def update(ctx: Context)  = ctx
            def cont                  = this
            override def apply[C, S2](v: Context < S2, cont2: Arrow[A, C, S2]) =
                v match
                    case kyo: Pending[Context, S2] @unchecked => Effect.defer(kyo, this, cont2)
                    case _ =>
                        cont2(Nested.nest[A, S2](Nested.unnest[Context](v).apply[A, E](effectTag)), Arrow.id)

    @nowarn("msg=anonymous")
    inline def suspendWith[A, E <: ContextEffect[A], B, S](
        inline effectTag: Tag[E]
    )(
        inline f: A => B < S
    )(using inline _frame: Frame): B < (E & S) =

        new Kyo.SuspendContextTransform[A, E, B, E & S]:
            override def frame        = _frame
            def tag                   = effectTag
            def answers(ctx: Context) = ctx.contains(effectTag)
            def update(ctx: Context)  = ctx
            def cont                  = this
            override def apply[C, S2](v: Context < S2, cont2: Arrow[B, C, S2]) =
                v match
                    case kyo: Pending[Context, S2] @unchecked => Effect.defer(kyo, this, cont2)
                    case _                                    => cont2(f(Nested.unnest[Context](v).apply[A, E](effectTag)), Arrow.id)

    @nowarn("msg=anonymous")
    inline def suspend[A, E <: ContextEffect[A]](
        inline effectTag: Tag[E],
        inline defaultValue: => A
    )(using inline _frame: Frame): A < Any =

        new Kyo.SuspendContextTransform[A, E, A, Any]:
            override def frame        = _frame
            def tag                   = effectTag
            def answers(ctx: Context) = true
            def update(ctx: Context)  = ctx
            def cont                  = this
            override def apply[C, S2](v: Context < S2, cont2: Arrow[A, C, S2]) =
                v match
                    case kyo: Pending[Context, S2] @unchecked => Effect.defer(kyo, this, cont2)
                    case _ =>
                        val ctx = Nested.unnest[Context](v)
                        val a   = if ctx.contains(effectTag) then ctx.apply[A, E](effectTag) else defaultValue
                        cont2(Nested.nest[A, S2](a), Arrow.id)

    @nowarn("msg=anonymous")
    inline def suspendWith[A, E <: ContextEffect[A], B, S](
        inline effectTag: Tag[E],
        inline defaultValue: => A
    )(
        inline f: A => B < S
    )(using inline _frame: Frame): B < S =

        new Kyo.SuspendContextTransform[A, E, B, S]:
            override def frame        = _frame
            def tag                   = effectTag
            def answers(ctx: Context) = true
            def update(ctx: Context)  = ctx
            def cont                  = this
            override def apply[C, S2](v: Context < S2, cont2: Arrow[B, C, S2]) =
                v match
                    case kyo: Pending[Context, S2] @unchecked => Effect.defer(kyo, this, cont2)
                    case _ =>
                        val ctx = Nested.unnest[Context](v)
                        val a   = if ctx.contains(effectTag) then ctx.apply[A, E](effectTag) else defaultValue
                        cont2(f(a), Arrow.id)

    @nowarn("msg=anonymous")
    inline def update[A, E <: ContextEffect[A]](
        inline effectTag: Tag[E]
    )(
        inline f: A => A
    )(using inline _frame: Frame): A < E =

        new Kyo.SuspendContextTransform[A, E, A, E]:
            override def frame        = _frame
            def tag                   = effectTag
            def answers(ctx: Context) = ctx.contains(effectTag)
            def update(ctx: Context)  = ctx.update(effectTag, f(ctx.apply[A, E](effectTag)))
            def cont                  = this
            override def apply[C, S2](v: Context < S2, cont2: Arrow[A, C, S2]) =
                v match
                    case kyo: Pending[Context, S2] @unchecked => Effect.defer(kyo, this, cont2)
                    case _ =>
                        cont2(Nested.nest[A, S2](Nested.unnest[Context](v).apply[A, E](effectTag)), Arrow.id)

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
                val h =
                    new HandlerContext[A, E, B, B, S]:
                        def tag                                                     = effectTag
                        def resolve(outer: Maybe[A])                                = derive(outer)
                        def fork(current: A)                                        = onFork(current)
                        def join(current: A, forked: A, result: Result[Nothing, A]) = onJoin(current, forked, result)
                        def done(state: A, v0: B)                                   = v0

                new Kyo.Handle[E, B, B, B, S, A]:
                    override def frame = _frame
                    def value          = v
                    def handler        = h
                    def state          = h.resolve(Maybe.empty)
                    def cont           = Arrow.id
                end new
            case _ => Nested.unnest[B](v)
        end match
    end handle

end ContextEffect

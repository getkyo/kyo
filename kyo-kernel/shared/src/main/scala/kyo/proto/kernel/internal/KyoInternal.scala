package kyo.proto.kernel.internal

import kyo.Frame
import kyo.Tag
import kyo.proto.Arrow
import kyo.proto.kernel.<
import kyo.proto.kernel.ArrowEffect
import kyo.proto.kernel.ContextEffect
import kyo.proto.kernel.Effect
import language.implicitConversions
import scala.annotation.publicInBinary
import scala.annotation.tailrec

private[proto] def short(v: Any): String =
    v match
        case v: Pending[?, ?]            => v.toString
        case v: Arrow.Chain[?, ?, ?, ?]  => v.toString
        case _: Arrow.Id[?]              => "Id"
        case _: Arrow.Transform[?, ?, ?] => "Transform"
        case v                           => v.toString

private[kyo] object Discarded extends Exception("continuation discarded", null, false, false)

private[proto] def site(frame: Frame): String =
    val callee = frame.calleeName
    if callee.isEmpty then s"${frame.callerName}(${frame.position.show})"
    else s"${frame.callerName}.$callee(${frame.position.show})"
end site

sealed trait Pending[+A, -S] extends kyo.proto.Kyo[A, S]:
    def frame: Frame = Frame.internal

    private[kyo] def release(ex: Throwable): Any < Any
end Pending

object Kyo:

    abstract class Defer[A, B, C, -S] @publicInBinary private[kyo] () extends Pending[C, S]:
        Debugger.onAlloc(this)

        private[kyo] def release(ex: Throwable): Any < Any =
            value match
                case p: Pending[?, ?] => p.release(ex)
                case _                => ()

        def value: A < S
        def contA: Arrow[A, B, S]
        def contB: Arrow[B, C, S]

        override def toString =
            def slot(a: Arrow[?, ?, ?]): String = if a eq this then s"this(${site(frame)})" else short(a)
            s"Defer(${short(value)}, ${slot(contA)}, ${slot(contB)})"
    end Defer

    abstract class DeferTransform[A, B, -S] extends Defer[A, B, B, S] with Arrow.Transform[A, B, S]

    abstract class SuspendArrowTransform[I[_], O[_], E <: ArrowEffect[I, O], State, A, S]
        extends SuspendArrow[I, O, E, State, A, S] with Arrow.Transform[O[State], A, S]

    abstract class SuspendContextTransform[State, E <: ContextEffect[State], A, S]
        extends SuspendContext[State, E, A, S] with Arrow.Transform[Context, A, S]

    abstract class HandleTransform[E <: Effect, A, B, C, -S, State]
        extends Handle[E, A, B, C, S, State] with Arrow.Transform[B, C, S]

    sealed abstract class Suspend[E <: Effect, A, S] extends Pending[A, S]:
        Debugger.onAlloc(this)

        private[kyo] def release(ex: Throwable): Any < Any = ()

        type Op
        def tag: Tag[E]
        def cont: Arrow[Op, A, S]
        def withCont[B, S2](c: Arrow[Op, B, S2]): Suspend[E, B, S2]
    end Suspend

    abstract class SuspendArrow[I[_], O[_], E <: ArrowEffect[I, O], State, A, S] extends Suspend[E, A, S]:
        type Op = O[State]
        def input: I[State]
        def withCont[B, S2](c: Arrow[Op, B, S2]) = SuspendArrow(tag, input, c)
        override def toString =
            s"SuspendArrow(${tag.show}, $input, ${if cont eq this then "this" else short(cont)})"
    end SuspendArrow

    object SuspendArrow:
        def apply[I[_], O[_], E <: ArrowEffect[I, O], State, A, S](
            t: Tag[E],
            in: I[State],
            c: Arrow[O[State], A, S]
        ): SuspendArrow[I, O, E, State, A, S] =
            new SuspendArrow[I, O, E, State, A, S]:
                def tag   = t
                def input = in
                def cont  = c
    end SuspendArrow

    abstract class SuspendContext[State, E <: ContextEffect[State], A, S] extends Suspend[E, A, S]:
        type Op = Context
        def answers(ctx: Context): Boolean
        def update(ctx: Context): Context
        def withCont[B, S2](c: Arrow[Op, B, S2]) =

            new SuspendContext[State, E, B, S2]:
                def tag                   = SuspendContext.this.tag
                def answers(ctx: Context) = SuspendContext.this.answers(ctx)
                def update(ctx: Context)  = SuspendContext.this.update(ctx)
                def cont                  = c
        override def toString =
            s"SuspendContext(${tag.show}, ${if cont eq this then "this" else short(cont)})"
    end SuspendContext

    def handle[E <: Effect, A, B, S, State](v: A < (E & S), handler: Handler[E, A, B, S, State], state: State): B < S =
        v match
            case kyo: Pending[A, E & S] @unchecked =>

                val h  = handler
                val st = state
                new Handle[E, A, B, B, S, State]:
                    def value   = kyo
                    def handler = h
                    def state   = st
                    def cont    = Arrow.id
                end new
            case _ =>

                handler.done(state, Nested.unnest[A](v))

    abstract class Handle[E <: Effect, A, B, C, -S, State] extends Pending[C, S]:
        Debugger.onAlloc(this)

        private[kyo] def release(ex: Throwable): Any < Any =
            Debugger.onRelease(handler, ex)
            value match
                case p: Pending[?, ?] => p.release(ex).andThen(handler.release(state, ex))(using Frame.internal)
                case _                => handler.release(state, ex)
        end release

        def value: A < (E & S)
        def handler: Handler[E, A, B, S, State]
        def state: State
        def cont: Arrow[B, C, S]

        override def toString =
            if cont.isInstanceOf[Arrow.Id[?]] then s"Handle(${short(value)}, $handler, $state)"
            else s"Handle(${short(value)}, $handler, $state, ${if cont eq this then "this" else short(cont)})"
    end Handle

    object Handle:
        def apply[E <: Effect, A, B, C, S, State](
            v: A < (E & S),
            h: Handler[E, A, B, S, State],
            st: State,
            c: Arrow[B, C, S]
        ): Handle[E, A, B, C, S, State] =
            new Handle[E, A, B, C, S, State]:
                def value   = v
                def handler = h
                def state   = st
                def cont    = c
    end Handle

    final class Park[+A, -S](
        val value: Any < Any,
        val entries: Array[AnyRef]
    ) extends Pending[A, S]:
        Debugger.onAlloc(this)

        private[kyo] def release(ex: Throwable): Any < Any =

            @tailrec def loop(i: Int, acc: Any < Any): Any < Any =
                if i < 0 then acc
                else
                    val handler = entries(i).asInstanceOf[Handler[Nothing, Any, Any, Any, Any]]
                    val state   = entries(i + 1)
                    Debugger.onRelease(handler, ex)
                    loop(i - 3, acc.andThen(handler.release(state, ex))(using Frame.internal))
            val owed: Any < Any = value match
                case p: Pending[?, ?] => p.release(ex)
                case _                => ()
            loop(entries.length - 3, owed)
        end release

        override def toString =
            s"Park(${short(value)}, regions = ${entries.length / 3})"
    end Park
end Kyo

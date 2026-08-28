package kyo.proto.kernel.internal

import kyo.Frame
import kyo.Tag
import kyo.proto.Arrow
import kyo.proto.kernel.<
import kyo.proto.kernel.ArrowEffect
import kyo.proto.kernel.ContextEffect
import kyo.proto.kernel.Effect
import language.implicitConversions

private[proto] def short(v: Any): String =
    v match
        case v: Kyo[?, ?]                => v.toString
        case v: Arrow.Chain[?, ?, ?, ?]  => v.toString
        case _: Arrow.Id[?]              => "Id"
        case _: Arrow.Transform[?, ?, ?] => "Transform"
        case v                           => v.toString

sealed abstract class Kyo[A, -S] extends Arrow.Transform[Any, A, S]:
    def frame = Frame.internal
    def apply[C, S2](v: Any < S2, cont: Arrow[A, C, S2]): C < (S & S2) =
        this.chain(cont)
end Kyo

object Kyo:

    abstract class Defer[A, B, C, -S] private[kyo] () extends Kyo[C, S]:
        def value: A < S
        def contA: Arrow[A, B, S]
        def contB: Arrow[B, C, S]

        override def chain[D, S2](a: Arrow[C, D, S2]): Arrow[Any, D, S & S2] =
            if contB.isInstanceOf[Arrow.Id[?]] then
                // contB eq Id pins B = C, so `a` composes directly into the free slot
                Effect.defer(value, contA, a.asInstanceOf[Arrow[B, D, S & S2]])
            else
                super.chain(a)

        override def toString = s"Defer(${short(value)}, ${short(contA)}, ${short(contB)})"
    end Defer

    sealed abstract class Suspend[E <: Effect, A, S] extends Kyo[A, S]:
        type Op
        def tag: Tag[E]
        def cont: Arrow[Op, A, S]
        def withCont[B, S2](c: Arrow[Op, B, S2]): Suspend[E, B, S2]
        override def chain[D, S2](a: Arrow[A, D, S2]): Arrow[Any, D, S & S2] =
            if a.isInstanceOf[Arrow.Id[?]] || !cont.isInstanceOf[Arrow.Id[?]] then super.chain(a)
            else
                // cont eq Id pins Op = A, so `a` composes directly into the free slot
                withCont(a.asInstanceOf[Arrow[Op, D, S & S2]])
    end Suspend

    final class SuspendArrow[I[_], O[_], E <: ArrowEffect[I, O], State, A, S](
        val tag: Tag[E],
        val input: I[State],
        val cont: Arrow[O[State], A, S]
    ) extends Suspend[E, A, S]:
        type Op = O[State]
        def withCont[B, S2](c: Arrow[Op, B, S2]) = SuspendArrow(tag, input, c)
        override def toString                    = s"SuspendArrow(${tag.show}, $input, ${short(cont)})"
    end SuspendArrow

    final class SuspendContext[State, E <: ContextEffect[State], A, S](
        val tag: Tag[E],
        val update: State => State,
        val cont: Arrow[State, A, S]
    ) extends Suspend[E, A, S]:
        type Op = State
        def withCont[B, S2](c: Arrow[Op, B, S2]) = SuspendContext(tag, update, c)
        override def toString                    = s"SuspendContext(${tag.show}, $update, ${short(cont)})"
    end SuspendContext

    final class SuspendContextDefault[State, E <: ContextEffect[State], A, S](
        val tag: Tag[E],
        val default: State,
        val update: State => State,
        val cont: Arrow[State, A, S]
    ) extends Suspend[E, A, S]:
        type Op = State
        def withCont[B, S2](c: Arrow[Op, B, S2]) = SuspendContextDefault(tag, default, update, c)
        override def toString                    = s"SuspendContextDefault(${tag.show}, $default, $update, ${short(cont)})"
    end SuspendContextDefault

    def handle[E <: Effect, A, B, S, State](v: A < (E & S), handler: Handler[E, A, B, S, State], state: State): B < S =
        v match
            case v: Arrow[Any, A, E & S] @unchecked =>
                Handle[E, A, B, S, State](v, handler, state)
            case _ =>
                handler.done(state, v.asInstanceOf[A])

    final class Handle[E <: Effect, A, B, -S, State] private[Kyo] (
        val value: A < (E & S),
        val handler: Handler[E, A, B, S, State],
        val state: State
    ) extends Kyo[B, S]:
        override def toString = s"Handle(${short(value)}, $handler, $state)"
    end Handle
end Kyo

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
            if a.isInstanceOf[Arrow.Id[?]] || !contB.isInstanceOf[Arrow.Id[?]] then super.chain(a)
            else
                // contB eq Id pins B = C, so `a` composes directly into the free slot
                Effect.defer(value, contA, a.asInstanceOf[Arrow[B, D, S & S2]])

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
                Handle[E, A, B, B, S, State](v, handler, state, Arrow.id)
            case _ =>
                handler.done(state, v.asInstanceOf[A])

    abstract class Handle[E <: Effect, A, B, C, -S, State] extends Kyo[C, S]:
        def value: A < (E & S)
        def handler: Handler[E, A, B, S, State]
        def state: State
        def cont: Arrow[B, C, S]

        override def chain[D, S2](a: Arrow[C, D, S2]): Arrow[Any, D, S & S2] =
            if a.isInstanceOf[Arrow.Id[?]] || !cont.isInstanceOf[Arrow.Id[?]] then super.chain(a)
            else
                // cont eq Id pins B = C, so `a` composes directly into the free slot
                Handle[E, A, B, D, S & S2, State](value, handler, state, a.asInstanceOf[Arrow[B, D, S & S2]])

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
end Kyo

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

private[proto] def short(v: Any): String =
    v match
        case v: Pending[?, ?]            => v.toString
        case v: Arrow.Chain[?, ?, ?, ?]  => v.toString
        case _: Arrow.Id[?]              => "Id"
        case _: Arrow.Transform[?, ?, ?] => "Transform"
        case v                           => v.toString

/** A construction site rendered as the call it was: the enclosing method, the combinator it called, and the position to jump to. */
private[proto] def site(frame: Frame): String =
    val callee = frame.calleeName
    if callee.isEmpty then s"${frame.callerName}(${frame.position.show})"
    else s"${frame.callerName}.$callee(${frame.position.show})"
end site

/** A pending computation: the node arm of the pending union.
  *
  * Sealed with every node class in this file, so union membership is closed: a value either settled or is one of the nodes below, and the
  * eval's destructuring match is exhaustive. Bare arrows are not members, which is what makes "not all arrows are computations" structural.
  */
sealed trait Pending[+A, -S] extends kyo.proto.Kyo[A, S]:
    Debugger.get.onAlloc(this)
    def frame: Frame = Frame.internal
end Pending

// Public object, private-free members for the same reason as before: the combinators' inline
// expansions reach the node classes from call sites outside this package.
object Kyo:

    abstract class Defer[A, B, C, -S] @publicInBinary private[kyo] () extends Pending[C, S]:
        def value: A < S
        def contA: Arrow[A, B, S]
        def contB: Arrow[B, C, S]

        override def toString =
            def slot(a: Arrow[?, ?, ?]): String = if a eq this then s"this(${site(frame)})" else short(a)
            s"Defer(${short(value)}, ${slot(contA)}, ${slot(contB)})"
    end Defer

    sealed abstract class Suspend[E <: Effect, A, S] extends Pending[A, S]:
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
        type Op = State
        def update: State => State
        def withCont[B, S2](c: Arrow[Op, B, S2]) = SuspendContext(tag, update, c)
        override def toString =
            s"SuspendContext(${tag.show}, $update, ${if cont eq this then "this" else short(cont)})"
    end SuspendContext

    object SuspendContext:
        def apply[State, E <: ContextEffect[State], A, S](
            t: Tag[E],
            u: State => State,
            c: Arrow[State, A, S]
        ): SuspendContext[State, E, A, S] =
            new SuspendContext[State, E, A, S]:
                def tag    = t
                def update = u
                def cont   = c
    end SuspendContext

    abstract class SuspendContextDefault[State, E <: ContextEffect[State], A, S] extends Suspend[E, A, S]:
        type Op = State
        def default: State
        def update: State => State
        def withCont[B, S2](c: Arrow[Op, B, S2]) = SuspendContextDefault(tag, default, update, c)
        override def toString =
            s"SuspendContextDefault(${tag.show}, $default, $update, ${if cont eq this then "this" else short(cont)})"
    end SuspendContextDefault

    // TODO let's avoid these factory methods. The development is a lot about memoty layout, allocations, being explicit about instantiations is good and gives space to find optimizations for example avoiding a field for a contsntat
    object SuspendContextDefault:
        def apply[State, E <: ContextEffect[State], A, S](
            t: Tag[E],
            d: State,
            u: State => State,
            c: Arrow[State, A, S]
        ): SuspendContextDefault[State, E, A, S] =
            new SuspendContextDefault[State, E, A, S]:
                def tag     = t
                def default = d
                def update  = u
                def cont    = c
    end SuspendContextDefault

    def handle[E <: Effect, A, B, S, State](v: A < (E & S), handler: Handler[E, A, B, S, State], state: State): B < S =
        v match
            case kyo: Pending[A, E & S] @unchecked =>
                // built directly rather than through the companion: the identity continuation is a
                // constant, not a captured field
                val h  = handler
                val st = state
                new Handle[E, A, B, B, S, State]:
                    def value   = kyo
                    def handler = h
                    def state   = st
                    def cont    = Arrow.id
                end new
            case _ =>
                handler.done(state, v.asInstanceOf[A])

    abstract class Handle[E <: Effect, A, B, C, -S, State] extends Pending[C, S]:
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
end Kyo

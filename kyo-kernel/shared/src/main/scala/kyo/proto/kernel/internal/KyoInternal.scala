package kyo.proto.kernel.internal

import kyo.Frame
import kyo.Maybe
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

private[proto] def site(frame: Frame): String =
    val callee = frame.calleeName
    if callee.isEmpty then s"${frame.callerName}(${frame.position.show})"
    else s"${frame.callerName}.$callee(${frame.position.show})"
end site

sealed trait Pending[+A, -S] extends kyo.proto.Kyo[A, S]:
    def frame: Frame = Frame.internal
end Pending

object Kyo:

    abstract class Defer[A, B, C, -S] @publicInBinary private[kyo] () extends Pending[C, S]:
        Debugger.onAlloc(this)

        def value: A < S
        def contA: Arrow[A, B, S]
        def contB: Arrow[B, C, S]

        override def toString =
            def slot(a: Arrow[?, ?, ?]): String = if a eq this then s"this(${site(frame)})" else short(a)
            s"Defer(${short(value)}, ${slot(contA)}, ${slot(contB)})"
    end Defer

    sealed abstract class Suspend[E <: Effect, A, B, S] extends Pending[B, S]:
        Debugger.onAlloc(this)

        def tag: Tag[E]
        def cont: Arrow[A, B, S]
    end Suspend

    abstract class SuspendArrow[I[_], O[_], E <: ArrowEffect[I, O], A, B, S] extends Suspend[E, O[A], B, S]:
        def input: I[A]

    abstract class SuspendContext[State, E <: ContextEffect[State], A, S] extends Suspend[E, State, A, S]:
        def default: Maybe[State]

    def handle[E <: Effect, A, B, S, State](v: A < (E & S), handler: Handler.ArrowHandler[E, A, B, S, State], state: State): B < S =
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

        def value: A < (E & S)
        def handler: Handler[E, A, B, S, State]
        def state: State
        def cont: Arrow[B, C, S]

        override def toString =
            if cont.isInstanceOf[Arrow.Id[?]] then s"Handle(${short(value)}, $handler, $state)"
            else s"Handle(${short(value)}, $handler, $state, ${if cont eq this then "this" else short(cont)})"
    end Handle

    abstract class DeferWith[A, B, -S] extends Defer[A, B, B, S] with Arrow.Transform[A, B, S]:
        def contA = this
        def contB = Arrow.id

    abstract class SuspendArrowWith[I[_], O[_], E <: ArrowEffect[I, O], State, A, S]
        extends SuspendArrow[I, O, E, State, A, S] with Arrow.Transform[O[State], A, S]

    abstract class SuspendContextWith[State, E <: ContextEffect[State], A, S]
        extends SuspendContext[State, E, A, S] with Arrow.Transform[State, A, S]

    // Reads the contextual regions in scope as Park currency, the snapshot a Park installs;
    // answered by the eval from the live stack without consuming it.
    abstract class Snapshot[A, -S] extends Pending[A, S]:
        Debugger.onAlloc(this)

        def cont: Arrow[Stack.Snapshot, A, S]
    end Snapshot

    abstract class SnapshotWith[A, -S] extends Snapshot[A, S] with Arrow.Transform[Stack.Snapshot, A, S]

    abstract class HandleWith[E <: Effect, A, B, C, -S, State]
        extends Handle[E, A, B, C, S, State] with Arrow.Transform[B, C, S]

    // object Handle:
    //     def apply[E <: Effect, A, B, C, S, State](
    //         v: A < (E & S),
    //         h: Handler[E, A, B, S, State],
    //         st: State,
    //         c: Arrow[B, C, S]
    //     ): Handle[E, A, B, C, S, State] =
    //         new Handle[E, A, B, C, S, State]:
    //             def value   = v
    //             def handler = h
    //             def state   = st
    //             def cont    = c
    // end Handle

    final class Park[+A, -S](
        val value: Any < Any,
        val entries: Stack.Snapshot
    ) extends Pending[A, S]:
        Debugger.onAlloc(this)

        override def toString =
            s"Park(${short(value)}, regions = ${entries.regions})"
    end Park
end Kyo

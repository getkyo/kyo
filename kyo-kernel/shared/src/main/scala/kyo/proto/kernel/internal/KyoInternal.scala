package kyo.proto.kernel.internal

import kyo.Chunk
import kyo.Frame
import kyo.Maybe
import kyo.Tag
import kyo.proto.kernel.<
import kyo.proto.kernel.Arrow
import kyo.proto.kernel.ArrowEffect
import kyo.proto.kernel.ContextEffect
import kyo.proto.kernel.Effect
import language.implicitConversions
import scala.annotation.publicInBinary
import scala.annotation.tailrec

// TODO we can not have methods thrown at files like this. A source file is a type + its companion
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

// TODO can't this be sealed abstract class and Debugger.onAlloc is in it? I imaigne putting in Kyo, which is a trait, would generate overhead? Or could we put it there so we can ensure all allocations are captured?
trait Kyo[+A, -S]:
    def frame: Frame

sealed trait Pending[+A, -S] extends Kyo[A, S]:
    def frame: Frame = Frame.internal
end Pending

// TODO this should be Pending no?
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

        override def toString: String =
            s"Kyo(${tag.show}, ${site(frame)})"
    end Suspend

    abstract class SuspendArrow[I[_], O[_], E <: ArrowEffect[I, O], A, B, S] extends Suspend[E, O[A], B, S]:
        def input: I[A]

        private[kyo] def crossing[C](entries: Stack.Snapshot, resume: Arrow[B, C, S]): Arrow[O[A], C, S] =
            val kc = cont
            new Arrow.Step[O[A], C, S]:
                def frame = Frame.internal
                override def apply[D, S3](v: O[A] < S3, cont2: Arrow[C, D, S3]) =
                    v match
                        case p: Pending[O[A], S3] @unchecked => Effect.defer(p, this, cont2)
                        case _ =>
                            cont2(
                                Kyo.Park(
                                    Effect.defer(v, kc, resume).asInstanceOf[Any < Any],
                                    entries
                                ),
                                Arrow.id
                            )
            end new
        end crossing
    end SuspendArrow

    abstract class SuspendContext[State, E <: ContextEffect[State], A, S] extends Suspend[E, State, A, S]:
        def default: Maybe[State]

    def handle[State, E <: Effect, A, B, S](v: A < (E & S), handler: Handler.ArrowHandler[State, E, A, B, S], state: State): B < S =
        v match
            case kyo: Pending[A, E & S] @unchecked =>
                val h  = handler
                val st = state
                new Handle[State, E, A, B, B, S]:
                    def value   = kyo
                    def handler = h
                    def state   = st
                    def cont    = Arrow.id
                end new
            case _ =>
                handler.done(state, Nested.unnest[A](v))

    abstract class Handle[State, E <: Effect, A, B, C, -S] extends Pending[C, S]:
        Debugger.onAlloc(this)

        def value: A < (E & S)
        def handler: Handler[E, B, S]
        def state: State
        def cont: Arrow[B, C, S]

        override def toString =
            if cont.isInstanceOf[Arrow.Id[?]] then s"Handle(${short(value)}, $handler, $state)"
            else s"Handle(${short(value)}, $handler, $state, ${if cont eq this then "this" else short(cont)})"
    end Handle

    abstract class Snapshot[A, -S] extends Pending[A, S]:
        Debugger.onAlloc(this)

        def cont: Arrow[Stack, A, S]
    end Snapshot

    final class Park[+A, -S](
        val value: Any < Any,
        val entries: Stack.Snapshot,
        val owed: Chunk[Stack.Snapshot] = Chunk.empty
    ) extends Pending[A, S]:
        Debugger.onAlloc(this)

        override def toString =
            s"Park(${short(value)}, regions = ${entries.regions})"
    end Park

    abstract class DeferWith[A, B, -S] extends Defer[A, B, B, S] with Arrow.Transform[A, B, S]:
        def contA = this
        def contB = Arrow.id

    abstract class SuspendArrowWith[I[_], O[_], E <: ArrowEffect[I, O], State, A, S]
        extends SuspendArrow[I, O, E, State, A, S] with Arrow.Transform[O[State], A, S]

    abstract class SuspendContextWith[State, E <: ContextEffect[State], A, S]
        extends SuspendContext[State, E, A, S] with Arrow.Transform[State, A, S]

    abstract class SnapshotWith[A, -S] extends Snapshot[A, S] with Arrow.Transform[Stack, A, S]

    abstract class HandleWith[State, E <: Effect, A, B, C, -S]
        extends Handle[State, E, A, B, C, S] with Arrow.Transform[B, C, S]

end Kyo

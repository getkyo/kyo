package kyo.kernel.internal

import kyo.Chunk
import kyo.Frame
import kyo.Maybe
import kyo.Tag
import kyo.kernel.<
import kyo.kernel.Arrow
import kyo.kernel.ArrowEffect
import kyo.kernel.ContextEffect
import kyo.kernel.Effect
import language.implicitConversions
import scala.annotation.publicInBinary

sealed trait Pending[+A, -S] extends Kyo[A, S]:
    def frame: Frame = Frame.internal
end Pending

object Pending:

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

        /** This request on its own, as the computation that raises it again: what a masking region's clause is
          * handed in place of an input it cannot interpret. Each kind of suspension rebuilds itself, so the
          * evaluator does not have to.
          */
        private[kyo] def reraise: A < E

        // Only `cont` is involved, so this carries a context read crossing to the region that masked it as
        // readily as an arrow operation crossing to its handler.
        private[kyo] def crossing[C](entries: Stack.Snapshot, resume: Arrow[B, C, S]): Arrow[A, C, S] =
            val kc = cont
            new Arrow.Step[A, C, S]:
                def frame = Frame.internal
                override def apply[D, S3](v: A < S3, cont2: Arrow[C, D, S3]) =
                    v match
                        case p: Pending[A, S3] @unchecked => Effect.defer(p, this, cont2)
                        case _ =>
                            cont2(
                                Park(
                                    Effect.defer(v, kc, resume).asInstanceOf[Any < Any],
                                    entries
                                ),
                                Arrow.id
                            )
            end new
        end crossing

        override def toString: String =
            s"Kyo(${tag.show}, ${site(frame)})"
    end Suspend

    abstract class SuspendArrow[I[_], O[_], E <: ArrowEffect[I, O], A, B, S] extends Suspend[E, O[A], B, S]:
        self =>
        def input: I[A]

        private[kyo] def reraise: O[A] < E =
            new SuspendArrow[I, O, E, A, O[A], E]:
                def tag   = self.tag
                def input = self.input
                def cont  = Arrow.id
    end SuspendArrow

    abstract class SuspendContext[State, E <: ContextEffect[State], A, S] extends Suspend[E, State, A, S]:
        self =>
        def default: Maybe[State]

        private[kyo] def reraise: State < E =
            new SuspendContext[State, E, State, E]:
                def tag     = self.tag
                def default = self.default
                def cont    = Arrow.id
    end SuspendContext

    def handle[State, E <: Effect, A, B, S](v: A < (E & S), handler: Handler.ArrowHandler[State, E, A, B, S], state: State): B < S =
        v match
            case kyo: Pending[A, E & S] @unchecked =>
                val h  = handler
                val st = state
                new HandleArrow[State, E, A, B, B, S]:
                    def value   = kyo
                    def handler = h
                    def state   = st
                    def cont    = Arrow.id
                end new
            case _ =>
                handler.done(state, Nested.unnest[A](v))

    sealed abstract class Handle[E <: Effect, A, C, -S] extends Pending[C, S]:
        Debugger.onAlloc(this)

        def value: A < (E & S)
        def handler: Handler[E, ?, S]
    end Handle

    abstract class HandleArrow[State, E <: Effect, A, B, C, -S] extends Handle[E, A, C, S]:
        def handler: Handler[E, B, S]
        def state: State
        def cont: Arrow[B, C, S]

        override def toString =
            if cont.isInstanceOf[Arrow.Id[?]] then s"HandleArrow(${short(value)}, $handler, $state)"
            else s"HandleArrow(${short(value)}, $handler, $state, ${if cont eq this then "this" else short(cont)})"
    end HandleArrow

    abstract class HandleContext[State, E <: ContextEffect[State], A, -S] extends Handle[E, A, A, S]:
        def handler: Handler.ContextHandler[State, E, A, S]

        override def toString = s"HandleContext(${short(value)}, $handler)"
    end HandleContext

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

    abstract class HandleArrowWith[State, E <: Effect, A, B, C, -S]
        extends HandleArrow[State, E, A, B, C, S] with Arrow.Transform[B, C, S]

end Pending

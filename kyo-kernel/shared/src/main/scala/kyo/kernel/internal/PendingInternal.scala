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

/** One node of a suspended computation, the reification of a single combinator.
  *
  * Each subclass is one thing the evaluator can encounter: a deferral holding a value with the continuations waiting on it, a suspension
  * awaiting an answer, a region entry, a parked slice, or a stack snapshot. With [[kyo.kernel.Arrow]] these are the whole of what a
  * computation is made of, which is why a new node kind implies a new combinator rather than a patch to the evaluator.
  *
  * The nodes are abstract classes so that each construction site implements the members anonymously and keeps its own types, which is what
  * lets a node hold a primitive input without boxing it.
  */
sealed trait Pending[+A, -S] extends Kyo[A, S]:
    def frame: Frame = Frame.internal // TODO this should be abstract
end Pending

object Pending:

    /** A value with the continuations waiting on it, reifying the application of a continuation rather than running it here.
      *
      * Two continuation slots rather than one because a composition arrives as a pair often enough to be worth storing flat: a caller
      * holding an `Arrow.Chain` hands its links over separately and one node carries both, instead of a node plus the chain. A site with
      * only one continuation puts `Arrow.id` in the other slot.
      */
    abstract class Defer[A, B, C, -S] @publicInBinary private[kyo] () extends Pending[C, S]:
        Debugger.onAlloc(this)

        def value: A < S
        def contA: Arrow[A, B, S]
        def contB: Arrow[B, C, S]

        override def toString =
            def slot(a: Arrow[?, ?, ?]): String = if a eq this then s"this(${site(frame)})" else short(a)
            s"Defer(${short(value)}, ${slot(contA)}, ${slot(contB)})"
    end Defer

    /** An operation waiting for a handler to answer it.
      *
      * `tag` names the effect, which is what a region matches on as the evaluator walks outward looking for a handler, and `cont` is the rest
      * of the computation from this point, applied to whatever answer arrives.
      */
    sealed abstract class Suspend[E <: Effect, A, B, S] extends Pending[B, S]:
        Debugger.onAlloc(this)

        def tag: Tag[E]
        def cont: Arrow[A, B, S]

        /** This request on its own, as the computation that raises it again: what a masking region's clause is
          * handed in place of an input it cannot interpret. Each kind of suspension rebuilds itself, so the
          * evaluator does not have to.
          */
        private[kyo] def reraise: A < E

        /** The continuation for an answer that has to re-enter regions the evaluator has already left.
          *
          * Applying it to a settled answer parks a slice carrying that answer, this suspension's own continuation and `resume`, together with
          * the snapshot of regions to reinstall before it runs again.
          *
          * Only `cont` is involved, so this carries a context read crossing back to the region that masked it as readily as an arrow
          * operation crossing to its handler.
          */
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

    /** A suspended [[kyo.kernel.ArrowEffect]] operation, carrying the input its clause will be handed. */
    abstract class SuspendArrow[I[_], O[_], E <: ArrowEffect[I, O], A, B, S] extends Suspend[E, O[A], B, S]:
        self =>
        def input: I[A]

        private[kyo] def reraise: O[A] < E =
            new SuspendArrow[I, O, E, A, O[A], E]:
                def tag   = self.tag
                def input = self.input
                def cont  = Arrow.id
    end SuspendArrow

    /** A suspended [[kyo.kernel.ContextEffect]] read.
      *
      * `default` present is the defaulted form, which is why such a read keeps the effect out of its row: no binding is required, because the
      * node can answer itself.
      */
    abstract class SuspendContext[State, E <: ContextEffect[State], A, S] extends Suspend[E, State, A, S]:
        self =>
        def default: Maybe[State]

        private[kyo] def reraise: State < E =
            new SuspendContext[State, E, State, E]:
                def tag     = self.tag
                def default = self.default
                def cont    = Arrow.id
    end SuspendContext

    /** Enters a region: builds the node that installs `handler` with `state` over `v`.
      *
      * A value that has already settled has no operation to answer, so it skips the region entirely and goes straight to the handler's `done`
      * arm.
      */
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

    /** A region entry: a computation together with the handler installed over it.
      *
      * The evaluator pushes the handler when it reaches this node and pops it when the computation inside is done, which is what makes the
      * region's extent exactly the extent of `value`.
      */
    sealed abstract class Handle[E <: Effect, A, C, -S] extends Pending[C, S]:
        Debugger.onAlloc(this)

        def value: A < (E & S)
        def handler: Handler[E, ?, S]
    end Handle

    /** A region answering an [[kyo.kernel.ArrowEffect]], carrying the handler's state and the continuation for the region's own result. */
    abstract class HandleArrow[State, E <: Effect, A, B, C, -S] extends Handle[E, A, C, S]:
        def handler: Handler[E, B, S]
        def state: State
        def cont: Arrow[B, C, S]

        override def toString =
            if cont.isInstanceOf[Arrow.Id[?]] then s"HandleArrow(${short(value)}, $handler, $state)"
            else s"HandleArrow(${short(value)}, $handler, $state, ${if cont eq this then "this" else short(cont)})"
    end HandleArrow

    /** A region binding a [[kyo.kernel.ContextEffect]].
      *
      * Binding a value transforms nothing, so this carries no continuation of its own and the region's result is the computation's, which is
      * why its two result parameters are the same type.
      */
    abstract class HandleContext[State, E <: ContextEffect[State], A, -S] extends Handle[E, A, A, S]:
        def handler: Handler.ContextHandler[State, E, A, S]

        override def toString = s"HandleContext(${short(value)}, $handler)"
    end HandleContext

    /** A node that hands the evaluator's own stack to its continuation, which is how a computation reads the regions standing around it. */
    abstract class Snapshot[A, -S] extends Pending[A, S]:
        Debugger.onAlloc(this)

        def cont: Arrow[Stack, A, S]
    end Snapshot

    /** A slice of computation set aside with what it needs to run again elsewhere, or later.
      *
      * `entries` is the snapshot of regions to reinstall before `value` resumes, so a parked slice carries its own context rather than
      * depending on where it is picked up. `owed` carries the obligations those regions have not discharged yet, which the evaluator hands to
      * the stack it resumes on.
      *
      * An empty `entries` is the degenerate case: nothing to reinstall, so the evaluator takes the debt and continues in place.
      */
    final class Park[+A, -S](
        val value: Any < Any,
        val entries: Stack.Snapshot,
        val owed: Chunk[Stack.Snapshot] = Chunk.empty
    ) extends Pending[A, S]:
        Debugger.onAlloc(this)

        override def toString =
            s"Park(${short(value)}, regions = ${entries.regions})"
    end Park

    // Each `*With` node is its own continuation: the site's transformation is inlined into the node's `apply`
    // rather than sitting in a separate arrow behind it, which is what fuses a `map` or a `done` into the node it
    // follows and saves the evaluator a hop. `Arrow.Transform` is what lets a node stand in an arrow position.

    // TODO let's move these two their non-with versions
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

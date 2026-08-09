package kyo.kernel.internal

import kyo.Frame
import kyo.Maybe
import kyo.Result
import kyo.Tag
import kyo.kernel.*
import language.implicitConversions

/** The kernel's node hierarchy: the pending representation behind the `<` opaque type.
  *
  * Everything here is execution machinery; the user-facing combinator surface lives in [[kyo.kernel.Kyo]].
  */
sealed abstract class Kyo[+A, -S]:
    private[kyo] def map[B, S2](f: Arrow[A, B, S2]): B < (S & S2)

object Kyo:

    inline def unnest(v: Any): Any =
        v match
            case n: Nested[?] => n.value
            case _            => v

    /** Reads a value the caller has proven settled: dispatch already excluded the pending cases, so what remains is the value,
      * possibly in its Nested box. The one cast is this seam; call sites stay cast free.
      */
    inline def settled[A, S](v: A < S): A =
        unnest(v).asInstanceOf[A]

    /** The bracket outcome a settled use value carries: a [[Result.Error]] is the currency a rotated error handler settles with,
      * so the kernel reads it as the completion outcome; any other value is a success.
      */
    private[kyo] def outcomeOf(v: Any): Maybe[Result.Error[Any]] =
        unnest(v) match
            case e: Result.Error[Any] @unchecked => Maybe(e)
            case _                               => Maybe.Absent

    // a case class so re-wrapping at pass-through positions preserves value equality; public
    // like Defer because the central lift's runtime arm expands at user sites, outside kyo
    final case class Nested[+A](value: A)

    /** A suspension: an arrow-effect operation with the fused continuation from its output.
      *
      * The one suspension shape: a bare operation's continuation is the identity arrow (the shared empty, so it costs nothing), and
      * mapping extends the continuation on a fresh node that still points at the bare operation. Every dispatch site matches this class
      * alone. Context reads are plain [[Defer]]s consuming the threaded context, so no read node exists.
      */
    abstract class Suspend[I[_], O[_], E <: ArrowEffect[I, O], A, +B, -S] extends Kyo[B, S]:

        def input: I[A]
        def tag: Tag[E]
        def frame: Frame

        /** The fused continuation from the operation's output; the identity arrow for a bare operation. */
        def cont: Arrow[O[A], B, S]

        /** The bare operation this suspension extends; itself for a bare one. */
        private[kyo] def origin: Suspend[I, O, E, A, O[A], E]

        final private[kyo] def erasedTag: Tag[Any] = tag.erased

        final private[kyo] def map[C, S2](f: Arrow[B, C, S2]): C < (S & S2) =
            Continue(origin, cont.map(f))

        /** This suspension with a different continuation. The input stays existential and the one cast re-anchors it at the
          * operation's own types through `origin`: the callers hold the continuation at the operation's output type (rotation) or at
          * the drive's currency (observation), and neither can name that type from outside.
          */
        final private[kyo] def continue[C, S2](cont2: Arrow[?, C, S2]): Kyo[C, S2] =
            Continue(origin, cont2.asInstanceOf[Arrow[O[A], C, S2]])

        override def toString = "Suspend(" + tag.show + ", " + frame.position.show + ")"

    end Suspend

    /** A suspension of an operation no handler can resume: the effect's output is Nothing, so nothing after the operation can
      * ever run. The bubble point and the handle loops' traversal pass it through untouched: no continuation is stacked and no
      * rotation installed, so a deep failure travels as the one node its suspension already is. The marker is checked through
      * `origin`, which every Continue preserves; the drive's finalizer attachment maps onto these suspensions like any other, so
      * releases still run when a boundary discards the remainder.
      */
    abstract class NeverResumed[I[_], O[_], E <: ArrowEffect[I, O], A, +B, -S] extends Suspend[I, O, E, A, B, S]

    final private[kyo] class Continue[I[_], O[_], E <: ArrowEffect[I, O], A, +B, -S](
        override val origin: Suspend[I, O, E, A, O[A], E],
        val cont: Arrow[O[A], B, S]
    ) extends Suspend[I, O, E, A, B, S]:

        def input = origin.input
        def tag   = origin.tag
        def frame = origin.frame

        override def toString = "Continue(" + origin + ", " + cont + ")"

    end Continue

    abstract class Bracket[R, A, S] extends Kyo[A, S]:

        def acquire: R < S

        /** Releases the resource with the use computation's outcome: Absent on success, the error when the settled value is a
          * [[Result.Error]] (the currency a rotated error handler settles with) or the use computation threw, and the boundary's
          * own error when a parked remainder is discarded. The kernel guarantees exactly one call per acquired resource.
          */
        def release(r: R, outcome: Maybe[Result.Error[Any]]): Unit < S
        def cont: Arrow[R, A, S]
        def frame: Frame

        /** Whether acquire is a settled value that needs no folding. Handle loops rebuild a bracket around the settled resource
          * once its acquisition folds, and mark the rebuilt node so the traversal terminates without evaluating acquire, which is
          * a by-name thunk on user brackets and must run exactly once per drive.
          */
        private[kyo] def settled: Boolean = false

        /** Whether the exit crossing no longer ends this node's cont chain: rotations wrap the chain and region rebuilds carry
          * the remainder behind a yield step, burying the crossing where plain extension cannot fuse onto it. Composition onto
          * such a node sequences after it instead, so it still runs after the release on every path.
          */
        private[kyo] def crossingBuried: Boolean = false

        // composition extends the use continuation; the region's exit crossing sits at the end
        // of every use chain (installed at construction), so the extension fuses onto the
        // crossing's continuation at drive time and runs outside the region, after the release.
        // Once a rotation or rebuild buries the crossing, composition sequences after the node
        // instead, applied by the drive when the region completes
        final private[kyo] def map[B, S2](f: Arrow[A, B, S2]): B < (S & S2) =
            if crossingBuried then
                new Sequenced[R, A, B, S & S2](this.asInstanceOf[Bracket[R, A, S & S2]], f)
            else
                val self = this.asInstanceOf[Bracket[R, A, S & S2]]
                new Bracket[R, B, S & S2]:
                    def acquire                                          = self.acquire
                    def release(r: R, outcome: Maybe[Result.Error[Any]]) = self.release(r, outcome)
                    def cont                                             = self.cont.map(f.asInstanceOf[Arrow[A, B, S & S2]])
                    def frame                                            = self.frame
                    override private[kyo] def settled                    = self.settled
                end new
        end map

        final override def toString = "Bracket(" + frame.position.show + ")"

    end Bracket

    /** A region whose crossing is buried, with its downstream continuation: `after` carries the steps composed after the
      * region (typically fused at drive time behind a handler that rotated it), and the drive applies it once the region node
      * completes, so it runs after the release on the crossing path and the short-circuit path alike. Handlers rotate through
      * `after` by composing it onto the chains they wrap; the release fold keeps its own loop, so its completion value never
      * meets `after`.
      */
    final private[kyo] class Sequenced[R, A, B, S](
        val bracket: Bracket[R, A, S],
        val after: Arrow[A, B, S]
    ) extends Kyo[B, S]:

        private[kyo] def map[C, S2](f: Arrow[B, C, S2]): C < (S & S2) =
            new Sequenced[R, A, C, S & S2](
                bracket.asInstanceOf[Bracket[R, A, S & S2]],
                after.asInstanceOf[Arrow[A, B, S & S2]].map(f)
            )

        override def toString = "Sequenced(" + bracket + ", " + after + ")"

    end Sequenced

    /** The effect of a region-exit crossing: every bracket's use chain ends with one, installed at construction, carrying the
      * region's completed result as its input. Everything composed onto the bracket, at any point in its life, fuses onto the
      * crossing's continuation, so it runs outside the region, and each activation mints exactly one crossing, which is what
      * lets the drive's region arm attribute any surfacing crossing to its nearest open region: there the release runs with the
      * carried result and the continuation resumes outside. Every other traversal treats it as an ordinary foreign suspension,
      * which is the point: a handler rotating across it covers the downstream with the state it reached at region exit, and
      * finalization walks its continuation like any other remainder.
      */
    sealed private[kyo] trait RegionExit extends ArrowEffect[kyo.Id, kyo.Id]

    private[kyo] val regionExitFullTag: Tag[RegionExit] = Tag[RegionExit]
    private[kyo] val regionExitTag: Tag[Any]            = regionExitFullTag.erased

    final private[kyo] class Exit[A](val payload: A) extends Suspend[kyo.Id, kyo.Id, RegionExit, A, A, RegionExit]:
        def input               = payload
        def tag                 = regionExitFullTag
        def frame               = Frame.internal
        def cont                = Arrow[A]
        private[kyo] def origin = this
    end Exit

    private val erasedExitStep: Arrow.Transform[Any, Any, Any] =
        new Arrow.Transform[Any, Any, Any]:
            def frame = Frame.internal
            def run[C, S2](v: Any, context: Context, handlers: Handlers, cont: Arrow[Any, C, S2]): C < (Any & S2) =
                cont(new Exit(v).asInstanceOf[Any < Any], context, handlers)

    /** Yields the region-exit crossing at the end of a use chain: the crossing carries the use result, and whatever follows in
      * the chain fuses onto the crossing's suspension. The shared erased instance behind a typed view, like `Arrow[A]` over the
      * empty arrow.
      */
    private[kyo] def exitStep[A, S]: Arrow[A, A, S] = erasedExitStep.asInstanceOf[Arrow[A, A, S]]

    // public because the inline trampoline's Defer arm expands at user sites.
    // value is a pending value, not a bare A: variance then types the public
    // deferring application and the drive's pop without casts
    final class Defer[A, +B, -S](
        val value: A < S,
        val cont: Arrow[A, B, S]
    ) extends Kyo[B, S]:

        private[kyo] def map[C, S2](f: Arrow[B, C, S2]): C < (S & S2) =
            Defer(value, cont.map(f))

        override def toString = "Defer(" + cont + ")"

    end Defer
end Kyo

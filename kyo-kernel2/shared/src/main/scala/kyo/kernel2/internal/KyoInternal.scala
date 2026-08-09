package kyo.kernel2.internal

import kyo.Frame
import kyo.Tag
import kyo.kernel2.*
import language.implicitConversions

/** The kernel's node hierarchy: the pending representation behind the `<` opaque type.
  *
  * Everything here is execution machinery; the user-facing combinator surface lives in [[kyo.kernel2.Kyo]].
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

    // a case class so re-wrapping at pass-through positions preserves value equality
    final private[kyo] case class Nested[+A](value: A)

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
        def release(r: R): Unit < S
        def cont: Arrow[R, A, S]
        def frame: Frame

        /** Whether acquire is a settled value that needs no folding. Handle loops rebuild a bracket around the settled resource
          * once its acquisition folds, and mark the rebuilt node so the traversal terminates without evaluating acquire, which is
          * a by-name thunk on user brackets and must run exactly once per drive.
          */
        private[kyo] def settled: Boolean = false

        final private[kyo] def map[B, S2](f: Arrow[A, B, S2]): B < (S & S2) =
            val outer = this
            new Bracket[R, B, S & S2]:
                def acquire       = outer.acquire
                def release(r: R) = outer.release(r)
                def cont          = outer.cont.map(f)
                def frame         = outer.frame
            end new
        end map

        final override def toString = "Bracket(" + frame.position.show + ")"

    end Bracket

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

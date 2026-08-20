package kyo.kernel

import kyo.Arrow
// unqualified so the inline expansions do not select these from Arrow.type or Kyo.type at a site
// outside package kyo, where they are not accessible. See the note in Pending.scala
import kyo.Arrow.Bracket
import kyo.Frame
import kyo.kernel.internal.*
import kyo.kernel.internal.Kyo.Defer
import scala.annotation.nowarn
import scala.annotation.static

abstract class Effect private[kernel] ()

object Effect:

    /** Holds a computation unevaluated until a drive reaches it. */
    private[kyo] def defer[A, S](f: => A < S)(using Frame): A < S =
        deferInline(f)

    // the node's payload is a by-name method, so the body runs when the evaluator
    // reads it and not when the node is built
    @nowarn("msg=anonymous")
    private[kyo] inline def deferInline[A, S](inline f: => A < S): A < S =
        new Defer[A, A, A, S]:
            def value = f
            def contA = Arrow.id[A]
            def contB = Arrow.id[A]

    @static def defer[A, B, S](v: A < S, next: Arrow[A, B, S]): B < S =
        new Defer[A, B, B, S]:
            def value = v
            def contA = next
            def contB = Arrow.id[B]

    @static def defer[A, B, C, S](v: A < S, a: Arrow[A, B, S], b: Arrow[B, C, S]): C < S =
        if b eq Arrow.id then
            defer(v, a.asInstanceOf[Arrow[A, C, S]])
        else
            new Defer[A, B, C, S]:
                def value = v
                def contA = a
                def contB = b

    // Surface the previous kernels carry that this one does not yet. Kept as signatures so the
    // gap is visible here rather than only in a parked test.

    /** Wraps a computation with error handling: `f` runs if a non-fatal exception escapes `v`, whether during the initial evaluation or
      * during any later effect operation.
      *
      * Shape below is the old kernel's, which is CPS: `catchingLoop` walks the computation and rebuilds each suspension with its
      * continuation wrapped, so resuming inside the region is inside the try. This kernel has no continuation to rebuild at construction
      * time; the drive holds continuations on its stack and applies them one at a time, and a try inside a single arrow's `apply` guards
      * that one application rather than the rest of the region.
      *
      * What the guard actually needs is to be a stack entry the drive consults while unwinding, so a throw from anything above it lands
      * here. That is the same mechanism a bracket's release needs, and the Bracket design introduces it (reviews/BRACKET-PARK-DESIGN.md).
      * Designing catching before that mechanism exists would duplicate it, so the signature is recorded and the implementation waits.
      *
      * The tracing contract this owes, established while porting EffectTrace: `EffectTrace.splice` currently runs only at the drive
      * boundary, which assumes an exception is observed only there. This handler is a second observation point, so it has to attach and
      * splice before calling `f`, or the handler sees frames in the carrier that are not in the stack trace.
      */
    // Parked: it cannot be implemented without other changes. CPS makes the continuation be the
    // rest of the computation, so the old kernel could wrap it in a try and guard everything
    // downstream. Here the drive owns a stack and the rest is spread across its entries, so a try
    // inside one arrow's apply guards building the next deferral, not running it. A self-
    // reinstalling guard arrow and a structural rewrite of every continuation were both
    // considered; the first does not guard the drive's own evaluation and the second pays an
    // allocation per drive step. What it needs is a stack entry the drive consults while
    // unwinding, which is the mechanism the Bracket work introduces, so this rides on that.
    // private[kyo] inline def catching[A, S, B >: A, S2](inline v: => A < S)(
    //     inline f: Throwable => B < S2
    // )(using inline _frame: Frame): B < (S & S2)

    /** Detaches a computation from the bindings standing at this point, so the child carries them and can be driven elsewhere.
      *
      * Waits on ContextEffect, which this kernel does not have.
      */
    // private[kyo] inline def detach[A, S](inline v: A < S)(using inline _frame: Frame): (A < S) < S

    /** Acquires a resource, uses it, and releases it, with the release running whether or not the use completes.
      *
      * The bracket is the acquire followed by an arrow that carries the release, so the arrow is reached only
      * once the acquire has settled: an acquire that never completes owes nothing, and one that completes owes
      * the release from that moment. A drive that completes runs it where the use ends; a drive that throws, or
      * that ends holding a continuation a clause never applied, runs it at the boundary.
      *
      * The release takes no effects. It has to be able to run where nothing is installed to answer for it,
      * which is what a drive that is ending can offer.
      */
    @nowarn("msg=anonymous")
    inline def bracket[A, B, S](inline acquire: A < S)(inline release: A => Any < Any)(
        inline use: A => B < S
    )(using inline _frame: Frame): B < S =
        val _release = release
        val _use     = use
        // the deferral of the acquire and the arrow that consumes it are one object, as a suspension and its
        // continuation are in suspendWith: the node's own first continuation is the bracket
        new Defer[A, B, B, S] with Bracket[A, B, S]:
            def frame   = _frame
            def value   = acquire
            def contA   = this
            def contB   = Arrow.id[B]
            val use     = Arrow(_use)(using _frame)
            val release = Arrow(_release)(using _frame)
        end new
    end bracket

end Effect

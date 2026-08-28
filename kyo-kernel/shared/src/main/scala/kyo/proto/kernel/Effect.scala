package kyo.proto.kernel

import kyo.Frame
import kyo.proto.Arrow
import kyo.proto.kernel.internal.*
import kyo.proto.kernel.internal.Kyo.*
import language.implicitConversions
import scala.annotation.nowarn

abstract class Effect private[kernel] ()

object Effect:

    /** Reifies the application of a continuation to a computation as a record. The raw factory: callers know their shapes, so no
      * normalization happens here; value composition with the free-slot laws is `<.chain`'s job.
      */
    def defer[A, B, S](v: A < S, cont: Arrow[A, B, S]): B < S =
        cont match
            case cont: Arrow.Chain[A, x, B, S] @unchecked =>
                new Defer[A, x, B, S]:
                    def value = v
                    def contA = cont.a
                    def contB = cont.b
            case _ =>
                new Defer[A, B, B, S]:
                    def value = v
                    def contA = cont
                    def contB = Arrow.id

    def defer[A, B, C, S](v: A < S, cont1: Arrow[A, B, S], cont2: Arrow[B, C, S]): C < S =
        if cont1.isInstanceOf[Arrow.Id[?]] then
            defer(v, cont2.asInstanceOf[Arrow[A, C, S]])
        else if cont2.isInstanceOf[Arrow.Id[?]] then
            defer(v, cont1.asInstanceOf[Arrow[A, C, S]])
        else
            new Defer[A, B, C, S]:
                def value = v
                def contA = cont1
                def contB = cont2
            end new
    end defer

    /** Holds a computation unevaluated until an eval reaches it. */
    def defer[A, S](f: => A < S)(using Frame): A < S =
        deferInline(f)

    // The payload a deferred body stands on: a raw value inhabits the union's first arm through the
    // `>: A` bound, so nothing nests and nothing lifts.
    private val unitValue: Unit < Any = ()

    // The body lives in the arrow, not the payload: building the record does not run it, and applying
    // the record's own transform is what runs it. The record is its own step, so the by-name form stays
    // one object.
    @nowarn("msg=anonymous")
    private[kyo] inline def deferInline[A, S](inline f: => A < S)(using inline _frame: Frame): A < S =
        new Defer[Unit, A, A, S] with Arrow.Transform[Unit, A, S]:
            override def frame          = _frame
            def value                   = unitValue
            def contA                   = this
            def contB                   = Arrow.id
            override def apply(v: Unit) = f
            override def apply[C, S2](v: Unit < S2, cont: Arrow[A, C, S2]) =
                v match
                    case kyo: Pending[Unit, S2] @unchecked =>
                        defer(kyo, this, cont)
                    case _ =>
                        val slot = Safepoint.get()
                        if !Safepoint.enter(slot) then
                            defer(v, this, cont)
                        else
                            val out = cont.head(apply(Nested.unnest(v)), cont.tail)
                            Safepoint.exit(slot)
                            out
                        end if
end Effect

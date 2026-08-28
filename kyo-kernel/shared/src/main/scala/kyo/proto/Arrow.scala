package kyo.proto

import kyo.Frame
import kyo.proto.kernel.Effect
import kyo.proto.kernel.internal.Debugger
import kyo.proto.kernel.internal.Nested
import kyo.proto.kernel.internal.Pending
import kyo.proto.kernel.internal.Safepoint
import kyo.proto.kernel.internal.short
import kyo.proto.kernel.internal.site
import scala.annotation.nowarn

/** The root of the kernel's value hierarchy: everything the machine holds is a [[Kyo]].
  *
  * Two families extend it. [[kyo.proto.kernel.internal.Pending]] is the computation-node arm: the values that inhabit the pending union and
  * that the eval destructures. [[Arrow]] is the transformation arm: the continuation currency the machine composes and applies. A merged
  * node extends both at the same instantiation, which is what lets one allocation be a computation and its own continuation with the input
  * typed exactly.
  */
trait Kyo[+A, -S]:
    /** The source position this value was built at, [[Frame.internal]] for values the kernel mints itself. */
    def frame: Frame
end Kyo

// deliberately not a Function1: the specialization forwarder grid costs a method surface no call
// site uses (the reference measured it), and its toString would shadow a merged node's rendering
// through the mixin linearization
sealed trait Arrow[-A, +B, -S] extends Kyo[B, S]:
    // computation nodes report from their own constructor; the guard keeps a node that mixes in the
    // arrow role from reporting twice
    if !this.isInstanceOf[Pending[?, ?]] then Debugger.get.onAlloc(this)

    def apply(v: A): B < S =
        Debugger.get.onUnfused(this)
        this(v, Arrow.id)
    def apply[C, S2](v: A < S2, cont: Arrow[B, C, S2]): C < (S & S2)

    def chain[C, S2](a: Arrow[B, C, S2]): Arrow[A, C, S & S2] =
        if a.isInstanceOf[Arrow.Id[?]] then
            this.asInstanceOf[Arrow[A, C, S & S2]]
        else
            Arrow.Chain(this, a)

    type X
    def head: Arrow[A, X, S]
    def tail: Arrow[X, B, S]
end Arrow

object Arrow:

    class Id[A] extends Transform[A, A, Any]:
        def frame                         = Frame.internal
        override def apply(v: A): A < Any = v
        def apply[C, S2](v: A < S2, cont: Arrow[A, C, S2]) =
            if cont.isInstanceOf[Arrow.Id[?]] then
                v.asInstanceOf[C < S2]
            else
                cont(v, Arrow.id)
        override def chain[C, S2](a: Arrow[A, C, S2]) = a
        override def toString                         = "Id"
    end Id

    private val identity = Id[Any]
    def id[A]: Id[A]     = identity.asInstanceOf[Id[A]]

    @nowarn("msg=anonymous")
    inline def apply[A](using _frame: Frame)[B, S](inline f: A => B < S): Arrow[A, B, S] =
        new Transform[A, B, S]:
            def frame                = _frame
            override def toString    = s"Transform(${site(frame)})"
            override def apply(v: A) = f(v)
            def apply[C, S2](v: A < S2, cont: Arrow[B, C, S2]) =
                v match
                    case v: Pending[A, S2] @unchecked =>
                        Effect.defer(v, this, cont)
                    case _ =>
                        val slot = Safepoint.get()
                        if !Safepoint.enter(slot) then
                            Effect.defer(v, this, cont)
                        else
                            val out = cont.head(apply(Nested.unnest(v)), cont.tail)
                            Safepoint.exit(slot)
                            out
                        end if

    /** A single-step arrow: its head is itself and nothing follows it.
      *
      * A trait rather than a class so a fusion site can mix it onto a node class: a merged node extends its node class for the value role
      * and this at its true input for the arrow role. It carries no toString so a node's own rendering survives the mixin; standalone sites
      * that want the arrow rendering supply the one-liner.
      */
    trait Transform[-A, B, -S] extends Arrow[A, B, S]:
        type X = B
        def head = this
        def tail = Arrow.id
    end Transform

    final class Chain[A, B, C, S] private[proto] (
        val a: Arrow[A, B, S],
        val b: Arrow[B, C, S]
    ) extends Arrow[A, C, S]:
        def frame = Frame.internal
        def apply[D, S2](v: A < S2, cont: Arrow[C, D, S2]) =
            a(v, b.chain(cont))

        type X = B
        def head              = a
        def tail              = b
        override def toString = s"Chain(${short(a)}, ${short(b)})"
    end Chain

end Arrow

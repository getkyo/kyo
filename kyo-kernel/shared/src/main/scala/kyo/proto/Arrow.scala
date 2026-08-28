package kyo.proto

import kyo.Frame
import kyo.proto.kernel.Effect
import kyo.proto.kernel.internal.Debugger
import kyo.proto.kernel.internal.Kyo
import kyo.proto.kernel.internal.Nested
import kyo.proto.kernel.internal.Safepoint
import kyo.proto.kernel.internal.short
import scala.annotation.nowarn

sealed trait Arrow[-A, +B, -S] extends (A => B < S):
    // computation nodes report from their own constructor; the guard keeps a node that mixes in the
    // arrow role from reporting twice
    if !this.isInstanceOf[Kyo[?, ?]] then Debugger.get.onAlloc(this)

    /** The source position this arrow was built at, [[Frame.internal]] for arrows the kernel mints itself. */
    def frame: Frame

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
            override def apply(v: A) = f(v)
            def apply[C, S2](v: A < S2, cont: Arrow[B, C, S2]) =
                v match
                    case v: Arrow[Any, A, S2] @unchecked =>
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

    abstract class Transform[-A, B, -S] extends Arrow[A, B, S]:
        type X = B
        def head              = this
        def tail              = Arrow.id
        override def toString = s"Transform(${frame.snippetShort})"
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

package kyo.proto

import kyo.Frame
import kyo.proto.kernel.internal.Debugger
import kyo.proto.kernel.internal.short
import scala.annotation.nowarn

sealed trait Arrow[-A, +B, -S] extends (A => B < S):
    Debugger.get.onAlloc(this)
    def apply(v: A): B < S =
        Debugger.get.onUnfused(this)
        this(v, Arrow.id)
    def apply[C, S2](v: A, cont: Arrow[B, C, S2]): C < (S & S2)

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
        override def apply(v: A): A < Any             = v
        def apply[C, S2](v: A, cont: Arrow[A, C, S2]) = cont(v)
        override def chain[C, S2](a: Arrow[A, C, S2]) = a
        override def toString                         = "Id"
    end Id

    private val identity = Id[Any]
    def id[A]: Id[A]     = identity.asInstanceOf[Id[A]]

    @nowarn("msg=anonymous")
    inline def apply[A](using frame: Frame)[B, S](inline f: A => B < S): Arrow[A, B, S] =
        new Transform[A, B, S]:
            def apply[C, S2](v: A, cont: Arrow[B, C, S2]) =
                f(v) match
                    case r: Arrow[Any, B, S] @unchecked =>
                        r.chain(cont)
                    case r =>
                        cont(r.asInstanceOf[B], Arrow.id)
            override def toString = s"Transform(${frame.snippetShort})"

    abstract class Transform[-A, B, -S] extends Arrow[A, B, S]:
        type X = B
        def head              = this
        def tail              = Arrow.id
        override def toString = "Transform"
    end Transform

    final class Chain[A, B, C, S] private[proto] (
        val a: Arrow[A, B, S],
        val b: Arrow[B, C, S]
    ) extends Arrow[A, C, S]:
        def apply[D, S2](v: A, cont: Arrow[C, D, S2]) =
            a(v, b.chain(cont))

        type X = B
        def head              = a
        def tail              = b
        override def toString = s"Chain(${short(a)}, ${short(b)})"
    end Chain

end Arrow

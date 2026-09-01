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

trait Kyo[+A, -S]:

    def frame: Frame
end Kyo

object Kyo:

    inline def lift[A, S](inline v: A): A < S = v
end Kyo

sealed trait Arrow[-A, +B, -S] extends Kyo[B, S]:

    def apply(v: A): B < S =
        Debugger.onUnfused(this)
        this.head(v, this.tail)

    def apply[C, S2](v: A < S2, cont: Arrow[B, C, S2]): C < (S & S2)

    def chain[C, S2](a: Arrow[B, C, S2]): Arrow[A, C, S & S2] =
        if this.isInstanceOf[Arrow.Id[?]] then
            a.asInstanceOf[Arrow[A, C, S & S2]]
        else if a.isInstanceOf[Arrow.Id[?]] then
            this.asInstanceOf[Arrow[A, C, S & S2]]
        else
            Arrow.Chain(this, a)

    type X
    def head: Arrow[A, X, S]
    def tail: Arrow[X, B, S]
end Arrow

object Arrow:

    class Id[A] private[Arrow] () extends Step[A, A, Any]:
        def frame                         = Frame.internal
        override def apply(v: A): A < Any = v
        def apply[C, S2](v: A < S2, cont: Arrow[A, C, S2]) =
            if cont.isInstanceOf[Arrow.Id[?]] then
                v.asInstanceOf[C < S2]
            else
                cont(v, Arrow.id)
        override def toString = "Id"
    end Id

    private val identity = Id[Any]()
    def id[A]: Id[A]     = identity.asInstanceOf[Id[A]]

    @nowarn("msg=anonymous")
    inline def apply[A](using _frame: Frame)[B, S](inline f: A => B < S): Arrow[A, B, S] =
        new Step[A, B, S]:
            def frame                = _frame
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

    private[kyo] trait Transform[-A, B, -S] extends Arrow[A, B, S]:
        type X = B
        def head = this
        def tail = Arrow.id
    end Transform

    abstract private[kyo] class Step[-A, B, -S] extends Transform[A, B, S]:
        Debugger.onAlloc(this)
        override def toString = s"Step(${site(frame)})"
    end Step

    // The step that opens a binding: it applies immediately on settled input, without the
    // budget gate every other arrow consults. A gate here could defer a settled value with
    // this step as its continuation, and a park of that node strands an obligation the
    // value was already owed but no region yet carries. Skipping the gate makes the
    // settle-to-open edge one slice by construction; the value's own gates all fire before
    // the value settles.
    abstract private[kyo] class Bind[-A, B, -S] extends Step[A, B, S]:
        override def apply(v: A): B < S

        final def apply[C, S2](v: A < S2, cont: Arrow[B, C, S2]): C < (S & S2) =
            v match
                case v: Pending[A, S2] @unchecked =>
                    Effect.defer(v, this, cont)
                case _ =>
                    cont.head(apply(Nested.unnest(v)), cont.tail)
    end Bind

    final private[kyo] class Chain[A, B, C, S] private[proto] (
        val a: Arrow[A, B, S],
        val b: Arrow[B, C, S]
    ) extends Arrow[A, C, S]:
        Debugger.onAlloc(this)
        def frame = Frame.internal
        def apply[D, S2](v: A < S2, cont: Arrow[C, D, S2]) =
            a(v, b.chain(cont))

        type X = B
        def head              = a
        def tail              = b
        override def toString = s"Chain(${short(a)}, ${short(b)})"
    end Chain

end Arrow

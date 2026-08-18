package kyo.proto

import kyo.Frame
import scala.annotation.nowarn

sealed trait Arrow[-A, +B, -S]:
    self =>

    def apply(v: A): B < S

    def apply[C, S2](v: A < S2, next: Arrow[B, C, S2]): C < (S & S2)

    def chain[C, S2](f: Arrow[B, C, S2]): Arrow[A, C, S & S2] = Arrow.Chain(this, f)
end Arrow

object Arrow:

    class Id[A] extends Transform[A, A, Any]:
        def frame                = Frame.internal
        def apply(v: A): A < Any = v
        override def apply[C, S2](v: A < S2, next: Arrow[A, C, S2]) =
            // next eq Id says C = A; the type system cannot carry that
            if next eq Id then v.asInstanceOf[C < S2] else next(v, Arrow.id)
        override def chain[C, S2](f: Arrow[A, C, S2]) = f
    end Id

    object Id extends Id[Any]

    def id[A]: Arrow.Id[A] = Id.asInstanceOf[Id[A]]

    trait Transform[-A, B, -S] extends Arrow[A, B, S]:
        def frame: Frame

    object Transform:
        @nowarn
        inline def apply[A, B, S](inline f: A => B < S)(using _frame: Frame): Transform[A, B, S] =
            new Transform[A, B, S]:
                def frame       = _frame
                def apply(v: A) = f(v)
                def apply[C, S2](v: A < S2, next: Arrow[B, C, S2]) =
                    v.lower(
                        pending = Kyo.Defer(_, this, next),
                        done = b =>
                            // the strict arm runs inside the safepoint budget; past it the settled
                            // step is deferred, so deep strict recursion continues on the
                            // evaluator's stack instead of the Java stack
                            val slot = Safepoint.get()
                            if !Safepoint.enter(slot) then Kyo.Defer(v, this, next)
                            else
                                val out = next(apply(b), Arrow.id)
                                Safepoint.exit(slot)
                                out
                            end if
                    )

    end Transform

    class Chain[-A, B, +C, -S](
        val a: Arrow[A, B, S],
        val b: Arrow[B, C, S]
    ) extends Arrow[A, C, S]:
        def apply(v: A) =
            b(a(v), Arrow.id)
        def apply[D, S2](v: A < S2, next: Arrow[C, D, S2]) =
            v.lower(
                pending = Kyo.Defer(_, this, next),
                done = a(_, b.chain(next))
            )

    end Chain

    object Chain:
        def apply[A, B, C, S](
            a: Arrow[A, B, S],
            b: Arrow[B, C, S]
        ): Arrow[A, C, S] =
            if a eq Id then b.asInstanceOf[Arrow[A, C, S]]
            else if b eq Id then a.asInstanceOf[Arrow[A, C, S]]
            else new Chain(a, b)
    end Chain

end Arrow

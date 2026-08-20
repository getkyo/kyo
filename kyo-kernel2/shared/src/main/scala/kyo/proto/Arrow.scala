package kyo.proto

import kyo.Frame
import scala.annotation.nowarn
import scala.annotation.static

sealed trait Arrow[-A, +B, -S] extends (A => B < S):
    self =>

    def frame: Frame

    def apply(v: A): B < S

    def apply[C, S2](v: A < S2, next: Arrow[B, C, S2]): C < (S & S2)

    final def chain[C, S2](f: Arrow[B, C, S2]): Arrow[A, C, S & S2] =
        if f eq Arrow.Id then this.asInstanceOf[Arrow[A, C, S]]
        else new Arrow.Chain(this, f)

    type X
    def head: Arrow[A, X, S]
    def tail: Arrow[X, B, S]

end Arrow

object Arrow:

    def id[A]: Arrow.Id[A] = Id.asInstanceOf[Id[A]]

    @nowarn
    inline def apply[A, B, S](inline f: A => B < S)(using _frame: Frame): Arrow[A, B, S] =
        new Transform[A, B, S]:
            def frame                = _frame
            override def apply(v: A) = f(v)
            def apply[C, S2](v: A < S2, next: Arrow[B, C, S2]) =
                v.lower(
                    pending = Effect.defer(_, this, next),
                    done = b =>
                        val slot = Safepoint.get()
                        if !Safepoint.enter(slot) then
                            Effect.defer(v, this, next)
                        else
                            val out = next.head(apply(b), next.tail)
                            Safepoint.exit(slot)
                            out
                        end if
                )

    @nowarn
    inline def recursive[A, B, S](inline f: (Arrow[A, B, S], A) => B < S)(using _frame: Frame): Arrow[A, B, S] =
        new Transform[A, B, S]:
            def frame                = _frame
            override def apply(v: A) = f(this, v)
            def apply[C, S2](v: A < S2, next: Arrow[B, C, S2]) =
                v.lower(
                    pending = Effect.defer(_, this, next),
                    done = b =>
                        val slot = Safepoint.get()
                        if !Safepoint.enter(slot) then
                            Effect.defer(v, this, next)
                        else
                            val out = next.head(apply(b), next.tail)
                            Safepoint.exit(slot)
                            out
                        end if
                )

    private[kyo] trait Transform[-A, B, -S] extends Arrow[A, B, S]:
        type X = B
        def head = this
        def tail = Arrow.id[B]

        def apply(v: A) = this(v, Arrow.id[B])
    end Transform

    // the evaluator flattens a chain onto its stack, so it sees the two halves
    private[proto] class Chain[-A, B, +C, -S](
        val a: Arrow[A, B, S],
        val b: Arrow[B, C, S]
    ) extends Arrow[A, C, S]:
        type X = B
        def head = a
        def tail = b

        def frame = Frame.internal
        def apply(v: A) =
            Effect.defer(v, a, b)
        def apply[D, S2](v: A < S2, next: Arrow[C, D, S2]) =
            Effect.defer(v, this, next)

    end Chain

    private[Arrow] class Id[A] extends Arrow[A, A, Any]:
        type X = A
        def head                 = this
        def tail                 = this
        def frame                = Frame.internal
        def apply(v: A): A < Any = v
        override def apply[C, S2](v: A < S2, next: Arrow[A, C, S2]) =
            if next eq Id then
                v.asInstanceOf[C < S2]
            else
                next(v, Arrow.id)
    end Id

    private[kyo] object Id extends Id[Any]

end Arrow

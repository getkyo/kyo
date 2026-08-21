package kyo

import kyo.Frame
import kyo.kernel.*
import kyo.kernel.internal.*
import scala.annotation.nowarn
import scala.annotation.static
import scala.annotation.tailrec

// deliberately not a Function1. Function1 is @specialized on both parameters, so every class that
// mixes it in emits the whole apply$mcXY$sp forwarder grid: 26 methods, measured at 19776 definitions
// across this module and not one genuine call site. Handlers take an Arrow directly instead, which
// also spares the eval an eta-expansion per suspension
sealed trait Arrow[-A, +B, -S]:
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
                v match
                    case kyo: Kyo[A, S2] @unchecked =>
                        Effect.defer(kyo, this, next)
                    case _ =>
                        val slot = Safepoint.get()
                        if !Safepoint.enter(slot) then
                            Effect.defer(v, this, next)
                        else
                            val out = next.head(apply(Nested.unnest(v)), next.tail)
                            Safepoint.exit(slot)
                            out
                        end if

    @nowarn
    inline def recursive[A, B, S](inline f: (Arrow[A, B, S], A) => B < S)(using _frame: Frame): Arrow[A, B, S] =
        new Transform[A, B, S]:
            def frame                = _frame
            override def apply(v: A) = f(this, v)
            def apply[C, S2](v: A < S2, next: Arrow[B, C, S2]) =
                v match
                    case kyo: Kyo[A, S2] @unchecked =>
                        Effect.defer(kyo, this, next)
                    case _ =>
                        val slot = Safepoint.get()
                        if !Safepoint.enter(slot) then
                            Effect.defer(v, this, next)
                        else
                            val out = next.head(apply(Nested.unnest(v)), next.tail)
                            Safepoint.exit(slot)
                            out
                        end if

    private[kyo] trait Transform[-A, B, -S] extends Arrow[A, B, S]:
        type X = B
        def head = this
        def tail = Arrow.id[B]

        def apply(v: A) = this(v, Arrow.id[B])

        override def toString: String = s"Arrow(${frame.position.show}, ${frame.snippetShort})"
    end Transform

    /** `Transform` as a class, for the sites that mint a standalone arrow.
      *
      * A mixin forwarder is emitted into every class that mixes a trait in, for each concrete trait member not
      * already implemented in a superclass. Since `Arrow` and `Transform` are both traits, an anonymous
      * `new Transform` is the first class in its chain and emits one for `head`, `tail`, `apply`, `toString` and
      * `chain`. Extending this instead inherits them, and an anonymous subclass carries only what it implements.
      *
      * `Transform` stays a trait because the sites that fuse an arrow into a `Kyo` node mix it onto that node's
      * class, and those cannot take a second superclass.
      */
    abstract private[kyo] class TransformBase[-A, B, -S] extends Transform[A, B, S]

    // the evaluator flattens a chain onto its stack, so it sees the two halves
    private[kyo] class Chain[-A, B, +C, -S](
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

        // a chain of any depth renders in bounded stack: the walk is a loop with a depth cap, so a
        // capture folded from a long eval stack stays printable in a debugger
        override def toString: String =
            val out = new StringBuilder
            @tailrec def loop(pending: List[Arrow[?, ?, ?] | String], fuel: Int): Unit =
                pending match
                    case (s: String) :: rest =>
                        out.append(s)
                        loop(rest, fuel)
                    case (link: Arrow[?, ?, ?]) :: rest =>
                        link match
                            case c: Chain[?, ?, ?, ?] if fuel > 0 =>
                                out.append("Arrow.Chain(")
                                loop(c.a :: ", " :: c.b :: ")" :: rest, fuel - 1)
                            case _: Chain[?, ?, ?, ?] =>
                                out.append("...")
                                loop(rest, fuel)
                            case other =>
                                out.append(other.toString)
                                loop(rest, fuel)
                    case _ => ()
            loop(this :: Nil, 32)
            out.result()
        end toString

    end Chain

    private[Arrow] class Id[A] extends Arrow[A, A, Any]:
        type X = A
        def head                      = this
        def tail                      = this
        def frame                     = Frame.internal
        def apply(v: A): A < Any      = v
        override def toString: String = "Arrow(identity)"
        override def apply[C, S2](v: A < S2, next: Arrow[A, C, S2]) =
            if next eq Id then
                v.asInstanceOf[C < S2]
            else
                next(v, Arrow.id)
    end Id

    private[kyo] object Id extends Id[Any]

end Arrow

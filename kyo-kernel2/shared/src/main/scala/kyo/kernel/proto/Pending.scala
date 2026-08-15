package kyo.kernel.proto

import kyo.Frame
import kyo.Span
import kyo.Tag
import scala.annotation.nowarn
import scala.annotation.static
import scala.language.implicitConversions

opaque type <[+A, -S] = A | Arrow[Any, A, S] | Nested[A]

private[kyo] trait Boxed

final private[proto] case class Nested[+A](value: A) extends Boxed

object Nested:

    /** The runtime arm the lift emission calls when a value of the type could be a computation. A monomorphic bridge rather than the
      * wrapping directly: the emission lands at every generic lift site, and the shortest call keeps those sites inside the JIT's inline
      * budget.
      */
    @static def nest[A, S](v: A): A < S =
        v match
            case v: Boxed => Nested(v).asInstanceOf[A < S]
            case v        => v.asInstanceOf[A < S]

    @static def unnest[A](v: Any): A =
        v match
            case n: Nested[?] => n.value.asInstanceOf[A]
            case _            => v.asInstanceOf[A]
end Nested

object `<`:
    implicit def lift[A, S](v: Arrow[Any, A, S]): A < S = v

    implicit inline def liftValue[A: CanLift](v: A): A < Any = CanLift.lift[A, Any](v)

    extension [A, S](self: A < S)

        @nowarn("msg=anonymous")
        inline def map[B, S2](inline f: A => B < S2)(using inline _frame: Frame): B < (S & S2) =
            def arrow =
                new Arrow.Transform[A, B, S & S2]:
                    def frame = _frame
                    def apply[C, S3](v: A < S3, next: Arrow[B, C, S3]): C < (S & S2 & S3) =
                        run(v, next)
            def run[C, S3](v: A < S3, next: Arrow[B, C, S3]): C < (S & S2 & S3) =
                v match
                    case v: Arrow[Any, A, S3] @unchecked =>
                        v.chain(arrow.chain(next))
                    case v =>
                        val res  = Nested.unnest[A](v)
                        val slot = Safepoint.get()
                        if !Safepoint.enter(slot) then
                            Arrow.Bind(v, arrow.chain(next))
                        else
                            val step = next.step
                            val out  = step.head(f(res), step.tail)
                            Safepoint.exit(slot)
                            out
                        end if
                end match
            end run
            run(self: A < S, Arrow[B])

    end extension

end `<`

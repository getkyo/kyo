package kyo.kernel.internal

import kyo.kernel.<
import scala.annotation.static

private[kyo] trait Boxed

// a plain class, not a case class: the box is a representation marker and must keep reference
// identity. Structural equals would make two boxes of the same value compare equal across levels,
// and the generated toString would leak the box into user-visible renders.
final private[kyo] class Nested[+A](val value: A) extends Boxed

object Nested:

    def apply[A](value: A): Nested[A] = new Nested(value)

    /** The runtime arm the lift emission calls when a value of the type could be a computation. A monomorphic bridge rather than the
      * wrapping directly: the emission lands at every generic lift site, and the shortest call keeps those sites inside the JIT's inline
      * budget.
      */
    @static def nest[A, S](v: A): A < S =
        v match
            case v: Boxed => Nested(v).asInstanceOf[A < S]
            case v        => v.asInstanceOf[A < S]

    @static def unnest[A](v: Any): A =
        (v match
            case n: Nested[?] => n.value
            case _            => v
        ).asInstanceOf[A]
end Nested

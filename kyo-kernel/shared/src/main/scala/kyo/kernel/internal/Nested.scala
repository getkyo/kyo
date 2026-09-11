package kyo.kernel.internal

import kyo.kernel.<
import kyo.kernel.Arrow
import scala.annotation.publicInBinary

/** The wrapper that lets a computation be carried as an ordinary value.
  *
  * `A < S` is a union whose second arm is the node family, so a computation stored where an `A` is expected would be indistinguishable from a
  * suspension the evaluator should unfold. Wrapping gives it an arm of its own, and the evaluator carries it opaquely.
  *
  * The contract is one layer: nest exactly once at the lift boundary, unnest exactly once at delivery. A value that is nested twice, or
  * delivered still wrapped, is a representation bug rather than a type error, which is why both operations live here and nowhere else.
  */
private[kyo] class Nested[+A](val value: A)

@publicInBinary private[kyo] object Nested:

    /** Removes one layer, if there is one. Applied where a payload is delivered to code that expects the value itself. */
    def unnest[A](v: Any): A =
        v match
            case v: Nested[A] @unchecked => v.value
            case v                       => v.asInstanceOf[A]

    /** Adds a layer, but only to what would otherwise be mistaken for a node.
      *
      * An ordinary value already inhabits the union's first arm and needs no wrapper, which is what keeps the common case free.
      */
    def nest[A, S](v: A): A < S =
        v match

            case v: (Pending[?, ?] | Nested[?]) => Nested(v).asInstanceOf[A < S]
            case _                              => v.asInstanceOf[A < S]

end Nested

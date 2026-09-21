package kyo.kernel.internal

import kyo.kernel.<
import kyo.kernel.Arrow
import scala.annotation.publicInBinary

/** The wrapper that lets a computation be carried as an ordinary value.
  *
  * `A < S` is a union whose second arm is the node family, so a computation stored where an `A` is expected would be indistinguishable from
  * a suspension the evaluator should unfold. Wrapping gives it its own arm, carried opaquely.
  *
  * Layers stack, one per level of nesting: `A < S < S2` carries two if both levels need one. The contract is one layer per crossing (a lift
  * adds at most one, a delivery removes at most one), so the wrappers and the type stay in step. What breaks it is a lift firing on a value
  * that is already union-represented, adding a layer nothing will take off.
  */
private[kyo] class Nested[+A](val value: A)

@publicInBinary private[kyo] object Nested:

    /** Removes one layer, if there is one. */
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

package kyo.internal

import kyo.*
import scala.annotation.nowarn
import scala.annotation.publicInBinary
import scala.annotation.tailrec
import scala.compiletime.*
import scala.deriving.Mirror

/** Ordering derivation for Schema types.
  *
  * Derives [[Ordering]] instances for case class types by comparing their fields in declaration order. Used by the `order` given on Schema.
  */
@publicInBinary private[kyo] object SchemaOrdering:

    /** Derives an Ordering[A] for a case class by comparing fields in declaration order.
      *
      * Recursively derives Ordering for nested case class fields via summonOrdering.
      */
    @nowarn("msg=anonymous")
    inline def deriveOrdering[A](using mir: Mirror.ProductOf[A]): Ordering[A] =
        val orderings = summonOrderings[mir.MirroredElemTypes]
        new Ordering[A]:
            def compare(x: A, y: A): Int =
                // Mirror.ProductOf guarantees A is a case class (extends Product)
                val px = x.asInstanceOf[Product]
                val py = y.asInstanceOf[Product]
                val n  = orderings.length
                @tailrec def loop(i: Int): Int =
                    if i < n then
                        val c = orderings(i).compare(px.productElement(i), py.productElement(i))
                        if c != 0 then c else loop(i + 1)
                    else 0
                loop(0)
            end compare
        end new
    end deriveOrdering

    /** Summons Ordering instances for each type in a tuple, falling back to recursive derivation for case classes. */
    private inline def summonOrderings[T <: Tuple]: Array[Ordering[Any]] =
        inline erasedValue[T] match
            case _: EmptyTuple => Array.empty[Ordering[Any]]
            case _: (h *: t)   => summonOrdering[h] +: summonOrderings[t]

    /** Summons an Ordering[H], falling back to Mirror-based derivation for case classes. */
    private inline def summonOrdering[H]: Ordering[Any] =
        summonFrom {
            case o: Ordering[H @unchecked] => o.asInstanceOf[Ordering[Any]]
            case m: Mirror.ProductOf[H @unchecked] =>
                deriveOrdering[H](using m).asInstanceOf[Ordering[Any]]
        }

end SchemaOrdering

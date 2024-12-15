package kyo.kernel

import scala.annotation.implicitNotFound
import scala.util.NotGiven

/** WeakFlat is a "soft" constraint that indicates a type should not contain nested effect computations (A < S). This constraint helps
  * prevent accidental nesting of effects that would require flattening, but cannot be strictly enforced in all generic contexts.
  *
  * @tparam A
  *   The type to check for nested effects
  */
@implicitNotFound("""
Type '${A}' may contain a nested effect computation.

This usually means you have a value of type 'X < S' where you need a plain value.
To fix this, you can:

1. Call .flatten to combine the nested effects:
     (x: (Int < S1) < S2).flatten  // Result: Int < (S1 & S2)

2. Split the computation into multiple statements:
     val x: Int < S1 = computeFirst()
     val y: Int < S2 = useResult(x)
""")
opaque type WeakFlat[A] = Null
trait WeakFlatLowPriority:
    inline given WeakFlat[Nothing] = WeakFlat.unsafe.bypass

object WeakFlat extends WeakFlatLowPriority:
    inline given WeakFlat[Unit] = unsafe.bypass

    inline given [A](using inline ng: NotGiven[A <:< (Any < Nothing)]): WeakFlat[A] = unsafe.bypass

    object unsafe:
        /** Unconditionally provides WeakFlat evidence for any type.
          *
          * Warning: This bypasses normal type safety checks and should only be used when you can guarantee through other means that no
          * problematic effect nesting will occur.
          */
        inline given bypass[A]: WeakFlat[A] = null
    end unsafe
end WeakFlat

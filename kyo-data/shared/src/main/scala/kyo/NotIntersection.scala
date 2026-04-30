package kyo

import kyo.internal.NotIntersectionMacro

/** Compile-time evidence that a type is not an intersection type.
  *
  * Used to prevent operations that are semantically invalid on intersection types, such as retrieving a single value from a TypeMap by an
  * intersection key like `Int & String`.
  *
  * @tparam A
  *   The type to verify is not an intersection
  */
sealed abstract class NotIntersection[A]

object NotIntersection:
    private[kyo] val singleton: NotIntersection[Any] = new NotIntersection[Any] {}
    inline given [A]: NotIntersection[A]             = ${ NotIntersectionMacro.derive[A] }
end NotIntersection

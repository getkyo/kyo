package kyo

/** One-directional type conversion with implicit conversion support via `scala.Conversion`.
  *
  * Wraps a total function `A => B`. Because Convert extends `scala.Conversion[A, B]`, providing a `given Convert[A, B]` enables implicit
  * use of A where B is expected at call sites.
  *
  * @tparam A
  *   The source type
  * @tparam B
  *   The target type
  *
  * @see
  *   [[Schema.convert]] for transform-aware conversion from A to B
  */
final class Convert[A, B](f: A => B) extends Conversion[A, B]:
    def apply(a: A): B = f(a)
    def andThen[C](other: Convert[B, C]): Convert[A, C] =
        new Convert(a => other(f(a)))
end Convert

object Convert:
    /** Auto-derives Convert[A, B] when A's fields are a superset of B's (or B has defaults). */
    transparent inline def apply[A, B]: Convert[A, B] =
        ${ internal.SchemaConvertMacro.autoImpl[A, B] }

    /** Creates a Convert from an explicit function. */
    def apply[A, B](f: A => B): Convert[A, B] = new Convert(f)
end Convert

package kyo.internal

import scala.compiletime.erasedValue

trait Inliner[A]:
    inline def apply[T]: A

object Inliner:
    inline def inlineAllLoop[A, T <: Tuple](f: Inliner[A]): List[A] =
        inline erasedValue[T] match
            case _: EmptyTuple => Nil
            case _: (h1 *: h2 *: h3 *: h4 *: h5 *: h6 *: h7 *: h8 *: h9 *: h10 *: h11 *: h12 *: h13 *: h14 *: h15 *: h16 *:
                    ts) =>
                f[h1] :: f[h2] :: f[h3] :: f[h4] :: f[h5] :: f[h6] :: f[h7] :: f[h8]
                    :: f[h9] :: f[h10] :: f[h11] :: f[h12] :: f[h13] :: f[h14] :: f[h15] :: f[h16]
                    :: inlineAllLoop[A, ts](f)
            case _: (h1 *: h2 *: h3 *: h4 *: ts) =>
                f[h1] :: f[h2] :: f[h3] :: f[h4] :: inlineAllLoop[A, ts](f)
            case _: (h *: ts) => f[h] :: inlineAllLoop[A, ts](f)
end Inliner

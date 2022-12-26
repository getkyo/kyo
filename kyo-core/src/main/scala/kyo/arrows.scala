package kyo

import scala.runtime.AbstractFunction1

import core._

object arrows {

  final class Arrows private[arrows] () extends Effect[[T] =>> Unit] {
    inline def apply[T, S, U, S2](inline f: T > (S | Arrows) => U > (S2 | Arrows))
        : T > S => U > S2 =
      val a =
        new Kyo[[T] =>> Unit, Arrows, T > S, T, S | Arrows]((), Arrows) {
          def stack = Nil
          def apply(v: T > S, s: Safepoint[Arrows]) = v
        }
      f(a).asInstanceOf[T > S => U > S2]

    inline def recursive[T, S, U, S2](f: (
        T > (S | Arrows),
        T > (S | Arrows) => U > (S2 | Arrows)
    ) => U > (S2 | Arrows)): T > S => U > S2 =
      new AbstractFunction1[T > S, U > S2] {
        val a =
          new Kyo[[T] =>> Unit, Arrows, T > S, T, S | Arrows]((), Arrows) {
            def stack = Nil
            def apply(v: T > S, s: Safepoint[Arrows]) = v
          }
        val g = f(a, this.asInstanceOf[T > (S | Arrows) => U > (S2 | Arrows)])
          .asInstanceOf[T > S => U > S2]
        def apply(v: T > S) = g(v)
      }
  }
  val Arrows = new Arrows
}

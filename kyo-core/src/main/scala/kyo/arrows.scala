package kyo

import scala.runtime.AbstractFunction1

import core._
import frames._

object arrows {

  final class Arrows private[arrows] () extends Effect[[T] =>> Unit] {
    inline def apply[T, S <: Effect[_], U, S2](
        f: T > (S | Arrows) => U > (S2 | Arrows)
    )(using inline fr: Frame["Arrows"]): T > S => U > S2 =
      val a =
        new Kyo[[T] =>> Unit, Arrows, T > S, T, S | Arrows] {
          def frame  = fr
          def value  = ()
          def effect = arrows.Arrows
          def apply(v: T > S, s: Safepoint[Arrows]) =
            v
        }
      f(a).asInstanceOf[Kyo[[T] =>> Unit, Arrows, T > S, U, S2]](_, Safepoint.noop)

    inline def recursive[T, S, U, S2](f: (
        T > (S | Arrows),
        T > (S | Arrows) => U > (S2 | Arrows)
    ) => U > (S2 | Arrows))(using inline fr: Frame["Arrows.recursive"]): T > S => U > S2 =
      new AbstractFunction1[T > S, U > S2] {
        val a =
          new Kyo[[T] =>> Unit, Arrows, T > S, T, S | Arrows] {
            def frame  = fr
            def value  = ()
            def effect = arrows.Arrows
            def apply(v: T > S, s: Safepoint[Arrows]) =
              v
          }
        val g = f(a, this.asInstanceOf[T > (S | Arrows) => U > (S2 | Arrows)])
          .asInstanceOf[Kyo[[T] =>> Unit, Arrows, T > S, U, S2]](_, Safepoint.noop)
        def apply(v: T > S) = g(v)
      }
  }
  val Arrows = new Arrows
}

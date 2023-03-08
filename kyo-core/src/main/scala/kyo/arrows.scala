package kyo

import scala.runtime.AbstractFunction1

import core._
import frames._
import locals._
import ios._

object arrows {

  final class Arrows private[arrows] () extends Effect[[T] =>> Unit] {
    /*inline(2)*/
    def apply[T, S <: Effect[_], U, S2](
        f: T > (S | Arrows) => U > (S2 | Arrows)
    )(using /*inline(2)*/ fr: Frame["Arrows"]): T > S => U > (S2 | IOs) =
      new AbstractFunction1[T > S, U > (S2 | IOs)] {
        val a =
          new Kyo[[T] =>> Unit, Arrows, T > S, T, S | Arrows] {
            def frame  = fr
            def value  = ()
            def effect = arrows.Arrows
            def apply(v: T > S, s: Safepoint[Arrows], l: Locals.State) =
              v
          }
        def apply(v: T > S) =
          Locals.save { st =>
            f(a).asInstanceOf[Kyo[[T] =>> Unit, Arrows, T > S, U, S2]](
                v,
                Safepoint.noop,
                st
            )
          }
      }

    /*inline(2)*/
    def recursive[T, S, U, S2](f: (
        T > (S | Arrows),
        T > (S | Arrows) => U > (S2 | Arrows)
    ) => U > (S2 | Arrows))(using /*inline(2)*/ fr: Frame["Arrows.recursive"])
        : T > S => U > (S2 | IOs) =
      new AbstractFunction1[T > S, U > (S2 | IOs)] {
        val a =
          new Kyo[[T] =>> Unit, Arrows, T > S, T, S | Arrows] {
            def frame  = fr
            def value  = ()
            def effect = arrows.Arrows
            def apply(v: T > S, s: Safepoint[Arrows], l: Locals.State) =
              v
          }
        val g = f(a, this.asInstanceOf[T > (S | Arrows) => U > (S2 | Arrows)])
          .asInstanceOf[Kyo[[T] =>> Unit, Arrows, T > S, U, S2]]
        def apply(v: T > S) =
          Locals.save { st =>
            g(v, Safepoint.noop, st)
          }
      }
  }
  val Arrows = new Arrows
}

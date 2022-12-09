package kyo

import core._
import defers._
import tries._
import scala.util.Try

object ios {

  opaque type IO[T] <: (Defer[T] | Try[T]) = Defer[T] | Try[T]

  opaque type IOs <: Effect[IO] = Defers | Tries

  object IOs {
    inline def apply[T, S <: Effect[_]](inline v: => T > (S | IOs)): T > (S | IOs) =
      Defers(Try(v) >> Tries)
    inline def run[T, S <: Effect[_]](v: T > (S | IOs)): T > (S | Tries) =
      val a: Try[T] > (S | Defers) = v < Tries
      val b: Defer[Try[T]] > S     = a < Defers
      val c: Try[T] > S            = b(_.run())
      val d: T > (S | Tries)       = c > Tries
      d
  }
}

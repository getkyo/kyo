package kyo

import core._
import defers._
import tries._
import scala.util.Try

object ios {

  opaque type IO[T] <: (Defer[T] | Try[T]) = Defer[T] | Try[T]

  opaque type IOs <: Effect[IO] = Defers | Tries

  object IOs {
    inline def apply[T, S](inline v: => T > (S | IOs)): T > (S | IOs) =
      Defers(Try(v) >> Tries)
    def run[T, S](v: T > (S | IOs)): T > (S | Tries) =
      val v1: T > (S | Tries | Defers) = v
      val v2 = v1 < Tries
      val v3 = v2 < Defers
      // (v < Tries < Defers)(_.run()) > Tries
      ???
      // val a: Try[T] > (S | Defers) = v < Tries
      // val b: Defer[Try[T]] > S     = a < Defers
      // val c: Try[T] > S            = b(_.run())
      // val d: T > (S | Tries)       = c > Tries
      // d
  }
}

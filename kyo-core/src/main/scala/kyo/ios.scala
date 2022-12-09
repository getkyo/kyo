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
      Defers(Tries(v))
    inline def tryRun[T, S](v: T > (S | IOs)): T > (S | Tries) =
      Tries((v < Defers)(_.run()))
    inline def run[T, S](v: T > (S | IOs)): T > S =
      val a: T > (Tries | S) = Tries((v < Defers)(_.run()))
      (a < Tries)(_.get)
  }
}

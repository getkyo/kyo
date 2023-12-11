package kyo

import kyo.aborts._

import scala.util._

object tries {

  type Tries >: Tries.Effects <: Tries.Effects

  object Tries {

    type Effects = Aborts[Throwable]

    private val aborts = Aborts[Throwable]

    def run[T, S](v: => T < (Tries with S))(implicit f: Flat[T < (Tries with S)]): Try[T] < S =
      aborts.run[T, S](v).map(_.toTry)

    def handle[T, S](v: => T < (Tries with S))(f: PartialFunction[Throwable, T < S])(
        implicit flat: Flat[T < (Tries with S)]
    ): T < S =
      run[T, S](v).map {
        case Failure(e) if (f.isDefinedAt(e)) =>
          f(e)
        case r =>
          r.get
      }

    def fail[T](ex: Throwable): T < Tries =
      aborts.fail(ex)

    def fail[T](msg: String): T < Tries =
      fail(new Exception(msg))

    def catching[T, S](v: => T < S): T < (Tries with S) =
      aborts.catching(v)

    def get[T, S](v: Try[T] < S): T < (Tries with S) =
      v.map {
        case Success(v) =>
          v
        case Failure(ex) =>
          fail(ex)
      }
  }
}

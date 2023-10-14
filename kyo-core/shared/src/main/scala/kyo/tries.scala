package kyo

import kyo.aborts._

import scala.util._

object tries {

  type Tries = Aborts[Throwable]

  object Tries {

    private val aborts = Aborts[Throwable]

    def run[T, S](v: => T > (Tries with S)): Try[T] > S =
      aborts.run[T, S](v).map(_.toTry)

    def handle[T, S](f: Throwable => T > S)(v: => T > S): T > S =
      run(v).map {
        case Success(v) => v
        case Failure(e) => f(e)
      }

    def fail[T](ex: Throwable): T > Tries =
      aborts.fail(ex)

    def fail[T](msg: String): T > Tries =
      fail(new Exception(msg))

    def apply[T, S](v: => T > S): T > (Tries with S) =
      aborts.catching(v)

    def get[T, S](v: Try[T] > S): T > (Tries with S) =
      v.map {
        case Success(v) =>
          v
        case Failure(ex) =>
          fail(ex)
      }
  }
}

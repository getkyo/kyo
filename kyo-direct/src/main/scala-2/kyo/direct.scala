package kyo

import kyo._
import language.experimental.macros

object direct {

  def defer[T](body: T): Any = macro Macro.defer[T]

  def await[T, S](m: T < S): T = throw new Exception("Unlift must be used within a `await` body.")

  object internal {

    def branch[T, S1, S2, S3](
        cond: Boolean < S1,
        ifTrue: => T < S2,
        ifFalse: => T < S3
    ): T < (S1 with S2 with S3) =
      cond.map {
        case true  => ifTrue
        case false => ifFalse
      }

    def cont[T, U, S1, S2](v: T < S1)(f: T => U < S2): U < (S1 with S2) =
      v.map(f)

    def loop[T, S](cond: => Boolean < S, v: => Any < S): Unit < S =
      cond.map {
        case true  => v.map(_ => loop(cond, v.unit))
        case false => ()
      }
  }

}

package kyo

import kyo._
import language.experimental.macros

object direct {

  def defer[T](body: T): Any = macro Macro.defer[T]

  def await[T, S](m: T > S): T = throw new Exception("Unlift must be used within a `await` body.")

  object internal {

    def whileLoop[T, S](cond: => Boolean > S, v: => Unit > S): Unit > S =
      cond.map {
        case true  => v.andThen(whileLoop(cond, v))
        case false => ()
      }

    def lift[T, S](t: T > S): T > S = t
  }

}

package kyo

import kyo.core._
import kyo.options._

object choices {

  private case object Drop
  private case class Choose[T](l: List[T])

  opaque type Choice[T] = Drop.type | Choose[T]

  extension [T](v: Choice[T]) {
    def run: List[T] =
      v match
        case Choose(l) => l
        case Drop      => Nil
  }

  final class Choices extends Effect[Choice] {

    def run[T, S](v: T > (S | Choices)): List[T] > S =
      (v < Choices)(_.run)

    def apply[T](v: List[T]): T > Choices =
      v match {
        case Nil => drop
        case l   => Choose(l) > Choices
      }

    def apply[T](v: T*): T > Choices =
      apply(v.toList)

    def ensure[S](v: Boolean > S): Unit > (S | Choices) =
      v {
        case true =>
          ()
        case false =>
          drop
      }

    def drop[T]: T > Choices =
      (Drop: Choice[T]) > Choices
  }
  val Choices = new Choices

  given ShallowHandler[Choice, Choices] with
    def pure[T](v: T) = Choose(List(v))
    def apply[T, U, S](v: Choice[T], f: T => U > (S | Choices)): U > (S | Choices) =
      v match
        case Choose(l) =>
          def loop(l: List[T], acc: List[List[U]]): U > (S | Choices) =
            l match
              case Nil =>
                Choices(acc.reverse.flatten)
              case t :: ts =>
                (f(t) < Choices) {
                  case Choose(l) => loop(ts, l :: acc)
                  case Drop      => loop(ts, acc)
                }
          loop(l, Nil)
        case Drop =>
          Choices.drop

}

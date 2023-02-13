package kyo

import kyo.core._
import kyo.options._

object lists {

  final class Lists private[lists] () extends Effect[List] {

    def run[T, S](v: T > (S | Lists)): List[T] > S =
      v < Lists

    def apply[T](v: List[T]): T > Lists =
      v > Lists

    def apply[T](v: T*): T > Lists =
      apply(v.toList)

    def filter[S](v: Boolean > S): Unit > (S | Lists) =
      v {
        case true =>
          ()
        case false =>
          drop
      }

    def drop[T]: T > Lists =
      List.empty[T] > Lists
  }
  val Lists = new Lists

  given Handler[List, Lists] with
    def pure[T](v: T) = List(v)
    def apply[T, U, S](v: List[T], f: T => U > (S | Lists)): U > (S | Lists) =
      def loop(l: List[T], acc: List[List[U]]): U > (S | Lists) =
        l match
          case Nil =>
            Lists(acc.reverse.flatten)
          case t :: ts =>
            (f(t) < Lists)(l => loop(ts, l :: acc))
      loop(v, Nil)

}

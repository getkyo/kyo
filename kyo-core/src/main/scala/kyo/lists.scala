package kyo

import kyo.core._
import kyo.options._
import scala.collection.mutable.ListBuffer

object lists {

  final class Lists private[lists] () extends Effect[List] {

    /*inline(1)*/
    def run[T, S](v: T > (S | Lists)): List[T] > S =
      v < Lists

    def foreach[T, S](v: List[T] > S): T > (S | Lists) =
      v {
        case head :: Nil => head
        case _           => v > Lists
      }

    def traverse[T, U, S, S2](v: List[T] > S)(f: T => U > S2): List[U] > (S | S2) =
      v { v =>
        collect(v(_.map(f)))
      }

    def foreach[T, S](v: (T > S)*): T > (S | Lists) =
      foreach(collect(v.toList))

    /*inline(1)*/
    def filter[S](v: Boolean > S): Unit > (S | Lists) =
      v {
        case true =>
          ()
        case false =>
          drop
      }

    def drop[T]: T > Lists =
      List.empty[T] > Lists

    /*inline(1)*/
    def collect[T, S](v: List[T > S]): List[T] > S =
      val buff = ListBuffer[T]()
      def loop(v: List[T > S]): List[T] > S =
        v match {
          case Nil => buff.toList
          case h :: t =>
            h(t1 => {
              buff.addOne(t1)
              loop(t)
            })
        }
      loop(v)
  }
  val Lists = new Lists

  given Handler[List, Lists] with
    def pure[T](v: T) = List(v)
    def apply[T, U, S](v: List[T], f: T => U > (S | Lists)): U > (S | Lists) =
      def loop(l: List[T], acc: List[List[U]]): U > (S | Lists) =
        l match
          case Nil =>
            Lists.foreach(acc.reverse.flatten)
          case t :: ts =>
            (f(t) < Lists)(l => loop(ts, l :: acc))
      loop(v, Nil)

}

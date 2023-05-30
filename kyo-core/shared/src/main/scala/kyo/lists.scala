package kyo

import kyo._
import kyo.core._
import kyo.options._
import scala.collection.mutable.ListBuffer

object lists {

  final class Lists private[lists] () extends Effect[List, Lists] {

    /*inline(1)*/
    def run[T, S](v: T > (Lists with S)): List[T] > S =
      implicit val handler: Handler[List, Lists] =
        new Handler[List, Lists] {
          def pure[T](v: T) = List(v)
          def apply[T, U, S](v: List[T], f: T => U > (Lists with S)): U > (Lists with S) =
            def loop(l: List[T], acc: List[List[U]]): U > (Lists with S) =
              l match
                case Nil =>
                  Lists.foreach(acc.reverse.flatten)
                case t :: ts =>
                  Lists.run(f(t)).map(l => loop(ts, l :: acc))
            loop(v, Nil)
        }
      handle(v)

    def foreach[T, S](v: List[T] > S): T > (Lists with S) =
      v.map {
        case head :: Nil => head
        case _           => suspend(v)
      }

    def traverse[T, U, S, S2](v: List[T] > S)(f: T => U > S2): List[U] > (S with S2) =
      v.map { v =>
        collect(v.map(f))
      }

    def foreach[T, S](v: (T > S)*): T > (Lists with S) =
      foreach(collect(v.toList))

    /*inline(1)*/
    def filter[S](v: Boolean > S): Unit > (Lists with S) =
      v.map {
        case true =>
          ()
        case false =>
          drop
      }

    def drop[T]: T > Lists =
      suspend(List.empty[T])

    /*inline(1)*/
    def collect[T, S](v: List[T > S]): List[T] > S =
      val buff = ListBuffer[T]()
      def loop(v: List[T > S]): List[T] > S =
        v match {
          case Nil => buff.toList
          case h :: t =>
            h.map(t1 => {
              buff.addOne(t1)
              loop(t)
            })
        }
      loop(v)
  }
  val Lists = new Lists
}

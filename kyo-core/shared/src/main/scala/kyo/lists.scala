package kyo

import kyo._
import kyo.core._
import kyo.options._
import scala.collection.mutable.ListBuffer

object lists {

  final class Lists private[lists] () extends Effect[List, Lists] {

    private implicit val handler: Handler[List, Lists, Any] =
      new Handler[List, Lists, Any] {
        def pure[T](v: T) = List(v)
        def apply[T, U, S](v: List[T], f: T => U > (Lists with S)): U > (Lists with S) = {
          def loop(l: List[T], acc: List[List[U]]): U > (Lists with S) =
            l match {
              case Nil =>
                Lists.foreach(acc.reverse.flatten: List[U])
              case t :: ts =>
                import Flat.unsafe._
                Lists.run[U, S](f(t)).map(l => loop(ts, l :: acc))
            }
          loop(v, Nil)
        }
      }

    def run[T, S](v: T > (Lists with S))(implicit f: Flat[T > (Lists with S)]): List[T] > S =
      handle[T, S, Any](v)

    def repeat(n: Int): Unit > Lists =
      foreach(List.fill(n)(()))

    def foreach[T, S](v: List[T] > S): T > (Lists with S) =
      v.map {
        case head :: Nil => head
        case _           => suspend(v)
      }

    def dropIf[S](v: Boolean > S): Unit > (Lists with S) =
      v.map {
        case true =>
          ()
        case false =>
          drop
      }

    val drop: Nothing > Lists =
      suspend(List.empty[Nothing])

    def traverse[T, U, S, S2](v: List[T] > S)(f: T => U > S2): List[U] > (S with S2) =
      v.map { v =>
        collect(v.map(f))
      }

    def traverseUnit[T, U, S, S2](v: List[T] > S)(f: T => Unit > S2): Unit > (S with S2) =
      v.map { v =>
        def loop(l: List[T]): Unit > (S with S2) =
          l match {
            case Nil => ()
            case h :: t =>
              f(h).andThen(loop(t))
          }
        loop(v)
      }

    def collect[T, S](v: List[T > S]): List[T] > S = {
      val b = List.newBuilder[T]
      def loop(v: List[T > S]): List[T] > S =
        v match {
          case Nil =>
            b.result()
          case h :: t =>
            h.map { t1 =>
              b += t1
              loop(t)
            }
        }
      loop(v)
    }

    def fill[T, S](n: Int)(v: => T > S): List[T] > S = {
      def loop(n: Int, acc: List[T]): List[T] > S =
        n match {
          case 0 => acc.reverse
          case n => v.map(v => loop(n - 1, v :: acc))
        }
      loop(n, Nil)
    }
  }
  val Lists = new Lists
}

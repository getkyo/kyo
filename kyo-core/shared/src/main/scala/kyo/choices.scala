package kyo

import kyo._
import kyo.core._
import kyo.options._
import scala.collection.mutable.ListBuffer

object choices {

  final class Choices private[choices] () extends Effect[List, Choices] {

    private implicit val handler: Handler[List, Choices] =
      new Handler[List, Choices] {
        def pure[T](v: T) = List(v)
        def apply[T, U, S](v: List[T], f: T => U > (Choices with S)): U > (Choices with S) = {
          def loop(l: List[T], acc: List[List[U]]): U > (Choices with S) =
            l match {
              case Nil =>
                Choices.foreach(acc.reverse.flatten: List[U])
              case t :: ts =>
                Choices.run[U, S](f(t)).map(l => loop(ts, l :: acc))
            }
          loop(v, Nil)
        }
      }

    def run[T, S](v: T > (Choices with S)): List[T] > S =
      handle[T, S](v)

    def repeat(n: Int): Unit > Choices =
      foreach(List.fill(n)(()))

    def foreach[T, S](v: List[T] > S): T > (Choices with S) =
      v.map {
        case head :: Nil => head
        case _           => suspend(v)
      }

    def dropIf[S](v: Boolean > S): Unit > (Choices with S) =
      v.map {
        case true =>
          ()
        case false =>
          drop
      }

    val drop: Nothing > Choices =
      suspend(List.empty[Nothing])

    def traverse[T, U, S, S2](v: List[T] > S)(f: T => U > S2): List[U] > (S with S2) =
      v.map { v =>
        collect(v.map(f))
      }

    def foreach[T, U, S, S2](v: List[T] > S)(f: T => Unit > S2): Unit > (S with S2) =
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
  }
  val Choices = new Choices
}

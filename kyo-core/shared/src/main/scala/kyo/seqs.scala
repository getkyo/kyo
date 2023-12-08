package kyo

import kyo._
import kyo.core._
import kyo.options._
import scala.collection.mutable.ListBuffer

object seqs {

  final class Seqs private[seqs] () extends Effect[Seq, Seqs] {

    def run[T, S](v: T > (Seqs with S))(implicit f: Flat[T > (Seqs with S)]): Seq[T] > S =
      handle[T, S, Any](v)

    def repeat(n: Int): Unit > Seqs =
      get(Seq.fill(n)(()))

    def get[T, S](v: Seq[T] > S): T > (Seqs with S) =
      v.map {
        case Seq(head) => head
        case _         => suspend(v)
      }

    def filter[S](v: Boolean > S): Unit > (Seqs with S) =
      v.map {
        case true =>
          ()
        case false =>
          drop
      }

    val drop: Nothing > Seqs =
      suspend(Seq.empty[Nothing])

    def traverse[T, U, S, S2](v: Seq[T] > S)(f: T => U > S2): Seq[U] > (S with S2) =
      v.map { v =>
        collect(v.map(f))
      }

    def traverseUnit[T, U, S, S2](v: Seq[T] > S)(f: T => Unit > S2): Unit > (S with S2) =
      v.map { v =>
        def loop(l: Seq[T]): Unit > (S with S2) =
          l match {
            case Seq() => ()
            case h +: t =>
              f(h).andThen(loop(t))
          }
        loop(v)
      }

    def collect[T, S](v: Seq[T > S]): Seq[T] > S = {
      val b = Seq.newBuilder[T]
      def loop(v: Seq[T > S]): Seq[T] > S =
        v match {
          case Seq() =>
            b.result()
          case h +: t =>
            h.map { t1 =>
              b += t1
              loop(t)
            }
        }
      loop(v)
    }

    def fill[T, S](n: Int)(v: => T > S): Seq[T] > S = {
      def loop(n: Int, acc: Seq[T]): Seq[T] > S =
        n match {
          case 0 => acc.reverse
          case n => v.map(v => loop(n - 1, v +: acc))
        }
      loop(n, Seq())
    }

    private implicit val handler: Handler[Seq, Seqs, Any] =
      new Handler[Seq, Seqs, Any] {
        def pure[T](v: T) = Seq(v)
        def apply[T, U, S](v: Seq[T], f: T => U > (Seqs with S)): U > (Seqs with S) = {
          def loop(l: Seq[T], acc: Seq[Seq[U]]): U > (Seqs with S) =
            l match {
              case Seq() =>
                Seqs.get(acc.reverse.flatten: Seq[U])
              case t +: ts =>
                import Flat.unsafe._
                Seqs.run[U, S](f(t)).map(l => loop(ts, l +: acc))
            }
          loop(v, Seq.empty)
        }
      }
  }
  val Seqs = new Seqs
}

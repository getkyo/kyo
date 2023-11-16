package kyo.concurrent

import kyo._
import kyo.core._
import kyo.core.internal._
import kyo.concurrent.channels._
import kyo.concurrent.fibers._
import kyo.ios._
import kyo.lists._
import kyo.App.Effects

object Test extends App {

  import streams._

  def run(args: List[String]) = {
    Streams.run {
      Streams.source(List(1, 2)).map(_ + 1).map(i => Streams.source(List(i, i + 1)))
    }.map(_.foreach(println(_)))
  }

}

object streams {

  import Stream._

  sealed trait Stream[+T] {

    def get: T > Streams = Streams.get(this)

    def sink[B >: T](ch: Channel[B]): Unit > (Fibers with IOs) = {
      def loop(s: Stream[T]): Unit > (Fibers with IOs) =
        s match {
          case Done => ()
          case More(v, tail) =>
            ch.put(v).andThen {
              Streams.run(tail).map(loop(_))
            }
        }
      loop(this)
    }

    def drain: Unit > (Fibers with IOs) =
      this match {
        case Done => ()
        case More(_, tail) =>
          Streams.run(tail).map(_.drain)
      }

    def foreach(f: T => Unit > (Fibers with IOs)): Unit > (Fibers with IOs) =
      this match {
        case Done => ()
        case More(v, tail) =>
          v.map(f).andThen {
            Streams.run(tail).map(_.foreach(f))
          }
      }

    def head: Option[T] > (Fibers with IOs) =
      this match {
        case Done       => None
        case More(v, _) => v.map(Some(_))
      }

    def last: Option[T] > (Fibers with IOs) = {
      def loop(s: Stream[T], prev: Option[T]): Option[T] > (Fibers with IOs) =
        s match {
          case Done => prev
          case More(v, tail) =>
            Streams.run(tail).map { s =>
              v.map(v => loop(s, Some(v)))
            }
        }
      loop(this, None)
    }

    def count: Int > (Fibers with IOs) = {
      def loop(s: Stream[T], acc: Int): Int > (Fibers with IOs) =
        s match {
          case Done => acc
          case More(_, tail) =>
            Streams.run(tail)
              .map(loop(_, acc + 1))
        }
      loop(this, 0)
    }

    def take(n: Int): List[T] > (Fibers with IOs) = {
      def loop(s: Stream[T], acc: List[T]): List[T] > (Fibers with IOs) =
        s match {
          case Done => acc.reverse
          case More(v, tail) =>
            v.map { v =>
              Streams.run(tail)
                .map(loop(_, v :: acc))
            }
        }
      loop(this, Nil)
    }
  }

  object Stream {

    case object Done
        extends Stream[Nothing]

    case class More[T](v: T > (Fibers with IOs), tail: T > (Fibers with IOs with Streams))
        extends Stream[T]
  }

  final class Streams private[streams] () extends Effect[Stream, Streams] {

    def run[T, S](v: T > (Streams with S)): Stream[T] > (Fibers with IOs with S) =
      handle[T, S, Fibers with IOs](v)

    def source[T](v: T > (Fibers with IOs), tail: T > (Fibers with IOs with Streams)): T > Streams =
      More(v, tail).get

    def source[T](f: () => T > Streams): T > Streams =
      f().map(v => source(v, source(f)))

    def source[T](ch: Channel[T]): T > Streams =
      source(ch.take, source(ch))

    def source[T, S](i: Iterable[T > Streams]): T > Streams = {
      val it = i.iterator
      def loop(): T > Streams =
        if (!it.hasNext) {
          done
        } else {
          it.next().map { v =>
            More[T](v, loop()).get
          }
        }
      loop()
    }

    val done: Nothing > Streams = suspend(Done)

    private[streams] def get[T](s: Stream[T]): T > Streams =
      suspend(s)

    private implicit val handler: Handler[Stream, Streams, Fibers with IOs] =
      new Handler[Stream, Streams, Fibers with IOs] {
        def pure[T](v: T) = More(v, done)
        def apply[T, U, S](s: Stream[T], f: T => U > (Streams with S)) = {
          def loop(s: Stream[T], acc: List[Stream[U]]): U > (Streams with Fibers with IOs with S) =
            s match {
              case Done =>
                source(acc.map(_.get))
              case More(v, tail) =>
                run(v.map(f)).map { r =>
                  run(tail).map { s =>
                    loop(s, r :: acc)
                  }
                }
            }
          loop(s, Nil)
        }
      }
  }
  val Streams = new Streams
}

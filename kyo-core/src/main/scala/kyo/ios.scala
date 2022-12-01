package kyo

import java.util.concurrent.ThreadLocalRandom
import scala.annotation.tailrec
import scala.concurrent.duration.Duration
import scala.util.NotGiven

import core._

object ios {

  private trait Defer[T] {
    def run(): IO[T]
  }

  opaque type IO[T] = T | Defer[T]

  extension [T](t: IO[T]) {

    inline def run(): T =
      @tailrec def ioRunLoop(t: IO[T]): T =
        t match {
          case t: Defer[T] @unchecked =>
            ioRunLoop(t.run())
          case _ =>
            t.asInstanceOf[T]
        }
      ioRunLoop(t)

    inline def runFor(duration: Duration): Either[IO[T], T] =
      val end = System.currentTimeMillis() + duration.toMillis
      @tailrec def ioRunForLoop(t: IO[T]): Either[IO[T], T] =
        t match {
          case t: Defer[T] @unchecked =>
            if (System.currentTimeMillis() <= end) {
              ioRunForLoop(t.run())
            } else {
              Left(t)
            }
          case _ =>
            Right(t.asInstanceOf[T])
        }
      ioRunForLoop(t)
  }

  final class IOs private[ios] () extends Effect[IO] {
    inline def apply[T, S](
        inline f: => T > (S | IOs)
    ): T > (S | IOs) =
      new Kyo[IO, IOs, Unit, T, (S | IOs)]((), IOs) {
        def apply(v: Unit) = f
      }
  }
  val IOs: IOs = new IOs

  inline given ShallowHandler[IO, IOs] =
    new ShallowHandler[IO, IOs] {
      def pure[T](v: T) =
        v
      def apply[T, U, S](
          m: IO[T],
          f: T => U > (S | IOs)
      ): U > (S | IOs) =
        m match {
          case _: Defer[T] @unchecked =>
            val io =
              new Defer[U > (S | IOs)] {
                def run() =
                  f(m.run())
              }
            io >> IOs
          case _ =>
            f(m.asInstanceOf[T])
        }
    }

  inline given DeepHandler[IO, IOs] =
    new DeepHandler[IO, IOs] {
      def pure[T](v: T) = v
      def flatMap[T, U](m: IO[T], f: T => IO[U]): IO[U] =
        new Defer[U] {
          def run() = f(m.run())
        }
    }
}

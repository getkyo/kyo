package kyo

import java.util.concurrent.ThreadLocalRandom
import scala.annotation.tailrec
import scala.concurrent.duration.Duration
import scala.util.NotGiven

import core._
import tries._

object ios {

  private trait Defer[T] {
    def run(): IO[T]
  }

  opaque type IO[T] = T | Defer[T]

  object IO {
    inline def apply[T](inline v: => T): IO[T] =
      new Defer[T] {
        def run(): IO[T] = v
      }
  }

  extension [T](t: IO[T]) {

    inline def run(): T =
      @tailrec def deferRunLoop(t: IO[T]): T =
        t match {
          case t: Defer[T] @unchecked =>
            deferRunLoop(t.run())
          case _ =>
            t.asInstanceOf[T]
        }
      deferRunLoop(t)

    inline def run(preempt: () => Boolean): Either[IO[T], T] =
      @tailrec def deferRunWhileLoop(t: IO[T]): Either[IO[T], T] =
        t match {
          case t: Defer[T] @unchecked =>
            if (!preempt()) {
              deferRunWhileLoop(t.run())
            } else {
              Left(t)
            }
          case _ =>
            Right(t.asInstanceOf[T])
        }
      deferRunWhileLoop(t)

    inline def runFor(duration: Duration): Either[IO[T], T] =
      val end = System.currentTimeMillis() + duration.toMillis
      run(() => System.currentTimeMillis() > end)
  }

  final class IOs private[ios] () extends Effect[IO] {
    inline def apply[T, S](
        inline f: => T > (S | IOs)
    ): T > (S | IOs) =
      new Kyo[IO, IOs, Unit, T, (S | IOs)]((), IOs) {
        def stack = Nil
        def apply(v: Unit, s: Safepoint[IOs]) = f
      }
    inline def run[T, S](f: T > (S | IOs)): T > S =
      (f < IOs)((_: IO[T]).run())
    inline def runTry[T, S](f: T > (S | IOs)): T > (S | Tries) =
      (f < IOs)((io: IO[T]) => Tries(io.run()))
  }
  val IOs: IOs = new IOs

  inline given ShallowHandler[IO, IOs] =
    new ShallowHandler[IO, IOs] {
      def pure[T](v: T) =
        v
      override def handle[T](ex: Throwable): T > IOs =
        new Defer[T] {
          def run() =
            throw ex
        } > IOs
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

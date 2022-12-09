package kyo

import java.util.concurrent.ThreadLocalRandom
import scala.annotation.tailrec
import scala.concurrent.duration.Duration
import scala.util.NotGiven

import core._

object defers {

  private trait Thunk[T] {
    def run(): Defer[T]
  }

  opaque type Defer[T] = T | Thunk[T]

  object Defer {
    inline def apply[T](inline v: => T): Defer[T] =
      new Thunk[T] {
        def run(): Defer[T] = v
      }
  }

  extension [T](t: Defer[T]) {

    inline def run(): T =
      @tailrec def deferRunLoop(t: Defer[T]): T =
        t match {
          case t: Thunk[T] @unchecked =>
            deferRunLoop(t.run())
          case _ =>
            t.asInstanceOf[T]
        }
      deferRunLoop(t)

    inline def runFor(duration: Duration): Either[Defer[T], T] =
      val end = System.currentTimeMillis() + duration.toMillis
      @tailrec def deferRunForLoop(t: Defer[T]): Either[Defer[T], T] =
        t match {
          case t: Thunk[T] @unchecked =>
            if (System.currentTimeMillis() <= end) {
              deferRunForLoop(t.run())
            } else {
              Left(t)
            }
          case _ =>
            Right(t.asInstanceOf[T])
        }
      deferRunForLoop(t)
  }

  final class Defers private[defers] () extends Effect[Defer] {
    inline def apply[T, S](
        inline f: => T > (S | Defers)
    ): T > (S | Defers) =
      new Kyo[Defer, Defers, Unit, T, (S | Defers)]((), Defers) {
        def apply(v: Unit) = f
      }
  }
  val Defers: Defers = new Defers

  inline given ShallowHandler[Defer, Defers] =
    new ShallowHandler[Defer, Defers] {
      def pure[T](v: T) =
        v
      override def handle[T, S](ex: Throwable): T > (S | Defers) =
        new Thunk[T] {
          def run() =
            throw ex
        } > Defers
      def apply[T, U, S](
          m: Defer[T],
          f: T => U > (S | Defers)
      ): U > (S | Defers) =
        m match {
          case _: Thunk[T] @unchecked =>
            val io =
              new Thunk[U > (S | Defers)] {
                def run() =
                  f(m.run())
              }
            io >> Defers
          case _ =>
            f(m.asInstanceOf[T])
        }
    }

  inline given DeepHandler[Defer, Defers] =
    new DeepHandler[Defer, Defers] {
      def pure[T](v: T) = v
      def flatMap[T, U](m: Defer[T], f: T => Defer[U]): Defer[U] =
        new Thunk[U] {
          def run() = f(m.run())
        }
    }
}

package kyo

import java.util.concurrent.ThreadLocalRandom
import scala.annotation.tailrec
import scala.concurrent.duration.Duration
import scala.util.NotGiven

import core._
import tries._
import options._
import frames._
import scala.runtime.AbstractFunction0
import scala.util.control.NonFatal
import scala.Conversion
import java.io.Closeable
import scala.util.Try

object ios {

  trait Preempt extends Safepoint[IOs] {
    def apply[T, S](v: => T > (S | IOs)) =
      IOs(v)
  }
  object Preempt {
    val never: Preempt =
      new Preempt {
        def apply() = false
      }
  }

  opaque type IO[+T] = T

  private[kyo] abstract class KyoIO[T, S]
      extends Kyo[IO, IOs, Unit, T, (S | IOs)] {
    def value  = ()
    def effect = ios.IOs
  }

  private[kyo] abstract class Finalizer[T, S]
      extends KyoIO[T, S] {
    def run(): Unit
  }

  final class IOs private[ios] () extends Effect[IO] {

    inline def value[T](v: T): T > IOs = v

    inline def attempt[T, S](v: => T > S): Try[T] > S =
      v < Tries

    inline def ensure[T, S](f: => Unit > IOs)(v: => T > S)(using
        inline fr: Frame["IOs.ensure"]
    ): T > (S | IOs) =
      new Finalizer[T, S] {
        def frame = fr
        def apply(u: Unit, s: Safepoint[IOs]) =
          v
        def run() =
          try IOs.run(f)
          catch {
            case ex if NonFatal(ex) =>
              ex.printStackTrace() // TODO log
          }
      }

    inline def apply[T, S](
        inline f: => T > (S | IOs)
    )(using inline fr: Frame["IOs"]): T > (S | IOs) =
      new KyoIO[T, (S | IOs)] {
        def frame = fr
        def apply(v: Unit, s: Safepoint[IOs]) =
          f
      }

    inline def run[T](v: T > IOs): T =
      val safepoint = Safepoint.noop[IOs]
      @tailrec def runLoop(v: T > IOs): T =
        v match {
          case kyo: Kyo[IO, IOs, Unit, T, IOs] @unchecked =>
            runLoop(kyo((), safepoint))
          case _ =>
            v.asInstanceOf[T]
        }

      runLoop(v)

    inline def lazyRun[T, S](v: T > (S | IOs)): T > S =
      given ShallowHandler[IO, IOs] with {
        def pure[T](v: T) = v
        def apply[T, U, S](m: IO[T], f: T => U > (S | IOs)): U > (S | IOs) =
          f(m)
      }
      v < IOs

    inline def isDone[T](v: T > IOs): Boolean =
      !v.isInstanceOf[Kyo[_, _, _, _, _]]

    inline def eval[T](p: Preempt)(v: T > IOs): T > IOs =
      @tailrec def finalize(l: List[Finalizer[T, IOs]]): Unit =
        if (l ne Nil) {
          l.head.run()
          finalize(l.tail)
        }
      @tailrec def evalLoop(v: T > IOs, l: List[Finalizer[T, IOs]]): T > IOs =
        if (p()) {
          if (l ne Nil) {
            IOs.ensure(finalize(l))(v)
          } else {
            v
          }
        } else {
          v match {
            case kyo: Finalizer[T, IOs] @unchecked =>
              evalLoop(kyo((), p), kyo :: l)
            case kyo: Kyo[IO, IOs, Unit, T, IOs] @unchecked =>
              evalLoop(kyo((), p), l)
            case _ =>
              finalize(l)
              v.asInstanceOf[T > IOs]
          }
        }

      evalLoop(v, Nil)
  }
  val IOs: IOs = new IOs
}

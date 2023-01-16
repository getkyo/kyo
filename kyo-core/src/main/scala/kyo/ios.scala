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

object iosTest extends App {
  import ios._

  val io =
    for {
      _ <- IOs(println(1))
      _ <- IOs.ensure(println(1.1))
      _ <- IOs(println(3))
    } yield ()

  IOs.run(io)
}

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

    def finalizers(l: List[Unit > IOs]): List[Unit > IOs] = l
  }

  final class IOs private[ios] () extends Effect[IO] {

    inline def value[T](v: T): T > IOs = v

    inline def use[T <: Closeable](resource: => T)(using
        inline fr: Frame["use"]
    ): T > IOs =
      new KyoIO[T, Nothing] {
        lazy val r = resource
        def frame  = fr
        def apply(v: Unit, s: Safepoint[IOs]) =
          r
        override def finalizers(l: List[Unit > IOs]) =
          IOs(r.close()) :: l
      }

    inline def ensure(inline f: => Unit > IOs)(using
        inline fr: Frame["ensure"]
    ): Unit > IOs =
      new KyoIO[Unit, Nothing] {
        def frame = fr
        def apply(v: Unit, s: Safepoint[IOs]) =
          v
        override def finalizers(l: List[Unit > IOs]) =
          IOs(f) :: l
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
      @tailrec def runLoop(v: T > IOs, finalizers: List[Unit > IOs]): T =
        v match {
          case kyo: KyoIO[T, Nothing] @unchecked =>
            runLoop(kyo((), safepoint), kyo.finalizers(finalizers))
          case kyo: Kyo[IO, IOs, Unit, T, IOs] @unchecked =>
            runLoop(kyo((), safepoint), finalizers)
          case _ =>
            if (finalizers.nonEmpty) {
              ???
            }
            v.asInstanceOf[T]
        }
      
      runLoop(v, Nil)

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
      @tailrec def evalLoop(v: T > IOs, finalizers: List[Unit > IOs]): T > IOs =
        if (p()) {
          if (finalizers.nonEmpty) {
            ???
          }
          v
        } else {
          v match {
            case kyo: KyoIO[T, Nothing] @unchecked =>
              evalLoop(kyo((), p), kyo.finalizers(finalizers))
            case kyo: Kyo[IO, IOs, Unit, T, IOs] @unchecked =>
              evalLoop(kyo((), p), finalizers)
            case _ =>
              if (finalizers.nonEmpty) {
                ???
              }
              v.asInstanceOf[T > IOs]
          }
        }

      evalLoop(v, Nil)
  }
  val IOs: IOs = new IOs
}
